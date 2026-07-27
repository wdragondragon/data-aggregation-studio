package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.model.DataSourceListView;
import com.jdragon.studio.dto.model.DatasourceClusterHealthView;
import com.jdragon.studio.dto.model.RuntimeClusterView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DatasourceConnectionHealthEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceConnectionHealthMapper;
import com.jdragon.studio.infra.mapper.DatasourceConnectionTestRecordMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasourceClusterHealthHydrationTest {

    @BeforeAll
    static void initTableMetadata() {
        if (TableInfoHelper.getTableInfo(DatasourceEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatasourceEntity.class);
        }
    }

    @Test
    void shouldHydrateIndependentHealthForEveryApplicableCluster() {
        DatasourceConnectionHealthMapper healthMapper = mock(DatasourceConnectionHealthMapper.class);
        DatasourceConnectionHealthEntity c46Health = new DatasourceConnectionHealthEntity();
        c46Health.setTenantId("tenant-a");
        c46Health.setRuntimeClusterId(46L);
        c46Health.setConnectionFingerprint("fingerprint-a");
        c46Health.setConnectionStatus(DataSourceConnectionStatus.AVAILABLE.name());
        c46Health.setLastConnectionTestAt(LocalDateTime.of(2026, 7, 20, 10, 30));
        c46Health.setLastConnectionTestMessage("connected");
        c46Health.setLastConnectionTestDurationMs(35L);
        c46Health.setProbeState("IDLE");
        when(healthMapper.selectList(any())).thenReturn(List.of(c46Health));

        DatasourceConnectionHealthService service = new DatasourceConnectionHealthService(
                healthMapper,
                mock(DatasourceConnectionTestRecordMapper.class),
                new StudioPlatformProperties(),
                mock(ClusterInstanceIdentity.class),
                mock(ThreadPoolTaskExecutor.class),
                mock(ThreadPoolTaskExecutor.class));
        DataSourceListView datasource = new DataSourceListView();
        datasource.setTenantId("tenant-a");
        datasource.setConnectionFingerprint("fingerprint-a");
        datasource.setApplicableClusters(List.of(cluster(46L, "C46", "46 集群"),
                cluster(50L, "C50", "50 集群")));

        service.hydrateClusterHealth(List.of(datasource));

        assertEquals(2, datasource.getClusterHealth().size());
        DatasourceClusterHealthView c46 = datasource.getClusterHealth().get(0);
        assertEquals(46L, c46.getRuntimeClusterId());
        assertEquals(DataSourceConnectionStatus.AVAILABLE, c46.getConnectionStatus());
        assertEquals("connected", c46.getLastConnectionTestMessage());
        assertEquals(35L, c46.getLastConnectionTestDurationMs());
        assertFalse(c46.getConnectionTesting());
        DatasourceClusterHealthView c50 = datasource.getClusterHealth().get(1);
        assertEquals(50L, c50.getRuntimeClusterId());
        assertEquals(DataSourceConnectionStatus.UNKNOWN, c50.getConnectionStatus());
    }

    @Test
    void shouldIncludeClusterHealthHydrationInDatasourceSummaryFlow() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        DatasourceConnectionHealthService healthService = mock(DatasourceConnectionHealthService.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        DatasourceEntity entity = new DatasourceEntity();
        entity.setId(91L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(100L);
        entity.setName("业务库");
        entity.setTypeCode("mysql");
        entity.setEnabled(1);
        entity.setExecutable(1);
        entity.setConnectionFingerprint("fingerprint-a");
        when(securityService.currentTenantId()).thenReturn("tenant-a");
        when(accessService.currentProjectId()).thenReturn(null);
        when(datasourceMapper.selectList(any())).thenReturn(List.of(entity));
        when(bindingService.listApplicableClusterIds(List.of(91L))).thenReturn(Map.of(91L, List.of(46L)));
        when(bindingService.listApplicableClusters(List.of(91L))).thenReturn(
                Map.of(91L, List.of(cluster(46L, "C46", "46 集群"))));
        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                mock(MetadataSchemaService.class),
                mock(DataModelIndexRebuildQueueService.class),
                mock(BusinessMetaModelMetadataService.class),
                securityService,
                accessService,
                mock(DatasourceTypeCapabilityService.class),
                mock(DatasourceConnectionFingerprintService.class),
                healthService);
        service.setDatasourceClusterBindingService(bindingService);

        List<DataSourceListView> summaries = service.listSummaries();

        assertEquals(List.of(46L), summaries.get(0).getApplicableClusterIds());
        verify(healthService).hydrateClusterHealth(argThat(items -> items.size() == 1
                && items.get(0).getApplicableClusters().size() == 1
                && Long.valueOf(46L).equals(items.get(0).getApplicableClusters().get(0).getId())));
    }

    @Test
    void shouldSkipUnreadableDatasourceInsteadOfStoppingScheduledHealthRound() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        DatasourceConnectionHealthService healthService = mock(DatasourceConnectionHealthService.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        DatasourceConnectionFingerprintService fingerprintService = mock(DatasourceConnectionFingerprintService.class);
        DatasourceEntity unreadable = new DatasourceEntity();
        unreadable.setId(91L);
        unreadable.setTenantId("tenant-a");
        unreadable.setEnabled(1);
        unreadable.setExecutable(1);
        unreadable.setTypeCode("mysql");
        unreadable.setTechnicalMetadata(Map.of("password", "ENC(unreadable)"));
        when(healthService.enabled()).thenReturn(true);
        when(datasourceMapper.selectList(any())).thenReturn(List.of(unreadable));
        when(bindingService.listApplicableClusterIds("tenant-a", List.of(91L)))
                .thenReturn(Map.of(91L, List.of(46L)));
        doThrow(new com.jdragon.studio.commons.exception.StudioException(
                com.jdragon.studio.commons.exception.StudioErrorCode.INTERNAL_SERVER_ERROR,
                "Failed to decrypt value"))
                .when(fingerprintService).fingerprint("tenant-a", "mysql", unreadable.getTechnicalMetadata());
        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                mock(MetadataSchemaService.class),
                mock(DataModelIndexRebuildQueueService.class),
                mock(BusinessMetaModelMetadataService.class),
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class),
                mock(DatasourceTypeCapabilityService.class),
                fingerprintService,
                healthService);
        service.setDatasourceClusterBindingService(bindingService);

        assertDoesNotThrow(service::dispatchDueScheduledConnectionTests);

        verify(healthService, never()).submitScheduledProbe(any(), anyLong(), any(), anyInt());
        verify(healthService).cleanupExpiredHistory();
    }

    private RuntimeClusterView cluster(Long id, String code, String name) {
        RuntimeClusterView cluster = new RuntimeClusterView();
        cluster.setId(id);
        cluster.setCode(code);
        cluster.setName(name);
        return cluster;
    }
}
