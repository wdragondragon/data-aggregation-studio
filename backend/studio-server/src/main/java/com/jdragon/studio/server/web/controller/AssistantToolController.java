package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.server.web.service.AssistantStudioToolExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Assistant", description = "Knowledge-driven assistant planning APIs")
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantToolController {

    private final AssistantStudioToolExecutionService assistantStudioToolExecutionService;

    public AssistantToolController(AssistantStudioToolExecutionService assistantStudioToolExecutionService) {
        this.assistantStudioToolExecutionService = assistantStudioToolExecutionService;
    }

    @Operation(summary = "Execute a controlled Studio assistant tool")
    @PostMapping("/tools/execute")
    public Result<Map<String, Object>> executeTool(@RequestBody(required = false) Map<String, Object> request) {
        return Result.success(assistantStudioToolExecutionService.execute(request));
    }
}
