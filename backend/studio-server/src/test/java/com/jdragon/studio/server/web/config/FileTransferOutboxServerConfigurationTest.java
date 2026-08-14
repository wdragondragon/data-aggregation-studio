package com.jdragon.studio.server.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class FileTransferOutboxServerConfigurationTest {

    @Test
    void shouldConfigureDedicatedOutboxScheduler() {
        ThreadPoolTaskScheduler scheduler = new FileTransferOutboxServerConfiguration()
                .fileTransferOutboxTaskScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("studio-file-transfer-outbox-");
            assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
        } finally {
            scheduler.shutdown();
        }
    }
}
