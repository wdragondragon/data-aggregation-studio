package com.jdragon.studio.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.RunLogChunkEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.RunService;
import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.infra.service.RuntimeEndpointHttpClient;
import com.jdragon.studio.infra.service.RuntimeEndpointSecurityService;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RunLogProxyService {

    private static final int DEFAULT_PAGE_BYTES = 64 * 1024;
    private static final int MAX_PAGE_BYTES = 512 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int READ_TIMEOUT_MILLIS = 5000;

    private final RunService runService;
    private final WorkerLeaseMapper workerLeaseMapper;
    private final StudioPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final RunLogStorageService runLogStorageService;
    private final RuntimeEndpointSecurityService runtimeEndpointSecurityService;
    private final RuntimeEndpointHttpClient runtimeEndpointHttpClient;
    private final RunLogChunkMapper runLogChunkMapper;

    public RunLogProxyService(RunService runService,
                              WorkerLeaseMapper workerLeaseMapper,
                              StudioPlatformProperties properties,
                              ObjectMapper objectMapper,
                              RunLogStorageService runLogStorageService,
                              RuntimeEndpointSecurityService runtimeEndpointSecurityService,
                              RuntimeEndpointHttpClient runtimeEndpointHttpClient) {
        this(runService, workerLeaseMapper, properties, objectMapper, runLogStorageService,
                runtimeEndpointSecurityService, runtimeEndpointHttpClient, null);
    }

    @Autowired
    public RunLogProxyService(RunService runService,
                              WorkerLeaseMapper workerLeaseMapper,
                              StudioPlatformProperties properties,
                              ObjectMapper objectMapper,
                              RunLogStorageService runLogStorageService,
                              RuntimeEndpointSecurityService runtimeEndpointSecurityService,
                              RuntimeEndpointHttpClient runtimeEndpointHttpClient,
                              RunLogChunkMapper runLogChunkMapper) {
        this.runService = runService;
        this.workerLeaseMapper = workerLeaseMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.runLogStorageService = runLogStorageService;
        this.runtimeEndpointSecurityService = runtimeEndpointSecurityService;
        this.runtimeEndpointHttpClient = runtimeEndpointHttpClient;
        this.runLogChunkMapper = runLogChunkMapper;
    }

    public RunLogView viewLog(Long runRecordId, Integer pageNo, Integer pageSizeBytes) {
        RunRecordEntity entity = runService.getLogPointer(runRecordId);
        if (isObjectStorageLog(entity)) {
            return runLogStorageService.readObjectLog(entity, pageNo, pageSizeBytes, false);
        }
        if (!StringUtils.hasText(entity.getLogFilePath()) || !StringUtils.hasText(entity.getWorkerCode())) {
            return runService.buildHistoricalFallback(runService.getEntity(runRecordId));
        }
        int safePageSizeBytes = normalizePageSize(pageSizeBytes);
        String apiBaseUrl = resolveWorkerApiBaseUrl(entity);
        String url = UriComponentsBuilder.fromUriString(apiBaseUrl)
                .path("/internal/runs/{id}/log")
                .queryParam("pageSizeBytes", safePageSizeBytes)
                .queryParamIfPresent("pageNo", pageNo == null || pageNo.intValue() <= 0
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(pageNo))
                .buildAndExpand(runRecordId)
                .toUriString();
        return exchange(url);
    }

    public RunLogView downloadLog(Long runRecordId) {
        RunRecordEntity entity = runService.getLogPointer(runRecordId);
        if (isObjectStorageLog(entity)) {
            return runLogStorageService.readObjectLog(entity, 1, Integer.MAX_VALUE, true);
        }
        if (!StringUtils.hasText(entity.getLogFilePath()) || !StringUtils.hasText(entity.getWorkerCode())) {
            return runService.buildHistoricalFallback(runService.getEntity(runRecordId));
        }
        String apiBaseUrl = resolveWorkerApiBaseUrl(entity);
        String url = UriComponentsBuilder.fromUriString(apiBaseUrl)
                .path("/internal/runs/{id}/log/download")
                .buildAndExpand(runRecordId)
                .toUriString();
        return exchange(url);
    }

    /** Reads only the requested streaming log chunk after task and tenant/project checks. */
    public RunLogView viewChunk(Long collectionTaskId,
                                Long chunkId,
                                Integer pageNo,
                                Integer pageSizeBytes) {
        if (runLogChunkMapper == null || collectionTaskId == null || chunkId == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Streaming log chunk not found");
        }
        RunLogChunkEntity chunk = runLogChunkMapper.selectOne(new LambdaQueryWrapper<RunLogChunkEntity>()
                .eq(RunLogChunkEntity::getId, chunkId)
                .eq(RunLogChunkEntity::getCollectionTaskId, collectionTaskId)
                .last("limit 1"));
        if (chunk == null || chunk.getRunRecordId() == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Streaming log chunk not found");
        }
        RunRecordEntity record = runService.getLogPointer(chunk.getRunRecordId());
        if (record.getCollectionTaskId() == null || !collectionTaskId.equals(record.getCollectionTaskId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Streaming log chunk not found");
        }
        if (record.getTenantId() != null && !record.getTenantId().equals(chunk.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Streaming log chunk not found");
        }
        if (record.getProjectId() != null && !record.getProjectId().equals(chunk.getProjectId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Streaming log chunk not found");
        }
        if (StringUtils.hasText(chunk.getObjectBucket()) && StringUtils.hasText(chunk.getObjectKey())) {
            return runLogStorageService.readObjectChunk(record, chunk, pageNo, pageSizeBytes);
        }
        if (!StringUtils.hasText(record.getLogFilePath()) || !StringUtils.hasText(record.getWorkerCode())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Streaming log chunk is not available");
        }
        String apiBaseUrl = resolveWorkerApiBaseUrl(record);
        String url = UriComponentsBuilder.fromUriString(apiBaseUrl)
                .path("/internal/runs/chunks/{chunkId}/preview")
                .queryParam("pageSizeBytes", normalizePageSize(pageSizeBytes))
                .queryParamIfPresent("pageNo", pageNo == null || pageNo.intValue() <= 0
                        ? java.util.Optional.empty() : java.util.Optional.of(pageNo))
                .buildAndExpand(chunkId)
                .toUriString();
        return exchange(url);
    }

    /** Streams a ZIP archive through the owning Worker or object storage. */
    public void streamArchive(Long runRecordId, OutputStream output) {
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        RunRecordEntity entity = runService.getLogPointer(runRecordId);
        if (isObjectStorageLog(entity)) {
            runLogStorageService.streamObjectLogArchive(entity, output);
            return;
        }
        if (!StringUtils.hasText(entity.getLogFilePath()) || !StringUtils.hasText(entity.getWorkerCode())) {
            RunLogView fallback = runService.buildHistoricalFallback(runService.getEntity(runRecordId));
            try {
                java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output);
                zip.putNextEntry(new java.util.zip.ZipEntry("run-" + runRecordId + ".log"));
                byte[] bytes = (fallback.getContent() == null ? "" : fallback.getContent())
                        .getBytes(StandardCharsets.UTF_8);
                zip.write(bytes);
                zip.closeEntry();
                zip.finish();
                zip.flush();
                return;
            } catch (java.io.IOException failure) {
                throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                        "Failed to stream historical run log archive");
            }
        }
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target;
        try {
            target = runtimeEndpointSecurityService.validateRequestTarget(
                    UriComponentsBuilder.fromUriString(resolveWorkerApiBaseUrl(entity))
                            .path("/internal/runs/{id}/log/archive")
                            .buildAndExpand(runRecordId).toUriString());
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                    "Worker log endpoint is not allowed by runtime endpoint policy");
        }
        try {
            Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
            addHeader(headers, StudioConstants.INTERNAL_API_TOKEN_HEADER, properties.getInternalApiToken());
            addHeader(headers, "Accept", "application/zip");
            RuntimeEndpointHttpClient.StreamingResponse response = runtimeEndpointHttpClient.executeStreaming(
                    target, "GET", headers, null, CONNECT_TIMEOUT_MILLIS, 300000, output);
            if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) {
                throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                        unauthenticatedRuntimeResponseMessage(null));
            }
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                        "Worker returned an invalid run log archive status");
            }
        } catch (StudioException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                    "Worker log archive endpoint is unavailable");
        }
    }

    private RunLogView exchange(String url) {
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target;
        try {
            target = runtimeEndpointSecurityService.validateRequestTarget(url);
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                    "Worker log endpoint is not allowed by runtime endpoint policy");
        }

        RuntimeEndpointHttpClient.Response response;
        try {
            Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
            addHeader(headers, StudioConstants.INTERNAL_API_TOKEN_HEADER, properties.getInternalApiToken());
            addHeader(headers, "Accept", "application/json");
            response = runtimeEndpointHttpClient.execute(
                    target, "GET", headers, null,
                    CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS,
                    runtimeEndpointSecurityService.maxResponseBytes());
        } catch (RuntimeEndpointSecurityService.ResponseTooLargeException exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Worker run log response exceeds the configured maximum size");
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                    "Worker log endpoint is unavailable");
        }
        if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) {
            throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                    unauthenticatedRuntimeResponseMessage(response));
        }

        try {
            String body = new String(response.getBody(), StandardCharsets.UTF_8);
            if (!StringUtils.hasText(body)) {
                throw invalidWorkerResponse("Worker returned an empty run log response");
            }
            JsonNode root = objectMapper.readTree(body);
            if (!root.path("success").asBoolean(false)) {
                String code = root.path("code").asText(null);
                String message = root.path("message").asText("Unknown error");
                throw new StudioException(StringUtils.hasText(code) ? code : StudioErrorCode.BUSINESS_ERROR,
                        "Failed to load run log from worker: " + message);
            }
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw invalidWorkerResponse("Worker returned an invalid run log status");
            }
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                throw invalidWorkerResponse("Worker returned no run log data");
            }
            RunLogView log = objectMapper.treeToValue(dataNode, RunLogView.class);
            log.setContent(StudioSensitiveLogSanitizer.sanitize(log.getContent()));
            return log;
        } catch (StudioException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidWorkerResponse("Worker returned an invalid run log response");
        }
    }

    private String resolveWorkerApiBaseUrl(RunRecordEntity runRecord) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime heartbeatCutoff = now.minusSeconds(StudioConstants.WORKER_HEARTBEAT_TIMEOUT_SECONDS);
        LambdaQueryWrapper<WorkerLeaseEntity> query = new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getTenantId, runRecord.getTenantId())
                .eq(WorkerLeaseEntity::getWorkerCode, runRecord.getWorkerCode())
                .eq(WorkerLeaseEntity::getStatus, StudioConstants.WORKER_STATUS_ONLINE)
                .eq(runRecord.getActualClusterId() != null,
                        WorkerLeaseEntity::getRuntimeClusterId, runRecord.getActualClusterId())
                .eq(runRecord.getActualClusterId() == null && StringUtils.hasText(runRecord.getActualClusterCode()),
                        WorkerLeaseEntity::getRuntimeClusterCode, runRecord.getActualClusterCode())
                .eq(StringUtils.hasText(runRecord.getWorkerInstanceId()),
                        WorkerLeaseEntity::getInstanceId, runRecord.getWorkerInstanceId())
                .eq(StringUtils.hasText(runRecord.getWorkerBootId()),
                        WorkerLeaseEntity::getBootId, runRecord.getWorkerBootId())
                .and(wrapper -> wrapper.gt(WorkerLeaseEntity::getLeaseExpiresAt, now)
                        .or()
                        .ge(WorkerLeaseEntity::getLastHeartbeatAt, heartbeatCutoff))
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt)
                .orderByDesc(WorkerLeaseEntity::getId)
                .last("limit 1");
        WorkerLeaseEntity lease = workerLeaseMapper.selectOne(query);
        if (lease == null || lease.getCapabilitiesJson() == null) {
            throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                    "The Worker instance that owns this local run log is not online");
        }
        Object apiBaseUrl = lease.getCapabilitiesJson().get("apiBaseUrl");
        if (apiBaseUrl == null || String.valueOf(apiBaseUrl).trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                    "The Worker instance that owns this local run log has no API endpoint");
        }
        return String.valueOf(apiBaseUrl).trim();
    }

    private int normalizePageSize(Integer pageSizeBytes) {
        if (pageSizeBytes == null || pageSizeBytes.intValue() <= 0) {
            return DEFAULT_PAGE_BYTES;
        }
        return Math.min(pageSizeBytes.intValue(), MAX_PAGE_BYTES);
    }

    private void addHeader(Map<String, List<String>> headers, String name, String value) {
        if (StringUtils.hasText(name) && StringUtils.hasText(value)) {
            headers.computeIfAbsent(name, ignored -> new ArrayList<String>()).add(value);
        }
    }

    private String unauthenticatedRuntimeResponseMessage(RuntimeEndpointHttpClient.Response response) {
        if (response != null) {
            for (Map.Entry<String, List<String>> entry : response.getHeaders().entrySet()) {
                if (RuntimeInternalHeaders.INTERNAL_ERROR_HEADER.equalsIgnoreCase(entry.getKey())
                        && entry.getValue() != null) {
                    for (String value : entry.getValue()) {
                        if (RuntimeInternalHeaders.INTERNAL_AUTHENTICATION.equalsIgnoreCase(value)) {
                            return "Worker rejected internal authentication while loading the run log";
                        }
                    }
                }
            }
        }
        return "Worker returned no authenticated run log response";
    }

    private StudioException invalidWorkerResponse(String message) {
        return new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, message);
    }

    private boolean isObjectStorageLog(RunRecordEntity entity) {
        return entity != null
                && RunLogStorageService.STORAGE_OBJECT.equalsIgnoreCase(entity.getLogStorageType())
                && StringUtils.hasText(entity.getLogObjectBucket())
                && StringUtils.hasText(entity.getLogObjectKey());
    }
}
