package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssistantBackendToolRegistryTest {

    @Test
    void registryShouldInvokeOnlyAnnotatedAllowListedMethods() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(AllowedTool.class);
        context.registerBean(UnannotatedTool.class);
        context.refresh();
        try {
            AssistantBackendToolRegistry registry = new AssistantBackendToolRegistry(context);

            List<Map<String, Object>> summaries = registry.listToolSummaries();
            assertEquals(1, summaries.size());
            assertEquals("allowed.echo", summaries.get(0).get("code"));

            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("value", "ok");
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) registry.invoke(
                    "allowed.echo",
                    new AssistantPlanRequest(),
                    params);

            assertEquals("ok", result.get("value"));
            assertEquals("allowed", result.get("source"));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.invoke("echo", new AssistantPlanRequest(), params));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.invoke("java.lang.System.exit", new AssistantPlanRequest(), params));
        } finally {
            context.close();
        }
    }

    public static class AllowedTool {

        @AssistantBackendTool(
                code = "allowed.echo",
                name = "Allowed Echo",
                description = "Test-only allow-listed tool."
        )
        public Map<String, Object> echo(AssistantPlanRequest request, Map<String, Object> params) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("source", "allowed");
            result.put("value", params.get("value"));
            result.put("hasRequest", Boolean.valueOf(request != null));
            return result;
        }
    }

    public static class UnannotatedTool {
        public Map<String, Object> echo(AssistantPlanRequest request, Map<String, Object> params) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("source", "unannotated");
            return result;
        }
    }
}
