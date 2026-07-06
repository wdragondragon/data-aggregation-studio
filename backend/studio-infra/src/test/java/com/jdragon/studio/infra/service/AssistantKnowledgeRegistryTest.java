package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantInterfaceDefinition;
import com.jdragon.studio.dto.model.assistant.AssistantKnowledgeCapability;
import com.jdragon.studio.dto.model.assistant.AssistantValueResolver;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

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
    void registryShouldReturnDraftWithoutBusinessMutationSideEffects() {
        AssistantKnowledgeRegistry registry = new AssistantKnowledgeRegistry();
        AssistantKnowledgeCapability capability = registry.resolveCapabilityByCode(
                AssistantKnowledgeRegistry.CAPABILITY_COLLECTION_SINGLE_TABLE_CREATE);

        assertNotNull(capability);
        assertEquals("PREVIEW_THEN_CONFIRM", registry.createInitialDraft(capability, "", null).getConfirmationLevel());
        assertEquals("DO_NOT_GENERATE_SCHEDULE",
                registry.createInitialDraft(capability, "", null).getPayload().get("schedulePolicy"));
    }

    @Test
    void capabilitiesShouldExposeGenericStudioFeatureToolsToLlm() {
        AssistantKnowledgeRegistry registry = new AssistantKnowledgeRegistry();
        AssistantKnowledgeCapability capability = registry.resolveCapabilityByCode(
                AssistantKnowledgeRegistry.CAPABILITY_COLLECTION_SINGLE_TABLE_CREATE);

        assertNotNull(capability);
        for (AssistantInterfaceDefinition interfaceDefinition : capability.getInterfaces()) {
            String code = interfaceDefinition.getInterfaceCode();
            assertTrue("studio.feature.list".equals(code)
                    || "studio.feature.get".equals(code)
                    || "studio.feature.action".equals(code));
        }
        StringBuilder text = new StringBuilder();
        for (AssistantInterfaceDefinition interfaceDefinition : capability.getInterfaces()) {
            text.append(interfaceDefinition.getInterfaceCode())
                    .append(interfaceDefinition.getPath())
                    .append(interfaceDefinition.getPurpose());
        }
        for (AssistantValueResolver resolver : capability.getValueResolvers()) {
            text.append(resolver.getInterfaceCode())
                    .append(resolver.getDescription());
        }
        assertFalse(text.toString().contains("catalog.capabilities"));
        assertFalse(text.toString().contains("datasources.options"));
        assertFalse(text.toString().contains("models.datasourceOptions"));
        assertFalse(text.toString().contains("models.get"));
        assertFalse(text.toString().contains("catalog.runtimeOptionSchema"));
        assertFalse(text.toString().contains("collectionTasks.preview"));
    }

    private void assertText(String value) {
        assertNotNull(value);
        assertFalse(value.trim().isEmpty());
    }
}
