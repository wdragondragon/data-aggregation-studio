package com.jdragon.studio.test;

import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

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
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", "长期回归-S17客户经营敏感数据源");
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
