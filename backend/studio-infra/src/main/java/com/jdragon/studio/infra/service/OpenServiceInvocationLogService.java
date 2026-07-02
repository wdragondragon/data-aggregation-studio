package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.InvocationLogSectionView;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DataIngestionAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionAccessLogEntity;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OpenServiceInvocationLogService {

    private static final Logger log = LoggerFactory.getLogger(OpenServiceInvocationLogService.class);

    public static final String DOMAIN_PROTOCOL_CONVERSIONS = "protocol-conversions";
    public static final String DOMAIN_DATA_SERVICES = "data-services";
    public static final String DOMAIN_DATA_INGESTION_SERVICES = "data-ingestion-services";

    public static final String ARCHIVE_AVAILABLE = "AVAILABLE";
    public static final String ARCHIVE_DISABLED = "DISABLED";
    public static final String ARCHIVE_SKIPPED = "SKIPPED";
    public static final String ARCHIVE_FAILED = "FAILED";
    public static final String DATA_INGESTION_TARGET_LOG_START_PREFIX = "===== DATA_INGESTION_TARGET_LOG_START ";
    public static final String DATA_INGESTION_TARGET_LOG_END_PREFIX = "===== DATA_INGESTION_TARGET_LOG_END ";
    public static final String DATA_INGESTION_TARGET_LOG_OBJECT_INDEX_TITLE = "Data Ingestion Target Log Object Index";

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
        return archive(domain, filePrefix, requestId, occurredAt, content, null);
    }

    public ArchiveResult archive(String domain,
                                 String filePrefix,
                                 String requestId,
                                 LocalDateTime occurredAt,
                                 String content,
                                 String objectKeyOverride) {
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
            String objectKey = StringUtils.hasText(objectKeyOverride) ? objectKeyOverride.trim() : buildObjectKey(domain, filePrefix, requestId, occurredAt);
            runLogStorageService.upload(bucket, objectKey, bytes, "text/plain;charset=" + DEFAULT_CHARSET);
            return ArchiveResult.objectStorage(bytes.length, bucket, objectKey);
        } catch (RuntimeException ex) {
            return ArchiveResult.failed(bytes.length, rootMessage(ex));
        }
    }

    public String buildArchiveObjectKey(String domain, String filePrefix, String requestId, LocalDateTime occurredAt) {
        return buildObjectKey(domain, filePrefix, requestId, occurredAt);
    }

    public ArchiveResult archiveDataIngestionTargetLog(String mainLogObjectKey, String sectionKey, String content) {
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
        if (!StringUtils.hasText(mainLogObjectKey) || !StringUtils.hasText(sectionKey)) {
            return ArchiveResult.failed(bytes.length, "target log object key metadata is missing");
        }
        try {
            String bucket = runLogStorageService.resolveBucket();
            String objectKey = buildTargetObjectKey(mainLogObjectKey, sectionKey);
            runLogStorageService.upload(bucket, objectKey, bytes, "text/plain;charset=" + DEFAULT_CHARSET);
            return ArchiveResult.objectStorage(bytes.length, bucket, objectKey);
        } catch (RuntimeException ex) {
            return ArchiveResult.failed(bytes.length, rootMessage(ex));
        }
    }

    boolean deleteDataIngestionArchivedObjects(Long accessLogId,
                                               String bucket,
                                               String objectKey,
                                               String charset,
                                               String archiveStatus) {
        InvocationLogPointer pointer = pointer(DOMAIN_DATA_INGESTION_SERVICES,
                accessLogId,
                null,
                null,
                null,
                RunLogStorageService.STORAGE_OBJECT,
                bucket,
                objectKey,
                null,
                charset,
                archiveStatus,
                null);
        try {
            String fullContent = readFullLogContent(pointer);
            List<TargetLogObjectPointer> targetPointers = parseTargetLogObjectPointers(fullContent, bucket);
            for (TargetLogObjectPointer targetPointer : targetPointers) {
                if (targetObjectAvailable(targetPointer)) {
                    runLogStorageService.deleteObject(targetPointer.bucket, targetPointer.objectKey);
                }
            }
            if (archivedObjectAvailable(pointer)) {
                runLogStorageService.deleteObject(bucket, objectKey);
            }
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    boolean deleteArchivedObjectQuietly(ArchiveResult archiveResult) {
        if (archiveResult == null
                || !RunLogStorageService.STORAGE_OBJECT.equalsIgnoreCase(archiveResult.getLogStorageType())
                || !StringUtils.hasText(archiveResult.getLogObjectBucket())
                || !StringUtils.hasText(archiveResult.getLogObjectKey())) {
            return true;
        }
        try {
            runLogStorageService.deleteObject(archiveResult.getLogObjectBucket(), archiveResult.getLogObjectKey());
            return true;
        } catch (RuntimeException ex) {
            log.warn("Failed to delete archived invocation log object {}", archiveResult.getLogObjectKey(), ex);
            return false;
        }
    }

    public RunLogView viewLog(String domain, Long accessLogId, Integer pageNo, Integer pageSizeBytes) {
        return readLog(domain, accessLogId, pageNo, pageSizeBytes, false);
    }

    public RunLogView downloadLog(String domain, Long accessLogId) {
        return readLog(domain, accessLogId, null, null, true);
    }

    public RunLogView viewLogSection(String domain, Long accessLogId, String sectionKey, Integer pageNo, Integer pageSizeBytes) {
        return readLogSection(domain, accessLogId, sectionKey, pageNo, pageSizeBytes, false);
    }

    public RunLogView downloadLogSection(String domain, Long accessLogId, String sectionKey) {
        return readLogSection(domain, accessLogId, sectionKey, null, null, true);
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
        RunLogView view;
        if (archivedObjectAvailable(pointer)) {
            view = runLogStorageService.readObjectLog(pointer.id,
                    pointer.logObjectBucket,
                    pointer.logObjectKey,
                    pointer.logCharset,
                    pointer.downloadName,
                    pointer.updatedAt,
                    pageNo,
                    pageSizeBytes,
                    full);
        } else {
            loadFallbackContent(pointer);
            view = fallback(pointer, full);
        }
        if (DOMAIN_DATA_INGESTION_SERVICES.equals(pointer.domain)) {
            String fullContent = full ? view.getContent() : readFullLogContent(pointer);
            List<TargetLogObjectPointer> targetPointers = parseTargetLogObjectPointers(fullContent, pointer.logObjectBucket);
            if (full && !targetPointers.isEmpty()) {
                String mergedContent = mergeTargetObjectLogs(pointer, fullContent, targetPointers);
                view = textLogView(pointer,
                        mergedContent,
                        pointer.downloadName,
                        !archivedObjectAvailable(pointer),
                        null,
                        null,
                        true);
            }
            view.setSections(sectionViews(fullContent, targetPointers));
        }
        return view;
    }

    private RunLogView readLogSection(String domain, Long accessLogId, String sectionKey, Integer pageNo, Integer pageSizeBytes, boolean full) {
        if (!DOMAIN_DATA_INGESTION_SERVICES.equals(normalizeDomain(domain))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invocation log sections are only supported for data ingestion services");
        }
        if (!StringUtils.hasText(sectionKey)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Log section key is required");
        }
        InvocationLogPointer pointer = requirePointer(domain, accessLogId);
        String fullContent = readFullLogContent(pointer);
        String normalizedSectionKey = sectionKey.trim();
        List<TargetLogObjectPointer> targetPointers = parseTargetLogObjectPointers(fullContent, pointer.logObjectBucket);
        TargetLogObjectPointer targetPointer = findTargetPointer(targetPointers, normalizedSectionKey);
        if (targetObjectAvailable(targetPointer)) {
            try {
                RunLogView objectView = readTargetObjectLog(pointer, targetPointer, pageNo, pageSizeBytes, full);
                objectView.setSections(sectionViews(fullContent, targetPointers));
                return objectView;
            } catch (RuntimeException ex) {
                // Fall through to the inline section fallback stored in the main log object or DB fallback.
            }
        }
        String sectionContent = extractSectionContent(fullContent, normalizedSectionKey);
        RunLogView view = textLogView(pointer,
                sectionContent,
                sectionDownloadName(pointer, normalizedSectionKey),
                !archivedObjectAvailable(pointer),
                pageNo,
                pageSizeBytes,
                full);
        view.setSections(sectionViews(fullContent, targetPointers));
        return view;
    }

    private InvocationLogPointer requirePointer(String domain, Long accessLogId) {
        String normalizedDomain = normalizeDomain(domain);
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Access log not found: " + accessLogId);
        }
        if (DOMAIN_DATA_SERVICES.equals(normalizedDomain)) {
            DataServiceAccessLogEntity entity = dataServiceAccessLogMapper.selectOne(new LambdaQueryWrapper<DataServiceAccessLogEntity>()
                    .select(DataServiceAccessLogEntity::getId,
                            DataServiceAccessLogEntity::getTenantId,
                            DataServiceAccessLogEntity::getProjectId,
                            DataServiceAccessLogEntity::getUpdatedAt,
                            DataServiceAccessLogEntity::getRequestId,
                            DataServiceAccessLogEntity::getLogStorageType,
                            DataServiceAccessLogEntity::getLogObjectBucket,
                            DataServiceAccessLogEntity::getLogObjectKey,
                            DataServiceAccessLogEntity::getLogSizeBytes,
                            DataServiceAccessLogEntity::getLogCharset,
                            DataServiceAccessLogEntity::getLogArchiveStatus)
                    .eq(DataServiceAccessLogEntity::getId, accessLogId)
                    .last("limit 1"));
            if (entity == null || !allowed(entity.getTenantId(), entity.getProjectId(), currentProjectId)) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Access log not found: " + accessLogId);
            }
            return pointer(normalizedDomain, entity.getId(), entity.getUpdatedAt(), entity.getRequestId(), null,
                    entity.getLogStorageType(), entity.getLogObjectBucket(), entity.getLogObjectKey(), entity.getLogSizeBytes(),
                    entity.getLogCharset(), entity.getLogArchiveStatus(), null);
        }
        if (DOMAIN_DATA_INGESTION_SERVICES.equals(normalizedDomain)) {
            DataIngestionAccessLogEntity entity = dataIngestionAccessLogMapper.selectOne(new LambdaQueryWrapper<DataIngestionAccessLogEntity>()
                    .select(DataIngestionAccessLogEntity::getId,
                            DataIngestionAccessLogEntity::getTenantId,
                            DataIngestionAccessLogEntity::getProjectId,
                            DataIngestionAccessLogEntity::getUpdatedAt,
                            DataIngestionAccessLogEntity::getRequestId,
                            DataIngestionAccessLogEntity::getLogStorageType,
                            DataIngestionAccessLogEntity::getLogObjectBucket,
                            DataIngestionAccessLogEntity::getLogObjectKey,
                            DataIngestionAccessLogEntity::getLogSizeBytes,
                            DataIngestionAccessLogEntity::getLogCharset,
                            DataIngestionAccessLogEntity::getLogArchiveStatus)
                    .eq(DataIngestionAccessLogEntity::getId, accessLogId)
                    .last("limit 1"));
            if (entity == null || !allowed(entity.getTenantId(), entity.getProjectId(), currentProjectId)) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Access log not found: " + accessLogId);
            }
            return pointer(normalizedDomain, entity.getId(), entity.getUpdatedAt(), entity.getRequestId(), null,
                    entity.getLogStorageType(), entity.getLogObjectBucket(), entity.getLogObjectKey(), entity.getLogSizeBytes(),
                    entity.getLogCharset(), entity.getLogArchiveStatus(), null);
        }
        if (DOMAIN_PROTOCOL_CONVERSIONS.equals(normalizedDomain)) {
            ProtocolConversionAccessLogEntity entity = protocolConversionAccessLogMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionAccessLogEntity>()
                    .select(ProtocolConversionAccessLogEntity::getId,
                            ProtocolConversionAccessLogEntity::getTenantId,
                            ProtocolConversionAccessLogEntity::getProjectId,
                            ProtocolConversionAccessLogEntity::getUpdatedAt,
                            ProtocolConversionAccessLogEntity::getRequestId,
                            ProtocolConversionAccessLogEntity::getLogStorageType,
                            ProtocolConversionAccessLogEntity::getLogObjectBucket,
                            ProtocolConversionAccessLogEntity::getLogObjectKey,
                            ProtocolConversionAccessLogEntity::getLogSizeBytes,
                            ProtocolConversionAccessLogEntity::getLogCharset,
                            ProtocolConversionAccessLogEntity::getLogArchiveStatus)
                    .eq(ProtocolConversionAccessLogEntity::getId, accessLogId)
                    .last("limit 1"));
            if (entity == null || !allowed(entity.getTenantId(), entity.getProjectId(), currentProjectId)) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Access log not found: " + accessLogId);
            }
            return pointer(normalizedDomain, entity.getId(), entity.getUpdatedAt(), entity.getRequestId(), null,
                    entity.getLogStorageType(), entity.getLogObjectBucket(), entity.getLogObjectKey(), entity.getLogSizeBytes(),
                    entity.getLogCharset(), entity.getLogArchiveStatus(), null);
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

    private boolean archivedObjectAvailable(InvocationLogPointer pointer) {
        return pointer != null
                && RunLogStorageService.STORAGE_OBJECT.equalsIgnoreCase(pointer.logStorageType)
                && StringUtils.hasText(pointer.logObjectBucket)
                && StringUtils.hasText(pointer.logObjectKey)
                && ARCHIVE_AVAILABLE.equalsIgnoreCase(pointer.logArchiveStatus);
    }

    private void loadFallbackContent(InvocationLogPointer pointer) {
        if (pointer == null || pointer.id == null) {
            return;
        }
        if (StringUtils.hasText(pointer.systemLog) || StringUtils.hasText(pointer.logArchiveError)) {
            return;
        }
        if (DOMAIN_DATA_SERVICES.equals(pointer.domain)) {
            DataServiceAccessLogEntity entity = dataServiceAccessLogMapper.selectOne(new LambdaQueryWrapper<DataServiceAccessLogEntity>()
                    .select(DataServiceAccessLogEntity::getUpdatedAt,
                            DataServiceAccessLogEntity::getSystemLog,
                            DataServiceAccessLogEntity::getLogArchiveError)
                    .eq(DataServiceAccessLogEntity::getId, pointer.id)
                    .last("limit 1"));
            if (entity != null) {
                applyFallbackContent(pointer, entity.getUpdatedAt(), entity.getSystemLog(), entity.getLogArchiveError());
            }
            return;
        }
        if (DOMAIN_DATA_INGESTION_SERVICES.equals(pointer.domain)) {
            DataIngestionAccessLogEntity entity = dataIngestionAccessLogMapper.selectOne(new LambdaQueryWrapper<DataIngestionAccessLogEntity>()
                    .select(DataIngestionAccessLogEntity::getUpdatedAt,
                            DataIngestionAccessLogEntity::getSystemLog,
                            DataIngestionAccessLogEntity::getLogArchiveError)
                    .eq(DataIngestionAccessLogEntity::getId, pointer.id)
                    .last("limit 1"));
            if (entity != null) {
                applyFallbackContent(pointer, entity.getUpdatedAt(), entity.getSystemLog(), entity.getLogArchiveError());
            }
            return;
        }
        if (DOMAIN_PROTOCOL_CONVERSIONS.equals(pointer.domain)) {
            ProtocolConversionAccessLogEntity entity = protocolConversionAccessLogMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionAccessLogEntity>()
                    .select(ProtocolConversionAccessLogEntity::getUpdatedAt,
                            ProtocolConversionAccessLogEntity::getSystemLog,
                            ProtocolConversionAccessLogEntity::getLogArchiveError)
                    .eq(ProtocolConversionAccessLogEntity::getId, pointer.id)
                    .last("limit 1"));
            if (entity != null) {
                applyFallbackContent(pointer, entity.getUpdatedAt(), entity.getSystemLog(), entity.getLogArchiveError());
            }
        }
    }

    private void applyFallbackContent(InvocationLogPointer pointer,
                                      LocalDateTime updatedAt,
                                      String systemLog,
                                      String logArchiveError) {
        pointer.systemLog = systemLog;
        pointer.logArchiveError = logArchiveError;
        if (pointer.updatedAt == null && updatedAt != null) {
            pointer.updatedAt = updatedAt;
        }
    }

    private RunLogView fallback(InvocationLogPointer pointer, boolean full) {
        String contentText = fallbackContent(pointer);
        byte[] bytes = contentText.getBytes(StandardCharsets.UTF_8);
        RunLogView view = new RunLogView();
        view.setRunRecordId(pointer.id);
        view.setContent(contentText);
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

    private String fallbackContent(InvocationLogPointer pointer) {
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
        return content.toString();
    }

    private String readFullLogContent(InvocationLogPointer pointer) {
        if (archivedObjectAvailable(pointer)) {
            RunLogView log = runLogStorageService.readObjectLog(pointer.id,
                    pointer.logObjectBucket,
                    pointer.logObjectKey,
                    pointer.logCharset,
                    pointer.downloadName,
                    pointer.updatedAt,
                    null,
                    null,
                    true);
            return log.getContent() == null ? "" : log.getContent();
        }
        loadFallbackContent(pointer);
        return fallbackContent(pointer);
    }

    private RunLogView textLogView(InvocationLogPointer pointer,
                                   String content,
                                   String downloadName,
                                   boolean historicalFallback,
                                   Integer pageNo,
                                   Integer pageSizeBytes,
                                   boolean full) {
        String safeContent = content == null ? "" : content;
        byte[] bytes = safeContent.getBytes(StandardCharsets.UTF_8);
        int safePageSize = normalizePageSizeBytes(pageSizeBytes);
        int totalPages = full ? 1 : computeTotalPages(safeContent.length(), safePageSize);
        int safePageNo = full ? 1 : normalizePageNo(pageNo, totalPages);
        String pageContent = full ? safeContent : sliceString(safeContent, safePageNo, safePageSize);
        RunLogView view = new RunLogView();
        view.setRunRecordId(pointer.id);
        view.setContent(pageContent);
        view.setContentType("text/plain;charset=" + DEFAULT_CHARSET);
        view.setCharset(DEFAULT_CHARSET);
        view.setDownloadName(downloadName);
        view.setHistoricalFallback(historicalFallback);
        view.setPaged(!full && totalPages > 1);
        view.setTruncated(false);
        view.setSizeBytes(Long.valueOf(bytes.length));
        view.setUpdatedAt(pointer.updatedAt == null ? LocalDateTime.now() : pointer.updatedAt);
        view.setPageNo(Integer.valueOf(safePageNo));
        view.setTotalPages(Integer.valueOf(totalPages));
        view.setPageSizeBytes(Integer.valueOf(full ? Integer.MAX_VALUE : safePageSize));
        return view;
    }

    private List<InvocationLogSectionView> sectionViews(String fullContent, List<TargetLogObjectPointer> targetPointers) {
        if (targetPointers != null && !targetPointers.isEmpty()) {
            List<InvocationLogSectionView> sections = new ArrayList<InvocationLogSectionView>(targetPointers.size());
            for (TargetLogObjectPointer pointer : targetPointers) {
                if (pointer != null && pointer.view != null) {
                    sections.add(pointer.view);
                }
            }
            return sections;
        }
        return parseSections(fullContent);
    }

    private RunLogView readTargetObjectLog(InvocationLogPointer pointer,
                                           TargetLogObjectPointer targetPointer,
                                           Integer pageNo,
                                           Integer pageSizeBytes,
                                           boolean full) {
        return runLogStorageService.readObjectLog(pointer.id,
                targetPointer.bucket,
                targetPointer.objectKey,
                pointer.logCharset,
                sectionDownloadName(pointer, targetPointer.sectionKey),
                pointer.updatedAt,
                pageNo,
                pageSizeBytes,
                full);
    }

    private String mergeTargetObjectLogs(InvocationLogPointer pointer,
                                         String mainContent,
                                         List<TargetLogObjectPointer> targetPointers) {
        StringBuilder builder = new StringBuilder(mainContent == null ? "" : mainContent.trim());
        if (targetPointers == null || targetPointers.isEmpty()) {
            return builder.toString();
        }
        for (TargetLogObjectPointer targetPointer : targetPointers) {
            if (targetPointer == null || !StringUtils.hasText(targetPointer.sectionKey)) {
                continue;
            }
            if (containsInlineSection(mainContent, targetPointer.sectionKey)) {
                continue;
            }
            String sectionContent = null;
            if (targetObjectAvailable(targetPointer)) {
                try {
                    RunLogView targetLog = readTargetObjectLog(pointer, targetPointer, null, null, true);
                    sectionContent = targetLog.getContent();
                } catch (RuntimeException ex) {
                    sectionContent = null;
                }
            }
            if (!StringUtils.hasText(sectionContent)) {
                try {
                    sectionContent = extractSectionContent(mainContent, targetPointer.sectionKey);
                } catch (RuntimeException ex) {
                    sectionContent = null;
                }
            }
            if (!StringUtils.hasText(sectionContent)) {
                continue;
            }
            appendInlineTargetSection(builder, targetPointer.sectionKey, sectionContent);
        }
        return builder.toString();
    }

    private void appendInlineTargetSection(StringBuilder builder, String sectionKey, String sectionContent) {
        if (builder.length() > 0) {
            builder.append(System.lineSeparator()).append(System.lineSeparator());
        }
        builder.append(DATA_INGESTION_TARGET_LOG_START_PREFIX)
                .append(sectionKey)
                .append(" =====")
                .append(System.lineSeparator());
        builder.append(sectionContent == null ? "" : sectionContent.trim()).append(System.lineSeparator());
        builder.append(DATA_INGESTION_TARGET_LOG_END_PREFIX)
                .append(sectionKey)
                .append(" =====")
                .append(System.lineSeparator());
    }

    private boolean containsInlineSection(String content, String sectionKey) {
        return StringUtils.hasText(content)
                && StringUtils.hasText(sectionKey)
                && content.contains(DATA_INGESTION_TARGET_LOG_START_PREFIX + sectionKey);
    }

    private boolean targetObjectAvailable(TargetLogObjectPointer targetPointer) {
        return targetPointer != null
                && ARCHIVE_AVAILABLE.equalsIgnoreCase(targetPointer.archiveStatus)
                && StringUtils.hasText(targetPointer.bucket)
                && StringUtils.hasText(targetPointer.objectKey);
    }

    private TargetLogObjectPointer findTargetPointer(List<TargetLogObjectPointer> targetPointers, String sectionKey) {
        if (targetPointers == null || !StringUtils.hasText(sectionKey)) {
            return null;
        }
        for (TargetLogObjectPointer targetPointer : targetPointers) {
            if (targetPointer != null && sectionKey.equals(targetPointer.sectionKey)) {
                return targetPointer;
            }
        }
        return null;
    }

    private List<TargetLogObjectPointer> parseTargetLogObjectPointers(String content, String defaultBucket) {
        List<TargetLogObjectPointer> result = new ArrayList<TargetLogObjectPointer>();
        List<Map<String, String>> blocks = parseTargetLogObjectIndexBlocks(content);
        for (Map<String, String> values : blocks) {
            String sectionKey = values.get("sectionKey");
            if (!StringUtils.hasText(sectionKey)) {
                continue;
            }
            TargetLogObjectPointer pointer = new TargetLogObjectPointer();
            pointer.sectionKey = sectionKey;
            pointer.bucket = firstText(values.get("logObjectBucket"), defaultBucket);
            pointer.objectKey = values.get("logObjectKey");
            pointer.archiveStatus = values.get("archiveStatus");
            pointer.archiveError = values.get("archiveError");
            pointer.view = toSectionView(sectionKey, values, parseLong(values.get("logSizeBytes")));
            result.add(pointer);
        }
        return result;
    }

    private List<Map<String, String>> parseTargetLogObjectIndexBlocks(String content) {
        List<Map<String, String>> blocks = new ArrayList<Map<String, String>>();
        if (!StringUtils.hasText(content)) {
            return blocks;
        }
        String normalizedContent = content.replace("\r\n", "\n").replace('\r', '\n');
        int title = normalizedContent.indexOf(DATA_INGESTION_TARGET_LOG_OBJECT_INDEX_TITLE);
        if (title < 0) {
            return blocks;
        }
        Map<String, String> current = new LinkedHashMap<String, String>();
        String tail = normalizedContent.substring(lineEnd(normalizedContent, title) + 1);
        String[] lines = tail.split("\n", -1);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (!current.isEmpty()) {
                    blocks.add(current);
                    current = new LinkedHashMap<String, String>();
                }
                continue;
            }
            if (isDashLine(line)) {
                continue;
            }
            if (!line.startsWith("[")) {
                if (!current.isEmpty()) {
                    blocks.add(current);
                    current = new LinkedHashMap<String, String>();
                }
                break;
            }
            int end = line.indexOf(']');
            if (end <= 1 || end + 1 >= line.length()) {
                continue;
            }
            String key = line.substring(1, end).trim();
            String value = line.substring(end + 1).trim();
            if (StringUtils.hasText(key)) {
                current.put(key, "-".equals(value) ? null : value);
            }
        }
        if (!current.isEmpty()) {
            blocks.add(current);
        }
        return blocks;
    }

    private boolean isDashLine(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) != '-') {
                return false;
            }
        }
        return true;
    }

    private List<InvocationLogSectionView> parseSections(String content) {
        List<InvocationLogSectionView> sections = new ArrayList<InvocationLogSectionView>();
        if (!StringUtils.hasText(content)) {
            return sections;
        }
        int cursor = 0;
        while (cursor >= 0 && cursor < content.length()) {
            int start = content.indexOf(DATA_INGESTION_TARGET_LOG_START_PREFIX, cursor);
            if (start < 0) {
                break;
            }
            int startLineEnd = lineEnd(content, start);
            String sectionKey = parseMarkerKey(content.substring(start, startLineEnd), DATA_INGESTION_TARGET_LOG_START_PREFIX);
            if (!StringUtils.hasText(sectionKey)) {
                cursor = startLineEnd + 1;
                continue;
            }
            int bodyStart = startLineEnd + 1;
            int end = content.indexOf(DATA_INGESTION_TARGET_LOG_END_PREFIX + sectionKey, bodyStart);
            if (end < 0) {
                break;
            }
            String sectionContent = content.substring(bodyStart, end).trim();
            InvocationLogSectionView section = toSectionView(sectionKey, sectionContent);
            sections.add(section);
            cursor = lineEnd(content, end) + 1;
        }
        return sections;
    }

    private String extractSectionContent(String content, String requestedSectionKey) {
        if (!StringUtils.hasText(content)) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Invocation log section not found: " + requestedSectionKey);
        }
        int cursor = 0;
        while (cursor >= 0 && cursor < content.length()) {
            int start = content.indexOf(DATA_INGESTION_TARGET_LOG_START_PREFIX, cursor);
            if (start < 0) {
                break;
            }
            int startLineEnd = lineEnd(content, start);
            String sectionKey = parseMarkerKey(content.substring(start, startLineEnd), DATA_INGESTION_TARGET_LOG_START_PREFIX);
            int bodyStart = startLineEnd + 1;
            int end = StringUtils.hasText(sectionKey)
                    ? content.indexOf(DATA_INGESTION_TARGET_LOG_END_PREFIX + sectionKey, bodyStart)
                    : -1;
            if (end < 0) {
                break;
            }
            if (requestedSectionKey.equals(sectionKey)) {
                return content.substring(bodyStart, end).trim() + System.lineSeparator();
            }
            cursor = lineEnd(content, end) + 1;
        }
        throw new StudioException(StudioErrorCode.NOT_FOUND, "Invocation log section not found: " + requestedSectionKey);
    }

    private InvocationLogSectionView toSectionView(String sectionKey, String sectionContent) {
        Map<String, String> values = parseSummaryValues(sectionContent);
        return toSectionView(sectionKey, values, Long.valueOf((sectionContent == null ? "" : sectionContent).getBytes(StandardCharsets.UTF_8).length));
    }

    private InvocationLogSectionView toSectionView(String sectionKey, Map<String, String> values, Long sizeBytes) {
        InvocationLogSectionView view = new InvocationLogSectionView();
        view.setSectionKey(sectionKey);
        view.setSourceCode(values.get("sourceCode"));
        view.setSourceName(firstText(values.get("sourceName"), values.get("source")));
        view.setTargetDatasourceName(firstText(values.get("targetDatasourceName"), values.get("targetDatasource")));
        view.setTargetModelName(firstText(values.get("targetModelName"), values.get("targetModel")));
        view.setReceivedCount(parseLong(values.get("receivedCount")));
        view.setSuccessCount(parseLong(values.get("successCount")));
        view.setFailedCount(parseLong(values.get("failedCount")));
        view.setStatus(values.get("status"));
        view.setMessage(values.get("message"));
        view.setJobId(parseLong(values.get("jobId")));
        view.setSizeBytes(sizeBytes);
        view.setArchiveStatus(values.get("archiveStatus"));
        view.setArchiveError(values.get("archiveError"));
        return view;
    }

    private Map<String, String> parseSummaryValues(String content) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (!StringUtils.hasText(content)) {
            return result;
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            if (!line.startsWith("[")) {
                continue;
            }
            int end = line.indexOf(']');
            if (end <= 1 || end + 1 >= line.length()) {
                continue;
            }
            String key = line.substring(1, end).trim();
            String value = line.substring(end + 1).trim();
            if (StringUtils.hasText(key) && !result.containsKey(key)) {
                result.put(key, "-".equals(value) ? null : value);
            }
        }
        return result;
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String parseMarkerKey(String markerLine, String prefix) {
        if (!StringUtils.hasText(markerLine) || !markerLine.startsWith(prefix)) {
            return null;
        }
        String value = markerLine.substring(prefix.length()).trim();
        while (value.endsWith("=")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private int lineEnd(String content, int start) {
        int newline = content.indexOf('\n', start);
        if (newline < 0) {
            return content.length();
        }
        return newline > start && content.charAt(newline - 1) == '\r' ? newline - 1 : newline;
    }

    private int normalizePageSizeBytes(Integer pageSizeBytes) {
        if (pageSizeBytes == null || pageSizeBytes.intValue() <= 0) {
            return 64 * 1024;
        }
        return Math.min(pageSizeBytes.intValue(), 512 * 1024);
    }

    private int computeTotalPages(int length, int pageSize) {
        if (length <= 0) {
            return 1;
        }
        return Math.max(1, (length + pageSize - 1) / pageSize);
    }

    private int normalizePageNo(Integer pageNo, int totalPages) {
        if (pageNo == null || pageNo.intValue() <= 0) {
            return totalPages;
        }
        return Math.min(pageNo.intValue(), Math.max(1, totalPages));
    }

    private String sliceString(String content, int pageNo, int pageSize) {
        if (content == null || content.length() <= pageSize) {
            return content == null ? "" : content;
        }
        int start = Math.max(0, Math.min(content.length(), (pageNo - 1) * pageSize));
        int end = Math.min(content.length(), start + pageSize);
        return content.substring(start, end);
    }

    private String sectionDownloadName(InvocationLogPointer pointer, String sectionKey) {
        String base = pointer == null || !StringUtils.hasText(pointer.downloadName)
                ? "data-ingestion-section.log"
                : pointer.downloadName;
        int dot = base.lastIndexOf('.');
        String safeSectionKey = sectionKey.replaceAll("[^A-Za-z0-9_-]", "_");
        if (dot > 0) {
            return base.substring(0, dot) + "-" + safeSectionKey + base.substring(dot);
        }
        return base + "-" + safeSectionKey + ".log";
    }

    private String buildTargetObjectKey(String mainLogObjectKey, String sectionKey) {
        String safeMainKey = mainLogObjectKey.replace('\\', '/').trim();
        int dot = safeMainKey.lastIndexOf(".log");
        String base = dot > 0 ? safeMainKey.substring(0, dot) : safeMainKey;
        String safeSectionKey = sectionKey.replaceAll("[^A-Za-z0-9_-]", "_");
        return base + "/targets/" + safeSectionKey + ".log";
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
        return runLogStorageService.objectStorageBucketConfigured();
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

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
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

    private static final class TargetLogObjectPointer {
        private String sectionKey;
        private String bucket;
        private String objectKey;
        private String archiveStatus;
        private String archiveError;
        private InvocationLogSectionView view;
    }
}
