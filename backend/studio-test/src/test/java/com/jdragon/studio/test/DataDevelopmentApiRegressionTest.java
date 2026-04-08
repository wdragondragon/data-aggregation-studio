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

    @Test
    void shouldSaveAndExecuteJavaScriptWithoutDatasource() throws Exception {
        String authorization = adminAuthorizationHeader();

        Map<String, Object> scriptPayload = new LinkedHashMap<String, Object>();
        scriptPayload.put("fileName", "demo_job.java");
        scriptPayload.put("scriptType", "JAVA");
        scriptPayload.put("description", "Demo Java script");
        scriptPayload.put("content", ""
                + "import com.jdragon.studio.infra.script.java.JavaDataScript;\n"
                + "import com.jdragon.studio.infra.script.java.JavaDataScriptContext;\n"
                + "import com.jdragon.studio.infra.script.java.JavaDataScriptResult;\n"
                + "\n"
                + "public class DemoJavaDataScript implements JavaDataScript {\n"
                + "    @Override\n"
                + "    public JavaDataScriptResult execute(JavaDataScriptContext context) throws Exception {\n"
                + "        context.getLogger().info(\"Java script started\");\n"
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
                .andExpect(jsonPath("$.data.scriptType").value("JAVA"));

        Map<String, Object> executionPayload = new LinkedHashMap<String, Object>();
        executionPayload.put("scriptType", "JAVA");
        executionPayload.put("content", scriptPayload.get("content"));
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("batchSize", 100);
        executionPayload.put("arguments", arguments);

        mockMvc.perform(post("/api/v1/data-development/scripts/execute")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executionPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.scriptType").value("JAVA"))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.message").value("Java script executed successfully"))
                .andExpect(jsonPath("$.data.logs").isNotEmpty())
                .andExpect(jsonPath("$.data.resultJson.tenantId").value("default"))
                .andExpect(jsonPath("$.data.resultJson.arguments.batchSize").value(100));
    }

    @Test
    void shouldSaveAndExecutePythonScriptWithoutDatasource() throws Exception {
        String authorization = adminAuthorizationHeader();
        createSqlDatasource(authorization, "Python Bridge Datasource");

        Map<String, Object> scriptPayload = new LinkedHashMap<String, Object>();
        scriptPayload.put("fileName", "demo_job.py");
        scriptPayload.put("scriptType", "PYTHON");
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
                .andExpect(jsonPath("$.data.scriptType").value("PYTHON"));

        Map<String, Object> executionPayload = new LinkedHashMap<String, Object>();
        executionPayload.put("scriptType", "PYTHON");
        executionPayload.put("content", scriptPayload.get("content"));
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("batchSize", 64);
        executionPayload.put("arguments", arguments);

        mockMvc.perform(post("/api/v1/data-development/scripts/execute")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executionPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.scriptType").value("PYTHON"))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.logs").isNotEmpty())
                .andExpect(jsonPath("$.data.resultJson.tenantId").value("default"))
                .andExpect(jsonPath("$.data.resultJson.arguments.batchSize").value(64))
                .andExpect(jsonPath("$.data.resultJson.datasourceCount").value(1));
    }

    private String createSqlDatasource(String authorization, String name) throws Exception {
        Map<String, Object> datasourcePayload = new LinkedHashMap<String, Object>();
        datasourcePayload.put("name", name);
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
                .andReturn();
        return readBody(datasourceResult).path("data").path("id").asText();
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
