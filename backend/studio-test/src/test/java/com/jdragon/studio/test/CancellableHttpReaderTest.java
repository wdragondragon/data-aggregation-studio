package com.jdragon.studio.test;

import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.httpreader.Key;
import com.jdragon.studio.worker.runtime.CancellableHttpReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellableHttpReaderTest {

    @Test
    void destroyShouldAbortBlockedResponseAndStopRetries() throws Exception {
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/long", exchange -> streamUntilReleased(exchange, responseStarted, releaseServer));
        server.start();

        CancellableHttpReader reader = new CancellableHttpReader();
        reader.setPluginJobConf(readerConfiguration(server.getAddress().getPort()));
        reader.init();
        Map<String, String> httpContent = new LinkedHashMap<String, String>();
        httpContent.put(Key.HEADER, "{}");
        httpContent.put(Key.PARAM_STR, "{}");
        httpContent.put(Key.REQUEST_BODY, "{}");
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread execution = new Thread(() -> {
            try {
                reader.connectToGetData(httpContent, 1);
            } catch (Throwable expected) {
                failure.set(expected);
            }
        });

        try {
            execution.start();
            assertTrue(responseStarted.await(2, TimeUnit.SECONDS));
            reader.destroy();
            reader.destroy();
            execution.join(2500L);

            assertFalse(execution.isAlive());
            assertNotNull(failure.get());
        } finally {
            releaseServer.countDown();
            server.stop(0);
            if (execution.isAlive()) {
                execution.interrupt();
            }
        }
    }

    private Configuration readerConfiguration(int port) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(Key.URL, "http://127.0.0.1:" + port + "/long");
        values.put(Key.MODE, "GET");
        values.put(Key.HEADER, "{}");
        values.put(Key.PARAM_STR, "{}");
        values.put(Key.REQUEST_BODY, "{}");
        values.put(Key.RESULT_DATA_TYPE, "json");
        values.put(Key.COLUMN, Collections.emptyList());
        values.put("connectTimeout", 3000);
        values.put("readTimeout", 60000);
        return Configuration.from(values);
    }

    private void streamUntilReleased(HttpExchange exchange,
                                     CountDownLatch responseStarted,
                                     CountDownLatch releaseServer) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write("[".getBytes(StandardCharsets.UTF_8));
            output.flush();
            responseStarted.countDown();
            try {
                releaseServer.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            exchange.close();
        }
    }
}
