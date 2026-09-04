package com.jdragon.studio.flink.connector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RemoteManagedFileCache {
    private static final Duration DEFAULT_IDLE_TTL = Duration.ofHours(24);
    private static final long DEFAULT_MAX_BYTES = 1024L * 1024L * 1024L;
    private static final Object MONITOR = new Object();
    private static final Map<Path, Integer> ACTIVE = new HashMap<Path, Integer>();

    private RemoteManagedFileCache() {
    }

    static Path cacheRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "studio-flink-managed-files")
                .toAbsolutePath().normalize();
    }

    static CacheLease acquire(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        synchronized (MONITOR) {
            if (!Files.isRegularFile(normalized)) {
                throw new IOException("managed file cache entry is unavailable: " + normalized);
            }
            ACTIVE.put(normalized, ACTIVE.getOrDefault(normalized, 0) + 1);
            touch(normalized);
            try {
                cleanupLocked(cacheRoot(), Instant.now(), DEFAULT_IDLE_TTL, DEFAULT_MAX_BYTES,
                        new HashSet<Path>(ACTIVE.keySet()));
            } catch (IOException ignored) {
                // Cache maintenance is best effort and must not break a verified file load.
            }
        }
        return new CacheLease(normalized);
    }

    static void cleanup(Path root, Instant now, Duration idleTtl, long maxBytes) throws IOException {
        synchronized (MONITOR) {
            cleanupLocked(root.toAbsolutePath().normalize(), now, idleTtl, maxBytes,
                    new HashSet<Path>(ACTIVE.keySet()));
        }
    }

    private static void release(Path path) {
        synchronized (MONITOR) {
            Integer count = ACTIVE.get(path);
            if (count == null) return;
            if (count <= 1) ACTIVE.remove(path);
            else ACTIVE.put(path, count - 1);
            touch(path);
        }
    }

    private static void cleanupLocked(Path root, Instant now, Duration idleTtl, long maxBytes,
                                      Set<Path> protectedPaths) throws IOException {
        if (!Files.isDirectory(root)) return;
        Path kerberosRoot = root.resolve("kerberos").toAbsolutePath().normalize();
        List<CacheEntry> entries = new ArrayList<CacheEntry>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !path.startsWith(kerberosRoot))
                    .filter(path -> "managed-file.bin".equals(path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            entries.add(new CacheEntry(path, Files.size(path), Files.getLastModifiedTime(path)));
                        } catch (IOException ignored) {
                            // A concurrent process may have removed an inactive cache entry.
                        }
                    });
        }

        Instant cutoff = now.minus(idleTtl == null ? DEFAULT_IDLE_TTL : idleTtl);
        for (CacheEntry entry : new ArrayList<CacheEntry>(entries)) {
            if (!protectedPaths.contains(entry.path)
                    && !entry.lastModified.toInstant().isAfter(cutoff)) {
                deleteEntry(root, entry.path);
                entries.remove(entry);
            }
        }

        long boundedMaxBytes = maxBytes < 0 ? DEFAULT_MAX_BYTES : maxBytes;
        long totalBytes = entries.stream().mapToLong(entry -> entry.size).sum();
        entries.sort(Comparator.comparing(entry -> entry.lastModified));
        for (CacheEntry entry : entries) {
            if (totalBytes <= boundedMaxBytes) break;
            if (protectedPaths.contains(entry.path)) continue;
            if (deleteEntry(root, entry.path)) {
                totalBytes -= entry.size;
            }
        }
    }

    private static boolean deleteEntry(Path root, Path path) {
        try {
            boolean deleted = Files.deleteIfExists(path);
            deleteEmptyParents(root, path.getParent());
            return deleted;
        } catch (IOException ignored) {
            // Locked files remain available and will be retried on a later cache access.
            return false;
        }
    }

    private static void deleteEmptyParents(Path root, Path start) throws IOException {
        Path current = start;
        while (current != null && current.startsWith(root) && !current.equals(root)) {
            try (java.util.stream.Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) return;
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private static void touch(Path path) {
        try {
            if (Files.exists(path)) Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // Last access time is advisory; content verification still protects cache reuse.
        }
    }

    static final class CacheLease implements AutoCloseable {
        private final Path path;
        private boolean closed;

        private CacheLease(Path path) {
            this.path = path;
        }

        Path getPath() {
            return path;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            release(path);
        }
    }

    private static final class CacheEntry {
        private final Path path;
        private final long size;
        private final FileTime lastModified;

        private CacheEntry(Path path, long size, FileTime lastModified) {
            this.path = path;
            this.size = size;
            this.lastModified = lastModified;
        }
    }
}
