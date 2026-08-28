package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunLogChunkEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.nio.file.Path;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedInputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Service
public class RunLogStorageService {

    private static final int DEFAULT_PAGE_BYTES = 64 * 1024;
    private static final int MAX_PAGE_BYTES = 512 * 1024;
    public static final String STORAGE_LOCAL = "LOCAL";
    public static final String STORAGE_OBJECT = "OBJECT_STORAGE";

    private final StudioPlatformProperties properties;
    private final RunLogObjectStore objectStore;
    private final CloudObjectStorageService cloudObjectStorageService;
    private RunLogChunkMapper runLogChunkMapper;

    public RunLogStorageService(StudioPlatformProperties properties,
                                RunLogObjectStore objectStore,
                                CloudObjectStorageService cloudObjectStorageService) {
        this(properties, objectStore, cloudObjectStorageService, null);
    }

    @Autowired
    public RunLogStorageService(StudioPlatformProperties properties,
                                RunLogObjectStore objectStore,
                                CloudObjectStorageService cloudObjectStorageService,
                                RunLogChunkMapper runLogChunkMapper) {
        this.properties = properties;
        this.objectStore = objectStore;
        this.cloudObjectStorageService = cloudObjectStorageService;
        this.runLogChunkMapper = runLogChunkMapper;
    }

    public boolean objectStorageEnabled() {
        return STORAGE_OBJECT.equalsIgnoreCase(storageType());
    }

    public boolean objectStorageAvailable() {
        return !objectStorageEnabled() || objectStore.available();
    }

    public String storageType() {
        String value = properties.getRunLog() == null ? null : properties.getRunLog().getStorageType();
        return StringUtils.hasText(value) ? value.trim().toUpperCase() : STORAGE_LOCAL;
    }

    public String resolveBucket() {
        return cloudObjectStorageService.resolveBucket();
    }

    public boolean objectStorageBucketConfigured() {
        return cloudObjectStorageService.bucketConfigured();
    }

    public String buildObjectKey(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        StudioPlatformProperties.RunLogProperties runLog = properties.getRunLog();
        String prefix = runLog == null ? null : runLog.getObjectPrefix();
        if (!StringUtils.hasText(prefix) && runLog != null && runLog.getObjectStorage() != null) {
            prefix = runLog.getObjectStorage().getPrefix();
        }
        if (!StringUtils.hasText(prefix)) {
            prefix = "studio/run-logs";
        }
        String normalizedPrefix = StringUtils.hasText(prefix) ? trimSlashes(prefix.trim()) : "";
        String normalizedPath = trimSlashes(relativePath.replace('\\', '/'));
        return normalizedPrefix.isEmpty() ? normalizedPath : normalizedPrefix + "/" + normalizedPath;
    }

    public void upload(String bucket, String objectKey, byte[] bytes, String contentType) {
        objectStore.put(bucket, objectKey, bytes, contentType);
    }

    public void uploadFile(String bucket, String objectKey, Path source, String contentType) {
        objectStore.putFile(bucket, objectKey, source, contentType);
    }

    public void downloadTo(String bucket, String objectKey, Path target) {
        objectStore.downloadTo(bucket, objectKey, target);
    }

    public void downloadTo(String bucket, String objectKey, OutputStream output) {
        objectStore.downloadTo(bucket, objectKey, output);
    }

    /** Streams one archived log object to the response without a heap-sized byte array. */
    public void streamObjectLog(RunRecordEntity entity, OutputStream output) {
        if (entity == null || !StringUtils.hasText(entity.getLogObjectBucket())
                || !StringUtils.hasText(entity.getLogObjectKey()) || output == null) {
            throw new IllegalArgumentException("Run log object metadata is missing");
        }
        downloadTo(entity.getLogObjectBucket(), entity.getLogObjectKey(), output);
    }

