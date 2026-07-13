package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.jdragon.aggregation.datasource.file.FileHelper;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.connector.source.ReaderOutput;
import org.apache.flink.api.connector.source.SourceOutput;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.connector.source.LookupTableSource;
import org.apache.flink.table.connector.source.abilities.SupportsLimitPushDown;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.NestedFieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.legacy.connector.source.TableFunctionProvider;
import org.apache.flink.table.types.DataType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregationConnectorRoutingTest {

    @Test
    void classifiesAllPlannedPluginFamilies() {
        assertStructured("mysql5", "mysql8", "postgres", "oracle", "dm",
                "tbds-hive2", "tbds-hive3", "odps", "influxdb", "influxdbv1");
        assertFile("ftp", "sftp", "tbds-hdfs", "tbds-hdfs3", "minio", "s3", "oss");
        assertQueue("kafka", "rocketmq", "rabbitmq");
        assertEquals(AggregationPluginKind.HTTP, AggregationPluginClassifier.classify("http"));
    }

    @Test
    void routesPluginFamiliesToDedicatedStrategies() {
        assertInstanceOf(StructuredPluginSourceStrategy.class, AggregationSourceStrategyFactory.create("mysql8"));
        assertInstanceOf(StructuredPluginSourceStrategy.class, AggregationSourceStrategyFactory.create("odps"));
        assertInstanceOf(FilePluginSourceStrategy.class, AggregationSourceStrategyFactory.create("s3"));
        assertInstanceOf(FilePluginSourceStrategy.class, AggregationSourceStrategyFactory.create("tbds-hdfs3"));
        assertInstanceOf(QueuePluginSourceStrategy.class, AggregationSourceStrategyFactory.create("kafka"));
        assertInstanceOf(StructuredPluginSourceStrategy.class, AggregationSourceStrategyFactory.create("http"));
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
    void sourceCopiesKeepIndependentHttpFilterRuntime() throws Exception {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(httpReaderOptionsMetadata("{\"customer_id\":\"\"}", "{}", ""));
        String ref = AggregationFlinkRuntimeRegistry.register(runtime, 300);
        try {
            AggregationDynamicTableSource source = new AggregationDynamicTableSource(
                    AggregationRuntimeHandle.local(ref),
                    "http",
                    "bounded",
                    null,
                    DataTypes.ROW(DataTypes.FIELD("customer_id", DataTypes.STRING())));
            AggregationDynamicTableSource left = (AggregationDynamicTableSource) source.copy();
            AggregationDynamicTableSource right = (AggregationDynamicTableSource) source.copy();

            left.applyFilters(Collections.singletonList(
                    binary(BuiltInFunctionDefinitions.EQUALS, "param.customer_id", DataTypes.STRING(),
                            "C001", DataTypes.STRING())));
            right.applyFilters(Collections.singletonList(
                    binary(BuiltInFunctionDefinitions.EQUALS, "param.customer_id", DataTypes.STRING(),
                            "C002", DataTypes.STRING())));

            assertEquals(Collections.singletonList("C001"), AggregationRuntimeResolver.resolve(runtimeHandle(left))
                    .getHttpPushdownFilters().get(0).get("values"));
            assertEquals(Collections.singletonList("C002"), AggregationRuntimeResolver.resolve(runtimeHandle(right))
                    .getHttpPushdownFilters().get(0).get("values"));
        } finally {
            AggregationFlinkRuntimeRegistry.remove(ref);
        }
    }

    @Test
    void unfilteredHttpSourceCopyDoesNotInheritAlwaysFalseAuditState() throws Exception {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(httpReaderOptionsMetadata("{\"customer_id\":\"\"}", "{}", ""));
        String ref = AggregationFlinkRuntimeRegistry.register(runtime, 300);
        try {
            AggregationDynamicTableSource source = new AggregationDynamicTableSource(
                    AggregationRuntimeHandle.local(ref),
                    "http",
                    "bounded",
                    null,
                    DataTypes.ROW(DataTypes.FIELD("customer_id", DataTypes.STRING())));
            AggregationDynamicTableSource filtered = (AggregationDynamicTableSource) source.copy();
            AggregationDynamicTableSource unfiltered = (AggregationDynamicTableSource) source.copy();

            filtered.applyFilters(Collections.singletonList(
                    binaryNullable(BuiltInFunctionDefinitions.EQUALS, "param.customer_id", DataTypes.STRING(),
                            null, DataTypes.STRING())));

            assertTrue(AggregationRuntimeResolver.resolve(runtimeHandle(filtered)).isHttpFilterAlwaysFalse());
            assertFalse(AggregationRuntimeResolver.resolve(runtimeHandle(unfiltered)).isHttpFilterAlwaysFalse());
        } finally {
            AggregationFlinkRuntimeRegistry.remove(ref);
        }
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
    void translatesExplicitHttpFieldToPushdownWithoutResidualFilter() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(new LinkedHashMap<String, Object>());

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "param.customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertTrue(translation.getRemainingFilters().isEmpty());
        assertEquals("param", translation.getHttpPushdownFilters().get(0).get("location"));
        assertEquals("customer_id", translation.getHttpPushdownFilters().get(0).get("requestParamName"));
        assertEquals(Collections.singletonList("C001"), translation.getHttpPushdownFilters().get(0).get("values"));
    }

    @Test
    void translatesFlinkNestedHttpFieldsWithoutResidualFilters() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                "{\"filter\":{\"id\":\"\"}}"));

        ResolvedExpression paramField = new NestedFieldReferenceExpression(
                new String[]{"param", "customer_id"}, new int[]{0, 0}, DataTypes.STRING());
        ResolvedExpression bodyField = new NestedFieldReferenceExpression(
                new String[]{"body", "filter", "id"}, new int[]{1, 0, 0}, DataTypes.STRING());
        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Arrays.asList(
                                binary(BuiltInFunctionDefinitions.EQUALS, paramField,
                                        "C001", DataTypes.STRING()),
                                binary(BuiltInFunctionDefinitions.EQUALS, bodyField,
                                        "F001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(2, translation.getAcceptedFilters().size());
        assertTrue(translation.getRemainingFilters().isEmpty());
        assertEquals("customer_id", translation.getHttpPushdownFilters().get(0).get("requestParamName"));
        assertEquals("filter.id", translation.getHttpPushdownFilters().get(1).get("bodyPath"));
    }

    @Test
    void translatesQuotedExplicitHttpFieldToPushdownWithoutResidualFilter() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(new LinkedHashMap<String, Object>());

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "`param.customer_id`", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertTrue(translation.getRemainingFilters().isEmpty());
        assertEquals("param", translation.getHttpPushdownFilters().get(0).get("location"));
        assertEquals("customer_id", translation.getHttpPushdownFilters().get(0).get("requestParamName"));
    }

    @Test
    void translatesExplicitQueryHttpFieldToPushdownWithoutResidualFilter() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "query.customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertTrue(translation.getRemainingFilters().isEmpty());
        assertEquals("query", translation.getHttpPushdownFilters().get(0).get("location"));
        assertEquals("customer_id", translation.getHttpPushdownFilters().get(0).get("requestParamName"));
    }

    @Test
    void translatesUniqueReaderOptionHttpFieldToPushdownWithoutResidualFilter() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertTrue(translation.getRemainingFilters().isEmpty());
        assertEquals(1, translation.getHttpPushdownFilters().size());
        assertEquals("param", translation.getHttpPushdownFilters().get(0).get("location"));
        assertEquals("customer_id", translation.getHttpPushdownFilters().get(0).get("requestParamName"));
    }

    @Test
    void normalizesTemporalHttpPushdownValuesForJsonTransport() throws Exception {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"biz_date\":\"\"}",
                "{}",
                ""));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "biz_date", DataTypes.DATE(),
                                        LocalDate.of(2026, 7, 12), DataTypes.DATE())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(Collections.singletonList("2026-07-12"),
                translation.getHttpPushdownFilters().get(0).get("values"));
        String serialized = new ObjectMapper().writeValueAsString(translation.getHttpPushdownFilters());
        assertTrue(serialized.contains("2026-07-12"));
    }

    @Test
    void translatesNestedBodyReaderOptionHttpFieldToPushdownWithoutResidualFilter() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{}",
                "{}",
                "{\"filter\":{\"customer_id\":\"\"}}"));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                                        "C004", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertTrue(translation.getRemainingFilters().isEmpty());
        assertEquals("body", translation.getHttpPushdownFilters().get(0).get("location"));
        assertEquals("filter.customer_id", translation.getHttpPushdownFilters().get(0).get("bodyPath"));
    }

    @Test
    void rejectsBodyPushdownForHttpMethodWithoutRequestBody() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        Map<String, Object> metadata = httpReaderOptionsMetadata(
                "{}",
                "{}",
                "{\"filter\":{\"customer_id\":\"\"}}");
        metadata.put("mode", "GET");
        runtime.setModelMetadata(metadata);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "body.filter.customer_id",
                                        DataTypes.STRING(), "C004", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));

        assertTrue(error.getMessage().contains("POST/PUT/PATCH"));
        assertTrue(error.getMessage().contains("method=GET"));
    }

    @Test
    void validatesBodyPushdownAgainstSerializedReaderMethod() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        Map<String, Object> metadata = httpReaderOptionsMetadata("{}", "{}", "{\"customer_id\":\"\"}");
        metadata.put("mode", "GET");
        runtime.setModelMetadata(metadata);
        BaseDataSourceDTO dto = new BaseDataSourceDTO();
        dto.setExtraParams(Collections.singletonMap(
                "__studio_http_reader_config", "{\"mode\":\"PATCH\"}"));
        runtime.setDataSourceDTO(dto);

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "body.customer_id",
                                        DataTypes.STRING(), "C004", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
    }

    @Test
    void rejectsInvalidBodyMethodAgainBeforeHttpRuntimeStarts() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(Collections.<String, Object>singletonMap("mode", "GET"));
        Map<String, Object> filter = new LinkedHashMap<String, Object>();
        filter.put("field", "customer_id");
        filter.put("location", "body");
        filter.put("bodyPath", "customer_id");
        filter.put("values", Collections.singletonList("C004"));
        runtime.setHttpPushdownFilters(Collections.singletonList(filter));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new StructuredPluginSourceStrategy().readRows(runtime, row -> true));

        assertTrue(error.getMessage().contains("method=GET"));
    }

    @Test
    void translatesExplicitNestedBodyPathWithoutDroppingIntermediateSegments() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{}",
                "{}",
                "{\"filter\":{\"customer\":{\"id\":\"\"}}}"));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "body.filter.customer.id", DataTypes.STRING(),
                                        "C004", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertTrue(translation.getRemainingFilters().isEmpty());
        assertEquals("filter.customer.id", translation.getHttpPushdownFilters().get(0).get("bodyPath"));
    }

    @Test
    void derivesSoapXmlBodyMappingFromRequestTemplate() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{}",
                "{}",
                "<Envelope><Body><Query><customer_id/></Query></Body></Envelope>"));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                                        "C005", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertEquals("body", translation.getHttpPushdownFilters().get(0).get("location"));
        assertEquals("Envelope.Body.Query.customer_id",
                translation.getHttpPushdownFilters().get(0).get("bodyPath"));
    }

    @Test
    void keepsSoapNamespacesInBodyPushdownMappings() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{}",
                "{}",
                "<Envelope xmlns:a=\"urn:a\" xmlns:b=\"urn:b\"><a:Query><a:customer_id/></a:Query><b:Query><b:customer_id/></b:Query></Envelope>"));

        IllegalArgumentException ambiguous = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                                        "C005", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));
        assertTrue(ambiguous.getMessage().contains("同时映射到"));
        assertTrue(ambiguous.getMessage().contains("body.Envelope.a:Query.a:customer_id"));
        assertTrue(ambiguous.getMessage().contains("body.Envelope.b:Query.b:customer_id"));

        ResolvedExpression namespacedField = new NestedFieldReferenceExpression(
                new String[]{"body", "Envelope", "a:Query", "a:customer_id"},
                new int[]{0, 0, 0, 0},
                DataTypes.STRING());
        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, namespacedField,
                                        "C005", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals("Envelope.a:Query.a:customer_id",
                translation.getHttpPushdownFilters().get(0).get("bodyPath"));
    }

    @Test
    void carriesPaginationTemplateTokensIntoHttpPushdownPredicate() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"page\":\"{dyn_page}\",\"size\":\"{dyn_pageSize}\"}",
                "{}",
                ""));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "page", DataTypes.INT(),
                                        2, DataTypes.INT())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(Collections.singletonList("PAGE"),
                translation.getHttpPushdownFilters().get(0).get("paginationTokens"));
    }

    @Test
    void translatesPathReaderOptionHttpFieldToPushdownWithoutResidualFilter() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        Map<String, Object> metadata = httpReaderOptionsMetadata(
                "{}",
                "{}",
                "");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerOptions = (Map<String, Object>) metadata.get("readerOptions");
        readerOptions.put("url", "http://api.example.com/customers/{customer_id}/risk");
        runtime.setModelMetadata(metadata);

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertTrue(translation.getRemainingFilters().isEmpty());
        assertEquals("path", translation.getHttpPushdownFilters().get(0).get("location"));
        assertEquals("customer_id", translation.getHttpPushdownFilters().get(0).get("pathVariable"));
    }

    @Test
    void translatesPhysicalLocatorPathFieldWithoutResidualFilter() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata("{}", "{}", ""));
        runtime.setPhysicalLocator("/customers/{customer_id}/risk");

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertTrue(translation.getRemainingFilters().isEmpty());
        assertEquals("path", translation.getHttpPushdownFilters().get(0).get("location"));
        assertEquals("customer_id", translation.getHttpPushdownFilters().get(0).get("pathVariable"));
    }

    @Test
    void rejectsExplicitPathFieldWhenPhysicalLocatorHasNoPlaceholder() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata("{}", "{}", ""));
        runtime.setPhysicalLocator("/customers/risk");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "path.customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));

        assertTrue(error.getMessage().contains("不符合内置参数下推规则"));
    }

    @Test
    void rejectsAmbiguousNestedBodyLeafMappings() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{}",
                "{}",
                "{\"billing\":{\"customer_id\":\"\"},\"shipping\":{\"customer_id\":\"\"}}"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));

        assertTrue(error.getMessage().contains("同时映射到"));
    }

    @Test
    void keepsUnmappedUnprefixedHttpFieldAsResidualFilter() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "status", DataTypes.STRING(),
                                        "ACTIVE", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertTrue(translation.getAcceptedFilters().isEmpty());
        assertEquals(1, translation.getRemainingFilters().size());
        assertTrue(translation.getHttpPushdownFilters().isEmpty());
    }

    @Test
    void rejectsAmbiguousUnprefixedHttpField() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{\"customer_id\":\"\"}",
                ""));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));

        assertTrue(error.getMessage().contains("请改用 a.<location>.customer_id"));
    }

    @Test
    void rejectsUnsupportedUnprefixedHttpOperatorForVirtualRequestField() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.GREATER_THAN, "customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));

        assertTrue(error.getMessage().contains("仅存在于 ReaderOptions"));
        assertTrue(error.getMessage().contains("不能作为响应结果残留过滤"));
    }

    @Test
    void rejectsUnsupportedExplicitHttpOperator() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.GREATER_THAN,
                                        "param.customer_id", DataTypes.STRING(), "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));

        assertTrue(error.getMessage().contains("不支持操作符"));
    }

    @Test
    void marksConflictingHttpRequestTargetsAsAlwaysFalse() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata("{\"id\":\"\"}", "{}", ""));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Arrays.asList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "param.id", DataTypes.STRING(), "A", DataTypes.STRING()),
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "query.id", DataTypes.STRING(), "B", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(2, translation.getAcceptedFilters().size());
        assertTrue(translation.getRemainingFilters().isEmpty());
        assertTrue(translation.isHttpFilterAlwaysFalse());
    }

    @Test
    void treatsSqlEquivalentNumericHttpTargetValuesAsTheSameCondition() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata("{\"id\":\"\"}", "{}", ""));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Arrays.asList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "param.id", DataTypes.DECIMAL(10, 2),
                                        new BigDecimal("1.0"), DataTypes.DECIMAL(10, 2)),
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "query.id", DataTypes.DECIMAL(10, 2),
                                        new BigDecimal("1.00"), DataTypes.DECIMAL(10, 2))),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(2, translation.getAcceptedFilters().size());
        assertEquals(1, translation.getHttpPushdownFilters().size());
        assertFalse(translation.isHttpFilterAlwaysFalse());
    }

    @Test
    void marksDifferentNumericHttpTargetValuesAsConflicting() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata("{\"id\":\"\"}", "{}", ""));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Arrays.asList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "param.id", DataTypes.DECIMAL(10, 2),
                                        new BigDecimal("1.0"), DataTypes.DECIMAL(10, 2)),
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "query.id", DataTypes.DECIMAL(10, 2),
                                        new BigDecimal("2.00"), DataTypes.DECIMAL(10, 2))),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertTrue(translation.isHttpFilterAlwaysFalse());
    }

    @Test
    void marksEqualsNullAsAlwaysFalseWithoutSendingHttpPredicate() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata("{\"customer_id\":\"\"}", "{}", ""));

        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binaryNullable(BuiltInFunctionDefinitions.EQUALS,
                                        "customer_id", DataTypes.STRING(), null, DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);

        assertEquals(1, translation.getAcceptedFilters().size());
        assertTrue(translation.getHttpPushdownFilters().isEmpty());
        assertTrue(translation.isHttpFilterAlwaysFalse());
    }

    @Test
    void blocksBodyArrayToPreserveRequestShape() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{}",
                "{}",
                "{\"accounts\":[{\"customer_id\":\"C001\"}]}"));
        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(runtime.getModelMetadata());

        assertTrue(config.findByField("accounts").isEmpty());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "body.accounts", DataTypes.STRING(), "[]", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));
        assertTrue(error.getMessage().contains("不符合内置参数下推规则"));
    }

    @Test
    void blocksBodyObjectAncestorToPreserveRequestShape() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{}",
                "{}",
                "{\"filter\":{\"customer_id\":\"\"}}"));
        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(runtime.getModelMetadata());

        assertEquals(1, config.findByField("customer_id").size());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "body.filter", DataTypes.STRING(), "{}", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));
        assertTrue(error.getMessage().contains("不符合内置参数下推规则"));
    }

    @Test
    void blocksXmlBodyAncestorToPreserveRequestShape() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{}",
                "{}",
                "<Envelope><Body><Query><customer_id/></Query></Body></Envelope>"));
        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(runtime.getModelMetadata());

        assertEquals(1, config.findByField("customer_id").size());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "body.Envelope.Body.Query", DataTypes.STRING(), "blocked", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));
        assertTrue(error.getMessage().contains("不符合内置参数下推规则"));
    }

    @Test
    void scansPathMappingsOnlyFromUriPath() {
        Map<String, Object> metadata = httpReaderOptionsMetadata("{}", "{}", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerOptions = (Map<String, Object>) metadata.get("readerOptions");
        readerOptions.put("url", "http://user:id@example.com/customers/static?redirect=:query_id#/{fragment_id}");

        HttpPushdownMappingConfig queryOnly = HttpPushdownMappingConfig.from(metadata);
        assertNull(queryOnly.findByLocationAndField("path", "query_id"));
        assertNull(queryOnly.findByLocationAndField("path", "fragment_id"));

        readerOptions.put("url", "http://example.com/customers/{customer_id}?redirect=:query_id");
        HttpPushdownMappingConfig withPath = HttpPushdownMappingConfig.from(metadata);
        assertEquals("customer_id", withPath.findByLocationAndField("path", "customer_id").getPathVariable());
        assertNull(withPath.findByLocationAndField("path", "query_id"));
    }

    @Test
    void scansPathMappingsFromModelRequestPathWhenPhysicalLocatorIsBlank() {
        Map<String, Object> metadata = httpReaderOptionsMetadata("{}", "{}", "");
        metadata.put("requestPath", "/v2/customers/{customer_id}");

        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(metadata, null);

        assertEquals("customer_id",
                config.findByLocationAndField("path", "customer_id").getPathVariable());
    }

    @Test
    void fillsVirtualTopLevelAndParamQueryAliasesFromPushedHttpContext() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(httpReaderOptionsMetadata("{\"customer_id\":\"\"}", "{}", ""));
        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "customer_id", DataTypes.STRING(), "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);
        runtime.setHttpPushdownFilters(translation.getHttpPushdownFilters());
        String internalField = String.valueOf(translation.getHttpPushdownFilters().get(0).get("field"));
        Map<String, Object> pluginParam = new LinkedHashMap<String, Object>();
        pluginParam.put(internalField, "C001");
        Map<String, Object> pluginRow = new LinkedHashMap<String, Object>();
        pluginRow.put("customer_id", null);
        pluginRow.put(internalField, "C001");
        pluginRow.put("param", pluginParam);

        Map<String, Object> row = StructuredPluginSourceStrategy.attachHttpResultContext(runtime, pluginRow);

        assertEquals("C001", row.get("customer_id"));
        assertFalse(row.containsKey(internalField));
        @SuppressWarnings("unchecked")
        Map<String, Object> param = (Map<String, Object>) row.get("param");
        assertFalse(param.containsKey(internalField));
        assertEquals("C001", param.get("customer_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) row.get("query");
        assertEquals("C001", query.get("customer_id"));
    }

    @Test
    void fillsDottedParamQueryAndHeaderNamesAsFlatFields() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer.id\":\"\"}",
                "{\"X.Trace.Id\":\"\"}",
                ""));
        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Arrays.asList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "param.customer.id", DataTypes.STRING(), "C001", DataTypes.STRING()),
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "header.X.Trace.Id", DataTypes.STRING(), "trace-1", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);
        runtime.setHttpPushdownFilters(translation.getHttpPushdownFilters());

        Map<String, Object> row = StructuredPluginSourceStrategy.attachHttpResultContext(
                runtime, new LinkedHashMap<String, Object>());

        @SuppressWarnings("unchecked")
        Map<String, Object> param = (Map<String, Object>) row.get("param");
        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) row.get("query");
        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) row.get("header");
        assertEquals("C001", param.get("customer.id"));
        assertEquals("C001", query.get("customer.id"));
        assertFalse(param.containsKey("customer"));
        assertFalse(query.containsKey("customer"));
        assertEquals("trace-1", header.get("X.Trace.Id"));
        assertFalse(header.containsKey("X"));
    }

    @Test
    void preservesPhysicalHttpFieldWhileStillFillingSemanticAliases() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        Map<String, Object> metadata = httpReaderOptionsMetadata("{\"customer_id\":\"\"}", "{}", "");
        metadata.put("columns", Collections.singletonList(Collections.singletonMap("name", "customer_id")));
        runtime.setModelMetadata(metadata);
        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "customer_id", DataTypes.STRING(), "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);
        runtime.setHttpPushdownFilters(translation.getHttpPushdownFilters());
        Map<String, Object> pluginRow = new LinkedHashMap<String, Object>();
        pluginRow.put("customer_id", null);

        Map<String, Object> row = StructuredPluginSourceStrategy.attachHttpResultContext(runtime, pluginRow);

        assertNull(row.get("customer_id"));
        assertEquals("C001", ((Map<?, ?>) row.get("param")).get("customer_id"));
        assertEquals("C001", ((Map<?, ?>) row.get("query")).get("customer_id"));
    }

    @Test
    void doesNotPopulateAmbiguousVirtualTopLevelHttpField() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"id\":\"\"}", "{}", "{\"filter\":{\"id\":\"\"}}"));
        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(Arrays.asList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "param.id", DataTypes.STRING(), "query-id", DataTypes.STRING()),
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "body.filter.id", DataTypes.STRING(), "body-id", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);
        runtime.setHttpPushdownFilters(translation.getHttpPushdownFilters());

        Map<String, Object> row = StructuredPluginSourceStrategy.attachHttpResultContext(
                runtime, new LinkedHashMap<String, Object>());

        assertFalse(row.containsKey("id"));
        assertEquals("query-id", ((Map<?, ?>) row.get("param")).get("id"));
        Map<?, ?> body = (Map<?, ?>) row.get("body");
        assertEquals("body-id", ((Map<?, ?>) body.get("filter")).get("id"));
    }

    @Test
    void keepsClarkNamespaceSegmentIntactInHttpResultContext() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        Map<String, Object> filter = new LinkedHashMap<String, Object>();
        filter.put("location", "body");
        filter.put("resultField", "{http://example.com/soap}Envelope.Body.customer_id");
        filter.put("values", Collections.singletonList("C001"));
        runtime.setHttpPushdownFilters(Collections.singletonList(filter));

        Map<String, Object> row = StructuredPluginSourceStrategy.attachHttpResultContext(
                runtime, new LinkedHashMap<String, Object>());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) row.get("body");
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) body.get("{http://example.com/soap}Envelope");
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedBody = (Map<String, Object>) envelope.get("Body");
        assertEquals("C001", nestedBody.get("customer_id"));
    }

    @Test
    void passesHttpSourceMaxRowsThroughInternalExtraParam() throws Exception {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setMaxRows(500);
        BaseDataSourceDTO dto = new BaseDataSourceDTO();

        new StructuredPluginSourceStrategy().attachHttpRuntimeParams(runtime, dto);

        assertEquals("500", dto.getExtraParams().get(StructuredPluginSourceStrategy.HTTP_MAX_ROWS_KEY));
    }

    @Test
    void skipsHttpPluginForAlwaysFalseFilter() throws Exception {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setHttpFilterAlwaysFalse(true);
        int[] emitted = new int[]{0};

        new StructuredPluginSourceStrategy().readRows(runtime, row -> {
            emitted[0]++;
            return true;
        });

        assertEquals(0, emitted[0]);
    }

    @Test
    void rejectsMappedVirtualHttpFieldInsideOr() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));

        ResolvedExpression expression = or(
                binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                        "C001", DataTypes.STRING()),
                binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                        "C002", DataTypes.STRING()));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(expression),
                        runtime,
                        AggregationPluginKind.HTTP));

        assertTrue(error.getMessage().contains("customer_id"));
        assertTrue(error.getMessage().contains("不能作为响应结果残留过滤"));
    }

    @Test
    void rejectsLikeOnMappedVirtualHttpField() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.LIKE, "customer_id", DataTypes.STRING(),
                                        "C%", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));

        assertTrue(error.getMessage().contains("customer_id"));
        assertTrue(error.getMessage().contains("不能作为响应结果残留过滤"));
    }

    @Test
    void rejectsNotAndIsNullOnMappedVirtualHttpField() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));
        List<ResolvedExpression> expressions = Arrays.asList(
                not(binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                        "C001", DataTypes.STRING())),
                unary(BuiltInFunctionDefinitions.IS_NULL, "customer_id", DataTypes.STRING()));

        for (ResolvedExpression expression : expressions) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                    AggregationFilterPushDownTranslator.translate(Collections.singletonList(expression),
                            runtime,
                            AggregationPluginKind.HTTP));
            assertTrue(error.getMessage().contains("不能作为响应结果残留过滤"));
        }
    }

    @Test
    void keepsUnsupportedExpressionsOnPhysicalHttpFieldAsResidualFilters() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        Map<String, Object> metadata = httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                "");
        metadata.put("columns", Collections.singletonList(Collections.singletonMap("name", "customer_id")));
        runtime.setModelMetadata(metadata);
        List<ResolvedExpression> expressions = Arrays.asList(
                binary(BuiltInFunctionDefinitions.GREATER_THAN, "customer_id", DataTypes.STRING(),
                        "C001", DataTypes.STRING()),
                binary(BuiltInFunctionDefinitions.LIKE, "customer_id", DataTypes.STRING(),
                        "C%", DataTypes.STRING()),
                or(binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                                "C001", DataTypes.STRING()),
                        binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                                "C002", DataTypes.STRING())),
                not(binary(BuiltInFunctionDefinitions.EQUALS, "customer_id", DataTypes.STRING(),
                        "C001", DataTypes.STRING())),
                unary(BuiltInFunctionDefinitions.IS_NULL, "customer_id", DataTypes.STRING()));

        for (ResolvedExpression expression : expressions) {
            AggregationFilterPushDownTranslator.Translation translation =
                    AggregationFilterPushDownTranslator.translate(Collections.singletonList(expression),
                            runtime,
                            AggregationPluginKind.HTTP);
            assertTrue(translation.getAcceptedFilters().isEmpty());
            assertEquals(1, translation.getRemainingFilters().size());
            assertTrue(translation.getHttpPushdownFilters().isEmpty());
        }
    }

    @Test
    void buildsHttpLookupPushdownForUniquelyMappedKey() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));

        AggregationLookupFunction.HttpLookupPlan plan = AggregationLookupFunction.buildHttpLookupPlan(
                runtime,
                Collections.singletonList("customer_id"),
                new Object[]{"C001"});

        assertEquals(1, plan.getPushdownFilters().size());
        assertEquals("customer_id", plan.getPushdownFilters().get(0).get("requestParamName"));
        assertTrue(plan.getResidualValues().isEmpty());
    }

    @Test
    void returnsNoMatchWhenStaticHttpPushdownConflictsWithDynamicLookupKey() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));
        AggregationFilterPushDownTranslator.Translation staticPushdown =
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "param.customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP);
        runtime.setHttpPushdownFilters(staticPushdown.getHttpPushdownFilters());

        AggregationLookupFunction.HttpLookupPlan conflicting = AggregationLookupFunction.buildHttpLookupPlan(
                runtime,
                Collections.singletonList("param.customer_id"),
                Collections.singletonList(DataTypes.STRING()),
                new Object[]{"C002"});
        AggregationLookupFunction.HttpLookupPlan matching = AggregationLookupFunction.buildHttpLookupPlan(
                runtime,
                Collections.singletonList("param.customer_id"),
                Collections.singletonList(DataTypes.STRING()),
                new Object[]{"C001"});

        assertTrue(conflicting.isNoMatch());
        assertFalse(matching.isNoMatch());
        assertEquals(1, matching.getPushdownFilters().size());
    }

    @Test
    void normalizesTemporalHttpLookupPushdownValuesByDataType() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"biz_date\":\"\",\"biz_time\":\"\",\"updated_at\":\"\"}",
                "{}",
                ""));

        LocalDate date = LocalDate.of(2026, 7, 12);
        AggregationLookupFunction.HttpLookupPlan plan = AggregationLookupFunction.buildHttpLookupPlan(
                runtime,
                Arrays.asList("biz_date", "biz_time", "updated_at"),
                Arrays.asList(DataTypes.DATE(), DataTypes.TIME(3), DataTypes.TIMESTAMP(3)),
                new Object[]{
                        Math.toIntExact(date.toEpochDay()),
                        (10 * 60 * 60 + 15 * 60 + 30) * 1000 + 123,
                        TimestampData.fromLocalDateTime(LocalDateTime.of(
                                2026, 7, 12, 10, 15, 30, 123_456_789))
                });

        assertEquals(Collections.singletonList("2026-07-12"),
                plan.getPushdownFilters().get(0).get("values"));
        assertEquals(Collections.singletonList("10:15:30.123"),
                plan.getPushdownFilters().get(1).get("values"));
        assertEquals(Collections.singletonList("2026-07-12 10:15:30.123"),
                plan.getPushdownFilters().get(2).get("values"));
    }

    @Test
    void comparesTemporalHttpLookupResidualsByDataType() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(httpReaderOptionsMetadata("{}", "{}", ""));
        LocalDate date = LocalDate.of(2026, 7, 12);

        AggregationLookupFunction.HttpLookupPlan plan = AggregationLookupFunction.buildHttpLookupPlan(
                runtime,
                Arrays.asList("biz_date", "biz_time", "updated_at"),
                Arrays.asList(DataTypes.DATE(), DataTypes.TIME(3), DataTypes.TIMESTAMP(3)),
                new Object[]{
                        Math.toIntExact(date.toEpochDay()),
                        (10 * 60 * 60 + 15 * 60 + 30) * 1000 + 123,
                        TimestampData.fromLocalDateTime(LocalDateTime.of(
                                2026, 7, 12, 10, 15, 30, 123_456_789))
                });
        Map<String, Object> matchingRow = new LinkedHashMap<String, Object>();
        matchingRow.put("biz_date", "2026-07-12 10:15:30");
        matchingRow.put("biz_time", "10:15:30.123999999");
        matchingRow.put("updated_at", "2026-07-12 10:15:30.123999");

        assertTrue(AggregationLookupFunction.matchesResidualLookup(
                matchingRow, plan.getResidualValues(), plan.getResidualTypes()));
        matchingRow.put("updated_at", "2026-07-12 10:15:30.124");
        assertFalse(AggregationLookupFunction.matchesResidualLookup(
                matchingRow, plan.getResidualValues(), plan.getResidualTypes()));
    }

    @Test
    void resolvesCompleteNestedLookupKeyPath() {
        DataType rowType = DataTypes.ROW(
                DataTypes.FIELD("body", DataTypes.ROW(
                        DataTypes.FIELD("customer", DataTypes.ROW(
                                DataTypes.FIELD("id", DataTypes.STRING()))))));

        assertEquals(Collections.singletonList("body.customer.id"),
                AggregationLookupFunction.resolveLookupKeyNames(rowType, new int[][]{{0, 0, 0}}));
    }

    @Test
    void exposesLookupRuntimeProviderForHttpPlugin() {
        AggregationDynamicTableSource source = new AggregationDynamicTableSource(
                AggregationRuntimeHandle.local("http-runtime"),
                "http",
                "bounded",
                100,
                DataTypes.ROW(DataTypes.FIELD("customer_id", DataTypes.STRING())));
        LookupTableSource.LookupRuntimeProvider provider = source.getLookupRuntimeProvider(
                new LookupTableSource.LookupContext() {
                    @Override
                    public int[][] getKeys() {
                        return new int[][]{{0}};
                    }

                    @Override
                    public boolean preferCustomShuffle() {
                        return false;
                    }

                    @Override
                    public <T> org.apache.flink.api.common.typeinfo.TypeInformation<T> createTypeInformation(
                            DataType dataType) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public <T> org.apache.flink.api.common.typeinfo.TypeInformation<T> createTypeInformation(
                            org.apache.flink.table.types.logical.LogicalType logicalType) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public org.apache.flink.table.connector.source.DynamicTableSource.DataStructureConverter
                    createDataStructureConverter(DataType dataType) {
                        throw new UnsupportedOperationException();
                    }
                });

        assertTrue(provider instanceof TableFunctionProvider);
    }

    @Test
    void keepsUnmappedHttpLookupKeyAsResultFilter() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                ""));

        AggregationLookupFunction.HttpLookupPlan plan = AggregationLookupFunction.buildHttpLookupPlan(
                runtime,
                Collections.singletonList("status"),
                new Object[]{"ACTIVE"});

        assertTrue(plan.getPushdownFilters().isEmpty());
        assertEquals("ACTIVE", plan.getResidualValues().get("status"));
        assertTrue(AggregationLookupFunction.matchesResidualLookup(
                Collections.<String, Object>singletonMap("status", "ACTIVE"),
                plan.getResidualValues()));
        assertFalse(AggregationLookupFunction.matchesResidualLookup(
                Collections.<String, Object>singletonMap("status", "INACTIVE"),
                plan.getResidualValues()));
    }

    @Test
    void rejectsHttpLookupBodyPushdownForGetMethod() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setPluginName("http");
        Map<String, Object> metadata = httpReaderOptionsMetadata("{}", "{}", "{\"customer_id\":\"\"}");
        metadata.put("mode", "GET");
        runtime.setModelMetadata(metadata);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationLookupFunction.buildHttpLookupPlan(
                        runtime,
                        Collections.singletonList("body.customer_id"),
                        Collections.singletonList(DataTypes.STRING()),
                        new Object[]{"C001"}));

        assertTrue(error.getMessage().contains("method=GET"));
    }

    @Test
    void emitsEveryMatchingHttpLookupRow() {
        List<Map<String, Object>> collected = new ArrayList<Map<String, Object>>();
        Map<String, Object> residualValues = Collections.<String, Object>singletonMap("status", "ACTIVE");
        List<Map<String, Object>> rows = Arrays.asList(
                new LinkedHashMap<String, Object>(Map.of("id", "1", "status", "ACTIVE")),
                new LinkedHashMap<String, Object>(Map.of("id", "2", "status", "ACTIVE")));

        for (Map<String, Object> row : rows) {
            if (!AggregationLookupFunction.emitLookupMatch(row, residualValues, collected::add)) {
                break;
            }
        }

        assertEquals(2, collected.size());
        assertEquals("1", collected.get(0).get("id"));
        assertEquals("2", collected.get(1).get("id"));
    }

    @Test
    void ignoresConfiguredHttpPushdownMappingsAndKeepsBuiltInRules() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        Map<String, Object> metadata = httpReaderOptionsMetadata(
                "{\"customer_id\":\"\"}",
                "{}",
                "");
        Map<String, Object> configuredMapping = new LinkedHashMap<String, Object>();
        configuredMapping.put("field", "customer_id");
        configuredMapping.put("location", "param");
        configuredMapping.put("supportedOperators", Collections.singletonList(">"));
        metadata.put("httpPushdownMappings", Collections.singletonList(configuredMapping));
        runtime.setModelMetadata(metadata);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.GREATER_THAN, "customer_id", DataTypes.STRING(),
                                        "C001", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));

        assertTrue(error.getMessage().contains("仅存在于 ReaderOptions"));
        assertTrue(error.getMessage().contains("不能作为响应结果残留过滤"));
    }

    @Test
    void excludesStaticAuthenticationFieldsFromBuiltInHttpPushdown() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"customer_id\":\"\",\"api_token\":\"model-token\",\"db_pwd\":\"db-secret\"}",
                "{\"Authorization\":\"Bearer model-secret\",\"X-Auth\":\"auth-secret\",\"Session-Id\":\"session-secret\"}",
                "{\"filter\":{\"customer_id\":\"\",\"proxy_passwd\":\"proxy-secret\"},\"password\":\"body-secret\"}"));

        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(runtime.getModelMetadata());
        assertEquals(2, config.findByField("customer_id").size());
        assertTrue(config.findByField("api_token").isEmpty());
        assertTrue(config.findByField("db_pwd").isEmpty());
        assertTrue(config.findByField("Authorization").isEmpty());
        assertTrue(config.findByField("X-Auth").isEmpty());
        assertTrue(config.findByField("Session-Id").isEmpty());
        assertTrue(config.findByField("password").isEmpty());
        assertTrue(config.findByField("proxy_passwd").isEmpty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS, "header.Authorization", DataTypes.STRING(),
                                        "Bearer sql-secret", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));
        assertTrue(error.getMessage().contains("不符合内置参数下推规则"));
    }

    @Test
    void excludesCommonCloudSignatureFieldsFromBuiltInHttpPushdown() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(httpReaderOptionsMetadata(
                "{\"sig\":\"signature\",\"sas\":\"sas-value\",\"subscription-key\":\"subscription\"}",
                "{\"x-functions-key\":\"function-key\"}",
                ""));

        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(runtime.getModelMetadata());
        for (String field : Arrays.asList("sig", "sas", "subscription-key", "x-functions-key")) {
            assertTrue(config.findByField(field).isEmpty(), field);
            assertTrue(HttpPushdownMappingConfig.isSensitiveRequestField(field), field);
        }

        for (String field : Arrays.asList(
                "param.sig", "param.sas", "param.subscription-key", "header.x-functions-key")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                    AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                    binary(BuiltInFunctionDefinitions.EQUALS,
                                            field, DataTypes.STRING(), "sql-secret", DataTypes.STRING())),
                            runtime,
                            AggregationPluginKind.HTTP));
            assertTrue(error.getMessage().contains("不符合内置参数下推规则"), field);
        }
    }

    @Test
    void rejectsRestXmlProtocolHeaderPushdown() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        Map<String, Object> metadata = httpReaderOptionsMetadata(
                "{}",
                "{\"Content-Type\":\"application/xml\",\"X-Biz-Date\":\"\"}",
                "");
        metadata.put("protocolMode", "REST_XML");
        runtime.setModelMetadata(metadata);

        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(metadata);
        assertTrue(config.findByField("Content-Type").isEmpty());
        assertEquals("X-Biz-Date", config.findByLocationAndField("header", "X-Biz-Date").getHeaderName());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "header.Content-Type", DataTypes.STRING(),
                                        "application/json", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));
        assertTrue(error.getMessage().contains("协议/传输保留头"));
    }

    @Test
    void rejectsSoapProtocolHeaderPushdown() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        Map<String, Object> metadata = httpReaderOptionsMetadata(
                "{}",
                "{\"Content-Type\":\"application/soap+xml\",\"SOAPAction\":\"urn:query\"}",
                "<Envelope><Body><Query/></Body></Envelope>");
        metadata.put("protocolMode", "SOAP");
        metadata.put("soapVersion", "1.2");
        runtime.setModelMetadata(metadata);

        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(metadata);
        assertTrue(config.findByField("Content-Type").isEmpty());
        assertTrue(config.findByField("SOAPAction").isEmpty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AggregationFilterPushDownTranslator.translate(Collections.singletonList(
                                binary(BuiltInFunctionDefinitions.EQUALS,
                                        "header.SOAPAction", DataTypes.STRING(), "urn:override", DataTypes.STRING())),
                        runtime,
                        AggregationPluginKind.HTTP));
        assertTrue(error.getMessage().contains("协议/传输保留头"));
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

    @Test
    void expandsFileModelRootPartitionGlobToConcretePaths() throws Exception {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("rootPath", "/contacts");
        metadata.put("partitionType", "glob");
        metadata.put("partition", "iq_ftp_contacts_multi_*_20260709.csv");
        runtime.setModelMetadata(metadata);
        FakeFileHelper fileHelper = new FakeFileHelper();
        fileHelper.add("/contacts", "iq_ftp_contacts_multi_a_20260709.csv");
        fileHelper.add("/contacts", "iq_ftp_contacts_multi_b_20260709.csv");
        fileHelper.add("/contacts", "other.csv");

        List<ResolvedFilePath> requested = FilePathPushdownResolver.resolve(runtime);
        List<ResolvedFilePath> expanded = FilePathExpansion.expand(fileHelper, runtime, requested.get(0));

        assertEquals(1, requested.size());
        assertEquals("/contacts/iq_ftp_contacts_multi_*_20260709.csv", requested.get(0).getPath());
        assertEquals(Arrays.asList("/contacts/iq_ftp_contacts_multi_a_20260709.csv",
                "/contacts/iq_ftp_contacts_multi_b_20260709.csv"),
                expanded.stream().map(ResolvedFilePath::getPath).collect(Collectors.toList()));
    }

    @Test
    void doesNotDuplicateRelativeRootPathWhenResolvingExactFile() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("rootPath", "ui-http-business/202607110611");
        metadata.put("fileName", "customer_events_202607110611.efile");
        runtime.setModelMetadata(metadata);

        List<ResolvedFilePath> requested = FilePathPushdownResolver.resolve(runtime);

        assertEquals(1, requested.size());
        assertEquals("ui-http-business/202607110611/customer_events_202607110611.efile",
                requested.get(0).getPath());
    }

    @Test
    void skipsExactFilePathWhenSourceReportsMissing() throws Exception {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setModelMetadata(new LinkedHashMap<String, Object>());

        List<ResolvedFilePath> expanded = FilePathExpansion.expand(new FakeFileHelper(), runtime,
                new ResolvedFilePath("/oss/events/2026-07-08.csv", Collections.<String, LocalDate>emptyMap()));

        assertTrue(expanded.isEmpty());
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

    private ResolvedExpression binary(org.apache.flink.table.functions.BuiltInFunctionDefinition function,
                                      ResolvedExpression field,
                                      Object value,
                                      DataType valueType) {
        return CallExpression.permanent(function,
                Arrays.asList(field, new ValueLiteralExpression(value, valueType.notNull())),
                DataTypes.BOOLEAN());
    }

    private ResolvedExpression binaryNullable(org.apache.flink.table.functions.BuiltInFunctionDefinition function,
                                              String fieldName,
                                              DataType fieldType,
                                              Object value,
                                              DataType valueType) {
        return CallExpression.permanent(function,
                Arrays.asList(
                        new FieldReferenceExpression(fieldName, fieldType, 0, 0),
                        new ValueLiteralExpression(value, valueType.nullable())),
                DataTypes.BOOLEAN());
    }

    private ResolvedExpression and(ResolvedExpression left, ResolvedExpression right) {
        return CallExpression.permanent(BuiltInFunctionDefinitions.AND,
                Arrays.asList(left, right),
                DataTypes.BOOLEAN());
    }

    private ResolvedExpression or(ResolvedExpression left, ResolvedExpression right) {
        return CallExpression.permanent(BuiltInFunctionDefinitions.OR,
                Arrays.asList(left, right),
                DataTypes.BOOLEAN());
    }

    private ResolvedExpression not(ResolvedExpression expression) {
        return CallExpression.permanent(BuiltInFunctionDefinitions.NOT,
                Collections.singletonList(expression),
                DataTypes.BOOLEAN());
    }

    private ResolvedExpression unary(org.apache.flink.table.functions.BuiltInFunctionDefinition function,
                                     String fieldName,
                                     DataType fieldType) {
        return CallExpression.permanent(function,
                Collections.singletonList(new FieldReferenceExpression(fieldName, fieldType, 0, 0)),
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

    private Map<String, Object> httpReaderOptionsMetadata(String params, String header, String requestBody) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("params", params);
        readerOptions.put("header", header);
        readerOptions.put("requestBody", requestBody);
        metadata.put("readerOptions", readerOptions);
        if (requestBody != null && !requestBody.trim().isEmpty()) {
            metadata.put("mode", "POST");
        }
        return metadata;
    }

    private AggregationRuntimeHandle runtimeHandle(AggregationDynamicTableSource source) throws Exception {
        Field field = AggregationDynamicTableSource.class.getDeclaredField("runtimeHandle");
        field.setAccessible(true);
        return (AggregationRuntimeHandle) field.get(source);
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

    private static class FakeFileHelper implements FileHelper {
        private final Map<String, Set<String>> files = new LinkedHashMap<String, Set<String>>();

        void add(String dir, String name) {
            files.computeIfAbsent(dir, key -> new HashSet<String>()).add(name);
        }

        @Override
        public boolean exists(String path, String name) {
            Set<String> names = files.get(path);
            return names != null && names.contains(name);
        }

        @Override
        public Set<String> listFile(String dir, String regex) {
            Set<String> result = new HashSet<String>();
            Set<String> names = files.get(dir);
            if (names == null) {
                return result;
            }
            for (String name : names) {
                if (name.matches(regex)) {
                    result.add(name);
                }
            }
            return result;
        }

        @Override
        public boolean isFile(String dir, String fileName) {
            return exists(dir, fileName);
        }

        @Override
        public void mkdir(String filePath) {
        }

        @Override
        public void rm(String path) {
        }

        @Override
        public boolean connect(Configuration configuration) {
            return true;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean mv(String from, String to) {
            return true;
        }

        @Override
        public InputStream getInputStream(String path, String name) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream getOutputStream(String path, String name) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
