package com.jdragon.studio.worker.runtime.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
import com.jdragon.studio.commons.util.StudioPathUtils;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.service.RunLogStorageService;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    private final StudioPlatformProperties properties;
    private final RunLogStorageService runLogStorageService;
    private final LoggerContext loggerContext;
    private final Logger rootLogger;
    private final Map<Long, FileAppender<ILoggingEvent>> appenders = new ConcurrentHashMap<Long, FileAppender<ILoggingEvent>>();
    private final Map<Long, PreparedRunLog> activeLogs = new ConcurrentHashMap<Long, PreparedRunLog>();
    private final Map<String, String> lastUploadErrorByPath = new ConcurrentHashMap<String, String>();

    public RunLogFileService(StudioPlatformProperties properties, RunLogStorageService runLogStorageService) {
        this.properties = properties;
        this.runLogStorageService = runLogStorageService;
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
        return uploadLog(prepared, "AVAILABLE");
    }

    public Map<Long, RunLogStorageResult> syncActiveObjectLogs() {
        Map<Long, RunLogStorageResult> results = new LinkedHashMap<Long, RunLogStorageResult>();
        if (!runLogStorageService.objectStorageEnabled()) {
            return results;
        }
        for (PreparedRunLog prepared : activeLogs.values()) {
            if (prepared == null || prepared.getRunRecordId() == null) {
                continue;
            }
            results.put(prepared.getRunRecordId(), uploadLog(prepared, "WRITING"));
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
            byte[] bytes = sanitizedFileBytes(prepared.getAbsolutePath(), prepared.getCharset());
            runLogStorageService.upload(bucket, objectKey, bytes, "text/plain;charset=" + prepared.getCharset());
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
        try {
            long size = Files.size(path);
            int safePageSizeBytes = normalizePageSizeBytes(pageSizeBytes);
            int totalPages = full ? 1 : computeTotalPages(size, safePageSizeBytes);
            int safePageNo = full ? 1 : normalizePageNo(pageNo, totalPages);
            Charset charset = Charset.forName(entity.getLogCharset() == null ? DEFAULT_CHARSET.name() : entity.getLogCharset());
            byte[] bytes = full ? Files.readAllBytes(path) : readPageBytes(path, safePageNo, safePageSizeBytes, charset);
            RunLogView view = new RunLogView();
            view.setRunRecordId(entity.getId());
            view.setCharset(charset.name());
            view.setContentType("text/plain;charset=" + view.getCharset());
            view.setContent(StudioSensitiveLogSanitizer.sanitize(decodeBytes(bytes, charset)));
            view.setSizeBytes(size);
            view.setTruncated(false);
            view.setPaged(!full && totalPages > 1);
            view.setUpdatedAt(resolveUpdatedAt(path));
            view.setDownloadName(path.getFileName().toString());
            view.setHistoricalFallback(false);
            view.setPageNo(Integer.valueOf(safePageNo));
            view.setTotalPages(Integer.valueOf(totalPages));
            view.setPageSizeBytes(Integer.valueOf(full ? Integer.MAX_VALUE : safePageSizeBytes));
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

    private byte[] sanitizedFileBytes(Path path, String charsetName) throws IOException {
        Charset charset = Charset.forName(charsetName == null || charsetName.trim().isEmpty()
                ? DEFAULT_CHARSET.name() : charsetName.trim());
        return StudioSensitiveLogSanitizer.sanitize(Files.readString(path, charset)).getBytes(charset);
    }

    private FileAppender<ILoggingEvent> buildAppender(PreparedRunLog prepared) {
        SanitizingPatternLayoutEncoder encoder = new SanitizingPatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setCharset(DEFAULT_CHARSET);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();

        FileAppender<ILoggingEvent> appender = new FileAppender<ILoggingEvent>();
        appender.setContext(loggerContext);
        appender.setName("RUN_LOG_" + prepared.getRunRecordId());
        appender.setFile(prepared.getAbsolutePath().toString());
        appender.setAppend(true);
        appender.setEncoder(encoder);
        appender.addFilter(new RunLogIdFilter(String.valueOf(prepared.getRunRecordId())));
        appender.start();
        return appender;
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

        private RunLogStorageResult(long sizeBytes,
                                    String storageType,
                                    String bucket,
                                    String objectKey,
                                    String status,
                                    String errorSummary) {
            this.sizeBytes = sizeBytes;
            this.storageType = storageType;
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.status = status;
            this.errorSummary = errorSummary;
        }

        public static RunLogStorageResult local(long sizeBytes) {
            return new RunLogStorageResult(sizeBytes, RunLogStorageService.STORAGE_LOCAL, null, null, "AVAILABLE", null);
        }

        public static RunLogStorageResult objectStorage(long sizeBytes, String bucket, String objectKey) {
            return objectStorage(sizeBytes, bucket, objectKey, "AVAILABLE");
        }

        public static RunLogStorageResult objectStorage(long sizeBytes, String bucket, String objectKey, String status) {
            return new RunLogStorageResult(sizeBytes, RunLogStorageService.STORAGE_OBJECT, bucket, objectKey,
                    status == null || status.trim().isEmpty() ? "AVAILABLE" : status.trim(), null);
        }

        public static RunLogStorageResult failed(long sizeBytes, String errorSummary) {
            return new RunLogStorageResult(sizeBytes, RunLogStorageService.STORAGE_OBJECT, null, null, "FAILED", errorSummary);
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
    }

    public static final class PreparedRunLog {
        private final Long runRecordId;
        private final String relativePath;
        private final Path absolutePath;
        private final String charset;

        public PreparedRunLog(Long runRecordId, String relativePath, Path absolutePath, String charset) {
            this.runRecordId = runRecordId;
            this.relativePath = relativePath;
            this.absolutePath = absolutePath;
            this.charset = charset;
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
    }
}
