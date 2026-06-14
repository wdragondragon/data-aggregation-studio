package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
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

@Service
public class RunLogStorageService {

    private static final int DEFAULT_PAGE_BYTES = 64 * 1024;
    private static final int MAX_PAGE_BYTES = 512 * 1024;
    public static final String STORAGE_LOCAL = "LOCAL";
    public static final String STORAGE_OBJECT = "OBJECT_STORAGE";

    private final StudioPlatformProperties properties;
    private final RunLogObjectStore objectStore;

    public RunLogStorageService(StudioPlatformProperties properties, RunLogObjectStore objectStore) {
        this.properties = properties;
        this.objectStore = objectStore;
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
        String bucket = properties.getRunLog().getObjectStorage().getBucket();
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("studio.run-log.object-storage.bucket is required");
        }
        return bucket.trim();
    }

    public String buildObjectKey(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        String prefix = properties.getRunLog().getObjectStorage().getPrefix();
        String normalizedPrefix = StringUtils.hasText(prefix) ? trimSlashes(prefix.trim()) : "";
        String normalizedPath = trimSlashes(relativePath.replace('\\', '/'));
        return normalizedPrefix.isEmpty() ? normalizedPath : normalizedPrefix + "/" + normalizedPath;
    }

    public void upload(String bucket, String objectKey, byte[] bytes, String contentType) {
        objectStore.put(bucket, objectKey, bytes, contentType);
    }

    public RunLogView readObjectLog(RunRecordEntity entity, Integer pageNo, Integer pageSizeBytes, boolean full) {
        if (entity == null || !StringUtils.hasText(entity.getLogObjectBucket()) || !StringUtils.hasText(entity.getLogObjectKey())) {
            throw new IllegalStateException("Run log object metadata is missing");
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
        byte[] bytes = objectStore.get(bucket, objectKey);
        Charset charset = Charset.forName(StringUtils.hasText(charsetName) ? charsetName : StandardCharsets.UTF_8.name());
        int safePageSizeBytes = normalizePageSizeBytes(pageSizeBytes == null || pageSizeBytes.intValue() <= 0
                ? DEFAULT_PAGE_BYTES
                : pageSizeBytes.intValue());
        int totalPages = full ? 1 : computeTotalPages(bytes.length, safePageSizeBytes);
        int safePageNo = full ? 1 : normalizePageNo(pageNo, totalPages);
        byte[] pageBytes = full ? bytes : slice(bytes, safePageNo, safePageSizeBytes, charset);

        RunLogView view = new RunLogView();
        view.setRunRecordId(ownerId);
        view.setCharset(charset.name());
        view.setContentType("text/plain;charset=" + charset.name());
        view.setContent(new String(pageBytes, charset));
        view.setSizeBytes(Long.valueOf(bytes.length));
        view.setTruncated(false);
        view.setPaged(!full && totalPages > 1);
        view.setUpdatedAt(updatedAt == null ? LocalDateTime.now() : updatedAt);
        view.setDownloadName(StringUtils.hasText(downloadName) ? downloadName : defaultDownloadName(ownerId));
        view.setHistoricalFallback(false);
        view.setPageNo(Integer.valueOf(safePageNo));
        view.setTotalPages(Integer.valueOf(totalPages));
        view.setPageSizeBytes(Integer.valueOf(full ? Integer.MAX_VALUE : safePageSizeBytes));
        return view;
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
