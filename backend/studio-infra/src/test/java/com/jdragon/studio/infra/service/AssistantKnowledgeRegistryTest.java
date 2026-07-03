package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantInterfaceDefinition;
import com.jdragon.studio.dto.model.assistant.AssistantKnowledgeCapability;
import com.jdragon.studio.dto.model.assistant.AssistantToolCall;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantKnowledgeRegistryTest {

    @Test
    void capabilitiesShouldHaveUniqueCodesAndCompleteInterfaceDefinitions() {
        AssistantKnowledgeRegistry registry = new AssistantKnowledgeRegistry();
        Set<String> capabilityCodes = new HashSet<String>();

        assertFalse(registry.listCapabilities().isEmpty());
        for (AssistantKnowledgeCapability capability : registry.listCapabilities()) {
            assertText(capability.getCapabilityCode());
            assertText(capability.getCapabilityName());
            assertTrue(capabilityCodes.add(capability.getCapabilityCode()));
            assertFalse(capability.getIntentExamples().isEmpty());
            assertFalse(capability.getRequiredInputs().isEmpty());
            assertFalse(capability.getInterfaces().isEmpty());
            assertFalse(capability.getValueResolvers().isEmpty());
            assertFalse(capability.getAssemblyRules().isEmpty());
            assertFalse(capability.getConfirmationPolicy().isEmpty());

            for (AssistantInterfaceDefinition interfaceDefinition : capability.getInterfaces()) {
                assertText(interfaceDefinition.getInterfaceCode());
                assertText(interfaceDefinition.getMethod());
                assertText(interfaceDefinition.getPath());
            }
        }
    }

    @Test
    void registryShouldReturnInitialReadToolsWithoutBusinessMutationToolCalls() {
        AssistantKnowledgeRegistry registry = new AssistantKnowledgeRegistry();
        AssistantKnowledgeCapability capability = registry.resolveCapabilityByCode(
                AssistantKnowledgeRegistry.CAPABILITY_COLLECTION_SINGLE_TABLE_CREATE);

        assertNotNull(capability);
        assertEquals("PREVIEW_THEN_CONFIRM", registry.createInitialDraft(capability, "", null).getConfirmationLevel());
        assertEquals("DO_NOT_GENERATE_SCHEDULE",
                registry.createInitialDraft(capability, "", null).getPayload().get("schedulePolicy"));

        Set<String> suggestedToolCodes = registry.suggestInitialToolCalls(capability).stream()
                .map(AssistantToolCall::getInterfaceCode)
                .collect(Collectors.toSet());
        assertTrue(suggestedToolCodes.contains("catalog.capabilities"));
        assertTrue(suggestedToolCodes.contains("datasources.options"));
        assertFalse(suggestedToolCodes.contains("collectionTasks.save"));
        assertFalse(suggestedToolCodes.contains("collectionTasks.publish"));
        assertFalse(suggestedToolCodes.contains("collectionTasks.trigger"));
        assertFalse(suggestedToolCodes.contains("collectionTasks.delete"));
    }

    private void assertText(String value) {
        assertNotNull(value);
        assertFalse(value.trim().isEmpty());
    }
}
