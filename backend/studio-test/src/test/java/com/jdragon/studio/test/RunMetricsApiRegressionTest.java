package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RunMetricsApiRegressionTest extends StudioApiRegressionTestSupport {

    @Autowired
    private CollectionTaskDefinitionMapper collectionTaskDefinitionMapper;

    @Autowired
    private RunRecordMapper runRecordMapper;

    @Test
    void shouldExposeTypedMetricSummaryAndAggregateRunMetricDashboard() throws Exception {
        Long defaultProjectId = jdbcTemplate.queryForObject(
                "select id from studio_project where default_project = 1 limit 1",
                Long.class
        );
        assertThat(defaultProjectId).isNotNull();

        CollectionTaskDefinitionEntity task = new CollectionTaskDefinitionEntity();
        task.setId(31001L);
        task.setTenantId("default");
        task.setProjectId(defaultProjectId);
        task.setName("Orders Sync");
        task.setTaskType("SINGLE_TABLE");
        task.setStatus("ONLINE");
        task.setSourceCount(1);
        task.setSourceBindingsJson(sourceBindings());
        task.setTargetBindingJson(targetBinding());
        task.setExecutionOptionsJson(new LinkedHashMap<String, Object>());
        collectionTaskDefinitionMapper.insert(task);

        RunRecordEntity preciseRun = new RunRecordEntity();
        preciseRun.setId(41001L);
        preciseRun.setTenantId("default");
        preciseRun.setProjectId(defaultProjectId);
        preciseRun.setCollectionTaskId(task.getId());
        preciseRun.setExecutionType("COLLECTION_TASK");
        preciseRun.setNodeCode("collection_task_31001");
        preciseRun.setStatus("SUCCESS");
        preciseRun.setWorkerCode("worker-01");
        preciseRun.setMessage("Run completed");
        preciseRun.setStartedAt(LocalDateTime.of(2026, 4, 10, 10, 0, 0));
        preciseRun.setEndedAt(LocalDateTime.of(2026, 4, 10, 10, 5, 0));
        preciseRun.setCollectedRecords(12L);
        preciseRun.setReadSucceedRecords(10L);
        preciseRun.setReadFailedRecords(2L);
        preciseRun.setWriteSucceedRecords(11L);
        preciseRun.setWriteFailedRecords(1L);
        preciseRun.setFailedRecords(3L);
        preciseRun.setTransformerTotalRecords(12L);
        preciseRun.setTransformerSuccessRecords(9L);
        preciseRun.setTransformerFailedRecords(1L);
        preciseRun.setTransformerFilterRecords(2L);
        preciseRun.setResultJson(summaryPayload(12L, 10L, 2L, 11L, 1L, 3L, 12L, 9L, 1L, 2L));
        preciseRun.setPayloadJson(summaryPayload(12L, 10L, 2L, 11L, 1L, 3L, 12L, 9L, 1L, 2L));
        runRecordMapper.insert(preciseRun);

        RunRecordEntity legacyRun = new RunRecordEntity();
        legacyRun.setId(41002L);
        legacyRun.setTenantId("default");
        legacyRun.setProjectId(defaultProjectId);
        legacyRun.setCollectionTaskId(task.getId());
        legacyRun.setExecutionType("COLLECTION_TASK");
        legacyRun.setNodeCode("collection_task_31001");
        legacyRun.setStatus("SUCCESS");
        legacyRun.setWorkerCode("worker-02");
        legacyRun.setMessage("Legacy run");
        legacyRun.setStartedAt(LocalDateTime.of(2026, 4, 9, 9, 0, 0));
        legacyRun.setEndedAt(LocalDateTime.of(2026, 4, 9, 9, 3, 0));
        legacyRun.setResultJson(legacySummaryPayload());
        legacyRun.setPayloadJson(legacySummaryPayload());
        runRecordMapper.insert(legacyRun);

        String authorization = adminAuthorizationHeader();

        MvcResult runListResult = mockMvc.perform(get("/api/v1/runs")
                        .header("Authorization", authorization)
                        .param("collectionTaskId", String.valueOf(task.getId())))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode runRecords = readBody(runListResult).path("data").path("runRecords");
        assertThat(runRecords).hasSize(2);
        assertThat(findRunRecord(runRecords, "41001").path("metricSummary").path("successRecords").asLong()).isEqualTo(9L);
        assertThat(findRunRecord(runRecords, "41001").path("metricSummary").path("writeSucceedRecords").asLong()).isEqualTo(11L);
        assertThat(findRunRecord(runRecords, "41002").path("metricSummary").path("collectedRecords").asLong()).isEqualTo(8L);

        mockMvc.perform(get("/api/v1/runs/{id}", preciseRun.getId())
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metricSummary.collectedRecords").value(12))
                .andExpect(jsonPath("$.data.metricSummary.successRecords").value(9))
                .andExpect(jsonPath("$.data.metricSummary.writeSucceedRecords").value(11))
                .andExpect(jsonPath("$.data.metricSummary.transformerFilterRecords").value(2));

        mockMvc.perform(get("/api/v1/run-metrics/options")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.datasources.length()").value(2))
                .andExpect(jsonPath("$.data.sourceModels.length()").value(1))
                .andExpect(jsonPath("$.data.targetModels.length()").value(1));

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("startTime", "2026-04-08T00:00:00");
        payload.put("endTime", "2026-04-11T23:59:59");
        payload.put("granularity", "DAY");
        payload.put("topN", Integer.valueOf(5));

        MvcResult dashboardResult = mockMvc.perform(post("/api/v1/run-metrics/query")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.legacyRunCount").value(1))
                .andExpect(jsonPath("$.data.sourceDatasourceTopN[0].count").value(10))
                .andExpect(jsonPath("$.data.targetDatasourceTopN[0].count").value(9))
                .andExpect(jsonPath("$.data.sourceModelTopN[0].count").value(10))
                .andExpect(jsonPath("$.data.targetModelTopN[0].count").value(9))
                .andReturn();

        JsonNode body = readBody(dashboardResult);
        JsonNode trendSeries = body.path("data").path("trend").path("series");
        assertThat(trendSeries).hasSize(7);
        assertThat(trendSeries.get(0).path("key").asText()).isEqualTo("collectedRecords");
        assertThat(trendSeries.get(0).path("data")).anySatisfy(node -> assertThat(node.asLong()).isEqualTo(12L));
        assertThat(findSeries(trendSeries, "successRecords").path("data")).anySatisfy(node -> assertThat(node.asLong()).isEqualTo(9L));
    }

    private List<Map<String, Object>> sourceBindings() {
        List<Map<String, Object>> bindings = new ArrayList<Map<String, Object>>();
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("sourceAlias", "src1");
        source.put("datasourceId", 501L);
        source.put("datasourceName", "mysql_source");
        source.put("datasourceTypeCode", "mysql8");
        source.put("modelId", 601L);
        source.put("modelName", "orders_src");
        source.put("modelPhysicalLocator", "mock_data.orders_src");
        bindings.add(source);
        return bindings;
    }

    private Map<String, Object> targetBinding() {
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("datasourceId", 502L);
        target.put("datasourceName", "mysql_target");
        target.put("datasourceTypeCode", "mysql8");
        target.put("modelId", 602L);
        target.put("modelName", "orders_target");
        target.put("modelPhysicalLocator", "mock_data_target.orders_target");
        return target;
    }

    private Map<String, Object> summaryPayload(long collected,
                                               long readSucceed,
                                               long readFailed,
                                               long writeSucceed,
                                               long writeFailed,
                                               long failed,
                                               long transformerTotal,
                                               long transformerSuccess,
                                               long transformerFailed,
                                               long transformerFilter) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("collectedRecords", collected);
        summary.put("readSucceedRecords", readSucceed);
        summary.put("readFailedRecords", readFailed);
        summary.put("writeSucceedRecords", writeSucceed);
        summary.put("writeFailedRecords", writeFailed);
        summary.put("failedRecords", failed);
        summary.put("transformerTotalRecords", transformerTotal);
        summary.put("transformerSuccessRecords", transformerSuccess);
        summary.put("transformerFailedRecords", transformerFailed);
        summary.put("transformerFilterRecords", transformerFilter);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("summary", summary);
        return payload;
    }

    private Map<String, Object> legacySummaryPayload() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("totalRecords", 8L);
        summary.put("errorRecords", 1L);
        summary.put("transformerSuccess", 6L);
        summary.put("transformerError", 1L);
        summary.put("transformerFilter", 1L);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("summary", summary);
        return payload;
    }

    private JsonNode findRunRecord(JsonNode records, String id) {
        for (JsonNode record : records) {
            if (id.equals(record.path("id").asText())) {
                return record;
            }
        }
        throw new AssertionError("Run record not found: " + id);
    }

    private JsonNode findSeries(JsonNode seriesList, String key) {
        for (JsonNode series : seriesList) {
            if (key.equals(series.path("key").asText())) {
                return series;
            }
        }
        throw new AssertionError("Series not found: " + key);
    }
}
