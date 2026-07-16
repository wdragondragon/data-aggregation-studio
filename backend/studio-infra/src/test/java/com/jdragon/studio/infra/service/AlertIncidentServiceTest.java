package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import com.jdragon.studio.infra.entity.AlertDeliveryEntity;
import com.jdragon.studio.infra.entity.AlertEventEntity;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.mapper.AlertChannelMapper;
import com.jdragon.studio.infra.mapper.AlertDeliveryMapper;
import com.jdragon.studio.infra.mapper.AlertEventMapper;
import com.jdragon.studio.infra.mapper.AlertIncidentMapper;
import com.jdragon.studio.infra.mapper.AlertRuleMapper;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.model.AlertObservation;
import com.jdragon.studio.dto.model.request.AlertIncidentActionRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertIncidentServiceTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(AlertRuleEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), AlertRuleEntity.class);
        }
    }

    @Test
    void shouldUseListTargetsWhenAlertSubjectIsNotSpecific() {
        assertEquals("/collection-task-runs", AlertIncidentService.targetPath("COLLECTION_TASK", null, null));
        assertEquals("/quality-task-runs", AlertIncidentService.targetPath("QUALITY_TASK", null, null));
        assertEquals("/workflows", AlertIncidentService.targetPath("WORKFLOW", null, null));
        assertEquals("/data-service-metrics/access-logs", AlertIncidentService.targetPath("DATA_SERVICE", null, null));
        assertEquals("/data-ingestion-metrics/access-logs",
                AlertIncidentService.targetPath("DATA_INGESTION_SERVICE", null, null));
        assertEquals("/protocol-conversions/access-logs",
                AlertIncidentService.targetPath("PROTOCOL_CONVERSION_SERVICE", null, null));
    }

    @Test
    void shouldTriggerSilenceAndRecoverWithoutNotificationStorm() {
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        AlertRecipientResolver recipientResolver = mock(AlertRecipientResolver.class);
        AtomicReference<AlertIncidentEntity> incidentState = new AtomicReference<AlertIncidentEntity>();
        List<AlertEventEntity> events = new ArrayList<AlertEventEntity>();
        List<AlertDeliveryEntity> deliveries = new ArrayList<AlertDeliveryEntity>();
        AtomicLong ids = new AtomicLong(100L);

        when(incidentMapper.selectOne(any())).thenAnswer(invocation -> incidentState.get());
        when(incidentMapper.updateById(any(AlertIncidentEntity.class))).thenReturn(1);
        when(eventMapper.selectCount(any())).thenReturn(0L);
        when(ruleMapper.updateById(any(AlertRuleEntity.class))).thenReturn(1);
        when(recipientResolver.resolve(any(), any())).thenReturn(Collections.singletonList(9L));
        doAnswer(invocation -> {
            AlertIncidentEntity entity = invocation.getArgument(0);
            entity.setId(ids.incrementAndGet());
            incidentState.set(entity);
            return 1;
        }).when(incidentMapper).insert(any(AlertIncidentEntity.class));
        doAnswer(invocation -> {
            AlertEventEntity entity = invocation.getArgument(0);
            entity.setId(ids.incrementAndGet());
            events.add(entity);
            return 1;
        }).when(eventMapper).insert(any(AlertEventEntity.class));
        doAnswer(invocation -> {
            AlertDeliveryEntity entity = invocation.getArgument(0);
            entity.setId(ids.incrementAndGet());
            deliveries.add(entity);
            return 1;
        }).when(deliveryMapper).insert(any(AlertDeliveryEntity.class));

        AlertIncidentService service = new AlertIncidentService(
                incidentMapper, eventMapper, deliveryMapper, ruleMapper, channelMapper,
                mock(ProjectMapper.class), mock(AlertRuleService.class), recipientResolver,
                mock(StudioSecurityService.class), mock(ProjectResourceAccessService.class));
        AlertRuleEntity rule = rule();
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        LocalDateTime now = LocalDateTime.now();

        AlertObservation initialObservation = observation(true, now);
        initialObservation.getEvidence().put("runRecordId", 9007199254740993L);
        initialObservation.getEvidence().put("apiKey", "plain-api-key");
        initialObservation.getEvidence().put("access_key", "plain-access-key");
        initialObservation.getEvidence().put("private-key", "plain-private-key");
        initialObservation.getEvidence().put("credential", "plain-credential");
        AlertIncidentEntity triggered = service.recordCondition(rule, initialObservation);
        assertEquals("OPEN", triggered.getStatus());
        assertEquals("TRIGGERED", events.get(0).getEventType());
        assertEquals(1, deliveries.size());
        assertNotNull(triggered.getLastNotifiedAt());
        Map<String, Object> payload = deliveries.get(0).getPayloadJson();
        assertEquals(String.valueOf(events.get(0).getId()), payload.get("eventId"));
        assertEquals("20", payload.get("projectId"));
        assertEquals("10", ((Map<?, ?>) payload.get("rule")).get("id"));
        assertEquals(String.valueOf(triggered.getId()), ((Map<?, ?>) payload.get("incident")).get("id"));
        assertEquals("30", ((Map<?, ?>) payload.get("subject")).get("id"));
        assertEquals("9007199254740993", ((Map<?, ?>) payload.get("evidence")).get("runRecordId"));
        assertEquals("******", triggered.getCurrentEvidenceJson().get("apiKey"));
        assertEquals("******", triggered.getCurrentEvidenceJson().get("access_key"));
        assertEquals("******", triggered.getCurrentEvidenceJson().get("private-key"));
        assertEquals("******", triggered.getCurrentEvidenceJson().get("credential"));
        assertEquals("******", ((Map<?, ?>) payload.get("evidence")).get("apiKey"));
        assertEquals("******", ((Map<?, ?>) payload.get("evidence")).get("access_key"));
        assertEquals("******", ((Map<?, ?>) payload.get("evidence")).get("private-key"));
        assertEquals("******", ((Map<?, ?>) payload.get("evidence")).get("credential"));
        verify(ruleMapper, never()).updateById(any(AlertRuleEntity.class));

        service.recordCondition(rule, observation(true, now.plusSeconds(30)));
        assertEquals(2, incidentState.get().getOccurrenceCount());
        assertEquals(1, events.size(), "quiet scheduled repeats should not create timeline noise");
        assertEquals(1, deliveries.size(), "silence window should suppress repeated delivery");

        service.recordCondition(rule, observation(false, now.plusMinutes(1)));
        assertEquals("RECOVERED", incidentState.get().getStatus());
        assertEquals("RECOVERED", events.get(1).getEventType());
        assertEquals(2, deliveries.size(), "recovery notification should be queued");

        int eventCountAfterRecovery = events.size();
        service.recordCondition(rule, observation(true, now.plusSeconds(45)));
        assertEquals("RECOVERED", incidentState.get().getStatus(), "an older failure must not reopen a newer recovery");
        assertEquals(eventCountAfterRecovery, events.size());

        service.recordCondition(rule, observation(true, now.plusMinutes(2)));
        assertEquals("OPEN", incidentState.get().getStatus());
        int eventCountAfterReopen = events.size();
        service.recordCondition(rule, observation(false, now.plusSeconds(90)));
        assertEquals("OPEN", incidentState.get().getStatus(), "an older success must not recover a newer failure");
        assertEquals(eventCountAfterReopen, events.size());
    }

    @Test
    void shouldAcknowledgeCloseSuppressAndReopenOnlyAfterRecovery() {
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        AlertRecipientResolver recipientResolver = mock(AlertRecipientResolver.class);
        AtomicReference<AlertIncidentEntity> incidentState = new AtomicReference<AlertIncidentEntity>();
        List<AlertEventEntity> events = new ArrayList<AlertEventEntity>();
        List<AlertDeliveryEntity> deliveries = new ArrayList<AlertDeliveryEntity>();
        AtomicLong ids = new AtomicLong(200L);

        when(incidentMapper.selectOne(any())).thenAnswer(invocation -> incidentState.get());
        when(incidentMapper.updateById(any(AlertIncidentEntity.class))).thenReturn(1);
        when(eventMapper.selectCount(any())).thenReturn(0L);
        when(eventMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(deliveryMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(ruleMapper.updateById(any(AlertRuleEntity.class))).thenReturn(1);
        when(recipientResolver.resolve(any(), any())).thenReturn(Collections.singletonList(9L));
        doAnswer(invocation -> {
            AlertIncidentEntity entity = invocation.getArgument(0);
            entity.setId(ids.incrementAndGet());
            incidentState.set(entity);
            return 1;
        }).when(incidentMapper).insert(any(AlertIncidentEntity.class));
        doAnswer(invocation -> {
            AlertEventEntity entity = invocation.getArgument(0);
            entity.setId(ids.incrementAndGet());
            events.add(entity);
            return 1;
        }).when(eventMapper).insert(any(AlertEventEntity.class));
        doAnswer(invocation -> {
            AlertDeliveryEntity entity = invocation.getArgument(0);
            entity.setId(ids.incrementAndGet());
            deliveries.add(entity);
            return 1;
        }).when(deliveryMapper).insert(any(AlertDeliveryEntity.class));

        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(7L);
        when(securityService.currentUsername()).thenReturn("operator");
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        AlertIncidentService service = new AlertIncidentService(
                incidentMapper, eventMapper, deliveryMapper, ruleMapper, channelMapper,
                mock(ProjectMapper.class), mock(AlertRuleService.class), recipientResolver,
                securityService, accessService);
        AlertRuleEntity rule = rule();
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        LocalDateTime now = LocalDateTime.now();

        AlertObservation initialObservation = observation(true, now);
        initialObservation.getEvidence().put("latestHeartbeatAt", now);
        AlertIncidentEntity incident = service.recordCondition(rule, initialObservation);
        assertEquals(now.toString(), incident.getCurrentEvidenceJson().get("latestHeartbeatAt"));
        service.acknowledge(incident.getId(), new AlertIncidentActionRequest());
        assertEquals("ACKNOWLEDGED", incidentState.get().getStatus());
        assertEquals("ACKNOWLEDGED", events.get(events.size() - 1).getEventType());

        service.close(incident.getId(), new AlertIncidentActionRequest());
        assertEquals("CLOSED", incidentState.get().getStatus());
        assertEquals(1, incidentState.get().getClosedWhileActive());
        assertEquals("CLOSED", events.get(events.size() - 1).getEventType());

        int eventCountAfterClose = events.size();
        service.recordCondition(rule, observation(true, now.plusMinutes(1)));
        assertEquals("CLOSED", incidentState.get().getStatus());
        assertEquals(eventCountAfterClose, events.size(), "scheduled repeats remain suppressed while manually closed and active");

        service.recordCondition(rule, observation(false, now.plusMinutes(2)));
        assertEquals("CLOSED", incidentState.get().getStatus());
        assertEquals(0, incidentState.get().getClosedWhileActive());
        assertEquals("RECOVERED", events.get(events.size() - 1).getEventType());

        service.recordCondition(rule, observation(true, now.plusMinutes(3)));
        assertEquals("OPEN", incidentState.get().getStatus());
        assertEquals(1, incidentState.get().getReopenCount());
        assertNull(incidentState.get().getAcknowledgedAt());
        assertNull(incidentState.get().getAcknowledgedBy());
        assertEquals("REOPENED", events.get(events.size() - 1).getEventType());
        assertEquals(2, deliveries.size(), "initial trigger and reopen should each enqueue a notification");
    }

    @Test
    void shouldRejectManualActionAfterConcurrentIncidentChange() {
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        AlertIncidentEntity incident = new AlertIncidentEntity();
        incident.setId(10L);
        incident.setTenantId("default");
        incident.setProjectId(20L);
        incident.setStatus("OPEN");
        when(incidentMapper.selectOne(any())).thenReturn(incident);
        when(incidentMapper.updateById(incident)).thenReturn(0);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        AlertIncidentService service = new AlertIncidentService(
                incidentMapper, eventMapper, mock(AlertDeliveryMapper.class), mock(AlertRuleMapper.class),
                mock(AlertChannelMapper.class), mock(ProjectMapper.class), mock(AlertRuleService.class),
                mock(AlertRecipientResolver.class), securityService, accessService);

        assertThrows(StudioException.class, () -> service.acknowledge(incident.getId(), null));
        verify(eventMapper, org.mockito.Mockito.never()).insert(any(AlertEventEntity.class));
    }

    @Test
    void shouldRetryStateTransitionAfterOptimisticConflictWithoutRecursion() {
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        AlertIncidentEntity stale = activeIncident(0);
        AlertIncidentEntity refreshed = activeIncident(1);
        when(incidentMapper.selectOne(any())).thenReturn(stale, refreshed);
        when(incidentMapper.updateById(any(AlertIncidentEntity.class))).thenReturn(0, 1, 1);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        when(eventMapper.selectCount(any())).thenReturn(0L);
        AlertRuleEntity rule = rule();
        rule.setInAppEnabled(0);
        rule.setWebhookChannelIdsJson(Collections.emptyList());
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        AlertIncidentService service = new AlertIncidentService(
                incidentMapper, eventMapper, mock(AlertDeliveryMapper.class), ruleMapper,
                mock(AlertChannelMapper.class), mock(ProjectMapper.class), mock(AlertRuleService.class),
                mock(AlertRecipientResolver.class), mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class));

        AlertIncidentEntity result = service.recordCondition(rule, observation(true, LocalDateTime.now()));

        assertEquals(refreshed, result);
        verify(incidentMapper, times(2)).selectOne(any());
        verify(eventMapper).insert(any(AlertEventEntity.class));
    }

    @Test
    void shouldNotCreateDeliveryWhenNotificationMarkerConflicts() {
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        when(incidentMapper.selectOne(any())).thenAnswer(invocation -> activeIncident(0));
        AtomicLong updateCalls = new AtomicLong();
        when(incidentMapper.updateById(any(AlertIncidentEntity.class)))
                .thenAnswer(invocation -> updateCalls.incrementAndGet() % 2L == 1L ? 1 : 0);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        when(eventMapper.selectCount(any())).thenReturn(0L);
        AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
        AlertRuleEntity rule = rule();
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        when(ruleMapper.selectOne(any())).thenReturn(rule);

        AlertIncidentService service = new AlertIncidentService(
                incidentMapper, eventMapper, deliveryMapper, ruleMapper,
                mock(AlertChannelMapper.class), mock(ProjectMapper.class), mock(AlertRuleService.class),
                mock(AlertRecipientResolver.class), mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class));

        assertThrows(StudioException.class,
                () -> service.recordCondition(rule, observation(true, LocalDateTime.now())));
        verify(deliveryMapper, never()).insert(any(AlertDeliveryEntity.class));
        verify(incidentMapper, times(10)).updateById(any(AlertIncidentEntity.class));
    }

    @Test
    void shouldInsertDisabledWebhookAsSkippedBeforeIdempotentConflict() {
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        when(incidentMapper.selectOne(any())).thenReturn(null);
        when(incidentMapper.updateById(any(AlertIncidentEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            AlertIncidentEntity entity = invocation.getArgument(0);
            entity.setId(101L);
            return 1;
        }).when(incidentMapper).insert(any(AlertIncidentEntity.class));
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        when(eventMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            AlertEventEntity entity = invocation.getArgument(0);
            entity.setId(102L);
            return 1;
        }).when(eventMapper).insert(any(AlertEventEntity.class));
        AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
        when(deliveryMapper.insert(any(AlertDeliveryEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate delivery"));
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        AlertChannelEntity channel = new AlertChannelEntity();
        channel.setId(50L);
        channel.setTenantId("default");
        channel.setProjectId(20L);
        channel.setName("disabled webhook");
        channel.setEnabled(0);
        when(channelMapper.selectOne(any())).thenReturn(channel);
        AlertRuleEntity rule = rule();
        rule.setInAppEnabled(0);
        rule.setWebhookChannelIdsJson(Collections.singletonList(channel.getId()));
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        AlertIncidentService service = new AlertIncidentService(
                incidentMapper, eventMapper, deliveryMapper, ruleMapper, channelMapper,
                mock(ProjectMapper.class), mock(AlertRuleService.class), mock(AlertRecipientResolver.class),
                mock(StudioSecurityService.class), mock(ProjectResourceAccessService.class));

        assertNotNull(service.recordCondition(rule, observation(true, LocalDateTime.now())));

        ArgumentCaptor<AlertDeliveryEntity> captor = ArgumentCaptor.forClass(AlertDeliveryEntity.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals("SKIPPED", captor.getValue().getStatus());
        assertNull(captor.getValue().getNextAttemptAt());
        assertEquals("Webhook channel is disabled", captor.getValue().getErrorMessage());
        verify(deliveryMapper, never()).updateById(any(AlertDeliveryEntity.class));
        verify(channelMapper, never()).selectById(any());
    }

    @Test
    void shouldIgnoreObservationWhenRuleWasDisabledAfterEvaluationStarted() {
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertRuleEntity staleRule = rule();
        when(ruleMapper.selectOne(any())).thenReturn(null);
        AlertIncidentService service = new AlertIncidentService(
                incidentMapper, eventMapper, deliveryMapper, ruleMapper,
                mock(AlertChannelMapper.class), mock(ProjectMapper.class), mock(AlertRuleService.class),
                mock(AlertRecipientResolver.class), mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class));

        assertNull(service.recordCondition(staleRule, observation(true, LocalDateTime.now())));

        verify(incidentMapper, never()).selectOne(any());
        verify(incidentMapper, never()).insert(any(AlertIncidentEntity.class));
        verify(eventMapper, never()).insert(any(AlertEventEntity.class));
        verify(deliveryMapper, never()).insert(any(AlertDeliveryEntity.class));
    }

    private AlertIncidentEntity activeIncident(int version) {
        AlertIncidentEntity incident = new AlertIncidentEntity();
        incident.setId(100L);
        incident.setTenantId("default");
        incident.setProjectId(20L);
        incident.setRuleId(10L);
        incident.setRuleNameSnapshot("collection failure");
        incident.setRuleType("EXECUTION_FAILED");
        incident.setSignature("signature");
        incident.setSubjectType("COLLECTION_TASK");
        incident.setSubjectKey("30");
        incident.setSubjectId(30L);
        incident.setSubjectNameSnapshot("orders sync");
        incident.setSeverity("WARNING");
        incident.setStatus("OPEN");
        incident.setConditionActive(1);
        incident.setClosedWhileActive(0);
        incident.setOccurrenceCount(1);
        incident.setNotificationCount(0);
        incident.setReopenCount(0);
        incident.setFirstTriggeredAt(LocalDateTime.now().minusMinutes(5));
        incident.setLastTriggeredAt(LocalDateTime.now().minusMinutes(1));
        incident.setVersion(version);
        return incident;
    }

    private AlertRuleEntity rule() {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(10L);
        rule.setTenantId("default");
        rule.setProjectId(20L);
        rule.setName("collection failure");
        rule.setRuleType("EXECUTION_FAILED");
        rule.setSubjectType("COLLECTION_TASK");
        rule.setSeverity("WARNING");
        rule.setEnabled(1);
        rule.setSilenceMinutes(30);
        rule.setRecoveryNotificationEnabled(1);
        rule.setInAppEnabled(1);
        rule.setNotifyProjectAdmins(0);
        rule.setNotifyResourceOwner(0);
        rule.setRecipientUserIdsJson(Collections.singletonList(9L));
        rule.setWebhookChannelIdsJson(Collections.emptyList());
        rule.setConditionJson(new LinkedHashMap<String, Object>());
        rule.setActivationAt(LocalDateTime.now().minusMinutes(1));
        return rule;
    }

    private AlertObservation observation(boolean active, LocalDateTime at) {
        return new AlertObservation()
                .setActive(active)
                .setSubjectType("COLLECTION_TASK")
                .setSubjectKey("30")
                .setSubjectId(30L)
                .setSubjectName("orders sync")
                .setTargetPath("/collection-task-runs?collectionTaskId=30")
                .setSummary(active ? "orders sync failed" : "orders sync recovered")
                .setSourceType("SCHEDULED_EVALUATION")
                .setObservedAt(at);
    }
}
