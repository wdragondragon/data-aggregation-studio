package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.dto.model.DataSourceListView;
import com.jdragon.studio.dto.model.DataSourceOptionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.RunMetricFilterOptionView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DatasourceConnectionHealthEntity;
import com.jdragon.studio.infra.entity.DatasourceConnectionTestRecordEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceConnectionHealthMapper;
import com.jdragon.studio.infra.mapper.DatasourceConnectionTestRecordMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.DataModelIndexRebuildQueueService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceConnectionFingerprintService;
import com.jdragon.studio.infra.service.DatasourceConnectionHealthService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceListSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(DatasourceEntity.class);
        initTableInfo(DatasourceConnectionHealthEntity.class);
    }

    @Test
    void datasourcePageShouldSelectOnlySummaryFieldsAndSlimHealthTrend() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        DatasourceConnectionHealthMapper healthMapper = mock(DatasourceConnectionHealthMapper.class);
        DatasourceConnectionTestRecordMapper recordMapper = mock(DatasourceConnectionTestRecordMapper.class);
        DataSourceService service = dataSourceService(datasourceMapper, healthMapper, recordMapper);
        when(datasourceMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<DatasourceEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(datasource()));
            return page;
        });
        when(healthMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(health()));
        when(recordMapper.selectRecentTrendByFingerprints(any(), any(), any(Integer.class)))
                .thenReturn(Collections.singletonList(record()));

        PageView<DataSourceListView> page = service.listSummaryPage(1, 20);

        assertThat(page.getItems()).hasSize(1);
        DataSourceListView item = page.getItems().get(0);
        assertThat(item.getName()).isEqualTo("长期回归-客户订单数据源");
        assertThat(item.getConnectionTesting()).isTrue();
        assertThat(item.getRecentConnectionTests()).hasSize(1);
        assertThat(item.getRecentConnectionTests().get(0).getEndedAt()).isEqualTo(LocalDateTime.of(2026, 6, 27, 9, 0, 0));

        ArgumentCaptor<LambdaQueryWrapper<DatasourceEntity>> datasourceCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(datasourceMapper).selectPage(any(Page.class), datasourceCaptor.capture());
        assertThat(datasourceCaptor.getValue().getSqlSelect())
                .contains("connection_fingerprint", "connection_status", "last_connection_test_message")
                .doesNotContain("technical_metadata", "business_metadata");

        ArgumentCaptor<LambdaQueryWrapper<DatasourceConnectionHealthEntity>> healthCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(healthMapper).selectList(healthCaptor.capture());
        assertThat(healthCaptor.getValue().getSqlSelect())
                .contains("connection_fingerprint", "connection_status", "last_connection_test_message", "probe_state", "probe_lease_until", "next_probe_at")
                .doesNotContain("probe_owner", "probe_run_id", "probe_started_at", "failure_count");

        verify(recordMapper).selectRecentTrendByFingerprints(any(), any(), any(Integer.class));
        verify(recordMapper, never()).selectRecentByFingerprints(any(), any(), any(Integer.class));
    }

    @Test
    void datasourceSummaryListShouldReuseSlimSelect() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        DatasourceConnectionHealthMapper healthMapper = mock(DatasourceConnectionHealthMapper.class);
        DatasourceConnectionTestRecordMapper recordMapper = mock(DatasourceConnectionTestRecordMapper.class);
        DataSourceService service = dataSourceService(datasourceMapper, healthMapper, recordMapper);
        when(datasourceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(datasource()));
        when(healthMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(health()));
        when(recordMapper.selectRecentTrendByFingerprints(any(), any(), any(Integer.class)))
                .thenReturn(Collections.singletonList(record()));

        assertThat(service.listSummaries()).hasSize(1);

        ArgumentCaptor<LambdaQueryWrapper<DatasourceEntity>> datasourceCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(datasourceMapper).selectList(datasourceCaptor.capture());
        assertThat(datasourceCaptor.getValue().getSqlSelect())
                .contains("connection_fingerprint", "manual_connection_test_timeout_seconds")
                .doesNotContain("technical_metadata", "business_metadata");
        verify(recordMapper).selectRecentTrendByFingerprints(any(), any(), any(Integer.class));
    }

    @Test
    void datasourceBasicSummariesShouldSkipConnectionHealthHydration() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        DatasourceConnectionHealthMapper healthMapper = mock(DatasourceConnectionHealthMapper.class);
        DatasourceConnectionTestRecordMapper recordMapper = mock(DatasourceConnectionTestRecordMapper.class);
        DataSourceService service = dataSourceService(datasourceMapper, healthMapper, recordMapper);
        when(datasourceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(datasource()));

        List<DataSourceListView> summaries = service.listBasicSummaries();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getName()).isEqualTo("长期回归-客户订单数据源");
        assertThat(summaries.get(0).getConnectionStatus()).isNull();

        ArgumentCaptor<LambdaQueryWrapper<DatasourceEntity>> datasourceCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(datasourceMapper).selectList(datasourceCaptor.capture());
        assertThat(datasourceCaptor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "project_id", "name", "type_code", "schema_version_id", "enabled", "executable")
                .doesNotContain("connection_fingerprint",
                        "connection_status",
                        "last_connection_test_message",
                        "manual_connection_test_timeout_seconds",
                        "technical_metadata",
                        "business_metadata");
        verify(healthMapper, never()).selectList(any());
        verify(recordMapper, never()).selectRecentTrendByFingerprints(any(), any(), any(Integer.class));
    }

    @Test
    void datasourceOptionsShouldSelectOnlyOptionFieldsAndSkipHealthHydration() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        DatasourceConnectionHealthMapper healthMapper = mock(DatasourceConnectionHealthMapper.class);
        DatasourceConnectionTestRecordMapper recordMapper = mock(DatasourceConnectionTestRecordMapper.class);
        DataSourceService service = dataSourceService(datasourceMapper, healthMapper, recordMapper);
        when(datasourceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(datasource()));

        List<DataSourceOptionView> options = service.listBasicOptions();

        assertThat(options).hasSize(1);
        DataSourceOptionView option = options.get(0);
        assertThat(option.getName()).isEqualTo("长期回归-客户订单数据源");
        assertThat(option.getTypeCode()).isEqualTo("mysql8");
        assertThat(option.getSchemaVersionId()).isEqualTo(7001L);
        assertThat(option.getEnabled()).isTrue();
        assertThat(option.getExecutable()).isTrue();

        ArgumentCaptor<LambdaQueryWrapper<DatasourceEntity>> datasourceCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(datasourceMapper).selectList(datasourceCaptor.capture());
        assertThat(datasourceCaptor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "project_id", "name", "type_code", "schema_version_id", "enabled", "executable")
                .doesNotContain("connection_fingerprint",
                        "connection_status",
                        "last_connection_test_message",
                        "manual_connection_test_timeout_seconds",
                        "scheduled_connection_test_timeout_seconds",
                        "technical_metadata",
                        "business_metadata");
        verify(healthMapper, never()).selectList(any());
        verify(recordMapper, never()).selectRecentTrendByFingerprints(any(), any(), any(Integer.class));
    }

    @Test
    void datasourceIdsByTypeShouldSelectOnlyIdAndTypeAndSkipHealthHydration() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        DatasourceConnectionHealthMapper healthMapper = mock(DatasourceConnectionHealthMapper.class);
        DatasourceConnectionTestRecordMapper recordMapper = mock(DatasourceConnectionTestRecordMapper.class);
        DataSourceService service = dataSourceService(datasourceMapper, healthMapper, recordMapper);
        when(datasourceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(datasource()));

        Set<Long> ids = service.listAccessibleIdsByType("mysql8");

        assertThat(ids).containsExactly(11L);

        ArgumentCaptor<LambdaQueryWrapper<DatasourceEntity>> datasourceCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(datasourceMapper).selectList(datasourceCaptor.capture());
        assertThat(datasourceCaptor.getValue().getSqlSelect())
                .contains("id", "type_code")
                .doesNotContain("tenant_id",
                        "project_id",
                        "schema_version_id",
                        "connection_fingerprint",
                        "connection_status",
                        "technical_metadata",
                        "business_metadata");
        assertThat(datasourceCaptor.getValue().getTargetSql().toLowerCase()).contains("type_code");
        verify(healthMapper, never()).selectList(any());
        verify(recordMapper, never()).selectRecentTrendByFingerprints(any(), any(), any(Integer.class));
    }

    @Test
    void datasourceMetricFilterOptionsShouldSelectOnlyFilterFieldsAndSkipHealthHydration() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        DatasourceConnectionHealthMapper healthMapper = mock(DatasourceConnectionHealthMapper.class);
        DatasourceConnectionTestRecordMapper recordMapper = mock(DatasourceConnectionTestRecordMapper.class);
        DataSourceService service = dataSourceService(datasourceMapper, healthMapper, recordMapper);
        when(datasourceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(datasource()));

        List<RunMetricFilterOptionView> options = service.listMetricFilterOptions();

        assertThat(options).hasSize(1);
        assertThat(options.get(0).getName()).isEqualTo("长期回归-客户订单数据源");
        assertThat(options.get(0).getLabel()).isEqualTo("长期回归-客户订单数据源 / mysql8");
        assertThat(options.get(0).getTypeCode()).isEqualTo("mysql8");

        ArgumentCaptor<LambdaQueryWrapper<DatasourceEntity>> datasourceCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(datasourceMapper).selectList(datasourceCaptor.capture());
        assertThat(datasourceCaptor.getValue().getSqlSelect())
                .contains("id", "name", "type_code")
                .doesNotContain("tenant_id",
                        "project_id",
                        "schema_version_id",
                        "connection_fingerprint",
                        "connection_status",
                        "technical_metadata",
                        "business_metadata");
        verify(healthMapper, never()).selectList(any());
        verify(recordMapper, never()).selectRecentTrendByFingerprints(any(), any(), any(Integer.class));
    }

    @Test
    void recentTrendSqlShouldNotSelectFullHistoryRows() throws Exception {
        Method method = DatasourceConnectionTestRecordMapper.class.getMethod(
                "selectRecentTrendByFingerprints", String.class, Collection.class, int.class);
        Select select = method.getAnnotation(Select.class);

        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value()).toLowerCase();
        assertThat(sql)
                .contains("tenant_id", "connection_fingerprint", "connection_status", "ended_at")
                .doesNotContain("select r.*", "message", "duration_ms", "probe_mode", "timeout_seconds", "datasource_name");
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private DataSourceService dataSourceService(DatasourceMapper datasourceMapper,
                                                DatasourceConnectionHealthMapper healthMapper,
                                                DatasourceConnectionTestRecordMapper recordMapper) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        DatasourceConnectionHealthService healthService = new DatasourceConnectionHealthService(
                healthMapper,
                recordMapper,
                properties,
                new ClusterInstanceIdentity(properties),
                mock(ThreadPoolTaskExecutor.class),
                mock(ThreadPoolTaskExecutor.class));
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        return new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                mock(AggregationSourceCapabilityProvider.class),
                mock(MetadataSchemaService.class),
                mock(DataModelIndexRebuildQueueService.class),
                mock(BusinessMetaModelMetadataService.class),
                securityService,
                accessService,
                mock(DatasourceTypeCapabilityService.class),
                mock(DatasourceConnectionFingerprintService.class),
                healthService);
    }

    private DatasourceEntity datasource() {
        DatasourceEntity entity = new DatasourceEntity();
        entity.setId(11L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 27, 8, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 27, 8, 1, 0));
        entity.setName("长期回归-客户订单数据源");
        entity.setTypeCode("mysql8");
        entity.setSchemaVersionId(7001L);
        entity.setEnabled(1);
        entity.setExecutable(1);
        entity.setConnectionFingerprint("fp-customer-order");
        entity.setConnectionStatus("UNKNOWN");
        entity.setManualConnectionTestTimeoutSeconds(30);
        entity.setScheduledConnectionTestTimeoutSeconds(30);
        return entity;
    }

    private DatasourceConnectionHealthEntity health() {
        DatasourceConnectionHealthEntity entity = new DatasourceConnectionHealthEntity();
        entity.setTenantId("default");
        entity.setConnectionFingerprint("fp-customer-order");
        entity.setConnectionStatus("AVAILABLE");
        entity.setLastConnectionTestAt(LocalDateTime.of(2026, 6, 27, 8, 30, 0));
        entity.setLastConnectionTestMessage("连接成功");
        entity.setLastConnectionTestDurationMs(15L);
        entity.setProbeState("RUNNING");
        entity.setProbeLeaseUntil(LocalDateTime.now().plusMinutes(1));
        entity.setNextProbeAt(LocalDateTime.of(2026, 6, 27, 9, 30, 0));
        entity.setFailureCount(3);
        return entity;
    }

    private DatasourceConnectionTestRecordEntity record() {
        DatasourceConnectionTestRecordEntity entity = new DatasourceConnectionTestRecordEntity();
        entity.setTenantId("default");
        entity.setConnectionFingerprint("fp-customer-order");
        entity.setConnectionStatus("AVAILABLE");
        entity.setEndedAt(LocalDateTime.of(2026, 6, 27, 9, 0, 0));
        entity.setMessage("连接成功");
        entity.setDurationMs(15L);
        entity.setProbeMode("MANUAL");
        entity.setTimeoutSeconds(30);
        entity.setDatasourceName("长期回归-客户订单数据源");
        return entity;
    }
}
