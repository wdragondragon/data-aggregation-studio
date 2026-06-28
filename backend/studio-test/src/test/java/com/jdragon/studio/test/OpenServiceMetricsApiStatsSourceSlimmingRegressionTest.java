package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.DataIngestionApiMetricView;
import com.jdragon.studio.dto.model.DataServiceApiMetricView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.DataIngestionMetricQueryRequest;
import com.jdragon.studio.dto.model.request.DataServiceMetricQueryRequest;
import com.jdragon.studio.infra.entity.DataIngestionAccessLogEntity;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.mapper.DataIngestionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataIngestionSubscriptionMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataServiceSubscriptionMapper;
import com.jdragon.studio.infra.service.DataIngestionMetricsService;
import com.jdragon.studio.infra.service.DataServiceMetricsService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenServiceMetricsApiStatsSourceSlimmingRegressionTest {

    @BeforeAll
    static void initMyBatisLambdaCaches() {
        initTableInfo(DataServiceDefinitionEntity.class);
        initTableInfo(DataIngestionServiceEntity.class);
        initTableInfo(DataServiceAccessLogEntity.class);
        initTableInfo(DataIngestionAccessLogEntity.class);
    }

    @Test
    void dataServiceApiStatsShouldUseDatabaseAggregationPagination() {
        DataServiceAccessLogMapper accessLogMapper = mock(DataServiceAccessLogMapper.class);
        DataServiceAccessCounterMapper counterMapper = mock(DataServiceAccessCounterMapper.class);
        DataServiceDefinitionMapper definitionMapper = mock(DataServiceDefinitionMapper.class);
        DataServiceMetricsService service = new DataServiceMetricsService(accessLogMapper,
                counterMapper,
                definitionMapper,
                mock(DataServiceSubscriptionMapper.class),
                securityService(),
                accessService());
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(dataService()));
        when(accessLogMapper.countApiStats(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(accessLogMapper.selectApiStatsPage(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(dataServiceMetric()));

        PageView<DataServiceApiMetricView> page = service.queryApiStats(dataServiceRequest());

        assertEquals(1L, page.getTotal());
        assertEquals("客户画像查询服务", page.getItems().get(0).getServiceName());
        assertEquals("CUSTOMER_PROFILE_QUERY", page.getItems().get(0).getServiceCode());
        assertEquals("ONLINE", page.getItems().get(0).getStatus());
        assertEquals(Double.valueOf(75.0D), page.getItems().get(0).getSuccessRate());
        assertEquals(Double.valueOf(50.0D), page.getItems().get(0).getCacheHitRate());
        verify(accessLogMapper).countApiStats(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        verify(accessLogMapper).selectApiStatsPage(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(accessLogMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verifyNoInteractions(counterMapper);
    }

    @Test
    void dataIngestionApiStatsShouldUseDatabaseAggregationPagination() {
        DataIngestionAccessLogMapper accessLogMapper = mock(DataIngestionAccessLogMapper.class);
        DataIngestionAccessCounterMapper counterMapper = mock(DataIngestionAccessCounterMapper.class);
        DataIngestionServiceMapper serviceMapper = mock(DataIngestionServiceMapper.class);
        DataIngestionMetricsService service = new DataIngestionMetricsService(accessLogMapper,
                counterMapper,
                serviceMapper,
                mock(DataIngestionSubscriptionMapper.class),
                securityService(),
                accessService());
        when(serviceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(dataIngestionService()));
        when(accessLogMapper.countApiStats(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(accessLogMapper.selectApiStatsPage(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(dataIngestionMetric()));

        PageView<DataIngestionApiMetricView> page = service.queryApiStats(dataIngestionRequest());

        assertEquals(1L, page.getTotal());
        assertEquals("订单接入服务", page.getItems().get(0).getServiceName());
        assertEquals("ORDER_INGESTION", page.getItems().get(0).getServiceCode());
        assertEquals("ONLINE", page.getItems().get(0).getStatus());
        assertEquals(Double.valueOf(80.0D), page.getItems().get(0).getSuccessRate());
        verify(accessLogMapper).countApiStats(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any());
        verify(accessLogMapper).selectApiStatsPage(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(accessLogMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verifyNoInteractions(counterMapper);
    }

    private static void initTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    private StudioSecurityService securityService() {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        return securityService;
    }

    private ProjectResourceAccessService accessService() {
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(accessService.currentProjectId()).thenReturn(100L);
        return accessService;
    }

    private DataServiceMetricQueryRequest dataServiceRequest() {
        DataServiceMetricQueryRequest request = new DataServiceMetricQueryRequest();
        request.setPageNo(1);
        request.setPageSize(10);
        request.setStartTime("2026-06-27 00:00:00");
        request.setEndTime("2026-06-28 00:00:00");
        return request;
    }

    private DataIngestionMetricQueryRequest dataIngestionRequest() {
        DataIngestionMetricQueryRequest request = new DataIngestionMetricQueryRequest();
        request.setPageNo(1);
        request.setPageSize(10);
        request.setStartTime("2026-06-27 00:00:00");
        request.setEndTime("2026-06-28 00:00:00");
        return request;
    }

    private DataServiceDefinitionEntity dataService() {
        DataServiceDefinitionEntity service = new DataServiceDefinitionEntity();
        service.setId(11L);
        service.setServiceName("客户画像查询服务");
        service.setServiceCode("CUSTOMER_PROFILE_QUERY");
        service.setStatus("ONLINE");
        return service;
    }

    private DataIngestionServiceEntity dataIngestionService() {
        DataIngestionServiceEntity service = new DataIngestionServiceEntity();
        service.setId(21L);
        service.setServiceName("订单接入服务");
        service.setServiceCode("ORDER_INGESTION");
        service.setStatus("ONLINE");
        return service;
    }

    private DataServiceApiMetricView dataServiceMetric() {
        DataServiceApiMetricView metric = new DataServiceApiMetricView();
        metric.setServiceId(11L);
        metric.setAccessCount(4L);
        metric.setSuccessCount(3L);
        metric.setFailureCount(1L);
        metric.setCacheEnabledCount(2L);
        metric.setCacheHitCount(1L);
        metric.setCacheMissCount(1L);
        metric.setCacheDisabledCount(2L);
        return metric;
    }

    private DataIngestionApiMetricView dataIngestionMetric() {
        DataIngestionApiMetricView metric = new DataIngestionApiMetricView();
        metric.setServiceId(21L);
        metric.setAccessCount(5L);
        metric.setSuccessCount(4L);
        metric.setFailureCount(1L);
        metric.setReceivedCount(100L);
        metric.setWrittenCount(95L);
        metric.setFailedCount(5L);
        return metric;
    }
}
