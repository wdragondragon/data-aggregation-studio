package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.service.CollectionTaskIncrementalStateService;
import com.jdragon.studio.infra.service.DataModelLineageService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.ExecutionEventService;
import com.jdragon.studio.infra.service.FollowSubscriptionService;
import com.jdragon.studio.infra.service.NotificationService;
import com.jdragon.studio.infra.service.QualityIssueService;
import com.jdragon.studio.infra.service.RunMetricSummaryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionEventServiceRegressionTest {

    @Test
    void shouldTruncateLongRunRecordMessageBeforePersistingTerminalEvent() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity existingRun = new RunRecordEntity();
        existingRun.setId(100L);
        existingRun.setStatus("RUNNING");
        existingRun.setStartedAt(LocalDateTime.of(2026, 5, 1, 10, 0, 0));
        when(runRecordMapper.selectById(eq(100L))).thenReturn(existingRun);

        ExecutionEventService service = new ExecutionEventService(
                runRecordMapper,
                mock(DispatchTaskMapper.class),
                mock(CollectionTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class),
                mock(DispatchService.class),
                mock(RunMetricSummaryMapper.class),
                mock(FollowSubscriptionService.class),
                mock(NotificationService.class),
                mock(DataModelLineageService.class),
                mock(QualityIssueService.class),
                mock(CollectionTaskIncrementalStateService.class));

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(100L);
        event.setEventType("FAILED");
        event.setOccurredAt(LocalDateTime.of(2026, 5, 1, 10, 5, 0));
        event.getPayload().put("error", "x".repeat(2400));

        service.publish(event);

        ArgumentCaptor<RunRecordEntity> captor = ArgumentCaptor.forClass(RunRecordEntity.class);
        verify(runRecordMapper).updateById(captor.capture());
        RunRecordEntity updatedRun = captor.getValue();
        assertEquals("FAILED", updatedRun.getStatus());
        assertEquals(2000, updatedRun.getMessage().length());
        assertTrue(updatedRun.getMessage().endsWith("..."));
    }
}
