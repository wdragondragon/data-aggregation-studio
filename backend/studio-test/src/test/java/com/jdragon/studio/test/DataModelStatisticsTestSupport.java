package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class DataModelStatisticsTestSupport extends StudioApiRegressionTestSupport {

    private static final AtomicLong INDEX_ID_SEQUENCE = new AtomicLong(900000L);
    private Long testRuntimeClusterId;

    protected Long syncMysqlTechnicalTableSchema(String authorization) throws Exception {
        mockMvc.perform(post("/api/v1/meta-schemas/technical/sync/mysql8")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        MvcResult result = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        for (JsonNode schema : readBody(result).path("data")) {
            if ("technical:mysql8:table".equals(schema.path("schemaCode").asText())) {
                return schema.path("currentVersionId").asLong();
            }
        }
        throw new IllegalStateException("Unable to resolve technical:mysql8:table currentVersionId");
    }

    protected Long createBusinessSchema(String authorization,
                                      String schemaCode,
                                      String schemaName,
                                      String metaModelCode,
                                      String directoryCode,
                                      String directoryName,
                                      String displayMode,
                                      List<Map<String, Object>> fields) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("schemaCode", schemaCode);
        payload.put("schemaName", schemaName);
        payload.put("objectType", "business");
        payload.put("typeCode", directoryCode + "." + metaModelCode);
        payload.put("description", "META_MODEL_CONFIG:{\"domain\":\"BUSINESS\",\"directoryCode\":\"" + directoryCode
                + "\",\"directoryName\":\"" + directoryName + "\",\"metaModelCode\":\"" + metaModelCode
                + "\",\"metaModelName\":\"" + schemaName + "\",\"displayMode\":\"" + displayMode
                + "\",\"required\":false}");
        payload.put("fields", fields);

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
                return schema.path("currentVersionId").asLong();
            }
        }
        throw new IllegalStateException("Unable to resolve currentVersionId for schema " + schemaCode);
    }

    protected void saveBusinessSchemaDraft(String authorization,
                                         Long schemaId,
                                         String schemaCode,
                                         String schemaName,
                                         String metaModelCode,
                                         String directoryCode,
                                         String directoryName,
                                         String displayMode,
                                         List<Map<String, Object>> fields) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("schemaId", schemaId);
        payload.put("schemaCode", schemaCode);
        payload.put("schemaName", schemaName);
        payload.put("objectType", "business");
        payload.put("typeCode", directoryCode + "." + metaModelCode);
        payload.put("description", "META_MODEL_CONFIG:{\"domain\":\"BUSINESS\",\"directoryCode\":\"" + directoryCode
                + "\",\"directoryName\":\"" + directoryName + "\",\"metaModelCode\":\"" + metaModelCode
                + "\",\"metaModelName\":\"" + schemaName + "\",\"displayMode\":\"" + displayMode
                + "\",\"required\":false}");
        payload.put("fields", fields);

        mockMvc.perform(post("/api/v1/meta-schemas/draft")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        awaitIndexQueueIdle();
    }

    protected JsonNode findSchemaByCode(String authorization,
                                      String schemaCode) throws Exception {
        MvcResult listResult = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode schema : readBody(listResult).path("data")) {
            if (schemaCode.equals(schema.path("schemaCode").asText())) {
                return schema;
            }
        }
        throw new IllegalStateException("Unable to resolve schema by code " + schemaCode);
    }

    protected Map<String, Object> businessField(String fieldKey,
                                              String fieldName,
                                              String valueType,
                                              boolean searchable,
                                              boolean sensitive) {
        Map<String, Object> field = new LinkedHashMap<String, Object>();
        field.put("fieldKey", fieldKey);
        field.put("fieldName", fieldName);
        field.put("scope", "BUSINESS");
        field.put("valueType", valueType);
        field.put("componentType", componentType(valueType));
        field.put("required", false);
        field.put("sensitive", sensitive);
        field.put("searchable", searchable);
        field.put("sortable", searchable);
        field.put("queryOperators", queryOperators(valueType));
        field.put("queryDefaultOperator", defaultOperator(valueType));
        return field;
    }

    protected String componentType(String valueType) {
        if ("INTEGER".equalsIgnoreCase(valueType) || "LONG".equalsIgnoreCase(valueType) || "DECIMAL".equalsIgnoreCase(valueType)) {
            return "NUMBER";
        }
        if ("BOOLEAN".equalsIgnoreCase(valueType)) {
            return "SWITCH";
        }
        if ("JSON".equalsIgnoreCase(valueType)) {
            return "JSON_EDITOR";
        }
        return "INPUT";
    }

    protected List<String> queryOperators(String valueType) {
        if ("INTEGER".equalsIgnoreCase(valueType) || "LONG".equalsIgnoreCase(valueType) || "DECIMAL".equalsIgnoreCase(valueType)) {
            return Arrays.asList("EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN");
        }
        if ("BOOLEAN".equalsIgnoreCase(valueType)) {
            return Arrays.asList("EQ");
        }
        if ("STRING".equalsIgnoreCase(valueType)) {
            return Arrays.asList("EQ", "LIKE", "IN");
        }
        return new ArrayList<String>();
    }

    protected String defaultOperator(String valueType) {
        return "STRING".equalsIgnoreCase(valueType) ? "LIKE" : "EQ";
    }

    protected Long createProject(String authorization, String projectCode, String projectName) throws Exception {
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
        Long projectId = readBody(result).path("data").path("id").asLong();
        if (testRuntimeClusterId == null) {
            testRuntimeClusterId = createAndAuthorizeTestRuntimeCluster(authorization, projectId);
        } else {
            authorizeTestRuntimeCluster(authorization, projectId, testRuntimeClusterId);
        }
        return projectId;
    }

    protected Long createDatasource(String authorization, Long projectId, String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        payload.put("typeCode", "mysql8");
        payload.put("enabled", true);
        payload.put("executable", false);
        payload.put("applicableClusterIds", java.util.Collections.singletonList(requireTestRuntimeClusterId(projectId)));
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

    private Long requireTestRuntimeClusterId(Long projectId) {
        if (testRuntimeClusterId == null) {
            throw new IllegalStateException("Test runtime cluster was not prepared for project " + projectId);
        }
        return testRuntimeClusterId;
    }

    protected Long createModel(String authorization,
                             Long projectId,
                             Long datasourceId,
                             Long schemaVersionId,
                             String name,
                             Map<String, Object> businessMetadata) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("datasourceId", datasourceId);
        payload.put("name", name);
        payload.put("physicalLocator", name);
        payload.put("modelKind", "TABLE");
        payload.put("schemaVersionId", schemaVersionId);
        payload.put("technicalMetadata", new LinkedHashMap<String, Object>());
        payload.put("businessMetadata", businessMetadata);

        MvcResult result = mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        awaitIndexQueueIdle();
        return readBody(result).path("data").path("id").asLong();
    }

    protected void shareModel(String authorization,
                            Long sourceProjectId,
                            Long targetProjectId,
                            Long modelId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sourceProjectId", sourceProjectId);
        payload.put("targetProjectId", targetProjectId);
        payload.put("resourceType", StudioConstants.RESOURCE_TYPE_DATA_MODEL);
        payload.put("resourceId", modelId);
        payload.put("enabled", 1);

        mockMvc.perform(post("/api/v1/system/resource-shares")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    protected JsonNode statistics(String authorization,
                                Long projectId,
                                Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/models/statistics")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return readBody(result);
    }

    protected JsonNode chartQuery(String authorization,
                                Long projectId,
                                Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/statistics/charts/query")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return readBody(result);
    }

    protected Map<String, Object> statisticsRequest(String schemaCode, String fieldKey, String statType) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("targetMetaSchemaCode", schemaCode);
        payload.put("targetFieldKey", fieldKey);
        payload.put("targetScope", "BUSINESS");
        payload.put("statType", statType);
        return payload;
    }

    protected Map<String, Object> chartRequest(String schemaCode,
                                             String fieldKey,
                                             String targetScope,
                                             String chartType) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("targetMetaSchemaCode", schemaCode);
        payload.put("targetFieldKey", fieldKey);
        payload.put("targetScope", targetScope);
        payload.put("chartType", chartType);
        payload.put("topN", 10);
        payload.put("timeMode", "CREATED_AT");
        return payload;
    }

    protected Map<String, Object> singleBusinessMetadata(Long schemaVersionId, String fieldKey, Object value) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("schemaVersionId", schemaVersionId);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(fieldKey, value);
        entry.put("values", values);
        entries.add(entry);
        metadata.put("__metaModels", entries);
        return metadata;
    }

    protected Map<String, Object> multipleBusinessMetadata(Long schemaVersionId, List<Map<String, Object>> rows) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("schemaVersionId", schemaVersionId);
        entry.put("rows", rows);
        entries.add(entry);
        metadata.put("__metaModels", entries);
        return metadata;
    }

    protected Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            row.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return row;
    }

    protected Map<String, Object> queryGroup(String schemaCode, String rowMatchMode, Map<String, Object>... conditions) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("scope", "BUSINESS");
        group.put("metaSchemaCode", schemaCode);
        group.put("rowMatchMode", rowMatchMode);
        group.put("conditions", Arrays.asList(conditions));
        return group;
    }

    protected Map<String, Object> condition(String fieldKey, String operator, Object value) {
        Map<String, Object> condition = new LinkedHashMap<String, Object>();
        condition.put("fieldKey", fieldKey);
        condition.put("operator", operator);
        condition.put("value", value);
        return condition;
    }

    protected Map<String, Long> bucketCountMap(JsonNode buckets) {
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        for (JsonNode bucket : buckets) {
            result.put(bucket.path("value").asText(), bucket.path("count").asLong());
        }
        return result;
    }

    protected List<String> schemaCodes(JsonNode schemas) {
        List<String> result = new ArrayList<String>();
        for (JsonNode schema : schemas) {
            result.add(schema.path("schemaCode").asText());
        }
        return result;
    }

    protected List<String> fieldKeys(JsonNode fields) {
        List<String> result = new ArrayList<String>();
        for (JsonNode field : fields) {
            result.add(field.path("fieldKey").asText());
        }
        return result;
    }

    protected JsonNode findSchemaOptionByCode(JsonNode schemas, String schemaCode) {
        for (JsonNode schema : schemas) {
            if (schemaCode.equals(schema.path("schemaCode").asText())) {
                return schema;
            }
        }
        throw new IllegalStateException("Unable to resolve schema option by code " + schemaCode);
    }

    protected Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(key, value);
        return result;
    }

    protected void insertForeignTenantLeakRow(Long modelId,
                                            Long datasourceId,
                                            Long schemaVersionId,
                                            String schemaCode,
                                            String fieldKey,
                                            String value,
                                            Long projectId) {
        jdbcTemplate.update("insert into data_model_attr_index (" +
                        "id, tenant_id, project_id, model_id, datasource_id, meta_schema_version_id, " +
                        "meta_schema_code, scope, meta_model_code, item_key, field_key, value_type, keyword_value, text_value, raw_value" +
                        ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                INDEX_ID_SEQUENCE.incrementAndGet(),
                "other-tenant",
                projectId,
                modelId,
                datasourceId,
                schemaVersionId,
                schemaCode,
                "BUSINESS",
                "owner",
                "__single__",
                fieldKey,
                "STRING",
                value,
                value.toLowerCase(),
                value);
    }

    protected Map<String, Object> minimalSqlMetadata() {
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("host", "127.0.0.1");
        technicalMetadata.put("port", "3306");
        technicalMetadata.put("database", "demo");
        technicalMetadata.put("userName", "root");
        technicalMetadata.put("password", "root");
        return technicalMetadata;
    }
}
