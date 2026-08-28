package com.jdragon.studio.server.web.config;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.mapper.FileTransferEventConsumerCursorMapper;
import com.jdragon.studio.infra.mapper.FileTransferEventOutboxMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.FileTransferEventService;
import com.jdragon.studio.infra.service.FileTransferOutboxCleanupService;
import com.jdragon.studio.infra.service.StreamingHistoryCleanupService;
import com.jdragon.studio.infra.service.FileTransferRunService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.RunLogObjectStore;
import com.jdragon.studio.infra.service.StudioSecurityService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Server-only Outbox consumers. Workers only append events through the state mutation service. */
@Configuration(proxyBeanMethods = false)
public class FileTransferOutboxServerConfiguration {

    public static final String OUTBOX_TASK_SCHEDULER = "fileTransferOutboxTaskScheduler";

    @Bean(name = OUTBOX_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler fileTransferOutboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("studio-file-transfer-outbox-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Bean
    public FileTransferEventService fileTransferEventService(
            FileTransferRunMapper runMapper,
            FileTransferRunItemMapper itemMapper,
            FileTransferEventOutboxMapper outboxMapper,
            FileTransferEventConsumerCursorMapper cursorMapper,
            FileTransferRunService runService,
            StudioSecurityService securityService,
            ProjectResourceAccessService projectResourceAccessService,
            StudioPlatformProperties properties,
            ClusterInstanceIdentity instanceIdentity,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new FileTransferEventService(runMapper, itemMapper, outboxMapper, cursorMapper, runService,
                securityService, projectResourceAccessService, properties, instanceIdentity, meterRegistryProvider);
    }

    @Bean
    public FileTransferOutboxCleanupService fileTransferOutboxCleanupService(
            JdbcTemplate jdbcTemplate,
            ClusterLockService clusterLockService,
            StudioPlatformProperties properties,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new FileTransferOutboxCleanupService(jdbcTemplate, clusterLockService, properties, meterRegistryProvider);
    }

    @Bean
    public StreamingHistoryCleanupService streamingHistoryCleanupService(
            JdbcTemplate jdbcTemplate,
            ClusterLockService clusterLockService,
            StudioPlatformProperties properties,
            RunLogObjectStore runLogObjectStore,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new StreamingHistoryCleanupService(jdbcTemplate, clusterLockService, properties,
                runLogObjectStore, meterRegistryProvider);
    }
}
