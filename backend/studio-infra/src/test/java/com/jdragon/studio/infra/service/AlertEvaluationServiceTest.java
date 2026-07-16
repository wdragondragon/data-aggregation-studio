package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DataIngestionAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessCounterEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.AlertIncidentMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.model.AlertObservation;
import com.jdragon.studio.infra.model.AlertSignal;
import com.jdragon.studio.infra.model.WorkflowRunOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertEvaluationServiceTest {

    @Test
    void shouldIgnoreInvocationSignalWhenANewerCommittedObservationExists() {
        Fixture fixture = new Fixture();
        LocalDateTime now = LocalDateTime.now();
        AlertRuleEntity rule = rule("INVOCATION_WRITE_FAILED", "DATA_INGESTION_SERVICE");
        when(fixture.ruleService.enabledRules(any(), any(), eq("INVOCATION_WRITE_FAILED"), any(), any()))
                .thenReturn(Collections.singletonList(rule));
        DataIngestionAccessLogEntity latest = new DataIngestionAccessLogEntity();
        latest.setId(102L);
        latest.setOccurredAt(now.plusSeconds(1));
        when(fixture.ingestionLogMapper.selectOne(any())).thenReturn(latest);

        fixture.service.evaluateSignal(invocationSignal(101L, now));

        verify(fixture.incidentService, never()).recordCondition(any(), any());
        verify(fixture.ruleService, never()).markEvaluation(any(), any(), any());
    }

    @Test
    void shouldNotQueryExecutionFreshnessWithoutMatchingRules() {
        Fixture fixture = new Fixture();

        fixture.service.evaluateSignal(executionSignal(101L, LocalDateTime.now()));

        verify(fixture.runRecordMapper, never()).selectOne(any());
        verify(fixture.runRecordMapper, never()).selectRecentWorkflowRunOutcomes(any(), any(), any(), any(Integer.class));
        verify(fixture.incidentService, never()).recordCondition(any(), any());
    }

    @Test
    void shouldNotQueryInvocationFreshnessWithoutMatchingRules() {
        Fixture fixture = new Fixture();

        fixture.service.evaluateSignal(invocationSignal(101L, LocalDateTime.now()));

        verify(fixture.ingestionLogMapper, never()).selectOne(any());
        verify(fixture.incidentService, never()).recordCondition(any(), any());
    }

    @Test
    void shouldMarkMatchingExecutionRulesWhenFreshnessCheckFails() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = rule("EXECUTION_FAILED", "COLLECTION_TASK");
        when(fixture.ruleService.enabledRules(any(), any(), eq("EXECUTION_FAILED"), any(), any()))
                .thenReturn(Collections.singletonList(rule));
        when(fixture.runRecordMapper.selectOne(any()))
                .thenThrow(new IllegalStateException("simulated run lookup failure"));

        fixture.service.evaluateSignal(executionSignal(101L, LocalDateTime.now()));

        verify(fixture.incidentService, never()).recordCondition(any(), any());
        verify(fixture.ruleService).markEvaluation(eq(rule), eq("ERROR"), contains("simulated run lookup failure"));
    }

    @Test
    void shouldMarkMatchingInvocationRulesWhenFreshnessCheckFails() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = rule("INVOCATION_WRITE_FAILED", "DATA_INGESTION_SERVICE");
        when(fixture.ruleService.enabledRules(any(), any(), eq("INVOCATION_WRITE_FAILED"), any(), any()))
                .thenReturn(Collections.singletonList(rule));
        when(fixture.ingestionLogMapper.selectOne(any()))
                .thenThrow(new IllegalStateException("simulated invocation lookup failure"));

        fixture.service.evaluateSignal(invocationSignal(101L, LocalDateTime.now()));

        verify(fixture.incidentService, never()).recordCondition(any(), any());
        verify(fixture.ruleService).markEvaluation(eq(rule), eq("ERROR"), contains("simulated invocation lookup failure"));
    }

    @Test
    void shouldIgnoreEventSignalsWhenEvaluationIsDisabled() {
        Fixture fixture = new Fixture();
        fixture.properties.getAlert().setEvaluationEnabled(false);

        fixture.service.evaluateSignal(executionSignal(101L, LocalDateTime.now()));

        verify(fixture.ruleService, never()).enabledRules(any(), any(), any(), any(), any());
        verify(fixture.incidentService, never()).recordCondition(any(), any());
    }

    @Test
    void shouldNotOverwriteEventRuleEvaluationStateDuringPeriodicScan() {
        Fixture fixture = new Fixture();
        AlertRuleEntity eventRule = rule("EXECUTION_FAILED", "COLLECTION_TASK");
        when(fixture.ruleService.enabledRulesForEvaluation()).thenReturn(Collections.singletonList(eventRule));

        fixture.service.evaluateAll();

        verify(fixture.ruleService, never()).markEvaluation(any(), any(), any());
        verify(fixture.incidentService, never()).recordCondition(any(), any());
    }

    @Test
    void shouldNotOverwriteLogRuleEvaluationStateForSupersededSignal() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = rule("LOG_UPLOAD_FAILED", "LOG_STORAGE");
        rule.setConditionJson(Map.of("domains", Collections.singletonList("DATA_SERVICE_LOG")));
        when(fixture.ruleService.enabledRules(any(), any(), eq("LOG_UPLOAD_FAILED"), any(), any()))
                .thenReturn(Collections.singletonList(rule));
        LocalDateTime now = LocalDateTime.now();
        DataServiceAccessLogEntity latest = new DataServiceAccessLogEntity();
        latest.setId(102L);
        latest.setOccurredAt(now.plusSeconds(1));
        latest.setLogArchiveStatus("AVAILABLE");
        when(fixture.dataServiceLogMapper.selectOne(any())).thenReturn(latest);

        fixture.service.evaluateSignal(logSignal(101L, now, "FAILED"));

        verify(fixture.incidentService, never()).recordCondition(any(), any());
        verify(fixture.ruleService, never()).markEvaluation(any(), any(), any());
    }

    @Test
    void shouldMarkMatchingLogRulesWhenFreshnessCheckFails() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = rule("LOG_UPLOAD_FAILED", "LOG_STORAGE");
        rule.setConditionJson(Map.of("domains", Collections.singletonList("DATA_SERVICE_LOG")));
        when(fixture.ruleService.enabledRules(any(), any(), eq("LOG_UPLOAD_FAILED"), any(), any()))
                .thenReturn(Collections.singletonList(rule));
        when(fixture.dataServiceLogMapper.selectOne(any()))
                .thenThrow(new IllegalStateException("simulated log lookup failure"));

        fixture.service.evaluateSignal(logSignal(101L, LocalDateTime.now(), "FAILED"));

        verify(fixture.incidentService, never()).recordCondition(any(), any());
        verify(fixture.ruleService).markEvaluation(eq(rule), eq("ERROR"), contains("simulated log lookup failure"));
    }

    @Test
    void shouldEvaluateExecutionFailed() {
        Fixture fixture = new Fixture();
        LocalDateTime now = LocalDateTime.now();
        AlertRuleEntity rule = rule("EXECUTION_FAILED", "COLLECTION_TASK");
        RunRecordEntity latest = run(101L, now, "FAILED");
        when(fixture.runRecordMapper.selectOne(any())).thenReturn(latest);
        when(fixture.ruleService.enabledRules(any(), any(), eq("EXECUTION_FAILED"), any(), any()))
                .thenReturn(Collections.singletonList(rule));

        fixture.service.evaluateSignal(executionSignal(101L, now));

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldContinueEvaluatingSignalRulesAfterOneRuleFails() {
        Fixture fixture = new Fixture();
        AlertRuleEntity broken = rule("EXECUTION_FAILED", "COLLECTION_TASK");
        AlertRuleEntity healthy = rule("EXECUTION_FAILED", "COLLECTION_TASK");
        healthy.setId(11L);
        when(fixture.ruleService.enabledRules(any(), any(), eq("EXECUTION_FAILED"), any(), any()))
                .thenReturn(List.of(broken, healthy));
        when(fixture.incidentService.recordCondition(any(), any()))
                .thenThrow(new IllegalStateException("simulated incident failure"))
                .thenReturn(null);

        fixture.service.evaluateSignal(executionSignal(101L, LocalDateTime.now()));

        verify(fixture.incidentService, times(2)).recordCondition(any(), any());
        verify(fixture.ruleService).markEvaluation(eq(broken), eq("ERROR"), contains("simulated incident failure"));
        verify(fixture.ruleService).markEvaluation(eq(healthy), eq("SUCCESS"), isNull());
    }

    @Test
    void shouldEvaluateConsecutiveFailures() {
        Fixture fixture = new Fixture();
        LocalDateTime now = LocalDateTime.now();
        AlertRuleEntity rule = rule("CONSECUTIVE_FAILURES", "COLLECTION_TASK");
        rule.setConditionJson(Map.of("consecutiveCount", 3));
        when(fixture.runRecordMapper.selectOne(any())).thenReturn(run(103L, now, "FAILED"));
        when(fixture.runRecordMapper.selectList(any())).thenReturn(List.of(
                run(103L, now, "FAILED"), run(102L, now.minusMinutes(1), "ERROR"), run(101L, now.minusMinutes(2), "FAILED")));
        when(fixture.ruleService.enabledRules(any(), any(), eq("CONSECUTIVE_FAILURES"), any(), any()))
                .thenReturn(Collections.singletonList(rule));

        fixture.service.evaluateSignal(executionSignal(103L, now));

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldCountOnlyCompletedWorkflowRunsReturnedByOutcomeQuery() {
        Fixture fixture = new Fixture();
        LocalDateTime now = LocalDateTime.now();
        AlertRuleEntity rule = rule("CONSECUTIVE_FAILURES", "WORKFLOW");
        rule.setConditionJson(Map.of("consecutiveCount", 4));
        WorkflowRunOutcome current = workflowOutcome(103L, now, true);
        when(fixture.runRecordMapper.selectRecentWorkflowRunOutcomes(any(), any(), any(), eq(1)))
                .thenReturn(Collections.singletonList(current));
        when(fixture.runRecordMapper.selectRecentWorkflowRunOutcomes(any(), any(), any(), eq(20)))
                .thenReturn(List.of(current, workflowOutcome(102L, now.minusMinutes(1), true),
                        workflowOutcome(101L, now.minusMinutes(2), true)));
        when(fixture.ruleService.enabledRules(any(), any(), eq("CONSECUTIVE_FAILURES"), any(), any()))
                .thenReturn(Collections.singletonList(rule));
        AlertSignal signal = executionSignal(203L, now)
                .setSubjectType("WORKFLOW")
                .setSubjectName("daily workflow")
                .setEvidence(new LinkedHashMap<String, Object>(Map.of("targetRunId", 103L)));

        fixture.service.evaluateSignal(signal);

        ArgumentCaptor<AlertObservation> captor = ArgumentCaptor.forClass(AlertObservation.class);
        verify(fixture.incidentService).recordCondition(eq(rule), captor.capture());
        assertFalse(captor.getValue().isActive());
    }

    @Test
    void shouldSupportTwentyConsecutiveWorkflowFailures() {
        Fixture fixture = new Fixture();
        LocalDateTime now = LocalDateTime.now();
        AlertRuleEntity rule = rule("CONSECUTIVE_FAILURES", "WORKFLOW");
        rule.setConditionJson(Map.of("consecutiveCount", 20));
        List<WorkflowRunOutcome> outcomes = new java.util.ArrayList<WorkflowRunOutcome>();
        for (long id = 120L; id >= 101L; id--) {
            outcomes.add(workflowOutcome(id, now.minusMinutes(120L - id), true));
        }
        when(fixture.runRecordMapper.selectRecentWorkflowRunOutcomes(any(), any(), any(), eq(1)))
                .thenReturn(Collections.singletonList(outcomes.get(0)));
        when(fixture.runRecordMapper.selectRecentWorkflowRunOutcomes(any(), any(), any(), eq(20)))
                .thenReturn(outcomes);
        when(fixture.ruleService.enabledRules(any(), any(), eq("CONSECUTIVE_FAILURES"), any(), any()))
                .thenReturn(Collections.singletonList(rule));
        AlertSignal signal = executionSignal(999L, now)
                .setSubjectType("WORKFLOW")
                .setSubjectName("large workflow")
                .setEvidence(new LinkedHashMap<String, Object>(Map.of("targetRunId", 120L)));

        fixture.service.evaluateSignal(signal);

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldEvaluateInvocationWriteFailed() {
        Fixture fixture = new Fixture();
        LocalDateTime now = LocalDateTime.now();
        AlertRuleEntity rule = rule("INVOCATION_WRITE_FAILED", "DATA_INGESTION_SERVICE");
        DataIngestionAccessLogEntity latest = new DataIngestionAccessLogEntity();
        latest.setId(101L);
        latest.setOccurredAt(now);
        when(fixture.ingestionLogMapper.selectOne(any())).thenReturn(latest);
        when(fixture.ruleService.enabledRules(any(), any(), eq("INVOCATION_WRITE_FAILED"), any(), any()))
                .thenReturn(Collections.singletonList(rule));

        fixture.service.evaluateSignal(invocationSignal(101L, now));

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldEvaluateRunTimeout() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "RUN_TIMEOUT", "COLLECTION_TASK", Map.of("durationMinutes", 30));
        RunRecordEntity run = run(101L, LocalDateTime.now().minusHours(1), "RUNNING");
        run.setStartedAt(LocalDateTime.now().minusHours(1));
        run.setCollectionTaskId(30L);
        when(fixture.runRecordMapper.selectList(any())).thenReturn(Collections.singletonList(run));
        when(fixture.collectionMapper.selectOne(any())).thenReturn(collectionTask());

        fixture.service.evaluateAll();

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldEvaluateServiceFailureRate() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "SERVICE_FAILURE_RATE", "DATA_SERVICE",
                Map.of("windowHours", 1, "failureRatePercent", 20, "minimumRequests", 20));
        DataServiceDefinitionEntity service = new DataServiceDefinitionEntity();
        service.setId(30L);
        service.setServiceName("orders api");
        when(fixture.dataServiceMapper.selectList(any())).thenReturn(Collections.singletonList(service));
        DataServiceAccessCounterEntity failed = new DataServiceAccessCounterEntity();
        failed.setSuccess(0);
        failed.setAccessCount(4L);
        DataServiceAccessCounterEntity succeeded = new DataServiceAccessCounterEntity();
        succeeded.setSuccess(1);
        succeeded.setAccessCount(16L);
        when(fixture.dataServiceCounterMapper.selectList(any())).thenReturn(List.of(failed, succeeded));

        fixture.service.evaluateAll();

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldEvaluateWorkerOffline() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "WORKER_OFFLINE", "WORKER_GROUP", Map.of("offlineSeconds", 120));
        ProjectWorkerBindingEntity binding = new ProjectWorkerBindingEntity();
        binding.setWorkerGroupCode("default-group");
        binding.setEnabled(1);
        when(fixture.workerBindingMapper.selectList(any())).thenReturn(Collections.singletonList(binding));
        when(fixture.workerLeaseMapper.selectList(any())).thenReturn(Collections.emptyList());

        fixture.service.evaluateAll();

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldReportOnlyValidWorkerLeasesAsOnlineInstances() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "WORKER_OFFLINE", "WORKER_GROUP", Map.of("offlineSeconds", 120));
        ProjectWorkerBindingEntity binding = new ProjectWorkerBindingEntity();
        binding.setWorkerGroupCode("default-group");
        binding.setEnabled(1);
        when(fixture.workerBindingMapper.selectList(any())).thenReturn(Collections.singletonList(binding));
        WorkerLeaseEntity expiredLease = new WorkerLeaseEntity();
        expiredLease.setTenantId("default");
        expiredLease.setWorkerGroupCode("default-group");
        expiredLease.setStatus("ONLINE");
        expiredLease.setLastHeartbeatAt(LocalDateTime.now().minusMinutes(5));
        expiredLease.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(fixture.workerLeaseMapper.selectList(any())).thenReturn(Collections.singletonList(expiredLease));

        fixture.service.evaluateAll();

        ArgumentCaptor<AlertObservation> observationCaptor = ArgumentCaptor.forClass(AlertObservation.class);
        verify(fixture.incidentService).recordCondition(eq(rule), observationCaptor.capture());
        AlertObservation observation = observationCaptor.getValue();
        assertThat(observation.isActive()).isTrue();
        assertThat(observation.getEvidence()).containsEntry("instanceCount", 0);
        assertThat(observation.getEvidence()).containsEntry("reportedOnlineLeaseCount", 1);
    }

    @Test
    void shouldTreatRecentlyHeartbeatingOfflineLeaseAsOffline() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "WORKER_OFFLINE", "WORKER_GROUP", Map.of("offlineSeconds", 120));
        ProjectWorkerBindingEntity binding = new ProjectWorkerBindingEntity();
        binding.setWorkerGroupCode("default-group");
        binding.setEnabled(1);
        when(fixture.workerBindingMapper.selectList(any())).thenReturn(Collections.singletonList(binding));
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setTenantId("default");
        lease.setWorkerGroupCode("default-group");
        lease.setStatus("OFFLINE");
        lease.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(1));
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(fixture.workerLeaseMapper.selectList(any())).thenReturn(Collections.singletonList(lease));

        fixture.service.evaluateAll();

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldIgnoreDisabledWorkerBindings() {
        Fixture fixture = new Fixture();
        periodicRule(fixture, "WORKER_OFFLINE", "WORKER_GROUP", Map.of("offlineSeconds", 120));
        ProjectWorkerBindingEntity binding = new ProjectWorkerBindingEntity();
        binding.setId(101L);
        binding.setWorkerGroupCode("disabled-group");
        binding.setEnabled(0);
        when(fixture.workerBindingMapper.selectList(any())).thenReturn(Collections.singletonList(binding));

        fixture.service.evaluateAll();

        verify(fixture.incidentService, never()).recordCondition(any(), any());
    }

    @Test
    void shouldMarkSpecificWorkerRuleAsErrorWhenBindingIsDisabled() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "WORKER_OFFLINE", "WORKER_GROUP", Map.of("offlineSeconds", 120));
        rule.setSubjectId(101L);
        ProjectWorkerBindingEntity binding = new ProjectWorkerBindingEntity();
        binding.setId(101L);
        binding.setWorkerGroupCode("disabled-group");
        binding.setEnabled(0);
        when(fixture.workerBindingMapper.selectList(any())).thenReturn(Collections.singletonList(binding));

        fixture.service.evaluateAll();

        verify(fixture.incidentService, never()).recordCondition(any(), any());
        verify(fixture.ruleService).markEvaluation(eq(rule), eq("ERROR"), contains("missing or disabled"));
    }

    @Test
    void shouldEvaluateQueueBacklog() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "QUEUE_BACKLOG", "PROJECT_QUEUE",
                Map.of("queuedCount", 2, "oldestWaitMinutes", 5));
        DispatchTaskEntity first = queuedTask(101L, LocalDateTime.now().minusMinutes(10));
        DispatchTaskEntity second = queuedTask(102L, LocalDateTime.now().minusMinutes(8));
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(List.of(first, second));

        fixture.service.evaluateAll();

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldEvaluatePeriodicRuleImmediatelyAfterActivation() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = rule("WORKER_OFFLINE", "WORKER_GROUP");
        rule.setConditionJson(new LinkedHashMap<String, Object>(Map.of("offlineSeconds", 120)));
        when(fixture.ruleService.enabledRules("default", 20L, "WORKER_OFFLINE", "WORKER_GROUP", null))
                .thenReturn(Collections.singletonList(rule));
        ProjectWorkerBindingEntity binding = new ProjectWorkerBindingEntity();
        binding.setId(101L);
        binding.setWorkerGroupCode("default-group");
        binding.setEnabled(1);
        when(fixture.workerBindingMapper.selectList(any())).thenReturn(Collections.singletonList(binding));
        when(fixture.workerLeaseMapper.selectList(any())).thenReturn(Collections.emptyList());
        AlertSignal signal = new AlertSignal()
                .setTenantId("default")
                .setProjectId(20L)
                .setSignalType("RULE_ACTIVATED")
                .setSubjectType("WORKER_GROUP")
                .setSubjectKey("10")
                .setStatus("WORKER_OFFLINE")
                .setSourceId("10")
                .setOccurredAt(LocalDateTime.now());

        fixture.service.evaluateSignal(signal);

        assertActiveObservation(fixture, rule);
        verify(fixture.ruleService).markEvaluation(eq(rule), eq("SUCCESS"), isNull());
    }

    @Test
    void shouldMeasureQueueWaitFromScheduledEligibilityInsteadOfCreationTime() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "QUEUE_BACKLOG", "PROJECT_QUEUE",
                Map.of("queuedCount", 2, "oldestWaitMinutes", 5));
        LocalDateTime now = LocalDateTime.now();
        DispatchTaskEntity first = queuedTask(101L, now.minusHours(2));
        first.setScheduledFireTime(now.minusMinutes(1));
        DispatchTaskEntity second = queuedTask(102L, now.minusHours(1));
        second.setScheduledFireTime(now.minusSeconds(30));
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(List.of(first, second));

        fixture.service.evaluateAll();

        ArgumentCaptor<AlertObservation> captor = ArgumentCaptor.forClass(AlertObservation.class);
        verify(fixture.incidentService).recordCondition(eq(rule), captor.capture());
        assertFalse(captor.getValue().isActive());
        assertTrue(((Number) captor.getValue().getEvidence().get("oldestWaitMinutes")).longValue() < 5L);
    }

    @Test
    void shouldEvaluateScheduleDelay() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "SCHEDULE_DELAY", "COLLECTION_TASK", Map.of("delayMinutes", 10));
        DispatchTaskEntity task = queuedTask(101L, LocalDateTime.now().minusMinutes(20));
        task.setExecutionType("COLLECTION_TASK");
        task.setCollectionTaskId(30L);
        task.setScheduledFireTime(LocalDateTime.now().minusMinutes(20));
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(fixture.collectionMapper.selectOne(any())).thenReturn(collectionTask());

        fixture.service.evaluateAll();

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldEvaluateLogUploadFailed() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "LOG_UPLOAD_FAILED", "LOG_STORAGE",
                Map.of("domains", Collections.singletonList("DATA_SERVICE_LOG")));
        DataServiceAccessLogEntity log = new DataServiceAccessLogEntity();
        log.setId(101L);
        log.setOccurredAt(LocalDateTime.now());
        log.setLogArchiveStatus("FAILED");
        when(fixture.dataServiceLogMapper.selectOne(any())).thenReturn(log);

        fixture.service.evaluateAll();

        assertActiveObservation(fixture, rule);
    }

    @Test
    void shouldRecoverLogDomainRemovedFromRuleConfiguration() {
        Fixture fixture = new Fixture();
        AlertRuleEntity rule = periodicRule(fixture, "LOG_UPLOAD_FAILED", "LOG_STORAGE",
                Map.of("domains", Collections.singletonList("DATA_INGESTION_LOG")));
        AlertIncidentEntity incident = new AlertIncidentEntity();
        incident.setTenantId("default");
        incident.setProjectId(20L);
        incident.setRuleId(rule.getId());
        incident.setConditionActive(1);
        incident.setSubjectType("LOG_STORAGE");
        incident.setSubjectKey("DATA_SERVICE_LOG");
        incident.setSubjectNameSnapshot("data service logs");
        when(fixture.incidentMapper.selectList(any())).thenReturn(Collections.singletonList(incident));

        fixture.service.evaluateAll();

        ArgumentCaptor<AlertObservation> captor = ArgumentCaptor.forClass(AlertObservation.class);
        verify(fixture.incidentService).recordCondition(eq(rule), captor.capture());
        assertFalse(captor.getValue().isActive());
        assertTrue("DATA_SERVICE_LOG".equals(captor.getValue().getSubjectKey()));
    }

    private void assertActiveObservation(Fixture fixture, AlertRuleEntity rule) {
        ArgumentCaptor<AlertObservation> captor = ArgumentCaptor.forClass(AlertObservation.class);
        verify(fixture.incidentService).recordCondition(eq(rule), captor.capture());
        assertTrue(captor.getValue().isActive());
    }

    private AlertRuleEntity periodicRule(Fixture fixture, String type, String subjectType, Map<String, Object> condition) {
        AlertRuleEntity rule = rule(type, subjectType);
        rule.setConditionJson(new LinkedHashMap<String, Object>(condition));
        when(fixture.ruleService.enabledRulesForEvaluation()).thenReturn(Collections.singletonList(rule));
        return rule;
    }

    private AlertRuleEntity rule(String type, String subjectType) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(10L);
        rule.setTenantId("default");
        rule.setProjectId(20L);
        rule.setName(type);
        rule.setRuleType(type);
        rule.setSubjectType(subjectType);
        rule.setSeverity("WARNING");
        rule.setConditionJson(new LinkedHashMap<String, Object>());
        return rule;
    }

    private AlertSignal executionSignal(Long sourceId, LocalDateTime occurredAt) {
        return new AlertSignal().setTenantId("default").setProjectId(20L).setSignalType("EXECUTION")
                .setSubjectType("COLLECTION_TASK").setSubjectId(30L).setSubjectKey("30").setSubjectName("orders")
                .setSourceId(String.valueOf(sourceId)).setSourceEventKey("execution:" + sourceId)
                .setOccurredAt(occurredAt).setSuccess(false).setStatus("FAILED");
    }

    private AlertSignal invocationSignal(Long sourceId, LocalDateTime occurredAt) {
        return new AlertSignal().setTenantId("default").setProjectId(20L).setSignalType("INVOCATION")
                .setSubjectType("DATA_INGESTION_SERVICE").setSubjectId(30L).setSubjectKey("30").setSubjectName("ingestion")
                .setSourceId(String.valueOf(sourceId)).setSourceEventKey("invocation:" + sourceId)
                .setOccurredAt(occurredAt).setSuccess(false).setFailureCount(1L).setStatus("FAILED");
    }

    private AlertSignal logSignal(Long sourceId, LocalDateTime occurredAt, String status) {
        return new AlertSignal().setTenantId("default").setProjectId(20L).setSignalType("LOG_ARCHIVE")
                .setSubjectType("LOG_STORAGE").setSubjectKey("DATA_SERVICE_LOG").setSubjectName("data service logs")
                .setSourceId(String.valueOf(sourceId)).setSourceEventKey("data-service-log:" + sourceId + ":" + status)
                .setOccurredAt(occurredAt).setSuccess("AVAILABLE".equals(status)).setStatus(status);
    }

    private RunRecordEntity run(Long id, LocalDateTime endedAt, String status) {
        RunRecordEntity run = new RunRecordEntity();
        run.setId(id);
        run.setTenantId("default");
        run.setProjectId(20L);
        run.setCollectionTaskId(30L);
        run.setStatus(status);
        run.setEndedAt(endedAt);
        return run;
    }

    private RunRecordEntity workflowRun(Long id, Long workflowRunId, LocalDateTime endedAt, String status) {
        RunRecordEntity run = run(id, endedAt, status);
        run.setCollectionTaskId(null);
        run.setWorkflowDefinitionId(30L);
        run.setWorkflowRunId(workflowRunId);
        return run;
    }

    private WorkflowRunOutcome workflowOutcome(Long workflowRunId, LocalDateTime observedAt, boolean failed) {
        WorkflowRunOutcome outcome = new WorkflowRunOutcome();
        outcome.setWorkflowRunId(workflowRunId);
        outcome.setObservedAt(observedAt);
        outcome.setFailed(failed ? 1 : 0);
        return outcome;
    }

    private CollectionTaskDefinitionEntity collectionTask() {
        CollectionTaskDefinitionEntity task = new CollectionTaskDefinitionEntity();
        task.setId(30L);
        task.setName("orders");
        return task;
    }

    private DispatchTaskEntity queuedTask(Long id, LocalDateTime createdAt) {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(id);
        task.setTenantId("default");
        task.setProjectId(20L);
        task.setStatus("QUEUED");
        task.setCreatedAt(createdAt);
        return task;
    }

    private final class Fixture {
        private final AlertRuleService ruleService = mock(AlertRuleService.class);
        private final AlertIncidentService incidentService = mock(AlertIncidentService.class);
        private final AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        private final StudioPlatformProperties properties = new StudioPlatformProperties();
        private final RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        private final DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        private final WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        private final ProjectWorkerBindingMapper workerBindingMapper = mock(ProjectWorkerBindingMapper.class);
        private final CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        private final DataServiceDefinitionMapper dataServiceMapper = mock(DataServiceDefinitionMapper.class);
        private final DataServiceAccessCounterMapper dataServiceCounterMapper = mock(DataServiceAccessCounterMapper.class);
        private final DataServiceAccessLogMapper dataServiceLogMapper = mock(DataServiceAccessLogMapper.class);
        private final DataIngestionAccessLogMapper ingestionLogMapper = mock(DataIngestionAccessLogMapper.class);
        private final AlertEvaluationService service;

        private Fixture() {
            service = new AlertEvaluationService(ruleService, incidentService, incidentMapper,
                    properties, runRecordMapper, dispatchTaskMapper, workerLeaseMapper,
                    workerBindingMapper, collectionMapper, mock(QualityTaskDefinitionMapper.class),
                    mock(WorkflowDefinitionMapper.class), dataServiceMapper, mock(DataIngestionServiceMapper.class),
                    mock(ProtocolConversionServiceMapper.class), dataServiceCounterMapper,
                    mock(DataIngestionAccessCounterMapper.class), mock(ProtocolConversionAccessCounterMapper.class),
                    dataServiceLogMapper, ingestionLogMapper, mock(ProtocolConversionAccessLogMapper.class));
        }
    }
}
