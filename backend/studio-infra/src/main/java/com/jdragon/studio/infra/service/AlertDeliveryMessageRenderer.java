package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AlertDeliveryMessageRenderer {

    static final String FORMAT_TEXT = "TEXT";
    static final String FORMAT_JSON = "JSON";
    static final String DELIVERY_AUDIT_KEY = "_deliveryAudit";

    private static final int MAX_ELINK_TEXT_BYTES = 2048;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private AlertDeliveryMessageRenderer() {
    }

    static RenderedMessage renderInApp(Map<String, Object> payload) {
        RenderedMessage audited = auditedMessage(payload, "IN_APP");
        return audited == null ? renderInAppEnvelope(payload) : audited;
    }

    private static RenderedMessage renderInAppEnvelope(Map<String, Object> payload) {
        Map<String, Object> rule = nestedMap(payload, "rule");
        String severity = text(rule.get("severity"));
        String title = "[" + fallback(severity, "INFO") + "] "
                + fallback(text(rule.get("name")), "Studio 告警");
        String content = fallback(text(payload == null ? null : payload.get("summary")), "告警状态发生变化");
        return new RenderedMessage(FORMAT_TEXT, title, content);
    }

    static String renderElinkText(Map<String, Object> payload) {
        RenderedMessage audited = auditedMessage(payload, "ELINK");
        if (audited != null && StringUtils.hasText(audited.getContent())) {
            return audited.getContent();
        }
        return renderElinkEnvelope(payload);
    }

    private static String renderElinkEnvelope(Map<String, Object> payload) {
        Map<String, Object> rule = nestedMap(payload, "rule");
        Map<String, Object> subject = nestedMap(payload, "subject");
        List<String> lines = new ArrayList<String>();
        String severity = text(rule.get("severity"));
        String ruleName = text(rule.get("name"));
        lines.add((StringUtils.hasText(severity) ? "[" + severity + "] " : "")
                + fallback(ruleName, "Studio alert"));
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
        return truncateUtf8(String.join("\n", lines), MAX_ELINK_TEXT_BYTES);
    }

    static String renderWebhookJson(Map<String, Object> payload) {
        Map<String, Object> envelope = webhookEnvelope(payload);
        try {
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            return String.valueOf(envelope);
        }
    }

    static Map<String, Object> webhookEnvelope(Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        if (payload != null) {
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().startsWith("_")) {
                    envelope.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return envelope;
    }

    static Map<String, Object> withDeliveryAudit(Map<String, Object> payload, String channelType) {
        Map<String, Object> snapshot = payload == null
                ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(payload);
        Map<String, Object> audit = new LinkedHashMap<String, Object>();
        audit.put("channelType", channelType);
        if ("IN_APP".equals(channelType)) {
            RenderedMessage message = renderInAppEnvelope(payload);
            audit.put("messageFormat", message.getFormat());
            audit.put("messageTitle", message.getTitle());
            audit.put("messageContent", message.getContent());
        } else if ("ELINK".equals(channelType)) {
            audit.put("messageFormat", FORMAT_TEXT);
            audit.put("messageContent", renderElinkEnvelope(payload));
        } else if ("WEBHOOK".equals(channelType)) {
            audit.put("messageFormat", FORMAT_JSON);
        }
        snapshot.put(DELIVERY_AUDIT_KEY, audit);
        return snapshot;
    }

    static void setRecipientDisplay(Map<String, Object> payload, String recipientDisplay) {
        if (payload == null || !StringUtils.hasText(recipientDisplay)) {
            return;
        }
        Map<String, Object> current = nestedMap(payload, DELIVERY_AUDIT_KEY);
        Map<String, Object> audit = new LinkedHashMap<String, Object>(current);
        audit.put("recipientDisplay", recipientDisplay);
        payload.put(DELIVERY_AUDIT_KEY, audit);
    }

    static String auditedRecipientDisplay(Map<String, Object> payload) {
        return text(nestedMap(payload, DELIVERY_AUDIT_KEY).get("recipientDisplay"));
    }

    private static RenderedMessage auditedMessage(Map<String, Object> payload, String channelType) {
        Map<String, Object> audit = nestedMap(payload, DELIVERY_AUDIT_KEY);
        if (!channelType.equals(text(audit.get("channelType")))) {
            return null;
        }
        String content = text(audit.get("messageContent"));
        if (!StringUtils.hasText(content)) {
            return null;
        }
        return new RenderedMessage(fallback(text(audit.get("messageFormat")), FORMAT_TEXT),
                text(audit.get("messageTitle")), content);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> nestedMap(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static String truncateUtf8(String value, int maxBytes) {
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

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String fallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    static final class RenderedMessage {
        private final String format;
        private final String title;
        private final String content;

        private RenderedMessage(String format, String title, String content) {
            this.format = format;
            this.title = title;
            this.content = content;
        }

        String getFormat() {
            return format;
        }

        String getTitle() {
            return title;
        }

        String getContent() {
            return content;
        }
    }
}
