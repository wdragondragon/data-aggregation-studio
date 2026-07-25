package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.request.AlertChannelQueryRequest;
import com.jdragon.studio.dto.model.request.AlertDeliveryQueryRequest;
import com.jdragon.studio.dto.model.request.AlertIncidentQueryRequest;
import com.jdragon.studio.dto.model.request.AlertTenantSummaryQueryRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import com.jdragon.studio.infra.entity.AlertDeliveryEntity;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.mapper.AlertChannelMapper;
import com.jdragon.studio.infra.mapper.AlertDeliveryMapper;
import com.jdragon.studio.infra.mapper.AlertEventMapper;
import com.jdragon.studio.infra.mapper.AlertIncidentMapper;
import com.jdragon.studio.infra.mapper.AlertRuleMapper;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.model.AlertProjectSummaryAggregate;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertQueryNullSafetyTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(AlertChannelEntity.class);
        initTableInfo(AlertDeliveryEntity.class);
        initTableInfo(AlertIncidentEntity.class);
        initTableInfo(ProjectEntity.class);
    }

    @Test
    void shouldQueryChannelsWhenKeywordIsNull() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(securityService.currentTenantId()).thenReturn("default");
        when(channelMapper.selectCount(any())).thenReturn(0L);
        when(channelMapper.selectList(any())).thenReturn(Collections.emptyList());
        AlertChannelService service = new AlertChannelService(
                channelMapper, mock(AlertRuleMapper.class), mock(AlertRuleService.class),
                securityService, accessService, mock(EncryptionService.class),
                mock(AlertWebhookSecurityService.class), new ObjectMapper());

        assertEquals(0L, service.query(new AlertChannelQueryRequest()).getTotal());
    }

    @Test
    void shouldQueryTenantSummaryWhenKeywordIsNull() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(projectMapper.selectCount(any())).thenReturn(0L);
        when(projectMapper.selectList(any())).thenReturn(Collections.emptyList());
        AlertIncidentService service = new AlertIncidentService(
                mock(AlertIncidentMapper.class), mock(AlertEventMapper.class), mock(AlertDeliveryMapper.class),
                mock(AlertRuleMapper.class), mock(AlertChannelMapper.class), projectMapper,
                mock(AlertRuleService.class), mock(AlertRecipientResolver.class), securityService,
                mock(ProjectResourceAccessService.class));

        assertEquals(0L, service.tenantSummary(new AlertTenantSummaryQueryRequest()).getTotal());
    }

    @Test
    void shouldAggregateTenantSummaryWithFixedQueryCount() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);

        ProjectEntity first = project(20L, "First");
        ProjectEntity second = project(21L, "Second");
        when(projectMapper.selectCount(any())).thenReturn(2L);
        when(projectMapper.selectList(any())).thenReturn(List.of(first, second));
        when(ruleMapper.selectEnabledCountsByProjectIds("default", List.of(20L, 21L)))
                .thenReturn(List.of(aggregate(20L, 3L, null, null, null)));
        when(incidentMapper.selectIncidentCountsByProjectIds("default", List.of(20L, 21L)))
                .thenReturn(List.of(aggregate(20L, null, 4L, 2L, null),
                        aggregate(21L, null, 1L, 0L, null)));
        when(deliveryMapper.selectFailedCountsByProjectIds("default", List.of(20L, 21L)))
                .thenReturn(List.of(aggregate(21L, null, null, null, 5L)));

        AlertIncidentService service = new AlertIncidentService(
                incidentMapper, mock(AlertEventMapper.class), deliveryMapper,
                ruleMapper, mock(AlertChannelMapper.class), projectMapper,
                mock(AlertRuleService.class), mock(AlertRecipientResolver.class), securityService,
                mock(ProjectResourceAccessService.class));

        var page = service.tenantSummary(new AlertTenantSummaryQueryRequest());

        assertEquals(2L, page.getTotal());
        assertEquals(3L, page.getItems().get(0).getEnabledRuleCount());
        assertEquals(4L, page.getItems().get(0).getOpenIncidentCount());
        assertEquals(2L, page.getItems().get(0).getCriticalIncidentCount());
        assertEquals(0L, page.getItems().get(0).getFailedDeliveryCount());
        assertEquals(0L, page.getItems().get(1).getEnabledRuleCount());
        assertEquals(1L, page.getItems().get(1).getOpenIncidentCount());
        assertEquals(5L, page.getItems().get(1).getFailedDeliveryCount());
        verify(ruleMapper).selectEnabledCountsByProjectIds("default", List.of(20L, 21L));
        verify(incidentMapper).selectIncidentCountsByProjectIds("default", List.of(20L, 21L));
        verify(deliveryMapper).selectFailedCountsByProjectIds("default", List.of(20L, 21L));
        verify(ruleMapper, never()).selectCount(any());
    }

    @Test
    void shouldApplyActiveIncidentSummaryFilter() {
        AlertIncidentMapper incidentMapper = mock(AlertIncidentMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(incidentMapper.selectCount(any())).thenReturn(0L);
        when(incidentMapper.selectList(any())).thenReturn(Collections.emptyList());
        AlertIncidentService service = new AlertIncidentService(
                incidentMapper, mock(AlertEventMapper.class), mock(AlertDeliveryMapper.class),
                mock(AlertRuleMapper.class), mock(AlertChannelMapper.class), mock(ProjectMapper.class),
                mock(AlertRuleService.class), mock(AlertRecipientResolver.class), securityService, accessService);
        AlertIncidentQueryRequest request = new AlertIncidentQueryRequest();
        request.setSeverity("CRITICAL");
        request.setActiveOnly(true);
        request.setRequestedClusterId(41L);
        request.setActualClusterId(42L);

        service.query(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AlertIncidentEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(incidentMapper).selectCount(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("status IN"));
        assertTrue(captor.getValue().getSqlSegment().contains("requested_cluster_id"));
        assertTrue(captor.getValue().getSqlSegment().contains("actual_cluster_id"));
    }

    @Test
    void shouldApplyFailedDeliverySummaryFilter() {
        AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(20L);
        when(deliveryMapper.selectCount(any())).thenReturn(0L);
        when(deliveryMapper.selectList(any())).thenReturn(Collections.emptyList());
        AlertDeliveryService service = new AlertDeliveryService(
                deliveryMapper, mock(AlertEventMapper.class), mock(AlertIncidentService.class),
                mock(AlertChannelService.class), mock(AlertRuleService.class), mock(NotificationService.class),
                mock(AlertWebhookSecurityService.class), mock(AlertWebhookHttpClient.class),
                new StudioPlatformProperties(), securityService, accessService, new ObjectMapper());
        AlertDeliveryQueryRequest request = new AlertDeliveryQueryRequest();
        request.setFailedOnly(true);

        service.query(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AlertDeliveryEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(deliveryMapper).selectCount(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("status IN"));
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private ProjectEntity project(Long id, String name) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setProjectName(name);
        return project;
    }

    private AlertProjectSummaryAggregate aggregate(Long projectId, Long rules, Long open,
                                                    Long critical, Long failedDeliveries) {
        AlertProjectSummaryAggregate aggregate = new AlertProjectSummaryAggregate();
        aggregate.setProjectId(projectId);
        aggregate.setEnabledRuleCount(rules);
        aggregate.setOpenIncidentCount(open);
        aggregate.setCriticalIncidentCount(critical);
        aggregate.setFailedDeliveryCount(failedDeliveries);
        return aggregate;
    }
}
