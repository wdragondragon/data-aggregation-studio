package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.AssistantBuiltInSkillRegistry;
import com.jdragon.studio.infra.service.AssistantKnowledgeRegistry;
import com.jdragon.studio.infra.service.AssistantLlmPlanner;
import com.jdragon.studio.infra.service.AssistantPlanService;
import com.jdragon.studio.infra.service.AssistantStudioOperationRegistry;
import com.jdragon.studio.server.web.controller.AssistantController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantApiRegressionTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AssistantKnowledgeRegistry knowledgeRegistry = new AssistantKnowledgeRegistry();
        AssistantBuiltInSkillRegistry builtInSkillRegistry = new AssistantBuiltInSkillRegistry();
        AssistantLlmPlanner llmPlanner = new AssistantLlmPlanner(
                new StudioPlatformProperties(),
                objectMapper,
                java.util.Collections.singletonList(builtInSkillRegistry),
                null);
        AssistantPlanService planService = new AssistantPlanService(knowledgeRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(new AssistantController(
                        knowledgeRegistry,
                        planService,
                        llmPlanner,
                        builtInSkillRegistry,
                        new AssistantStudioOperationRegistry()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void configShouldExposeAssistantDisabledWhenLlmIsNotConfigured() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.reason").value("llm-disabled-or-incomplete"));
    }

    @Test
    void chatStreamShouldReturnDisabledErrorWhenLlmIsDisabled() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("message", "what can Studio assistant do?");
        payload.put("assistantMode", "goal");
        payload.put("responseLanguage", "en");
        payload.put("protocolVersion", "studio-assistant.v1");

        MvcResult streamResult = mockMvc.perform(post("/api/v1/assistant/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(streamResult))
                .andExpect(status().isOk())
                .andReturn();

        String content = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("event: progress");
        assertThat(content).contains("llm.disabled");
        assertThat(content).contains("event: error");
        assertThat(content).contains("AI 助手未启用");
        assertThat(content).contains("event: done");
        assertThat(content).doesNotContain("event: delta");
        assertThat(content).doesNotContain("studio-assistant-protocol");
    }

    @Test
    void chatStreamShouldNotReturnBuiltinPlanModeWhenLlmIsDisabled() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("message", "帮我规划一下数据开发 SQL 执行和结果验证");
        payload.put("assistantMode", "plan");
        payload.put("responseLanguage", "zh");
        payload.put("protocolVersion", "studio-assistant.v1");

        MvcResult streamResult = mockMvc.perform(post("/api/v1/assistant/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(streamResult))
                .andExpect(status().isOk())
                .andReturn();

        String content = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("event: progress");
        assertThat(content).contains("llm.disabled");
        assertThat(content).contains("event: error");
        assertThat(content).contains("AI 助手未启用");
        assertThat(content).contains("event: done");
        assertThat(content).doesNotContain("event: delta");
        assertThat(content).doesNotContain("我会按计划模式处理");
        assertThat(content).doesNotContain("studio-assistant-protocol");
    }

    @Test
    void legacyPlanEndpointShouldRemainDisabledAndNotReturnBuiltinAction() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"帮我规划一个单表采集\",\"responseLanguage\":\"zh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.plannerMode").value("LEGACY_DISABLED"))
                .andExpect(jsonPath("$.data.selectedCapabilityCode").doesNotExist())
                .andExpect(jsonPath("$.data.toolCalls").isArray())
                .andExpect(jsonPath("$.data.toolCalls.length()").value(0))
                .andExpect(jsonPath("$.data.backendToolResults").isArray())
                .andExpect(jsonPath("$.data.backendToolResults.length()").value(0))
                .andExpect(jsonPath("$.data.actionDraft").doesNotExist())
                .andExpect(jsonPath("$.data.llmPlan").doesNotExist())
                .andExpect(jsonPath("$.data.assistantMessage").value("旧版 /assistant/plan 已停用。请使用 Web 流式助手的 studio-assistant.v1 协议；本轮未生成任何 Studio 操作。"))
                .andExpect(jsonPath("$.data.warnings[0]").value("Legacy /assistant/plan is disabled; use /assistant/chat/stream with studio-assistant.v1 plan/loop/actions/controls."));
    }

    @Test
    void learnShouldRemainDisabledForPortableBuiltinSkillVersion() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/learn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"remember this\",\"assistantContent\":\"demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accepted").value(false))
                .andExpect(jsonPath("$.data.message").value("learning-disabled; built-in portable skills are used in this version"));
    }

    @Test
    void operationsShouldReturnPortableStudioCatalog() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/operations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[?(@.path == '/data-development')]").exists())
                .andExpect(jsonPath("$.data[?(@.schema == 'studio.operation.v1')]").exists())
                .andExpect(jsonPath("$.data[?(@.capabilityCode == 'studio.dataDevelopment.manage')]").exists())
                .andExpect(jsonPath("$..featureActions[?(@.action == 'executeSql')]").exists())
                .andExpect(jsonPath("$..featureActions[?(@.tool == 'studio.feature.action')]").exists());
    }

    @Test
    void skillsShouldReturnPortableBuiltinAndOperationSkillCards() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[?(@.schema == 'studio.skill.v1')]").exists())
                .andExpect(jsonPath("$.data[?(@.portable == true)]").exists())
                .andExpect(jsonPath("$.data[?(@.id == 'assistant-loop')]").exists())
                .andExpect(jsonPath("$.data[?(@.id == 'assistant-protocol' && @.protocolVersion == 'studio-assistant.v1')]").exists())
                .andExpect(jsonPath("$.data[?(@.id == 'assistant-protocol')].protocolSchema").exists())
                .andExpect(jsonPath("$..scriptToolContract").exists())
                .andExpect(jsonPath("$.data[?(@.id == 'assistant-protocol')].examples").exists())
                .andExpect(jsonPath("$.data[?(@.id == 'field-mapping-python-helper' && @.kind == 'assistant.script.skill')]").exists())
                .andExpect(jsonPath("$.data[?(@.id == 'field-mapping-python-helper')].scriptEntrypoints").exists())
                .andExpect(jsonPath("$.data[?(@.kind == 'studio.operation.catalog')]").exists())
                .andExpect(jsonPath("$.data[?(@.path == '/data-development')]").exists())
                .andExpect(jsonPath("$..operation[?(@.schema == 'studio.operation.v1')]").exists());
    }
}
