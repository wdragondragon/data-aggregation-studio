package com.jdragon.studio.flink.service;

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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FlinkSqlExecutionService {
    private final DataModelService dataModelService;
    private final DataSourceService dataSourceService;
    private final AggregationFlinkRuntimeBuilder runtimeBuilder;
    private final FlinkSqlGuard sqlGuard;
    private final StudioPlatformProperties properties;
    private final FlinkExecutionClientRouter executionClientRouter;

    public FlinkSqlExecutionService(DataModelService dataModelService,
                                    DataSourceService dataSourceService,
                                    AggregationFlinkRuntimeBuilder runtimeBuilder,
                                    FlinkSqlGuard sqlGuard,
                                    StudioPlatformProperties properties,
                                    FlinkExecutionClientRouter executionClientRouter) {
        this.dataModelService = dataModelService;
        this.dataSourceService = dataSourceService;
        this.runtimeBuilder = runtimeBuilder;
        this.sqlGuard = sqlGuard;
        this.properties = properties;
        this.executionClientRouter = executionClientRouter;
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
        try {
            List<AggregationFlinkTableRuntime> runtimes = new ArrayList<AggregationFlinkTableRuntime>();
            List<String> flinkTableNames = new ArrayList<String>();
            String executionMode = normalizedExecutionMode();
            FlinkExecutionClient executionClient = executionClientRouter.select(executionMode);
            for (Long modelId : request.getModelIds()) {
                DataModelDefinition model = dataModelService.get(modelId);
                DataSourceDefinition datasource = dataSourceService.getInternal(model.getDatasourceId());
                AggregationFlinkTableRuntime runtime = runtimeBuilder.build(datasource, model, scanMaxRows);
                runtimes.add(runtime);
                String flinkTableName = tableNameFor(model);
                flinkTableNames.add(flinkTableName);
                modelRefs.add(toModelRef(datasource, model, flinkTableName));
            }
            boolean streamingMode = runtimes.stream()
                    .anyMatch(runtime -> "unbounded".equalsIgnoreCase(runtime.getScanMode()));
            for (int i = 0; i < runtimes.size(); i++) {
                AggregationFlinkTableRuntime runtime = runtimes.get(i);
                String runtimeRef = AggregationFlinkRuntimeRegistry.register(runtime,
                        properties.getFlink().getRuntimeRegistryTtlSeconds());
                runtimeRefs.add(runtimeRef);
                String flinkTableName = flinkTableNames.get(i);
                FlinkRuntimeConnectorAccess access = "gateway".equals(executionMode)
                        ? FlinkRuntimeConnectorAccess.remote(requiredRuntimeEndpoint(), runtimeRef)
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
            throw ex;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Flink SQL execution failed: " + ex.getMessage(), ex);
        } finally {
            for (String ref : runtimeRefs) {
                AggregationFlinkRuntimeRegistry.remove(ref);
            }
        }
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
        ref.setPhysicalLocator(model.getPhysicalLocator());
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

    private void appendPushdownSummary(FlinkQuestionResultView view,
                                       List<AggregationFlinkTableRuntime> runtimes,
                                       List<String> flinkTableNames) {
        List<Map<String, Object>> pushedFilters = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> remainingFilters = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> sourceSql = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> filePaths = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> pathContextFilters = new ArrayList<Map<String, Object>>();
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
        }
        view.getSummary().put("pushedFilters", pushedFilters);
        view.getSummary().put("remainingFilters", remainingFilters);
        view.getSummary().put("resolvedSourceSql", sourceSql);
        view.getSummary().put("resolvedFilePaths", filePaths);
        view.getSummary().put("pathContextFilters", pathContextFilters);
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
