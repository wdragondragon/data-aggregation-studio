package com.jdragon.studio.test;

import com.jdragon.studio.dto.enums.ModelSyncTaskItemStatus;
import com.jdragon.studio.dto.enums.ModelSyncTaskSource;
import com.jdragon.studio.dto.enums.ModelSyncTaskStatus;
import com.jdragon.studio.infra.entity.ModelSyncTaskEntity;
import com.jdragon.studio.infra.entity.ModelSyncTaskItemEntity;
import com.jdragon.studio.infra.mapper.ModelSyncTaskItemMapper;
import com.jdragon.studio.infra.mapper.ModelSyncTaskMapper;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelSyncTaskApiRegressionTest extends StudioApiRegressionTestSupport {

    @Autowired
    private ModelSyncTaskMapper modelSyncTaskMapper;

    @Autowired
    private ModelSyncTaskItemMapper modelSyncTaskItemMapper;

    @Test
    void shouldListInspectStopAndDeleteModelSyncTasks() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long projectId = createProject(authorization, "model_sync_tasks", "Model Sync Tasks");

        ModelSyncTaskEntity runningTask = insertTask(projectId, 1001L, 1, ModelSyncTaskStatus.RUNNING, 2, 1, 0, 0, 50);
        insertTaskItem(projectId, runningTask.getId(), 1, "mock_orders", ModelSyncTaskItemStatus.SUCCESS, null, 220L);
        insertTaskItem(projectId, runningTask.getId(), 2, "mock_customers", ModelSyncTaskItemStatus.RUNNING, null, null);

        ModelSyncTaskEntity completedTask = insertTask(projectId, 1002L, 1, ModelSyncTaskStatus.SUCCESS, 1, 1, 0, 0, 100);
        insertTaskItem(projectId, completedTask.getId(), 1, "mock_payments", ModelSyncTaskItemStatus.SUCCESS, null, 180L);

        mockMvc.perform(get("/api/v1/model-sync-tasks")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(completedTask.getId()))
                .andExpect(jsonPath("$.data.items[1].id").value(runningTask.getId()));

        mockMvc.perform(get("/api/v1/model-sync-tasks/{taskId}", runningTask.getId())
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(runningTask.getId()))
                .andExpect(jsonPath("$.data.status").value(ModelSyncTaskStatus.RUNNING.name()))
                .andExpect(jsonPath("$.data.totalCount").value(2));

        mockMvc.perform(get("/api/v1/model-sync-tasks/{taskId}/items", runningTask.getId())
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .queryParam("status", ModelSyncTaskItemStatus.RUNNING.name())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].physicalLocator").value("mock_customers"))
                .andExpect(jsonPath("$.data.items[0].status").value(ModelSyncTaskItemStatus.RUNNING.name()));

        mockMvc.perform(post("/api/v1/model-sync-tasks/{taskId}/stop", runningTask.getId())
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(ModelSyncTaskStatus.STOPPING.name()));

        ModelSyncTaskEntity stoppedEntity = modelSyncTaskMapper.selectById(runningTask.getId());
        assertThat(stoppedEntity).isNotNull();
        assertThat(stoppedEntity.getStopRequested()).isEqualTo(1);
        assertThat(stoppedEntity.getStatus()).isEqualTo(ModelSyncTaskStatus.STOPPING.name());

        mockMvc.perform(delete("/api/v1/model-sync-tasks/{taskId}", completedTask.getId())
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(modelSyncTaskMapper.selectById(completedTask.getId())).isNull();
        Integer deletedTaskCount = jdbcTemplate.queryForObject(
                "select count(1) from model_sync_task where id = ? and deleted = 1",
                Integer.class,
                completedTask.getId());
        Integer deletedItemCount = jdbcTemplate.queryForObject(
                "select count(1) from model_sync_task_item where task_id = ? and deleted = 1",
                Integer.class,
                completedTask.getId());
        assertThat(deletedTaskCount).isEqualTo(1);
        assertThat(deletedItemCount).isEqualTo(1);

        mockMvc.perform(get("/api/v1/model-sync-tasks")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(runningTask.getId()));
    }

    private Long createProject(String authorization, String projectCode, String projectName) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("projectCode", projectCode);
        payload.put("projectName", projectName);
        payload.put("enabled", 1);
        payload.put("defaultProject", 0);
        MvcResult result = mockMvc.perform(post("/api/v1/system/projects")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private ModelSyncTaskEntity insertTask(Long projectId,
                                           Long datasourceId,
                                           int batchNo,
                                           ModelSyncTaskStatus status,
                                           int totalCount,
                                           int successCount,
                                           int failedCount,
                                           int stoppedCount,
                                           int progressPercent) {
        LocalDateTime now = LocalDateTime.now();
        ModelSyncTaskEntity entity = new ModelSyncTaskEntity();
        entity.setTenantId("default");
        entity.setProjectId(projectId);
        entity.setDeleted(Integer.valueOf(0));
        entity.setCreatedAt(now.minusMinutes(2));
        entity.setUpdatedAt(now.minusMinutes(2));
        entity.setDatasourceId(datasourceId);
        entity.setDatasourceType("mysql8");
        entity.setDatasourceNameSnapshot("mysql_src");
        entity.setBatchNo(Integer.valueOf(batchNo));
        entity.setName("mysql_src 第" + batchNo + "批");
        entity.setSource(ModelSyncTaskSource.MANUAL.name());
        entity.setStatus(status.name());
        entity.setTotalCount(Integer.valueOf(totalCount));
        entity.setSuccessCount(Integer.valueOf(successCount));
        entity.setFailedCount(Integer.valueOf(failedCount));
        entity.setStoppedCount(Integer.valueOf(stoppedCount));
        entity.setProgressPercent(Integer.valueOf(progressPercent));
        entity.setStopRequested(Integer.valueOf(0));
        entity.setCreatedBy(Long.valueOf(1L));
        entity.setStartedAt(now.minusMinutes(1));
        entity.setFinishedAt(ModelSyncTaskStatus.SUCCESS == status ? now.minusSeconds(10) : null);
        entity.setDurationMs(ModelSyncTaskStatus.SUCCESS == status ? Long.valueOf(50000L) : null);
        modelSyncTaskMapper.insert(entity);
        return entity;
    }

    private void insertTaskItem(Long projectId,
                                Long taskId,
                                int seqNo,
                                String locator,
                                ModelSyncTaskItemStatus status,
                                String message,
                                Long durationMs) {
        LocalDateTime now = LocalDateTime.now();
        ModelSyncTaskItemEntity entity = new ModelSyncTaskItemEntity();
        entity.setTenantId("default");
        entity.setProjectId(projectId);
        entity.setDeleted(Integer.valueOf(0));
        entity.setCreatedAt(now.minusMinutes(2));
        entity.setUpdatedAt(now.minusMinutes(2));
        entity.setTaskId(taskId);
        entity.setSeqNo(Integer.valueOf(seqNo));
        entity.setPhysicalLocator(locator);
        entity.setModelNameSnapshot(locator);
        entity.setStatus(status.name());
        entity.setMessage(message);
        entity.setStartedAt(now.minusSeconds(30));
        entity.setFinishedAt(ModelSyncTaskItemStatus.RUNNING == status ? null : now.minusSeconds(10));
        entity.setDurationMs(durationMs);
        modelSyncTaskItemMapper.insert(entity);
    }
}
