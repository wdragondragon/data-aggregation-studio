package com.jdragon.studio.worker.runtime;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.jdragon.aggregation.commons.exception.AggregationException;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.commons.util.FastJsonMemory;
import com.jdragon.aggregation.httpreader.HttpConstant;
import com.jdragon.aggregation.httpreader.HttpReader;
import com.jdragon.aggregation.httpreader.HttpReaderErrorCode;
import com.jdragon.aggregation.httpreader.Key;
import com.jdragon.aggregation.httpreader.ReadResultTypeEnum;
import com.jdragon.aggregation.plugin.httpdyn.HttpDynColumnExecuteHandler;
import com.jdragon.aggregation.unstructuredstorage.SoapToJSON;
import com.jdragon.aggregation.unstructuredstorage.XmlToJSON;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Studio-owned HTTP reader cancellation adapter. It preserves the framework
 * reader's parsing and pagination flow while retaining the active HTTP request
 * so Worker cancellation can close a blocked response immediately.
 */
public class CancellableHttpReader extends HttpReader {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000L;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<HttpRequestBase> activeRequest = new AtomicReference<HttpRequestBase>();
    private final AtomicReference<CloseableHttpResponse> activeResponse = new AtomicReference<CloseableHttpResponse>();
    private final AtomicReference<CloseableHttpClient> activeClient = new AtomicReference<CloseableHttpClient>();

    private Configuration configuration;
    private String url;
    private String method;
    private String contentType;
    private String resultType;
    private String soapAction;
    private boolean soapFaultFail;
    private int pageSize;
    private int connectTimeoutMs;
    private int readTimeoutMs;
    private Map<String, Object> responseStatus;

    @Override
    public void init() {
        super.init();
        configuration = getPluginJobConf();
        url = configuration.getNecessaryValue(Key.URL, HttpReaderErrorCode.REQUIRED_VALUE);
        method = configuration.getNecessaryValue(Key.MODE, HttpReaderErrorCode.REQUIRED_VALUE);
        String protocolMode = configuration.getUnnecessaryValue(Key.PROTOCOL_MODE, "REST_JSON", null);
        String soapVersion = configuration.getUnnecessaryValue(Key.SOAP_VERSION, "SOAP_11", null);
        soapAction = configuration.getUnnecessaryValue(Key.SOAP_ACTION, "", null);
        soapFaultFail = configuration.getBool(Key.SOAP_FAULT_FAIL, true);
        resultType = configuration.getUnnecessaryValue(Key.RESULT_DATA_TYPE,
                isSoapMode(protocolMode) ? "soap" : "json", null);
        String configuredContentType = configuration.getString(Key.CONTENT_TYPE, null);
        contentType = StringUtils.isNotBlank(configuredContentType)
                ? configuredContentType
                : isSoapMode(protocolMode)
                ? ("SOAP_12".equalsIgnoreCase(soapVersion)
                ? "application/soap+xml;charset=UTF-8"
                : "text/xml;charset=UTF-8")
                : HttpConstant.getContentType();
        pageSize = configuration.getInt(Key.PAGE_SIZE, 500);
        connectTimeoutMs = positive(configuration.getInt("connectTimeout", 3000), 3000);
        readTimeoutMs = positive(configuration.getInt("readTimeout",
                configuration.getInt("socketTimeout", 3000)), 3000);
        responseStatus = configuration.getMap(Key.RESPONSE_STATUS);
    }

    @Override
    public String connectToGetData(Map<String, String> httpContent, Integer pageNum) {
        ensureActive();
        int currentPage = pageNum == null ? 1 : pageNum.intValue();
        HttpDynColumnExecuteHandler.replacePage(httpContent, String.valueOf(currentPage));
        HttpDynColumnExecuteHandler.replacePageSize(httpContent, String.valueOf(pageSize));
        HttpDynColumnExecuteHandler.replaceOffset(httpContent,
                String.valueOf(Math.max(0L, (long) (currentPage - 1) * pageSize)));
        HttpDynColumnExecuteHandler.replaceDynVal(httpContent);

        Map<String, String> headers = parseStringMap(httpContent.get(Key.HEADER));
        Map<String, String> params = parseStringMap(httpContent.get(Key.PARAM_STR));
        String requestBody = httpContent.get(Key.REQUEST_BODY);
        String result = executeWithRetry(headers, params, requestBody);

        if (soapFaultFail) {
            String faultMessage = SoapToJSON.faultMessage(result);
            if (StringUtils.isNotBlank(faultMessage)) {
                throw AggregationException.asException(HttpReaderErrorCode.RESPONSE_STATUS_ERROR,
                        "SOAP Fault: " + faultMessage);
            }
        }

        if (ReadResultTypeEnum.XML.name().equalsIgnoreCase(resultType)) {
            try {
                result = JSONObject.toJSONString(XmlToJSON.toJson(result));
            } catch (Exception failure) {
                throw AggregationException.asException(HttpReaderErrorCode.RESULT_RO_JSON_ERROR,
                        "XML response parse failed: " + failure.getMessage());
            }
        } else if (ReadResultTypeEnum.SOAP.name().equalsIgnoreCase(resultType)) {
            try {
                result = JSONObject.toJSONString(SoapToJSON.toJson(result));
            } catch (Exception failure) {
                throw AggregationException.asException(HttpReaderErrorCode.RESULT_RO_JSON_ERROR,
                        "SOAP response parse failed: " + failure.getMessage());
            }
        }
        validateBusinessStatus(result);
        return result;
    }

