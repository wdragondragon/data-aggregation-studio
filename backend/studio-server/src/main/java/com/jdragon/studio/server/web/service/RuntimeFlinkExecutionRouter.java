package com.jdragon.studio.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.dto.model.request.RuntimeFlinkSqlExecuteRequest;
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

/** Routes synchronous Flink SQL execution to the selected managed Worker endpoint. */
@Service
public class RuntimeFlinkExecutionRouter {

    private static final String INTERNAL_PATH = "/internal/runtime/flink/sql/execute";

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

    public RuntimeFlinkExecutionRouter(RuntimeEndpointMapper endpointMapper,
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

    public FlinkQuestionResultView execute(FlinkSqlExecuteRequest request) {
        if (request == null || request.getRuntimeClusterId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Runtime cluster is required");
        }
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        RuntimeClusterEntity cluster = runtimeClusterService.requireAuthorized(
                projectId, request.getRuntimeClusterId());
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

        RuntimeFlinkSqlExecuteRequest payload = new RuntimeFlinkSqlExecuteRequest();
        payload.setTargetClusterId(cluster.getId());
        payload.setTargetClusterCode(cluster.getCode());
        payload.setTenantId(securityService.currentTenantId());
        payload.setProjectId(projectId);
        payload.setUserId(securityService.currentUserId());
        payload.setUsername(securityService.currentUsername());
        payload.setExecution(request);

        try {
            return post(endpoint, payload);
        } catch (StudioException exception) {
            throw exception;
        } catch (RuntimeEndpointSecurityService.ResponseTooLargeException exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime response exceeds the configured limit", exception);
        } catch (Exception exception) {
            throw unavailable("Target runtime cluster request failed");
        }
    }

    private FlinkQuestionResultView post(RuntimeEndpointEntity endpoint,
                                         RuntimeFlinkSqlExecuteRequest payload) throws Exception {
        String endpointUrl = encryptionService.decrypt(endpoint.getEndpointCiphertext());
        String validatedEndpointUrl = endpointSecurityService.validate(endpointUrl).toString();
        String baseUrl = validatedEndpointUrl.replaceAll("/+$", "");
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                endpointSecurityService.validateRequestTarget(baseUrl + INTERNAL_PATH);

        Map<String, String> configuredHeaders = configuredHeaders(endpoint);
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        addHeader(headers, "Content-Type", "application/json;charset=UTF-8");
        addHeader(headers, "Accept", "application/json");
        addHeader(headers, RuntimeInvocationRouter.INTERNAL_TOKEN_HEADER, properties.getInternalApiToken());
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

        byte[] body = objectMapper.writeValueAsBytes(payload);
        RuntimeEndpointHttpClient.Response response = httpClient.execute(
                target, "POST", headers, body,
                timeout(endpoint.getConnectTimeoutMillis(), 3000),
                timeout(endpoint.getReadTimeoutMillis(), 60000),
                endpointSecurityService.maxResponseBytes());
        if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) {
            throw unavailable(unauthenticatedRuntimeResponseMessage(response));
        }

        JavaType type = objectMapper.getTypeFactory()
                .constructParametricType(Result.class, FlinkQuestionResultView.class);
        Result<FlinkQuestionResultView> result;
        try {
            result = objectMapper.readValue(response.getBody(), type);
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime cluster returned an invalid Flink SQL response", exception);
        }
        if (result == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime cluster returned an empty Flink SQL response");
        }
        if (!result.isSuccess()) {
            String code = response.getStatusCode() == 503
                    ? StudioErrorCode.SERVICE_UNAVAILABLE
                    : StringUtils.hasText(result.getCode())
                    ? result.getCode() : StudioErrorCode.BUSINESS_ERROR;
            String message = StringUtils.hasText(result.getMessage())
                    ? result.getMessage() : "Flink SQL execution failed";
            throw new StudioException(code, message);
        }
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300 || result.getData() == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime cluster returned no Flink SQL result");
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
            return headerPolicy.sanitizeConfiguredHeaders(headers,
                    Set.of("content-type", "accept"));
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Stored runtime endpoint headers cannot be decrypted");
        }
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
                if (expectedName.equalsIgnoreCase(name) && items != null) {
                    values.addAll(items);
                }
            });
        }
        return values;
    }

    private boolean containsHeader(Map<String, String> headers, String expectedName) {
        for (String name : headers.keySet()) {
            if (expectedName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void addHeader(Map<String, List<String>> headers, String name, String value) {
        headers.computeIfAbsent(name, ignored -> new ArrayList<String>()).add(value);
    }

    private int timeout(Integer value, int fallback) {
        return value == null ? fallback : Math.max(100, Math.min(value, 60000));
    }

    private StudioException unavailable(String message) {
        return new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE, message);
    }
}
