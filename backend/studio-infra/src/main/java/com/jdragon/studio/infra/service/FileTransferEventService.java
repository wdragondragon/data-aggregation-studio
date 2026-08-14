package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.FileTransferQueueEventView;
import com.jdragon.studio.dto.model.FileTransferRunItemView;
import com.jdragon.studio.dto.model.FileTransferRunView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.FileTransferEventConsumerCursorEntity;
import com.jdragon.studio.infra.entity.FileTransferEventOutboxEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferEventConsumerCursorMapper;
import com.jdragon.studio.infra.mapper.FileTransferEventOutboxMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.model.FileTransferEventMode;
import com.jdragon.studio.infra.model.FileTransferOutboxEventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class FileTransferEventService {

    private static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final int RECENT_CHANGE_LIMIT = 2_000;
    private static final int MAX_ITEMS_PER_EVENT = 200;
    private static final int SCOPE_LOCK_STRIPES = 64;
    private static final List<String> ACTIVE_RUN_STATUSES = List.of("QUEUED", "RUNNING", "PAUSED");

    private final FileTransferRunMapper runMapper;
    private final FileTransferRunItemMapper itemMapper;
    private final FileTransferEventOutboxMapper outboxMapper;
    private final FileTransferEventConsumerCursorMapper cursorMapper;
    private final FileTransferRunService runService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final StudioPlatformProperties properties;
    private final ClusterInstanceIdentity instanceIdentity;
    private final MeterRegistry meterRegistry;
    private final Map<ScopeKey, CopyOnWriteArrayList<EmitterConnection>> connectionsByScope =
            new ConcurrentHashMap<ScopeKey, CopyOnWriteArrayList<EmitterConnection>>();
    private final Object[] scopeLocks = createScopeLocks();
    private final Map<ScopeKey, LegacyScopeState> legacyStatesByScope =
            new ConcurrentHashMap<ScopeKey, LegacyScopeState>();
    private final Map<ScopeKey, FileTransferEventConsumerCursorEntity> cursorsByScope =
            new ConcurrentHashMap<ScopeKey, FileTransferEventConsumerCursorEntity>();
    private final Map<ScopeKey, Long> cursorLagByScope = new ConcurrentHashMap<ScopeKey, Long>();
    private final AtomicLong cursorLag = new AtomicLong();
    private volatile LocalDateTime lastConsumedAt;
    private volatile String lastPollError;

    public FileTransferEventService(FileTransferRunMapper runMapper,
                                    FileTransferRunItemMapper itemMapper,
                                    FileTransferEventOutboxMapper outboxMapper,
                                    FileTransferEventConsumerCursorMapper cursorMapper,
                                    FileTransferRunService runService,
                                    StudioSecurityService securityService,
                                    ProjectResourceAccessService projectResourceAccessService,
                                    StudioPlatformProperties properties,
                                    ClusterInstanceIdentity instanceIdentity,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.outboxMapper = outboxMapper;
        this.cursorMapper = cursorMapper;
        this.runService = runService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.properties = properties;
        this.instanceIdentity = instanceIdentity;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        if (this.meterRegistry != null) {
            Gauge.builder("studio.file-transfer.outbox.cursor-lag", cursorLag, AtomicLong::get)
                    .tag("mode", eventMode().name())
                    .register(this.meterRegistry);
        }
    }

    /** Compatibility constructor retained for focused unit tests of legacy scan mode. */
    FileTransferEventService(FileTransferRunMapper runMapper,
                             FileTransferRunItemMapper itemMapper,
                             FileTransferRunService runService,
                             StudioSecurityService securityService,
                             ProjectResourceAccessService projectResourceAccessService) {
        this(runMapper, itemMapper, null, null, runService, securityService, projectResourceAccessService,
                legacyProperties(), new ClusterInstanceIdentity(legacyProperties()), emptyMeterRegistryProvider());
    }

    public SseEmitter connect(String lastEventIdHeader) {
        ScopeKey scope = currentScope();
        SseEmitter emitter = createEmitter();
        EmitterConnection connection = new EmitterConnection(emitter, scope, parseEventId(lastEventIdHeader));
        emitter.onCompletion(() -> removeConnection(connection));
        emitter.onTimeout(() -> removeConnection(connection));
        emitter.onError(throwable -> removeConnection(connection));

        synchronized (scopeLock(scope)) {
            connectionsByScope.compute(scope, (ignored, current) -> {
                CopyOnWriteArrayList<EmitterConnection> connections = current == null
                        ? new CopyOnWriteArrayList<EmitterConnection>() : current;
                connections.add(connection);
                return connections;
            });
            if (eventMode() == FileTransferEventMode.LEGACY_SCAN) {
                legacyStatesByScope.computeIfAbsent(scope,
                        ignored -> new LegacyScopeState(LocalDateTime.now().minusSeconds(2L)));
                sendSnapshot(connection, 0L);
                return emitter;
            }
            try {
                long maximumId = maximumEventId(scope);
                if (connection.lastSentEventId.get() < 0L) {
                    sendSnapshot(connection, maximumId);
                } else {
                    replay(connection, maximumId);
                }
                ensureCursor(scope, maximumId);
                lastPollError = null;
            } catch (RuntimeException exception) {
                removeConnection(connection);
                log.error("Failed to open file transfer Outbox event stream", exception);
                throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                        "File transfer Outbox event stream is unavailable");
            }
        }
        return emitter;
    }

    public SseEmitter connect() {
        return connect(null);
    }

    SseEmitter createEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MILLIS);
    }

    public void publishRunChanged(Long runId) {
        if (eventMode() != FileTransferEventMode.LEGACY_SCAN || runId == null) {
            return;
        }
        ScopeKey scope = currentScope();
        FileTransferRunEntity run = findRun(scope, runId);
        if (run == null) {
            publishRunRemoved(runId);
            return;
        }
        FileTransferQueueEventView event = event("RUN_CHANGED", runId, null, null);
        event.setRun(runService.toRunView(run));
        publishLegacy(scope, "run-changed", event);
        legacyStatesByScope.computeIfAbsent(scope,
                ignored -> new LegacyScopeState(LocalDateTime.now().minusSeconds(2L)))
                .runFingerprints.put(runId, runFingerprint(run));
    }

    public void publishRunRemoved(Long runId) {
        if (eventMode() != FileTransferEventMode.LEGACY_SCAN || runId == null) {
            return;
        }
        ScopeKey scope = currentScope();
        publishLegacy(scope, "run-removed", event("RUN_REMOVED", runId, null, null));
        LegacyScopeState state = legacyStatesByScope.get(scope);
        if (state != null) {
            state.runFingerprints.remove(runId);
            state.itemFingerprints.entrySet().removeIf(entry -> Objects.equals(entry.getValue().runId, runId));
        }
    }

    public void publishItemRemoved(Long runId, Long itemId) {
        if (eventMode() != FileTransferEventMode.LEGACY_SCAN || runId == null || itemId == null) {
            return;
        }
        ScopeKey scope = currentScope();
        FileTransferQueueEventView event = event("ITEM_REMOVED", runId, itemId, null);
        FileTransferRunEntity run = findRun(scope, runId);
        if (run != null) {
            event.setRun(runService.toRunView(run));
        }
        publishLegacy(scope, "item-removed", event);
        LegacyScopeState state = legacyStatesByScope.get(scope);
        if (state != null) {
            state.itemFingerprints.remove(itemId);
        }
    }

    @Scheduled(fixedDelayString = "${studio.file-transfer.event-poll-delay-millis:250}",
            scheduler = "fileTransferOutboxTaskScheduler")
    public void publishDatabaseChanges() {
        if (connectionsByScope.isEmpty()) {
            return;
        }
        if (eventMode() == FileTransferEventMode.LEGACY_SCAN) {
            publishLegacyDatabaseChanges();
            return;
        }
        for (ScopeKey scope : new ArrayList<ScopeKey>(connectionsByScope.keySet())) {
            pollOutbox(scope);
        }
    }

    @Scheduled(fixedDelay = 15_000L, scheduler = "fileTransferOutboxTaskScheduler")
    public void heartbeat() {
        for (CopyOnWriteArrayList<EmitterConnection> connections : connectionsByScope.values()) {
            for (EmitterConnection connection : connections) {
                try {
                    connection.emitter.send(SseEmitter.event().name("heartbeat").data("{}"));
                    connection.lastHeartbeatAt = LocalDateTime.now();
                } catch (IOException | IllegalStateException exception) {
                    removeConnection(connection);
                }
            }
        }
        touchConnectedScopeCursors();
        refreshInstanceCursorLag();
    }

    private void pollOutbox(ScopeKey scope) {
        CopyOnWriteArrayList<EmitterConnection> connections = connectionsByScope.get(scope);
        if (connections == null || connections.isEmpty()) {
            return;
        }
        synchronized (scopeLock(scope)) {
            try {
                FileTransferEventConsumerCursorEntity cursor = ensureCursor(scope);
                long lastEventId = value(cursor.getLastEventId());
                int batchSize = eventBatchSize();
                List<FileTransferEventOutboxEntity> events = selectEventsAfter(scope, lastEventId, batchSize);
                if (events.isEmpty()) {
                    setLag(scope, 0L);
                    lastPollError = null;
                    return;
                }
                increment("studio.file-transfer.outbox.events-polled", "mode", "OUTBOX", events.size());
                List<OutboundEvent> outbound = buildOutboundEvents(scope, events);
                for (OutboundEvent candidate : outbound) {
                    publishOutbox(scope, candidate, false);
                }
                long scannedId = events.get(events.size() - 1).getId();
                touchCursor(cursor, scannedId);
                if (events.size() < batchSize) {
                    setLag(scope, 0L);
                } else {
                    updateLag(scope, scannedId);
                }
                lastConsumedAt = LocalDateTime.now();
                lastPollError = null;
            } catch (RuntimeException exception) {
                lastPollError = exception.getMessage();
                log.error("Failed to consume file transfer Outbox for tenant {} project {}",
                        scope.tenantId, scope.projectId, exception);
            }
        }
    }

    private void replay(EmitterConnection connection, long maximumId) {
        long requestedId = connection.lastSentEventId.get();
        long minimumId = minimumEventId(connection.scope);
        if (requestedId > maximumId || (minimumId > 0L && requestedId < minimumId - 1L)) {
            sendSnapshot(connection, maximumId);
            return;
        }
        int replayLimit = replayMaxEvents();
        List<FileTransferEventOutboxEntity> events = selectEventsAfter(connection.scope, requestedId, replayLimit + 1);
        if (events.size() > replayLimit) {
            sendSnapshot(connection, maximumId);
            return;
        }
        for (OutboundEvent event : buildOutboundEvents(connection.scope, events)) {
            sendOutbox(connection, event, true);
        }
        if (events.isEmpty() && requestedId < maximumId) {
            sendSnapshot(connection, maximumId);
        }
    }

    private List<OutboundEvent> buildOutboundEvents(ScopeKey scope,
                                                     List<FileTransferEventOutboxEntity> sourceEvents) {
        if (sourceEvents == null || sourceEvents.isEmpty()) {
            return new ArrayList<OutboundEvent>();
        }
        Map<Long, RunBatch> batches = new LinkedHashMap<Long, RunBatch>();
        for (FileTransferEventOutboxEntity source : sourceEvents) {
            if (source == null || source.getId() == null || source.getRunId() == null) {
                continue;
            }
            RunBatch batch = batches.computeIfAbsent(source.getRunId(), RunBatch::new);
            batch.accept(source);
        }

        Set<Long> runIds = new LinkedHashSet<Long>();
        Set<Long> itemIds = new LinkedHashSet<Long>();
        for (RunBatch batch : batches.values()) {
            if (batch.runRemoved == null) {
                runIds.add(batch.runId);
                itemIds.addAll(batch.changedItems.keySet());
            }
        }
        Map<Long, FileTransferRunView> runs = loadRunViews(scope, runIds);
        Map<Long, FileTransferRunItemView> items = loadItemViews(scope, itemIds);
        List<OutboundEvent> outbound = new ArrayList<OutboundEvent>();
        for (RunBatch batch : batches.values()) {
            if (batch.runRemoved != null) {
                outbound.add(new OutboundEvent(batch.runRemoved.getId(), "run-removed",
                        event("RUN_REMOVED", batch.runId, null, batch.runRemoved.getId(),
                                batch.runRemoved.getOccurredAt())));
                continue;
            }
            FileTransferRunView run = runs.get(batch.runId);
            for (FileTransferEventOutboxEntity removed : batch.removedItems.values()) {
                FileTransferQueueEventView view = event("ITEM_REMOVED", batch.runId,
                        removed.getItemId(), removed.getId(), removed.getOccurredAt());
                view.setRun(run);
                outbound.add(new OutboundEvent(removed.getId(), "item-removed", view));
            }
            List<Map.Entry<Long, FileTransferEventOutboxEntity>> changed =
                    new ArrayList<Map.Entry<Long, FileTransferEventOutboxEntity>>(batch.changedItems.entrySet());
            changed.sort(Comparator.comparingLong(entry -> entry.getValue().getId()));
            int offset = 0;
            while (offset < changed.size()) {
                int end = Math.min(offset + MAX_ITEMS_PER_EVENT, changed.size());
                List<FileTransferRunItemView> chunk = new ArrayList<FileTransferRunItemView>();
                long eventId = 0L;
                for (int index = offset; index < end; index++) {
                    Map.Entry<Long, FileTransferEventOutboxEntity> entry = changed.get(index);
                    FileTransferRunItemView item = items.get(entry.getKey());
                    if (item != null) {
                        overlayLiveProgress(item, entry.getValue());
                        chunk.add(item);
                    }
                    eventId = Math.max(eventId, entry.getValue().getId());
                }
                if (end == changed.size()) {
                    eventId = Math.max(eventId, batch.runEventId);
                }
                FileTransferQueueEventView view = event(batch.runCreated ? "RUN_CREATED" : "RUN_CHANGED",
                        batch.runId, null, eventId);
                view.setRun(run);
                view.setItems(chunk);
                outbound.add(new OutboundEvent(eventId, batch.runCreated ? "run-created" : "run-changed", view));
                offset = end;
            }
            if (changed.isEmpty() && batch.runEventId > 0L) {
                FileTransferQueueEventView view = event(batch.runCreated ? "RUN_CREATED" : "RUN_CHANGED",
                        batch.runId, null, batch.runEventId);
                view.setRun(run);
                view.setItems(new ArrayList<FileTransferRunItemView>());
                outbound.add(new OutboundEvent(batch.runEventId,
                        batch.runCreated ? "run-created" : "run-changed", view));
            }
        }
        outbound.removeIf(candidate -> candidate.eventId <= 0L);
        outbound.sort(Comparator.comparingLong(candidate -> candidate.eventId));
        return outbound;
    }

    private void overlayLiveProgress(FileTransferRunItemView item,
                                     FileTransferEventOutboxEntity event) {
        Map<String, Object> payload = event == null ? null : event.getPayloadJson();
        if (item == null || payload == null || !Boolean.TRUE.equals(payload.get("live"))) {
            return;
        }
        long confirmedBytes = number(payload.get("confirmedBytes"), value(item.getTransferredBytes()));
        long observedBytes = number(payload.get("observedBytes"), confirmedBytes);
        boolean transferring = "TRANSFERRING".equalsIgnoreCase(item.getStatus());
        boolean verifying = "VERIFYING".equalsIgnoreCase(item.getStatus())
                && "TARGET_CHECKSUM".equalsIgnoreCase(String.valueOf(payload.get("verificationPhase")));
        if ((!transferring && !verifying)
                || value(item.getTransferredBytes()) > confirmedBytes
                || observedBytes < value(item.getTransferredBytes())) {
            return;
        }
        item.setObservedBytes(observedBytes);
        item.setLive(true);
        item.setCurrentBytesPerSecond(number(payload.get("liveBytesPerSecond"),
                value(item.getCurrentBytesPerSecond())));
        Object resumePhase = payload.get("resumePhase");
        if (resumePhase != null) {
            item.setResumePhase(String.valueOf(resumePhase));
        }
        if ("REBUILDING_CHECKSUM".equalsIgnoreCase(item.getResumePhase())) {
            long activityBytes = number(payload.get("activityBytes"), confirmedBytes);
            item.setResumeCheckedBytes(Math.max(0L, activityBytes - confirmedBytes));
            item.setResumeTotalBytes(confirmedBytes);
        }
        if (verifying) {
            long totalBytes = number(payload.get("verificationTotalBytes"), value(item.getFileSize()));
            long verifiedBytes = number(payload.get("verificationBytes"), 0L);
            item.setVerificationPhase("TARGET_CHECKSUM");
            item.setVerificationBytes(Math.min(verifiedBytes, totalBytes));
            item.setVerificationTotalBytes(totalBytes);
        }
    }

    private long number(Object raw, long fallback) {
        if (raw instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(raw)));
        } catch (RuntimeException ignored) {
            return Math.max(0L, fallback);
        }
    }

    private Map<Long, FileTransferRunView> loadRunViews(ScopeKey scope, Set<Long> runIds) {
        Map<Long, FileTransferRunView> result = new LinkedHashMap<Long, FileTransferRunView>();
        if (runIds.isEmpty()) {
            return result;
        }
        List<FileTransferRunEntity> entities = runMapper.selectList(new LambdaQueryWrapper<FileTransferRunEntity>()
                .eq(FileTransferRunEntity::getTenantId, scope.tenantId)
                .eq(FileTransferRunEntity::getProjectId, scope.projectId)
                .in(FileTransferRunEntity::getId, runIds));
        for (FileTransferRunView view : runService.toRunViews(entities)) {
            result.put(view.getId(), view);
        }
        return result;
    }

    private Map<Long, FileTransferRunItemView> loadItemViews(ScopeKey scope, Set<Long> itemIds) {
        Map<Long, FileTransferRunItemView> result = new LinkedHashMap<Long, FileTransferRunItemView>();
        if (itemIds.isEmpty()) {
            return result;
        }
        List<FileTransferRunItemEntity> entities = itemMapper.selectList(
                new LambdaQueryWrapper<FileTransferRunItemEntity>()
                        .eq(FileTransferRunItemEntity::getTenantId, scope.tenantId)
                        .eq(FileTransferRunItemEntity::getProjectId, scope.projectId)
                        .in(FileTransferRunItemEntity::getId, itemIds));
        for (FileTransferRunItemView view : runService.toItemViews(entities)) {
            result.put(view.getId(), view);
        }
        return result;
    }

    private void publishOutbox(ScopeKey scope, OutboundEvent event, boolean replay) {
        CopyOnWriteArrayList<EmitterConnection> connections = connectionsByScope.get(scope);
        if (connections == null) {
            return;
        }
        for (EmitterConnection connection : connections) {
            sendOutbox(connection, event, replay);
        }
    }

    private void sendOutbox(EmitterConnection connection, OutboundEvent event, boolean replay) {
        if (event.eventId <= connection.lastSentEventId.get()) {
            return;
        }
        try {
            connection.emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.eventId))
                    .name(event.eventName)
                    .data(event.view));
            connection.lastSentEventId.set(event.eventId);
            increment(replay ? "studio.file-transfer.outbox.events-replayed"
                    : "studio.file-transfer.outbox.events-published", "result", "success", 1D);
        } catch (IOException | IllegalStateException exception) {
            increment("studio.file-transfer.outbox.send-failures", "result", "failure", 1D);
            removeConnection(connection);
        }
    }

    private void sendSnapshot(EmitterConnection connection, long eventId) {
        FileTransferQueueEventView event = event("SNAPSHOT_REQUIRED", null, null, eventId);
        try {
            connection.emitter.send(SseEmitter.event()
                    .id(String.valueOf(eventId))
                    .name("snapshot")
                    .data(event));
            connection.lastSentEventId.set(eventId);
        } catch (IOException | IllegalStateException exception) {
            removeConnection(connection);
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to open file transfer event stream");
        }
    }

    private List<FileTransferEventOutboxEntity> selectEventsAfter(ScopeKey scope, long eventId, int limit) {
        return outboxMapper.selectList(new LambdaQueryWrapper<FileTransferEventOutboxEntity>()
                .eq(FileTransferEventOutboxEntity::getTenantId, scope.tenantId)
                .eq(FileTransferEventOutboxEntity::getProjectId, scope.projectId)
                .gt(FileTransferEventOutboxEntity::getId, eventId)
                .orderByAsc(FileTransferEventOutboxEntity::getId)
                .last("limit " + Math.max(1, limit)));
    }

    private long maximumEventId(ScopeKey scope) {
        FileTransferEventOutboxEntity event = outboxMapper.selectOne(
                new LambdaQueryWrapper<FileTransferEventOutboxEntity>()
                        .select(FileTransferEventOutboxEntity::getId)
                        .eq(FileTransferEventOutboxEntity::getTenantId, scope.tenantId)
                        .eq(FileTransferEventOutboxEntity::getProjectId, scope.projectId)
                        .orderByDesc(FileTransferEventOutboxEntity::getId)
                        .last("limit 1"));
        return event == null ? 0L : value(event.getId());
    }

    private long minimumEventId(ScopeKey scope) {
        FileTransferEventOutboxEntity event = outboxMapper.selectOne(
                new LambdaQueryWrapper<FileTransferEventOutboxEntity>()
                        .select(FileTransferEventOutboxEntity::getId)
                        .eq(FileTransferEventOutboxEntity::getTenantId, scope.tenantId)
                        .eq(FileTransferEventOutboxEntity::getProjectId, scope.projectId)
                        .orderByAsc(FileTransferEventOutboxEntity::getId)
                        .last("limit 1"));
        return event == null ? 0L : value(event.getId());
    }

    private FileTransferEventConsumerCursorEntity ensureCursor(ScopeKey scope) {
        FileTransferEventConsumerCursorEntity cached = cursorsByScope.get(scope);
        if (cached != null) {
            return cached;
        }
        FileTransferEventConsumerCursorEntity existing = findCursor(scope);
        if (existing != null) {
            cursorsByScope.put(scope, existing);
            return existing;
        }
        return ensureCursor(scope, maximumEventId(scope));
    }

    private FileTransferEventConsumerCursorEntity ensureCursor(ScopeKey scope, long initialEventId) {
        FileTransferEventConsumerCursorEntity cached = cursorsByScope.get(scope);
        if (cached != null) {
            return cached;
        }
        FileTransferEventConsumerCursorEntity cursor = findCursor(scope);
        if (cursor != null) {
            cursorsByScope.put(scope, cursor);
            return cursor;
        }
        LocalDateTime now = LocalDateTime.now();
        cursor = new FileTransferEventConsumerCursorEntity();
        cursor.setInstanceId(instanceIdentity.instanceId());
        cursor.setTenantId(scope.tenantId);
        cursor.setProjectId(scope.projectId);
        cursor.setLastEventId(initialEventId);
        cursor.setLastSeenAt(now);
        cursor.setCreatedAt(now);
        cursor.setUpdatedAt(now);
        try {
            cursorMapper.insert(cursor);
            cursorsByScope.put(scope, cursor);
            return cursor;
        } catch (DuplicateKeyException exception) {
            FileTransferEventConsumerCursorEntity existing = findCursor(scope);
            if (existing == null) {
                throw exception;
            }
            cursorsByScope.put(scope, existing);
            return existing;
        }
    }

    private FileTransferEventConsumerCursorEntity findCursor(ScopeKey scope) {
        return cursorMapper.selectOne(new LambdaQueryWrapper<FileTransferEventConsumerCursorEntity>()
                .eq(FileTransferEventConsumerCursorEntity::getInstanceId, instanceIdentity.instanceId())
                .eq(FileTransferEventConsumerCursorEntity::getTenantId, scope.tenantId)
                .eq(FileTransferEventConsumerCursorEntity::getProjectId, scope.projectId)
                .last("limit 1"));
    }

    private void touchCursor(FileTransferEventConsumerCursorEntity cursor, long eventId) {
        LocalDateTime now = LocalDateTime.now();
        cursorMapper.update(null, new LambdaUpdateWrapper<FileTransferEventConsumerCursorEntity>()
                .set(FileTransferEventConsumerCursorEntity::getLastEventId, eventId)
                .set(FileTransferEventConsumerCursorEntity::getLastSeenAt, now)
                .set(FileTransferEventConsumerCursorEntity::getUpdatedAt, now)
                .eq(FileTransferEventConsumerCursorEntity::getId, cursor.getId()));
        cursor.setLastEventId(eventId);
        cursor.setLastSeenAt(now);
    }

    private void updateLag(ScopeKey scope, long eventId) {
        Long lag = outboxMapper.selectCount(new LambdaQueryWrapper<FileTransferEventOutboxEntity>()
                .eq(FileTransferEventOutboxEntity::getTenantId, scope.tenantId)
                .eq(FileTransferEventOutboxEntity::getProjectId, scope.projectId)
                .gt(FileTransferEventOutboxEntity::getId, eventId));
        setLag(scope, lag == null ? 0L : Math.max(0L, lag.longValue()));
    }

    private void setLag(ScopeKey scope, long lag) {
        cursorLagByScope.put(scope, Math.max(0L, lag));
        refreshCursorLagGauge();
    }

    private void touchConnectedScopeCursors() {
        for (ScopeKey scope : new ArrayList<ScopeKey>(connectionsByScope.keySet())) {
            synchronized (scopeLock(scope)) {
                CopyOnWriteArrayList<EmitterConnection> connections = connectionsByScope.get(scope);
                if (connections == null || connections.isEmpty()) {
                    continue;
                }
                FileTransferEventConsumerCursorEntity cursor = ensureCursor(scope);
                touchCursor(cursor, value(cursor.getLastEventId()));
            }
        }
    }

    private void refreshInstanceCursorLag() {
        if (eventMode() != FileTransferEventMode.OUTBOX || cursorMapper == null || outboxMapper == null) {
            return;
        }
        List<FileTransferEventConsumerCursorEntity> cursors = cursorMapper.selectList(
                new LambdaQueryWrapper<FileTransferEventConsumerCursorEntity>()
                        .eq(FileTransferEventConsumerCursorEntity::getInstanceId, instanceIdentity.instanceId()));
        cursorLagByScope.clear();
        for (FileTransferEventConsumerCursorEntity cursor : cursors) {
            if (cursor == null || cursor.getTenantId() == null || cursor.getProjectId() == null) {
                continue;
            }
            updateLag(new ScopeKey(cursor.getTenantId(), cursor.getProjectId()), value(cursor.getLastEventId()));
        }
        refreshCursorLagGauge();
    }

    private void publishLegacyDatabaseChanges() {
        for (Map.Entry<ScopeKey, CopyOnWriteArrayList<EmitterConnection>> entry : connectionsByScope.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                publishLegacyDatabaseChanges(entry.getKey());
            }
        }
    }

    private void publishLegacyDatabaseChanges(ScopeKey scope) {
        LegacyScopeState state = legacyStatesByScope.computeIfAbsent(scope,
                ignored -> new LegacyScopeState(LocalDateTime.now().minusSeconds(2L)));
        LocalDateTime scanStartedAt = LocalDateTime.now();
        LocalDateTime changedSince = state.lastScanAt.minusSeconds(2L);
        List<FileTransferRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<FileTransferRunEntity>()
                .eq(FileTransferRunEntity::getTenantId, scope.tenantId)
                .eq(FileTransferRunEntity::getProjectId, scope.projectId)
                .and(query -> query.in(FileTransferRunEntity::getStatus, ACTIVE_RUN_STATUSES)
                        .or().ge(FileTransferRunEntity::getUpdatedAt, changedSince))
                .orderByDesc(FileTransferRunEntity::getUpdatedAt)
                .orderByDesc(FileTransferRunEntity::getId)
                .last("limit " + RECENT_CHANGE_LIMIT));
        List<FileTransferRunItemEntity> items = itemMapper.selectList(
                new LambdaQueryWrapper<FileTransferRunItemEntity>()
                        .eq(FileTransferRunItemEntity::getTenantId, scope.tenantId)
                        .eq(FileTransferRunItemEntity::getProjectId, scope.projectId)
                        .ge(FileTransferRunItemEntity::getUpdatedAt, changedSince)
                        .orderByDesc(FileTransferRunItemEntity::getUpdatedAt)
                        .orderByDesc(FileTransferRunItemEntity::getId)
                        .last("limit " + RECENT_CHANGE_LIMIT));

        Map<Long, FileTransferRunEntity> runsById = new LinkedHashMap<Long, FileTransferRunEntity>();
        Map<Long, List<FileTransferRunItemView>> changedItemsByRun =
                new LinkedHashMap<Long, List<FileTransferRunItemView>>();
        List<FileTransferRunItemEntity> changedItemEntities = new ArrayList<FileTransferRunItemEntity>();
        for (FileTransferRunEntity run : runs) {
            if (run != null && run.getId() != null) {
                runsById.put(run.getId(), run);
            }
        }
        for (FileTransferRunItemEntity item : items) {
            if (item == null || item.getId() == null || item.getRunId() == null) {
                continue;
            }
            String fingerprint = itemFingerprint(item);
            ItemFingerprint previous = state.itemFingerprints.put(
                    item.getId(), new ItemFingerprint(item.getRunId(), fingerprint));
            if (previous != null && fingerprint.equals(previous.fingerprint)) {
                continue;
            }
            changedItemEntities.add(item);
            if (!runsById.containsKey(item.getRunId())) {
                FileTransferRunEntity run = findRun(scope, item.getRunId());
                if (run != null) {
                    runsById.put(run.getId(), run);
                }
            }
        }
        List<FileTransferRunItemView> changedItemViews = runService.toItemViews(changedItemEntities);
        for (int index = 0; index < changedItemEntities.size(); index++) {
            changedItemsByRun.computeIfAbsent(changedItemEntities.get(index).getRunId(),
                    ignored -> new ArrayList<FileTransferRunItemView>()).add(changedItemViews.get(index));
        }
        Map<Long, FileTransferRunView> runViews = new LinkedHashMap<Long, FileTransferRunView>();
        for (FileTransferRunView view : runService.toRunViews(new ArrayList<FileTransferRunEntity>(runsById.values()))) {
            runViews.put(view.getId(), view);
        }
        for (FileTransferRunEntity run : runsById.values()) {
            String fingerprint = runFingerprint(run);
            String previous = state.runFingerprints.put(run.getId(), fingerprint);
            List<FileTransferRunItemView> changedItems = changedItemsByRun.get(run.getId());
            if (fingerprint.equals(previous) && (changedItems == null || changedItems.isEmpty())) {
                continue;
            }
            FileTransferQueueEventView event = event("RUN_CHANGED", run.getId(), null, null);
            event.setRun(runViews.get(run.getId()));
            event.setItems(changedItems == null ? new ArrayList<FileTransferRunItemView>() : changedItems);
            publishLegacy(scope, "run-changed", event);
        }
        state.lastScanAt = scanStartedAt;
    }

    private FileTransferRunEntity findRun(ScopeKey scope, Long runId) {
        return runMapper.selectOne(new LambdaQueryWrapper<FileTransferRunEntity>()
                .eq(FileTransferRunEntity::getId, runId)
                .eq(FileTransferRunEntity::getTenantId, scope.tenantId)
                .eq(FileTransferRunEntity::getProjectId, scope.projectId)
                .last("limit 1"));
    }

    private void publishLegacy(ScopeKey scope, String eventName, FileTransferQueueEventView event) {
        CopyOnWriteArrayList<EmitterConnection> connections = connectionsByScope.get(scope);
        if (connections == null) {
            return;
        }
        for (EmitterConnection connection : connections) {
            try {
                connection.emitter.send(SseEmitter.event().name(eventName).data(event));
            } catch (IOException | IllegalStateException exception) {
                removeConnection(connection);
            }
        }
    }

    private void removeConnection(EmitterConnection connection) {
        boolean removedScope = false;
        synchronized (scopeLock(connection.scope)) {
            CopyOnWriteArrayList<EmitterConnection> connections = connectionsByScope.get(connection.scope);
            if (connections == null) {
                return;
            }
            connections.remove(connection);
            if (connections.isEmpty() && connectionsByScope.remove(connection.scope, connections)) {
                legacyStatesByScope.remove(connection.scope);
                cursorLagByScope.remove(connection.scope);
                removedScope = true;
            }
        }
        if (removedScope) {
            refreshCursorLagGauge();
        }
    }

    private void refreshCursorLagGauge() {
        long total = 0L;
        for (Long value : cursorLagByScope.values()) {
            if (value != null && value.longValue() > 0L) {
                total = Long.MAX_VALUE - total < value.longValue()
                        ? Long.MAX_VALUE : total + value.longValue();
            }
        }
        cursorLag.set(total);
    }

    private Object scopeLock(ScopeKey scope) {
        return scopeLocks[(scope.hashCode() & Integer.MAX_VALUE) % scopeLocks.length];
    }

    private static Object[] createScopeLocks() {
        Object[] locks = new Object[SCOPE_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private ScopeKey currentScope() {
        return new ScopeKey(securityService.currentTenantId(),
                projectResourceAccessService.requireCurrentProjectId());
    }

    private FileTransferQueueEventView event(String type, Long runId, Long itemId, Long eventId) {
        return event(type, runId, itemId, eventId, LocalDateTime.now());
    }

    private FileTransferQueueEventView event(String type, Long runId, Long itemId,
                                             Long eventId, LocalDateTime occurredAt) {
        FileTransferQueueEventView event = new FileTransferQueueEventView();
        event.setType(type);
        event.setEventId(eventId);
        event.setRunId(runId);
        event.setItemId(itemId);
        event.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        return event;
    }

    private FileTransferEventMode eventMode() {
        FileTransferEventMode mode = properties.getFileTransfer().getEventMode();
        return mode == null ? FileTransferEventMode.OUTBOX : mode;
    }

    private int eventBatchSize() {
        Integer configured = properties.getFileTransfer().getEventBatchSize();
        return configured == null ? 500 : Math.max(1, configured);
    }

    private int replayMaxEvents() {
        Integer configured = properties.getFileTransfer().getReplayMaxEvents();
        return configured == null ? 5_000 : Math.max(1, configured);
    }

    private long parseEventId(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private long value(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private String runFingerprint(FileTransferRunEntity run) {
        return String.join("|",
                text(run.getStatus()), text(run.getRunRecordId()), text(run.getTotalFiles()),
                text(run.getSuccessFiles()), text(run.getSkippedFiles()), text(run.getFailedFiles()),
                text(run.getConflictFiles()), text(run.getPostActionFailedFiles()), text(run.getTotalBytes()),
                text(run.getTransferredBytes()), text(run.getFailedBytes()), text(run.getCurrentBytesPerSecond()),
                text(run.getPeakBytesPerSecond()), text(run.getActiveFiles()), text(run.getRetryCount()),
                text(run.getMessage()), text(run.getStartedAt()), text(run.getEndedAt()), text(run.getUpdatedAt()));
    }

    private String itemFingerprint(FileTransferRunItemEntity item) {
        return String.join("|",
                text(item.getRunId()), text(item.getStatus()), text(item.getTransferredBytes()),
                text(item.getResumedBytes()), text(item.getCurrentBytesPerSecond()), text(item.getAttempts()),
                text(item.getSourceChecksum()), text(item.getTargetChecksum()), text(item.getErrorCode()),
                text(item.getErrorMessage()), text(item.getPostActionStatus()), text(item.getStartedAt()),
                text(item.getEndedAt()), text(item.getUpdatedAt()));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void increment(String name, String tagName, String tagValue, double amount) {
        if (meterRegistry != null) {
            Counter.builder(name).tag(tagName, tagValue).register(meterRegistry).increment(amount);
        }
    }

    public FileTransferOutboxStatus status() {
        return new FileTransferOutboxStatus(eventMode(), lastConsumedAt, lastPollError, cursorLag.get());
    }

    @PreDestroy
    public void completeEmitters() {
        for (CopyOnWriteArrayList<EmitterConnection> connections : connectionsByScope.values()) {
            for (EmitterConnection connection : connections) {
                connection.emitter.complete();
            }
        }
        connectionsByScope.clear();
        legacyStatesByScope.clear();
        cursorsByScope.clear();
        cursorLagByScope.clear();
        cursorLag.set(0L);
    }

    private static StudioPlatformProperties legacyProperties() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getFileTransfer().setEventMode(FileTransferEventMode.LEGACY_SCAN);
        properties.setInstanceId("file-transfer-event-test");
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> emptyMeterRegistryProvider() {
        return new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);
    }

    public record FileTransferOutboxStatus(FileTransferEventMode mode, LocalDateTime lastConsumedAt,
                                           String lastError, long cursorLag) {
    }

    private static final class EmitterConnection {
        private final SseEmitter emitter;
        private final ScopeKey scope;
        private final AtomicLong lastSentEventId;
        private final LocalDateTime connectedAt = LocalDateTime.now();
        private volatile LocalDateTime lastHeartbeatAt;

        private EmitterConnection(SseEmitter emitter, ScopeKey scope, long lastSentEventId) {
            this.emitter = emitter;
            this.scope = scope;
            this.lastSentEventId = new AtomicLong(lastSentEventId);
        }
    }

    private static final class RunBatch {
        private final Long runId;
        private final Map<Long, FileTransferEventOutboxEntity> changedItems =
                new LinkedHashMap<Long, FileTransferEventOutboxEntity>();
        private final Map<Long, FileTransferEventOutboxEntity> removedItems =
                new LinkedHashMap<Long, FileTransferEventOutboxEntity>();
        private FileTransferEventOutboxEntity runRemoved;
        private long runEventId;
        private boolean runCreated;

        private RunBatch(Long runId) {
            this.runId = runId;
        }

        private void accept(FileTransferEventOutboxEntity event) {
            FileTransferOutboxEventType type;
            try {
                type = FileTransferOutboxEventType.valueOf(event.getEventType());
            } catch (RuntimeException exception) {
                return;
            }
            if (type == FileTransferOutboxEventType.RUN_REMOVED) {
                runRemoved = event;
                return;
            }
            if (runRemoved != null && event.getId() < runRemoved.getId()) {
                return;
            }
            if (type == FileTransferOutboxEventType.ITEM_REMOVED && event.getItemId() != null) {
                changedItems.remove(event.getItemId());
                removedItems.put(event.getItemId(), event);
            } else if (type == FileTransferOutboxEventType.ITEM_CHANGED && event.getItemId() != null) {
                removedItems.remove(event.getItemId());
                changedItems.put(event.getItemId(), event);
            } else if (type == FileTransferOutboxEventType.RUN_CREATED
                    || type == FileTransferOutboxEventType.RUN_CHANGED) {
                runEventId = Math.max(runEventId, event.getId());
                runCreated = runCreated || type == FileTransferOutboxEventType.RUN_CREATED;
            }
        }
    }

    private static final class OutboundEvent {
        private final long eventId;
        private final String eventName;
        private final FileTransferQueueEventView view;

        private OutboundEvent(long eventId, String eventName, FileTransferQueueEventView view) {
            this.eventId = eventId;
            this.eventName = eventName;
            this.view = view;
        }
    }

    private static final class LegacyScopeState {
        private volatile LocalDateTime lastScanAt;
        private final Map<Long, String> runFingerprints = new ConcurrentHashMap<Long, String>();
        private final Map<Long, ItemFingerprint> itemFingerprints =
                new ConcurrentHashMap<Long, ItemFingerprint>();

        private LegacyScopeState(LocalDateTime lastScanAt) {
            this.lastScanAt = lastScanAt;
        }
    }

    private static final class ItemFingerprint {
        private final Long runId;
        private final String fingerprint;

        private ItemFingerprint(Long runId, String fingerprint) {
            this.runId = runId;
            this.fingerprint = fingerprint;
        }
    }

    private static final class ScopeKey {
        private final String tenantId;
        private final Long projectId;

        private ScopeKey(String tenantId, Long projectId) {
            this.tenantId = tenantId;
            this.projectId = projectId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ScopeKey)) {
                return false;
            }
            ScopeKey that = (ScopeKey) other;
            return Objects.equals(tenantId, that.tenantId) && Objects.equals(projectId, that.projectId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, projectId);
        }
    }
}
