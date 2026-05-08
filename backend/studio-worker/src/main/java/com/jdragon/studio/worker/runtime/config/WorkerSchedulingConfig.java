package com.jdragon.studio.worker.runtime.config;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
public class WorkerSchedulingConfig implements SchedulingConfigurer {

    private final StudioPlatformProperties properties;

    public WorkerSchedulingConfig(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler workerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(workerSchedulerPoolSize());
        scheduler.setThreadNamePrefix("studio-worker-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(workerTaskScheduler());
    }

    private int workerSchedulerPoolSize() {
        Integer poolSize = properties.getDispatch() == null ? null : properties.getDispatch().getWorkerSchedulerPoolSize();
        return Math.max(2, poolSize == null ? 4 : poolSize.intValue());
    }
}
