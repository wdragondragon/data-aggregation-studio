package com.jdragon.studio.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.RunService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class RunLogProxyService {

    private static final int DEFAULT_TAIL_BYTES = 64 * 1024;

    private final RunService runService;
    private final WorkerLeaseMapper workerLeaseMapper;
    private final StudioPlatformProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    public RunLogProxyService(RunService runService,
                              WorkerLeaseMapper workerLeaseMapper,
                              StudioPlatformProperties properties,
                              RestTemplate restTemplate,
                              ObjectMapper objectMapper) {
        this.runService = runService;
        this.workerLeaseMapper = workerLeaseMapper;
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public RunLogView viewLog(Long runRecordId) {
        RunRecordEntity entity = runService.getEntity(runRecordId);
        if (!StringUtils.hasText(entity.getLogFilePath()) || !StringUtils.hasText(entity.getWorkerCode())) {
            return runService.buildHistoricalFallback(entity);
        }
        String apiBaseUrl = resolveWorkerApiBaseUrl(entity);
        String url = UriComponentsBuilder.fromHttpUrl(apiBaseUrl)
                .path("/internal/runs/{id}/log")
                .queryParam("maxBytes", DEFAULT_TAIL_BYTES)
                .buildAndExpand(runRecordId)
                .toUriString();
        return exchange(url);
    }

    public RunLogView downloadLog(Long runRecordId) {
        RunRecordEntity entity = runService.getEntity(runRecordId);
        if (!StringUtils.hasText(entity.getLogFilePath()) || !StringUtils.hasText(entity.getWorkerCode())) {
            return runService.buildHistoricalFallback(entity);
        }
        String apiBaseUrl = resolveWorkerApiBaseUrl(entity);
        String url = UriComponentsBuilder.fromHttpUrl(apiBaseUrl)
                .path("/internal/runs/{id}/log/download")
                .buildAndExpand(runRecordId)
                .toUriString();
        return exchange(url);
    }

    private RunLogView exchange(String url) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.add(StudioConstants.INTERNAL_API_TOKEN_HEADER, properties.getInternalApiToken());
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<Void>(headers),
                    String.class
            );
            String body = response.getBody();
            if (!StringUtils.hasText(body)) {
                throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Failed to load run log from worker");
            }
            JsonNode root = objectMapper.readTree(body);
            if (!root.path("success").asBoolean(false)) {
                throw new StudioException(
                        StudioErrorCode.INTERNAL_SERVER_ERROR,
                        "Failed to load run log from worker: " + root.path("message").asText("Unknown error"));
            }
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Failed to load run log from worker");
            }
            return objectMapper.treeToValue(dataNode, RunLogView.class);
        } catch (StudioException e) {
            throw e;
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Failed to load run log from worker: " + e.getMessage());
        }
    }

    private String resolveWorkerApiBaseUrl(RunRecordEntity runRecord) {
        WorkerLeaseEntity lease = workerLeaseMapper.selectOne(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getTenantId, runRecord.getTenantId())
                .eq(WorkerLeaseEntity::getWorkerCode, runRecord.getWorkerCode())
                .last("limit 1"));
        if (lease == null || lease.getCapabilitiesJson() == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Worker lease not found for " + runRecord.getWorkerCode());
        }
        Object apiBaseUrl = lease.getCapabilitiesJson().get("apiBaseUrl");
        if (apiBaseUrl == null || String.valueOf(apiBaseUrl).trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Worker API base URL is missing for " + runRecord.getWorkerCode());
        }
        return String.valueOf(apiBaseUrl).trim();
    }
}
