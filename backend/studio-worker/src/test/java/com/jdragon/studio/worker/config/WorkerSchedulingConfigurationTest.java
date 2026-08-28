package com.jdragon.studio.worker.config;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.service.DispatchProtectedPayloadService;
import com.jdragon.studio.infra.service.FileTransferStateMutationService;
import com.jdragon.studio.infra.service.RuntimeClusterHeartbeatService;
import com.jdragon.studio.worker.runtime.runner.WorkerLifecycleRunner;
import com.jdragon.studio.worker.runtime.config.WorkerSchedulingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class WorkerSchedulingConfigurationTest {

    @Test
    void legacySchedulingCompatibilityClassIsNotASecondSpringConfiguration() {
        assertThat(WorkerSchedulingConfig.class.getAnnotation(Configuration.class)).isNull();
    }

    @Test
    void configuresWorkerSchedulerPoolFromDispatchProperties() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(WorkerSchedulingConfiguration.class)
                .withPropertyValues("studio.worker.lifecycle.enabled=false")
                .withBean(StudioPlatformProperties.class, () -> {
                    StudioPlatformProperties properties = new StudioPlatformProperties();
                    properties.getDispatch().setWorkerSchedulerPoolSize(7);
                    return properties;
                });

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskScheduler scheduler = context.getBean(
                    WorkerSchedulingConfiguration.DEFAULT_TASK_SCHEDULER, ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(7);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("studio-worker-scheduler-");
        });
    }

    @Test
    void providesIndependentHeartbeatScheduler() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(WorkerSchedulingConfiguration.class)
                .withPropertyValues("studio.worker.lifecycle.enabled=false")
                .withBean(StudioPlatformProperties.class, StudioPlatformProperties::new);

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskScheduler workerScheduler = context.getBean(
                    WorkerSchedulingConfiguration.DEFAULT_TASK_SCHEDULER, ThreadPoolTaskScheduler.class);
            ThreadPoolTaskScheduler heartbeatScheduler = context.getBean(
                    WorkerSchedulingConfiguration.HEARTBEAT_TASK_SCHEDULER, ThreadPoolTaskScheduler.class);

            assertThat(heartbeatScheduler).isNotSameAs(workerScheduler);
            assertThat(heartbeatScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(heartbeatScheduler.getThreadNamePrefix()).isEqualTo("studio-worker-heartbeat-");
        });
    }

    @Test
    void pinsAnnotationDrivenTasksToWorkerScheduler() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(WorkerSchedulingConfiguration.class)
                .withPropertyValues("studio.worker.lifecycle.enabled=false")
                .withBean(StudioPlatformProperties.class, StudioPlatformProperties::new);

        contextRunner.run(context -> {
            ThreadPoolTaskScheduler workerScheduler = context.getBean(
                    WorkerSchedulingConfiguration.DEFAULT_TASK_SCHEDULER, ThreadPoolTaskScheduler.class);
            SchedulingConfigurer configurer = context.getBean(SchedulingConfigurer.class);
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

            configurer.configureTasks(registrar);

            assertThat(ReflectionTestUtils.getField(registrar, "taskScheduler")).isSameAs(workerScheduler);
        });
    }

    @Test
    void heartbeatIsRegisteredProgrammaticallyOnDedicatedScheduler() throws Exception {
        Method heartbeat = WorkerLifecycleRunner.class.getMethod("heartbeat");
        Scheduled scheduled = heartbeat.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNull();

        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(WorkerSchedulingConfiguration.class, HeartbeatRunnerConfiguration.class)
                .withBean(RuntimeClusterHeartbeatService.class,
                        () -> mock(RuntimeClusterHeartbeatService.class))
                .withBean(DispatchProtectedPayloadService.class,
                        () -> mock(DispatchProtectedPayloadService.class))
                .withBean(FileTransferStateMutationService.class,
                        () -> mock(FileTransferStateMutationService.class))
                .withBean(DispatchTaskMapper.class, () -> mock(DispatchTaskMapper.class))
                .withBean(StudioPlatformProperties.class, StudioPlatformProperties::new);

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ScheduledTaskRegistrar registrar = context.getBean(
                    WorkerSchedulingConfiguration.HEARTBEAT_TASK_REGISTRAR, ScheduledTaskRegistrar.class);
            assertThat(registrar.getScheduledTasks()).hasSize(1);
            assertThat(registrar.getFixedDelayTaskList()).singleElement().satisfies(task -> {
                assertThat(task.getIntervalDuration()).isEqualTo(java.time.Duration.ofSeconds(5));
                assertThat(task.getRunnable().toString())
                        .contains(WorkerLifecycleRunner.class.getName())
                        .contains(heartbeat.getName());
            });
        });
    }

    @Test
    void activeRunLogSyncUsesTheBoundedSixtySecondCadence() throws Exception {
        Method sync = WorkerLifecycleRunner.class.getMethod("syncActiveRunLogs");
        Scheduled scheduled = sync.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelay()).isEqualTo(60000L);
        assertThat(scheduled.fixedDelay()).isEqualTo(60000L);
    }

    @Test
    void heartbeatRunsWhileDefaultSchedulerThreadIsBlocked() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(WorkerSchedulingConfiguration.class, SchedulingProbeConfiguration.class)
                .withBean(RuntimeClusterHeartbeatService.class,
                        () -> mock(RuntimeClusterHeartbeatService.class))
                .withBean(DispatchProtectedPayloadService.class,
                        () -> mock(DispatchProtectedPayloadService.class))
                .withBean(FileTransferStateMutationService.class,
                        () -> mock(FileTransferStateMutationService.class))
                .withBean(DispatchTaskMapper.class, () -> mock(DispatchTaskMapper.class))
                .withBean(StudioPlatformProperties.class, () -> {
                    StudioPlatformProperties properties = new StudioPlatformProperties();
                    properties.getDispatch().setWorkerSchedulerPoolSize(1);
                    return properties;
                });

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SchedulingProbe probe = context.getBean(SchedulingProbe.class);
            try {
                assertThat(probe.defaultSchedulerStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(probe.heartbeatSchedulerRan.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(probe.defaultSchedulerThread.get()).startsWith("studio-worker-scheduler-");
                assertThat(probe.heartbeatSchedulerThread.get()).startsWith("studio-worker-heartbeat-");
            } finally {
                probe.releaseDefaultScheduler.countDown();
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingProbeConfiguration {

        @Bean
        SchedulingProbe schedulingProbe() {
            return new SchedulingProbe();
        }

        @Bean
        WorkerLifecycleRunner workerLifecycleRunner(SchedulingProbe probe) {
            WorkerLifecycleRunner runner = mock(WorkerLifecycleRunner.class);
            doAnswer(invocation -> {
                probe.heartbeatSchedulerThread.set(Thread.currentThread().getName());
                probe.heartbeatSchedulerRan.countDown();
                return null;
            }).when(runner).heartbeat();
            return runner;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HeartbeatRunnerConfiguration {

        @Bean
        WorkerLifecycleRunner workerLifecycleRunner() {
            return mock(WorkerLifecycleRunner.class);
        }
    }

    static class SchedulingProbe {
        private final CountDownLatch defaultSchedulerStarted = new CountDownLatch(1);
        private final CountDownLatch releaseDefaultScheduler = new CountDownLatch(1);
        private final CountDownLatch heartbeatSchedulerRan = new CountDownLatch(1);
        private final AtomicReference<String> defaultSchedulerThread = new AtomicReference<String>();
        private final AtomicReference<String> heartbeatSchedulerThread = new AtomicReference<String>();

        @Scheduled(initialDelay = 0L, fixedDelay = 60000L)
        public void blockDefaultScheduler() throws InterruptedException {
            defaultSchedulerThread.set(Thread.currentThread().getName());
            defaultSchedulerStarted.countDown();
            releaseDefaultScheduler.await(5, TimeUnit.SECONDS);
        }

    }
}
