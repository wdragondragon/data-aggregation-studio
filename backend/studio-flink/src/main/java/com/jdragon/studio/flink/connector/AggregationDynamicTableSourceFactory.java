package com.jdragon.studio.flink.connector;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.factories.DynamicTableSourceFactory;
import org.apache.flink.table.factories.FactoryUtil;

import java.util.HashSet;
import java.util.Set;

public class AggregationDynamicTableSourceFactory implements DynamicTableSourceFactory {
    @Override
    public DynamicTableSource createDynamicTableSource(Context context) {
        FactoryUtil.TableFactoryHelper helper = FactoryUtil.createTableFactoryHelper(this, context);
        helper.validate();
        String runtimeRef = helper.getOptions().get(AggregationConnectorOptions.RUNTIME_REF);
        String pluginName = helper.getOptions().get(AggregationConnectorOptions.PLUGIN_NAME);
        String scanMode = helper.getOptions().get(AggregationConnectorOptions.SCAN_MODE);
        Integer maxRows = helper.getOptions().getOptional(AggregationConnectorOptions.MAX_ROWS).orElse(null);
        return new AggregationDynamicTableSource(
                runtimeRef,
                pluginName,
                scanMode,
                maxRows,
                context.getCatalogTable().getResolvedSchema().toPhysicalRowDataType());
    }

    @Override
    public String factoryIdentifier() {
        return AggregationConnectorOptions.IDENTIFIER;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        Set<ConfigOption<?>> options = new HashSet<ConfigOption<?>>();
        options.add(AggregationConnectorOptions.RUNTIME_REF);
        options.add(AggregationConnectorOptions.PLUGIN_NAME);
        return options;
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        Set<ConfigOption<?>> options = new HashSet<ConfigOption<?>>();
        options.add(AggregationConnectorOptions.DATASOURCE_ID);
        options.add(AggregationConnectorOptions.MODEL_ID);
        options.add(AggregationConnectorOptions.TABLE);
        options.add(AggregationConnectorOptions.SCAN_SQL);
        options.add(AggregationConnectorOptions.SCAN_MODE);
        options.add(AggregationConnectorOptions.FETCH_SIZE);
        options.add(AggregationConnectorOptions.QUERY_TIMEOUT_SECONDS);
        options.add(AggregationConnectorOptions.MAX_ROWS);
        return options;
    }
}
