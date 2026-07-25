package com.jdragon.studio.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.AssistantBuiltInSkillRegistry;
import com.jdragon.studio.infra.service.AssistantScriptSkillExecutionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers process-backed assistant helpers only in Worker runtimes. */
@Configuration(proxyBeanMethods = false)
public class WorkerAssistantExecutionConfiguration {

    @Bean
    public AssistantScriptSkillExecutionService assistantScriptSkillExecutionService(
            AssistantBuiltInSkillRegistry builtInSkillRegistry,
            StudioPlatformProperties properties,
            ObjectMapper objectMapper) {
        return new AssistantScriptSkillExecutionService(builtInSkillRegistry, properties, objectMapper);
    }
}
