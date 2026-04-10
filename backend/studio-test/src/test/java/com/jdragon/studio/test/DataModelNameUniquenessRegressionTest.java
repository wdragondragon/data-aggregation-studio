package com.jdragon.studio.test;

import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataModelNameUniquenessRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void shouldAllowDuplicateModelNamesAcrossDifferentDatasourcesWithinSameProject() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long projectId = createProject(authorization, "duplicate_model_names", "Duplicate Model Names");
        Long datasourceAId = createDatasource(authorization, projectId, "mysql-a");
        Long datasourceBId = createDatasource(authorization, projectId, "mysql-b");
        Long tableSchemaVersionId = syncMysqlTechnicalTableSchema(authorization);

        Long firstModelId = createModel(authorization, projectId, datasourceAId, tableSchemaVersionId, "orders");
        Long secondModelId = createModel(authorization, projectId, datasourceBId, tableSchemaVersionId, "orders");

        assertThat(firstModelId).isNotEqualTo(secondModelId);

        mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(modelPayload(datasourceAId, tableSchemaVersionId, "orders", "orders_copy"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Model name already exists in the current datasource"));
    }

    private Long syncMysqlTechnicalTableSchema(String authorization) throws Exception {
        mockMvc.perform(post("/api/v1/meta-schemas/technical/sync/mysql8")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        MvcResult schemas = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        for (com.fasterxml.jackson.databind.JsonNode schema : readBody(schemas).path("data")) {
            if ("technical:mysql8:table".equals(schema.path("schemaCode").asText())) {
                return schema.path("currentVersionId").asLong();
            }
        }
        throw new IllegalStateException("Unable to resolve technical:mysql8:table currentVersionId");
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

    private Long createDatasource(String authorization, Long projectId, String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        payload.put("typeCode", "mysql8");
        payload.put("enabled", true);
        payload.put("executable", false);
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

    private Long createModel(String authorization,
                             Long projectId,
                             Long datasourceId,
                             Long schemaVersionId,
                             String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(modelPayload(datasourceId, schemaVersionId, name, name))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        awaitIndexQueueIdle();
        return readBody(result).path("data").path("id").asLong();
    }

    private Map<String, Object> modelPayload(Long datasourceId,
                                             Long schemaVersionId,
                                             String name,
                                             String physicalLocator) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("datasourceId", datasourceId);
        payload.put("name", name);
        payload.put("physicalLocator", physicalLocator);
        payload.put("modelKind", "TABLE");
        payload.put("schemaVersionId", schemaVersionId);
        payload.put("technicalMetadata", new LinkedHashMap<String, Object>());
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());
        return payload;
    }

    private Map<String, Object> minimalSqlMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("host", "127.0.0.1");
        metadata.put("port", 3306);
        metadata.put("database", "test");
        metadata.put("userName", "root");
        metadata.put("password", "root");
        metadata.put("other", "");
        metadata.put("usePool", Boolean.FALSE);
        metadata.put("extraParams", new LinkedHashMap<String, Object>());
        return metadata;
    }
}
