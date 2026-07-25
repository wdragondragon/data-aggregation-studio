package com.jdragon.studio.worker.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import org.springframework.util.StringUtils;
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
    private final ClusterInstanceIdentity instanceIdentity;

    public InternalRunLogController(RunRecordMapper runRecordMapper,
                                    RunLogFileService runLogFileService,
                                    StudioPlatformProperties properties,
                                    ClusterInstanceIdentity instanceIdentity) {
        this.runRecordMapper = runRecordMapper;
        this.runLogFileService = runLogFileService;
        this.properties = properties;
        this.instanceIdentity = instanceIdentity;
    }

    @GetMapping("/{id}/log")
    public Result<RunLogView> viewLog(@PathVariable("id") Long id,
                                      @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                      @RequestParam(value = "pageSizeBytes", required = false) Integer pageSizeBytes) {
        return Result.success(runLogFileService.readPage(requiredRecord(id), pageNo, pageSizeBytes));
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
        if (!StringUtils.hasText(entity.getWorkerCode())
                || !properties.getWorkerCode().equals(entity.getWorkerCode())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record does not belong to this worker");
        }
        if (StringUtils.hasText(entity.getWorkerInstanceId())
                && !entity.getWorkerInstanceId().equals(instanceIdentity.instanceId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record does not belong to this worker instance");
        }
        if (StringUtils.hasText(entity.getWorkerBootId())
                && !entity.getWorkerBootId().equals(instanceIdentity.bootId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record does not belong to this worker boot");
        }
        if (StringUtils.hasText(entity.getActualClusterCode())
                && !entity.getActualClusterCode().equalsIgnoreCase(properties.getRuntimeClusterCode())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record does not belong to this runtime cluster");
        }
        if (entity.getLogFilePath() == null || entity.getLogFilePath().trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run log file is not available for this record");
        }
        return entity;
    }
}
