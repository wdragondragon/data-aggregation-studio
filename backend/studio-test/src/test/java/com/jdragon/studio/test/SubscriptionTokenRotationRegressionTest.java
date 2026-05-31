package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataIngestionSubscriptionView;
import com.jdragon.studio.dto.model.DataServiceSubscriptionView;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataIngestionSubscriptionEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DataServiceSubscriptionEntity;
import com.jdragon.studio.infra.mapper.DataIngestionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataIngestionSubscriptionMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataServicePublishParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceRequestParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceResponseParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceSubscriptionMapper;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataIngestionService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataServiceResponseCacheService;
import com.jdragon.studio.infra.service.DataServiceService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.PluginRuntimeOptionSchemaService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionTokenRotationRegressionTest {

    @Test
    void shouldRotateDataServiceTokenAndKeepListMaskedOnly() {
        DataServiceDefinitionMapper definitionMapper = mock(DataServiceDefinitionMapper.class);
        DataServiceSubscriptionMapper subscriptionMapper = mock(DataServiceSubscriptionMapper.class);
        StudioSecurityService securityService = security("default", 900L);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        DataServiceService service = dataService(definitionMapper, subscriptionMapper, securityService, accessService);

        DataServiceDefinitionEntity definition = dataServiceDefinition();
        DataServiceSubscriptionEntity subscription = dataServiceSubscription("old-hash", null);
        when(definitionMapper.selectById(10L)).thenReturn(definition);
        when(subscriptionMapper.selectById(20L)).thenReturn(subscription);
        when(subscriptionMapper.updateById(any(DataServiceSubscriptionEntity.class))).thenReturn(1);
        when(subscriptionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(subscription));

        DataServiceSubscriptionView rotated = service.rotateSubscription(10L, 20L);

        assertNotNull(rotated.getToken());
        assertTrue(rotated.getToken().startsWith("dsvc_"));
        assertNotEquals("old-hash", subscription.getTokenHash());
        assertEquals(rotated.getTokenMasked(), subscription.getTokenMasked());
        assertNotNull(rotated.getRotatedAt());
        assertEquals(Long.valueOf(900L), rotated.getRotatedBy());
        assertNull(subscription.getLastUsedAt());

        DataServiceSubscriptionView listed = service.listSubscriptions(10L).get(0);
        assertNull(listed.getToken());
        assertEquals(subscription.getTokenMasked(), listed.getTokenMasked());
    }

    @Test
    void shouldRejectDataServiceRotationWhenServiceIsReadOnlySharedResource() {
        DataServiceDefinitionMapper definitionMapper = mock(DataServiceDefinitionMapper.class);
        DataServiceSubscriptionMapper subscriptionMapper = mock(DataServiceSubscriptionMapper.class);
        StudioSecurityService securityService = security("default", 900L);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        DataServiceService service = dataService(definitionMapper, subscriptionMapper, securityService, accessService);
        DataServiceDefinitionEntity definition = dataServiceDefinition();
        when(definitionMapper.selectById(10L)).thenReturn(definition);
        doThrow(new StudioException(StudioErrorCode.FORBIDDEN, "Resource belongs to another project"))
                .when(accessService).assertWritable(100L);

        StudioException ex = assertThrows(StudioException.class, () -> service.rotateSubscription(10L, 20L));
        assertEquals(StudioErrorCode.FORBIDDEN, ex.getCode());
    }

    @Test
    void shouldRotateDataIngestionTokenAndKeepListMaskedOnly() {
        DataIngestionServiceMapper serviceMapper = mock(DataIngestionServiceMapper.class);
        DataIngestionSubscriptionMapper subscriptionMapper = mock(DataIngestionSubscriptionMapper.class);
        StudioSecurityService securityService = security("default", 901L);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        DataIngestionService service = dataIngestionService(serviceMapper, subscriptionMapper, securityService, accessService);

        DataIngestionServiceEntity definition = dataIngestionDefinition();
        DataIngestionSubscriptionEntity subscription = dataIngestionSubscription("old-hash", null);
        when(serviceMapper.selectById(30L)).thenReturn(definition);
        when(subscriptionMapper.selectById(40L)).thenReturn(subscription);
        when(subscriptionMapper.updateById(any(DataIngestionSubscriptionEntity.class))).thenReturn(1);
        when(subscriptionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(subscription));

        DataIngestionSubscriptionView rotated = service.rotateSubscription(30L, 40L);

        assertNotNull(rotated.getToken());
        assertTrue(rotated.getToken().startsWith("dsvc_"));
        assertNotEquals("old-hash", subscription.getTokenHash());
        assertEquals(rotated.getTokenMasked(), subscription.getTokenMasked());
        assertNotNull(rotated.getRotatedAt());
        assertEquals(Long.valueOf(901L), rotated.getRotatedBy());
        assertNull(subscription.getLastUsedAt());

        DataIngestionSubscriptionView listed = service.listSubscriptions(30L).get(0);
        assertNull(listed.getToken());
        assertEquals(subscription.getTokenMasked(), listed.getTokenMasked());
    }

    @Test
    void shouldShowLegacyMaskedHintWhenTokenMaskedIsMissing() {
        DataIngestionServiceMapper serviceMapper = mock(DataIngestionServiceMapper.class);
        DataIngestionSubscriptionMapper subscriptionMapper = mock(DataIngestionSubscriptionMapper.class);
        StudioSecurityService securityService = security("default", 901L);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        DataIngestionService service = dataIngestionService(serviceMapper, subscriptionMapper, securityService, accessService);

        when(serviceMapper.selectById(30L)).thenReturn(dataIngestionDefinition());
        when(subscriptionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(dataIngestionSubscription("legacy-hash", null)));

        DataIngestionSubscriptionView listed = service.listSubscriptions(30L).get(0);

        assertNull(listed.getToken());
        assertEquals("历史 Token 不可查看，请重新生成", listed.getTokenMasked());
    }

    private DataServiceService dataService(DataServiceDefinitionMapper definitionMapper,
                                           DataServiceSubscriptionMapper subscriptionMapper,
                                           StudioSecurityService securityService,
                                           ProjectResourceAccessService accessService) {
        return new DataServiceService(
                definitionMapper,
                mock(DataServiceRequestParamMapper.class),
                mock(DataServiceResponseParamMapper.class),
                mock(DataServicePublishParamMapper.class),
                subscriptionMapper,
                mock(DataServiceAccessLogMapper.class),
                mock(DataServiceAccessCounterMapper.class),
                mock(DataSourceService.class),
                mock(DataModelService.class),
                mock(DataDevelopmentSqlExecutor.class),
                securityService,
                accessService,
                mock(DataServiceResponseCacheService.class)
        );
    }

    private DataIngestionService dataIngestionService(DataIngestionServiceMapper serviceMapper,
                                                      DataIngestionSubscriptionMapper subscriptionMapper,
                                                      StudioSecurityService securityService,
                                                      ProjectResourceAccessService accessService) {
        return new DataIngestionService(
                serviceMapper,
                subscriptionMapper,
                mock(DataIngestionAccessLogMapper.class),
                mock(DataIngestionAccessCounterMapper.class),
                mock(DataSourceService.class),
                mock(DataModelService.class),
                mock(DataDevelopmentSqlExecutor.class),
                securityService,
                accessService,
                mock(PluginRuntimeOptionSchemaService.class),
                mock(CollectionTaskAssemblerService.class),
                new ObjectMapper()
        );
    }

    private StudioSecurityService security(String tenantId, Long userId) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentTenantId()).thenReturn(tenantId);
        when(securityService.currentUserId()).thenReturn(userId);
        return securityService;
    }

    private DataServiceDefinitionEntity dataServiceDefinition() {
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(10L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        return entity;
    }

    private DataIngestionServiceEntity dataIngestionDefinition() {
        DataIngestionServiceEntity entity = new DataIngestionServiceEntity();
        entity.setId(30L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        return entity;
    }

    private DataServiceSubscriptionEntity dataServiceSubscription(String tokenHash, String tokenMasked) {
        DataServiceSubscriptionEntity entity = new DataServiceSubscriptionEntity();
        entity.setId(20L);
        entity.setServiceId(10L);
        entity.setTokenHash(tokenHash);
        entity.setTokenMasked(tokenMasked);
        entity.setSubscriptionName("client-a");
        entity.setEnabled(1);
        return entity;
    }

    private DataIngestionSubscriptionEntity dataIngestionSubscription(String tokenHash, String tokenMasked) {
        DataIngestionSubscriptionEntity entity = new DataIngestionSubscriptionEntity();
        entity.setId(40L);
        entity.setServiceId(30L);
        entity.setTokenHash(tokenHash);
        entity.setTokenMasked(tokenMasked);
        entity.setSubscriptionName("client-a");
        entity.setEnabled(1);
        return entity;
    }
}
