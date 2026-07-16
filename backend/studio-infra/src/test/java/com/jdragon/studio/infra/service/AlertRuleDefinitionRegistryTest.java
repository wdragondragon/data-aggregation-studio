package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.AlertOptionView;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertRuleDefinitionRegistryTest {

    private final AlertRuleDefinitionRegistry registry = new AlertRuleDefinitionRegistry();

    @Test
    void shouldExposeAllNineRuleDefinitions() {
        List<AlertOptionView> options = registry.options();

        assertEquals(9, options.size());
        assertTrue(options.stream().anyMatch(item -> "SERVICE_FAILURE_RATE".equals(item.getCode())
                && item.getSubjectTypes().contains("PROTOCOL_CONVERSION_SERVICE")));
        assertTrue(options.stream().anyMatch(item -> "LOG_UPLOAD_FAILED".equals(item.getCode())
                && item.getDefaults().containsKey("domains")));
        assertTrue(options.stream().allMatch(item -> item.getDefaultSeverity() != null));
        assertTrue(options.stream().anyMatch(item -> "WORKER_OFFLINE".equals(item.getCode())
                && "CRITICAL".equals(item.getDefaultSeverity())));
    }

    @Test
    void shouldApplyDefaultsAndValidateBounds() {
        Map<String, Object> normalized = registry.validateAndNormalize(
                "SERVICE_FAILURE_RATE", "DATA_SERVICE", Collections.<String, Object>emptyMap());

        assertEquals(1, normalized.get("windowHours"));
        assertEquals(20, normalized.get("failureRatePercent"));
        assertEquals(20, normalized.get("minimumRequests"));

        Map<String, Object> invalid = new LinkedHashMap<String, Object>();
        invalid.put("failureRatePercent", 101);
        assertThrows(StudioException.class,
                () -> registry.validateAndNormalize("SERVICE_FAILURE_RATE", "DATA_SERVICE", invalid));
    }

    @Test
    void shouldRejectUnsupportedSubjectType() {
        assertThrows(StudioException.class,
                () -> registry.validateAndNormalize("WORKER_OFFLINE", "DATA_SERVICE", Collections.<String, Object>emptyMap()));
    }

    @Test
    void shouldRejectFractionalOrOverflowingIntegerConditions() {
        assertThrows(StudioException.class, () -> registry.validateAndNormalize(
                "CONSECUTIVE_FAILURES", "COLLECTION_TASK", Map.of("consecutiveCount", 2.5D)));
        assertThrows(StudioException.class, () -> registry.validateAndNormalize(
                "CONSECUTIVE_FAILURES", "COLLECTION_TASK", Map.of("consecutiveCount", Long.MAX_VALUE)));
    }
}