    /** Streams an object-storage log as a one-entry ZIP archive. */
    public void streamObjectLogArchive(RunRecordEntity entity, OutputStream output) {
        if (entity == null || output == null || !StringUtils.hasText(entity.getLogObjectBucket())
                || !StringUtils.hasText(entity.getLogObjectKey())) {
            throw new IllegalArgumentException("Run log object metadata is missing");
        }
        try {
            ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output));
            java.util.Set<String> names = new java.util.HashSet<String>();
            int entries = 0;
            if (runLogChunkMapper != null && entity.getId() != null) {
                java.util.List<RunLogChunkEntity> chunks = runLogChunkMapper.selectList(
                        new LambdaQueryWrapper<RunLogChunkEntity>()
                                .eq(RunLogChunkEntity::getTenantId, entity.getTenantId())
                                .eq(RunLogChunkEntity::getProjectId, entity.getProjectId())
                                .eq(RunLogChunkEntity::getRunRecordId, entity.getId())
                                .orderByAsc(RunLogChunkEntity::getSequenceNo)
                                .orderByAsc(RunLogChunkEntity::getId));
                for (RunLogChunkEntity chunk : chunks) {
                    if (chunk == null || !StringUtils.hasText(chunk.getObjectBucket())
                            || !StringUtils.hasText(chunk.getObjectKey())) {
                        continue;
                    }
                    String name = uniqueArchiveName(chunk.getObjectKey(), chunk.getSequenceNo(), names);
                    zip.putNextEntry(new ZipEntry(name));
                    downloadTo(chunk.getObjectBucket(), chunk.getObjectKey(), zip);
                    zip.closeEntry();
                    entries++;
                }
            }
            if (entries == 0) {
                String name = uniqueArchiveName(entity.getLogObjectKey(), null, names);
                zip.putNextEntry(new ZipEntry(name));
                downloadTo(entity.getLogObjectBucket(), entity.getLogObjectKey(), zip);
                zip.closeEntry();
            }
            zip.finish();
            zip.flush();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to stream run log archive", failure);
        }
    }

    private String uniqueArchiveName(String objectKey, Integer sequence, java.util.Set<String> names) {
        String key = objectKey == null ? "run.log" : objectKey.replace('\\', '/');
        int slash = key.lastIndexOf('/');
        String base = slash < 0 ? key : key.substring(slash + 1);
        if (!StringUtils.hasText(base)) {
            base = "run.log";
        }
        String candidate = sequence == null ? base : String.format("%04d-%s", sequence.intValue(), base);
        if (names.add(candidate)) {
            return candidate;
        }
        int suffix = 1;
        while (!names.add(suffix + "-" + candidate)) {
            suffix++;
        }
        return suffix + "-" + candidate;
    }

    public void deleteObject(String bucket, String objectKey) {
        objectStore.delete(bucket, objectKey);
    }

    public RunLogView readObjectLog(RunRecordEntity entity, Integer pageNo, Integer pageSizeBytes, boolean full) {
        if (entity == null || !StringUtils.hasText(entity.getLogObjectBucket()) || !StringUtils.hasText(entity.getLogObjectKey())) {
            throw new IllegalStateException("Run log object metadata is missing");
        }
        if (runLogChunkMapper != null && entity.getId() != null) {
            List<RunLogChunkEntity> chunks = runLogChunkMapper.selectList(
                    new LambdaQueryWrapper<RunLogChunkEntity>()
                            .eq(RunLogChunkEntity::getTenantId, entity.getTenantId())
                            .eq(RunLogChunkEntity::getProjectId, entity.getProjectId())
                            .eq(RunLogChunkEntity::getRunRecordId, entity.getId())
                            .orderByAsc(RunLogChunkEntity::getSequenceNo)
                            .orderByAsc(RunLogChunkEntity::getId));
            if (chunks != null && chunks.stream().anyMatch(chunk -> chunk != null
                    && StringUtils.hasText(chunk.getObjectBucket())
                    && StringUtils.hasText(chunk.getObjectKey()))) {
                return readObjectChunksLog(entity, chunks, pageNo, pageSizeBytes, full);
            }
        }
        return readObjectLog(entity.getId(),
                entity.getLogObjectBucket(),
                entity.getLogObjectKey(),
                entity.getLogCharset(),
                downloadName(entity),
                entity.getUpdatedAt(),
                pageNo,
                pageSizeBytes,
                full);
    }

    /** Reads one persisted streaming chunk without joining it to the logical run log. */
    public RunLogView readObjectChunk(RunRecordEntity entity,
                                      RunLogChunkEntity chunk,
                                      Integer pageNo,
                                      Integer pageSizeBytes) {
        if (entity == null || chunk == null
                || !StringUtils.hasText(chunk.getObjectBucket())
                || !StringUtils.hasText(chunk.getObjectKey())) {
            throw new IllegalStateException("Run log chunk object metadata is missing");
        }
        Path downloaded = null;
        Path readable = null;
        try {
            downloaded = Files.createTempFile("studio-run-log-chunk-", ".part");
            try {
                downloadTo(chunk.getObjectBucket(), chunk.getObjectKey(), downloaded);
            } catch (UnsupportedOperationException unsupported) {
                Files.write(downloaded, objectStore.get(chunk.getObjectBucket(), chunk.getObjectKey()),
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
            readable = downloaded;
            if (chunk.getObjectKey().toLowerCase(java.util.Locale.ROOT).endsWith(".gz")) {
                Path decompressed = Files.createTempFile("studio-run-log-chunk-", ".log");
                try (InputStream raw = new BufferedInputStream(Files.newInputStream(downloaded));
                     InputStream input = new GZIPInputStream(raw);
                     OutputStream output = Files.newOutputStream(decompressed, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            output.write(buffer, 0, read);
                        }
                    }
                }
                readable = decompressed;
            }
            LocalDateTime updatedAt = chunk.getChunkEndedAt() != null ? chunk.getChunkEndedAt()
                    : (chunk.getChunkStartedAt() != null ? chunk.getChunkStartedAt() : entity.getUpdatedAt());
            return readFileLog(entity.getId(), readable, entity.getLogCharset(),
                    chunkDownloadName(chunk), updatedAt, pageNo, pageSizeBytes, false);
        } catch (UnsupportedOperationException unsupported) {
            throw new IllegalStateException("Run log chunk object storage is not readable", unsupported);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read run log chunk object", failure);
        } finally {
            if (readable != null && !readable.equals(downloaded)) {
                try {
                    Files.deleteIfExists(readable);
                } catch (IOException ignored) {
                    // Best-effort cleanup of the decompressed temporary chunk.
                }
            }
            if (downloaded != null) {
                try {
                    Files.deleteIfExists(downloaded);
                } catch (IOException ignored) {
                    // Best-effort cleanup of the downloaded temporary chunk.
                }
            }
        }
    }

    private String chunkDownloadName(RunLogChunkEntity chunk) {
        String key = chunk == null ? null : chunk.getObjectKey();
        if (!StringUtils.hasText(key)) {
            return "run-log-chunk.log";
        }
        String normalized = key.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        return StringUtils.hasText(name) ? name : "run-log-chunk.log";
    }

    /**
     * Materialises only a temporary disk representation of persisted chunks. The response page itself
     * remains bounded by MAX_PAGE_BYTES and no complete object is retained in the JVM heap.
     */
    private RunLogView readObjectChunksLog(RunRecordEntity entity,
                                           List<RunLogChunkEntity> chunks,
                                           Integer pageNo,
                                           Integer pageSizeBytes,
                                           boolean full) {
        Path staging = null;
        try {
            staging = Files.createTempFile("studio-run-log-chunks-", ".log");
            try (OutputStream output = Files.newOutputStream(staging, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (RunLogChunkEntity chunk : chunks) {
                    if (chunk == null || !StringUtils.hasText(chunk.getObjectBucket())
                            || !StringUtils.hasText(chunk.getObjectKey())) {
                        continue;
                    }
                    Path part = Files.createTempFile("studio-run-log-chunk-", ".part");
                    try {
                        try {
                            downloadTo(chunk.getObjectBucket(), chunk.getObjectKey(), part);
                        } catch (UnsupportedOperationException unsupported) {
                            Files.write(part, objectStore.get(chunk.getObjectBucket(), chunk.getObjectKey()),
                                    StandardOpenOption.TRUNCATE_EXISTING);
                        }
                        try (InputStream raw = new BufferedInputStream(Files.newInputStream(part));
                             InputStream input = chunk.getObjectKey().toLowerCase(java.util.Locale.ROOT).endsWith(".gz")
                                     ? new GZIPInputStream(raw) : raw) {
                            byte[] buffer = new byte[64 * 1024];
                            int read;
                            while ((read = input.read(buffer)) >= 0) {
                                if (read > 0) {
                                    output.write(buffer, 0, read);
                                }
                            }
                        }
                    } finally {
                        Files.deleteIfExists(part);
                    }
                }
            }
            return readFileLog(entity.getId(), staging, entity.getLogCharset(),
                    downloadName(entity), entity.getUpdatedAt(), pageNo, pageSizeBytes, full);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read persisted run log chunks", failure);
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the bounded page has already been produced.
                }
            }
        }
    }

    private RunLogView readFileLog(Long ownerId,
                                   Path path,
                                   String charsetName,
                                   String downloadName,
                                   LocalDateTime updatedAt,
                                   Integer pageNo,
                                   Integer pageSizeBytes,
                                   boolean full) throws IOException {
        Charset charset = Charset.forName(StringUtils.hasText(charsetName) ? charsetName : StandardCharsets.UTF_8.name());
        int safePageSizeBytes = normalizePageSizeBytes(pageSizeBytes == null || pageSizeBytes.intValue() <= 0
                ? DEFAULT_PAGE_BYTES : pageSizeBytes.intValue());
        long sizeBytes = Files.size(path);
        int effectivePageSize = full ? MAX_PAGE_BYTES : safePageSizeBytes;
        int totalPages = computeTotalPages(sizeBytes, effectivePageSize);
        int safePageNo = full ? totalPages : normalizePageNo(pageNo, totalPages);
        byte[] pageBytes = readPageBytes(path, safePageNo, effectivePageSize, charset);
        RunLogView view = new RunLogView();
        view.setRunRecordId(ownerId);
        view.setCharset(charset.name());
        view.setContentType("text/plain;charset=" + charset.name());
        view.setContent(StudioSensitiveLogSanitizer.sanitize(new String(pageBytes, charset)));
        view.setSizeBytes(Long.valueOf(sizeBytes));
        view.setTruncated(full && sizeBytes > MAX_PAGE_BYTES);
        view.setPaged(totalPages > 1);
        view.setUpdatedAt(updatedAt == null ? LocalDateTime.now() : updatedAt);
        view.setDownloadName(StringUtils.hasText(downloadName) ? downloadName : defaultDownloadName(ownerId));
        view.setHistoricalFallback(false);
        view.setPageNo(Integer.valueOf(safePageNo));
        view.setTotalPages(Integer.valueOf(totalPages));
        view.setPageSizeBytes(Integer.valueOf(effectivePageSize));
        return view;
    }

    public RunLogView readObjectLog(Long ownerId,
                                    String bucket,
                                    String objectKey,
                                    String charsetName,
                                    String downloadName,
                                    LocalDateTime updatedAt,
                                    Integer pageNo,
                                    Integer pageSizeBytes,
                                    boolean full) {
        if (!StringUtils.hasText(bucket) || !StringUtils.hasText(objectKey)) {
            throw new IllegalStateException("Log object metadata is missing");
        }
        Charset charset = Charset.forName(StringUtils.hasText(charsetName) ? charsetName : StandardCharsets.UTF_8.name());
        int safePageSizeBytes = normalizePageSizeBytes(pageSizeBytes == null || pageSizeBytes.intValue() <= 0
                ? DEFAULT_PAGE_BYTES
                : pageSizeBytes.intValue());
        byte[] pageBytes;
        long sizeBytes;
        int totalPages;
        int safePageNo;
        Path staging = null;
        try {
            staging = Files.createTempFile("studio-run-log-", ".log");
            objectStore.downloadTo(bucket, objectKey, staging);
            sizeBytes = Files.size(staging);
            int effectivePageSize = full ? MAX_PAGE_BYTES : safePageSizeBytes;
            totalPages = computeTotalPages(sizeBytes, effectivePageSize);
            safePageNo = full ? totalPages : normalizePageNo(pageNo, totalPages);
            pageBytes = readPageBytes(staging, safePageNo, effectivePageSize, charset);
        } catch (UnsupportedOperationException unsupported) {
            // Keep test doubles and legacy stores compatible while production stores use streaming I/O.
            byte[] bytes = objectStore.get(bucket, objectKey);
            sizeBytes = bytes.length;
            totalPages = computeTotalPages(sizeBytes, full ? MAX_PAGE_BYTES : safePageSizeBytes);
            safePageNo = full ? totalPages : normalizePageNo(pageNo, totalPages);
            pageBytes = slice(bytes, safePageNo, full ? MAX_PAGE_BYTES : safePageSizeBytes, charset);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to stage run log object " + objectKey, failure);
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) {
                    // Temporary cleanup is best effort; the bounded page is already available.
                }
            }
        }

        RunLogView view = new RunLogView();
        view.setRunRecordId(ownerId);
        view.setCharset(charset.name());
        view.setContentType("text/plain;charset=" + charset.name());
        view.setContent(StudioSensitiveLogSanitizer.sanitize(new String(pageBytes, charset)));
        view.setSizeBytes(Long.valueOf(sizeBytes));
        view.setTruncated(full && sizeBytes > MAX_PAGE_BYTES);
        view.setPaged(totalPages > 1);
        view.setUpdatedAt(updatedAt == null ? LocalDateTime.now() : updatedAt);
        view.setDownloadName(StringUtils.hasText(downloadName) ? downloadName : defaultDownloadName(ownerId));
        view.setHistoricalFallback(false);
        view.setPageNo(Integer.valueOf(safePageNo));
        view.setTotalPages(Integer.valueOf(totalPages));
        view.setPageSizeBytes(Integer.valueOf(full ? MAX_PAGE_BYTES : safePageSizeBytes));
        return view;
    }

    private byte[] readPageBytes(Path path, int pageNo, int pageSizeBytes, Charset charset) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long size = file.length();
            if (size <= pageSizeBytes) {
                byte[] bytes = new byte[(int) size];
                file.readFully(bytes);
                return bytes;
            }
            long start = Math.max(0L, Math.min(size - 1L, (long) (pageNo - 1) * pageSizeBytes));
            long safeStart = start;
            if ("UTF-8".equalsIgnoreCase(charset.name())) {
                while (safeStart > 0L) {
                    file.seek(safeStart);
                    int current = file.read();
                    if (current < 0 || (current & 0xC0) != 0x80) {
                        break;
                    }
                    safeStart--;
                }
            }
            int length = (int) Math.min((long) pageSizeBytes + (start - safeStart), size - safeStart);
            byte[] bytes = new byte[length];
            file.seek(safeStart);
            file.readFully(bytes);
            return bytes;
        }
    }

    private byte[] slice(byte[] bytes, int pageNo, int pageSizeBytes, Charset charset) {
        if (bytes.length <= pageSizeBytes) {
            return bytes;
        }
        int requestedStart = Math.max(0, Math.min(bytes.length, (pageNo - 1) * pageSizeBytes));
        int safeStart = alignPageStart(bytes, requestedStart, charset);
        int readLength = Math.min(pageSizeBytes + (requestedStart - safeStart), bytes.length - safeStart);
        byte[] result = Arrays.copyOfRange(bytes, safeStart, safeStart + readLength);
        return trimToCharsetBoundary(result, safeStart, bytes.length, charset);
    }

    private int alignPageStart(byte[] bytes, int start, Charset charset) {
        if (start <= 0 || bytes == null || charset == null || !"UTF-8".equalsIgnoreCase(charset.name())) {
            return start;
        }
        int safeStart = start;
        while (safeStart > 0 && safeStart < bytes.length && isUtf8ContinuationByte(bytes[safeStart])) {
            safeStart--;
        }
        return safeStart;
    }

    private byte[] trimToCharsetBoundary(byte[] bytes, int absoluteStart, int totalSize, Charset charset) {
        if (bytes == null || bytes.length == 0 || charset == null || !"UTF-8".equalsIgnoreCase(charset.name())) {
            return bytes;
        }
        int safeStart = 0;
        if (absoluteStart > 0) {
            while (safeStart < bytes.length && isUtf8ContinuationByte(bytes[safeStart])) {
                safeStart++;
            }
        }
        int safeEnd = bytes.length;
        if (absoluteStart + bytes.length < totalSize) {
            while (safeEnd > safeStart && !canDecode(bytes, safeStart, safeEnd - safeStart, charset)) {
                safeEnd--;
            }
        }
        if (safeStart <= 0 && safeEnd >= bytes.length) {
            return bytes;
        }
        if (safeEnd <= safeStart) {
            return new byte[0];
        }
        return Arrays.copyOfRange(bytes, safeStart, safeEnd);
    }

    private boolean canDecode(byte[] bytes, int offset, int length, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private boolean isUtf8ContinuationByte(byte value) {
        return (value & 0xC0) == 0x80;
    }

    private int normalizePageNo(Integer pageNo, int totalPages) {
        if (totalPages <= 0) {
            return 1;
        }
        if (pageNo == null || pageNo.intValue() <= 0) {
            return totalPages;
        }
        return Math.min(pageNo.intValue(), totalPages);
    }

    private int normalizePageSizeBytes(int pageSizeBytes) {
        if (pageSizeBytes <= 0) {
            return DEFAULT_PAGE_BYTES;
        }
        return Math.min(pageSizeBytes, MAX_PAGE_BYTES);
    }

    private int computeTotalPages(long sizeBytes, int pageSizeBytes) {
        if (sizeBytes <= 0L) {
            return 1;
        }
        return (int) Math.max(1L, (sizeBytes + pageSizeBytes - 1L) / pageSizeBytes);
    }

    private String downloadName(RunRecordEntity entity) {
        if (StringUtils.hasText(entity.getLogFilePath())) {
            String path = entity.getLogFilePath().replace('\\', '/');
            int index = path.lastIndexOf('/');
            return index >= 0 ? path.substring(index + 1) : path;
        }
        return "run-" + entity.getId() + ".log";
    }

    private String defaultDownloadName(Long ownerId) {
        return "log-" + (ownerId == null ? "unknown" : ownerId) + ".log";
    }

    private String trimSlashes(String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
