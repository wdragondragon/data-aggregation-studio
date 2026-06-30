package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.core.enums.State;
import com.jdragon.aggregation.core.job.JobContainer;
import com.jdragon.aggregation.core.statistics.communication.Communication;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.DataIngestionStatus;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.dto.model.DataIngestionSourceBinding;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataIngestionInvocationLogSupportTest {

    @Test
    void shouldExposeReadableRootCauseWithoutStackTrace() {
        RuntimeException failure = new RuntimeException("Job failed",
                new SQLException("逐行写入失败，共 1 条记录写入失败",
                        new SQLIntegrityConstraintViolationException("Duplicate entry 'M05DUP0620091956' for key 'PRIMARY'")));

        String message = DataIngestionExecutionSupport.safeFailureMessage(failure);

        assertEquals("Duplicate entry 'M05DUP0620091956' for key 'PRIMARY'", message);
    }

    @Test
    void shouldStripStackTraceFromFlattenedFailureMessage() {
        RuntimeException failure = new RuntimeException("Data ingestion write failed: java.sql.SQLException: 逐行写入失败\r\n"
                + "\tat com.jdragon.aggregation.rdbms.writer.CommonRdbmsWriter.doOneInsert(CommonRdbmsWriter.java:530)\r\n"
                + "Caused by: java.sql.SQLIntegrityConstraintViolationException: Duplicate entry 'M05DUP' for key 'PRIMARY'\r\n"
                + "\tat com.mysql.cj.jdbc.ClientPreparedStatement.execute(ClientPreparedStatement.java:354)");

        String message = DataIngestionExecutionSupport.safeFailureMessage(failure);

        assertEquals("Duplicate entry 'M05DUP' for key 'PRIMARY'", message);
    }

    @Test
    void shouldMaskSensitiveInvocationLogFieldNameVariants() {
        String raw = "{\"password\":\"plain-password\","
                + "\"token\":\"plain-token\","
                + "\"secretToken\":\"plain-secret-token\","
                + "\"clientSecret\":\"plain-client-secret\","
                + "\"api_key\":\"plain-api-key\","
                + "\"access-key\":\"plain-access-key\","
                + "\"credentialValue\":\"plain-credential\","
                + "\"customerName\":\"长期回归客户\"}"
                + System.lineSeparator()
                + "clientSecret=plain-assignment-secret api_key=plain-assignment-api-key";

        String sanitized = OpenServiceInvocationLogSupport.sanitizeSensitiveLog(raw);

        assertTrue(!sanitized.contains("plain-password"));
        assertTrue(!sanitized.contains("plain-token"));
        assertTrue(!sanitized.contains("plain-secret-token"));
        assertTrue(!sanitized.contains("plain-client-secret"));
        assertTrue(!sanitized.contains("plain-api-key"));
        assertTrue(!sanitized.contains("plain-access-key"));
        assertTrue(!sanitized.contains("plain-credential"));
        assertTrue(!sanitized.contains("plain-assignment-secret"));
        assertTrue(!sanitized.contains("plain-assignment-api-key"));
        assertContains(sanitized, "\"customerName\":\"长期回归客户\"");
    }

    @Test
    void shouldMaskSensitiveSoapElementAndAttributeValues() {
        String raw = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "apiKey=\"plain-xml-attribute-key\">"
                + "<soap:Header>"
                + "<tns:protocolConversionToken>plain-soap-token</tns:protocolConversionToken>"
                + "<tns:clientSecret>plain-soap-client-secret</tns:clientSecret>"
                + "</soap:Header>"
                + "<soap:Body>"
                + "<tns:submitCustomerTraceRequest>"
                + "<traceId>LT-S15-SOAP-TRACE</traceId>"
                + "<customerName>长期回归S15客户SOAP脱敏</customerName>"
                + "<password>plain-soap-password</password>"
                + "<apiKey>plain-soap-api-key</apiKey>"
                + "</tns:submitCustomerTraceRequest>"
                + "</soap:Body>"
                + "</soap:Envelope>";

        String sanitized = OpenServiceInvocationLogSupport.sanitizeSensitiveLog(raw);

        assertTrue(!sanitized.contains("plain-xml-attribute-key"));
        assertTrue(!sanitized.contains("plain-soap-token"));
        assertTrue(!sanitized.contains("plain-soap-client-secret"));
        assertTrue(!sanitized.contains("plain-soap-password"));
        assertTrue(!sanitized.contains("plain-soap-api-key"));
        assertContains(sanitized, "apiKey=\"******\"");
        assertContains(sanitized, "<tns:protocolConversionToken>******</tns:protocolConversionToken>");
        assertContains(sanitized, "<tns:clientSecret>******</tns:clientSecret>");
        assertContains(sanitized, "<password>******</password>");
        assertContains(sanitized, "<apiKey>******</apiKey>");
        assertContains(sanitized, "<customerName>长期回归S15客户SOAP脱敏</customerName>");
        assertContains(sanitized, "<traceId>LT-S15-SOAP-TRACE</traceId>");
    }

    @Test
    void shouldCaptureJobContainerAndTaskThreadLogsForInvocation() {
        configureAggregationHome();
        DataIngestionExecutionSupport executionSupport = new DataIngestionExecutionSupport(
                new ConsoleWriterAssembler(),
                new ObjectMapper());
        DataIngestionInvocationLogSupport logSupport = new DataIngestionInvocationLogSupport();
        Long jobId = Long.valueOf(2026052901L);
        String requestId = "request-" + jobId;

        DataIngestionServiceView service = new DataIngestionServiceView();
        service.setStatus(DataIngestionStatus.ONLINE);
        service.setServiceCode("log_capture_test");
        service.setDatasourceId(Long.valueOf(1L));
        service.setModelId(Long.valueOf(1L));
        service.setMaxBatchSize(Integer.valueOf(10));
        service.setWriterOptions(new LinkedHashMap<String, Object>());

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "Alice");
        body.put("age", Integer.valueOf(31));

        DataIngestionInvocationLogSupport.LogScope scope = logSupport.open(requestId, jobId);
        DataIngestionInvokeResult result;
        try {
            result = executionSupport.execute(service,
                    Arrays.asList(mapping("name"), mapping("age")),
                    new LinkedHashMap<String, Object>(),
                    new LinkedHashMap<String, Object>(),
                    new LinkedHashMap<String, Object>(),
                    body,
                    requestId,
                    jobId,
                    requestId,
                    true);
        } finally {
            scope.close();
        }

        assertEquals(Long.valueOf(1L), result.getSuccessCount());
        String capturedLog = scope.content();
        assertContains(capturedLog, "DataIngestion-JobContainer-" + jobId);
        assertContains(capturedLog, "Starting data ingestion JobContainer");
        assertContains(capturedLog, "start job from configuration");
        assertContains(capturedLog, "job writer init start");
        assertContains(capturedLog, "job reader init start");
        assertContains(capturedLog, "DataAggregation-Thread-writer-" + jobId);
        assertContains(capturedLog, "DataAggregation-Thread-reporter-" + jobId);
        assertContains(capturedLog, "Alice");
        assertContains(capturedLog, "\"secretKey\":\"******\"");
        assertTrue(!capturedLog.contains("plain-secret"), "Captured task logs must not persist secret values");
    }

    @Test
    void shouldWaitForSlowSuccessfulWriteBeyondThreshold() {
        configureAggregationHome();
        DataIngestionExecutionSupport executionSupport = new DataIngestionExecutionSupport(
                new ConsoleWriterAssembler(),
                new ObjectMapper(),
                25L);
        long startedAt = System.currentTimeMillis();

        executionSupport.startAndAssertJob(new SlowSuccessfulJobContainer(100L),
                "request-slow-success",
                Long.valueOf(2026062101L),
                null);

        assertTrue(System.currentTimeMillis() - startedAt >= 75L,
                "Slow successful writes should wait for the final job state instead of returning timeout");
    }

    @Test
    void shouldParseMultipleSourcePathsFromSameJsonBody() {
        DataIngestionExecutionSupport executionSupport = new DataIngestionExecutionSupport(
                new ConsoleWriterAssembler(),
                new ObjectMapper());
        Map<String, Object> firstOrder = row("orderNo", "O-1");
        Map<String, Object> secondOrder = row("orderNo", "O-2");
        Map<String, Object> customer = row("name", "Alice");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("orders", Arrays.asList(firstOrder, secondOrder));
        payload.put("customer", customer);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("data", payload);

        List<Map<String, Object>> orderRows = executionSupport.parseSourceRows(
                binding("orders", 1L, 1L, "data.orders", mapping("orderNo")),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                body,
                Arrays.asList(mapping("orderNo")));
        List<Map<String, Object>> customerRows = executionSupport.parseSourceRows(
                binding("customer", 2L, 2L, "data.customer", mapping("name")),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                body,
                Arrays.asList(mapping("name")));

        assertEquals(2, orderRows.size());
        assertEquals("O-1", orderRows.get(0).get("orderNo"));
        assertEquals("O-2", orderRows.get(1).get("orderNo"));
        assertEquals(1, customerRows.size());
        assertEquals("Alice", customerRows.get(0).get("name"));
    }

    @Test
    void shouldExecuteMixedHeaderQueryFormAndBodyPathMappings() {
        configureAggregationHome();
        DataIngestionExecutionSupport executionSupport = new DataIngestionExecutionSupport(
                new ConsoleWriterAssembler(),
                new ObjectMapper());
        DataIngestionServiceView service = serviceView();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("items", Arrays.asList(row("name", "Alice")));
        body.put("data", payload);

        DataIngestionInvokeResult result = executionSupport.executeBindings(service,
                Arrays.asList(binding("mixed", 1L, 1L, "data.items",
                        mapping(DataIngestionSourcePosition.BODY, "name", "name"),
                        mapping(DataIngestionSourcePosition.HEADER, "trace_id", "trace_id"),
                        mapping(DataIngestionSourcePosition.QUERY, "tenant", "tenant"),
                        mapping(DataIngestionSourcePosition.FORM, "phone", "phone"))),
                row("trace_id", "T-1"),
                row("tenant", "default"),
                row("phone", "13800000000"),
                body,
                "request-mixed-source",
                Long.valueOf(2026063001L),
                null,
                true);

        assertEquals("SUCCESS", result.getStatus());
        assertEquals(Long.valueOf(1L), result.getReceivedCount());
        assertEquals(Long.valueOf(1L), result.getSuccessCount());
        assertEquals(Long.valueOf(0L), result.getFailedCount());
        assertEquals(1, result.getSourceResults().size());
    }

    @Test
    void shouldContinueFollowingSourceWhenOneBindingFails() {
        configureAggregationHome();
        DataIngestionExecutionSupport executionSupport = new DataIngestionExecutionSupport(
                new FailFirstConsoleWriterAssembler(),
                new ObjectMapper());
        DataIngestionServiceView service = serviceView();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("bad", Arrays.asList(row("name", "bad")));
        data.put("good", Arrays.asList(row("name", "good")));
        body.put("data", data);

        DataIngestionInvokeResult result = executionSupport.executeBindings(service,
                Arrays.asList(
                        binding("bad", 999L, 1L, "data.bad", mapping("name")),
                        binding("good", 1L, 1L, "data.good", mapping("name"))),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                body,
                "request-partial-source",
                Long.valueOf(2026063002L),
                null,
                true);

        assertEquals("PARTIAL_SUCCESS", result.getStatus());
        assertEquals(Long.valueOf(2L), result.getReceivedCount());
        assertEquals(Long.valueOf(1L), result.getSuccessCount());
        assertEquals(Long.valueOf(1L), result.getFailedCount());
        assertEquals("FAILED", result.getSourceResults().get(0).getStatus());
        assertEquals("SUCCESS", result.getSourceResults().get(1).getStatus());
        assertTrue(!result.getSourceResults().get(0).getMessage().contains(" at "),
                "Source failure message must not expose stack frames");
    }

    private static DataIngestionFieldMapping mapping(String field) {
        DataIngestionFieldMapping mapping = new DataIngestionFieldMapping();
        mapping.setSourcePosition(DataIngestionSourcePosition.BODY);
        mapping.setSourceField(field);
        mapping.setTargetField(field);
        mapping.setValueType("age".equals(field) ? FieldValueType.INTEGER : FieldValueType.STRING);
        mapping.setRequired(Boolean.TRUE);
        return mapping;
    }

    private static DataIngestionFieldMapping mapping(DataIngestionSourcePosition position, String sourceField, String targetField) {
        DataIngestionFieldMapping mapping = mapping(targetField);
        mapping.setSourcePosition(position);
        mapping.setSourceField(sourceField);
        mapping.setTargetField(targetField);
        mapping.setValueType(FieldValueType.STRING);
        return mapping;
    }

    private static DataIngestionSourceBinding binding(String sourceCode,
                                                       Long datasourceId,
                                                       Long modelId,
                                                       String sourcePath,
                                                       DataIngestionFieldMapping... mappings) {
        DataIngestionSourceBinding binding = new DataIngestionSourceBinding();
        binding.setSourceCode(sourceCode);
        binding.setSourceName(sourceCode);
        binding.setSourcePosition(DataIngestionSourcePosition.BODY);
        binding.setSourcePath(sourcePath);
        binding.setDatasourceId(datasourceId);
        binding.setDatasourceName("datasource-" + datasourceId);
        binding.setModelId(modelId);
        binding.setModelName("model-" + modelId);
        binding.setFieldMappings(Arrays.asList(mappings));
        binding.setWriterOptions(new LinkedHashMap<String, Object>());
        binding.setEnabled(Boolean.TRUE);
        return binding;
    }

    private static DataIngestionServiceView serviceView() {
        DataIngestionServiceView service = new DataIngestionServiceView();
        service.setStatus(DataIngestionStatus.ONLINE);
        service.setServiceCode("multi_source_test");
        service.setMaxBatchSize(Integer.valueOf(10));
        return service;
    }

    private static Map<String, Object> row(String key, Object value) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put(key, value);
        return row;
    }

    private static void configureAggregationHome() {
        Path aggregationHome = locateAggregationHome();
        String normalizedHome = aggregationHome.toAbsolutePath().normalize().toString();
        System.setProperty("aggregation.home", normalizedHome);
        SystemConstants.HOME = normalizedHome;
        SystemConstants.PLUGIN_HOME = aggregationHome.resolve("plugin").toAbsolutePath().normalize().toString();
        SystemConstants.CORE_CONFIG = aggregationHome.resolve("conf").resolve("core.json").toAbsolutePath().normalize().toString();
    }

    private static Path locateAggregationHome() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("package_all").resolve("aggregation");
            if (Files.isDirectory(candidate.resolve("plugin").resolve("writer").resolve("consolewriter"))) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate package_all/aggregation for JobContainer log test");
    }

    private static void assertContains(String value, String expected) {
        assertTrue(value != null && value.contains(expected),
                "Expected captured JobContainer log to contain [" + expected + "]");
    }

    private static class ConsoleWriterAssembler extends CollectionTaskAssemblerService {

        private ConsoleWriterAssembler() {
            super(null, null, null, null);
        }

        @Override
        public Map<String, Object> assembleWriter(Long datasourceId,
                                                  Long modelId,
                                                  List<String> targetFields,
                                                  Map<String, Object> writerOptions) {
            Map<String, Object> writer = new LinkedHashMap<String, Object>();
            writer.put("type", "console");
            Map<String, Object> config = new LinkedHashMap<String, Object>();
            config.put("secretKey", "plain-secret");
            writer.put("config", config);
            return writer;
        }
    }

    private static final class FailFirstConsoleWriterAssembler extends ConsoleWriterAssembler {
        @Override
        public Map<String, Object> assembleWriter(Long datasourceId,
                                                  Long modelId,
                                                  List<String> targetFields,
                                                  Map<String, Object> writerOptions) {
            if (Long.valueOf(999L).equals(datasourceId)) {
                throw new RuntimeException("Writer failed\r\n"
                        + "\tat com.example.Writer.write(Writer.java:10)\r\n"
                        + "Caused by: java.lang.IllegalStateException: simulated write failure");
            }
            return super.assembleWriter(datasourceId, modelId, targetFields, writerOptions);
        }
    }

    private static final class SlowSuccessfulJobContainer extends JobContainer {
        private final long sleepMs;

        private SlowSuccessfulJobContainer(long sleepMs) {
            super(Configuration.newDefault());
            this.sleepMs = sleepMs;
        }

        @Override
        public void start() {
            Communication communication = new Communication();
            communication.setTimestamp(System.currentTimeMillis());
            communication.setState(State.RUNNING);
            getJobPointReporter().setTrackCommunication(communication);
            try {
                Thread.sleep(sleepMs);
                communication.setState(State.SUCCEEDED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                communication.setThrowable(e);
                communication.setState(State.FAILED);
                throw new RuntimeException(e);
            }
        }
    }
}
