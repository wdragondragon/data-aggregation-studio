package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.FileTransferQueueEventView;
import com.jdragon.studio.dto.model.FileTransferRunItemView;
import com.jdragon.studio.dto.model.FileTransferRunView;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class FileTransferEventService {

    private static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final int RECENT_CHANGE_LIMIT = 2_000;
    private static final List<String> ACTIVE_RUN_STATUSES = List.of("QUEUED", "RUNNING", "PAUSED");

    private final FileTransferRunMapper runMapper;
    private final FileTransferRunItemMapper itemMapper;
    private final FileTransferRunService runService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final Map<ScopeKey, CopyOnWriteArrayList<SseEmitter>> emittersByScope =
            new ConcurrentHashMap<ScopeKey, CopyOnWriteArrayList<SseEmitter>>();
    private final Map<ScopeKey, ScopeState> statesByScope =
            new ConcurrentHashMap<ScopeKey, ScopeState>();

    public FileTransferEventService(FileTransferRunMapper runMapper,
                                    FileTransferRunItemMapper itemMapper,
                                    FileTransferRunService runService,
                                    StudioSecurityService securityService,
                                    ProjectResourceAccessService projectResourceAccessService) {
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.runService = runService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public SseEmitter connect() {
        ScopeKey scope = currentScope();
        SseEmitter emitter = createEmitter();
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByScope.computeIfAbsent(
                scope, ignored -> new CopyOnWriteArrayList<SseEmitter>());
        emitters.add(emitter);
        statesByScope.computeIfAbsent(scope,
                ignored -> new ScopeState(LocalDateTime.now().minusSeconds(2L)));
        emitter.onCompletion(() -> removeEmitter(scope, emitter));
        emitter.onTimeout(() -> removeEmitter(scope, emitter));
        emitter.onError(throwable -> removeEmitter(scope, emitter));
        FileTransferQueueEventView event = event("SNAPSHOT_REQUIRED", null, null);
        send(emitter, "snapshot", event);
        return emitter;
    }

    SseEmitter createEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MILLIS);
    }

    public void publishRunChanged(Long runId) {
        if (runId == null) {
            return;
        }
        ScopeKey scope = currentScope();
        FileTransferRunEntity run = findRun(scope, runId);
        if (run == null) {
            publishRunRemoved(runId);
            return;
        }
        FileTransferQueueEventView event = event("RUN_CHANGED", runId, null);
        event.setRun(runService.toRunView(run));
        publish(scope, "run-changed", event);
        statesByScope.computeIfAbsent(scope,
                ignored -> new ScopeState(LocalDateTime.now().minusSeconds(2L)))
                .runFingerprints.put(runId, runFingerprint(run));
    }

    public void publishRunRemoved(Long runId) {
        if (runId == null) {
            return;
        }
        ScopeKey scope = currentScope();
        FileTransferQueueEventView event = event("RUN_REMOVED", runId, null);
        publish(scope, "run-removed", event);
        ScopeState state = statesByScope.get(scope);
        if (state != null) {
            state.runFingerprints.remove(runId);
            state.itemFingerprints.entrySet().removeIf(entry -> Objects.equals(entry.getValue().runId, runId));
        }
    }

    public void publishItemRemoved(Long runId, Long itemId) {
        if (runId == null || itemId == null) {
            return;
        }
        ScopeKey scope = currentScope();
        FileTransferQueueEventView event = event("ITEM_REMOVED", runId, itemId);
        FileTransferRunEntity run = findRun(scope, runId);
        if (run != null) {
            event.setRun(runService.toRunView(run));
        }
        publish(scope, "item-removed", event);
        ScopeState state = statesByScope.get(scope);
        if (state != null) {
            state.itemFingerprints.remove(itemId);
        }
    }

    @Scheduled(fixedDelay = 1_000L)
    public void publishDatabaseChanges() {
        if (emittersByScope.isEmpty()) {
            return;
        }
        for (Map.Entry<ScopeKey, CopyOnWriteArrayList<SseEmitter>> entry : emittersByScope.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            publishDatabaseChanges(entry.getKey());
        }
    }

    @Scheduled(fixedDelay = 15_000L)
    public void heartbeat() {
        for (Map.Entry<ScopeKey, CopyOnWriteArrayList<SseEmitter>> entry : emittersByScope.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("{}"));
                } catch (IOException | IllegalStateException exception) {
                    removeEmitter(entry.getKey(), emitter);
                }
            }
        }
    }

    private void publishDatabaseChanges(ScopeKey scope) {
        ScopeState state = statesByScope.computeIfAbsent(scope,
                ignored -> new ScopeState(LocalDateTime.now().minusSeconds(2L)));
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
            if (run == null || run.getId() == null) {
                continue;
            }
            runsById.put(run.getId(), run);
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
            FileTransferRunItemEntity item = changedItemEntities.get(index);
            changedItemsByRun.computeIfAbsent(item.getRunId(),
                    ignored -> new ArrayList<FileTransferRunItemView>()).add(changedItemViews.get(index));
        }

        Map<Long, FileTransferRunView> runViewsById = new LinkedHashMap<Long, FileTransferRunView>();
        for (FileTransferRunView view : runService.toRunViews(new ArrayList<FileTransferRunEntity>(runsById.values()))) {
            runViewsById.put(view.getId(), view);
        }

        for (FileTransferRunEntity run : runsById.values()) {
            String fingerprint = runFingerprint(run);
            String previous = state.runFingerprints.put(run.getId(), fingerprint);
            List<FileTransferRunItemView> changedItems = changedItemsByRun.get(run.getId());
            if (fingerprint.equals(previous) && (changedItems == null || changedItems.isEmpty())) {
                continue;
            }
            FileTransferQueueEventView event = event("RUN_CHANGED", run.getId(), null);
            event.setRun(runViewsById.get(run.getId()));
            event.setItems(changedItems == null ? new ArrayList<FileTransferRunItemView>() : changedItems);
            publish(scope, "run-changed", event);
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

    private void publish(ScopeKey scope, String eventName, FileTransferQueueEventView event) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByScope.get(scope);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(event));
            } catch (IOException | IllegalStateException exception) {
                removeEmitter(scope, emitter);
            }
        }
    }

    private void send(SseEmitter emitter, String eventName, FileTransferQueueEventView event) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(event));
        } catch (IOException | IllegalStateException exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to open file transfer event stream");
        }
    }

    private void removeEmitter(ScopeKey scope, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByScope.get(scope);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByScope.remove(scope);
            statesByScope.remove(scope);
        }
    }

    private ScopeKey currentScope() {
        return new ScopeKey(securityService.currentTenantId(),
                projectResourceAccessService.requireCurrentProjectId());
    }

    private FileTransferQueueEventView event(String type, Long runId, Long itemId) {
        FileTransferQueueEventView event = new FileTransferQueueEventView();
        event.setType(type);
        event.setRunId(runId);
        event.setItemId(itemId);
        event.setOccurredAt(LocalDateTime.now());
        return event;
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

    @PreDestroy
    public void completeEmitters() {
        for (CopyOnWriteArrayList<SseEmitter> emitters : emittersByScope.values()) {
            for (SseEmitter emitter : emitters) {
                emitter.complete();
            }
        }
        emittersByScope.clear();
        statesByScope.clear();
    }

    private static final class ScopeState {
        private volatile LocalDateTime lastScanAt;
        private final Map<Long, String> runFingerprints = new ConcurrentHashMap<Long, String>();
        private final Map<Long, ItemFingerprint> itemFingerprints =
                new ConcurrentHashMap<Long, ItemFingerprint>();

        private ScopeState(LocalDateTime lastScanAt) {
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
