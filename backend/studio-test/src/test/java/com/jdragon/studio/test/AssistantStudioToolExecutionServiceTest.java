package com.jdragon.studio.test;

import com.jdragon.studio.dto.enums.LineageLevel;
import com.jdragon.studio.dto.enums.DataServiceSourceType;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataSourceListView;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskListView;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataModelLineageView;
import com.jdragon.studio.dto.model.DataModelStatisticsChartView;
import com.jdragon.studio.dto.model.DataModelStatisticsOptionsView;
import com.jdragon.studio.dto.model.DataModelStatisticsView;
import com.jdragon.studio.dto.model.DataDevelopmentScriptView;
import com.jdragon.studio.dto.model.DataIngestionResolveFieldsView;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.DataServiceDefinitionView;
import com.jdragon.studio.dto.model.DataServiceResolveFieldsView;
import com.jdragon.studio.dto.model.DataServiceSubscriptionView;
import com.jdragon.studio.dto.model.FieldMappingRuleView;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.NotificationSnapshotView;
import com.jdragon.studio.dto.model.NotificationView;
import com.jdragon.studio.dto.model.OpsCenterOverviewView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.PluginRuntimeOptionSchemaView;
import com.jdragon.studio.dto.model.ProtocolConversionSubscriptionView;
import com.jdragon.studio.dto.model.QualityIssueDetailView;
import com.jdragon.studio.dto.model.QualityIssueView;
import com.jdragon.studio.dto.model.QualityMetricDashboardView;
import com.jdragon.studio.dto.model.QualityRuleParseResultView;
import com.jdragon.studio.dto.model.QualityRuleValidationResultView;
import com.jdragon.studio.dto.model.QualityRuleView;
import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.dto.model.RunMetricDashboardView;
import com.jdragon.studio.dto.model.ScriptEnvironmentView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.StudioUserListView;
import com.jdragon.studio.dto.model.WebServicePreviewView;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.request.DataModelSaveRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsChartRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsOptionsRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsRequest;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentScriptSaveRequest;
import com.jdragon.studio.dto.model.request.DataIngestionResolveFieldsRequest;
import com.jdragon.studio.dto.model.request.DataServiceResolveFieldsRequest;
import com.jdragon.studio.dto.model.request.DataServiceSubscriptionCreateRequest;
import com.jdragon.studio.dto.model.request.FieldMappingRuleSaveRequest;
import com.jdragon.studio.dto.model.request.MetadataSchemaSaveRequest;
import com.jdragon.studio.dto.model.request.NotificationQueryRequest;
import com.jdragon.studio.dto.model.request.QualityIssueQueryRequest;
import com.jdragon.studio.dto.model.request.QualityIssueStatusRequest;
import com.jdragon.studio.dto.model.request.QualityMetricDashboardQueryRequest;
import com.jdragon.studio.dto.model.request.QualityRuleParseRequest;
import com.jdragon.studio.dto.model.request.QualityRuleValidateRequest;
import com.jdragon.studio.dto.model.request.SavedDataScriptExecutionRequest;
import com.jdragon.studio.dto.model.request.ScriptEnvironmentSaveRequest;
import com.jdragon.studio.dto.model.request.SqlExecutionRequest;
import com.jdragon.studio.dto.model.request.WorkflowSaveRequest;
import com.jdragon.studio.dto.model.system.ResourceShareView;
import com.jdragon.studio.dto.model.system.UserRegistrationRequestView;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.service.AssistantStudioOperationRegistry;
import com.jdragon.studio.infra.service.AssistantScriptSkillExecutionService;
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
import com.jdragon.studio.infra.service.ScriptEnvironmentService;
import com.jdragon.studio.infra.service.StandardRuntimeOptionSchemaBootstrapService;
import com.jdragon.studio.infra.service.StudioDashboardService;
import com.jdragon.studio.infra.service.SystemManagementService;
import com.jdragon.studio.infra.service.UserManagementService;
import com.jdragon.studio.infra.service.UserRegistrationRequestService;
import com.jdragon.studio.infra.service.WorkspaceAccessService;
import com.jdragon.studio.infra.service.WorkflowService;
import com.jdragon.studio.server.web.service.AssistantStudioToolExecutionService;
import com.jdragon.studio.server.web.service.RunLogProxyService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssistantStudioToolExecutionServiceTest {

    @Test
    void executeShouldRouteDatasourceListThroughBackendReadOnlyTool() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        PageView<DataSourceListView> page = PageView.of(2, 10, 1L, Collections.singletonList(datasource("mysql1")));
        when(dataSourceService.listSummaryPage(2, 10)).thenReturn(page);
        AssistantStudioToolExecutionService service = service(dataSourceService);

        Map<String, Object> request = request("studio.feature.list", params(
                "path", "/datasources",
                "pageNo", 2,
                "pageSize", 10));

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("schema")).isEqualTo("studio.tool-result.v1");
        assertThat(result.get("interfaceCode")).isEqualTo("studio.feature.list");
        assertThat(result.get("path")).isEqualTo("/datasources");
        assertThat(result.get("executedBy")).isEqualTo("backend");
        assertThat(result.get("mutation")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("data")).isSameAs(page);
        assertThat(resultMap(result, "params"))
                .containsEntry("path", "/datasources")
                .containsEntry("pageNo", 2)
                .containsEntry("pageSize", 10);
        assertThat(resultMap(result, "effectiveParams"))
                .containsEntry("path", "/datasources")
                .containsEntry("pageNo", 2)
                .containsEntry("pageSize", 10);
        assertThat(resultStringList(result, "defaultedParams")).isEmpty();
        verify(dataSourceService).listSummaryPage(2, 10);
    }

    @Test
    void executeShouldExposeDefaultedDatasourcePaginationInEffectiveParams() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        PageView<DataSourceListView> page = PageView.of(1, 20, 1L, Collections.singletonList(datasource("mysql1")));
        when(dataSourceService.listSummaryPage(1, 20)).thenReturn(page);
        AssistantStudioToolExecutionService service = service(dataSourceService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/datasources")));

        assertThat(result.get("data")).isSameAs(page);
        assertThat(resultMap(result, "params"))
                .containsEntry("path", "/datasources")
                .doesNotContainKeys("pageNo", "pageSize");
        assertThat(resultMap(result, "effectiveParams"))
                .containsEntry("path", "/datasources")
                .containsEntry("pageNo", 1)
                .containsEntry("pageSize", 20);
        assertThat(resultStringList(result, "defaultedParams")).containsExactly("pageNo", "pageSize");
        verify(dataSourceService).listSummaryPage(1, 20);
    }

    @Test
    void executeShouldRejectLegacyNarrowToolCodes() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService = mock(PluginRuntimeOptionSchemaService.class);
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        AssistantStudioToolExecutionService service = service(
                dataSourceService,
                dataModelService,
                pluginRuntimeOptionSchemaService,
                collectionTaskService);

        List<String> legacyToolCodes = java.util.Arrays.asList(
                "catalog.capabilities",
                "datasources.options",
                "models.datasourceOptions",
                "models.get",
                "catalog.runtimeOptionSchema",
                "collectionTasks.preview");
        for (String interfaceCode : legacyToolCodes) {
            assertThatThrownBy(() -> service.execute(request(interfaceCode, params(
                    "path", "/datasources",
                    "id", 11L,
                    "datasourceId", 11L,
                    "collectionTaskPayload", params("name", "order_sync")))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("studio.feature.list/get/action")
                    .hasMessageContaining("assistant.script.execute");
        }

        verifyNoInteractions(dataSourceService);
        verifyNoInteractions(dataModelService);
        verifyNoInteractions(pluginRuntimeOptionSchemaService);
        verifyNoInteractions(collectionTaskService);
    }

    @Test
    void executeShouldRejectFeatureListWithoutCanonicalPath() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        AssistantStudioToolExecutionService service = service(dataSourceService);

        assertThatThrownBy(() -> service.execute(request("studio.feature.list", params(
                "featurePath", "/datasources"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistant tool path is required");

        verifyNoInteractions(dataSourceService);
    }

    @Test
    void executeShouldReadRuntimeOptionSchemaThroughGenericCatalogListTool() {
        PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService = mock(PluginRuntimeOptionSchemaService.class);
        PluginRuntimeOptionSchemaView schema = new PluginRuntimeOptionSchemaView();
        when(pluginRuntimeOptionSchemaService.schema("reader", "MYSQL", "jdbc")).thenReturn(schema);
        AssistantStudioToolExecutionService service = service(pluginRuntimeOptionSchemaService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/catalog",
                "view", "runtimeOptionSchema",
                "role", "reader",
                "datasourceType", "MYSQL",
                "protocolMode", "jdbc")));

        assertThat(result.get("interfaceCode")).isEqualTo("studio.feature.list");
        assertThat(result.get("path")).isEqualTo("/catalog");
        assertThat(result.get("executedBy")).isEqualTo("backend");
        assertThat(result.get("data")).isSameAs(schema);
        verify(pluginRuntimeOptionSchemaService).schema("reader", "MYSQL", "jdbc");
    }

    @Test
    void executeShouldRouteModelDetailThroughBackendReadOnlyTool() {
        DataModelService dataModelService = mock(DataModelService.class);
        DataModelDefinition model = new DataModelDefinition();
        model.setId(Long.valueOf(7L));
        model.setName("ods_order");
        when(dataModelService.get(Long.valueOf(7L))).thenReturn(model);
        when(dataModelService.maskSensitiveReaderOptions(model)).thenReturn(model);
        AssistantStudioToolExecutionService service = service(dataModelService);

        Map<String, Object> result = service.execute(request("studio.feature.get", params(
                "path", "/models",
                "id", 7L)));

        assertThat(result.get("data")).isSameAs(model);
        verify(dataModelService).get(Long.valueOf(7L));
        verify(dataModelService).maskSensitiveReaderOptions(model);
    }

    @Test
    void executeShouldRejectFeatureGetWithoutCanonicalId() {
        DataModelService dataModelService = mock(DataModelService.class);
        AssistantStudioToolExecutionService service = service(dataModelService);

        assertThatThrownBy(() -> service.execute(request("studio.feature.get", params(
                "path", "/models",
                "modelId", 7L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires id");

        verifyNoInteractions(dataModelService);
    }

    @Test
    void executeShouldRejectUndeclaredModelListParameterWithoutKeywordInference() {
        DataModelService dataModelService = mock(DataModelService.class);
        AssistantStudioToolExecutionService service = service(dataModelService);

        assertThatThrownBy(() -> service.execute(request("studio.feature.list", params(
                "path", "/models",
                "tableName", "orders",
                "pageNo", 1,
                "pageSize", 20))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not declare parameter tableName");

        verifyNoInteractions(dataModelService);
    }

    @Test
    void executeShouldRouteCollectionTaskListThroughBackendReadOnlyTool() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        PageView<CollectionTaskListView> page = PageView.of(1, 20, 0L, Collections.<CollectionTaskListView>emptyList());
        when(collectionTaskService.listSummaryPage(1, 20, "order", null, null)).thenReturn(page);
        AssistantStudioToolExecutionService service = service(collectionTaskService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/collection-tasks",
                "keyword", "order")));

        assertThat(result.get("data")).isSameAs(page);
        verify(collectionTaskService).listSummaryPage(1, 20, "order", null, null);
    }

    @Test
    void executeShouldRouteMetadataListThroughBackendReadOnlyTool() {
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        MetadataSchemaDefinition schema = new MetadataSchemaDefinition();
        schema.setSchemaCode("datasource:mysql8");
        when(metadataSchemaService.listSchemas(false)).thenReturn(Collections.singletonList(schema));
        AssistantStudioToolExecutionService service = service(metadataSchemaService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/metadata",
                "includeFields", Boolean.FALSE)));

        assertThat(result.get("data")).isEqualTo(Collections.singletonList(schema));
        verify(metadataSchemaService).listSchemas(false);
    }

    @Test
    void executeShouldRouteMetadataDetailBySchemaCodeThroughBackendReadOnlyTool() {
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        MetadataSchemaDefinition schema = new MetadataSchemaDefinition();
        schema.setSchemaCode("datasource:mysql8");
        when(metadataSchemaService.getSchemaByCode("datasource:mysql8")).thenReturn(schema);
        AssistantStudioToolExecutionService service = service(metadataSchemaService);

        Map<String, Object> result = service.execute(request("studio.feature.get", params(
                "path", "/metadata",
                "schemaCode", "datasource:mysql8")));

        assertThat(result.get("data")).isSameAs(schema);
        verify(metadataSchemaService).getSchemaByCode("datasource:mysql8");
    }

    @Test
    void executeShouldRouteModelLineageThroughBackendReadOnlyTool() {
        DataModelLineageService dataModelLineageService = mock(DataModelLineageService.class);
        DataModelLineageView lineage = new DataModelLineageView();
        when(dataModelLineageService.getModelLineage(Long.valueOf(7L), LineageLevel.FIELD)).thenReturn(lineage);
        AssistantStudioToolExecutionService service = service(dataModelLineageService);

        Map<String, Object> result = service.execute(request("studio.feature.get", params(
                "path", "/models",
                "id", 7L,
                "view", "lineage",
                "lineageLevel", "FIELD")));

        assertThat(result.get("data")).isSameAs(lineage);
        verify(dataModelLineageService).getModelLineage(Long.valueOf(7L), LineageLevel.FIELD);
    }

    @Test
    void executeShouldRouteStatisticsOptionsThroughBackendReadOnlyTool() {
        DataModelStatisticsWorkspaceService statisticsWorkspaceService = mock(DataModelStatisticsWorkspaceService.class);
        DataModelStatisticsOptionsView options = new DataModelStatisticsOptionsView();
        options.setDatasourceType("MYSQL");
        when(statisticsWorkspaceService.options(org.mockito.ArgumentMatchers.any(DataModelStatisticsOptionsRequest.class))).thenReturn(options);
        AssistantStudioToolExecutionService service = service(statisticsWorkspaceService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/statistics",
                "view", "options",
                "datasourceType", "MYSQL",
                "targetScope", "TECHNICAL")));

        assertThat(result.get("data")).isSameAs(options);
        verify(statisticsWorkspaceService).options(argThat(request ->
                "MYSQL".equals(request.getDatasourceType())
                        && "TECHNICAL".equals(request.getTargetScope())));
    }

    @Test
    void executeShouldRouteStatisticsChartThroughBackendReadOnlyTool() {
        DataModelStatisticsWorkspaceService statisticsWorkspaceService = mock(DataModelStatisticsWorkspaceService.class);
        DataModelStatisticsChartView chart = new DataModelStatisticsChartView();
        chart.setChartType("BAR");
        when(statisticsWorkspaceService.queryChart(org.mockito.ArgumentMatchers.any(DataModelStatisticsChartRequest.class))).thenReturn(chart);
        AssistantStudioToolExecutionService service = service(statisticsWorkspaceService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/statistics",
                "view", "chart",
                "payload", params(
                        "chartType", "BAR",
                        "targetMetaSchemaCode", "business:order",
                        "targetFieldKey", "status",
                        "targetScope", "BUSINESS"))));

        assertThat(result.get("data")).isSameAs(chart);
        verify(statisticsWorkspaceService).queryChart(argThat(request ->
                "BAR".equals(request.getChartType())
                        && "business:order".equals(request.getTargetMetaSchemaCode())
                        && "status".equals(request.getTargetFieldKey())));
    }

    @Test
    void executeShouldRouteRawStatisticsThroughBackendReadOnlyTool() {
        DataModelStatisticsService statisticsService = mock(DataModelStatisticsService.class);
        DataModelStatisticsView statistics = new DataModelStatisticsView();
        statistics.setMatchedModelCount(Long.valueOf(3L));
        when(statisticsService.statistics(org.mockito.ArgumentMatchers.any(DataModelStatisticsRequest.class))).thenReturn(statistics);
        AssistantStudioToolExecutionService service = service(statisticsService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/statistics",
                "view", "raw",
                "targetMetaSchemaCode", "business:order",
                "targetFieldKey", "amount",
                "targetScope", "BUSINESS",
                "statType", "SUMMARY")));

        assertThat(result.get("data")).isSameAs(statistics);
        verify(statisticsService).statistics(argThat(request ->
                "business:order".equals(request.getTargetMetaSchemaCode())
                        && "amount".equals(request.getTargetFieldKey())
                        && "SUMMARY".equals(request.getStatType())));
    }

    @Test
    void executeShouldRouteNotificationsListThroughBackendReadOnlyTool() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationView notification = new NotificationView();
        notification.setId(Long.valueOf(501L));
        notification.setTitle("Assistant notice");
        PageView<NotificationView> page = PageView.of(3, 5, 1L, Collections.singletonList(notification));
        when(notificationService.list(org.mockito.ArgumentMatchers.any(NotificationQueryRequest.class))).thenReturn(page);
        AssistantStudioToolExecutionService service = service(notificationService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/notifications",
                "pageNo", 3,
                "pageSize", 5,
                "unreadOnly", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(page);
        verify(notificationService).list(argThat(request ->
                Integer.valueOf(3).equals(request.getPageNo())
                        && Integer.valueOf(5).equals(request.getPageSize())
                        && Boolean.TRUE.equals(request.getUnreadOnly())));
    }

    @Test
    void executeShouldRouteNotificationSnapshotAndUnreadCountThroughBackendReadOnlyTool() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationSnapshotView snapshot = new NotificationSnapshotView();
        snapshot.setUnreadCount(4L);
        when(notificationService.snapshot()).thenReturn(snapshot);
        when(notificationService.unreadCount()).thenReturn(4L);
        AssistantStudioToolExecutionService service = service(notificationService);

        Map<String, Object> snapshotResult = service.execute(request("studio.feature.list", params(
                "path", "/notifications",
                "view", "snapshot")));
        Map<String, Object> unreadResult = service.execute(request("studio.feature.list", params(
                "path", "/notifications",
                "view", "unread-count")));

        assertThat(snapshotResult.get("data")).isSameAs(snapshot);
        assertThat(unreadResult.get("data")).isEqualTo(Long.valueOf(4L));
        verify(notificationService).snapshot();
        verify(notificationService).unreadCount();
    }

    @Test
    void executeShouldRequireConfirmationBeforeNotificationMarkRead() {
        NotificationService notificationService = mock(NotificationService.class);
        AssistantStudioToolExecutionService service = service(notificationService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/notifications",
                "action", "markRead",
                "id", 501L)));

        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.TRUE);
        verifyNoInteractions(notificationService);
    }

    @Test
    void executeShouldRouteConfirmedNotificationMarkReadThroughBackendTool() {
        NotificationService notificationService = mock(NotificationService.class);
        AssistantStudioToolExecutionService service = service(notificationService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/notifications",
                "action", "markRead",
                "id", 501L,
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("mutation")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        verify(notificationService).markRead(Long.valueOf(501L));
    }

    @Test
    void executeShouldRouteRunLogThroughBackendReadOnlyTool() {
        RunLogProxyService runLogProxyService = mock(RunLogProxyService.class);
        RunLogView log = new RunLogView();
        when(runLogProxyService.viewLog(Long.valueOf(9L), Integer.valueOf(2), Integer.valueOf(4096))).thenReturn(log);
        AssistantStudioToolExecutionService service = service(runLogProxyService);

        Map<String, Object> result = service.execute(request("studio.feature.get", params(
                "path", "/runs",
                "id", 9L,
                "view", "log",
                "pageNo", 2,
                "pageSizeBytes", 4096)));

        assertThat(result.get("data")).isSameAs(log);
        verify(runLogProxyService).viewLog(Long.valueOf(9L), Integer.valueOf(2), Integer.valueOf(4096));
    }

    @Test
    void executeShouldRouteRunMetricsThroughBackendReadOnlyTool() {
        RunMetricsService runMetricsService = mock(RunMetricsService.class);
        RunMetricDashboardView dashboard = new RunMetricDashboardView();
        when(runMetricsService.query(org.mockito.ArgumentMatchers.any())).thenReturn(dashboard);
        AssistantStudioToolExecutionService service = service(runMetricsService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/run-metrics",
                "granularity", "DAY",
                "topN", 5)));

        assertThat(result.get("data")).isSameAs(dashboard);
        verify(runMetricsService).query(org.mockito.ArgumentMatchers.argThat(request ->
                "DAY".equals(request.getGranularity()) && Integer.valueOf(5).equals(request.getTopN())));
    }

    @Test
    void executeShouldRouteOpsOverviewThroughBackendReadOnlyTool() {
        OpsCenterService opsCenterService = mock(OpsCenterService.class);
        OpsCenterOverviewView overview = new OpsCenterOverviewView();
        when(opsCenterService.overview(org.mockito.ArgumentMatchers.any())).thenReturn(overview);
        AssistantStudioToolExecutionService service = service(opsCenterService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/ops-center",
                "status", "FAILED",
                "pageSize", 50)));

        assertThat(result.get("data")).isSameAs(overview);
        verify(opsCenterService).overview(org.mockito.ArgumentMatchers.argThat(request ->
                "FAILED".equals(request.getStatus()) && Integer.valueOf(50).equals(request.getPageSize())));
    }

    @Test
    void executeShouldRouteSystemUsersPageThroughBackendReadOnlyTool() {
        UserManagementService userManagementService = mock(UserManagementService.class);
        StudioUserListView user = new StudioUserListView();
        user.setUsername("assistant_admin");
        PageView<StudioUserListView> page = PageView.of(2, 10, 1L, Collections.singletonList(user));
        when(userManagementService.listPage(2, 10)).thenReturn(page);
        AssistantStudioToolExecutionService service = service(userManagementService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/system",
                "resource", "users",
                "pageNo", 2,
                "pageSize", 10)));

        assertThat(result.get("data")).isSameAs(page);
        verify(userManagementService).listPage(2, 10);
    }

    @Test
    void executeShouldRouteSystemResourceSharesPageThroughBackendReadOnlyTool() {
        SystemManagementService systemManagementService = mock(SystemManagementService.class);
        ResourceShareView share = new ResourceShareView();
        share.setResourceType("DATA_SERVICE");
        share.setResourceName("assistant_service");
        PageView<ResourceShareView> page = PageView.of(1, 20, 1L, Collections.singletonList(share));
        when(systemManagementService.listResourceSharesPage("DATA_SERVICE", Long.valueOf(100L), 1, 20)).thenReturn(page);
        AssistantStudioToolExecutionService service = service(systemManagementService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/system",
                "resource", "shares",
                "projectId", 100L,
                "resourceType", "DATA_SERVICE")));

        assertThat(result.get("data")).isSameAs(page);
        verify(systemManagementService).listResourceSharesPage("DATA_SERVICE", Long.valueOf(100L), 1, 20);
    }

    @Test
    void executeShouldRequireConfirmationBeforeSystemUserDelete() {
        UserManagementService userManagementService = mock(UserManagementService.class);
        AssistantStudioToolExecutionService service = service(userManagementService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/system",
                "resource", "users",
                "action", "deleteUser",
                "id", Long.valueOf(7L))));

        assertThat(result.get("path")).isEqualTo("/system");
        assertThat(result.get("action")).isEqualTo("deleteUser");
        assertThat(result.get("mutation")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.TRUE);
        verifyNoInteractions(userManagementService);
    }

    @Test
    void executeShouldRouteConfirmedSystemUserSaveThroughBackendActionTool() {
        UserManagementService userManagementService = mock(UserManagementService.class);
        StudioUserEntity saved = new StudioUserEntity();
        saved.setId(Long.valueOf(11L));
        saved.setUsername("assistant_editor");
        when(userManagementService.save(org.mockito.ArgumentMatchers.any(StudioUserEntity.class))).thenReturn(saved);
        AssistantStudioToolExecutionService service = service(userManagementService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/system",
                "resource", "users",
                "action", "saveUser",
                "payload", params(
                        "username", "assistant_editor",
                        "displayName", "Assistant Editor",
                        "enabled", Integer.valueOf(1)),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(saved);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        verify(userManagementService).save(argThat(user ->
                "assistant_editor".equals(user.getUsername())
                        && "Assistant Editor".equals(user.getDisplayName())
                        && Integer.valueOf(1).equals(user.getEnabled())));
    }

    @Test
    void executeShouldRouteConfirmedRegistrationApprovalThroughBackendActionTool() {
        UserRegistrationRequestService requestService = mock(UserRegistrationRequestService.class);
        UserRegistrationRequestView approved = new UserRegistrationRequestView();
        approved.setId(Long.valueOf(88L));
        approved.setUsername("registered_user");
        approved.setStatus("APPROVED");
        when(requestService.approve(org.mockito.ArgumentMatchers.eq(Long.valueOf(88L)),
                org.mockito.ArgumentMatchers.any())).thenReturn(approved);
        AssistantStudioToolExecutionService service = service(requestService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/system",
                "resource", "userRegistrationRequests",
                "action", "approve",
                "id", Long.valueOf(88L),
                "payload", params("reviewComment", "approved by assistant"),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(approved);
        verify(requestService).approve(org.mockito.ArgumentMatchers.eq(Long.valueOf(88L)), argThat(request ->
                "approved by assistant".equals(request.getReviewComment())));
    }

    @Test
    void executeShouldRouteConfirmedTenantSaveAndDeleteThroughBackendActionTool() {
        SystemManagementService systemManagementService = mock(SystemManagementService.class);
        TenantEntity saved = new TenantEntity();
        saved.setId(Long.valueOf(21L));
        saved.setTenantCode("assistant_tenant");
        when(systemManagementService.saveTenant(org.mockito.ArgumentMatchers.any(TenantEntity.class))).thenReturn(saved);
        AssistantStudioToolExecutionService service = service(systemManagementService);

        Map<String, Object> saveResult = service.execute(request("studio.feature.action", params(
                "path", "/system",
                "resource", "tenants",
                "action", "saveTenant",
                "payload", params(
                        "tenantCode", "assistant_tenant",
                        "tenantName", "Assistant Tenant"),
                "confirmed", Boolean.TRUE)));
        Map<String, Object> deleteResult = service.execute(request("studio.feature.action", params(
                "path", "/system",
                "resource", "tenants",
                "action", "deleteTenant",
                "id", Long.valueOf(21L),
                "confirmed", Boolean.TRUE)));

        assertThat(saveResult.get("data")).isSameAs(saved);
        assertThat(deleteResult.get("data")).isNull();
        verify(systemManagementService).saveTenant(argThat(tenant ->
                "assistant_tenant".equals(tenant.getTenantCode())
                        && "Assistant Tenant".equals(tenant.getTenantName())));
        verify(systemManagementService).deleteTenant(Long.valueOf(21L));
    }

    @Test
    void executeShouldRouteQualityMetricsDashboardThroughBackendReadOnlyTool() {
        QualityMetricsService qualityMetricsService = mock(QualityMetricsService.class);
        QualityMetricDashboardView dashboard = new QualityMetricDashboardView();
        when(qualityMetricsService.queryDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(dashboard);
        AssistantStudioToolExecutionService service = service(qualityMetricsService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/quality-metrics",
                "datasourceId", 7L,
                "granularity", "TABLE",
                "topN", 5)));

        assertThat(result.get("data")).isSameAs(dashboard);
        verify(qualityMetricsService).queryDashboard(org.mockito.ArgumentMatchers.argThat(request ->
                Long.valueOf(7L).equals(request.getDatasourceId())
                        && "TABLE".equals(request.getGranularity())
                        && Integer.valueOf(5).equals(request.getTopN())));
    }

    @Test
    void executeShouldRouteQualityIssuePageThroughBackendReadOnlyTool() {
        QualityIssueService qualityIssueService = mock(QualityIssueService.class);
        PageView<QualityIssueView> page = PageView.of(2, 10, 1L, Collections.<QualityIssueView>emptyList());
        when(qualityIssueService.queryPage(org.mockito.ArgumentMatchers.any(QualityIssueQueryRequest.class))).thenReturn(page);
        AssistantStudioToolExecutionService service = service(qualityIssueService);

        Map<String, Object> result = service.execute(request("studio.feature.list", params(
                "path", "/quality-metrics",
                "view", "issues",
                "status", "OPEN",
                "severity", "HIGH",
                "pageNo", 2,
                "pageSize", 10)));

        assertThat(result.get("data")).isSameAs(page);
        verify(qualityIssueService).queryPage(org.mockito.ArgumentMatchers.argThat(request ->
                "OPEN".equals(request.getStatus())
                        && "HIGH".equals(request.getSeverity())
                        && Integer.valueOf(2).equals(request.getPageNo())
                        && Integer.valueOf(10).equals(request.getPageSize())));
    }

    @Test
    void executeShouldRouteQualityIssueDetailThroughBackendReadOnlyTool() {
        QualityIssueService qualityIssueService = mock(QualityIssueService.class);
        QualityIssueDetailView detail = new QualityIssueDetailView();
        when(qualityIssueService.get(Long.valueOf(91L))).thenReturn(detail);
        AssistantStudioToolExecutionService service = service(qualityIssueService);

        Map<String, Object> result = service.execute(request("studio.feature.get", params(
                "path", "/quality-metrics",
                "id", 91L,
                "resource", "issues")));

        assertThat(result.get("data")).isSameAs(detail);
        verify(qualityIssueService).get(Long.valueOf(91L));
    }

    @Test
    void executeShouldRouteConfirmedQualityIssueStatusActionThroughBackendTool() {
        QualityIssueService qualityIssueService = mock(QualityIssueService.class);
        QualityIssueDetailView detail = new QualityIssueDetailView();
        when(qualityIssueService.updateStatus(org.mockito.ArgumentMatchers.eq(Long.valueOf(91L)),
                org.mockito.ArgumentMatchers.any(QualityIssueStatusRequest.class))).thenReturn(detail);
        AssistantStudioToolExecutionService service = service(qualityIssueService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/quality-metrics",
                "resource", "issues",
                "action", "updateIssueStatus",
                "id", 91L,
                "payload", params(
                        "status", "RESOLVED",
                        "comment", "confirmed by assistant"),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(detail);
        assertThat(result.get("mutation")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        verify(qualityIssueService).updateStatus(org.mockito.ArgumentMatchers.eq(Long.valueOf(91L)),
                org.mockito.ArgumentMatchers.argThat(request ->
                        "RESOLVED".equals(request.getStatus())
                                && "confirmed by assistant".equals(request.getComment())));
    }

    @Test
    void executeShouldRouteDataServiceWebServicePreviewThroughBackendReadOnlyTool() {
        DataServiceService dataServiceService = mock(DataServiceService.class);
        WebServicePreviewView preview = new WebServicePreviewView();
        when(dataServiceService.previewWebService(Long.valueOf(11L))).thenReturn(preview);
        AssistantStudioToolExecutionService service = service(dataServiceService);

        Map<String, Object> result = service.execute(request("studio.feature.get", params(
                "path", "/data-services",
                "id", 11L,
                "view", "webservice-preview")));

        assertThat(result.get("data")).isSameAs(preview);
        verify(dataServiceService).previewWebService(Long.valueOf(11L));
    }

    @Test
    void executeShouldRouteDataServiceSubscriptionsThroughBackendReadOnlyTool() {
        DataServiceService dataServiceService = mock(DataServiceService.class);
        when(dataServiceService.listSubscriptions(Long.valueOf(11L))).thenReturn(Collections.emptyList());
        AssistantStudioToolExecutionService service = service(dataServiceService);

        Map<String, Object> result = service.execute(request("studio.feature.get", params(
                "path", "/data-services",
                "id", 11L,
                "view", "subscriptions")));

        assertThat(result.get("data")).isEqualTo(Collections.emptyList());
        verify(dataServiceService).listSubscriptions(Long.valueOf(11L));
    }

    @Test
    void executeShouldRouteDataServiceResolveFieldsThroughBackendActionToolWithoutConfirmation() {
        DataServiceService dataServiceService = mock(DataServiceService.class);
        DataServiceResolveFieldsView view = new DataServiceResolveFieldsView();
        when(dataServiceService.resolveFields(org.mockito.ArgumentMatchers.any(DataServiceResolveFieldsRequest.class))).thenReturn(view);
        AssistantStudioToolExecutionService service = service(dataServiceService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/data-services",
                "action", "resolveFields",
                "payload", params(
                        "sourceType", "SQL",
                        "datasourceId", 71L,
                        "customSql", "select id, name from customer"))));

        assertThat(result.get("data")).isSameAs(view);
        assertThat(result.get("mutation")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        verify(dataServiceService).resolveFields(org.mockito.ArgumentMatchers.argThat(request ->
                DataServiceSourceType.SQL.equals(request.getSourceType())
                        && Long.valueOf(71L).equals(request.getDatasourceId())
                        && "select id, name from customer".equals(request.getCustomSql())));
    }

    @Test
    void executeShouldRouteDataIngestionWebServicePreviewThroughBackendReadOnlyTool() {
        DataIngestionService dataIngestionService = mock(DataIngestionService.class);
        WebServicePreviewView preview = new WebServicePreviewView();
        when(dataIngestionService.previewWebService(Long.valueOf(12L))).thenReturn(preview);
        AssistantStudioToolExecutionService service = service(dataIngestionService);

        Map<String, Object> result = service.execute(request("studio.feature.get", params(
                "path", "/data-ingestion-services",
                "id", 12L,
                "view", "preview")));

        assertThat(result.get("data")).isSameAs(preview);
        verify(dataIngestionService).previewWebService(Long.valueOf(12L));
    }

    @Test
    void executeShouldRouteDataIngestionSubscriptionsThroughBackendReadOnlyTool() {
        DataIngestionService dataIngestionService = mock(DataIngestionService.class);
        when(dataIngestionService.listSubscriptions(Long.valueOf(12L))).thenReturn(Collections.emptyList());
        AssistantStudioToolExecutionService service = service(dataIngestionService);

        Map<String, Object> result = service.execute(request("studio.feature.get", params(
                "path", "/data-ingestion-services",
                "id", 12L,
                "view", "subscriptions")));

        assertThat(result.get("data")).isEqualTo(Collections.emptyList());
        verify(dataIngestionService).listSubscriptions(Long.valueOf(12L));
    }

    @Test
    void executeShouldRouteDataIngestionResolveFieldsThroughBackendActionToolWithoutConfirmation() {
        DataIngestionService dataIngestionService = mock(DataIngestionService.class);
        DataIngestionResolveFieldsView view = new DataIngestionResolveFieldsView();
        when(dataIngestionService.resolveFields(org.mockito.ArgumentMatchers.any(DataIngestionResolveFieldsRequest.class))).thenReturn(view);
        AssistantStudioToolExecutionService service = service(dataIngestionService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/data-ingestion-services",
                "action", "resolveFields",
                "payload", params(
                        "datasourceId", 72L,
                        "modelId", 7201L))));

        assertThat(result.get("data")).isSameAs(view);
        assertThat(result.get("mutation")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        verify(dataIngestionService).resolveFields(org.mockito.ArgumentMatchers.argThat(request ->
                Long.valueOf(72L).equals(request.getDatasourceId())
                        && Long.valueOf(7201L).equals(request.getModelId())));
    }

    @Test
    void executeShouldRouteProtocolConversionSubscriptionsThroughBackendReadOnlyTool() {
        ProtocolConversionService protocolConversionService = mock(ProtocolConversionService.class);
        when(protocolConversionService.listSubscriptions(Long.valueOf(13L))).thenReturn(Collections.emptyList());
        AssistantStudioToolExecutionService service = service(protocolConversionService);

        Map<String, Object> result = service.execute(request("studio.feature.get", params(
                "path", "/protocol-conversions",
                "id", 13L,
                "view", "subscriptions")));

        assertThat(result.get("data")).isEqualTo(Collections.emptyList());
        verify(protocolConversionService).listSubscriptions(Long.valueOf(13L));
    }

    @Test
    void executeShouldRequireConfirmationBeforeMutationFeatureAction() {
        DataServiceService dataServiceService = mock(DataServiceService.class);
        AssistantStudioToolExecutionService service = service(dataServiceService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/data-services",
                "id", 11L,
                "action", "publish")));

        assertThat(result.get("schema")).isEqualTo("studio.tool-result.v1");
        assertThat(result.get("interfaceCode")).isEqualTo("studio.feature.action");
        assertThat(result.get("path")).isEqualTo("/data-services");
        assertThat(result.get("action")).isEqualTo("publish");
        assertThat(result.get("mutation")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("data")).isInstanceOf(Map.class);
        verifyNoInteractions(dataServiceService);
    }

    @Test
    void executeShouldRouteConfirmedDataServicePublishActionThroughBackendTool() {
        DataServiceService dataServiceService = mock(DataServiceService.class);
        DataServiceDefinitionView view = new DataServiceDefinitionView();
        when(dataServiceService.publish(Long.valueOf(11L))).thenReturn(view);
        AssistantStudioToolExecutionService service = service(dataServiceService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/data-services",
                "id", 11L,
                "action", "publish",
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(view);
        assertThat(result.get("mutation")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        verify(dataServiceService).publish(Long.valueOf(11L));
    }

    @Test
    void executeShouldRouteConfirmedDatasourceSaveActionThroughBackendTool() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataSourceDefinition definition = new DataSourceDefinition();
        when(dataSourceService.save(org.mockito.ArgumentMatchers.any(DataSourceSaveRequest.class))).thenReturn(definition);
        AssistantStudioToolExecutionService service = service(dataSourceService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/datasources",
                "action", "save",
                "payload", params(
                        "name", "assistant_mysql",
                        "typeCode", "mysql8",
                        "enabled", Boolean.TRUE),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(definition);
        verify(dataSourceService).save(org.mockito.ArgumentMatchers.argThat(request ->
                "assistant_mysql".equals(request.getName())
                        && "mysql8".equals(request.getTypeCode())
                        && Boolean.TRUE.equals(request.getEnabled())));
    }

    @Test
    void executeShouldRouteConfirmedModelSaveActionThroughBackendTool() {
        DataModelService dataModelService = mock(DataModelService.class);
        DataModelDefinition savedDefinition = new DataModelDefinition();
        DataModelDefinition maskedDefinition = new DataModelDefinition();
        when(dataModelService.save(org.mockito.ArgumentMatchers.any(DataModelSaveRequest.class))).thenReturn(savedDefinition);
        when(dataModelService.maskSensitiveReaderOptions(savedDefinition)).thenReturn(maskedDefinition);
        AssistantStudioToolExecutionService service = service(dataModelService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/models",
                "action", "save",
                "payload", params(
                        "datasourceId", 71L,
                        "name", "ods_order",
                        "physicalLocator", "ods_order"),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(maskedDefinition);
        verify(dataModelService).save(org.mockito.ArgumentMatchers.argThat(request ->
                Long.valueOf(71L).equals(request.getDatasourceId())
                        && "ods_order".equals(request.getName())
                        && "ods_order".equals(request.getPhysicalLocator())));
        verify(dataModelService).maskSensitiveReaderOptions(savedDefinition);
    }

    @Test
    void executeShouldReturnMaskedModelsForConfirmedModelSyncAction() {
        DataModelService dataModelService = mock(DataModelService.class);
        List<DataModelDefinition> syncedDefinitions = Collections.singletonList(new DataModelDefinition());
        List<DataModelDefinition> maskedDefinitions = Collections.singletonList(new DataModelDefinition());
        when(dataModelService.syncFromDatasource(Long.valueOf(71L))).thenReturn(syncedDefinitions);
        when(dataModelService.maskSensitiveReaderOptions(syncedDefinitions)).thenReturn(maskedDefinitions);
        AssistantStudioToolExecutionService service = service(dataModelService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/models",
                "datasourceId", 71L,
                "action", "sync",
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(maskedDefinitions);
        verify(dataModelService).syncFromDatasource(Long.valueOf(71L));
        verify(dataModelService).maskSensitiveReaderOptions(syncedDefinitions);
    }

    @Test
    void executeShouldRouteConfirmedModelSyncSelectedActionThroughBackendTool() {
        DataModelService dataModelService = mock(DataModelService.class);
        DataModelDefinition definition = new DataModelDefinition();
        List<DataModelDefinition> syncedDefinitions = Collections.singletonList(definition);
        List<DataModelDefinition> maskedDefinitions = Collections.singletonList(new DataModelDefinition());
        when(dataModelService.syncFromDatasource(org.mockito.ArgumentMatchers.eq(Long.valueOf(71L)),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(syncedDefinitions);
        when(dataModelService.maskSensitiveReaderOptions(syncedDefinitions)).thenReturn(maskedDefinitions);
        AssistantStudioToolExecutionService service = service(dataModelService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/models",
                "datasourceId", 71L,
                "action", "syncSelected",
                "physicalLocators", Collections.singletonList("ods_order"),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(maskedDefinitions);
        verify(dataModelService).syncFromDatasource(org.mockito.ArgumentMatchers.eq(Long.valueOf(71L)),
                org.mockito.ArgumentMatchers.argThat(locators ->
                        locators.size() == 1 && "ods_order".equals(locators.get(0))));
        verify(dataModelService).maskSensitiveReaderOptions(syncedDefinitions);
    }

    @Test
    void executeShouldRejectDatasourceDiscoverWithUndeclaredDatasourceId() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        AssistantStudioToolExecutionService service = service(dataSourceService);

        assertThatThrownBy(() -> service.execute(request("studio.feature.action", params(
                "path", "/datasources",
                "action", "discover",
                "datasourceId", 71L,
                "confirmed", Boolean.TRUE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not declare parameter datasourceId");

        verifyNoInteractions(dataSourceService);
    }

    @Test
    void executeShouldRejectDatasourceDiscoverWithUndeclaredNameFilter() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        AssistantStudioToolExecutionService service = service(dataSourceService);

        assertThatThrownBy(() -> service.execute(request("studio.feature.action", params(
                "path", "/datasources",
                "action", "discover",
                "id", 71L,
                "name", "orders",
                "confirmed", Boolean.TRUE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not declare parameter name");

        verifyNoInteractions(dataSourceService);
    }

    @Test
    void executeShouldRejectDatasourceSaveWithUndeclaredDataPayloadAlias() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        AssistantStudioToolExecutionService service = service(dataSourceService);

        assertThatThrownBy(() -> service.execute(request("studio.feature.action", params(
                "path", "/datasources",
                "action", "save",
                "data", params("name", "assistant_mysql"),
                "confirmed", Boolean.TRUE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not declare parameter data");

        verifyNoInteractions(dataSourceService);
    }

    @Test
    void executeShouldRejectModelSyncSelectedWithoutCanonicalPhysicalLocators() {
        DataModelService dataModelService = mock(DataModelService.class);
        AssistantStudioToolExecutionService service = service(dataModelService);

        assertThatThrownBy(() -> service.execute(request("studio.feature.action", params(
                "path", "/models",
                "datasourceId", 71L,
                "action", "syncSelected",
                "locators", Collections.singletonList("ods_order"),
                "confirmed", Boolean.TRUE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not declare parameter locators");

        verifyNoInteractions(dataModelService);
    }

    @Test
    void executeShouldRejectModelSyncWithUndeclaredGenericIdBeforeConfirmation() {
        DataModelService dataModelService = mock(DataModelService.class);
        AssistantStudioToolExecutionService service = service(dataModelService);

        assertThatThrownBy(() -> service.execute(request("studio.feature.action", params(
                "path", "/models",
                "id", 71L,
                "action", "sync"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not declare parameter id");

        verifyNoInteractions(dataModelService);
    }

    @Test
    void executeShouldRejectModelSyncWithOperationAliasBeforeConfirmation() {
        DataModelService dataModelService = mock(DataModelService.class);
        AssistantStudioToolExecutionService service = service(dataModelService);

        assertThatThrownBy(() -> service.execute(request("studio.feature.action", params(
                "path", "/models",
                "datasourceId", 71L,
                "operation", "sync"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistant action is required");

        verifyNoInteractions(dataModelService);
    }

    @Test
    void executeShouldRouteConfirmedFieldMappingRuleSaveActionThroughBackendTool() {
        FieldMappingRuleService fieldMappingRuleService = mock(FieldMappingRuleService.class);
        FieldMappingRuleView view = new FieldMappingRuleView();
        when(fieldMappingRuleService.save(org.mockito.ArgumentMatchers.any(FieldMappingRuleSaveRequest.class))).thenReturn(view);
        AssistantStudioToolExecutionService service = service(fieldMappingRuleService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/field-mapping-rules",
                "action", "save",
                "payload", params(
                        "mappingName", "Trim text",
                        "mappingType", "TRANSFORMER",
                        "mappingCode", "trimText"),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(view);
        verify(fieldMappingRuleService).save(org.mockito.ArgumentMatchers.argThat(request ->
                "Trim text".equals(request.getMappingName())
                        && "TRANSFORMER".equals(request.getMappingType())
                        && "trimText".equals(request.getMappingCode())));
    }

    @Test
    void executeShouldRequireConfirmationBeforeMetadataPublishAction() {
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        AssistantStudioToolExecutionService service = service(metadataSchemaService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/metadata",
                "id", 7L,
                "action", "publish")));

        assertThat(result.get("path")).isEqualTo("/metadata");
        assertThat(result.get("action")).isEqualTo("publish");
        assertThat(result.get("mutation")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.TRUE);
        verifyNoInteractions(metadataSchemaService);
    }

    @Test
    void executeShouldRouteConfirmedMetadataSaveDraftActionThroughBackendTool() {
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        MetadataSchemaDefinition schema = new MetadataSchemaDefinition();
        schema.setSchemaCode("business:customer");
        when(metadataSchemaService.saveDraft(org.mockito.ArgumentMatchers.any(MetadataSchemaSaveRequest.class))).thenReturn(schema);
        AssistantStudioToolExecutionService service = service(metadataSchemaService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/metadata",
                "action", "saveDraft",
                "payload", params(
                        "schemaCode", "business:customer",
                        "schemaName", "Customer business metadata",
                        "objectType", "model",
                        "typeCode", "customer"),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(schema);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        verify(metadataSchemaService).saveDraft(org.mockito.ArgumentMatchers.argThat(request ->
                "business:customer".equals(request.getSchemaCode())
                        && "Customer business metadata".equals(request.getSchemaName())
                        && "model".equals(request.getObjectType())
                        && "customer".equals(request.getTypeCode())));
    }

    @Test
    void executeShouldRouteConfirmedMetadataRuntimeOptionSyncThroughBackendTool() {
        StandardRuntimeOptionSchemaBootstrapService runtimeOptionSchemaBootstrapService =
                mock(StandardRuntimeOptionSchemaBootstrapService.class);
        MetadataSchemaDefinition schema = new MetadataSchemaDefinition();
        schema.setSchemaCode("runtime:mysql8:reader");
        when(runtimeOptionSchemaBootstrapService.syncStandardRuntimeOptionSchemas()).thenReturn(Collections.singletonList(schema));
        AssistantStudioToolExecutionService service = service(runtimeOptionSchemaBootstrapService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/metadata",
                "action", "syncStandardRuntimeOptions",
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isEqualTo(Collections.singletonList(schema));
        verify(runtimeOptionSchemaBootstrapService).syncStandardRuntimeOptionSchemas();
    }

    @Test
    void executeShouldRouteQualityRuleParseActionThroughBackendToolWithoutConfirmation() {
        QualityRuleService qualityRuleService = mock(QualityRuleService.class);
        QualityRuleParseResultView parseResult = new QualityRuleParseResultView();
        when(qualityRuleService.parse(org.mockito.ArgumentMatchers.any(QualityRuleParseRequest.class))).thenReturn(parseResult);
        AssistantStudioToolExecutionService service = service(qualityRuleService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/quality-rules",
                "action", "parse",
                "payload", params(
                        "granularity", "TABLE",
                        "logicSql", "select count(*) as total_count from ${table}"))));

        assertThat(result.get("data")).isSameAs(parseResult);
        assertThat(result.get("mutation")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        verify(qualityRuleService).parse(org.mockito.ArgumentMatchers.argThat(request ->
                request.getGranularity() != null
                        && "select count(*) as total_count from ${table}".equals(request.getLogicSql())));
    }

    @Test
    void executeShouldRouteQualityRuleValidateActionThroughBackendToolWithoutConfirmation() {
        QualityRuleService qualityRuleService = mock(QualityRuleService.class);
        QualityRuleValidationResultView validation = new QualityRuleValidationResultView();
        when(qualityRuleService.validate(org.mockito.ArgumentMatchers.any(QualityRuleValidateRequest.class))).thenReturn(validation);
        AssistantStudioToolExecutionService service = service(qualityRuleService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/quality-rules",
                "action", "validate",
                "payload", params(
                        "granularity", "COLUMN",
                        "logicSql", "select count(*) as null_count from ${table}"))));

        assertThat(result.get("data")).isSameAs(validation);
        assertThat(result.get("mutation")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        verify(qualityRuleService).validate(org.mockito.ArgumentMatchers.argThat(request ->
                request.getGranularity() != null
                        && "select count(*) as null_count from ${table}".equals(request.getLogicSql())));
    }

    @Test
    void executeShouldRouteConfirmedQualityRuleEnableActionThroughBackendTool() {
        QualityRuleService qualityRuleService = mock(QualityRuleService.class);
        QualityRuleView view = new QualityRuleView();
        when(qualityRuleService.enable(Long.valueOf(61L))).thenReturn(view);
        AssistantStudioToolExecutionService service = service(qualityRuleService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/quality-rules",
                "id", 61L,
                "action", "enable",
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(view);
        verify(qualityRuleService).enable(Long.valueOf(61L));
    }

    @Test
    void executeShouldRouteConfirmedScriptEnvironmentRefreshActionThroughBackendTool() {
        ScriptEnvironmentService scriptEnvironmentService = mock(ScriptEnvironmentService.class);
        ScriptEnvironmentView view = new ScriptEnvironmentView();
        when(scriptEnvironmentService.refresh(Long.valueOf(81L))).thenReturn(view);
        AssistantStudioToolExecutionService service = service(scriptEnvironmentService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/script-environments",
                "id", 81L,
                "action", "refresh",
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(view);
        verify(scriptEnvironmentService).refresh(Long.valueOf(81L));
    }

    @Test
    void executeShouldRouteConfirmedScriptEnvironmentSaveActionThroughBackendTool() {
        ScriptEnvironmentService scriptEnvironmentService = mock(ScriptEnvironmentService.class);
        ScriptEnvironmentView view = new ScriptEnvironmentView();
        when(scriptEnvironmentService.saveOrUpdateCheck(org.mockito.ArgumentMatchers.any(ScriptEnvironmentSaveRequest.class))).thenReturn(view);
        AssistantStudioToolExecutionService service = service(scriptEnvironmentService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/script-environments",
                "action", "save",
                "payload", params(
                        "environmentName", "Assistant Python",
                        "environmentCode", "assistant-python",
                        "enabled", Boolean.TRUE),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(view);
        verify(scriptEnvironmentService).saveOrUpdateCheck(org.mockito.ArgumentMatchers.argThat(request ->
                "Assistant Python".equals(request.getEnvironmentName())
                        && "assistant-python".equals(request.getEnvironmentCode())
                        && Boolean.TRUE.equals(request.getEnabled())));
    }

    @Test
    void executeShouldRouteConfirmedCollectionTaskTriggerActionThroughBackendTool() {
        DispatchService dispatchService = mock(DispatchService.class);
        AssistantStudioToolExecutionService service = service(dispatchService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/collection-tasks",
                "id", 31L,
                "action", "trigger",
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("mutation")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("data")).isNull();
        verify(dispatchService).triggerCollectionTask(Long.valueOf(31L));
    }

    @Test
    void executeShouldRouteConfirmedCollectionTaskScheduleActionThroughBackendTool() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        CollectionTaskDefinitionView view = new CollectionTaskDefinitionView();
        when(collectionTaskService.updateSchedule(org.mockito.ArgumentMatchers.eq(Long.valueOf(31L)),
                org.mockito.ArgumentMatchers.any(CollectionTaskScheduleDefinition.class))).thenReturn(view);
        AssistantStudioToolExecutionService service = service(collectionTaskService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/collection-tasks",
                "id", 31L,
                "action", "schedule",
                "payload", params(
                        "cronExpression", "0 0/5 * * * ?",
                        "enabled", Boolean.TRUE,
                        "timezone", "Asia/Shanghai"),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(view);
        verify(collectionTaskService).updateSchedule(org.mockito.ArgumentMatchers.eq(Long.valueOf(31L)),
                org.mockito.ArgumentMatchers.argThat(schedule ->
                        "0 0/5 * * * ?".equals(schedule.getCronExpression())
                                && Boolean.TRUE.equals(schedule.getEnabled())
                                && "Asia/Shanghai".equals(schedule.getTimezone())));
    }

    @Test
    void executeShouldRouteConfirmedWorkflowTriggerActionThroughBackendTool() {
        DispatchService dispatchService = mock(DispatchService.class);
        AssistantStudioToolExecutionService service = service(dispatchService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/workflows",
                "id", 41L,
                "action", "trigger",
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isNull();
        verify(dispatchService).triggerManualRun(Long.valueOf(41L));
    }

    @Test
    void executeShouldRouteConfirmedWorkflowScheduleActionThroughBackendTool() {
        WorkflowService workflowService = mock(WorkflowService.class);
        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(Long.valueOf(41L));
        workflow.setCode("wf_order");
        workflow.setName("Order workflow");
        WorkflowDefinitionView saved = new WorkflowDefinitionView();
        when(workflowService.get(Long.valueOf(41L))).thenReturn(workflow);
        when(workflowService.save(org.mockito.ArgumentMatchers.any(WorkflowSaveRequest.class))).thenReturn(saved);
        AssistantStudioToolExecutionService service = service(workflowService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/workflows",
                "id", 41L,
                "action", "schedule",
                "payload", params(
                        "cronExpression", "0 15 2 * * ?",
                        "enabled", Boolean.FALSE,
                        "timezone", "UTC"),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(saved);
        verify(workflowService).get(Long.valueOf(41L));
        verify(workflowService).save(org.mockito.ArgumentMatchers.argThat(request ->
                Long.valueOf(41L).equals(request.getDefinitionId())
                        && "wf_order".equals(request.getCode())
                        && "Order workflow".equals(request.getName())
                        && request.getNodes() != null
                        && request.getEdges() != null
                        && request.getSchedule() != null
                        && "0 15 2 * * ?".equals(request.getSchedule().getCronExpression())
                        && Boolean.FALSE.equals(request.getSchedule().getEnabled())
                        && "UTC".equals(request.getSchedule().getTimezone())));
    }

    @Test
    void executeShouldRouteConfirmedQualityTaskTriggerActionThroughBackendTool() {
        DispatchService dispatchService = mock(DispatchService.class);
        AssistantStudioToolExecutionService service = service(dispatchService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/quality-tasks",
                "id", 51L,
                "action", "trigger",
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isNull();
        verify(dispatchService).triggerQualityTask(Long.valueOf(51L));
    }

    @Test
    void executeShouldRouteConfirmedQualityTaskScheduleActionThroughBackendTool() {
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        QualityTaskDefinitionView view = new QualityTaskDefinitionView();
        when(qualityTaskService.updateSchedule(org.mockito.ArgumentMatchers.eq(Long.valueOf(51L)),
                org.mockito.ArgumentMatchers.any(CollectionTaskScheduleDefinition.class))).thenReturn(view);
        AssistantStudioToolExecutionService service = service(qualityTaskService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/quality-tasks",
                "id", 51L,
                "action", "schedule",
                "payload", params(
                        "cronExpression", "0 30 1 * * ?",
                        "enabled", Boolean.TRUE,
                        "timezone", "Asia/Shanghai"),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(view);
        verify(qualityTaskService).updateSchedule(org.mockito.ArgumentMatchers.eq(Long.valueOf(51L)),
                org.mockito.ArgumentMatchers.argThat(schedule ->
                        "0 30 1 * * ?".equals(schedule.getCronExpression())
                                && Boolean.TRUE.equals(schedule.getEnabled())
                                && "Asia/Shanghai".equals(schedule.getTimezone())));
    }

    @Test
    void executeShouldRouteConfirmedDataDevelopmentSqlActionThroughBackendTool() {
        DataDevelopmentService dataDevelopmentService = mock(DataDevelopmentService.class);
        SqlExecutionResultView sqlResult = new SqlExecutionResultView();
        when(dataDevelopmentService.execute(org.mockito.ArgumentMatchers.<SqlExecutionRequest>any())).thenReturn(sqlResult);
        AssistantStudioToolExecutionService service = service(dataDevelopmentService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/data-development",
                "resource", "sql",
                "action", "executeSql",
                "payload", params(
                        "datasourceId", 3L,
                        "scriptType", "SQL",
                        "content", "select 1",
                        "maxRows", 10),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(sqlResult);
        verify(dataDevelopmentService).execute(org.mockito.ArgumentMatchers.<SqlExecutionRequest>argThat(request ->
                Long.valueOf(3L).equals(request.getDatasourceId())
                        && ScriptType.SQL.equals(request.getScriptType())
                        && "select 1".equals(request.getContent())
                        && Integer.valueOf(10).equals(request.getMaxRows())));
    }

    @Test
    void executeShouldRouteConfirmedDataDevelopmentSaveScriptActionThroughBackendTool() {
        DataDevelopmentService dataDevelopmentService = mock(DataDevelopmentService.class);
        DataDevelopmentScriptView script = new DataDevelopmentScriptView();
        when(dataDevelopmentService.saveScript(org.mockito.ArgumentMatchers.any(DataDevelopmentScriptSaveRequest.class))).thenReturn(script);
        AssistantStudioToolExecutionService service = service(dataDevelopmentService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/data-development",
                "resource", "scripts",
                "action", "saveScript",
                "payload", params(
                        "fileName", "assistant_job.py",
                        "scriptType", "PYTHON",
                        "content", "print('ok')"),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(script);
        verify(dataDevelopmentService).saveScript(org.mockito.ArgumentMatchers.argThat(request ->
                "assistant_job.py".equals(request.getFileName())
                        && ScriptType.PYTHON.equals(request.getScriptType())
                        && "print('ok')".equals(request.getContent())));
    }

    @Test
    void executeShouldRouteConfirmedDataDevelopmentSavedScriptActionThroughBackendTool() {
        DataDevelopmentService dataDevelopmentService = mock(DataDevelopmentService.class);
        DataScriptExecutionResultView scriptResult = new DataScriptExecutionResultView();
        when(dataDevelopmentService.executeSavedScript(org.mockito.ArgumentMatchers.eq(Long.valueOf(21L)),
                org.mockito.ArgumentMatchers.any(SavedDataScriptExecutionRequest.class))).thenReturn(scriptResult);
        AssistantStudioToolExecutionService service = service(dataDevelopmentService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/data-development",
                "resource", "scripts",
                "action", "executeSavedScript",
                "id", 21L,
                "payload", params("maxRows", 50, "waitTimeoutSeconds", 3),
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(scriptResult);
        verify(dataDevelopmentService).executeSavedScript(org.mockito.ArgumentMatchers.eq(Long.valueOf(21L)),
                org.mockito.ArgumentMatchers.argThat(request ->
                        Integer.valueOf(50).equals(request.getMaxRows())
                                && Integer.valueOf(3).equals(request.getWaitTimeoutSeconds())));
    }

    @Test
    void executeShouldRouteConfirmedDataServiceCreateSubscriptionActionThroughBackendTool() {
        DataServiceService dataServiceService = mock(DataServiceService.class);
        DataServiceSubscriptionView subscription = new DataServiceSubscriptionView();
        when(dataServiceService.createSubscription(org.mockito.ArgumentMatchers.eq(Long.valueOf(11L)),
                org.mockito.ArgumentMatchers.any(DataServiceSubscriptionCreateRequest.class))).thenReturn(subscription);
        AssistantStudioToolExecutionService service = service(dataServiceService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/data-services",
                "id", 11L,
                "resource", "subscriptions",
                "action", "createSubscription",
                "subscriptionName", "assistant-client",
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(subscription);
        verify(dataServiceService).createSubscription(org.mockito.ArgumentMatchers.eq(Long.valueOf(11L)),
                org.mockito.ArgumentMatchers.argThat(request -> "assistant-client".equals(request.getSubscriptionName())));
    }

    @Test
    void executeShouldRouteConfirmedDataIngestionDisableSubscriptionActionThroughBackendTool() {
        DataIngestionService dataIngestionService = mock(DataIngestionService.class);
        when(dataIngestionService.disableSubscription(Long.valueOf(12L), Long.valueOf(1201L))).thenReturn(null);
        AssistantStudioToolExecutionService service = service(dataIngestionService);

        service.execute(request("studio.feature.action", params(
                "path", "/data-ingestion-services",
                "id", 12L,
                "resource", "subscriptions",
                "action", "disableSubscription",
                "subscriptionId", 1201L,
                "confirmed", Boolean.TRUE)));

        verify(dataIngestionService).disableSubscription(Long.valueOf(12L), Long.valueOf(1201L));
    }

    @Test
    void executeShouldRouteConfirmedProtocolConversionRotateSubscriptionActionThroughBackendTool() {
        ProtocolConversionService protocolConversionService = mock(ProtocolConversionService.class);
        ProtocolConversionSubscriptionView subscription = new ProtocolConversionSubscriptionView();
        when(protocolConversionService.rotateSubscription(Long.valueOf(13L), Long.valueOf(1301L))).thenReturn(subscription);
        AssistantStudioToolExecutionService service = service(protocolConversionService);

        Map<String, Object> result = service.execute(request("studio.feature.action", params(
                "path", "/protocol-conversions",
                "id", 13L,
                "resource", "subscriptions",
                "action", "rotateSubscription",
                "subscriptionId", 1301L,
                "confirmed", Boolean.TRUE)));

        assertThat(result.get("data")).isSameAs(subscription);
        verify(protocolConversionService).rotateSubscription(Long.valueOf(13L), Long.valueOf(1301L));
    }

    @Test
    void executeShouldRejectUnregisteredFeatureAction() {
        AssistantStudioToolExecutionService service = service();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.execute(request("studio.feature.action", params(
                        "path", "/data-services",
                        "id", 11L,
                        "action", "delete",
                        "confirmed", Boolean.TRUE))));
    }

    @Test
    void executeShouldRouteAssistantScriptThroughRegisteredBackendExecutor() {
        AssistantScriptSkillExecutionService scriptSkillExecutionService = mock(AssistantScriptSkillExecutionService.class);
        Map<String, Object> scriptResult = params(
                "schema", "studio.script-result.v1",
                "success", Boolean.TRUE,
                "entrypointId", "field-mapping-suggester",
                "data", params("summary", params("mappedCount", 2, "unresolvedCount", 1)));
        Map<String, Object> scriptParams = params(
                "entrypointId", "field-mapping-suggester",
                "input", params(
                        "sourceFields", java.util.Arrays.asList("id", "order_no"),
                        "targetFields", java.util.Arrays.asList("ID", "orderNo", "missing_col")));
        when(scriptSkillExecutionService.execute(scriptParams)).thenReturn(scriptResult);
        AssistantStudioToolExecutionService service = service(scriptSkillExecutionService);

        Map<String, Object> result = service.execute(request("assistant.script.execute", scriptParams));

        assertThat(result.get("schema")).isEqualTo("studio.tool-result.v1");
        assertThat(result.get("interfaceCode")).isEqualTo("assistant.script.execute");
        assertThat(result.get("executedBy")).isEqualTo("backend");
        assertThat(result.get("mutation")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("requiresConfirmation")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("data")).isSameAs(scriptResult);
        assertThat(result.get("params")).isEqualTo(scriptParams);
        assertThat(result.get("effectiveParams")).isEqualTo(scriptParams);
        assertThat(resultStringList(result, "defaultedParams")).isEmpty();
        verify(scriptSkillExecutionService).execute(scriptParams);
    }

    @Test
    void executeShouldRejectAssistantScriptWithoutCanonicalEntrypointId() {
        AssistantScriptSkillExecutionService scriptSkillExecutionService = mock(AssistantScriptSkillExecutionService.class);
        AssistantStudioToolExecutionService service = service(scriptSkillExecutionService);

        assertThatThrownBy(() -> service.execute(request("assistant.script.execute", params(
                "scriptId", "field-mapping-suggester",
                "input", params(
                        "sourceFields", java.util.Arrays.asList("id"),
                        "targetFields", java.util.Arrays.asList("ID"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entrypointId");

        verifyNoInteractions(scriptSkillExecutionService);
    }

    @Test
    void executeShouldRejectAssistantScriptWithoutCanonicalInput() {
        AssistantScriptSkillExecutionService scriptSkillExecutionService = mock(AssistantScriptSkillExecutionService.class);
        AssistantStudioToolExecutionService service = service(scriptSkillExecutionService);

        assertThatThrownBy(() -> service.execute(request("assistant.script.execute", params(
                "entrypointId", "field-mapping-suggester",
                "payload", params(
                        "sourceFields", java.util.Arrays.asList("id"),
                        "targetFields", java.util.Arrays.asList("ID"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input");

        verifyNoInteractions(scriptSkillExecutionService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultMap(Map<String, Object> result, String key) {
        return (Map<String, Object>) result.get(key);
    }

    @SuppressWarnings("unchecked")
    private List<String> resultStringList(Map<String, Object> result, String key) {
        return (List<String>) result.get(key);
    }

    private AssistantStudioToolExecutionService service(Object... overrides) {
        AssistantScriptSkillExecutionService scriptSkillExecutionService = override(AssistantScriptSkillExecutionService.class, overrides);
        DataSourceService dataSourceService = override(DataSourceService.class, overrides);
        DataDevelopmentService dataDevelopmentService = override(DataDevelopmentService.class, overrides);
        MetadataSchemaService metadataSchemaService = override(MetadataSchemaService.class, overrides);
        StandardRuntimeOptionSchemaBootstrapService runtimeOptionSchemaBootstrapService =
                override(StandardRuntimeOptionSchemaBootstrapService.class, overrides);
        PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService =
                override(PluginRuntimeOptionSchemaService.class, overrides);
        DataModelService dataModelService = override(DataModelService.class, overrides);
        DataModelLineageService dataModelLineageService = override(DataModelLineageService.class, overrides);
        DataModelStatisticsService dataModelStatisticsService = override(DataModelStatisticsService.class, overrides);
        DataModelStatisticsWorkspaceService dataModelStatisticsWorkspaceService =
                override(DataModelStatisticsWorkspaceService.class, overrides);
        FieldMappingRuleService fieldMappingRuleService = override(FieldMappingRuleService.class, overrides);
        CollectionTaskService collectionTaskService = override(CollectionTaskService.class, overrides);
        WorkflowService workflowService = override(WorkflowService.class, overrides);
        RunService runService = override(RunService.class, overrides);
        RunLogProxyService runLogProxyService = override(RunLogProxyService.class, overrides);
        DispatchService dispatchService = override(DispatchService.class, overrides);
        RunMetricsService runMetricsService = override(RunMetricsService.class, overrides);
        DataServiceService dataServiceService = override(DataServiceService.class, overrides);
        DataServiceMetricsService dataServiceMetricsService = override(DataServiceMetricsService.class, overrides);
        DataIngestionService dataIngestionService = override(DataIngestionService.class, overrides);
        DataIngestionMetricsService dataIngestionMetricsService = override(DataIngestionMetricsService.class, overrides);
        ProtocolConversionService protocolConversionService = override(ProtocolConversionService.class, overrides);
        QualityMetricsService qualityMetricsService = override(QualityMetricsService.class, overrides);
        QualityIssueService qualityIssueService = override(QualityIssueService.class, overrides);
        QualityRuleService qualityRuleService = override(QualityRuleService.class, overrides);
        QualityTaskService qualityTaskService = override(QualityTaskService.class, overrides);
        ScriptEnvironmentService scriptEnvironmentService = override(ScriptEnvironmentService.class, overrides);
        SystemManagementService systemManagementService = override(SystemManagementService.class, overrides);
        UserManagementService userManagementService = override(UserManagementService.class, overrides);
        UserRegistrationRequestService userRegistrationRequestService =
                override(UserRegistrationRequestService.class, overrides);
        NotificationService notificationService = override(NotificationService.class, overrides);
        OpsCenterService opsCenterService = override(OpsCenterService.class, overrides);
        return new AssistantStudioToolExecutionService(
                new AssistantStudioOperationRegistry(),
                scriptSkillExecutionService,
                mock(StudioDashboardService.class),
                mock(WorkspaceAccessService.class),
                mock(DatasourceTypeCapabilityService.class),
                dataSourceService,
                dataDevelopmentService,
                metadataSchemaService,
                runtimeOptionSchemaBootstrapService,
                pluginRuntimeOptionSchemaService,
                dataModelService,
                dataModelLineageService,
                dataModelStatisticsService,
                dataModelStatisticsWorkspaceService,
                fieldMappingRuleService,
                collectionTaskService,
                workflowService,
                runService,
                runLogProxyService,
                dispatchService,
                runMetricsService,
                dataServiceService,
                dataServiceMetricsService,
                dataIngestionService,
                dataIngestionMetricsService,
                protocolConversionService,
                qualityMetricsService,
                qualityIssueService,
                qualityRuleService,
                qualityTaskService,
                scriptEnvironmentService,
                systemManagementService,
                userManagementService,
                userRegistrationRequestService,
                notificationService,
                opsCenterService);
    }

    @SuppressWarnings("unchecked")
    private <T> T override(Class<T> type, Object... overrides) {
        for (Object override : overrides) {
            if (type.isInstance(override)) {
                return (T) override;
            }
        }
        return mock(type);
    }

    private DataSourceListView datasource(String name) {
        DataSourceListView view = new DataSourceListView();
        view.setId(Long.valueOf(1L));
        view.setName(name);
        view.setTypeCode("MYSQL");
        view.setEnabled(Boolean.TRUE);
        return view;
    }

    private Map<String, Object> request(String interfaceCode, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("interfaceCode", interfaceCode);
        request.put("params", params);
        return request;
    }

    private Map<String, Object> params(Object... values) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            params.put(String.valueOf(values[index]), values[index + 1]);
        }
        return params;
    }
}