    @Override
    public void destroy() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        HttpRequestBase request = activeRequest.getAndSet(null);
        if (request != null) {
            request.abort();
        }
        close(activeResponse.getAndSet(null));
        close(activeClient.getAndSet(null));
    }

    private String executeWithRetry(Map<String, String> headers,
                                    Map<String, String> params,
                                    String requestBody) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ensureActive();
            try {
                return executeOnce(headers, params, requestBody);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                if (cancelled.get() || Thread.currentThread().isInterrupted() || attempt == MAX_ATTEMPTS) {
                    throw failure;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("HTTP reader interrupted during retry delay", interrupted);
                }
            }
        }
        throw lastFailure == null ? new IllegalStateException("HTTP reader request failed") : lastFailure;
    }

    private String executeOnce(Map<String, String> headers,
                               Map<String, String> params,
                               String requestBody) {
        HttpRequestBase request = null;
        CloseableHttpClient client = null;
        CloseableHttpResponse response = null;
        try {
            URIBuilder uriBuilder = new URIBuilder(url);
            for (Map.Entry<String, String> param : params.entrySet()) {
                uriBuilder.addParameter(param.getKey(), param.getValue());
            }
            request = createRequest(method, uriBuilder.build(), requestBody);
            request.setConfig(RequestConfig.custom()
                    .setConnectTimeout(connectTimeoutMs)
                    .setConnectionRequestTimeout(connectTimeoutMs)
                    .setSocketTimeout(readTimeoutMs)
                    .build());
            request.setHeader("Content-Type", contentType);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.setHeader(header.getKey(), header.getValue());
            }
            if (StringUtils.isNotBlank(soapAction) && !containsHeaderIgnoreCase(headers, "SOAPAction")) {
                request.setHeader("SOAPAction", soapAction);
            }

            client = HttpClients.createDefault();
            activeClient.set(client);
            activeRequest.set(request);
            ensureActive();
            response = client.execute(request);
            activeResponse.set(response);
            int statusCode = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();
            String result = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            if (statusCode != 200) {
                throw new IllegalStateException("HTTP request failed with status " + statusCode + ": " + result);
            }
            return result;
        } catch (Exception failure) {
            if (request != null) {
                request.abort();
            }
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("HTTP reader request cancelled", failure);
            }
            throw new IllegalStateException("HTTP reader request failed: " + failure.getMessage(), failure);
        } finally {
            if (request != null) {
                activeRequest.compareAndSet(request, null);
            }
            if (response != null) {
                activeResponse.compareAndSet(response, null);
                close(response);
            }
            if (client != null) {
                activeClient.compareAndSet(client, null);
                close(client);
            }
        }
    }

    private HttpRequestBase createRequest(String requestMethod, URI uri, String requestBody) {
        String normalized = requestMethod == null ? "GET" : requestMethod.trim().toUpperCase();
        HttpRequestBase request;
        switch (normalized) {
            case "POST":
                request = new HttpPost(uri);
                break;
            case "PUT":
                request = new HttpPut(uri);
                break;
            case "PATCH":
                request = new HttpPatch(uri);
                break;
            case "DELETE":
                request = new HttpDelete(uri);
                break;
            case "HEAD":
                request = new HttpHead(uri);
                break;
            case "OPTIONS":
                request = new HttpOptions(uri);
                break;
            case "TRACE":
                request = new HttpTrace(uri);
                break;
            case "GET":
                request = new HttpGet(uri);
                break;
            default:
                throw new IllegalArgumentException("Unsupported HTTP request method: " + normalized);
        }
        if (request instanceof HttpEntityEnclosingRequestBase && StringUtils.isNotBlank(requestBody)) {
            ((HttpEntityEnclosingRequestBase) request).setEntity(
                    new StringEntity(requestBody, StandardCharsets.UTF_8));
        }
        return request;
    }

    private void validateBusinessStatus(String result) {
        if (responseStatus == null || responseStatus.isEmpty()) {
            return;
        }
        Object path = responseStatus.get("path");
        Object expectedCode = responseStatus.get("code");
        Object actualCode = path == null ? null : new FastJsonMemory(result).get(String.valueOf(path));
        if (actualCode == null || expectedCode == null) {
            throw AggregationException.asException(HttpReaderErrorCode.REQUIRED_VALUE,
                    "Response status path and code must not be empty");
        }
        if (!String.valueOf(expectedCode).equalsIgnoreCase(String.valueOf(actualCode))) {
            throw AggregationException.asException(HttpReaderErrorCode.RESPONSE_STATUS_ERROR,
                    String.format("Expected business status [%s], actual status [%s]", expectedCode, actualCode));
        }
    }

    private Map<String, String> parseStringMap(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyMap();
        }
        Map<String, String> parsed = JSONObject.parseObject(json,
                new TypeReference<Map<String, String>>() { });
        return parsed == null ? Collections.emptyMap() : new LinkedHashMap<String, String>(parsed);
    }

    private boolean isSoapMode(String protocolMode) {
        return "SOAP".equalsIgnoreCase(protocolMode)
                || ReadResultTypeEnum.SOAP.name().equalsIgnoreCase(
                configuration.getString(Key.RESULT_DATA_TYPE, ""));
    }

    private boolean containsHeaderIgnoreCase(Map<String, String> headers, String expected) {
        for (String name : headers.keySet()) {
            if (expected.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private void ensureActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("HTTP reader request cancelled");
        }
    }

    private void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Cancellation cleanup is idempotent and best effort.
        }
    }
}
