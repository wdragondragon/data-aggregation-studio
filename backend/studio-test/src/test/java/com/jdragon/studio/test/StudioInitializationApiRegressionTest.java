package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("odps")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("http")));

        mockMvc.perform(get("/api/v1/catalog/capabilities")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executableSourceTypes", hasItem("mysql8")))
                .andExpect(jsonPath("$.data.executableSourceTypes", hasItem("odps")))
                .andExpect(jsonPath("$.data.executableTargetTypes", hasItem("mysql8")))
                .andExpect(jsonPath("$.data.executableTargetTypes", hasItem("odps")))
                .andExpect(jsonPath("$.data.executableTargetTypes", hasItem("minio")))
                .andExpect(jsonPath("$.data.executableDatasourceTypes", hasItem("mysql8")))
                .andExpect(jsonPath("$.data.executableDatasourceTypes", hasItem("odps")))
                .andExpect(jsonPath("$.data.executableDatasourceTypes", hasItem("http")))
                .andExpect(jsonPath("$.data.sourceCapabilities", hasSize(org.hamcrest.Matchers.greaterThan(0))))
                .andExpect(jsonPath("$.data.sourceCapabilities[*].typeCode", hasItem("mysql8")))
                .andExpect(jsonPath("$.data.sourceCapabilities[*].typeCode", hasItem("odps")))
                .andExpect(jsonPath("$.data.sourceCapabilities[*].typeCode", hasItem("http")));
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
    void metadataSchemasShouldContainHttpTechnicalMetaModelsAndFieldDefinitions() throws Exception {
        String authorization = adminAuthorizationHeader();

        MvcResult result = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode schemas = readBody(result).path("data");
        JsonNode httpSource = findSchema(schemas, "technical:http:source");
        JsonNode httpTable = findSchema(schemas, "technical:http:table");
        JsonNode httpField = findSchema(schemas, "technical:http:field");

        assertThat(httpSource).as("http source metamodel").isNotNull();
        assertThat(httpTable).as("http table metamodel").isNotNull();
        assertThat(httpField).as("http field metamodel").isNotNull();

        assertThat(extractFieldKeys(httpSource)).containsExactly("url");
        assertThat(extractFieldKeys(httpTable))
                .containsExactly("physicalName", "description", "protocolMode", "mode", "resultType",
                        "soapVersion", "namespaceUri", "operationName", "soapAction",
                        "requestRootName", "responseRootName", "wsdlUrl",
                        "businessStatusPath", "businessStatusCode", "totalCodePath");
        assertThat(fieldByKey(httpTable, "physicalName").path("fieldName").asText()).isEqualTo("请求路径");
        assertThat(extractFieldKeys(httpField))
                .containsExactly("name", "cnName", "parentNode", "remarks", "primaryKey", "nullable", "type", "size", "scale");
        assertThat(fieldByKey(httpField, "parentNode").path("required").asBoolean()).isTrue();
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
        assertThat(extractFieldKeys(findSchema(schemas, "technical:odps:field")))
                .contains("name", "type", "nullable", "partitionColumn");
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

        MvcResult httpReaderResult = mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "reader")
                        .param("datasourceType", "http")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("http"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andExpect(jsonPath("$.data.incrementalSupported").value(false))
                .andExpect(jsonPath("$.data.fields", hasSize(6)))
                .andReturn();
        JsonNode httpReaderSchema = readBody(httpReaderResult).path("data");
        assertThat(extractFieldKeys(httpReaderSchema))
                .containsExactly("contentType", "header", "params", "requestBody", "pageRead", "pageSize")
                .doesNotContain("url", "mode", "resultType", "responseStatus", "totalCodePath", "columns");
        assertThat(fieldByKey(httpReaderSchema, "header").path("componentType").asText()).isEqualTo("JSON_EDITOR");
        assertThat(fieldByKey(httpReaderSchema, "params").path("defaultValue").asText()).isEqualTo("{}");
        assertThat(fieldByKey(httpReaderSchema, "requestBody").path("defaultValue").asText()).isEqualTo("");

        MvcResult httpWriterResult = mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "writer")
                        .param("datasourceType", "http")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("http"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andExpect(jsonPath("$.data.incrementalSupported").value(false))
                .andExpect(jsonPath("$.data.fields", hasSize(15)))
                .andReturn();
        JsonNode httpWriterSchema = readBody(httpWriterResult).path("data");
        assertThat(extractFieldKeys(httpWriterSchema))
                .containsExactly("contentType", "header", "params", "requestBody", "payloadMode",
                        "dataNodePath", "includeTotal", "totalNodePath", "batchSize",
                        "responseStatus.path", "responseStatus.code", "retryTimes", "retryIntervalMs",
                        "connectTimeoutMs", "socketTimeoutMs")
                .doesNotContain("url", "mode", "columns");
        assertThat(fieldByKey(httpWriterSchema, "header").path("componentType").asText()).isEqualTo("JSON_EDITOR");
        assertThat(fieldByKey(httpWriterSchema, "payloadMode").path("defaultValue").asText()).isEqualTo("object");
        assertThat(fieldByKey(httpWriterSchema, "batchSize").path("defaultValue").asText()).isEqualTo("500");

        MvcResult httpSoapWriterResult = mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "writer")
                        .param("datasourceType", "http")
                        .param("protocolMode", "SOAP")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("http"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andExpect(jsonPath("$.data.fields", hasSize(16)))
                .andReturn();
        JsonNode httpSoapWriterSchema = readBody(httpSoapWriterResult).path("data");
        assertThat(extractFieldKeys(httpSoapWriterSchema))
                .containsExactly("soapVersion", "soapAction", "contentType", "header", "params", "requestBody",
                        "payloadMode", "dataNodePath", "batchSize", "soapFaultFail", "responseStatus.path",
                        "responseStatus.code", "retryTimes", "retryIntervalMs", "connectTimeoutMs", "socketTimeoutMs")
                .doesNotContain("url", "mode", "payloadFormat", "responseType", "columns");
        assertThat(fieldByKey(httpSoapWriterSchema, "payloadMode").path("defaultValue").asText()).isEqualTo("object");
        assertThat(fieldByKey(httpSoapWriterSchema, "dataNodePath").path("defaultValue").isNull()).isTrue();
        assertThat(fieldByKey(httpSoapWriterSchema, "batchSize").path("defaultValue").asText()).isEqualTo("500");

        MvcResult odpsReaderResult = mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "reader")
                        .param("datasourceType", "odps")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("odps"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andReturn();
        JsonNode odpsReaderSchema = readBody(odpsReaderResult).path("data");
        assertThat(extractFieldKeys(odpsReaderSchema))
                .containsExactly("readMode", "selectSql", "partitionSpec", "includePartitionColumns", "offset", "maxRows");
        assertThat(fieldByKey(odpsReaderSchema, "readMode").path("componentType").asText()).isEqualTo("SELECT");
        assertThat(fieldByKey(odpsReaderSchema, "readMode").path("defaultValue").asText()).isEqualTo("auto");

        MvcResult odpsWriterResult = mockMvc.perform(get("/api/v1/catalog/runtime-option-schemas")
                        .param("role", "writer")
                        .param("datasourceType", "odps")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pluginType").value("odps"))
                .andExpect(jsonPath("$.data.runtimeSupported").value(true))
                .andReturn();
        JsonNode odpsWriterSchema = readBody(odpsWriterResult).path("data");
        assertThat(extractFieldKeys(odpsWriterSchema))
                .containsExactly("writeMode", "partitionSpec", "partitionColumns", "batchSize",
                        "emptyAsNull", "autoCreatePartition", "preSql", "postSql");
        assertThat(fieldByKey(odpsWriterSchema, "partitionColumns").path("valueType").asText()).isEqualTo("ARRAY");
        assertThat(fieldByKey(odpsWriterSchema, "writeMode").path("defaultValue").asText()).isEqualTo("append");

        mockMvc.perform(post("/api/v1/meta-schemas/runtime-options/sync-standard")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(22)))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("reader:ftp")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("reader:sftp")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("reader:minio")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("reader:http")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("reader:http-soap")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("reader:odps")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("writer:ftp")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("writer:sftp")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("writer:minio")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("writer:http")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("writer:http-soap")))
                .andExpect(jsonPath("$.data[*].typeCode", hasItem("writer:odps")));
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
    void roleAndPermissionManagementApisShouldRejectProjectMember() throws Exception {
        JsonNode loginBody = loginAsAdmin();
        String authorization = adminAuthorizationHeader(loginBody);
        Long projectId = currentProjectId(loginBody);

        MvcResult userResult = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lt_reg_m09_api_member\",\"displayName\":\"长期回归-权限接口普通成员\",\"passwordHash\":\"LtReg@20260620!\",\"enabled\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        String userId = readBody(userResult).path("data").path("id").asText();

        mockMvc.perform(post("/api/v1/system/project-members")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"" + projectId + "\",\"userId\":\"" + userId + "\",\"roleCode\":\"PROJECT_MEMBER\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        String memberAuthorization = loginAndGetAuthorization("lt_reg_m09_api_member", "LtReg@20260620!", projectId);

        mockMvc.perform(get("/api/v1/roles")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/roles")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"LT_REG_M09_FORBIDDEN_ROLE_PROBE\",\"name\":\"长期回归-低权限越权角色探针\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/permissions")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/permissions")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"lt_reg:m09:forbidden-permission-probe\",\"name\":\"长期回归-低权限越权权限探针\",\"httpMethod\":\"GET\",\"pathPattern\":\"/api/v1/lt-reg-m09-probe\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void metadataSchemaWriteApisShouldRejectProjectMember() throws Exception {
        JsonNode loginBody = loginAsAdmin();
        String authorization = adminAuthorizationHeader(loginBody);
        Long projectId = currentProjectId(loginBody);

        MvcResult userResult = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lt_reg_s16_meta_schema_member\",\"displayName\":\"长期回归-S16元模型普通成员\",\"passwordHash\":\"LtReg@20260622!\",\"enabled\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        String userId = readBody(userResult).path("data").path("id").asText();

        mockMvc.perform(post("/api/v1/system/project-members")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"" + projectId + "\",\"userId\":\"" + userId + "\",\"roleCode\":\"PROJECT_MEMBER\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        String memberAuthorization = loginAndGetAuthorization("lt_reg_s16_meta_schema_member", "LtReg@20260622!", projectId);
        String schemaPayload = "{\"schemaCode\":\"business:lt_reg_s16_acl:customer_profile\","
                + "\"schemaName\":\"长期回归-S16客户画像元模型\","
                + "\"objectType\":\"business\","
                + "\"typeCode\":\"lt_reg_s16_acl.customer_profile\","
                + "\"description\":\"META_MODEL_CONFIG:{\\\"domain\\\":\\\"BUSINESS\\\",\\\"directoryCode\\\":\\\"lt_reg_s16_acl\\\",\\\"directoryName\\\":\\\"客户经营画像\\\",\\\"metaModelCode\\\":\\\"customer_profile\\\",\\\"metaModelName\\\":\\\"客户画像元模型\\\",\\\"displayMode\\\":\\\"SINGLE\\\",\\\"required\\\":false}\","
                + "\"fields\":[{\"fieldKey\":\"customerSegment\",\"fieldName\":\"客户分层\",\"scope\":\"BUSINESS\",\"valueType\":\"STRING\",\"componentType\":\"INPUT\",\"required\":false,\"searchable\":true,\"sortable\":true,\"queryOperators\":[\"EQ\",\"LIKE\"],\"queryDefaultOperator\":\"LIKE\"}]}";

        MvcResult schemaResult = mockMvc.perform(post("/api/v1/meta-schemas/draft")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schemaPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        Long schemaId = readBody(schemaResult).path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/meta-schemas")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/v1/meta-schemas/draft")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schemaPayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/meta-schemas/{schemaId}/publish", schemaId)
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/meta-schemas/technical/sync/mysql8")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/meta-schemas/technical/sync-all")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/meta-schemas/runtime-options/sync-standard")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(delete("/api/v1/meta-schemas/{schemaId}", schemaId)
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/meta-schemas/{schemaId}/publish", schemaId)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void registrationShouldRejectExistingUsernameAtSubmitTime() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\",\"displayName\":\"长期回归-重复注册探针\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Username already exists"));
    }

    @Test
    void disabledUserLoginShouldReturnUnauthorizedInsteadOfServerError() throws Exception {
        String authorization = adminAuthorizationHeader();
        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lt_reg_m09_disabled_login\",\"displayName\":\"长期回归-禁用登录探针\",\"passwordHash\":\"LtReg@20260620!\",\"enabled\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lt_reg_m09_disabled_login\",\"password\":\"LtReg@20260620!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void projectExportShouldNotIncludeSharedWorkflowsFromOtherProjects() throws Exception {
        JsonNode loginBody = loginAsAdmin();
        String authorization = adminAuthorizationHeader(loginBody);
        Long sourceProjectId = currentProjectId(loginBody);

        MvcResult targetProjectResult = mockMvc.perform(post("/api/v1/system/projects")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(sourceProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectCode\":\"lt_reg_m10_export_receiver\",\"projectName\":\"长期回归-M10导出接收项目\",\"enabled\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        Long targetProjectId = readBody(targetProjectResult).path("data").path("id").asLong();

        MvcResult workflowResult = mockMvc.perform(post("/api/v1/workflows")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(sourceProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"lt_reg_m10_export_source_workflow\",\"name\":\"长期回归-M10源项目共享流程\",\"nodes\":[{\"nodeCode\":\"m10_shell_probe\",\"nodeName\":\"长期回归-M10导出探针节点\",\"nodeType\":\"SHELL\",\"config\":{}}],\"edges\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        Long sourceWorkflowId = readBody(workflowResult).path("data").path("id").asLong();

        mockMvc.perform(post("/api/v1/system/resource-shares")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(sourceProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceProjectId\":\"" + sourceProjectId + "\",\"targetProjectId\":\"" + targetProjectId + "\",\"resourceType\":\"WORKFLOW\",\"resourceId\":\"" + sourceWorkflowId + "\",\"enabled\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        MvcResult sharedWorkflowListResult = mockMvc.perform(get("/api/v1/workflows")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(targetProjectId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        assertThat(extractIds(readBody(sharedWorkflowListResult).path("data"))).contains(String.valueOf(sourceWorkflowId));

        MvcResult exportResult = mockMvc.perform(get("/api/v1/exports/project")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(targetProjectId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        assertThat(extractIds(readBody(exportResult).path("data").path("workflows"))).doesNotContain(String.valueOf(sourceWorkflowId));
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

    private String loginAndGetAuthorization(String username, String password, Long projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return "Bearer " + readBody(result).path("data").path("token").asText();
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

    private List<String> extractIds(JsonNode items) {
        List<String> ids = new ArrayList<String>();
        if (items == null || !items.isArray()) {
            return ids;
        }
        Iterator<JsonNode> iterator = items.elements();
        while (iterator.hasNext()) {
            ids.add(iterator.next().path("id").asText());
        }
        return ids;
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
