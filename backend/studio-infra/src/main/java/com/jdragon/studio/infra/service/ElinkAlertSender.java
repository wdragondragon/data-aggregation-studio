package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ElinkAlertSender {

    private static final int MAX_TEXT_BYTES = 2048;

    private final AlertChannelService alertChannelService;
    private final ObjectProvider<DiscoveryClient> discoveryClientProvider;
    private final StudioPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicInteger instanceCursor = new AtomicInteger();

    public ElinkAlertSender(AlertChannelService alertChannelService,
                            ObjectProvider<DiscoveryClient> discoveryClientProvider,
                            StudioPlatformProperties properties,
                            ObjectMapper objectMapper) {
        this.alertChannelService = alertChannelService;
        this.discoveryClientProvider = discoveryClientProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(positive(settings().getConnectTimeoutSeconds(), 3)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public SendResult send(AlertChannelEntity channel, Map<String, Object> payload) {
        if (!settings().isEnabled()) {
            return SendResult.skipped("eLink alert delivery is disabled");
        }
        try {
            String targetType = alertChannelService.elinkTargetType(channel);
            URI endpoint = endpoint(targetType, alertChannelService.elinkGroupId(channel));
            byte[] requestBody = objectMapper.writeValueAsBytes(requestBody(
                    targetType, alertChannelService.elinkUserIds(channel), alertText(payload)));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(positive(settings().getRequestTimeoutSeconds(), 10)))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return classify(response);
        } catch (StudioException ex) {
            return SendResult.dead(null, sanitize(ex.getMessage()), null);
        } catch (JsonProcessingException ex) {
            return SendResult.dead(null, "eLink alert request could not be serialized", null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return SendResult.retry(null, failureMessage(ex), null);
        } catch (IOException ex) {
            return SendResult.retry(null, failureMessage(ex), null);
        } catch (RuntimeException ex) {
            return SendResult.retry(null, failureMessage(ex), null);
        }
    }

    private URI endpoint(String targetType, Long groupId) {
        DiscoveryClient discoveryClient = discoveryClientProvider.getIfAvailable();
        if (discoveryClient == null) {
            throw new IllegalStateException("Nacos discovery is unavailable for eLink delivery");
        }
        String serviceName = requireText(settings().getServiceName(), "eLink service name is not configured");
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceName);
        if (instances == null || instances.isEmpty()) {
            throw new IllegalStateException("No eLink service instance is available: " + serviceName);
        }
        ServiceInstance instance = instances.get(Math.floorMod(instanceCursor.getAndIncrement(), instances.size()));
        URI base = instance.getUri();
        if (base == null || !StringUtils.hasText(base.getHost())) {
            throw new IllegalStateException("The discovered eLink service instance is invalid");
        }
        String path = normalizePath(settings().getPathPrefix());
        if ("GROUP".equals(targetType)) {
            if (groupId == null || groupId.longValue() <= 0L) {
                throw badRequest("Stored eLink group id is invalid");
            }
            path += "/groups/" + groupId + "/messages";
        } else if ("PERSONAL".equals(targetType)) {
            path += "/messages";
        } else {
            throw badRequest("Stored eLink target type is invalid");
        }
        return appendPath(base, path);
    }

    private Map<String, Object> requestBody(String targetType, List<String> userIds, String content) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("msgType", "text");
        body.put("content", content);
        if ("PERSONAL".equals(targetType)) {
            if (userIds == null || userIds.isEmpty()) {
                throw badRequest("Stored eLink accounts are missing");
            }
            body.put("userIds", new ArrayList<String>(userIds));
        }
        return body;
    }

    private SendResult classify(HttpResponse<InputStream> response) throws IOException {
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status < 200 || status >= 300) {
                String excerpt = readLimited(body, Math.max(1024,
                        positive(settings().getMaxErrorResponseBytes(), 16 * 1024)));
                String error = managerHttpError(excerpt, status);
                return retryableHttpStatus(status)
                        ? SendResult.retry(status, sanitize(error), sanitize(excerpt))
                        : SendResult.dead(status, sanitize(error), sanitize(excerpt));
            }
            ElinkSendResponse result = objectMapper.readValue(body, ElinkSendResponse.class);
            String excerpt = responseExcerpt(result);
            if (result.succeeded()) {
                return SendResult.success(status, excerpt);
            }
            String error = firstText(result.getErrorMessage(), result.getErrmsg(),
                    result.getErrcode() == null ? "eLink returned an unsuccessful response"
                            : "eLink returned errcode " + result.getErrcode());
            return SendResult.dead(status, sanitize(error), excerpt);
        }
    }

    private String managerHttpError(String excerpt, int status) {
        if (StringUtils.hasText(excerpt)) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(excerpt);
                for (String field : List.of("errorMessage", "message", "errmsg", "error")) {
                    String value = node.path(field).asText(null);
                    if (StringUtils.hasText(value)) {
                        return value;
                    }
                }
            } catch (JsonProcessingException parseFailure) {
                return excerpt;
            }
            return excerpt;
        }
        return "eLink Manager returned HTTP " + status;
    }

    private String alertText(Map<String, Object> payload) {
        Map<String, Object> rule = nestedMap(payload, "rule");
        Map<String, Object> subject = nestedMap(payload, "subject");
        List<String> lines = new ArrayList<String>();
        String severity = text(rule.get("severity"));
        String ruleName = text(rule.get("name"));
        lines.add((StringUtils.hasText(severity) ? "[" + severity + "] " : "")
                + firstText(ruleName, "Studio alert", "Studio alert"));
        String subjectName = text(subject.get("name"));
        if (StringUtils.hasText(subjectName)) {
            lines.add("对象：" + subjectName);
        }
        String summary = text(payload == null ? null : payload.get("summary"));
        if (StringUtils.hasText(summary)) {
            lines.add(summary);
        }
        String occurredAt = text(payload == null ? null : payload.get("occurredAt"));
        if (StringUtils.hasText(occurredAt)) {
            lines.add("时间：" + occurredAt);
        }
        return truncateUtf8(String.join("\n", lines), MAX_TEXT_BYTES);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private URI appendPath(URI base, String suffix) {
        String basePath = StringUtils.hasText(base.getPath()) && !"/".equals(base.getPath())
                ? stripTrailingSlash(base.getPath()) : "";
        String path = suffix.startsWith("/") ? suffix : "/" + suffix;
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), basePath + path, null, null);
        } catch (Exception ex) {
            throw new IllegalStateException("The discovered eLink service address is invalid", ex);
        }
    }

    private String normalizePath(String value) {
        String path = requireText(value, "eLink path prefix is not configured");
        path = path.startsWith("/") ? path : "/" + path;
        return stripTrailingSlash(path);
    }

    private String stripTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String readLimited(InputStream input, int maxBytes) throws IOException {
        byte[] value = input.readNBytes(maxBytes + 1);
        int length = Math.min(value.length, maxBytes);
        return new String(value, 0, length, StandardCharsets.UTF_8);
    }

    private String responseExcerpt(ElinkSendResponse result) {
        ObjectNode node = objectMapper.createObjectNode();
        if (result.getId() != null) {
            node.put("id", result.getId());
        }
        if (result.getSuccess() != null) {
            node.put("success", result.getSuccess());
        }
        if (result.getErrcode() != null) {
            node.put("errcode", result.getErrcode());
        }
        if (StringUtils.hasText(result.getErrmsg())) {
            node.put("errmsg", result.getErrmsg());
        }
        if (StringUtils.hasText(result.getErrorMessage())) {
            node.put("errorMessage", result.getErrorMessage());
        }
        if (StringUtils.hasText(result.getJobId())) {
            node.put("jobId", result.getJobId());
        }
        if (result.getInvalidUser() != null) {
            node.set("invaliduser", objectMapper.valueToTree(result.getInvalidUser()));
        }
        if (result.getInvalidParty() != null) {
            node.set("invalidparty", objectMapper.valueToTree(result.getInvalidParty()));
        }
        return sanitize(node.toString());
    }

    private String truncateUtf8(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) {
                break;
            }
            result.append(character);
            bytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private boolean retryableHttpStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private String failureMessage(Exception ex) {
        String message = sanitize(ex.getMessage());
        return StringUtils.hasText(message) ? message : ex.getClass().getSimpleName();
    }

    private String sanitize(String value) {
        return StringUtils.hasText(value) ? AlertSensitiveTextSanitizer.sanitize(value) : value;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstText(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : fallback;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value.intValue() < 1 ? fallback : value.intValue();
    }

    private StudioException badRequest(String message) {
        return new StudioException(StudioErrorCode.BAD_REQUEST, message);
    }

    private StudioPlatformProperties.ElinkProperties settings() {
        return properties.getAlert().getElink();
    }

    public static final class SendResult {
        private final boolean success;
        private final boolean retryable;
        private final boolean skipped;
        private final Integer httpStatus;
        private final String errorMessage;
        private final String responseExcerpt;

        private SendResult(boolean success, boolean retryable, boolean skipped, Integer httpStatus,
                           String errorMessage, String responseExcerpt) {
            this.success = success;
            this.retryable = retryable;
            this.skipped = skipped;
            this.httpStatus = httpStatus;
            this.errorMessage = errorMessage;
            this.responseExcerpt = responseExcerpt;
        }

        public static SendResult success(Integer status, String excerpt) {
            return new SendResult(true, false, false, status, null, excerpt);
        }

        public static SendResult retry(Integer status, String error, String excerpt) {
            return new SendResult(false, true, false, status, error, excerpt);
        }

        public static SendResult dead(Integer status, String error, String excerpt) {
            return new SendResult(false, false, false, status, error, excerpt);
        }

        public static SendResult skipped(String error) {
            return new SendResult(false, false, true, null, error, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isRetryable() {
            return retryable;
        }

        public boolean isSkipped() {
            return skipped;
        }

        public Integer getHttpStatus() {
            return httpStatus;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getResponseExcerpt() {
            return responseExcerpt;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ElinkSendResponse {
        private Long id;
        private Boolean success;
        private Integer errcode;
        private String errmsg;
        private String errorMessage;
        private String jobId;
        private Object invalidUser;
        private Object invalidParty;

        private boolean succeeded() {
            if (errcode != null && errcode.intValue() != 0) {
                return false;
            }
            if (Boolean.FALSE.equals(success)) {
                return false;
            }
            return errcode != null || Boolean.TRUE.equals(success);
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Boolean getSuccess() {
            return success;
        }

        public void setSuccess(Boolean success) {
            this.success = success;
        }

        public Integer getErrcode() {
            return errcode;
        }

        public void setErrcode(Integer errcode) {
            this.errcode = errcode;
        }

        public String getErrmsg() {
            return errmsg;
        }

        public void setErrmsg(String errmsg) {
            this.errmsg = errmsg;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public String getJobId() {
            return jobId;
        }

        @JsonAlias("jobid")
        public void setJobId(String jobId) {
            this.jobId = jobId;
        }

        public Object getInvalidUser() {
            return invalidUser;
        }

        @JsonAlias("invaliduser")
        public void setInvalidUser(Object invalidUser) {
            this.invalidUser = invalidUser;
        }

        public Object getInvalidParty() {
            return invalidParty;
        }

        @JsonAlias("invalidparty")
        public void setInvalidParty(Object invalidParty) {
            this.invalidParty = invalidParty;
        }
    }
}
