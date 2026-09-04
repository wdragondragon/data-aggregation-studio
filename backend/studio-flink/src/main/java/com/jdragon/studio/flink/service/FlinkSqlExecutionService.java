package com.jdragon.studio.flink.service;

import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FlinkModelRefView;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.flink.connector.AggregationFlinkRuntimeRegistry;
import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntime;
import com.jdragon.studio.flink.execution.FlinkExecutionClient;
import com.jdragon.studio.flink.execution.FlinkExecutionClientRouter;
import com.jdragon.studio.flink.execution.FlinkExecutionRequest;
import com.jdragon.studio.flink.execution.FlinkExecutionResult;
import com.jdragon.studio.flink.execution.FlinkRuntimeConnectorAccess;
import com.jdragon.studio.flink.execution.FlinkTableDdlBuilder;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.HttpReaderOptionSecurityService;
import com.jdragon.studio.infra.service.ManagedFileService;
import com.jdragon.studio.infra.service.ManagedRuntimeFileResolver;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.RuntimeClusterSelectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.net.URI;

@Service
@ConditionalOnClass(name = "com.jdragon.studio.worker.bootstrap.StudioWorkerApplication")
public class FlinkSqlExecutionService {
    private final DataModelService dataModelService;
    private final DataSourceService dataSourceService;
    private final HttpReaderOptionSecurityService httpReaderOptionSecurityService;
    private final AggregationFlinkRuntimeBuilder runtimeBuilder;
    private final FlinkSqlGuard sqlGuard;
    private final StudioPlatformProperties properties;
    private final FlinkExecutionClientRouter executionClientRouter;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final RuntimeClusterSelectionService runtimeClusterSelectionService;
    private ManagedRuntimeFileResolver managedRuntimeFileResolver;

    public FlinkSqlExecutionService(DataModelService dataModelService,
                                    DataSourceService dataSourceService,
                                    HttpReaderOptionSecurityService httpReaderOptionSecurityService,
                                    AggregationFlinkRuntimeBuilder runtimeBuilder,
                                    FlinkSqlGuard sqlGuard,
                                    StudioPlatformProperties properties,
                                    FlinkExecutionClientRouter executionClientRouter,
                                    ProjectResourceAccessService projectResourceAccessService,
                                    RuntimeClusterSelectionService runtimeClusterSelectionService) {
        this.dataModelService = dataModelService;
        this.dataSourceService = dataSourceService;
        this.httpReaderOptionSecurityService = httpReaderOptionSecurityService;
        this.runtimeBuilder = runtimeBuilder;
        this.sqlGuard = sqlGuard;
        this.properties = properties;
        this.executionClientRouter = executionClientRouter;
        this.projectResourceAccessService = projectResourceAccessService;
        this.runtimeClusterSelectionService = runtimeClusterSelectionService;
    }

    @Autowired(required = false)
    public void setManagedRuntimeFileResolver(ManagedRuntimeFileResolver managedRuntimeFileResolver) {
        this.managedRuntimeFileResolver = managedRuntimeFileResolver;
    }

