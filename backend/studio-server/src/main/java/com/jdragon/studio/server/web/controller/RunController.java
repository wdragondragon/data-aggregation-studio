package com.jdragon.studio.server.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.service.DispatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Runs", description = "Workflow run and dispatch APIs")
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final DispatchService dispatchService;
    private final RunRecordMapper runRecordMapper;

    public RunController(DispatchService dispatchService, RunRecordMapper runRecordMapper) {
        this.dispatchService = dispatchService;
        this.runRecordMapper = runRecordMapper;
    }

    @Operation(summary = "List queued tasks and run records")
    @GetMapping
    public Result<Map<String, Object>> list() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("queuedTasks", dispatchService.queuedTasks());
        payload.put("runRecords", runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                .orderByDesc(RunRecordEntity::getCreatedAt)));
        return Result.success(payload);
    }
}
