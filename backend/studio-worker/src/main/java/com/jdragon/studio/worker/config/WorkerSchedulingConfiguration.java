package com.jdragon.studio.worker.config;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.worker.runtime.runner.WorkerLifecycleRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.lang.reflect.Method;
import java.time.Duration;

/** Keeps dispatch work and the lease heartbeat on independent scheduler threads. */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class WorkerSchedulingConfiguration {

    public static final String HEARTBEAT_TASK_SCHEDULER = "workerHeartbeatTaskScheduler";
    public static final String HEARTBEAT_TASK_REGISTRAR = "workerHeartbeatTaskRegistrar";
    static final String DEFAULT_TASK_SCHEDULER = "taskScheduler";
    private static final int DEFAULT_WORKER_SCHEDULER_POOL_SIZE = 4;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    private static final Method HEARTBEAT_METHOD = heartbeatMethod();

    @Bean(name = DEFAULT_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler workerTaskScheduler(StudioPlatformProperties properties) {
        int poolSize = workerSchedulerPoolSize(properties);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("studio-worker-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        log.info("Configured Worker task scheduler pool size: {}", poolSize);
        return scheduler;
    }

    @Bean(name = HEARTBEAT_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler workerHeartbeatTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("studio-worker-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    public SchedulingConfigurer workerSchedulingConfigurer(
            @Qualifier(DEFAULT_TASK_SCHEDULER) TaskScheduler taskScheduler) {
        return taskRegistrar -> taskRegistrar.setTaskScheduler(taskScheduler);
    }

    @Bean(name = HEARTBEAT_TASK_REGISTRAR)
    @ConditionalOnProperty(name = "studio.worker.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
    public ScheduledTaskRegistrar workerHeartbeatTaskRegistrar(
            @Qualifier(HEARTBEAT_TASK_SCHEDULER) TaskScheduler heartbeatTaskScheduler,
            WorkerLifecycleRunner workerLifecycleRunner) {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        registrar.setTaskScheduler(heartbeatTaskScheduler);
        registrar.addFixedDelayTask(
                new ScheduledMethodRunnable(workerLifecycleRunner, HEARTBEAT_METHOD), HEARTBEAT_INTERVAL);
        return registrar;
    }

    private int workerSchedulerPoolSize(StudioPlatformProperties properties) {
        Integer configuredPoolSize = properties.getDispatch().getWorkerSchedulerPoolSize();
        return configuredPoolSize == null || configuredPoolSize < 1
                ? DEFAULT_WORKER_SCHEDULER_POOL_SIZE
                : configuredPoolSize;
    }

    private static Method heartbeatMethod() {
        try {
            return WorkerLifecycleRunner.class.getMethod("heartbeat");
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Worker heartbeat method is unavailable", ex);
        }
    }
}
