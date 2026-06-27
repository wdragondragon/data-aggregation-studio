package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.DataIngestionRequestFormat;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.DataIngestionStatus;
import com.jdragon.studio.dto.enums.DataIngestionTargetType;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.dto.model.DataIngestionResolveFieldsView;
import com.jdragon.studio.dto.model.DataIngestionServiceListView;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.dto.model.DataIngestionSubscriptionView;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.WebServiceConfig;
import com.jdragon.studio.dto.model.WebServiceDebugResult;
import com.jdragon.studio.dto.model.WebServicePreviewView;
import com.jdragon.studio.dto.model.request.DataIngestionDebugRequest;
import com.jdragon.studio.dto.model.request.DataIngestionResolveFieldsRequest;
import com.jdragon.studio.dto.model.request.DataIngestionServiceSaveRequest;
import com.jdragon.studio.dto.model.request.DataServiceSubscriptionCreateRequest;
import com.jdragon.studio.dto.model.request.WebServiceDebugRequest;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataIngestionSubscriptionEntity;
import com.jdragon.studio.infra.mapper.DataIngestionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataIngestionSubscriptionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DataIngestionService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_MAX_BATCH_SIZE = 500;
    private static final int MAX_BATCH_SIZE = 500;
    private static final String CATEGORY_FILE_SYSTEM = "FILE_SYSTEM";
    private static final String OPEN_PATH_PREFIX = "/openapi/data-ingestion-services";
    private static final String WS_OPEN_PATH_PREFIX = "/openapi/ws/data-ingestion-services";
    private static final String DEFAULT_NO_TOKEN_SUBSCRIPTION_NAME = "免 Token 调用";
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataIngestionServiceMapper serviceMapper;
    private final DataIngestionSubscriptionMapper subscriptionMapper;
    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService;
    private final ObjectMapper objectMapper;
    private final DataServiceTokenSupport tokenSupport = new DataServiceTokenSupport();
    private final DataIngestionAccessLogSupport accessLogSupport;
    private final DataIngestionExecutionSupport executionSupport;
    private final DataIngestionFieldSupport fieldSupport;
    private final OpenServiceInvocationLogSupport invocationLogSupport;
    private final OpenServiceInvocationLogService invocationLogService;
    private final WebServiceSupport webServiceSupport = new WebServiceSupport();

    public DataIngestionService(DataIngestionServiceMapper serviceMapper,
                                DataIngestionSubscriptionMapper subscriptionMapper,
                                DataIngestionAccessLogMapper accessLogMapper,
                                DataIngestionAccessCounterMapper accessCounterMapper,
                                DataSourceService dataSourceService,
                                DataModelService dataModelService,
                                DataDevelopmentSqlExecutor sqlExecutor,
                                StudioSecurityService securityService,
                                ProjectResourceAccessService projectResourceAccessService,
                                PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService,
                                CollectionTaskAssemblerService collectionTaskAssemblerService,
                                ObjectMapper objectMapper,
                                OpenServiceInvocationLogService invocationLogService) {
        this.serviceMapper = serviceMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.pluginRuntimeOptionSchemaService = pluginRuntimeOptionSchemaService;
        this.objectMapper = objectMapper;
        this.accessLogSupport = new DataIngestionAccessLogSupport(accessLogMapper, accessCounterMapper);
        this.executionSupport = new DataIngestionExecutionSupport(collectionTaskAssemblerService, objectMapper);
        this.fieldSupport = new DataIngestionFieldSupport(sqlExecutor);
        this.invocationLogSupport = new OpenServiceInvocationLogSupport();
        this.invocationLogService = invocationLogService;
    }

    public PageView<DataIngestionServiceListView> list(Integer pageNo,
                                                       Integer pageSize,
                                                       String keyword,
                                                       String status,
                                                       String targetType) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return PageView.of(safePageNo, safePageSize, 0L, new ArrayList<DataIngestionServiceListView>());
        }
        String normalizedKeyword = normalizeText(keyword);
        String normalizedStatus = normalizeText(status);
        String normalizedTargetType = normalizeText(targetType);
        Page<DataIngestionServiceEntity> page = new Page<DataIngestionServiceEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<DataIngestionServiceEntity> queryWrapper = new LambdaQueryWrapper<DataIngestionServiceEntity>()
                .select(DataIngestionServiceEntity::getId,
                        DataIngestionServiceEntity::getTenantId,
                        DataIngestionServiceEntity::getProjectId,
                        DataIngestionServiceEntity::getDeleted,
                        DataIngestionServiceEntity::getCreatedAt,
                        DataIngestionServiceEntity::getUpdatedAt,
                        DataIngestionServiceEntity::getCreatedBy,
                        DataIngestionServiceEntity::getServiceCode,
                        DataIngestionServiceEntity::getServiceName,
                        DataIngestionServiceEntity::getStatus,
                        DataIngestionServiceEntity::getRequestFormat,
                        DataIngestionServiceEntity::getPayloadMode,
                        DataIngestionServiceEntity::getDataNodePath,
                        DataIngestionServiceEntity::getTargetType,
                        DataIngestionServiceEntity::getDatasourceId,
                        DataIngestionServiceEntity::getDatasourceNameSnapshot,
                        DataIngestionServiceEntity::getDatasourceTypeCode,
                        DataIngestionServiceEntity::getModelId,
                        DataIngestionServiceEntity::getModelNameSnapshot,
                        DataIngestionServiceEntity::getModelPhysicalLocator,
                        DataIngestionServiceEntity::getEndpointPath,
                        DataIngestionServiceEntity::getMaxBatchSize,
                        DataIngestionServiceEntity::getTokenRequired,
                        DataIngestionServiceEntity::getDefaultSubscriptionName,
                        DataIngestionServiceEntity::getWebserviceEnabled,
                        DataIngestionServiceEntity::getFieldMappingsJson)
                .eq(DataIngestionServiceEntity::getTenantId, securityService.currentTenantId());
        List<Long> sharedIds = projectResourceAccessService.sharedResourceIdList(StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE);
        if (sharedIds.isEmpty()) {
            queryWrapper.eq(DataIngestionServiceEntity::getProjectId, currentProjectId);
        } else {
            queryWrapper.and(wrapper -> wrapper.eq(DataIngestionServiceEntity::getProjectId, currentProjectId)
                    .or()
                    .in(DataIngestionServiceEntity::getId, sharedIds));
        }
        queryWrapper
                .and(hasText(normalizedKeyword), wrapper -> wrapper.like(DataIngestionServiceEntity::getServiceName, normalizedKeyword)
                        .or()
                        .like(DataIngestionServiceEntity::getServiceCode, normalizedKeyword)
                        .or()
                        .like(DataIngestionServiceEntity::getDatasourceNameSnapshot, normalizedKeyword)
                        .or()
                        .like(DataIngestionServiceEntity::getModelNameSnapshot, normalizedKeyword))
                .eq(hasText(normalizedStatus), DataIngestionServiceEntity::getStatus,
                        normalizedStatus == null ? null : normalizedStatus.toUpperCase(Locale.ROOT))
                .eq(hasText(normalizedTargetType), DataIngestionServiceEntity::getTargetType,
                        normalizedTargetType == null ? null : normalizedTargetType.toUpperCase(Locale.ROOT))
                .orderByDesc(DataIngestionServiceEntity::getUpdatedAt)
                .orderByDesc(DataIngestionServiceEntity::getId);
        Page<DataIngestionServiceEntity> entityPage = serviceMapper.selectPage(page, queryWrapper);
        List<DataIngestionServiceListView> items = new ArrayList<DataIngestionServiceListView>();
        for (DataIngestionServiceEntity entity : entityPage.getRecords()) {
            items.add(toListView(entity));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    public DataIngestionServiceView get(Long id) {
        return toView(requireAccessibleEntity(id));
    }

    @Transactional
    public DataIngestionServiceView save(DataIngestionServiceSaveRequest request) {
        validateSaveRequest(request);
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        DataIngestionServiceEntity entity = request.getId() == null
                ? new DataIngestionServiceEntity()
                : requireWritableEntity(request.getId());
        ensureUniqueServiceCode(currentProjectId, request.getServiceCode(), entity.getId());
        ensureUniqueServiceName(currentProjectId, request.getServiceName(), entity.getId());

        DataIngestionPayloadMode payloadMode = request.getPayloadMode() == null ? DataIngestionPayloadMode.OBJECT : request.getPayloadMode();
        DataIngestionTargetType targetType = request.getTargetType() == null ? DataIngestionTargetType.DATABASE : request.getTargetType();
        int maxBatchSize = normalizeMaxBatchSize(request.getMaxBatchSize());

        DataSourceDefinition datasource = requiredDatasource(request.getDatasourceId());
        DataModelDefinition model = requiredModel(request.getModelId());
        validateTarget(datasource, model, targetType);
        List<DataIngestionFieldMapping> mappings = fieldSupport.normalizeFieldMappings(request.getFieldMappings(), model);
        DataIngestionRequestFormat requestFormat = Boolean.TRUE.equals(request.getWebserviceEnabled())
                ? DataIngestionRequestFormat.SOAP
                : deriveRequestFormat(request.getRequestFormat(), mappings);

        entity.setTenantId(securityService.currentTenantId());
        entity.setProjectId(currentProjectId);
        entity.setCreatedBy(entity.getId() == null ? securityService.currentUserId() : entity.getCreatedBy());
        entity.setServiceCode(normalizeRequiredText(request.getServiceCode(), "Service code is required"));
        entity.setServiceName(normalizeRequiredText(request.getServiceName(), "Service name is required"));
        entity.setStatus(resolveSavedStatus(entity).name());
        entity.setRequestFormat(requestFormat.name());
        entity.setPayloadMode(payloadMode.name());
        entity.setDataNodePath(normalizeText(request.getDataNodePath()));
        entity.setTargetType(targetType.name());
        entity.setDatasourceId(datasource.getId());
        entity.setDatasourceNameSnapshot(datasource.getName());
        entity.setDatasourceTypeCode(datasource.getTypeCode());
        entity.setModelId(model.getId());
        entity.setModelNameSnapshot(model.getName());
        entity.setModelPhysicalLocator(model.getPhysicalLocator());
        entity.setServiceKey(hasText(entity.getServiceKey()) ? entity.getServiceKey() : tokenSupport.generateServiceKey());
        entity.setEndpointPath(buildEndpointPath(entity.getServiceCode(), entity.getServiceKey()));
        entity.setMaxBatchSize(Integer.valueOf(maxBatchSize));
        entity.setTokenRequired(Boolean.FALSE.equals(request.getTokenRequired()) ? Integer.valueOf(0) : Integer.valueOf(1));
        entity.setDefaultSubscriptionName(normalizeDefaultSubscriptionName(request.getDefaultSubscriptionName()));
        entity.setWebserviceEnabled(Boolean.TRUE.equals(request.getWebserviceEnabled()) ? Integer.valueOf(1) : Integer.valueOf(0));
        entity.setWebserviceConfigJson(toWebServiceConfigMap(request.getWebserviceConfig(), "data-ingestion-service", entity.getServiceCode(),
                Integer.valueOf(1).equals(entity.getWebserviceEnabled())));
        entity.setWriterOptionsJson(request.getWriterOptions() == null ? new LinkedHashMap<String, Object>() : request.getWriterOptions());
        entity.setFieldMappingsJson(toMapList(mappings));
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
    public DataIngestionServiceView publish(Long id) {
        DataIngestionServiceEntity entity = requireWritableEntity(id);
        validateExecutable(toView(entity));
        if (!hasText(entity.getServiceKey())) {
            entity.setServiceKey(tokenSupport.generateServiceKey());
            entity.setEndpointPath(buildEndpointPath(entity.getServiceCode(), entity.getServiceKey()));
        }
        entity.setStatus(DataIngestionStatus.ONLINE.name());
        serviceMapper.updateById(entity);
        return get(id);
    }

    @Transactional
    public DataIngestionServiceView offline(Long id) {
        DataIngestionServiceEntity entity = requireWritableEntity(id);
        entity.setStatus(DataIngestionStatus.OFFLINE.name());
        serviceMapper.updateById(entity);
        return get(id);
    }

    public DataIngestionResolveFieldsView resolveFields(DataIngestionResolveFieldsRequest request) {
        if (request == null || request.getDatasourceId() == null || request.getModelId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource and model are required");
        }
        DataSourceDefinition datasource = requiredDatasource(request.getDatasourceId());
        DataModelDefinition model = requiredModel(request.getModelId());
        if (model.getDatasourceId() == null || model.getDatasourceId().longValue() != datasource.getId().longValue()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model does not belong to selected datasource");
        }
        DataIngestionResolveFieldsView view = new DataIngestionResolveFieldsView();
        view.setFields(fieldSupport.resolveModelFields(datasource, model));
        view.setFieldMappings(fieldSupport.defaultFieldMappings(model));
        return view;
    }

    public DataIngestionInvokeResult debug(Long id, DataIngestionDebugRequest request) {
        DataIngestionServiceView view = get(id);
        validateExecutable(view);
        return execute(view,
                request == null ? new LinkedHashMap<String, Object>() : request.getHeaders(),
                request == null ? new LinkedHashMap<String, Object>() : request.getQuery(),
                request == null ? new LinkedHashMap<String, Object>() : request.getForm(),
                request == null ? null : request.getBody(),
                newRequestId(),
                false);
    }

    public WebServicePreviewView previewWebService(Long id) {
        DataIngestionServiceView view = get(id);
        return webServiceSupport.previewForDataIngestion(view, buildWebServiceEndpointPath(view.getServiceCode(), view.getServiceKey()));
    }

    public WebServiceDebugResult debugWebService(Long id, WebServiceDebugRequest request) {
        DataIngestionServiceView view = get(id);
        ensureWebServiceEnabled(view);
        String envelope = request == null || request.getSoapEnvelope() == null || request.getSoapEnvelope().trim().isEmpty()
                ? previewWebService(id).getSampleRequest()
                : request.getSoapEnvelope();
        WebServiceSupport.ParsedSoapRequest parsed = webServiceSupport.parse(envelope);
        WebServiceConfig config = normalizedWebServiceConfig(view);
        validateSoapOperation(config, parsed);
        DataIngestionInvokeResult result = execute(view,
                webServiceSupport.mergeHeaders(request == null ? null : request.getHeaders(), parsed.getHeaders()),
                parsed.getBody(),
                new LinkedHashMap<String, Object>(),
                parsed.getBody(),
                newRequestId(),
                null,
                null,
                false);
        WebServiceDebugResult debugResult = new WebServiceDebugResult();
        debugResult.setSuccess(Boolean.TRUE);
        debugResult.setHttpStatus(Integer.valueOf(200));
        debugResult.setRequestEnvelope(envelope);
        debugResult.setResult(result);
        debugResult.setResponseEnvelope(webServiceSupport.successEnvelope(config, webServiceSupport.ingestionResultToPayload(result), parsed.getSoapVersion()));
        return debugResult;
    }

    public String webServiceWsdl(String serviceCode, String serviceKey, String endpointUrl) {
        DataIngestionServiceEntity entity = requireOpenWebServiceEntity(serviceCode, serviceKey);
        DataIngestionServiceView view = toView(entity);
        WebServiceConfig config = normalizedWebServiceConfig(view);
        return webServiceSupport.wsdlForDataIngestion(view, config, endpointUrl);
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
        DataIngestionServiceEntity entity = requireOpenWebServiceEntity(serviceCode, serviceKey);
        DataIngestionServiceView view = toView(entity);
        WebServiceSupport.ParsedSoapRequest parsed = webServiceSupport.parse(soapEnvelope);
        WebServiceConfig config = normalizedWebServiceConfig(view);
        validateSoapOperation(config, parsed);
        String effectiveToken = hasText(token)
                ? token
                : webServiceSupport.tokenFromSoapHeader(parsed, "token", "dataIngestionToken");
        DataIngestionInvokeResult result = invoke(serviceCode,
                serviceKey,
                effectiveToken,
                webServiceSupport.mergeHeaders(httpHeaders, parsed.getHeaders()),
                parsed.getBody(),
                new LinkedHashMap<String, Object>(),
                parsed.getBody(),
                "SOAP",
                clientIp,
                userAgent);
        return webServiceSupport.successEnvelope(config, webServiceSupport.ingestionResultToPayload(result), parsed.getSoapVersion());
    }

    public DataIngestionInvokeResult invoke(String serviceCode,
                                            String serviceKey,
                                            String token,
                                            Map<String, Object> headers,
                                            Map<String, Object> query,
                                            Map<String, Object> form,
                                            Object body,
                                            String requestMethod,
                                            String clientIp,
                                            String userAgent) {
        long startedAt = System.nanoTime();
        LocalDateTime occurredAt = LocalDateTime.now();
        String requestId = newRequestId();
        DataIngestionServiceEntity entity = null;
        DataIngestionSubscriptionEntity subscription = null;
        DataIngestionInvokeResult result = null;
        boolean success = false;
        int httpStatus = 200;
        String errorCode = null;
        String errorMessage = null;
        Long jobId = IdWorker.getId();
        OpenServiceInvocationLogSupport.LogScope logScope = invocationLogSupport.open(requestId,
                OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES,
                jobId);
        try {
            entity = serviceMapper.selectOne(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                    .eq(DataIngestionServiceEntity::getServiceCode, normalizeRequiredText(serviceCode, "Service code is required"))
                    .eq(DataIngestionServiceEntity::getServiceKey, normalizeRequiredText(serviceKey, "Service key is required"))
                    .last("limit 1"));
            if (entity == null || !DataIngestionStatus.ONLINE.name().equalsIgnoreCase(entity.getStatus())) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Data ingestion service is not available");
            }
            subscription = resolveInvocationSubscription(entity, token);
            result = execute(toView(entity), headers, query, form, body, requestId, jobId, logScope == null ? null : requestId, true);
            success = true;
            return result;
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
            long receivedCount = result == null || result.getReceivedCount() == null ? 0L : result.getReceivedCount().longValue();
            long successCount = result == null || result.getSuccessCount() == null ? 0L : result.getSuccessCount().longValue();
            long failedCount = result == null || result.getFailedCount() == null ? 0L : result.getFailedCount().longValue();
            if (logScope != null) {
                logScope.close();
            }
            String capturedLog = logScope == null ? null : logScope.content();
            String systemLog = buildInvocationSystemLog(entity, subscription, defaultSubscriptionNameForLog(entity), requestId, requestMethod,
                    occurredAt, startedAt, success, httpStatus, errorCode, errorMessage, receivedCount, successCount, failedCount);
            String archiveContent = buildInvocationArchiveLog(systemLog, headers, query, form, body, result, capturedLog);
            OpenServiceInvocationLogService.ArchiveResult archiveResult = invocationLogService.archive(
                    OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES,
                    "data-ingestion",
                    requestId,
                    occurredAt,
                    archiveContent);
            accessLogSupport.recordAccessLog(entity, subscription, defaultSubscriptionNameForLog(entity), requestId, requestMethod, occurredAt, startedAt, success,
                    httpStatus, errorCode, errorMessage, systemLog, clientIp, userAgent, receivedCount, successCount, failedCount, archiveResult);
        }
    }

    public List<DataIngestionSubscriptionView> listSubscriptions(Long serviceId) {
        requireAccessibleServiceReference(serviceId);
        List<DataIngestionSubscriptionEntity> entities = subscriptionMapper.selectList(new LambdaQueryWrapper<DataIngestionSubscriptionEntity>()
                .select(DataIngestionSubscriptionEntity::getId,
                        DataIngestionSubscriptionEntity::getTenantId,
                        DataIngestionSubscriptionEntity::getProjectId,
                        DataIngestionSubscriptionEntity::getDeleted,
                        DataIngestionSubscriptionEntity::getCreatedAt,
                        DataIngestionSubscriptionEntity::getUpdatedAt,
                        DataIngestionSubscriptionEntity::getServiceId,
                        DataIngestionSubscriptionEntity::getSubscriptionName,
                        DataIngestionSubscriptionEntity::getTokenMasked,
                        DataIngestionSubscriptionEntity::getEnabled,
                        DataIngestionSubscriptionEntity::getCreatedBy,
                        DataIngestionSubscriptionEntity::getLastUsedAt,
                        DataIngestionSubscriptionEntity::getRotatedAt,
                        DataIngestionSubscriptionEntity::getRotatedBy)
                .eq(DataIngestionSubscriptionEntity::getServiceId, serviceId)
                .orderByDesc(DataIngestionSubscriptionEntity::getEnabled)
                .orderByDesc(DataIngestionSubscriptionEntity::getCreatedAt)
                .orderByDesc(DataIngestionSubscriptionEntity::getId));
        List<DataIngestionSubscriptionView> result = new ArrayList<DataIngestionSubscriptionView>();
        for (DataIngestionSubscriptionEntity entity : entities) {
            result.add(toSubscriptionView(entity, null));
        }
        return result;
    }

    @Transactional
    public DataIngestionSubscriptionView createSubscription(Long serviceId, DataServiceSubscriptionCreateRequest request) {
        DataIngestionServiceEntity service = requireWritableServiceReference(serviceId);
        if (request == null || !hasText(request.getSubscriptionName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Subscription name is required");
        }
        String subscriptionName = request.getSubscriptionName().trim();
        DataIngestionSubscriptionEntity activeDuplicate = subscriptionMapper.selectOne(new LambdaQueryWrapper<DataIngestionSubscriptionEntity>()
                .select(DataIngestionSubscriptionEntity::getId)
                .eq(DataIngestionSubscriptionEntity::getServiceId, serviceId)
                .eq(DataIngestionSubscriptionEntity::getSubscriptionName, subscriptionName)
                .eq(DataIngestionSubscriptionEntity::getEnabled, 1)
                .last("limit 1"));
        if (activeDuplicate != null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Subscription name already exists");
        }
        String token = tokenSupport.generateSubscriptionToken();
        DataIngestionSubscriptionEntity entity = new DataIngestionSubscriptionEntity();
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
    public DataIngestionSubscriptionView rotateSubscription(Long serviceId, Long subscriptionId) {
        DataIngestionSubscriptionEntity entity = requireSubscription(serviceId, subscriptionId);
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
    public DataIngestionSubscriptionView disableSubscription(Long serviceId, Long subscriptionId) {
        DataIngestionSubscriptionEntity entity = requireSubscription(serviceId, subscriptionId);
        entity.setEnabled(Integer.valueOf(0));
        subscriptionMapper.updateById(entity);
        return toSubscriptionView(entity, null);
    }

    @Transactional
    public DataIngestionSubscriptionView enableSubscription(Long serviceId, Long subscriptionId) {
        DataIngestionSubscriptionEntity entity = requireSubscription(serviceId, subscriptionId);
        DataIngestionSubscriptionEntity activeDuplicate = subscriptionMapper.selectOne(new LambdaQueryWrapper<DataIngestionSubscriptionEntity>()
                .select(DataIngestionSubscriptionEntity::getId)
                .eq(DataIngestionSubscriptionEntity::getServiceId, serviceId)
                .eq(DataIngestionSubscriptionEntity::getSubscriptionName, entity.getSubscriptionName())
                .eq(DataIngestionSubscriptionEntity::getEnabled, 1)
                .ne(DataIngestionSubscriptionEntity::getId, subscriptionId)
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

    private DataIngestionInvokeResult execute(DataIngestionServiceView service,
                                              Map<String, Object> headers,
                                              Map<String, Object> query,
                                              Map<String, Object> form,
                                              Object body,
                                              String requestId,
                                              boolean enforceStatus) {
        return execute(service, headers, query, form, body, requestId, null, null, enforceStatus);
    }

    private DataIngestionInvokeResult execute(DataIngestionServiceView service,
                                              Map<String, Object> headers,
                                              Map<String, Object> query,
                                              Map<String, Object> form,
                                              Object body,
                                              String requestId,
                                              Long jobId,
                                              String logCaptureId,
                                              boolean enforceStatus) {
        validateExecutable(service);
        List<DataIngestionFieldMapping> mappings = fieldSupport.normalizeFieldMappings(service.getFieldMappings(), requiredModel(service.getModelId()));
        return executionSupport.execute(service, mappings, headers, query, form, body, requestId, jobId, logCaptureId, enforceStatus);
    }

    private List<Map<String, Object>> parseSourceRows(DataIngestionServiceView service,
                                                      Object body,
                                                      List<DataIngestionFieldMapping> mappings) {
        return executionSupport.parseSourceRows(service, body, mappings);
    }

    private DataIngestionRequestFormat deriveRequestFormat(DataIngestionRequestFormat requested,
                                                           List<DataIngestionFieldMapping> mappings) {
        if (fieldSupport.usesJsonBody(mappings)) {
            return DataIngestionRequestFormat.JSON;
        }
        for (DataIngestionFieldMapping mapping : mappings) {
            if (mapping != null && mapping.getSourcePosition() == DataIngestionSourcePosition.FORM) {
                return DataIngestionRequestFormat.FORM;
            }
        }
        return requested == null ? DataIngestionRequestFormat.JSON : requested;
    }

    private DataIngestionServiceListView toListView(DataIngestionServiceEntity entity) {
        DataIngestionServiceListView view = new DataIngestionServiceListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setCreatedBy(entity.getCreatedBy());
        view.setServiceCode(entity.getServiceCode());
        view.setServiceName(entity.getServiceName());
        view.setStatus(enumValue(DataIngestionStatus.class, entity.getStatus(), DataIngestionStatus.DRAFT));
        view.setPayloadMode(enumValue(DataIngestionPayloadMode.class, entity.getPayloadMode(), DataIngestionPayloadMode.OBJECT));
        view.setDataNodePath(entity.getDataNodePath());
        view.setTargetType(enumValue(DataIngestionTargetType.class, entity.getTargetType(), DataIngestionTargetType.DATABASE));
        view.setDatasourceId(entity.getDatasourceId());
        view.setDatasourceName(entity.getDatasourceNameSnapshot());
        view.setDatasourceTypeCode(entity.getDatasourceTypeCode());
        view.setModelId(entity.getModelId());
        view.setModelName(entity.getModelNameSnapshot());
        view.setModelPhysicalLocator(entity.getModelPhysicalLocator());
        view.setEndpointPath(entity.getEndpointPath());
        view.setMaxBatchSize(entity.getMaxBatchSize() == null ? Integer.valueOf(DEFAULT_MAX_BATCH_SIZE) : entity.getMaxBatchSize());
        view.setTokenRequired(isTokenRequired(entity));
        view.setDefaultSubscriptionName(entity.getDefaultSubscriptionName());
        view.setWebserviceEnabled(entity.getWebserviceEnabled() != null && entity.getWebserviceEnabled().intValue() == 1);
        view.setRequestFormat(Boolean.TRUE.equals(view.getWebserviceEnabled())
                ? DataIngestionRequestFormat.SOAP
                : enumValue(DataIngestionRequestFormat.class, entity.getRequestFormat(), DataIngestionRequestFormat.JSON));
        view.setSourcePositions(sourcePositions(entity.getFieldMappingsJson()));
        return view;
    }

    private DataIngestionServiceView toView(DataIngestionServiceEntity entity) {
        DataIngestionServiceListView listView = toListView(entity);
        DataIngestionServiceView view = new DataIngestionServiceView();
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
        view.setRequestFormat(listView.getRequestFormat());
        view.setPayloadMode(listView.getPayloadMode());
        view.setDataNodePath(listView.getDataNodePath());
        view.setTargetType(listView.getTargetType());
        view.setDatasourceId(listView.getDatasourceId());
        view.setDatasourceName(listView.getDatasourceName());
        view.setDatasourceTypeCode(listView.getDatasourceTypeCode());
        view.setModelId(listView.getModelId());
        view.setModelName(listView.getModelName());
        view.setModelPhysicalLocator(listView.getModelPhysicalLocator());
        view.setEndpointPath(listView.getEndpointPath());
        view.setMaxBatchSize(listView.getMaxBatchSize());
        view.setTokenRequired(listView.getTokenRequired());
        view.setDefaultSubscriptionName(listView.getDefaultSubscriptionName());
        view.setWebserviceEnabled(listView.getWebserviceEnabled());
        view.setSourcePositions(listView.getSourcePositions());
        view.setServiceKey(entity.getServiceKey());
        view.setWebserviceConfig(fromWebServiceConfigMap(entity.getWebserviceConfigJson(), "data-ingestion-service", entity.getServiceCode(), view.getWebserviceEnabled()));
        view.setWriterOptions(entity.getWriterOptionsJson() == null ? new LinkedHashMap<String, Object>() : entity.getWriterOptionsJson());
        view.setFieldMappings(fromMapList(entity.getFieldMappingsJson()));
        return view;
    }

    private DataIngestionSubscriptionView toSubscriptionView(DataIngestionSubscriptionEntity entity, String token) {
        DataIngestionSubscriptionView view = new DataIngestionSubscriptionView();
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

    private String tokenMaskedForList(String tokenMasked) {
        return hasText(tokenMasked) ? tokenMasked : "历史 Token 不可查看，请重新生成";
    }

    private void validateSaveRequest(DataIngestionServiceSaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Request is required");
        }
        normalizeRequiredText(request.getServiceCode(), "Service code is required");
        normalizeRequiredText(request.getServiceName(), "Service name is required");
        validateSimpleIdentifier(request.getServiceCode(), "Service code must contain only letters, numbers and underscores");
        if (request.getDatasourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource is required");
        }
        if (request.getModelId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model is required");
        }
        normalizeMaxBatchSize(request.getMaxBatchSize());
    }

    private void validateExecutable(DataIngestionServiceView view) {
        if (view.getDatasourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource is required");
        }
        if (view.getModelId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model is required");
        }
        if (view.getFieldMappings() == null || view.getFieldMappings().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Field mappings are not configured");
        }
    }

    private void validateTarget(DataSourceDefinition datasource, DataModelDefinition model, DataIngestionTargetType targetType) {
        if (model.getDatasourceId() == null || model.getDatasourceId().longValue() != datasource.getId().longValue()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model does not belong to selected datasource");
        }
        String category = pluginRuntimeOptionSchemaService.sourceCategory(datasource.getTypeCode());
        boolean fileDatasource = CATEGORY_FILE_SYSTEM.equalsIgnoreCase(category)
                || "ftp".equalsIgnoreCase(datasource.getTypeCode())
                || "sftp".equalsIgnoreCase(datasource.getTypeCode())
                || "minio".equalsIgnoreCase(datasource.getTypeCode());
        if (targetType == DataIngestionTargetType.FILE && !fileDatasource) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "File target requires a file-system datasource");
        }
        if (targetType == DataIngestionTargetType.DATABASE && fileDatasource) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Database target requires a database datasource");
        }
    }

    private void ensureUniqueServiceCode(Long projectId, String serviceCode, Long selfId) {
        String normalizedServiceCode = normalizeRequiredText(serviceCode, "Service code is required");
        List<DataIngestionServiceEntity> duplicates = serviceMapper.selectList(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                .eq(DataIngestionServiceEntity::getTenantId, securityService.currentTenantId())
                .eq(DataIngestionServiceEntity::getProjectId, projectId)
                .eq(DataIngestionServiceEntity::getServiceCode, normalizedServiceCode));
        for (DataIngestionServiceEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Service code already exists in current project");
        }
    }

    private void ensureUniqueServiceName(Long projectId, String serviceName, Long selfId) {
        String normalizedServiceName = normalizeRequiredText(serviceName, "Service name is required");
        List<DataIngestionServiceEntity> duplicates = serviceMapper.selectList(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                .eq(DataIngestionServiceEntity::getTenantId, securityService.currentTenantId())
                .eq(DataIngestionServiceEntity::getProjectId, projectId)
                .eq(DataIngestionServiceEntity::getServiceName, normalizedServiceName));
        for (DataIngestionServiceEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Service name already exists in current project");
        }
    }

    private DataIngestionServiceEntity requireAccessibleEntity(Long id) {
        DataIngestionServiceEntity entity = serviceMapper.selectById(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data ingestion service not found: " + id);
        }
        if (!securityService.currentTenantId().equals(entity.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data ingestion service not found: " + id);
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE,
                entity.getProjectId(), entity.getId(), "Data ingestion service not found: " + id);
        return entity;
    }

    private DataIngestionServiceEntity requireAccessibleServiceReference(Long id) {
        DataIngestionServiceEntity entity = serviceMapper.selectOne(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                .select(DataIngestionServiceEntity::getId,
                        DataIngestionServiceEntity::getTenantId,
                        DataIngestionServiceEntity::getProjectId)
                .eq(DataIngestionServiceEntity::getId, id)
                .last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data ingestion service not found: " + id);
        }
        if (!securityService.currentTenantId().equals(entity.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data ingestion service not found: " + id);
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE,
                entity.getProjectId(), entity.getId(), "Data ingestion service not found: " + id);
        return entity;
    }

    private DataIngestionServiceEntity requireWritableEntity(Long id) {
        DataIngestionServiceEntity entity = requireAccessibleEntity(id);
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private DataIngestionServiceEntity requireWritableServiceReference(Long id) {
        DataIngestionServiceEntity entity = requireAccessibleServiceReference(id);
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private DataIngestionServiceEntity requireOpenWebServiceEntity(String serviceCode, String serviceKey) {
        DataIngestionServiceEntity entity = serviceMapper.selectOne(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                .eq(DataIngestionServiceEntity::getServiceCode, normalizeRequiredText(serviceCode, "Service code is required"))
                .eq(DataIngestionServiceEntity::getServiceKey, normalizeRequiredText(serviceKey, "Service key is required"))
                .last("limit 1"));
        if (entity == null || !DataIngestionStatus.ONLINE.name().equalsIgnoreCase(entity.getStatus())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data ingestion service is not available");
        }
        if (entity.getWebserviceEnabled() == null || entity.getWebserviceEnabled().intValue() != 1) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data ingestion service WebService endpoint is not available");
        }
        return entity;
    }

    private void ensureWebServiceEnabled(DataIngestionServiceView view) {
        if (view == null || !Boolean.TRUE.equals(view.getWebserviceEnabled())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "WebService is not enabled");
        }
    }

    private void validateSoapOperation(WebServiceConfig config, WebServiceSupport.ParsedSoapRequest parsed) {
        if (config == null || parsed == null || !hasText(parsed.getOperationName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SOAP operation is required");
        }
        if (!config.getRequestRootName().equals(parsed.getOperationName())
                && !config.getOperationName().equals(parsed.getOperationName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SOAP operation does not match service config");
        }
    }

    private DataIngestionSubscriptionEntity requireSubscription(Long serviceId, Long subscriptionId) {
        requireWritableServiceReference(serviceId);
        DataIngestionSubscriptionEntity entity = subscriptionMapper.selectOne(new LambdaQueryWrapper<DataIngestionSubscriptionEntity>()
                .select(DataIngestionSubscriptionEntity::getId,
                        DataIngestionSubscriptionEntity::getTenantId,
                        DataIngestionSubscriptionEntity::getProjectId,
                        DataIngestionSubscriptionEntity::getDeleted,
                        DataIngestionSubscriptionEntity::getCreatedAt,
                        DataIngestionSubscriptionEntity::getUpdatedAt,
                        DataIngestionSubscriptionEntity::getServiceId,
                        DataIngestionSubscriptionEntity::getSubscriptionName,
                        DataIngestionSubscriptionEntity::getTokenMasked,
                        DataIngestionSubscriptionEntity::getEnabled,
                        DataIngestionSubscriptionEntity::getCreatedBy,
                        DataIngestionSubscriptionEntity::getLastUsedAt,
                        DataIngestionSubscriptionEntity::getRotatedAt,
                        DataIngestionSubscriptionEntity::getRotatedBy)
                .eq(DataIngestionSubscriptionEntity::getId, subscriptionId)
                .last("limit 1"));
        if (entity == null || entity.getServiceId() == null || entity.getServiceId().longValue() != serviceId.longValue()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Subscription not found: " + subscriptionId);
        }
        return entity;
    }

    private DataIngestionSubscriptionEntity resolveInvocationSubscription(DataIngestionServiceEntity service, String token) {
        if (hasText(token)) {
            return validateSubscriptionToken(service.getId(), token);
        }
        if (isTokenRequired(service)) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Data ingestion token is required");
        }
        return null;
    }

    private String buildInvocationSystemLog(DataIngestionServiceEntity service,
                                            DataIngestionSubscriptionEntity subscription,
                                            String defaultSubscriptionName,
                                            String requestId,
                                            String requestMethod,
                                            LocalDateTime occurredAt,
                                            long startedAt,
                                            boolean success,
                                            int httpStatus,
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
        values.put("serviceStatus", service == null ? null : service.getStatus());
        values.put("authMode", service == null ? "UNKNOWN" : (isTokenRequired(service) ? "TOKEN_REQUIRED" : "TOKEN_OPTIONAL"));
        values.put("subscription", subscription == null ? defaultSubscriptionName : subscription.getSubscriptionName());
        values.put("targetType", service == null ? null : service.getTargetType());
        values.put("datasource", service == null ? null : service.getDatasourceNameSnapshot());
        values.put("datasourceType", service == null ? null : service.getDatasourceTypeCode());
        values.put("model", service == null ? null : service.getModelNameSnapshot());
        values.put("modelLocator", service == null ? null : service.getModelPhysicalLocator());
        values.put("receivedCount", receivedCount);
        values.put("successCount", successCount);
        values.put("failedCount", failedCount);
        values.put("httpStatus", httpStatus);
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
                                             Object body,
                                             DataIngestionInvokeResult result,
                                             String capturedLog) {
        StringBuilder builder = new StringBuilder(4096);
        invocationLogService.appendSection(builder, "Invocation Summary", systemLog);
        invocationLogService.appendSection(builder, "Request Headers", invocationLogService.previewValue(invocationLogService.sanitizeHeaders(headers)));
        invocationLogService.appendSection(builder, "Request Query", invocationLogService.previewValue(query));
        invocationLogService.appendSection(builder, "Request Form", invocationLogService.previewValue(form));
        invocationLogService.appendSection(builder, "Request Body", invocationLogService.previewValue(body));
        invocationLogService.appendSection(builder, "Response Summary", invocationLogService.previewValue(result));
        invocationLogService.appendSection(builder, "Captured Console Logs",
                hasText(capturedLog) ? capturedLog : "No console log was captured for this invocation.");
        return builder.toString();
    }

    private String joinError(String errorCode, String errorMessage) {
        String code = hasText(errorCode) ? errorCode.trim() : "";
        String message = hasText(errorMessage) ? errorMessage.trim() : "";
        return (code + " " + message).trim();
    }

    private DataIngestionSubscriptionEntity validateSubscriptionToken(Long serviceId, String token) {
        if (!hasText(token)) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Data ingestion token is required");
        }
        DataIngestionSubscriptionEntity entity = subscriptionMapper.selectOne(new LambdaQueryWrapper<DataIngestionSubscriptionEntity>()
                .eq(DataIngestionSubscriptionEntity::getServiceId, serviceId)
                .eq(DataIngestionSubscriptionEntity::getTokenHash, tokenSupport.hashToken(token.trim()))
                .eq(DataIngestionSubscriptionEntity::getEnabled, 1)
                .last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Invalid data ingestion token");
        }
        subscriptionMapper.update(null, new LambdaUpdateWrapper<DataIngestionSubscriptionEntity>()
                .eq(DataIngestionSubscriptionEntity::getId, entity.getId())
                .set(DataIngestionSubscriptionEntity::getLastUsedAt, LocalDateTime.now()));
        return entity;
    }

    private boolean isTokenRequired(DataIngestionServiceEntity service) {
        return service == null || service.getTokenRequired() == null || service.getTokenRequired().intValue() != 0;
    }

    private String defaultSubscriptionNameForLog(DataIngestionServiceEntity service) {
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

    private DataSourceDefinition requiredDatasource(Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        return datasource;
    }

    private DataModelDefinition requiredModel(Long modelId) {
        return dataModelService.get(modelId);
    }

    private DataIngestionStatus resolveSavedStatus(DataIngestionServiceEntity entity) {
        if (entity.getId() == null) {
            return DataIngestionStatus.DRAFT;
        }
        DataIngestionStatus current = enumValue(DataIngestionStatus.class, entity.getStatus(), DataIngestionStatus.DRAFT);
        return current == DataIngestionStatus.ONLINE ? DataIngestionStatus.ONLINE : DataIngestionStatus.DRAFT;
    }

    private String buildEndpointPath(String serviceCode, String serviceKey) {
        return OPEN_PATH_PREFIX + "/" + serviceCode + "/" + serviceKey;
    }

    private String buildWebServiceEndpointPath(String serviceCode, String serviceKey) {
        return WS_OPEN_PATH_PREFIX + "/" + serviceCode + "/" + serviceKey;
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

    private int normalizeMaxBatchSize(Integer maxBatchSize) {
        if (maxBatchSize == null || maxBatchSize.intValue() <= 0) {
            return DEFAULT_MAX_BATCH_SIZE;
        }
        if (maxBatchSize.intValue() > MAX_BATCH_SIZE) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Max batch size cannot exceed " + MAX_BATCH_SIZE);
        }
        return maxBatchSize.intValue();
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

    private void validateSimpleIdentifier(String identifier, String message) {
        if (!hasText(identifier) || !SIMPLE_IDENTIFIER.matcher(identifier.trim()).matches()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
    }

    private String normalizeRequiredText(String value, String message) {
        if (!hasText(value)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeText(String value) {
        return value == null || value.trim().isEmpty() ? absent() : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private List<Map<String, Object>> toMapList(List<DataIngestionFieldMapping> mappings) {
        return objectMapper.convertValue(mappings, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    private List<DataIngestionFieldMapping> fromMapList(List<Map<String, Object>> mappings) {
        if (mappings == null) {
            return new ArrayList<DataIngestionFieldMapping>();
        }
        return objectMapper.convertValue(mappings, new TypeReference<List<DataIngestionFieldMapping>>() {
        });
    }

    private List<String> sourcePositions(List<Map<String, Object>> mappings) {
        Set<String> positions = new LinkedHashSet<String>();
        if (mappings != null) {
            for (Map<String, Object> mapping : mappings) {
                Object value = mapping == null ? null : mapping.get("sourcePosition");
                String position = value == null ? null : String.valueOf(value).trim();
                positions.add(hasText(position) ? position.toUpperCase(Locale.ROOT) : DataIngestionSourcePosition.BODY.name());
            }
        }
        return new ArrayList<String>(positions);
    }

    private WebServiceConfig normalizedWebServiceConfig(DataIngestionServiceView view) {
        return webServiceSupport.normalizeConfig(view == null ? null : view.getWebserviceConfig(),
                "data-ingestion-service",
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

    private String newRequestId() {
        return String.valueOf(IdWorker.getId());
    }

    private <T> T absent() {
        return Optional.<T>empty().orElse(null);
    }

}
