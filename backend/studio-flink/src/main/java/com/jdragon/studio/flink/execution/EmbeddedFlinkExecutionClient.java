package com.jdragon.studio.flink.execution;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EmbeddedFlinkExecutionClient implements FlinkExecutionClient {
    private final StudioPlatformProperties properties;

    public EmbeddedFlinkExecutionClient(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    @Override
    public String executionMode() {
        return "embedded";
    }

    @Override
    public FlinkExecutionResult execute(FlinkExecutionRequest request) throws Exception {
        TableEnvironment tableEnvironment = createTableEnvironment(request.isStreamingMode());
        for (String ddl : request.getCreateTableDdls()) {
            tableEnvironment.executeSql(ddl);
        }
        tableEnvironment.explainSql(request.getSql());
        return collectResult(tableEnvironment.executeSql(request.getSql()), request.getMaxRows());
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

    private FlinkExecutionResult collectResult(TableResult tableResult, int maxRows) throws Exception {
        FlinkExecutionResult result = new FlinkExecutionResult();
        List<String> columns = tableResult.getResolvedSchema().getColumnNames();
        result.setColumns(new ArrayList<String>(columns));
        try (CloseableIterator<Row> iterator = tableResult.collect()) {
            while (iterator.hasNext() && (maxRows <= 0 || result.getRows().size() < maxRows)) {
                Row row = iterator.next();
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                for (int i = 0; i < columns.size(); i++) {
                    item.put(columns.get(i), row.getField(i));
                }
                result.addRow(item);
            }
        }
        return result;
    }
}
