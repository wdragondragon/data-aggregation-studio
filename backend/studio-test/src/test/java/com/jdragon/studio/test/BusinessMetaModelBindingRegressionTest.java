package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BusinessMetaModelBindingRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void shouldPersistMultipleBusinessMetaModelsForDatasourceSourceCode() throws Exception {
        String authorization = adminAuthorizationHeader();
        mockMvc.perform(post("/api/v1/meta-schemas/technical/sync/mysql8")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Long governanceSchemaVersionId = createBusinessSchema(authorization,
                "business:governance:source",
                "治理信息",
                "source",
                "governance",
                "治理目录",
                "owner",
                "负责人");
        Long securitySchemaVersionId = createBusinessSchema(authorization,
                "business:security:source",
                "安全信息",
                "source",
                "security",
                "安全目录",
                "dataLevel",
                "数据等级");

        Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        entries.add(singleBusinessEntry(governanceSchemaVersionId, "owner", "alice"));
        entries.add(singleBusinessEntry(securitySchemaVersionId, "dataLevel", "L2"));
        businessMetadata.put("__metaModels", entries);

        Map<String, Object> datasourcePayload = new LinkedHashMap<String, Object>();
        datasourcePayload.put("name", "Business Bound Datasource");
        datasourcePayload.put("typeCode", "mysql8");
        datasourcePayload.put("enabled", true);
        datasourcePayload.put("executable", false);
        datasourcePayload.put("technicalMetadata", minimalSqlMetadata());
        datasourcePayload.put("businessMetadata", businessMetadata);

        MvcResult saveResult = mockMvc.perform(post("/api/v1/datasources")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(datasourcePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.businessMetadata.__metaModels.length()").value(2))
                .andReturn();

        JsonNode savedEntries = readBody(saveResult).path("data").path("businessMetadata").path("__metaModels");
        assertThat(savedEntries.isArray()).isTrue();
        assertThat(savedEntries).hasSize(2);
        assertThat(savedEntries.get(0).path("metaModelCode").asText()).isEqualTo("source");
        assertThat(savedEntries.get(0).path("values").isObject()).isTrue();
        assertThat(savedEntries.get(1).path("metaModelCode").asText()).isEqualTo("source");
    }

    @Test
    void shouldNotInheritDatasourceBusinessMetadataIntoManualModelSave() throws Exception {
        String authorization = adminAuthorizationHeader();
        mockMvc.perform(post("/api/v1/meta-schemas/technical/sync/mysql8")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Long governanceSchemaVersionId = createBusinessSchema(authorization,
                "business:governance:source",
                "治理信息",
                "source",
                "governance",
                "治理目录",
                "owner",
                "负责人");

        Map<String, Object> datasourceBusinessMetadata = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> datasourceEntries = new ArrayList<Map<String, Object>>();
        datasourceEntries.add(singleBusinessEntry(governanceSchemaVersionId, "owner", "alice"));
        datasourceBusinessMetadata.put("__metaModels", datasourceEntries);

        Map<String, Object> datasourcePayload = new LinkedHashMap<String, Object>();
        datasourcePayload.put("name", "Model Sync Source");
        datasourcePayload.put("typeCode", "mysql8");
        datasourcePayload.put("enabled", true);
        datasourcePayload.put("executable", false);
        datasourcePayload.put("technicalMetadata", minimalSqlMetadata());
        datasourcePayload.put("businessMetadata", datasourceBusinessMetadata);

        MvcResult datasourceResult = mockMvc.perform(post("/api/v1/datasources")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(datasourcePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        String datasourceId = readBody(datasourceResult).path("data").path("id").asText();
        assertThat(datasourceId).isNotBlank();

        MvcResult schemasResult = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode schemas = readBody(schemasResult).path("data");
        Long tableSchemaVersionId = null;
        for (JsonNode schema : schemas) {
            if ("technical:mysql8:table".equals(schema.path("schemaCode").asText())) {
                tableSchemaVersionId = schema.path("currentVersionId").isNumber()
                        ? schema.path("currentVersionId").asLong()
                        : Long.parseLong(schema.path("currentVersionId").asText());
                break;
            }
        }
        assertThat(tableSchemaVersionId).isNotNull();

        Map<String, Object> modelPayload = new LinkedHashMap<String, Object>();
        modelPayload.put("datasourceId", datasourceId);
        modelPayload.put("name", "manual_orders");
        modelPayload.put("physicalLocator", "manual_orders");
        modelPayload.put("modelKind", "TABLE");
        modelPayload.put("schemaVersionId", tableSchemaVersionId);
        modelPayload.put("technicalMetadata", new LinkedHashMap<String, Object>());
        modelPayload.put("businessMetadata", new LinkedHashMap<String, Object>());

        mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(modelPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.businessMetadata.__metaModels.length()").value(0));
    }

    private Long createBusinessSchema(String authorization,
                                      String schemaCode,
                                      String schemaName,
                                      String metaModelCode,
                                      String directoryCode,
                                      String directoryName,
                                      String fieldKey,
                                      String fieldName) throws Exception {
        Map<String, Object> field = new LinkedHashMap<String, Object>();
        field.put("fieldKey", fieldKey);
        field.put("fieldName", fieldName);
        field.put("scope", "BUSINESS");
        field.put("valueType", "STRING");
        field.put("componentType", "INPUT");
        field.put("required", false);
        field.put("searchable", true);
        field.put("sortable", true);
        field.put("queryOperators", java.util.Arrays.asList("EQ", "LIKE"));
        field.put("queryDefaultOperator", "LIKE");

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("schemaCode", schemaCode);
        payload.put("schemaName", schemaName);
        payload.put("objectType", "business");
        payload.put("typeCode", directoryCode + "." + metaModelCode);
        payload.put("description", "META_MODEL_CONFIG:{\"domain\":\"BUSINESS\",\"directoryCode\":\"" + directoryCode
                + "\",\"directoryName\":\"" + directoryName + "\",\"metaModelCode\":\"" + metaModelCode
                + "\",\"metaModelName\":\"" + schemaName + "\",\"displayMode\":\"SINGLE\",\"required\":false}");
        payload.put("fields", java.util.Collections.singletonList(field));

        MvcResult draftResult = mockMvc.perform(post("/api/v1/meta-schemas/draft")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        Long schemaId = readBody(draftResult).path("data").path("id").asLong();
        mockMvc.perform(post("/api/v1/meta-schemas/{schemaId}/publish", schemaId)
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        MvcResult listResult = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        for (JsonNode schema : readBody(listResult).path("data")) {
            if (schemaCode.equals(schema.path("schemaCode").asText())) {
                return schema.path("currentVersionId").isNumber()
                        ? schema.path("currentVersionId").asLong()
                        : Long.parseLong(schema.path("currentVersionId").asText());
            }
        }
        throw new IllegalStateException("Unable to resolve currentVersionId for schema " + schemaCode);
    }

    private Map<String, Object> singleBusinessEntry(Long schemaVersionId, String fieldKey, Object value) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("schemaVersionId", schemaVersionId);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(fieldKey, value);
        entry.put("values", values);
        return entry;
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
