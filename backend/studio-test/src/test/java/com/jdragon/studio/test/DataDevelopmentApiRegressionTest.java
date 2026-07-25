package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
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

class DataDevelopmentApiRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void shouldCreateDirectoryAndSqlScriptAndExposeThemThroughTreeApis() throws Exception {
        JsonNode loginBody = loginAsAdmin();
        String authorization = adminAuthorizationHeader(loginBody);
        Long projectId = currentProjectId(loginBody);
        String currentProjectId = projectId.toString();
        Long runtimeClusterId = currentRuntimeClusterId(authorization, projectId);

        MvcResult directoryResult = mockMvc.perform(post("/api/v1/data-development/directories")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ods\",\"permissionCode\":\"tenant:data-dev:ods\",\"description\":\"ODS scripts\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("ods"))
                .andExpect(jsonPath("$.data.projectId").value(currentProjectId))
                .andReturn();

        String directoryId = readBody(directoryResult).path("data").path("id").asText();
        assertThat(directoryId).isNotBlank();

        Map<String, Object> datasourcePayload = new LinkedHashMap<String, Object>();
        datasourcePayload.put("name", "Test SQL Datasource");
        datasourcePayload.put("typeCode", "mysql8");
        datasourcePayload.put("enabled", true);
        datasourcePayload.put("executable", false);
        datasourcePayload.put("applicableClusterIds", Collections.singletonList(runtimeClusterId));
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
        Long otherRuntimeClusterId = createAndAuthorizeTestRuntimeCluster(authorization, projectId);
        createDatasource(authorization, projectId, otherRuntimeClusterId,
                "Other Cluster SQL Datasource");

        Map<String, Object> scriptPayload = new LinkedHashMap<String, Object>();
        scriptPayload.put("directoryId", directoryId);
        scriptPayload.put("fileName", "orders_profile.sql");
        scriptPayload.put("scriptType", "SQL");
        scriptPayload.put("runtimeClusterId", runtimeClusterId);
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
                .andExpect(jsonPath("$.data.projectId").value(currentProjectId))
                .andExpect(jsonPath("$.data.datasourceName").value("Test SQL Datasource"));

        MvcResult fullDatasourceResult = mockMvc.perform(get("/api/v1/data-development/datasources")
                        .param("runtimeClusterId", runtimeClusterId.toString())
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].typeCode").value("mysql8"))
                .andReturn();
        assertThat(fullDatasourceResult.getResponse().getContentAsString())
                .contains("Test SQL Datasource")
                .doesNotContain("Other Cluster SQL Datasource");

        mockMvc.perform(get("/api/v1/data-development/datasources")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/data-development/datasource-options")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        MvcResult datasourceOptionsResult = mockMvc.perform(get("/api/v1/data-development/datasource-options")
                        .param("runtimeClusterId", runtimeClusterId.toString())
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].typeCode").value("mysql8"))
                .andReturn();
        String datasourceOptionsBody = datasourceOptionsResult.getResponse().getContentAsString();
        assertThat(datasourceOptionsBody)
                .contains("Test SQL Datasource")
                .doesNotContain("Other Cluster SQL Datasource");
        assertThat(datasourceOptionsBody).doesNotContain("technicalMetadata");
        assertThat(datasourceOptionsBody).doesNotContain("businessMetadata");
        assertThat(datasourceOptionsBody).doesNotContain("recentConnectionTests");

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

    @Test
    void shouldSaveJavaScriptAndRejectDirectEditorExecution() throws Exception {
        JsonNode loginBody = loginAsAdmin();
        String authorization = adminAuthorizationHeader(loginBody);
        Long projectId = currentProjectId(loginBody);
        String currentProjectId = projectId.toString();
        Long runtimeClusterId = currentRuntimeClusterId(authorization, projectId);

        Map<String, Object> scriptPayload = new LinkedHashMap<String, Object>();
        scriptPayload.put("fileName", "demo_job.java");
        scriptPayload.put("scriptType", "JAVA");
        scriptPayload.put("runtimeClusterId", runtimeClusterId);
        scriptPayload.put("description", "Demo Java script");
        scriptPayload.put("content", ""
                + "import com.jdragon.studio.infra.script.java.JavaDataScript;\n"
                + "import com.jdragon.studio.infra.script.java.JavaDataScriptContext;\n"
                + "import com.jdragon.studio.infra.script.java.JavaDataScriptResult;\n"
                + "import org.slf4j.Logger;\n"
                + "\n"
                + "public class DemoJavaDataScript implements JavaDataScript {\n"
                + "    @Override\n"
                + "    public JavaDataScriptResult execute(JavaDataScriptContext context) throws Exception {\n"
                + "        context.getLogger().info(\"Java script started\");\n"
                + "        Logger logger = context.getLogger();\n"
                + "        logger.info(\"Java script slf4j logger started by {}\", context.getUsername());\n"
                + "        JavaDataScriptResult result = new JavaDataScriptResult();\n"
                + "        result.setMessage(\"Java script executed successfully\");\n"
                + "        result.getResultJson().put(\"tenantId\", context.getTenantId());\n"
                + "        result.getResultJson().put(\"arguments\", context.getArguments());\n"
                + "        return result;\n"
                + "    }\n"
                + "}\n");

        mockMvc.perform(post("/api/v1/data-development/scripts")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scriptPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fileName").value("demo_job.java"))
                .andExpect(jsonPath("$.data.projectId").value(currentProjectId))
                .andExpect(jsonPath("$.data.scriptType").value("JAVA"));

        Map<String, Object> executionPayload = new LinkedHashMap<String, Object>();
        executionPayload.put("scriptType", "JAVA");
        executionPayload.put("runtimeClusterId", runtimeClusterId);
        executionPayload.put("content", scriptPayload.get("content"));
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("batchSize", 100);
        executionPayload.put("arguments", arguments);

        mockMvc.perform(post("/api/v1/data-development/scripts/execute")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executionPayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Non-SQL scripts must be saved before execution"));
    }

    @Test
    void shouldSavePythonScriptAndRejectDirectEditorExecution() throws Exception {
        JsonNode loginBody = loginAsAdmin();
        String authorization = adminAuthorizationHeader(loginBody);
        Long projectId = currentProjectId(loginBody);
        String currentProjectId = projectId.toString();
        Long runtimeClusterId = currentRuntimeClusterId(authorization, projectId);

        Map<String, Object> scriptPayload = new LinkedHashMap<String, Object>();
        scriptPayload.put("fileName", "demo_job.py");
        scriptPayload.put("scriptType", "PYTHON");
        scriptPayload.put("runtimeClusterId", runtimeClusterId);
        scriptPayload.put("description", "Demo Python script");
        scriptPayload.put("content", ""
                + "def execute(context):\n"
                + "    context.logger.info(\"Python script started by %s\" % context.username)\n"
                + "    datasources = context.services.list_datasources()\n"
                + "    return {\n"
                + "        \"tenantId\": context.tenant_id,\n"
                + "        \"arguments\": context.arguments,\n"
                + "        \"datasourceCount\": len(datasources),\n"
                + "    }\n");

        mockMvc.perform(post("/api/v1/data-development/scripts")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scriptPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fileName").value("demo_job.py"))
                .andExpect(jsonPath("$.data.projectId").value(currentProjectId))
                .andExpect(jsonPath("$.data.scriptType").value("PYTHON"));

        Map<String, Object> executionPayload = new LinkedHashMap<String, Object>();
        executionPayload.put("scriptType", "PYTHON");
        executionPayload.put("runtimeClusterId", runtimeClusterId);
        executionPayload.put("content", scriptPayload.get("content"));
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("batchSize", 64);
        executionPayload.put("arguments", arguments);

        mockMvc.perform(post("/api/v1/data-development/scripts/execute")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executionPayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Non-SQL scripts must be saved before execution"));
    }

    @Test
    void shouldSaveFlinkQuestionSqlScriptWithExecutionConfig() throws Exception {
        JsonNode loginBody = loginAsAdmin();
        String authorization = adminAuthorizationHeader(loginBody);
        Long projectId = currentProjectId(loginBody);
        Long runtimeClusterId = currentRuntimeClusterId(authorization, projectId);
        Long datasourceId = createDatasource(authorization, projectId, runtimeClusterId,
                "Flink question datasource");
        Long firstModelId = createModel(authorization, projectId, datasourceId,
                "Flink question orders model", "flink_question_orders");
        Long secondModelId = createModel(authorization, projectId, datasourceId,
                "Flink question customers model", "flink_question_customers");

        Map<String, Object> scriptPayload = new LinkedHashMap<String, Object>();
        scriptPayload.put("fileName", "orders_question.flink.sql");
        scriptPayload.put("scriptType", "FLINK_QUESTION_SQL");
        scriptPayload.put("runtimeClusterId", runtimeClusterId);
        scriptPayload.put("description", "Model Flink SQL");
        scriptPayload.put("content", "select count(*) as total_count from m_1001");
        Map<String, Object> executionConfig = new LinkedHashMap<String, Object>();
        executionConfig.put("modelIds", java.util.Arrays.asList(firstModelId, secondModelId));
        scriptPayload.put("executionConfig", executionConfig);

        MvcResult saveResult = mockMvc.perform(post("/api/v1/data-development/scripts")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scriptPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fileName").value("orders_question.flink.sql"))
                .andExpect(jsonPath("$.data.scriptType").value("FLINK_QUESTION_SQL"))
                .andExpect(jsonPath("$.data.executionConfig.modelIds[0]").value(firstModelId))
                .andReturn();

        String scriptId = readBody(saveResult).path("data").path("id").asText();
        mockMvc.perform(get("/api/v1/data-development/scripts/" + scriptId)
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executionConfig.modelIds[1]").value(secondModelId));
    }

    @Test
    void shouldRejectFlinkQuestionSqlExecutionWithoutModels() throws Exception {
        JsonNode loginBody = loginAsAdmin();
        String authorization = adminAuthorizationHeader(loginBody);
        Long projectId = currentProjectId(loginBody);
        Long runtimeClusterId = currentRuntimeClusterId(authorization, projectId);

        Map<String, Object> executionPayload = new LinkedHashMap<String, Object>();
        executionPayload.put("scriptType", "FLINK_QUESTION_SQL");
        executionPayload.put("runtimeClusterId", runtimeClusterId);
        executionPayload.put("content", "select count(*) as total_count from m_1001");
        executionPayload.put("maxRows", 100);

        mockMvc.perform(post("/api/v1/data-development/scripts/execute")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executionPayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("modelIds are required for 模型 Flink SQL execution"));
    }

    private Long currentRuntimeClusterId(String authorization, Long projectId) throws Exception {
        return createAndAuthorizeDefaultLocalRuntimeCluster(authorization, projectId);
    }

    private Long createDatasource(String authorization,
                                  Long projectId,
                                  Long runtimeClusterId,
                                  String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        payload.put("typeCode", "mysql8");
        payload.put("enabled", true);
        payload.put("executable", false);
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

    private Long createModel(String authorization,
                             Long projectId,
                             Long datasourceId,
                             String name,
                             String physicalLocator) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("datasourceId", datasourceId);
        payload.put("name", name);
        payload.put("physicalLocator", physicalLocator);
        payload.put("modelKind", "TABLE");
        payload.put("technicalMetadata", tableMetadata(physicalLocator));
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());

        MvcResult result = mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private Map<String, Object> tableMetadata(String physicalLocator) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("physicalName", physicalLocator);
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        Map<String, Object> column = new LinkedHashMap<String, Object>();
        column.put("name", "id");
        column.put("type", "BIGINT");
        columns.add(column);
        metadata.put("columns", columns);
        return metadata;
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
