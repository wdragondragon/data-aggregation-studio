package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.ProtocolConversionAccessLogEntity;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import com.jdragon.studio.infra.service.OpenServiceInvocationLogService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.RunLogObjectStore;
import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenServiceInvocationLogSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(ProtocolConversionAccessLogEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProtocolConversionAccessLogEntity.class);
        }
    }

    @Test
    void objectBackedInvocationLogShouldReadPointerWithoutSystemLog() {
        ProtocolConversionAccessLogMapper protocolMapper = mock(ProtocolConversionAccessLogMapper.class);
        CountingObjectStore objectStore = new CountingObjectStore("长期回归-协议转换完整日志");
        OpenServiceInvocationLogService service = invocationLogService(protocolMapper, objectStore);
        when(protocolMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(objectPointer());

        RunLogView log = service.downloadLog(OpenServiceInvocationLogService.DOMAIN_PROTOCOL_CONVERSIONS, 44L);

        assertThat(log.isHistoricalFallback()).isFalse();
        assertThat(log.getContent()).contains("长期回归-协议转换完整日志");
        assertThat(objectStore.getCount).isEqualTo(1);

        ArgumentCaptor<LambdaQueryWrapper<ProtocolConversionAccessLogEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(protocolMapper, times(1)).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "project_id", "updated_at", "request_id",
                        "log_storage_type", "log_object_bucket", "log_object_key", "log_size_bytes",
                        "log_charset", "log_archive_status")
                .doesNotContain("system_log", "log_archive_error", "error_message", "user_agent");
        verify(protocolMapper, never()).selectById(any());
    }

    @Test
    void localFallbackInvocationLogShouldLoadSystemLogAfterPointerQuery() {
        ProtocolConversionAccessLogMapper protocolMapper = mock(ProtocolConversionAccessLogMapper.class);
        CountingObjectStore objectStore = new CountingObjectStore("unused");
        OpenServiceInvocationLogService service = invocationLogService(protocolMapper, objectStore);
        when(protocolMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(localPointer(), fallbackContent());

        RunLogView log = service.viewLog(OpenServiceInvocationLogService.DOMAIN_PROTOCOL_CONVERSIONS, 45L, 1, 4096);

        assertThat(log.isHistoricalFallback()).isTrue();
        assertThat(log.getContent()).contains("长期回归-协议转换历史兜底日志");
        assertThat(objectStore.getCount).isEqualTo(0);

        ArgumentCaptor<LambdaQueryWrapper<ProtocolConversionAccessLogEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(protocolMapper, times(2)).selectOne(captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSelect())
                .contains("id", "tenant_id", "project_id", "updated_at", "request_id",
                        "log_storage_type", "log_object_bucket", "log_object_key", "log_size_bytes",
                        "log_charset", "log_archive_status")
                .doesNotContain("system_log", "log_archive_error");
        assertThat(captor.getAllValues().get(1).getSqlSelect())
                .contains("updated_at", "system_log", "log_archive_error")
                .doesNotContain("request_method", "source_protocol_snapshot", "target_protocol_snapshot", "user_agent");
        verify(protocolMapper, never()).selectById(any());
    }

    private OpenServiceInvocationLogService invocationLogService(ProtocolConversionAccessLogMapper protocolMapper,
                                                                 CountingObjectStore objectStore) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        CloudObjectStorageService cloudObjectStorageService = mock(CloudObjectStorageService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(cloudObjectStorageService.bucketConfigured()).thenReturn(true);
        when(cloudObjectStorageService.resolveBucket()).thenReturn("studio-log-bucket");
        return new OpenServiceInvocationLogService(
                new StudioPlatformProperties(),
                new RunLogStorageService(new StudioPlatformProperties(), objectStore, cloudObjectStorageService),
                mock(DataServiceAccessLogMapper.class),
                mock(DataIngestionAccessLogMapper.class),
                protocolMapper,
                securityService,
                accessService,
                new ObjectMapper());
    }

    private ProtocolConversionAccessLogEntity objectPointer() {
        ProtocolConversionAccessLogEntity entity = pointerBase(44L);
        entity.setLogStorageType(RunLogStorageService.STORAGE_OBJECT);
        entity.setLogObjectBucket("studio-log-bucket");
        entity.setLogObjectKey("studio/invocation-logs/protocol-conversions/2026-06-29/trace.log");
        entity.setLogSizeBytes(128L);
        entity.setLogCharset(StandardCharsets.UTF_8.name());
        entity.setLogArchiveStatus(OpenServiceInvocationLogService.ARCHIVE_AVAILABLE);
        entity.setSystemLog("这个大字段不应在对象归档可用时读取");
        return entity;
    }

    private ProtocolConversionAccessLogEntity localPointer() {
        ProtocolConversionAccessLogEntity entity = pointerBase(45L);
        entity.setLogStorageType(RunLogStorageService.STORAGE_LOCAL);
        entity.setLogArchiveStatus(OpenServiceInvocationLogService.ARCHIVE_SKIPPED);
        entity.setSystemLog("这个大字段应由第二次兜底查询读取");
        return entity;
    }

    private ProtocolConversionAccessLogEntity fallbackContent() {
        ProtocolConversionAccessLogEntity entity = new ProtocolConversionAccessLogEntity();
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 10, 12));
        entity.setSystemLog("长期回归-协议转换历史兜底日志");
        entity.setLogArchiveError("对象归档未启用");
        return entity;
    }

    private ProtocolConversionAccessLogEntity pointerBase(Long id) {
        ProtocolConversionAccessLogEntity entity = new ProtocolConversionAccessLogEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 10, 10));
        entity.setRequestId("lt-reg-protocol-log-" + id);
        return entity;
    }

    private static final class CountingObjectStore implements RunLogObjectStore {
        private final byte[] content;
        private int getCount;

        private CountingObjectStore(String content) {
            this.content = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void put(String bucket, String objectKey, byte[] bytes, String contentType) {
            // Not used by these source-slimming tests.
        }

        @Override
        public byte[] get(String bucket, String objectKey) {
            getCount++;
            return content;
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
