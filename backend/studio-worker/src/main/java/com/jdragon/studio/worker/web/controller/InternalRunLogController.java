package com.jdragon.studio.worker.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.RunLogChunkEntity;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/internal/runs")
public class InternalRunLogController {

    private final RunRecordMapper runRecordMapper;
    private final RunLogChunkMapper runLogChunkMapper;
    private final RunLogFileService runLogFileService;
    private final StudioPlatformProperties properties;
    private final ClusterInstanceIdentity instanceIdentity;

    public InternalRunLogController(RunRecordMapper runRecordMapper,
                                    RunLogFileService runLogFileService,
                                    StudioPlatformProperties properties,
                                    ClusterInstanceIdentity instanceIdentity) {
        this(runRecordMapper, runLogFileService, properties, instanceIdentity, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InternalRunLogController(RunRecordMapper runRecordMapper,
                                    RunLogFileService runLogFileService,
                                    StudioPlatformProperties properties,
                                    ClusterInstanceIdentity instanceIdentity,
                                    RunLogChunkMapper runLogChunkMapper) {
        this.runRecordMapper = runRecordMapper;
        this.runLogFileService = runLogFileService;
        this.properties = properties;
        this.instanceIdentity = instanceIdentity;
        this.runLogChunkMapper = runLogChunkMapper;
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

    @GetMapping("/chunks/{chunkId}/preview")
    public Result<RunLogView> previewChunk(@PathVariable("chunkId") Long chunkId,
                                           @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                           @RequestParam(value = "pageSizeBytes", required = false) Integer pageSizeBytes) {
        if (runLogChunkMapper == null || chunkId == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run log chunk not found: " + chunkId);
        }
        RunLogChunkEntity chunk = runLogChunkMapper.selectOne(new LambdaQueryWrapper<RunLogChunkEntity>()
                .eq(RunLogChunkEntity::getId, chunkId)
                .last("limit 1"));
        if (chunk == null || chunk.getRunRecordId() == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run log chunk not found: " + chunkId);
        }
        RunRecordEntity record = requiredRecord(chunk.getRunRecordId());
        if (record.getId() == null || !record.getId().equals(chunk.getRunRecordId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run log chunk not found: " + chunkId);
        }
        return Result.success(runLogFileService.readChunkPage(record, chunk, pageNo, pageSizeBytes));
    }

    @GetMapping("/{id}/log/archive")
    public ResponseEntity<StreamingResponseBody> archive(@PathVariable("id") Long id) {
        RunRecordEntity record = requiredRecord(id);
        String name = "run-" + id + ".zip";
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        StreamingResponseBody body = output -> runLogFileService.writeArchive(record, output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Accel-Buffering", "no")
                .body(body);
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
