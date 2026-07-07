package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.server.web.service.FlinkProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Flink Query Proxy", description = "Proxy APIs from studio-server to the standalone studio-flink service")
@RestController
@RequestMapping("/api/flink")
public class FlinkProxyController {

    private final FlinkProxyService flinkProxyService;

    public FlinkProxyController(FlinkProxyService flinkProxyService) {
        this.flinkProxyService = flinkProxyService;
    }

    @Operation(summary = "Proxy guarded Flink SQL execution")
    @PostMapping("/sql/execute")
    public Result<FlinkQuestionResultView> executeSql(@Valid @RequestBody FlinkSqlExecuteRequest request) {
        return flinkProxyService.executeSql(request);
    }

    @Operation(summary = "Proxy natural-language data question to Flink SQL")
    @PostMapping("/question/ask")
    public Result<FlinkQuestionResultView> ask(@Valid @RequestBody FlinkQuestionAskRequest request) {
        return flinkProxyService.ask(request);
    }
}
