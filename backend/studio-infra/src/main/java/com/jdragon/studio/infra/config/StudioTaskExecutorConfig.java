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
}
