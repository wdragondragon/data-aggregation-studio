package com.jdragon.studio.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.request.RuntimeAssistantScriptExecuteRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeEndpointEntity;
import com.jdragon.studio.infra.mapper.RuntimeEndpointMapper;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.RuntimeClusterService;
import com.jdragon.studio.infra.service.RuntimeEndpointHeaderPolicy;
import com.jdragon.studio.infra.service.RuntimeEndpointHttpClient;
import com.jdragon.studio.infra.service.RuntimeEndpointSecurityService;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Routes registered assistant script execution to a selected Worker endpoint. */
@Service
public class AssistantScriptRuntimeRouter {

    private static final String INTERNAL_PATH = "/internal/runtime/assistant/scripts/execute";

    private final RuntimeEndpointMapper endpointMapper;
    private final RuntimeClusterService runtimeClusterService;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final StudioPlatformProperties properties;
    private final RuntimeEndpointSecurityService endpointSecurityService;
    private final RuntimeEndpointHeaderPolicy headerPolicy;
    private final RuntimeEndpointHttpClient httpClient;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;

    public AssistantScriptRuntimeRouter(RuntimeEndpointMapper endpointMapper,
                                        RuntimeClusterService runtimeClusterService,
                                        EncryptionService encryptionService,
                                        ObjectMapper objectMapper,
                                        StudioPlatformProperties properties,
                                        RuntimeEndpointSecurityService endpointSecurityService,
                                        RuntimeEndpointHeaderPolicy headerPolicy,
                                        RuntimeEndpointHttpClient httpClient,
                                        StudioSecurityService securityService,
                                        ProjectResourceAccessService projectResourceAccessService) {
        this.endpointMapper = endpointMapper;
        this.runtimeClusterService = runtimeClusterService;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.endpointSecurityService = endpointSecurityService;
        this.headerPolicy = headerPolicy;
        this.httpClient = httpClient;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public ExecutionResult execute(Map<String, Object> params) {
        String entrypointId = text(params == null ? null : params.get("entrypointId"));
        Object inputValue = params == null ? null : params.get("input");
        if (!StringUtils.hasText(entrypointId)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "assistant script entrypointId is required");
        }
        if (!(inputValue instanceof Map<?, ?>)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "assistant script input is required");
        }

        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        Long runtimeClusterId = longValue(params == null ? null : params.get("runtimeClusterId"));
        if (runtimeClusterId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "runtimeClusterId is required for assistant script execution");
        }
        RuntimeClusterEntity cluster = runtimeClusterService.requireAuthorized(projectId, runtimeClusterId);
        if (!runtimeClusterService.hasOnlineInstance(cluster)) {
            throw unavailable("Target runtime cluster has no online Worker");
        }
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null) {
            throw unavailable("Target runtime cluster has no available HTTP endpoint");
        }
        if (!StringUtils.hasText(properties.getInternalApiToken())) {
            throw unavailable("Internal runtime authentication is not configured");
        }

        RuntimeAssistantScriptExecuteRequest payload = new RuntimeAssistantScriptExecuteRequest();
        payload.setTargetClusterId(cluster.getId());
        payload.setTargetClusterCode(cluster.getCode());
        payload.setTenantId(securityService.currentTenantId());
        payload.setProjectId(projectId);
        payload.setUserId(securityService.currentUserId());
        payload.setUsername(securityService.currentUsername());
        payload.setEntrypointId(entrypointId);
        payload.setInput(copyInput(inputValue));
        try {
            return new ExecutionResult(cluster.getId(), post(endpoint, payload));
        } catch (StudioException exception) {
            throw exception;
        } catch (RuntimeEndpointSecurityService.ResponseTooLargeException exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime response exceeds the configured limit", exception);
        } catch (Exception exception) {
            throw unavailable("Target runtime cluster request failed");
        }
    }

    private Map<String, Object> post(RuntimeEndpointEntity endpoint,
                                     RuntimeAssistantScriptExecuteRequest payload) throws Exception {
        String endpointUrl = encryptionService.decrypt(endpoint.getEndpointCiphertext());
        String validatedEndpointUrl = endpointSecurityService.validate(endpointUrl).toString();
        String baseUrl = validatedEndpointUrl.replaceAll("/+$", "");
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                endpointSecurityService.validateRequestTarget(baseUrl + INTERNAL_PATH);

        Map<String, String> configuredHeaders = configuredHeaders(endpoint);
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        addHeader(headers, "Content-Type", "application/json;charset=UTF-8");
        addHeader(headers, "Accept", "application/json");
        addHeader(headers, RuntimeInvocationRouter.INTERNAL_TOKEN_HEADER,
                properties.getInternalApiToken());
        addHeader(headers, RuntimeInvocationRouter.TARGET_CLUSTER_ID_HEADER,
                String.valueOf(payload.getTargetClusterId()));
        String endpointToken = StringUtils.hasText(endpoint.getTokenCiphertext())
                ? encryptionService.decrypt(endpoint.getTokenCiphertext()) : null;
        if (StringUtils.hasText(endpointToken) && !containsHeader(configuredHeaders, "Authorization")) {
            addHeader(headers, "Authorization", "Bearer " + endpointToken.trim());
        }
        for (Map.Entry<String, String> header : configuredHeaders.entrySet()) {
            addHeader(headers, header.getKey(), header.getValue());
        }

        RuntimeEndpointHttpClient.Response response = httpClient.execute(
                target, "POST", headers, objectMapper.writeValueAsBytes(payload),
                timeout(endpoint.getConnectTimeoutMillis(), 3000, 60000),
                assistantReadTimeout(endpoint.getReadTimeoutMillis()),
                endpointSecurityService.maxResponseBytes());
        if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) {
            throw unavailable(unauthenticatedRuntimeResponseMessage(response));
        }

        JavaType mapType = objectMapper.getTypeFactory().constructMapType(
                LinkedHashMap.class, String.class, Object.class);
        JavaType resultType = objectMapper.getTypeFactory().constructParametricType(Result.class, mapType);
        Result<Map<String, Object>> result;
        try {
            result = objectMapper.readValue(response.getBody(), resultType);
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime cluster returned an invalid assistant script response", exception);
        }
        if (result == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime cluster returned an empty assistant script response");
        }
        if (!result.isSuccess()) {
            String code = response.getStatusCode() == 503
                    ? StudioErrorCode.SERVICE_UNAVAILABLE
                    : StringUtils.hasText(result.getCode())
                    ? result.getCode() : StudioErrorCode.BUSINESS_ERROR;
            throw new StudioException(code, StringUtils.hasText(result.getMessage())
                    ? result.getMessage() : "Assistant script execution failed");
        }
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300 || result.getData() == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime cluster returned no assistant script result");
        }
        return result.getData();
    }

    private RuntimeEndpointEntity endpoint(RuntimeClusterEntity cluster) {
        return endpointMapper.selectOne(new LambdaQueryWrapper<RuntimeEndpointEntity>()
                .eq(RuntimeEndpointEntity::getTenantId, cluster.getTenantId())
                .eq(RuntimeEndpointEntity::getRuntimeClusterId, cluster.getId())
                .eq(RuntimeEndpointEntity::getMode, "HTTP")
                .eq(RuntimeEndpointEntity::getEnabled, 1)
                .orderByAsc(RuntimeEndpointEntity::getId)
                .last("limit 1"));
    }

    private Map<String, String> configuredHeaders(RuntimeEndpointEntity endpoint) {
        if (!StringUtils.hasText(endpoint.getHeadersCiphertext())) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> headers = objectMapper.readValue(
                    encryptionService.decrypt(endpoint.getHeadersCiphertext()),
                    new TypeReference<LinkedHashMap<String, String>>() {
                    });
            return headerPolicy.sanitizeConfiguredHeaders(headers, Set.of("content-type", "accept"));
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Stored runtime endpoint headers cannot be decrypted");
        }
    }

    private int assistantReadTimeout(Integer endpointTimeoutMillis) {
        long executionSeconds = properties.getPython() == null
                || properties.getPython().getExecutionTimeoutSeconds() == null
                ? 120L : Math.max(1L, properties.getPython().getExecutionTimeoutSeconds());
        int executionTimeoutMillis = (int) Math.min(300000L, executionSeconds * 1000L + 5000L);
        return Math.max(timeout(endpointTimeoutMillis, 5000, 300000), executionTimeoutMillis);
    }

    private String unauthenticatedRuntimeResponseMessage(RuntimeEndpointHttpClient.Response response) {
        if (response == null) {
            return "Target runtime cluster returned no authenticated Worker response";
        }
        for (String value : headerValues(response.getHeaders(), RuntimeInternalHeaders.INTERNAL_ERROR_HEADER)) {
            if (RuntimeInternalHeaders.INTERNAL_AUTHENTICATION.equalsIgnoreCase(value)) {
                return "Target runtime cluster rejected internal authentication";
            }
        }
        return "Target runtime cluster returned no authenticated Worker response";
    }

    private List<String> headerValues(Map<String, List<String>> headers, String expectedName) {
        List<String> values = new ArrayList<String>();
        if (headers != null) {
            headers.forEach((name, items) -> {
                if (expectedName.equalsIgnoreCase(name) && items != null) values.addAll(items);
            });
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyInput(Object value) {
        return new LinkedHashMap<String, Object>((Map<String, Object>) value);
    }

    private Long longValue(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) return null;
        try {
            long parsed = new java.math.BigDecimal(String.valueOf(value).trim()).longValueExact();
            if (parsed <= 0L) {
                throw new ArithmeticException("runtimeClusterId must be positive");
            }
            return Long.valueOf(parsed);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "runtimeClusterId is invalid");
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private boolean containsHeader(Map<String, String> headers, String expectedName) {
        for (String name : headers.keySet()) {
            if (expectedName.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private void addHeader(Map<String, List<String>> headers, String name, String value) {
        headers.computeIfAbsent(name, ignored -> new ArrayList<String>()).add(value);
    }

    private int timeout(Integer value, int fallback, int maximum) {
        return value == null ? fallback : Math.max(100, Math.min(value, maximum));
    }

    private StudioException unavailable(String message) {
        return new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE, message);
    }

    public static final class ExecutionResult {
        private final Long runtimeClusterId;
        private final Map<String, Object> data;

        public ExecutionResult(Long runtimeClusterId, Map<String, Object> data) {
            this.runtimeClusterId = runtimeClusterId;
            this.data = data;
        }

        public Long getRuntimeClusterId() {
            return runtimeClusterId;
        }

        public Map<String, Object> getData() {
            return data;
        }
    }
}
