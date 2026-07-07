package com.jdragon.studio.flink.connector;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;

final class AggregationConnectorOptions {
    static final String IDENTIFIER = "dataaggregation";

    static final ConfigOption<String> RUNTIME_REF = ConfigOptions.key("runtime.ref").stringType().noDefaultValue();
    static final ConfigOption<String> DATASOURCE_ID = ConfigOptions.key("datasource.id").stringType().noDefaultValue();
    static final ConfigOption<String> MODEL_ID = ConfigOptions.key("model.id").stringType().noDefaultValue();
    static final ConfigOption<String> PLUGIN_NAME = ConfigOptions.key("plugin.name").stringType().noDefaultValue();
    static final ConfigOption<String> TABLE = ConfigOptions.key("table").stringType().noDefaultValue();
    static final ConfigOption<String> SCAN_SQL = ConfigOptions.key("scan.sql").stringType().noDefaultValue();
    static final ConfigOption<String> SCAN_MODE = ConfigOptions.key("scan.mode").stringType().defaultValue("bounded");
    static final ConfigOption<Integer> FETCH_SIZE = ConfigOptions.key("scan.fetch-size").intType().noDefaultValue();
    static final ConfigOption<Integer> QUERY_TIMEOUT_SECONDS = ConfigOptions.key("scan.query-timeout-seconds").intType().noDefaultValue();
    static final ConfigOption<Integer> MAX_ROWS = ConfigOptions.key("scan.max-rows").intType().noDefaultValue();

    private AggregationConnectorOptions() {
    }
}
