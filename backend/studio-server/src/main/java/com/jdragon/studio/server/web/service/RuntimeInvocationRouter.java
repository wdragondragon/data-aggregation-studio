package com.jdragon.studio.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.dto.model.DataServiceDefinitionView;
import com.jdragon.studio.dto.model.ProtocolConversionDebugResult;
import com.jdragon.studio.dto.model.ProtocolConversionServiceView;
import com.jdragon.studio.dto.model.WebServiceDebugResult;
import com.jdragon.studio.dto.model.request.DataIngestionDebugRequest;
import com.jdragon.studio.dto.model.request.DataServiceDebugRequest;
import com.jdragon.studio.dto.model.request.ProtocolConversionDebugRequest;
import com.jdragon.studio.dto.model.request.WebServiceDebugRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeEndpointEntity;
import com.jdragon.studio.infra.entity.RuntimeValidationEntity;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeEndpointMapper;
import com.jdragon.studio.infra.mapper.RuntimeValidationMapper;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.RuntimeClusterService;
import com.jdragon.studio.infra.service.RuntimeEndpointHeaderPolicy;
import com.jdragon.studio.infra.service.RuntimeEndpointHttpClient;
import com.jdragon.studio.infra.service.RuntimeEndpointSecurityService;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.jdragon.studio.infra.service.RuntimeInvocationFingerprintSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Routes public synchronous invocations to the execution cluster without exposing worker endpoints. */
@Service
public class RuntimeInvocationRouter {
    private static final String SERVICE_UNAVAILABLE_CODE = "SERVICE_UNAVAILABLE";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String IDEMPOTENCY_KEY_REQUIRED_CODE = "IDEMPOTENCY_KEY_REQUIRED";
    private static final String IDEMPOTENCY_KEY_INVALID_CODE = "IDEMPOTENCY_KEY_INVALID";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;
    public static final String INTERNAL_TOKEN_HEADER = "X-Studio-Internal-Token";
    public static final String INVOCATION_VARIANT_HEADER = "X-Studio-Invocation-Variant";
    public static final String ORIGINAL_URL_HEADER = "X-Studio-Original-Url";
    public static final String TARGET_CLUSTER_ID_HEADER = "X-Studio-Target-Cluster-Id";
    public static final String TRANSPORT_AUTHORIZATION_HEADER = "X-Studio-Transport-Authorization";
    public static final String TRANSPORT_HEADER_NAMES_HEADER = "X-Studio-Transport-Header-Names";
    private static final Set<String> SENSITIVE_RESPONSE_HEADERS = Set.of(
            "set-cookie", "set-cookie2", "www-authenticate");

    private final DataServiceDefinitionMapper dataServiceMapper;
    private final DataIngestionServiceMapper ingestionMapper;
    private final ProtocolConversionServiceMapper conversionMapper;
    private final RuntimeClusterMapper clusterMapper;
    private final RuntimeEndpointMapper endpointMapper;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final StudioPlatformProperties properties;
    private final RuntimeEndpointSecurityService runtimeEndpointSecurityService;
    private final RuntimeEndpointHeaderPolicy runtimeEndpointHeaderPolicy;
    private final RuntimeEndpointHttpClient runtimeEndpointHttpClient;
    private final RuntimeClusterService runtimeClusterService;
    private RuntimeValidationMapper runtimeValidationMapper;

    public RuntimeInvocationRouter(DataServiceDefinitionMapper dataServiceMapper,
                                   DataIngestionServiceMapper ingestionMapper,
                                   ProtocolConversionServiceMapper conversionMapper,
                                   RuntimeClusterMapper clusterMapper,
                                   RuntimeEndpointMapper endpointMapper,
                                   EncryptionService encryptionService,
                                   ObjectMapper objectMapper,
                                   StudioPlatformProperties properties,
                                   RuntimeEndpointSecurityService runtimeEndpointSecurityService,
                                   RuntimeEndpointHeaderPolicy runtimeEndpointHeaderPolicy,
                                   RuntimeEndpointHttpClient runtimeEndpointHttpClient,
                                   RuntimeClusterService runtimeClusterService) {
        this.dataServiceMapper = dataServiceMapper;
        this.ingestionMapper = ingestionMapper;
        this.conversionMapper = conversionMapper;
        this.clusterMapper = clusterMapper;
        this.endpointMapper = endpointMapper;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.runtimeEndpointSecurityService = runtimeEndpointSecurityService;
        this.runtimeEndpointHeaderPolicy = runtimeEndpointHeaderPolicy;
        this.runtimeEndpointHttpClient = runtimeEndpointHttpClient;
        this.runtimeClusterService = runtimeClusterService;
    }