    public FlinkQuestionResultView execute(FlinkSqlExecuteRequest request) {
        if (!properties.getFlink().isEnabled()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Flink SQL execution is disabled");
        }
        if (request == null || request.getModelIds() == null || request.getModelIds().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "modelIds are required for Flink SQL execution");
        }
        int maxRows = normalizeMaxRows(request.getMaxRows());
        Integer scanMaxRows = normalizeScanMaxRows(request.getScanMaxRows());
        String guardedSql = sqlGuard.guardSelectSql(request.getSql(), maxRows);
        long startedAt = System.currentTimeMillis();
        List<String> runtimeRefs = new ArrayList<String>();
        List<String> createTableDdls = new ArrayList<String>();
        List<FlinkModelRefView> modelRefs = new ArrayList<FlinkModelRefView>();
        List<ManagedRuntimeFileResolver.Resolution<DataSourceDefinition>> managedFileResolutions =
                new ArrayList<ManagedRuntimeFileResolver.Resolution<DataSourceDefinition>>();
        List<Map<Long, Path>> managedFileScopes = new ArrayList<Map<Long, Path>>();
        PluginRuntimeSession capabilityPluginSelection = null;
        try {
            List<DataModelDefinition> models = new ArrayList<DataModelDefinition>();
            List<DataSourceDefinition> datasources = new ArrayList<DataSourceDefinition>();
            List<Long> datasourceIds = new ArrayList<Long>();
            for (Long modelId : request.getModelIds()) {
                DataModelDefinition model = dataModelService.get(modelId);
                DataSourceDefinition datasource = dataSourceService.getInternal(model.getDatasourceId());
                models.add(model);
                datasources.add(datasource);
                datasourceIds.add(datasource.getId());
            }
            runtimeClusterSelectionService.validateExplicitDatasourceSelection(
                    projectResourceAccessService.requireCurrentProjectId(),
                    request.getRuntimeClusterId(),
                    datasourceIds);
            List<AggregationFlinkTableRuntime> runtimes = new ArrayList<AggregationFlinkTableRuntime>();
            List<String> flinkTableNames = new ArrayList<String>();
            String executionMode = normalizedExecutionMode();
            boolean remoteConnector = "gateway".equals(executionMode);
            String runtimeEndpoint = remoteConnector ? requiredRuntimeEndpoint() : null;
            FlinkExecutionClient executionClient = executionClientRouter.select(executionMode);
            for (int i = 0; i < models.size(); i++) {
                DataModelDefinition model = models.get(i);
                DataSourceDefinition datasource = datasources.get(i);
                ManagedRuntimeFileResolver.Resolution<DataSourceDefinition> managedFiles =
                        resolveManagedDatasource(datasource, request, i);
                managedFileResolutions.add(managedFiles);
                DataSourceDefinition resolvedDatasource = managedFiles == null
                        ? datasource : managedFiles.getValue();
                Map<Long, Path> managedFileScope = managedFiles == null
                        ? new LinkedHashMap<Long, Path>()
                        : collectManagedFileScope(datasource.getTechnicalMetadata(),
                        resolvedDatasource.getTechnicalMetadata());
                managedFileScopes.add(managedFileScope);
                if (remoteConnector && !managedFileScope.isEmpty()) {
                    assertSecureManagedFileEndpoint(runtimeEndpoint);
                }
                AggregationFlinkTableRuntime runtime = runtimeBuilder.build(
                        remoteConnector ? datasource : resolvedDatasource, model, scanMaxRows);
                runtimes.add(runtime);
                String flinkTableName = tableNameFor(model);
                flinkTableNames.add(flinkTableName);
                modelRefs.add(toModelRef(datasource, model, flinkTableName));
            }
            boolean streamingMode = runtimes.stream()
                    .anyMatch(runtime -> "unbounded".equalsIgnoreCase(runtime.getScanMode()));
            List<ResolvedPlugin> selectedCapabilityPlugins = new ArrayList<ResolvedPlugin>();
            if (remoteConnector) {
                capabilityPluginSelection = PluginRuntimeSession.createDetached();
                selectedCapabilityPlugins = selectCapabilityPlugins(runtimes, capabilityPluginSelection);
            }
            for (int i = 0; i < runtimes.size(); i++) {
                AggregationFlinkTableRuntime runtime = runtimes.get(i);
                String runtimeRef = remoteConnector
                        ? AggregationFlinkRuntimeRegistry.registerCapability(runtime,
                        properties.getFlink().getRuntimeRegistryTtlSeconds(), selectedCapabilityPlugins.get(i),
                        managedFileScopes.get(i), managedFileResolutions.get(i))
                        : AggregationFlinkRuntimeRegistry.register(runtime,
                        properties.getFlink().getRuntimeRegistryTtlSeconds(),
                        managedFileScopes.get(i), managedFileResolutions.get(i));
                runtimeRefs.add(runtimeRef);
                String flinkTableName = flinkTableNames.get(i);
                FlinkRuntimeConnectorAccess access = remoteConnector
                        ? FlinkRuntimeConnectorAccess.remote(runtimeEndpoint, runtimeRef)
                        : FlinkRuntimeConnectorAccess.local(runtimeRef);
                createTableDdls.add(FlinkTableDdlBuilder.buildCreateTemporaryTableDdl(flinkTableName, runtime, access));
            }
            FlinkExecutionResult result = executionClient.execute(new FlinkExecutionRequest(
                    guardedSql,
                    createTableDdls,
                    streamingMode,
                    maxRows));
            FlinkQuestionResultView view = toView(result);
            view.setSql(guardedSql);
            view.setModelRefs(modelRefs);
            view.setExecutionMs(System.currentTimeMillis() - startedAt);
            view.getSummary().put("modelCount", modelRefs.size());
            view.getSummary().put("executionMode", executionMode);
            view.getSummary().put("runtimeMode", streamingMode ? "streaming" : "batch");
            view.getSummary().put("maxRows", maxRows);
            view.getSummary().put("scanMaxRows", scanMaxRows);
            appendPushdownSummary(view, runtimes, flinkTableNames);
            return view;
        } catch (StudioException ex) {
            String message = redactRuntimeCapabilities(ex.getMessage(), runtimeRefs);
            if (message.equals(ex.getMessage())) {
                throw ex;
            }
            throw new StudioException(ex.getCode(), message);
        } catch (Exception ex) {
            String message = redactRuntimeCapabilities(ex.getMessage(), runtimeRefs);
            if (message.equals(ex.getMessage())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Flink SQL execution failed: " + message, ex);
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Flink SQL execution failed: " + message);
        } finally {
            for (String ref : runtimeRefs) {
                AggregationFlinkRuntimeRegistry.remove(ref);
            }
            if (capabilityPluginSelection != null) {
                capabilityPluginSelection.close();
            }
            for (ManagedRuntimeFileResolver.Resolution<DataSourceDefinition> resolution : managedFileResolutions) {
                if (resolution != null) resolution.close();
            }
        }
    }

