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

class DataSourceApiRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void currentFormConnectionTestShouldRejectCrossProjectDatasourceId() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long ownerProjectId = createProject(authorization, "lt_reg_s17_owner", "长期回归-S17数据源归属项目");
        Long receiverProjectId = createProject(authorization, "lt_reg_s17_receiver", "长期回归-S17数据源越权接收项目");
        Long datasourceId = createDatasource(authorization, ownerProjectId);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("id", datasourceId);
        payload.put("name", "长期回归-S17跨项目借用连接测试");
        payload.put("typeCode", "mysql8");
        payload.put("enabled", true);
        payload.put("executable", true);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("host", "127.0.0.1");
        metadata.put("port", 3306);
        metadata.put("database", "studio_longterm_regression");
        metadata.put("userName", "root");
        payload.put("technicalMetadata", metadata);
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());

        mockMvc.perform(post("/api/v1/datasources/test")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", receiverProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Resource belongs to another project"));
    }

    @Test
    void datasourceOptionsShouldExposeSlimOptionView() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long projectId = createProject(authorization, "lt_reg_s51_options", "长期回归-S51数据源选项项目");
        Long datasourceId = createDatasource(authorization, projectId, "长期回归-S51数据源选项经营数据库");

        MvcResult result = mockMvc.perform(get("/api/v1/datasources/options")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode data = readBody(result).path("data");
        JsonNode target = null;
        for (JsonNode item : data) {
            if (item.path("id").asLong() == datasourceId.longValue()) {
                target = item;
                break;
            }
        }
        assertThat(target).isNotNull();
        assertThat(target.path("name").asText()).isEqualTo("长期回归-S51数据源选项经营数据库");
        assertThat(target.path("typeCode").asText()).isEqualTo("mysql8");
        assertThat(target.has("connectionStatus")).isFalse();
        assertThat(target.has("recentConnectionTests")).isFalse();
        assertThat(target.has("technicalMetadata")).isFalse();
        assertThat(target.has("businessMetadata")).isFalse();
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

    private Long createDatasource(String authorization, Long projectId) throws Exception {
        return createDatasource(authorization, projectId, "长期回归-S17客户经营敏感数据源");
    }

    private Long createDatasource(String authorization, Long projectId, String datasourceName) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", datasourceName);
        payload.put("typeCode", "mysql8");
        payload.put("enabled", true);
        payload.put("executable", true);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("host", "127.0.0.1");
        metadata.put("port", 3306);
        metadata.put("database", "studio_longterm_regression");
        metadata.put("userName", "root");
        metadata.put("password", "S17-secret-password");
        payload.put("technicalMetadata", metadata);
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
}
