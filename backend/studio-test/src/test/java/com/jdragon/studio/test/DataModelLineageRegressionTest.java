package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DataModelLineageRelationEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DataModelLineageRelationMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataModelLineageRegressionTest extends StudioApiRegressionTestSupport {

    @Autowired
    private DatasourceMapper datasourceMapper;

    @Autowired
    private DataModelMapper dataModelMapper;

    @Autowired
    private DataModelLineageRelationMapper dataModelLineageRelationMapper;

    @Autowired
    private RunRecordMapper runRecordMapper;

    @Test
    void shouldExposeLatestRunStatusFromRunRecordsEvenWhenLineageRowIsNotRun() throws Exception {
        Long defaultProjectId = jdbcTemplate.queryForObject(
                "select id from studio_project where default_project = 1 limit 1",
                Long.class
        );
        assertThat(defaultProjectId).isNotNull();

        DatasourceEntity sourceDatasource = new DatasourceEntity();
        sourceDatasource.setId(51001L);
        sourceDatasource.setTenantId("default");
        sourceDatasource.setProjectId(defaultProjectId);
        sourceDatasource.setName("lineage_source");
        sourceDatasource.setTypeCode("mysql8");
        sourceDatasource.setEnabled(Integer.valueOf(1));
        sourceDatasource.setExecutable(Integer.valueOf(1));
        sourceDatasource.setTechnicalMetadata(datasourceMetadata("127.0.0.1", "3306", "lineage_db"));
        datasourceMapper.insert(sourceDatasource);

        DatasourceEntity targetDatasource = new DatasourceEntity();
        targetDatasource.setId(51002L);
        targetDatasource.setTenantId("default");
        targetDatasource.setProjectId(defaultProjectId);
        targetDatasource.setName("lineage_target");
        targetDatasource.setTypeCode("mysql8");
        targetDatasource.setEnabled(Integer.valueOf(1));
        targetDatasource.setExecutable(Integer.valueOf(1));
        targetDatasource.setTechnicalMetadata(datasourceMetadata("127.0.0.1", "3306", "lineage_target_db"));
        datasourceMapper.insert(targetDatasource);

        DataModelEntity sourceModel = new DataModelEntity();
        sourceModel.setId(61001L);
        sourceModel.setTenantId("default");
        sourceModel.setProjectId(defaultProjectId);
        sourceModel.setDatasourceId(sourceDatasource.getId());
        sourceModel.setName("orders_src");
        sourceModel.setModelKind("TABLE");
        sourceModel.setPhysicalLocator("lineage_db.orders_src");
        dataModelMapper.insert(sourceModel);

        DataModelEntity targetModel = new DataModelEntity();
        targetModel.setId(61002L);
        targetModel.setTenantId("default");
        targetModel.setProjectId(defaultProjectId);
        targetModel.setDatasourceId(targetDatasource.getId());
        targetModel.setName("orders_target");
        targetModel.setModelKind("TABLE");
        targetModel.setPhysicalLocator("lineage_target_db.orders_target");
        dataModelMapper.insert(targetModel);

        DataModelLineageRelationEntity relation = new DataModelLineageRelationEntity();
        relation.setId(71001L);
        relation.setTenantId("default");
        relation.setProjectId(defaultProjectId);
        relation.setLevel("TABLE");
        relation.setSourceType("COLLECTION_TASK");
        relation.setCollectionTaskId(81001L);
        relation.setCollectionTaskNameSnapshot("Orders Sync");
        relation.setSourceDatasourceId(sourceDatasource.getId());
        relation.setSourceDatasourceNameSnapshot(sourceDatasource.getName());
        relation.setSourceDatasourceTypeSnapshot(sourceDatasource.getTypeCode());
        relation.setSourceDatabaseNameSnapshot("lineage_db");
        relation.setSourceHostSnapshot("127.0.0.1");
        relation.setSourcePortSnapshot("3306");
        relation.setSourceModelId(sourceModel.getId());
        relation.setSourceModelNameSnapshot(sourceModel.getName());
        relation.setSourceModelLocatorSnapshot(sourceModel.getPhysicalLocator());
        relation.setTargetDatasourceId(targetDatasource.getId());
        relation.setTargetDatasourceNameSnapshot(targetDatasource.getName());
        relation.setTargetDatasourceTypeSnapshot(targetDatasource.getTypeCode());
        relation.setTargetDatabaseNameSnapshot("lineage_target_db");
        relation.setTargetHostSnapshot("127.0.0.1");
        relation.setTargetPortSnapshot("3306");
        relation.setTargetModelId(targetModel.getId());
        relation.setTargetModelNameSnapshot(targetModel.getName());
        relation.setTargetModelLocatorSnapshot(targetModel.getPhysicalLocator());
        relation.setLatestRunStatus("NOT_RUN");
        dataModelLineageRelationMapper.insert(relation);

        RunRecordEntity latestRun = new RunRecordEntity();
        latestRun.setId(91001L);
        latestRun.setTenantId("default");
        latestRun.setProjectId(defaultProjectId);
        latestRun.setCollectionTaskId(81001L);
        latestRun.setExecutionType("COLLECTION_TASK");
        latestRun.setNodeCode("collection_task_81001");
        latestRun.setStatus("SUCCESS");
        latestRun.setWorkerCode("worker-01");
        latestRun.setMessage("Run completed");
        latestRun.setStartedAt(LocalDateTime.of(2026, 4, 12, 10, 0, 0));
        latestRun.setEndedAt(LocalDateTime.of(2026, 4, 12, 10, 5, 0));
        latestRun.setCollectedRecords(5L);
        latestRun.setReadSucceedRecords(5L);
        latestRun.setWriteSucceedRecords(5L);
        latestRun.setWriteFailedRecords(0L);
        latestRun.setFailedRecords(0L);
        runRecordMapper.insert(latestRun);

        String authorization = adminAuthorizationHeader();

        MvcResult lineageResult = mockMvc.perform(get("/api/v1/models/{modelId}/lineage", targetModel.getId())
                        .header("Authorization", authorization)
                        .param("level", "TABLE"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode lineageBody = readBody(lineageResult).path("data");
        JsonNode edge = lineageBody.path("edges").get(0);
        assertThat(edge.path("latestRunStatus").asText()).isEqualTo("SUCCESS");
        assertThat(edge.path("latestRunId").asLong()).isEqualTo(91001L);

        String edgeId = edge.path("edgeId").asText();
        mockMvc.perform(get("/api/v1/models/{modelId}/lineage/edges/{edgeId}", targetModel.getId(), edgeId)
                        .header("Authorization", authorization)
                        .param("level", "TABLE"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode detail = readBody(result).path("data");
                    assertThat(detail.path("latestRunStatus").asText()).isEqualTo("SUCCESS");
                    assertThat(detail.path("latestRunId").asLong()).isEqualTo(91001L);
                    assertThat(detail.path("contributors")).hasSize(1);
                    assertThat(detail.path("contributors").get(0).path("latestRunStatus").asText()).isEqualTo("SUCCESS");
                });
    }

    @Test
    void shouldExposeDatabaseLevelSelfLoopWithoutOverflowingSummary() throws Exception {
        Long defaultProjectId = jdbcTemplate.queryForObject(
                "select id from studio_project where default_project = 1 limit 1",
                Long.class
        );
        assertThat(defaultProjectId).isNotNull();

        DatasourceEntity datasource = new DatasourceEntity();
        datasource.setId(52001L);
        datasource.setTenantId("default");
        datasource.setProjectId(defaultProjectId);
        datasource.setName("same_ds");
        datasource.setTypeCode("mysql8");
        datasource.setEnabled(Integer.valueOf(1));
        datasource.setExecutable(Integer.valueOf(1));
        datasource.setTechnicalMetadata(datasourceMetadata("127.0.0.1", "3306", "same_db"));
        datasourceMapper.insert(datasource);

        DataModelEntity targetModel = new DataModelEntity();
        targetModel.setId(62001L);
        targetModel.setTenantId("default");
        targetModel.setProjectId(defaultProjectId);
        targetModel.setDatasourceId(datasource.getId());
        targetModel.setName("same_target");
        targetModel.setModelKind("TABLE");
        targetModel.setPhysicalLocator("same_db.same_target");
        dataModelMapper.insert(targetModel);

        DataModelLineageRelationEntity databaseRelation = new DataModelLineageRelationEntity();
        databaseRelation.setId(72001L);
        databaseRelation.setTenantId("default");
        databaseRelation.setProjectId(defaultProjectId);
        databaseRelation.setLevel("DATABASE");
        databaseRelation.setSourceType("COLLECTION_TASK");
        databaseRelation.setCollectionTaskId(82001L);
        databaseRelation.setCollectionTaskNameSnapshot("Same Database Sync");
        databaseRelation.setSourceDatasourceId(datasource.getId());
        databaseRelation.setSourceDatasourceNameSnapshot(datasource.getName());
        databaseRelation.setSourceDatasourceTypeSnapshot(datasource.getTypeCode());
        databaseRelation.setSourceDatabaseNameSnapshot("same_db");
        databaseRelation.setTargetDatasourceId(datasource.getId());
        databaseRelation.setTargetDatasourceNameSnapshot(datasource.getName());
        databaseRelation.setTargetDatasourceTypeSnapshot(datasource.getTypeCode());
        databaseRelation.setTargetDatabaseNameSnapshot("same_db");
        databaseRelation.setLatestRunStatus("NOT_RUN");
        dataModelLineageRelationMapper.insert(databaseRelation);

        String authorization = adminAuthorizationHeader();

        mockMvc.perform(get("/api/v1/models/{modelId}/lineage", targetModel.getId())
                        .header("Authorization", authorization)
                        .param("level", "DATABASE"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode data = readBody(result).path("data");
                    assertThat(data.path("summary").path("upstreamDepth").asInt()).isZero();
                    assertThat(data.path("summary").path("totalUpstreamCount").asInt()).isZero();
                    assertThat(data.path("summary").path("directUpstreamCount").asInt()).isZero();
                    assertThat(data.path("edges")).hasSize(1);
                    assertThat(data.path("edges").get(0).path("selfLoop").asBoolean()).isTrue();
                    assertThat(data.path("edges").get(0).path("sourceNodeId").asText())
                            .isEqualTo(data.path("edges").get(0).path("targetNodeId").asText());
                });
    }

    private Map<String, Object> datasourceMetadata(String host, String port, String database) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("host", host);
        metadata.put("port", port);
        metadata.put("database", database);
        return metadata;
    }
}