    private ManagedRuntimeFileResolver.Resolution<DataSourceDefinition> resolveManagedDatasource(
            DataSourceDefinition datasource, FlinkSqlExecuteRequest request, int index) {
        if (managedRuntimeFileResolver == null) return null;
        String datasourceId = datasource.getId() == null ? "unknown" : String.valueOf(datasource.getId());
        return managedRuntimeFileResolver.resolveDatasource(datasource, "FLINK_SQL",
                datasourceId + ":" + index + ":" + Long.toUnsignedString(System.nanoTime()));
    }

    private Map<Long, Path> collectManagedFileScope(Object original, Object resolved) {
        Map<Long, Path> scope = new LinkedHashMap<Long, Path>();
        collectManagedFileScope(original, resolved, scope);
        return scope;
    }

    private void collectManagedFileScope(Object original, Object resolved, Map<Long, Path> scope) {
        if (ManagedFileService.isManagedFileUri(original) && resolved != null) {
            Long fileId = ManagedFileService.parseManagedFileId(original, "Flink runtime configuration");
            scope.putIfAbsent(fileId, Path.of(String.valueOf(resolved)).toAbsolutePath().normalize());
            return;
        }
        if (original instanceof Map<?, ?> && resolved instanceof Map<?, ?>) {
            Map<?, ?> resolvedMap = (Map<?, ?>) resolved;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) original).entrySet()) {
                collectManagedFileScope(entry.getValue(), resolvedMap.get(entry.getKey()), scope);
            }
            return;
        }
        if (original instanceof List<?> && resolved instanceof List<?>) {
            List<?> originals = (List<?>) original;
            List<?> resolvedValues = (List<?>) resolved;
            int size = Math.min(originals.size(), resolvedValues.size());
            for (int index = 0; index < size; index++) {
                collectManagedFileScope(originals.get(index), resolvedValues.get(index), scope);
            }
        }
    }

    private void assertSecureManagedFileEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Invalid studio.flink.runtime-endpoint for managed file transfer");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) return;
        String host = uri.getHost() == null ? "" : uri.getHost().trim().toLowerCase();
        boolean loopback = "localhost".equals(host) || "::1".equals(host) || host.startsWith("127.");
        if (!loopback) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "HTTPS is required to transfer managed authentication files to a remote Flink runtime");
        }
    }

    private List<ResolvedPlugin> selectCapabilityPlugins(List<AggregationFlinkTableRuntime> runtimes,
                                                          PluginRuntimeSession selection) {
        Map<String, ResolvedPlugin> selectedByCoordinate = new LinkedHashMap<String, ResolvedPlugin>();
        List<ResolvedPlugin> selected = new ArrayList<ResolvedPlugin>();
        for (AggregationFlinkTableRuntime runtime : runtimes) {
            String pluginName = runtime == null ? null : runtime.getPluginName();
            if (pluginName == null || pluginName.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "DataAggregation Flink runtime plugin is required for a remote capability");
            }
            pluginName = pluginName.trim();
            String coordinate = SourcePluginType.SOURCE.getName() + "/" + pluginName;
            ResolvedPlugin plugin = selectedByCoordinate.get(coordinate);
            if (plugin == null) {
                plugin = selection.resolve(SourcePluginType.SOURCE, pluginName);
                selectedByCoordinate.put(coordinate, plugin);
            }
            selected.add(plugin);
        }
        return selected;
    }

    public static String tableNameFor(DataModelDefinition model) {
        return "m_" + model.getId();
    }

    private FlinkQuestionResultView toView(FlinkExecutionResult result) {
        FlinkQuestionResultView view = new FlinkQuestionResultView();
        view.setColumns(result.getColumns());
        view.setRows(result.getRows());
        view.getSummary().put("rowCount", result.getRows().size());
        return view;
    }

    private FlinkModelRefView toModelRef(DataSourceDefinition datasource, DataModelDefinition model, String flinkTableName) {
        FlinkModelRefView ref = new FlinkModelRefView();
        ref.setDatasourceId(datasource.getId());
        ref.setDatasourceName(datasource.getName());
        ref.setDatasourceType(datasource.getTypeCode());
        ref.setModelId(model.getId());
        ref.setModelName(model.getName());
        ref.setPhysicalLocator("http".equalsIgnoreCase(datasource.getTypeCode())
                ? httpReaderOptionSecurityService.maskSensitiveUrl(model.getPhysicalLocator())
                : model.getPhysicalLocator());
        ref.setFlinkTableName(flinkTableName);
        return ref;
    }

    private int normalizeMaxRows(Integer requested) {
        int configured = properties.getFlink().getMaxRows() == null ? 500 : properties.getFlink().getMaxRows();
        if (requested == null || requested <= 0) {
            return configured;
        }
        return Math.min(requested, configured);
    }

    private Integer normalizeScanMaxRows(Integer requested) {
        if (requested == null || requested <= 0) {
            return null;
        }
        return requested;
    }

    private String normalizedExecutionMode() {
        String mode = properties.getFlink().getExecutionMode();
        return mode == null || mode.trim().isEmpty() ? "embedded" : mode.trim().toLowerCase();
    }

    private String requiredRuntimeEndpoint() {
        String endpoint = properties.getFlink().getRuntimeEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "studio.flink.runtime-endpoint is required when execution-mode=gateway");
        }
        return endpoint.trim();
    }

    private String redactRuntimeCapabilities(String message, List<String> runtimeRefs) {
        String redacted = message == null ? "" : message;
        for (String runtimeRef : runtimeRefs) {
            if (runtimeRef != null && !runtimeRef.isEmpty()) {
                redacted = redacted.replace(runtimeRef, "***");
            }
        }
        return redacted;
    }

    private void appendPushdownSummary(FlinkQuestionResultView view,
                                       List<AggregationFlinkTableRuntime> runtimes,
                                       List<String> flinkTableNames) {
        List<Map<String, Object>> pushedFilters = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> remainingFilters = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> sourceSql = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> filePaths = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> pathContextFilters = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> httpPushdownFilters = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < runtimes.size(); i++) {
            AggregationFlinkTableRuntime runtime = runtimes.get(i);
            String tableName = flinkTableNames.get(i);
            addSummaryItem(pushedFilters, runtime, tableName, "filters", runtime.getPushedFilters());
            addSummaryItem(remainingFilters, runtime, tableName, "filters", runtime.getRemainingFilters());
            addSummaryItem(sourceSql, runtime, tableName, "sql", runtime.getResolvedSourceSql());
            addSummaryItem(filePaths, runtime, tableName, "paths", runtime.getResolvedFilePaths());
            if (!runtime.getPathContextFilters().isEmpty()) {
                Map<String, Object> item = baseSummaryItem(runtime, tableName);
                List<Map<String, Object>> filters = new ArrayList<Map<String, Object>>();
                runtime.getPathContextFilters().forEach(filter -> filters.add(filter.asMap()));
                item.put("filters", filters);
                pathContextFilters.add(item);
            }
            if (!runtime.getHttpPushdownFilters().isEmpty()) {
                Map<String, Object> item = baseSummaryItem(runtime, tableName);
                item.put("filters", new ArrayList<Map<String, Object>>(runtime.getHttpPushdownFilters()));
                httpPushdownFilters.add(item);
            }
        }
        view.getSummary().put("pushedFilters", pushedFilters);
        view.getSummary().put("remainingFilters", remainingFilters);
        view.getSummary().put("resolvedSourceSql", sourceSql);
        view.getSummary().put("resolvedFilePaths", filePaths);
        view.getSummary().put("pathContextFilters", pathContextFilters);
        view.getSummary().put("httpPushdownFilters", httpPushdownFilters);
    }

    private void addSummaryItem(List<Map<String, Object>> target,
                                AggregationFlinkTableRuntime runtime,
                                String tableName,
                                String valueKey,
                                List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<String, Object> item = baseSummaryItem(runtime, tableName);
        item.put(valueKey, new ArrayList<String>(values));
        target.add(item);
    }

    private Map<String, Object> baseSummaryItem(AggregationFlinkTableRuntime runtime, String tableName) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("datasourceId", runtime.getDatasourceId());
        item.put("modelId", runtime.getModelId());
        item.put("flinkTableName", tableName);
        item.put("pluginName", runtime.getPluginName());
        return item;
    }
}
