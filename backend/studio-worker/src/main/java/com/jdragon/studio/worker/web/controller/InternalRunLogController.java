package com.jdragon.studio.worker.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/runs")
public class InternalRunLogController {

    private final RunRecordMapper runRecordMapper;
    private final RunLogFileService runLogFileService;
    private final StudioPlatformProperties properties;

    public InternalRunLogController(RunRecordMapper runRecordMapper,
                                    RunLogFileService runLogFileService,
                                    StudioPlatformProperties properties) {
        this.runRecordMapper = runRecordMapper;
        this.runLogFileService = runLogFileService;
        this.properties = properties;
    }

    @GetMapping("/{id}/log")
    public Result<RunLogView> viewLog(@PathVariable("id") Long id,
                                      @RequestParam(value = "maxBytes", required = false) Integer maxBytes) {
        return Result.success(runLogFileService.readTail(requiredRecord(id), maxBytes));
    }

    @GetMapping("/{id}/log/download")
    public Result<RunLogView> downloadLog(@PathVariable("id") Long id) {
        return Result.success(runLogFileService.readFull(requiredRecord(id)));
    }

    private RunRecordEntity requiredRecord(Long id) {
        RunRecordEntity entity = runRecordMapper.selectById(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record not found: " + id);
        }
        if (entity.getWorkerCode() != null && !properties.getWorkerCode().equals(entity.getWorkerCode())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record does not belong to this worker");
        }
        if (entity.getLogFilePath() == null || entity.getLogFilePath().trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run log file is not available for this record");
        }
        return entity;
    }
}
