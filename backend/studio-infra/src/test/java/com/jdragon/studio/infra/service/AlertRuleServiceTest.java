package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.model.request.AlertRuleSaveRequest;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.AlertSelectOptionView;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.mapper.AlertChannelMapper;
import com.jdragon.studio.infra.mapper.AlertIncidentMapper;
import com.jdragon.studio.infra.mapper.AlertRuleMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.model.AlertSignal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertRuleServiceTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(AlertRuleEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), AlertRuleEntity.class);
        }
        if (TableInfoHelper.getTableInfo(AlertIncidentEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), AlertIncidentEntity.class);
        }
        if (TableInfoHelper.getTableInfo(CollectionTaskDefinitionEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), CollectionTaskDefinitionEntity.class);
        }
    }

    @Test
    void shouldPersistNullEvaluationErrorAfterSuccessfulEvaluation() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertRuleService service = new AlertRuleService(
                ruleMapper, mock(AlertChannelMapper.class), mock(AlertIncidentMapper.class),
                mock(AlertRuleDefinitionRegistry.class), mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class), mock(CollectionTaskDefinitionMapper.class),
                mock(QualityTaskDefinitionMapper.class), mock(WorkflowDefinitionMapper.class),
                mock(DataServiceDefinitionMapper.class), mock(DataIngestionServiceMapper.class),
                mock(ProtocolConversionServiceMapper.class), mock(ProjectWorkerBindingMapper.class),
                mock(ProjectMemberMapper.class), mock(StudioUserMapper.class));
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(10L);
        rule.setEnabled(1);
        rule.setUpdatedAt(java.time.LocalDateTime.now());
        rule.setLastEvaluationError("password=plain-secret");

        service.markEvaluation(rule, "SUCCESS", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AlertRuleEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(ruleMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("last_evaluation_error"));
        assertTrue(captor.getValue().getSqlSegment().contains("enabled"));
        assertTrue(captor.getValue().getSqlSegment().contains("updated_at"));
        assertNull(rule.getLastEvaluationError());
    }

    @Test
    void shouldRejectEnablingRuleWithoutAnyActiveDestination() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);

        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(10L);
        rule.setTenantId("default");
        rule.setProjectId(20L);
        rule.setSubjectType("COLLECTION_TASK");
        rule.setInAppEnabled(0);
        rule.setWebhookChannelIdsJson(Collections.singletonList(30L));
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        when(channelMapper.selectCount(any())).thenReturn(0L);

        AlertRuleService service = new AlertRuleService(
                ruleMapper, channelMapper, mock(AlertIncidentMapper.class),
                new AlertRuleDefinitionRegistry(), securityService, accessService,
                mock(CollectionTaskDefinitionMapper.class), mock(QualityTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class), mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class), mock(ProtocolConversionServiceMapper.class),
                mock(ProjectWorkerBindingMapper.class), mock(ProjectMemberMapper.class), mock(StudioUserMapper.class));

        assertThrows(StudioException.class, () -> service.enable(rule.getId()));
    }

    @Test
    void shouldRejectEnablingRuleWhenItsSpecificSubjectNoLongerExists() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);

        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(10L);
        rule.setTenantId("default");
        rule.setProjectId(20L);
        rule.setSubjectType("COLLECTION_TASK");
        rule.setSubjectId(30L);
        rule.setEnabled(0);
        rule.setInAppEnabled(1);
        rule.setNotifyProjectAdmins(1);
        rule.setWebhookChannelIdsJson(Collections.emptyList());
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        when(collectionMapper.selectOne(any())).thenReturn(null);

        AlertRuleService service = new AlertRuleService(
                ruleMapper, mock(AlertChannelMapper.class), mock(AlertIncidentMapper.class),
                new AlertRuleDefinitionRegistry(), securityService, accessService, collectionMapper,
                mock(QualityTaskDefinitionMapper.class), mock(WorkflowDefinitionMapper.class),
                mock(DataServiceDefinitionMapper.class), mock(DataIngestionServiceMapper.class),
                mock(ProtocolConversionServiceMapper.class), mock(ProjectWorkerBindingMapper.class),
                mock(ProjectMemberMapper.class), mock(StudioUserMapper.class));

        assertThrows(StudioException.class, () -> service.enable(rule.getId()));
        verify(ruleMapper, never()).updateById(any(AlertRuleEntity.class));
    }

    @Test
    void shouldNotTreatDisabledProjectAdminAsAnEffectiveDestination() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);

        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(10L);
        rule.setTenantId("default");
        rule.setProjectId(20L);
        rule.setSubjectType("COLLECTION_TASK");
        rule.setInAppEnabled(1);
        rule.setNotifyProjectAdmins(1);
        rule.setWebhookChannelIdsJson(Collections.emptyList());
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        ProjectMemberEntity admin = new ProjectMemberEntity();
        admin.setUserId(7L);
        admin.setRoleCode(StudioConstants.ROLE_PROJECT_ADMIN);
        admin.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
        when(memberMapper.selectList(any())).thenReturn(Collections.singletonList(admin));
        com.jdragon.studio.infra.entity.StudioUserEntity disabledUser =
                new com.jdragon.studio.infra.entity.StudioUserEntity();
        disabledUser.setId(7L);
        disabledUser.setTenantId("default");
        disabledUser.setEnabled(0);
        when(userMapper.selectByIds(any())).thenReturn(Collections.singletonList(disabledUser));

        AlertRuleService service = new AlertRuleService(
                ruleMapper, channelMapper, mock(AlertIncidentMapper.class),
                new AlertRuleDefinitionRegistry(), securityService, accessService,
                mock(CollectionTaskDefinitionMapper.class), mock(QualityTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class), mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class), mock(ProtocolConversionServiceMapper.class),
                mock(ProjectWorkerBindingMapper.class), memberMapper, userMapper);

        assertThrows(StudioException.class, () -> service.enable(rule.getId()));
    }

    @Test
    void shouldKeepActivationTimeWhenEnableIsRepeated() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(10L);
        rule.setTenantId("default");
        rule.setProjectId(20L);
        rule.setEnabled(1);
        java.time.LocalDateTime activationAt = java.time.LocalDateTime.now().minusHours(2);
        rule.setActivationAt(activationAt);
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        AlertRuleService service = new AlertRuleService(
                ruleMapper, mock(AlertChannelMapper.class), mock(AlertIncidentMapper.class),
                new AlertRuleDefinitionRegistry(), securityService, accessService,
                mock(CollectionTaskDefinitionMapper.class), mock(QualityTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class), mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class), mock(ProtocolConversionServiceMapper.class),
                mock(ProjectWorkerBindingMapper.class), mock(ProjectMemberMapper.class), mock(StudioUserMapper.class));

        service.enable(rule.getId());

        assertEquals(activationAt, rule.getActivationAt());
        verify(ruleMapper, never()).updateById(any(AlertRuleEntity.class));
        verify(ruleMapper, never()).update(isNull(), any());
    }

    @Test
    void shouldPublishActivationSignalWhenRuleIsEnabled() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        AlertSignalPublisher signalPublisher = mock(AlertSignalPublisher.class);
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(99L);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        ProjectMemberEntity admin = new ProjectMemberEntity();
        admin.setUserId(7L);
        admin.setRoleCode(StudioConstants.ROLE_PROJECT_ADMIN);
        admin.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
        when(memberMapper.selectList(any())).thenReturn(Collections.singletonList(admin));
        com.jdragon.studio.infra.entity.StudioUserEntity enabledAdmin =
                new com.jdragon.studio.infra.entity.StudioUserEntity();
        enabledAdmin.setId(7L);
        enabledAdmin.setTenantId("default");
        enabledAdmin.setEnabled(1);
        when(userMapper.selectByIds(any())).thenReturn(Collections.singletonList(enabledAdmin));
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(10L);
        rule.setTenantId("default");
        rule.setProjectId(20L);
        rule.setRuleType("WORKER_OFFLINE");
        rule.setSubjectType("WORKER_GROUP");
        rule.setEnabled(0);
        rule.setInAppEnabled(1);
        rule.setNotifyProjectAdmins(1);
        rule.setRecipientUserIdsJson(Collections.emptyList());
        rule.setWebhookChannelIdsJson(Collections.emptyList());
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        when(ruleMapper.updateById(rule)).thenReturn(1);
        AlertRuleService service = new AlertRuleService(
                ruleMapper, mock(AlertChannelMapper.class), mock(AlertIncidentMapper.class),
                new AlertRuleDefinitionRegistry(), securityService, accessService,
                mock(CollectionTaskDefinitionMapper.class), mock(QualityTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class), mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class), mock(ProtocolConversionServiceMapper.class),
                mock(ProjectWorkerBindingMapper.class), memberMapper, userMapper);
        service.setAlertSignalPublisher(signalPublisher);

        service.enable(rule.getId());

        ArgumentCaptor<AlertSignal> captor = ArgumentCaptor.forClass(AlertSignal.class);
        verify(signalPublisher).publish(captor.capture());
        AlertSignal signal = captor.getValue();
        assertEquals("RULE_ACTIVATED", signal.getSignalType());
        assertEquals("WORKER_OFFLINE", signal.getStatus());
        assertEquals("10", signal.getSourceId());
        assertEquals(rule.getActivationAt(), signal.getOccurredAt());
    }

    @Test
    void shouldPreserveEnabledStateWhenUpdateOmitsEnabledFlag() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(99L);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(ruleMapper.selectCount(any())).thenReturn(0L);
        ProjectMemberEntity admin = new ProjectMemberEntity();
        admin.setTenantId("default");
        admin.setProjectId(20L);
        admin.setUserId(7L);
        admin.setRoleCode(StudioConstants.ROLE_PROJECT_ADMIN);
        admin.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
        when(memberMapper.selectList(any())).thenReturn(Collections.singletonList(admin));
        com.jdragon.studio.infra.entity.StudioUserEntity enabledAdmin =
                new com.jdragon.studio.infra.entity.StudioUserEntity();
        enabledAdmin.setId(7L);
        enabledAdmin.setTenantId("default");
        enabledAdmin.setEnabled(1);
        when(userMapper.selectByIds(any())).thenReturn(Collections.singletonList(enabledAdmin));
        AlertRuleEntity existing = new AlertRuleEntity();
        existing.setId(10L);
        existing.setTenantId("default");
        existing.setProjectId(20L);
        existing.setName("task failures");
        existing.setRuleType("EXECUTION_FAILED");
        existing.setSubjectType("COLLECTION_TASK");
        existing.setSeverity("WARNING");
        existing.setEnabled(1);
        java.time.LocalDateTime activationAt = java.time.LocalDateTime.now().minusHours(1);
        existing.setActivationAt(activationAt);
        when(ruleMapper.selectOne(any())).thenReturn(existing);
        when(ruleMapper.updateById(existing)).thenReturn(1);
        AlertRuleSaveRequest request = new AlertRuleSaveRequest();
        request.setId(existing.getId());
        request.setName(existing.getName());
        request.setRuleType(existing.getRuleType());
        request.setSubjectType(existing.getSubjectType());
        request.setSeverity(existing.getSeverity());

        AlertRuleService service = new AlertRuleService(
                ruleMapper, mock(AlertChannelMapper.class), incidentMapper,
                new AlertRuleDefinitionRegistry(), securityService, accessService,
                mock(CollectionTaskDefinitionMapper.class), mock(QualityTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class), mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class), mock(ProtocolConversionServiceMapper.class),
                mock(ProjectWorkerBindingMapper.class), memberMapper, userMapper);

        service.save(request);

        assertEquals(1, existing.getEnabled());
        assertEquals(activationAt, existing.getActivationAt());
        verify(ruleMapper).updateById(existing);
    }

    @Test
    void shouldUseBindingIdsForCollidingWorkerGroupCodes() {
        ProjectWorkerBindingMapper bindingMapper = mock(ProjectWorkerBindingMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        ProjectWorkerBindingEntity first = new ProjectWorkerBindingEntity();
        first.setId(101L);
        first.setWorkerGroupCode("Aa");
        first.setEnabled(1);
        ProjectWorkerBindingEntity second = new ProjectWorkerBindingEntity();
        second.setId(102L);
        second.setWorkerGroupCode("BB");
        second.setEnabled(1);
        assertEquals("Aa".hashCode(), "BB".hashCode(), "test data must demonstrate the old collision");
        when(bindingMapper.selectList(any())).thenReturn(java.util.List.of(first, second));

        AlertRuleService service = new AlertRuleService(
                mock(AlertRuleMapper.class), mock(AlertChannelMapper.class), mock(AlertIncidentMapper.class),
                new AlertRuleDefinitionRegistry(), securityService, accessService,
                mock(CollectionTaskDefinitionMapper.class), mock(QualityTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class), mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class), mock(ProtocolConversionServiceMapper.class),
                bindingMapper, mock(ProjectMemberMapper.class), mock(StudioUserMapper.class));

        PageView<AlertSelectOptionView> page = service.subjectOptions("WORKER_GROUP", null, 1, 20);

        assertEquals(2, page.getItems().size());
        assertNotEquals(page.getItems().get(0).getId(), page.getItems().get(1).getId());
        assertEquals(101L, page.getItems().get(0).getId());
        assertEquals(102L, page.getItems().get(1).getId());
    }

    @Test
    void shouldScopeSubjectOptionsByTenantAndProject() {
        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        when(collectionMapper.selectList(any())).thenReturn(Collections.emptyList());
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("tenant-a");
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        AlertRuleService service = new AlertRuleService(
                mock(AlertRuleMapper.class), mock(AlertChannelMapper.class), mock(AlertIncidentMapper.class),
                new AlertRuleDefinitionRegistry(), securityService, accessService, collectionMapper,
                mock(QualityTaskDefinitionMapper.class), mock(WorkflowDefinitionMapper.class),
                mock(DataServiceDefinitionMapper.class), mock(DataIngestionServiceMapper.class),
                mock(ProtocolConversionServiceMapper.class), mock(ProjectWorkerBindingMapper.class),
                mock(ProjectMemberMapper.class), mock(StudioUserMapper.class));

        service.subjectOptions("COLLECTION_TASK", null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<CollectionTaskDefinitionEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(collectionMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("tenant_id"));
        assertTrue(sql.contains("project_id"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("tenant-a"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(20L));
    }

    @Test
    void shouldRejectRetargetingRuleWithActiveIncidents() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(99L);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(ruleMapper.selectCount(any())).thenReturn(0L);
        when(incidentMapper.selectCount(any())).thenReturn(1L);
        ProjectMemberEntity admin = new ProjectMemberEntity();
        admin.setTenantId("default");
        admin.setProjectId(20L);
        admin.setUserId(7L);
        admin.setRoleCode(StudioConstants.ROLE_PROJECT_ADMIN);
        admin.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
        when(memberMapper.selectList(any())).thenReturn(Collections.singletonList(admin));
        com.jdragon.studio.infra.entity.StudioUserEntity enabledAdmin =
                new com.jdragon.studio.infra.entity.StudioUserEntity();
        enabledAdmin.setId(7L);
        enabledAdmin.setTenantId("default");
        enabledAdmin.setEnabled(1);
        when(userMapper.selectByIds(any())).thenReturn(Collections.singletonList(enabledAdmin));
        AlertRuleEntity existing = new AlertRuleEntity();
        existing.setId(10L);
        existing.setTenantId("default");
        existing.setProjectId(20L);
        existing.setName("task failures");
        existing.setRuleType("EXECUTION_FAILED");
        existing.setSubjectType("COLLECTION_TASK");
        existing.setEnabled(1);
        when(ruleMapper.selectOne(any())).thenReturn(existing);
        AlertRuleSaveRequest request = new AlertRuleSaveRequest();
        request.setId(10L);
        request.setName("task failures");
        request.setRuleType("CONSECUTIVE_FAILURES");
        request.setSubjectType("COLLECTION_TASK");
        request.setSeverity("WARNING");
        request.setEnabled(true);
        request.setInAppEnabled(true);
        request.setNotifyProjectAdmins(true);

        AlertRuleService service = new AlertRuleService(
                ruleMapper, mock(AlertChannelMapper.class), incidentMapper,
                new AlertRuleDefinitionRegistry(), securityService, accessService,
                mock(CollectionTaskDefinitionMapper.class), mock(QualityTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class), mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class), mock(ProtocolConversionServiceMapper.class),
                mock(ProjectWorkerBindingMapper.class), memberMapper, userMapper);

        assertThrows(StudioException.class, () -> service.save(request));
        assertEquals("EXECUTION_FAILED", existing.getRuleType());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AlertIncidentEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(incidentMapper).selectCount(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("condition_active"));
    }
}
