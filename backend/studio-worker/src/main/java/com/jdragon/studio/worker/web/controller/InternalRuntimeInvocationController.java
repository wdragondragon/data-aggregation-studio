package com.jdragon.studio.worker.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.enums.ProtocolConversionProtocol;
import com.jdragon.studio.dto.enums.WebServiceSoapVersion;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.dto.model.ProtocolConversionInvokeResult;
import com.jdragon.studio.dto.model.request.DataIngestionDebugRequest;
import com.jdragon.studio.dto.model.request.DataServiceDebugRequest;
import com.jdragon.studio.dto.model.request.ProtocolConversionDebugRequest;
import com.jdragon.studio.dto.model.request.WebServiceDebugRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.DataIngestionService;
import com.jdragon.studio.infra.service.DataServiceService;
import com.jdragon.studio.infra.service.ProtocolConversionService;
import com.jdragon.studio.infra.service.RuntimeEndpointHeaderPolicy;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.jdragon.studio.infra.service.RuntimeInvocationFingerprintSupport;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.worker.idempotency.RuntimeInvocationIdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Private execution entry point. The OMS router authenticates before it forwards any public invocation here. */
@RestController
@RequestMapping("/internal/runtime")
public class InternalRuntimeInvocationController {
    private static final String TOKEN_HEADER = "X-Studio-Internal-Token";
    private static final String VARIANT_HEADER = "X-Studio-Invocation-Variant";
    private static final String ORIGINAL_URL_HEADER = "X-Studio-Original-Url";
    private static final String TARGET_CLUSTER_ID_HEADER = "X-Studio-Target-Cluster-Id";
    private static final String TRANSPORT_AUTHORIZATION_HEADER = "X-Studio-Transport-Authorization";
    private static final String TRANSPORT_HEADER_NAMES_HEADER = "X-Studio-Transport-Header-Names";
    private static final String PAYLOAD_TOO_LARGE_CODE = "PAYLOAD_TOO_LARGE";
    private static final String CACHED_REQUEST_BODY_ATTRIBUTE =
            InternalRuntimeInvocationController.class.getName() + ".requestBody";
    private final DataServiceService dataServiceService;
    private final DataIngestionService ingestionService;
    private final ProtocolConversionService conversionService;
    private final ObjectMapper objectMapper;
    private final StudioPlatformProperties properties;
    private final RuntimeEndpointHeaderPolicy runtimeEndpointHeaderPolicy;
    private RuntimeClusterMapper runtimeClusterMapper;
    private DataServiceDefinitionMapper dataServiceMapper;
    private DataIngestionServiceMapper ingestionMapper;
    private ProtocolConversionServiceMapper conversionMapper;
    private RuntimeInvocationIdempotencyService idempotencyService;
    private WorkerAuthorizationService workerAuthorizationService;

    public InternalRuntimeInvocationController(DataServiceService dataServiceService, DataIngestionService ingestionService,
                                               ProtocolConversionService conversionService, ObjectMapper objectMapper,
                                               StudioPlatformProperties properties,
                                               RuntimeEndpointHeaderPolicy runtimeEndpointHeaderPolicy) {
        this.dataServiceService = dataServiceService;
        this.ingestionService = ingestionService;
        this.conversionService = conversionService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.runtimeEndpointHeaderPolicy = runtimeEndpointHeaderPolicy;
    }

    @Autowired
    void setRuntimeIdentityMappers(RuntimeClusterMapper runtimeClusterMapper,
                                   DataServiceDefinitionMapper dataServiceMapper,
                                   DataIngestionServiceMapper ingestionMapper,
                                   ProtocolConversionServiceMapper conversionMapper) {
        this.runtimeClusterMapper = runtimeClusterMapper;
        this.dataServiceMapper = dataServiceMapper;
        this.ingestionMapper = ingestionMapper;
        this.conversionMapper = conversionMapper;
    }

