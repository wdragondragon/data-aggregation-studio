package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import com.jdragon.studio.test.support.StudioHttpIntegrationTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Enumeration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RealWebServiceOpenApiIT extends StudioHttpIntegrationTestSupport {

    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @AfterAll
    static void shutdownJdbcCleanupThreads() {
        AbandonedConnectionCleanupThread.checkedShutdown();
        Enumeration<java.sql.Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            java.sql.Driver driver = drivers.nextElement();
            if (driver.getClass().getName().startsWith("com.mysql.")) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (Exception ignored) {
                    // Best-effort test process cleanup.
                }
            }
        }
    }

    @Test
    void dataServiceSoapEndpointShouldExposeWsdlAndQueryMysqlAggThroughRealHttp() throws Exception {
        MySqlAggConfig mysql = mysqlAggConfig();
        assumeMysqlAggReachable(mysql);

        TestSession session = loginSession();
        Long datasourceId = ensureMysqlAggDatasource(session, mysql);
        String suffix = runSuffix();
        String tableName = "codex_ws_ds_" + suffix;
        createQueryTable(mysql, tableName);
        Long modelId = createModel(session, datasourceId, tableName, queryColumns());
        JsonNode service = createDataService(session, datasourceId, modelId, tableName, suffix);
        JsonNode published = requireSuccess(postJson("/api/v1/data-services/" + service.path("data").path("id").asText() + "/publish",
                session.authorization, session.projectId, Collections.emptyMap()));
        JsonNode subscription = createDataServiceSubscription(session, published.path("data").path("id").asLong(), "codex-ws-ds");

        String serviceCode = published.path("data").path("serviceCode").asText();
        String serviceKey = published.path("data").path("serviceKey").asText();
        String token = subscription.path("data").path("token").asText();

        HttpResponse<String> wsdl = get("/openapi/ws/data-services/" + serviceCode + "/" + serviceKey + "?wsdl", null, null);
        assertThat(wsdl.statusCode()).isEqualTo(200);
        assertThat(wsdl.body()).contains("wsdl:definitions", "queryCustomerOrders", "customerId");

        String httpTokenRequest = soapEnvelope("http://studio.jdragon.com/test/" + serviceCode,
                "queryCustomerOrders",
                null,
                Map.of("customerId", "1001", "pageSize", "5"));
        HttpResponse<String> httpTokenResponse = postXml("/openapi/ws/data-services/" + serviceCode + "/" + serviceKey,
                httpTokenRequest,
                Collections.singletonMap("X-Data-Service-Token", token));
        assertThat(httpTokenResponse.statusCode()).isEqualTo(200);
        assertThat(httpTokenResponse.body()).contains("Alice SOAP", "customer_name", "pageSize");

        String soapHeaderTokenRequest = soapEnvelope("http://studio.jdragon.com/test/" + serviceCode,
                "queryCustomerOrders",
                Collections.singletonMap("dataServiceToken", token),
                Map.of("customerId", "1002", "pageSize", "5"));
        HttpResponse<String> soapHeaderTokenResponse = postXml("/openapi/ws/data-services/" + serviceCode + "/" + serviceKey,
                soapHeaderTokenRequest,
                Collections.emptyMap());
        assertThat(soapHeaderTokenResponse.statusCode()).isEqualTo(200);
        assertThat(soapHeaderTokenResponse.body()).contains("Bob SOAP").doesNotContain("Alice SOAP");

        HttpResponse<String> missingTokenResponse = postXml("/openapi/ws/data-services/" + serviceCode + "/" + serviceKey,
                httpTokenRequest,
                Collections.emptyMap());
        assertThat(missingTokenResponse.statusCode()).isEqualTo(401);
        assertThat(missingTokenResponse.body()).contains("soap:Fault");
    }

    @Test
    void dataIngestionSoapEndpointShouldExposeWsdlAndWriteMysqlAggRowsThroughRealHttp() throws Exception {
        MySqlAggConfig mysql = mysqlAggConfig();
        assumeMysqlAggReachable(mysql);

        TestSession session = loginSession();
        Long datasourceId = ensureMysqlAggDatasource(session, mysql);
        String suffix = runSuffix();
        String tableName = "codex_ws_ing_" + suffix;
        createIngestionTable(mysql, tableName);
        Long modelId = createModel(session, datasourceId, tableName, ingestionColumns());
        JsonNode service = createDataIngestionService(session, datasourceId, modelId, tableName, suffix);
        JsonNode published = requireSuccess(postJson("/api/v1/data-ingestion-services/" + service.path("data").path("id").asText() + "/publish",
                session.authorization, session.projectId, Collections.emptyMap()));
        JsonNode subscription = createDataIngestionSubscription(session, published.path("data").path("id").asLong(), "codex-ws-ing");

        String serviceCode = published.path("data").path("serviceCode").asText();
        String serviceKey = published.path("data").path("serviceKey").asText();
        String token = subscription.path("data").path("token").asText();

        HttpResponse<String> wsdl = get("/openapi/ws/data-ingestion-services/" + serviceCode + "/" + serviceKey + "?wsdl", null, null);
        assertThat(wsdl.statusCode()).isEqualTo(200);
        assertThat(wsdl.body()).contains("wsdl:definitions", "submitCustomerOrders", "requestId", "receivedCount");

        String request = soapEnvelope("http://studio.jdragon.com/test/" + serviceCode,
                "submitCustomerOrders",
                Map.of("dataIngestionToken", token, "sourceChannel", "SOAP_HEADER"),
                Map.of("records", List.of(
                        Map.of("record", Map.of("id", "2001", "ingestName", "Carol SOAP", "amount", "41.20")),
                        Map.of("record", Map.of("id", "2002", "ingestName", "Dave SOAP", "amount", "72.80"))
                )));
        HttpResponse<String> response = postXml("/openapi/ws/data-ingestion-services/" + serviceCode + "/" + serviceKey,
                request,
                Collections.emptyMap());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("receivedCount", "2", "successCount", "SUCCESS");

        assertInsertedRow(mysql, tableName, 2001L, "Carol SOAP", new BigDecimal("41.20"), "SOAP_HEADER");
        assertInsertedRow(mysql, tableName, 2002L, "Dave SOAP", new BigDecimal("72.80"), "SOAP_HEADER");

        HttpResponse<String> missingTokenResponse = postXml("/openapi/ws/data-ingestion-services/" + serviceCode + "/" + serviceKey,
                soapEnvelope("http://studio.jdragon.com/test/" + serviceCode,
                        "submitCustomerOrders",
                        null,
                        Map.of("records", List.of(Map.of("record", Map.of("id", "2999", "ingestName", "No Token", "amount", "1.00"))))),
                Collections.emptyMap());
        assertThat(missingTokenResponse.statusCode()).isEqualTo(401);
        assertThat(missingTokenResponse.body()).contains("soap:Fault");
    }

    private TestSession loginSession() throws Exception {
        JsonNode login = loginAsAdminHttp();
        return new TestSession(bearer(login), currentProjectId(login));
    }

    private Long ensureMysqlAggDatasource(TestSession session, MySqlAggConfig mysql) throws Exception {
        JsonNode list = requireSuccess(get("/api/v1/datasources", session.authorization, session.projectId));
        for (JsonNode item : list.path("data")) {
            if ("mysql_agg".equals(item.path("name").asText()) && "mysql8".equalsIgnoreCase(item.path("typeCode").asText())) {
                return item.path("id").asLong();
            }
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", "mysql_agg");
        payload.put("typeCode", "mysql8");
        payload.put("enabled", Boolean.TRUE);
        payload.put("executable", Boolean.TRUE);
        payload.put("technicalMetadata", mysql.toTechnicalMetadata());
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());
        JsonNode created = requireSuccess(postJson("/api/v1/datasources", session.authorization, session.projectId, payload));
        return created.path("data").path("id").asLong();
    }

    private Long createModel(TestSession session,
                             Long datasourceId,
                             String tableName,
                             List<Map<String, Object>> columns) throws Exception {
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("physicalName", tableName);
        technicalMetadata.put("tableType", "BASE TABLE");
        technicalMetadata.put("columnCount", columns.size());
        technicalMetadata.put("columns", columns);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("datasourceId", datasourceId);
        payload.put("name", tableName);
        payload.put("physicalLocator", tableName);
        payload.put("modelKind", "TABLE");
        payload.put("technicalMetadata", technicalMetadata);
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());
        JsonNode created = requireSuccess(postJson("/api/v1/models", session.authorization, session.projectId, payload));
        awaitIndexQueueIdle();
        return created.path("data").path("id").asLong();
    }

    private JsonNode createDataService(TestSession session,
                                       Long datasourceId,
                                       Long modelId,
                                       String tableName,
                                       String suffix) throws Exception {
        String serviceCode = "codex_ws_ds_" + suffix;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("serviceCode", serviceCode);
        payload.put("serviceName", "Codex WS Data Service " + suffix);
        payload.put("serviceType", "MODEL_PUBLISH");
        payload.put("sourceType", "TABLE");
        payload.put("datasourceId", datasourceId);
        payload.put("modelId", modelId);
        payload.put("requestMethod", "POST");
        payload.put("responseType", "JSON");
        payload.put("cacheEnabled", Boolean.FALSE);
        payload.put("tokenRequired", Boolean.TRUE);
        payload.put("webserviceEnabled", Boolean.TRUE);
        payload.put("webserviceConfig", webServiceConfig(serviceCode, "queryCustomerOrders"));
        payload.put("requestParams", List.of(requestParam(3, "customerId", "id", "INT", "EQ", true)));
        payload.put("responseParams", List.of(
                responseParam(1, "id", "id"),
                responseParam(2, "customer_name", "customer_name"),
                responseParam(3, "amount", "amount"),
                responseParam(4, "category", "category")
        ));
        payload.put("publishParams", List.of(
                publishParam(1, "pageNum", "pageNum", "BODY", "INT", "1", false),
                publishParam(2, "pageSize", "pageSize", "BODY", "INT", "5", false),
                publishParam(3, "customerId", "customerId", "BODY", "INT", "1001", true)
        ));
        JsonNode created = requireSuccess(postJson("/api/v1/data-services", session.authorization, session.projectId, payload));
        assertThat(created.path("data").path("modelPhysicalLocator").asText()).isEqualTo(tableName);
        return created;
    }

    private JsonNode createDataIngestionService(TestSession session,
                                                Long datasourceId,
                                                Long modelId,
                                                String tableName,
                                                String suffix) throws Exception {
        String serviceCode = "codex_ws_ing_" + suffix;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("serviceCode", serviceCode);
        payload.put("serviceName", "Codex WS Data Ingestion " + suffix);
        payload.put("requestFormat", "SOAP");
        payload.put("payloadMode", "ARRAY");
        payload.put("dataNodePath", "records.record");
        payload.put("targetType", "DATABASE");
        payload.put("datasourceId", datasourceId);
        payload.put("modelId", modelId);
        payload.put("maxBatchSize", Integer.valueOf(10));
        payload.put("tokenRequired", Boolean.TRUE);
        payload.put("webserviceEnabled", Boolean.TRUE);
        payload.put("webserviceConfig", webServiceConfig(serviceCode, "submitCustomerOrders"));
        payload.put("writerOptions", Collections.singletonMap("writeMode", "insert"));
        payload.put("fieldMappings", List.of(
                fieldMapping(1, "BODY", "id", "id", "LONG", true, null),
                fieldMapping(2, "BODY", "ingestName", "ingest_name", "STRING", true, null),
                fieldMapping(3, "BODY", "amount", "amount", "DECIMAL", true, null),
                fieldMapping(4, "HEADER", "sourceChannel", "source_channel", "STRING", false, "SOAP")
        ));
        JsonNode created = requireSuccess(postJson("/api/v1/data-ingestion-services", session.authorization, session.projectId, payload));
        assertThat(created.path("data").path("modelPhysicalLocator").asText()).isEqualTo(tableName);
        return created;
    }

    private JsonNode createDataServiceSubscription(TestSession session, long serviceId, String name) throws Exception {
        return requireSuccess(postJson("/api/v1/data-services/" + serviceId + "/subscriptions",
                session.authorization,
                session.projectId,
                Collections.singletonMap("subscriptionName", name)));
    }

    private JsonNode createDataIngestionSubscription(TestSession session, long serviceId, String name) throws Exception {
        return requireSuccess(postJson("/api/v1/data-ingestion-services/" + serviceId + "/subscriptions",
                session.authorization,
                session.projectId,
                Collections.singletonMap("subscriptionName", name)));
    }

    private Map<String, Object> webServiceConfig(String serviceCode, String operationName) {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("enabled", Boolean.TRUE);
        config.put("soapVersion", "SOAP_11");
        config.put("namespaceUri", "http://studio.jdragon.com/test/" + serviceCode);
        config.put("operationName", operationName);
        config.put("soapAction", "http://studio.jdragon.com/test/" + serviceCode + "/" + operationName);
        config.put("requestRootName", operationName);
        config.put("responseRootName", operationName + "Response");
        return config;
    }

    private Map<String, Object> requestParam(int sortOrder,
                                             String paramName,
                                             String fieldName,
                                             String valueType,
                                             String operator,
                                             boolean required) {
        Map<String, Object> param = new LinkedHashMap<String, Object>();
        param.put("sortOrder", Integer.valueOf(sortOrder));
        param.put("paramName", paramName);
        param.put("fieldName", fieldName);
        param.put("valueType", valueType);
        param.put("queryOperator", operator);
        param.put("required", Boolean.valueOf(required));
        return param;
    }

    private Map<String, Object> responseParam(int sortOrder, String paramName, String fieldName) {
        Map<String, Object> param = new LinkedHashMap<String, Object>();
        param.put("sortOrder", Integer.valueOf(sortOrder));
        param.put("enabled", Boolean.TRUE);
        param.put("paramName", paramName);
        param.put("fieldName", fieldName);
        param.put("exampleValue", "sample");
        return param;
    }

    private Map<String, Object> publishParam(int sortOrder,
                                             String frontendParamName,
                                             String backendParamName,
                                             String position,
                                             String valueType,
                                             String exampleValue,
                                             boolean required) {
        Map<String, Object> param = new LinkedHashMap<String, Object>();
        param.put("sortOrder", Integer.valueOf(sortOrder));
        param.put("frontendParamName", frontendParamName);
        param.put("backendParamName", backendParamName);
        param.put("position", position);
        param.put("valueType", valueType);
        param.put("exampleValue", exampleValue);
        param.put("required", Boolean.valueOf(required));
        return param;
    }

    private Map<String, Object> fieldMapping(int sortOrder,
                                             String sourcePosition,
                                             String sourceField,
                                             String targetField,
                                             String valueType,
                                             boolean required,
                                             String defaultValue) {
        Map<String, Object> mapping = new LinkedHashMap<String, Object>();
        mapping.put("sortOrder", Integer.valueOf(sortOrder));
        mapping.put("sourcePosition", sourcePosition);
        mapping.put("sourceField", sourceField);
        mapping.put("targetField", targetField);
        mapping.put("valueType", valueType);
        mapping.put("required", Boolean.valueOf(required));
        mapping.put("defaultValue", defaultValue);
        return mapping;
    }

    private List<Map<String, Object>> queryColumns() {
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        columns.add(column("id", "BIGINT"));
        columns.add(column("customer_name", "VARCHAR"));
        columns.add(column("amount", "DECIMAL"));
        columns.add(column("category", "VARCHAR"));
        return columns;
    }

    private List<Map<String, Object>> ingestionColumns() {
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        columns.add(column("id", "BIGINT"));
        columns.add(column("ingest_name", "VARCHAR"));
        columns.add(column("amount", "DECIMAL"));
        columns.add(column("source_channel", "VARCHAR"));
        return columns;
    }

    private Map<String, Object> column(String name, String type) {
        Map<String, Object> column = new LinkedHashMap<String, Object>();
        column.put("name", name);
        column.put("type", type);
        return column;
    }

    private String soapEnvelope(String namespaceUri,
                                String operationName,
                                Map<String, ?> headers,
                                Map<String, ?> body) {
        StringBuilder builder = new StringBuilder(1024);
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        builder.append("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:tns=\"")
                .append(escapeXml(namespaceUri))
                .append("\">");
        if (headers != null && !headers.isEmpty()) {
            builder.append("<soapenv:Header>");
            appendXmlMap(builder, headers);
            builder.append("</soapenv:Header>");
        }
        builder.append("<soapenv:Body><tns:")
                .append(operationName)
                .append(">");
        if (body != null) {
            appendXmlMap(builder, body);
        }
        builder.append("</tns:")
                .append(operationName)
                .append("></soapenv:Body></soapenv:Envelope>");
        return builder.toString();
    }

    private void appendXmlMap(StringBuilder builder, Map<String, ?> values) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            appendXmlValue(builder, entry.getKey(), entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private void appendXmlValue(StringBuilder builder, String key, Object value) {
        builder.append('<').append(key).append('>');
        if (value instanceof Map<?, ?>) {
            appendXmlMap(builder, (Map<String, ?>) value);
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                if (item instanceof Map<?, ?>) {
                    appendXmlMap(builder, (Map<String, ?>) item);
                } else {
                    builder.append(escapeXml(String.valueOf(item)));
                }
            }
        } else if (value != null) {
            builder.append(escapeXml(String.valueOf(value)));
        }
        builder.append("</").append(key).append('>');
    }

    private void createQueryTable(MySqlAggConfig mysql, String tableName) throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table if not exists `" + tableName + "` (" +
                    "id bigint primary key, " +
                    "customer_name varchar(128), " +
                    "amount decimal(18,2), " +
                    "category varchar(64))");
        }
        try (Connection connection = DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password);
             PreparedStatement statement = connection.prepareStatement("insert into `" + tableName + "` (id, customer_name, amount, category) values (?, ?, ?, ?)")) {
            statement.setLong(1, 1001L);
            statement.setString(2, "Alice SOAP");
            statement.setBigDecimal(3, new BigDecimal("12.30"));
            statement.setString(4, "A");
            statement.addBatch();
            statement.setLong(1, 1002L);
            statement.setString(2, "Bob SOAP");
            statement.setBigDecimal(3, new BigDecimal("98.70"));
            statement.setString(4, "B");
            statement.addBatch();
            statement.executeBatch();
        }
    }

    private void createIngestionTable(MySqlAggConfig mysql, String tableName) throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table if not exists `" + tableName + "` (" +
                    "id bigint primary key, " +
                    "ingest_name varchar(128), " +
                    "amount decimal(18,2), " +
                    "source_channel varchar(64))");
        }
    }

    private void assertInsertedRow(MySqlAggConfig mysql,
                                   String tableName,
                                   long id,
                                   String expectedName,
                                   BigDecimal expectedAmount,
                                   String expectedChannel) throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password);
             PreparedStatement statement = connection.prepareStatement("select ingest_name, amount, source_channel from `" + tableName + "` where id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("ingest_name")).isEqualTo(expectedName);
                assertThat(resultSet.getBigDecimal("amount")).isEqualByComparingTo(expectedAmount);
                assertThat(resultSet.getString("source_channel")).isEqualTo(expectedChannel);
            }
        }
    }

    private void assumeMysqlAggReachable(MySqlAggConfig mysql) {
        try (Connection connection = DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password)) {
            assertThat(connection.isValid(5)).isTrue();
        } catch (Exception ex) {
            Assumptions.assumeTrue(false, "mysql_agg is not reachable for real WebService integration tests: " + ex.getMessage());
        }
    }

    private MySqlAggConfig mysqlAggConfig() {
        String host = configValue("studio.real.mysql.host", "STUDIO_WS_REAL_MYSQL_HOST", "192.168.188.129");
        String port = configValue("studio.real.mysql.port", "STUDIO_WS_REAL_MYSQL_PORT", "3306");
        String database = configValue("studio.real.mysql.database", "STUDIO_WS_REAL_MYSQL_DATABASE", "data_aggregation_studio");
        String username = configValue("studio.real.mysql.username", "STUDIO_WS_REAL_MYSQL_USERNAME", "root");
        String password = configValue("studio.real.mysql.password", "STUDIO_WS_REAL_MYSQL_PASSWORD", "951753");
        String jdbcUrl = configValue("studio.real.mysql.jdbc-url", "STUDIO_WS_REAL_MYSQL_JDBC_URL",
                "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        return new MySqlAggConfig(host, Integer.parseInt(port), database, username, password, jdbcUrl);
    }

    private String configValue(String propertyName, String envName, String defaultValue) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }
        String env = System.getenv(envName);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return defaultValue;
    }

    private String runSuffix() {
        String time = RUN_ID_FORMAT.format(LocalDateTime.now());
        String nano = Long.toString(Math.abs(System.nanoTime() % 100000L));
        return (time + "_" + nano).toLowerCase(Locale.ENGLISH);
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static final class TestSession {
        private final String authorization;
        private final Long projectId;

        private TestSession(String authorization, Long projectId) {
            this.authorization = authorization;
            this.projectId = projectId;
        }
    }

    private static final class MySqlAggConfig {
        private final String host;
        private final int port;
        private final String database;
        private final String username;
        private final String password;
        private final String jdbcUrl;

        private MySqlAggConfig(String host,
                               int port,
                               String database,
                               String username,
                               String password,
                               String jdbcUrl) {
            this.host = host;
            this.port = port;
            this.database = database;
            this.username = username;
            this.password = password;
            this.jdbcUrl = jdbcUrl;
        }

        private Map<String, Object> toTechnicalMetadata() {
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("host", host);
            metadata.put("port", Integer.valueOf(port));
            metadata.put("database", database);
            metadata.put("userName", username);
            metadata.put("password", password);
            metadata.put("usePool", Boolean.FALSE);
            metadata.put("other", "{\"useUnicode\":\"true\",\"characterEncoding\":\"utf8\",\"serverTimezone\":\"Asia/Shanghai\",\"useSSL\":\"false\",\"allowPublicKeyRetrieval\":\"true\"}");
            return metadata;
        }
    }
}
