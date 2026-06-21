package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AggregationSourceCapabilityProviderRegressionTest {

    @Test
    void httpDatasourceConnectionTestShouldSucceedOnlyWhenStatusIs200() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            AggregationSourceCapabilityProvider provider = httpProvider();

            ConnectionTestResult success = provider.testConnection(httpDatasource(baseUrl + "/ok"));
            ConnectionTestResult failure = provider.testConnection(httpDatasource(baseUrl + "/missing"));
            ConnectionTestResult serverError = provider.testConnection(httpDatasource(baseUrl + "/error"));

            assertThat(success.isSuccess()).isTrue();
            assertThat(success.getMessage()).isEqualTo("HTTP status 200");
            assertThat(failure.isSuccess()).isFalse();
            assertThat(failure.getMessage()).isEqualTo("HTTP status 404");
            assertThat(serverError.isSuccess()).isFalse();
            assertThat(serverError.getMessage()).isEqualTo("HTTP status 500");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void modelHydrationFailureMessageShouldExposeDeepestCause() throws Exception {
        AggregationSourceCapabilityProvider provider = httpProvider();
        Method method = AggregationSourceCapabilityProvider.class
                .getDeclaredMethod("mostSpecificErrorMessage", Throwable.class, Throwable.class);
        method.setAccessible(true);
        RuntimeException accessDenied = new RuntimeException("Access denied for user 'root'@'127.0.0.1' (using password: YES)");
        IllegalStateException wrapped = new IllegalStateException(
                "Failed to load model metadata for lt_reg_customer_profile",
                new RuntimeException("Failed to initialize pool", accessDenied));
        InvocationTargetException reflected = new InvocationTargetException(wrapped);

        Object message = method.invoke(provider, reflected, new RuntimeException("batch failure"));

        assertThat(message).isEqualTo("Access denied for user 'root'@'127.0.0.1' (using password: YES)");
    }

    @Test
    void connectionFailureMessageShouldNotExposeJavaExceptionPrefix() throws Exception {
        AggregationSourceCapabilityProvider provider = httpProvider();
        Method method = AggregationSourceCapabilityProvider.class
                .getDeclaredMethod("userFriendlyErrorMessage", Throwable.class);
        method.setAccessible(true);
        RuntimeException wrapped = new RuntimeException(
                "java.lang.RuntimeException: Cannot create PoolableConnectionFactory",
                new RuntimeException("java.lang.RuntimeException: Communications link failure"));

        Object message = method.invoke(provider, wrapped);

        assertThat(message).isEqualTo("Communications link failure");
    }

    private AggregationSourceCapabilityProvider httpProvider() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        return new AggregationSourceCapabilityProvider(
                properties,
                new EncryptionService(properties),
                mock(BusinessMetaModelMetadataService.class));
    }

    private DataSourceDefinition httpDatasource(String url) {
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setTypeCode("http");
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("url", url);
        definition.setTechnicalMetadata(metadata);
        return definition;
    }
}
