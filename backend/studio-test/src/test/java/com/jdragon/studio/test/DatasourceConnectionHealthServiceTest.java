package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DatasourceConnectionHealthEntity;
import com.jdragon.studio.infra.mapper.DatasourceConnectionHealthMapper;
import com.jdragon.studio.infra.mapper.DatasourceConnectionTestRecordMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.DatasourceConnectionHealthService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasourceConnectionHealthServiceTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        if (TableInfoHelper.getTableInfo(DatasourceConnectionHealthEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), DatasourceConnectionHealthEntity.class);
        }
    }

    @Test
    void shouldKeepProbeLeaseRunningWhenProbeTimesOutBeforeTaskStops() throws Exception {
        DatasourceConnectionHealthMapper healthMapper = mock(DatasourceConnectionHealthMapper.class);
        DatasourceConnectionTestRecordMapper recordMapper = mock(DatasourceConnectionTestRecordMapper.class);
        when(healthMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(health("tenant-a", "fp-a", "IDLE"));
        when(healthMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(recordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        ThreadPoolTaskExecutor manualExecutor = executor("manual-timeout-", 1);
        ThreadPoolTaskExecutor scheduledExecutor = executor("scheduled-timeout-", 1);
        DatasourceConnectionHealthService service = service(healthMapper, recordMapper, manualExecutor, scheduledExecutor, properties());
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);

        ConnectionTestResult result = service.runManualProbe(definition("tenant-a", "fp-a"), () -> {
            taskStarted.countDown();
            releaseTask.await(5, TimeUnit.SECONDS);
            ConnectionTestResult success = new ConnectionTestResult();
            success.setSuccess(true);
            success.setMessage("late success");
            return success;
        }, 1);

        assertThat(taskStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.getStatus()).isEqualTo(DataSourceConnectionStatus.UNAVAILABLE);
        assertThat(result.getTesting()).isTrue();

        ArgumentCaptor<LambdaUpdateWrapper<DatasourceConnectionHealthEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(healthMapper, atLeast(2)).update(isNull(), captor.capture());
        String timeoutUpdate = captor.getAllValues().stream()
                .map(LambdaUpdateWrapper::getSqlSet)
                .filter(sqlSet -> sqlSet != null && sqlSet.contains("connectionStatus"))
                .findFirst()
                .orElse("");
        assertThat(timeoutUpdate)
                .contains("connectionStatus", "lastConnectionTestAt", "probeLeaseUntil")
                .doesNotContain("probeState", "probeRunId", "probeStartedAt");

        releaseTask.countDown();
        manualExecutor.shutdown();
        scheduledExecutor.shutdown();
    }

    @Test
    void shouldWaitForRunningFingerprintBeforeCheckingManualPoolCapacity() throws Exception {
        DatasourceConnectionHealthMapper healthMapper = mock(DatasourceConnectionHealthMapper.class);
        DatasourceConnectionTestRecordMapper recordMapper = mock(DatasourceConnectionTestRecordMapper.class);
        when(healthMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(health("tenant-a", "fp-a", "RUNNING"));
        when(healthMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        when(recordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        StudioPlatformProperties properties = properties();
        ThreadPoolTaskExecutor manualExecutor = executor("manual-busy-", 1);
        ThreadPoolTaskExecutor scheduledExecutor = executor("scheduled-busy-", 1);
        DatasourceConnectionHealthService service = service(healthMapper, recordMapper, manualExecutor, scheduledExecutor, properties);
        CountDownLatch formProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseFormProbe = new CountDownLatch(1);
        Thread formProbe = new Thread(() -> service.runCurrentFormProbe(() -> {
            formProbeStarted.countDown();
            releaseFormProbe.await(5, TimeUnit.SECONDS);
            ConnectionTestResult success = new ConnectionTestResult();
            success.setSuccess(true);
            return success;
        }, 5), "manual-capacity-holder");
        formProbe.start();
        assertThat(formProbeStarted.await(1, TimeUnit.SECONDS)).isTrue();

        AtomicInteger duplicateProbeCalls = new AtomicInteger();
        ConnectionTestResult result = service.runManualProbe(definition("tenant-a", "fp-a"), () -> {
            duplicateProbeCalls.incrementAndGet();
            ConnectionTestResult success = new ConnectionTestResult();
            success.setSuccess(true);
            return success;
        }, 5);

        assertThat(result.getTesting()).isTrue();
        assertThat(result.getBusy()).isFalse();
        assertThat(duplicateProbeCalls).hasValue(0);

        releaseFormProbe.countDown();
        formProbe.join(1000L);
        manualExecutor.shutdown();
        scheduledExecutor.shutdown();
    }

    private DatasourceConnectionHealthService service(DatasourceConnectionHealthMapper healthMapper,
                                                      DatasourceConnectionTestRecordMapper recordMapper,
                                                      ThreadPoolTaskExecutor manualExecutor,
                                                      ThreadPoolTaskExecutor scheduledExecutor,
                                                      StudioPlatformProperties properties) {
        return new DatasourceConnectionHealthService(healthMapper, recordMapper, properties,
                new ClusterInstanceIdentity(properties), manualExecutor, scheduledExecutor);
    }

    private ThreadPoolTaskExecutor executor(String prefix, int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    private StudioPlatformProperties properties() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInstanceId("datasource-health-test");
        properties.getDatasourceHealth().getManual().setMaxConcurrency(1);
        properties.getDatasourceHealth().getScheduled().setMaxConcurrency(1);
        properties.getDatasourceHealth().setGlobalMaxConcurrency(1);
        properties.getDatasourceHealth().setManualReservedConcurrency(0);
        properties.getDatasourceHealth().setManualWaitRunningSeconds(0);
        properties.getDatasourceHealth().getHistory().setRecentLimit(10);
        return properties;
    }

    private DataSourceDefinition definition(String tenantId, String fingerprint) {
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(11L);
        definition.setTenantId(tenantId);
        definition.setName("mysql_source");
        definition.setTypeCode("mysql8");
        definition.setEnabled(true);
        definition.setExecutable(true);
        definition.setConnectionFingerprint(fingerprint);
        return definition;
    }

    private DatasourceConnectionHealthEntity health(String tenantId, String fingerprint, String probeState) {
        DatasourceConnectionHealthEntity health = new DatasourceConnectionHealthEntity();
        health.setTenantId(tenantId);
        health.setConnectionFingerprint(fingerprint);
        health.setConnectionStatus(DataSourceConnectionStatus.AVAILABLE.name());
        health.setProbeState(probeState);
        health.setProbeRunId("running-run");
        health.setProbeLeaseUntil(LocalDateTime.now().plusMinutes(5));
        health.setFailureCount(0);
        return health;
    }
}
