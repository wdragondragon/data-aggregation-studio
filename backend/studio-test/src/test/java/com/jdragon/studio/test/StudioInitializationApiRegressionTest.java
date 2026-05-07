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
    void catalogEndpointsShouldExposeTableDrivenDatasourceTypesAndCapabilities() throws Exception {
        String authorization = adminAuthorizationHeader();

        mockMvc.perform(get("/api/v1/catalog/datasource-types")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(org.hamcrest.Matchers.greaterThan(0))))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("mysql8")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("odps")));

        mockMvc.perform(get("/api/v1/catalog/capabilities")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executableSourceTypes", hasItem("mysql8")))
                .andExpect(jsonPath("$.data.executableTargetTypes", hasItem("mysql8")))
                .andExpect(jsonPath("$.data.executableTargetTypes", hasItem("minio")))
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
    void metadataSchemasShouldAlignNonDatabaseSourceMetaModelsWithCapabilityTypes() throws Exception {
        String authorization = adminAuthorizationHeader();

        MvcResult result = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode schemas = readBody(result).path("data");

        assertThat(extractFieldKeys(findSchema(schemas, "technical:ftp:source")))
                .contains("host", "port", "username", "password", "ftpTLS", "connectMode", "timeout")
                .doesNotContain("endpoint");
        assertThat(extractFieldKeys(findSchema(schemas, "technical:minio:source")))
                .contains("endpoint", "accessKey", "secretKey", "bucket");
        assertThat(extractFieldKeys(findSchema(schemas, "technical:minio:source")))
                .doesNotContain("rootPath", "pattern", "fileType", "encoding", "delimiter");
        assertThat(extractFieldKeys(findSchema(schemas, "technical:tbds-hdfs:source")))
                .contains("hdfsSiteFilePath", "coreSiteFilePath", "hadoopConfig", "kerberosPrincipal", "kerberosKeytabFilePath", "krb5Conf")
                .doesNotContain("endpoint");
        assertThat(extractFieldKeys(findSchema(schemas, "technical:kafka:source")))
                .contains("bootstrap.servers", "topic", "group.id", "username", "password")
                .doesNotContain("brokers");
        assertThat(extractFieldKeys(findSchema(schemas, "technical:rabbitmq:source")))
                .contains("host", "port", "username", "password", "queueName");
        assertThat(extractFieldKeys(findSchema(schemas, "technical:rocketmq:source")))
                .contains("namesrvAddr", "producerGroup", "topic", "consumerGroup", "accessKey", "secretKey");
        assertThat(extractFieldKeys(findSchema(schemas, "technical:influxdb:source")))
                .contains("host", "database", "bucket", "password");
        assertThat(extractFieldKeys(findSchema(schemas, "technical:influxdbv1:source")))
                .contains("host", "database", "userName", "password");
        assertThat(extractFieldKeys(findSchema(schemas, "technical:odps:source")))
                .contains("host", "database", "userName", "password", "extraParams");
        assertThat(extractFieldKeys(findSchema(schemas, "technical:tbds-hive3:source")))
                .contains("host", "port", "database", "principal", "keytabPath", "krb5File", "other");
    }

    @Test
    void runtimeOptionSchemaShouldBeDrivenByMetaSchemaDefinitionsOnly() throws Exception {
        String authorization = adminAuthorizationHeader();

        MvcResult mysqlReaderResult = mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "reader")
                        .param("datasourceType", "mysql8")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("mysql8"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andExpect(jsonPath("$.data.fields", hasSize(2)))
                .andReturn();

        JsonNode mysqlReaderSchema = readBody(mysqlReaderResult).path("data");
        assertThat(extractFieldKeys(mysqlReaderSchema)).containsExactly("selectSql", "mandatoryEncoding");

        MvcResult mysqlWriterResult = mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "writer")
                        .param("datasourceType", "mysql8")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("mysql8"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andReturn();
        JsonNode mysqlWriterSchema = readBody(mysqlWriterResult).path("data");
        assertThat(extractFieldKeys(mysqlWriterSchema)).containsExactly("writeMode", "pkColumn", "batchSize", "emptyAsNull");
        JsonNode pkColumnField = fieldByKey(mysqlWriterSchema, "pkColumn");
        assertThat(pkColumnField.path("valueType").asText()).isEqualTo("ARRAY");
        assertThat(pkColumnField.path("componentType").asText()).isEqualTo("SELECT");
        assertThat(pkColumnField.path("defaultValue").asText()).isEqualTo("[]");

        mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "writer")
                        .param("datasourceType", "postgres")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("postgresql"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andExpect(jsonPath("$.data.fields[*].fieldKey", hasItem("writeMode")));

        MvcResult minioReaderResult = mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "reader")
                        .param("datasourceType", "minio")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("minio"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andReturn();
        JsonNode minioReaderSchema = readBody(minioReaderResult).path("data");
        assertThat(extractFieldKeys(minioReaderSchema))
                .containsExactly("hasHeader", "nullFormat", "fieldQuote", "dataType")
                .doesNotContain("rootPath", "partitionType", "partition", "pattern", "fileType", "encoding", "delimiter");

        MvcResult minioWriterResult = mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "writer")
                        .param("datasourceType", "minio")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("minio"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andReturn();
        JsonNode minioWriterSchema = readBody(minioWriterResult).path("data");
        assertThat(extractFieldKeys(minioWriterSchema))
                .containsExactly("writeMode", "hasHeader", "nullFormat", "fieldQuote", "sheetName")
                .doesNotContain("rootPath", "fileName", "fileType", "encoding", "delimiter", "efile");

        MvcResult fusionReaderResult = mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "reader")
                        .param("datasourceType", "fusion")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("fusion"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andReturn();
        JsonNode fusionReaderSchema = readBody(fusionReaderResult).path("data");
        assertThat(extractFieldKeys(fusionReaderSchema))
                .contains("defaultStrategy", "errorMode", "performance.parallelSourceCount",
                        "performance.memoryLimitMB", "cache.partitionCount", "adaptiveMerge.enabled",
                        "adaptiveMerge.pendingKeyThreshold", "adaptiveMerge.pendingMemoryMB",
                        "adaptiveMerge.overflowSpillPath");

        mockMvc.perform(post("/api/v1/meta-schemas/runtime-options/sync-standard")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(16)))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("reader:ftp")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("reader:sftp")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("reader:minio")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("writer:ftp")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("writer:sftp")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("writer:minio")));
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
                .andExpect(jsonPath("$.data.items", hasSize(0)));

        mockMvc.perform(post("/api/v1/models/query")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items", hasSize(0)));

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

    private JsonNode fieldByKey(JsonNode schema, String fieldKey) {
        if (schema == null || !schema.has("fields") || !schema.get("fields").isArray()) {
            return null;
        }
        Iterator<JsonNode> iterator = schema.get("fields").elements();
        while (iterator.hasNext()) {
            JsonNode field = iterator.next();
            if (fieldKey.equals(field.path("fieldKey").asText())) {
                return field;
            }
        }
        return null;
    }
}