    @Autowired
    void setRuntimeValidationMapper(RuntimeValidationMapper runtimeValidationMapper) {
        this.runtimeValidationMapper = runtimeValidationMapper;
    }

    public boolean routeIfRemote(String kind, String serviceCode, String serviceKey, String variant,
                                 HttpServletRequest request, HttpServletResponse response) throws IOException {
        Target target = resolve(kind, serviceCode, serviceKey);
        if (target != null && StringUtils.hasText(target.unavailableMessage)) {
            unavailable(response, target.unavailableMessage);
            return true;
        }
        if (target == null) {
            notFound(response, kind);
            return true;
        }
        if (target.endpoint == null) {
            unavailable(response, "The target runtime cluster has no available HTTP endpoint");
            return true;
        }
        if (!StringUtils.hasText(properties.getInternalApiToken())) {
            unavailable(response, "Internal runtime authentication is not configured");
            return true;
        }
        try {
            forward(target.endpoint, kind, serviceCode, serviceKey, variant, request, response);
        } catch (IdempotencyRequestException ex) {
            badRequest(response, ex.code, ex.getMessage());
        } catch (PayloadTooLargeException ex) {
            payloadTooLarge(response);
        } catch (RuntimeEndpointSecurityService.ResponseTooLargeException ex) {
            responseTooLarge(response);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            unavailable(response, "The target runtime cluster request was interrupted");
        } catch (Exception ex) {
            unavailable(response, "The target runtime cluster is unavailable");
        }
        return true;
    }

