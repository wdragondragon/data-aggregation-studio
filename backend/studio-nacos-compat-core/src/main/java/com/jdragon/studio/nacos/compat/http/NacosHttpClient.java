package com.jdragon.studio.nacos.compat.http;

import com.jdragon.studio.nacos.compat.support.NacosUrlSupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;

public class NacosHttpClient {

    private final HttpClient httpClient;

    public NacosHttpClient(Duration connectTimeout) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public NacosHttpResponse get(String serverAddr, String path, Map<String, String> query, Map<String, String> headers,
            Duration timeout) {
        return send(serverAddr, path, query, uri -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout)
                    .GET();
            addHeaders(builder, headers);
            return builder.build();
        });
    }

    public NacosHttpResponse delete(String serverAddr, String path, Map<String, String> query, Map<String, String> headers,
            Duration timeout) {
        return send(serverAddr, path, query, uri -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout)
                    .DELETE();
            addHeaders(builder, headers);
            return builder.build();
        });
    }

    public NacosHttpResponse postForm(String serverAddr, String path, Map<String, String> query, Map<String, String> form,
            Map<String, String> headers, Duration timeout) {
        return send(serverAddr, path, query, uri -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(toForm(form)));
            builder.header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            addHeaders(builder, headers);
            return builder.build();
        });
    }

    public NacosHttpResponse putForm(String serverAddr, String path, Map<String, String> query, Map<String, String> form,
            Map<String, String> headers, Duration timeout) {
        return send(serverAddr, path, query, uri -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout)
                    .PUT(HttpRequest.BodyPublishers.ofString(toForm(form)));
            builder.header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            addHeaders(builder, headers);
            return builder.build();
        });
    }

    private NacosHttpResponse send(String serverAddr, String path, Map<String, String> query,
            Function<URI, HttpRequest> requestFactory) {
        List<URI> uris = NacosUrlSupport.buildAll(serverAddr, path, query);
        NacosHttpResponse lastResponse = null;
        IllegalStateException lastException = null;
        for (URI uri : uris) {
            try {
                NacosHttpResponse response = send(requestFactory.apply(uri));
                if (response.is2xxSuccessful()) {
                    return response;
                }
                lastResponse = response;
            }
            catch (IllegalStateException ex) {
                lastException = ex;
            }
        }
        if (lastResponse != null) {
            return lastResponse;
        }
        throw new IllegalStateException("Nacos http request failed for all servers: " + uris, lastException);
    }

    private NacosHttpResponse send(HttpRequest request) {
        try {
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new NacosHttpResponse(response.statusCode(), response.body());
        }
        catch (IOException ex) {
            throw new IllegalStateException("Nacos http request failed: " + request.uri(), ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Nacos http request interrupted: " + request.uri(), ex);
        }
    }

    private void addHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        headers.forEach((key, value) -> {
            if (value != null) {
                builder.header(key, value);
            }
        });
    }

    private String toForm(Map<String, String> form) {
        if (form == null || form.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("&");
        form.forEach((key, value) -> {
            if (value != null) {
                joiner.add(NacosUrlSupport.urlEncode(key) + "=" + NacosUrlSupport.urlEncode(value));
            }
        });
        return joiner.toString();
    }

}
