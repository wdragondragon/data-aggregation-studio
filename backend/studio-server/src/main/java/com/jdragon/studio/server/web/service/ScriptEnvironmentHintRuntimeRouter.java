package com.jdragon.studio.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.JavaImportHintResponse;
import com.jdragon.studio.dto.model.JavaMemberHintResponse;
import com.jdragon.studio.dto.model.request.RuntimeScriptEnvironmentHintRequest;
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

/** Routes Java environment hint generation to the selected managed Worker. */
@Service
public class ScriptEnvironmentHintRuntimeRouter {

    private static final String IMPORT_HINT_PATH =
            "/internal/runtime/script-environments/java/import-hints";
    private static final String MEMBER_HINT_PATH =
            "/internal/runtime/script-environments/java/member-hints";

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

    public ScriptEnvironmentHintRuntimeRouter(RuntimeEndpointMapper endpointMapper,
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

    public JavaImportHintResponse importHints(Long runtimeClusterId,
                                              Long environmentId,
                                              String keyword,
                                              Integer limit) {
        PreparedRequest prepared = request(
                runtimeClusterId, environmentId, keyword, limit);
        return execute(prepared, IMPORT_HINT_PATH, JavaImportHintResponse.class);
    }

    public JavaMemberHintResponse memberHints(Long runtimeClusterId,
                                              Long environmentId,
                                              String className,
                                              String keyword,
                                              Boolean staticOnly,
                                              Integer limit) {
        if (!StringUtils.hasText(className)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Java class name is required");
        }
        PreparedRequest prepared = request(
                runtimeClusterId, environmentId, keyword, limit);
        prepared.request.setClassName(className.trim());
        prepared.request.setStaticOnly(staticOnly);
        return execute(prepared, MEMBER_HINT_PATH, JavaMemberHintResponse.class);
    }

    private PreparedRequest request(Long runtimeClusterId,
                                    Long environmentId,
                                    String keyword,
                                    Integer limit) {
        if (runtimeClusterId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Runtime cluster is required");
        }
        if (limit != null && (limit.intValue() < 1 || limit.intValue() > 500)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Java hint limit must be between 1 and 500");
        }
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        RuntimeClusterEntity cluster = runtimeClusterService.requireAuthorized(projectId, runtimeClusterId);
        if (!runtimeClusterService.hasOnlineInstance(cluster)) {
            throw unavailable("Target runtime cluster has no online Worker");
        }
        if (!StringUtils.hasText(properties.getInternalApiToken())) {
            throw unavailable("Internal runtime authentication is not configured");
        }

        RuntimeScriptEnvironmentHintRequest request = new RuntimeScriptEnvironmentHintRequest();
        request.setTargetClusterId(cluster.getId());
        request.setTargetClusterCode(cluster.getCode());
        request.setTenantId(securityService.currentTenantId());
        request.setProjectId(projectId);
        request.setUserId(securityService.currentUserId());
        request.setUsername(securityService.currentUsername());
        request.setEnvironmentId(environmentId);
        request.setKeyword(keyword);
        request.setLimit(limit);
        return new PreparedRequest(cluster, request);
    }

    private <T> T execute(PreparedRequest prepared,
                          String path,
                          Class<T> responseType) {
        RuntimeEndpointEntity endpoint = endpoint(prepared.cluster);
        if (endpoint == null) {
            throw unavailable("Target runtime cluster has no available HTTP endpoint");
        }
        try {
            return post(endpoint, prepared.request, path, responseType);
        } catch (StudioException exception) {
            throw exception;
        } catch (RuntimeEndpointSecurityService.ResponseTooLargeException exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime response exceeds the configured limit", exception);
        } catch (Exception exception) {
            throw unavailable("Target runtime cluster request failed");
        }
    }

    private <T> T post(RuntimeEndpointEntity endpoint,
                       RuntimeScriptEnvironmentHintRequest payload,
                       String path,
                       Class<T> responseType) throws Exception {
        String endpointUrl = encryptionService.decrypt(endpoint.getEndpointCiphertext());
        String validatedEndpointUrl = endpointSecurityService.validate(endpointUrl).toString();
        String baseUrl = validatedEndpointUrl.replaceAll("/+$", "");
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                endpointSecurityService.validateRequestTarget(baseUrl + path);

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
                timeout(endpoint.getReadTimeoutMillis(), 60000, 120000),
                endpointSecurityService.maxResponseBytes());
        if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) {
            throw unavailable(unauthenticatedRuntimeResponseMessage(response));
        }

        JavaType resultType = objectMapper.getTypeFactory()
                .constructParametricType(Result.class, responseType);
        Result<T> result;
        try {
            result = objectMapper.readValue(response.getBody(), resultType);
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime cluster returned an invalid Java hint response", exception);
        }
        if (result == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime cluster returned an empty Java hint response");
        }
        if (!result.isSuccess()) {
            String code = response.getStatusCode() == 503
                    ? StudioErrorCode.SERVICE_UNAVAILABLE
                    : StringUtils.hasText(result.getCode())
                    ? result.getCode() : StudioErrorCode.BUSINESS_ERROR;
            throw new StudioException(code, StringUtils.hasText(result.getMessage())
                    ? result.getMessage() : "Java environment hint generation failed");
        }
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300 || result.getData() == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target runtime cluster returned no Java hint result");
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

    private String unauthenticatedRuntimeResponseMessage(RuntimeEndpointHttpClient.Response response) {
        if (response == null) {
            return "Target runtime cluster returned no authenticated Worker response";
        }
        for (String value : headerValues(
                response.getHeaders(), RuntimeInternalHeaders.INTERNAL_ERROR_HEADER)) {
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

    private int timeout(Integer value, int fallback, int maximum) {
        return value == null ? fallback : Math.max(100, Math.min(value, maximum));
    }

    private StudioException unavailable(String message) {
        return new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE, message);
    }

    private static final class PreparedRequest {
        private final RuntimeClusterEntity cluster;
        private final RuntimeScriptEnvironmentHintRequest request;

        private PreparedRequest(RuntimeClusterEntity cluster,
                                RuntimeScriptEnvironmentHintRequest request) {
            this.cluster = cluster;
            this.request = request;
        }
    }
}