    @Autowired
    void setIdempotencyService(RuntimeInvocationIdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Autowired
    void setWorkerAuthorizationService(WorkerAuthorizationService workerAuthorizationService) {
        this.workerAuthorizationService = workerAuthorizationService;
    }

    @GetMapping("/health")
    public void health(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!internalTokenMatches(request.getHeader(TOKEN_HEADER))) {
            writeInternalAuthenticationFailure(response);
            return;
        }
        response.setHeader(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
        Long targetClusterId = parseLong(request.getHeader(TARGET_CLUSTER_ID_HEADER));
        if (!runtimeProcessIdentityMatches(targetClusterId)) {
            writeJson(response, 503, Result.error(StudioErrorCode.BUSINESS_ERROR,
                    "Runtime process identity does not match the requested target cluster"));
            return;
        }
        writeJson(response, 200, Result.success("OK"));
    }

    @RequestMapping(value = "/{kind}/{serviceCode}/{serviceKey}", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH})
    public void invoke(@PathVariable("kind") String kind,
                       @PathVariable("serviceCode") String serviceCode,
                       @PathVariable("serviceKey") String serviceKey,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!internalTokenMatches(request.getHeader(TOKEN_HEADER))) {
            writeInternalAuthenticationFailure(response);
            return;
        }
        response.setHeader(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
        Long targetClusterId = parseLong(request.getHeader(TARGET_CLUSTER_ID_HEADER));
        RuntimeDebugIdentity identity = runtimeInvocationIdentity(kind, serviceCode, serviceKey, targetClusterId);
        if (identity == null) {
            writeJson(response, 503, Result.error(StudioErrorCode.BUSINESS_ERROR,
                    "Runtime process identity does not match the requested target cluster"));
            return;
        }
        if (!projectClusterAuthorized(identity, targetClusterId)) {
            writeJson(response, 403, Result.error(StudioErrorCode.FORBIDDEN,
                    "Runtime cluster is not authorized for the requested project"));
            return;
        }
        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        StudioRequestContext context = new StudioRequestContext();
        context.setTenantId(identity.tenantId);
        context.setProjectId(identity.projectId);
        StudioRequestContextHolder.setContext(context);
        String variant = request.getHeader(VARIANT_HEADER);
        try {
            ensureDeclaredRequestBodyWithinLimit(request);
            cacheInvocationRequestBody(request);
            if (idempotencyProtectedWrite(kind, request.getMethod())) {
                invokeIdempotently(identity, kind, serviceCode, serviceKey, variant, request, response);
            } else {
                executeInvocation(kind, serviceCode, serviceKey, variant, request, response);
            }
        } catch (StudioException ex) {
            writeError(kind, variant, request, response, statusFor(ex), ex.getCode(), ex.getMessage());
        } catch (Exception ex) {
            writeError(kind, variant, request, response, 500, StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage());
        } finally {
            restoreContext(previous);
        }
    }

