package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.test.support.StudioHttpIntegrationTestSupport;
import com.jdragon.studio.worker.bootstrap.StudioWorkerApplication;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RealWebServiceOpenApiIT extends StudioHttpIntegrationTestSupport {

    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String TEST_RUNTIME_CLUSTER_CODE = "HTTP-TEST";
    private static final Object WORKER_MONITOR = new Object();
    private static volatile ConfigurableApplicationContext workerContext;
    private static volatile String workerBaseUrl;
    private final List<TestTable> testTables = new ArrayList<TestTable>();

    @AfterAll
    static void shutdownJdbcCleanupThreads() {
        ConfigurableApplicationContext context = workerContext;
        workerContext = null;
        workerBaseUrl = null;
        if (context != null) {
            context.close();
        }
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

    @AfterEach
    void dropExternalTestTables() throws Exception {
        Exception cleanupFailure = null;
        for (TestTable table : testTables) {
            try (Connection connection = DriverManager.getConnection(
                    table.mysql.jdbcUrl, table.mysql.username, table.mysql.password);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("drop table if exists `" + table.tableName + "`");
            } catch (Exception ex) {
                if (cleanupFailure == null) {
                    cleanupFailure = ex;
                } else {
                    cleanupFailure.addSuppressed(ex);
                }
            }
        }
        testTables.clear();
        if (cleanupFailure != null) {
            throw cleanupFailure;
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

        HttpResponse<String> restResponse = postJson(
                "/openapi/data-services/" + serviceCode + "/" + serviceKey,
                null,
                null,
                Map.of("customerId", Integer.valueOf(1001), "pageSize", Integer.valueOf(5)),
                Collections.singletonMap("X-Data-Service-Token", token));
        assertThat(restResponse.statusCode()).isEqualTo(200);
        assertThat(restResponse.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(restResponse.body()).contains("Alice SOAP", "customer_name").doesNotContain("Bob SOAP");

        HttpResponse<String> missingTokenResponse = postXml("/openapi/ws/data-services/" + serviceCode + "/" + serviceKey,
                httpTokenRequest,
                Collections.emptyMap());
        assertThat(missingTokenResponse.statusCode()).isEqualTo(401);
        assertThat(missingTokenResponse.body()).contains("soap:Fault");
        assertAccessLogs(session, "data-service-metrics", published.path("data").path("id").asLong(), 4, 3);
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

        Map<String, Object> restBody = new LinkedHashMap<String, Object>();
        restBody.put("records", Map.of("record", List.of(
                Map.of("id", "2003", "ingestName", "Eve REST", "amount", "19.25"),
                Map.of("id", "2004", "ingestName", "Frank REST", "amount", "33.75")
        )));
        HttpResponse<String> restResponse = postJson(
                "/openapi/data-ingestion-services/" + serviceCode + "/" + serviceKey,
                null,
                null,
                restBody,
                Map.of("X-Data-Ingestion-Token", token, "sourceChannel", "REST_HEADER"));
        assertThat(restResponse.statusCode()).isEqualTo(200);
        assertThat(restResponse.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(restResponse.body()).contains("receivedCount", "2", "SUCCESS");
        assertInsertedRow(mysql, tableName, 2003L, "Eve REST", new BigDecimal("19.25"), "REST_HEADER");
        assertInsertedRow(mysql, tableName, 2004L, "Frank REST", new BigDecimal("33.75"), "REST_HEADER");

        HttpResponse<String> missingTokenResponse = postXml("/openapi/ws/data-ingestion-services/" + serviceCode + "/" + serviceKey,
                soapEnvelope("http://studio.jdragon.com/test/" + serviceCode,
                        "submitCustomerOrders",
                        null,
                        Map.of("records", List.of(Map.of("record", Map.of("id", "2999", "ingestName", "No Token", "amount", "1.00"))))),
                Collections.emptyMap());
        assertThat(missingTokenResponse.statusCode()).isEqualTo(401);
        assertThat(missingTokenResponse.body()).contains("soap:Fault");
        assertAccessLogs(session, "data-ingestion-metrics", published.path("data").path("id").asLong(), 3, 2);
    }

    @Test
    void failedDataServiceAndIngestionShouldWriteOneWorkerLogAndOneIngestionAlert() throws Exception {
        TestSession session = loginSession();
        String suffix = runSuffix();
        Long datasourceId = createUnavailableMysqlDatasource(session, suffix);
        String tableName = "codex_unreachable_" + suffix;
        Long modelId = createModel(session, datasourceId, tableName, ingestionColumns());

        JsonNode dataService = createDataService(session, datasourceId, modelId, tableName,
                suffix + "_failed_query");
        JsonNode publishedDataService = requireSuccess(postJson("/api/v1/data-services/"
                        + dataService.path("data").path("id").asText() + "/publish",
                session.authorization, session.projectId, Collections.emptyMap()));
        long dataServiceId = publishedDataService.path("data").path("id").asLong();
        String dataServiceToken = createDataServiceSubscription(session, dataServiceId,
                "codex-failed-query").path("data").path("token").asText();
        HttpResponse<String> dataServiceResponse = postJson(
                "/openapi/data-services/" + publishedDataService.path("data").path("serviceCode").asText()
                        + "/" + publishedDataService.path("data").path("serviceKey").asText(),
                null,
                null,
                Map.of("customerId", Integer.valueOf(1001), "pageSize", Integer.valueOf(5)),
                Collections.singletonMap("X-Data-Service-Token", dataServiceToken));
        assertThat(dataServiceResponse.statusCode()).isEqualTo(400);
        assertAccessLogs(session, "data-service-metrics", dataServiceId, 1, 0);
        assertFailureMetricsOnce(session, "data-service-metrics", dataServiceId);

        JsonNode ingestion = createDataIngestionService(session, datasourceId, modelId, tableName,
                suffix + "_failed_write");
        JsonNode publishedIngestion = requireSuccess(postJson("/api/v1/data-ingestion-services/"
                        + ingestion.path("data").path("id").asText() + "/publish",
                session.authorization, session.projectId, Collections.emptyMap()));
        long ingestionServiceId = publishedIngestion.path("data").path("id").asLong();
        String ingestionServiceName = publishedIngestion.path("data").path("serviceName").asText();
        String alertRuleName = "Codex ingestion transport failure " + suffix;
        createInvocationFailureAlertRule(session, ingestionServiceId, alertRuleName,
                "DATA_INGESTION_SERVICE");
        String ingestionToken = createDataIngestionSubscription(session, ingestionServiceId,
                "codex-failed-write").path("data").path("token").asText();
        HttpResponse<String> ingestionResponse = postJson(
                "/openapi/data-ingestion-services/"
                        + publishedIngestion.path("data").path("serviceCode").asText()
                        + "/" + publishedIngestion.path("data").path("serviceKey").asText(),
                null,
                null,
                Map.of("records", Map.of("record", List.of(
                        Map.of("id", "9001", "ingestName", "Failed Worker Write", "amount", "1.00")))),
                Collections.singletonMap("X-Data-Ingestion-Token", ingestionToken));
        assertThat(ingestionResponse.statusCode()).isEqualTo(200);
        assertThat(ingestionResponse.body()).contains("FAILED");
        JsonNode ingestionLogs = assertAccessLogs(session, "data-ingestion-metrics",
                ingestionServiceId, 1, 0);
        assertThat(ingestionLogs.path("items").get(0).path("errorMessage").asText()).isNotBlank();
        assertFailureMetricsOnce(session, "data-ingestion-metrics", ingestionServiceId);

        JsonNode incidents = queryInvocationFailureIncidents(session, alertRuleName,
                "DATA_INGESTION_SERVICE");
        assertThat(incidents.path("total").asInt()).isEqualTo(1);
        JsonNode incident = incidents.path("items").get(0);
        assertThat(incident.path("subjectId").asLong()).isEqualTo(ingestionServiceId);
        assertThat(incident.path("subjectName").asText()).isEqualTo(ingestionServiceName);
        assertThat(incident.path("occurrenceCount").asInt()).isEqualTo(1);
        assertThat(incident.path("requestedClusterId").asLong())
                .isEqualTo(session.runtimeClusterId.longValue());
        assertThat(incident.path("actualClusterId").asLong())
                .isEqualTo(session.runtimeClusterId.longValue());

        long incidentId = incident.path("id").asLong();
        JsonNode events = requireSuccess(get("/api/v1/alerts/incidents/" + incidentId
                        + "/events?pageNo=1&pageSize=20",
                session.authorization, session.projectId)).path("data");
        assertThat(events.path("total").asInt()).isEqualTo(1);
        assertThat(events.path("items").get(0).path("eventType").asText()).isEqualTo("TRIGGERED");

        Map<String, Object> deliveryQuery = new LinkedHashMap<String, Object>();
        deliveryQuery.put("incidentId", Long.valueOf(incidentId));
        deliveryQuery.put("pageNo", Integer.valueOf(1));
        deliveryQuery.put("pageSize", Integer.valueOf(20));
        JsonNode deliveries = requireSuccess(postJson("/api/v1/alerts/deliveries/query",
                session.authorization, session.projectId, deliveryQuery)).path("data");
        assertThat(deliveries.path("total").asInt()).isEqualTo(1);
        assertThat(deliveries.path("items").get(0).path("channelType").asText()).isEqualTo("IN_APP");
    }

    @Test
    void protocolConversionSoapEndpointShouldRouteThroughWorkerAndCallHttpTarget() throws Exception {
        TestSession session = loginSession();
        String suffix = runSuffix();
        Long datasourceId = createHttpDatasource(session, workerBaseUrl);
        JsonNode service = createProtocolConversionService(session, datasourceId, suffix);
        JsonNode published = requireSuccess(postJson("/api/v1/protocol-conversions/"
                        + service.path("data").path("id").asText() + "/publish",
                session.authorization, session.projectId, Collections.emptyMap()));
        JsonNode subscription = requireSuccess(postJson("/api/v1/protocol-conversions/"
                        + published.path("data").path("id").asText() + "/subscriptions",
                session.authorization,
                session.projectId,
                Collections.singletonMap("subscriptionName", "codex-ws-protocol")));

        String serviceCode = published.path("data").path("serviceCode").asText();
        String serviceKey = published.path("data").path("serviceKey").asText();
        String token = subscription.path("data").path("token").asText();
        String operationName = "submitRuntimeProbe";
        String namespaceUri = "http://studio.jdragon.com/test/" + serviceCode;

        HttpResponse<String> wsdl = get("/openapi/ws/protocol-conversions/" + serviceCode + "/" + serviceKey + "?wsdl",
                null, null);
        assertThat(wsdl.statusCode()).isEqualTo(200);
        assertThat(wsdl.body()).contains("wsdl:definitions", operationName, "xsd:any");

        String request = soapEnvelope(namespaceUri,
                operationName,
                Collections.singletonMap("protocolConversionToken", token),
                Map.of("traceId", "worker-route-" + suffix, "customerName", "Worker HTTP target"));
        HttpResponse<String> response = postXml("/openapi/ws/protocol-conversions/" + serviceCode + "/" + serviceKey,
                request,
                Collections.emptyMap());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(operationName + "Response", "UP");

        HttpResponse<String> missingTokenResponse = postXml(
                "/openapi/ws/protocol-conversions/" + serviceCode + "/" + serviceKey,
                soapEnvelope(namespaceUri, operationName, null,
                        Collections.singletonMap("traceId", "missing-token-" + suffix)),
                Collections.emptyMap());
        assertThat(missingTokenResponse.statusCode()).isEqualTo(401);
        assertThat(missingTokenResponse.body()).contains("soap:Fault");
        assertAccessLogs(session, "protocol-conversion-metrics",
                published.path("data").path("id").asLong(), 2, 1);
    }

    @Test
    void protocolConversionXmlRestShouldPreserveSuccessAndDownstreamFailureThroughWorker() throws Exception {
        AtomicInteger successInvocations = new AtomicInteger();
        AtomicInteger failureInvocations = new AtomicInteger();
        AtomicInteger abortedInvocations = new AtomicInteger();
        AtomicInteger oversizedInvocations = new AtomicInteger();
        AtomicReference<String> successQuery = new AtomicReference<String>();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/runtime-success", exchange -> {
            successInvocations.incrementAndGet();
            successQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] response = "{\"status\":\"UP\",\"message\":\"xml-rest-ok\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        target.createContext("/runtime-failure", exchange -> {
            failureInvocations.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"code\":\"DOWNSTREAM_REJECTED\",\"message\":\"xml-rest-failure-marker\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(422, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        target.createContext("/runtime-abort", exchange -> {
            abortedInvocations.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        });
        target.createContext("/runtime-oversized", exchange -> {
            oversizedInvocations.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"status\":\"UP\",\"message\":\"" + "x".repeat(8192) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        target.start();
        try {
            TestSession session = loginSession();
            String suffix = runSuffix();
            String targetBaseUrl = "http://127.0.0.1:" + target.getAddress().getPort();
            Long datasourceId = createHttpDatasource(session, targetBaseUrl);

            JsonNode successService = createXmlProtocolConversionService(session, datasourceId,
                    suffix + "_success", "/runtime-success");
            JsonNode publishedSuccess = publishProtocolConversion(session, successService);
            String successToken = createProtocolConversionSubscription(session,
                    publishedSuccess.path("data").path("id").asLong(), "codex-xml-rest-success");
            HttpResponse<String> successResponse = postXml(protocolConversionOpenPath(publishedSuccess),
                    "<request><traceId>xml-rest-trace</traceId><customerName>XML REST</customerName></request>",
                    Collections.singletonMap("X-Protocol-Conversion-Token", successToken));
            assertThat(successResponse.statusCode())
                    .withFailMessage("Unexpected XML protocol success response: %s", successResponse.body())
                    .isEqualTo(200);
            assertThat(successResponse.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("application/xml");
            assertThat(successResponse.body()).contains("<status>UP</status>", "xml-rest-ok");
            assertThat(successInvocations.get()).isEqualTo(1);
            assertThat(successQuery.get()).contains("trace=xml-rest-trace");
            JsonNode successLogs = assertAccessLogs(session, "protocol-conversion-metrics",
                    publishedSuccess.path("data").path("id").asLong(), 1, 1);
            assertThat(successLogs.path("items").get(0).path("httpStatus").asInt()).isEqualTo(200);
            assertThat(successLogs.path("items").get(0).path("targetHttpStatus").asInt()).isEqualTo(200);

            JsonNode failureService = createXmlProtocolConversionService(session, datasourceId,
                    suffix + "_failure", "/runtime-failure");
            JsonNode publishedFailure = publishProtocolConversion(session, failureService);
            String failureToken = createProtocolConversionSubscription(session,
                    publishedFailure.path("data").path("id").asLong(), "codex-xml-rest-failure");
            HttpResponse<String> failureResponse = postXml(protocolConversionOpenPath(publishedFailure),
                    "<request><traceId>xml-rest-failure</traceId></request>",
                    Collections.singletonMap("X-Protocol-Conversion-Token", failureToken));
            assertThat(failureResponse.statusCode())
                    .withFailMessage("Unexpected XML protocol failure response: %s", failureResponse.body())
                    .isEqualTo(500);
            assertThat(failureResponse.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("application/json");
            assertThat(failureResponse.body())
                    .contains("Target HTTP request failed: 422", "xml-rest-failure-marker");
            assertThat(failureInvocations.get()).isEqualTo(1);
            JsonNode failureLogs = assertAccessLogs(session, "protocol-conversion-metrics",
                    publishedFailure.path("data").path("id").asLong(), 1, 0);
            JsonNode failureLog = failureLogs.path("items").get(0);
            assertThat(failureLog.path("httpStatus").asInt()).isEqualTo(500);
            assertThat(failureLog.path("targetHttpStatus").asInt()).isEqualTo(422);
            assertThat(failureLog.path("errorMessage").asText())
                    .contains("Target HTTP request failed: 422", "xml-rest-failure-marker");

            JsonNode abortedService = createXmlProtocolConversionService(session, datasourceId,
                    suffix + "_abort", "/runtime-abort");
            JsonNode publishedAborted = publishProtocolConversion(session, abortedService);
            long abortedServiceId = publishedAborted.path("data").path("id").asLong();
            String abortedServiceName = publishedAborted.path("data").path("serviceName").asText();
            String alertRuleName = "Codex protocol transport failure " + suffix;
            createInvocationFailureAlertRule(session, abortedServiceId, alertRuleName);
            String abortedToken = createProtocolConversionSubscription(session,
                    abortedServiceId, "codex-xml-rest-abort");
            HttpResponse<String> abortedResponse = postXml(protocolConversionOpenPath(publishedAborted),
                    "<request><traceId>xml-rest-abort</traceId></request>",
                    Collections.singletonMap("X-Protocol-Conversion-Token", abortedToken));
            assertThat(abortedResponse.statusCode())
                    .withFailMessage("Unexpected XML protocol transport response: %s", abortedResponse.body())
                    .isEqualTo(500);
            assertThat(abortedResponse.body()).contains("Target HTTP request failed");
            assertThat(abortedInvocations.get()).isEqualTo(1);

            JsonNode abortedLogs = assertAccessLogs(session, "protocol-conversion-metrics",
                    abortedServiceId, 1, 0);
            JsonNode abortedLog = abortedLogs.path("items").get(0);
            assertThat(abortedLog.path("httpStatus").asInt()).isEqualTo(500);
            assertThat(abortedLog.path("targetHttpStatus").isNull()
                    || abortedLog.path("targetHttpStatus").isMissingNode()).isTrue();
            assertThat(abortedLog.path("errorMessage").asText())
                    .contains("Target HTTP request failed")
                    .doesNotContain(targetBaseUrl);

            JsonNode incidents = queryInvocationFailureIncidents(session, alertRuleName);
            assertThat(incidents.path("total").asInt()).isEqualTo(1);
            JsonNode incident = incidents.path("items").get(0);
            assertThat(incident.path("ruleType").asText()).isEqualTo("INVOCATION_WRITE_FAILED");
            assertThat(incident.path("subjectType").asText()).isEqualTo("PROTOCOL_CONVERSION_SERVICE");
            assertThat(incident.path("subjectId").asLong()).isEqualTo(abortedServiceId);
            assertThat(incident.path("subjectName").asText()).isEqualTo(abortedServiceName);
            assertThat(incident.path("requestedClusterId").asLong()).isEqualTo(session.runtimeClusterId.longValue());
            assertThat(incident.path("actualClusterId").asLong()).isEqualTo(session.runtimeClusterId.longValue());
            assertThat(incident.path("occurrenceCount").asInt()).isEqualTo(1);
            assertThat(incident.path("evidence").path("targetClusterId").asLong())
                    .isEqualTo(session.runtimeClusterId.longValue());
            assertThat(incident.path("evidence").path("actualClusterId").asLong())
                    .isEqualTo(session.runtimeClusterId.longValue());

            long incidentId = incident.path("id").asLong();
            JsonNode events = requireSuccess(get("/api/v1/alerts/incidents/" + incidentId
                            + "/events?pageNo=1&pageSize=20",
                    session.authorization, session.projectId)).path("data");
            assertThat(events.path("total").asInt()).isEqualTo(1);
            assertThat(events.path("items").get(0).path("eventType").asText()).isEqualTo("TRIGGERED");

            Map<String, Object> deliveryQuery = new LinkedHashMap<String, Object>();
            deliveryQuery.put("incidentId", Long.valueOf(incidentId));
            deliveryQuery.put("pageNo", Integer.valueOf(1));
            deliveryQuery.put("pageSize", Integer.valueOf(20));
            JsonNode deliveries = requireSuccess(postJson("/api/v1/alerts/deliveries/query",
                    session.authorization, session.projectId, deliveryQuery)).path("data");
            assertThat(deliveries.path("total").asInt()).isEqualTo(1);
            JsonNode delivery = deliveries.path("items").get(0);
            assertThat(delivery.path("channelType").asText()).isEqualTo("IN_APP");
            assertThat(delivery.path("eventType").asText()).isEqualTo("TRIGGERED");
            assertThat(delivery.path("ruleName").asText()).isEqualTo(alertRuleName);
            assertThat(delivery.path("subjectId").asLong()).isEqualTo(abortedServiceId);
            assertThat(delivery.path("messageContent").asText())
                    .contains(alertRuleName, abortedServiceName);

            JsonNode oversizedService = createXmlProtocolConversionService(session, datasourceId,
                    suffix + "_oversized", "/runtime-oversized");
            JsonNode publishedOversized = publishProtocolConversion(session, oversizedService);
            long oversizedServiceId = publishedOversized.path("data").path("id").asLong();
            String oversizedToken = createProtocolConversionSubscription(session,
                    oversizedServiceId, "codex-xml-rest-oversized");
            HttpResponse<String> oversizedResponse = postXml(protocolConversionOpenPath(publishedOversized),
                    "<request><traceId>xml-rest-oversized</traceId></request>",
                    Collections.singletonMap("X-Protocol-Conversion-Token", oversizedToken));
            assertThat(oversizedResponse.statusCode()).isEqualTo(500);
            assertThat(oversizedResponse.body()).contains("Target response exceeds the configured limit");
            assertThat(oversizedInvocations.get()).isEqualTo(1);
            JsonNode oversizedLogs = assertAccessLogs(session, "protocol-conversion-metrics",
                    oversizedServiceId, 1, 0);
            assertThat(oversizedLogs.path("items").get(0).path("errorMessage").asText())
                    .contains("Target response exceeds the configured limit");
        } finally {
            target.stop(0);
        }
    }

    private TestSession loginSession() throws Exception {
        JsonNode login = loginAsAdminHttp();
        String authorization = bearer(login);
        Long projectId = currentProjectId(login);
        String tenantId = login.path("data").path("currentTenantId").asText();
        assertThat(tenantId).isNotBlank();
        String runtimeBaseUrl = ensureTestWorkerStarted();

        Map<String, Object> clusterPayload = new LinkedHashMap<String, Object>();
        clusterPayload.put("code", TEST_RUNTIME_CLUSTER_CODE);
        clusterPayload.put("name", "HTTP integration Worker");
        clusterPayload.put("enabled", Boolean.TRUE);
        JsonNode cluster = requireSuccess(postJson("/api/v1/runtime-clusters",
                authorization, projectId, clusterPayload));
        Long runtimeClusterId = cluster.path("data").path("id").asLong();

        Map<String, Object> authorizationPayload = new LinkedHashMap<String, Object>();
        authorizationPayload.put("projectId", projectId);
        authorizationPayload.put("runtimeClusterId", runtimeClusterId);
        authorizationPayload.put("enabled", Boolean.TRUE);
        authorizationPayload.put("preferred", Boolean.TRUE);
        authorizationPayload.put("allowManualOverride", Boolean.FALSE);
        requireSuccess(postJson("/api/v1/runtime-clusters/project-authorizations",
                authorization, projectId, authorizationPayload));

        Map<String, Object> endpointPayload = new LinkedHashMap<String, Object>();
        endpointPayload.put("runtimeClusterId", runtimeClusterId);
        endpointPayload.put("mode", "HTTP");
        endpointPayload.put("endpointUrl", runtimeBaseUrl);
        endpointPayload.put("headers", Collections.emptyMap());
        endpointPayload.put("connectTimeoutMillis", Integer.valueOf(3000));
        endpointPayload.put("readTimeoutMillis", Integer.valueOf(30000));
        endpointPayload.put("enabled", Boolean.TRUE);
        JsonNode endpoint = requireSuccess(postJson("/api/v1/runtime-clusters/endpoints",
                authorization, projectId, endpointPayload));
        JsonNode endpointTest = requireSuccess(postJson("/api/v1/runtime-clusters/endpoints/"
                        + endpoint.path("data").path("id").asText() + "/test",
                authorization, projectId, Collections.emptyMap()));
        assertThat(endpointTest.path("data").path("lastTestStatus").asText()).isEqualTo("SUCCESS");

        Map<String, Object> heartbeatPayload = new LinkedHashMap<String, Object>();
        heartbeatPayload.put("tenantId", tenantId);
        heartbeatPayload.put("clusterCode", TEST_RUNTIME_CLUSTER_CODE);
        heartbeatPayload.put("instanceId", "real-webservice-openapi-it");
        heartbeatPayload.put("version", "test");
        heartbeatPayload.put("summary", runtimeBaseUrl);
        requireSuccess(postJson("/api/v1/runtime-clusters/internal/heartbeat", null, null,
                heartbeatPayload,
                Collections.singletonMap(StudioConstants.INTERNAL_API_TOKEN_HEADER, TEST_INTERNAL_API_TOKEN)));

        JsonNode options = requireSuccess(get("/api/v1/runtime-clusters/options?projectId=" + projectId,
                authorization, projectId));
        assertThat(options.path("data").isArray()).isTrue();
        assertThat(options.path("data").findValuesAsText("id"))
                .contains(String.valueOf(runtimeClusterId));
        return new TestSession(authorization, projectId, runtimeClusterId);
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
        payload.put("applicableClusterIds", Collections.singletonList(session.runtimeClusterId));
        payload.put("technicalMetadata", mysql.toTechnicalMetadata());
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());
        JsonNode created = requireSuccess(postJson("/api/v1/datasources", session.authorization, session.projectId, payload));
        return created.path("data").path("id").asLong();
    }

    private Long createHttpDatasource(TestSession session, String baseUrl) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", "codex_http_target_" + runSuffix());
        payload.put("typeCode", "http");
        payload.put("enabled", Boolean.TRUE);
        payload.put("executable", Boolean.TRUE);
        payload.put("applicableClusterIds", Collections.singletonList(session.runtimeClusterId));
        payload.put("technicalMetadata", Collections.singletonMap("url", baseUrl));
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());
        JsonNode created = requireSuccess(postJson("/api/v1/datasources",
                session.authorization, session.projectId, payload));
        return created.path("data").path("id").asLong();
    }

    private Long createUnavailableMysqlDatasource(TestSession session, String suffix) throws Exception {
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("host", "127.0.0.1");
        technicalMetadata.put("port", Integer.valueOf(1));
        technicalMetadata.put("database", "unreachable");
        technicalMetadata.put("userName", "unused");
        technicalMetadata.put("password", "unused");
        technicalMetadata.put("usePool", Boolean.FALSE);
        technicalMetadata.put("other", "{\"connectTimeout\":\"1000\",\"socketTimeout\":\"1000\"}");

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", "codex_unreachable_mysql_" + suffix);
        payload.put("typeCode", "mysql8");
        payload.put("enabled", Boolean.TRUE);
        payload.put("executable", Boolean.TRUE);
        payload.put("applicableClusterIds", Collections.singletonList(session.runtimeClusterId));
        payload.put("technicalMetadata", technicalMetadata);
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());
        JsonNode created = requireSuccess(postJson("/api/v1/datasources",
                session.authorization, session.projectId, payload));
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
        payload.put("runtimeClusterId", session.runtimeClusterId);
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
        assertThat(created.path("data").path("responseType").asText()).isEqualTo("XML");
        return created;
    }

    private JsonNode createDataIngestionService(TestSession session,
                                                Long datasourceId,
                                                Long modelId,
                                                String tableName,
                                                String suffix) throws Exception {
        String serviceCode = "codex_ws_ing_" + suffix;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("runtimeClusterId", session.runtimeClusterId);
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
        assertThat(created.path("data").path("requestFormat").asText()).isEqualTo("SOAP");
        return created;
    }

    private JsonNode createProtocolConversionService(TestSession session,
                                                      Long datasourceId,
                                                      String suffix) throws Exception {
        String serviceCode = "codex_ws_protocol_" + suffix;
        String operationName = "submitRuntimeProbe";
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("runtimeClusterId", session.runtimeClusterId);
        payload.put("serviceCode", serviceCode);
        payload.put("serviceName", "Codex WS Protocol Conversion " + suffix);
        payload.put("tokenRequired", Boolean.TRUE);
        payload.put("sourceProtocol", "SOAP_11");
        payload.put("sourceMethod", "POST");
        payload.put("webserviceConfig", webServiceConfig(serviceCode, operationName));
        payload.put("conversionMode", "FIELD_MAPPING");
        payload.put("fieldMappings", List.of(
                fieldMapping(1, "BODY", "traceId", "traceId", "STRING", true, null),
                fieldMapping(2, "BODY", "customerName", "customerName", "STRING", false, "Worker HTTP target")
        ));
        payload.put("rawTransformers", Collections.emptyList());
        payload.put("fixedFields", Collections.emptyList());
        payload.put("bodyBridgeOptions", Collections.emptyMap());
        payload.put("requestPassthrough", Map.of("body", Boolean.FALSE, "query", Boolean.FALSE, "headers", Boolean.FALSE));
        payload.put("targetDatasourceId", datasourceId);
        payload.put("targetPath", "/actuator/health");
        payload.put("targetProtocol", "HTTP_JSON");
        payload.put("targetMethod", "GET");
        payload.put("targetHeaders", Collections.singletonMap("X-Studio-Test", "worker-only"));
        payload.put("targetQuery", Collections.singletonMap("trace", "{{traceId}}"));
        payload.put("targetWebserviceConfig", webServiceConfig(serviceCode + "_target", "targetHealth"));
        payload.put("payloadMode", "OBJECT");
        payload.put("batchSize", Integer.valueOf(1));
        payload.put("responseStatus", Map.of("path", "status", "code", "UP"));
        return requireSuccess(postJson("/api/v1/protocol-conversions",
                session.authorization, session.projectId, payload));
    }

    private JsonNode createXmlProtocolConversionService(TestSession session,
                                                         Long datasourceId,
                                                         String suffix,
                                                         String targetPath) throws Exception {
        String serviceCode = "codex_xml_protocol_" + suffix;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("runtimeClusterId", session.runtimeClusterId);
        payload.put("serviceCode", serviceCode);
        payload.put("serviceName", "Codex XML Protocol Conversion " + suffix);
        payload.put("tokenRequired", Boolean.TRUE);
        payload.put("sourceProtocol", "HTTP_XML");
        payload.put("sourceMethod", "POST");
        payload.put("conversionMode", "FIELD_MAPPING");
        payload.put("fieldMappings", List.of(
                fieldMapping(1, "BODY", "request.traceId", "traceId", "STRING", true, null),
                fieldMapping(2, "BODY", "request.customerName", "customerName", "STRING", false, "XML REST")
        ));
        payload.put("rawTransformers", Collections.emptyList());
        payload.put("fixedFields", Collections.emptyList());
        payload.put("bodyBridgeOptions", Collections.emptyMap());
        payload.put("requestPassthrough", Map.of("body", Boolean.FALSE, "query", Boolean.FALSE, "headers", Boolean.FALSE));
        payload.put("targetDatasourceId", datasourceId);
        payload.put("targetPath", targetPath);
        payload.put("targetProtocol", "HTTP_JSON");
        payload.put("targetMethod", "GET");
        payload.put("targetHeaders", Collections.singletonMap("X-Studio-Test", "xml-rest"));
        payload.put("targetQuery", Collections.singletonMap("trace", "{{traceId}}"));
        payload.put("payloadMode", "OBJECT");
        payload.put("batchSize", Integer.valueOf(1));
        payload.put("responseStatus", Map.of("path", "status", "code", "UP"));
        return requireSuccess(postJson("/api/v1/protocol-conversions",
                session.authorization, session.projectId, payload));
    }

    private JsonNode publishProtocolConversion(TestSession session, JsonNode service) throws Exception {
        return requireSuccess(postJson("/api/v1/protocol-conversions/"
                        + service.path("data").path("id").asText() + "/publish",
                session.authorization, session.projectId, Collections.emptyMap()));
    }

    private String createProtocolConversionSubscription(TestSession session,
                                                        long serviceId,
                                                        String subscriptionName) throws Exception {
        JsonNode subscription = requireSuccess(postJson("/api/v1/protocol-conversions/" + serviceId
                        + "/subscriptions",
                session.authorization,
                session.projectId,
                Collections.singletonMap("subscriptionName", subscriptionName)));
        return subscription.path("data").path("token").asText();
    }

    private String protocolConversionOpenPath(JsonNode published) {
        return "/openapi/protocol-conversions/" + published.path("data").path("serviceCode").asText()
                + "/" + published.path("data").path("serviceKey").asText();
    }

    private void createInvocationFailureAlertRule(TestSession session,
                                                  long serviceId,
                                                  String ruleName) throws Exception {
        createInvocationFailureAlertRule(session, serviceId, ruleName,
                "PROTOCOL_CONVERSION_SERVICE");
    }

    private void createInvocationFailureAlertRule(TestSession session,
                                                  long serviceId,
                                                  String ruleName,
                                                  String subjectType) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", ruleName);
        payload.put("ruleType", "INVOCATION_WRITE_FAILED");
        payload.put("subjectType", subjectType);
        payload.put("subjectId", Long.valueOf(serviceId));
        payload.put("severity", "CRITICAL");
        payload.put("enabled", Boolean.TRUE);
        payload.put("condition", Collections.emptyMap());
        payload.put("inAppEnabled", Boolean.TRUE);
        payload.put("notifyProjectAdmins", Boolean.TRUE);
        requireSuccess(postJson("/api/v1/alerts/rules", session.authorization, session.projectId, payload));
    }

    private JsonNode queryInvocationFailureIncidents(TestSession session, String ruleName) throws Exception {
        return queryInvocationFailureIncidents(session, ruleName,
                "PROTOCOL_CONVERSION_SERVICE");
    }

    private JsonNode queryInvocationFailureIncidents(TestSession session,
                                                      String ruleName,
                                                      String subjectType) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("keyword", ruleName);
        payload.put("ruleType", "INVOCATION_WRITE_FAILED");
        payload.put("subjectType", subjectType);
        payload.put("requestedClusterId", session.runtimeClusterId);
        payload.put("actualClusterId", session.runtimeClusterId);
        payload.put("pageNo", Integer.valueOf(1));
        payload.put("pageSize", Integer.valueOf(20));
        return requireSuccess(postJson("/api/v1/alerts/incidents/query",
                session.authorization, session.projectId, payload)).path("data");
    }

    private void assertFailureMetricsOnce(TestSession session,
                                          String metricsPath,
                                          long serviceId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("serviceId", Long.valueOf(serviceId));
        payload.put("requestedClusterId", session.runtimeClusterId);
        payload.put("actualClusterId", session.runtimeClusterId);
        JsonNode dashboard = requireSuccess(postJson("/api/v1/" + metricsPath + "/dashboard/query",
                session.authorization, session.projectId, payload)).path("data");
        assertThat(dashboard.path("summary").path("accessCount").asLong()).isEqualTo(1L);
        assertThat(dashboard.path("summary").path("successCount").asLong()).isZero();
        assertThat(dashboard.path("summary").path("failureCount").asLong()).isEqualTo(1L);
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
        testTables.add(new TestTable(mysql, tableName));
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
        testTables.add(new TestTable(mysql, tableName));
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

    private JsonNode assertAccessLogs(TestSession session,
                                      String metricsPath,
                                      long serviceId,
                                      int expectedTotal,
                                      int expectedSuccessCount) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("serviceId", Long.valueOf(serviceId));
        payload.put("requestedClusterId", session.runtimeClusterId);
        payload.put("actualClusterId", session.runtimeClusterId);
        payload.put("pageNo", Integer.valueOf(1));
        payload.put("pageSize", Integer.valueOf(20));
        JsonNode response = requireSuccess(postJson("/api/v1/" + metricsPath + "/access-logs/query",
                session.authorization, session.projectId, payload));
        JsonNode page = response.path("data");
        assertThat(page.path("total").asInt()).isEqualTo(expectedTotal);
        assertThat(page.path("items").size()).isEqualTo(expectedTotal);
        int successCount = 0;
        for (JsonNode item : page.path("items")) {
            assertThat(item.path("requestedClusterId").asLong()).isEqualTo(session.runtimeClusterId.longValue());
            assertThat(item.path("actualClusterId").asLong()).isEqualTo(session.runtimeClusterId.longValue());
            if (item.path("success").asBoolean()) {
                successCount++;
            }
        }
        assertThat(successCount).isEqualTo(expectedSuccessCount);
        return page;
    }

    private String ensureTestWorkerStarted() {
        if (workerBaseUrl != null) {
            return workerBaseUrl;
        }
        synchronized (WORKER_MONITOR) {
            if (workerBaseUrl != null) {
                return workerBaseUrl;
            }
            Map<String, Object> properties = new LinkedHashMap<String, Object>();
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", "0");
            properties.put("spring.application.name", "studio-worker");
            properties.put("spring.profiles.active", "test");
            properties.put("spring.config.import", "");
            properties.put("spring.main.banner-mode", "off");
            properties.put("spring.datasource.url", "jdbc:sqlite:"
                    + SQLITE_DB.toAbsolutePath().normalize().toString().replace('\\', '/'));
            properties.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
            properties.put("spring.datasource.username", "");
            properties.put("spring.datasource.password", "");
            properties.put("spring.datasource.hikari.connection-init-sql", "PRAGMA busy_timeout=30000");
            properties.put("spring.datasource.hikari.maximum-pool-size", "1");
            properties.put("spring.sql.init.mode", "never");
            properties.put("spring.quartz.auto-startup", "false");
            properties.put("spring.cloud.nacos.config.enabled", "false");
            properties.put("spring.cloud.nacos.discovery.enabled", "false");
            properties.put("spring.autoconfigure.exclude",
                    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                            + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
            properties.put("studio.schema.auto-upgrade-on-startup", "false");
            properties.put("studio.aggregation-home", AGGREGATION_HOME.toAbsolutePath().normalize().toString());
            properties.put("studio.internal-api-token", TEST_INTERNAL_API_TOKEN);
            properties.put("studio.encryption-secret", TEST_ENCRYPTION_SECRET);
            properties.put("studio.runtime-cluster-code", TEST_RUNTIME_CLUSTER_CODE);
            properties.put("studio.instance-id", "real-webservice-openapi-it");
            properties.put("studio.worker-code", "real-webservice-openapi-it");
            properties.put("studio.worker-group-code", "real-webservice-openapi-it");
            properties.put("studio.worker.lifecycle.enabled", "false");
            properties.put("studio.runtime-invocation-idempotency.cleanup-enabled", "false");
            properties.put("studio.runtime-endpoint.max-response-bytes", "4096");
            properties.put("studio.datasource-health.enabled", "false");
            properties.put("studio.scan-plugins-on-startup", "false");
            properties.put("studio.alert.enabled", "true");
            properties.put("studio.runtime-log-dir", TEST_RUNTIME_DIR.resolve("worker-run-logs").toString());
            List<String> arguments = new ArrayList<String>();
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                arguments.add("--" + entry.getKey() + "=" + String.valueOf(entry.getValue()));
            }
            ConfigurableApplicationContext context = new SpringApplicationBuilder(StudioWorkerApplication.class)
                    .run(arguments.toArray(new String[0]));
            if (!(context instanceof WebServerApplicationContext)) {
                context.close();
                throw new IllegalStateException("Studio Worker test context did not start a web server");
            }
            int workerPort = ((WebServerApplicationContext) context).getWebServer().getPort();
            workerContext = context;
            workerBaseUrl = "http://127.0.0.1:" + workerPort;
            return workerBaseUrl;
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
        String host = configValue("studio.real.mysql.host", "STUDIO_WS_REAL_MYSQL_HOST", null);
        String port = configValue("studio.real.mysql.port", "STUDIO_WS_REAL_MYSQL_PORT", null);
        String database = configValue("studio.real.mysql.database", "STUDIO_WS_REAL_MYSQL_DATABASE", null);
        String username = configValue("studio.real.mysql.username", "STUDIO_WS_REAL_MYSQL_USERNAME", null);
        String password = configValue("studio.real.mysql.password", "STUDIO_WS_REAL_MYSQL_PASSWORD", null);
        Assumptions.assumeTrue(hasText(host) && hasText(port) && hasText(database)
                        && hasText(username) && hasText(password),
                "Real WebService integration database settings are not configured");
        String jdbcUrl = configValue("studio.real.mysql.jdbc-url", "STUDIO_WS_REAL_MYSQL_JDBC_URL",
                "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        final int parsedPort;
        try {
            parsedPort = Integer.parseInt(port);
        } catch (NumberFormatException ex) {
            Assumptions.assumeTrue(false, "Real WebService integration database port is invalid");
            return null;
        }
        return new MySqlAggConfig(host, parsedPort, database, username, password, jdbcUrl);
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
        private final Long runtimeClusterId;

        private TestSession(String authorization, Long projectId, Long runtimeClusterId) {
            this.authorization = authorization;
            this.projectId = projectId;
            this.runtimeClusterId = runtimeClusterId;
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

    private static final class TestTable {
        private final MySqlAggConfig mysql;
        private final String tableName;

        private TestTable(MySqlAggConfig mysql, String tableName) {
            this.mysql = mysql;
            this.tableName = tableName;
        }
    }
}
