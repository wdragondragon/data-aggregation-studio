package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.request.AlertChannelSaveRequest;
import com.jdragon.studio.commons.exception.StudioException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals("Webhook 通道名称已存在", error.getMessage());
    }
}
