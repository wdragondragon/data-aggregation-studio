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

@Service
public class ElinkAlertSender {

    private final AlertChannelService alertChannelService;
    private final ElinkManagerEndpointResolver endpointResolver;
    private final StudioPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ElinkAlertSender(AlertChannelService alertChannelService,
                            ElinkManagerEndpointResolver endpointResolver,
                            StudioPlatformProperties properties,
                            ObjectMapper objectMapper) {
        this.alertChannelService = alertChannelService;
        this.endpointResolver = endpointResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(positive(settings().getConnectTimeoutSeconds(), 3)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public SendResult send(AlertChannelEntity channel, Map<String, Object> payload) {
        return send(channel, payload, null, null);
    }

    public SendResult send(AlertChannelEntity channel, Map<String, Object> payload,
                           List<String> userIdsOverride) {
        return send(channel, payload, userIdsOverride, null);
    }

    public SendResult send(AlertChannelEntity channel, Map<String, Object> payload,
                           List<String> userIdsOverride, List<String> mobilesOverride) {
        if (!settings().isEnabled()) {
            return SendResult.skipped("eLink alert delivery is disabled");
        }
        try {
            boolean overridePersonalRecipients = (userIdsOverride != null && !userIdsOverride.isEmpty())
                    || (mobilesOverride != null && !mobilesOverride.isEmpty());
            String targetType = overridePersonalRecipients
                    ? "PERSONAL" : alertChannelService.elinkTargetType(channel);
            Long groupId = "GROUP".equals(targetType) ? alertChannelService.elinkGroupId(channel) : null;
            URI endpoint = endpoint(targetType, groupId);
            byte[] requestBody = objectMapper.writeValueAsBytes(requestBody(
                    targetType,
                    overridePersonalRecipients ? userIdsOverride : alertChannelService.elinkUserIds(channel),
                    overridePersonalRecipients ? mobilesOverride : null,
                    AlertDeliveryMessageRenderer.renderElinkText(payload)));
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
        if ("GROUP".equals(targetType)) {
            if (groupId == null || groupId.longValue() <= 0L) {
                throw badRequest("Stored eLink group id is invalid");
            }
            return endpointResolver.resolve("/groups/" + groupId + "/messages");
        } else if ("PERSONAL".equals(targetType)) {
            return endpointResolver.resolve("/messages");
        } else {
            throw badRequest("Stored eLink target type is invalid");
        }
    }

    private Map<String, Object> requestBody(String targetType, List<String> userIds,
                                            List<String> mobiles, String content) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("msgType", "text");
        body.put("content", content);
        if ("PERSONAL".equals(targetType)) {
            boolean hasUserIds = userIds != null && !userIds.isEmpty();
            boolean hasMobiles = mobiles != null && !mobiles.isEmpty();
            if (!hasUserIds && !hasMobiles) {
                throw badRequest("Stored eLink recipients are missing");
            }
            if (hasUserIds) {
                body.put("userIds", new ArrayList<String>(userIds));
            }
            if (hasMobiles) {
                body.put("mobiles", new ArrayList<String>(mobiles));
            }
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

    private String firstText(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : fallback;
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
