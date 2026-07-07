package com.jdragon.studio.flink.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.flink.service.FlinkQuestionService;
import com.jdragon.studio.flink.service.FlinkSqlExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Flink Query", description = "DataAggregation Source plugin backed Flink SQL APIs")
@RestController
@RequestMapping("/api/flink")
public class FlinkQueryController {
    private final FlinkSqlExecutionService flinkSqlExecutionService;
    private final FlinkQuestionService flinkQuestionService;

    public FlinkQueryController(FlinkSqlExecutionService flinkSqlExecutionService,
                                FlinkQuestionService flinkQuestionService) {
        this.flinkSqlExecutionService = flinkSqlExecutionService;
        this.flinkQuestionService = flinkQuestionService;
    }

    @Operation(summary = "Execute guarded Flink SQL")
    @PostMapping("/sql/execute")
    public Result<FlinkQuestionResultView> executeSql(@Valid @RequestBody FlinkSqlExecuteRequest request) {
        return Result.success(flinkSqlExecutionService.execute(request));
    }

    @Operation(summary = "Ask a natural-language data question using Flink SQL")
    @PostMapping("/question/ask")
    public Result<FlinkQuestionResultView> ask(@Valid @RequestBody FlinkQuestionAskRequest request) {
        return Result.success(flinkQuestionService.ask(request));
    }
}
