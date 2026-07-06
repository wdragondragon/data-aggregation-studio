package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantScriptSkillExecutionServiceTest {

    @Test
    void executeShouldRejectUnregisteredEntrypoint() {
        AssistantScriptSkillExecutionService service = service();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.execute(params(
                        "entrypointId", "unknown-script",
                        "input", params("sourceFields", java.util.Collections.emptyList(),
                                "targetFields", java.util.Collections.emptyList()))));

        assertTrue(exception.getMessage().contains("not registered"));
    }

    @Test
    void executeShouldValidateRegisteredEntrypointInputBeforeStartingPython() {
        AssistantScriptSkillExecutionService service = service();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.execute(params(
                        "entrypointId", "field-mapping-suggester",
                        "input", params("sourceFields", java.util.Collections.singletonList("id")))));

        assertTrue(exception.getMessage().contains("targetFields"));
    }

    @Test
    void executeShouldRejectScriptIdAlias() {
        AssistantScriptSkillExecutionService service = service();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.execute(params(
                        "scriptId", "field-mapping-suggester",
                        "input", params("sourceFields", java.util.Collections.singletonList("id"),
                                "targetFields", java.util.Collections.singletonList("ID")))));

        assertTrue(exception.getMessage().contains("entrypointId"));
    }

    @Test
    void executeShouldRejectPayloadAlias() {
        AssistantScriptSkillExecutionService service = service();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.execute(params(
                        "entrypointId", "field-mapping-suggester",
                        "payload", params("sourceFields", java.util.Collections.singletonList("id"),
                                "targetFields", java.util.Collections.singletonList("ID")))));

        assertTrue(exception.getMessage().contains("input"));
    }

    private AssistantScriptSkillExecutionService service() {
        return new AssistantScriptSkillExecutionService(
                new AssistantBuiltInSkillRegistry(),
                new StudioPlatformProperties(),
                new ObjectMapper());
    }

    private Map<String, Object> params(Object... values) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            params.put(String.valueOf(values[index]), values[index + 1]);
        }
        return params;
    }
}
