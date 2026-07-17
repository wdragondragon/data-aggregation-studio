package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.ElinkGroupOptionView;
import com.jdragon.studio.dto.model.ElinkUserOptionView;
import com.jdragon.studio.dto.model.request.AlertChannelSaveRequest;
import com.jdragon.studio.dto.model.AlertChannelView;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.mapper.AlertChannelMapper;
import com.jdragon.studio.infra.mapper.AlertRuleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertChannelServiceTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(AlertChannelEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), AlertChannelEntity.class);
        }
    }

    @Test
    void shouldUpdateOnlyWebhookTestResultFields() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class), mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());

        service.markTestResult(30L, "SUCCEEDED", "ok");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AlertChannelEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(channelMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("last_tested_at"));
        assertTrue(captor.getValue().getSqlSet().contains("last_test_status"));
        assertTrue(captor.getValue().getSqlSet().contains("last_test_message"));
        verify(channelMapper, never()).updateById(any(AlertChannelEntity.class));
    }

    @Test
    void shouldNotDisableLastDestinationThroughChannelSave() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertRuleService ruleService = mock(AlertRuleService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);

        AlertChannelEntity channel = new AlertChannelEntity();
        channel.setId(30L);
        channel.setTenantId("default");
        channel.setProjectId(20L);
        channel.setName("primary");
        channel.setEnabled(1);
        when(channelMapper.selectOne(any())).thenReturn(channel);

        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(10L);
        rule.setTenantId("default");
        rule.setProjectId(20L);
        rule.setName("only webhook");
        rule.setEnabled(1);
        rule.setInAppEnabled(0);
        rule.setWebhookChannelIdsJson(java.util.Arrays.asList(channel.getId(), 31L));
        when(ruleMapper.selectList(any())).thenReturn(Collections.singletonList(rule));
        when(ruleService.hasEffectiveInAppDestination(rule)).thenReturn(false);

        AlertChannelService service = new AlertChannelService(channelMapper, ruleMapper, ruleService,
                securityService, accessService, mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setId(channel.getId());
        request.setName(channel.getName());
        request.setEnabled(false);

        assertThrows(StudioException.class, () -> service.save(request));
        verify(channelMapper, never()).updateById(any(AlertChannelEntity.class));
        verify(channelMapper, never()).selectById(any());
    }

    @Test
    void shouldClearStoredHeadersWhenEmptyObjectIsSubmitted() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        AlertRuleService ruleService = mock(AlertRuleService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        AlertChannelEntity channel = new AlertChannelEntity();
        channel.setId(30L);
        channel.setTenantId("default");
        channel.setProjectId(20L);
        channel.setName("primary");
        channel.setEnabled(1);
        channel.setEndpointCiphertext("endpoint");
        channel.setHeadersCiphertext("old-headers");
        when(channelMapper.selectOne(any())).thenReturn(channel);
        when(channelMapper.updateById(channel)).thenReturn(1);
        when(encryptionService.encrypt("{}")).thenReturn("empty-headers");
        when(encryptionService.decrypt("endpoint")).thenReturn("https://example.com/hook");
        when(encryptionService.decrypt("empty-headers")).thenReturn("{}");
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class), ruleService,
                securityService, accessService, encryptionService,
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setId(channel.getId());
        request.setName(channel.getName());
        request.setEnabled(true);
        request.setHeaders(new LinkedHashMap<String, String>());

        service.save(request);

        assertEquals("empty-headers", channel.getHeadersCiphertext());
    }

    @Test
    void shouldPreserveDisabledStateWhenUpdateOmitsEnabledFlag() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        AlertRuleService ruleService = mock(AlertRuleService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        AlertChannelEntity channel = new AlertChannelEntity();
        channel.setId(30L);
        channel.setTenantId("default");
        channel.setProjectId(20L);
        channel.setName("disabled");
        channel.setEnabled(0);
        when(channelMapper.selectOne(any())).thenReturn(channel);
        when(channelMapper.updateById(channel)).thenReturn(1);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class), ruleService,
                securityService, accessService, mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setId(channel.getId());
        request.setName(channel.getName());

        service.save(request);

        assertEquals(0, channel.getEnabled());
        verify(channelMapper).updateById(channel);
    }

    @Test
    void shouldPreserveSigningSecretWhitespace() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        AlertWebhookSecurityService webhookSecurityService = mock(AlertWebhookSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        when(webhookSecurityService.validate(any())).thenReturn(URI.create("https://example.com/hook"));
        when(encryptionService.encrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(encryptionService.decrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(channelMapper.insert(any(AlertChannelEntity.class))).thenReturn(1);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, encryptionService,
                webhookSecurityService, new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setName("signed");
        request.setEndpointUrl("https://example.com/hook");
        request.setSigningSecret("  exact secret  ");

        service.save(request);

        ArgumentCaptor<AlertChannelEntity> captor = ArgumentCaptor.forClass(AlertChannelEntity.class);
        verify(channelMapper).insert(captor.capture());
        assertEquals("  exact secret  ", captor.getValue().getSigningSecretCiphertext());
    }

    @Test
    void shouldSaveSimpleElinkPersonalChannelWithoutWebhookConfiguration() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(7L);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        when(channelMapper.insert(any(AlertChannelEntity.class))).thenReturn(1);
        AlertWebhookSecurityService webhookSecurityService = mock(AlertWebhookSecurityService.class);
        ElinkManagerOptionService optionService = mock(ElinkManagerOptionService.class);
        ElinkUserOptionView firstUser = new ElinkUserOptionView();
        firstUser.setUserId("user-1");
        firstUser.setName("First User");
        firstUser.setEnabled(true);
        ElinkUserOptionView secondUser = new ElinkUserOptionView();
        secondUser.setUserId("user-2");
        secondUser.setName("Second User");
        secondUser.setEnabled(true);
        when(optionService.requireUser("user-1")).thenReturn(firstUser);
        when(optionService.requireUser("user-2")).thenReturn(secondUser);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, mock(EncryptionService.class),
                webhookSecurityService, new com.fasterxml.jackson.databind.ObjectMapper());
        service.setElinkManagerOptionService(optionService);
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setName("eLink operations");
        request.setChannelType("ELINK");
        request.setElinkTargetType("PERSONAL");
        request.setElinkUserIds(List.of(" user-1 ", "user-1", "user-2"));

        service.save(request);

        ArgumentCaptor<AlertChannelEntity> captor = ArgumentCaptor.forClass(AlertChannelEntity.class);
        verify(channelMapper).insert(captor.capture());
        AlertChannelEntity saved = captor.getValue();
        assertEquals("ELINK", saved.getChannelType());
        assertEquals("PERSONAL", saved.getConfigJson().get("targetType"));
        assertEquals(List.of("user-1", "user-2"), saved.getConfigJson().get("userIds"));
        assertEquals(List.of("First User", "Second User"), saved.getConfigJson().get("userNames"));
        assertNull(saved.getEndpointCiphertext());
        verify(optionService).requireUser("user-1");
        verify(optionService).requireUser("user-2");
        verify(webhookSecurityService, never()).validate(any());
    }

    @Test
    void shouldPreserveUnchangedFixedTargetsWithoutCallingManager() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(7L);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        AlertChannelEntity channel = new AlertChannelEntity();
        channel.setId(30L);
        channel.setTenantId("default");
        channel.setProjectId(20L);
        channel.setName("legacy personal");
        channel.setChannelType("ELINK");
        channel.setEnabled(1);
        channel.setConfigJson(new LinkedHashMap<String, Object>(java.util.Map.of(
                "targetType", "PERSONAL",
                "userIds", List.of("user-1"),
                "userNames", List.of("Historical User"))));
        when(channelMapper.selectOne(any())).thenReturn(channel);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        ElinkManagerOptionService optionService = mock(ElinkManagerOptionService.class);
        service.setElinkManagerOptionService(optionService);
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setId(30L);
        request.setName("renamed personal");
        request.setChannelType("ELINK");
        request.setElinkTargetType("PERSONAL");
        request.setElinkUserIds(List.of("user-1"));

        service.save(request);

        assertEquals(List.of("Historical User"), channel.getConfigJson().get("userNames"));
        verify(optionService, never()).requireUser(any());
    }

    @Test
    void shouldHideDiscardedLegacyTransportFieldsForElinkChannel() {
        EncryptionService encryptionService = mock(EncryptionService.class);
        AlertChannelService service = new AlertChannelService(mock(AlertChannelMapper.class),
                mock(AlertRuleMapper.class), mock(AlertRuleService.class), mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class), encryptionService,
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelEntity entity = new AlertChannelEntity();
        entity.setChannelType("ELINK");
        entity.setEndpointCiphertext("legacy-endpoint");
        entity.setHeadersCiphertext("legacy-headers");
        entity.setSigningSecretCiphertext("legacy-secret");
        LinkedHashMap<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("targetType", "PERSONAL");
        config.put("userIds", List.of("user-1"));
        entity.setConfigJson(config);

        AlertChannelView view = service.toView(entity);

        assertNull(view.getEndpointMasked());
        assertTrue(view.getHeaderNames().isEmpty());
        assertEquals(false, view.isHasSigningSecret());
        assertEquals("FIXED", view.getElinkRecipientMode());
        verify(encryptionService, never()).decrypt(any());
    }

    @Test
    void shouldSaveRuleRecipientElinkChannelWithoutFixedTarget() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(7L);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        when(channelMapper.insert(any(AlertChannelEntity.class))).thenReturn(1);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setName("follow rule recipients");
        request.setChannelType("ELINK");
        request.setElinkRecipientMode("RULE_RECIPIENTS");
        request.setElinkTargetType("PERSONAL");

        service.save(request);

        ArgumentCaptor<AlertChannelEntity> captor = ArgumentCaptor.forClass(AlertChannelEntity.class);
        verify(channelMapper).insert(captor.capture());
        assertEquals("RULE_RECIPIENTS", captor.getValue().getConfigJson().get("recipientMode"));
        assertEquals("PERSONAL", captor.getValue().getConfigJson().get("targetType"));
        assertEquals(false, captor.getValue().getConfigJson().containsKey("userIds"));
        assertEquals(false, captor.getValue().getConfigJson().containsKey("groupId"));
    }

    @Test
    void shouldRejectGroupOrFixedTargetsForRuleRecipientMode() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setName("invalid dynamic group");
        request.setChannelType("ELINK");
        request.setElinkRecipientMode("RULE_RECIPIENTS");
        request.setElinkTargetType("GROUP");
        request.setElinkGroupId(1L);

        assertThrows(StudioException.class, () -> service.save(request));

        request.setElinkTargetType("PERSONAL");
        request.setElinkGroupId(null);
        request.setElinkUserIds(List.of("user-1"));
        assertThrows(StudioException.class, () -> service.save(request));
        verify(channelMapper, never()).insert(any(AlertChannelEntity.class));
    }

    @Test
    void shouldClearDiscardedLegacyTransportFieldsWhenUpdatingElinkChannel() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(7L);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        AlertChannelEntity entity = new AlertChannelEntity();
        entity.setId(30L);
        entity.setTenantId("default");
        entity.setProjectId(20L);
        entity.setName("legacy eLink");
        entity.setChannelType("ELINK");
        entity.setEnabled(1);
        entity.setEndpointCiphertext("legacy-endpoint");
        entity.setHeadersCiphertext("legacy-headers");
        entity.setSigningSecretCiphertext("legacy-secret");
        when(channelMapper.selectOne(any())).thenReturn(entity);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setId(entity.getId());
        request.setName(entity.getName());
        request.setChannelType("ELINK");
        request.setElinkTargetType("PERSONAL");
        request.setElinkUserIds(List.of("user-1"));

        service.save(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AlertChannelEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(channelMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("endpoint_ciphertext"));
        assertTrue(captor.getValue().getSqlSet().contains("headers_ciphertext"));
        assertTrue(captor.getValue().getSqlSet().contains("signing_secret_ciphertext"));
    }

    @Test
    void shouldRequirePositiveGroupIdForElinkGroupChannel() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setName("eLink group");
        request.setChannelType("ELINK");
        request.setElinkTargetType("GROUP");
        request.setElinkGroupId(0L);

        assertThrows(StudioException.class, () -> service.save(request));
        verify(channelMapper, never()).insert(any(AlertChannelEntity.class));
    }

    @Test
    void shouldValidateElinkGroupAndStoreItsNameSnapshot() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(7L);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        when(channelMapper.insert(any(AlertChannelEntity.class))).thenReturn(1);
        ElinkManagerOptionService optionService = mock(ElinkManagerOptionService.class);
        ElinkGroupOptionView group = new ElinkGroupOptionView();
        group.setId(9L);
        group.setName("Operations Group");
        group.setMemberCount(2);
        when(optionService.requireGroup(9L)).thenReturn(group);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        service.setElinkManagerOptionService(optionService);
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setName("eLink group");
        request.setChannelType("ELINK");
        request.setElinkTargetType("GROUP");
        request.setElinkGroupId(9L);

        service.save(request);

        ArgumentCaptor<AlertChannelEntity> captor = ArgumentCaptor.forClass(AlertChannelEntity.class);
        verify(channelMapper).insert(captor.capture());
        assertEquals(9L, captor.getValue().getConfigJson().get("groupId"));
        assertEquals("Operations Group", captor.getValue().getConfigJson().get("groupName"));
        verify(optionService).requireGroup(9L);
    }

    @Test
    void shouldRejectElinkChannelWhenRuntimeCapabilityIsDisabled() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getAlert().getElink().setEnabled(false);
        service.setStudioPlatformProperties(properties);
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setName("eLink disabled");
        request.setChannelType("ELINK");
        request.setElinkTargetType("PERSONAL");
        request.setElinkUserIds(List.of("user-1"));

        StudioException error = assertThrows(StudioException.class, () -> service.save(request));

        assertTrue(error.getMessage().contains("not enabled"));
        verify(channelMapper, never()).insert(any(AlertChannelEntity.class));
    }

    @Test
    void shouldRejectClearingAndReplacingSigningSecretInTheSameRequest() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        AlertWebhookSecurityService webhookSecurityService = mock(AlertWebhookSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(webhookSecurityService.validate(any())).thenReturn(URI.create("https://example.com/hook"));
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, mock(EncryptionService.class),
                webhookSecurityService, new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setName("signed");
        request.setEndpointUrl("https://example.com/hook");
        request.setSigningSecret("replacement");
        request.setClearSigningSecret(true);

        assertThrows(StudioException.class, () -> service.save(request));
        verify(channelMapper, never()).insert(any(AlertChannelEntity.class));
    }

    @Test
    void shouldRejectExcessiveAndTransportControlledHeaders() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        AlertWebhookSecurityService webhookSecurityService = mock(AlertWebhookSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(webhookSecurityService.validate(any())).thenReturn(URI.create("https://example.com/hook"));
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class),
                mock(AlertRuleService.class), securityService, accessService, mock(EncryptionService.class),
                webhookSecurityService, new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setName("headers");
        request.setEndpointUrl("https://example.com/hook");
        LinkedHashMap<String, String> tooManyHeaders = new LinkedHashMap<String, String>();
        for (int index = 0; index < 33; index++) {
            tooManyHeaders.put("X-Custom-" + index, "value");
        }
        request.setHeaders(tooManyHeaders);

        assertThrows(StudioException.class, () -> service.save(request));

        request.setHeaders(new LinkedHashMap<String, String>(Collections.singletonMap(
                "Proxy-Authorization", "secret")));
        assertThrows(StudioException.class, () -> service.save(request));
        verify(channelMapper, never()).insert(any(AlertChannelEntity.class));
    }

    @Test
    void shouldTranslateConcurrentDuplicateNameToBusinessError() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        AlertRuleService ruleService = mock(AlertRuleService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        AlertWebhookSecurityService webhookSecurityService = mock(AlertWebhookSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(channelMapper.selectCount(any())).thenReturn(0L);
        when(webhookSecurityService.validate(any())).thenReturn(URI.create("https://example.com/hook"));
        when(encryptionService.encrypt(any())).thenReturn("encrypted");
        when(channelMapper.insert(any(AlertChannelEntity.class))).thenThrow(new DuplicateKeyException("duplicate"));
        AlertChannelService service = new AlertChannelService(channelMapper, mock(AlertRuleMapper.class), ruleService,
                securityService, accessService, encryptionService, webhookSecurityService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        AlertChannelSaveRequest request = new AlertChannelSaveRequest();
        request.setName("primary");
        request.setEndpointUrl("https://example.com/hook");

        StudioException error = assertThrows(StudioException.class, () -> service.save(request));

        assertEquals("通知通道名称已存在", error.getMessage());
    }
}
