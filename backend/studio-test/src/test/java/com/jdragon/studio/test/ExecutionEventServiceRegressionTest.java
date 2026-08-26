package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.service.CollectionTaskIncrementalStateService;
import com.jdragon.studio.infra.service.DataModelLineageService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.ExecutionEventService;
import com.jdragon.studio.infra.service.FollowSubscriptionService;
import com.jdragon.studio.infra.service.FileTransferStateMutationService;
import com.jdragon.studio.infra.service.NotificationCommand;
import com.jdragon.studio.infra.service.NotificationService;
import com.jdragon.studio.infra.service.QualityIssueService;
import com.jdragon.studio.infra.service.RunMetricSummaryMapper;
import com.jdragon.studio.infra.service.StaleExecutionRecoveryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ExecutionEventServiceRegressionTest {

    @BeforeAll
    static void initializeFileTransferRunMetadata() {
        if (TableInfoHelper.getTableInfo(FileTransferRunEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "execution-event-file-transfer-test"),
                    FileTransferRunEntity.class);
        }
    }

    @Test
    void shouldRequireFileTransferMutationServiceInSpringRuntime() throws Exception {
        Method injectionPoint = ExecutionEventService.class.getDeclaredMethod(
                "setFileTransferStateMutationService", FileTransferStateMutationService.class);

        Autowired autowired = injectionPoint.getAnnotation(Autowired.class);

        assertTrue(autowired != null && autowired.required());
    }

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
        verify(runRecordMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        RunRecordEntity updatedRun = captor.getValue();
        assertEquals("FAILED", updatedRun.getStatus());
        assertEquals(2000, updatedRun.getMessage().length());
        assertTrue(updatedRun.getMessage().endsWith("..."));
    }

    @Test
    void shouldIgnoreLateTerminalEventAfterManualTermination() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity terminated = new RunRecordEntity();
        terminated.setId(101L);
        terminated.setStatus("FAILED");
        terminated.setTerminationRequested(1);
        terminated.getPayloadJson().put("errorCode", "USER_TERMINATED");
        when(runRecordMapper.selectById(eq(101L))).thenReturn(terminated);

        DispatchService dispatchService = mock(DispatchService.class);
        ExecutionEventService service = new ExecutionEventService(
                runRecordMapper,
                mock(FileTransferRunMapper.class),
                mock(DispatchTaskMapper.class),
                mock(CollectionTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class),
                dispatchService,
                mock(RunMetricSummaryMapper.class),
                mock(FollowSubscriptionService.class),
                mock(NotificationService.class),
                mock(DataModelLineageService.class),
                mock(QualityIssueService.class),
                mock(CollectionTaskIncrementalStateService.class),
                mock(StaleExecutionRecoveryService.class));

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(101L);
        event.setEventType("SUCCESS");
        service.publish(event);

        verify(runRecordMapper, never()).update(any(RunRecordEntity.class), any());
        verify(dispatchService, never()).continueWorkflowRun(any(ExecutionEvent.class));
    }

    @Test
    void shouldIgnoreLateRunningEventAfterManualTermination() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity terminated = new RunRecordEntity();
        terminated.setId(107L);
        terminated.setStatus("FAILED");
        terminated.setTerminationRequested(1);
        terminated.getPayloadJson().put("errorCode", "USER_TERMINATED");
        terminated.getResultJson().put("errorCode", "USER_TERMINATED");
        when(runRecordMapper.selectById(eq(107L))).thenReturn(terminated);

        DispatchService dispatchService = mock(DispatchService.class);
        ExecutionEventService service = new ExecutionEventService(
                runRecordMapper,
                mock(FileTransferRunMapper.class),
                mock(DispatchTaskMapper.class),
                mock(CollectionTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class),
                dispatchService,
                mock(RunMetricSummaryMapper.class),
                mock(FollowSubscriptionService.class),
                mock(NotificationService.class),
                mock(DataModelLineageService.class),
                mock(QualityIssueService.class),
                mock(CollectionTaskIncrementalStateService.class),
                mock(StaleExecutionRecoveryService.class));

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(107L);
        event.setEventType("RUNNING");
        event.getPayload().put("message", "late heartbeat");
        service.publish(event);

        verify(runRecordMapper, never()).update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class));
        verify(dispatchService, never()).continueWorkflowRun(any(ExecutionEvent.class));
        assertEquals("FAILED", terminated.getStatus());
        assertEquals("USER_TERMINATED", terminated.getPayloadJson().get("errorCode"));
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
        verify(runRecordMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
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
        verify(runRecordMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        RunRecordEntity updatedRun = captor.getValue();
        assertEquals("Unknown column 'contract_amount' in 'field list'", updatedRun.getMessage());
        assertCleanFailureText(updatedRun.getMessage());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) updatedRun.getPayloadJson().get("summary");
        assertEquals("Unknown column 'contract_amount' in 'field list'", summary.get("errorMessage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistPluginRevisionsInExistingRunPayloadAndResult() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity existingRun = new RunRecordEntity();
        existingRun.setId(103L);
        existingRun.setStatus("RUNNING");
        when(runRecordMapper.selectById(eq(103L))).thenReturn(existingRun);

        ExecutionEventService service = service(runRecordMapper);
        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(103L);
        event.setEventType("SUCCESS");
        event.setOccurredAt(LocalDateTime.of(2026, 7, 31, 16, 30, 0));
        event.getPayload().put("pluginRevisions", Map.of(
                "reader/mysql8reader", "codex-e2e-v2-identity",
                "writer/mysql8writer", "codex-e2e-v2-identity"));

        service.publish(event);

        ArgumentCaptor<RunRecordEntity> captor = ArgumentCaptor.forClass(RunRecordEntity.class);
        verify(runRecordMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        RunRecordEntity updatedRun = captor.getValue();
        assertEquals("codex-e2e-v2-identity",
                ((Map<String, String>) updatedRun.getPayloadJson().get("pluginRevisions"))
                        .get("reader/mysql8reader"));
        assertEquals("codex-e2e-v2-identity",
                ((Map<String, String>) updatedRun.getResultJson().get("pluginRevisions"))
                        .get("writer/mysql8writer"));
    }

    @Test
    void shouldMarkFileTransferRunFailedWhenDispatchFailsBeforeExecutor() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity existingRun = new RunRecordEntity();
        existingRun.setId(104L);
        existingRun.setStatus("RUNNING");
        when(runRecordMapper.selectById(eq(104L))).thenReturn(existingRun);
        FileTransferRunMapper fileTransferRunMapper = mock(FileTransferRunMapper.class);
        FileTransferStateMutationService mutationService = mock(FileTransferStateMutationService.class);
        ExecutionEventService service = service(runRecordMapper, fileTransferRunMapper);
        ReflectionTestUtils.setField(service, "fileTransferStateMutationService", mutationService);

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(104L);
        event.setFileTransferRunId(204L);
        event.setProjectId(10L);
        event.setExecutionType(DispatchExecutionType.FILE_TRANSFER);
        event.setEventType("FAILED");
        event.setOccurredAt(LocalDateTime.of(2026, 8, 8, 9, 18, 58));
        event.getPayload().put("error", "File transfer dispatch run identity is incomplete");

        service.publish(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mutationService).updateRunAndEvent(eq(204L), captor.capture(), eq(false), eq(true));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("FAILED"));
        assertTrue(captor.getValue().getSqlSet().contains("status"));
        verify(fileTransferRunMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void shouldWriteFileTransferExecutionFailureThroughOutboxMutationBoundary() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity existingRun = new RunRecordEntity();
        existingRun.setId(105L);
        existingRun.setStatus("RUNNING");
        when(runRecordMapper.selectById(105L)).thenReturn(existingRun);
        FileTransferRunMapper fileTransferRunMapper = mock(FileTransferRunMapper.class);
        FileTransferStateMutationService mutationService = mock(FileTransferStateMutationService.class);
        ExecutionEventService service = service(runRecordMapper, fileTransferRunMapper);
        ReflectionTestUtils.setField(service, "fileTransferStateMutationService", mutationService);

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(105L);
        event.setFileTransferRunId(205L);
        event.setProjectId(10L);
        event.setExecutionType(DispatchExecutionType.FILE_TRANSFER);
        event.setEventType("FAILED");
        event.setOccurredAt(LocalDateTime.of(2026, 8, 12, 14, 0));
        event.getPayload().put("error", "Worker stopped before file executor startup");

        service.publish(event);

        verify(mutationService).updateRunAndEvent(eq(205L), any(LambdaUpdateWrapper.class),
                eq(false), eq(true));
        verify(fileTransferRunMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void shouldNotOverwriteQueuedFileTransferRestartWithPreviousDispatchFailure() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity existingRun = new RunRecordEntity();
        existingRun.setId(106L);
        existingRun.setStatus("RUNNING");
        when(runRecordMapper.selectById(106L)).thenReturn(existingRun);
        FileTransferRunMapper fileTransferRunMapper = mock(FileTransferRunMapper.class);
        FileTransferStateMutationService mutationService = mock(FileTransferStateMutationService.class);
        ExecutionEventService service = service(runRecordMapper, fileTransferRunMapper);
        ReflectionTestUtils.setField(service, "fileTransferStateMutationService", mutationService);

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(106L);
        event.setFileTransferRunId(206L);
        event.setProjectId(10L);
        event.setExecutionType(DispatchExecutionType.FILE_TRANSFER);
        event.setEventType("FAILED");
        event.setOccurredAt(LocalDateTime.of(2026, 8, 12, 15, 0));
        event.getPayload().put("error", "Task was interrupted by worker restart before completion");
        event.getPayload().put("fileTransferRestartRecoveryEligible", Boolean.TRUE);

        service.publish(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunEntity>> update =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mutationService).updateRunAndEvent(eq(206L), update.capture(), eq(false), eq(true));
        assertFalse(update.getValue().getSqlSet().contains("status"));
        assertTrue(update.getValue().getSqlSet().contains("run_record_id"));
    }

    @Test
    void sharedWorkflowFollowerNotificationShouldTargetReadableProject() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity existingRun = new RunRecordEntity();
        existingRun.setId(200L);
        existingRun.setTenantId(StudioConstants.DEFAULT_TENANT_ID);
        existingRun.setProjectId(10L);
        existingRun.setWorkflowDefinitionId(300L);
        existingRun.setWorkflowRunId(900L);
        existingRun.setStatus("RUNNING");
        when(runRecordMapper.selectById(eq(200L))).thenReturn(existingRun);
        when(runRecordMapper.selectCount(any())).thenReturn(0L);

        WorkflowDefinitionMapper workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId(300L);
        workflow.setTenantId(StudioConstants.DEFAULT_TENANT_ID);
        workflow.setProjectId(10L);
        workflow.setName("长期回归-S20共享关注通知可达流程");
        when(workflowDefinitionMapper.selectOne(any())).thenReturn(workflow);

        FollowSubscriptionService followSubscriptionService = mock(FollowSubscriptionService.class);
        when(followSubscriptionService.followerUserProjectIds(
                eq(StudioConstants.DEFAULT_TENANT_ID),
                eq(10L),
                eq(StudioConstants.FOLLOW_TARGET_WORKFLOW),
                eq(300L))).thenReturn(Map.of(501L, 20L));
        when(followSubscriptionService.followerUserIds(
                eq(StudioConstants.DEFAULT_TENANT_ID),
                eq(10L),
                eq(StudioConstants.FOLLOW_TARGET_WORKFLOW_RUN),
                eq(900L))).thenReturn(Collections.emptyList());

        NotificationService notificationService = mock(NotificationService.class);
        ExecutionEventService service = service(runRecordMapper, workflowDefinitionMapper, followSubscriptionService, notificationService);

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(200L);
        event.setEventType("SUCCESS");
        event.setExecutionType(DispatchExecutionType.WORKFLOW_NODE);
        event.setWorkflowDefinitionId(300L);
        event.setWorkflowRunId(900L);
        event.setProjectId(10L);
        event.setOccurredAt(LocalDateTime.of(2026, 6, 22, 6, 0, 0));

        service.publish(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> recipientsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<NotificationCommand> commandCaptor = ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notificationService).notifyUsers(recipientsCaptor.capture(), commandCaptor.capture());
        assertEquals(List.of(501L), recipientsCaptor.getValue());
        NotificationCommand command = commandCaptor.getValue();
        assertEquals(20L, command.getTargetProjectId());
        assertEquals("/workflows/300", command.getTargetPath());
        assertEquals(StudioConstants.FOLLOW_TARGET_WORKFLOW_RUN, command.getTargetType());
        assertEquals(900L, command.getTargetId());
    }

    private ExecutionEventService service(RunRecordMapper runRecordMapper) {
        return service(runRecordMapper, mock(FileTransferRunMapper.class),
                mock(WorkflowDefinitionMapper.class),
                mock(FollowSubscriptionService.class),
                mock(NotificationService.class));
    }

    private ExecutionEventService service(RunRecordMapper runRecordMapper,
                                          FileTransferRunMapper fileTransferRunMapper) {
        return service(runRecordMapper, fileTransferRunMapper,
                mock(WorkflowDefinitionMapper.class),
                mock(FollowSubscriptionService.class),
                mock(NotificationService.class));
    }

    private ExecutionEventService service(RunRecordMapper runRecordMapper,
                                          WorkflowDefinitionMapper workflowDefinitionMapper,
                                          FollowSubscriptionService followSubscriptionService,
                                          NotificationService notificationService) {
        return service(runRecordMapper, mock(FileTransferRunMapper.class), workflowDefinitionMapper,
                followSubscriptionService, notificationService);
    }

    private ExecutionEventService service(RunRecordMapper runRecordMapper,
                                          FileTransferRunMapper fileTransferRunMapper,
                                          WorkflowDefinitionMapper workflowDefinitionMapper,
                                          FollowSubscriptionService followSubscriptionService,
                                          NotificationService notificationService) {
        when(runRecordMapper.update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        return new ExecutionEventService(
                runRecordMapper,
                fileTransferRunMapper,
                mock(DispatchTaskMapper.class),
                mock(CollectionTaskDefinitionMapper.class),
                workflowDefinitionMapper,
                mock(DispatchService.class),
                mock(RunMetricSummaryMapper.class),
                followSubscriptionService,
                notificationService,
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
