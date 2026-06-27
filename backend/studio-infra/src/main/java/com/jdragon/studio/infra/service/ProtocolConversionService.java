package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.ProtocolConversionMode;
import com.jdragon.studio.dto.enums.ProtocolConversionProtocol;
import com.jdragon.studio.dto.enums.ProtocolConversionStatus;
import com.jdragon.studio.dto.enums.WebServiceSoapVersion;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.ProtocolConversionDebugResult;
import com.jdragon.studio.dto.model.ProtocolConversionFieldMapping;
import com.jdragon.studio.dto.model.ProtocolConversionFixedField;
import com.jdragon.studio.dto.model.ProtocolConversionInvokeResult;
import com.jdragon.studio.dto.model.ProtocolConversionServiceListView;
import com.jdragon.studio.dto.model.ProtocolConversionServiceView;
import com.jdragon.studio.dto.model.ProtocolConversionSubscriptionView;
import com.jdragon.studio.dto.model.ProtocolConversionTraceStepView;
import com.jdragon.studio.dto.model.ProtocolConversionTraceView;
import com.jdragon.studio.dto.model.TransformerBinding;
import com.jdragon.studio.dto.model.WebServiceConfig;
import com.jdragon.studio.dto.model.request.DataServiceSubscriptionCreateRequest;
import com.jdragon.studio.dto.model.request.ProtocolConversionDebugRequest;
import com.jdragon.studio.dto.model.request.ProtocolConversionServiceSaveRequest;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionSubscriptionEntity;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionSubscriptionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProtocolConversionService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_BATCH_SIZE = 1;
    private static final String OPEN_PATH_PREFIX = "/openapi/protocol-conversions";
    private static final String WS_OPEN_PATH_PREFIX = "/openapi/ws/protocol-conversions";
    private static final String TRACE_SECTION_TITLE = "Protocol Conversion Trace";
    private static final String TRACE_JSON_SECTION_TITLE = "Protocol Conversion Trace JSON";
    private static final String TRACE_STATUS_SUCCESS = "SUCCESS";
    private static final String TRACE_STATUS_FAILED = "FAILED";
    private static final String DEFAULT_NO_TOKEN_SUBSCRIPTION_NAME = "免 Token 调用";
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern RECORDS_REPEAT_PATTERN = Pattern.compile("\\{\\{#records}}([\\s\\S]*?)\\{\\{/records}}");
    private static final int HTTP_TIMEOUT_SECONDS = 30;

    private final ProtocolConversionServiceMapper serviceMapper;
    private final ProtocolConversionSubscriptionMapper subscriptionMapper;
    private final DataSourceService dataSourceService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final ObjectMapper objectMapper;
    private final DataServiceTokenSupport tokenSupport = new DataServiceTokenSupport();
    private final ProtocolConversionAccessLogSupport accessLogSupport;
    private final OpenServiceInvocationLogSupport invocationLogSupport;
    private final OpenServiceInvocationLogService invocationLogService;
    private final WebServiceSupport webServiceSupport = new WebServiceSupport();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .build();

    public ProtocolConversionService(ProtocolConversionServiceMapper serviceMapper,
                                     ProtocolConversionSubscriptionMapper subscriptionMapper,
                                     ProtocolConversionAccessLogMapper accessLogMapper,
                                     ProtocolConversionAccessCounterMapper accessCounterMapper,
                                     DataSourceService dataSourceService,
                                     StudioSecurityService securityService,
                                     ProjectResourceAccessService projectResourceAccessService,
                                     ObjectMapper objectMapper,
                                     OpenServiceInvocationLogService invocationLogService) {
        this.serviceMapper = serviceMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.dataSourceService = dataSourceService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.objectMapper = objectMapper;
        this.accessLogSupport = new ProtocolConversionAccessLogSupport(accessLogMapper, accessCounterMapper);
        this.invocationLogSupport = new OpenServiceInvocationLogSupport();
        this.invocationLogService = invocationLogService;
    }

    public PageView<ProtocolConversionServiceListView> list(Integer pageNo,
                                                            Integer pageSize,
                                                            String keyword,
                                                            String status) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return PageView.of(safePageNo, safePageSize, 0L, new ArrayList<ProtocolConversionServiceListView>());
        }
        String normalizedKeyword = normalizeText(keyword);
        String normalizedStatus = normalizeText(status);
        Page<ProtocolConversionServiceEntity> page = new Page<ProtocolConversionServiceEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<ProtocolConversionServiceEntity> queryWrapper = new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                .select(ProtocolConversionServiceEntity::getId,
                        ProtocolConversionServiceEntity::getTenantId,
                        ProtocolConversionServiceEntity::getProjectId,
                        ProtocolConversionServiceEntity::getDeleted,
                        ProtocolConversionServiceEntity::getCreatedAt,
                        ProtocolConversionServiceEntity::getUpdatedAt,
                        ProtocolConversionServiceEntity::getCreatedBy,
                        ProtocolConversionServiceEntity::getServiceCode,
                        ProtocolConversionServiceEntity::getServiceName,
                        ProtocolConversionServiceEntity::getStatus,
                        ProtocolConversionServiceEntity::getEndpointPath,
                        ProtocolConversionServiceEntity::getWebserviceEndpointPath,
                        ProtocolConversionServiceEntity::getTokenRequired,
                        ProtocolConversionServiceEntity::getDefaultSubscriptionName,
                        ProtocolConversionServiceEntity::getSourceProtocol,
                        ProtocolConversionServiceEntity::getSourceMethod,
                        ProtocolConversionServiceEntity::getSourceDataNodePath,
                        ProtocolConversionServiceEntity::getConversionMode,
                        ProtocolConversionServiceEntity::getTargetDatasourceId,
                        ProtocolConversionServiceEntity::getTargetDatasourceNameSnapshot,
                        ProtocolConversionServiceEntity::getTargetPath,
                        ProtocolConversionServiceEntity::getTargetProtocol,
                        ProtocolConversionServiceEntity::getTargetMethod,
                        ProtocolConversionServiceEntity::getPayloadMode)
                .eq(ProtocolConversionServiceEntity::getTenantId, securityService.currentTenantId());
        List<Long> sharedIds = projectResourceAccessService.sharedResourceIdList(StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE);
        if (sharedIds.isEmpty()) {
            queryWrapper.eq(ProtocolConversionServiceEntity::getProjectId, currentProjectId);
        } else {
            queryWrapper.and(wrapper -> wrapper.eq(ProtocolConversionServiceEntity::getProjectId, currentProjectId)
                    .or()
                    .in(ProtocolConversionServiceEntity::getId, sharedIds));
        }
        queryWrapper
                .and(hasText(normalizedKeyword), wrapper -> wrapper.like(ProtocolConversionServiceEntity::getServiceName, normalizedKeyword)
                        .or()
                        .like(ProtocolConversionServiceEntity::getServiceCode, normalizedKeyword)
                        .or()
                        .like(ProtocolConversionServiceEntity::getTargetDatasourceNameSnapshot, normalizedKeyword))
                .eq(hasText(normalizedStatus), ProtocolConversionServiceEntity::getStatus,
                        normalizedStatus == null ? null : normalizedStatus.toUpperCase(Locale.ROOT))
                .orderByDesc(ProtocolConversionServiceEntity::getUpdatedAt)
                .orderByDesc(ProtocolConversionServiceEntity::getId);
        Page<ProtocolConversionServiceEntity> entityPage = serviceMapper.selectPage(page, queryWrapper);
        List<ProtocolConversionServiceListView> items = new ArrayList<ProtocolConversionServiceListView>();
        for (ProtocolConversionServiceEntity entity : entityPage.getRecords()) {
            items.add(toListView(entity));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    public ProtocolConversionServiceView get(Long id) {
        return toView(requireAccessibleEntity(id));
    }

    @Transactional
    public ProtocolConversionServiceView save(ProtocolConversionServiceSaveRequest request) {
        validateSaveRequest(request);
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        ProtocolConversionServiceEntity entity = request.getId() == null
                ? new ProtocolConversionServiceEntity()
                : requireWritableEntity(request.getId());
        ensureUniqueServiceCode(currentProjectId, request.getServiceCode(), entity.getId());
        ensureUniqueServiceName(currentProjectId, request.getServiceName(), entity.getId());

        DataSourceDefinition targetDatasource = requiredHttpDatasource(request.getTargetDatasourceId());
        ProtocolConversionProtocol sourceProtocol = request.getSourceProtocol() == null
                ? ProtocolConversionProtocol.HTTP_JSON
                : request.getSourceProtocol();
        ProtocolConversionProtocol targetProtocol = request.getTargetProtocol() == null
                ? ProtocolConversionProtocol.HTTP_JSON
                : request.getTargetProtocol();
        ProtocolConversionMode conversionMode = request.getConversionMode() == null
                ? ProtocolConversionMode.FIELD_MAPPING
                : request.getConversionMode();

        entity.setTenantId(securityService.currentTenantId());
        entity.setProjectId(currentProjectId);
        entity.setCreatedBy(entity.getId() == null ? securityService.currentUserId() : entity.getCreatedBy());
        entity.setServiceCode(normalizeRequiredText(request.getServiceCode(), "Service code is required"));
        entity.setServiceName(normalizeRequiredText(request.getServiceName(), "Service name is required"));
        entity.setStatus(resolveSavedStatus(entity).name());
        entity.setServiceKey(hasText(entity.getServiceKey()) ? entity.getServiceKey() : tokenSupport.generateServiceKey());
        entity.setEndpointPath(buildEndpointPath(entity.getServiceCode(), entity.getServiceKey()));
        entity.setWebserviceEndpointPath(buildWebServiceEndpointPath(entity.getServiceCode(), entity.getServiceKey()));
        entity.setTokenRequired(Boolean.FALSE.equals(request.getTokenRequired()) ? Integer.valueOf(0) : Integer.valueOf(1));
        entity.setDefaultSubscriptionName(normalizeDefaultSubscriptionName(request.getDefaultSubscriptionName()));

        entity.setSourceProtocol(sourceProtocol.name());
        entity.setSourceMethod(normalizeMethod(request.getSourceMethod(), sourceProtocol == ProtocolConversionProtocol.HTTP_JSON ? "POST" : "POST"));
        entity.setSourceDataNodePath(normalizeText(request.getSourceDataNodePath()));
        entity.setWebserviceConfigJson(toWebServiceConfigMap(request.getWebserviceConfig(), "protocol-conversion", entity.getServiceCode()));

        entity.setConversionMode(conversionMode.name());
        entity.setFieldMappingsJson(toMapList(request.getFieldMappings()));
        entity.setRawTransformersJson(transformerMapList(request.getRawTransformers()));
        entity.setFixedFieldsJson(toMapList(request.getFixedFields()));
        entity.setBodyBridgeOptionsJson(safeMap(request.getBodyBridgeOptions()));
        entity.setRequestPassthroughJson(safeMap(request.getRequestPassthrough()));

        entity.setTargetDatasourceId(targetDatasource.getId());
        entity.setTargetDatasourceNameSnapshot(targetDatasource.getName());
        entity.setTargetPath(normalizeRequiredText(request.getTargetPath(), "Target path is required"));
        entity.setTargetProtocol(targetProtocol.name());
        entity.setTargetMethod(normalizeMethod(request.getTargetMethod(), "POST"));
        entity.setTargetHeadersJson(safeMap(request.getTargetHeaders()));
        entity.setTargetQueryJson(safeMap(request.getTargetQuery()));
        entity.setTargetWebserviceConfigJson(toWebServiceConfigMap(request.getTargetWebserviceConfig(), "protocol-conversion-target", entity.getServiceCode()));
        entity.setTargetBodyTemplate(normalizeText(request.getTargetBodyTemplate()));
        entity.setTargetDataNodePath(normalizeText(request.getTargetDataNodePath()));
        entity.setPayloadMode((request.getPayloadMode() == null ? DataIngestionPayloadMode.OBJECT : request.getPayloadMode()).name());
        entity.setBatchSize(Integer.valueOf(DEFAULT_BATCH_SIZE));
        entity.setResponseStatusJson(safeMap(request.getResponseStatus()));

        if (entity.getId() == null) {
            serviceMapper.insert(entity);
        } else {
            serviceMapper.updateById(entity);
        }
        return get(entity.getId());
    }

    @Transactional
    public void delete(Long id) {
        requireWritableEntity(id);
        serviceMapper.deleteById(id);
    }

    @Transactional
    public ProtocolConversionServiceView publish(Long id) {
        ProtocolConversionServiceEntity entity = requireWritableEntity(id);
        validateExecutable(toView(entity));
        entity.setStatus(ProtocolConversionStatus.ONLINE.name());
        if (!hasText(entity.getServiceKey())) {
            entity.setServiceKey(tokenSupport.generateServiceKey());
        }
        entity.setEndpointPath(buildEndpointPath(entity.getServiceCode(), entity.getServiceKey()));
        entity.setWebserviceEndpointPath(buildWebServiceEndpointPath(entity.getServiceCode(), entity.getServiceKey()));
        serviceMapper.updateById(entity);
        return get(id);
    }

    @Transactional
    public ProtocolConversionServiceView offline(Long id) {
        ProtocolConversionServiceEntity entity = requireWritableEntity(id);
        entity.setStatus(ProtocolConversionStatus.OFFLINE.name());
        serviceMapper.updateById(entity);
        return get(id);
    }

    public ProtocolConversionDebugResult debug(Long id, ProtocolConversionDebugRequest request) {
        ProtocolConversionServiceView view = get(id);
        validateExecutable(view);
        String rawBody = debugRawBody(view, request);
        String requestId = newRequestId();
        ProtocolConversionTraceView trace = newTrace(requestId);
        try {
            return execute(view,
                    request == null ? new LinkedHashMap<String, Object>() : request.getHeaders(),
                    request == null ? new LinkedHashMap<String, Object>() : request.getQuery(),
                    request == null ? new LinkedHashMap<String, Object>() : request.getForm(),
                    request == null ? null : request.getBody(),
                    rawBody,
                    requestId,
                    false,
                    true,
                    trace);
        } catch (StudioException ex) {
            return failedDebugResult(view, requestId, trace, ex.getMessage());
        } catch (RuntimeException ex) {
            return failedDebugResult(view, requestId, trace, rootMessage(ex));
        }
    }

    public ProtocolConversionInvokeResult invoke(String serviceCode,
                                                  String serviceKey,
                                                  String token,
                                                 Map<String, Object> headers,
                                                 Map<String, Object> query,
                                                 Map<String, Object> form,
                                                 String rawBody,
                                                 String requestMethod,
                                                 String clientIp,
                                                  String userAgent) {
        return invokeInternal(serviceCode, serviceKey, token, headers, query, form, rawBody, requestMethod, clientIp, userAgent, null);
    }

    public ProtocolConversionProtocol openSourceProtocol(String serviceCode, String serviceKey) {
        return toView(requireOpenEntity(serviceCode, serviceKey)).getSourceProtocol();
    }

    public String httpXmlResponseBody(Object responseBody) {
        return objectToXml("root", responseBody == null ? new LinkedHashMap<String, Object>() : responseBody);
    }

    public String invokeWebService(String serviceCode,
                                   String serviceKey,
                                   String token,
                                   Map<String, Object> httpHeaders,
                                   String soapEnvelope,
                                   String clientIp,
                                   String userAgent) {
        WebServiceSupport.ParsedSoapRequest parsed = webServiceSupport.parse(soapEnvelope);
        ProtocolConversionServiceEntity entity = requireOpenEntity(serviceCode, serviceKey);
        ProtocolConversionServiceView view = toView(entity);
        validateSoapSource(view, parsed);
        String effectiveToken = hasText(token)
                ? token
                : webServiceSupport.tokenFromSoapHeader(parsed, "token", "protocolConversionToken");
        ProtocolConversionInvokeResult result = invokeInternal(serviceCode,
                serviceKey,
                effectiveToken,
                webServiceSupport.mergeHeaders(httpHeaders, parsed.getHeaders()),
                parsed.getBody(),
                new LinkedHashMap<String, Object>(),
                soapEnvelope,
                "SOAP",
                clientIp,
                userAgent,
                parsed);
        return webServiceSupport.successEnvelope(normalizedSourceWebServiceConfig(view), responseBodyOrEmpty(result), parsed.getSoapVersion());
    }

    public String webServiceWsdl(String serviceCode, String serviceKey, String endpointUrl) {
        ProtocolConversionServiceEntity entity = requireOpenEntity(serviceCode, serviceKey);
        ProtocolConversionServiceView view = toView(entity);
        WebServiceConfig config = normalizedSourceWebServiceConfig(view);
        return genericWsdl(view.getServiceName(), config, endpointUrl);
    }

    public String webServiceFault(WebServiceSoapVersion version, String code, String message) {
        return webServiceSupport.faultEnvelope(version, code, message);
    }

    private ProtocolConversionInvokeResult invokeInternal(String serviceCode,
                                                          String serviceKey,
                                                          String token,
                                                          Map<String, Object> headers,
                                                          Map<String, Object> query,
                                                          Map<String, Object> form,
                                                          String rawBody,
                                                          String requestMethod,
                                                          String clientIp,
                                                          String userAgent,
                                                          WebServiceSupport.ParsedSoapRequest parsedSoap) {
        long startedAt = System.nanoTime();
        LocalDateTime occurredAt = LocalDateTime.now();
        String requestId = newRequestId();
        ProtocolConversionServiceEntity entity = null;
        ProtocolConversionSubscriptionEntity subscription = null;
        ProtocolConversionInvokeResult result = null;
        boolean success = false;
        int httpStatus = 200;
        Integer targetHttpStatus = null;
        String errorCode = null;
        String errorMessage = null;
        ProtocolConversionTraceView trace = newTrace(requestId);
        trace.setSourceRequest(sourceRequestTraceStep(null, requestMethod, headers, query, form, rawBody));
        OpenServiceInvocationLogSupport.LogScope logScope = invocationLogSupport.open(requestId,
                OpenServiceInvocationLogService.DOMAIN_PROTOCOL_CONVERSIONS,
                null);
        try {
            entity = requireOpenEntity(serviceCode, serviceKey);
            subscription = resolveInvocationSubscription(entity, token);
            ProtocolConversionServiceView view = toView(entity);
            validateSourceInvocationMethod(view, requestMethod);
            if (parsedSoap != null) {
                validateSoapSource(view, parsedSoap);
            }
            result = execute(view, headers, query, form, parsedSoap == null ? null : parsedSoap.getBody(), rawBody, requestId, true, false, trace);
            targetHttpStatus = result.getTargetHttpStatus();
            success = true;
            return result;
        } catch (StudioException ex) {
            httpStatus = statusForException(ex);
            errorCode = ex.getCode();
            errorMessage = ex.getMessage();
            if (ex instanceof TargetResponseException) {
                TargetResponse response = ((TargetResponseException) ex).getTargetResponse();
                targetHttpStatus = response == null ? null : Integer.valueOf(response.status);
            }
            throw ex;
        } catch (RuntimeException ex) {
            httpStatus = 500;
            errorCode = StudioErrorCode.INTERNAL_SERVER_ERROR;
            errorMessage = ex.getMessage();
            throw ex;
        } finally {
            long receivedCount = result == null || result.getReceivedCount() == null ? 0L : result.getReceivedCount().longValue();
            long successCount = result == null || result.getSuccessCount() == null ? 0L : result.getSuccessCount().longValue();
            long failedCount = result == null || result.getFailedCount() == null ? 0L : result.getFailedCount().longValue();
            if (logScope != null) {
                logScope.close();
            }
            String capturedLog = logScope == null ? null : logScope.content();
            String systemLog = buildInvocationSystemLog(entity, subscription, defaultSubscriptionNameForLog(entity), requestId, requestMethod,
                    occurredAt, startedAt, success, httpStatus, targetHttpStatus, errorCode, errorMessage, receivedCount, successCount, failedCount);
            String archiveContent = buildInvocationArchiveLog(systemLog, headers, query, form, rawBody, result, trace, capturedLog);
            OpenServiceInvocationLogService.ArchiveResult archiveResult = invocationLogService.archive(
                    OpenServiceInvocationLogService.DOMAIN_PROTOCOL_CONVERSIONS,
                    "protocol-conversion",
                    requestId,
                    occurredAt,
                    archiveContent);
            accessLogSupport.recordAccessLog(entity, subscription, defaultSubscriptionNameForLog(entity), requestId, requestMethod, occurredAt, startedAt,
                    success, httpStatus, targetHttpStatus, errorCode, errorMessage, systemLog, clientIp, userAgent, receivedCount, successCount, failedCount,
                    archiveResult);
        }
    }

    private <T extends ProtocolConversionInvokeResult> T execute(ProtocolConversionServiceView service,
                                                                 Map<String, Object> headers,
                                                                 Map<String, Object> query,
                                                                 Map<String, Object> form,
                                                                 Object parsedSoapBody,
                                                                 String rawBody,
                                                                 String requestId,
                                                                 boolean enforceStatus,
                                                                 boolean includeTargetRequest,
                                                                 ProtocolConversionTraceView trace) {
        if (enforceStatus && service.getStatus() != ProtocolConversionStatus.ONLINE) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Protocol conversion service is not available");
        }
        validateExecutable(service);
        SourcePayload sourcePayload;
        List<Map<String, Object>> rows;
        TargetRequest targetRequest;
        TargetResponse targetResponse;
        try {
            sourcePayload = parseSourcePayload(service, parsedSoapBody, rawBody);
            setSourceTrace(trace, sourceRequestTraceStep(service, service.getSourceMethod(), headers, query, form,
                    hasText(rawBody) ? rawBody : sourcePayload.body));
        } catch (RuntimeException ex) {
            setSourceTrace(trace, failedStep(sourceRequestTraceStep(service, service.getSourceMethod(), headers, query, form, rawBody), rootMessage(ex)));
            throw ex;
        }
        try {
            rows = buildRows(service, sourcePayload, headers, query, form, rawBody);
            targetRequest = buildTargetRequest(service, rows, sourcePayload, headers, query, form, rawBody);
            setConvertedRequestTrace(trace, convertedRequestTraceStep(service, targetRequest, rows.size()));
        } catch (RuntimeException ex) {
            setConvertedRequestTrace(trace, failedStep(baseStep("convertedRequest", "目标请求生成", service.getTargetProtocol()), rootMessage(ex)));
            throw ex;
        }
        try {
            targetResponse = sendTarget(service, targetRequest);
            setTargetResponseTrace(trace, targetResponseTraceStep(service, targetResponse));
        } catch (TargetResponseException ex) {
            setTargetResponseTrace(trace, failedStep(targetResponseTraceStep(service, ex.getTargetResponse()), rootMessage(ex)));
            throw ex;
        } catch (RuntimeException ex) {
            setTargetResponseTrace(trace, failedStep(baseStep("targetResponse", "原响应", service.getTargetProtocol()), rootMessage(ex)));
            throw ex;
        }

        @SuppressWarnings("unchecked")
        T result = (T) (includeTargetRequest ? new ProtocolConversionDebugResult() : new ProtocolConversionInvokeResult());
        result.setRequestId(requestId);
        result.setServiceCode(service.getServiceCode());
        result.setSourceProtocol(service.getSourceProtocol() == null ? null : service.getSourceProtocol().name());
        result.setStatus("SUCCESS");
        Object targetBody;
        Object responseBody;
        try {
            validateTargetResponse(service, targetResponse);
            targetBody = parseResponseBody(service.getTargetProtocol(), targetResponse.body);
            responseBody = extractResponseBody(service.getTargetProtocol(), targetBody);
            setConvertedResponseTrace(trace, convertedResponseTraceStep(service, responseBody));
        } catch (RuntimeException ex) {
            setConvertedResponseTrace(trace, failedStep(baseStep("convertedResponse", "对外响应生成", service.getSourceProtocol()), rootMessage(ex)));
            throw ex;
        }
        result.setTargetHttpStatus(Integer.valueOf(targetResponse.status));
        result.setTargetContentType(targetResponse.contentType);
        result.setTargetBody(targetBody);
        result.setResponseBody(responseBody);
        result.setReceivedCount(Long.valueOf(rows.size()));
        result.setSuccessCount(Long.valueOf(rows.size()));
        result.setFailedCount(Long.valueOf(0L));
        if (result instanceof ProtocolConversionDebugResult) {
            ((ProtocolConversionDebugResult) result).setTargetRequest(sanitizeTargetRequest(targetRequest.snapshot()));
            ((ProtocolConversionDebugResult) result).setConversionTrace(trace);
        }
        return result;
    }

    private ProtocolConversionDebugResult failedDebugResult(ProtocolConversionServiceView service,
                                                            String requestId,
                                                            ProtocolConversionTraceView trace,
                                                            String errorMessage) {
        ProtocolConversionDebugResult result = new ProtocolConversionDebugResult();
        result.setRequestId(requestId);
        result.setServiceCode(service == null ? null : service.getServiceCode());
        result.setSourceProtocol(service == null || service.getSourceProtocol() == null ? null : service.getSourceProtocol().name());
        result.setStatus(TRACE_STATUS_FAILED);
        result.setReceivedCount(Long.valueOf(0L));
        result.setSuccessCount(Long.valueOf(0L));
        result.setFailedCount(Long.valueOf(1L));
        result.setResponseBody(Collections.singletonMap("error", errorMessage));
        result.setConversionTrace(trace);
        return result;
    }

    private ProtocolConversionTraceView newTrace(String requestId) {
        ProtocolConversionTraceView trace = new ProtocolConversionTraceView();
        trace.setRequestId(requestId);
        return trace;
    }

    private ProtocolConversionTraceStepView sourceRequestTraceStep(ProtocolConversionServiceView service,
                                                                   String method,
                                                                   Map<String, Object> headers,
                                                                   Map<String, Object> query,
                                                                   Map<String, Object> form,
                                                                   Object body) {
        ProtocolConversionProtocol protocol = service == null ? null : service.getSourceProtocol();
        ProtocolConversionTraceStepView step = baseStep("sourceRequest", "原请求", protocol);
        step.setMethod(normalizeMethod(method, "POST"));
        step.setHeaders(sanitizeHeaders(headers));
        step.setQuery(sanitizeSensitiveMap(query));
        step.setForm(sanitizeSensitiveMap(form));
        step.setBodyFormat(bodyFormat(protocol));
        step.setBodyPreview(previewForTrace(body));
        step.setSummary("调用方传入的 Header / Query / Form / Body");
        return step;
    }

    private ProtocolConversionTraceStepView convertedRequestTraceStep(ProtocolConversionServiceView service,
                                                                      TargetRequest request,
                                                                      int rowCount) {
        ProtocolConversionTraceStepView step = baseStep("convertedRequest", "目标请求生成", service == null ? null : service.getTargetProtocol());
        step.setMethod(request == null ? null : request.method);
        step.setUrl(request == null ? null : request.url);
        step.setContentType(request == null ? null : request.contentType);
        step.setHeaders(sanitizeHeaders(request == null ? null : request.headers));
        step.setBodyFormat(bodyFormat(service == null ? null : service.getTargetProtocol()));
        step.setBodyPreview(previewForTrace(request == null ? null : request.body));
        step.setSummary("已根据透传、显式参数、字段映射或 Body Bridge 生成目标请求，记录数 " + rowCount);
        return step;
    }

    private ProtocolConversionTraceStepView targetResponseTraceStep(ProtocolConversionServiceView service,
                                                                    TargetResponse response) {
        ProtocolConversionTraceStepView step = baseStep("targetResponse", "原响应", service == null ? null : service.getTargetProtocol());
        step.setHttpStatus(response == null ? null : Integer.valueOf(response.status));
        step.setContentType(response == null ? null : response.contentType);
        step.setBodyFormat(bodyFormat(service == null ? null : service.getTargetProtocol()));
        step.setBodyPreview(previewForTrace(response == null ? null : response.body));
        step.setSummary("目标服务返回的 HTTP 状态、Content-Type 和原始响应体");
        return step;
    }

    private ProtocolConversionTraceStepView convertedResponseTraceStep(ProtocolConversionServiceView service,
                                                                       Object responseBody) {
        ProtocolConversionProtocol protocol = service == null ? null : service.getSourceProtocol();
        ProtocolConversionTraceStepView step = baseStep("convertedResponse", "对外响应生成", protocol);
        step.setBodyFormat(bodyFormat(protocol));
        step.setBodyPreview(previewForTrace(externalResponsePreviewBody(service, responseBody)));
        step.setSummary("已解析目标响应并按对外入口协议生成返回体");
        return step;
    }

    private Object externalResponsePreviewBody(ProtocolConversionServiceView service, Object responseBody) {
        if (service == null || service.getSourceProtocol() == null) {
            return responseBody;
        }
        if (service.getSourceProtocol() == ProtocolConversionProtocol.HTTP_XML) {
            return httpXmlResponseBody(responseBody);
        }
        if (service.getSourceProtocol() == ProtocolConversionProtocol.SOAP_11
                || service.getSourceProtocol() == ProtocolConversionProtocol.SOAP_12) {
            WebServiceSoapVersion version = service.getSourceProtocol() == ProtocolConversionProtocol.SOAP_12
                    ? WebServiceSoapVersion.SOAP_12
                    : WebServiceSoapVersion.SOAP_11;
            return webServiceSupport.successEnvelope(normalizedSourceWebServiceConfig(service), responseBodyOrEmpty(responseBody), version);
        }
        return responseBody;
    }

    private ProtocolConversionTraceStepView baseStep(String key, String title, ProtocolConversionProtocol protocol) {
        ProtocolConversionTraceStepView step = new ProtocolConversionTraceStepView();
        step.setKey(key);
        step.setTitle(title);
        step.setProtocol(protocol == null ? null : protocol.name());
        step.setStatus(TRACE_STATUS_SUCCESS);
        return step;
    }

    private ProtocolConversionTraceStepView failedStep(ProtocolConversionTraceStepView step, String errorMessage) {
        ProtocolConversionTraceStepView safeStep = step == null ? new ProtocolConversionTraceStepView() : step;
        safeStep.setStatus(TRACE_STATUS_FAILED);
        safeStep.setErrorMessage(errorMessage);
        safeStep.setSummary(hasText(errorMessage) ? errorMessage : safeStep.getSummary());
        return safeStep;
    }

    private void setSourceTrace(ProtocolConversionTraceView trace, ProtocolConversionTraceStepView step) {
        if (trace != null) {
            trace.setSourceRequest(step);
        }
    }

    private void setConvertedRequestTrace(ProtocolConversionTraceView trace, ProtocolConversionTraceStepView step) {
        if (trace != null) {
            trace.setConvertedRequest(step);
        }
    }

    private void setTargetResponseTrace(ProtocolConversionTraceView trace, ProtocolConversionTraceStepView step) {
        if (trace != null) {
            trace.setTargetResponse(step);
        }
    }

    private void setConvertedResponseTrace(ProtocolConversionTraceView trace, ProtocolConversionTraceStepView step) {
        if (trace != null) {
            trace.setConvertedResponse(step);
        }
    }

    private String bodyFormat(ProtocolConversionProtocol protocol) {
        if (protocol == ProtocolConversionProtocol.HTTP_JSON) {
            return "JSON";
        }
        if (protocol == ProtocolConversionProtocol.HTTP_XML || isSoapTarget(protocol)) {
            return "XML";
        }
        return "TEXT";
    }

    private String previewForTrace(Object value) {
        if (invocationLogService != null) {
            return invocationLogService.previewValue(value);
        }
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return truncate(sanitizeSensitiveText(String.valueOf(value)), 4000);
        }
        try {
            return truncate(sanitizeSensitiveText(objectMapper.writeValueAsString(value)), 4000);
        } catch (Exception ex) {
            return truncate(sanitizeSensitiveText(String.valueOf(value)), 4000);
        }
    }

    private SourcePayload parseSourcePayload(ProtocolConversionServiceView service, Object parsedSoapBody, String rawBody) {
        ProtocolConversionProtocol protocol = service.getSourceProtocol() == null
                ? ProtocolConversionProtocol.HTTP_JSON
                : service.getSourceProtocol();
        if (protocol == ProtocolConversionProtocol.SOAP_11 || protocol == ProtocolConversionProtocol.SOAP_12) {
            Object body = parsedSoapBody;
            if (body == null && hasText(rawBody)) {
                body = webServiceSupport.parse(rawBody).getBody();
            }
            return new SourcePayload(body == null ? new LinkedHashMap<String, Object>() : body, rawBody);
        }
        if (protocol == ProtocolConversionProtocol.HTTP_XML) {
            return new SourcePayload(hasText(rawBody) ? parseXmlToMap(rawBody) : new LinkedHashMap<String, Object>(), rawBody);
        }
        if (!hasText(rawBody)) {
            return new SourcePayload(new LinkedHashMap<String, Object>(), rawBody);
        }
        try {
            return new SourcePayload(objectMapper.readValue(rawBody, Object.class), rawBody);
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid JSON request body: " + rootMessage(ex));
        }
    }

    private List<Map<String, Object>> buildRows(ProtocolConversionServiceView service,
                                                SourcePayload sourcePayload,
                                                Map<String, Object> headers,
                                                Map<String, Object> query,
                                                Map<String, Object> form,
                                                String rawBody) {
        ProtocolConversionMode mode = service.getConversionMode() == null
                ? ProtocolConversionMode.FIELD_MAPPING
                : service.getConversionMode();
        if (mode == ProtocolConversionMode.RAW_MESSAGE_FIELD) {
            Map<String, Object> row = passthroughBodyBase(service, sourcePayload.body, null);
            applyRawMessageRow(service, row, rawBody, headers, query);
            return Collections.singletonList(row);
        }
        if (mode == ProtocolConversionMode.BODY_BRIDGE) {
            Map<String, Object> row = asObjectMap(bodyBridgePayload(service, sourcePayload, rawBody));
            applyFixedFields(row, service.getFixedFields());
            return Collections.singletonList(row);
        }
        List<Map<String, Object>> sourceRows = sourceRows(service, sourcePayload.body);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> sourceRow : sourceRows) {
            Map<String, Object> row = passthroughBodyBase(service, sourcePayload.body, sourceRow);
            for (ProtocolConversionFieldMapping mapping : service.getFieldMappings()) {
                if (mapping == null || !hasText(mapping.getTargetField())) {
                    continue;
                }
                Object value = readIncomingValue(mapping, sourcePayload.body, sourceRow, headers, query, form);
                if (isBlankValue(value) && hasText(mapping.getDefaultValue())) {
                    value = mapping.getDefaultValue().trim();
                }
                if (isBlankValue(value) && Boolean.TRUE.equals(mapping.getRequired())) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST, "Field is required: " + mapping.getTargetField());
                }
                value = convertValue(applySafeTransformers(value, mapping.getTransformers()), mapping.getValueType());
                putPath(row, targetPath(mapping.getTargetPath(), mapping.getTargetField()), value);
            }
            applyFixedFields(row, service.getFixedFields());
            rows.add(row);
        }
        return rows;
    }

    private void applyRawMessageRow(ProtocolConversionServiceView service,
                                    Map<String, Object> row,
                                    String rawBody,
                                    Map<String, Object> headers,
                                    Map<String, Object> query) {
        Object transformedRaw = applySafeTransformers(rawBody == null ? "" : rawBody, service.getRawTransformers());
        String targetField = optionText(service.getBodyBridgeOptions(), "targetField");
        if (!hasText(targetField) && service.getFieldMappings() != null && !service.getFieldMappings().isEmpty()) {
            ProtocolConversionFieldMapping mapping = service.getFieldMappings().get(0);
            targetField = targetPath(mapping.getTargetPath(), mapping.getTargetField());
        }
        putPath(row, hasText(targetField) ? targetField : "payload", transformedRaw);
        putPath(row, optionText(service.getBodyBridgeOptions(), "requestIdField", "requestId"), String.valueOf(IdWorker.getId()));
        putPath(row, optionText(service.getBodyBridgeOptions(), "receivedAtField", "receivedAt"),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        putPath(row, optionText(service.getBodyBridgeOptions(), "headersField", "headersJson"), toJson(headers));
        putPath(row, optionText(service.getBodyBridgeOptions(), "queryField", "queryJson"), toJson(query));
        applyFixedFields(row, service.getFixedFields());
    }

    private Object bodyBridgePayload(ProtocolConversionServiceView service, SourcePayload sourcePayload, String rawBody) {
        String mode = optionText(service.getBodyBridgeOptions(), "mode", "CONVERT");
        if ("RAW".equalsIgnoreCase(mode)) {
            return rawBody == null ? "" : rawBody;
        }
        return sourcePayload == null || sourcePayload.body == null
                ? new LinkedHashMap<String, Object>()
                : sourcePayload.body;
    }

    private Map<String, Object> passthroughBodyBase(ProtocolConversionServiceView service, Object sourceBody, Map<String, Object> sourceRow) {
        if (!isPassthroughEnabled(service, "body")) {
            return new LinkedHashMap<String, Object>();
        }
        Object candidate = sourceRow != null ? sourceRow : sourceBody;
        return asObjectMap(candidate);
    }

    private List<Map<String, Object>> sourceRows(ProtocolConversionServiceView service, Object sourceBody) {
        boolean hasDataNodePath = hasText(service.getSourceDataNodePath());
        Object payload = hasDataNodePath ? readPath(sourceBody, service.getSourceDataNodePath()) : sourceBody;
        if (hasDataNodePath && isBlankValue(payload)) {
            return Collections.emptyList();
        }
        if (payload instanceof List<?>) {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Object item : (List<?>) payload) {
                if (hasDataNodePath && isBlankValue(item)) {
                    continue;
                }
                rows.add(asObjectMap(item));
            }
            return rows;
        }
        return Collections.singletonList(asObjectMap(payload));
    }

    private Object readIncomingValue(ProtocolConversionFieldMapping mapping,
                                     Object sourceBody,
                                     Map<String, Object> sourceRow,
                                     Map<String, Object> headers,
                                     Map<String, Object> query,
                                     Map<String, Object> form) {
        String sourceField = hasText(mapping.getSourceField()) ? mapping.getSourceField().trim() : mapping.getTargetField();
        DataIngestionSourcePosition position = mapping.getSourcePosition() == null ? DataIngestionSourcePosition.BODY : mapping.getSourcePosition();
        if (position == DataIngestionSourcePosition.HEADER) {
            return lookupIgnoreCase(headers, sourceField);
        }
        if (position == DataIngestionSourcePosition.QUERY) {
            return lookupIgnoreCase(query, sourceField);
        }
        if (position == DataIngestionSourcePosition.FORM) {
            return lookupIgnoreCase(form, sourceField);
        }
        Object value = readPath(sourceRow, sourceField);
        return value == null ? readPath(sourceBody, sourceField) : value;
    }

    private void applyFixedFields(Map<String, Object> row, List<ProtocolConversionFixedField> fixedFields) {
        if (fixedFields == null) {
            return;
        }
        for (ProtocolConversionFixedField field : fixedFields) {
            if (field == null || !hasText(field.getTargetField())) {
                continue;
            }
            putPath(row, targetPath(field.getTargetPath(), field.getTargetField()), convertValue(field.getValue(), field.getValueType()));
        }
    }

    private TargetRequest buildTargetRequest(ProtocolConversionServiceView service,
                                             List<Map<String, Object>> rows,
                                             SourcePayload sourcePayload,
                                             Map<String, Object> headers,
                                             Map<String, Object> query,
                                             Map<String, Object> form,
                                             String rawBody) {
        TargetRequest request = new TargetRequest();
        request.method = normalizeMethod(service.getTargetMethod(), "POST");
        Map<String, Object> first = rows.isEmpty() ? new LinkedHashMap<String, Object>() : rows.get(0);
        Map<String, Object> templateValues = targetTemplateValues(first, sourcePayload, headers, query, form, rawBody);
        Map<String, Object> targetQuery = new LinkedHashMap<String, Object>();
        if (isPassthroughEnabled(service, "query")) {
            targetQuery.putAll(query == null ? new LinkedHashMap<String, Object>() : query);
        }
        targetQuery.putAll(renderMap(service.getTargetQuery(), templateValues));
        request.url = buildTargetUrl(service, targetQuery);
        request.headers = new LinkedHashMap<String, Object>();
        if (isPassthroughEnabled(service, "headers")) {
            request.headers.putAll(passThroughHeaders(headers));
        }
        request.headers.putAll(renderMap(service.getTargetHeaders(), templateValues));
        request.body = buildTargetBody(service, rows, sourcePayload, rawBody, headers, query, form);
        request.contentType = contentTypeForTarget(service.getTargetProtocol());
        if (!containsHeader(request.headers, "Content-Type")) {
            request.headers.put("Content-Type", request.contentType);
        }
        if (service.getTargetProtocol() == ProtocolConversionProtocol.SOAP_11 && !containsHeader(request.headers, "SOAPAction")) {
            String soapAction = normalizedTargetWebServiceConfig(service).getSoapAction();
            if (hasText(soapAction)) {
                request.headers.put("SOAPAction", soapAction);
            }
        }
        return request;
    }

    private String buildTargetUrl(ProtocolConversionServiceView service, Map<String, Object> targetQuery) {
        DataSourceDefinition datasource = requiredHttpDatasource(service.getTargetDatasourceId());
        Object base = datasource.getTechnicalMetadata() == null ? null : datasource.getTechnicalMetadata().get("url");
        if (base == null || !hasText(String.valueOf(base))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP target datasource url is required");
        }
        String targetPath = service.getTargetPath();
        if (isAbsoluteHttpUrl(targetPath)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target path must be relative to selected HTTP datasource");
        }
        String url = joinHttpUrl(String.valueOf(base).trim(), targetPath);
        Map<String, Object> query = targetQuery == null ? new LinkedHashMap<String, Object>() : targetQuery;
        if (!query.isEmpty()) {
            StringBuilder builder = new StringBuilder(url);
            builder.append(url.contains("?") ? "&" : "?");
            boolean first = true;
            for (Map.Entry<String, Object> entry : query.entrySet()) {
                if (!first) {
                    builder.append('&');
                }
                first = false;
                builder.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue() == null ? "" : String.valueOf(entry.getValue())));
            }
            url = builder.toString();
        }
        return url;
    }

    private String buildTargetBody(ProtocolConversionServiceView service,
                                   List<Map<String, Object>> rows,
                                   SourcePayload sourcePayload,
                                   String rawBody,
                                   Map<String, Object> headers,
                                   Map<String, Object> query,
                                   Map<String, Object> form) {
        DataIngestionPayloadMode payloadMode = service.getPayloadMode() == null ? DataIngestionPayloadMode.OBJECT : service.getPayloadMode();
        Map<String, Object> first = rows.isEmpty() ? new LinkedHashMap<String, Object>() : rows.get(0);
        Map<String, Object> variables = targetTemplateValues(first, sourcePayload, headers, query, form, rawBody);
        if (hasText(service.getTargetBodyTemplate())) {
            if (payloadMode == DataIngestionPayloadMode.ARRAY) {
                return renderArrayTemplate(service.getTargetBodyTemplate(), rows, sourcePayload, headers, query, form, rawBody);
            }
            return renderTemplate(service.getTargetBodyTemplate(), variables, isXmlTarget(service.getTargetProtocol()));
        }
        if (service.getConversionMode() == ProtocolConversionMode.BODY_BRIDGE) {
            return buildBodyBridgeTargetBody(service, sourcePayload, rawBody);
        }
        if (service.getTargetProtocol() == ProtocolConversionProtocol.HTTP_JSON) {
            Object payload = payloadMode == DataIngestionPayloadMode.ARRAY ? arrayPayload(service, rows) : first;
            return toJson(payload);
        }
        if (service.getTargetProtocol() == ProtocolConversionProtocol.HTTP_XML) {
            Object payload = payloadMode == DataIngestionPayloadMode.ARRAY ? arrayPayload(service, rows, true) : first;
            return objectToXml("root", payload);
        }
        return buildTargetSoapEnvelope(service, payloadMode == DataIngestionPayloadMode.ARRAY ? arrayPayload(service, rows, true) : first);
    }

    private String buildBodyBridgeTargetBody(ProtocolConversionServiceView service, SourcePayload sourcePayload, String rawBody) {
        String mode = optionText(service.getBodyBridgeOptions(), "mode", "CONVERT");
        boolean rawMode = "RAW".equalsIgnoreCase(mode);
        Object payload = bodyBridgePayload(service, sourcePayload, rawBody);
        if (!rawMode && service.getFixedFields() != null && !service.getFixedFields().isEmpty()) {
            Map<String, Object> enriched = asObjectMap(payload);
            applyFixedFields(enriched, service.getFixedFields());
            payload = enriched;
        }
        if (service.getTargetProtocol() == ProtocolConversionProtocol.HTTP_JSON) {
            return rawMode ? String.valueOf(payload == null ? "" : payload) : toJson(payload);
        }
        if (service.getTargetProtocol() == ProtocolConversionProtocol.HTTP_XML) {
            return rawMode ? String.valueOf(payload == null ? "" : payload) : objectToXml("root", payload);
        }
        return buildTargetSoapEnvelope(service, payload);
    }

    private Object arrayPayload(ProtocolConversionServiceView service, List<Map<String, Object>> rows) {
        return arrayPayload(service, rows, false);
    }

    private Object arrayPayload(ProtocolConversionServiceView service, List<Map<String, Object>> rows, boolean requireNodePath) {
        String dataNodePath = resolveArrayDataNodePath(service, requireNodePath);
        if (!hasText(dataNodePath)) {
            return rows;
        }
        List<Map<String, Object>> normalizedRows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            normalizedRows.add(stripArrayRecordNode(row, dataNodePath));
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        putPath(root, dataNodePath, normalizedRows);
        return root;
    }

    private String resolveArrayDataNodePath(ProtocolConversionServiceView service, boolean requireNodePath) {
        if (hasText(service.getTargetDataNodePath())) {
            return service.getTargetDataNodePath().trim();
        }
        String inferred = inferArrayDataNodePath(service);
        if (hasText(inferred)) {
            return inferred;
        }
        if (requireNodePath) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Target data node path is required for XML/SOAP array payload");
        }
        return null;
    }

    private String inferArrayDataNodePath(ProtocolConversionServiceView service) {
        String common = null;
        int pathCount = 0;
        if (service == null) {
            return null;
        }
        return inferArrayDataNodePath(service.getFieldMappings(), service.getFixedFields());
    }

    private String inferArrayDataNodePath(List<ProtocolConversionFieldMapping> fieldMappings,
                                          List<ProtocolConversionFixedField> fixedFields) {
        String common = null;
        int pathCount = 0;
        if (fieldMappings != null) {
            for (ProtocolConversionFieldMapping mapping : fieldMappings) {
                if (mapping == null || !hasText(mapping.getTargetField())) {
                    continue;
                }
                String parent = parentPath(targetPath(mapping.getTargetPath(), mapping.getTargetField()));
                if (!hasText(parent)) {
                    return null;
                }
                pathCount++;
                if (common == null) {
                    common = parent;
                } else if (!common.equals(parent)) {
                    return null;
                }
            }
        }
        if (fixedFields != null) {
            for (ProtocolConversionFixedField fixedField : fixedFields) {
                if (fixedField == null || !hasText(fixedField.getTargetField())) {
                    continue;
                }
                String parent = parentPath(targetPath(fixedField.getTargetPath(), fixedField.getTargetField()));
                if (!hasText(parent)) {
                    return null;
                }
                pathCount++;
                if (common == null) {
                    common = parent;
                } else if (!common.equals(parent)) {
                    return null;
                }
            }
        }
        return pathCount > 0 ? common : null;
    }

    private Map<String, Object> stripArrayRecordNode(Map<String, Object> row, String dataNodePath) {
        Object record = readPath(row, dataNodePath);
        if (record instanceof Map<?, ?>) {
            return castMap(record);
        }
        return row;
    }

    private String renderArrayTemplate(String template,
                                       List<Map<String, Object>> rows,
                                       SourcePayload sourcePayload,
                                       Map<String, Object> headers,
                                       Map<String, Object> query,
                                       Map<String, Object> form,
                                       String rawBody) {
        Matcher matcher = RECORDS_REPEAT_PATTERN.matcher(template == null ? "" : template);
        if (!matcher.find()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Array targetBodyTemplate must contain {{#records}}...{{/records}}");
        }
        String inner = matcher.group(1);
        if (matcher.find()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Array targetBodyTemplate must contain only one {{#records}}...{{/records}} block");
        }
        StringBuilder repeated = new StringBuilder();
        for (Map<String, Object> row : rows) {
            repeated.append(renderTemplate(inner, targetTemplateValues(row, sourcePayload, headers, query, form, rawBody), true));
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(repeated.toString()));
    }

    private void validateArrayTemplate(String template) {
        Matcher matcher = RECORDS_REPEAT_PATTERN.matcher(template == null ? "" : template);
        if (!matcher.find()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Array targetBodyTemplate must contain {{#records}}...{{/records}}");
        }
        if (matcher.find()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Array targetBodyTemplate must contain only one {{#records}}...{{/records}} block");
        }
    }

    private TargetResponse sendTarget(ProtocolConversionServiceView service, TargetRequest targetRequest) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(targetRequest.url))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS));
            for (Map.Entry<String, Object> entry : targetRequest.headers.entrySet()) {
                if (hasText(entry.getKey()) && entry.getValue() != null) {
                    builder.header(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            String method = normalizeMethod(targetRequest.method, "POST");
            if ("GET".equalsIgnoreCase(method)) {
                builder.GET();
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(targetRequest.body == null ? "" : targetRequest.body, StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            TargetResponse targetResponse = new TargetResponse(response.statusCode(), contentType, response.body());
            if (isSoapTarget(service.getTargetProtocol()) && hasSoapFault(response.body())) {
                throw new TargetResponseException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                        "SOAP Fault: " + soapFaultMessage(response.body()),
                        targetResponse);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TargetResponseException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                        "Target HTTP request failed: " + response.statusCode() + " " + truncate(response.body(), 500),
                        targetResponse);
            }
            return targetResponse;
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Target HTTP request failed: " + rootMessage(ex));
        }
    }

    private void validateTargetResponse(ProtocolConversionServiceView service, TargetResponse response) {
        Map<String, Object> responseStatus = service.getResponseStatus();
        if (responseStatus == null || responseStatus.isEmpty()) {
            return;
        }
        String path = optionText(responseStatus, "path");
        String code = optionText(responseStatus, "code");
        if (!hasText(path)) {
            return;
        }
        Object parsed = parseResponseBody(service.getTargetProtocol(), response.body);
        Object responseBody = extractResponseBody(service.getTargetProtocol(), parsed);
        Object actual = readPath(responseBody, path);
        if (actual == null && responseBody != parsed) {
            actual = readPath(parsed, path);
        }
        if (!String.valueOf(code).equals(String.valueOf(actual))) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Target response business status mismatch: path=" + path + ", expected=" + code + ", actual=" + actual);
        }
    }

    private Object parseResponseBody(ProtocolConversionProtocol protocol, String body) {
        if (!hasText(body)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            if (protocol == ProtocolConversionProtocol.HTTP_JSON) {
                return objectMapper.readValue(body, Object.class);
            }
            return parseXmlToMap(body);
        } catch (StudioException ex) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Target response parse failed: " + ex.getMessage());
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Target response parse failed: " + rootMessage(ex));
        }
    }

    public List<ProtocolConversionSubscriptionView> listSubscriptions(Long serviceId) {
        requireAccessibleServiceReference(serviceId);
        List<ProtocolConversionSubscriptionEntity> entities = subscriptionMapper.selectList(new LambdaQueryWrapper<ProtocolConversionSubscriptionEntity>()
                .select(ProtocolConversionSubscriptionEntity::getId,
                        ProtocolConversionSubscriptionEntity::getTenantId,
                        ProtocolConversionSubscriptionEntity::getProjectId,
                        ProtocolConversionSubscriptionEntity::getDeleted,
                        ProtocolConversionSubscriptionEntity::getCreatedAt,
                        ProtocolConversionSubscriptionEntity::getUpdatedAt,
                        ProtocolConversionSubscriptionEntity::getServiceId,
                        ProtocolConversionSubscriptionEntity::getSubscriptionName,
                        ProtocolConversionSubscriptionEntity::getTokenMasked,
                        ProtocolConversionSubscriptionEntity::getEnabled,
                        ProtocolConversionSubscriptionEntity::getCreatedBy,
                        ProtocolConversionSubscriptionEntity::getLastUsedAt,
                        ProtocolConversionSubscriptionEntity::getRotatedAt,
                        ProtocolConversionSubscriptionEntity::getRotatedBy)
                .eq(ProtocolConversionSubscriptionEntity::getServiceId, serviceId)
                .orderByDesc(ProtocolConversionSubscriptionEntity::getEnabled)
                .orderByDesc(ProtocolConversionSubscriptionEntity::getCreatedAt)
                .orderByDesc(ProtocolConversionSubscriptionEntity::getId));
        List<ProtocolConversionSubscriptionView> result = new ArrayList<ProtocolConversionSubscriptionView>();
        for (ProtocolConversionSubscriptionEntity entity : entities) {
            result.add(toSubscriptionView(entity, null));
        }
        return result;
    }

    @Transactional
    public ProtocolConversionSubscriptionView createSubscription(Long serviceId, DataServiceSubscriptionCreateRequest request) {
        ProtocolConversionServiceEntity service = requireWritableServiceReference(serviceId);
        if (request == null || !hasText(request.getSubscriptionName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Subscription name is required");
        }
        String subscriptionName = request.getSubscriptionName().trim();
        ProtocolConversionSubscriptionEntity activeDuplicate = subscriptionMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionSubscriptionEntity>()
                .select(ProtocolConversionSubscriptionEntity::getId)
                .eq(ProtocolConversionSubscriptionEntity::getServiceId, serviceId)
                .eq(ProtocolConversionSubscriptionEntity::getSubscriptionName, subscriptionName)
                .eq(ProtocolConversionSubscriptionEntity::getEnabled, 1)
                .last("limit 1"));
        if (activeDuplicate != null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Subscription name already exists");
        }
        String token = tokenSupport.generateSubscriptionToken();
        ProtocolConversionSubscriptionEntity entity = new ProtocolConversionSubscriptionEntity();
        entity.setTenantId(service.getTenantId());
        entity.setProjectId(service.getProjectId());
        entity.setServiceId(service.getId());
        entity.setSubscriptionName(subscriptionName);
        entity.setTokenHash(tokenSupport.hashToken(token));
        entity.setTokenMasked(tokenSupport.maskToken(token));
        entity.setEnabled(Integer.valueOf(1));
        entity.setCreatedBy(securityService.currentUserId());
        try {
            subscriptionMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Subscription name already exists");
        }
        return toSubscriptionView(entity, token);
    }

    @Transactional
    public ProtocolConversionSubscriptionView rotateSubscription(Long serviceId, Long subscriptionId) {
        ProtocolConversionSubscriptionEntity entity = requireSubscription(serviceId, subscriptionId);
        String token = tokenSupport.generateSubscriptionToken();
        entity.setTokenHash(tokenSupport.hashToken(token));
        entity.setTokenMasked(tokenSupport.maskToken(token));
        entity.setEnabled(Integer.valueOf(1));
        entity.setLastUsedAt(null);
        entity.setRotatedAt(LocalDateTime.now());
        entity.setRotatedBy(securityService.currentUserId());
        subscriptionMapper.updateById(entity);
        return toSubscriptionView(entity, token);
    }

    @Transactional
    public ProtocolConversionSubscriptionView disableSubscription(Long serviceId, Long subscriptionId) {
        ProtocolConversionSubscriptionEntity entity = requireSubscription(serviceId, subscriptionId);
        entity.setEnabled(Integer.valueOf(0));
        subscriptionMapper.updateById(entity);
        return toSubscriptionView(entity, null);
    }

    @Transactional
    public ProtocolConversionSubscriptionView enableSubscription(Long serviceId, Long subscriptionId) {
        ProtocolConversionSubscriptionEntity entity = requireSubscription(serviceId, subscriptionId);
        ProtocolConversionSubscriptionEntity activeDuplicate = subscriptionMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionSubscriptionEntity>()
                .select(ProtocolConversionSubscriptionEntity::getId)
                .eq(ProtocolConversionSubscriptionEntity::getServiceId, serviceId)
                .eq(ProtocolConversionSubscriptionEntity::getSubscriptionName, entity.getSubscriptionName())
                .eq(ProtocolConversionSubscriptionEntity::getEnabled, 1)
                .ne(ProtocolConversionSubscriptionEntity::getId, subscriptionId)
                .last("limit 1"));
        if (activeDuplicate != null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Another enabled subscription with the same name already exists");
        }
        entity.setEnabled(Integer.valueOf(1));
        try {
            subscriptionMapper.updateById(entity);
        } catch (DuplicateKeyException ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Another enabled subscription with the same name already exists");
        }
        return toSubscriptionView(entity, null);
    }

    private ProtocolConversionServiceListView toListView(ProtocolConversionServiceEntity entity) {
        ProtocolConversionServiceListView view = new ProtocolConversionServiceListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setCreatedBy(entity.getCreatedBy());
        view.setServiceCode(entity.getServiceCode());
        view.setServiceName(entity.getServiceName());
        view.setStatus(enumValue(ProtocolConversionStatus.class, entity.getStatus(), ProtocolConversionStatus.DRAFT));
        view.setEndpointPath(entity.getEndpointPath());
        view.setWebserviceEndpointPath(entity.getWebserviceEndpointPath());
        view.setTokenRequired(isTokenRequired(entity));
        view.setDefaultSubscriptionName(entity.getDefaultSubscriptionName());
        view.setSourceProtocol(enumValue(ProtocolConversionProtocol.class, entity.getSourceProtocol(), ProtocolConversionProtocol.HTTP_JSON));
        view.setSourceMethod(entity.getSourceMethod());
        view.setSourceDataNodePath(entity.getSourceDataNodePath());
        view.setConversionMode(enumValue(ProtocolConversionMode.class, entity.getConversionMode(), ProtocolConversionMode.FIELD_MAPPING));
        view.setTargetDatasourceId(entity.getTargetDatasourceId());
        view.setTargetDatasourceName(entity.getTargetDatasourceNameSnapshot());
        view.setTargetPath(entity.getTargetPath());
        view.setTargetProtocol(enumValue(ProtocolConversionProtocol.class, entity.getTargetProtocol(), ProtocolConversionProtocol.HTTP_JSON));
        view.setTargetMethod(entity.getTargetMethod());
        view.setPayloadMode(enumValue(DataIngestionPayloadMode.class, entity.getPayloadMode(), DataIngestionPayloadMode.OBJECT));
        view.setBatchSize(Integer.valueOf(DEFAULT_BATCH_SIZE));
        return view;
    }

    private ProtocolConversionServiceView toView(ProtocolConversionServiceEntity entity) {
        ProtocolConversionServiceListView listView = toListView(entity);
        ProtocolConversionServiceView view = new ProtocolConversionServiceView();
        view.setId(listView.getId());
        view.setTenantId(listView.getTenantId());
        view.setProjectId(listView.getProjectId());
        view.setDeleted(listView.getDeleted());
        view.setCreatedAt(listView.getCreatedAt());
        view.setUpdatedAt(listView.getUpdatedAt());
        view.setCreatedBy(listView.getCreatedBy());
        view.setServiceCode(listView.getServiceCode());
        view.setServiceName(listView.getServiceName());
        view.setStatus(listView.getStatus());
        view.setEndpointPath(listView.getEndpointPath());
        view.setWebserviceEndpointPath(listView.getWebserviceEndpointPath());
        view.setTokenRequired(listView.getTokenRequired());
        view.setDefaultSubscriptionName(listView.getDefaultSubscriptionName());
        view.setSourceProtocol(listView.getSourceProtocol());
        view.setSourceMethod(listView.getSourceMethod());
        view.setSourceDataNodePath(listView.getSourceDataNodePath());
        view.setConversionMode(listView.getConversionMode());
        view.setTargetDatasourceId(listView.getTargetDatasourceId());
        view.setTargetDatasourceName(listView.getTargetDatasourceName());
        view.setTargetPath(listView.getTargetPath());
        view.setTargetProtocol(listView.getTargetProtocol());
        view.setTargetMethod(listView.getTargetMethod());
        view.setPayloadMode(listView.getPayloadMode());
        view.setBatchSize(listView.getBatchSize());
        view.setServiceKey(entity.getServiceKey());
        view.setWebserviceConfig(fromWebServiceConfigMap(entity.getWebserviceConfigJson(), entity.getServiceCode()));
        view.setFieldMappings(fromMapList(entity.getFieldMappingsJson(), new TypeReference<List<ProtocolConversionFieldMapping>>() {
        }));
        view.setRawTransformers(fromMapList(entity.getRawTransformersJson(), new TypeReference<List<TransformerBinding>>() {
        }));
        view.setFixedFields(fromMapList(entity.getFixedFieldsJson(), new TypeReference<List<ProtocolConversionFixedField>>() {
        }));
        view.setBodyBridgeOptions(safeMap(entity.getBodyBridgeOptionsJson()));
        view.setRequestPassthrough(safeMap(entity.getRequestPassthroughJson()));
        view.setTargetHeaders(safeMap(entity.getTargetHeadersJson()));
        view.setTargetQuery(safeMap(entity.getTargetQueryJson()));
        view.setTargetWebserviceConfig(fromTargetWebServiceConfigMap(entity.getTargetWebserviceConfigJson(), entity.getWebserviceConfigJson(), entity.getServiceCode()));
        view.setTargetBodyTemplate(entity.getTargetBodyTemplate());
        view.setTargetDataNodePath(entity.getTargetDataNodePath());
        view.setResponseStatus(safeMap(entity.getResponseStatusJson()));
        return view;
    }

    private ProtocolConversionSubscriptionView toSubscriptionView(ProtocolConversionSubscriptionEntity entity, String token) {
        ProtocolConversionSubscriptionView view = new ProtocolConversionSubscriptionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setServiceId(entity.getServiceId());
        view.setSubscriptionName(entity.getSubscriptionName());
        view.setToken(token);
        view.setTokenMasked(token == null ? tokenMaskedForList(entity.getTokenMasked()) : tokenSupport.maskToken(token));
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setCreatedBy(entity.getCreatedBy());
        view.setLastUsedAt(entity.getLastUsedAt());
        view.setRotatedAt(entity.getRotatedAt());
        view.setRotatedBy(entity.getRotatedBy());
        return view;
    }

    private void validateSaveRequest(ProtocolConversionServiceSaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Request is required");
        }
        normalizeRequiredText(request.getServiceCode(), "Service code is required");
        normalizeRequiredText(request.getServiceName(), "Service name is required");
        validateSimpleIdentifier(request.getServiceCode(), "Service code must contain only letters, numbers and underscores");
        if (request.getTargetDatasourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target datasource is required");
        }
        normalizeRequiredText(request.getTargetPath(), "Target path is required");
        if (request.getConversionMode() == ProtocolConversionMode.FIELD_MAPPING
                && (request.getFieldMappings() == null || request.getFieldMappings().isEmpty())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Field mappings are required");
        }
        validateArrayTargetConfig(request.getTargetProtocol(), request.getPayloadMode(), request.getTargetBodyTemplate(), request.getTargetDataNodePath(),
                request.getFieldMappings(), request.getFixedFields());
    }

    private void validateExecutable(ProtocolConversionServiceView view) {
        if (view == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Protocol conversion service not found");
        }
        if (view.getTargetDatasourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target datasource is required");
        }
        if (!hasText(view.getTargetPath())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target path is required");
        }
        if (view.getConversionMode() == ProtocolConversionMode.FIELD_MAPPING
                && (view.getFieldMappings() == null || view.getFieldMappings().isEmpty())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Field mappings are not configured");
        }
        validateArrayTargetConfig(view.getTargetProtocol(), view.getPayloadMode(), view.getTargetBodyTemplate(), view.getTargetDataNodePath(),
                view.getFieldMappings(), view.getFixedFields());
    }

    private void validateArrayTargetConfig(ProtocolConversionProtocol targetProtocol,
                                           DataIngestionPayloadMode payloadMode,
                                           String targetBodyTemplate,
                                           String targetDataNodePath,
                                           List<ProtocolConversionFieldMapping> fieldMappings,
                                           List<ProtocolConversionFixedField> fixedFields) {
        if (payloadMode != DataIngestionPayloadMode.ARRAY) {
            return;
        }
        if (hasText(targetBodyTemplate)) {
            validateArrayTemplate(targetBodyTemplate);
            return;
        }
        if (targetProtocol != ProtocolConversionProtocol.HTTP_XML && !isSoapTarget(targetProtocol)) {
            return;
        }
        if (hasText(targetDataNodePath) || hasText(inferArrayDataNodePath(fieldMappings, fixedFields))) {
            return;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST,
                "Target data node path is required for XML/SOAP array payload");
    }

    private DataSourceDefinition requiredHttpDatasource(Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        if (!"http".equalsIgnoreCase(datasource.getTypeCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Protocol conversion target requires an HTTP datasource");
        }
        return datasource;
    }

    private ProtocolConversionServiceEntity requireAccessibleEntity(Long id) {
        ProtocolConversionServiceEntity entity = serviceMapper.selectById(id);
        if (entity == null || !securityService.currentTenantId().equals(entity.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Protocol conversion service not found: " + id);
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE,
                entity.getProjectId(), entity.getId(), "Protocol conversion service not found: " + id);
        return entity;
    }

    private ProtocolConversionServiceEntity requireAccessibleServiceReference(Long id) {
        ProtocolConversionServiceEntity entity = serviceMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                .select(ProtocolConversionServiceEntity::getId,
                        ProtocolConversionServiceEntity::getTenantId,
                        ProtocolConversionServiceEntity::getProjectId)
                .eq(ProtocolConversionServiceEntity::getId, id)
                .last("limit 1"));
        if (entity == null || !securityService.currentTenantId().equals(entity.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Protocol conversion service not found: " + id);
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE,
                entity.getProjectId(), entity.getId(), "Protocol conversion service not found: " + id);
        return entity;
    }

    private ProtocolConversionServiceEntity requireWritableEntity(Long id) {
        ProtocolConversionServiceEntity entity = requireAccessibleEntity(id);
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private ProtocolConversionServiceEntity requireWritableServiceReference(Long id) {
        ProtocolConversionServiceEntity entity = requireAccessibleServiceReference(id);
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private ProtocolConversionServiceEntity requireOpenEntity(String serviceCode, String serviceKey) {
        ProtocolConversionServiceEntity entity = serviceMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                .eq(ProtocolConversionServiceEntity::getServiceCode, normalizeRequiredText(serviceCode, "Service code is required"))
                .eq(ProtocolConversionServiceEntity::getServiceKey, normalizeRequiredText(serviceKey, "Service key is required"))
                .last("limit 1"));
        if (entity == null || !ProtocolConversionStatus.ONLINE.name().equalsIgnoreCase(entity.getStatus())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Protocol conversion service is not available");
        }
        return entity;
    }

    private ProtocolConversionSubscriptionEntity requireSubscription(Long serviceId, Long subscriptionId) {
        requireWritableServiceReference(serviceId);
        ProtocolConversionSubscriptionEntity entity = subscriptionMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionSubscriptionEntity>()
                .select(ProtocolConversionSubscriptionEntity::getId,
                        ProtocolConversionSubscriptionEntity::getTenantId,
                        ProtocolConversionSubscriptionEntity::getProjectId,
                        ProtocolConversionSubscriptionEntity::getDeleted,
                        ProtocolConversionSubscriptionEntity::getCreatedAt,
                        ProtocolConversionSubscriptionEntity::getUpdatedAt,
                        ProtocolConversionSubscriptionEntity::getServiceId,
                        ProtocolConversionSubscriptionEntity::getSubscriptionName,
                        ProtocolConversionSubscriptionEntity::getTokenMasked,
                        ProtocolConversionSubscriptionEntity::getEnabled,
                        ProtocolConversionSubscriptionEntity::getCreatedBy,
                        ProtocolConversionSubscriptionEntity::getLastUsedAt,
                        ProtocolConversionSubscriptionEntity::getRotatedAt,
                        ProtocolConversionSubscriptionEntity::getRotatedBy)
                .eq(ProtocolConversionSubscriptionEntity::getId, subscriptionId)
                .last("limit 1"));
        if (entity == null || entity.getServiceId() == null || entity.getServiceId().longValue() != serviceId.longValue()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Subscription not found: " + subscriptionId);
        }
        return entity;
    }

    private ProtocolConversionSubscriptionEntity resolveInvocationSubscription(ProtocolConversionServiceEntity service, String token) {
        if (hasText(token)) {
            return validateSubscriptionToken(service.getId(), token);
        }
        if (isTokenRequired(service)) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Protocol conversion token is required");
        }
        return null;
    }

    private ProtocolConversionSubscriptionEntity validateSubscriptionToken(Long serviceId, String token) {
        if (!hasText(token)) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Protocol conversion token is required");
        }
        ProtocolConversionSubscriptionEntity entity = subscriptionMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionSubscriptionEntity>()
                .eq(ProtocolConversionSubscriptionEntity::getServiceId, serviceId)
                .eq(ProtocolConversionSubscriptionEntity::getTokenHash, tokenSupport.hashToken(token.trim()))
                .eq(ProtocolConversionSubscriptionEntity::getEnabled, 1)
                .last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Invalid protocol conversion token");
        }
        subscriptionMapper.update(null, new LambdaUpdateWrapper<ProtocolConversionSubscriptionEntity>()
                .eq(ProtocolConversionSubscriptionEntity::getId, entity.getId())
                .set(ProtocolConversionSubscriptionEntity::getLastUsedAt, LocalDateTime.now()));
        return entity;
    }

    private void ensureUniqueServiceCode(Long projectId, String serviceCode, Long selfId) {
        String normalizedServiceCode = normalizeRequiredText(serviceCode, "Service code is required");
        List<ProtocolConversionServiceEntity> duplicates = serviceMapper.selectList(new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                .eq(ProtocolConversionServiceEntity::getTenantId, securityService.currentTenantId())
                .eq(ProtocolConversionServiceEntity::getProjectId, projectId)
                .eq(ProtocolConversionServiceEntity::getServiceCode, normalizedServiceCode));
        for (ProtocolConversionServiceEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Service code already exists in current project");
        }
    }

    private void ensureUniqueServiceName(Long projectId, String serviceName, Long selfId) {
        String normalizedServiceName = normalizeRequiredText(serviceName, "Service name is required");
        List<ProtocolConversionServiceEntity> duplicates = serviceMapper.selectList(new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                .eq(ProtocolConversionServiceEntity::getTenantId, securityService.currentTenantId())
                .eq(ProtocolConversionServiceEntity::getProjectId, projectId)
                .eq(ProtocolConversionServiceEntity::getServiceName, normalizedServiceName));
        for (ProtocolConversionServiceEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Service name already exists in current project");
        }
    }

    private void validateSoapSource(ProtocolConversionServiceView view, WebServiceSupport.ParsedSoapRequest parsed) {
        if (view.getSourceProtocol() != ProtocolConversionProtocol.SOAP_11 && view.getSourceProtocol() != ProtocolConversionProtocol.SOAP_12) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Service source protocol is not SOAP");
        }
        WebServiceSoapVersion expectedVersion = view.getSourceProtocol() == ProtocolConversionProtocol.SOAP_12
                ? WebServiceSoapVersion.SOAP_12
                : WebServiceSoapVersion.SOAP_11;
        if (parsed.getSoapVersion() != expectedVersion) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "SOAP version does not match service config: expected=" + expectedVersion + ", actual=" + parsed.getSoapVersion());
        }
        WebServiceConfig config = normalizedSourceWebServiceConfig(view);
        if (!config.getRequestRootName().equals(parsed.getOperationName())
                && !config.getOperationName().equals(parsed.getOperationName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SOAP operation does not match service config");
        }
    }

    private void validateSourceInvocationMethod(ProtocolConversionServiceView view, String requestMethod) {
        ProtocolConversionProtocol sourceProtocol = view == null || view.getSourceProtocol() == null
                ? ProtocolConversionProtocol.HTTP_JSON
                : view.getSourceProtocol();
        if (sourceProtocol == ProtocolConversionProtocol.SOAP_11 || sourceProtocol == ProtocolConversionProtocol.SOAP_12) {
            if (!"SOAP".equalsIgnoreCase(requestMethod)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "SOAP source must be invoked through WebService endpoint");
            }
            return;
        }
        String expected = normalizeMethod(view.getSourceMethod(), "POST");
        String actual = normalizeMethod(requestMethod, "");
        if (hasText(expected) && hasText(actual) && !expected.equalsIgnoreCase(actual)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Source method does not match service config: expected=" + expected + ", actual=" + actual);
        }
    }

    private Object applySafeTransformers(Object value, List<TransformerBinding> transformers) {
        Object current = value;
        if (transformers == null || transformers.isEmpty()) {
            return current;
        }
        for (TransformerBinding transformer : transformers) {
            String code = transformerCode(transformer);
            if (!hasText(code)) {
                continue;
            }
            current = applySafeTransformer(current, code, transformer.getParameters());
            if (current == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Protocol conversion transformer returned null: " + code);
            }
        }
        return current;
    }

    private Object applySafeTransformer(Object value, String code, Map<String, Object> parameters) {
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("filter") || normalized.contains("groovy")) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Transformer is not allowed for protocol conversion: " + code);
        }
        String text = value == null ? "" : String.valueOf(value);
        if ("md5".equals(normalized) || "md5_str".equals(normalized)) {
            return digest("MD5", text + optionText(parameters, "key", ""));
        }
        if ("sha256".equals(normalized) || "sha_256".equals(normalized)) {
            return digest("SHA-256", text + optionText(parameters, "key", ""));
        }
        if ("base64".equals(normalized) || "base64_encode".equals(normalized)) {
            return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }
        if ("trim".equals(normalized) || "trim_spaces_str".equals(normalized)) {
            return text.trim();
        }
        if ("mask".equals(normalized) || "date_mask".equals(normalized)) {
            int keepStart = intOption(parameters, "keepStart", 3);
            int keepEnd = intOption(parameters, "keepEnd", 4);
            String mask = optionText(parameters, "mask", "****");
            if (text.length() <= keepStart + keepEnd) {
                return mask;
            }
            return text.substring(0, keepStart) + mask + text.substring(text.length() - keepEnd);
        }
        if ("insert_sys_time".equals(normalized)) {
            return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported protocol conversion transformer: " + code);
    }

    private Object convertValue(Object value, FieldValueType valueType) {
        if (value == null) {
            return null;
        }
        FieldValueType type = valueType == null ? FieldValueType.STRING : valueType;
        try {
            if (type == FieldValueType.INTEGER || type == FieldValueType.LONG) {
                if (value instanceof Number) {
                    return Long.valueOf(((Number) value).longValue());
                }
                return Long.valueOf(String.valueOf(value).trim());
            }
            if (type == FieldValueType.DECIMAL) {
                if (value instanceof BigDecimal) {
                    return value;
                }
                if (value instanceof Number) {
                    return BigDecimal.valueOf(((Number) value).doubleValue());
                }
                return new BigDecimal(String.valueOf(value).trim());
            }
            if (type == FieldValueType.BOOLEAN) {
                if (value instanceof Boolean) {
                    return value;
                }
                return Boolean.valueOf(String.valueOf(value).trim());
            }
            if (type == FieldValueType.ARRAY || type == FieldValueType.OBJECT || type == FieldValueType.JSON) {
                return value instanceof String ? value : objectMapper.writeValueAsString(value);
            }
            return value;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Field value conversion failed: " + rootMessage(ex));
        }
    }

    private Object parseXmlToMap(String xml) {
        try {
            Document document = secureDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put(localName(root), elementValue(root));
            return result;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid XML body: " + rootMessage(ex));
        }
    }

    private DocumentBuilder secureDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder();
    }

    private Object elementValue(Element element) {
        if (isNilElement(element)) {
            return null;
        }
        Map<String, Object> children = new LinkedHashMap<String, Object>();
        NodeList childNodes = element.getChildNodes();
        for (int index = 0; index < childNodes.getLength(); index++) {
            Node node = childNodes.item(index);
            if (node instanceof Element) {
                putRepeated(children, localName(node), elementValue((Element) node));
            }
        }
        if (!children.isEmpty()) {
            return children;
        }
        return element.getTextContent() == null ? null : element.getTextContent().trim();
    }

    private boolean isNilElement(Element element) {
        if (element == null) {
            return false;
        }
        String namespaced = element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil");
        if ("true".equalsIgnoreCase(namespaced) || "1".equals(namespaced)) {
            return true;
        }
        String prefixed = element.getAttribute("xsi:nil");
        return "true".equalsIgnoreCase(prefixed) || "1".equals(prefixed);
    }

    private void putRepeated(Map<String, Object> target, String key, Object value) {
        if (!target.containsKey(key)) {
            target.put(key, value);
            return;
        }
        Object existing = target.get(key);
        List<Object> list;
        if (existing instanceof List<?>) {
            list = new ArrayList<Object>((List<?>) existing);
        } else {
            list = new ArrayList<Object>();
            list.add(existing);
        }
        list.add(value);
        target.put(key, list);
    }

    private String objectToXml(String rootName, Object value) {
        StringBuilder builder = new StringBuilder();
        builder.append('<').append(normalizeXmlName(rootName, "root")).append('>');
        appendXmlValue(builder, value, "item");
        builder.append("</").append(normalizeXmlName(rootName, "root")).append('>');
        return builder.toString();
    }

    private void appendXmlValue(StringBuilder builder, Object value, String fallbackName) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = normalizeXmlName(String.valueOf(entry.getKey()), fallbackName);
                Object child = entry.getValue();
                if (child instanceof List<?>) {
                    for (Object item : (List<?>) child) {
                        builder.append('<').append(key).append('>');
                        appendXmlValue(builder, item, key);
                        builder.append("</").append(key).append('>');
                    }
                } else {
                    builder.append('<').append(key).append('>');
                    appendXmlValue(builder, child, key);
                    builder.append("</").append(key).append('>');
                }
            }
        } else if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                builder.append('<').append(normalizeXmlName(fallbackName, "item")).append('>');
                appendXmlValue(builder, item, "item");
                builder.append("</").append(normalizeXmlName(fallbackName, "item")).append('>');
            }
        } else if (value != null) {
            builder.append(escapeXml(String.valueOf(value)));
        }
    }

    private String buildTargetSoapEnvelope(ProtocolConversionServiceView service, Object payload) {
        WebServiceSoapVersion version = service.getTargetProtocol() == ProtocolConversionProtocol.SOAP_12
                ? WebServiceSoapVersion.SOAP_12
                : WebServiceSoapVersion.SOAP_11;
        return buildSoapEnvelope(version, normalizedTargetWebServiceConfig(service), payload);
    }

    private String buildSourceSoapEnvelope(ProtocolConversionServiceView service, Object payload) {
        WebServiceSoapVersion version = service.getSourceProtocol() == ProtocolConversionProtocol.SOAP_12
                ? WebServiceSoapVersion.SOAP_12
                : WebServiceSoapVersion.SOAP_11;
        return buildSoapEnvelope(version, normalizedSourceWebServiceConfig(service), payload);
    }

    private String buildSoapEnvelope(WebServiceSoapVersion version, WebServiceConfig config, Object payload) {
        String soapNs = version == WebServiceSoapVersion.SOAP_12
                ? "http://www.w3.org/2003/05/soap-envelope"
                : "http://schemas.xmlsoap.org/soap/envelope/";
        StringBuilder builder = new StringBuilder();
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        builder.append("<soap:Envelope xmlns:soap=\"").append(soapNs).append("\" xmlns:tns=\"")
                .append(escapeXml(config.getNamespaceUri())).append("\"><soap:Body><tns:")
                .append(config.getRequestRootName()).append(">");
        appendXmlValue(builder, payload, "item");
        builder.append("</tns:").append(config.getRequestRootName()).append("></soap:Body></soap:Envelope>");
        return builder.toString();
    }

    private String genericWsdl(String serviceName, WebServiceConfig config, String endpointPath) {
        String name = normalizeXmlName(serviceName, config.getOperationName());
        String ns = escapeXml(config.getNamespaceUri());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\" xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\" "
                + "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:tns=\"" + ns + "\" targetNamespace=\"" + ns + "\">"
                + "<wsdl:types><xsd:schema targetNamespace=\"" + ns + "\" elementFormDefault=\"qualified\">"
                + "<xsd:element name=\"" + config.getRequestRootName() + "\"><xsd:complexType><xsd:sequence>"
                + "<xsd:any minOccurs=\"0\" maxOccurs=\"unbounded\" processContents=\"lax\"/>"
                + "</xsd:sequence></xsd:complexType></xsd:element>"
                + "<xsd:element name=\"" + config.getResponseRootName() + "\"><xsd:complexType><xsd:sequence>"
                + "<xsd:any minOccurs=\"0\" maxOccurs=\"unbounded\" processContents=\"lax\"/>"
                + "</xsd:sequence></xsd:complexType></xsd:element></xsd:schema></wsdl:types>"
                + "<wsdl:message name=\"" + config.getRequestRootName() + "Message\"><wsdl:part name=\"parameters\" element=\"tns:" + config.getRequestRootName() + "\"/></wsdl:message>"
                + "<wsdl:message name=\"" + config.getResponseRootName() + "Message\"><wsdl:part name=\"parameters\" element=\"tns:" + config.getResponseRootName() + "\"/></wsdl:message>"
                + "<wsdl:portType name=\"" + name + "PortType\"><wsdl:operation name=\"" + config.getOperationName() + "\"><wsdl:input message=\"tns:"
                + config.getRequestRootName() + "Message\"/><wsdl:output message=\"tns:" + config.getResponseRootName() + "Message\"/></wsdl:operation></wsdl:portType>"
                + "<wsdl:binding name=\"" + name + "Binding\" type=\"tns:" + name + "PortType\"><soap:binding style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"/>"
                + "<wsdl:operation name=\"" + config.getOperationName() + "\"><soap:operation soapAction=\"" + escapeXml(config.getSoapAction()) + "\"/>"
                + "<wsdl:input><soap:body use=\"literal\"/></wsdl:input><wsdl:output><soap:body use=\"literal\"/></wsdl:output></wsdl:operation></wsdl:binding>"
                + "<wsdl:service name=\"" + name + "\"><wsdl:port name=\"" + name + "Port\" binding=\"tns:" + name + "Binding\"><soap:address location=\""
                + escapeXml(endpointPath) + "\"/></wsdl:port></wsdl:service></wsdl:definitions>";
    }

    private Object responseBodyOrEmpty(ProtocolConversionInvokeResult result) {
        Object responseBody = result == null ? null : result.getResponseBody();
        return responseBodyOrEmpty(responseBody);
    }

    private Object responseBodyOrEmpty(Object responseBody) {
        return responseBody == null ? new LinkedHashMap<String, Object>() : responseBody;
    }

    @SuppressWarnings("unchecked")
    private Object extractResponseBody(ProtocolConversionProtocol protocol, Object targetBody) {
        Object responseBody;
        if (!isSoapTarget(protocol)) {
            responseBody = targetBody;
        } else {
            Object body = readPath(targetBody, "Envelope.Body");
            if (!(body instanceof Map<?, ?>)) {
                responseBody = targetBody;
            } else {
                Map<String, Object> bodyMap = (Map<String, Object>) body;
                if (bodyMap.isEmpty()) {
                    responseBody = new LinkedHashMap<String, Object>();
                } else if (bodyMap.size() == 1) {
                    responseBody = bodyMap.values().iterator().next();
                } else {
                    Map<String, Object> result = new LinkedHashMap<String, Object>();
                    for (Map.Entry<String, Object> entry : bodyMap.entrySet()) {
                        if (!"Fault".equalsIgnoreCase(entry.getKey())) {
                            result.put(entry.getKey(), entry.getValue());
                        }
                    }
                    responseBody = result.isEmpty() ? bodyMap : result;
                }
            }
        }
        normalizeEmptyDataServiceTable(responseBody);
        return responseBody;
    }

    @SuppressWarnings("unchecked")
    private void normalizeEmptyDataServiceTable(Object responseBody) {
        if (!(responseBody instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> response = (Map<String, Object>) responseBody;
        Object table = response.get("table");
        if (!hasDataServicePageShape(response)) {
            return;
        }
        if (isBlankValue(table)) {
            Map<String, Object> normalizedTable = new LinkedHashMap<String, Object>();
            normalizedTable.put("row", null);
            response.put("table", normalizedTable);
            return;
        }
        if (table instanceof Map<?, ?>) {
            Map<String, Object> tableMap = (Map<String, Object>) table;
            Object row = tableMap.get("row");
            if (isBlankValue(row)) {
                tableMap.put("row", null);
            }
        }
    }

    private boolean hasDataServicePageShape(Map<String, Object> response) {
        return response != null
                && response.containsKey("pageNum")
                && response.containsKey("pageSize")
                && response.containsKey("pages")
                && response.containsKey("table");
    }

    private Object readPath(Object source, String path) {
        if (source == null || !hasText(path)) {
            return source;
        }
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (current == null || !hasText(segment)) {
                return null;
            }
            current = readSegment(current, segment.trim());
        }
        return current;
    }

    private Object readSegment(Object source, String segment) {
        String name = segment;
        Integer index = null;
        int bracket = segment.indexOf('[');
        if (bracket >= 0 && segment.endsWith("]")) {
            name = segment.substring(0, bracket);
            try {
                index = Integer.valueOf(segment.substring(bracket + 1, segment.length() - 1));
            } catch (Exception ex) {
                return null;
            }
        }
        Object value = source;
        if (hasText(name)) {
            if (!(source instanceof Map<?, ?>)) {
                return null;
            }
            value = lookupIgnoreCase(castMap(source), name);
        }
        if (index != null) {
            if (!(value instanceof List<?>)) {
                return null;
            }
            List<?> list = (List<?>) value;
            return index.intValue() >= 0 && index.intValue() < list.size() ? list.get(index.intValue()) : null;
        }
        return value;
    }

    private void putPath(Map<String, Object> root, String path, Object value) {
        if (!hasText(path)) {
            return;
        }
        String[] segments = path.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index].trim();
            if (!hasText(segment)) {
                continue;
            }
            if (index == segments.length - 1) {
                current.put(segment, value);
                return;
            }
            Object next = current.get(segment);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                current.put(segment, next);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> nextMap = (Map<String, Object>) next;
            current = nextMap;
        }
    }

    private Map<String, Object> asObjectMap(Object value) {
        if (value instanceof Map<?, ?>) {
            return castMap(value);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("value", value);
        return result;
    }

    private Map<String, Object> castMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private Object lookupIgnoreCase(Map<String, Object> source, String key) {
        if (source == null || key == null) {
            return null;
        }
        if (source.containsKey(key)) {
            return source.get(key);
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Map<String, Object> renderMap(Map<String, Object> source, Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!hasText(entry.getKey())) {
                continue;
            }
            Object value = entry.getValue();
            result.put(entry.getKey(), value instanceof String ? renderTemplate((String) value, values, false) : value);
        }
        return result;
    }

    private Map<String, Object> targetTemplateValues(Map<String, Object> row,
                                                     SourcePayload sourcePayload,
                                                     Map<String, Object> headers,
                                                     Map<String, Object> query,
                                                     Map<String, Object> form,
                                                     String rawBody) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        if (row != null) {
            values.putAll(row);
        }
        Map<String, Object> safeHeaders = headers == null ? new LinkedHashMap<String, Object>() : headers;
        Map<String, Object> safeQuery = query == null ? new LinkedHashMap<String, Object>() : query;
        Map<String, Object> safeForm = form == null ? new LinkedHashMap<String, Object>() : form;
        Object body = sourcePayload == null ? new LinkedHashMap<String, Object>() : sourcePayload.body;
        values.put("header", safeHeaders);
        values.put("headers", safeHeaders);
        values.put("query", safeQuery);
        values.put("form", safeForm);
        values.put("body", body);
        values.put("rawBody", rawBody == null ? "" : rawBody);
        values.put("headersJson", toJson(safeHeaders));
        values.put("queryJson", toJson(safeQuery));
        values.put("formJson", toJson(safeForm));
        values.put("bodyJson", toJson(body));
        values.put("receivedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return values;
    }

    private boolean isPassthroughEnabled(ProtocolConversionServiceView service, String key) {
        if (service == null || service.getRequestPassthrough() == null || !hasText(key)) {
            return false;
        }
        Object value = lookupIgnoreCase(service.getRequestPassthrough(), key);
        if (value == null && key.endsWith("s")) {
            value = lookupIgnoreCase(service.getRequestPassthrough(), key.substring(0, key.length() - 1));
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private Map<String, Object> passThroughHeaders(Map<String, Object> headers) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (headers == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (!hasText(entry.getKey()) || entry.getValue() == null || isBlockedPassthroughHeader(entry.getKey())) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private boolean isBlockedPassthroughHeader(String name) {
        if (!hasText(name)) {
            return true;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return "host".equals(normalized)
                || "connection".equals(normalized)
                || "keep-alive".equals(normalized)
                || "proxy-authenticate".equals(normalized)
                || "proxy-authorization".equals(normalized)
                || "te".equals(normalized)
                || "trailer".equals(normalized)
                || "transfer-encoding".equals(normalized)
                || "upgrade".equals(normalized)
                || "content-length".equals(normalized)
                || "content-type".equals(normalized);
    }

    private String renderTemplate(String template, Map<String, Object> values, boolean xmlEscape) {
        if (template == null) {
            return "";
        }
        String result = template;
        Matcher matcher = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}").matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = readPath(values, key);
            if (value == null) {
                value = values == null ? null : values.get(key);
            }
            String replacement = value == null ? "" : value instanceof String ? (String) value : toJson(value);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(xmlEscape ? escapeXml(replacement) : replacement));
        }
        matcher.appendTail(buffer);
        result = buffer.toString();
        return result;
    }

    private String contentTypeForTarget(ProtocolConversionProtocol protocol) {
        if (protocol == ProtocolConversionProtocol.HTTP_XML) {
            return "application/xml;charset=UTF-8";
        }
        if (protocol == ProtocolConversionProtocol.SOAP_12) {
            return "application/soap+xml;charset=UTF-8";
        }
        if (protocol == ProtocolConversionProtocol.SOAP_11) {
            return "text/xml;charset=UTF-8";
        }
        return "application/json;charset=UTF-8";
    }

    private boolean containsHeader(Map<String, Object> headers, String name) {
        if (headers == null || name == null) {
            return false;
        }
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSoapFault(String xml) {
        if (!hasText(xml)) {
            return false;
        }
        try {
            return readPath(parseXmlToMap(xml), "Envelope.Body.Fault") != null;
        } catch (StudioException ex) {
            return false;
        }
    }

    private String soapFaultMessage(String xml) {
        Object parsed;
        try {
            parsed = parseXmlToMap(xml);
        } catch (StudioException ex) {
            return truncate(xml, 500);
        }
        Object faultString = readPath(parsed, "Envelope.Body.Fault.faultstring");
        if (faultString == null) {
            faultString = readPath(parsed, "Envelope.Body.Fault.Reason.Text");
        }
        return faultString == null ? truncate(xml, 500) : String.valueOf(faultString);
    }

    private WebServiceConfig normalizedSourceWebServiceConfig(ProtocolConversionServiceView view) {
        WebServiceConfig parsed = view == null ? null : view.getWebserviceConfig();
        return webServiceSupport.normalizeConfig(parsed, "protocol-conversion", view == null ? null : view.getServiceCode());
    }

    private WebServiceConfig normalizedTargetWebServiceConfig(ProtocolConversionServiceView view) {
        WebServiceConfig parsed = view == null ? null : view.getTargetWebserviceConfig();
        if (isEmptyWebServiceConfig(parsed) && view != null && !isEmptyWebServiceConfig(view.getWebserviceConfig())) {
            parsed = view.getWebserviceConfig();
        }
        WebServiceConfig normalized = webServiceSupport.normalizeConfig(parsed, "protocol-conversion-target", view == null ? null : view.getServiceCode());
        if (parsed != null && !hasText(parsed.getSoapAction())) {
            normalized.setSoapAction(null);
        }
        return normalized;
    }

    private Map<String, Object> toWebServiceConfigMap(WebServiceConfig config, String domain, String serviceCode) {
        WebServiceConfig normalized = webServiceSupport.normalizeConfig(config, domain, serviceCode);
        normalized.setEnabled(Boolean.TRUE);
        return objectMapper.convertValue(normalized, new TypeReference<Map<String, Object>>() {
        });
    }

    private WebServiceConfig fromWebServiceConfigMap(Map<String, Object> config, String serviceCode) {
        WebServiceConfig parsed = config == null
                ? new WebServiceConfig()
                : objectMapper.convertValue(config, WebServiceConfig.class);
        parsed.setEnabled(Boolean.TRUE);
        return webServiceSupport.normalizeConfig(parsed, "protocol-conversion", serviceCode);
    }

    private WebServiceConfig fromTargetWebServiceConfigMap(Map<String, Object> targetConfig,
                                                           Map<String, Object> sourceConfig,
                                                           String serviceCode) {
        boolean useSourceFallback = targetConfig == null || targetConfig.isEmpty();
        Map<String, Object> effective = useSourceFallback ? sourceConfig : targetConfig;
        WebServiceConfig parsed = effective == null
                ? new WebServiceConfig()
                : objectMapper.convertValue(effective, WebServiceConfig.class);
        parsed.setEnabled(Boolean.TRUE);
        return webServiceSupport.normalizeConfig(parsed,
                useSourceFallback ? "protocol-conversion" : "protocol-conversion-target",
                serviceCode);
    }

    private boolean isEmptyWebServiceConfig(WebServiceConfig config) {
        return config == null
                || (!hasText(config.getNamespaceUri())
                && !hasText(config.getOperationName())
                && !hasText(config.getSoapAction())
                && !hasText(config.getRequestRootName())
                && !hasText(config.getResponseRootName()));
    }

    private String debugRawBody(ProtocolConversionServiceView view, ProtocolConversionDebugRequest request) {
        if (request != null && hasText(request.getRawBody())) {
            return request.getRawBody();
        }
        if (request == null || request.getBody() == null) {
            return "";
        }
        try {
            if (view.getSourceProtocol() == ProtocolConversionProtocol.HTTP_XML) {
                return request.getBody() instanceof String ? String.valueOf(request.getBody()) : objectToXml("root", request.getBody());
            }
            if (view.getSourceProtocol() == ProtocolConversionProtocol.SOAP_11 || view.getSourceProtocol() == ProtocolConversionProtocol.SOAP_12) {
                return request.getBody() instanceof String ? String.valueOf(request.getBody()) : buildSourceSoapEnvelope(view, request.getBody());
            }
            return request.getBody() instanceof String ? String.valueOf(request.getBody()) : objectMapper.writeValueAsString(request.getBody());
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Failed to build debug request body: " + rootMessage(ex));
        }
    }

    private String buildInvocationSystemLog(ProtocolConversionServiceEntity service,
                                            ProtocolConversionSubscriptionEntity subscription,
                                            String defaultSubscriptionName,
                                            String requestId,
                                            String requestMethod,
                                            LocalDateTime occurredAt,
                                            long startedAt,
                                            boolean success,
                                            int httpStatus,
                                            Integer targetHttpStatus,
                                            String errorCode,
                                            String errorMessage,
                                            long receivedCount,
                                            long successCount,
                                            long failedCount) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("requestId", requestId);
        values.put("occurredAt", occurredAt == null ? null : occurredAt.toString());
        values.put("requestMethod", requestMethod);
        values.put("serviceCode", service == null ? null : service.getServiceCode());
        values.put("serviceName", service == null ? null : service.getServiceName());
        values.put("conversionMode", service == null ? null : service.getConversionMode());
        values.put("sourceProtocol", service == null ? null : service.getSourceProtocol());
        values.put("targetProtocol", service == null ? null : service.getTargetProtocol());
        values.put("targetPath", service == null ? null : service.getTargetPath());
        values.put("subscription", subscription == null ? defaultSubscriptionName : subscription.getSubscriptionName());
        values.put("receivedCount", receivedCount);
        values.put("successCount", successCount);
        values.put("failedCount", failedCount);
        values.put("httpStatus", httpStatus);
        values.put("targetHttpStatus", targetHttpStatus);
        values.put("result", success ? "SUCCESS" : "FAILED");
        if (hasText(errorCode) || hasText(errorMessage)) {
            values.put("error", joinError(errorCode, errorMessage));
        }
        values.put("durationMs", Math.max(0L, (System.nanoTime() - startedAt) / 1000000L));
        return invocationLogService.summaryLog("Invocation Summary", values);
    }

    private String buildInvocationArchiveLog(String systemLog,
                                             Map<String, Object> headers,
                                             Map<String, Object> query,
                                             Map<String, Object> form,
                                             String rawBody,
                                             ProtocolConversionInvokeResult result,
                                             ProtocolConversionTraceView trace,
                                             String capturedLog) {
        StringBuilder builder = new StringBuilder(4096);
        invocationLogService.appendSection(builder, "Invocation Summary", systemLog);
        invocationLogService.appendSection(builder, TRACE_SECTION_TITLE, renderTraceText(trace));
        invocationLogService.appendSection(builder, TRACE_JSON_SECTION_TITLE, traceJson(trace));
        invocationLogService.appendSection(builder, "Source Request Headers", invocationLogService.previewValue(invocationLogService.sanitizeHeaders(headers)));
        invocationLogService.appendSection(builder, "Source Request Query", invocationLogService.previewValue(query));
        invocationLogService.appendSection(builder, "Source Request Form", invocationLogService.previewValue(form));
        invocationLogService.appendSection(builder, "Source Raw Body", invocationLogService.previewValue(rawBody));
        invocationLogService.appendSection(builder, "Target Response Body", invocationLogService.previewValue(result == null ? null : result.getTargetBody()));
        invocationLogService.appendSection(builder, "Open Response Body", invocationLogService.previewValue(result == null ? null : result.getResponseBody()));
        invocationLogService.appendSection(builder, "Captured Console Logs",
                hasText(capturedLog) ? capturedLog : "No console log was captured for this invocation.");
        return builder.toString();
    }

    private String renderTraceText(ProtocolConversionTraceView trace) {
        if (trace == null) {
            return "-";
        }
        StringBuilder builder = new StringBuilder(2048);
        appendTraceStep(builder, trace.getSourceRequest());
        appendTraceStep(builder, trace.getConvertedRequest());
        appendTraceStep(builder, trace.getTargetResponse());
        appendTraceStep(builder, trace.getConvertedResponse());
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private void appendTraceStep(StringBuilder builder, ProtocolConversionTraceStepView step) {
        if (builder == null || step == null) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(System.lineSeparator());
        }
        builder.append("[").append(step.getTitle()).append("] ")
                .append(step.getStatus() == null ? "-" : step.getStatus()).append(System.lineSeparator());
        invocationLogService.appendLine(builder, "protocol", step.getProtocol());
        invocationLogService.appendLine(builder, "method", step.getMethod());
        invocationLogService.appendLine(builder, "url", step.getUrl());
        invocationLogService.appendLine(builder, "httpStatus", step.getHttpStatus());
        invocationLogService.appendLine(builder, "contentType", step.getContentType());
        invocationLogService.appendLine(builder, "summary", step.getSummary());
        if (hasText(step.getErrorMessage())) {
            invocationLogService.appendLine(builder, "error", step.getErrorMessage());
        }
    }

    private String traceJson(ProtocolConversionTraceView trace) {
        if (trace == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String joinError(String errorCode, String errorMessage) {
        String code = hasText(errorCode) ? errorCode.trim() : "";
        String message = hasText(errorMessage) ? errorMessage.trim() : "";
        return (code + " " + message).trim();
    }

    private ProtocolConversionStatus resolveSavedStatus(ProtocolConversionServiceEntity entity) {
        if (entity.getId() == null) {
            return ProtocolConversionStatus.DRAFT;
        }
        ProtocolConversionStatus current = enumValue(ProtocolConversionStatus.class, entity.getStatus(), ProtocolConversionStatus.DRAFT);
        return current == ProtocolConversionStatus.ONLINE ? ProtocolConversionStatus.ONLINE : ProtocolConversionStatus.DRAFT;
    }

    private boolean isTokenRequired(ProtocolConversionServiceEntity service) {
        return service == null || service.getTokenRequired() == null || service.getTokenRequired().intValue() != 0;
    }

    private String defaultSubscriptionNameForLog(ProtocolConversionServiceEntity service) {
        if (isTokenRequired(service)) {
            return null;
        }
        String configuredName = normalizeDefaultSubscriptionName(service.getDefaultSubscriptionName());
        return hasText(configuredName) ? configuredName : DEFAULT_NO_TOKEN_SUBSCRIPTION_NAME;
    }

    private String normalizeDefaultSubscriptionName(String value) {
        String normalized = normalizeText(value);
        if (!hasText(normalized)) {
            return null;
        }
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private int statusForException(StudioException ex) {
        if (StudioErrorCode.UNAUTHORIZED.equals(ex.getCode())) {
            return 401;
        }
        if (StudioErrorCode.NOT_FOUND.equals(ex.getCode())) {
            return 404;
        }
        if (StudioErrorCode.INTERNAL_SERVER_ERROR.equals(ex.getCode())) {
            return 500;
        }
        return 400;
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() < 1 ? DEFAULT_PAGE_NO : pageNo.intValue();
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize.intValue() < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize.intValue(), MAX_PAGE_SIZE);
    }

    private String normalizeMethod(String method, String defaultMethod) {
        return hasText(method) ? method.trim().toUpperCase(Locale.ROOT) : defaultMethod;
    }

    private String buildEndpointPath(String serviceCode, String serviceKey) {
        return OPEN_PATH_PREFIX + "/" + serviceCode + "/" + serviceKey;
    }

    private String buildWebServiceEndpointPath(String serviceCode, String serviceKey) {
        return WS_OPEN_PATH_PREFIX + "/" + serviceCode + "/" + serviceKey;
    }

    private String targetPath(String targetPath, String targetField) {
        return hasText(targetPath) ? targetPath.trim() : targetField;
    }

    private String parentPath(String path) {
        if (!hasText(path)) {
            return null;
        }
        String trimmed = path.trim();
        int index = trimmed.lastIndexOf('.');
        return index <= 0 ? null : trimmed.substring(0, index);
    }

    private Map<String, Object> sanitizeTargetRequest(Map<String, Object> snapshot) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (snapshot == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            if ("headers".equals(entry.getKey()) && entry.getValue() instanceof Map<?, ?>) {
                result.put(entry.getKey(), sanitizeHeaders(castMap(entry.getValue())));
            } else if ("url".equals(entry.getKey()) && entry.getValue() instanceof String) {
                result.put(entry.getKey(), sanitizeUrl(String.valueOf(entry.getValue())));
            } else if ("body".equals(entry.getKey())) {
                result.put(entry.getKey(), previewForTrace(entry.getValue()));
            } else if (entry.getValue() instanceof Map<?, ?>) {
                result.put(entry.getKey(), sanitizeSensitiveMap(castMap(entry.getValue())));
            } else {
                result.put(entry.getKey(), entry.getValue() instanceof String
                        ? sanitizeSensitiveText(String.valueOf(entry.getValue()))
                        : entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Object> sanitizeSensitiveMap(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (values == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (isSensitiveDiagnosticName(entry.getKey())) {
                result.put(entry.getKey(), "******");
            } else if (entry.getValue() instanceof Map<?, ?>) {
                result.put(entry.getKey(), sanitizeSensitiveMap(castMap(entry.getValue())));
            } else if (entry.getValue() instanceof List<?>) {
                result.put(entry.getKey(), sanitizeSensitiveList((List<?>) entry.getValue()));
            } else if (entry.getValue() instanceof String) {
                result.put(entry.getKey(), sanitizeSensitiveText(String.valueOf(entry.getValue())));
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private List<Object> sanitizeSensitiveList(List<?> values) {
        List<Object> result = new ArrayList<Object>();
        if (values == null) {
            return result;
        }
        for (Object value : values) {
            if (value instanceof Map<?, ?>) {
                result.add(sanitizeSensitiveMap(castMap(value)));
            } else if (value instanceof List<?>) {
                result.add(sanitizeSensitiveList((List<?>) value));
            } else if (value instanceof String) {
                result.add(sanitizeSensitiveText(String.valueOf(value)));
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private String sanitizeUrl(String url) {
        if (!hasText(url)) {
            return url;
        }
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return sanitizeSensitiveText(url);
        }
        int fragmentStart = url.indexOf('#', queryStart + 1);
        String prefix = url.substring(0, queryStart + 1);
        String query = fragmentStart < 0 ? url.substring(queryStart + 1) : url.substring(queryStart + 1, fragmentStart);
        String fragment = fragmentStart < 0 ? "" : url.substring(fragmentStart);
        String[] parts = query.split("&", -1);
        StringBuilder builder = new StringBuilder(url.length());
        builder.append(prefix);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append('&');
            }
            String part = parts[i];
            int equalsIndex = part.indexOf('=');
            String name = equalsIndex < 0 ? part : part.substring(0, equalsIndex);
            if (isSensitiveDiagnosticName(name)) {
                builder.append(name);
                if (equalsIndex >= 0) {
                    builder.append("=******");
                }
            } else {
                builder.append(sanitizeSensitiveText(part));
            }
        }
        builder.append(fragment);
        return builder.toString();
    }

    private String sanitizeSensitiveText(String value) {
        return OpenServiceInvocationLogSupport.sanitizeSensitiveLog(value);
    }

    private Map<String, Object> sanitizeHeaders(Map<String, Object> headers) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (headers == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            result.put(entry.getKey(), isSensitiveHeader(entry.getKey()) ? "******" : entry.getValue());
        }
        return result;
    }

    private boolean isSensitiveHeader(String name) {
        return isSensitiveDiagnosticName(name);
    }

    private boolean isSensitiveDiagnosticName(String name) {
        if (!hasText(name)) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("secret")
                || normalized.contains("key")
                || normalized.contains("password")
                || normalized.contains("credential");
    }

    private boolean isXmlTarget(ProtocolConversionProtocol protocol) {
        return protocol == ProtocolConversionProtocol.HTTP_XML || isSoapTarget(protocol);
    }

    private boolean isSoapTarget(ProtocolConversionProtocol protocol) {
        return protocol == ProtocolConversionProtocol.SOAP_11 || protocol == ProtocolConversionProtocol.SOAP_12;
    }

    private String joinHttpUrl(String baseUrl, String requestPath) {
        boolean baseEndsWithSlash = baseUrl.endsWith("/");
        boolean pathStartsWithSlash = requestPath.startsWith("/");
        if (baseEndsWithSlash && pathStartsWithSlash) {
            return baseUrl + requestPath.substring(1);
        }
        if (!baseEndsWithSlash && !pathStartsWithSlash) {
            return baseUrl + "/" + requestPath;
        }
        return baseUrl + requestPath;
    }

    private boolean isAbsoluteHttpUrl(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String transformerCode(TransformerBinding binding) {
        if (binding == null) {
            return null;
        }
        if (hasText(binding.getTransformerCode())) {
            return binding.getTransformerCode();
        }
        return binding.getMappingCode();
    }

    private String digest(String algorithm, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Digest failed: " + rootMessage(ex));
        }
    }

    private int intOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options == null ? null : options.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private String optionText(Map<String, Object> options, String key) {
        return optionText(options, key, null);
    }

    private String optionText(Map<String, Object> options, String key, String defaultValue) {
        Object value = options == null ? null : options.get(key);
        return value == null || !hasText(String.valueOf(value)) ? defaultValue : String.valueOf(value).trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? new LinkedHashMap<String, Object>() : value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String localName(Node node) {
        String localName = node.getLocalName();
        if (hasText(localName)) {
            return localName;
        }
        String name = node.getNodeName();
        int colon = name == null ? -1 : name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private String normalizeXmlName(String value, String fallback) {
        String raw = hasText(value) ? value.trim() : fallback;
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            boolean allowed = current == '_' || current == '-' || current == '.' || Character.isLetterOrDigit(current);
            if (allowed) {
                builder.append(current);
            }
        }
        String normalized = builder.length() == 0 ? fallback : builder.toString();
        char first = normalized.charAt(0);
        return Character.isLetter(first) || first == '_' ? normalized : "n" + normalized;
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private boolean isBlankValue(Object value) {
        return value == null || value instanceof String && ((String) value).trim().isEmpty();
    }

    private String normalizeRequiredText(String value, String message) {
        if (!hasText(value)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeText(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void validateSimpleIdentifier(String identifier, String message) {
        if (!hasText(identifier) || !SIMPLE_IDENTIFIER.matcher(identifier.trim()).matches()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
    }

    private String tokenMaskedForList(String tokenMasked) {
        return hasText(tokenMasked) ? tokenMasked : "历史 Token 不可查看，请重新生成";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null ? "unknown error" : current.getMessage();
    }

    private String newRequestId() {
        return String.valueOf(IdWorker.getId());
    }

    private <T> List<Map<String, Object>> toMapList(List<T> values) {
        if (values == null) {
            return new ArrayList<Map<String, Object>>();
        }
        return objectMapper.convertValue(values, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    private List<Map<String, Object>> transformerMapList(List<TransformerBinding> values) {
        if (values == null) {
            return new ArrayList<Map<String, Object>>();
        }
        return objectMapper.convertValue(values, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    private <T> List<T> fromMapList(List<Map<String, Object>> values, TypeReference<List<T>> typeReference) {
        if (values == null) {
            return new ArrayList<T>();
        }
        return objectMapper.convertValue(values, typeReference);
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<String, Object>() : value;
    }

    private <E extends Enum<E>> E enumValue(Class<E> enumClass, String value, E defaultValue) {
        if (!hasText(value)) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private static final class SourcePayload {
        private final Object body;
        private final String rawBody;

        private SourcePayload(Object body, String rawBody) {
            this.body = body;
            this.rawBody = rawBody;
        }
    }

    private static final class TargetRequest {
        private String url;
        private String method;
        private Map<String, Object> headers = new LinkedHashMap<String, Object>();
        private String contentType;
        private String body;

        private Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("url", url);
            result.put("method", method);
            result.put("headers", headers);
            result.put("contentType", contentType);
            result.put("body", body);
            return result;
        }
    }

    private static final class TargetResponse {
        private final int status;
        private final String contentType;
        private final String body;

        private TargetResponse(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private static final class TargetResponseException extends StudioException {
        private final TargetResponse targetResponse;

        private TargetResponseException(String code, String message, TargetResponse targetResponse) {
            super(code, message);
            this.targetResponse = targetResponse;
        }

        private TargetResponse getTargetResponse() {
            return targetResponse;
        }
    }
}