    public DebugRoute<Map<String, Object>> routeDataServiceDebug(DataServiceDefinitionView view,
                                                                  DataServiceDebugRequest request) {
        return routeDebug("data-services", resource(view.getRuntimeClusterId(), view.getTenantId(), view.getProjectId(),
                "DATA_SERVICE", view.getId()), "REST", request,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    public DebugRoute<WebServiceDebugResult> routeDataServiceWebServiceDebug(DataServiceDefinitionView view,
                                                                               WebServiceDebugRequest request) {
        return routeDebug("data-services", resource(view.getRuntimeClusterId(), view.getTenantId(), view.getProjectId(),
                "DATA_SERVICE", view.getId()), "SOAP", request,
                objectMapper.getTypeFactory().constructType(WebServiceDebugResult.class));
    }

    public DebugRoute<DataIngestionInvokeResult> routeDataIngestionDebug(DataIngestionServiceView view,
                                                                          DataIngestionDebugRequest request) {
        return routeDebug("data-ingestion-services", resource(view.getRuntimeClusterId(), view.getTenantId(),
                view.getProjectId(), "DATA_INGESTION_SERVICE", view.getId()), "REST", request,
                objectMapper.getTypeFactory().constructType(DataIngestionInvokeResult.class));
    }

    public DebugRoute<WebServiceDebugResult> routeDataIngestionWebServiceDebug(DataIngestionServiceView view,
                                                                                 WebServiceDebugRequest request) {
        return routeDebug("data-ingestion-services", resource(view.getRuntimeClusterId(), view.getTenantId(),
                view.getProjectId(), "DATA_INGESTION_SERVICE", view.getId()), "SOAP", request,
                objectMapper.getTypeFactory().constructType(WebServiceDebugResult.class));
    }

    public DebugRoute<ProtocolConversionDebugResult> routeProtocolConversionDebug(ProtocolConversionServiceView view,
                                                                                    ProtocolConversionDebugRequest request) {
        return routeDebug("protocol-conversions", resource(view.getRuntimeClusterId(), view.getTenantId(),
                view.getProjectId(), "PROTOCOL_CONVERSION_SERVICE", view.getId()), "REST", request,
                objectMapper.getTypeFactory().constructType(ProtocolConversionDebugResult.class));
    }

    private <T> DebugRoute<T> routeDebug(String kind, RuntimeResource resource, String variant,
                                         Object payload, JavaType payloadType) {
        Target target = resolve(resource);
        if (target != null && StringUtils.hasText(target.unavailableMessage)) {
            return DebugRoute.failure(HttpServletResponse.SC_SERVICE_UNAVAILABLE, target.unavailableMessage);
        }
        if (target == null) return DebugRoute.failure(HttpServletResponse.SC_NOT_FOUND,
                "The target runtime resource was not found");
        if (target.endpoint == null) {
            return DebugRoute.failure(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "The target runtime cluster has no available HTTP endpoint");
        }
        if (!StringUtils.hasText(properties.getInternalApiToken())) {
            return DebugRoute.failure(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Internal runtime authentication is not configured");
        }
        try {
            return forwardDebug(target.endpoint, kind, resource.resourceId, variant, payload, payloadType);
        } catch (RuntimeEndpointSecurityService.ResponseTooLargeException ex) {
            return DebugRoute.responseTooLarge();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return DebugRoute.failure(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "The target runtime cluster request was interrupted");
        } catch (Exception ex) {
            return DebugRoute.failure(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "The target runtime cluster is unavailable");
        }
    }

    private Target resolve(String kind, String serviceCode, String serviceKey) {
        return resolve(runtimeResource(kind, serviceCode, serviceKey));
    }

    private Target resolve(RuntimeResource resource) {
        if (resource == null) {
            return null;
        }
        if (resource.clusterId == null) {
            return new Target(null, "The service runtime cluster is not configured");
        }
        if (isRuntimeInvalid(resource)) {
            return new Target(null, "The service runtime configuration is invalid");
        }
        Long clusterId = resource.clusterId;
        RuntimeClusterEntity cluster = clusterMapper.selectById(clusterId);
        if (cluster == null || !Objects.equals(cluster.getTenantId(), resource.tenantId)
                || !Integer.valueOf(1).equals(cluster.getEnabled())) {
            return new Target(null, "The target runtime cluster is disabled or missing");
        }
        if (!runtimeClusterService.hasOnlineInstance(cluster)) {
            return new Target(null, "The target runtime cluster has no online instance");
        }
        RuntimeEndpointEntity endpoint = endpointMapper.selectOne(new LambdaQueryWrapper<RuntimeEndpointEntity>()
                .eq(RuntimeEndpointEntity::getTenantId, cluster.getTenantId())
                .eq(RuntimeEndpointEntity::getRuntimeClusterId, clusterId)
                .eq(RuntimeEndpointEntity::getEnabled, 1)
                .eq(RuntimeEndpointEntity::getMode, "HTTP")
                .orderByAsc(RuntimeEndpointEntity::getId).last("limit 1"));
        if (endpoint == null || !StringUtils.hasText(endpoint.getEndpointCiphertext())) {
            return new Target(null, null);
        }
        return new Target(endpoint, null);
    }

    private RuntimeResource resource(Long clusterId, String tenantId, Long projectId,
                                     String resourceType, Long resourceId) {
        return new RuntimeResource(clusterId, tenantId, projectId, resourceType, resourceId);
    }

    private RuntimeResource runtimeResource(String kind, String serviceCode, String serviceKey) {
        if ("data-services".equals(kind)) {
            DataServiceDefinitionEntity entity = dataServiceMapper.selectOne(new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                    .eq(DataServiceDefinitionEntity::getServiceCode, serviceCode).eq(DataServiceDefinitionEntity::getServiceKey, serviceKey).last("limit 1"));
            return entity == null ? null : new RuntimeResource(entity.getRuntimeClusterId(), entity.getTenantId(),
                    entity.getProjectId(), "DATA_SERVICE", entity.getId());
        }
        if ("data-ingestion-services".equals(kind)) {
            DataIngestionServiceEntity entity = ingestionMapper.selectOne(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                    .eq(DataIngestionServiceEntity::getServiceCode, serviceCode).eq(DataIngestionServiceEntity::getServiceKey, serviceKey).last("limit 1"));
            return entity == null ? null : new RuntimeResource(entity.getRuntimeClusterId(), entity.getTenantId(),
                    entity.getProjectId(), "DATA_INGESTION_SERVICE", entity.getId());
        }
        if ("protocol-conversions".equals(kind)) {
            ProtocolConversionServiceEntity entity = conversionMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                    .eq(ProtocolConversionServiceEntity::getServiceCode, serviceCode)
                    .eq(ProtocolConversionServiceEntity::getServiceKey, serviceKey).last("limit 1"));
            return entity == null ? null : new RuntimeResource(entity.getRuntimeClusterId(), entity.getTenantId(),
                    entity.getProjectId(), "PROTOCOL_CONVERSION_SERVICE", entity.getId());
        }
        return null;
    }

    private boolean isRuntimeInvalid(RuntimeResource resource) {
        if (resource == null || resource.resourceId == null) {
            return false;
        }
        Long count = runtimeValidationMapper.selectCount(new LambdaQueryWrapper<RuntimeValidationEntity>()
                .eq(RuntimeValidationEntity::getTenantId, resource.tenantId)
                .eq(RuntimeValidationEntity::getProjectId, resource.projectId)
                .eq(RuntimeValidationEntity::getResourceType, resource.resourceType)
                .eq(RuntimeValidationEntity::getResourceId, resource.resourceId)
                .eq(RuntimeValidationEntity::getValid, 0));
        return count != null && count.longValue() > 0L;
    }

    private void forward(RuntimeEndpointEntity endpoint, String kind, String serviceCode, String serviceKey, String variant,
                         HttpServletRequest source, HttpServletResponse destination) throws Exception {
        String baseUrl = encryptionService.decrypt(endpoint.getEndpointCiphertext());
        String validatedBaseUrl = runtimeEndpointSecurityService.validate(baseUrl).toString();
        String normalizedBase = validatedBaseUrl.endsWith("/")
                ? validatedBaseUrl.substring(0, validatedBaseUrl.length() - 1) : validatedBaseUrl;
        String path = "/internal/runtime/" + kind + "/" + encode(serviceCode) + "/" + encode(serviceKey);
        String query = source.getQueryString();
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                runtimeEndpointSecurityService.validateRequestTarget(
                        normalizedBase + path + (StringUtils.hasText(query) ? "?" + query : ""));
        byte[] body = readBody(source);
        boolean idempotencyProtectedWrite = idempotencyProtectedWrite(kind, source.getMethod());
        String idempotencyKeyHash = idempotencyKeyHash(kind, source);
        Set<String> connectionHeaderNames = connectionHeaderNames(source);
        Map<String, String> configuredHeaders = runtimeEndpointHeaderPolicy.sanitizeConfiguredHeaders(
                endpointHeaders(endpoint), idempotencyProtectedWrite
                        ? Set.of(IDEMPOTENCY_KEY_HEADER) : Set.of());
        String endpointToken = StringUtils.hasText(endpoint.getTokenCiphertext())
                ? encryptionService.decrypt(endpoint.getTokenCiphertext()) : null;
        boolean transportAuthorization = StringUtils.hasText(endpointToken)
                || containsHeader(configuredHeaders, "Authorization");
        Map<String, List<String>> requestHeaders = new LinkedHashMap<String, List<String>>();
        addHeader(requestHeaders, INTERNAL_TOKEN_HEADER, properties.getInternalApiToken());
        addHeader(requestHeaders, INVOCATION_VARIANT_HEADER, variant);
        addHeader(requestHeaders, TARGET_CLUSTER_ID_HEADER, String.valueOf(endpoint.getRuntimeClusterId()));
        addHeader(requestHeaders, ORIGINAL_URL_HEADER, source.getRequestURL().toString());
        if (transportAuthorization) {
            addHeader(requestHeaders, TRANSPORT_AUTHORIZATION_HEADER, "true");
        }
        String transportHeaderNames = transportHeaderNames(configuredHeaders);
        if (StringUtils.hasText(transportHeaderNames)) {
            addHeader(requestHeaders, TRANSPORT_HEADER_NAMES_HEADER, transportHeaderNames);
        }
        copyRequestHeaders(source, requestHeaders, configuredHeaders, transportAuthorization,
                connectionHeaderNames, idempotencyProtectedWrite);
        if (!StringUtils.hasText(source.getHeader("X-Forwarded-For"))
                && !containsHeader(configuredHeaders, "X-Forwarded-For")
                && StringUtils.hasText(source.getRemoteAddr())) {
            addHeader(requestHeaders, "X-Forwarded-For", source.getRemoteAddr().trim());
        }
        if (StringUtils.hasText(endpointToken) && !containsHeader(configuredHeaders, "Authorization")) {
            addHeader(requestHeaders, "Authorization", "Bearer " + endpointToken.trim());
        }
        for (Map.Entry<String, String> entry : configuredHeaders.entrySet()) {
            addHeader(requestHeaders, entry.getKey(), entry.getValue());
        }
        if (idempotencyKeyHash != null) {
            Map<String, List<String>> businessHeaders = businessHeadersForFingerprint(
                    requestHeaders, configuredHeaders, transportAuthorization);
            addHeader(requestHeaders, RuntimeInternalHeaders.IDEMPOTENCY_KEY_HASH_HEADER,
                    idempotencyKeyHash);
            addHeader(requestHeaders, RuntimeInternalHeaders.IDEMPOTENCY_FINGERPRINT_HEADER,
                    RuntimeInvocationFingerprintSupport.fingerprint(
                            kind, serviceCode, serviceKey, variant, source.getMethod(),
                            source.getQueryString(), source.getContentType(), businessHeaders, body));
        }
        RuntimeEndpointHttpClient.Response response = runtimeEndpointHttpClient.execute(
                target, source.getMethod(), requestHeaders, body,
                timeout(endpoint.getConnectTimeoutMillis(), 3000),
                timeout(endpoint.getReadTimeoutMillis(), 5000),
                runtimeEndpointSecurityService.maxResponseBytes());
        byte[] responseBody = response.getBody();
        if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) {
            unavailable(destination, unauthenticatedRuntimeResponseMessage(response));
            return;
        }
        destination.setStatus(response.getStatusCode());
        copyResponseHeaders(response.getHeaders(), destination, target);
        destination.getOutputStream().write(responseBody);
    }

    private <T> DebugRoute<T> forwardDebug(RuntimeEndpointEntity endpoint, String kind, Long resourceId,
                                            String variant, Object payload, JavaType payloadType) throws Exception {
        String baseUrl = encryptionService.decrypt(endpoint.getEndpointCiphertext());
        String validatedBaseUrl = runtimeEndpointSecurityService.validate(baseUrl).toString();
        String normalizedBase = validatedBaseUrl.endsWith("/")
                ? validatedBaseUrl.substring(0, validatedBaseUrl.length() - 1) : validatedBaseUrl;
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                runtimeEndpointSecurityService.validateRequestTarget(
                        normalizedBase + "/internal/runtime/debug/" + kind + "/" + resourceId);
        byte[] body = objectMapper.writeValueAsBytes(payload == null ? Collections.emptyMap() : payload);
        Map<String, String> configuredHeaders = runtimeEndpointHeaderPolicy.sanitizeConfiguredHeaders(
                endpointHeaders(endpoint), Set.of("content-type", "accept"));
        String endpointToken = StringUtils.hasText(endpoint.getTokenCiphertext())
                ? encryptionService.decrypt(endpoint.getTokenCiphertext()) : null;
        Map<String, List<String>> requestHeaders = new LinkedHashMap<String, List<String>>();
        addHeader(requestHeaders, "Content-Type", "application/json;charset=UTF-8");
        addHeader(requestHeaders, "Accept", "application/json");
        addHeader(requestHeaders, INTERNAL_TOKEN_HEADER, properties.getInternalApiToken());
        addHeader(requestHeaders, INVOCATION_VARIANT_HEADER, variant);
        addHeader(requestHeaders, TARGET_CLUSTER_ID_HEADER, String.valueOf(endpoint.getRuntimeClusterId()));
        if (StringUtils.hasText(endpointToken) && !containsHeader(configuredHeaders, "Authorization")) {
            addHeader(requestHeaders, "Authorization", "Bearer " + endpointToken.trim());
        }
        for (Map.Entry<String, String> entry : configuredHeaders.entrySet()) {
            addHeader(requestHeaders, entry.getKey(), entry.getValue());
        }
        RuntimeEndpointHttpClient.Response response = runtimeEndpointHttpClient.execute(
                target, "POST", requestHeaders, body,
                timeout(endpoint.getConnectTimeoutMillis(), 3000),
                timeout(endpoint.getReadTimeoutMillis(), 5000),
                runtimeEndpointSecurityService.maxResponseBytes());
        byte[] responseBody = response.getBody();
        if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) {
            return DebugRoute.failure(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    unauthenticatedRuntimeResponseMessage(response));
        }
        JavaType resultType = objectMapper.getTypeFactory().constructParametricType(Result.class, payloadType);
        try {
            @SuppressWarnings("unchecked")
            Result<T> result = (Result<T>) objectMapper.readValue(responseBody, resultType);
            return DebugRoute.handled(response.getStatusCode(), result);
        } catch (Exception ex) {
            int status = response.getStatusCode() >= 400
                    ? response.getStatusCode() : HttpServletResponse.SC_BAD_GATEWAY;
            return DebugRoute.handled(status, Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "The target runtime cluster returned an invalid debug response"));
        }
    }

    private void copyRequestHeaders(HttpServletRequest source, Map<String, List<String>> target,
                                    Map<String, String> configuredHeaders,
                                    boolean transportAuthorization,
                                    Set<String> connectionHeaderNames,
                                    boolean consumeIdempotencyKey) {
        Enumeration<String> names = source.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (runtimeEndpointHeaderPolicy.isHopByHop(name, connectionHeaderNames)
                    || runtimeEndpointHeaderPolicy.isReservedStudioHeader(name)
                    || containsHeader(configuredHeaders, name)
                    || (consumeIdempotencyKey && IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(name))
                    || (transportAuthorization && "Authorization".equalsIgnoreCase(name))) continue;
            Enumeration<String> values = source.getHeaders(name);
            while (values.hasMoreElements()) addHeader(target, name, values.nextElement());
        }
    }
    private void copyResponseHeaders(Map<String, List<String>> source, HttpServletResponse target,
                                     RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint endpoint) {
        Set<String> connectionHeaderNames = runtimeEndpointHeaderPolicy.connectionHeaderNames(
                headerValues(source, "Connection"));
        source.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (runtimeEndpointHeaderPolicy.isHopByHop(name, connectionHeaderNames)
                    || SENSITIVE_RESPONSE_HEADERS.contains(lower)
                    || runtimeEndpointHeaderPolicy.isReservedStudioHeader(name)) {
                return;
            }
            for (String value : values) {
                if (!"location".equals(lower) || !internalLocation(value, endpoint)) {
                    target.addHeader(name, value);
                }
            }
        });
    }

    private Set<String> connectionHeaderNames(HttpServletRequest request) {
        List<String> values = new ArrayList<String>();
        Enumeration<String> headers = request.getHeaders("Connection");
        while (headers != null && headers.hasMoreElements()) {
            values.add(headers.nextElement());
        }
        return runtimeEndpointHeaderPolicy.connectionHeaderNames(values);
    }

    private List<String> headerValues(Map<String, List<String>> headers, String expectedName) {
        List<String> result = new ArrayList<String>();
        if (headers == null) return result;
        headers.forEach((name, values) -> {
            if (expectedName.equalsIgnoreCase(name) && values != null) result.addAll(values);
        });
        return result;
    }

    private String unauthenticatedRuntimeResponseMessage(RuntimeEndpointHttpClient.Response response) {
        if (response == null) {
            return "The target runtime cluster returned no authenticated Worker response";
        }
        for (String value : headerValues(response.getHeaders(), RuntimeInternalHeaders.INTERNAL_ERROR_HEADER)) {
            if (RuntimeInternalHeaders.INTERNAL_AUTHENTICATION.equalsIgnoreCase(value)) {
                return "The target runtime cluster rejected internal authentication";
            }
        }
        return "The target runtime cluster returned no authenticated Worker response";
    }

    private boolean internalLocation(String value,
                                     RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint endpoint) {
        if (!StringUtils.hasText(value)) return true;
        try {
            URI location = URI.create(value.trim()).normalize();
            String path = location.getPath();
            if (path != null && ("/internal".equals(path) || path.startsWith("/internal/"))) {
                return true;
            }
            if (!location.isAbsolute()) {
                return location.getRawAuthority() != null;
            }
            return !runtimeEndpointSecurityService.isSafeExternalRedirect(location, endpoint);
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }
    private Map<String, String> endpointHeaders(RuntimeEndpointEntity endpoint) {
        if (!StringUtils.hasText(endpoint.getHeadersCiphertext())) return Collections.emptyMap();
        try {
            Map<String, String> headers = objectMapper.readValue(
                    encryptionService.decrypt(endpoint.getHeadersCiphertext()),
                    new TypeReference<LinkedHashMap<String, String>>() {});
            if (headers == null) throw new IllegalArgumentException("Runtime endpoint headers are null");
            return headers;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Stored runtime endpoint headers cannot be decrypted");
        }
    }
    private boolean containsHeader(Map<String, String> headers, String expectedName) {
        for (String name : headers.keySet()) if (expectedName.equalsIgnoreCase(name)) return true;
        return false;
    }
    private void addHeader(Map<String, List<String>> headers, String name, String value) {
        headers.computeIfAbsent(name, ignored -> new ArrayList<String>()).add(value);
    }
    private String transportHeaderNames(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        return headers.keySet().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(name -> !runtimeEndpointHeaderPolicy.isReservedStudioHeader(name))
                .collect(java.util.stream.Collectors.joining(","));
    }
    private void unavailable(HttpServletResponse response, String message) throws IOException {
        writeRoutingError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                SERVICE_UNAVAILABLE_CODE, message);
    }
    private void badRequest(HttpServletResponse response, String code, String message) throws IOException {
        writeRoutingError(response, HttpServletResponse.SC_BAD_REQUEST, code, message);
    }
    private void notFound(HttpServletResponse response, String kind) throws IOException {
        String message = "data-services".equals(kind)
                ? "Data service is not available"
                : "data-ingestion-services".equals(kind)
                ? "Data ingestion service is not available"
                : "Protocol conversion service is not available";
        writeRoutingError(response, HttpServletResponse.SC_NOT_FOUND,
                StudioErrorCode.NOT_FOUND, message);
    }
    private void payloadTooLarge(HttpServletResponse response) throws IOException {
        writeRoutingError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "PAYLOAD_TOO_LARGE", "Request body exceeds the runtime proxy limit");
    }
    private void responseTooLarge(HttpServletResponse response) throws IOException {
        writeRoutingError(response, HttpServletResponse.SC_BAD_GATEWAY,
                "BAD_GATEWAY", "Target runtime response exceeds the configured limit");
    }
    private void writeRoutingError(HttpServletResponse response, int status,
                                   String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
    }
    private byte[] readBody(HttpServletRequest request) throws IOException, PayloadTooLargeException {
        int configured = properties.getRuntimeInvocationMaxBodyBytes() == null
                ? 10 * 1024 * 1024 : properties.getRuntimeInvocationMaxBodyBytes().intValue();
        int limit = Math.max(1024, configured);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        InputStream input = request.getInputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) throw new PayloadTooLargeException();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
    private String idempotencyKeyHash(String kind,
                                      HttpServletRequest request) throws IdempotencyRequestException {
        if (!idempotencyProtectedWrite(kind, request.getMethod())) {
            return null;
        }
        List<String> keys = new ArrayList<String>();
        Enumeration<String> values = request.getHeaders(IDEMPOTENCY_KEY_HEADER);
        while (values != null && values.hasMoreElements()) {
            keys.add(values.nextElement());
        }
        boolean required = properties.getRuntimeInvocationIdempotency() != null
                && properties.getRuntimeInvocationIdempotency().getMode()
                == StudioPlatformProperties.RuntimeInvocationIdempotencyMode.REQUIRED_WRITE;
        if (keys.isEmpty()) {
            if (required) {
                throw new IdempotencyRequestException(IDEMPOTENCY_KEY_REQUIRED_CODE,
                        "Idempotency-Key is required for this write invocation");
            }
            return null;
        }
        if (keys.size() != 1) {
            throw new IdempotencyRequestException(IDEMPOTENCY_KEY_INVALID_CODE,
                    "Exactly one Idempotency-Key header is required");
        }
        String key = keys.get(0) == null ? null : keys.get(0).trim();
        if (!validIdempotencyKey(key)) {
            throw new IdempotencyRequestException(IDEMPOTENCY_KEY_INVALID_CODE,
                    "Idempotency-Key must contain 1 to 255 printable characters");
        }
        return RuntimeInvocationFingerprintSupport.hashKey(key);
    }
    private Map<String, List<String>> businessHeadersForFingerprint(
            Map<String, List<String>> forwardedHeaders,
            Map<String, String> configuredHeaders,
            boolean transportAuthorization) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        Set<String> transportNames = configuredHeaders == null
                ? Set.of()
                : configuredHeaders.keySet().stream()
                .filter(StringUtils::hasText)
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        forwardedHeaders.forEach((name, values) -> {
            String lower = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if (runtimeEndpointHeaderPolicy.isReservedStudioHeader(name)
                    || transportNames.contains(lower)
                    || unstableFingerprintHeader(lower)
                    || (transportAuthorization && "authorization".equals(lower))) {
                return;
            }
            result.put(name, values == null
                    ? Collections.emptyList() : new ArrayList<String>(values));
        });
        return result;
    }
    private boolean unstableFingerprintHeader(String lowerName) {
        return "forwarded".equals(lowerName)
                || lowerName.startsWith("x-forwarded-")
                || "x-real-ip".equals(lowerName)
                || "user-agent".equals(lowerName)
                || "via".equals(lowerName)
                || "traceparent".equals(lowerName)
                || "tracestate".equals(lowerName)
                || "baggage".equals(lowerName)
                || "x-request-id".equals(lowerName)
                || "x-correlation-id".equals(lowerName)
                || lowerName.startsWith("x-b3-")
                || "x-ot-span-context".equals(lowerName)
                || "x-cloud-trace-context".equals(lowerName);
    }
    private boolean idempotencyProtectedWrite(String kind, String method) {
        return ("data-ingestion-services".equals(kind) || "protocol-conversions".equals(kind))
                && StringUtils.hasText(method) && !"GET".equalsIgnoreCase(method)
                && !"HEAD".equalsIgnoreCase(method) && !"OPTIONS".equalsIgnoreCase(method);
    }
    private boolean validIdempotencyKey(String key) {
        if (!StringUtils.hasText(key) || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char current = key.charAt(i);
            if (Character.isISOControl(current)) {
                return false;
            }
        }
        return true;
    }
    private int timeout(Integer value, int fallback) { return value == null ? fallback : Math.max(100, Math.min(value, 60000)); }
    private String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static final class Target {
        private final RuntimeEndpointEntity endpoint;
        private final String unavailableMessage;
        private Target(RuntimeEndpointEntity endpoint, String unavailableMessage) {
            this.endpoint = endpoint; this.unavailableMessage = unavailableMessage;
        }
    }
    private static final class RuntimeResource {
        private final Long clusterId;
        private final String tenantId;
        private final Long projectId;
        private final String resourceType;
        private final Long resourceId;
        private RuntimeResource(Long clusterId, String tenantId, Long projectId, String resourceType, Long resourceId) {
            this.clusterId = clusterId; this.tenantId = tenantId; this.projectId = projectId;
            this.resourceType = resourceType; this.resourceId = resourceId;
        }
    }
    private static final class IdempotencyRequestException extends Exception {
        private final String code;
        private IdempotencyRequestException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
    private static final class PayloadTooLargeException extends Exception {
    }

    public static final class DebugRoute<T> {
        private final boolean handled;
        private final int status;
        private final Result<T> result;

        private DebugRoute(boolean handled, int status, Result<T> result) {
            this.handled = handled;
            this.status = status;
            this.result = result;
        }

        private static <T> DebugRoute<T> handled(int status, Result<T> result) {
            return new DebugRoute<T>(true, status, result);
        }

        private static <T> DebugRoute<T> failure(int status, String message) {
            return handled(status, Result.error(SERVICE_UNAVAILABLE_CODE, message));
        }

        private static <T> DebugRoute<T> responseTooLarge() {
            return handled(HttpServletResponse.SC_BAD_GATEWAY,
                    Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR,
                            "Target runtime response exceeds the configured limit"));
        }

        public boolean isHandled() {
            return handled;
        }

        public int getStatus() {
            return status;
        }

        public Result<T> getResult() {
            return result;
        }
    }
}
