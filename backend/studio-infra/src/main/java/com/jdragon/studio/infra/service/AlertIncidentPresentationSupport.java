package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.model.AlertDeliveryView;
import com.jdragon.studio.dto.model.AlertEventView;
import com.jdragon.studio.dto.model.AlertIncidentView;
import com.jdragon.studio.infra.entity.AlertDeliveryEntity;
import com.jdragon.studio.infra.entity.AlertEventEntity;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.mapper.AlertDeliveryMapper;
import com.jdragon.studio.infra.mapper.AlertEventMapper;
import org.springframework.util.StringUtils;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AlertIncidentPresentationSupport {

    private final AlertEventMapper alertEventMapper;
    private final AlertDeliveryMapper alertDeliveryMapper;

    AlertIncidentPresentationSupport(AlertEventMapper alertEventMapper, AlertDeliveryMapper alertDeliveryMapper) {
        this.alertEventMapper = alertEventMapper;
        this.alertDeliveryMapper = alertDeliveryMapper;
    }

    AlertIncidentView toView(AlertIncidentEntity entity, boolean detail) {
        AlertIncidentView view = new AlertIncidentView();
        view.setId(entity.getId());
        view.setRuleId(entity.getRuleId());
        view.setRuleName(entity.getRuleNameSnapshot());
        view.setRuleType(entity.getRuleType());
        view.setSubjectType(entity.getSubjectType());
        view.setSubjectKey(entity.getSubjectKey());
        view.setSubjectId(entity.getSubjectId());
        view.setSubjectName(entity.getSubjectNameSnapshot());
        view.setTargetPath(entity.getTargetPath());
        view.setSeverity(entity.getSeverity());
        view.setStatus(entity.getStatus());
        view.setSummary(entity.getSummary());
        view.setEvidence(entity.getCurrentEvidenceJson());
        view.setOccurrenceCount(entity.getOccurrenceCount());
        view.setNotificationCount(entity.getNotificationCount());
        view.setReopenCount(entity.getReopenCount());
        view.setConditionActive(Integer.valueOf(1).equals(entity.getConditionActive()));
        view.setClosedWhileActive(Integer.valueOf(1).equals(entity.getClosedWhileActive()));
        view.setFirstTriggeredAt(entity.getFirstTriggeredAt());
        view.setLastTriggeredAt(entity.getLastTriggeredAt());
        view.setLastNotifiedAt(entity.getLastNotifiedAt());
        view.setAcknowledgedAt(entity.getAcknowledgedAt());
        view.setRecoveredAt(entity.getRecoveredAt());
        view.setClosedAt(entity.getClosedAt());
        view.setAcknowledgedBy(entity.getAcknowledgedBy());
        view.setClosedBy(entity.getClosedBy());
        if (detail) {
            List<AlertEventEntity> events = alertEventMapper.selectList(new LambdaQueryWrapper<AlertEventEntity>()
                    .eq(AlertEventEntity::getIncidentId, entity.getId())
                    .orderByDesc(AlertEventEntity::getObservedAt)
                    .orderByDesc(AlertEventEntity::getId).last("limit 100"));
            for (AlertEventEntity event : events) {
                view.getRecentEvents().add(toEventView(event));
            }
            List<AlertDeliveryEntity> deliveries = alertDeliveryMapper.selectList(new LambdaQueryWrapper<AlertDeliveryEntity>()
                    .eq(AlertDeliveryEntity::getIncidentId, entity.getId())
                    .orderByDesc(AlertDeliveryEntity::getCreatedAt)
                    .orderByDesc(AlertDeliveryEntity::getId).last("limit 100"));
            for (AlertDeliveryEntity delivery : deliveries) {
                view.getRecentDeliveries().add(toDeliveryView(delivery));
            }
        }
        return view;
    }

    AlertEventView toEventView(AlertEventEntity entity) {
        AlertEventView view = new AlertEventView();
        view.setId(entity.getId());
        view.setIncidentId(entity.getIncidentId());
        view.setRuleId(entity.getRuleId());
        view.setEventType(entity.getEventType());
        view.setStatusFrom(entity.getStatusFrom());
        view.setStatusTo(entity.getStatusTo());
        view.setSourceType(entity.getSourceType());
        view.setSourceId(entity.getSourceId());
        view.setSubjectType(entity.getSubjectType());
        view.setSubjectKey(entity.getSubjectKey());
        view.setSubjectId(entity.getSubjectId());
        view.setSubjectName(entity.getSubjectNameSnapshot());
        view.setTargetPath(entity.getTargetPath());
        view.setSeverity(entity.getSeverity());
        view.setSummary(entity.getSummary());
        view.setEvidence(entity.getEvidenceJson());
        view.setActorUserId(entity.getActorUserId());
        view.setActorName(entity.getActorNameSnapshot());
        view.setObservedAt(entity.getObservedAt());
        view.setCreatedAt(entity.getCreatedAt());
        return view;
    }

    AlertDeliveryView toDeliveryView(AlertDeliveryEntity entity) {
        AlertDeliveryView view = new AlertDeliveryView();
        view.setId(entity.getId());
        view.setEventId(entity.getEventId());
        view.setIncidentId(entity.getIncidentId());
        view.setChannelType(entity.getChannelType());
        view.setChannelId(entity.getChannelId());
        view.setChannelName(entity.getChannelNameSnapshot());
        view.setRecipientUserId(entity.getRecipientUserId());
        view.setStatus(entity.getStatus());
        view.setAttemptCount(entity.getAttemptCount());
        view.setNextAttemptAt(entity.getNextAttemptAt());
        view.setLastAttemptAt(entity.getLastAttemptAt());
        view.setHttpStatus(entity.getHttpStatus());
        view.setResponseExcerpt(entity.getResponseExcerpt());
        view.setErrorMessage(entity.getErrorMessage());
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        return view;
    }

    Map<String, Object> webhookPayload(AlertRuleEntity rule, AlertIncidentEntity incident, AlertEventEntity event) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "studio.alert.webhook.v1");
        result.put("eventId", stringId(event.getId()));
        result.put("eventType", event.getEventType());
        result.put("occurredAt", event.getObservedAt() == null ? null : event.getObservedAt().toString());
        result.put("tenantId", event.getTenantId());
        result.put("projectId", stringId(event.getProjectId()));
        Map<String, Object> rulePayload = new LinkedHashMap<String, Object>();
        rulePayload.put("id", rule == null ? null : stringId(rule.getId()));
        rulePayload.put("name", rule == null ? "Webhook channel test" : rule.getName());
        rulePayload.put("type", rule == null ? "TEST" : rule.getRuleType());
        rulePayload.put("severity", event.getSeverity());
        result.put("rule", rulePayload);
        Map<String, Object> incidentPayload = new LinkedHashMap<String, Object>();
        incidentPayload.put("id", incident == null ? null : stringId(incident.getId()));
        incidentPayload.put("status", incident == null ? null : incident.getStatus());
        incidentPayload.put("firstTriggeredAt", incident == null || incident.getFirstTriggeredAt() == null
                ? null : incident.getFirstTriggeredAt().toString());
        incidentPayload.put("lastTriggeredAt", incident == null || incident.getLastTriggeredAt() == null
                ? null : incident.getLastTriggeredAt().toString());
        incidentPayload.put("recoveredAt", incident == null || incident.getRecoveredAt() == null
                ? null : incident.getRecoveredAt().toString());
        incidentPayload.put("occurrenceCount", incident == null ? 0 : incident.getOccurrenceCount());
        result.put("incident", incidentPayload);
        Map<String, Object> subject = new LinkedHashMap<String, Object>();
        subject.put("type", event.getSubjectType());
        subject.put("id", stringId(event.getSubjectId()));
        subject.put("key", event.getSubjectKey());
        subject.put("name", event.getSubjectNameSnapshot());
        subject.put("path", event.getTargetPath());
        result.put("subject", subject);
        result.put("summary", event.getSummary());
        result.put("evidence", webhookEvidence(event.getEvidenceJson()));
        return result;
    }

    Map<String, Object> sanitizeEvidence(Map<String, Object> evidence) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (evidence == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : evidence.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            result.put(entry.getKey(), sanitizeEvidenceValue(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private Map<String, Object> webhookEvidence(Map<String, Object> evidence) {
        Map<String, Object> sanitized = sanitizeEvidence(evidence);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : sanitized.entrySet()) {
            result.put(entry.getKey(), webhookEvidenceValue(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private Object webhookEvidenceValue(String key, Object value) {
        if (value instanceof Number && isIdentifierKey(key)) {
            return String.valueOf(((Number) value).longValue());
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> nested = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                if (item.getKey() != null) {
                    String nestedKey = String.valueOf(item.getKey());
                    nested.put(nestedKey, webhookEvidenceValue(nestedKey, item.getValue()));
                }
            }
            return nested;
        }
        if (value instanceof Iterable<?>) {
            List<Object> nested = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                nested.add(webhookEvidenceValue(key, item));
            }
            return nested;
        }
        return value;
    }

    private boolean isIdentifierKey(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        String normalized = key.trim();
        String lower = normalized.toLowerCase();
        return "id".equals(lower) || normalized.endsWith("Id") || normalized.endsWith("Ids")
                || lower.endsWith("_id") || lower.endsWith("_ids")
                || lower.endsWith("-id") || lower.endsWith("-ids");
    }

    private Object sanitizeEvidenceValue(String key, Object value) {
        if (isSensitiveEvidenceKey(key)) {
            return "******";
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> nested = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                if (item.getKey() != null) {
                    String nestedKey = String.valueOf(item.getKey());
                    nested.put(nestedKey, sanitizeEvidenceValue(nestedKey, item.getValue()));
                }
            }
            return nested;
        }
        if (value instanceof Iterable<?>) {
            List<Object> nested = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                nested.add(sanitizeEvidenceValue(key, item));
            }
            return nested;
        }
        if (value instanceof String) {
            return sanitizeText((String) value, 2000);
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof Enum<?>) {
            return ((Enum<?>) value).name();
        }
        return value;
    }

    private boolean isSensitiveEvidenceKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase().replaceAll("[^a-z0-9]", "");
        return normalized.contains("password") || normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("authorization") || normalized.contains("cookie") || normalized.contains("body")
                || normalized.contains("header") || normalized.contains("accesskey") || normalized.contains("privatekey")
                || normalized.contains("apikey") || normalized.contains("credential");
    }

    String sanitizeText(String value, int max) {
        String sanitized = NotificationTextSanitizer.sanitize(AlertSensitiveTextSanitizer.sanitize(value));
        if (sanitized == null) {
            return null;
        }
        return truncate(sanitized, max);
    }

    private String stringId(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    static String targetPath(String subjectType, Long subjectId, Long sourceId) {
        if ("COLLECTION_TASK".equals(subjectType)) {
            if (subjectId == null) {
                return "/collection-task-runs";
            }
            return "/collection-task-runs?collectionTaskId=" + subjectId
                    + (sourceId == null ? "" : "&runRecordId=" + sourceId);
        }
        if ("QUALITY_TASK".equals(subjectType)) {
            if (subjectId == null) {
                return "/quality-task-runs";
            }
            return "/quality-task-runs?qualityTaskId=" + subjectId
                    + (sourceId == null ? "" : "&runRecordId=" + sourceId);
        }
        if ("WORKFLOW".equals(subjectType)) {
            if (subjectId == null) {
                return "/workflows";
            }
            return sourceId == null ? "/workflows/" + subjectId : "/runs/" + sourceId;
        }
        if ("DATA_SERVICE".equals(subjectType)) {
            return subjectId == null ? "/data-service-metrics/access-logs"
                    : "/data-service-metrics/access-logs?serviceId=" + subjectId;
        }
        if ("DATA_INGESTION_SERVICE".equals(subjectType)) {
            return subjectId == null ? "/data-ingestion-metrics/access-logs"
                    : "/data-ingestion-metrics/access-logs?serviceId=" + subjectId;
        }
        if ("PROTOCOL_CONVERSION_SERVICE".equals(subjectType)) {
            return subjectId == null ? "/protocol-conversions/access-logs"
                    : "/protocol-conversions/access-logs?serviceId=" + subjectId;
        }
        if ("WORKER_GROUP".equals(subjectType)) {
            return "/ops-center?section=workers";
        }
        if ("PROJECT_QUEUE".equals(subjectType)) {
            return "/ops-center?section=queue";
        }
        if ("LOG_STORAGE".equals(subjectType)) {
            return "/ops-center?section=logs";
        }
        return "/alerts";
    }
}
