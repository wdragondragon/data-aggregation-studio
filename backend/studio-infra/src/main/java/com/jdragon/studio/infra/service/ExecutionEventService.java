package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionEventService implements ExecutionEventPublisher {

    private final RunRecordMapper runRecordMapper;
    private final DispatchService dispatchService;

    public ExecutionEventService(RunRecordMapper runRecordMapper,
                                 DispatchService dispatchService) {
        this.runRecordMapper = runRecordMapper;
        this.dispatchService = dispatchService;
    }

    @Override
    @Transactional
    public void publish(ExecutionEvent event) {
        RunRecordEntity entity = event.getRunRecordId() == null
                ? null
                : runRecordMapper.selectById(event.getRunRecordId());
        if (entity == null) {
            entity = new RunRecordEntity();
            entity.setId(event.getRunRecordId());
        }
        entity.setExecutionType(event.getExecutionType() == null ? null : event.getExecutionType().name());
        entity.setWorkflowRunId(event.getWorkflowRunId());
        entity.setWorkflowDefinitionId(event.getWorkflowDefinitionId());
        entity.setWorkflowVersionId(event.getWorkflowVersionId());
        entity.setCollectionTaskId(event.getCollectionTaskId());
        entity.setProjectId(event.getProjectId());
        entity.setNodeCode(event.getNodeCode());
        entity.setWorkerCode(event.getWorkerCode());
        entity.setStatus(event.getEventType());
        entity.setPayloadJson(event.getPayload());
        entity.setResultJson(event.getPayload());
        entity.setStartedAt(event.getStartedAt() == null ? event.getOccurredAt() : event.getStartedAt());
        entity.setEndedAt(event.getEndedAt() == null ? event.getOccurredAt() : event.getEndedAt());
        entity.setLogFilePath(event.getLogFilePath());
        entity.setLogSizeBytes(event.getLogSizeBytes());
        entity.setLogCharset(event.getLogCharset());
        entity.setMessage(resolveMessage(event));
        if (entity.getId() == null) {
            runRecordMapper.insert(entity);
        } else {
            runRecordMapper.updateById(entity);
        }
        dispatchService.continueWorkflowRun(event);
    }

    private String resolveMessage(ExecutionEvent event) {
        if (event.getPayload() != null) {
            Object error = event.getPayload().get("error");
            if (error != null) {
                return String.valueOf(error);
            }
            Object message = event.getPayload().get("message");
            if (message != null) {
                return String.valueOf(message);
            }
        }
        return event.getEventType();
    }
}

