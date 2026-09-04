package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.ManagedFileEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ManagedFileMaterializer {

    private final ManagedFileService managedFileService;
    private final ManagedFilePolicyRegistry policyRegistry;
    private final StudioPlatformProperties properties;
    private final ConcurrentMap<Long, Object> locks = new ConcurrentHashMap<Long, Object>();
    private final ConcurrentMap<Path, AtomicInteger> activePaths = new ConcurrentHashMap<Path, AtomicInteger>();

    public ManagedFileMaterializer(ManagedFileService managedFileService,
                                   ManagedFilePolicyRegistry policyRegistry,
                                   StudioPlatformProperties properties) {
        this.managedFileService = managedFileService;
        this.policyRegistry = policyRegistry;
        this.properties = properties;
    }

    public MaterializedFile materialize(Long fileId, String tenantId, Long projectId,
                                        String expectedPolicy, String consumerType,
                                        String consumerId, String workerInstanceId) {
        ManagedFileEntity file = managedFileService.requireReadyFile(fileId, tenantId, projectId, expectedPolicy);
        ManagedFileService.LeaseRecord lease = managedFileService.acquireLease(fileId, tenantId, projectId,
                consumerType, consumerId, workerInstanceId);
        try {
            Path target = cachePath(file);
            Object jvmLock = locks.computeIfAbsent(fileId, ignored -> new Object());
            synchronized (jvmLock) {
                materializeLocked(file, target);
            }
            activePaths.computeIfAbsent(target, ignored -> new AtomicInteger()).incrementAndGet();
            return new MaterializedFile(file, target, lease);
        } catch (RuntimeException e) {
            managedFileService.releaseLease(lease.getToken());
            throw e;
        }
    }

    public void release(MaterializedFile materializedFile) {
        if (materializedFile == null || !materializedFile.markReleased()) return;
        try {
            managedFileService.releaseLease(materializedFile.getLease().getToken());
        } finally {
            activePaths.computeIfPresent(materializedFile.getPath(), (path, counter) ->
                    counter.decrementAndGet() <= 0 ? null : counter);
        }
    }

    private void materializeLocked(ManagedFileEntity file, Path target) {
        try {
            Path root = cacheRoot();
            ensureInside(root, target);
            Files.createDirectories(target.getParent());
            applyDirectoryPermissions(target.getParent(), policyRegistry.require(file.getPolicyCode()).isSensitive());
            Path lockPath = target.getParent().resolve(".materialize.lock");
            ensureInside(root, lockPath);
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && cacheMatches(target, file)) {
                    Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(Instant.now()));
                    return;
                }
                Files.deleteIfExists(target);
                managedFileService.materialize(file, target);
                applyFilePermissions(target, policyRegistry.require(file.getPolicyCode()).isSensitive());
                if (!cacheMatches(target, file)) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException("Managed file cache verification failed");
                }
            }
        } catch (StudioException e) {
            throw e;
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to prepare managed file cache", e);
        }
    }

    private boolean cacheMatches(Path path, ManagedFileEntity file) throws Exception {
        if (file.getPlaintextSize() == null || Files.size(path) != file.getPlaintextSize().longValue()) {
            return false;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return file.getSha256() != null && file.getSha256().equalsIgnoreCase(hex(digest.digest()));
    }

    private Path cachePath(ManagedFileEntity file) {
        String safeName = policyRegistry.requireSafeFileName(file.getOriginalFileName());
        Path root = cacheRoot();
        Path target = root.resolve(tenantHash(file.getTenantId()))
                .resolve(String.valueOf(file.getProjectId()))
                .resolve(String.valueOf(file.getId()))
                .resolve(file.getSha256())
                .resolve(safeName)
                .toAbsolutePath().normalize();
        ensureInside(root, target);
        return target;
    }

    private Path cacheRoot() {
        StudioPlatformProperties.ManagedFileProperties managed = managedProperties();
        String configured = StringUtils.hasText(managed.getCacheDir())
                ? managed.getCacheDir().trim() : "./runtime/managed-files";
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private void ensureInside(Path root, Path target) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Managed file cache path escapes cache root");
        }
    }

    private void applyDirectoryPermissions(Path directory, boolean sensitive) throws Exception {
        if (supportsPosix(directory)) {
            Files.setPosixFilePermissions(directory, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            return;
        }
        if (applyOwnerOnlyAcl(directory)) {
            return;
        }
        if (sensitive) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Sensitive managed file cache directory cannot be restricted to the runtime owner");
        }
    }

    private void applyFilePermissions(Path file, boolean sensitive) throws Exception {
        if (supportsPosix(file)) {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            return;
        }
        if (applyOwnerOnlyAcl(file)) {
            return;
        }
        if (sensitive) {
            Files.deleteIfExists(file);
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Sensitive managed file cannot be restricted to the runtime owner");
        }
    }

    private boolean supportsPosix(Path path) {
        return Files.getFileAttributeView(path,
                java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
    }

    private boolean applyOwnerOnlyAcl(Path path) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view == null) return false;
            AclEntry entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(view.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            view.setAcl(Collections.singletonList(entry));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${studio.managed-file.gc-interval-millis:300000}")
    public void cleanupCache() {
        if (!managedProperties().isEnabled()) return;
        Path root = cacheRoot();
        if (!Files.isDirectory(root)) return;
        Path kerberosRoot = root.resolve("kerberos").toAbsolutePath().normalize();
        long maxBytes = positive(managedProperties().getCacheMaxBytes(), 1024L * 1024L * 1024L);
        Instant idleCutoff = Instant.now().minusSeconds(positive(managedProperties().getCacheIdleHours(), 24) * 3600L);
        try {
            java.util.List<Path> files = new java.util.ArrayList<Path>();
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !path.toAbsolutePath().normalize().startsWith(kerberosRoot))
                        .filter(path -> !".materialize.lock".equals(path.getFileName().toString()))
                        .forEach(files::add);
            }
            files.sort(java.util.Comparator.comparingLong(this::lastModifiedMillis));
            long total = 0L;
            for (Path file : files) total += sizeQuietly(file);
            for (Path file : files) {
                if (isActive(file)) continue;
                boolean idle = Files.getLastModifiedTime(file).toInstant().isBefore(idleCutoff);
                if (!idle && total <= maxBytes) continue;
                long size = sizeQuietly(file);
                Files.deleteIfExists(file);
                total = Math.max(0L, total - size);
            }
        } catch (Exception ignored) {
            // Cache cleanup is best effort and must not disrupt active tasks.
        }
    }

    private boolean isActive(Path path) {
        AtomicInteger counter = activePaths.get(path.toAbsolutePath().normalize());
        return counter != null && counter.get() > 0;
    }

    private long lastModifiedMillis(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception e) { return Long.MAX_VALUE; }
    }

    private long sizeQuietly(Path path) {
        try { return Files.size(path); }
        catch (Exception e) { return 0L; }
    }

    private long positive(Long value, long fallback) {
        return value == null || value.longValue() <= 0L ? fallback : value.longValue();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value.intValue() <= 0 ? fallback : value.intValue();
    }

    private StudioPlatformProperties.ManagedFileProperties managedProperties() {
        return properties.getManagedFile() == null
                ? new StudioPlatformProperties.ManagedFileProperties() : properties.getManagedFile();
    }

    private String tenantHash(String tenantId) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(tenantId).getBytes(StandardCharsets.UTF_8));
            return hex(java.util.Arrays.copyOf(bytes, 8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash tenant id", e);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    public static final class MaterializedFile {
        private final ManagedFileEntity file;
        private final Path path;
        private final ManagedFileService.LeaseRecord lease;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private MaterializedFile(ManagedFileEntity file, Path path, ManagedFileService.LeaseRecord lease) {
            this.file = file;
            this.path = path;
            this.lease = lease;
        }

        public ManagedFileEntity getFile() { return file; }
        public Path getPath() { return path; }
        public ManagedFileService.LeaseRecord getLease() { return lease; }
        private boolean markReleased() { return released.compareAndSet(false, true); }
    }
}
