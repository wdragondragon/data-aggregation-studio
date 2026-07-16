package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.AlertChannelType;
import com.jdragon.studio.dto.enums.AlertDeliveryStatus;
import com.jdragon.studio.dto.model.AlertDeliveryView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.AlertDeliveryQueryRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import com.jdragon.studio.infra.entity.AlertDeliveryEntity;
import com.jdragon.studio.infra.entity.AlertEventEntity;
import com.jdragon.studio.infra.mapper.AlertDeliveryMapper;
import com.jdragon.studio.infra.mapper.AlertEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class AlertDeliveryService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long[] RETRY_MINUTES = new long[]{1L, 5L, 15L, 60L};

    private final AlertDeliveryMapper alertDeliveryMapper;
    private final AlertEventMapper alertEventMapper;
    private final AlertIncidentService alertIncidentService;
    private final AlertChannelService alertChannelService;
    private final AlertRuleService alertRuleService;
    private final NotificationService notificationService;
    private final AlertWebhookSecurityService webhookSecurityService;
    private final AlertWebhookHttpClient webhookHttpClient;
    private final ElinkAlertSender elinkAlertSender;
    private final StudioPlatformProperties properties;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AlertDeliveryService(AlertDeliveryMapper alertDeliveryMapper,
                                 AlertEventMapper alertEventMapper,
                                AlertIncidentService alertIncidentService,
                                AlertChannelService alertChannelService,
                                AlertRuleService alertRuleService,
                                NotificationService notificationService,
                                 AlertWebhookSecurityService webhookSecurityService,
                                 AlertWebhookHttpClient webhookHttpClient,
                                 ElinkAlertSender elinkAlertSender,
                                 StudioPlatformProperties properties,
                                StudioSecurityService securityService,
                                ProjectResourceAccessService projectResourceAccessService,
                                ObjectMapper objectMapper) {
        this.alertDeliveryMapper = alertDeliveryMapper;
        this.alertEventMapper = alertEventMapper;
        this.alertIncidentService = alertIncidentService;
        this.alertChannelService = alertChannelService;
        this.alertRuleService = alertRuleService;
        this.notificationService = notificationService;
        this.webhookSecurityService = webhookSecurityService;
        this.webhookHttpClient = webhookHttpClient;
        this.elinkAlertSender = elinkAlertSender;
        this.properties = properties;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.objectMapper = objectMapper;
    }

    AlertDeliveryService(AlertDeliveryMapper alertDeliveryMapper,
                         AlertEventMapper alertEventMapper,
                         AlertIncidentService alertIncidentService,
                         AlertChannelService alertChannelService,
                         AlertRuleService alertRuleService,
                         NotificationService notificationService,
                         AlertWebhookSecurityService webhookSecurityService,
                         AlertWebhookHttpClient webhookHttpClient,
                         StudioPlatformProperties properties,
                         StudioSecurityService securityService,
                         ProjectResourceAccessService projectResourceAccessService,
                         ObjectMapper objectMapper) {
        this(alertDeliveryMapper, alertEventMapper, alertIncidentService, alertChannelService, alertRuleService,
                notificationService, webhookSecurityService, webhookHttpClient, null, properties, securityService,
                projectResourceAccessService, objectMapper);
    }

    public PageView<AlertDeliveryView> query(AlertDeliveryQueryRequest request) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        int pageNo = pageNo(request == null ? null : request.getPageNo());
        int pageSize = pageSize(request == null ? null : request.getPageSize());
        LambdaQueryWrapper<AlertDeliveryEntity> query = new LambdaQueryWrapper<AlertDeliveryEntity>()
                .eq(AlertDeliveryEntity::getTenantId, securityService.currentTenantId())
                .eq(AlertDeliveryEntity::getProjectId, projectId)
                .eq(request != null && request.getIncidentId() != null, AlertDeliveryEntity::getIncidentId, request == null ? null : request.getIncidentId())
                .eq(request != null && request.getEventId() != null, AlertDeliveryEntity::getEventId, request == null ? null : request.getEventId())
                .eq(request != null && request.getChannelId() != null, AlertDeliveryEntity::getChannelId, request == null ? null : request.getChannelId())
                .eq(request != null && StringUtils.hasText(request.getChannelType()), AlertDeliveryEntity::getChannelType,
                        request == null ? null : upper(request.getChannelType()))
                .eq(request != null && StringUtils.hasText(request.getStatus()), AlertDeliveryEntity::getStatus,
                        request == null ? null : upper(request.getStatus()))
                .in(request != null && Boolean.TRUE.equals(request.getFailedOnly()), AlertDeliveryEntity::getStatus,
                        AlertDeliveryStatus.RETRY.name(), AlertDeliveryStatus.DEAD.name())
                .ge(request != null && request.getStartTime() != null, AlertDeliveryEntity::getCreatedAt, request == null ? null : request.getStartTime())
                .le(request != null && request.getEndTime() != null, AlertDeliveryEntity::getCreatedAt, request == null ? null : request.getEndTime());
        Long total = alertDeliveryMapper.selectCount(query);
        List<AlertDeliveryEntity> entities = alertDeliveryMapper.selectList(query.orderByDesc(AlertDeliveryEntity::getCreatedAt)
                .orderByDesc(AlertDeliveryEntity::getId)
                .last("limit " + ((pageNo - 1) * pageSize) + "," + pageSize));
        List<AlertDeliveryView> items = new ArrayList<AlertDeliveryView>();
        for (AlertDeliveryEntity entity : entities) {
            items.add(alertIncidentService.toDeliveryView(entity));
        }
        return PageView.of(pageNo, pageSize, total == null ? 0L : total.longValue(), items);
    }

    @Transactional
    public AlertDeliveryView retry(Long id) {
        alertRuleService.requireManage();
        AlertDeliveryEntity entity = requireCurrentProjectDelivery(id);
        if (!AlertDeliveryStatus.DEAD.name().equals(entity.getStatus())
                && !AlertDeliveryStatus.RETRY.name().equals(entity.getStatus())
                && !AlertDeliveryStatus.SKIPPED.name().equals(entity.getStatus())) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "Only failed or skipped deliveries can be retried");
        }
        if ((AlertChannelType.WEBHOOK.name().equals(entity.getChannelType())
                || AlertChannelType.ELINK.name().equals(entity.getChannelType()))
                && (entity.getChannelId() == null || alertChannelService.findById(
                entity.getChannelId(), entity.getTenantId(), entity.getProjectId()) == null)) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Alert channel is missing; this delivery cannot be retried");
        }
        entity.setStatus(AlertDeliveryStatus.PENDING.name());
        entity.setAttemptCount(0);
        entity.setNextAttemptAt(LocalDateTime.now());
        entity.setErrorMessage(null);
        entity.setResponseExcerpt(null);
        entity.setHttpStatus(null);
        entity.setUpdatedAt(LocalDateTime.now());
        int updated = alertDeliveryMapper.update(null, new LambdaUpdateWrapper<AlertDeliveryEntity>()
                .eq(AlertDeliveryEntity::getId, entity.getId())
                .in(AlertDeliveryEntity::getStatus, AlertDeliveryStatus.DEAD.name(),
                        AlertDeliveryStatus.RETRY.name(), AlertDeliveryStatus.SKIPPED.name())
                .set(AlertDeliveryEntity::getStatus, entity.getStatus())
                .set(AlertDeliveryEntity::getAttemptCount, entity.getAttemptCount())
                .set(AlertDeliveryEntity::getNextAttemptAt, entity.getNextAttemptAt())
                .set(AlertDeliveryEntity::getHttpStatus, null)
                .set(AlertDeliveryEntity::getResponseExcerpt, null)
                .set(AlertDeliveryEntity::getErrorMessage, null)
                .set(AlertDeliveryEntity::getUpdatedAt, entity.getUpdatedAt()));
        if (updated == 0) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Alert delivery changed concurrently; refresh and retry");
        }
        return alertIncidentService.toDeliveryView(entity);
    }

    public void dispatchDue() {
        if (properties.getAlert() == null || !properties.getAlert().isEnabled() || !properties.getAlert().isDeliveryEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int batchSize = properties.getAlert().getBatchSize() == null ? 100 : Math.max(1, properties.getAlert().getBatchSize());
        resetStaleProcessing(now.minusMinutes(5), batchSize);
        boolean webhookEnabled = webhookDeliveryEnabled();
        boolean elinkEnabled = elinkDeliveryEnabled();
        List<AlertDeliveryEntity> due = alertDeliveryMapper.selectList(new LambdaQueryWrapper<AlertDeliveryEntity>()
                .in(AlertDeliveryEntity::getStatus, AlertDeliveryStatus.PENDING.name(), AlertDeliveryStatus.RETRY.name())
                .ne(!webhookEnabled, AlertDeliveryEntity::getChannelType, AlertChannelType.WEBHOOK.name())
                .ne(!elinkEnabled, AlertDeliveryEntity::getChannelType, AlertChannelType.ELINK.name())
                .le(AlertDeliveryEntity::getNextAttemptAt, now)
                .orderByAsc(AlertDeliveryEntity::getNextAttemptAt)
                .orderByAsc(AlertDeliveryEntity::getId)
                .last("limit " + batchSize));
        for (AlertDeliveryEntity delivery : due) {
            try {
                dispatchOne(delivery.getId());
            } catch (Exception ex) {
                log.warn("Alert delivery {} failed unexpectedly; continuing the batch: {}: {}",
                        delivery.getId(), ex.getClass().getSimpleName(), sanitize(ex.getMessage()));
            }
        }
    }

    @Transactional
    public void cleanup() {
        if (properties.getAlert() == null || !properties.getAlert().isEnabled()) {
            return;
        }
        int deliveryDays = properties.getAlert().getDeliveryRetentionDays() == null ? 30 : properties.getAlert().getDeliveryRetentionDays();
        LocalDateTime deliveryCutoff = LocalDateTime.now().minusDays(Math.max(1, deliveryDays));
        alertDeliveryMapper.hardDeleteTerminalBefore(deliveryCutoff);
        int eventDays = properties.getAlert().getEventRetentionDays() == null ? 180 : properties.getAlert().getEventRetentionDays();
        alertEventMapper.hardDeleteBefore(LocalDateTime.now().minusDays(Math.max(1, eventDays)));
    }

    private void dispatchOne(Long id) {
        AlertDeliveryEntity delivery = alertDeliveryMapper.selectById(id);
        if (delivery == null || (!AlertDeliveryStatus.PENDING.name().equals(delivery.getStatus())
                && !AlertDeliveryStatus.RETRY.name().equals(delivery.getStatus()))) {
            return;
        }
        if (AlertChannelType.WEBHOOK.name().equals(delivery.getChannelType()) && !webhookDeliveryEnabled()) {
            return;
        }
        if (AlertChannelType.ELINK.name().equals(delivery.getChannelType()) && !elinkDeliveryEnabled()) {
            return;
        }
        if (safe(delivery.getAttemptCount()) >= MAX_ATTEMPTS) {
            markAttemptLimitExceeded(delivery);
            return;
        }
        LocalDateTime attemptAt = LocalDateTime.now();
        int attemptCount = safe(delivery.getAttemptCount()) + 1;
        int claimed = alertDeliveryMapper.update(null, new LambdaUpdateWrapper<AlertDeliveryEntity>()
                .eq(AlertDeliveryEntity::getId, delivery.getId())
                .in(AlertDeliveryEntity::getStatus, AlertDeliveryStatus.PENDING.name(), AlertDeliveryStatus.RETRY.name())
                .set(AlertDeliveryEntity::getStatus, AlertDeliveryStatus.PROCESSING.name())
                .set(AlertDeliveryEntity::getLastAttemptAt, attemptAt)
                .set(AlertDeliveryEntity::getAttemptCount, attemptCount)
                .set(AlertDeliveryEntity::getUpdatedAt, attemptAt));
        if (claimed == 0) {
            return;
        }
        delivery.setStatus(AlertDeliveryStatus.PROCESSING.name());
        delivery.setLastAttemptAt(attemptAt);
        delivery.setAttemptCount(attemptCount);
        delivery.setUpdatedAt(attemptAt);
        DeliveryResult result;
        try {
            if (AlertChannelType.IN_APP.name().equals(delivery.getChannelType())) {
                result = deliverInApp(delivery);
            } else if (AlertChannelType.WEBHOOK.name().equals(delivery.getChannelType())) {
                result = deliverWebhook(delivery);
            } else if (AlertChannelType.ELINK.name().equals(delivery.getChannelType())) {
                result = deliverElink(delivery);
            } else {
                result = DeliveryResult.dead(null, "Unsupported alert channel type");
            }
        } catch (StudioException ex) {
            result = (AlertChannelType.WEBHOOK.name().equals(delivery.getChannelType())
                    || AlertChannelType.ELINK.name().equals(delivery.getChannelType()))
                    ? DeliveryResult.dead(null, sanitize(ex.getMessage()))
                    : DeliveryResult.retry(null, sanitize(ex.getMessage()));
        } catch (JsonProcessingException ex) {
            result = DeliveryResult.dead(null, "Webhook payload could not be serialized");
        } catch (Exception ex) {
            result = DeliveryResult.retry(null, failureMessage(delivery, ex));
        }
        applyResult(delivery, result);
    }

    private DeliveryResult deliverInApp(AlertDeliveryEntity delivery) {
        if (delivery.getRecipientUserId() == null) {
            return DeliveryResult.dead(null, "In-app recipient is missing");
        }
        Map<String, Object> payload = delivery.getPayloadJson() == null
                ? new LinkedHashMap<String, Object>() : delivery.getPayloadJson();
        Map<String, Object> subject = map(payload.get("subject"));
        Map<String, Object> rule = map(payload.get("rule"));
        String eventType = string(payload.get("eventType"));
        String severity = string(rule.get("severity"));
        String title = "[" + fallback(severity, "INFO") + "] " + fallback(string(rule.get("name")), "Studio 告警");
        String content = fallback(string(payload.get("summary")), "告警状态发生变化");
        if (notificationService.notifyUsers(java.util.Collections.singletonList(delivery.getRecipientUserId()),
                new NotificationCommand()
                        .setCategory("ALERT")
                        .setTitle(title)
                        .setContent(content)
                        .setTargetType(fallback(string(subject.get("type")), "ALERT"))
                        .setTargetId(longValue(subject.get("id")).orElse(null))
                        .setTargetPath(fallback(string(subject.get("path")), "/alerts"))
                        .setTargetTenantId(delivery.getTenantId())
                        .setTargetProjectId(delivery.getProjectId())
                        .setDedupeKey("alert:" + delivery.getEventId() + ":" + delivery.getRecipientUserId())
                        .setPayloadJson(payload)).isEmpty()) {
            return DeliveryResult.skipped("In-app recipient is disabled or missing");
        }
        return DeliveryResult.success(null, null);
    }

    private DeliveryResult deliverWebhook(AlertDeliveryEntity delivery) throws Exception {
        AlertChannelEntity channel = alertChannelService.findById(
                delivery.getChannelId(), delivery.getTenantId(), delivery.getProjectId());
        if (channel == null) {
            return DeliveryResult.dead(null, "Webhook channel is missing");
        }
        boolean testDelivery = "TEST".equalsIgnoreCase(string(delivery.getPayloadJson() == null
                ? null : delivery.getPayloadJson().get("eventType")));
        if (!Integer.valueOf(1).equals(channel.getEnabled()) && !testDelivery) {
            return DeliveryResult.skipped("Webhook channel is disabled");
        }
        String endpoint = alertChannelService.endpoint(channel);
        AlertWebhookSecurityService.ValidatedWebhookTarget target = webhookSecurityService.validateAndResolve(endpoint);
        byte[] body = objectMapper.writeValueAsBytes(delivery.getPayloadJson());
        int connectTimeout = Math.max(1, properties.getAlert().getWebhook().getConnectTimeoutSeconds());
        int requestTimeout = Math.max(1, properties.getAlert().getWebhook().getRequestTimeoutSeconds());
        Map<String, String> configuredHeaders = alertChannelService.headers(channel);
        Map<String, String> headers = new LinkedHashMap<String, String>(configuredHeaders);
        headers.put("X-Studio-Event-Id", String.valueOf(delivery.getEventId()));
        String signingSecret = alertChannelService.signingSecret(channel);
        if (StringUtils.hasText(signingSecret)) {
            String timestamp = String.valueOf(currentEpochSecond());
            headers.put("X-Studio-Timestamp", timestamp);
            headers.put("X-Studio-Signature-SHA256", hmacHex(signingSecret,
                    timestamp + "." + new String(body, StandardCharsets.UTF_8)));
        }
        int maxBytes = Math.max(1024, properties.getAlert().getWebhook().getMaxResponseBytes());
        AlertWebhookHttpClient.Response response = webhookHttpClient.post(target, headers, body,
                connectTimeout, requestTimeout, maxBytes);
        String excerpt = redactWebhookResponse(response.bodyAsText(), endpoint, headers);
        excerpt = redactExact(excerpt, signingSecret);
        excerpt = sanitize(excerpt);
        int status = response.getStatusCode();
        if (status >= 200 && status < 300) {
            return DeliveryResult.success(Integer.valueOf(status), excerpt);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return DeliveryResult.retry(Integer.valueOf(status), "Webhook returned HTTP " + status, excerpt);
        }
        return DeliveryResult.dead(Integer.valueOf(status), "Webhook returned HTTP " + status, excerpt);
    }

    private DeliveryResult deliverElink(AlertDeliveryEntity delivery) {
        if (elinkAlertSender == null) {
            return DeliveryResult.dead(null, "eLink delivery adapter is unavailable");
        }
        AlertChannelEntity channel = alertChannelService.findById(
                delivery.getChannelId(), delivery.getTenantId(), delivery.getProjectId());
        if (channel == null) {
            return DeliveryResult.dead(null, "eLink channel is missing");
        }
        boolean testDelivery = "TEST".equalsIgnoreCase(string(delivery.getPayloadJson() == null
                ? null : delivery.getPayloadJson().get("eventType")));
        if (!Integer.valueOf(1).equals(channel.getEnabled()) && !testDelivery) {
            return DeliveryResult.skipped("eLink channel is disabled");
        }
        ElinkAlertSender.SendResult result = elinkAlertSender.send(channel,
                delivery.getPayloadJson() == null ? new LinkedHashMap<String, Object>() : delivery.getPayloadJson());
        if (result.isSkipped()) {
            return DeliveryResult.skipped(result.getErrorMessage());
        }
        if (result.isSuccess()) {
            return DeliveryResult.success(result.getHttpStatus(), result.getResponseExcerpt());
        }
        return result.isRetryable()
                ? DeliveryResult.retry(result.getHttpStatus(), result.getErrorMessage(), result.getResponseExcerpt())
                : DeliveryResult.dead(result.getHttpStatus(), result.getErrorMessage(), result.getResponseExcerpt());
    }

    private void applyResult(AlertDeliveryEntity delivery, DeliveryResult result) {
        LocalDateTime updatedAt = LocalDateTime.now();
        delivery.setHttpStatus(result.httpStatus);
        delivery.setResponseExcerpt(truncate(result.responseExcerpt, 2000));
        delivery.setErrorMessage(truncate(result.errorMessage, 1000));
        if (result.skipped) {
            delivery.setStatus(AlertDeliveryStatus.SKIPPED.name());
            delivery.setNextAttemptAt(null);
        } else if (result.success) {
            delivery.setStatus(AlertDeliveryStatus.SUCCEEDED.name());
            delivery.setNextAttemptAt(null);
        } else if (result.retryable && safe(delivery.getAttemptCount()) < MAX_ATTEMPTS) {
            delivery.setStatus(AlertDeliveryStatus.RETRY.name());
            int delayIndex = Math.min(Math.max(0, safe(delivery.getAttemptCount()) - 1), RETRY_MINUTES.length - 1);
            delivery.setNextAttemptAt(LocalDateTime.now().plusMinutes(RETRY_MINUTES[delayIndex]));
        } else {
            delivery.setStatus(AlertDeliveryStatus.DEAD.name());
            delivery.setNextAttemptAt(null);
        }
        delivery.setUpdatedAt(updatedAt);
        int updated = alertDeliveryMapper.update(null, new LambdaUpdateWrapper<AlertDeliveryEntity>()
                .eq(AlertDeliveryEntity::getId, delivery.getId())
                .eq(AlertDeliveryEntity::getStatus, AlertDeliveryStatus.PROCESSING.name())
                .eq(AlertDeliveryEntity::getAttemptCount, delivery.getAttemptCount())
                .set(AlertDeliveryEntity::getStatus, delivery.getStatus())
                .set(AlertDeliveryEntity::getAttemptCount, delivery.getAttemptCount())
                .set(AlertDeliveryEntity::getNextAttemptAt, delivery.getNextAttemptAt())
                .set(AlertDeliveryEntity::getLastAttemptAt, delivery.getLastAttemptAt())
                .set(AlertDeliveryEntity::getHttpStatus, delivery.getHttpStatus())
                .set(AlertDeliveryEntity::getResponseExcerpt, delivery.getResponseExcerpt())
                .set(AlertDeliveryEntity::getErrorMessage, delivery.getErrorMessage())
                .set(AlertDeliveryEntity::getUpdatedAt, updatedAt));
        if (updated == 0) {
            return;
        }
        AlertEventEntity event = alertEventMapper.selectById(delivery.getEventId());
        if (event != null && "TEST".equals(event.getEventType()) && delivery.getChannelId() != null) {
            String channelName = AlertChannelType.ELINK.name().equals(delivery.getChannelType())
                    ? "eLink" : "Webhook";
            alertChannelService.markTestResult(delivery.getChannelId(), delivery.getStatus(),
                    result.success ? channelName + " test succeeded" : delivery.getErrorMessage());
        }
    }

    private void resetStaleProcessing(LocalDateTime cutoff, int batchSize) {
        List<AlertDeliveryEntity> stale = alertDeliveryMapper.selectList(new LambdaQueryWrapper<AlertDeliveryEntity>()
                .eq(AlertDeliveryEntity::getStatus, AlertDeliveryStatus.PROCESSING.name())
                .lt(AlertDeliveryEntity::getLastAttemptAt, cutoff)
                .orderByAsc(AlertDeliveryEntity::getLastAttemptAt)
                .orderByAsc(AlertDeliveryEntity::getId)
                .last("limit " + Math.max(1, batchSize)));
        for (AlertDeliveryEntity delivery : stale) {
            try {
                LocalDateTime recoveredAt = LocalDateTime.now();
                boolean attemptsExhausted = safe(delivery.getAttemptCount()) >= MAX_ATTEMPTS;
                delivery.setStatus(attemptsExhausted ? AlertDeliveryStatus.DEAD.name() : AlertDeliveryStatus.RETRY.name());
                delivery.setNextAttemptAt(attemptsExhausted ? null : recoveredAt);
                delivery.setErrorMessage(attemptsExhausted
                        ? "Delivery attempt limit reached while recovering stale processing state"
                        : "Recovered stale delivery processing state");
                delivery.setUpdatedAt(recoveredAt);
                alertDeliveryMapper.update(null, new LambdaUpdateWrapper<AlertDeliveryEntity>()
                        .eq(AlertDeliveryEntity::getId, delivery.getId())
                        .eq(AlertDeliveryEntity::getStatus, AlertDeliveryStatus.PROCESSING.name())
                        .le(AlertDeliveryEntity::getLastAttemptAt, cutoff)
                        .set(AlertDeliveryEntity::getStatus, delivery.getStatus())
                        .set(AlertDeliveryEntity::getNextAttemptAt, delivery.getNextAttemptAt())
                        .set(AlertDeliveryEntity::getErrorMessage, delivery.getErrorMessage())
                        .set(AlertDeliveryEntity::getUpdatedAt, recoveredAt));
            } catch (Exception ex) {
                log.warn("Stale alert delivery {} could not be recovered; continuing the batch: {}: {}",
                        delivery.getId(), ex.getClass().getSimpleName(), sanitize(ex.getMessage()));
            }
        }
    }

    private boolean webhookDeliveryEnabled() {
        return properties.getAlert() != null && properties.getAlert().getWebhook() != null
                && properties.getAlert().getWebhook().isEnabled();
    }

    private boolean elinkDeliveryEnabled() {
        return properties.getAlert() != null && properties.getAlert().getElink() != null
                && properties.getAlert().getElink().isEnabled();
    }

    private void markAttemptLimitExceeded(AlertDeliveryEntity delivery) {
        LocalDateTime updatedAt = LocalDateTime.now();
        delivery.setStatus(AlertDeliveryStatus.DEAD.name());
        delivery.setNextAttemptAt(null);
        delivery.setErrorMessage("Delivery attempt limit reached");
        delivery.setUpdatedAt(updatedAt);
        alertDeliveryMapper.update(null, new LambdaUpdateWrapper<AlertDeliveryEntity>()
                .eq(AlertDeliveryEntity::getId, delivery.getId())
                .in(AlertDeliveryEntity::getStatus, AlertDeliveryStatus.PENDING.name(), AlertDeliveryStatus.RETRY.name())
                .eq(AlertDeliveryEntity::getAttemptCount, delivery.getAttemptCount())
                .set(AlertDeliveryEntity::getStatus, AlertDeliveryStatus.DEAD.name())
                .set(AlertDeliveryEntity::getNextAttemptAt, null)
                .set(AlertDeliveryEntity::getErrorMessage, delivery.getErrorMessage())
                .set(AlertDeliveryEntity::getUpdatedAt, updatedAt));
    }

    private AlertDeliveryEntity requireCurrentProjectDelivery(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Alert delivery id is required");
        }
        AlertDeliveryEntity entity = alertDeliveryMapper.selectOne(new LambdaQueryWrapper<AlertDeliveryEntity>()
                .eq(AlertDeliveryEntity::getId, id)
                .eq(AlertDeliveryEntity::getTenantId, securityService.currentTenantId())
                .eq(AlertDeliveryEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId())
                .last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Alert delivery was not found");
        }
        return entity;
    }

    private String hmacHex(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }

    static long currentEpochSecond() {
        return Instant.now().getEpochSecond();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Optional<Long> longValue(Object value) {
        if (value instanceof Number) {
            return Optional.of(((Number) value).longValue());
        }
        try {
            return value == null ? Optional.empty() : Optional.of(Long.valueOf(String.valueOf(value)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String fallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return NotificationTextSanitizer.sanitize(AlertSensitiveTextSanitizer.sanitize(value));
    }

    private String failureMessage(AlertDeliveryEntity delivery, Exception ex) {
        if (delivery != null && AlertChannelType.WEBHOOK.name().equals(delivery.getChannelType())) {
            return "Webhook request failed (" + ex.getClass().getSimpleName() + ")";
        }
        if (delivery != null && AlertChannelType.ELINK.name().equals(delivery.getChannelType())) {
            return sanitize(ex.getMessage());
        }
        return sanitize(ex.getMessage());
    }

    private String redactExact(String value, String sensitiveValue) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(sensitiveValue)) {
            return value;
        }
        return value.replace(sensitiveValue, "******");
    }

    private String redactWebhookResponse(String value, String endpoint, Map<String, String> configuredHeaders) {
        String redacted = redactExact(value, endpoint);
        if (configuredHeaders != null) {
            for (String headerValue : configuredHeaders.values()) {
                redacted = redactExact(redacted, headerValue);
            }
        }
        if (!StringUtils.hasText(redacted) || !StringUtils.hasText(endpoint)) {
            return redacted;
        }
        try {
            URI uri = URI.create(endpoint);
            redacted = redactComponent(redacted, uri.getRawPath());
            redacted = redactComponent(redacted, uri.getPath());
            redacted = redactComponent(redacted, uri.getRawQuery());
            redacted = redactComponent(redacted, uri.getQuery());
            redacted = redactPathSegments(redacted, uri.getRawPath());
            String rawQuery = uri.getRawQuery();
            if (StringUtils.hasText(rawQuery)) {
                for (String pair : rawQuery.split("&")) {
                    redacted = redactComponent(redacted, pair);
                    int separator = pair.indexOf('=');
                    if (separator >= 0 && separator + 1 < pair.length()) {
                        String rawValue = pair.substring(separator + 1);
                        redacted = redactComponent(redacted, rawValue);
                        redacted = redactComponent(redacted, decodeUrlComponent(rawValue));
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            return redacted;
        }
        return redacted;
    }

    private String redactPathSegments(String value, String rawPath) {
        String redacted = value;
        if (!StringUtils.hasText(rawPath)) {
            return redacted;
        }
        for (String segment : rawPath.split("/")) {
            redacted = redactComponent(redacted, segment);
            redacted = redactComponent(redacted, decodeUrlComponent(segment));
        }
        return redacted;
    }

    private String redactComponent(String value, String component) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(component)) {
            return value;
        }
        return value.replace(component, "******");
    }

    private String decodeUrlComponent(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private int safe(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    private int pageNo(Integer value) {
        return value == null || value.intValue() < 1 ? 1 : value.intValue();
    }

    private int pageSize(Integer value) {
        return Math.min(value == null || value.intValue() < 1 ? 20 : value.intValue(), 100);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static final class DeliveryResult {
        private final boolean success;
        private final boolean retryable;
        private final boolean skipped;
        private final Integer httpStatus;
        private final String errorMessage;
        private final String responseExcerpt;

        private DeliveryResult(boolean success, boolean retryable, boolean skipped, Integer httpStatus,
                               String errorMessage, String responseExcerpt) {
            this.success = success;
            this.retryable = retryable;
            this.skipped = skipped;
            this.httpStatus = httpStatus;
            this.errorMessage = errorMessage;
            this.responseExcerpt = responseExcerpt;
        }

        private static DeliveryResult success(Integer status, String excerpt) {
            return new DeliveryResult(true, false, false, status, null, excerpt);
        }

        private static DeliveryResult retry(Integer status, String error) {
            return retry(status, error, null);
        }

        private static DeliveryResult retry(Integer status, String error, String excerpt) {
            return new DeliveryResult(false, true, false, status, error, excerpt);
        }

        private static DeliveryResult dead(Integer status, String error) {
            return dead(status, error, null);
        }

        private static DeliveryResult dead(Integer status, String error, String excerpt) {
            return new DeliveryResult(false, false, false, status, error, excerpt);
        }

        private static DeliveryResult skipped(String error) {
            return new DeliveryResult(false, false, true, null, error, null);
        }
    }
}
