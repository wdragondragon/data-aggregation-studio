package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataServiceRequestMethod;
import com.jdragon.studio.dto.enums.DataServiceResponseType;
import com.jdragon.studio.dto.enums.DataServiceSourceType;
import com.jdragon.studio.dto.enums.DataServiceStatus;
import com.jdragon.studio.dto.enums.DataServiceType;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataServiceDefinitionView;
import com.jdragon.studio.dto.model.DataServiceFieldView;
import com.jdragon.studio.dto.model.DataServicePublishParamView;
import com.jdragon.studio.dto.model.DataServiceRequestParamView;
import com.jdragon.studio.dto.model.DataServiceResolveFieldsView;
import com.jdragon.studio.dto.model.DataServiceResponseParamView;
import com.jdragon.studio.dto.model.DataServiceSubscriptionView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.WebServiceConfig;
import com.jdragon.studio.dto.model.WebServiceDebugResult;
import com.jdragon.studio.dto.model.WebServicePreviewView;
import com.jdragon.studio.dto.model.request.DataServiceDebugRequest;
import com.jdragon.studio.dto.model.request.DataServiceResolveFieldsRequest;
import com.jdragon.studio.dto.model.request.DataServiceSaveRequest;
import com.jdragon.studio.dto.model.request.DataServiceSubscriptionCreateRequest;
import com.jdragon.studio.dto.model.request.WebServiceDebugRequest;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DataServiceSubscriptionEntity;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataServicePublishParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceRequestParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceResponseParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceSubscriptionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DataServiceService {

    private static final long CACHE_TTL_MILLIS = 60000L;
    private static final String DEFAULT_NO_TOKEN_SUBSCRIPTION_NAME = "免 Token 调用";
    private static final String WS_OPEN_PATH_PREFIX = "/openapi/ws/data-services";

    private final DataServiceDefinitionMapper definitionMapper;
    private final DataServiceSubscriptionMapper subscriptionMapper;
    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final DataDevelopmentSqlExecutor sqlExecutor;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final DataServiceResponseCacheService responseCacheService;
    private final DataServiceInvocationSupport dataServiceInvocationSupport = new DataServiceInvocationSupport();
    private final DataServiceParamSupport dataServiceParamSupport;
    private final DataServiceAccessLogSupport dataServiceAccessLogSupport;
    private final DataServiceTokenSupport dataServiceTokenSupport = new DataServiceTokenSupport();
    private final WebServiceSupport webServiceSupport = new WebServiceSupport();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataServiceService(DataServiceDefinitionMapper definitionMapper,
                              DataServiceRequestParamMapper requestParamMapper,
                              DataServiceResponseParamMapper responseParamMapper,
                              DataServicePublishParamMapper publishParamMapper,
                              DataServiceSubscriptionMapper subscriptionMapper,
                              DataServiceAccessLogMapper accessLogMapper,
                              DataServiceAccessCounterMapper accessCounterMapper,
                              DataSourceService dataSourceService,
                              DataModelService dataModelService,
                              DataDevelopmentSqlExecutor sqlExecutor,
                              StudioSecurityService securityService,
                              ProjectResourceAccessService projectResourceAccessService,
                              DataServiceResponseCacheService responseCacheService) {
        this.definitionMapper = definitionMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.sqlExecutor = sqlExecutor;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.responseCacheService = responseCacheService;
        this.dataServiceParamSupport = new DataServiceParamSupport(requestParamMapper, responseParamMapper, publishParamMapper, dataServiceInvocationSupport);
        this.dataServiceAccessLogSupport = new DataServiceAccessLogSupport(accessLogMapper, accessCounterMapper, dataServiceInvocationSupport);
    }

    public PageView<DataServiceDefinitionView> list(Integer pageNo,
                                                    Integer pageSize,
                                                    String keyword,
                                                    String status,
                                                    String serviceType) {
        int safePageNo = dataServiceInvocationSupport.normalizePageNo(pageNo);
        int safePageSize = dataServiceInvocationSupport.normalizePageSize(pageSize);
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return PageView.of(safePageNo, safePageSize, 0L, new ArrayList<DataServiceDefinitionView>());
        }
        String normalizedKeyword = dataServiceInvocationSupport.normalizeText(keyword);
        String normalizedStatus = dataServiceInvocationSupport.normalizeText(status);
        String normalizedServiceType = dataServiceInvocationSupport.normalizeText(serviceType);
        Page<DataServiceDefinitionEntity> page = new Page<DataServiceDefinitionEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<DataServiceDefinitionEntity> queryWrapper = new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                .eq(DataServiceDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(DataServiceDefinitionEntity::getProjectId, currentProjectId)
                .and(dataServiceInvocationSupport.hasText(normalizedKeyword), wrapper -> wrapper.like(DataServiceDefinitionEntity::getServiceName, normalizedKeyword)
                        .or()
                        .like(DataServiceDefinitionEntity::getServiceCode, normalizedKeyword)
                        .or()
                        .like(DataServiceDefinitionEntity::getDatasourceNameSnapshot, normalizedKeyword)
                        .or()
                        .like(DataServiceDefinitionEntity::getModelNameSnapshot, normalizedKeyword))
                .eq(dataServiceInvocationSupport.hasText(normalizedStatus), DataServiceDefinitionEntity::getStatus,
                        normalizedStatus == null ? null : normalizedStatus.toUpperCase(Locale.ROOT))
                .eq(dataServiceInvocationSupport.hasText(normalizedServiceType), DataServiceDefinitionEntity::getServiceType,
                        normalizedServiceType == null ? null : normalizedServiceType.toUpperCase(Locale.ROOT))
                .orderByDesc(DataServiceDefinitionEntity::getUpdatedAt)
                .orderByDesc(DataServiceDefinitionEntity::getId);
        Page<DataServiceDefinitionEntity> entityPage = definitionMapper.selectPage(page, queryWrapper);
        List<DataServiceDefinitionView> items = new ArrayList<DataServiceDefinitionView>();
        for (DataServiceDefinitionEntity entity : entityPage.getRecords()) {
            items.add(toView(entity, false));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    public DataServiceDefinitionView get(Long id) {
        DataServiceDefinitionEntity entity = requireAccessibleEntity(id);
        return toView(entity, true);
    }

    @Transactional
    public DataServiceDefinitionView save(DataServiceSaveRequest request) {
        validateSaveRequest(request);
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        DataServiceDefinitionEntity entity = request.getId() == null
                ? new DataServiceDefinitionEntity()
                : requireWritableEntity(request.getId());
        ensureUniqueServiceCode(currentProjectId, request.getServiceCode(), entity.getId());

        DataServiceType serviceType = request.getServiceType() == null ? DataServiceType.MODEL_PUBLISH : request.getServiceType();
        DataServiceSourceType sourceType = request.getSourceType() == null ? DataServiceSourceType.TABLE : request.getSourceType();
        DataServiceRequestMethod requestMethod = request.getRequestMethod() == null ? DataServiceRequestMethod.GET : request.getRequestMethod();
        DataServiceResponseType responseType = request.getResponseType() == null ? DataServiceResponseType.JSON : request.getResponseType();

        DataSourceDefinition datasource = dataSourceService.getInternal(request.getDatasourceId());
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + request.getDatasourceId());
        }
        if (!sqlExecutor.supports(datasource)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource does not support SQL execution");
        }
        DataModelDefinition model = null;
        String physicalLocator = null;
        String normalizedSql = null;
        if (sourceType == DataServiceSourceType.TABLE) {
            if (request.getModelId() == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model is required for table source");
            }
            model = dataModelService.get(request.getModelId());
            if (model.getDatasourceId() == null || model.getDatasourceId().longValue() != datasource.getId().longValue()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model does not belong to selected datasource");
            }
            physicalLocator = dataServiceInvocationSupport.normalizeRequiredText(model.getPhysicalLocator(), "Model physical locator is empty");
            dataServiceInvocationSupport.validateTableReference(physicalLocator);
        } else {
            normalizedSql = dataServiceInvocationSupport.normalizeSelectSql(request.getCustomSql());
        }

        entity.setTenantId(securityService.currentTenantId());
        entity.setProjectId(currentProjectId);
        entity.setCreatedBy(entity.getId() == null ? securityService.currentUserId() : entity.getCreatedBy());
        entity.setServiceCode(dataServiceInvocationSupport.normalizeRequiredText(request.getServiceCode(), "Service code is required"));
        entity.setServiceName(dataServiceInvocationSupport.normalizeRequiredText(request.getServiceName(), "Service name is required"));
        entity.setServiceType(serviceType.name());
        entity.setStatus(resolveSavedStatus(entity).name());
        entity.setSourceType(sourceType.name());
        entity.setDatasourceId(datasource.getId());
        entity.setDatasourceNameSnapshot(datasource.getName());
        entity.setDatasourceTypeCode(datasource.getTypeCode());
        entity.setModelId(model == null ? null : model.getId());
        entity.setModelNameSnapshot(model == null ? null : model.getName());
        entity.setModelPhysicalLocator(physicalLocator);
        entity.setCustomSql(normalizedSql);
        entity.setRequestMethod(requestMethod.name());
        entity.setResponseType(responseType.name());
        entity.setServiceKey(dataServiceInvocationSupport.hasText(entity.getServiceKey()) ? entity.getServiceKey() : dataServiceTokenSupport.generateServiceKey());
        entity.setEndpointPath(dataServiceInvocationSupport.buildEndpointPath(entity.getServiceCode(), entity.getServiceKey()));
        entity.setCacheEnabled(Boolean.TRUE.equals(request.getCacheEnabled()) ? Integer.valueOf(1) : Integer.valueOf(0));
        entity.setTokenRequired(Boolean.FALSE.equals(request.getTokenRequired()) ? Integer.valueOf(0) : Integer.valueOf(1));
        entity.setDefaultSubscriptionName(normalizeDefaultSubscriptionName(request.getDefaultSubscriptionName()));
        entity.setWebserviceEnabled(Boolean.TRUE.equals(request.getWebserviceEnabled()) ? Integer.valueOf(1) : Integer.valueOf(0));
        entity.setWebserviceConfigJson(toWebServiceConfigMap(request.getWebserviceConfig(), "data-service", entity.getServiceCode(),
                Integer.valueOf(1).equals(entity.getWebserviceEnabled())));
        if (entity.getId() == null) {
            definitionMapper.insert(entity);
        } else {
            definitionMapper.updateById(entity);
            dataServiceParamSupport.deleteChildren(entity.getId());
            evictServiceCache(entity.getId());
        }

        DataServiceResolveFieldsView resolvedFields = resolveFieldsForDefinition(sourceType, datasource, model, normalizedSql);
        List<DataServiceRequestParamView> requestParams = dataServiceParamSupport.normalizeRequestParams(request.getRequestParams());
        List<DataServiceResponseParamView> responseParams = dataServiceParamSupport.normalizeResponseParams(request.getResponseParams(), resolvedFields.getResponseParams());
        List<DataServicePublishParamView> publishParams = dataServiceParamSupport.normalizePublishParams(request.getPublishParams(), requestParams, requestMethod);
        dataServiceParamSupport.saveChildren(entity.getId(), requestParams, responseParams, publishParams);
        return get(entity.getId());
    }

    @Transactional
    public void delete(Long id) {
        DataServiceDefinitionEntity entity = requireWritableEntity(id);
        dataServiceParamSupport.deleteChildren(entity.getId());
        definitionMapper.deleteById(id);
        evictServiceCache(id);
    }

    @Transactional
    public DataServiceDefinitionView publish(Long id) {
        DataServiceDefinitionEntity entity = requireWritableEntity(id);
        DataServiceDefinitionView view = toView(entity, true);
        validateExecutable(view);
        if (view.getServiceKey() == null || view.getServiceKey().trim().isEmpty()) {
            entity.setServiceKey(dataServiceTokenSupport.generateServiceKey());
            entity.setEndpointPath(dataServiceInvocationSupport.buildEndpointPath(entity.getServiceCode(), entity.getServiceKey()));
        }
        entity.setStatus(DataServiceStatus.ONLINE.name());
        definitionMapper.updateById(entity);
        evictServiceCache(id);
        return get(id);
    }

    @Transactional
    public DataServiceDefinitionView offline(Long id) {
        DataServiceDefinitionEntity entity = requireWritableEntity(id);
        entity.setStatus(DataServiceStatus.OFFLINE.name());
        definitionMapper.updateById(entity);
        evictServiceCache(id);
        return get(id);
    }

    public DataServiceResolveFieldsView resolveFields(DataServiceResolveFieldsRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Resolve request is required");
        }
        DataServiceSourceType sourceType = request.getSourceType() == null ? DataServiceSourceType.TABLE : request.getSourceType();
        if (request.getDatasourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource is required");
        }
        DataSourceDefinition datasource = dataSourceService.getInternal(request.getDatasourceId());
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + request.getDatasourceId());
        }
        if (!sqlExecutor.supports(datasource)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource does not support SQL execution");
        }
        DataModelDefinition model = null;
        String customSql = null;
        if (sourceType == DataServiceSourceType.TABLE) {
            if (request.getModelId() == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model is required");
            }
            model = dataModelService.get(request.getModelId());
            if (model.getDatasourceId() == null || model.getDatasourceId().longValue() != datasource.getId().longValue()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model does not belong to selected datasource");
            }
        } else {
            customSql = dataServiceInvocationSupport.normalizeSelectSql(request.getCustomSql());
        }
        return resolveFieldsForDefinition(sourceType, datasource, model, customSql);
    }

    public Map<String, Object> debug(Long id, DataServiceDebugRequest request) {
        DataServiceDefinitionView view = get(id);
        return execute(view,
                request == null ? new LinkedHashMap<String, Object>() : request.getHeaders(),
                request == null ? new LinkedHashMap<String, Object>() : request.getQuery(),
                request == null ? new LinkedHashMap<String, Object>() : request.getBody(),
                false).data;
    }

    public WebServicePreviewView previewWebService(Long id) {
        DataServiceDefinitionView view = get(id);
        return webServiceSupport.previewForDataService(view, buildWebServiceEndpointPath(view.getServiceCode(), view.getServiceKey()));
    }

    public WebServiceDebugResult debugWebService(Long id, WebServiceDebugRequest request) {
        DataServiceDefinitionView view = get(id);
        ensureWebServiceEnabled(view);
        String envelope = request == null || request.getSoapEnvelope() == null || request.getSoapEnvelope().trim().isEmpty()
                ? previewWebService(id).getSampleRequest()
                : request.getSoapEnvelope();
        WebServiceSupport.ParsedSoapRequest parsed = webServiceSupport.parse(envelope);
        WebServiceConfig config = normalizedWebServiceConfig(view);
        validateSoapOperation(config, parsed);
        Map<String, Object> headers = webServiceSupport.mergeHeaders(request == null ? null : request.getHeaders(), parsed.getHeaders());
        DataServiceExecutionResult result = execute(view, headers, parsed.getBody(), parsed.getBody(), false);
        WebServiceDebugResult debugResult = new WebServiceDebugResult();
        debugResult.setSuccess(Boolean.TRUE);
        debugResult.setHttpStatus(Integer.valueOf(200));
        debugResult.setRequestEnvelope(envelope);
        debugResult.setResult(result.data);
        debugResult.setResponseEnvelope(webServiceSupport.successEnvelope(config, result.data, parsed.getSoapVersion()));
        return debugResult;
    }

    public String webServiceWsdl(String serviceCode, String serviceKey, String endpointUrl) {
        DataServiceDefinitionEntity entity = requireOpenWebServiceEntity(serviceCode, serviceKey);
        DataServiceDefinitionView view = toView(entity, true);
        WebServiceConfig config = normalizedWebServiceConfig(view);
        return webServiceSupport.wsdlForDataService(view, config, endpointUrl);
    }

    public String webServiceFault(com.jdragon.studio.dto.enums.WebServiceSoapVersion version, String code, String message) {
        return webServiceSupport.faultEnvelope(version, code, message);
    }

    public String invokeWebService(String serviceCode,
                                   String serviceKey,
                                   String token,
                                   Map<String, Object> httpHeaders,
                                   String soapEnvelope,
                                   String clientIp,
                                   String userAgent) {
        DataServiceDefinitionEntity entity = requireOpenWebServiceEntity(serviceCode, serviceKey);
        DataServiceDefinitionView view = toView(entity, true);
        WebServiceSupport.ParsedSoapRequest parsed = webServiceSupport.parse(soapEnvelope);
        WebServiceConfig config = normalizedWebServiceConfig(view);
        validateSoapOperation(config, parsed);
        String effectiveToken = dataServiceInvocationSupport.hasText(token)
                ? token
                : webServiceSupport.tokenFromSoapHeader(parsed, "token", "dataServiceToken");
        Map<String, Object> headers = webServiceSupport.mergeHeaders(httpHeaders, parsed.getHeaders());
        Map<String, Object> data = invoke(serviceCode, serviceKey, effectiveToken, headers, parsed.getBody(), parsed.getBody(),
                "SOAP", clientIp, userAgent);
        return webServiceSupport.successEnvelope(config, data, parsed.getSoapVersion());
    }

    public Map<String, Object> invoke(String serviceCode,
                                      String serviceKey,
                                      String token,
                                       Map<String, Object> headers,
                                       Map<String, Object> query,
                                       Map<String, Object> body) {
        return invoke(serviceCode, serviceKey, token, headers, query, body, null, null, null);
    }

    public Map<String, Object> invoke(String serviceCode,
                                      String serviceKey,
                                      String token,
                                      Map<String, Object> headers,
                                      Map<String, Object> query,
                                      Map<String, Object> body,
                                      String requestMethod,
                                      String clientIp,
                                      String userAgent) {
        long startedAt = System.nanoTime();
        LocalDateTime occurredAt = LocalDateTime.now();
        DataServiceDefinitionEntity entity = null;
        DataServiceSubscriptionEntity subscription = null;
        boolean success = false;
        int httpStatus = 200;
        String errorCode = null;
        String errorMessage = null;
        boolean cacheEnabled = false;
        boolean cacheHit = false;
        long rowCount = 0L;
        try {
            entity = definitionMapper.selectOne(new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                    .eq(DataServiceDefinitionEntity::getServiceCode, dataServiceInvocationSupport.normalizeRequiredText(serviceCode, "Service code is required"))
                    .eq(DataServiceDefinitionEntity::getServiceKey, dataServiceInvocationSupport.normalizeRequiredText(serviceKey, "Service key is required"))
                    .last("limit 1"));
            if (entity == null || !DataServiceStatus.ONLINE.name().equalsIgnoreCase(entity.getStatus())) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Data service is not available");
            }
            cacheEnabled = Integer.valueOf(1).equals(entity.getCacheEnabled());
            subscription = resolveInvocationSubscription(entity, token);
            DataServiceExecutionResult result = execute(toView(entity, true), headers, query, body, true);
            success = true;
            cacheHit = result.cacheHit;
            rowCount = result.rowCount;
            return result.data;
        } catch (StudioException ex) {
            httpStatus = statusForException(ex);
            errorCode = ex.getCode();
            errorMessage = ex.getMessage();
            throw ex;
        } catch (RuntimeException ex) {
            httpStatus = 500;
            errorCode = StudioErrorCode.INTERNAL_SERVER_ERROR;
            errorMessage = ex.getMessage();
            throw ex;
        } finally {
            dataServiceAccessLogSupport.recordAccessLog(entity, subscription, defaultSubscriptionNameForLog(entity), requestMethod, occurredAt, startedAt, success, httpStatus,
                    errorCode, errorMessage, clientIp, userAgent, cacheEnabled, cacheHit, rowCount);
        }
    }

    public List<DataServiceSubscriptionView> listSubscriptions(Long serviceId) {
        DataServiceDefinitionEntity service = requireAccessibleEntity(serviceId);
        List<DataServiceSubscriptionEntity> entities = subscriptionMapper.selectList(new LambdaQueryWrapper<DataServiceSubscriptionEntity>()
                .eq(DataServiceSubscriptionEntity::getServiceId, service.getId())
                .orderByDesc(DataServiceSubscriptionEntity::getCreatedAt)
                .orderByDesc(DataServiceSubscriptionEntity::getId));
        List<DataServiceSubscriptionView> result = new ArrayList<DataServiceSubscriptionView>();
        for (DataServiceSubscriptionEntity entity : entities) {
            result.add(toSubscriptionView(entity, null));
        }
        return result;
    }

    @Transactional
    public DataServiceSubscriptionView createSubscription(Long serviceId, DataServiceSubscriptionCreateRequest request) {
        DataServiceDefinitionEntity service = requireWritableEntity(serviceId);
        if (request == null || !dataServiceInvocationSupport.hasText(request.getSubscriptionName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Subscription name is required");
        }
        String subscriptionName = request.getSubscriptionName().trim();
        List<DataServiceSubscriptionEntity> duplicates = subscriptionMapper.selectList(new LambdaQueryWrapper<DataServiceSubscriptionEntity>()
                .eq(DataServiceSubscriptionEntity::getServiceId, service.getId())
                .eq(DataServiceSubscriptionEntity::getSubscriptionName, subscriptionName)
                .orderByDesc(DataServiceSubscriptionEntity::getId));
        String token = dataServiceTokenSupport.generateSubscriptionToken();
        if (duplicates != null && !duplicates.isEmpty()) {
            DataServiceSubscriptionEntity entity = duplicates.get(0);
            entity.setTokenHash(dataServiceTokenSupport.hashToken(token));
            entity.setEnabled(1);
            entity.setCreatedBy(securityService.currentUserId());
            entity.setLastUsedAt(null);
            subscriptionMapper.updateById(entity);
            for (int index = 1; index < duplicates.size(); index++) {
                DataServiceSubscriptionEntity duplicate = duplicates.get(index);
                duplicate.setEnabled(0);
                subscriptionMapper.updateById(duplicate);
            }
            return toSubscriptionView(entity, token);
        }
        DataServiceSubscriptionEntity entity = new DataServiceSubscriptionEntity();
        entity.setTenantId(service.getTenantId());
        entity.setProjectId(service.getProjectId());
        entity.setServiceId(service.getId());
        entity.setSubscriptionName(subscriptionName);
        entity.setTokenHash(dataServiceTokenSupport.hashToken(token));
        entity.setEnabled(1);
        entity.setCreatedBy(securityService.currentUserId());
        subscriptionMapper.insert(entity);
        return toSubscriptionView(entity, token);
    }

    @Transactional
    public DataServiceSubscriptionView disableSubscription(Long serviceId, Long subscriptionId) {
        DataServiceSubscriptionEntity entity = requireSubscription(serviceId, subscriptionId);
        entity.setEnabled(0);
        subscriptionMapper.updateById(entity);
        return toSubscriptionView(entity, null);
    }

    @Transactional
    public DataServiceSubscriptionView enableSubscription(Long serviceId, Long subscriptionId) {
        DataServiceSubscriptionEntity entity = requireSubscription(serviceId, subscriptionId);
        DataServiceSubscriptionEntity activeDuplicate = subscriptionMapper.selectOne(new LambdaQueryWrapper<DataServiceSubscriptionEntity>()
                .eq(DataServiceSubscriptionEntity::getServiceId, serviceId)
                .eq(DataServiceSubscriptionEntity::getSubscriptionName, entity.getSubscriptionName())
                .eq(DataServiceSubscriptionEntity::getEnabled, 1)
                .ne(DataServiceSubscriptionEntity::getId, subscriptionId)
                .last("limit 1"));
        if (activeDuplicate != null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Another enabled subscription with the same name already exists");
        }
        entity.setEnabled(1);
        subscriptionMapper.updateById(entity);
        return toSubscriptionView(entity, null);
    }

    private DataServiceExecutionResult execute(DataServiceDefinitionView service,
                                               Map<String, Object> headers,
                                               Map<String, Object> query,
                                               Map<String, Object> body,
                                               boolean allowCache) {
        validateExecutable(service);
        Map<String, Object> safeHeaders = headers == null ? new LinkedHashMap<String, Object>() : headers;
        Map<String, Object> safeQuery = query == null ? new LinkedHashMap<String, Object>() : query;
        Map<String, Object> safeBody = body == null ? new LinkedHashMap<String, Object>() : body;
        DataServiceInvocationSupport.InvocationPlan plan = dataServiceInvocationSupport.buildInvocationPlan(service, safeHeaders, safeQuery, safeBody);
        String cacheKey = null;
        if (allowCache && Boolean.TRUE.equals(service.getCacheEnabled())) {
            cacheKey = dataServiceInvocationSupport.buildCacheKey(service.getId(), plan);
            DataServiceResponseCacheService.CacheLookup cached = responseCacheService.get(service.getId(), cacheKey);
            if (cached != null) {
                return new DataServiceExecutionResult(dataServiceInvocationSupport.copyResponseData(cached.getData()), true, cached.getRowCount());
            }
        }
        DataSourceDefinition datasource = dataSourceService.getInternal(service.getDatasourceId());
        SqlExecutionResultView countResult = sqlExecutor.executePreparedQuery(datasource, plan.countSql, plan.countParameters, 1);
        long total = dataServiceInvocationSupport.extractTotal(countResult);
        SqlExecutionResultView dataResult = sqlExecutor.executePreparedQuery(datasource, plan.dataSql, plan.dataParameters, plan.pageSize);
        Map<String, Object> data = dataServiceInvocationSupport.buildInvokeData(plan.pageNum, plan.pageSize, total, dataResult.getRows());
        long rowCount = dataResult.getRows() == null ? 0L : dataResult.getRows().size();
        if (cacheKey != null) {
            responseCacheService.put(service.getId(), cacheKey, dataServiceInvocationSupport.copyResponseData(data), rowCount, CACHE_TTL_MILLIS);
        }
        return new DataServiceExecutionResult(data, false, rowCount);
    }

    private DataServiceResolveFieldsView resolveFieldsForDefinition(DataServiceSourceType sourceType,
                                                                    DataSourceDefinition datasource,
                                                                    DataModelDefinition model,
                                                                    String customSql) {
        DataServiceResolveFieldsView result = new DataServiceResolveFieldsView();
        List<DataServiceFieldView> fields = sourceType == DataServiceSourceType.TABLE
                ? resolveModelFields(datasource, model)
                : resolveSqlFields(datasource, customSql);
        result.setFields(fields);
        result.setRequestParams(dataServiceParamSupport.defaultRequestParams());
        result.setResponseParams(dataServiceParamSupport.defaultResponseParams(fields));
        return result;
    }

    private List<DataServiceFieldView> resolveModelFields(DataSourceDefinition datasource, DataModelDefinition model) {
        List<DataServiceFieldView> fields = fieldsFromModelMetadata(model);
        if (!fields.isEmpty()) {
            return fields;
        }
        String physicalLocator = dataServiceInvocationSupport.normalizeRequiredText(model.getPhysicalLocator(), "Model physical locator is empty");
        dataServiceInvocationSupport.validateTableReference(physicalLocator);
        SqlExecutionResultView result = sqlExecutor.executePreparedQuery(datasource,
                "select * from " + physicalLocator + " where 1 = 0",
                Collections.<Object>emptyList(),
                1);
        return fieldsFromColumns(result.getColumns());
    }

    private List<DataServiceFieldView> resolveSqlFields(DataSourceDefinition datasource, String customSql) {
        SqlExecutionResultView result = sqlExecutor.executePreparedQuery(datasource,
                "select * from (" + dataServiceInvocationSupport.normalizeSelectSql(customSql) + ") ds where 1 = 0",
                Collections.<Object>emptyList(),
                1);
        return fieldsFromColumns(result.getColumns());
    }

    private List<DataServiceFieldView> fieldsFromModelMetadata(DataModelDefinition model) {
        List<DataServiceFieldView> result = new ArrayList<DataServiceFieldView>();
        if (model == null || model.getTechnicalMetadata() == null) {
            return result;
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        if (!(columns instanceof List<?>)) {
            return result;
        }
        for (Object column : (List<?>) columns) {
            if (!(column instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> item = (Map<?, ?>) column;
            String name = dataServiceInvocationSupport.asString(dataServiceInvocationSupport.firstPresent(item, "name", "columnName", "fieldName"));
            if (!dataServiceInvocationSupport.hasText(name)) {
                continue;
            }
            DataServiceFieldView field = new DataServiceFieldView();
            field.setFieldName(name.trim());
            field.setFieldType(dataServiceInvocationSupport.asString(dataServiceInvocationSupport.firstPresent(item, "type", "dataType", "columnType")));
            field.setDescription(dataServiceInvocationSupport.asString(dataServiceInvocationSupport.firstPresent(item, "comment", "description", "remark")));
            field.setExampleValue(dataServiceInvocationSupport.defaultExampleValue(dataServiceInvocationSupport.resolveValueType(field.getFieldType())));
            result.add(field);
        }
        return result;
    }

    private List<DataServiceFieldView> fieldsFromColumns(List<String> columns) {
        List<DataServiceFieldView> result = new ArrayList<DataServiceFieldView>();
        if (columns == null) {
            return result;
        }
        for (String column : columns) {
            if (!dataServiceInvocationSupport.hasText(column)) {
                continue;
            }
            DataServiceFieldView field = new DataServiceFieldView();
            field.setFieldName(column.trim());
            field.setFieldType("string");
            field.setExampleValue("示例");
            result.add(field);
        }
        return result;
    }

    private DataServiceDefinitionView toView(DataServiceDefinitionEntity entity, boolean includeChildren) {
        DataServiceDefinitionView view = new DataServiceDefinitionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setCreatedBy(entity.getCreatedBy());
        view.setServiceCode(entity.getServiceCode());
        view.setServiceName(entity.getServiceName());
        view.setServiceType(dataServiceInvocationSupport.enumValue(DataServiceType.class, entity.getServiceType(), DataServiceType.MODEL_PUBLISH));
        view.setStatus(dataServiceInvocationSupport.enumValue(DataServiceStatus.class, entity.getStatus(), DataServiceStatus.DRAFT));
        view.setSourceType(dataServiceInvocationSupport.enumValue(DataServiceSourceType.class, entity.getSourceType(), DataServiceSourceType.TABLE));
        view.setDatasourceId(entity.getDatasourceId());
        view.setDatasourceName(entity.getDatasourceNameSnapshot());
        view.setDatasourceTypeCode(entity.getDatasourceTypeCode());
        view.setModelId(entity.getModelId());
        view.setModelName(entity.getModelNameSnapshot());
        view.setModelPhysicalLocator(entity.getModelPhysicalLocator());
        view.setCustomSql(entity.getCustomSql());
        view.setRequestMethod(dataServiceInvocationSupport.enumValue(DataServiceRequestMethod.class, entity.getRequestMethod(), DataServiceRequestMethod.GET));
        view.setResponseType(dataServiceInvocationSupport.enumValue(DataServiceResponseType.class, entity.getResponseType(), DataServiceResponseType.JSON));
        view.setEndpointPath(entity.getEndpointPath());
        view.setServiceKey(entity.getServiceKey());
        view.setCacheEnabled(entity.getCacheEnabled() != null && entity.getCacheEnabled() == 1);
        view.setTokenRequired(isTokenRequired(entity));
        view.setDefaultSubscriptionName(entity.getDefaultSubscriptionName());
        view.setWebserviceEnabled(entity.getWebserviceEnabled() != null && entity.getWebserviceEnabled().intValue() == 1);
        view.setWebserviceConfig(fromWebServiceConfigMap(entity.getWebserviceConfigJson(), "data-service", entity.getServiceCode(), view.getWebserviceEnabled()));
        if (includeChildren) {
            view.setRequestParams(dataServiceParamSupport.loadRequestParams(entity.getId()));
            view.setResponseParams(dataServiceParamSupport.loadResponseParams(entity.getId()));
            view.setPublishParams(dataServiceParamSupport.loadPublishParams(entity.getId()));
        }
        return view;
    }

    private DataServiceSubscriptionView toSubscriptionView(DataServiceSubscriptionEntity entity, String token) {
        DataServiceSubscriptionView view = new DataServiceSubscriptionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setServiceId(entity.getServiceId());
        view.setSubscriptionName(entity.getSubscriptionName());
        view.setToken(token);
        view.setTokenMasked(token == null ? "仅创建时展示" : dataServiceTokenSupport.maskToken(token));
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        view.setCreatedBy(entity.getCreatedBy());
        view.setLastUsedAt(entity.getLastUsedAt());
        return view;
    }

    private void validateSaveRequest(DataServiceSaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Request is required");
        }
        dataServiceInvocationSupport.normalizeRequiredText(request.getServiceCode(), "Service code is required");
        dataServiceInvocationSupport.normalizeRequiredText(request.getServiceName(), "Service name is required");
        dataServiceInvocationSupport.validateSimpleIdentifier(request.getServiceCode(), "Service code must contain only letters, numbers and underscores");
        DataServiceType serviceType = request.getServiceType() == null ? DataServiceType.MODEL_PUBLISH : request.getServiceType();
        if (serviceType == DataServiceType.SERVICE_PROXY) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Service proxy is not supported in v1");
        }
        DataServiceResponseType responseType = request.getResponseType() == null ? DataServiceResponseType.JSON : request.getResponseType();
        if (responseType != DataServiceResponseType.JSON) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only JSON response is supported in v1");
        }
        if (request.getDatasourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource is required");
        }
    }

    private void validateExecutable(DataServiceDefinitionView view) {
        if (view.getServiceType() == DataServiceType.SERVICE_PROXY) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Service proxy is not supported in v1");
        }
        if (view.getResponseType() != DataServiceResponseType.JSON) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only JSON response is supported in v1");
        }
        if (view.getDatasourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource is required");
        }
        if (view.getRequestParams() == null || view.getRequestParams().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Request parameters are not configured");
        }
        if (view.getResponseParams() == null || view.getResponseParams().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Response parameters are not configured");
        }
        if (view.getPublishParams() == null || view.getPublishParams().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Publish parameters are not configured");
        }
    }

    private void ensureUniqueServiceCode(Long projectId, String serviceCode, Long selfId) {
        List<DataServiceDefinitionEntity> duplicates = definitionMapper.selectList(new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                .eq(DataServiceDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(DataServiceDefinitionEntity::getProjectId, projectId)
                .eq(DataServiceDefinitionEntity::getServiceCode, serviceCode.trim()));
        for (DataServiceDefinitionEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Service code already exists in current project");
        }
    }

    private DataServiceDefinitionEntity requireAccessibleEntity(Long id) {
        DataServiceDefinitionEntity entity = definitionMapper.selectById(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data service not found: " + id);
        }
        if (!securityService.currentTenantId().equals(entity.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data service not found: " + id);
        }
        projectResourceAccessService.assertReadable("DATA_SERVICE", entity.getProjectId(), entity.getId(), "Data service not found: " + id);
        return entity;
    }

    private DataServiceDefinitionEntity requireWritableEntity(Long id) {
        DataServiceDefinitionEntity entity = requireAccessibleEntity(id);
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private DataServiceDefinitionEntity requireOpenWebServiceEntity(String serviceCode, String serviceKey) {
        DataServiceDefinitionEntity entity = definitionMapper.selectOne(new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                .eq(DataServiceDefinitionEntity::getServiceCode, dataServiceInvocationSupport.normalizeRequiredText(serviceCode, "Service code is required"))
                .eq(DataServiceDefinitionEntity::getServiceKey, dataServiceInvocationSupport.normalizeRequiredText(serviceKey, "Service key is required"))
                .last("limit 1"));
        if (entity == null || !DataServiceStatus.ONLINE.name().equalsIgnoreCase(entity.getStatus())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data service is not available");
        }
        if (entity.getWebserviceEnabled() == null || entity.getWebserviceEnabled().intValue() != 1) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data service WebService endpoint is not available");
        }
        return entity;
    }

    private void ensureWebServiceEnabled(DataServiceDefinitionView view) {
        if (view == null || !Boolean.TRUE.equals(view.getWebserviceEnabled())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "WebService is not enabled");
        }
    }

    private void validateSoapOperation(WebServiceConfig config, WebServiceSupport.ParsedSoapRequest parsed) {
        if (config == null || parsed == null || !dataServiceInvocationSupport.hasText(parsed.getOperationName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SOAP operation is required");
        }
        if (!config.getRequestRootName().equals(parsed.getOperationName())
                && !config.getOperationName().equals(parsed.getOperationName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SOAP operation does not match service config");
        }
    }

    private String buildWebServiceEndpointPath(String serviceCode, String serviceKey) {
        return WS_OPEN_PATH_PREFIX + "/" + serviceCode + "/" + serviceKey;
    }

    private DataServiceSubscriptionEntity requireSubscription(Long serviceId, Long subscriptionId) {
        requireWritableEntity(serviceId);
        DataServiceSubscriptionEntity entity = subscriptionMapper.selectById(subscriptionId);
        if (entity == null || entity.getServiceId() == null || entity.getServiceId().longValue() != serviceId.longValue()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Subscription not found: " + subscriptionId);
        }
        return entity;
    }

    private DataServiceSubscriptionEntity resolveInvocationSubscription(DataServiceDefinitionEntity service, String token) {
        if (dataServiceInvocationSupport.hasText(token)) {
            return validateSubscriptionToken(service.getId(), token);
        }
        if (isTokenRequired(service)) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Data service token is required");
        }
        return null;
    }

    private DataServiceSubscriptionEntity validateSubscriptionToken(Long serviceId, String token) {
        if (!dataServiceInvocationSupport.hasText(token)) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Data service token is required");
        }
        DataServiceSubscriptionEntity entity = subscriptionMapper.selectOne(new LambdaQueryWrapper<DataServiceSubscriptionEntity>()
                .eq(DataServiceSubscriptionEntity::getServiceId, serviceId)
                .eq(DataServiceSubscriptionEntity::getTokenHash, dataServiceTokenSupport.hashToken(token.trim()))
                .eq(DataServiceSubscriptionEntity::getEnabled, 1)
                .last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Invalid data service token");
        }
        entity.setLastUsedAt(LocalDateTime.now());
        subscriptionMapper.updateById(entity);
        return entity;
    }

    private boolean isTokenRequired(DataServiceDefinitionEntity service) {
        return service == null || service.getTokenRequired() == null || service.getTokenRequired().intValue() != 0;
    }

    private String defaultSubscriptionNameForLog(DataServiceDefinitionEntity service) {
        if (isTokenRequired(service)) {
            return null;
        }
        String configuredName = normalizeDefaultSubscriptionName(service.getDefaultSubscriptionName());
        return dataServiceInvocationSupport.hasText(configuredName) ? configuredName : DEFAULT_NO_TOKEN_SUBSCRIPTION_NAME;
    }

    private String normalizeDefaultSubscriptionName(String value) {
        String normalized = dataServiceInvocationSupport.normalizeText(value);
        if (!dataServiceInvocationSupport.hasText(normalized)) {
            return null;
        }
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private WebServiceConfig normalizedWebServiceConfig(DataServiceDefinitionView view) {
        return webServiceSupport.normalizeConfig(view == null ? null : view.getWebserviceConfig(),
                "data-service",
                view == null ? null : view.getServiceCode());
    }

    private Map<String, Object> toWebServiceConfigMap(WebServiceConfig config,
                                                      String domain,
                                                      String serviceCode,
                                                      boolean enabled) {
        WebServiceConfig normalized = webServiceSupport.normalizeConfig(config, domain, serviceCode);
        normalized.setEnabled(Boolean.valueOf(enabled));
        return objectMapper.convertValue(normalized, new TypeReference<Map<String, Object>>() {
        });
    }

    private WebServiceConfig fromWebServiceConfigMap(Map<String, Object> config,
                                                    String domain,
                                                    String serviceCode,
                                                    Boolean enabled) {
        WebServiceConfig parsed = config == null
                ? new WebServiceConfig()
                : objectMapper.convertValue(config, WebServiceConfig.class);
        parsed.setEnabled(Boolean.TRUE.equals(enabled));
        return webServiceSupport.normalizeConfig(parsed, domain, serviceCode);
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

    private DataServiceStatus resolveSavedStatus(DataServiceDefinitionEntity entity) {
        if (entity.getId() == null) {
            return DataServiceStatus.DRAFT;
        }
        DataServiceStatus current = dataServiceInvocationSupport.enumValue(DataServiceStatus.class, entity.getStatus(), DataServiceStatus.DRAFT);
        return current == DataServiceStatus.ONLINE ? DataServiceStatus.ONLINE : DataServiceStatus.DRAFT;
    }

    private void evictServiceCache(Long serviceId) {
        responseCacheService.evictService(serviceId);
    }

    private static class DataServiceExecutionResult {
        private final Map<String, Object> data;
        private final boolean cacheHit;
        private final long rowCount;

        private DataServiceExecutionResult(Map<String, Object> data, boolean cacheHit, long rowCount) {
            this.data = data;
            this.cacheHit = cacheHit;
            this.rowCount = rowCount;
        }
    }

}
