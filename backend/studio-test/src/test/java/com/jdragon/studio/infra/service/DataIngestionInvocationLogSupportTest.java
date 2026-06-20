package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.DataIngestionStatus;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
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

    private static DataIngestionFieldMapping mapping(String field) {
        DataIngestionFieldMapping mapping = new DataIngestionFieldMapping();
        mapping.setSourcePosition(DataIngestionSourcePosition.BODY);
        mapping.setSourceField(field);
        mapping.setTargetField(field);
        mapping.setValueType("age".equals(field) ? FieldValueType.INTEGER : FieldValueType.STRING);
        mapping.setRequired(Boolean.TRUE);
        return mapping;
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

    private static final class ConsoleWriterAssembler extends CollectionTaskAssemblerService {

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
}
