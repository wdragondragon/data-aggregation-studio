package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantBuiltInSkillRegistryTest {

    @Test
    void builtInSkillsShouldUsePortableSchema() {
        AssistantBuiltInSkillRegistry registry = new AssistantBuiltInSkillRegistry();
        AssistantPlanRequest request = new AssistantPlanRequest();
        request.setMessage("workflow plan");

        List<Map<String, Object>> skills = registry.assistantSkills(request);

        assertFalse(skills.isEmpty());
        assertEquals("studio.skill.v1", skills.get(0).get("schema"));
        assertEquals(Boolean.TRUE, skills.get(0).get("portable"));
        assertTrue(skills.get(0).containsKey("agentUsage"));
    }

    @Test
    void protocolSkillShouldExposePortableProtocolContract() {
        AssistantBuiltInSkillRegistry registry = new AssistantBuiltInSkillRegistry();

        Optional<Map<String, Object>> protocolSkill = registry.allPortableSkills().stream()
                .filter(skill -> "assistant-protocol".equals(skill.get("id")))
                .findFirst();

        assertTrue(protocolSkill.isPresent());
        assertEquals("studio-assistant.v1", protocolSkill.get().get("protocolVersion"));
        assertEquals("studio-assistant-protocol", protocolSkill.get().get("protocolFence"));
        assertTrue(protocolSkill.get().get("protocolSchema") instanceof Map);
        assertTrue(protocolSkill.get().get("examples") instanceof List);
        assertTrue(String.valueOf(protocolSkill.get().get("providerPortability")).contains("function_call"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("toolResultContract"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("assistant.context.read"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("activeObject"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("scriptToolContract"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("completed"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("answer_complete"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("gateway must not decide"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("plan"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("protocolRequiredRule"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("must explicitly set protocol to studio-assistant.v1"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("planRequired"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("All four plan fields are required"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("loopRequired"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("Loop is required"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("forbiddenFields"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("toolCalls"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("Use actions"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("actionRequired"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("type, tool, and params"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("controlRequired"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("type, title, and paramKey"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("intent"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("basis"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("requiredObjects"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("nextActions"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("autoContinue"));
        assertTrue(String.valueOf(protocolSkill.get().get("protocolSchema")).contains("stopReason"));
        assertTrue(String.valueOf(protocolSkill.get().get("examples")).contains("studio.feature.list"));
        assertTrue(String.valueOf(protocolSkill.get().get("examples")).contains("assistant.script.execute"));
        assertTrue(String.valueOf(protocolSkill.get().get("examples")).contains("\"status\":\"completed\""));
        assertTrue(String.valueOf(protocolSkill.get().get("examples")).contains("\"actions\":[]"));
        assertTrue(String.valueOf(protocolSkill.get().get("examples")).contains("\"plan\""));
        assertTrue(String.valueOf(protocolSkill.get().get("examples")).contains("\"basis\""));
    }

    @Test
    void pythonScriptSkillShouldExposeControlledEntrypoint() {
        AssistantBuiltInSkillRegistry registry = new AssistantBuiltInSkillRegistry();

        Optional<Map<String, Object>> scriptSkill = registry.allPortableSkills().stream()
                .filter(skill -> "field-mapping-python-helper".equals(skill.get("id")))
                .findFirst();

        assertTrue(scriptSkill.isPresent());
        assertEquals("assistant.script.skill", scriptSkill.get().get("kind"));
        assertTrue(scriptSkill.get().get("scriptEntrypoints") instanceof List);
        assertTrue(String.valueOf(scriptSkill.get().get("scriptEntrypoints")).contains("field_mapping_suggester.py"));
        assertTrue(String.valueOf(scriptSkill.get().get("safetyPolicy")).contains("no-network"));
        assertTrue(String.valueOf(scriptSkill.get().get("safetyPolicy")).contains("must-be-invoked-by-registered-executor"));
        assertTrue(getClass().getClassLoader().getResource("assistant/python/field_mapping_suggester.py") != null);
    }
}
