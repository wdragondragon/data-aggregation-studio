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
import com.jdragon.studio.infra.service.StaleExecutionRecoveryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        ExecutionEventService service = service(runRecordMapper);

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

    @Test
    void shouldSanitizeDatabaseStackTraceBeforePersistingFailureEvent() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity existingRun = new RunRecordEntity();
        existingRun.setId(101L);
        existingRun.setStatus("RUNNING");
        when(runRecordMapper.selectById(eq(101L))).thenReturn(existingRun);

        ExecutionEventService service = service(runRecordMapper);
        String rawError = "Code:[DBUtilErrorCode-05], Description:[数据库写入错误]. - java.sql.SQLException: 逐行写入失败\n"
                + "\tat com.jdragon.aggregation.rdbms.writer.CommonRdbmsWriter.doOneInsert(CommonRdbmsWriter.java:530)\n"
                + "Caused by: java.sql.SQLException: Field 'audit_required' doesn't have a default value\n"
                + "\tat com.mysql.cj.jdbc.ClientPreparedStatement.executeInternal(ClientPreparedStatement.java:916)";

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(101L);
        event.setEventType("FAILED");
        event.setOccurredAt(LocalDateTime.of(2026, 6, 21, 11, 0, 0));
        event.getPayload().put("error", rawError);
        event.getPayload().put("message", "COLLECTION_TASK node failed in 12 ms (mysql -> mysql): " + rawError);
        event.getPayload().put("stackTrace", rawError);
        event.getPayload().put("exceptionType", "java.sql.SQLException");

        service.publish(event);

        ArgumentCaptor<RunRecordEntity> captor = ArgumentCaptor.forClass(RunRecordEntity.class);
        verify(runRecordMapper).updateById(captor.capture());
        RunRecordEntity updatedRun = captor.getValue();
        assertEquals("Field 'audit_required' doesn't have a default value", updatedRun.getMessage());
        assertCleanFailureText(updatedRun.getMessage());
        assertEquals("Field 'audit_required' doesn't have a default value", updatedRun.getPayloadJson().get("error"));
        assertEquals("Field 'audit_required' doesn't have a default value", updatedRun.getPayloadJson().get("message"));
        assertEquals("Field 'audit_required' doesn't have a default value", updatedRun.getPayloadJson().get("stackTrace"));
        assertEquals("SQLException", updatedRun.getPayloadJson().get("exceptionType"));
    }

    @Test
    void shouldSanitizeEmbeddedExceptionPrefixBeforePersistingFailureEvent() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity existingRun = new RunRecordEntity();
        existingRun.setId(102L);
        existingRun.setStatus("RUNNING");
        when(runRecordMapper.selectById(eq(102L))).thenReturn(existingRun);

        ExecutionEventService service = service(runRecordMapper);

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(102L);
        event.setEventType("FAILED");
        event.setOccurredAt(LocalDateTime.of(2026, 6, 21, 11, 5, 0));
        event.getPayload().put("error", "java.sql.SQLSyntaxErrorException: Unknown column 'contract_amount' in 'field list'");
        event.getPayload().put("summary", Map.of(
                "errorMessage",
                "COLLECTION_TASK node failed in 8 ms (mysql -> mysql): java.sql.SQLSyntaxErrorException: Unknown column 'contract_amount' in 'field list'"));

        service.publish(event);

        ArgumentCaptor<RunRecordEntity> captor = ArgumentCaptor.forClass(RunRecordEntity.class);
        verify(runRecordMapper).updateById(captor.capture());
        RunRecordEntity updatedRun = captor.getValue();
        assertEquals("Unknown column 'contract_amount' in 'field list'", updatedRun.getMessage());
        assertCleanFailureText(updatedRun.getMessage());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) updatedRun.getPayloadJson().get("summary");
        assertEquals("Unknown column 'contract_amount' in 'field list'", summary.get("errorMessage"));
    }

    private ExecutionEventService service(RunRecordMapper runRecordMapper) {
        return new ExecutionEventService(
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
                mock(CollectionTaskIncrementalStateService.class),
                mock(StaleExecutionRecoveryService.class));
    }

    private void assertCleanFailureText(String message) {
        assertFalse(message.contains("java."));
        assertFalse(message.contains("com.jdragon"));
        assertFalse(message.contains(".java:"));
        assertFalse(message.contains("\tat "));
    }
}
