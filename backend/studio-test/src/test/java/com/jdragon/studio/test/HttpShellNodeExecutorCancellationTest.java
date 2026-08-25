package com.jdragon.studio.test;

import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.worker.runtime.HttpShellNodeExecutor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class HttpShellNodeExecutorCancellationTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldAbortLongResponseWhenWorkerCancellationCallbackRuns() throws Exception {
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch responseClosed = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/long", exchange -> holdResponse(exchange, responseStarted, releaseResponse, responseClosed));
        server.start();

        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeType(NodeType.HTTP);
        node.setNodeCode("long-http");
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/long");
        config.put("method", "GET");
        node.setConfig(config);

        AtomicReference<Runnable> cancellation = new AtomicReference<Runnable>();
        AtomicReference<Map<String, Object>> result = new AtomicReference<Map<String, Object>>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();
        runtimeContext.put("studio.registerCancellation", (Consumer<Runnable>) cancellation::set);

        Thread execution = new Thread(() -> {
            try {
                result.set(new HttpShellNodeExecutor().execute(node, runtimeContext));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "http-shell-cancellation-test");
        execution.start();

        assertThat(responseStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(cancellation.get()).isNotNull();
        long cancellationStartedAt = System.nanoTime();
        cancellation.get().run();
        execution.join(2000L);
        long cancellationDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancellationStartedAt);
        assertThat(execution.isAlive()).isFalse();
        assertThat(cancellationDurationMs).isLessThan(2000L);
        releaseResponse.countDown();

        assertThat(responseClosed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(result.get()).isNotNull();
        assertThat(result.get().get("status")).isEqualTo("FAILED");
    }

    private static void holdResponse(HttpExchange exchange,
                                     CountDownLatch responseStarted,
                                     CountDownLatch releaseResponse,
                                     CountDownLatch responseClosed) {
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
            exchange.sendResponseHeaders(200, 0);
            responseStarted.countDown();
            releaseResponse.await(30, TimeUnit.SECONDS);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write("done".getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The client closing the connection is the expected cancellation path.
        } finally {
            exchange.close();
            responseClosed.countDown();
        }
    }
}
