package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataServiceParamPosition;
import com.jdragon.studio.dto.enums.DataServiceQueryOperator;
import com.jdragon.studio.dto.enums.DataServiceRequestMethod;
import com.jdragon.studio.dto.enums.DataServiceResponseType;
import com.jdragon.studio.dto.enums.DataServiceSourceType;
import com.jdragon.studio.dto.enums.DataServiceStatus;
import com.jdragon.studio.dto.enums.DataServiceType;
import com.jdragon.studio.dto.enums.DataServiceValueType;
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
import com.jdragon.studio.dto.model.request.DataServiceDebugRequest;
import com.jdragon.studio.dto.model.request.DataServiceResolveFieldsRequest;
import com.jdragon.studio.dto.model.request.DataServiceSaveRequest;
import com.jdragon.studio.dto.model.request.DataServiceSubscriptionCreateRequest;
import com.jdragon.studio.infra.entity.DataServiceAccessCounterEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DataServicePublishParamEntity;
import com.jdragon.studio.infra.entity.DataServiceRequestParamEntity;
import com.jdragon.studio.infra.entity.DataServiceResponseParamEntity;
import com.jdragon.studio.infra.entity.DataServiceSubscriptionEntity;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataServicePublishParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceRequestParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceResponseParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceSubscriptionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class DataServiceService {

    private static final Logger log = LoggerFactory.getLogger(DataServiceService.class);

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 1000;
    private static final long CACHE_TTL_MILLIS = 60000L;
    private static final String OPEN_PATH_PREFIX = "/openapi/data-services";
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern HTTP_HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+");
    private static final Pattern TABLE_REFERENCE = Pattern.compile("[A-Za-z0-9_.$`\"-]+");

    private final DataServiceDefinitionMapper definitionMapper;
    private final DataServiceRequestParamMapper requestParamMapper;
    private final DataServiceResponseParamMapper responseParamMapper;
    private final DataServicePublishParamMapper publishParamMapper;
    private final DataServiceSubscriptionMapper subscriptionMapper;
    private final DataServiceAccessLogMapper accessLogMapper;
    private final DataServiceAccessCounterMapper accessCounterMapper;
    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final DataDevelopmentSqlExecutor sqlExecutor;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final DataServiceResponseCacheService responseCacheService;
    private final SecureRandom secureRandom = new SecureRandom();

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
        this.requestParamMapper = requestParamMapper;
        this.responseParamMapper = responseParamMapper;
        this.publishParamMapper = publishParamMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.accessLogMapper = accessLogMapper;
        this.accessCounterMapper = accessCounterMapper;
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.sqlExecutor = sqlExecutor;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.responseCacheService = responseCacheService;
    }

    public PageView<DataServiceDefinitionView> list(Integer pageNo,
                                                    Integer pageSize,
                                                    String keyword,
                                                    String status,
                                                    String serviceType) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return PageView.of(safePageNo, safePageSize, 0L, new ArrayList<DataServiceDefinitionView>());
        }
        String normalizedKeyword = normalizeText(keyword);
        String normalizedStatus = normalizeText(status);
        String normalizedServiceType = normalizeText(serviceType);
        Page<DataServiceDefinitionEntity> page = new Page<DataServiceDefinitionEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<DataServiceDefinitionEntity> queryWrapper = new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                .eq(DataServiceDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(DataServiceDefinitionEntity::getProjectId, currentProjectId)
                .and(hasText(normalizedKeyword), wrapper -> wrapper.like(DataServiceDefinitionEntity::getServiceName, normalizedKeyword)
                        .or()
                        .like(DataServiceDefinitionEntity::getServiceCode, normalizedKeyword)
                        .or()
                        .like(DataServiceDefinitionEntity::getDatasourceNameSnapshot, normalizedKeyword)
                        .or()
                        .like(DataServiceDefinitionEntity::getModelNameSnapshot, normalizedKeyword))
                .eq(hasText(normalizedStatus), DataServiceDefinitionEntity::getStatus,
                        normalizedStatus == null ? null : normalizedStatus.toUpperCase(Locale.ROOT))
                .eq(hasText(normalizedServiceType), DataServiceDefinitionEntity::getServiceType,
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
            physicalLocator = normalizeRequiredText(model.getPhysicalLocator(), "Model physical locator is empty");
            validateTableReference(physicalLocator);
        } else {
            normalizedSql = normalizeSelectSql(request.getCustomSql());
        }

        entity.setTenantId(securityService.currentTenantId());
        entity.setProjectId(currentProjectId);
        entity.setCreatedBy(entity.getId() == null ? securityService.currentUserId() : entity.getCreatedBy());
        entity.setServiceCode(normalizeRequiredText(request.getServiceCode(), "Service code is required"));
        entity.setServiceName(normalizeRequiredText(request.getServiceName(), "Service name is required"));
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
        entity.setServiceKey(hasText(entity.getServiceKey()) ? entity.getServiceKey() : generateServiceKey());
        entity.setEndpointPath(buildEndpointPath(entity.getServiceCode(), entity.getServiceKey()));
        entity.setCacheEnabled(Boolean.TRUE.equals(request.getCacheEnabled()) ? Integer.valueOf(1) : Integer.valueOf(0));
        if (entity.getId() == null) {
            definitionMapper.insert(entity);
        } else {
            definitionMapper.updateById(entity);
            deleteChildren(entity.getId());
            evictServiceCache(entity.getId());
        }

        DataServiceResolveFieldsView resolvedFields = resolveFieldsForDefinition(sourceType, datasource, model, normalizedSql);
        List<DataServiceRequestParamView> requestParams = normalizeRequestParams(request.getRequestParams());
        List<DataServiceResponseParamView> responseParams = normalizeResponseParams(request.getResponseParams(), resolvedFields.getResponseParams());
        List<DataServicePublishParamView> publishParams = normalizePublishParams(request.getPublishParams(), requestParams, requestMethod);
        saveChildren(entity.getId(), requestParams, responseParams, publishParams);
        return get(entity.getId());
    }

    @Transactional
    public void delete(Long id) {
        DataServiceDefinitionEntity entity = requireWritableEntity(id);
        deleteChildren(entity.getId());
        definitionMapper.deleteById(id);
        evictServiceCache(id);
    }

    @Transactional
    public DataServiceDefinitionView publish(Long id) {
        DataServiceDefinitionEntity entity = requireWritableEntity(id);
        DataServiceDefinitionView view = toView(entity, true);
        validateExecutable(view);
        if (view.getServiceKey() == null || view.getServiceKey().trim().isEmpty()) {
            entity.setServiceKey(generateServiceKey());
            entity.setEndpointPath(buildEndpointPath(entity.getServiceCode(), entity.getServiceKey()));
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
            customSql = normalizeSelectSql(request.getCustomSql());
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
                    .eq(DataServiceDefinitionEntity::getServiceCode, normalizeRequiredText(serviceCode, "Service code is required"))
                    .eq(DataServiceDefinitionEntity::getServiceKey, normalizeRequiredText(serviceKey, "Service key is required"))
                    .last("limit 1"));
            if (entity == null || !DataServiceStatus.ONLINE.name().equalsIgnoreCase(entity.getStatus())) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Data service is not available");
            }
            cacheEnabled = Integer.valueOf(1).equals(entity.getCacheEnabled());
            subscription = validateSubscriptionToken(entity.getId(), token);
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
            recordAccessLog(entity, subscription, requestMethod, occurredAt, startedAt, success, httpStatus,
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
        if (request == null || !hasText(request.getSubscriptionName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Subscription name is required");
        }
        String subscriptionName = request.getSubscriptionName().trim();
        List<DataServiceSubscriptionEntity> duplicates = subscriptionMapper.selectList(new LambdaQueryWrapper<DataServiceSubscriptionEntity>()
                .eq(DataServiceSubscriptionEntity::getServiceId, service.getId())
                .eq(DataServiceSubscriptionEntity::getSubscriptionName, subscriptionName)
                .orderByDesc(DataServiceSubscriptionEntity::getId));
        String token = generateSubscriptionToken();
        if (duplicates != null && !duplicates.isEmpty()) {
            DataServiceSubscriptionEntity entity = duplicates.get(0);
            entity.setTokenHash(hashToken(token));
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
        entity.setTokenHash(hashToken(token));
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
        InvocationPlan plan = buildInvocationPlan(service, safeHeaders, safeQuery, safeBody);
        String cacheKey = null;
        if (allowCache && Boolean.TRUE.equals(service.getCacheEnabled())) {
            cacheKey = buildCacheKey(service.getId(), plan);
            DataServiceResponseCacheService.CacheLookup cached = responseCacheService.get(service.getId(), cacheKey);
            if (cached != null) {
                return new DataServiceExecutionResult(copyResponseData(cached.getData()), true, cached.getRowCount());
            }
        }
        DataSourceDefinition datasource = dataSourceService.getInternal(service.getDatasourceId());
        SqlExecutionResultView countResult = sqlExecutor.executePreparedQuery(datasource, plan.countSql, plan.countParameters, 1);
        long total = extractTotal(countResult);
        SqlExecutionResultView dataResult = sqlExecutor.executePreparedQuery(datasource, plan.dataSql, plan.dataParameters, plan.pageSize);
        Map<String, Object> data = buildInvokeData(plan.pageNum, plan.pageSize, total, dataResult.getRows());
        long rowCount = dataResult.getRows() == null ? 0L : dataResult.getRows().size();
        if (cacheKey != null) {
            responseCacheService.put(service.getId(), cacheKey, copyResponseData(data), rowCount, CACHE_TTL_MILLIS);
        }
        return new DataServiceExecutionResult(data, false, rowCount);
    }

    private InvocationPlan buildInvocationPlan(DataServiceDefinitionView service,
                                               Map<String, Object> headers,
                                               Map<String, Object> query,
                                               Map<String, Object> body) {
        int pageNum = DEFAULT_PAGE_NO;
        int pageSize = DEFAULT_PAGE_SIZE;
        List<Object> conditionParameters = new ArrayList<Object>();
        List<String> conditions = new ArrayList<String>();
        Map<String, DataServiceRequestParamView> requestParamMap = new LinkedHashMap<String, DataServiceRequestParamView>();
        for (DataServiceRequestParamView requestParam : service.getRequestParams()) {
            if (requestParam.getParamName() != null) {
                requestParamMap.put(requestParam.getParamName(), requestParam);
            }
        }
        for (DataServicePublishParamView publishParam : service.getPublishParams()) {
            if (publishParam == null || !hasText(publishParam.getFrontendParamName())) {
                continue;
            }
            DataServiceRequestParamView requestParam = requestParamMap.get(publishParam.getBackendParamName());
            Object rawValue = resolveIncomingValue(publishParam, headers, query, body);
            if (isPageParam(publishParam.getBackendParamName())) {
                if ("pageNum".equalsIgnoreCase(publishParam.getBackendParamName())) {
                    pageNum = normalizePageNo(toInteger(rawValue, DEFAULT_PAGE_NO));
                } else {
                    pageSize = normalizePageSize(toInteger(rawValue, DEFAULT_PAGE_SIZE));
                }
                continue;
            }
            if (isBlankValue(rawValue)) {
                if (Boolean.TRUE.equals(publishParam.getRequired())) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST, "Parameter is required: " + publishParam.getFrontendParamName());
                }
                continue;
            }
            if (requestParam == null || !hasText(requestParam.getFieldName())) {
                continue;
            }
            validateSimpleIdentifier(requestParam.getFieldName(), "Request field is invalid: " + requestParam.getFieldName());
            appendCondition(conditions, conditionParameters, requestParam, rawValue);
        }
        String selectSql = buildSelectSql(service);
        String whereSql = conditions.isEmpty() ? "" : " where " + join(conditions, " and ");
        int offset = (pageNum - 1) * pageSize;
        List<Object> dataParameters = new ArrayList<Object>(conditionParameters);
        dataParameters.add(Integer.valueOf(pageSize));
        dataParameters.add(Integer.valueOf(offset));
        InvocationPlan plan = new InvocationPlan();
        plan.pageNum = pageNum;
        plan.pageSize = pageSize;
        plan.dataSql = selectSql + whereSql + " limit ? offset ?";
        plan.countSql = "select count(*) as total_count from (" + selectSql + whereSql + ") ds_count";
        plan.dataParameters = dataParameters;
        plan.countParameters = new ArrayList<Object>(conditionParameters);
        return plan;
    }

    private String buildSelectSql(DataServiceDefinitionView service) {
        List<String> selectItems = new ArrayList<String>();
        for (DataServiceResponseParamView responseParam : service.getResponseParams()) {
            if (!Boolean.TRUE.equals(responseParam.getEnabled())) {
                continue;
            }
            String fieldName = normalizeRequiredText(responseParam.getFieldName(), "Response field is required");
            String paramName = hasText(responseParam.getParamName()) ? responseParam.getParamName().trim() : fieldName;
            validateSimpleIdentifier(fieldName, "Response field is invalid: " + fieldName);
            validateSimpleIdentifier(paramName, "Response param name is invalid: " + paramName);
            if (fieldName.equals(paramName)) {
                selectItems.add(fieldName);
            } else {
                selectItems.add(fieldName + " as " + paramName);
            }
        }
        if (selectItems.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "At least one response field must be enabled");
        }
        String sourceSql;
        if (service.getSourceType() == DataServiceSourceType.TABLE) {
            String table = normalizeRequiredText(service.getModelPhysicalLocator(), "Model physical locator is empty");
            validateTableReference(table);
            sourceSql = table;
        } else {
            sourceSql = "(" + normalizeSelectSql(service.getCustomSql()) + ") ds";
        }
        return "select " + join(selectItems, ", ") + " from " + sourceSql;
    }

    private void appendCondition(List<String> conditions,
                                 List<Object> parameters,
                                 DataServiceRequestParamView requestParam,
                                 Object rawValue) {
        DataServiceQueryOperator operator = requestParam.getQueryOperator() == null
                ? DataServiceQueryOperator.EQ
                : requestParam.getQueryOperator();
        String fieldName = requestParam.getFieldName();
        if (operator == DataServiceQueryOperator.LIKE) {
            conditions.add(fieldName + " like ?");
            parameters.add("%" + String.valueOf(rawValue) + "%");
        } else if (operator == DataServiceQueryOperator.NE) {
            conditions.add(fieldName + " <> ?");
            parameters.add(convertValue(rawValue, requestParam.getValueType()));
        } else if (operator == DataServiceQueryOperator.GT) {
            conditions.add(fieldName + " > ?");
            parameters.add(convertValue(rawValue, requestParam.getValueType()));
        } else if (operator == DataServiceQueryOperator.GE) {
            conditions.add(fieldName + " >= ?");
            parameters.add(convertValue(rawValue, requestParam.getValueType()));
        } else if (operator == DataServiceQueryOperator.LT) {
            conditions.add(fieldName + " < ?");
            parameters.add(convertValue(rawValue, requestParam.getValueType()));
        } else if (operator == DataServiceQueryOperator.LE) {
            conditions.add(fieldName + " <= ?");
            parameters.add(convertValue(rawValue, requestParam.getValueType()));
        } else if (operator == DataServiceQueryOperator.CONTAINS || operator == DataServiceQueryOperator.NOT_CONTAINS) {
            List<Object> values = parseListValues(rawValue, requestParam.getValueType());
            if (values.isEmpty()) {
                return;
            }
            conditions.add(fieldName + (operator == DataServiceQueryOperator.NOT_CONTAINS ? " not in (" : " in (") + placeholders(values.size()) + ")");
            parameters.addAll(values);
        } else {
            if (requestParam.getValueType() == DataServiceValueType.LIST) {
                List<Object> values = parseListValues(rawValue, requestParam.getValueType());
                if (values.isEmpty()) {
                    return;
                }
                conditions.add(fieldName + " in (" + placeholders(values.size()) + ")");
                parameters.addAll(values);
            } else {
                conditions.add(fieldName + " = ?");
                parameters.add(convertValue(rawValue, requestParam.getValueType()));
            }
        }
    }

    private Object resolveIncomingValue(DataServicePublishParamView publishParam,
                                        Map<String, Object> headers,
                                        Map<String, Object> query,
                                        Map<String, Object> body) {
        DataServiceParamPosition position = publishParam.getPosition() == null ? DataServiceParamPosition.QUERY : publishParam.getPosition();
        Map<String, Object> source = position == DataServiceParamPosition.HEADER ? headers
                : position == DataServiceParamPosition.BODY ? body : query;
        Object value = lookupIgnoreCase(source, publishParam.getFrontendParamName());
        if (isBlankValue(value) && hasText(publishParam.getDefaultValue())) {
            return publishParam.getDefaultValue().trim();
        }
        return value;
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

    private DataServiceResolveFieldsView resolveFieldsForDefinition(DataServiceSourceType sourceType,
                                                                    DataSourceDefinition datasource,
                                                                    DataModelDefinition model,
                                                                    String customSql) {
        DataServiceResolveFieldsView result = new DataServiceResolveFieldsView();
        List<DataServiceFieldView> fields = sourceType == DataServiceSourceType.TABLE
                ? resolveModelFields(datasource, model)
                : resolveSqlFields(datasource, customSql);
        result.setFields(fields);
        result.setRequestParams(defaultRequestParams());
        result.setResponseParams(defaultResponseParams(fields));
        return result;
    }

    private List<DataServiceFieldView> resolveModelFields(DataSourceDefinition datasource, DataModelDefinition model) {
        List<DataServiceFieldView> fields = fieldsFromModelMetadata(model);
        if (!fields.isEmpty()) {
            return fields;
        }
        String physicalLocator = normalizeRequiredText(model.getPhysicalLocator(), "Model physical locator is empty");
        validateTableReference(physicalLocator);
        SqlExecutionResultView result = sqlExecutor.executePreparedQuery(datasource,
                "select * from " + physicalLocator + " where 1 = 0",
                Collections.<Object>emptyList(),
                1);
        return fieldsFromColumns(result.getColumns());
    }

    private List<DataServiceFieldView> resolveSqlFields(DataSourceDefinition datasource, String customSql) {
        SqlExecutionResultView result = sqlExecutor.executePreparedQuery(datasource,
                "select * from (" + normalizeSelectSql(customSql) + ") ds where 1 = 0",
                Collections.<Object>emptyList(),
                1);
        return fieldsFromColumns(result.getColumns());
    }

    @SuppressWarnings("unchecked")
    private List<DataServiceFieldView> fieldsFromModelMetadata(DataModelDefinition model) {
        List<DataServiceFieldView> result = new ArrayList<DataServiceFieldView>();
        if (model == null || model.getTechnicalMetadata() == null) {
            return result;
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        if (!(columns instanceof List)) {
            return result;
        }
        for (Object column : (List<?>) columns) {
            if (!(column instanceof Map)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) column;
            String name = asString(firstPresent(item, "name", "columnName", "fieldName"));
            if (!hasText(name)) {
                continue;
            }
            DataServiceFieldView field = new DataServiceFieldView();
            field.setFieldName(name.trim());
            field.setFieldType(asString(firstPresent(item, "type", "dataType", "columnType")));
            field.setDescription(asString(firstPresent(item, "comment", "description", "remark")));
            field.setExampleValue(defaultExampleValue(resolveValueType(field.getFieldType())));
            result.add(field);
        }
        return result;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private List<DataServiceFieldView> fieldsFromColumns(List<String> columns) {
        List<DataServiceFieldView> result = new ArrayList<DataServiceFieldView>();
        if (columns == null) {
            return result;
        }
        for (String column : columns) {
            if (!hasText(column)) {
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

    private List<DataServiceRequestParamView> defaultRequestParams() {
        List<DataServiceRequestParamView> result = new ArrayList<DataServiceRequestParamView>();
        result.add(fixedPageParam("pageNum", "页码", 1));
        result.add(fixedPageParam("pageSize", "每页条数", 2));
        return result;
    }

    private DataServiceRequestParamView fixedPageParam(String name, String description, int sortOrder) {
        DataServiceRequestParamView view = new DataServiceRequestParamView();
        view.setSortOrder(Integer.valueOf(sortOrder));
        view.setParamName(name);
        view.setFieldName(name);
        view.setValueType(DataServiceValueType.INT);
        view.setQueryOperator(DataServiceQueryOperator.EQ);
        view.setRequired(Boolean.FALSE);
        view.setDescription(description);
        view.setFixedParam(Boolean.TRUE);
        return view;
    }

    private List<DataServiceResponseParamView> defaultResponseParams(List<DataServiceFieldView> fields) {
        List<DataServiceResponseParamView> result = new ArrayList<DataServiceResponseParamView>();
        int order = 1;
        for (DataServiceFieldView field : fields) {
            DataServiceResponseParamView responseParam = new DataServiceResponseParamView();
            responseParam.setSortOrder(Integer.valueOf(order++));
            responseParam.setEnabled(Boolean.TRUE);
            responseParam.setParamName(field.getFieldName());
            responseParam.setFieldName(field.getFieldName());
            responseParam.setExampleValue(field.getExampleValue());
            responseParam.setDescription(field.getDescription());
            result.add(responseParam);
        }
        return result;
    }

    private List<DataServiceRequestParamView> normalizeRequestParams(List<DataServiceRequestParamView> input) {
        List<DataServiceRequestParamView> result = new ArrayList<DataServiceRequestParamView>();
        result.add(fixedPageParam("pageNum", "页码", 1));
        result.add(fixedPageParam("pageSize", "每页条数", 2));
        int order = 3;
        if (input != null) {
            for (DataServiceRequestParamView item : input) {
                if (item == null || isPageParam(item.getParamName())) {
                    continue;
                }
                String paramName = normalizeRequiredText(item.getParamName(), "Request parameter name is required");
                DataServiceRequestParamView view = new DataServiceRequestParamView();
                view.setSortOrder(item.getSortOrder() == null ? Integer.valueOf(order) : item.getSortOrder());
                view.setParamName(paramName);
                view.setFieldName(hasText(item.getFieldName()) ? item.getFieldName().trim() : paramName);
                view.setValueType(item.getValueType() == null ? DataServiceValueType.STRING : item.getValueType());
                view.setQueryOperator(item.getQueryOperator() == null ? DataServiceQueryOperator.EQ : item.getQueryOperator());
                view.setRequired(Boolean.TRUE.equals(item.getRequired()));
                view.setDescription(normalizeNullableText(item.getDescription()));
                view.setFixedParam(Boolean.FALSE);
                validateSimpleIdentifier(view.getParamName(), "Request parameter name is invalid: " + view.getParamName());
                validateSimpleIdentifier(view.getFieldName(), "Request field is invalid: " + view.getFieldName());
                result.add(view);
                order++;
            }
        }
        return result;
    }

    private List<DataServiceResponseParamView> normalizeResponseParams(List<DataServiceResponseParamView> input,
                                                                       List<DataServiceResponseParamView> defaults) {
        List<DataServiceResponseParamView> source = input == null || input.isEmpty() ? defaults : input;
        List<DataServiceResponseParamView> result = new ArrayList<DataServiceResponseParamView>();
        int order = 1;
        for (DataServiceResponseParamView item : source) {
            if (item == null) {
                continue;
            }
            String fieldName = normalizeRequiredText(item.getFieldName(), "Response field is required");
            String paramName = hasText(item.getParamName()) ? item.getParamName().trim() : fieldName;
            validateSimpleIdentifier(fieldName, "Response field is invalid: " + fieldName);
            validateSimpleIdentifier(paramName, "Response param name is invalid: " + paramName);
            DataServiceResponseParamView view = new DataServiceResponseParamView();
            view.setSortOrder(item.getSortOrder() == null ? Integer.valueOf(order) : item.getSortOrder());
            view.setEnabled(!Boolean.FALSE.equals(item.getEnabled()));
            view.setParamName(paramName);
            view.setFieldName(fieldName);
            view.setExampleValue(normalizeNullableText(item.getExampleValue()));
            view.setDescription(normalizeNullableText(item.getDescription()));
            result.add(view);
            order++;
        }
        return result;
    }

    private List<DataServicePublishParamView> normalizePublishParams(List<DataServicePublishParamView> input,
                                                                     List<DataServiceRequestParamView> requestParams,
                                                                     DataServiceRequestMethod requestMethod) {
        List<DataServicePublishParamView> result = new ArrayList<DataServicePublishParamView>();
        Map<String, DataServicePublishParamView> existing = new LinkedHashMap<String, DataServicePublishParamView>();
        if (input != null) {
            for (DataServicePublishParamView item : input) {
                if (item != null && hasText(item.getBackendParamName())) {
                    existing.put(item.getBackendParamName(), item);
                }
            }
        }
        int order = 1;
        for (DataServiceRequestParamView requestParam : requestParams) {
            DataServicePublishParamView source = existing.get(requestParam.getParamName());
            DataServicePublishParamView view = new DataServicePublishParamView();
            view.setSortOrder(source == null || source.getSortOrder() == null ? Integer.valueOf(order) : source.getSortOrder());
            view.setFrontendParamName(source != null && hasText(source.getFrontendParamName())
                    ? source.getFrontendParamName().trim()
                    : requestParam.getParamName());
            view.setBackendParamName(requestParam.getParamName());
            view.setPosition(source == null || source.getPosition() == null
                    ? (requestMethod == DataServiceRequestMethod.POST ? DataServiceParamPosition.BODY : DataServiceParamPosition.QUERY)
                    : source.getPosition());
            view.setValueType(requestParam.getValueType());
            view.setExampleValue(source != null ? normalizeNullableText(source.getExampleValue()) : defaultExampleValue(requestParam.getValueType()));
            view.setDefaultValue(source != null ? normalizeNullableText(source.getDefaultValue()) : defaultValueFor(requestParam.getParamName()));
            view.setRequired(source == null ? requestParam.getRequired() : Boolean.TRUE.equals(source.getRequired()));
            view.setDescription(source != null && hasText(source.getDescription())
                    ? source.getDescription().trim()
                    : requestParam.getDescription());
            validateFrontendParamName(view);
            result.add(view);
            order++;
        }
        return result;
    }

    private void saveChildren(Long serviceId,
                              List<DataServiceRequestParamView> requestParams,
                              List<DataServiceResponseParamView> responseParams,
                              List<DataServicePublishParamView> publishParams) {
        for (DataServiceRequestParamView view : requestParams) {
            DataServiceRequestParamEntity entity = new DataServiceRequestParamEntity();
            entity.setServiceId(serviceId);
            entity.setSortOrder(view.getSortOrder());
            entity.setParamName(view.getParamName());
            entity.setFieldName(view.getFieldName());
            entity.setValueType(view.getValueType() == null ? null : view.getValueType().name());
            entity.setQueryOperator(view.getQueryOperator() == null ? null : view.getQueryOperator().name());
            entity.setRequired(Boolean.TRUE.equals(view.getRequired()) ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setDescription(view.getDescription());
            entity.setFixedParam(Boolean.TRUE.equals(view.getFixedParam()) ? Integer.valueOf(1) : Integer.valueOf(0));
            requestParamMapper.insert(entity);
        }
        for (DataServiceResponseParamView view : responseParams) {
            DataServiceResponseParamEntity entity = new DataServiceResponseParamEntity();
            entity.setServiceId(serviceId);
            entity.setSortOrder(view.getSortOrder());
            entity.setEnabled(Boolean.TRUE.equals(view.getEnabled()) ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setParamName(view.getParamName());
            entity.setFieldName(view.getFieldName());
            entity.setExampleValue(view.getExampleValue());
            entity.setDescription(view.getDescription());
            responseParamMapper.insert(entity);
        }
        for (DataServicePublishParamView view : publishParams) {
            DataServicePublishParamEntity entity = new DataServicePublishParamEntity();
            entity.setServiceId(serviceId);
            entity.setSortOrder(view.getSortOrder());
            entity.setFrontendParamName(view.getFrontendParamName());
            entity.setBackendParamName(view.getBackendParamName());
            entity.setPosition(view.getPosition() == null ? null : view.getPosition().name());
            entity.setValueType(view.getValueType() == null ? null : view.getValueType().name());
            entity.setExampleValue(view.getExampleValue());
            entity.setDefaultValue(view.getDefaultValue());
            entity.setRequired(Boolean.TRUE.equals(view.getRequired()) ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setDescription(view.getDescription());
            publishParamMapper.insert(entity);
        }
    }

    private void deleteChildren(Long serviceId) {
        requestParamMapper.delete(new LambdaQueryWrapper<DataServiceRequestParamEntity>()
                .eq(DataServiceRequestParamEntity::getServiceId, serviceId));
        responseParamMapper.delete(new LambdaQueryWrapper<DataServiceResponseParamEntity>()
                .eq(DataServiceResponseParamEntity::getServiceId, serviceId));
        publishParamMapper.delete(new LambdaQueryWrapper<DataServicePublishParamEntity>()
                .eq(DataServicePublishParamEntity::getServiceId, serviceId));
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
        view.setServiceType(enumValue(DataServiceType.class, entity.getServiceType(), DataServiceType.MODEL_PUBLISH));
        view.setStatus(enumValue(DataServiceStatus.class, entity.getStatus(), DataServiceStatus.DRAFT));
        view.setSourceType(enumValue(DataServiceSourceType.class, entity.getSourceType(), DataServiceSourceType.TABLE));
        view.setDatasourceId(entity.getDatasourceId());
        view.setDatasourceName(entity.getDatasourceNameSnapshot());
        view.setDatasourceTypeCode(entity.getDatasourceTypeCode());
        view.setModelId(entity.getModelId());
        view.setModelName(entity.getModelNameSnapshot());
        view.setModelPhysicalLocator(entity.getModelPhysicalLocator());
        view.setCustomSql(entity.getCustomSql());
        view.setRequestMethod(enumValue(DataServiceRequestMethod.class, entity.getRequestMethod(), DataServiceRequestMethod.GET));
        view.setResponseType(enumValue(DataServiceResponseType.class, entity.getResponseType(), DataServiceResponseType.JSON));
        view.setEndpointPath(entity.getEndpointPath());
        view.setServiceKey(entity.getServiceKey());
        view.setCacheEnabled(entity.getCacheEnabled() != null && entity.getCacheEnabled() == 1);
        if (includeChildren) {
            view.setRequestParams(loadRequestParams(entity.getId()));
            view.setResponseParams(loadResponseParams(entity.getId()));
            view.setPublishParams(loadPublishParams(entity.getId()));
        }
        return view;
    }

    private List<DataServiceRequestParamView> loadRequestParams(Long serviceId) {
        List<DataServiceRequestParamEntity> entities = requestParamMapper.selectList(new LambdaQueryWrapper<DataServiceRequestParamEntity>()
                .eq(DataServiceRequestParamEntity::getServiceId, serviceId)
                .orderByAsc(DataServiceRequestParamEntity::getSortOrder)
                .orderByAsc(DataServiceRequestParamEntity::getId));
        List<DataServiceRequestParamView> result = new ArrayList<DataServiceRequestParamView>();
        for (DataServiceRequestParamEntity entity : entities) {
            DataServiceRequestParamView view = new DataServiceRequestParamView();
            view.setId(entity.getId());
            view.setServiceId(entity.getServiceId());
            view.setSortOrder(entity.getSortOrder());
            view.setParamName(entity.getParamName());
            view.setFieldName(entity.getFieldName());
            view.setValueType(enumValue(DataServiceValueType.class, entity.getValueType(), DataServiceValueType.STRING));
            view.setQueryOperator(enumValue(DataServiceQueryOperator.class, entity.getQueryOperator(), DataServiceQueryOperator.EQ));
            view.setRequired(entity.getRequired() != null && entity.getRequired() == 1);
            view.setDescription(entity.getDescription());
            view.setFixedParam(entity.getFixedParam() != null && entity.getFixedParam() == 1);
            result.add(view);
        }
        return result;
    }

    private List<DataServiceResponseParamView> loadResponseParams(Long serviceId) {
        List<DataServiceResponseParamEntity> entities = responseParamMapper.selectList(new LambdaQueryWrapper<DataServiceResponseParamEntity>()
                .eq(DataServiceResponseParamEntity::getServiceId, serviceId)
                .orderByAsc(DataServiceResponseParamEntity::getSortOrder)
                .orderByAsc(DataServiceResponseParamEntity::getId));
        List<DataServiceResponseParamView> result = new ArrayList<DataServiceResponseParamView>();
        for (DataServiceResponseParamEntity entity : entities) {
            DataServiceResponseParamView view = new DataServiceResponseParamView();
            view.setId(entity.getId());
            view.setServiceId(entity.getServiceId());
            view.setSortOrder(entity.getSortOrder());
            view.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
            view.setParamName(entity.getParamName());
            view.setFieldName(entity.getFieldName());
            view.setExampleValue(entity.getExampleValue());
            view.setDescription(entity.getDescription());
            result.add(view);
        }
        return result;
    }

    private List<DataServicePublishParamView> loadPublishParams(Long serviceId) {
        List<DataServicePublishParamEntity> entities = publishParamMapper.selectList(new LambdaQueryWrapper<DataServicePublishParamEntity>()
                .eq(DataServicePublishParamEntity::getServiceId, serviceId)
                .orderByAsc(DataServicePublishParamEntity::getSortOrder)
                .orderByAsc(DataServicePublishParamEntity::getId));
        List<DataServicePublishParamView> result = new ArrayList<DataServicePublishParamView>();
        for (DataServicePublishParamEntity entity : entities) {
            DataServicePublishParamView view = new DataServicePublishParamView();
            view.setId(entity.getId());
            view.setServiceId(entity.getServiceId());
            view.setSortOrder(entity.getSortOrder());
            view.setFrontendParamName(entity.getFrontendParamName());
            view.setBackendParamName(entity.getBackendParamName());
            view.setPosition(enumValue(DataServiceParamPosition.class, entity.getPosition(), DataServiceParamPosition.QUERY));
            view.setValueType(enumValue(DataServiceValueType.class, entity.getValueType(), DataServiceValueType.STRING));
            view.setExampleValue(entity.getExampleValue());
            view.setDefaultValue(entity.getDefaultValue());
            view.setRequired(entity.getRequired() != null && entity.getRequired() == 1);
            view.setDescription(entity.getDescription());
            result.add(view);
        }
        return result;
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
        view.setTokenMasked(token == null ? "仅创建时展示" : maskToken(token));
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        view.setCreatedBy(entity.getCreatedBy());
        view.setLastUsedAt(entity.getLastUsedAt());
        return view;
    }

    private void validateSaveRequest(DataServiceSaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Request is required");
        }
        normalizeRequiredText(request.getServiceCode(), "Service code is required");
        normalizeRequiredText(request.getServiceName(), "Service name is required");
        validateSimpleIdentifier(request.getServiceCode(), "Service code must contain only letters, numbers and underscores");
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

    private DataServiceSubscriptionEntity requireSubscription(Long serviceId, Long subscriptionId) {
        requireWritableEntity(serviceId);
        DataServiceSubscriptionEntity entity = subscriptionMapper.selectById(subscriptionId);
        if (entity == null || entity.getServiceId() == null || entity.getServiceId().longValue() != serviceId.longValue()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Subscription not found: " + subscriptionId);
        }
        return entity;
    }

    private DataServiceSubscriptionEntity validateSubscriptionToken(Long serviceId, String token) {
        if (!hasText(token)) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Data service token is required");
        }
        DataServiceSubscriptionEntity entity = subscriptionMapper.selectOne(new LambdaQueryWrapper<DataServiceSubscriptionEntity>()
                .eq(DataServiceSubscriptionEntity::getServiceId, serviceId)
                .eq(DataServiceSubscriptionEntity::getTokenHash, hashToken(token.trim()))
                .eq(DataServiceSubscriptionEntity::getEnabled, 1)
                .last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Invalid data service token");
        }
        entity.setLastUsedAt(LocalDateTime.now());
        subscriptionMapper.updateById(entity);
        return entity;
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

    private void recordAccessLog(DataServiceDefinitionEntity service,
                                 DataServiceSubscriptionEntity subscription,
                                 String requestMethod,
                                 LocalDateTime occurredAt,
                                 long startedAt,
                                 boolean success,
                                 int httpStatus,
                                 String errorCode,
                                 String errorMessage,
                                 String clientIp,
                                 String userAgent,
                                 boolean cacheEnabled,
                                 boolean cacheHit,
                                 long rowCount) {
        try {
            DataServiceAccessLogEntity entity = new DataServiceAccessLogEntity();
            entity.setTenantId(service == null || !hasText(service.getTenantId()) ? StudioConstants.DEFAULT_TENANT_ID : service.getTenantId());
            entity.setProjectId(service == null ? null : service.getProjectId());
            entity.setServiceId(service == null ? null : service.getId());
            entity.setServiceCodeSnapshot(service == null ? null : service.getServiceCode());
            entity.setServiceNameSnapshot(service == null ? null : service.getServiceName());
            entity.setServiceStatusSnapshot(service == null ? null : service.getStatus());
            entity.setSubscriptionId(subscription == null ? null : subscription.getId());
            entity.setSubscriptionNameSnapshot(subscription == null ? null : subscription.getSubscriptionName());
            entity.setRequestMethod(hasText(requestMethod) ? requestMethod.toUpperCase(Locale.ROOT) : null);
            entity.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
            entity.setDurationMs(Long.valueOf(Math.max(0L, (System.nanoTime() - startedAt) / 1000000L)));
            entity.setSuccess(success ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setHttpStatus(Integer.valueOf(httpStatus));
            entity.setErrorCode(truncate(errorCode, 128));
            entity.setErrorMessage(truncate(errorMessage, 1000));
            entity.setClientIp(truncate(clientIp, 128));
            entity.setUserAgent(truncate(userAgent, 500));
            entity.setCacheEnabled(cacheEnabled ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setCacheHit(cacheHit ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setRowCount(Long.valueOf(Math.max(0L, rowCount)));
            accessLogMapper.insert(entity);
            recordAccessCounter(entity);
        } catch (RuntimeException ex) {
            log.warn("Failed to write data service access log", ex);
        }
    }

    private void recordAccessCounter(DataServiceAccessLogEntity logEntity) {
        if (logEntity == null || logEntity.getProjectId() == null || logEntity.getServiceId() == null) {
            return;
        }
        try {
            DataServiceAccessCounterEntity counter = new DataServiceAccessCounterEntity();
            counter.setId(IdWorker.getId());
            counter.setTenantId(logEntity.getTenantId());
            counter.setProjectId(logEntity.getProjectId());
            counter.setServiceId(logEntity.getServiceId());
            counter.setSubscriptionId(logEntity.getSubscriptionId() == null ? Long.valueOf(0L) : logEntity.getSubscriptionId());
            counter.setBucketStart(toHourBucket(logEntity.getOccurredAt()));
            counter.setSuccess(Integer.valueOf(1).equals(logEntity.getSuccess()) ? Integer.valueOf(1) : Integer.valueOf(0));
            counter.setCacheEnabled(Integer.valueOf(1).equals(logEntity.getCacheEnabled()) ? Integer.valueOf(1) : Integer.valueOf(0));
            counter.setCacheHit(Integer.valueOf(1).equals(logEntity.getCacheEnabled()) && Integer.valueOf(1).equals(logEntity.getCacheHit())
                    ? Integer.valueOf(1)
                    : Integer.valueOf(0));
            counter.setAccessCount(Long.valueOf(1L));
            counter.setRowCount(Long.valueOf(Math.max(0L, safeLong(logEntity.getRowCount()))));
            accessCounterMapper.upsert(counter);
        } catch (RuntimeException ex) {
            log.warn("Failed to update data service access counter", ex);
        }
    }

    private LocalDateTime toHourBucket(LocalDateTime time) {
        LocalDateTime safeTime = time == null ? LocalDateTime.now() : time;
        return safeTime.withMinute(0).withSecond(0).withNano(0);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private DataServiceStatus resolveSavedStatus(DataServiceDefinitionEntity entity) {
        if (entity.getId() == null) {
            return DataServiceStatus.DRAFT;
        }
        DataServiceStatus current = enumValue(DataServiceStatus.class, entity.getStatus(), DataServiceStatus.DRAFT);
        return current == DataServiceStatus.ONLINE ? DataServiceStatus.ONLINE : DataServiceStatus.DRAFT;
    }

    private String normalizeSelectSql(String sql) {
        String normalized = normalizeRequiredText(sql, "Custom SQL is required");
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (!normalized.toLowerCase(Locale.ENGLISH).startsWith("select")) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only SELECT SQL is supported");
        }
        if (containsSemicolonOutsideQuotes(normalized)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only one SELECT statement is supported");
        }
        return normalized;
    }

    private boolean containsSemicolonOutsideQuotes(String text) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (ch == ';' && !inSingleQuote && !inDoubleQuote) {
                return true;
            }
        }
        return false;
    }

    private void validateTableReference(String tableReference) {
        if (!TABLE_REFERENCE.matcher(tableReference).matches()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Table reference contains unsupported characters");
        }
    }

    private void validateSimpleIdentifier(String identifier, String message) {
        if (!hasText(identifier) || !SIMPLE_IDENTIFIER.matcher(identifier.trim()).matches()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
    }

    private void validateFrontendParamName(DataServicePublishParamView view) {
        String paramName = view == null ? null : view.getFrontendParamName();
        if (view != null && view.getPosition() == DataServiceParamPosition.HEADER) {
            if (!hasText(paramName) || !HTTP_HEADER_NAME.matcher(paramName.trim()).matches()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Header parameter name is invalid: " + paramName);
            }
            return;
        }
        validateSimpleIdentifier(paramName, "Frontend parameter name is invalid: " + paramName);
    }

    private Map<String, Object> buildInvokeData(int pageNum,
                                                int pageSize,
                                                long total,
                                                List<Map<String, Object>> rows) {
        long pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("pageNum", Integer.valueOf(pageNum));
        data.put("pageSize", Integer.valueOf(pageSize));
        data.put("pages", Long.valueOf(pages));
        Map<String, Object> table = new LinkedHashMap<String, Object>();
        table.put("bodies", rows == null ? new ArrayList<Map<String, Object>>() : rows);
        data.put("table", table);
        return data;
    }

    private Map<String, Object> copyResponseData(Map<String, Object> input) {
        return input == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(input);
    }

    private long extractTotal(SqlExecutionResultView result) {
        if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
            return 0L;
        }
        Map<String, Object> row = result.getRows().get(0);
        if (row == null || row.isEmpty()) {
            return 0L;
        }
        Object value = row.values().iterator().next();
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return 0L;
        }
    }

    private Object convertValue(Object value, DataServiceValueType valueType) {
        if (value == null) {
            return null;
        }
        DataServiceValueType type = valueType == null ? DataServiceValueType.STRING : valueType;
        if (type == DataServiceValueType.INT) {
            return Integer.valueOf(String.valueOf(value).trim());
        }
        if (type == DataServiceValueType.FLOAT) {
            return Double.valueOf(String.valueOf(value).trim());
        }
        return value;
    }

    private List<Object> parseListValues(Object rawValue, DataServiceValueType valueType) {
        List<Object> result = new ArrayList<Object>();
        if (rawValue == null) {
            return result;
        }
        if (rawValue instanceof Iterable) {
            for (Object item : (Iterable<?>) rawValue) {
                if (!isBlankValue(item)) {
                    result.add(convertValue(item, valueType == DataServiceValueType.LIST ? DataServiceValueType.STRING : valueType));
                }
            }
            return result;
        }
        if (rawValue.getClass().isArray()) {
            Object[] values = (Object[]) rawValue;
            for (Object item : values) {
                if (!isBlankValue(item)) {
                    result.add(convertValue(item, valueType == DataServiceValueType.LIST ? DataServiceValueType.STRING : valueType));
                }
            }
            return result;
        }
        String text = String.valueOf(rawValue);
        for (String item : text.split(",")) {
            if (hasText(item)) {
                result.add(item.trim());
            }
        }
        return result;
    }

    private String placeholders(int size) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < size; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append("?");
        }
        return builder.toString();
    }

    private DataServiceValueType resolveValueType(String fieldType) {
        if (fieldType == null) {
            return DataServiceValueType.STRING;
        }
        String normalized = fieldType.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("int") || normalized.contains("long")) {
            return DataServiceValueType.INT;
        }
        if (normalized.contains("decimal") || normalized.contains("double") || normalized.contains("float") || normalized.contains("number")) {
            return DataServiceValueType.FLOAT;
        }
        if (normalized.contains("timestamp") || normalized.contains("datetime")) {
            return DataServiceValueType.TIMESTAMP;
        }
        if (normalized.contains("date") || normalized.contains("time")) {
            return DataServiceValueType.TIME;
        }
        return DataServiceValueType.STRING;
    }

    private String defaultExampleValue(DataServiceValueType valueType) {
        if (valueType == DataServiceValueType.INT) {
            return "1";
        }
        if (valueType == DataServiceValueType.FLOAT) {
            return "1.0";
        }
        if (valueType == DataServiceValueType.TIME || valueType == DataServiceValueType.TIMESTAMP) {
            return "2026-04-16 12:00:00";
        }
        if (valueType == DataServiceValueType.LIST) {
            return "A,B";
        }
        return "示例";
    }

    private String defaultValueFor(String paramName) {
        if ("pageNum".equalsIgnoreCase(paramName)) {
            return "1";
        }
        if ("pageSize".equalsIgnoreCase(paramName)) {
            return "10";
        }
        return null;
    }

    private boolean isPageParam(String name) {
        return "pageNum".equalsIgnoreCase(name) || "pageSize".equalsIgnoreCase(name);
    }

    private Integer toInteger(Object value, int defaultValue) {
        if (isBlankValue(value)) {
            return Integer.valueOf(defaultValue);
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (Exception ex) {
            return Integer.valueOf(defaultValue);
        }
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

    private String buildEndpointPath(String serviceCode, String serviceKey) {
        return OPEN_PATH_PREFIX + "/" + serviceCode + "/" + serviceKey;
    }

    private String buildCacheKey(Long serviceId, InvocationPlan plan) {
        return serviceId + "|" + plan.dataSql + "|" + plan.dataParameters;
    }

    private void evictServiceCache(Long serviceId) {
        responseCacheService.evictService(serviceId);
    }

    private String generateServiceKey() {
        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateSubscriptionToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return "dsvc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Failed to hash token");
        }
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 12) {
            return "******";
        }
        return token.substring(0, 8) + "..." + token.substring(token.length() - 4);
    }

    private String normalizeRequiredText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeNullableText(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String normalizeText(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String) {
            return ((String) value).trim().isEmpty();
        }
        return false;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.trim().isEmpty() ? null : text.trim();
    }

    private String join(List<String> values, String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(delimiter);
            }
            builder.append(values.get(index));
        }
        return builder.toString();
    }

    private <T extends Enum<T>> T enumValue(Class<T> enumClass, String value, T defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private static class InvocationPlan {
        private int pageNum;
        private int pageSize;
        private String dataSql;
        private String countSql;
        private List<Object> dataParameters = new ArrayList<Object>();
        private List<Object> countParameters = new ArrayList<Object>();
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
