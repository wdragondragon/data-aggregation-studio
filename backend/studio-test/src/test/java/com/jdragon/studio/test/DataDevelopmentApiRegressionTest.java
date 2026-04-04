package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
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

class DataDevelopmentApiRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void shouldCreateDirectoryAndSqlScriptAndExposeThemThroughTreeApis() throws Exception {
        String authorization = adminAuthorizationHeader();

        MvcResult directoryResult = mockMvc.perform(post("/api/v1/data-development/directories")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ods\",\"permissionCode\":\"tenant:data-dev:ods\",\"description\":\"ODS scripts\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("ods"))
                .andReturn();

        String directoryId = readBody(directoryResult).path("data").path("id").asText();
        assertThat(directoryId).isNotBlank();

        Map<String, Object> datasourcePayload = new LinkedHashMap<String, Object>();
        datasourcePayload.put("name", "Test SQL Datasource");
        datasourcePayload.put("typeCode", "mysql8");
        datasourcePayload.put("enabled", true);
        datasourcePayload.put("executable", false);
        datasourcePayload.put("technicalMetadata", minimalSqlMetadata());
        datasourcePayload.put("businessMetadata", new LinkedHashMap<String, Object>());

        MvcResult datasourceResult = mockMvc.perform(post("/api/v1/datasources")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(datasourcePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.typeCode").value("mysql8"))
                .andReturn();

        String datasourceId = readBody(datasourceResult).path("data").path("id").asText();
        assertThat(datasourceId).isNotBlank();

        Map<String, Object> scriptPayload = new LinkedHashMap<String, Object>();
        scriptPayload.put("directoryId", directoryId);
        scriptPayload.put("fileName", "orders_profile.sql");
        scriptPayload.put("scriptType", "SQL");
        scriptPayload.put("datasourceId", datasourceId);
        scriptPayload.put("description", "Orders profile SQL");
        scriptPayload.put("content", "select * from orders limit 10;");

        mockMvc.perform(post("/api/v1/data-development/scripts")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scriptPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fileName").value("orders_profile.sql"))
                .andExpect(jsonPath("$.data.scriptType").value("SQL"))
                .andExpect(jsonPath("$.data.datasourceName").value("Test SQL Datasource"));

        mockMvc.perform(get("/api/v1/data-development/datasources")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].typeCode").value("mysql8"));

        MvcResult treeResult = mockMvc.perform(get("/api/v1/data-development/tree")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode tree = readBody(treeResult).path("data");
        assertThat(tree).isNotNull();
        assertThat(tree.isArray()).isTrue();
        assertThat(tree.size()).isGreaterThan(0);
        JsonNode directoryNode = tree.get(0);
        assertThat(directoryNode.path("nodeType").asText()).isEqualTo("DIRECTORY");
        assertThat(directoryNode.path("name").asText()).isEqualTo("ods");
        assertThat(directoryNode.path("children").isArray()).isTrue();
        assertThat(directoryNode.path("children").get(0).path("nodeType").asText()).isEqualTo("SCRIPT");
        assertThat(directoryNode.path("children").get(0).path("name").asText()).isEqualTo("orders_profile.sql");
    }

    private Map<String, Object> minimalSqlMetadata() {
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("host", "127.0.0.1");
        technicalMetadata.put("port", "3306");
        technicalMetadata.put("database", "demo");
        technicalMetadata.put("userName", "root");
        technicalMetadata.put("password", "root");
        return technicalMetadata;
    }
}
