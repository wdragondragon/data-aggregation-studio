package com.jdragon.studio.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class StudioTaskExecutorConfig {

    @Bean(name = "modelSyncTaskExecutor")
    public Executor modelSyncTaskExecutor(StudioPlatformProperties properties) {
        int configuredConcurrency = properties.getModelSyncTask() == null
                || properties.getModelSyncTask().getMaxConcurrency() == null
                ? 1
                : properties.getModelSyncTask().getMaxConcurrency().intValue();
        int safeConcurrency = Math.max(1, Math.min(configuredConcurrency, 2));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("model-sync-task-");
        executor.setCorePoolSize(safeConcurrency);
        executor.setMaxPoolSize(safeConcurrency);
        executor.setQueueCapacity(200);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "indexRebuildQueueExecutor")
    public Executor indexRebuildQueueExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("index-rebuild-queue-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50000);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "lineageRebuildExecutor")
    public Executor lineageRebuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("lineage-rebuild-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1000);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "datasourceManualProbeExecutor")
    public ThreadPoolTaskExecutor datasourceManualProbeExecutor(StudioPlatformProperties properties) {
        int concurrency = datasourceHealthConcurrency(properties, true, 2);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("datasource-manual-probe-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "datasourceScheduledProbeExecutor")
    public ThreadPoolTaskExecutor datasourceScheduledProbeExecutor(StudioPlatformProperties properties) {
        int concurrency = datasourceHealthConcurrency(properties, false, 3);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("datasource-scheduled-probe-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    private int datasourceHealthConcurrency(StudioPlatformProperties properties, boolean manual, int defaultValue) {
        if (properties == null || properties.getDatasourceHealth() == null) {
            return defaultValue;
        }
        StudioPlatformProperties.ProbeProperties probe = manual
                ? properties.getDatasourceHealth().getManual()
                : properties.getDatasourceHealth().getScheduled();
        Integer configured = probe == null ? null : probe.getMaxConcurrency();
        return Math.max(1, configured == null ? defaultValue : configured.intValue());
    }
}
