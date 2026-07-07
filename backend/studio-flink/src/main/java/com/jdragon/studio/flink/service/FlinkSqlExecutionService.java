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
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
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

    public FlinkSqlExecutionService(DataModelService dataModelService,
                                    DataSourceService dataSourceService,
                                    AggregationFlinkRuntimeBuilder runtimeBuilder,
                                    FlinkSqlGuard sqlGuard,
                                    StudioPlatformProperties properties) {
        this.dataModelService = dataModelService;
        this.dataSourceService = dataSourceService;
        this.runtimeBuilder = runtimeBuilder;
        this.sqlGuard = sqlGuard;
        this.properties = properties;
    }

    public FlinkQuestionResultView execute(FlinkSqlExecuteRequest request) {
        if (!properties.getFlink().isEnabled()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Flink SQL execution is disabled");
        }
        if (request == null || request.getModelIds() == null || request.getModelIds().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "modelIds are required for Flink SQL execution");
        }
        int maxRows = normalizeMaxRows(request.getMaxRows());
        String guardedSql = sqlGuard.guardSelectSql(request.getSql(), maxRows);
        long startedAt = System.currentTimeMillis();
        List<String> runtimeRefs = new ArrayList<String>();
        List<FlinkModelRefView> modelRefs = new ArrayList<FlinkModelRefView>();
        try {
            List<AggregationFlinkTableRuntime> runtimes = new ArrayList<AggregationFlinkTableRuntime>();
            List<String> flinkTableNames = new ArrayList<String>();
            for (Long modelId : request.getModelIds()) {
                DataModelDefinition model = dataModelService.get(modelId);
                DataSourceDefinition datasource = dataSourceService.getInternal(model.getDatasourceId());
                AggregationFlinkTableRuntime runtime = runtimeBuilder.build(datasource, model, maxRows);
                runtimes.add(runtime);
                String flinkTableName = tableNameFor(model);
                flinkTableNames.add(flinkTableName);
                modelRefs.add(toModelRef(datasource, model, flinkTableName));
            }
            boolean streamingMode = runtimes.stream()
                    .anyMatch(runtime -> "unbounded".equalsIgnoreCase(runtime.getScanMode()));
            TableEnvironment tableEnvironment = createTableEnvironment(streamingMode);
            for (int i = 0; i < runtimes.size(); i++) {
                AggregationFlinkTableRuntime runtime = runtimes.get(i);
                String runtimeRef = AggregationFlinkRuntimeRegistry.register(runtime,
                        properties.getFlink().getRuntimeRegistryTtlSeconds());
                runtimeRefs.add(runtimeRef);
                String flinkTableName = flinkTableNames.get(i);
                tableEnvironment.executeSql(buildCreateTemporaryTableDdl(flinkTableName, runtimeRef, runtime));
            }
            tableEnvironment.explainSql(guardedSql);
            TableResult tableResult = tableEnvironment.executeSql(guardedSql);
            FlinkQuestionResultView view = collectResult(tableResult, maxRows);
            view.setSql(guardedSql);
            view.setModelRefs(modelRefs);
            view.setExecutionMs(System.currentTimeMillis() - startedAt);
            view.getSummary().put("modelCount", modelRefs.size());
            view.getSummary().put("executionMode", properties.getFlink().getExecutionMode());
            view.getSummary().put("runtimeMode", streamingMode ? "streaming" : "batch");
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

    private TableEnvironment createTableEnvironment(boolean streamingMode) {
        Configuration configuration = new Configuration();
        configuration.set(CoreOptions.DEFAULT_PARALLELISM, Math.max(1, properties.getFlink().getDefaultParallelism()));
        EnvironmentSettings.Builder settingsBuilder = EnvironmentSettings.newInstance()
                .withConfiguration(configuration)
                .withBuiltInCatalogName("default_catalog")
                .withBuiltInDatabaseName("default_database");
        if (streamingMode) {
            settingsBuilder.inStreamingMode();
        } else {
            settingsBuilder.inBatchMode();
        }
        return TableEnvironment.create(settingsBuilder.build());
    }

    private String buildCreateTemporaryTableDdl(String flinkTableName, String runtimeRef, AggregationFlinkTableRuntime runtime) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TEMPORARY TABLE ").append(quoteIdentifier(flinkTableName)).append(" (");
        List<String> fields = runtime.getFieldNames();
        List<org.apache.flink.table.types.DataType> fieldTypes =
                org.apache.flink.table.types.DataType.getFieldDataTypes(runtime.getProducedDataType());
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(quoteIdentifier(fields.get(i))).append(" ").append(fieldTypes.get(i).getLogicalType().asSerializableString());
        }
        ddl.append(") WITH (");
        appendOption(ddl, "connector", "dataaggregation", true);
        appendOption(ddl, "runtime.ref", runtimeRef, false);
        appendOption(ddl, "datasource.id", String.valueOf(runtime.getDatasourceId()), false);
        appendOption(ddl, "model.id", String.valueOf(runtime.getModelId()), false);
        appendOption(ddl, "plugin.name", runtime.getPluginName(), false);
        appendOption(ddl, "scan.mode", runtime.getScanMode(), false);
        if (runtime.getMaxRows() != null && runtime.getMaxRows() > 0) {
            appendOption(ddl, "scan.max-rows", String.valueOf(runtime.getMaxRows()), false);
        }
        ddl.append(")");
        return ddl.toString();
    }

    private void appendOption(StringBuilder ddl, String key, String value, boolean first) {
        if (!first) {
            ddl.append(", ");
        }
        ddl.append("'").append(key).append("' = '").append(value == null ? "" : value.replace("'", "''")).append("'");
    }

    private FlinkQuestionResultView collectResult(TableResult tableResult, int maxRows) throws Exception {
        FlinkQuestionResultView view = new FlinkQuestionResultView();
        List<String> columns = tableResult.getResolvedSchema().getColumnNames();
        view.setColumns(new ArrayList<String>(columns));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        try (CloseableIterator<Row> iterator = tableResult.collect()) {
            while (iterator.hasNext() && (maxRows <= 0 || rows.size() < maxRows)) {
                Row row = iterator.next();
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                for (int i = 0; i < columns.size(); i++) {
                    item.put(columns.get(i), row.getField(i));
                }
                rows.add(item);
            }
        }
        view.setRows(rows);
        view.getSummary().put("rowCount", rows.size());
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

    private String quoteIdentifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }
}
