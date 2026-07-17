package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.AlertDeliveryView;
import com.jdragon.studio.infra.entity.AlertDeliveryEntity;
import com.jdragon.studio.infra.mapper.AlertDeliveryMapper;
import com.jdragon.studio.infra.mapper.AlertEventMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AlertDeliveryPresentationSupportTest {

    private final AlertIncidentPresentationSupport support = new AlertIncidentPresentationSupport(
            mock(AlertEventMapper.class), mock(AlertDeliveryMapper.class));

    @Test
    void shouldExplainInAppDeliveryFromImmutablePayloadSnapshot() {
        Map<String, Object> payload = payload();
        Map<String, Object> auditedPayload = AlertDeliveryMessageRenderer.withDeliveryAudit(payload, "IN_APP");
        AlertDeliveryMessageRenderer.setRecipientDisplay(auditedPayload, "Studio Admin");
        AlertDeliveryEntity entity = delivery("IN_APP", auditedPayload);
        entity.setRecipientUserId(42L);

        AlertDeliveryView view = support.toDeliveryView(entity);

        assertEquals(10L, view.getRuleId());
        assertEquals("P0-02 Worker 离线验收", view.getRuleName());
        assertEquals("WORKER_OFFLINE", view.getRuleType());
        assertEquals("WARNING", view.getSeverity());
        assertEquals("TRIGGERED", view.getEventType());
        assertEquals(LocalDateTime.of(2026, 7, 17, 15, 5, 17), view.getOccurredAt());
        assertEquals("WORKER_GROUP", view.getSubjectType());
        assertEquals(30L, view.getSubjectId());
        assertEquals("studio-online-worker-01", view.getSubjectName());
        assertEquals("/ops-center?section=workers", view.getTargetPath());
        assertEquals("Worker 组连续离线超过 30 秒", view.getSummary());
        assertEquals("Studio Admin", view.getRecipientDisplay());
        assertEquals("TEXT", view.getMessageFormat());
        assertEquals("[WARNING] P0-02 Worker 离线验收", view.getMessageTitle());
        assertEquals("Worker 组连续离线超过 30 秒", view.getMessageContent());
    }

    @Test
    void shouldUseTheSameElinkRendererAndNeverExposeInternalTargetId() {
        Map<String, Object> payload = AlertDeliveryMessageRenderer.withDeliveryAudit(payload(), "ELINK");
        payload.put("_elinkTargetUserId", "private-elink-user-id");
        AlertDeliveryEntity entity = delivery("ELINK", payload);
        entity.setRecipientUserId(42L);

        AlertDeliveryView view = support.toDeliveryView(entity);

        assertEquals("TEXT", view.getMessageFormat());
        assertNull(view.getMessageTitle());
        assertEquals(AlertDeliveryMessageRenderer.renderElinkText(payload), view.getMessageContent());
        assertEquals("规则接收人（eLink 账号）", view.getRecipientDisplay());
        assertFalse(view.getRecipientDisplay().contains("private-elink-user-id"));
        assertFalse(view.getMessageContent().contains("_elinkTargetUserId"));
    }

    @Test
    void shouldFormatOnlyThePublicWebhookEnvelopeAndSanitizeSecrets() {
        Map<String, Object> payload = AlertDeliveryMessageRenderer.withDeliveryAudit(payload(), "WEBHOOK");
        payload.put("_elinkTargetMobile", "13800000009");
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) payload.get("evidence");
        evidence.put("accessToken", "plain-secret-token");
        AlertDeliveryEntity entity = delivery("WEBHOOK", payload);
        entity.setChannelNameSnapshot("生产 Webhook");

        AlertDeliveryView view = support.toDeliveryView(entity);

        assertEquals("JSON", view.getMessageFormat());
        assertTrue(view.getMessageContent().contains("\n"));
        assertTrue(view.getMessageContent().contains("\"schemaVersion\" : \"studio.alert.webhook.v1\""));
        assertTrue(view.getMessageContent().contains("\"accessToken\" : \"******\""));
        assertFalse(view.getMessageContent().contains("plain-secret-token"));
        assertFalse(view.getMessageContent().contains("_deliveryAudit"));
        assertFalse(view.getMessageContent().contains("_elinkTargetMobile"));
        assertFalse(view.getMessageContent().contains("13800000009"));
        assertEquals("Webhook 通道：生产 Webhook", view.getRecipientDisplay());
    }

    @Test
    void shouldMarkHistoricalFixedElinkTargetAsNotSnapshotted() {
        AlertDeliveryEntity entity = delivery("ELINK", payload());
        entity.setChannelNameSnapshot("P0-02 eLink 固定账号");

        AlertDeliveryView view = support.toDeliveryView(entity);

        assertEquals("固定通道：P0-02 eLink 固定账号（历史目标未快照）", view.getRecipientDisplay());
        assertEquals(AlertDeliveryMessageRenderer.renderElinkText(payload()), view.getMessageContent());
    }

    private AlertDeliveryEntity delivery(String channelType, Map<String, Object> payload) {
        AlertDeliveryEntity entity = new AlertDeliveryEntity();
        entity.setId(100L);
        entity.setEventId(200L);
        entity.setIncidentId(300L);
        entity.setChannelType(channelType);
        entity.setChannelId(400L);
        entity.setStatus("SUCCEEDED");
        entity.setPayloadJson(payload);
        return entity;
    }

    private Map<String, Object> payload() {
        Map<String, Object> rule = new LinkedHashMap<String, Object>();
        rule.put("id", "10");
        rule.put("name", "P0-02 Worker 离线验收");
        rule.put("type", "WORKER_OFFLINE");
        rule.put("severity", "WARNING");
        Map<String, Object> subject = new LinkedHashMap<String, Object>();
        subject.put("type", "WORKER_GROUP");
        subject.put("id", "30");
        subject.put("name", "studio-online-worker-01");
        subject.put("path", "/ops-center?section=workers");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "studio.alert.webhook.v1");
        result.put("eventId", "200");
        result.put("eventType", "TRIGGERED");
        result.put("occurredAt", "2026-07-17T15:05:17");
        result.put("rule", rule);
        result.put("subject", subject);
        result.put("summary", "Worker 组连续离线超过 30 秒");
        result.put("evidence", new LinkedHashMap<String, Object>(Map.of("offlineSeconds", 31)));
        return result;
    }
}
