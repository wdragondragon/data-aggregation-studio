package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.FileTransferEventOutboxEntity;
import com.jdragon.studio.infra.mapper.FileTransferEventOutboxMapper;
import com.jdragon.studio.infra.model.FileTransferEventIntent;
import com.jdragon.studio.infra.model.FileTransferOutboxEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferOutboxWriterTest {

    @Test
    void writesSanitizedEventAndSuppressesRepeatedProgress() {
        FileTransferEventOutboxMapper mapper = mock(FileTransferEventOutboxMapper.class);
        when(mapper.insert(any(FileTransferEventOutboxEntity.class))).thenAnswer(invocation -> {
            FileTransferEventOutboxEntity entity = invocation.getArgument(0);
            entity.setId(101L);
            return 1;
        });
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getFileTransfer().setProgressEventIntervalMillis(60_000);
        FileTransferOutboxWriter writer = new FileTransferOutboxWriter(mapper, properties,
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        FileTransferEventIntent intent = FileTransferEventIntent.builder()
                .tenantId("tenant-a")
                .projectId(10L)
                .eventType(FileTransferOutboxEventType.ITEM_CHANGED)
                .runId(100L)
                .itemId(200L)
                .payload(Map.of("reason", "progress", "password", "secret", "workerAddress", "internal"))
                .build();

        FileTransferEventOutboxEntity first = writer.appendProgress(intent, "item:200", false);
        assertEquals(101L, first.getId());
        assertEquals(Map.of("reason", "progress"), first.getPayloadJson());
        assertNull(writer.appendProgress(intent, "item:200", false));
        assertSame(first.getClass(), writer.appendProgress(intent, "item:200", true).getClass());
        assertSame(first.getClass(), writer.appendProgress(intent, "item:200", false).getClass());
        verify(mapper, times(3)).insert(any(FileTransferEventOutboxEntity.class));
    }

    @Test
    void ordinaryEventStoresNullPayload() {
        FileTransferEventOutboxMapper mapper = mock(FileTransferEventOutboxMapper.class);
        when(mapper.insert(any(FileTransferEventOutboxEntity.class))).thenReturn(1);
        FileTransferOutboxWriter writer = new FileTransferOutboxWriter(mapper, new StudioPlatformProperties(),
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        FileTransferEventOutboxEntity event = writer.append(FileTransferEventIntent.builder()
                .tenantId("tenant-a")
                .projectId(10L)
                .eventType(FileTransferOutboxEventType.RUN_CREATED)
                .runId(100L)
                .build());
        assertNull(event.getPayloadJson());
    }

    @Test
    void rolledBackProgressDoesNotSuppressTheNextCommittedEvent() {
        FileTransferEventOutboxMapper mapper = mock(FileTransferEventOutboxMapper.class);
        when(mapper.insert(any(FileTransferEventOutboxEntity.class))).thenReturn(1);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getFileTransfer().setProgressEventIntervalMillis(60_000);
        FileTransferOutboxWriter writer = new FileTransferOutboxWriter(mapper, properties,
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        FileTransferEventIntent intent = progressIntent();

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertSame(FileTransferEventOutboxEntity.class,
                    writer.appendProgress(intent, "item:200", false).getClass());
            assertNull(writer.appendProgress(intent, "item:200", false));
            completeSynchronization(TransactionSynchronization.STATUS_ROLLED_BACK, false);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        assertSame(FileTransferEventOutboxEntity.class,
                writer.appendProgress(intent, "item:200", false).getClass());
        verify(mapper, times(2)).insert(any(FileTransferEventOutboxEntity.class));
    }

    @Test
    void committedProgressStartsTheThrottleWindowOnlyAfterCommit() {
        FileTransferEventOutboxMapper mapper = mock(FileTransferEventOutboxMapper.class);
        when(mapper.insert(any(FileTransferEventOutboxEntity.class))).thenReturn(1);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getFileTransfer().setProgressEventIntervalMillis(60_000);
        FileTransferOutboxWriter writer = new FileTransferOutboxWriter(mapper, properties,
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        FileTransferEventIntent intent = progressIntent();

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertSame(FileTransferEventOutboxEntity.class,
                    writer.appendProgress(intent, "item:200", false).getClass());
            completeSynchronization(TransactionSynchronization.STATUS_COMMITTED, true);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        assertNull(writer.appendProgress(intent, "item:200", false));
        verify(mapper, times(1)).insert(any(FileTransferEventOutboxEntity.class));
    }

    private static FileTransferEventIntent progressIntent() {
        return FileTransferEventIntent.builder()
                .tenantId("tenant-a")
                .projectId(10L)
                .eventType(FileTransferOutboxEventType.ITEM_CHANGED)
                .runId(100L)
                .itemId(200L)
                .build();
    }

    private static void completeSynchronization(int status, boolean committed) {
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        if (committed) {
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        }
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
        TransactionSynchronizationManager.clearSynchronization();
    }
}
