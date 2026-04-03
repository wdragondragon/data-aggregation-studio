package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.springframework.stereotype.Service;

@Service
public class ExecutionEventService implements ExecutionEventPublisher {

    private final RunRecordMapper runRecordMapper;

    public ExecutionEventService(RunRecordMapper runRecordMapper) {
        this.runRecordMapper = runRecordMapper;
    }

    @Override
    public void publish(ExecutionEvent event) {
        RunRecordEntity entity = new RunRecordEntity();
        entity.setWorkflowDefinitionId(event.getWorkflowRunId());
        entity.setNodeCode(event.getNodeCode());
        entity.setWorkerCode(event.getWorkerCode());
        entity.setStatus(event.getEventType());
        entity.setPayloadJson(event.getPayload());
        entity.setStartedAt(event.getOccurredAt());
        entity.setMessage(event.getEventType());
        runRecordMapper.insert(entity);
    }
}

