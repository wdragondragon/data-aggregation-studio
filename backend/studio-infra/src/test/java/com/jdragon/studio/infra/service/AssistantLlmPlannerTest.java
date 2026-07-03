package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.assistant.AssistantBackendToolCall;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantLlmPlannerTest {

    @Test
    void extractBackendToolCallsShouldSupportFencedAndLooseActionJson() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(new StudioPlatformProperties(), new ObjectMapper());
        String content = "先读取技能。\n"
                + "```assistant-action\n"
                + "{\"backendToolCalls\":[{\"code\":\"assistant.skills.search\",\"reason\":\"检索\",\"params\":{\"query\":\"单表采集\"}}]}\n"
                + "```\n"
                + "畸形前缀" + "\u0cbe\u0caf\u0c95ction"
                + "{\"backendToolCalls\":[{\"code\":\"assistant.skills.search\",\"params\":{\"query\":\"重复\"}}]}";

        List<AssistantBackendToolCall> calls = planner.extractBackendToolCalls(content);

        assertEquals(1, calls.size());
        assertEquals("assistant.skills.search", calls.get(0).getCode());
        assertEquals("单表采集", calls.get(0).getParams().get("query"));
    }
}
