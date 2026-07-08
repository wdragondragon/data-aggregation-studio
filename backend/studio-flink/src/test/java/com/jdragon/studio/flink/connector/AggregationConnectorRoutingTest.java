package com.jdragon.studio.flink.connector;

import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.connector.source.ReaderOutput;
import org.apache.flink.api.connector.source.SourceOutput;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.connector.source.abilities.SupportsLimitPushDown;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.types.DataType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregationConnectorRoutingTest {

    @Test
    void classifiesAllPlannedPluginFamilies() {
        assertStructured("mysql5", "mysql8", "postgres", "oracle", "dm",
                "tbds-hive2", "tbds-hive3", "odps", "influxdb", "influxdbv1");
        assertFile("ftp", "sftp", "tbds-hdfs", "tbds-hdfs3", "minio", "s3", "oss");
        assertQueue("kafka", "rocketmq", "rabbitmq");
    }

    @Test
    void routesPluginFamiliesToDedicatedStrategies() {
        assertInstanceOf(StructuredPluginSourceStrategy.class, AggregationSourceStrategyFactory.create("mysql8"));
        assertInstanceOf(StructuredPluginSourceStrategy.class, AggregationSourceStrategyFactory.create("odps"));
        assertInstanceOf(FilePluginSourceStrategy.class, AggregationSourceStrategyFactory.create("s3"));
        assertInstanceOf(FilePluginSourceStrategy.class, AggregationSourceStrategyFactory.create("tbds-hdfs3"));
        assertInstanceOf(QueuePluginSourceStrategy.class, AggregationSourceStrategyFactory.create("kafka"));
    }

    @Test
    void exposesUnifiedConnectorOptions() {
        AggregationDynamicTableSourceFactory factory = new AggregationDynamicTableSourceFactory();

        assertEquals("dataaggregation", factory.factoryIdentifier());
        assertOptionKeys(factory.requiredOptions(), "plugin.name");
        assertOptionKeys(factory.optionalOptions(), "runtime.ref", "runtime.endpoint", "runtime.token",
                "datasource.id", "model.id", "table", "scan.sql",
                "scan.mode", "scan.fetch-size", "scan.query-timeout-seconds", "scan.max-rows");
    }

    @Test
    void boundedReaderEndsWhenNoSplitIsAssigned() throws Exception {
        AggregationSourceReader reader = new AggregationSourceReader(AggregationRuntimeHandle.local("missing-runtime"),
                null,
                DataTypes.ROW(DataTypes.FIELD("payload", DataTypes.STRING())),
                Boundedness.BOUNDED);

        reader.notifyNoMoreSplits();

        assertEquals(InputStatus.END_OF_INPUT, reader.pollNext(new NoopReaderOutput()));
    }

    @Test
    void doesNotPushQueryLimitAsSourceScanLimit() {
        AggregationDynamicTableSource source = new AggregationDynamicTableSource(AggregationRuntimeHandle.local("runtime-ref"),
                "mysql8",
                "bounded",
                null,
                DataTypes.ROW(DataTypes.FIELD("payload", DataTypes.STRING())));

        assertFalse(source instanceof SupportsLimitPushDown);
    }

    @Test
    void translatesStructuredFiltersToNativeSqlPredicates() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(new LinkedHashMap<String, Object>());

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                        binary(BuiltInFunctionDefinitions.EQUALS, "biz_date", DataTypes.DATE(),
                                LocalDate.of(2026, 7, 5), DataTypes.DATE())),
                        runtime,
                        AggregationPluginKind.STRUCTURED);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertEquals(Collections.singletonList("biz_date = '2026-07-05'"), translation.getPushedFilterSql());
        assertTrue(translation.getRemainingFilters().isEmpty());
    }

    @Test
    void keepsUnsupportedFileContentFilterAsResidual() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(pathMetadata(true, true));

        ResolvedExpression pathFilter = binary(BuiltInFunctionDefinitions.EQUALS,
                "__path_inbound_date", DataTypes.DATE(), LocalDate.of(2026, 7, 5), DataTypes.DATE());
        ResolvedExpression contentFilter = binary(BuiltInFunctionDefinitions.EQUALS,
                "update_time", DataTypes.DATE(), LocalDate.of(2026, 7, 8), DataTypes.DATE());
        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Arrays.asList(pathFilter, contentFilter),
                        runtime,
                        AggregationPluginKind.FILE);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertEquals(1, translation.getRemainingFilters().size());
        assertEquals("__path_inbound_date", translation.getPathContextFilters().get(0).getField());
        assertEquals(Collections.singletonList("2026-07-05"), translation.getPathContextFilters().get(0).getValues());
    }

    @Test
    void splitsMixedFileConjunctsSoPathFilterCanBePushed() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(pathMetadata(true, true));

        ResolvedExpression mixedFilter = and(
                binary(BuiltInFunctionDefinitions.EQUALS,
                        "__path_inbound_date", DataTypes.DATE(), LocalDate.of(2026, 7, 5), DataTypes.DATE()),
                binary(BuiltInFunctionDefinitions.EQUALS,
                        "update_time", DataTypes.DATE(), LocalDate.of(2026, 7, 8), DataTypes.DATE()));
        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(mixedFilter),
                        runtime,
                        AggregationPluginKind.FILE);

        assertEquals(Collections.singletonList("__path_inbound_date = '2026-07-05'"),
                translation.getPushedFilterSql());
        assertEquals(1, translation.getAcceptedFilters().size());
        assertEquals(1, translation.getRemainingFilters().size());
        assertEquals("__path_inbound_date", translation.getPathContextFilters().get(0).getField());
    }

    @Test
    void appendsPushedFiltersOutsideWrappedScanSql() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setScanSql("select id, biz_date from orders where available = 1");
        runtime.setPushedFilters(Collections.singletonList("biz_date = '2026-07-05'"));
        runtime.setMaxRows(10);

        String query = AggregationSourceUtil.buildQuery(runtime,
                DataTypes.ROW(
                        DataTypes.FIELD("id", DataTypes.INT()),
                        DataTypes.FIELD("biz_date", DataTypes.DATE())));

        assertEquals("SELECT * FROM (select id, biz_date from orders where available = 1) da_flink_src "
                + "WHERE (biz_date = '2026-07-05') LIMIT 10", query);
    }

    @Test
    void resolvesDynamicFilePathWithQueryDateAsBaseTime() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(pathMetadata(true, true));
        runtime.setPathContextFilters(Collections.singletonList(new FilePathPushdownFilter(
                "__path_inbound_date",
                "入库时间",
                "=",
                Collections.singletonList("2026-07-05"),
                "__path_inbound_date = DATE '2026-07-05'")));

        List<ResolvedFilePath> paths = FilePathPushdownResolver.resolve(runtime);

        assertEquals(1, paths.size());
        assertEquals("wbsj/events/2026/07/04", paths.get(0).getPath());
        assertEquals(LocalDate.of(2026, 7, 5), paths.get(0).getContextValues().get("__path_inbound_date"));
    }

    @Test
    void rejectsRequiredFilePathContextWithoutDateFilter() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(pathMetadata(true, true));

        assertThrows(IllegalArgumentException.class, () -> FilePathPushdownResolver.resolve(runtime));
    }

    private void assertStructured(String... names) {
        for (String name : names) {
            assertEquals(AggregationPluginKind.STRUCTURED, AggregationPluginClassifier.classify(name), name);
        }
    }

    private void assertFile(String... names) {
        for (String name : names) {
            assertEquals(AggregationPluginKind.FILE, AggregationPluginClassifier.classify(name), name);
        }
    }

    private void assertQueue(String... names) {
        for (String name : names) {
            assertEquals(AggregationPluginKind.QUEUE, AggregationPluginClassifier.classify(name), name);
        }
    }

    private void assertOptionKeys(Set<ConfigOption<?>> actual, String... expectedKeys) {
        Set<String> actualKeys = actual.stream().map(ConfigOption::key).collect(Collectors.toSet());
        for (String key : expectedKeys) {
            assertTrue(actualKeys.contains(key), key);
        }
    }

    private ResolvedExpression binary(org.apache.flink.table.functions.BuiltInFunctionDefinition function,
                                      String fieldName,
                                      DataType fieldType,
                                      Object value,
                                      DataType valueType) {
        return CallExpression.permanent(function,
                Arrays.asList(
                        new FieldReferenceExpression(fieldName, fieldType, 0, 0),
                        new ValueLiteralExpression(value, valueType.notNull())),
                DataTypes.BOOLEAN());
    }

    private ResolvedExpression and(ResolvedExpression left, ResolvedExpression right) {
        return CallExpression.permanent(BuiltInFunctionDefinitions.AND,
                Arrays.asList(left, right),
                DataTypes.BOOLEAN());
    }

    private Map<String, Object> pathMetadata(boolean enabled, boolean required) {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("field", "__path_inbound_date");
        context.put("displayName", "入库时间");
        context.put("aliases", Arrays.asList("入库日期", "落盘日期", "文件日期"));
        context.put("type", "DATE");
        context.put("pathExpressions", Collections.singletonList(
                "wbsj/events/$getCurrentTime('yyyy', '-1d')/$getCurrentTime('MM', '-1d')/$getCurrentTime('dd', '-1d')"));
        context.put("maxExpandedDates", 31);
        Map<String, Object> pushdown = new LinkedHashMap<String, Object>();
        pushdown.put("enabled", enabled);
        pushdown.put("required", required);
        pushdown.put("contexts", Collections.singletonList(context));
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("filePathPushdown", pushdown);
        return metadata;
    }

    private static class NoopReaderOutput implements ReaderOutput<RowData> {
        @Override
        public void collect(RowData record) {
        }

        @Override
        public void collect(RowData record, long timestamp) {
        }

        @Override
        public void emitWatermark(Watermark watermark) {
        }

        @Override
        public void markIdle() {
        }

        @Override
        public void markActive() {
        }

        @Override
        public SourceOutput<RowData> createOutputForSplit(String splitId) {
            return this;
        }

        @Override
        public void releaseOutputForSplit(String splitId) {
        }
    }
}
