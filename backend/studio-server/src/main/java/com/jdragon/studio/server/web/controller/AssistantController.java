package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.assistant.AssistantLearnRequest;
import com.jdragon.studio.dto.model.assistant.AssistantLearnResponse;
import com.jdragon.studio.dto.model.assistant.AssistantKnowledgeCapability;
import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import com.jdragon.studio.dto.model.assistant.AssistantPlanResponse;
import com.jdragon.studio.infra.service.AssistantBuiltInSkillRegistry;
import com.jdragon.studio.infra.service.AssistantKnowledgeRegistry;
import com.jdragon.studio.infra.service.AssistantLlmPlanner;
import com.jdragon.studio.infra.service.AssistantPlanService;
import com.jdragon.studio.infra.service.AssistantStudioOperationRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Assistant", description = "Knowledge-driven assistant planning APIs")
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final AssistantKnowledgeRegistry assistantKnowledgeRegistry;
    private final AssistantPlanService assistantPlanService;
    private final AssistantLlmPlanner assistantLlmPlanner;
    private final AssistantBuiltInSkillRegistry assistantBuiltInSkillRegistry;
    private final AssistantStudioOperationRegistry assistantStudioOperationRegistry;

    public AssistantController(AssistantKnowledgeRegistry assistantKnowledgeRegistry,
                               AssistantPlanService assistantPlanService,
                               AssistantLlmPlanner assistantLlmPlanner,
                               AssistantBuiltInSkillRegistry assistantBuiltInSkillRegistry,
                               AssistantStudioOperationRegistry assistantStudioOperationRegistry) {
        this.assistantKnowledgeRegistry = assistantKnowledgeRegistry;
        this.assistantPlanService = assistantPlanService;
        this.assistantLlmPlanner = assistantLlmPlanner;
        this.assistantBuiltInSkillRegistry = assistantBuiltInSkillRegistry;
        this.assistantStudioOperationRegistry = assistantStudioOperationRegistry;
    }

    @Deprecated
    @Operation(summary = "Legacy non-stream assistant plan endpoint, disabled for Web LLM-first execution")
    @PostMapping("/plan")
    public Result<AssistantPlanResponse> plan(@RequestBody(required = false) AssistantPlanRequest request) {
        return Result.success(assistantPlanService.plan(request));
    }

    @Operation(summary = "Persist useful assistant interaction as skill memory")
    @PostMapping("/learn")
    public Result<AssistantLearnResponse> learn(@RequestBody(required = false) AssistantLearnRequest request) {
        AssistantLearnResponse response = new AssistantLearnResponse();
        response.setAccepted(Boolean.FALSE);
        response.setMessage("learning-disabled; built-in portable skills are used in this version");
        return Result.success(response);
    }

    @Operation(summary = "Stream assistant chat response")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamChat(@RequestBody(required = false) AssistantPlanRequest request) {
        AssistantPlanRequest safeRequest = request == null ? new AssistantPlanRequest() : request;
        StreamingResponseBody body = outputStream -> assistantLlmPlanner.streamChat(
                safeRequest,
                assistantKnowledgeRegistry.listCapabilities(),
                outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @Operation(summary = "List assistant knowledge capabilities")
    @GetMapping("/capabilities")
    public Result<List<AssistantKnowledgeCapability>> capabilities() {
        return Result.success(assistantKnowledgeRegistry.listCapabilities());
    }

    @Operation(summary = "Read assistant runtime availability")
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        boolean enabled = assistantLlmPlanner.isEnabled();
        config.put("enabled", Boolean.valueOf(enabled));
        config.put("reason", enabled ? "llm-enabled" : "llm-disabled-or-incomplete");
        return Result.success(config);
    }

    @Operation(summary = "List portable Studio operation catalog for assistant context")
    @GetMapping("/operations")
    public Result<List<Map<String, Object>>> operations() {
        return Result.success(assistantStudioOperationRegistry.allOperations());
    }

    @Operation(summary = "Export portable assistant skills for other agent platforms")
    @GetMapping("/skills")
    public Result<List<Map<String, Object>>> skills() {
        List<Map<String, Object>> skills = new ArrayList<Map<String, Object>>();
        skills.addAll(assistantBuiltInSkillRegistry.allPortableSkills());
        skills.addAll(assistantStudioOperationRegistry.allOperationSkillCards());
        return Result.success(skills);
    }
}