    @PostMapping("/debug/{kind}/{resourceId}")
    public void debug(@PathVariable("kind") String kind,
                      @PathVariable("resourceId") Long resourceId,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!internalTokenMatches(request.getHeader(TOKEN_HEADER))) {
            writeInternalAuthenticationFailure(response);
            return;
        }
        response.setHeader(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
        Long targetClusterId = parseLong(request.getHeader(TARGET_CLUSTER_ID_HEADER));
        RuntimeDebugIdentity identity = runtimeDebugIdentity(kind, resourceId, targetClusterId);
        if (identity == null) {
            writeJson(response, 503, Result.error(StudioErrorCode.BUSINESS_ERROR,
                    "Runtime process identity does not match the requested target cluster"));
            return;
        }
        if (!projectClusterAuthorized(identity, targetClusterId)) {
            writeJson(response, 403, Result.error(StudioErrorCode.FORBIDDEN,
                    "Runtime cluster is not authorized for the requested project"));
            return;
        }
        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        StudioRequestContext context = new StudioRequestContext();
        context.setTenantId(identity.tenantId);
        context.setProjectId(identity.projectId);
        StudioRequestContextHolder.setContext(context);
        try {
            JsonNode payload = jsonPayload(request);
            String variant = request.getHeader(VARIANT_HEADER);
            if ("data-services".equals(kind)) {
                Object result = soap(variant)
                        ? dataServiceService.debugWebService(resourceId, convert(payload, WebServiceDebugRequest.class))
                        : dataServiceService.debug(resourceId, convert(payload, DataServiceDebugRequest.class));
                writeJson(response, 200, Result.success(result));
                return;
            }
            if ("data-ingestion-services".equals(kind)) {
                Object result = soap(variant)
                        ? ingestionService.debugWebService(resourceId, convert(payload, WebServiceDebugRequest.class))
                        : ingestionService.debug(resourceId, convert(payload, DataIngestionDebugRequest.class));
                writeJson(response, 200, Result.success(result));
                return;
            }
            if ("protocol-conversions".equals(kind)) {
                writeJson(response, 200, Result.success(conversionService.debug(resourceId,
                        convert(payload, ProtocolConversionDebugRequest.class))));
                return;
            }
            writeJson(response, 404, Result.error(StudioErrorCode.NOT_FOUND,
                    "Unknown runtime debug type"));
        } catch (StudioException ex) {
            writeJson(response, statusFor(ex), Result.error(ex.getCode(), ex.getMessage()));
        } catch (Exception ex) {
            writeJson(response, 500, Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
        } finally {
            restoreContext(previous);
        }
    }

    private void invokeDataService(String code, String key, String variant, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (soap(variant)) {
            if (request.getParameter("wsdl") != null) { writeXml(response, 200, "text/xml;charset=UTF-8", dataServiceService.webServiceWsdl(code, key, originalUrl(request))); return; }
            String result = dataServiceService.invokeWebService(code, key, request.getHeader("X-Data-Service-Token"), headers(request), body(request), clientIp(request), request.getHeader("User-Agent"));
            writeXml(response, 200, "text/xml;charset=UTF-8", result); return;
        }
        Map<String, Object> result = dataServiceService.invoke(code, key, request.getHeader("X-Data-Service-Token"), headers(request), query(request), jsonMap(request), request.getMethod(), clientIp(request), request.getHeader("User-Agent"));
        writeJson(response, 200, Result.success(result));
    }

    private void executeInvocation(String kind, String serviceCode, String serviceKey, String variant,
                                   HttpServletRequest request, HttpServletResponse response) throws IOException {
        if ("data-services".equals(kind)) {
            invokeDataService(serviceCode, serviceKey, variant, request, response);
            return;
        }
        if ("data-ingestion-services".equals(kind)) {
            invokeIngestion(serviceCode, serviceKey, variant, request, response);
            return;
        }
        if ("protocol-conversions".equals(kind)) {
            invokeConversion(serviceCode, serviceKey, variant, request, response);
            return;
        }
        writeJson(response, 404, Result.error(StudioErrorCode.NOT_FOUND,
                "Unknown runtime invocation type"));
    }

    private void invokeIdempotently(RuntimeDebugIdentity identity,
                                    String kind,
                                    String serviceCode,
                                    String serviceKey,
                                    String variant,
                                    HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        String keyHash = singleInternalHeader(request,
                RuntimeInternalHeaders.IDEMPOTENCY_KEY_HASH_HEADER);
        String fingerprint = singleInternalHeader(request,
                RuntimeInternalHeaders.IDEMPOTENCY_FINGERPRINT_HEADER);
        boolean metadataSupplied = request.getHeader(RuntimeInternalHeaders.IDEMPOTENCY_KEY_HASH_HEADER) != null
                || request.getHeader(RuntimeInternalHeaders.IDEMPOTENCY_FINGERPRINT_HEADER) != null;
        boolean required = properties.getRuntimeInvocationIdempotency() != null
                && properties.getRuntimeInvocationIdempotency().getMode()
                == StudioPlatformProperties.RuntimeInvocationIdempotencyMode.REQUIRED_WRITE;
        if (!StringUtils.hasText(keyHash) && !StringUtils.hasText(fingerprint)) {
            if (metadataSupplied) {
                writeError(kind, variant, request, response, 400, "IDEMPOTENCY_KEY_INVALID",
                        "The internal idempotency metadata is invalid");
                return;
            }
            if (required) {
                writeError(kind, variant, request, response, 400, "IDEMPOTENCY_KEY_REQUIRED",
                        "Idempotency-Key is required for this write invocation");
                return;
            }
            executeInvocation(kind, serviceCode, serviceKey, variant, request, response);
            return;
        }
        if (!RuntimeInvocationFingerprintSupport.isSha256(keyHash)
                || !RuntimeInvocationFingerprintSupport.isSha256(fingerprint)) {
            writeError(kind, variant, request, response, 400, "IDEMPOTENCY_KEY_INVALID",
                    "The internal idempotency metadata is invalid");
            return;
        }
        if (idempotencyService == null) {
            writeError(kind, variant, request, response, 503, StudioErrorCode.SERVICE_UNAVAILABLE,
                    "Runtime idempotency storage is unavailable");
            return;
        }

        RuntimeInvocationIdempotencyService.BeginResult begin = idempotencyService.begin(
                identity.tenantId, identity.projectId, identity.runtimeClusterId,
                identity.resourceType, identity.resourceId, keyHash, fingerprint);
        if (begin.getAction() == RuntimeInvocationIdempotencyService.Action.REPLAY) {
            writeStoredResponse(response, begin.getStoredResponse());
            return;
        }
        if (begin.getAction() == RuntimeInvocationIdempotencyService.Action.CONFLICT) {
            writeError(kind, variant, request, response, 409, "IDEMPOTENCY_CONFLICT",
                    idempotencyConflictMessage(begin.getConflictReason()));
            return;
        }

        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        boolean deterministic = false;
        try {
            executeInvocation(kind, serviceCode, serviceKey, variant, request, buffered);
            deterministic = true;
        } catch (StudioException ex) {
            buffered.resetBuffer();
            int status = statusFor(ex);
            try {
                writeError(kind, variant, request, buffered, status, ex.getCode(), ex.getMessage());
            } catch (IOException writeFailure) {
                tryMarkUnknown(begin);
                throw writeFailure;
            }
            deterministic = status < 500;
        } catch (Exception ex) {
            buffered.resetBuffer();
            try {
                writeError(kind, variant, request, buffered, 500,
                        StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage());
            } catch (IOException writeFailure) {
                tryMarkUnknown(begin);
                throw writeFailure;
            }
        }

        try {
            buffered.getWriter().flush();
        } catch (IOException ex) {
            tryMarkUnknown(begin);
            throw ex;
        } catch (RuntimeException ex) {
            tryMarkUnknown(begin);
            throw ex;
        }

        if (!deterministic) {
            tryMarkUnknown(begin);
            buffered.copyBodyToResponse();
            return;
        }
        try {
            idempotencyService.complete(begin.getGuardId(), begin.getOwnerToken(),
                    buffered.getStatus(), buffered.getContentType(), buffered.getContentAsByteArray());
        } catch (RuntimeException ex) {
            tryMarkUnknown(begin);
            response.resetBuffer();
            writeError(kind, variant, request, response, 409, "IDEMPOTENCY_RESULT_UNKNOWN",
                    "The write may have completed, but its idempotency result could not be recorded");
            return;
        }
        buffered.copyBodyToResponse();
    }

    private void tryMarkUnknown(RuntimeInvocationIdempotencyService.BeginResult begin) {
        try {
            idempotencyService.markUnknown(begin.getGuardId(), begin.getOwnerToken());
        } catch (RuntimeException ignored) {
            // A lost ownership CAS must never allow this Worker to overwrite another terminal state.
        }
    }

    private String singleInternalHeader(HttpServletRequest request, String headerName) {
        Enumeration<String> values = request.getHeaders(headerName);
        String result = null;
        int count = 0;
        while (values != null && values.hasMoreElements()) {
            result = values.nextElement();
            count++;
        }
        return count == 1 && result != null ? result.trim() : null;
    }

    private boolean idempotencyProtectedWrite(String kind, String method) {
        return ("data-ingestion-services".equals(kind) || "protocol-conversions".equals(kind))
                && StringUtils.hasText(method) && !"GET".equalsIgnoreCase(method)
                && !"HEAD".equalsIgnoreCase(method) && !"OPTIONS".equalsIgnoreCase(method);
    }

    private String idempotencyConflictMessage(RuntimeInvocationIdempotencyService.ConflictReason reason) {
        if (reason == RuntimeInvocationIdempotencyService.ConflictReason.FINGERPRINT_MISMATCH) {
            return "Idempotency-Key was already used for a different request";
        }
        if (reason == RuntimeInvocationIdempotencyService.ConflictReason.RUNNING) {
            return "A request with this Idempotency-Key is still running";
        }
        return "The previous request outcome is unknown and cannot be retried automatically";
    }

    private void writeStoredResponse(HttpServletResponse response,
                                     RuntimeInvocationIdempotencyService.StoredResponse stored) throws IOException {
        response.resetBuffer();
        response.setStatus(stored.getStatus());
        if (StringUtils.hasText(stored.getContentType())) {
            response.setContentType(stored.getContentType());
        }
        response.getOutputStream().write(stored.getBody());
    }

    private void invokeIngestion(String code, String key, String variant, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (soap(variant)) {
            if (request.getParameter("wsdl") != null) { writeXml(response, 200, "text/xml;charset=UTF-8", ingestionService.webServiceWsdl(code, key, originalUrl(request))); return; }
            String result = ingestionService.invokeWebService(code, key, request.getHeader("X-Data-Ingestion-Token"), headers(request), body(request), clientIp(request), request.getHeader("User-Agent"));
            writeXml(response, 200, "text/xml;charset=UTF-8", result); return;
        }
        DataIngestionInvokeResult result = ingestionService.invoke(code, key, request.getHeader("X-Data-Ingestion-Token"), headers(request), queryLastValue(request), form(request), jsonObject(request), request.getMethod(), clientIp(request), request.getHeader("User-Agent"));
        writeJson(response, 200, result);
    }
    private void invokeConversion(String code, String key, String variant, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (soap(variant)) {
            if (request.getParameter("wsdl") != null) { writeXml(response, 200, "text/xml;charset=UTF-8", conversionService.webServiceWsdl(code, key, originalUrl(request))); return; }
            String raw = body(request); WebServiceSoapVersion soapVersion = soapVersion(request.getContentType(), raw);
            String result = conversionService.invokeWebService(code, key, request.getHeader("X-Protocol-Conversion-Token"), headers(request), raw, clientIp(request), request.getHeader("User-Agent"));
            writeXml(response, 200, soapContentType(soapVersion), result); return;
        }
        ProtocolConversionProtocol protocol = conversionService.openSourceProtocol(code, key);
        String raw = body(request);
        ProtocolConversionInvokeResult result = conversionService.invoke(code, key, request.getHeader("X-Protocol-Conversion-Token"), headers(request), queryLastValue(request), form(request), raw, request.getMethod(), clientIp(request), request.getHeader("User-Agent"));
        if (protocol == ProtocolConversionProtocol.HTTP_XML) writeXml(response, 200, "application/xml;charset=UTF-8", conversionService.httpXmlResponseBody(result.getResponseBody()));
        else writeJson(response, 200, result.getResponseBody());
    }

    private void writeError(String kind, String variant, HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        if (!soap(variant)) { writeJson(response, status, Result.error(code, message)); return; }
        WebServiceSoapVersion soapVersion = "protocol-conversions".equals(kind)
                ? soapVersion(request.getContentType(), cachedBody(request)) : WebServiceSoapVersion.SOAP_11;
        String fault = "data-services".equals(kind) ? dataServiceService.webServiceFault(soapVersion, code, message)
                : "data-ingestion-services".equals(kind) ? ingestionService.webServiceFault(soapVersion, code, message)
                : conversionService.webServiceFault(soapVersion, code, message);
        writeXml(response, status, "protocol-conversions".equals(kind) ? soapContentType(soapVersion) : "text/xml;charset=UTF-8", fault);
    }
    private boolean internalTokenMatches(String actual) {
        String expected = properties.getInternalApiToken();
        return StringUtils.hasText(actual) && StringUtils.hasText(expected)
                && MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
    private RuntimeDebugIdentity runtimeInvocationIdentity(String kind, String serviceCode, String serviceKey,
                                                           Long targetClusterId) {
        RuntimeClusterEntity cluster = runtimeProcessCluster(targetClusterId);
        if (cluster == null) return null;
        String tenantId = null;
        Long projectId = null;
        Long resourceClusterId = null;
        Long resourceId = null;
        String resourceType = null;
        if ("data-services".equals(kind) && dataServiceMapper != null) {
            DataServiceDefinitionEntity entity = dataServiceMapper.selectOne(new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                    .eq(DataServiceDefinitionEntity::getServiceCode, serviceCode)
                    .eq(DataServiceDefinitionEntity::getServiceKey, serviceKey)
                    .eq(DataServiceDefinitionEntity::getRuntimeClusterId, targetClusterId).last("limit 1"));
            if (entity != null) {
                tenantId = entity.getTenantId();
                projectId = entity.getProjectId();
                resourceClusterId = entity.getRuntimeClusterId();
                resourceId = entity.getId();
                resourceType = "DATA_SERVICE";
            }
        } else if ("data-ingestion-services".equals(kind) && ingestionMapper != null) {
            DataIngestionServiceEntity entity = ingestionMapper.selectOne(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                    .eq(DataIngestionServiceEntity::getServiceCode, serviceCode)
                    .eq(DataIngestionServiceEntity::getServiceKey, serviceKey)
                    .eq(DataIngestionServiceEntity::getRuntimeClusterId, targetClusterId).last("limit 1"));
            if (entity != null) {
                tenantId = entity.getTenantId();
                projectId = entity.getProjectId();
                resourceClusterId = entity.getRuntimeClusterId();
                resourceId = entity.getId();
                resourceType = "DATA_INGESTION_SERVICE";
            }
        } else if ("protocol-conversions".equals(kind) && conversionMapper != null) {
            ProtocolConversionServiceEntity entity = conversionMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                    .eq(ProtocolConversionServiceEntity::getServiceCode, serviceCode)
                    .eq(ProtocolConversionServiceEntity::getServiceKey, serviceKey)
                    .eq(ProtocolConversionServiceEntity::getRuntimeClusterId, targetClusterId).last("limit 1"));
            if (entity != null) {
                tenantId = entity.getTenantId();
                projectId = entity.getProjectId();
                resourceClusterId = entity.getRuntimeClusterId();
                resourceId = entity.getId();
                resourceType = "PROTOCOL_CONVERSION_SERVICE";
            }
        }
        if (!targetClusterId.equals(resourceClusterId) || !StringUtils.hasText(tenantId)
                || projectId == null || !tenantId.equals(cluster.getTenantId())) return null;
        return new RuntimeDebugIdentity(tenantId, projectId, resourceClusterId, resourceType, resourceId);
    }
    private boolean runtimeProcessIdentityMatches(Long targetClusterId) {
        return runtimeProcessCluster(targetClusterId) != null;
    }
    private boolean projectClusterAuthorized(RuntimeDebugIdentity identity, Long targetClusterId) {
        return identity != null && workerAuthorizationService != null
                && workerAuthorizationService.isRuntimeClusterAuthorizedForProject(
                identity.tenantId, identity.projectId, targetClusterId);
    }
    private RuntimeClusterEntity runtimeProcessCluster(Long targetClusterId) {
        if (targetClusterId == null || runtimeClusterMapper == null) return null;
        RuntimeClusterEntity cluster = runtimeClusterMapper.selectById(targetClusterId);
        return cluster != null && Integer.valueOf(1).equals(cluster.getEnabled())
                && StringUtils.hasText(properties.getRuntimeClusterCode())
                && properties.getRuntimeClusterCode().trim().equalsIgnoreCase(cluster.getCode()) ? cluster : null;
    }
    private RuntimeDebugIdentity runtimeDebugIdentity(String kind, Long resourceId, Long targetClusterId) {
        if (targetClusterId == null || resourceId == null || runtimeClusterMapper == null) return null;
        RuntimeClusterEntity cluster = runtimeClusterMapper.selectById(targetClusterId);
        if (cluster == null || !Integer.valueOf(1).equals(cluster.getEnabled())
                || !StringUtils.hasText(properties.getRuntimeClusterCode())
                || !properties.getRuntimeClusterCode().trim().equalsIgnoreCase(cluster.getCode())) return null;
        String tenantId = null;
        Long projectId = null;
        Long resourceClusterId = null;
        if ("data-services".equals(kind) && dataServiceMapper != null) {
            DataServiceDefinitionEntity entity = dataServiceMapper.selectById(resourceId);
            if (entity != null) {
                tenantId = entity.getTenantId();
                projectId = entity.getProjectId();
                resourceClusterId = entity.getRuntimeClusterId();
            }
        } else if ("data-ingestion-services".equals(kind) && ingestionMapper != null) {
            DataIngestionServiceEntity entity = ingestionMapper.selectById(resourceId);
            if (entity != null) {
                tenantId = entity.getTenantId();
                projectId = entity.getProjectId();
                resourceClusterId = entity.getRuntimeClusterId();
            }
        } else if ("protocol-conversions".equals(kind) && conversionMapper != null) {
            ProtocolConversionServiceEntity entity = conversionMapper.selectById(resourceId);
            if (entity != null) {
                tenantId = entity.getTenantId();
                projectId = entity.getProjectId();
                resourceClusterId = entity.getRuntimeClusterId();
            }
        }
        if (!targetClusterId.equals(resourceClusterId) || !StringUtils.hasText(tenantId)
                || !tenantId.equals(cluster.getTenantId())) return null;
        return new RuntimeDebugIdentity(tenantId, projectId);
    }
    private <T> T convert(JsonNode payload, Class<T> type) throws IOException {
        return payload == null || payload.isNull() ? null : objectMapper.treeToValue(payload, type);
    }
    private Long parseLong(String value) { try { return StringUtils.hasText(value) ? Long.valueOf(value.trim()) : null; } catch (NumberFormatException ex) { return null; } }
    private void restoreContext(StudioRequestContext previous) { if (previous == null) StudioRequestContextHolder.clear(); else StudioRequestContextHolder.setContext(previous); }
    private boolean soap(String variant) { return "SOAP".equalsIgnoreCase(variant); }
    private String originalUrl(HttpServletRequest request) { String value = request.getHeader(ORIGINAL_URL_HEADER); return StringUtils.hasText(value) ? value : request.getRequestURL().toString(); }
    private String body(HttpServletRequest request) throws IOException {
        return new String(requestBodyBytes(request), StandardCharsets.UTF_8);
    }
    private byte[] requestBodyBytes(HttpServletRequest request) throws IOException {
        Object cached = request.getAttribute(CACHED_REQUEST_BODY_ATTRIBUTE);
        if (cached instanceof byte[]) {
            return (byte[]) cached;
        }
        ensureDeclaredRequestBodyWithinLimit(request);
        int limit = runtimeInvocationBodyLimit();
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        InputStream input = request.getInputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                throw payloadTooLarge();
            }
            output.write(buffer, 0, read);
        }
        byte[] value = output.toByteArray();
        request.setAttribute(CACHED_REQUEST_BODY_ATTRIBUTE, value);
        return value;
    }
    @SuppressWarnings("unchecked") private Map<String, Object> jsonMap(HttpServletRequest request) throws IOException { Object value=jsonObject(request); return value instanceof Map ? (Map<String,Object>) value : new LinkedHashMap<String,Object>(); }
    private Object jsonObject(HttpServletRequest request) throws IOException {
        if (request.getContentType()==null || !request.getContentType().toLowerCase(Locale.ROOT).contains("application/json")) return null;
        byte[] body = requestBodyBytes(request);
        return body.length == 0 ? null : objectMapper.readValue(body, Object.class);
    }
    private JsonNode jsonPayload(HttpServletRequest request) throws IOException {
        byte[] body = requestBodyBytes(request);
        return body.length == 0 ? null : objectMapper.readTree(body);
    }
    private String cachedBody(HttpServletRequest request) {
        Object cached = request.getAttribute(CACHED_REQUEST_BODY_ATTRIBUTE);
        return cached instanceof byte[] ? new String((byte[]) cached, StandardCharsets.UTF_8) : "";
    }
    private void ensureDeclaredRequestBodyWithinLimit(HttpServletRequest request) {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > runtimeInvocationBodyLimit()) {
            throw payloadTooLarge();
        }
    }
    private void cacheInvocationRequestBody(HttpServletRequest request) throws IOException {
        String method = request.getMethod();
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)) {
            requestBodyBytes(request);
        }
    }
    private int runtimeInvocationBodyLimit() {
        Integer configured = properties.getRuntimeInvocationMaxBodyBytes();
        return Math.max(1024, configured == null ? 10 * 1024 * 1024 : configured.intValue());
    }
    private StudioException payloadTooLarge() {
        return new StudioException(PAYLOAD_TOO_LARGE_CODE,
                "Request body exceeds the runtime Worker limit");
    }
    private Map<String,Object> headers(HttpServletRequest request) {
        Map<String,Object> values = new LinkedHashMap<String,Object>();
        boolean transportAuthorization = "true".equalsIgnoreCase(request.getHeader(TRANSPORT_AUTHORIZATION_HEADER));
        Set<String> transportHeaderNames = transportHeaderNames(request.getHeader(TRANSPORT_HEADER_NAMES_HEADER));
        Set<String> connectionHeaderNames = connectionHeaderNames(request);
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (TOKEN_HEADER.equalsIgnoreCase(name) || VARIANT_HEADER.equalsIgnoreCase(name)
                    || ORIGINAL_URL_HEADER.equalsIgnoreCase(name) || TARGET_CLUSTER_ID_HEADER.equalsIgnoreCase(name)
                    || TRANSPORT_AUTHORIZATION_HEADER.equalsIgnoreCase(name)
                    || TRANSPORT_HEADER_NAMES_HEADER.equalsIgnoreCase(name)
                    || "Idempotency-Key".equalsIgnoreCase(name)
                    || runtimeEndpointHeaderPolicy.isHopByHop(name, connectionHeaderNames)
                    || runtimeEndpointHeaderPolicy.isReservedStudioHeader(name)
                    || transportHeaderNames.contains(lowerName)
                    || (transportAuthorization && "Authorization".equalsIgnoreCase(name))) {
                continue;
            }
            values.put(name, request.getHeader(name));
        }
        return values;
    }
    private Set<String> transportHeaderNames(String value) {
        Set<String> names = new HashSet<String>();
        if (!StringUtils.hasText(value)) return names;
        for (String name : value.split(",")) {
            if (StringUtils.hasText(name)) names.add(name.trim().toLowerCase(Locale.ROOT));
        }
        return names;
    }
    private Set<String> connectionHeaderNames(HttpServletRequest request) {
        Set<String> names = new HashSet<String>();
        Enumeration<String> values = request.getHeaders("Connection");
        while (values != null && values.hasMoreElements()) {
            for (String name : values.nextElement().split(",")) {
                if (StringUtils.hasText(name)) names.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
    private Map<String,Object> query(HttpServletRequest request) {
        return allParameterValues(parseUrlEncoded(request.getQueryString()));
    }
    private Map<String,Object> queryLastValue(HttpServletRequest request) {
        Map<String,Object> values = new LinkedHashMap<String,Object>();
        parseUrlEncoded(request.getQueryString()).forEach((key, items) ->
                values.put(key, items.isEmpty() ? null : items.get(items.size() - 1)));
        return values;
    }
    private Map<String,Object> form(HttpServletRequest request) throws IOException {
        if (request.getContentType() == null
                || !request.getContentType().toLowerCase(Locale.ROOT)
                .contains("application/x-www-form-urlencoded")) {
            return new LinkedHashMap<String,Object>();
        }
        return allParameterValues(parseUrlEncoded(body(request)));
    }
    private Map<String,List<String>> parseUrlEncoded(String encoded) {
        Map<String,List<String>> values = new LinkedHashMap<String,List<String>>();
        if (!StringUtils.hasText(encoded)) return values;
        try {
            for (String pair : encoded.split("&", -1)) {
                if (pair.isEmpty()) continue;
                int separator = pair.indexOf('=');
                String rawName = separator < 0 ? pair : pair.substring(0, separator);
                String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
                String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                values.computeIfAbsent(name, ignored -> new ArrayList<String>()).add(value);
            }
            return values;
        } catch (IllegalArgumentException ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Request parameters contain invalid URL encoding");
        }
    }
    private Map<String,Object> allParameterValues(Map<String,List<String>> source) {
        Map<String,Object> values = new LinkedHashMap<String,Object>();
        source.forEach((key, items) -> values.put(key,
                items.isEmpty() ? null : items.size() == 1 ? items.get(0) : items.toArray(new String[0])));
        return values;
    }
    private String clientIp(HttpServletRequest request) { String forwarded=request.getHeader("X-Forwarded-For");if(StringUtils.hasText(forwarded))return forwarded.split(",")[0].trim();String realIp=request.getHeader("X-Real-IP");if(StringUtils.hasText(realIp))return realIp.trim();return request.getRemoteAddr(); }
    private int statusFor(StudioException ex){if(StudioErrorCode.UNAUTHORIZED.equals(ex.getCode()))return 401;if(StudioErrorCode.FORBIDDEN.equals(ex.getCode()))return 403;if(StudioErrorCode.NOT_FOUND.equals(ex.getCode()))return 404;if(StudioErrorCode.SERVICE_UNAVAILABLE.equals(ex.getCode()))return 503;if(StudioErrorCode.INTERNAL_SERVER_ERROR.equals(ex.getCode()))return 500;if(PAYLOAD_TOO_LARGE_CODE.equals(ex.getCode()))return 413;return 400;}
    private WebServiceSoapVersion soapVersion(String type,String body){return(type!=null&&type.toLowerCase(Locale.ROOT).contains("application/soap+xml"))||(body!=null&&body.contains("http://www.w3.org/2003/05/soap-envelope"))?WebServiceSoapVersion.SOAP_12:WebServiceSoapVersion.SOAP_11;}
    private String soapContentType(WebServiceSoapVersion version){return version==WebServiceSoapVersion.SOAP_12?"application/soap+xml;charset=UTF-8":"text/xml;charset=UTF-8";}
    private void writeInternalAuthenticationFailure(HttpServletResponse response) throws IOException {
        response.setHeader(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER,
                RuntimeInternalHeaders.INTERNAL_AUTHENTICATION);
        writeJson(response, 401, Result.error(StudioErrorCode.UNAUTHORIZED,
                "Internal runtime authentication failed"));
    }
    private void writeJson(HttpServletResponse response,int status,Object value)throws IOException{response.setStatus(status);response.setCharacterEncoding(StandardCharsets.UTF_8.name());response.setContentType("application/json;charset=UTF-8");response.getWriter().write(objectMapper.writeValueAsString(value));}
    private void writeXml(HttpServletResponse response,int status,String type,String value)throws IOException{response.setStatus(status);response.setCharacterEncoding(StandardCharsets.UTF_8.name());response.setContentType(type);response.getWriter().write(value==null?"":value);}
    private static final class RuntimeDebugIdentity {
        private final String tenantId;
        private final Long projectId;
        private final Long runtimeClusterId;
        private final String resourceType;
        private final Long resourceId;
        private RuntimeDebugIdentity(String tenantId, Long projectId) {
            this(tenantId, projectId, null, null, null);
        }
        private RuntimeDebugIdentity(String tenantId, Long projectId, Long runtimeClusterId,
                                     String resourceType, Long resourceId) {
            this.tenantId = tenantId;
            this.projectId = projectId;
            this.runtimeClusterId = runtimeClusterId;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
        }
    }
}
