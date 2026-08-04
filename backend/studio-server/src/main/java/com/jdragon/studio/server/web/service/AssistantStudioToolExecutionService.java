package com.jdragon.studio.server.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.enums.LineageLevel;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.RuntimeClusterView;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowScheduleDefinition;
import com.jdragon.studio.dto.model.request.CollectionTaskSaveRequest;
import com.jdragon.studio.dto.model.request.AlertChannelQueryRequest;
import com.jdragon.studio.dto.model.request.AlertDeliveryQueryRequest;
import com.jdragon.studio.dto.model.request.AlertIncidentActionRequest;
import com.jdragon.studio.dto.model.request.AlertIncidentQueryRequest;
import com.jdragon.studio.dto.model.request.AlertRuleQueryRequest;
import com.jdragon.studio.dto.model.request.DataModelSaveRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsChartRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsOptionsRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsRequest;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentDirectorySaveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentMoveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentScriptSaveRequest;
import com.jdragon.studio.dto.model.request.DataIngestionResolveFieldsRequest;
import com.jdragon.studio.dto.model.request.DataIngestionDebugRequest;
import com.jdragon.studio.dto.model.request.DataIngestionServiceSaveRequest;
import com.jdragon.studio.dto.model.request.DataScriptExecutionRequest;
import com.jdragon.studio.dto.model.request.DataIngestionMetricQueryRequest;
import com.jdragon.studio.dto.model.request.DataServiceMetricQueryRequest;
import com.jdragon.studio.dto.model.request.DataServiceResolveFieldsRequest;
import com.jdragon.studio.dto.model.request.DataServiceDebugRequest;
import com.jdragon.studio.dto.model.request.DataServiceSaveRequest;
import com.jdragon.studio.dto.model.request.DataServiceSubscriptionCreateRequest;
import com.jdragon.studio.dto.model.request.FieldMappingRuleSaveRequest;
import com.jdragon.studio.dto.model.request.MetadataSchemaSaveRequest;
import com.jdragon.studio.dto.model.request.NotificationQueryRequest;
import com.jdragon.studio.dto.model.request.OpsCenterQueryRequest;
import com.jdragon.studio.dto.model.request.QualityRuleParseRequest;
import com.jdragon.studio.dto.model.request.QualityRuleSaveRequest;
import com.jdragon.studio.dto.model.request.QualityRuleValidateRequest;
import com.jdragon.studio.dto.model.request.QualityAssetQueryRequest;
import com.jdragon.studio.dto.model.request.QualityIssueAssignRequest;
import com.jdragon.studio.dto.model.request.QualityIssueCommentRequest;
import com.jdragon.studio.dto.model.request.QualityIssueQueryRequest;
import com.jdragon.studio.dto.model.request.QualityIssueSeverityRequest;
import com.jdragon.studio.dto.model.request.QualityIssueStatusRequest;
import com.jdragon.studio.dto.model.request.QualityMetricDashboardQueryRequest;
import com.jdragon.studio.dto.model.request.QualityTaskSaveRequest;
import com.jdragon.studio.dto.model.request.ProtocolConversionDebugRequest;
import com.jdragon.studio.dto.model.request.ProtocolConversionServiceSaveRequest;
import com.jdragon.studio.dto.model.request.RunMetricDashboardQueryRequest;
import com.jdragon.studio.dto.model.request.SavedDataScriptExecutionRequest;
import com.jdragon.studio.dto.model.request.ScriptEnvironmentSaveRequest;
import com.jdragon.studio.dto.model.request.SqlExecutionRequest;
import com.jdragon.studio.dto.model.request.UserRegistrationRequestReviewRequest;
import com.jdragon.studio.dto.model.request.WorkflowSaveRequest;
import com.jdragon.studio.dto.model.request.WebServiceDebugRequest;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.ProjectMemberRequestEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.ResourceShareEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.entity.TenantMemberEntity;
import com.jdragon.studio.infra.service.AssistantStudioOperationRegistry;
import com.jdragon.studio.infra.service.AlertChannelService;
import com.jdragon.studio.infra.service.AlertDeliveryService;
import com.jdragon.studio.infra.service.AlertIncidentService;
import com.jdragon.studio.infra.service.AlertRuleService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DataIngestionService;
import com.jdragon.studio.infra.service.DataIngestionMetricsService;
import com.jdragon.studio.infra.service.DataDevelopmentService;
import com.jdragon.studio.infra.service.DataModelLineageService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataModelStatisticsService;
import com.jdragon.studio.infra.service.DataModelStatisticsWorkspaceService;
import com.jdragon.studio.infra.service.DataServiceService;
import com.jdragon.studio.infra.service.DataServiceMetricsService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.FieldMappingRuleService;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.NotificationService;
import com.jdragon.studio.infra.service.OpsCenterService;
import com.jdragon.studio.infra.service.PluginRuntimeOptionSchemaService;
import com.jdragon.studio.infra.service.ProtocolConversionService;
import com.jdragon.studio.infra.service.QualityIssueService;
import com.jdragon.studio.infra.service.QualityMetricsService;
import com.jdragon.studio.infra.service.QualityRuleService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.RunMetricsService;
import com.jdragon.studio.infra.service.RunService;
import com.jdragon.studio.infra.service.RuntimeClusterService;
import com.jdragon.studio.infra.service.ScriptEnvironmentService;
import com.jdragon.studio.infra.service.StandardRuntimeOptionSchemaBootstrapService;
import com.jdragon.studio.infra.service.StudioDashboardService;
import com.jdragon.studio.infra.service.SystemManagementService;
import com.jdragon.studio.infra.service.UserManagementService;
import com.jdragon.studio.infra.service.UserRegistrationRequestService;
import com.jdragon.studio.infra.service.WorkspaceAccessService;
import com.jdragon.studio.infra.service.WorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AssistantStudioToolExecutionService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULTED_PARAM_KEYS = "__assistantDefaultedParams";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AssistantStudioOperationRegistry operationRegistry;
    private final AssistantScriptRuntimeRouter assistantScriptRuntimeRouter;
    private final StudioDashboardService studioDashboardService;
    private final WorkspaceAccessService workspaceAccessService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final DataSourceService dataSourceService;
    private final DataDevelopmentService dataDevelopmentService;
    private final MetadataSchemaService metadataSchemaService;
    private final StandardRuntimeOptionSchemaBootstrapService runtimeOptionSchemaBootstrapService;
    private final PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService;
    private final DataModelService dataModelService;
    private final DataModelLineageService dataModelLineageService;
    private final DataModelStatisticsService dataModelStatisticsService;
    private final DataModelStatisticsWorkspaceService dataModelStatisticsWorkspaceService;
    private final FieldMappingRuleService fieldMappingRuleService;
    private final CollectionTaskService collectionTaskService;
    private final WorkflowService workflowService;
    private final RunService runService;
    private final RunLogProxyService runLogProxyService;
    private final DispatchService dispatchService;
    private final RunMetricsService runMetricsService;
    private final DataServiceService dataServiceService;
    private final DataServiceMetricsService dataServiceMetricsService;
    private final DataIngestionService dataIngestionService;
    private final DataIngestionMetricsService dataIngestionMetricsService;
    private final ProtocolConversionService protocolConversionService;
    private final QualityMetricsService qualityMetricsService;
    private final QualityIssueService qualityIssueService;
    private final QualityRuleService qualityRuleService;
    private final QualityTaskService qualityTaskService;
    private final ScriptEnvironmentService scriptEnvironmentService;
    private final SystemManagementService systemManagementService;
    private final UserManagementService userManagementService;
    private final UserRegistrationRequestService userRegistrationRequestService;
    private final NotificationService notificationService;
    private final OpsCenterService opsCenterService;
    private AlertRuleService alertRuleService;
    private AlertIncidentService alertIncidentService;
    private AlertChannelService alertChannelService;
    private AlertDeliveryService alertDeliveryService;
    private RuntimeClusterService runtimeClusterService;
    private RuntimeInvocationRouter runtimeInvocationRouter;

    public AssistantStudioToolExecutionService(AssistantStudioOperationRegistry operationRegistry,
                                               AssistantScriptRuntimeRouter assistantScriptRuntimeRouter,
                                               StudioDashboardService studioDashboardService,
                                               WorkspaceAccessService workspaceAccessService,
                                               DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                                               DataSourceService dataSourceService,
                                               DataDevelopmentService dataDevelopmentService,
                                               MetadataSchemaService metadataSchemaService,
                                               StandardRuntimeOptionSchemaBootstrapService runtimeOptionSchemaBootstrapService,
                                               PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService,
                                               DataModelService dataModelService,
                                               DataModelLineageService dataModelLineageService,
                                               DataModelStatisticsService dataModelStatisticsService,
                                               DataModelStatisticsWorkspaceService dataModelStatisticsWorkspaceService,
                                               FieldMappingRuleService fieldMappingRuleService,
                                               CollectionTaskService collectionTaskService,
                                               WorkflowService workflowService,
                                               RunService runService,
                                               RunLogProxyService runLogProxyService,
                                               DispatchService dispatchService,
                                               RunMetricsService runMetricsService,
                                               DataServiceService dataServiceService,
                                               DataServiceMetricsService dataServiceMetricsService,
                                               DataIngestionService dataIngestionService,
                                               DataIngestionMetricsService dataIngestionMetricsService,
                                               ProtocolConversionService protocolConversionService,
                                               QualityMetricsService qualityMetricsService,
                                               QualityIssueService qualityIssueService,
                                               QualityRuleService qualityRuleService,
                                               QualityTaskService qualityTaskService,
                                               ScriptEnvironmentService scriptEnvironmentService,
                                               SystemManagementService systemManagementService,
                                               UserManagementService userManagementService,
                                               UserRegistrationRequestService userRegistrationRequestService,
                                               NotificationService notificationService,
                                               OpsCenterService opsCenterService) {
        this.operationRegistry = operationRegistry;
        this.assistantScriptRuntimeRouter = assistantScriptRuntimeRouter;
        this.studioDashboardService = studioDashboardService;
        this.workspaceAccessService = workspaceAccessService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.dataSourceService = dataSourceService;
        this.dataDevelopmentService = dataDevelopmentService;
        this.metadataSchemaService = metadataSchemaService;
        this.runtimeOptionSchemaBootstrapService = runtimeOptionSchemaBootstrapService;
        this.pluginRuntimeOptionSchemaService = pluginRuntimeOptionSchemaService;
        this.dataModelService = dataModelService;
        this.dataModelLineageService = dataModelLineageService;
        this.dataModelStatisticsService = dataModelStatisticsService;
        this.dataModelStatisticsWorkspaceService = dataModelStatisticsWorkspaceService;
        this.fieldMappingRuleService = fieldMappingRuleService;
        this.collectionTaskService = collectionTaskService;
        this.workflowService = workflowService;
        this.runService = runService;
        this.runLogProxyService = runLogProxyService;
        this.dispatchService = dispatchService;
        this.runMetricsService = runMetricsService;
        this.dataServiceService = dataServiceService;
        this.dataServiceMetricsService = dataServiceMetricsService;
        this.dataIngestionService = dataIngestionService;
        this.dataIngestionMetricsService = dataIngestionMetricsService;
        this.protocolConversionService = protocolConversionService;
        this.qualityMetricsService = qualityMetricsService;
        this.qualityIssueService = qualityIssueService;
        this.qualityRuleService = qualityRuleService;
        this.qualityTaskService = qualityTaskService;
        this.scriptEnvironmentService = scriptEnvironmentService;
        this.systemManagementService = systemManagementService;
        this.userManagementService = userManagementService;
        this.userRegistrationRequestService = userRegistrationRequestService;
        this.notificationService = notificationService;
        this.opsCenterService = opsCenterService;
    }

    @Autowired(required = false)
    void setAlertServices(AlertRuleService alertRuleService,
                          AlertIncidentService alertIncidentService,
                          AlertChannelService alertChannelService,
                          AlertDeliveryService alertDeliveryService) {
        this.alertRuleService = alertRuleService;
        this.alertIncidentService = alertIncidentService;
        this.alertChannelService = alertChannelService;
        this.alertDeliveryService = alertDeliveryService;
    }

    @Autowired
    void setRuntimeClusterService(RuntimeClusterService runtimeClusterService) {
        this.runtimeClusterService = runtimeClusterService;
    }

    @Autowired
    void setRuntimeInvocationRouter(RuntimeInvocationRouter runtimeInvocationRouter) {
        this.runtimeInvocationRouter = runtimeInvocationRouter;
    }

    public Map<String, Object> execute(Map<String, Object> request) {
        String interfaceCode = stringValue(firstValue(request, "interfaceCode", "tool"));
        Map<String, Object> params = objectMap(request == null ? null : request.get("params"));
        Map<String, Object> effectiveParams = new LinkedHashMap<String, Object>(params);
        if (!StringUtils.hasText(interfaceCode)) {
            throw new IllegalArgumentException("assistant tool interfaceCode is required");
        }
        if ("assistant.script.execute".equals(interfaceCode)) {
            return executeAssistantScript(params, effectiveParams);
        }
        if (!"studio.feature.list".equals(interfaceCode)
                && !"studio.feature.get".equals(interfaceCode)
                && !"studio.feature.action".equals(interfaceCode)) {
            throw new IllegalArgumentException("assistant backend tool only supports controlled studio.feature.list/get/action and assistant.script.execute");
        }
        String path = normalizePath(effectiveParams.get("path"));
        String action = "";
        String resource = "";
        boolean mutation = false;
        boolean requiresConfirmation = false;
        Object data;
        if ("studio.feature.action".equals(interfaceCode)) {
            action = normalizeView(effectiveParams.get("action"));
            resource = normalizeView(effectiveParams.get("resource"));
            Map<String, Object> actionDefinition = assertActionToolAllowed(path, action, resource);
            validateActionToolDeclaredParameters(path, action, resource, actionDefinition, effectiveParams);
            mutation = booleanValue(actionDefinition.get("mutation"));
            requiresConfirmation = mutation && !confirmed(effectiveParams);
            if (requiresConfirmation) {
                data = confirmationRequired(path, action, resource, actionDefinition);
            } else {
                validateRequiredValues(path, action, resource, actionDefinition, effectiveParams);
                data = executeAction(path, action, resource, effectiveParams);
            }
        } else {
            Map<String, Object> readToolDefinition = assertReadToolAllowed(path, interfaceCode);
            validateReadToolRequiredValues(path, interfaceCode, readToolDefinition, effectiveParams);
            validateReadToolDeclaredParameters(path, interfaceCode, readToolDefinition, effectiveParams);
            data = "studio.feature.list".equals(interfaceCode)
                    ? executeList(path, effectiveParams)
                    : executeGet(path, effectiveParams);
        }

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("schema", "studio.tool-result.v1");
        response.put("interfaceCode", interfaceCode);
        response.put("path", path);
        if (StringUtils.hasText(action)) {
            response.put("action", action);
        }
        if (StringUtils.hasText(resource)) {
            response.put("resource", resource);
        }
        response.put("executedBy", "backend");
        response.put("mutation", Boolean.valueOf(mutation));
        response.put("requiresConfirmation", Boolean.valueOf(requiresConfirmation));
        response.put("params", params);
        response.put("effectiveParams", cleanEffectiveParams(effectiveParams));
        response.put("defaultedParams", defaultedParams(effectiveParams));
        response.put("data", data);
        return response;
    }

    private Map<String, Object> executeAssistantScript(Map<String, Object> params, Map<String, Object> effectiveParams) {
        if (!StringUtils.hasText(stringValue(effectiveParams.get("entrypointId")))) {
            throw new IllegalArgumentException("assistant script entrypointId is required");
        }
        if (!(effectiveParams.get("input") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("assistant script input is required");
        }
        AssistantScriptRuntimeRouter.ExecutionResult execution =
                assistantScriptRuntimeRouter.execute(effectiveParams);
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("schema", "studio.tool-result.v1");
        response.put("interfaceCode", "assistant.script.execute");
        response.put("entrypointId", effectiveParams.get("entrypointId"));
        response.put("executedBy", "worker");
        response.put("runtimeClusterId", execution.getRuntimeClusterId());
        response.put("mutation", Boolean.FALSE);
        response.put("requiresConfirmation", Boolean.FALSE);
        response.put("params", params);
        response.put("effectiveParams", cleanEffectiveParams(effectiveParams));
        response.put("defaultedParams", defaultedParams(effectiveParams));
        response.put("data", execution.getData());
        return response;
    }

    private Object executeList(String path, Map<String, Object> params) {
        String view = normalizeView(params.get("view"));
        if ("/dashboard".equals(path)) {
            return studioDashboardService.overview();
        }
        if ("/access-center".equals(path)) {
            return workspaceAccessService.overview();
        }
        if ("/catalog".equals(path)) {
            if ("datasourceTypes".equals(view)) {
                return datasourceTypeCapabilityService.listEnabled();
            }
            if ("runtimeOptionSchema".equals(view)) {
                return pluginRuntimeOptionSchemaService.schema(
                        stringParam(params, "role"),
                        stringParam(params, "datasourceType", "typeCode"),
                        stringParam(params, "protocolMode"));
            }
            return capabilityMatrix();
        }
        if ("/runtime-clusters".equals(path)) {
            if (runtimeClusterService == null) {
                throw new IllegalStateException("Runtime cluster service is unavailable");
            }
            return runtimeClusterOptions(params);
        }
        if ("/metadata".equals(path)) {
            return metadataSchemaService.listSchemas(booleanParam(params, "includeFields", true));
        }
        if ("/datasources".equals(path)) {
            return dataSourceService.listSummaryPage(intParam(params, "pageNo", DEFAULT_PAGE_NO, 1, 10000),
                    intParam(params, "pageSize", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE));
        }
        if ("/models".equals(path)) {
            Long datasourceId = optionalLongParam(params, "datasourceId");
            Long runtimeClusterId = optionalLongParam(params, "runtimeClusterId");
            String keyword = stringParam(params, "keyword");
            if (runtimeClusterId != null) {
                return dataModelService.listSelectorOptions(
                        stringParam(params, "datasourceType"), datasourceId, runtimeClusterId, keyword,
                        pageNo(params), pageSize(params));
            }
            if (StringUtils.hasText(keyword)) {
                if (datasourceId != null) {
                    return dataModelService.listDatasourceOptions(datasourceId, keyword,
                            pageNo(params), pageSize(params));
                }
                return dataModelService.listOptions(keyword, pageNo(params), pageSize(params));
            }
            if (datasourceId != null) {
                return dataModelService.listByDatasourceSummaryPage(datasourceId, pageNo(params), pageSize(params),
                        stringParam(params, "sortField"), stringParam(params, "sortOrder"));
            }
            return dataModelService.listSummaryPage(stringParam(params, "datasourceType"),
                    pageNo(params), pageSize(params), stringParam(params, "sortField"), stringParam(params, "sortOrder"));
        }
        if ("/statistics".equals(path)) {
            return executeStatisticsList(view, params);
        }
        if ("/field-mapping-rules".equals(path)) {
            return fieldMappingRuleService.list(pageNo(params), pageSize(params),
                    keyword(params), stringParam(params, "mappingType"), optionalBooleanParam(params, "enabled"));
        }
        if ("/collection-tasks".equals(path)) {
            return collectionTaskService.listSummaryPage(pageNo(params), pageSize(params),
                    stringParam(params, "name", "keyword"),
                    stringParam(params, "targetDatasource", "targetDatasourceName"),
                    stringParam(params, "targetModel", "targetModelName"));
        }
        if ("/collection-task-runs".equals(path)) {
            return runService.listRunRecords(optionalLongParam(params, "collectionTaskId", "taskId"),
                    null, null, Boolean.TRUE, null, status(params),
                    optionalLongParam(params, "requestedClusterId", "targetClusterId"),
                    optionalLongParam(params, "actualClusterId"),
                    dateTimeParam(params, "startTime"), dateTimeParam(params, "endTime"),
                    pageNo(params), pageSize(params));
        }
        if ("/workflows".equals(path)) {
            return workflowService.listSummaryPage(pageNo(params), pageSize(params));
        }
        if ("/runs".equals(path)) {
            return runService.listRunRecords(optionalLongParam(params, "collectionTaskId"),
                    optionalLongParam(params, "qualityTaskId"),
                    optionalLongParam(params, "workflowDefinitionId", "workflowId"),
                    null, null, status(params),
                    optionalLongParam(params, "requestedClusterId", "targetClusterId"),
                    optionalLongParam(params, "actualClusterId"),
                    dateTimeParam(params, "startTime"), dateTimeParam(params, "endTime"),
                    pageNo(params), pageSize(params));
        }
        if ("/run-metrics".equals(path)) {
            if ("options".equals(view)) {
                return runMetricsService.options();
            }
            return runMetricsService.query(runMetricQuery(params));
        }
        if ("/data-services".equals(path)) {
            return dataServiceService.list(pageNo(params), pageSize(params), keyword(params), status(params), stringParam(params, "serviceType"));
        }
        if ("/data-ingestion-services".equals(path)) {
            return dataIngestionService.list(pageNo(params), pageSize(params), keyword(params), status(params), stringParam(params, "targetType"));
        }
        if ("/data-service-metrics".equals(path)) {
            if ("options".equals(view)) {
                return dataServiceMetricsService.options();
            }
            if ("apiStats".equals(view) || "apis".equals(view)) {
                return dataServiceMetricsService.queryApiStats(dataServiceMetricQuery(params));
            }
            if ("accessLogs".equals(view) || "logs".equals(view)) {
                return dataServiceMetricsService.queryAccessLogs(dataServiceMetricQuery(params));
            }
            return dataServiceMetricsService.queryDashboard(dataServiceMetricQuery(params));
        }
        if ("/data-ingestion-metrics".equals(path)) {
            if ("options".equals(view)) {
                return dataIngestionMetricsService.options();
            }
            if ("apiStats".equals(view) || "apis".equals(view)) {
                return dataIngestionMetricsService.queryApiStats(dataIngestionMetricQuery(params));
            }
            if ("accessLogs".equals(view) || "logs".equals(view)) {
                return dataIngestionMetricsService.queryAccessLogs(dataIngestionMetricQuery(params));
            }
            return dataIngestionMetricsService.queryDashboard(dataIngestionMetricQuery(params));
        }
        if ("/protocol-conversions".equals(path)) {
            return protocolConversionService.list(pageNo(params), pageSize(params), keyword(params), status(params));
        }
        if ("/data-development".equals(path)) {
            if ("scripts".equals(view)) {
                return dataDevelopmentService.listScripts(scriptType(params.get("scriptType")));
            }
            if ("datasources".equals(view)) {
                return dataDevelopmentService.listSqlCapableDatasourceOptions(
                        longParam(params, "runtimeClusterId"));
            }
            if ("datasourceTypes".equals(view)) {
                return dataDevelopmentService.listSqlDatasourceTypes();
            }
            return dataDevelopmentService.tree();
        }
        if ("/quality-rules".equals(path)) {
            return qualityRuleService.list(pageNo(params), pageSize(params), keyword(params),
                    stringParam(params, "ruleDimension"), stringParam(params, "scopeType"), optionalBooleanParam(params, "enabled"));
        }
        if ("/quality-tasks".equals(path)) {
            return qualityTaskService.list(pageNo(params), pageSize(params), keyword(params), status(params),
                    stringParam(params, "ruleDimension"), stringParam(params, "granularity"));
        }
        if ("/quality-task-runs".equals(path)) {
            return runService.listRunRecords(null, optionalLongParam(params, "qualityTaskId", "taskId"),
                    null, null, Boolean.TRUE, status(params),
                    optionalLongParam(params, "requestedClusterId", "targetClusterId"),
                    optionalLongParam(params, "actualClusterId"),
                    dateTimeParam(params, "startTime"), dateTimeParam(params, "endTime"),
                    pageNo(params), pageSize(params));
        }
        if ("/quality-metrics".equals(path)) {
            return executeQualityMetricsList(view, params);
        }
        if ("/notifications".equals(path)) {
            return executeNotificationList(view, params);
        }
        if ("/alerts".equals(path)) {
            return executeAlertList(view, params);
        }
        if ("/script-environments".equals(path)) {
            return scriptEnvironmentService.queryPage(pageNo(params), pageSize(params), keyword(params), optionalBooleanParam(params, "enabled"));
        }
        if ("/system".equals(path)) {
            return executeSystemList(params);
        }
        if ("/ops-center".equals(path)) {
            OpsCenterQueryRequest request = opsCenterQuery(params);
            if ("options".equals(view)) {
                return opsCenterService.options();
            }
            if ("queue".equals(view)) {
                return opsCenterService.queryQueue(request);
            }
            if ("runs".equals(view) || "incidents".equals(view)) {
                return opsCenterService.queryRuns(request);
            }
            if ("workers".equals(view)) {
                return opsCenterService.queryWorkers(request);
            }
            if ("serviceEvents".equals(view)) {
                return opsCenterService.queryServiceEvents(request);
            }
            if ("ingestionEvents".equals(view)) {
                return opsCenterService.queryIngestionEvents(request);
            }
            if ("logEvents".equals(view) || "logs".equals(view)) {
                return opsCenterService.queryLogEvents(request);
            }
            return opsCenterService.overview(request);
        }
        throw new IllegalArgumentException("assistant backend list tool does not support path: " + path);
    }

    private Object executeStatisticsList(String view, Map<String, Object> params) {
        if (!StringUtils.hasText(view) || "options".equals(view)) {
            return dataModelStatisticsWorkspaceService.options(payloadOrParams(params, DataModelStatisticsOptionsRequest.class));
        }
        if ("chart".equals(view) || "charts".equals(view) || "queryChart".equals(view)) {
            return dataModelStatisticsWorkspaceService.queryChart(payloadOrParams(params, DataModelStatisticsChartRequest.class));
        }
        if ("raw".equals(view) || "summary".equals(view) || "statistics".equals(view)) {
            return dataModelStatisticsService.statistics(payloadOrParams(params, DataModelStatisticsRequest.class));
        }
        throw new IllegalArgumentException("assistant backend list tool does not support statistics view: " + view);
    }

    private Object executeSystemList(Map<String, Object> params) {
        String resource = normalizeView(firstValue(params, "resource", "view", "tab"));
        if (!StringUtils.hasText(resource)) {
            resource = "tenants";
        }
        if ("users".equals(resource)) {
            return userManagementService.listPage(pageNo(params), pageSize(params));
        }
        if ("registrationRequests".equals(resource) || "userRegistrationRequests".equals(resource)) {
            return userRegistrationRequestService.listPage(pageNo(params), pageSize(params));
        }
        if ("tenants".equals(resource)) {
            return systemManagementService.listTenantsPage(pageNo(params), pageSize(params));
        }
        if ("projects".equals(resource)) {
            return systemManagementService.listProjectsPage(pageNo(params), pageSize(params));
        }
        if ("projectOptions".equals(resource)) {
            return systemManagementService.listProjectOptions();
        }
        if ("tenantMembers".equals(resource)) {
            return systemManagementService.listTenantMembersPage(pageNo(params), pageSize(params));
        }
        if ("projectMembers".equals(resource)) {
            return systemManagementService.listProjectMembersPage(optionalLongParam(params, "projectId"), pageNo(params), pageSize(params));
        }
        if ("requests".equals(resource) || "projectMemberRequests".equals(resource)) {
            return systemManagementService.listProjectMemberRequestsPage(optionalLongParam(params, "projectId"), pageNo(params), pageSize(params));
        }
        if ("workers".equals(resource) || "projectWorkers".equals(resource)) {
            return systemManagementService.listProjectWorkersPage(optionalLongParam(params, "projectId"), pageNo(params), pageSize(params));
        }
        if ("workerOptions".equals(resource) || "projectWorkerOptions".equals(resource)) {
            return systemManagementService.listProjectWorkerOptions(optionalLongParam(params, "projectId"));
        }
        if ("shares".equals(resource) || "resourceShares".equals(resource)) {
            return systemManagementService.listResourceSharesPage(stringParam(params, "resourceType"),
                    optionalLongParam(params, "projectId"), pageNo(params), pageSize(params));
        }
        if ("shareOptions".equals(resource) || "resourceShareOptions".equals(resource)) {
            String resourceType = stringParam(params, "resourceType");
            if (!StringUtils.hasText(resourceType)) {
                throw new IllegalArgumentException("assistant system share options require resourceType");
            }
            return systemManagementService.listShareResourceOptions(resourceType, optionalLongParam(params, "projectId"));
        }
        throw new IllegalArgumentException("assistant backend list tool does not support system resource: " + resource);
    }

    private Object executeGet(String path, Map<String, Object> params) {
        String view = normalizeView(params.get("view"));
        if ("/quality-metrics".equals(path)) {
            return executeQualityMetricsGet(view, params);
        }
        if ("/metadata".equals(path)) {
            String schemaCode = stringParam(params, "schemaCode", "code");
            return StringUtils.hasText(schemaCode)
                    ? metadataSchemaService.getSchemaByCode(schemaCode)
                    : metadataSchemaService.getSchema(longParam(params, "id", "schemaId", "recordId", "entityId"));
        }
        Long id = longParam(params, "id", "recordId", "entityId", "taskId", "modelId", "serviceId", "runId", "scriptId");
        if ("/datasources".equals(path)) {
            if ("history".equals(view) || "connectionHistory".equals(view)) {
                return dataSourceService.connectionHistory(id,
                        intParam(params, "days", 7, 1, 90),
                        intParam(params, "limit", 20, 1, 200),
                        longParam(params, "runtimeClusterId"));
            }
            return dataSourceService.get(id);
        }
        if ("/models".equals(path)) {
            if ("lineage".equals(view)) {
                return dataModelLineageService.getModelLineage(id, lineageLevel(params));
            }
            return dataModelService.maskSensitiveReaderOptions(dataModelService.get(id));
        }
        if ("/field-mapping-rules".equals(path)) {
            return fieldMappingRuleService.get(id);
        }
        if ("/collection-tasks".equals(path)) {
            return collectionTaskService.get(id);
        }
        if ("/collection-task-runs".equals(path) || "/runs".equals(path) || "/quality-task-runs".equals(path)) {
            if ("log".equals(view) || booleanParam(params, "log", false)) {
                return runLogProxyService.viewLog(id,
                        optionalIntParam(params, "pageNo"),
                        optionalIntParam(params, "pageSizeBytes"));
            }
            return runService.get(id);
        }
        if ("/data-development".equals(path)) {
            return dataDevelopmentService.getScript(id);
        }
        if ("/workflows".equals(path)) {
            return workflowService.get(id);
        }
        if ("/data-services".equals(path)) {
            if ("subscriptions".equals(view)) {
                return dataServiceService.listSubscriptions(id);
            }
            if ("webservicePreview".equals(view) || "preview".equals(view)) {
                return dataServiceService.previewWebService(id);
            }
            return dataServiceService.get(id);
        }
        if ("/data-ingestion-services".equals(path)) {
            if ("subscriptions".equals(view)) {
                return dataIngestionService.listSubscriptions(id);
            }
            if ("webservicePreview".equals(view) || "preview".equals(view)) {
                return dataIngestionService.previewWebService(id);
            }
            return dataIngestionService.get(id);
        }
        if ("/protocol-conversions".equals(path)) {
            if ("subscriptions".equals(view)) {
                return protocolConversionService.listSubscriptions(id);
            }
            return protocolConversionService.get(id);
        }
        if ("/quality-rules".equals(path)) {
            return qualityRuleService.get(id);
        }
        if ("/quality-tasks".equals(path)) {
            return qualityTaskService.get(id);
        }
        if ("/script-environments".equals(path)) {
            return scriptEnvironmentService.get(id);
        }
        throw new IllegalArgumentException("assistant backend get tool does not support path: " + path);
    }

    private Object executeAction(String path, String action, String resource, Map<String, Object> params) {
        if ("/datasources".equals(path)) {
            return executeDatasourceAction(action, params);
        }
        if ("/models".equals(path)) {
            return executeModelAction(action, params);
        }
        if ("/field-mapping-rules".equals(path)) {
            return executeFieldMappingRuleAction(action, params);
        }
        if ("/metadata".equals(path)) {
            return executeMetadataAction(action, params);
        }
        if ("/data-development".equals(path)) {
            return executeDataDevelopmentAction(action, resource, params);
        }
        if ("/collection-tasks".equals(path)) {
            return executeCollectionTaskAction(action, params);
        }
        if ("/workflows".equals(path)) {
            return executeWorkflowAction(action, params);
        }
        if ("/data-services".equals(path)) {
            return executeDataServiceAction(action, resource, params);
        }
        if ("/data-ingestion-services".equals(path)) {
            return executeDataIngestionServiceAction(action, resource, params);
        }
        if ("/protocol-conversions".equals(path)) {
            return executeProtocolConversionAction(action, resource, params);
        }
        if ("/quality-rules".equals(path)) {
            return executeQualityRuleAction(action, params);
        }
        if ("/quality-tasks".equals(path)) {
            return executeQualityTaskAction(action, params);
        }
        if ("/quality-metrics".equals(path)) {
            return executeQualityMetricsAction(action, resource, params);
        }
        if ("/notifications".equals(path)) {
            return executeNotificationAction(action, params);
        }
        if ("/alerts".equals(path)) {
            return executeAlertAction(action, params);
        }
        if ("/script-environments".equals(path)) {
            return executeScriptEnvironmentAction(action, params);
        }
        if ("/system".equals(path)) {
            return executeSystemAction(action, resource, params);
        }
        throw new IllegalArgumentException("assistant backend action tool does not support path: " + path);
    }

    private Object executeQualityMetricsList(String view, Map<String, Object> params) {
        if ("options".equals(view)) {
            return qualityMetricsService.options();
        }
        if ("modelOptions".equals(view) || "models".equals(view)) {
            return qualityMetricsService.modelOptions(optionalLongParam(params, "datasourceId"),
                    stringParam(params, "keyword"),
                    optionalIntParam(params, "pageNo"),
                    optionalIntParam(params, "pageSize"));
        }
        if ("assigneeOptions".equals(view) || "assignees".equals(view)) {
            return qualityIssueService.listAssigneeOptions();
        }
        if ("assets".equals(view) || "assetRisks".equals(view)) {
            return qualityMetricsService.queryAssetsPage(qualityAssetQuery(params));
        }
        if ("issues".equals(view)) {
            return qualityIssueService.queryPage(qualityIssueQuery(params));
        }
        return qualityMetricsService.queryDashboard(qualityMetricDashboardQuery(params));
    }

    private Object executeNotificationList(String view, Map<String, Object> params) {
        if (!StringUtils.hasText(view) || "list".equals(view) || "inbox".equals(view)) {
            return notificationService.list(payloadOrParams(params, NotificationQueryRequest.class));
        }
        if ("snapshot".equals(view)) {
            return notificationService.snapshot();
        }
        if ("unread".equals(view) || "unreadCount".equals(view)) {
            return Long.valueOf(notificationService.unreadCount());
        }
        throw new IllegalArgumentException("assistant backend list tool does not support notifications view: " + view);
    }

    private Object executeNotificationAction(String action, Map<String, Object> params) {
        if ("markRead".equals(action)) {
            notificationService.markRead(longParam(params, "id"));
            return null;
        }
        if ("markAllRead".equals(action)) {
            notificationService.markAllRead();
            return null;
        }
        throw new IllegalArgumentException("assistant backend action tool does not support notifications action: " + action);
    }

    private Object executeAlertList(String view, Map<String, Object> params) {
        requireAlertServices();
        if (!StringUtils.hasText(view) || "summary".equals(view)) {
            return alertIncidentService.summary();
        }
        if ("options".equals(view)) {
            return alertRuleService.options();
        }
        if ("rules".equals(view)) {
            return alertRuleService.query(payloadOrParams(params, AlertRuleQueryRequest.class));
        }
        if ("incidents".equals(view)) {
            return alertIncidentService.query(payloadOrParams(params, AlertIncidentQueryRequest.class));
        }
        if ("events".equals(view)) {
            return alertIncidentService.events(longParam(params, "incidentId", "id"), pageNo(params), pageSize(params));
        }
        if ("channels".equals(view)) {
            return alertChannelService.query(payloadOrParams(params, AlertChannelQueryRequest.class));
        }
        if ("deliveries".equals(view)) {
            return alertDeliveryService.query(payloadOrParams(params, AlertDeliveryQueryRequest.class));
        }
        throw new IllegalArgumentException("assistant backend list tool does not support alerts view: " + view);
    }

    private Object executeAlertAction(String action, Map<String, Object> params) {
        requireAlertServices();
        Long id = longParam(params, "id", "ruleId", "incidentId", "channelId", "deliveryId");
        if ("enableRule".equals(action)) {
            return alertRuleService.enable(id);
        }
        if ("disableRule".equals(action)) {
            return alertRuleService.disable(id);
        }
        if ("testRule".equals(action)) {
            return alertIncidentService.testRule(id);
        }
        if ("acknowledgeIncident".equals(action)) {
            return alertIncidentService.acknowledge(id, payloadOrParams(params, AlertIncidentActionRequest.class));
        }
        if ("closeIncident".equals(action)) {
            return alertIncidentService.close(id, payloadOrParams(params, AlertIncidentActionRequest.class));
        }
        if ("testChannel".equals(action)) {
            return alertIncidentService.testChannel(id);
        }
        if ("retryDelivery".equals(action)) {
            return alertDeliveryService.retry(id);
        }
        throw new IllegalArgumentException("assistant backend action tool does not support alerts action: " + action);
    }

    private void requireAlertServices() {
        if (alertRuleService == null || alertIncidentService == null || alertChannelService == null || alertDeliveryService == null) {
            throw new IllegalStateException("Alert center services are unavailable");
        }
    }

    private Object executeQualityMetricsGet(String view, Map<String, Object> params) {
        String resource = normalizeView(firstValue(params, "resource", "type"));
        if ("assets".equals(view) || "asset".equals(view) || "assets".equals(resource) || "asset".equals(resource)) {
            String assetId = stringParam(params, "assetId", "id");
            if (!StringUtils.hasText(assetId)) {
                throw new IllegalArgumentException("assistant quality asset detail requires assetId");
            }
            return qualityMetricsService.getAssetDetail(assetId,
                    dateTimeParam(params, "startTime"),
                    dateTimeParam(params, "endTime"));
        }
        return qualityIssueService.get(longParam(params, "id", "issueId"));
    }

    private Object executeQualityMetricsAction(String action, String resource, Map<String, Object> params) {
        if (!"issues".equals(resource)) {
            throw new IllegalArgumentException("assistant backend action tool only supports quality metric issue actions");
        }
        Long id = longParam(params, "id");
        if ("assignIssue".equals(action)) {
            return qualityIssueService.assign(id, optionalPayload(params, QualityIssueAssignRequest.class));
        }
        if ("updateIssueStatus".equals(action)) {
            return qualityIssueService.updateStatus(id, requiredPayload(params, QualityIssueStatusRequest.class));
        }
        if ("updateIssueSeverity".equals(action)) {
            return qualityIssueService.updateSeverity(id, requiredPayload(params, QualityIssueSeverityRequest.class));
        }
        if ("addIssueComment".equals(action)) {
            return qualityIssueService.addComment(id, requiredPayload(params, QualityIssueCommentRequest.class));
        }
        throw new IllegalArgumentException("assistant backend action tool does not support quality metric action: " + action);
    }

    private Object executeDatasourceAction(String action, Map<String, Object> params) {
        if ("save".equals(action)) {
            return dataSourceService.save(requiredPayload(params, DataSourceSaveRequest.class));
        }
        if ("testCurrent".equals(action)) {
            return dataSourceService.testConnection(requiredPayload(params, DataSourceSaveRequest.class),
                    longParam(params, "runtimeClusterId"));
        }
        Long id = longParam(params, "id");
        if ("test".equals(action)) {
            return dataSourceService.testConnection(id, longParam(params, "runtimeClusterId"));
        }
        if ("discover".equals(action)) {
            return dataSourceService.discoverModels(id,
                    stringParam(params, "keyword"),
                    optionalIntParam(params, "pageNo"),
                    optionalIntParam(params, "pageSize"),
                    longParam(params, "runtimeClusterId"));
        }
        throw new IllegalArgumentException("assistant backend action tool does not support datasource action: " + action);
    }

    private Object executeModelAction(String action, Map<String, Object> params) {
        if ("preview".equals(action)) {
            Long id = longParam(params, "id");
            Long runtimeClusterId = longParam(params, "runtimeClusterId");
            int limit = intParam(params, "limit", 20, 1, 100);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("detail", dataModelService.maskSensitiveReaderOptions(dataModelService.get(id)));
            List<Map<String, Object>> previewRows = dataModelService.preview(id, limit, runtimeClusterId);
            result.put("previewRows", previewRows);
            result.put("sampleRows", previewRows);
            result.put("previewRowCount", Integer.valueOf(previewRows.size()));
            return result;
        }
        if ("save".equals(action)) {
            return dataModelService.maskSensitiveReaderOptions(
                    dataModelService.save(requiredPayload(params, DataModelSaveRequest.class)));
        }
        if ("rebuildIndex".equals(action)) {
            return Integer.valueOf(dataModelService.rebuildSearchIndex(optionalLongParam(params, "datasourceId")));
        }
        Long datasourceId = longParam(params, "datasourceId");
        if ("sync".equals(action)) {
            return dataModelService.maskSensitiveReaderOptions(dataModelService.syncFromDatasource(
                    datasourceId, longParam(params, "runtimeClusterId")));
        }
        if ("syncSelected".equals(action)) {
            return dataModelService.maskSensitiveReaderOptions(
                    dataModelService.syncFromDatasource(datasourceId,
                            stringListParam(params, "physicalLocators"),
                            longParam(params, "runtimeClusterId")));
        }
        throw new IllegalArgumentException("assistant backend action tool does not support model action: " + action);
    }

    private Object executeFieldMappingRuleAction(String action, Map<String, Object> params) {
        if ("save".equals(action)) {
            return fieldMappingRuleService.save(requiredPayload(params, FieldMappingRuleSaveRequest.class));
        }
        throw new IllegalArgumentException("assistant backend action tool does not support field mapping rule action: " + action);
    }

    private Object executeMetadataAction(String action, Map<String, Object> params) {
        if ("saveDraft".equals(action)) {
            return metadataSchemaService.saveDraft(requiredPayload(params, MetadataSchemaSaveRequest.class));
        }
        if ("publish".equals(action)) {
            return metadataSchemaService.publish(longParam(params, "id"));
        }
        if ("syncTechnical".equals(action)) {
            return metadataSchemaService.syncTechnicalMetaModels(stringParam(params, "typeCode"));
        }
        if ("syncAllTechnical".equals(action)) {
            return metadataSchemaService.syncAllTechnicalMetaModels();
        }
        if ("syncStandardRuntimeOptions".equals(action)) {
            return runtimeOptionSchemaBootstrapService.syncStandardRuntimeOptionSchemas();
        }
        throw new IllegalArgumentException("assistant backend action tool does not support metadata action: " + action);
    }

    private Object executeSystemAction(String action, String resource, Map<String, Object> params) {
        if ("users".equals(resource)) {
            if ("saveUser".equals(action)) {
                return userManagementService.save(requiredPayload(params, StudioUserEntity.class));
            }
            if ("deleteUser".equals(action)) {
                userManagementService.delete(longParam(params, "id"));
                return null;
            }
        }
        if ("userRegistrationRequests".equals(resource) || "registrationRequests".equals(resource)) {
            Long id = longParam(params, "id");
            if ("approve".equals(action)) {
                return userRegistrationRequestService.approve(id, optionalPayload(params, UserRegistrationRequestReviewRequest.class));
            }
            if ("reject".equals(action)) {
                return userRegistrationRequestService.reject(id, optionalPayload(params, UserRegistrationRequestReviewRequest.class));
            }
            if ("deleteRegistrationRequest".equals(action)) {
                userRegistrationRequestService.delete(id);
                return null;
            }
        }
        if ("tenants".equals(resource)) {
            if ("saveTenant".equals(action)) {
                return systemManagementService.saveTenant(requiredPayload(params, TenantEntity.class));
            }
            if ("deleteTenant".equals(action)) {
                systemManagementService.deleteTenant(longParam(params, "id"));
                return null;
            }
        }
        if ("projects".equals(resource)) {
            if ("saveProject".equals(action)) {
                return systemManagementService.saveProject(requiredPayload(params, ProjectEntity.class));
            }
            if ("deleteProject".equals(action)) {
                systemManagementService.deleteProject(longParam(params, "id"));
                return null;
            }
        }
        if ("tenantMembers".equals(resource)) {
            if ("saveTenantMember".equals(action)) {
                return systemManagementService.saveTenantMember(requiredPayload(params, TenantMemberEntity.class));
            }
            if ("deleteTenantMember".equals(action)) {
                systemManagementService.deleteTenantMember(longParam(params, "id"));
                return null;
            }
        }
        if ("projectMembers".equals(resource)) {
            if ("saveProjectMember".equals(action)) {
                return systemManagementService.saveProjectMember(requiredPayload(params, ProjectMemberEntity.class));
            }
            if ("deleteProjectMember".equals(action)) {
                systemManagementService.deleteProjectMember(longParam(params, "id"));
                return null;
            }
        }
        if ("projectMemberRequests".equals(resource) || "requests".equals(resource)) {
            if ("saveProjectMemberRequest".equals(action)) {
                return systemManagementService.saveProjectMemberRequest(requiredPayload(params, ProjectMemberRequestEntity.class));
            }
            if ("approve".equals(action)) {
                return systemManagementService.saveProjectMemberRequest(reviewProjectMemberRequest(params, StudioConstants.MEMBER_REQUEST_APPROVED));
            }
            if ("reject".equals(action)) {
                return systemManagementService.saveProjectMemberRequest(reviewProjectMemberRequest(params, StudioConstants.MEMBER_REQUEST_REJECTED));
            }
            if ("deleteProjectMemberRequest".equals(action)) {
                systemManagementService.deleteProjectMemberRequest(longParam(params, "id"));
                return null;
            }
        }
        if ("projectWorkers".equals(resource) || "workers".equals(resource)) {
            if ("saveProjectWorker".equals(action)) {
                return systemManagementService.saveProjectWorkerBinding(requiredPayload(params, ProjectWorkerBindingEntity.class));
            }
            if ("deleteProjectWorker".equals(action)) {
                systemManagementService.deleteProjectWorkerBinding(longParam(params, "id"));
                return null;
            }
        }
        if ("resourceShares".equals(resource) || "shares".equals(resource)) {
            if ("saveResourceShare".equals(action)) {
                return systemManagementService.saveResourceShare(requiredPayload(params, ResourceShareEntity.class));
            }
            if ("deleteResourceShare".equals(action)) {
                systemManagementService.deleteResourceShare(longParam(params, "id"));
                return null;
            }
        }
        throw new IllegalArgumentException("assistant backend action tool does not support system action: " + resource + "/" + action);
    }

    private ProjectMemberRequestEntity reviewProjectMemberRequest(Map<String, Object> params, String status) {
        ProjectMemberRequestEntity entity = optionalPayload(params, ProjectMemberRequestEntity.class);
        if (entity == null) {
            entity = new ProjectMemberRequestEntity();
        }
        entity.setId(longParam(params, "id"));
        entity.setStatus(status);
        return entity;
    }

    private Object executeCollectionTaskAction(String action, Map<String, Object> params) {
        if ("preview".equals(action)) {
            return collectionTaskService.previewForView(requiredPayload(params, CollectionTaskSaveRequest.class));
        }
        if ("save".equals(action)) {
            return collectionTaskService.save(requiredPayload(params, CollectionTaskSaveRequest.class));
        }
        Long id = longParam(params, "id");
        if ("publish".equals(action)) {
            return collectionTaskService.publish(id);
        }
        if ("trigger".equals(action)) {
            Long runtimeClusterId = optionalLongParam(params, "runtimeClusterId");
            if (runtimeClusterId == null) {
                dispatchService.triggerCollectionTask(id);
            } else {
                dispatchService.triggerCollectionTask(id, runtimeClusterId);
            }
            return null;
        }
        if ("schedule".equals(action)) {
            return collectionTaskService.updateSchedule(id, requiredPayload(params, CollectionTaskScheduleDefinition.class));
        }
        if ("resetIncrementalCursor".equals(action)) {
            return collectionTaskService.resetIncrementalCursor(id,
                    stringParam(params, "sourceAlias"),
                    stringParam(params, "incrColumn"),
                    stringParam(params, "incrModel"));
        }
        throw new IllegalArgumentException("assistant backend action tool does not support collection task action: " + action);
    }

    private Object executeWorkflowAction(String action, Map<String, Object> params) {
        if ("save".equals(action)) {
            return workflowService.save(requiredPayload(params, WorkflowSaveRequest.class));
        }
        Long id = longParam(params, "id");
        if ("publish".equals(action)) {
            return workflowService.publish(id);
        }
        if ("trigger".equals(action)) {
            Long runtimeClusterId = optionalLongParam(params, "runtimeClusterId");
            if (runtimeClusterId == null) {
                dispatchService.triggerManualRun(id);
            } else {
                dispatchService.triggerManualRun(id, runtimeClusterId);
            }
            return null;
        }
        if ("schedule".equals(action)) {
            WorkflowDefinitionView workflow = workflowService.get(id);
            WorkflowSaveRequest request = new WorkflowSaveRequest();
            request.setDefinitionId(workflow.getId());
            request.setCode(workflow.getCode());
            request.setName(workflow.getName());
            request.setRuntimeClusterId(workflow.getRuntimeClusterId());
            request.setNodes(workflow.getNodes() == null ? Collections.emptyList() : workflow.getNodes());
            request.setEdges(workflow.getEdges() == null ? Collections.emptyList() : workflow.getEdges());
            request.setSchedule(requiredPayload(params, WorkflowScheduleDefinition.class));
            return workflowService.save(request);
        }
        throw new IllegalArgumentException("assistant backend action tool does not support workflow action: " + action);
    }

    private Object executeQualityTaskAction(String action, Map<String, Object> params) {
        if ("preview".equals(action)) {
            return qualityTaskService.preview(requiredPayload(params, QualityTaskSaveRequest.class));
        }
        if ("validate".equals(action)) {
            return qualityTaskService.validate(requiredPayload(params, QualityTaskSaveRequest.class));
        }
        if ("save".equals(action)) {
            return qualityTaskService.save(requiredPayload(params, QualityTaskSaveRequest.class));
        }
        Long id = longParam(params, "id");
        if ("publish".equals(action)) {
            return qualityTaskService.publish(id);
        }
        if ("trigger".equals(action)) {
            Long runtimeClusterId = optionalLongParam(params, "runtimeClusterId");
            if (runtimeClusterId == null) {
                dispatchService.triggerQualityTask(id);
            } else {
                dispatchService.triggerQualityTask(id, runtimeClusterId);
            }
            return null;
        }
        if ("schedule".equals(action)) {
            return qualityTaskService.updateSchedule(id, requiredPayload(params, CollectionTaskScheduleDefinition.class));
        }
        throw new IllegalArgumentException("assistant backend action tool does not support quality task action: " + action);
    }

    private Object executeDataDevelopmentAction(String action, String resource, Map<String, Object> params) {
        if ("directories".equals(resource)) {
            if ("saveDirectory".equals(action)) {
                return dataDevelopmentService.saveDirectory(requiredPayload(params, DataDevelopmentDirectorySaveRequest.class));
            }
            if ("moveDirectory".equals(action)) {
                dataDevelopmentService.moveDirectory(longParam(params, "id"),
                        requiredPayload(params, DataDevelopmentMoveRequest.class));
                return null;
            }
        }
        if ("scripts".equals(resource)) {
            if ("saveScript".equals(action)) {
                return dataDevelopmentService.saveScript(requiredPayload(params, DataDevelopmentScriptSaveRequest.class));
            }
            if ("moveScript".equals(action)) {
                dataDevelopmentService.moveScript(longParam(params, "id"),
                        requiredPayload(params, DataDevelopmentMoveRequest.class));
                return null;
            }
            if ("executeScript".equals(action)) {
                return dataDevelopmentService.execute(requiredPayload(params, DataScriptExecutionRequest.class));
            }
            if ("executeSavedScript".equals(action)) {
                return dataDevelopmentService.executeSavedScript(longParam(params, "id"),
                        optionalPayload(params, SavedDataScriptExecutionRequest.class));
            }
        }
        if ("sql".equals(resource) && "executeSql".equals(action)) {
            return dataDevelopmentService.execute(requiredPayload(params, SqlExecutionRequest.class));
        }
        throw new IllegalArgumentException("assistant backend action tool does not support data development action: " + resource + "/" + action);
    }

    private Object executeDataServiceAction(String action, String resource, Map<String, Object> params) {
        if ("save".equals(action)) {
            return dataServiceService.save(requiredPayload(params, DataServiceSaveRequest.class));
        }
        if ("resolveFields".equals(action)) {
            return dataServiceService.resolveFields(requiredPayload(params, DataServiceResolveFieldsRequest.class));
        }
        Long id = longParam(params, "id");
        if ("debug".equals(action)) {
            DataServiceDebugRequest request = optionalPayload(params, DataServiceDebugRequest.class);
            RuntimeInvocationRouter.DebugRoute<Map<String, Object>> route = requireRuntimeInvocationRouter()
                    .routeDataServiceDebug(dataServiceService.get(id), request);
            return routedDebug(route);
        }
        if ("debugWebService".equals(action)) {
            WebServiceDebugRequest request = optionalPayload(params, WebServiceDebugRequest.class);
            RuntimeInvocationRouter.DebugRoute<com.jdragon.studio.dto.model.WebServiceDebugResult> route =
                    requireRuntimeInvocationRouter().routeDataServiceWebServiceDebug(dataServiceService.get(id), request);
            return routedDebug(route);
        }
        if ("publish".equals(action)) {
            return dataServiceService.publish(id);
        }
        if ("offline".equals(action)) {
            return dataServiceService.offline(id);
        }
        if ("subscriptions".equals(resource)) {
            if ("createSubscription".equals(action)) {
                return dataServiceService.createSubscription(id, subscriptionCreateRequest(params));
            }
            Long subscriptionId = longParam(params, "subscriptionId");
            if ("enableSubscription".equals(action)) {
                return dataServiceService.enableSubscription(id, subscriptionId);
            }
            if ("disableSubscription".equals(action)) {
                return dataServiceService.disableSubscription(id, subscriptionId);
            }
            if ("rotateSubscription".equals(action)) {
                return dataServiceService.rotateSubscription(id, subscriptionId);
            }
        }
        throw new IllegalArgumentException("assistant backend action tool does not support data service action: " + action);
    }

    private Object executeDataIngestionServiceAction(String action, String resource, Map<String, Object> params) {
        if ("save".equals(action)) {
            return dataIngestionService.save(requiredPayload(params, DataIngestionServiceSaveRequest.class));
        }
        if ("resolveFields".equals(action)) {
            return dataIngestionService.resolveFields(requiredPayload(params, DataIngestionResolveFieldsRequest.class));
        }
        Long id = longParam(params, "id");
        if ("debug".equals(action)) {
            DataIngestionDebugRequest request = optionalPayload(params, DataIngestionDebugRequest.class);
            RuntimeInvocationRouter.DebugRoute<com.jdragon.studio.dto.model.DataIngestionInvokeResult> route =
                    requireRuntimeInvocationRouter().routeDataIngestionDebug(dataIngestionService.get(id), request);
            return routedDebug(route);
        }
        if ("debugWebService".equals(action)) {
            WebServiceDebugRequest request = optionalPayload(params, WebServiceDebugRequest.class);
            RuntimeInvocationRouter.DebugRoute<com.jdragon.studio.dto.model.WebServiceDebugResult> route =
                    requireRuntimeInvocationRouter().routeDataIngestionWebServiceDebug(dataIngestionService.get(id), request);
            return routedDebug(route);
        }
        if ("publish".equals(action)) {
            return dataIngestionService.publish(id);
        }
        if ("offline".equals(action)) {
            return dataIngestionService.offline(id);
        }
        if ("subscriptions".equals(resource)) {
            if ("createSubscription".equals(action)) {
                return dataIngestionService.createSubscription(id, subscriptionCreateRequest(params));
            }
            Long subscriptionId = longParam(params, "subscriptionId");
            if ("enableSubscription".equals(action)) {
                return dataIngestionService.enableSubscription(id, subscriptionId);
            }
            if ("disableSubscription".equals(action)) {
                return dataIngestionService.disableSubscription(id, subscriptionId);
            }
            if ("rotateSubscription".equals(action)) {
                return dataIngestionService.rotateSubscription(id, subscriptionId);
            }
        }
        throw new IllegalArgumentException("assistant backend action tool does not support data ingestion service action: " + action);
    }

    private Object executeProtocolConversionAction(String action, String resource, Map<String, Object> params) {
        if ("save".equals(action)) {
            return protocolConversionService.save(requiredPayload(params, ProtocolConversionServiceSaveRequest.class));
        }
        Long id = longParam(params, "id");
        if ("debug".equals(action)) {
            ProtocolConversionDebugRequest request = optionalPayload(params, ProtocolConversionDebugRequest.class);
            RuntimeInvocationRouter.DebugRoute<com.jdragon.studio.dto.model.ProtocolConversionDebugResult> route =
                    requireRuntimeInvocationRouter().routeProtocolConversionDebug(protocolConversionService.get(id), request);
            return routedDebug(route);
        }
        if ("publish".equals(action)) {
            return protocolConversionService.publish(id);
        }
        if ("offline".equals(action)) {
            return protocolConversionService.offline(id);
        }
        if ("subscriptions".equals(resource)) {
            if ("createSubscription".equals(action)) {
                return protocolConversionService.createSubscription(id, subscriptionCreateRequest(params));
            }
            Long subscriptionId = longParam(params, "subscriptionId");
            if ("enableSubscription".equals(action)) {
                return protocolConversionService.enableSubscription(id, subscriptionId);
            }
            if ("disableSubscription".equals(action)) {
                return protocolConversionService.disableSubscription(id, subscriptionId);
            }
            if ("rotateSubscription".equals(action)) {
                return protocolConversionService.rotateSubscription(id, subscriptionId);
            }
        }
        throw new IllegalArgumentException("assistant backend action tool does not support protocol conversion action: " + action);
    }

    private <T> T routedDebug(RuntimeInvocationRouter.DebugRoute<T> route) {
        Result<T> result = route.getResult();
        if (result != null && result.isSuccess()) {
            return result.getData();
        }
        throw new StudioException(result == null || !StringUtils.hasText(result.getCode())
                ? StudioErrorCode.BUSINESS_ERROR : result.getCode(),
                result == null || !StringUtils.hasText(result.getMessage())
                        ? "Runtime debug failed" : result.getMessage());
    }

    private RuntimeInvocationRouter requireRuntimeInvocationRouter() {
        if (runtimeInvocationRouter == null) {
            throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                    "Runtime invocation router is unavailable; Server-side execution fallback is disabled");
        }
        return runtimeInvocationRouter;
    }

    private Object executeQualityRuleAction(String action, Map<String, Object> params) {
        if ("save".equals(action)) {
            return qualityRuleService.save(requiredPayload(params, QualityRuleSaveRequest.class));
        }
        if ("parse".equals(action)) {
            return qualityRuleService.parse(requiredPayload(params, QualityRuleParseRequest.class));
        }
        if ("validate".equals(action)) {
            return qualityRuleService.validate(requiredPayload(params, QualityRuleValidateRequest.class));
        }
        Long id = longParam(params, "id");
        if ("enable".equals(action)) {
            return qualityRuleService.enable(id);
        }
        if ("disable".equals(action)) {
            return qualityRuleService.disable(id);
        }
        throw new IllegalArgumentException("assistant backend action tool does not support quality rule action: " + action);
    }

    private Object executeScriptEnvironmentAction(String action, Map<String, Object> params) {
        if ("save".equals(action)) {
            return scriptEnvironmentService.saveOrUpdateCheck(requiredPayload(params, ScriptEnvironmentSaveRequest.class));
        }
        Long id = longParam(params, "id");
        if ("enable".equals(action)) {
            return scriptEnvironmentService.enable(id);
        }
        if ("disable".equals(action)) {
            return scriptEnvironmentService.disable(id);
        }
        if ("refresh".equals(action)) {
            return scriptEnvironmentService.refresh(id);
        }
        throw new IllegalArgumentException("assistant backend action tool does not support script environment action: " + action);
    }

    private DataServiceSubscriptionCreateRequest subscriptionCreateRequest(Map<String, Object> params) {
        String subscriptionName = stringParam(params, "subscriptionName");
        if (!StringUtils.hasText(subscriptionName)) {
            throw new IllegalArgumentException("assistant backend subscription action requires subscriptionName");
        }
        DataServiceSubscriptionCreateRequest request = new DataServiceSubscriptionCreateRequest();
        request.setSubscriptionName(subscriptionName);
        return request;
    }

    private <T> T requiredPayload(Map<String, Object> params, Class<T> type) {
        Object payload = params.get("payload");
        if (payload == null) {
            throw new IllegalArgumentException("assistant backend action requires payload");
        }
        return objectMapper.convertValue(payload, type);
    }

    private <T> T optionalPayload(Map<String, Object> params, Class<T> type) {
        Object payload = params.get("payload");
        if (payload == null) {
            return null;
        }
        return objectMapper.convertValue(payload, type);
    }

    private <T> T payloadOrParams(Map<String, Object> params, Class<T> type) {
        Object payload = params == null ? null : params.get("payload");
        if (payload != null) {
            return objectMapper.convertValue(payload, type);
        }
        Map<String, Object> payloadParams = new LinkedHashMap<String, Object>();
        if (params != null) {
            payloadParams.putAll(params);
        }
        payloadParams.remove("path");
        payloadParams.remove("featurePath");
        payloadParams.remove("route");
        payloadParams.remove("view");
        payloadParams.remove("resource");
        payloadParams.remove("action");
        payloadParams.remove("operation");
        payloadParams.remove("command");
        payloadParams.remove("confirmed");
        return objectMapper.convertValue(payloadParams, type);
    }

    private Map<String, Object> capabilityMatrix() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executableSourceTypes", datasourceTypeCapabilityService.executableSourceTypes());
        payload.put("executableTargetTypes", datasourceTypeCapabilityService.executableTargetTypes());
        payload.put("executableDatasourceTypes", datasourceTypeCapabilityService.executableDatasourceTypes());
        payload.put("sourceCapabilities", datasourceTypeCapabilityService.sourceCapabilities());
        return payload;
    }

    private RunMetricDashboardQueryRequest runMetricQuery(Map<String, Object> params) {
        RunMetricDashboardQueryRequest request = new RunMetricDashboardQueryRequest();
        request.setDatasourceId(optionalLongParam(params, "datasourceId"));
        request.setSourceModelId(optionalLongParam(params, "sourceModelId"));
        request.setTargetModelId(optionalLongParam(params, "targetModelId"));
        request.setStartTime(stringParam(params, "startTime"));
        request.setEndTime(stringParam(params, "endTime"));
        request.setGranularity(stringParam(params, "granularity"));
        request.setTopN(optionalIntParam(params, "topN"));
        return request;
    }

    private DataServiceMetricQueryRequest dataServiceMetricQuery(Map<String, Object> params) {
        DataServiceMetricQueryRequest request = new DataServiceMetricQueryRequest();
        request.setServiceId(optionalLongParam(params, "serviceId", "id"));
        request.setSubscriptionId(optionalLongParam(params, "subscriptionId"));
        request.setServiceStatus(stringParam(params, "serviceStatus", "status"));
        request.setSuccess(optionalBooleanParam(params, "success"));
        request.setCacheHit(optionalBooleanParam(params, "cacheHit"));
        request.setStartTime(stringParam(params, "startTime"));
        request.setEndTime(stringParam(params, "endTime"));
        request.setGranularity(stringParam(params, "granularity"));
        request.setLogFocus(stringParam(params, "logFocus"));
        request.setMinDurationMs(optionalLongParam(params, "minDurationMs"));
        request.setTopN(optionalIntParam(params, "topN"));
        request.setPageNo(pageNo(params));
        request.setPageSize(pageSize(params));
        return request;
    }

    private DataIngestionMetricQueryRequest dataIngestionMetricQuery(Map<String, Object> params) {
        DataIngestionMetricQueryRequest request = new DataIngestionMetricQueryRequest();
        request.setServiceId(optionalLongParam(params, "serviceId", "id"));
        request.setSubscriptionId(optionalLongParam(params, "subscriptionId"));
        request.setServiceStatus(stringParam(params, "serviceStatus", "status"));
        request.setSuccess(optionalBooleanParam(params, "success"));
        request.setStartTime(stringParam(params, "startTime"));
        request.setEndTime(stringParam(params, "endTime"));
        request.setGranularity(stringParam(params, "granularity"));
        request.setLogFocus(stringParam(params, "logFocus"));
        request.setMinDurationMs(optionalLongParam(params, "minDurationMs"));
        request.setTopN(optionalIntParam(params, "topN"));
        request.setPageNo(pageNo(params));
        request.setPageSize(pageSize(params));
        return request;
    }

    private QualityMetricDashboardQueryRequest qualityMetricDashboardQuery(Map<String, Object> params) {
        QualityMetricDashboardQueryRequest request = new QualityMetricDashboardQueryRequest();
        request.setDatasourceId(optionalLongParam(params, "datasourceId"));
        request.setModelId(optionalLongParam(params, "modelId"));
        request.setRuleDimension(stringParam(params, "ruleDimension", "dimension"));
        request.setGranularity(stringParam(params, "granularity"));
        request.setTaskStatus(stringParam(params, "taskStatus", "status"));
        request.setStartTime(dateTimeParam(params, "startTime"));
        request.setEndTime(dateTimeParam(params, "endTime"));
        request.setTopN(optionalIntParam(params, "topN"));
        return request;
    }

    private QualityAssetQueryRequest qualityAssetQuery(Map<String, Object> params) {
        QualityAssetQueryRequest request = new QualityAssetQueryRequest();
        request.setDatasourceId(optionalLongParam(params, "datasourceId"));
        request.setModelId(optionalLongParam(params, "modelId"));
        request.setRuleDimension(stringParam(params, "ruleDimension", "dimension"));
        request.setGranularity(stringParam(params, "granularity"));
        request.setTaskStatus(stringParam(params, "taskStatus", "status"));
        request.setStartTime(dateTimeParam(params, "startTime"));
        request.setEndTime(dateTimeParam(params, "endTime"));
        request.setOnlyProblemAssets(optionalBooleanParam(params, "onlyProblemAssets"));
        request.setOnlyLowCoverageAssets(optionalBooleanParam(params, "onlyLowCoverageAssets"));
        request.setPageNo(pageNo(params));
        request.setPageSize(pageSize(params));
        return request;
    }

    private QualityIssueQueryRequest qualityIssueQuery(Map<String, Object> params) {
        QualityIssueQueryRequest request = new QualityIssueQueryRequest();
        request.setDatasourceId(optionalLongParam(params, "datasourceId"));
        request.setModelId(optionalLongParam(params, "modelId"));
        request.setRuleDimension(stringParam(params, "ruleDimension", "dimension"));
        request.setGranularity(stringParam(params, "granularity"));
        request.setTaskStatus(stringParam(params, "taskStatus"));
        request.setSeverity(stringParam(params, "severity"));
        request.setStatus(status(params));
        request.setAssigneeUserId(optionalLongParam(params, "assigneeUserId", "assigneeId"));
        request.setStartTime(dateTimeParam(params, "startTime"));
        request.setEndTime(dateTimeParam(params, "endTime"));
        request.setPageNo(pageNo(params));
        request.setPageSize(pageSize(params));
        return request;
    }

    private OpsCenterQueryRequest opsCenterQuery(Map<String, Object> params) {
        OpsCenterQueryRequest request = new OpsCenterQueryRequest();
        request.setStartTime(stringParam(params, "startTime"));
        request.setEndTime(stringParam(params, "endTime"));
        request.setExecutionType(stringParam(params, "executionType", "type"));
        request.setStatus(status(params));
        request.setWorkerGroupCode(stringParam(params, "workerGroupCode", "workerGroup"));
        request.setRequestedClusterId(optionalLongParam(params, "requestedClusterId", "targetClusterId"));
        request.setActualClusterId(optionalLongParam(params, "actualClusterId"));
        request.setPageNo(pageNo(params));
        request.setPageSize(pageSize(params));
        return request;
    }

    private LineageLevel lineageLevel(Map<String, Object> params) {
        String raw = stringParam(params, "lineageLevel", "level");
        if (!StringUtils.hasText(raw)) {
            return LineageLevel.TABLE;
        }
        return LineageLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> assertReadToolAllowed(String path, String interfaceCode) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("assistant tool path is required");
        }
        for (Map<String, Object> operation : operationRegistry.allOperations()) {
            if (!path.equals(operation.get("path"))) {
                continue;
            }
            for (Map<String, Object> item : readTools(operation)) {
                if (interfaceCode.equals(item.get("tool"))) {
                    return item;
                }
            }
            throw new IllegalArgumentException("assistant operation " + path + " does not allow " + interfaceCode);
        }
        throw new IllegalArgumentException("assistant operation path is not registered: " + path);
    }

    private Map<String, Object> assertActionToolAllowed(String path, String action, String resource) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("assistant tool path is required");
        }
        if (!StringUtils.hasText(action)) {
            throw new IllegalArgumentException("assistant action is required");
        }
        for (Map<String, Object> operation : operationRegistry.allOperations()) {
            if (!path.equals(operation.get("path"))) {
                continue;
            }
            for (Map<String, Object> item : featureActions(operation)) {
                if (!"studio.feature.action".equals(item.get("tool"))) {
                    continue;
                }
                String itemAction = normalizeView(item.get("action"));
                String itemResource = normalizeView(item.get("resource"));
                boolean resourceMatches = StringUtils.hasText(itemResource)
                        ? itemResource.equals(resource)
                        : !StringUtils.hasText(resource);
                if (itemAction.equals(action) && resourceMatches) {
                    return item;
                }
            }
            throw new IllegalArgumentException("assistant operation " + path + " does not allow action " + action);
        }
        throw new IllegalArgumentException("assistant operation path is not registered: " + path);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readTools(Map<String, Object> operation) {
        Object value = operation.get("readTools");
        if (value instanceof List<?>) {
            return (List<Map<String, Object>>) value;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> featureActions(Map<String, Object> operation) {
        Object value = operation.get("featureActions");
        if (value instanceof List<?>) {
            return (List<Map<String, Object>>) value;
        }
        return Collections.emptyList();
    }

    private void validateReadToolRequiredValues(String path,
                                                String interfaceCode,
                                                Map<String, Object> readToolDefinition,
                                                Map<String, Object> params) {
        Object value = readToolDefinition.get("requiredValues");
        if (!(value instanceof List<?>)) {
            return;
        }
        for (Object item : (List<?>) value) {
            String key = stringValue(item).trim();
            if (!StringUtils.hasText(key) || "path".equals(key)) {
                continue;
            }
            if (!hasRequiredValue(requiredValue(params, key))) {
                throw new IllegalArgumentException("assistant tool " + interfaceCode + " " + path + " requires " + key);
            }
        }
    }

    private void validateReadToolDeclaredParameters(String path,
                                                    String interfaceCode,
                                                    Map<String, Object> readToolDefinition,
                                                    Map<String, Object> params) {
        Set<String> allowed = new LinkedHashSet<String>();
        allowed.add("path");
        addDeclaredParameterKeys(allowed, readToolDefinition.get("requiredValues"));
        addDeclaredParameterKeys(allowed, readToolDefinition.get("optionalValues"));
        for (String key : params.keySet()) {
            if (DEFAULTED_PARAM_KEYS.equals(key)) {
                continue;
            }
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("assistant tool " + interfaceCode + " " + path
                        + " does not declare parameter " + key);
            }
        }
    }

    private void validateActionToolDeclaredParameters(String path,
                                                      String action,
                                                      String resource,
                                                      Map<String, Object> actionDefinition,
                                                      Map<String, Object> params) {
        Set<String> allowed = new LinkedHashSet<String>();
        allowed.add("path");
        allowed.add("action");
        allowed.add("resource");
        allowed.add("confirmed");
        addDeclaredParameterKeys(allowed, actionDefinition.get("requiredValues"));
        addDeclaredParameterKeys(allowed, actionDefinition.get("optionalValues"));
        for (String key : params.keySet()) {
            if (DEFAULTED_PARAM_KEYS.equals(key)) {
                continue;
            }
            if (!allowed.contains(key)) {
                StringBuilder message = new StringBuilder("assistant action ")
                        .append(path)
                        .append(" ")
                        .append(action);
                if (StringUtils.hasText(resource)) {
                    message.append(" ").append(resource);
                }
                message.append(" does not declare parameter ").append(key);
                throw new IllegalArgumentException(message.toString());
            }
        }
    }

    private void addDeclaredParameterKeys(Set<String> allowed, Object value) {
        if (!(value instanceof List<?>)) {
            return;
        }
        for (Object item : (List<?>) value) {
            String key = stringValue(item).trim();
            if (StringUtils.hasText(key)) {
                allowed.add(key);
                int nestedIndex = key.indexOf('.');
                if (nestedIndex > 0) {
                    allowed.add(key.substring(0, nestedIndex));
                }
            }
        }
    }

    private void validateRequiredValues(String path,
                                        String action,
                                        String resource,
                                        Map<String, Object> actionDefinition,
                                        Map<String, Object> params) {
        Object value = actionDefinition.get("requiredValues");
        if (!(value instanceof List<?>)) {
            return;
        }
        for (Object item : (List<?>) value) {
            String key = stringValue(item).trim();
            if (!StringUtils.hasText(key)) {
                continue;
            }
            if (!hasRequiredValue(requiredValue(params, key))) {
                StringBuilder message = new StringBuilder("assistant action ")
                        .append(path)
                        .append(" ")
                        .append(action);
                if (StringUtils.hasText(resource)) {
                    message.append(" ").append(resource);
                }
                message.append(" requires ").append(key);
                throw new IllegalArgumentException(message.toString());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object requiredValue(Map<String, Object> params, String key) {
        if (params == null || !StringUtils.hasText(key)) {
            return null;
        }
        String[] parts = key.split("\\.");
        Object current = params;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?>)) {
                return null;
            }
            Map<String, Object> currentMap = (Map<String, Object>) current;
            if (!currentMap.containsKey(part)) {
                return null;
            }
            current = currentMap.get(part);
        }
        return current;
    }

    private boolean hasRequiredValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof CharSequence) {
            return StringUtils.hasText(value.toString());
        }
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                if (hasRequiredValue(item)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Map<?, ?>) {
            return !((Map<?, ?>) value).isEmpty();
        }
        return true;
    }

    private ScriptType scriptType(Object value) {
        String normalized = stringValue(value).trim().toUpperCase(Locale.ROOT);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if ("PY".equals(normalized)) {
            return ScriptType.PYTHON;
        }
        return ScriptType.valueOf(normalized);
    }

    private Long longParam(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value == null || !StringUtils.hasText(String.valueOf(value))) {
                continue;
            }
            if (value instanceof Number) {
                return Long.valueOf(((Number) value).longValue());
            }
            return Long.valueOf(String.valueOf(value));
        }
        throw new IllegalArgumentException("assistant tool "
                + (keys.length == 0 ? "id" : keys[0]) + " is required");
    }

    private Long optionalLongParam(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value == null || !StringUtils.hasText(String.valueOf(value))) {
                continue;
            }
            if (value instanceof Number) {
                return Long.valueOf(((Number) value).longValue());
            }
            return Long.valueOf(String.valueOf(value));
        }
        return null;
    }

    private List<RuntimeClusterView> runtimeClusterOptions(Map<String, Object> params) {
        List<RuntimeClusterView> authorizedOptions = runtimeClusterService.options(null);
        Set<Long> applicableClusterIds = null;

        List<Long> requestedApplicableClusterIds = optionalLongListParam(params, "applicableClusterIds");
        if (params.containsKey("applicableClusterIds")) {
            applicableClusterIds = new LinkedHashSet<Long>(requestedApplicableClusterIds);
        }

        Set<Long> datasourceIds = new LinkedHashSet<Long>(optionalLongListParam(params, "datasourceIds"));
        for (Long modelId : optionalLongListParam(params, "modelIds")) {
            DataModelDefinition model = dataModelService.get(modelId);
            if (model.getDatasourceId() != null) {
                datasourceIds.add(model.getDatasourceId());
            }
        }
        for (Long datasourceId : datasourceIds) {
            DataSourceDefinition datasource = dataSourceService.get(datasourceId);
            if (datasource == null) {
                throw new IllegalArgumentException("assistant runtime cluster datasource was not found: " + datasourceId);
            }
            Set<Long> datasourceClusterIds = datasource.getApplicableClusterIds() == null
                    ? new LinkedHashSet<Long>()
                    : new LinkedHashSet<Long>(datasource.getApplicableClusterIds());
            if (applicableClusterIds == null) {
                applicableClusterIds = datasourceClusterIds;
            } else {
                applicableClusterIds.retainAll(datasourceClusterIds);
            }
        }

        if (applicableClusterIds == null) {
            return authorizedOptions;
        }
        List<RuntimeClusterView> result = new ArrayList<RuntimeClusterView>();
        for (RuntimeClusterView option : authorizedOptions) {
            if (option != null && option.getId() != null && applicableClusterIds.contains(option.getId())) {
                result.add(option);
            }
        }
        return result;
    }

    private List<Long> optionalLongListParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<Long>();
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    result.add(item instanceof Number
                            ? Long.valueOf(((Number) item).longValue())
                            : Long.valueOf(String.valueOf(item)));
                }
            }
        } else {
            for (String item : String.valueOf(value).split(",")) {
                if (StringUtils.hasText(item)) {
                    result.add(Long.valueOf(item.trim()));
                }
            }
        }
        return result;
    }

    private List<String> stringListParam(Map<String, Object> params, String... keys) {
        Object value = firstValue(params, keys);
        if (value == null) {
            throw new IllegalArgumentException("assistant tool " + String.join("/", keys) + " is required");
        }
        List<String> result = new ArrayList<String>();
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                String text = stringValue(item).trim();
                if (StringUtils.hasText(text)) {
                    result.add(text);
                }
            }
        } else {
            for (String item : String.valueOf(value).split(",")) {
                String text = item == null ? "" : item.trim();
                if (StringUtils.hasText(text)) {
                    result.add(text);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("assistant tool " + String.join("/", keys) + " is required");
        }
        return result;
    }

    private int intParam(Map<String, Object> params, String key, int fallback, int min, int max) {
        Object value = params.get(key);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            recordDefaultedParam(params, key);
            params.put(key, Integer.valueOf(fallback));
            return fallback;
        }
        int parsed = value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
        int resolved = Math.min(max, Math.max(min, parsed));
        params.put(key, Integer.valueOf(resolved));
        return resolved;
    }

    private Integer pageNo(Map<String, Object> params) {
        return Integer.valueOf(intParam(params, "pageNo", DEFAULT_PAGE_NO, 1, 10000));
    }

    private Integer pageSize(Map<String, Object> params) {
        return Integer.valueOf(intParam(params, "pageSize", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE));
    }

    private Integer optionalIntParam(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value == null || !StringUtils.hasText(String.valueOf(value))) {
                continue;
            }
            return Integer.valueOf(value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value)));
        }
        return null;
    }

    private String keyword(Map<String, Object> params) {
        return stringParam(params, "keyword");
    }

    private String status(Map<String, Object> params) {
        return stringParam(params, "status");
    }

    private String stringParam(Map<String, Object> params, String... keys) {
        Object value = firstValue(params, keys);
        String text = stringValue(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private Boolean optionalBooleanParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    private boolean booleanParam(Map<String, Object> params, String key, boolean fallback) {
        Boolean value = optionalBooleanParam(params, key);
        if (value == null) {
            recordDefaultedParam(params, key);
            params.put(key, Boolean.valueOf(fallback));
            return fallback;
        }
        params.put(key, value);
        return value.booleanValue();
    }

    private boolean confirmed(Map<String, Object> params) {
        return Boolean.TRUE.equals(optionalBooleanParam(params, "confirmed"));
    }

    private Map<String, Object> cleanEffectiveParams(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (params == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!DEFAULTED_PARAM_KEYS.equals(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private List<String> defaultedParams(Map<String, Object> params) {
        if (params == null) {
            return Collections.emptyList();
        }
        Object value = params.get(DEFAULTED_PARAM_KEYS);
        if (value instanceof List<?>) {
            List<String> result = new ArrayList<String>();
            for (Object item : (List<?>) value) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return Collections.emptyList();
    }

    private void recordDefaultedParam(Map<String, Object> params, String key) {
        if (params == null || !StringUtils.hasText(key)) {
            return;
        }
        Object value = params.get(DEFAULTED_PARAM_KEYS);
        List<String> keys;
        if (value instanceof List<?>) {
            keys = new ArrayList<String>();
            for (Object item : (List<?>) value) {
                keys.add(String.valueOf(item));
            }
        } else {
            keys = new ArrayList<String>();
        }
        if (!keys.contains(key)) {
            keys.add(key);
        }
        params.put(DEFAULTED_PARAM_KEYS, keys);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> confirmationRequired(String path,
                                                     String action,
                                                     String resource,
                                                     Map<String, Object> actionDefinition) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("requiresConfirmation", Boolean.TRUE);
        result.put("path", path);
        result.put("action", action);
        if (StringUtils.hasText(resource)) {
            result.put("resource", resource);
        }
        result.put("purpose", actionDefinition.get("purpose"));
        result.put("message", "assistant feature action requires confirmed=true before backend execution");
        return result;
    }

    private LocalDateTime dateTimeParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.contains(" ")) {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return LocalDateTime.parse(text);
    }

    private String normalizePath(Object path) {
        String value = stringValue(path).trim();
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private String normalizeView(Object view) {
        String raw = stringValue(view).trim();
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        boolean upperNext = false;
        for (char item : raw.toCharArray()) {
            if (item == '-' || item == '_' || Character.isWhitespace(item)) {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(item) : item);
            upperNext = false;
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<String, Object>();
    }

    private Object firstValue(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            if (params.containsKey(key) && params.get(key) != null) {
                return params.get(key);
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
