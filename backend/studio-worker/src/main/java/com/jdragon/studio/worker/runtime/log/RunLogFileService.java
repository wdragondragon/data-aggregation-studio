package com.jdragon.studio.worker.runtime.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.util.StudioPathUtils;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RunLogFileService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final int DEFAULT_TAIL_BYTES = 64 * 1024;

    private final StudioPlatformProperties properties;
    private final LoggerContext loggerContext;
    private final Logger rootLogger;
    private final Map<Long, FileAppender<ILoggingEvent>> appenders = new ConcurrentHashMap<Long, FileAppender<ILoggingEvent>>();

    public RunLogFileService(StudioPlatformProperties properties) {
        this.properties = properties;
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
        MDC.put(StudioConstants.MDC_RUN_LOG_ID, String.valueOf(prepared.getRunRecordId()));
        MDC.put(StudioConstants.MDC_RUN_LOG_PATH, prepared.getRelativePath());
        return new RunLogScope(prepared.getRunRecordId());
    }

    public RunLogView readTail(RunRecordEntity entity, Integer maxBytes) {
        return readLog(entity, maxBytes == null || maxBytes <= 0 ? DEFAULT_TAIL_BYTES : maxBytes, false);
    }

    public RunLogView readFull(RunRecordEntity entity) {
        return readLog(entity, Integer.MAX_VALUE, true);
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

    private RunLogView readLog(RunRecordEntity entity, int maxBytes, boolean full) {
        Path path = resolveLogPath(entity.getLogFilePath());
        if (!Files.exists(path)) {
            throw new IllegalStateException("Run log file not found for runRecordId=" + entity.getId());
        }
        try {
            long size = Files.size(path);
            byte[] bytes = full ? Files.readAllBytes(path) : readTailBytes(path, maxBytes);
            RunLogView view = new RunLogView();
            view.setRunRecordId(entity.getId());
            view.setCharset(entity.getLogCharset() == null ? DEFAULT_CHARSET.name() : entity.getLogCharset());
            view.setContentType("text/plain;charset=" + view.getCharset());
            view.setContent(new String(bytes, Charset.forName(view.getCharset())));
            view.setSizeBytes(size);
            view.setTruncated(!full && size > bytes.length);
            view.setUpdatedAt(resolveUpdatedAt(path));
            view.setDownloadName(path.getFileName().toString());
            view.setHistoricalFallback(false);
            return view;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read run log file for runRecordId=" + entity.getId(), e);
        }
    }

    private byte[] readTailBytes(Path path, int maxBytes) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long size = file.length();
            if (size <= maxBytes) {
                byte[] bytes = new byte[(int) size];
                file.readFully(bytes);
                return bytes;
            }
            byte[] bytes = new byte[maxBytes];
            file.seek(size - maxBytes);
            file.readFully(bytes);
            return bytes;
        }
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
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
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
            FileAppender<ILoggingEvent> appender = appenders.remove(runRecordId);
            if (appender != null) {
                rootLogger.detachAppender(appender);
                appender.stop();
            }
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
