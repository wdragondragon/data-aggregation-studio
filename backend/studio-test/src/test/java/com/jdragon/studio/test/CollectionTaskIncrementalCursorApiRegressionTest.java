package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.infra.service.CollectionTaskIncrementalStateService;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CollectionTaskIncrementalCursorApiRegressionTest extends StudioApiRegressionTestSupport {

    @Autowired
    private CollectionTaskIncrementalStateService incrementalStateService;

    @Test
    void collectionTaskCursorShouldBeSystemOwnedPreviewedAndResettable() throws Exception {
        JsonNode loginBody = loginAsAdmin();
        String authorization = adminAuthorizationHeader(loginBody);
        Long projectId = currentProjectId(loginBody);
        Long runtimeClusterId = createAndAuthorizeTestRuntimeCluster(authorization, projectId);
        Long sourceDatasourceId = createDatasource(authorization, projectId, runtimeClusterId, "cursor_source");
        Long targetDatasourceId = createDatasource(authorization, projectId, runtimeClusterId, "cursor_target");
        Long sourceModelId = createModel(authorization, projectId, sourceDatasourceId, "source_table");
        Long targetModelId = createModel(authorization, projectId, targetDatasourceId, "target_table");

        JsonNode created = saveTask(authorization, projectId,
                taskPayload(null, runtimeClusterId, sourceDatasourceId, sourceModelId,
                        targetDatasourceId, targetModelId, "id", Long.valueOf(999L)));
        Long taskId = created.path("data").path("id").asLong();
        assertNullish(cursorValue(created));
        awaitIndexQueueIdle();

        updateCursor(taskId, "id", Long.valueOf(42L), "LONG", Long.valueOf(77L));

        JsonNode updated = getTask(authorization, projectId, taskId);
        assertCursorLong(updated, 42L);
        assertThat(updated.path("data").path("sourceBindings").path(0).path("incremental").path("lastRunRecordId").asLong())
                .isEqualTo(77L);
        assertCursorStateValue(updated, "id", 42L);

        JsonNode savedWithIncomingCursor = saveTask(authorization, projectId,
                taskPayload(taskId, runtimeClusterId, sourceDatasourceId, sourceModelId,
                        targetDatasourceId, targetModelId, "id", Long.valueOf(999L)));
        assertCursorLong(savedWithIncomingCursor, 42L);
        assertCursorStateValue(savedWithIncomingCursor, "id", 42L);

        JsonNode preview = previewTask(authorization, projectId,
                taskPayload(taskId, runtimeClusterId, sourceDatasourceId, sourceModelId,
                        targetDatasourceId, targetModelId, "id", Long.valueOf(999L)));
        assertThat(preview.path("data").path("reader").path("config").path("pkValue").asLong()).isEqualTo(42L);

        JsonNode savedAfterColumnChange = saveTask(authorization, projectId,
                taskPayload(taskId, runtimeClusterId, sourceDatasourceId, sourceModelId,
                        targetDatasourceId, targetModelId, "updated_at", Long.valueOf(1000L)));
        assertNullish(cursorValue(savedAfterColumnChange));
        assertCursorStateValue(savedAfterColumnChange, "id", 42L);

        JsonNode updatedAtPreviewWithoutCursor = previewTask(authorization, projectId,
                taskPayload(taskId, runtimeClusterId, sourceDatasourceId, sourceModelId,
                        targetDatasourceId, targetModelId, "updated_at", Long.valueOf(1000L)));
        assertNullish(updatedAtPreviewWithoutCursor.path("data").path("reader").path("config").path("pkValue"));

        String updatedAtCursor = "2026-05-03 10:00:00";
        updateCursor(taskId, "updated_at", updatedAtCursor, "STRING", Long.valueOf(78L));

        JsonNode updatedAtTask = getTask(authorization, projectId, taskId);
        assertThat(cursorValue(updatedAtTask).asText()).isEqualTo(updatedAtCursor);
        assertCursorStateValue(updatedAtTask, "id", 42L);
        assertCursorStateValue(updatedAtTask, "updated_at", updatedAtCursor);

        JsonNode switchedBackToId = saveTask(authorization, projectId,
                taskPayload(taskId, runtimeClusterId, sourceDatasourceId, sourceModelId,
                        targetDatasourceId, targetModelId, "id", Long.valueOf(1001L)));
        assertCursorLong(switchedBackToId, 42L);
        assertCursorStateValue(switchedBackToId, "updated_at", updatedAtCursor);

        JsonNode idPreview = previewTask(authorization, projectId,
                taskPayload(taskId, runtimeClusterId, sourceDatasourceId, sourceModelId,
                        targetDatasourceId, targetModelId, "id", Long.valueOf(1001L)));
        assertThat(idPreview.path("data").path("reader").path("config").path("pkValue").asLong()).isEqualTo(42L);

        JsonNode reset = resetCursor(authorization, projectId, taskId, "src1", "id", ">");
        JsonNode resetIncremental = reset.path("data").path("sourceBindings").path(0).path("incremental");
        assertThat(resetIncremental.path("incrColumn").asText()).isEqualTo("id");
        assertThat(resetIncremental.path("incrModel").asText()).isEqualTo(">");
        assertNullish(resetIncremental.path("pkValue"));
        assertNullish(resetIncremental.path("lastRunRecordId"));
        assertMissingCursorState(reset, "id");
        assertCursorStateValue(reset, "updated_at", updatedAtCursor);

        JsonNode switchedToUpdatedAtAfterReset = saveTask(authorization, projectId,
                taskPayload(taskId, runtimeClusterId, sourceDatasourceId, sourceModelId,
                        targetDatasourceId, targetModelId, "updated_at", Long.valueOf(1002L)));
        assertThat(cursorValue(switchedToUpdatedAtAfterReset).asText()).isEqualTo(updatedAtCursor);
    }

    private void updateCursor(Long taskId, String incrColumn, Object pkValue, String valueType, Long runRecordId) {
        Map<String, Object> cursor = new LinkedHashMap<String, Object>();
        cursor.put("sourceAlias", "src1");
        cursor.put("incrColumn", incrColumn);
        cursor.put("incrModel", ">");
        cursor.put("valueType", valueType);
        cursor.put("pkValue", pkValue);
        Map<String, Object> cursors = new LinkedHashMap<String, Object>();
        cursors.put("src1", cursor);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("incrementalCursors", cursors);
        incrementalStateService.updateFromExecutionResult(taskId, runRecordId, result);
    }

    private JsonNode cursorValue(JsonNode taskResponse) {
        return taskResponse.path("data").path("sourceBindings").path(0).path("incremental").path("pkValue");
    }

    private void assertCursorLong(JsonNode taskResponse, long expected) {
        JsonNode value = cursorValue(taskResponse);
        assertThat(value.isNumber()).isTrue();
        assertThat(value.asLong()).isEqualTo(expected);
    }

    private void assertCursorStateValue(JsonNode taskResponse, String incrColumn, Object expected) {
        JsonNode cursorState = findCursorState(taskResponse, incrColumn);
        assertThat(cursorState.isMissingNode()).isFalse();
        if (expected instanceof Number) {
            assertThat(cursorState.path("pkValue").asLong()).isEqualTo(((Number) expected).longValue());
        } else {
            assertThat(cursorState.path("pkValue").asText()).isEqualTo(String.valueOf(expected));
        }
    }

    private void assertMissingCursorState(JsonNode taskResponse, String incrColumn) {
        assertThat(findCursorState(taskResponse, incrColumn).isMissingNode()).isTrue();
    }

    private JsonNode findCursorState(JsonNode taskResponse, String incrColumn) {
        JsonNode cursorStates = taskResponse.path("data").path("sourceBindings").path(0).path("incremental").path("cursorStates");
        if (!cursorStates.isArray()) {
            return cursorStates.path(0);
        }
        for (JsonNode cursorState : cursorStates) {
            if (incrColumn.equals(cursorState.path("incrColumn").asText())) {
                return cursorState;
            }
        }
        return cursorStates.path(cursorStates.size());
    }

    private void assertNullish(JsonNode value) {
        assertThat(value.isMissingNode() || value.isNull()).isTrue();
    }

    private Long createDatasource(String authorization,
                                  Long projectId,
                                  Long runtimeClusterId,
                                  String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        payload.put("typeCode", "mysql8");
        payload.put("enabled", Boolean.TRUE);
        payload.put("executable", Boolean.FALSE);
        payload.put("applicableClusterIds", Collections.singletonList(runtimeClusterId));
        payload.put("technicalMetadata", minimalSqlMetadata());
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());

        MvcResult result = mockMvc.perform(post("/api/v1/datasources")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private Long createModel(String authorization, Long projectId, Long datasourceId, String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("datasourceId", datasourceId);
        payload.put("name", name);
        payload.put("physicalLocator", name);
        payload.put("modelKind", "TABLE");
        payload.put("technicalMetadata", tableMetadata());
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());

        MvcResult result = mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        awaitIndexQueueIdle();
        return readBody(result).path("data").path("id").asLong();
    }

    private JsonNode saveTask(String authorization, Long projectId, Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/collection-tasks")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result);
    }

    private JsonNode previewTask(String authorization, Long projectId, Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/collection-tasks/preview")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result);
    }

    private JsonNode resetCursor(String authorization,
                                 Long projectId,
                                 Long taskId,
                                 String sourceAlias,
                                 String incrColumn,
                                 String incrModel) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/collection-tasks/{id}/incremental-cursors/reset", taskId)
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .param("sourceAlias", sourceAlias)
                        .param("incrColumn", incrColumn)
                        .param("incrModel", incrModel)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result);
    }

    private JsonNode getTask(String authorization, Long projectId, Long taskId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/collection-tasks/{id}", taskId)
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result);
    }

    private Map<String, Object> taskPayload(Long id,
                                            Long runtimeClusterId,
                                            Long sourceDatasourceId,
                                            Long sourceModelId,
                                            Long targetDatasourceId,
                                            Long targetModelId,
                                            String incrColumn,
                                            Object incomingCursor) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (id != null) {
            payload.put("id", id);
        }
        payload.put("name", "cursor_task");
        payload.put("runtimeClusterId", runtimeClusterId);
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("sourceAlias", "src1");
        source.put("datasourceId", sourceDatasourceId);
        source.put("modelId", sourceModelId);
        source.put("readerOptions", new LinkedHashMap<String, Object>());
        Map<String, Object> incremental = new LinkedHashMap<String, Object>();
        incremental.put("enabled", Boolean.TRUE);
        incremental.put("incrColumn", incrColumn);
        incremental.put("incrModel", ">");
        incremental.put("pkValue", incomingCursor);
        incremental.put("lastRunRecordId", Long.valueOf(999L));
        source.put("incremental", incremental);
        sources.add(source);
        payload.put("sourceBindings", sources);

        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("datasourceId", targetDatasourceId);
        target.put("modelId", targetModelId);
        target.put("writerOptions", Collections.singletonMap("writeMode", "insert"));
        payload.put("targetBinding", target);

        List<Map<String, Object>> fieldMappings = new ArrayList<Map<String, Object>>();
        Map<String, Object> mapping = new LinkedHashMap<String, Object>();
        mapping.put("sourceAlias", "src1");
        mapping.put("sourceField", "id");
        mapping.put("targetField", "id");
        mapping.put("transformers", new ArrayList<Map<String, Object>>());
        fieldMappings.add(mapping);
        payload.put("fieldMappings", fieldMappings);

        Map<String, Object> executionOptions = new LinkedHashMap<String, Object>();
        executionOptions.put("collectionMode", "INCREMENTAL");
        payload.put("executionOptions", executionOptions);
        payload.put("schedule", null);
        return payload;
    }

    private Map<String, Object> minimalSqlMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("host", "127.0.0.1");
        metadata.put("port", Integer.valueOf(3306));
        metadata.put("database", "test");
        metadata.put("userName", "root");
        metadata.put("password", "root");
        metadata.put("usePool", Boolean.FALSE);
        metadata.put("extraParams", new LinkedHashMap<String, Object>());
        return metadata;
    }

    private Map<String, Object> tableMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("physicalName", "source_table");
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        columns.add(column("id", "BIGINT"));
        columns.add(column("updated_at", "TIMESTAMP"));
        metadata.put("columns", columns);
        return metadata;
    }

    private Map<String, Object> column(String name, String type) {
        Map<String, Object> column = new LinkedHashMap<String, Object>();
        column.put("name", name);
        column.put("type", type);
        return column;
    }
}
