package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.FileTransferEventOutboxEntity;
import com.jdragon.studio.infra.mapper.FileTransferEventOutboxMapper;
import com.jdragon.studio.infra.model.FileTransferEventIntent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FileTransferOutboxWriter {

    private static final int PAYLOAD_VERSION = 1;

    private final FileTransferEventOutboxMapper outboxMapper;
    private final StudioPlatformProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<String, Long> lastProgressEventAt = new ConcurrentHashMap<String, Long>();
    private final ThreadLocal<Set<String>> pendingProgressKeys = new ThreadLocal<Set<String>>();

    public FileTransferOutboxWriter(FileTransferEventOutboxMapper outboxMapper,
                                    StudioPlatformProperties properties,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.outboxMapper = outboxMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public FileTransferEventOutboxEntity append(FileTransferEventIntent intent) {
        validate(intent);
        FileTransferEventOutboxEntity event = new FileTransferEventOutboxEntity();
        event.setTenantId(intent.getTenantId());
        event.setProjectId(intent.getProjectId());
        event.setEventType(intent.getEventType().name());
        event.setRunId(intent.getRunId());
        event.setItemId(intent.getItemId());
        event.setOccurredAt(intent.getOccurredAt() == null ? LocalDateTime.now() : intent.getOccurredAt());
        event.setPayloadVersion(PAYLOAD_VERSION);
        event.setPayloadJson(safePayload(intent.getPayload()));
        outboxMapper.insert(event);
        afterCommit(() -> increment("studio.file-transfer.outbox.events-written", intent.getEventType().name()));
        return event;
    }

    public synchronized FileTransferEventOutboxEntity appendProgress(FileTransferEventIntent intent,
                                                                     String aggregationKey,
                                                                     boolean force) {
        if (force || aggregationKey == null || aggregationKey.isBlank()) {
            FileTransferEventOutboxEntity event = append(intent);
            if (aggregationKey != null) {
                afterCommit(() -> lastProgressEventAt.remove(aggregationKey));
            }
            return event;
        }
        long now = System.currentTimeMillis();
        long interval = progressIntervalMillis();
        Long previous = lastProgressEventAt.get(aggregationKey);
        if ((previous != null && now - previous.longValue() < interval) || progressPending(aggregationKey)) {
            increment("studio.file-transfer.outbox.events-suppressed", intent.getEventType().name());
            return null;
        }
        FileTransferEventOutboxEntity event = append(intent);
        markProgressPending(aggregationKey);
        return event;
    }

    private boolean progressPending(String aggregationKey) {
        Set<String> pending = pendingProgressKeys.get();
        return pending != null && pending.contains(aggregationKey);
    }

    private void markProgressPending(String aggregationKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            lastProgressEventAt.put(aggregationKey, System.currentTimeMillis());
            return;
        }
        Set<String> pending = pendingProgressKeys.get();
        if (pending == null) {
            pending = new HashSet<String>();
            pendingProgressKeys.set(pending);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    pendingProgressKeys.remove();
                }
            });
        }
        pending.add(aggregationKey);
        afterCommit(() -> lastProgressEventAt.put(aggregationKey, System.currentTimeMillis()));
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private long progressIntervalMillis() {
        Integer configured = properties.getFileTransfer().getProgressEventIntervalMillis();
        return configured == null ? 1000L : Math.max(100L, configured.longValue());
    }

    private Map<String, Object> safePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String normalized = key.toLowerCase();
            if (normalized.contains("password") || normalized.contains("secret")
                    || normalized.contains("token") || normalized.contains("credential")
                    || normalized.contains("worker") || normalized.contains("endpoint")
                    || normalized.contains("spec")) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
                safe.put(key, value);
            }
        }
        return safe.isEmpty() ? null : safe;
    }

    private void validate(FileTransferEventIntent intent) {
        if (intent == null || intent.getEventType() == null || intent.getRunId() == null
                || intent.getProjectId() == null || intent.getTenantId() == null
                || intent.getTenantId().isBlank()) {
            throw new IllegalArgumentException("File transfer outbox event identity is incomplete");
        }
    }

    private void increment(String name, String eventType) {
        if (meterRegistry != null) {
            Counter.builder(name)
                    .tag("event_type", eventType == null ? "UNKNOWN" : eventType)
                    .register(meterRegistry)
                    .increment();
        }
    }
}
