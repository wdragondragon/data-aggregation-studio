package com.jdragon.studio.flink.connector;

import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.connector.source.ReaderOutput;
import org.apache.flink.api.connector.source.SourceOutput;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.data.RowData;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        assertOptionKeys(factory.requiredOptions(), "runtime.ref", "plugin.name");
        assertOptionKeys(factory.optionalOptions(), "datasource.id", "model.id", "table", "scan.sql",
                "scan.mode", "scan.fetch-size", "scan.query-timeout-seconds", "scan.max-rows");
    }

    @Test
    void boundedReaderEndsWhenNoSplitIsAssigned() throws Exception {
        AggregationSourceReader reader = new AggregationSourceReader("missing-runtime",
                null,
                DataTypes.ROW(DataTypes.FIELD("payload", DataTypes.STRING())),
                Boundedness.BOUNDED);

        reader.notifyNoMoreSplits();

        assertEquals(InputStatus.END_OF_INPUT, reader.pollNext(new NoopReaderOutput()));
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
