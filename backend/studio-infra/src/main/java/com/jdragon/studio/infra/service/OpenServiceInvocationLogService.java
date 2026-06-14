package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DataIngestionAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionAccessLogEntity;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class OpenServiceInvocationLogService {

    public static final String DOMAIN_PROTOCOL_CONVERSIONS = "protocol-conversions";
    public static final String DOMAIN_DATA_SERVICES = "data-services";
    public static final String DOMAIN_DATA_INGESTION_SERVICES = "data-ingestion-services";

    public static final String ARCHIVE_AVAILABLE = "AVAILABLE";
    public static final String ARCHIVE_DISABLED = "DISABLED";
    public static final String ARCHIVE_SKIPPED = "SKIPPED";
    public static final String ARCHIVE_FAILED = "FAILED";

    private static final DateTimeFormatter DATE_FOLDER_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String DEFAULT_CHARSET = StandardCharsets.UTF_8.name();

    private final StudioPlatformProperties properties;
    private final RunLogStorageService runLogStorageService;
    private final DataServiceAccessLogMapper dataServiceAccessLogMapper;
    private final DataIngestionAccessLogMapper dataIngestionAccessLogMapper;
    private final ProtocolConversionAccessLogMapper protocolConversionAccessLogMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final ObjectMapper objectMapper;

    public OpenServiceInvocationLogService(StudioPlatformProperties properties,
                                           RunLogStorageService runLogStorageService,
                                           DataServiceAccessLogMapper dataServiceAccessLogMapper,
                                           DataIngestionAccessLogMapper dataIngestionAccessLogMapper,
                                           ProtocolConversionAccessLogMapper protocolConversionAccessLogMapper,
                                           StudioSecurityService securityService,
                                           ProjectResourceAccessService projectResourceAccessService,
                                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.runLogStorageService = runLogStorageService;
        this.dataServiceAccessLogMapper = dataServiceAccessLogMapper;
        this.dataIngestionAccessLogMapper = dataIngestionAccessLogMapper;
        this.protocolConversionAccessLogMapper = protocolConversionAccessLogMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.objectMapper = objectMapper;
    }

    public ArchiveResult archive(String domain,
                                 String filePrefix,
                                 String requestId,
                                 LocalDateTime occurredAt,
                                 String content) {
        String safeContent = truncateLog(OpenServiceInvocationLogSupport.sanitizeSensitiveLog(content == null ? "" : content));
        byte[] bytes = safeContent.getBytes(StandardCharsets.UTF_8);
        StudioPlatformProperties.InvocationLogProperties invocationLog = invocationLogProperties();
        if (!invocationLog.isEnabled()) {
            return ArchiveResult.disabled(bytes.length);
        }
        String storageType = storageType(invocationLog);
        if (!RunLogStorageService.STORAGE_OBJECT.equalsIgnoreCase(storageType)) {
            return ArchiveResult.skipped(storageType, bytes.length);
        }
        if (!objectStorageBucketConfigured()) {
            return ArchiveResult.skipped(storageType, bytes.length);
        }
        try {
            String bucket = runLogStorageService.resolveBucket();
            String objectKey = buildObjectKey(domain, filePrefix, requestId, occurredAt);
            runLogStorageService.upload(bucket, objectKey, bytes, "text/plain;charset=" + DEFAULT_CHARSET);
            return ArchiveResult.objectStorage(bytes.length, bucket, objectKey);
        } catch (RuntimeException ex) {
            return ArchiveResult.failed(bytes.length, rootMessage(ex));
        }
    }

    public RunLogView viewLog(String domain, Long accessLogId, Integer pageNo, Integer pageSizeBytes) {
        return readLog(domain, accessLogId, pageNo, pageSizeBytes, false);
    }

    public RunLogView downloadLog(String domain, Long accessLogId) {
        return readLog(domain, accessLogId, null, null, true);
    }

    public String previewValue(Object value) {
        String text;
        if (value == null) {
            text = "";
        } else if (value instanceof String) {
            text = String.valueOf(value);
        } else {
            try {
                text = objectMapper.writeValueAsString(value);
            } catch (Exception ex) {
                text = String.valueOf(value);
            }
        }
        return truncateBody(OpenServiceInvocationLogSupport.sanitizeSensitiveLog(text));
    }

    public Map<String, Object> sanitizeHeaders(Map<String, Object> headers) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (headers == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            result.put(entry.getKey(), isSensitiveName(entry.getKey()) ? "******" : entry.getValue());
        }
        return result;
    }

    public String summaryLog(String title, Map<String, Object> values) {
        StringBuilder builder = new StringBuilder(1024);
        if (StringUtils.hasText(title)) {
            builder.append(title.trim()).append(System.lineSeparator());
            builder.append(repeat('-', Math.min(80, title.trim().length()))).append(System.lineSeparator());
        }
        if (values != null) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                appendLine(builder, entry.getKey(), entry.getValue());
            }
        }
        return builder.toString();
    }

    public void appendSection(StringBuilder builder, String title, String content) {
        if (builder == null) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(System.lineSeparator());
        }
        builder.append(title).append(System.lineSeparator());
        builder.append(repeat('-', Math.min(80, title == null ? 0 : title.length()))).append(System.lineSeparator());
        if (StringUtils.hasText(content)) {
            builder.append(content.trim()).append(System.lineSeparator());
        } else {
            builder.append("-").append(System.lineSeparator());
        }
    }

    public void appendLine(StringBuilder builder, String key, Object value) {
        if (builder == null || !StringUtils.hasText(key)) {
            return;
        }
        builder.append('[').append(key).append("] ");
        builder.append(value == null || !StringUtils.hasText(String.valueOf(value)) ? "-" : String.valueOf(value).trim());
        builder.append(System.lineSeparator());
    }

    private RunLogView readLog(String domain, Long accessLogId, Integer pageNo, Integer pageSizeBytes, boolean full) {
        if (accessLogId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Access log id is required");
        }
        InvocationLogPointer pointer = requirePointer(domain, accessLogId);
        if (RunLogStorageService.STORAGE_OBJECT.equalsIgnoreCase(pointer.logStorageType)
                && StringUtils.hasText(pointer.logObjectBucket)
                && StringUtils.hasText(pointer.logObjectKey)
                && ARCHIVE_AVAILABLE.equalsIgnoreCase(pointer.logArchiveStatus)) {
            return runLogStorageService.readObjectLog(pointer.id,
                    pointer.logObjectBucket,
                    pointer.logObjectKey,
                    pointer.logCharset,
                    pointer.downloadName,
                    pointer.updatedAt,
                    pageNo,
                    pageSizeBytes,
                    full);
        }
        return fallback(pointer, full);
    }

    private InvocationLogPointer requirePointer(String domain, Long accessLogId) {
        String normalizedDomain = normalizeDomain(domain);
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Access log not found: " + accessLogId);
        }
        if (DOMAIN_DATA_SERVICES.equals(normalizedDomain)) {
            DataServiceAccessLogEntity entity = dataServiceAccessLogMapper.selectById(accessLogId);
            if (entity == null || !allowed(entity.getTenantId(), entity.getProjectId(), currentProjectId)) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Access log not found: " + accessLogId);
            }
            return pointer(normalizedDomain, entity.getId(), entity.getUpdatedAt(), entity.getRequestId(), entity.getSystemLog(),
                    entity.getLogStorageType(), entity.getLogObjectBucket(), entity.getLogObjectKey(), entity.getLogSizeBytes(),
                    entity.getLogCharset(), entity.getLogArchiveStatus(), entity.getLogArchiveError());
        }
        if (DOMAIN_DATA_INGESTION_SERVICES.equals(normalizedDomain)) {
            DataIngestionAccessLogEntity entity = dataIngestionAccessLogMapper.selectById(accessLogId);
            if (entity == null || !allowed(entity.getTenantId(), entity.getProjectId(), currentProjectId)) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Access log not found: " + accessLogId);
            }
            return pointer(normalizedDomain, entity.getId(), entity.getUpdatedAt(), entity.getRequestId(), entity.getSystemLog(),
                    entity.getLogStorageType(), entity.getLogObjectBucket(), entity.getLogObjectKey(), entity.getLogSizeBytes(),
                    entity.getLogCharset(), entity.getLogArchiveStatus(), entity.getLogArchiveError());
        }
        if (DOMAIN_PROTOCOL_CONVERSIONS.equals(normalizedDomain)) {
            ProtocolConversionAccessLogEntity entity = protocolConversionAccessLogMapper.selectById(accessLogId);
            if (entity == null || !allowed(entity.getTenantId(), entity.getProjectId(), currentProjectId)) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Access log not found: " + accessLogId);
            }
            return pointer(normalizedDomain, entity.getId(), entity.getUpdatedAt(), entity.getRequestId(), entity.getSystemLog(),
                    entity.getLogStorageType(), entity.getLogObjectBucket(), entity.getLogObjectKey(), entity.getLogSizeBytes(),
                    entity.getLogCharset(), entity.getLogArchiveStatus(), entity.getLogArchiveError());
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported invocation log domain: " + domain);
    }

    private InvocationLogPointer pointer(String domain,
                                         Long id,
                                         LocalDateTime updatedAt,
                                         String requestId,
                                         String systemLog,
                                         String logStorageType,
                                         String logObjectBucket,
                                         String logObjectKey,
                                         Long logSizeBytes,
                                         String logCharset,
                                         String logArchiveStatus,
                                         String logArchiveError) {
        InvocationLogPointer pointer = new InvocationLogPointer();
        pointer.domain = domain;
        pointer.id = id;
        pointer.updatedAt = updatedAt;
        pointer.requestId = requestId;
        pointer.systemLog = systemLog;
        pointer.logStorageType = logStorageType;
        pointer.logObjectBucket = logObjectBucket;
        pointer.logObjectKey = logObjectKey;
        pointer.logSizeBytes = logSizeBytes;
        pointer.logCharset = logCharset;
        pointer.logArchiveStatus = logArchiveStatus;
        pointer.logArchiveError = logArchiveError;
        pointer.downloadName = downloadName(domain, requestId, id);
        return pointer;
    }

    private boolean allowed(String tenantId, Long projectId, Long currentProjectId) {
        String currentTenantId = securityService.currentTenantId();
        return currentTenantId != null
                && currentTenantId.equals(tenantId)
                && currentProjectId != null
                && projectId != null
                && currentProjectId.longValue() == projectId.longValue();
    }

    private RunLogView fallback(InvocationLogPointer pointer, boolean full) {
        StringBuilder content = new StringBuilder();
        if (StringUtils.hasText(pointer.systemLog)) {
            content.append(pointer.systemLog.trim()).append(System.lineSeparator());
        }
        if (StringUtils.hasText(pointer.logArchiveError)) {
            if (content.length() > 0) {
                content.append(System.lineSeparator());
            }
            content.append("[archiveError] ").append(pointer.logArchiveError.trim()).append(System.lineSeparator());
        }
        if (content.length() == 0) {
            content.append("No archived invocation log is available.").append(System.lineSeparator());
        }
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        RunLogView view = new RunLogView();
        view.setRunRecordId(pointer.id);
        view.setContent(content.toString());
        view.setContentType("text/plain;charset=" + DEFAULT_CHARSET);
        view.setCharset(DEFAULT_CHARSET);
        view.setDownloadName(pointer.downloadName);
        view.setHistoricalFallback(true);
        view.setPaged(false);
        view.setTruncated(false);
        view.setSizeBytes(Long.valueOf(bytes.length));
        view.setUpdatedAt(pointer.updatedAt == null ? LocalDateTime.now() : pointer.updatedAt);
        view.setPageNo(Integer.valueOf(1));
        view.setTotalPages(Integer.valueOf(1));
        view.setPageSizeBytes(Integer.valueOf(full ? Integer.MAX_VALUE : bytes.length));
        return view;
    }

    private String buildObjectKey(String domain, String filePrefix, String requestId, LocalDateTime occurredAt) {
        String dateFolder = (occurredAt == null ? LocalDateTime.now() : occurredAt).format(DATE_FOLDER_FORMATTER);
        String safePrefix = StringUtils.hasText(filePrefix) ? filePrefix.trim() : "invocation";
        String safeRequestId = StringUtils.hasText(requestId) ? requestId.trim() : String.valueOf(System.currentTimeMillis());
        String relativePath = normalizeDomain(domain) + "/" + dateFolder + "/" + safePrefix + "-" + safeRequestId + ".log";
        String prefix = invocationLogProperties().getObjectPrefix();
        String normalizedPrefix = StringUtils.hasText(prefix) ? trimSlashes(prefix.trim()) : "";
        return normalizedPrefix.isEmpty() ? relativePath : normalizedPrefix + "/" + relativePath;
    }

    private String normalizeDomain(String domain) {
        String normalized = StringUtils.hasText(domain) ? domain.trim().toLowerCase(Locale.ROOT) : "";
        if (DOMAIN_PROTOCOL_CONVERSIONS.equals(normalized)
                || DOMAIN_DATA_SERVICES.equals(normalized)
                || DOMAIN_DATA_INGESTION_SERVICES.equals(normalized)) {
            return normalized;
        }
        return normalized;
    }

    private String storageType(StudioPlatformProperties.InvocationLogProperties invocationLog) {
        String value = invocationLog == null ? null : invocationLog.getStorageType();
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : runLogStorageService.storageType();
    }

    private boolean objectStorageBucketConfigured() {
        StudioPlatformProperties.RunLogProperties runLog = properties.getRunLog();
        StudioPlatformProperties.ObjectStorageProperties objectStorage = runLog == null ? null : runLog.getObjectStorage();
        return objectStorage != null && StringUtils.hasText(objectStorage.getBucket());
    }

    private StudioPlatformProperties.InvocationLogProperties invocationLogProperties() {
        return properties.getInvocationLog() == null ? new StudioPlatformProperties.InvocationLogProperties() : properties.getInvocationLog();
    }

    private String truncateLog(String value) {
        int max = invocationLogProperties().getMaxLogChars() == null
                ? OpenServiceInvocationLogSupport.MAX_LOG_CHARS
                : Math.max(4096, invocationLogProperties().getMaxLogChars().intValue());
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + System.lineSeparator() + "[log truncated after " + max + " chars]";
    }

    private String truncateBody(String value) {
        int max = invocationLogProperties().getMaxBodyChars() == null
                ? 64 * 1024
                : Math.max(1024, invocationLogProperties().getMaxBodyChars().intValue());
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + System.lineSeparator() + "[body truncated after " + max + " chars]";
    }

    private boolean isSensitiveName(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("secret")
                || normalized.contains("key")
                || normalized.contains("password");
    }

    private String downloadName(String domain, String requestId, Long id) {
        String safeDomain = StringUtils.hasText(domain) ? domain : "invocation";
        String safeId = StringUtils.hasText(requestId) ? requestId : String.valueOf(id == null ? "unknown" : id);
        return safeDomain + "-" + safeId + ".log";
    }

    private String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(Math.max(0, count));
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
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

    @Data
    public static final class ArchiveResult {
        private String logStorageType;
        private String logObjectBucket;
        private String logObjectKey;
        private Long logSizeBytes;
        private String logCharset;
        private String logArchiveStatus;
        private String logArchiveError;

        static ArchiveResult objectStorage(long sizeBytes, String bucket, String objectKey) {
            ArchiveResult result = base(sizeBytes, ARCHIVE_AVAILABLE);
            result.setLogStorageType(RunLogStorageService.STORAGE_OBJECT);
            result.setLogObjectBucket(bucket);
            result.setLogObjectKey(objectKey);
            return result;
        }

        static ArchiveResult disabled(long sizeBytes) {
            return base(sizeBytes, ARCHIVE_DISABLED);
        }

        static ArchiveResult skipped(String storageType, long sizeBytes) {
            ArchiveResult result = base(sizeBytes, ARCHIVE_SKIPPED);
            result.setLogStorageType(storageType);
            return result;
        }

        static ArchiveResult failed(long sizeBytes, String error) {
            ArchiveResult result = base(sizeBytes, ARCHIVE_FAILED);
            result.setLogStorageType(RunLogStorageService.STORAGE_OBJECT);
            result.setLogArchiveError(error);
            return result;
        }

        private static ArchiveResult base(long sizeBytes, String status) {
            ArchiveResult result = new ArchiveResult();
            result.setLogSizeBytes(Long.valueOf(Math.max(0L, sizeBytes)));
            result.setLogCharset(DEFAULT_CHARSET);
            result.setLogArchiveStatus(status);
            return result;
        }
    }

    private static final class InvocationLogPointer {
        private String domain;
        private Long id;
        private LocalDateTime updatedAt;
        private String requestId;
        private String systemLog;
        private String logStorageType;
        private String logObjectBucket;
        private String logObjectKey;
        private Long logSizeBytes;
        private String logCharset;
        private String logArchiveStatus;
        private String logArchiveError;
        private String downloadName;
    }
}
