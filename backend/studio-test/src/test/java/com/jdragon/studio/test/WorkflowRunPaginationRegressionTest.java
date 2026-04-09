package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowRunPaginationRegressionTest extends StudioApiRegressionTestSupport {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void shouldPageWorkflowRunsForSummaryList() throws Exception {
        insertWorkflowDefinition(101L, "wf_101", "Workflow 101");
        insertWorkflowDefinition(102L, "wf_102", "Workflow 102");
        insertWorkflowRunRecord(3001L, 9001L, 101L, "SUCCESS", LocalDateTime.of(2026, 4, 7, 12, 0, 0));
        insertWorkflowRunRecord(3002L, 9002L, 101L, "FAILED", LocalDateTime.of(2026, 4, 7, 12, 5, 0));
        insertWorkflowRunRecord(3003L, 9003L, 102L, "RUNNING", LocalDateTime.of(2026, 4, 7, 12, 10, 0));

        MvcResult firstPageResult = mockMvc.perform(get("/api/v1/workflow-runs")
                        .header("Authorization", adminAuthorizationHeader())
                        .accept(MediaType.APPLICATION_JSON)
                        .param("pageNo", "1")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstPage = readBody(firstPageResult).path("data");
        assertThat(firstPage.path("total").asInt()).isEqualTo(3);
        assertThat(firstPage.path("items")).hasSize(2);
        assertThat(firstPage.path("items").get(0).path("workflowRunId").asLong()).isEqualTo(9003L);
        assertThat(firstPage.path("items").get(1).path("workflowRunId").asLong()).isEqualTo(9002L);

        MvcResult secondPageResult = mockMvc.perform(get("/api/v1/workflow-runs")
                        .header("Authorization", adminAuthorizationHeader())
                        .accept(MediaType.APPLICATION_JSON)
                        .param("pageNo", "2")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode secondPage = readBody(secondPageResult).path("data");
        assertThat(secondPage.path("total").asInt()).isEqualTo(3);
        assertThat(secondPage.path("items")).hasSize(1);
        assertThat(secondPage.path("items").get(0).path("workflowRunId").asLong()).isEqualTo(9001L);
    }

    @Test
    void shouldFilterWorkflowRunsBySummaryStatus() throws Exception {
        insertWorkflowDefinition(201L, "wf_201", "Workflow 201");
        insertWorkflowDefinition(202L, "wf_202", "Workflow 202");
        insertWorkflowRunRecord(4001L, 9101L, 201L, "SUCCESS", LocalDateTime.of(2026, 4, 7, 13, 0, 0));
        insertWorkflowRunRecord(4002L, 9102L, 201L, "FAILED", LocalDateTime.of(2026, 4, 7, 13, 5, 0));
        insertWorkflowRunRecord(4003L, 9103L, 202L, "SUCCESS", LocalDateTime.of(2026, 4, 7, 13, 10, 0));

        MvcResult failedOnlyResult = mockMvc.perform(get("/api/v1/workflow-runs")
                        .header("Authorization", adminAuthorizationHeader())
                        .accept(MediaType.APPLICATION_JSON)
                        .param("status", "FAILED")
                        .param("pageNo", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode failedOnly = readBody(failedOnlyResult).path("data");
        assertThat(failedOnly.path("total").asInt()).isEqualTo(1);
        assertThat(failedOnly.path("items")).hasSize(1);
        assertThat(failedOnly.path("items").get(0).path("workflowRunId").asLong()).isEqualTo(9102L);
        assertThat(failedOnly.path("items").get(0).path("status").asText()).isEqualTo("FAILED");

        MvcResult successOnlyResult = mockMvc.perform(get("/api/v1/workflow-runs")
                        .header("Authorization", adminAuthorizationHeader())
                        .accept(MediaType.APPLICATION_JSON)
                        .param("status", "SUCCESS")
                        .param("pageNo", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode successOnly = readBody(successOnlyResult).path("data");
        assertThat(successOnly.path("total").asInt()).isEqualTo(2);
        assertThat(successOnly.path("items")).hasSize(2);
        assertThat(successOnly.path("items").get(0).path("workflowRunId").asLong()).isEqualTo(9103L);
        assertThat(successOnly.path("items").get(1).path("workflowRunId").asLong()).isEqualTo(9101L);
    }

    private void insertWorkflowDefinition(Long definitionId, String code, String name) {
        String timestamp = FORMATTER.format(LocalDateTime.now());
        jdbcTemplate.update("insert into workflow_definition (id, tenant_id, deleted, created_at, updated_at, code, name, current_version_id, published) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                definitionId, "default", 0, timestamp, timestamp, code, name, null, 1);
    }

    private void insertWorkflowRunRecord(Long recordId,
                                         Long workflowRunId,
                                         Long workflowDefinitionId,
                                         String status,
                                         LocalDateTime startedAt) {
        String timestamp = FORMATTER.format(startedAt);
        jdbcTemplate.update("insert into run_record (id, tenant_id, deleted, created_at, updated_at, execution_type, workflow_run_id, workflow_definition_id, workflow_version_id, collection_task_id, node_code, status, worker_code, message, started_at, ended_at, log_file_path, log_size_bytes, log_charset, payload_json, result_json) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                recordId,
                "default",
                0,
                timestamp,
                timestamp,
                "WORKFLOW_NODE",
                workflowRunId,
                workflowDefinitionId,
                null,
                null,
                "node_" + recordId,
                status,
                "worker-test",
                status + " message",
                timestamp,
                timestamp,
                null,
                null,
                null,
                "{}",
                "{}");
    }
}
