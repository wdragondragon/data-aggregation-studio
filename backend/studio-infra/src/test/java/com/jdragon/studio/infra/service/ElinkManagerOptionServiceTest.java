package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.ElinkGroupOptionView;
import com.jdragon.studio.dto.model.ElinkUserOptionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ElinkManagerOptionServiceTest {

    @Test
    void shouldCacheFilterPageAndResolveUsersAndGroups() throws Exception {
        AtomicInteger userRequests = new AtomicInteger();
        AtomicInteger groupRequests = new AtomicInteger();
        try (TestServer server = TestServer.start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/app/allow-users".equals(path)) {
                userRequests.incrementAndGet();
                respond(exchange, 200, "{\"users\":["
                        + "{\"userid\":\"bob\",\"name\":\"Bob\",\"enable\":0},"
                        + "{\"userid\":\"alice\",\"name\":\"Alice\",\"enable\":1}]}");
                return;
            }
            groupRequests.incrementAndGet();
            respond(exchange, 200, "[{\"id\":9,\"name\":\"Ops Group\","
                    + "\"members\":[{\"userId\":\"alice\"},{\"userId\":\"bob\"}]}]");
        })) {
            AtomicLong ticker = new AtomicLong(1L);
            ElinkManagerOptionService service = service(server, allowedSecurity(), ticker);

            PageView<ElinkUserOptionView> alicePage = service.users("ALI", 1, 10);
            assertEquals(1L, alicePage.getTotal());
            assertEquals("alice", alicePage.getItems().get(0).getUserId());
            assertTrue(alicePage.getItems().get(0).getEnabled());
            PageView<ElinkUserOptionView> bobPage = service.users("bob", 1, 10);
            assertFalse(bobPage.getItems().get(0).getEnabled());
            assertEquals(1, userRequests.get());
            assertEquals("alice", service.requireUser("alice").getUserId());
            StudioException disabled = assertThrows(StudioException.class, () -> service.requireUser("bob"));
            assertEquals(StudioErrorCode.BAD_REQUEST, disabled.getCode());
            assertThrows(StudioException.class, () -> service.requireUser("missing"));

            PageView<ElinkGroupOptionView> groupPage = service.groups("9", 1, 10);
            assertEquals(1L, groupPage.getTotal());
            assertEquals(2, groupPage.getItems().get(0).getMemberCount());
            assertEquals("Ops Group", service.requireGroup(9L).getName());
            assertThrows(StudioException.class, () -> service.requireGroup(10L));
            assertEquals(1, groupRequests.get());

            ticker.addAndGet(TimeUnit.SECONDS.toNanos(31));
            service.users(null, 1, 1);
            assertEquals(2, userRequests.get());
        }
    }

    @Test
    void shouldPreserveAndSanitizeManagerError() throws Exception {
        try (TestServer server = TestServer.start(exchange -> respond(exchange, 500,
                "{\"message\":\"eLink 原始错误; Authorization: Bearer secret-value\"}"))) {
            ElinkManagerOptionService service = service(server, allowedSecurity(), new AtomicLong(1L));

            StudioException error = assertThrows(StudioException.class,
                    () -> service.users(null, 1, 20));

            assertTrue(error.getMessage().startsWith("eLink 原始错误"));
            assertTrue(error.getMessage().contains("Authorization: ******"));
            assertFalse(error.getMessage().contains("secret-value"));
        }
    }

    @Test
    void shouldRejectOversizedManagerResponse() throws Exception {
        try (TestServer server = TestServer.start(exchange -> respond(exchange, 200,
                "{\"users\":[],\"padding\":\"" + "x".repeat(1200) + "\"}"))) {
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getAlert().getElink().setMaxOptionResponseBytes(1024);
            ElinkManagerOptionService service = service(server, allowedSecurity(),
                    new AtomicLong(1L), properties);

            StudioException error = assertThrows(StudioException.class,
                    () -> service.users(null, 1, 20));

            assertTrue(error.getMessage().contains("size limit"));
        }
    }

    @Test
    void shouldRequireAlertManagementRoleBeforeCallingManager() {
        ElinkManagerEndpointResolver resolver = mock(ElinkManagerEndpointResolver.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        ElinkManagerOptionService service = new ElinkManagerOptionService(
                resolver, properties, new ObjectMapper(), securityService, () -> 1L);

        StudioException error = assertThrows(StudioException.class,
                () -> service.users(null, 1, 20));

        assertEquals(StudioErrorCode.FORBIDDEN, error.getCode());
        verifyNoInteractions(resolver);
    }

    private ElinkManagerOptionService service(TestServer server,
                                              StudioSecurityService securityService,
                                              AtomicLong ticker) {
        return service(server, securityService, ticker, new StudioPlatformProperties());
    }

    private ElinkManagerOptionService service(TestServer server,
                                              StudioSecurityService securityService,
                                              AtomicLong ticker,
                                              StudioPlatformProperties properties) {
        ElinkManagerEndpointResolver resolver = mock(ElinkManagerEndpointResolver.class);
        when(resolver.resolve(any(String.class))).thenAnswer(invocation ->
                URI.create("http://127.0.0.1:" + server.port() + invocation.getArgument(0, String.class)));
        properties.getAlert().getElink().setRequestTimeoutSeconds(2);
        return new ElinkManagerOptionService(resolver, properties, new ObjectMapper(),
                securityService, ticker::get);
    }

    private StudioSecurityService allowedSecurity() {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.hasAnyRole(any(String.class), any(String.class), any(String.class))).thenReturn(true);
        return securityService;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;

        private TestServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        private static TestServer start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            server.createContext("/", handler);
            server.setExecutor(executor);
            server.start();
            return new TestServer(server, executor);
        }

        private int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
