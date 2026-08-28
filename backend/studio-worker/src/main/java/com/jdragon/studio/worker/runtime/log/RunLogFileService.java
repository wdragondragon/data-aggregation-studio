package com.jdragon.studio.worker.runtime.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import ch.qos.logback.core.spi.FilterReply;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
import com.jdragon.studio.commons.util.StudioPathUtils;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.RunLogChunkEntity;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import com.jdragon.studio.infra.service.RunLogStorageService;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.DirectoryStream;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.GZIPInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RunLogFileService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final int DEFAULT_PAGE_BYTES = 64 * 1024;
    private static final int MAX_PAGE_BYTES = 512 * 1024;
    private static final long MAX_CHUNK_BYTES = 128L * 1024L * 1024L;

    private final StudioPlatformProperties properties;
    private final RunLogStorageService runLogStorageService;
    private final LoggerContext loggerContext;
    private final Logger rootLogger;
    private final RunLogChunkMapper runLogChunkMapper;
    private final Map<Long, FileAppender<ILoggingEvent>> appenders = new ConcurrentHashMap<Long, FileAppender<ILoggingEvent>>();
    private final Map<Long, PreparedRunLog> activeLogs = new ConcurrentHashMap<Long, PreparedRunLog>();
    private final Map<String, String> lastUploadErrorByPath = new ConcurrentHashMap<String, String>();
    private final Map<String, ChunkUploadState> uploadedChunks = new ConcurrentHashMap<String, ChunkUploadState>();

    public RunLogFileService(StudioPlatformProperties properties, RunLogStorageService runLogStorageService) {
        this(properties, runLogStorageService, null);
    }

    @Autowired
    public RunLogFileService(StudioPlatformProperties properties,
                             RunLogStorageService runLogStorageService,
                             RunLogChunkMapper runLogChunkMapper) {
        this.properties = properties;
        this.runLogStorageService = runLogStorageService;
        this.runLogChunkMapper = runLogChunkMapper;
        this.loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    public PreparedRunLog prepare(Long runRecordId) {
        try {
            String dateFolder = LocalDate.now(resolveZoneId()).format(DATE_FORMATTER);
            Path root = StudioPathUtils.resolveStudioPath(properties.getRuntimeLogDir());
            Path folder = root.resolve(dateFolder);
            Files.createDirectories(folder);
            String relativePath = dateFolder + "/run-" + runRecordId + ".log";
            Path absolutePath = folder.resolve("run-" + runRecordId + ".log");
            Files.write(absolutePath, new byte[0]);
            return new PreparedRunLog(runRecordId, relativePath, absolutePath, DEFAULT_CHARSET.name());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare run log file for runRecordId=" + runRecordId, e);
        }
    }

    /** Prepares the bounded rolling log used by one long-lived streaming attempt. */
    public PreparedRunLog prepareStreaming(Long collectionTaskId,
                                           Long runId,
                                           Long attemptId,
                                           Long runRecordId,
                                           String tenantId,
                                           Long projectId) {
        try {
            String dateFolder = LocalDate.now(resolveZoneId()).format(DATE_FORMATTER);
            Path root = StudioPathUtils.resolveStudioPath(properties.getRuntimeLogDir());
            Path folder = root.resolve(dateFolder);
            Files.createDirectories(folder);
            String fileName = "stream-run-" + runId + "-attempt-" + attemptId + ".log";
            String relativePath = dateFolder + "/" + fileName;
            Path absolutePath = folder.resolve(fileName);
            Files.write(absolutePath, new byte[0]);
            return new PreparedRunLog(runRecordId, relativePath, absolutePath, DEFAULT_CHARSET.name(),
                    true, collectionTaskId, runId, attemptId, tenantId, projectId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare streaming run log file for attemptId=" + attemptId, e);
        }
    }

    public RunLogScope openScope(PreparedRunLog prepared) {
        FileAppender<ILoggingEvent> appender = buildAppender(prepared);
        rootLogger.addAppender(appender);
        appenders.put(prepared.getRunRecordId(), appender);
        activeLogs.put(prepared.getRunRecordId(), prepared);
        MDC.put(StudioConstants.MDC_RUN_LOG_ID, String.valueOf(prepared.getRunRecordId()));
        MDC.put(StudioConstants.MDC_RUN_LOG_PATH, prepared.getRelativePath());
        return new RunLogScope(prepared.getRunRecordId());
    }

    public RunLogView readPage(RunRecordEntity entity, Integer pageNo, Integer pageSizeBytes) {
        return readLogPage(entity, pageNo, pageSizeBytes == null || pageSizeBytes.intValue() <= 0 ? DEFAULT_PAGE_BYTES : pageSizeBytes.intValue(), false);
    }

    public RunLogView readFull(RunRecordEntity entity) {
        return readLogPage(entity, 1, Integer.MAX_VALUE, true);
    }

    /** Reads only the persisted local file represented by one streaming log chunk. */
    public RunLogView readChunkPage(RunRecordEntity entity,
                                    RunLogChunkEntity chunk,
                                    Integer pageNo,
                                    Integer pageSizeBytes) {
        if (entity == null || chunk == null || !StringUtils.hasText(chunk.getLocalPath())) {
            throw new IllegalStateException("Run log chunk file metadata is missing");
        }
        Path root = StudioPathUtils.resolveStudioPath(properties.getRuntimeLogDir()).toAbsolutePath().normalize();
        Path path = root.resolve(chunk.getLocalPath()).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new IllegalStateException("Run log chunk file is not available for runRecordId=" + entity.getId());
        }
        try {
            return readChunkFilesLogPage(entity, Collections.singletonList(path), pageNo,
                    normalizePageSizeBytes(pageSizeBytes == null || pageSizeBytes.intValue() <= 0
                            ? DEFAULT_PAGE_BYTES : pageSizeBytes.intValue()), false, path.getFileName().toString(),
                    chunk.getChunkEndedAt() != null ? chunk.getChunkEndedAt()
                            : (chunk.getChunkStartedAt() != null ? chunk.getChunkStartedAt() : resolveUpdatedAt(path)));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read run log chunk file for runRecordId=" + entity.getId(), failure);
        }
    }

    public long fileSize(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return 0L;
        }
        try {
            Path path = resolveLogPath(relativePath);
            if (!Files.exists(path)) {
                return 0L;
            }
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    public RunLogStorageResult finalizeLog(PreparedRunLog prepared) {
        if (prepared == null) {
            return RunLogStorageResult.local(0L);
        }
        activeLogs.remove(prepared.getRunRecordId());
        return prepared.isStreaming() ? syncStreamingChunks(prepared, true) : uploadLog(prepared, "AVAILABLE");
    }

    /** Writes all local log chunks to a ZIP stream without loading the archive into memory. */
    public void writeArchive(RunRecordEntity entity, OutputStream output) {
        if (entity == null || output == null || !StringUtils.hasText(entity.getLogFilePath())) {
            throw new IllegalArgumentException("Run log archive context is incomplete");
        }
        Path base = resolveLogPath(entity.getLogFilePath());
        if (!Files.exists(base)) {
            throw new IllegalStateException("Run log file not found for runRecordId=" + entity.getId());
        }
        try {
            List<Path> files = discoverChunkFiles(base);
            ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output));
            Set<String> names = new HashSet<String>();
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!names.add(name)) {
                    name = String.valueOf(names.size()) + "-" + name;
                }
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            zip.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
            zip.finish();
            zip.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stream run log archive for runRecordId=" + entity.getId(), e);
        }
    }

    public Map<Long, RunLogStorageResult> syncActiveObjectLogs() {
        Map<Long, RunLogStorageResult> results = new LinkedHashMap<Long, RunLogStorageResult>();
        for (PreparedRunLog prepared : activeLogs.values()) {
            if (prepared == null || prepared.getRunRecordId() == null) {
                continue;
            }
            if (!runLogStorageService.objectStorageEnabled() && !prepared.isStreaming()) {
                continue;
            }
            results.put(prepared.getRunRecordId(), prepared.isStreaming()
                    ? syncStreamingChunks(prepared, false)
                    : uploadLog(prepared, "WRITING"));
        }
        return results;
    }

    public RunLogStorageResult syncExistingLog(String relativePath, String charset) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return RunLogStorageResult.local(0L);
        }
        String resolvedCharset = charset == null || charset.trim().isEmpty() ? DEFAULT_CHARSET.name() : charset.trim();
        PreparedRunLog prepared = new PreparedRunLog(null, relativePath, resolveLogPath(relativePath), resolvedCharset);
        return uploadLog(prepared, "AVAILABLE");
    }

    private RunLogStorageResult uploadLog(PreparedRunLog prepared, String successStatus) {
        long size = fileSize(prepared.getRelativePath());
        if (!runLogStorageService.objectStorageEnabled()) {
            return RunLogStorageResult.local(size);
        }
        String bucket = null;
        String objectKey = null;
        try {
            bucket = runLogStorageService.resolveBucket();
            objectKey = runLogStorageService.buildObjectKey(prepared.getRelativePath());
            runLogStorageService.uploadFile(bucket, objectKey, prepared.getAbsolutePath(),
                    "text/plain;charset=" + prepared.getCharset());
            lastUploadErrorByPath.remove(prepared.getRelativePath());
            return RunLogStorageResult.objectStorage(size, bucket, objectKey, successStatus);
        } catch (Exception e) {
            String summary = summarizeThrowable(e);
            appendUploadFailure(prepared, summary);
            return RunLogStorageResult.failed(fileSize(prepared.getRelativePath()), summary);
        }
    }

    private void appendUploadFailure(PreparedRunLog prepared, String summary) {
        if (prepared == null || prepared.getAbsolutePath() == null || prepared.getRelativePath() == null) {
            return;
        }
        String previous = lastUploadErrorByPath.put(prepared.getRelativePath(), summary);
        if (summary != null && summary.equals(previous)) {
            return;
        }
        String line = System.lineSeparator()
                + "[Studio] Run log object storage upload failed: "
                + (summary == null || summary.trim().isEmpty() ? "unknown error" : StudioSensitiveLogSanitizer.sanitize(summary.trim()))
                + System.lineSeparator();
        try {
            Files.write(prepared.getAbsolutePath(), line.getBytes(DEFAULT_CHARSET),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The original object storage failure is already returned through log_error_summary.
        }
    }

    private String summarizeThrowable(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (builder.length() > 0) {
                builder.append(" <- ");
            }
            builder.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                builder.append(": ").append(message.trim());
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    private RunLogView readLogPage(RunRecordEntity entity, Integer pageNo, int pageSizeBytes, boolean full) {
        Path path = resolveLogPath(entity.getLogFilePath());
        if (!Files.exists(path)) {
            throw new IllegalStateException("Run log file not found for runRecordId=" + entity.getId());
        }
        if (isChunkedStreamingLog(path)) {
            return readChunkedLogPage(entity, path, pageNo, pageSizeBytes, full);
        }
        try {
            long size = Files.size(path);
            int safePageSizeBytes = normalizePageSizeBytes(pageSizeBytes);
            int effectivePageSize = full ? MAX_PAGE_BYTES : safePageSizeBytes;
            int totalPages = computeTotalPages(size, effectivePageSize);
            int safePageNo = full ? totalPages : normalizePageNo(pageNo, totalPages);
            Charset charset = Charset.forName(entity.getLogCharset() == null ? DEFAULT_CHARSET.name() : entity.getLogCharset());
            boolean truncated = full && size > MAX_PAGE_BYTES;
            byte[] bytes = readPageBytes(path, safePageNo, effectivePageSize, charset);
            RunLogView view = new RunLogView();
            view.setRunRecordId(entity.getId());
            view.setCharset(charset.name());
            view.setContentType("text/plain;charset=" + view.getCharset());
            view.setContent(StudioSensitiveLogSanitizer.sanitize(decodeBytes(bytes, charset)));
            view.setSizeBytes(size);
            view.setTruncated(truncated);
            view.setPaged(totalPages > 1);
            view.setUpdatedAt(resolveUpdatedAt(path));
            view.setDownloadName(path.getFileName().toString());
            view.setHistoricalFallback(false);
            view.setPageNo(Integer.valueOf(safePageNo));
            view.setTotalPages(Integer.valueOf(totalPages));
            view.setPageSizeBytes(Integer.valueOf(effectivePageSize));
            return view;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read run log file for runRecordId=" + entity.getId(), e);
        }
    }

    private byte[] readPageBytes(Path path, int pageNo, int pageSizeBytes, Charset charset) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long size = file.length();
            if (size <= pageSizeBytes) {
                byte[] bytes = new byte[(int) size];
                file.readFully(bytes);
                return bytes;
            }
            long start = (long) (pageNo - 1) * (long) pageSizeBytes;
            if (start >= size) {
                start = Math.max(0L, size - pageSizeBytes);
            }
            long safeStart = alignPageStart(file, start, charset);
            int readLength = (int) Math.min((long) pageSizeBytes + (start - safeStart), size - safeStart);
            byte[] bytes = new byte[readLength];
            file.seek(safeStart);
            file.readFully(bytes);
            return trimToCharsetBoundary(bytes, safeStart, size, charset);
        }
    }

    private long alignPageStart(RandomAccessFile file, long start, Charset charset) throws IOException {
        if (start <= 0L || charset == null || !"UTF-8".equalsIgnoreCase(charset.name())) {
            return start;
        }
        long safeStart = start;
        while (safeStart > 0L) {
            file.seek(safeStart);
            int current = file.read();
            if (current < 0 || !isUtf8ContinuationByte((byte) current)) {
                break;
            }
            safeStart--;
        }
        return safeStart;
    }

    private byte[] trimToCharsetBoundary(byte[] bytes, long absoluteStart, long fileSize, Charset charset) {
        if (bytes == null || bytes.length == 0 || charset == null || !"UTF-8".equalsIgnoreCase(charset.name())) {
            return bytes;
        }
        int safeStart = 0;
        if (absoluteStart > 0L) {
            while (safeStart < bytes.length && isUtf8ContinuationByte(bytes[safeStart])) {
                safeStart++;
            }
        }
        int safeEnd = bytes.length;
        if (absoluteStart + bytes.length < fileSize) {
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
        byte[] result = new byte[safeEnd - safeStart];
        System.arraycopy(bytes, safeStart, result, 0, result.length);
        return result;
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

    private String decodeBytes(byte[] bytes, Charset charset) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return new String(bytes, charset);
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

    private LocalDateTime resolveUpdatedAt(Path path) throws IOException {
        return LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), resolveZoneId());
    }

    private Path resolveLogPath(String relativePath) {
        return StudioPathUtils.resolveStudioPath(properties.getRuntimeLogDir()).resolve(relativePath).normalize();
    }

    private ZoneId resolveZoneId() {
        return ZoneId.of(properties.getTimezone() == null || properties.getTimezone().trim().isEmpty()
                ? StudioConstants.DEFAULT_TIMEZONE
                : properties.getTimezone().trim());
    }

    private FileAppender<ILoggingEvent> buildAppender(PreparedRunLog prepared) {
        SanitizingPatternLayoutEncoder encoder = new SanitizingPatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setCharset(DEFAULT_CHARSET);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();

        FileAppender<ILoggingEvent> appender;
        if (prepared.isStreaming()) {
            RollingFileAppender<ILoggingEvent> rolling = new RollingFileAppender<ILoggingEvent>();
            rolling.setContext(loggerContext);
            rolling.setName("RUN_LOG_" + prepared.getRunRecordId());
            rolling.setFile(prepared.getAbsolutePath().toString());
            rolling.setAppend(true);
            SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy =
                    new SizeAndTimeBasedRollingPolicy<ILoggingEvent>();
            policy.setContext(loggerContext);
            policy.setParent(rolling);
            policy.setFileNamePattern(prepared.getAbsolutePath().toString() + ".%d{yyyy-MM-dd-HH}.%i.log.gz");
            policy.setMaxFileSize(FileSize.valueOf("128MB"));
            policy.setMaxHistory(168);
            policy.start();
            rolling.setRollingPolicy(policy);
            rolling.setTriggeringPolicy(policy);
            rolling.setEncoder(encoder);
            rolling.addFilter(new RunLogIdFilter(String.valueOf(prepared.getRunRecordId())));
            rolling.start();
            appender = rolling;
        } else {
            appender = new FileAppender<ILoggingEvent>();
            appender.setContext(loggerContext);
            appender.setName("RUN_LOG_" + prepared.getRunRecordId());
            appender.setFile(prepared.getAbsolutePath().toString());
            appender.setAppend(true);
            appender.setEncoder(encoder);
            appender.addFilter(new RunLogIdFilter(String.valueOf(prepared.getRunRecordId())));
            appender.start();
        }
        return appender;
    }

    private RunLogStorageResult syncStreamingChunks(PreparedRunLog prepared, boolean finalizing) {
        List<Path> files;
        try {
            files = discoverChunkFiles(prepared.getAbsolutePath());
        } catch (IOException failure) {
            return RunLogStorageResult.failed(fileSize(prepared.getRelativePath()), summarizeThrowable(failure));
        }
        long totalSize = 0L;
        String bucket = null;
        String primaryObjectKey = null;
        String firstError = null;
        int sequence = 0;
        boolean objectStorage = runLogStorageService.objectStorageEnabled();
        if (objectStorage) {
            try {
                bucket = runLogStorageService.resolveBucket();
            } catch (RuntimeException failure) {
                firstError = summarizeThrowable(failure);
            }
        }
        for (Path file : files) {
            long size = safeFileSize(file);
            totalSize += size;
            String relative = relativePath(file);
            String objectKey = objectStorage && bucket != null
                    ? runLogStorageService.buildObjectKey(relative) : null;
            if (primaryObjectKey == null && objectKey != null && file.equals(prepared.getAbsolutePath())) {
                primaryObjectKey = objectKey;
            }
            String checksum;
            try {
                checksum = checksum(file);
            } catch (IOException failure) {
                if (firstError == null) {
                    firstError = summarizeThrowable(failure);
                }
                checksum = null;
            }
            boolean active = file.equals(prepared.getAbsolutePath());
            String status = finalizing || !active ? "AVAILABLE" : "WRITING";
            boolean uploaded = false;
            if (objectStorage && bucket != null && checksum != null) {
                String stateKey = file.toAbsolutePath().normalize().toString();
                ChunkUploadState previous = uploadedChunks.get(stateKey);
                int sequenceNo = sequence;
                if (previous == null) {
                    RunLogChunkEntity persisted = findPersistedChunk(prepared, sequenceNo);
                    if (persisted != null && checksum.equals(persisted.getChecksumSha256())
                            && persisted.getUploadedAt() != null
                            && objectKey != null && objectKey.equals(persisted.getObjectKey())) {
                        previous = new ChunkUploadState(checksum, true);
                        uploadedChunks.put(stateKey, previous);
                    }
                }
                boolean needsUpload = previous == null || !checksum.equals(previous.checksum)
                        || !previous.uploaded;
                if (needsUpload) {
                    try {
                        runLogStorageService.uploadFile(bucket, objectKey, file,
                                active ? "text/plain;charset=" + prepared.getCharset() : "application/gzip");
                        uploadedChunks.put(stateKey, new ChunkUploadState(checksum, true));
                        uploaded = true;
                    } catch (RuntimeException failure) {
                        if (firstError == null) {
                            firstError = summarizeThrowable(failure);
                        }
                    }
                } else {
                    uploaded = previous.uploaded;
                }
            }
            persistChunk(prepared, sequence++, file, status, objectStorage ? RunLogStorageService.STORAGE_OBJECT
                    : RunLogStorageService.STORAGE_LOCAL, bucket, objectKey, size, checksum,
                    uploaded ? LocalDateTime.now() : null, finalizing || !active);
        }
        if (firstError != null) {
            return RunLogStorageResult.failed(totalSize, firstError, files.size());
        }
        if (!objectStorage) {
            return RunLogStorageResult.local(totalSize, files.size());
        }
        return RunLogStorageResult.objectStorage(totalSize, bucket, primaryObjectKey,
                finalizing ? "AVAILABLE" : "WRITING", files.size());
    }

    private void persistChunk(PreparedRunLog prepared,
                              int sequence,
                              Path file,
                              String status,
                              String storageType,
                              String bucket,
                              String objectKey,
                              long size,
                              String checksum,
                              LocalDateTime uploadedAt,
                              boolean sealed) {
        if (runLogChunkMapper == null || prepared.getStreamAttemptId() == null) {
            return;
        }
        RunLogChunkEntity entity = findPersistedChunk(prepared, sequence);
        boolean created = entity == null;
        if (entity == null) {
            entity = new RunLogChunkEntity();
            entity.setId(IdWorker.getId());
            entity.setTenantId(prepared.getTenantId());
            entity.setProjectId(prepared.getProjectId());
            entity.setCollectionTaskId(prepared.getCollectionTaskId());
            entity.setRunRecordId(prepared.getRunRecordId());
            entity.setStreamAttemptId(prepared.getStreamAttemptId());
            entity.setSequenceNo(sequence);
            entity.setChunkStartedAt(resolveFileTime(file));
        }
        entity.setStatus(status);
        entity.setLocalPath(relativePath(file));
        entity.setStorageType(storageType);
        entity.setObjectBucket(bucket);
        entity.setObjectKey(objectKey);
        entity.setSizeBytes(size);
        entity.setChecksumSha256(checksum);
        entity.setChunkEndedAt(sealed ? resolveFileTime(file) : null);
        if (uploadedAt != null) {
            entity.setUploadedAt(uploadedAt);
        }
        if (created) {
            // Populate non-null columns before INSERT; MySQL does not provide a default status.
            runLogChunkMapper.insert(entity);
        } else {
            runLogChunkMapper.updateById(entity);
        }
    }

    /** Reads a streaming attempt as one logical UTF-8 log while keeping page memory bounded. */
    private RunLogView readChunkedLogPage(RunRecordEntity entity,
                                          Path base,
                                          Integer pageNo,
                                          int pageSizeBytes,
                                          boolean full) {
        try {
            List<Path> files = discoverChunkFiles(base);
            return readChunkFilesLogPage(entity, files, pageNo, pageSizeBytes, full,
                    base.getFileName().toString(), resolveUpdatedAt(base));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read chunked run log file for runRecordId=" + entity.getId(), failure);
        }
    }

    private RunLogView readChunkFilesLogPage(RunRecordEntity entity,
                                             List<Path> files,
                                             Integer pageNo,
                                             int pageSizeBytes,
                                             boolean full,
                                             String downloadName,
                                             LocalDateTime updatedAt) throws IOException {
        long size = logicalSize(files);
            int safePageSizeBytes = normalizePageSizeBytes(pageSizeBytes);
            int effectivePageSize = full ? MAX_PAGE_BYTES : safePageSizeBytes;
            int totalPages = computeTotalPages(size, effectivePageSize);
            int safePageNo = full ? totalPages : normalizePageNo(pageNo, totalPages);
            Charset charset = Charset.forName(entity.getLogCharset() == null
                    ? DEFAULT_CHARSET.name() : entity.getLogCharset());
            byte[] bytes = readChunkedPageBytes(files, safePageNo, effectivePageSize, size, charset);
            RunLogView view = new RunLogView();
            view.setRunRecordId(entity.getId());
            view.setCharset(charset.name());
            view.setContentType("text/plain;charset=" + view.getCharset());
            view.setContent(StudioSensitiveLogSanitizer.sanitize(decodeBytes(bytes, charset)));
            view.setSizeBytes(size);
            view.setTruncated(full && size > MAX_PAGE_BYTES);
            view.setPaged(totalPages > 1);
            view.setUpdatedAt(updatedAt == null ? LocalDateTime.now(resolveZoneId()) : updatedAt);
            view.setDownloadName(StringUtils.hasText(downloadName) ? downloadName : "run.log");
            view.setHistoricalFallback(false);
            view.setPageNo(Integer.valueOf(safePageNo));
            view.setTotalPages(Integer.valueOf(totalPages));
            view.setPageSizeBytes(Integer.valueOf(effectivePageSize));
        return view;
    }

    private boolean isChunkedStreamingLog(Path path) {
        return path != null && path.getFileName() != null
                && path.getFileName().toString().startsWith("stream-run-");
    }

    private long logicalSize(List<Path> files) throws IOException {
        long size = 0L;
        for (Path file : files) {
            try (InputStream input = openChunkInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        size += read;
                    }
                }
            }
        }
        return size;
    }

    private byte[] readChunkedPageBytes(List<Path> files,
                                        int pageNo,
                                        int pageSizeBytes,
                                        long logicalSize,
                                        Charset charset) throws IOException {
        if (files.isEmpty() || logicalSize <= 0L) {
            return new byte[0];
        }
        long requestedStart = Math.max(0L, Math.min(logicalSize - 1L,
                (long) (pageNo - 1) * pageSizeBytes));
        long safeStart = Math.max(0L, requestedStart - 3L);
        long toRead = Math.min((long) pageSizeBytes + (requestedStart - safeStart) + 4L,
                logicalSize - safeStart);
        try (InputStream input = openConcatenatedChunkStream(files)) {
            skipFully(input, safeStart);
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, toRead));
            byte[] buffer = new byte[64 * 1024];
            long remaining = toRead;
            while (remaining > 0L) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                if (read > 0) {
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            return trimToCharsetBoundary(output.toByteArray(), safeStart, logicalSize, charset);
        }
    }

    private InputStream openConcatenatedChunkStream(List<Path> files) throws IOException {
        List<InputStream> streams = new ArrayList<InputStream>(files.size());
        try {
            for (Path file : files) {
                streams.add(openChunkInputStream(file));
            }
            return new SequenceInputStream(Collections.enumeration(streams));
        } catch (IOException failure) {
            for (InputStream stream : streams) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Preserve the original open failure.
                }
            }
            throw failure;
        }
    }

    private InputStream openChunkInputStream(Path file) throws IOException {
        InputStream input = new BufferedInputStream(Files.newInputStream(file));
        if (file.getFileName().toString().endsWith(".gz")) {
            return new GZIPInputStream(input);
        }
        return input;
    }

    private void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                break;
            }
            remaining--;
        }
    }

    private RunLogChunkEntity findPersistedChunk(PreparedRunLog prepared, int sequence) {
        if (runLogChunkMapper == null || prepared == null || prepared.getStreamAttemptId() == null) {
            return null;
        }
        return runLogChunkMapper.selectOne(new LambdaQueryWrapper<RunLogChunkEntity>()
                .eq(RunLogChunkEntity::getTenantId, prepared.getTenantId())
                .eq(RunLogChunkEntity::getProjectId, prepared.getProjectId())
                .eq(RunLogChunkEntity::getStreamAttemptId, prepared.getStreamAttemptId())
                .eq(RunLogChunkEntity::getSequenceNo, sequence)
                .last("limit 1"));
    }

    private List<Path> discoverChunkFiles(Path base) throws IOException {
        if (base == null || base.getParent() == null) {
            return Collections.emptyList();
        }
        List<Path> files = new ArrayList<Path>();
        if (Files.isRegularFile(base)) {
            files.add(base);
        }
        String prefix = base.getFileName().toString() + ".";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base.getParent())) {
            for (Path candidate : stream) {
                String name = candidate.getFileName().toString();
                if (Files.isRegularFile(candidate) && name.startsWith(prefix) && name.endsWith(".gz")) {
                    files.add(candidate);
                }
            }
        }
        files.sort(Comparator.comparingLong(this::safeLastModified)
                .thenComparing(path -> path.getFileName().toString()));
        return files;
    }

    private String relativePath(Path path) {
        Path root = StudioPathUtils.resolveStudioPath(properties.getRuntimeLogDir()).toAbsolutePath().normalize();
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private long safeFileSize(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private LocalDateTime resolveFileTime(Path path) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), resolveZoneId());
        } catch (IOException ignored) {
            return LocalDateTime.now(resolveZoneId());
        }
    }

    private String checksum(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 is not available", failure);
        }
    }

    private static final class RunLogIdFilter extends Filter<ILoggingEvent> {
        private final String expectedRunLogId;

        private RunLogIdFilter(String expectedRunLogId) {
            this.expectedRunLogId = expectedRunLogId;
            start();
        }

        @Override
        public FilterReply decide(ILoggingEvent event) {
            if (event == null) {
                return FilterReply.DENY;
            }
            String current = event.getMDCPropertyMap().get(StudioConstants.MDC_RUN_LOG_ID);
            return expectedRunLogId.equals(current) ? FilterReply.ACCEPT : FilterReply.DENY;
        }
    }

    public final class RunLogScope implements AutoCloseable {
        private final Long runRecordId;

        private RunLogScope(Long runRecordId) {
            this.runRecordId = runRecordId;
        }

        @Override
        public void close() {
            MDC.remove(StudioConstants.MDC_RUN_LOG_ID);
            MDC.remove(StudioConstants.MDC_RUN_LOG_PATH);
            activeLogs.remove(runRecordId);
            FileAppender<ILoggingEvent> appender = appenders.remove(runRecordId);
            if (appender != null) {
                rootLogger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    public static final class RunLogStorageResult {
        private final long sizeBytes;
        private final String storageType;
        private final String bucket;
        private final String objectKey;
        private final String status;
        private final String errorSummary;
        private final int chunkCount;

        private RunLogStorageResult(long sizeBytes,
                                    String storageType,
                                    String bucket,
                                    String objectKey,
                                    String status,
                                    String errorSummary) {
            this(sizeBytes, storageType, bucket, objectKey, status, errorSummary,
                    objectKey == null ? 0 : 1);
        }

        private RunLogStorageResult(long sizeBytes,
                                    String storageType,
                                    String bucket,
                                    String objectKey,
                                    String status,
                                    String errorSummary,
                                    int chunkCount) {
            this.sizeBytes = sizeBytes;
            this.storageType = storageType;
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.status = status;
            this.errorSummary = errorSummary;
            this.chunkCount = Math.max(0, chunkCount);
        }

        public static RunLogStorageResult local(long sizeBytes) {
            return new RunLogStorageResult(sizeBytes, RunLogStorageService.STORAGE_LOCAL, null, null, "AVAILABLE", null);
        }

        public static RunLogStorageResult local(long sizeBytes, int chunkCount) {
            return new RunLogStorageResult(sizeBytes, RunLogStorageService.STORAGE_LOCAL, null, null,
                    "AVAILABLE", null, chunkCount);
        }

        public static RunLogStorageResult objectStorage(long sizeBytes, String bucket, String objectKey) {
            return objectStorage(sizeBytes, bucket, objectKey, "AVAILABLE");
        }

        public static RunLogStorageResult objectStorage(long sizeBytes, String bucket, String objectKey, String status) {
            return new RunLogStorageResult(sizeBytes, RunLogStorageService.STORAGE_OBJECT, bucket, objectKey,
                    status == null || status.trim().isEmpty() ? "AVAILABLE" : status.trim(), null);
        }

        public static RunLogStorageResult objectStorage(long sizeBytes, String bucket, String objectKey,
                                                        String status, int chunkCount) {
            return new RunLogStorageResult(sizeBytes, RunLogStorageService.STORAGE_OBJECT, bucket, objectKey,
                    status == null || status.trim().isEmpty() ? "AVAILABLE" : status.trim(), null, chunkCount);
        }

        public static RunLogStorageResult failed(long sizeBytes, String errorSummary) {
            return new RunLogStorageResult(sizeBytes, RunLogStorageService.STORAGE_OBJECT, null, null, "FAILED", errorSummary);
        }

        public static RunLogStorageResult failed(long sizeBytes, String errorSummary, int chunkCount) {
            return new RunLogStorageResult(sizeBytes, RunLogStorageService.STORAGE_OBJECT, null, null,
                    "FAILED", errorSummary, chunkCount);
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public String getStorageType() {
            return storageType;
        }

        public String getBucket() {
            return bucket;
        }

        public String getObjectKey() {
            return objectKey;
        }

        public String getStatus() {
            return status;
        }

        public String getErrorSummary() {
            return errorSummary;
        }

        public int getChunkCount() {
            return chunkCount;
        }
    }

    public static final class PreparedRunLog {
        private final Long runRecordId;
        private final String relativePath;
        private final Path absolutePath;
        private final String charset;
        private final boolean streaming;
        private final Long collectionTaskId;
        private final Long streamRunId;
        private final Long streamAttemptId;
        private final String tenantId;
        private final Long projectId;

        public PreparedRunLog(Long runRecordId, String relativePath, Path absolutePath, String charset) {
            this(runRecordId, relativePath, absolutePath, charset, false,
                    null, null, null, null, null);
        }

        public PreparedRunLog(Long runRecordId,
                              String relativePath,
                              Path absolutePath,
                              String charset,
                              boolean streaming,
                              Long collectionTaskId,
                              Long streamRunId,
                              Long streamAttemptId,
                              String tenantId,
                              Long projectId) {
            this.runRecordId = runRecordId;
            this.relativePath = relativePath;
            this.absolutePath = absolutePath;
            this.charset = charset;
            this.streaming = streaming;
            this.collectionTaskId = collectionTaskId;
            this.streamRunId = streamRunId;
            this.streamAttemptId = streamAttemptId;
            this.tenantId = tenantId;
            this.projectId = projectId;
        }

        public Long getRunRecordId() {
            return runRecordId;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public Path getAbsolutePath() {
            return absolutePath;
        }

        public String getCharset() {
            return charset;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public Long getCollectionTaskId() {
            return collectionTaskId;
        }

        public Long getStreamRunId() {
            return streamRunId;
        }

        public Long getStreamAttemptId() {
            return streamAttemptId;
        }

        public String getTenantId() {
            return tenantId;
        }

        public Long getProjectId() {
            return projectId;
        }
    }

    private static final class ChunkUploadState {
        private final String checksum;
        private final boolean uploaded;

        private ChunkUploadState(String checksum, boolean uploaded) {
            this.checksum = checksum;
            this.uploaded = uploaded;
        }
    }
}
