package com.jdragon.studio.infra.service;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class AlertWebhookHttpClient {

    public Response post(AlertWebhookSecurityService.ValidatedWebhookTarget target,
                         Map<String, String> headers,
                         byte[] body,
                         int connectTimeoutSeconds,
                         int requestTimeoutSeconds,
                         int maxResponseBytes) throws Exception {
        int connectTimeout = Math.max(1, connectTimeoutSeconds);
        int requestTimeout = Math.max(1, requestTimeoutSeconds);
        int maxBytes = Math.max(1024, maxResponseBytes);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(connectTimeout))
                .setResponseTimeout(Timeout.ofSeconds(requestTimeout))
                .setRedirectsEnabled(false)
                .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PinnedDnsResolver(target))
                .build();
        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build()) {
            HttpPost request = new HttpPost(target.getUri());
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.setHeader(entry.getKey(), entry.getValue());
            }
            request.setHeader("Content-Type", "application/json");
            request.setHeader("Accept", "application/json");
            request.setHeader("User-Agent", "Data-Aggregation-Studio-Alert/1.0");
            request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
            return client.execute(request, response -> new Response(response.getCode(),
                    readResponse(response.getEntity(), maxBytes,
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(requestTimeout))));
        }
    }

    private byte[] readResponse(HttpEntity entity, int maxBytes, long deadlineNanos) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        try (InputStream stream = entity.getContent();
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 4096))) {
            byte[] buffer = new byte[Math.min(4096, maxBytes + 1)];
            while (output.size() <= maxBytes) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw new SocketTimeoutException("Webhook response body timed out");
                }
                int remaining = maxBytes + 1 - output.size();
                int read = stream.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
            }
            byte[] bytes = output.toByteArray();
            return bytes.length <= maxBytes ? bytes : Arrays.copyOf(bytes, maxBytes);
        }
    }

    public static final class Response {
        private final int statusCode;
        private final byte[] body;

        private Response(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String bodyAsText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class PinnedDnsResolver implements DnsResolver {
        private final String host;
        private final InetAddress[] addresses;

        private PinnedDnsResolver(AlertWebhookSecurityService.ValidatedWebhookTarget target) {
            this.host = target.getUri().getHost().toLowerCase(Locale.ROOT);
            this.addresses = target.getAddresses();
        }

        @Override
        public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
            if (requestedHost == null || !host.equals(requestedHost.toLowerCase(Locale.ROOT))) {
                throw new UnknownHostException("Webhook DNS resolution escaped the validated host");
            }
            return Arrays.copyOf(addresses, addresses.length);
        }

        @Override
        public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
            if (requestedHost == null || !host.equals(requestedHost.toLowerCase(Locale.ROOT))) {
                throw new UnknownHostException("Webhook canonical host escaped the validated host");
            }
            return host;
        }
    }
}
