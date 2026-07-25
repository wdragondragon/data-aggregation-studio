package com.jdragon.studio.flink.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FlinkQuestionPlanView;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.flink.service.FlinkQuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Flink Query Planning", description = "Control-plane planning APIs for Flink SQL")
@RestController
@RequestMapping("/api/flink")
public class FlinkQueryController {
    private final FlinkQuestionService flinkQuestionService;

    public FlinkQueryController(FlinkQuestionService flinkQuestionService) {
        this.flinkQuestionService = flinkQuestionService;
    }

    @Operation(summary = "Generate Flink SQL for a natural-language question without executing it")
    @PostMapping("/question/plan")
    public Result<FlinkQuestionPlanView> plan(@Valid @RequestBody FlinkQuestionAskRequest request) {
        return Result.success(flinkQuestionService.plan(request));
    }
}
