package com.jdragon.studio.infra.service;

import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class RuntimeEndpointHttpClient {

    private static final int MAX_CONNECTIONS = 100;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 20;
    private static final TimeValue IDLE_CONNECTION_AGE = TimeValue.ofMinutes(2L);

    private final PinnedDnsResolver dnsResolver;
    private final PoolingHttpClientConnectionManager connectionManager;
    private final CloseableHttpClient client;

    public RuntimeEndpointHttpClient() {
        this.dnsResolver = new PinnedDnsResolver();
        this.connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .setValidateAfterInactivity(TimeValue.ZERO_MILLISECONDS)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build();
    }

    public Response execute(RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target,
                            String method,
                            Map<String, List<String>> headers,
                            byte[] body,
                            int connectTimeoutMillis,
                            int readTimeoutMillis,
                            int maxResponseBytes) throws Exception {
        int connectTimeout = timeout(connectTimeoutMillis, 3000);
        int readTimeout = timeout(readTimeoutMillis, 5000);
        int maxBytes = Math.min(64 * 1024 * 1024, Math.max(1024, maxResponseBytes));
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeout))
                .setRedirectsEnabled(false)
                .build();
        dnsResolver.pin(target);
        connectionManager.closeExpired();
        connectionManager.closeIdle(IDLE_CONNECTION_AGE);
        HttpUriRequestBase request = new HttpUriRequestBase(method, target.getUri());
        request.setConfig(requestConfig);
        if (body != null && body.length > 0) {
            request.setEntity(new ByteArrayEntity(body, null));
        }
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    if (value != null) {
                        request.addHeader(entry.getKey(), value);
                    }
                }
            }
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeout);
        return client.execute(request, response -> new Response(
                response.getCode(), responseHeaders(response.getHeaders()),
                readResponse(response.getEntity(), maxBytes, deadlineNanos)));
    }

    public Response execute(RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target,
                            String method,
                            Map<String, List<String>> headers,
                            InputStream body,
                            long contentLength,
                            int connectTimeoutMillis,
                            int readTimeoutMillis,
                            int maxResponseBytes) throws Exception {
        if (body == null || contentLength < 0L) {
            throw new IllegalArgumentException("Streaming request body and content length are required");
        }
        int connectTimeout = timeout(connectTimeoutMillis, 3000);
        int readTimeout = streamingTimeout(readTimeoutMillis, 300000);
        int maxBytes = Math.min(1024 * 1024, Math.max(1024, maxResponseBytes));
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeout))
                .setRedirectsEnabled(false)
                .build();
        dnsResolver.pin(target);
        connectionManager.closeExpired();
        connectionManager.closeIdle(IDLE_CONNECTION_AGE);
        HttpUriRequestBase request = new HttpUriRequestBase(method, target.getUri());
        request.setConfig(requestConfig);
        request.setEntity(new InputStreamEntity(body, contentLength,
                ContentType.APPLICATION_OCTET_STREAM));
        addHeaders(request, headers);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeout);
        return client.execute(request, response -> new Response(
                response.getCode(), responseHeaders(response.getHeaders()),
                readResponse(response.getEntity(), maxBytes, deadlineNanos)));
    }

    public StreamingResponse executeStreaming(RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target,
                                               String method,
                                               Map<String, List<String>> headers,
                                               byte[] body,
                                               int connectTimeoutMillis,
                                               int readTimeoutMillis,
                                               OutputStream output) throws Exception {
        int connectTimeout = timeout(connectTimeoutMillis, 3000);
        int readTimeout = streamingTimeout(readTimeoutMillis, 60000);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeout))
                .setRedirectsEnabled(false)
                .build();
        dnsResolver.pin(target);
        connectionManager.closeExpired();
        connectionManager.closeIdle(IDLE_CONNECTION_AGE);
        HttpUriRequestBase request = new HttpUriRequestBase(method, target.getUri());
        request.setConfig(requestConfig);
        if (body != null && body.length > 0) {
            request.setEntity(new ByteArrayEntity(body, null));
        }
        addHeaders(request, headers);
        return client.execute(request, response -> {
            Map<String, List<String>> responseHeaders = responseHeaders(response.getHeaders());
            if (response.getCode() >= 200 && response.getCode() < 300) {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    entity.writeTo(output);
                }
                return new StreamingResponse(response.getCode(), responseHeaders);
            }
            byte[] errorBody = readResponse(response.getEntity(), 1024 * 1024,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeout));
            return new StreamingResponse(response.getCode(), responseHeaders, errorBody);
        });
    }

    @PreDestroy
    public void close() {
        try {
            client.close();
        } catch (IOException ignored) {
            // The application is already shutting down; no retry is useful here.
        }
        dnsResolver.clear();
    }

    private byte[] readResponse(HttpEntity entity, int maxBytes, long deadlineNanos) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        if (entity.getContentLength() > maxBytes) {
            throw new RuntimeEndpointSecurityService.ResponseTooLargeException(maxBytes);
        }
        try (InputStream input = entity.getContent();
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024))) {
            byte[] buffer = new byte[Math.min(8192, maxBytes)];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw new SocketTimeoutException("Runtime endpoint response body timed out");
                }
                total += read;
                if (total > maxBytes) {
                    throw new RuntimeEndpointSecurityService.ResponseTooLargeException(maxBytes);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private Map<String, List<String>> responseHeaders(Header[] headers) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        if (headers == null) {
            return result;
        }
        for (Header header : headers) {
            if (header == null || header.getName() == null || header.getValue() == null) {
                continue;
            }
            result.computeIfAbsent(header.getName(), ignored -> new ArrayList<String>())
                    .add(header.getValue());
        }
        return result;
    }

    private void addHeaders(HttpUriRequestBase request, Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value != null) {
                    request.addHeader(entry.getKey(), value);
                }
            }
        }
    }

    private int timeout(int value, int fallback) {
        int configured = value <= 0 ? fallback : value;
        return Math.max(100, Math.min(configured, 60000));
    }

    private int streamingTimeout(int value, int fallback) {
        int configured = value <= 0 ? fallback : value;
        return Math.max(1000, Math.min(configured, 30 * 60 * 1000));
    }

    public static final class Response {
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final byte[] body;

        private Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
            this.statusCode = statusCode;
            this.headers = copyHeaders(headers);
            this.body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
        }

        public int getStatusCode() {
            return statusCode;
        }

        public Map<String, List<String>> getHeaders() {
            return copyHeaders(headers);
        }

        public byte[] getBody() {
            return Arrays.copyOf(body, body.length);
        }

        private static Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
            Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
            if (source != null) {
                source.forEach((name, values) -> copy.put(name,
                        values == null ? new ArrayList<String>() : new ArrayList<String>(values)));
            }
            return copy;
        }
    }

    public static final class StreamingResponse {
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final byte[] errorBody;

        private StreamingResponse(int statusCode, Map<String, List<String>> headers) {
            this(statusCode, headers, new byte[0]);
        }

        private StreamingResponse(int statusCode, Map<String, List<String>> headers, byte[] errorBody) {
            this.statusCode = statusCode;
            this.headers = Response.copyHeaders(headers);
            this.errorBody = errorBody == null ? new byte[0] : Arrays.copyOf(errorBody, errorBody.length);
        }

        public int getStatusCode() {
            return statusCode;
        }

        public Map<String, List<String>> getHeaders() {
            return Response.copyHeaders(headers);
        }

        public byte[] getErrorBody() {
            return Arrays.copyOf(errorBody, errorBody.length);
        }
    }

    private static final class PinnedDnsResolver implements DnsResolver {
        private final Map<String, InetAddress[]> addressesByHost = new ConcurrentHashMap<String, InetAddress[]>();

        private void pin(RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target) throws UnknownHostException {
            String host = normalizeHost(target.getUri().getHost());
            InetAddress[] addresses = target.getAddresses();
            if (host.isEmpty() || addresses == null || addresses.length == 0) {
                throw new UnknownHostException("Runtime endpoint has no validated address");
            }
            addressesByHost.put(host, Arrays.copyOf(addresses, addresses.length));
        }

        private void clear() {
            addressesByHost.clear();
        }

        @Override
        public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
            InetAddress[] addresses = addressesByHost.get(normalizeHost(requestedHost));
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException("Runtime endpoint host was not validated before use");
            }
            return Arrays.copyOf(addresses, addresses.length);
        }

        @Override
        public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
            String host = normalizeHost(requestedHost);
            if (!addressesByHost.containsKey(host)) {
                throw new UnknownHostException("Runtime endpoint canonical host was not validated before use");
            }
            return host;
        }

        private static String normalizeHost(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
                return normalized.substring(1, normalized.length() - 1);
            }
            return normalized;
        }
    }
}
