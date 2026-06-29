package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.ProtocolConversionTraceView;
import com.jdragon.studio.infra.entity.ProtocolConversionAccessLogEntity;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionSubscriptionMapper;
import com.jdragon.studio.infra.service.OpenServiceInvocationLogService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.ProtocolConversionMetricsService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolConversionTraceSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(ProtocolConversionAccessLogEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProtocolConversionAccessLogEntity.class);
        }
    }

    @Test
    void accessLogTraceShouldAuthorizeWithSummaryColumnsOnly() {
        ProtocolConversionAccessLogMapper accessLogMapper = mock(ProtocolConversionAccessLogMapper.class);
        OpenServiceInvocationLogService invocationLogService = mock(OpenServiceInvocationLogService.class);
        ProtocolConversionMetricsService service = metricsService(accessLogMapper, invocationLogService);
        when(accessLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(accessLogSummary());
        when(invocationLogService.downloadLog(eq(OpenServiceInvocationLogService.DOMAIN_PROTOCOL_CONVERSIONS), eq(77L)))
                .thenThrow(new IllegalStateException("长期回归-对象日志暂不可用"));

        ProtocolConversionTraceView trace = service.accessLogTrace(77L);

        assertThat(trace.getRequestId()).isEqualTo("lt-reg-trace-001");
        assertThat(trace.getSourceRequest().getProtocol()).isEqualTo("HTTP_JSON");
        assertThat(trace.getConvertedResponse().getErrorMessage()).isEqualTo("目标系统返回 502");

        ArgumentCaptor<LambdaQueryWrapper<ProtocolConversionAccessLogEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(accessLogMapper).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "project_id", "request_id", "request_method",
                        "source_protocol_snapshot", "target_protocol_snapshot", "success",
                        "http_status", "target_http_status", "error_message")
                .doesNotContain("system_log", "log_storage_type", "log_object_key", "log_archive_error", "user_agent");
        verify(accessLogMapper, never()).selectById(any());
    }

    private ProtocolConversionMetricsService metricsService(ProtocolConversionAccessLogMapper accessLogMapper,
                                                            OpenServiceInvocationLogService invocationLogService) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        return new ProtocolConversionMetricsService(
                accessLogMapper,
                mock(ProtocolConversionServiceMapper.class),
                mock(ProtocolConversionSubscriptionMapper.class),
                securityService,
                accessService,
                invocationLogService,
                new ObjectMapper());
    }

    private ProtocolConversionAccessLogEntity accessLogSummary() {
        ProtocolConversionAccessLogEntity entity = new ProtocolConversionAccessLogEntity();
        entity.setId(77L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setRequestId("lt-reg-trace-001");
        entity.setRequestMethod("POST");
        entity.setSourceProtocolSnapshot("HTTP_JSON");
        entity.setTargetProtocolSnapshot("SOAP_11");
        entity.setSuccess(0);
        entity.setHttpStatus(502);
        entity.setTargetHttpStatus(502);
        entity.setErrorMessage("目标系统返回 502");
        entity.setSystemLog("这个大字段不应由 trace 授权摘要查询读取");
        return entity;
    }
}
