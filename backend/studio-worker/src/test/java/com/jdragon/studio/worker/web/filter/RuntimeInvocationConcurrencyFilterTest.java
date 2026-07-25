package com.jdragon.studio.worker.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeInvocationConcurrencyFilterTest {

    @Test
    void shouldRejectExcessRuntimeCallsAndReleasePermitAfterCompletion() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeInvocationMaxConcurrency(Integer.valueOf(1));
        RuntimeInvocationConcurrencyFilter filter = new RuntimeInvocationConcurrencyFilter(
                new ObjectMapper().findAndRegisterModules(), properties);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> {
                try {
                    filter.doFilter(runtimeRequest(), new MockHttpServletResponse(), (request, response) -> {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting to release the first request");
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while waiting to release the first request", ex);
                        }
                    });
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            AtomicBoolean excessChainCalled = new AtomicBoolean(false);
            MockHttpServletResponse excessResponse = new MockHttpServletResponse();
            filter.doFilter(runtimeRequest(), excessResponse,
                    (request, response) -> excessChainCalled.set(true));

            assertFalse(excessChainCalled.get());
            assertEquals(503, excessResponse.getStatus());
            assertTrue(excessResponse.getContentAsString().contains("concurrency limit"));

            release.countDown();
            first.get(5, TimeUnit.SECONDS);

            AtomicBoolean nextChainCalled = new AtomicBoolean(false);
            filter.doFilter(runtimeRequest(), new MockHttpServletResponse(),
                    (request, response) -> nextChainCalled.set(true));
            assertTrue(nextChainCalled.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldNotLimitRuntimeHealthChecks() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeInvocationMaxConcurrency(Integer.valueOf(1));
        RuntimeInvocationConcurrencyFilter filter = new RuntimeInvocationConcurrencyFilter(
                new ObjectMapper().findAndRegisterModules(), properties);
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/runtime/health");

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertTrue(chainCalled.get());
    }

    private MockHttpServletRequest runtimeRequest() {
        return new MockHttpServletRequest("POST", "/internal/runtime/data-services/demo/public");
    }
}
