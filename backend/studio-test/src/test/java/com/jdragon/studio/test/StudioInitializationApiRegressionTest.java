package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudioInitializationApiRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void loginShouldReturnBootstrapAdminTokenAndCurrentUser() throws Exception {
        String token = loginAndGetAdminToken();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void catalogEndpointsShouldExposeScannedPluginsAndCapabilities() throws Exception {
        String authorization = adminAuthorizationHeader();

        mockMvc.perform(get("/api/v1/catalog/plugins")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(org.hamcrest.Matchers.greaterThan(0))))
                .andExpect(jsonPath("$.data[*].pluginName", hasItem("mysql8")));

        mockMvc.perform(get("/api/v1/catalog/capabilities")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executableSourceTypes", hasItem("mysql8")))
                .andExpect(jsonPath("$.data.executableTargetTypes", hasItem("mysql8")))
                .andExpect(jsonPath("$.data.executableDatasourceTypes", hasItem("mysql8")))
                .andExpect(jsonPath("$.data.sourceCapabilities", hasSize(org.hamcrest.Matchers.greaterThan(0))))
                .andExpect(jsonPath("$.data.sourceCapabilities[*].typeCode", hasItem("mysql8")));
    }

    @Test
    void metadataSchemasShouldContainRequiredMysqlTechnicalMetaModelsAndFieldDefinitions() throws Exception {
        String authorization = adminAuthorizationHeader();

        MvcResult result = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode schemas = readBody(result).path("data");
        JsonNode mysqlSource = findSchema(schemas, "technical:mysql8:source");
        JsonNode mysqlTable = findSchema(schemas, "technical:mysql8:table");
        JsonNode mysqlField = findSchema(schemas, "technical:mysql8:field");

        assertThat(mysqlSource).as("mysql source metamodel").isNotNull();
        assertThat(mysqlTable).as("mysql table metamodel").isNotNull();
        assertThat(mysqlField).as("mysql field metamodel").isNotNull();

        assertThat(extractFieldKeys(mysqlSource)).contains("host", "port", "database", "userName", "password");
        assertThat(extractFieldKeys(mysqlTable)).contains("physicalName", "tableType", "columnCount", "columns");
        assertThat(extractFieldKeys(mysqlField)).contains("name", "type", "size", "scale", "nullable", "primaryKey", "autoIncrement");
    }

    @Test
    void bootstrapSecurityDataShouldBeVisibleThroughManagementApis() throws Exception {
        String authorization = adminAuthorizationHeader();

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].username").value("admin"));

        mockMvc.perform(get("/api/v1/roles")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[*].code", hasItem("ADMIN")));

        mockMvc.perform(get("/api/v1/permissions")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[*].code", hasItem("studio:*")));
    }

    @Test
    void initializedBusinessApisShouldStartEmptyAndDynamicModelQueryShouldBeSafe() throws Exception {
        String authorization = adminAuthorizationHeader();

        mockMvc.perform(get("/api/v1/datasources")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/v1/models")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(post("/api/v1/models/query")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(post("/api/v1/models/index/rebuild")
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(0));

        mockMvc.perform(get("/api/v1/workflows")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/v1/schedules")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/v1/runs")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.queuedTasks", hasSize(0)))
                .andExpect(jsonPath("$.data.runRecords", hasSize(0)));
    }

    private JsonNode findSchema(JsonNode schemas, String schemaCode) {
        if (schemas == null || !schemas.isArray()) {
            return null;
        }
        Iterator<JsonNode> iterator = schemas.elements();
        while (iterator.hasNext()) {
            JsonNode schema = iterator.next();
            if (schemaCode.equals(schema.path("schemaCode").asText())) {
                return schema;
            }
        }
        return null;
    }

    private List<String> extractFieldKeys(JsonNode schema) {
        List<String> keys = new ArrayList<String>();
        if (schema == null || !schema.has("fields") || !schema.get("fields").isArray()) {
            return keys;
        }
        Iterator<JsonNode> iterator = schema.get("fields").elements();
        while (iterator.hasNext()) {
            keys.add(iterator.next().path("fieldKey").asText());
        }
        return keys;
    }
}
