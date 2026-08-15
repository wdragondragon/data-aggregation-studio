package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import com.jdragon.studio.dto.model.FileTransferRunItemView;
import com.jdragon.studio.dto.model.FileTransferRunView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.FileTransferManualItemRequest;
import com.jdragon.studio.dto.model.request.FileTransferManualRunRequest;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.entity.FileTransferTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class FileTransferRunService {

    private static final List<String> ACTIVE_STATUSES = List.of("QUEUED", "RUNNING", "PAUSED");
    private static final List<String> TERMINAL_STATUSES = List.of(
            "SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELED");
    private static final List<String> ACTIVE_ITEM_STATUSES = List.of(
            "QUEUED", "DISCOVERING", "TRANSFERRING", "VERIFYING", "COMMITTING", "POST_ACTION", "PAUSED");
    private static final List<String> BUSY_ITEM_STATUSES = List.of(
            "DISCOVERING", "TRANSFERRING", "VERIFYING", "COMMITTING", "POST_ACTION", "PAUSED");

    private final FileTransferRunMapper runMapper;
    private final FileTransferRunItemMapper itemMapper;
    private final DispatchTaskMapper dispatchTaskMapper;
    private final FileTransferTaskService taskService;
    private final DataSourceService dataSourceService;
    private final RuntimeClusterSelectionService runtimeClusterSelectionService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final StudioSecurityService securityService;
    private final UnstructuredManagementService unstructuredManagementService;
    private final ClusterLockService clusterLockService;
    private final ObjectMapper objectMapper;
    private final FileTransferStateMutationService mutationService;

    @Autowired
    public FileTransferRunService(FileTransferRunMapper runMapper,
                                  FileTransferRunItemMapper itemMapper,
                                  DispatchTaskMapper dispatchTaskMapper,
                                  FileTransferTaskService taskService,
                                  DataSourceService dataSourceService,
                                  RuntimeClusterSelectionService runtimeClusterSelectionService,
                                  ProjectResourceAccessService projectResourceAccessService,
                                  StudioSecurityService securityService,
                                  UnstructuredManagementService unstructuredManagementService,
                                  ClusterLockService clusterLockService,
                                  ObjectMapper objectMapper,
                                  FileTransferStateMutationService mutationService) {
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.taskService = taskService;
        this.dataSourceService = dataSourceService;
        this.runtimeClusterSelectionService = runtimeClusterSelectionService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.securityService = securityService;
        this.unstructuredManagementService = unstructuredManagementService;
        this.clusterLockService = clusterLockService;
        this.objectMapper = objectMapper;
        this.mutationService = mutationService;
    }

    FileTransferRunService(FileTransferRunMapper runMapper,
                           FileTransferRunItemMapper itemMapper,
                           DispatchTaskMapper dispatchTaskMapper,
                           FileTransferTaskService taskService,
                           DataSourceService dataSourceService,
                           RuntimeClusterSelectionService runtimeClusterSelectionService,
                           ProjectResourceAccessService projectResourceAccessService,
                           StudioSecurityService securityService,
                           UnstructuredManagementService unstructuredManagementService,
                           ClusterLockService clusterLockService,
                           ObjectMapper objectMapper) {
        this(runMapper, itemMapper, dispatchTaskMapper, taskService, dataSourceService,
                runtimeClusterSelectionService, projectResourceAccessService, securityService,
                unstructuredManagementService, clusterLockService, objectMapper, null);
    }

    @Transactional
    public FileTransferRunView createManualRun(FileTransferManualRunRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw bad("At least one transfer item is required");
        }
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        request.setPolicy(FileTransferPolicyNormalizer.normalize(request.getPolicy()));
        Long runtimeClusterId = validateManualItems(projectId, request.getRuntimeClusterId(), request.getItems());
        request.setRuntimeClusterId(runtimeClusterId);
        FileTransferRunEntity run = newRun(projectId, "MANUAL", runtimeClusterId);
        run.setRuntimeClusterId(runtimeClusterId);
        run.setSourceRuntimeClusterId(runtimeClusterId);
        run.setSourceDatasourceId(commonSourceDatasource(request.getItems()));
        run.setTargetRuntimeClusterId(runtimeClusterId);
        run.setTargetDatasourceId(commonTargetDatasource(request.getItems()));
        run.setChannel("LOCAL_WORKER");
        run.setResolvedSpecJson(manualSnapshot(request));
        insertRun(run);
        if (!Boolean.FALSE.equals(request.getAutoStart())) {
            insertDispatch(run, null, null);
        }
        return toRunView(run);
    }

    @Transactional
    public FileTransferRunView addManualItems(Long runId, List<FileTransferManualItemRequest> items) {
        FileTransferRunEntity run = requireWritableRun(runId);
        if (!"MANUAL".equalsIgnoreCase(run.getTriggerType())) {
            throw bad("Only manual file transfer runs accept additional items");
        }
        if (!"QUEUED".equalsIgnoreCase(run.getStatus())) {
            throw bad("Items can only be added before a file transfer run starts");
        }
        Long runtimeClusterId = validateManualItems(run.getProjectId(), run.getRuntimeClusterId(), items);
        if (!Objects.equals(runtimeClusterId, runRuntimeClusterId(run))) {
            throw bad("All items in one run must use the same runtime cluster");
        }
        Map<String, Object> snapshot = copy(run.getResolvedSpecJson());
        List<Map<String, Object>> current = maps(snapshot.get("manualItems"));
        for (FileTransferManualItemRequest item : items) {
            current.add(objectMapper.convertValue(item, new TypeReference<LinkedHashMap<String, Object>>() { }));
        }
        snapshot.put("manualItems", current);
        run.setResolvedSpecJson(snapshot);
        updateRun(run.getId(), new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getResolvedSpecJson, snapshot,
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                .eq(FileTransferRunEntity::getId, run.getId()));
        ensureDispatch(run, null, null);
        return toRunView(run);
    }

    @Transactional
    public FileTransferRunView triggerTask(Long taskId, String triggerType, LocalDateTime scheduledFireTime) {
        return clusterLockService.executeIfAcquiredNonReentrant(
                "file-transfer-task-run:" + taskId, 30L, true,
                () -> triggerTaskLocked(taskId, triggerType, scheduledFireTime),
                () -> {
                    throw bad("File transfer task trigger is already in progress");
                });
    }

    private FileTransferRunView triggerTaskLocked(Long taskId, String triggerType,
                                                  LocalDateTime scheduledFireTime) {
        FileTransferTaskDefinitionEntity task = taskService.requireOnlineForExecution(taskId);
        Long active = runMapper.selectCount(new LambdaQueryWrapper<FileTransferRunEntity>()
                .eq(FileTransferRunEntity::getTenantId, task.getTenantId())
                .eq(FileTransferRunEntity::getProjectId, task.getProjectId())
                .eq(FileTransferRunEntity::getTaskId, task.getId())
                .in(FileTransferRunEntity::getStatus, ACTIVE_STATUSES));
        if (active != null && active.longValue() > 0L) {
            throw bad("File transfer task already has an active run");
        }
        Long runtimeClusterId = taskRuntimeClusterId(task);
        FileTransferRunEntity run = newRun(task.getProjectId(),
                triggerType == null ? "MANUAL_TASK" : triggerType, runtimeClusterId);
        run.setTaskId(task.getId());
        run.setTaskNameSnapshot(task.getName());
        run.setRuntimeClusterId(runtimeClusterId);
        run.setSourceRuntimeClusterId(runtimeClusterId);
        run.setSourceDatasourceId(task.getSourceDatasourceId());
        run.setTargetRuntimeClusterId(runtimeClusterId);
        run.setTargetDatasourceId(task.getTargetDatasourceId());
        run.setChannel("LOCAL_WORKER");
        Map<String, Object> snapshot = taskService.publishedSnapshot(taskId);
        if (scheduledFireTime != null) {
            snapshot.put("scheduledFireTime", scheduledFireTime.toString());
        }
        run.setResolvedSpecJson(snapshot);
        insertRun(run);
        insertDispatch(run, task, scheduledFireTime);
        return toRunView(run);
    }

    /** Creates the file-transfer run owned by a workflow dispatch before that dispatch is queued. */
    @Transactional
    public FileTransferRunEntity createWorkflowRunSkeleton(Long taskId, Long projectId,
                                                            Long targetClusterId,
                                                            String triggerType,
                                                            String expectedResourceRevision) {
        FileTransferTaskDefinitionEntity task = taskService.requireOnlineForExecution(taskId);
        String currentResourceRevision = "file-transfer:" + task.getId() + ":" + task.getPublishedVersion();
        if (expectedResourceRevision != null && !expectedResourceRevision.trim().isEmpty()
                && !expectedResourceRevision.equals(currentResourceRevision)) {
            throw bad("Workflow file transfer task changed after the workflow was published");
        }
        Long runtimeClusterId = taskRuntimeClusterId(task);
        if (!Objects.equals(task.getProjectId(), projectId)
                || !Objects.equals(runtimeClusterId, targetClusterId)) {
            throw bad("Workflow file transfer task placement does not match the workflow runtime cluster");
        }
        FileTransferRunEntity run = newRun(projectId,
                triggerType == null ? "WORKFLOW" : triggerType, targetClusterId);
        run.setTaskId(task.getId());
        run.setTaskNameSnapshot(task.getName());
        run.setRuntimeClusterId(runtimeClusterId);
        run.setSourceRuntimeClusterId(runtimeClusterId);
        run.setSourceDatasourceId(task.getSourceDatasourceId());
        run.setTargetRuntimeClusterId(runtimeClusterId);
        run.setTargetDatasourceId(task.getTargetDatasourceId());
        run.setChannel("LOCAL_WORKER");
        run.setResolvedSpecJson(taskService.publishedSnapshot(taskId));
        insertRun(run);
        return run;
    }

    public PageView<FileTransferRunView> listPage(Integer pageNo, Integer pageSize, Long taskId,
                                                   String status, String triggerType, String statusGroup) {
        return listPage(pageNo, pageSize, taskId, status, triggerType, statusGroup, null);
    }

    public PageView<FileTransferRunView> listPage(Integer pageNo, Integer pageSize, Long taskId,
                                                   String status, String triggerType, String statusGroup,
                                                   Boolean queueOnly) {
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null ? 20 : Math.max(1, Math.min(200, pageSize));
        LambdaQueryWrapper<FileTransferRunEntity> query = new LambdaQueryWrapper<FileTransferRunEntity>()
                .eq(FileTransferRunEntity::getTenantId, securityService.currentTenantId());
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        query.eq(FileTransferRunEntity::getProjectId, projectId);
        if (Boolean.TRUE.equals(queueOnly)) {
            query.eq(FileTransferRunEntity::getQueueVisible, true);
        }
        if (taskId != null) {
            query.eq(FileTransferRunEntity::getTaskId, taskId);
        }
        if (status != null && !status.trim().isEmpty()) {
            query.eq(FileTransferRunEntity::getStatus, status.trim().toUpperCase());
        }
        if (statusGroup != null && !statusGroup.trim().isEmpty()) {
            String normalizedStatusGroup = statusGroup.trim().toUpperCase();
            if ("ACTIVE".equals(normalizedStatusGroup)) {
                query.in(FileTransferRunEntity::getStatus, ACTIVE_STATUSES);
            } else if ("TERMINAL".equals(normalizedStatusGroup)) {
                query.in(FileTransferRunEntity::getStatus, TERMINAL_STATUSES);
            } else {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "File transfer run statusGroup must be ACTIVE or TERMINAL");
            }
        }
        if (triggerType != null && !triggerType.trim().isEmpty()) {
            query.eq(FileTransferRunEntity::getTriggerType, triggerType.trim().toUpperCase());
        }
        query.orderByDesc(FileTransferRunEntity::getCreatedAt);
        Page<FileTransferRunEntity> page = runMapper.selectPage(
                new Page<FileTransferRunEntity>(safePageNo, safePageSize), query);
        List<FileTransferRunView> items = toRunViews(page.getRecords());
        return PageView.of(safePageNo, safePageSize, page.getTotal(), items);
    }

    public FileTransferRunView get(Long runId) {
        FileTransferRunEntity run = requireReadableRun(runId);
        return toRunView(run);
    }

    public PageView<FileTransferRunItemView> listItems(Long runId, Integer pageNo, Integer pageSize) {
        FileTransferRunEntity run = requireReadableRun(runId);
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null ? 100 : Math.max(1, Math.min(1000, pageSize));
        Page<FileTransferRunItemEntity> page = itemMapper.selectPage(
                new Page<FileTransferRunItemEntity>(safePageNo, safePageSize),
                new LambdaQueryWrapper<FileTransferRunItemEntity>()
                        .eq(FileTransferRunItemEntity::getTenantId, run.getTenantId())
                        .eq(FileTransferRunItemEntity::getProjectId, run.getProjectId())
                        .eq(FileTransferRunItemEntity::getRunId, run.getId())
                        .orderByAsc(FileTransferRunItemEntity::getCreatedAt));
        List<FileTransferRunItemView> items = toItemViews(page.getRecords());
        return PageView.of(safePageNo, safePageSize, page.getTotal(), items);
    }

    @Transactional
    public FileTransferRunView pause(Long runId) {
        FileTransferRunEntity run = requireWritableRun(runId);
        if (!"RUNNING".equalsIgnoreCase(run.getStatus()) && !"QUEUED".equalsIgnoreCase(run.getStatus())) {
            throw bad("Only queued or running file transfer runs can be paused");
        }
        run.setStatus("PAUSED");
        run.setMessage("Pause requested");
        run.setCurrentBytesPerSecond(0L);
        run.setActiveFiles(0);
        mutationService().pauseRunAndItemsAndEvent(run, new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getStatus, run.getStatus())
                .set(FileTransferRunEntity::getMessage, run.getMessage())
                .set(FileTransferRunEntity::getCurrentBytesPerSecond, 0L)
                .set(FileTransferRunEntity::getActiveFiles, 0)
                .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunEntity::getId, run.getId())
                .in(FileTransferRunEntity::getStatus, "RUNNING", "QUEUED"),
                new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                        .set(FileTransferRunItemEntity::getStatus, "PAUSED")
                        .set(FileTransferRunItemEntity::getCurrentBytesPerSecond, 0L)
                        .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                        .eq(FileTransferRunItemEntity::getTenantId, run.getTenantId())
                        .eq(FileTransferRunItemEntity::getProjectId, run.getProjectId())
                        .eq(FileTransferRunItemEntity::getRunId, run.getId())
                        .in(FileTransferRunItemEntity::getStatus,
                                "QUEUED", "DISCOVERING", "TRANSFERRING", "VERIFYING",
                                "COMMITTING", "POST_ACTION", "PAUSED"));
        return toRunView(run);
    }

    public FileTransferRunView resume(Long runId) {
        return clusterLockService.executeIfAcquiredNonReentrant(
                "file-transfer-run-resume:" + runId, 30L, true,
                () -> resumeLocked(runId),
                () -> {
                    throw bad("File transfer run resume is already in progress");
                });
    }

    private FileTransferRunView resumeLocked(Long runId) {
        FileTransferRunEntity run = requireWritableRun(runId);
        assertSingleClusterExecutable(run);
        if (!"PAUSED".equalsIgnoreCase(run.getStatus())
                && !"FAILED".equalsIgnoreCase(run.getStatus())
                && !"QUEUED".equalsIgnoreCase(run.getStatus())) {
            throw bad("Only paused, failed or queued file transfer runs can be resumed");
        }
        DispatchTaskEntity recoveryDispatch = newDispatch(run, null, null);
        String resumedStatus = mutationService().resumeRunAndEnsureDispatchAndEvent(run, recoveryDispatch);
        if (resumedStatus == null) {
            FileTransferRunEntity latest = requireWritableRun(runId);
            if (!"RUNNING".equalsIgnoreCase(latest.getStatus())
                    && !"QUEUED".equalsIgnoreCase(latest.getStatus())) {
                throw bad("File transfer run changed while resume was being requested");
            }
            run = latest;
        } else {
            run = requireWritableRun(runId);
        }
        return toRunView(run);
    }

    @Transactional
    public FileTransferRunView cancel(Long runId) {
        FileTransferRunEntity run = requireWritableRun(runId);
        if (terminal(run.getStatus())) {
            if ("CANCELED".equalsIgnoreCase(run.getStatus())) {
                cancelActiveItems(run, LocalDateTime.now());
            }
            return toRunView(run);
        }
        LocalDateTime canceledAt = LocalDateTime.now();
        run.setStatus("CANCELED");
        run.setMessage("Cancel requested");
        run.setActiveFiles(0);
        run.setCurrentBytesPerSecond(0L);
        run.setEndedAt(canceledAt);
        updateRun(run.getId(), new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getStatus, run.getStatus())
                .set(FileTransferRunEntity::getMessage, run.getMessage())
                .set(FileTransferRunEntity::getActiveFiles, 0)
                .set(FileTransferRunEntity::getCurrentBytesPerSecond, 0L)
                .set(FileTransferRunEntity::getEndedAt, canceledAt)
                .eq(FileTransferRunEntity::getId, run.getId()));
        dispatchTaskMapper.update(null, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .set(DispatchTaskEntity::getStatus, "CANCELED")
                .eq(DispatchTaskEntity::getFileTransferRunId, runId)
                .eq(DispatchTaskEntity::getStatus, "QUEUED"));
        cancelActiveItems(run, canceledAt);
        return toRunView(run);
    }

    @Transactional
    public FileTransferRunView retryItem(Long runId, Long itemId) {
        FileTransferRunEntity run = requireWritableRun(runId);
        assertSingleClusterExecutable(run);
        FileTransferRunItemEntity item = itemMapper.selectOne(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                .eq(FileTransferRunItemEntity::getId, itemId)
                .eq(FileTransferRunItemEntity::getRunId, runId)
                .eq(FileTransferRunItemEntity::getTenantId, run.getTenantId())
                .eq(FileTransferRunItemEntity::getProjectId, run.getProjectId())
                .last("limit 1"));
        if (item == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "File transfer item not found: " + itemId);
        }
        if (!List.of("FAILED", "CONFLICT", "POST_ACTION_FAILED", "CANCELED").contains(item.getStatus())) {
            throw bad("Only failed, conflicted, canceled or post-action-failed items can be retried");
        }
        boolean postActionOnly = "POST_ACTION_FAILED".equalsIgnoreCase(item.getStatus());
        item.setStatus("QUEUED");
        item.setErrorCode(null);
        item.setErrorMessage(null);
        updateItem(run.getId(), item.getCoreItemId(), new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getStatus, item.getStatus())
                .set(FileTransferRunItemEntity::getErrorCode, null)
                .set(FileTransferRunItemEntity::getErrorMessage, null)
                .eq(FileTransferRunItemEntity::getId, item.getId()), false, true);
        run.setStatus("QUEUED");
        run.setQueueVisible(true);
        run.setEndedAt(null);
        run.setMessage("Item retry requested");
        Map<String, Object> snapshot = copy(run.getResolvedSpecJson());
        snapshot.put("retryCoreItemId", item.getCoreItemId());
        snapshot.put("retryMode", postActionOnly ? "POST_ACTION_ONLY" : "TRANSFER");
        run.setResolvedSpecJson(snapshot);
        updateRun(run.getId(), new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getStatus, run.getStatus())
                .set(FileTransferRunEntity::getQueueVisible, true)
                .set(FileTransferRunEntity::getEndedAt, null)
                .set(FileTransferRunEntity::getMessage, run.getMessage())
                .set(FileTransferRunEntity::getResolvedSpecJson, snapshot,
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                .eq(FileTransferRunEntity::getId, run.getId()));
        ensureDispatch(run, null, null);
        return toRunView(run);
    }

    @Transactional
    public void removeManualRun(Long runId) {
        FileTransferRunEntity run = requireWritableRun(runId);
        assertManualRun(run);
        String status = normalizeStatus(run.getStatus());
        if ("RUNNING".equals(status) || "PAUSED".equals(status)) {
            throw bad("Cancel the active file transfer run before removing it");
        }
        if ("QUEUED".equals(status)) {
            cancelQueuedDispatches(run);
            if (hasClaimedDispatch(run)) {
                throw bad("Cancel the active file transfer run before removing it");
            }
            run.setStatus("CANCELED");
            run.setMessage("Removed from transfer queue");
            run.setEndedAt(LocalDateTime.now());
            updateRun(run.getId(), new LambdaUpdateWrapper<FileTransferRunEntity>()
                    .set(FileTransferRunEntity::getStatus, run.getStatus())
                    .set(FileTransferRunEntity::getMessage, run.getMessage())
                    .set(FileTransferRunEntity::getEndedAt, run.getEndedAt())
                    .eq(FileTransferRunEntity::getId, run.getId()));
        }
        if (hasClaimedDispatch(run)) {
            throw bad("Cancel the active file transfer run before removing it");
        }
        mutationService().removeRunAndEvent(run);
    }

    @Transactional
    public void dismissManualRunFromQueue(Long runId) {
        FileTransferRunEntity run = requireWritableRun(runId);
        assertManualRun(run);
        String status = normalizeStatus(run.getStatus());
        if ("RUNNING".equals(status) || "PAUSED".equals(status)) {
            throw bad("Cancel the active file transfer run before removing it from the queue");
        }
        if ("QUEUED".equals(status)) {
            cancelQueuedDispatches(run);
            if (hasClaimedDispatch(run)) {
                throw bad("Cancel the active file transfer run before removing it from the queue");
            }
            run.setStatus("CANCELED");
            run.setMessage("Removed from transfer queue");
            run.setEndedAt(LocalDateTime.now());
        }
        if (hasClaimedDispatch(run)) {
            throw bad("Cancel the active file transfer run before removing it from the queue");
        }
        run.setQueueVisible(false);
        LambdaUpdateWrapper<FileTransferRunEntity> update = new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getQueueVisible, false)
                .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunEntity::getTenantId, run.getTenantId())
                .eq(FileTransferRunEntity::getProjectId, run.getProjectId())
                .eq(FileTransferRunEntity::getId, run.getId())
                .eq(FileTransferRunEntity::getQueueVisible, true);
        if ("QUEUED".equals(status)) {
            update.set(FileTransferRunEntity::getStatus, run.getStatus())
                    .set(FileTransferRunEntity::getMessage, run.getMessage())
                    .set(FileTransferRunEntity::getEndedAt, run.getEndedAt());
        }
        mutationService().dismissRunFromQueueAndEvent(run, update);
    }

    @Transactional
    public void removeManualItem(Long runId, Long itemId) {
        FileTransferRunEntity run = requireWritableRun(runId);
        assertManualRun(run);
        String runStatus = normalizeStatus(run.getStatus());
        if ("RUNNING".equals(runStatus) || "PAUSED".equals(runStatus)) {
            throw bad("Cancel the active file transfer run before removing an item");
        }
        FileTransferRunItemEntity item = itemMapper.selectOne(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                .eq(FileTransferRunItemEntity::getId, itemId)
                .eq(FileTransferRunItemEntity::getRunId, runId)
                .eq(FileTransferRunItemEntity::getTenantId, run.getTenantId())
                .eq(FileTransferRunItemEntity::getProjectId, run.getProjectId())
                .last("limit 1"));
        if (item == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "File transfer item not found: " + itemId);
        }
        if (BUSY_ITEM_STATUSES.contains(normalizeStatus(item.getStatus()))
                && (!"CANCELED".equals(runStatus) || hasClaimedDispatch(run))) {
            throw bad("Only inactive file transfer items can be removed");
        }
        mutationService().removeItemAndEvent(run, item);
        Map<String, Object> snapshot = copy(run.getResolvedSpecJson());
        List<Map<String, Object>> manualItems = maps(snapshot.get("manualItems"));
        manualItems.removeIf(candidate -> sameTransferItem(candidate, item));
        snapshot.put("manualItems", manualItems);
        run.setResolvedSpecJson(snapshot);
        Long remainingItems = itemMapper.selectCount(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                .eq(FileTransferRunItemEntity::getTenantId, run.getTenantId())
                .eq(FileTransferRunItemEntity::getProjectId, run.getProjectId())
                .eq(FileTransferRunItemEntity::getRunId, run.getId()));
        if (remainingItems == null || remainingItems.longValue() == 0L) {
            cancelQueuedDispatches(run);
            run.setStatus("CANCELED");
            run.setMessage("All file transfer items were removed from the queue");
            run.setActiveFiles(0);
            run.setCurrentBytesPerSecond(0L);
            run.setEndedAt(LocalDateTime.now());
        }
        LambdaUpdateWrapper<FileTransferRunEntity> runUpdate = new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getResolvedSpecJson, snapshot,
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                .eq(FileTransferRunEntity::getId, run.getId());
        if (remainingItems == null || remainingItems.longValue() == 0L) {
            runUpdate.set(FileTransferRunEntity::getStatus, run.getStatus())
                    .set(FileTransferRunEntity::getMessage, run.getMessage())
                    .set(FileTransferRunEntity::getActiveFiles, 0)
                    .set(FileTransferRunEntity::getCurrentBytesPerSecond, 0L)
                    .set(FileTransferRunEntity::getEndedAt, run.getEndedAt());
        }
        updateRun(run.getId(), runUpdate);
    }

    private boolean sameTransferItem(Map<String, Object> candidate, FileTransferRunItemEntity item) {
        if (candidate == null || item == null) {
            return false;
        }
        return Objects.equals(text(candidate.get("sourcePath")), text(item.getSourcePath()))
                && Objects.equals(text(candidate.get("targetPath")), text(item.getTargetPath()));
    }

    private void insertRun(FileTransferRunEntity run) {
        mutationService().insertRunAndEvent(run);
    }

    private int updateRun(Long runId, LambdaUpdateWrapper<FileTransferRunEntity> update) {
        return mutationService().updateRunAndEvent(runId, update, false, true);
    }

    private void updateItem(Long runId, String coreItemId,
                            LambdaUpdateWrapper<FileTransferRunItemEntity> update,
                            boolean progress, boolean force) {
        mutationService().updateItemAndEvent(runId, coreItemId, update, progress, force);
    }

    private FileTransferStateMutationService mutationService() {
        if (mutationService == null) {
            throw new IllegalStateException("File transfer state mutation service is required");
        }
        return mutationService;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private FileTransferRunEntity newRun(Long projectId, String triggerType, Long runtimeClusterId) {
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setTenantId(securityService.currentTenantId());
        run.setProjectId(projectId);
        run.setTriggerType(triggerType);
        run.setStatus("QUEUED");
        run.setQueueVisible(true);
        run.setRuntimeClusterId(runtimeClusterId);
        run.setTargetRuntimeClusterId(runtimeClusterId);
        run.setTotalFiles(0L);
        run.setSuccessFiles(0L);
        run.setSkippedFiles(0L);
        run.setFailedFiles(0L);
        run.setConflictFiles(0L);
        run.setResumedFiles(0L);
        run.setPostActionFailedFiles(0L);
        run.setTotalBytes(0L);
        run.setTransferredBytes(0L);
        run.setFailedBytes(0L);
        run.setResumedBytes(0L);
        run.setCurrentBytesPerSecond(0L);
        run.setPeakBytesPerSecond(0L);
        run.setActiveFiles(0);
        run.setRetryCount(0);
        run.setMessage("File transfer queued");
        return run;
    }

    private Long validateManualItems(Long projectId, Long requestedRuntimeClusterId,
                                     List<FileTransferManualItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw bad("At least one transfer item is required");
        }
        Long runtimeClusterId = requestedRuntimeClusterId;
        for (FileTransferManualItemRequest item : items) {
            if (item == null || item.getSourceDatasourceId() == null || item.getTargetDatasourceId() == null
                    || item.getSourcePath() == null || item.getSourcePath().trim().isEmpty()
                    || item.getTargetPath() == null || item.getTargetPath().trim().isEmpty()) {
                throw bad("Manual transfer item identity and paths are required");
            }
            Long itemClusterId = item.getRuntimeClusterId() == null
                    && item.getSourceRuntimeClusterId() == null
                    && item.getTargetRuntimeClusterId() == null
                    ? runtimeClusterId
                    : resolveSingleCluster(item.getRuntimeClusterId(),
                    item.getSourceRuntimeClusterId(), item.getTargetRuntimeClusterId());
            if (itemClusterId == null) {
                throw bad("Runtime cluster is required");
            }
            if (runtimeClusterId == null) {
                runtimeClusterId = itemClusterId;
            } else if (!Objects.equals(runtimeClusterId, itemClusterId)) {
                throw new StudioException(StudioErrorCode.FILE_TRANSFER_CROSS_CLUSTER_DISABLED,
                        "All items in one run must use the same runtime cluster");
            }
            item.setRuntimeClusterId(runtimeClusterId);
            item.setSourceRuntimeClusterId(runtimeClusterId);
            item.setTargetRuntimeClusterId(runtimeClusterId);
            runtimeClusterSelectionService.assertExistingResourceRunnable(projectId,
                    runtimeClusterId, List.of(item.getSourceDatasourceId()));
            runtimeClusterSelectionService.assertExistingResourceRunnable(projectId,
                    runtimeClusterId, List.of(item.getTargetDatasourceId()));
            item.setSourcePath(unstructuredManagementService.assertPermission(runtimeClusterId,
                    item.getSourceDatasourceId(), item.getSourcePath(), UnstructuredAclPermission.DOWNLOAD));
            item.setTargetPath(unstructuredManagementService.assertPermission(runtimeClusterId,
                    item.getTargetDatasourceId(), item.getTargetPath(), UnstructuredAclPermission.EDIT));
        }
        if (runtimeClusterId == null) {
            throw bad("Runtime cluster is required");
        }
        requestNormalizeRuntimeCluster(items, runtimeClusterId);
        return runtimeClusterId;
    }

    private void ensureDispatch(FileTransferRunEntity run, FileTransferTaskDefinitionEntity task,
                                LocalDateTime scheduledFireTime) {
        Long active = dispatchTaskMapper.selectCount(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, run.getTenantId())
                .eq(DispatchTaskEntity::getProjectId, run.getProjectId())
                .eq(DispatchTaskEntity::getFileTransferRunId, run.getId())
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING"));
        if (active == null || active.longValue() == 0L) {
            insertDispatch(run, task, scheduledFireTime);
        }
    }

    private void insertDispatch(FileTransferRunEntity run, FileTransferTaskDefinitionEntity task,
                                LocalDateTime scheduledFireTime) {
        dispatchTaskMapper.insert(newDispatch(run, task, scheduledFireTime));
    }

    private DispatchTaskEntity newDispatch(FileTransferRunEntity run, FileTransferTaskDefinitionEntity task,
                                           LocalDateTime scheduledFireTime) {
        DispatchTaskEntity dispatch = new DispatchTaskEntity();
        dispatch.setTenantId(run.getTenantId());
        dispatch.setProjectId(run.getProjectId());
        dispatch.setExecutionType(DispatchExecutionType.FILE_TRANSFER.name());
        dispatch.setFileTransferTaskId(run.getTaskId());
        dispatch.setFileTransferRunId(run.getId());
        dispatch.setNodeCode("file_transfer_run_" + run.getId());
        dispatch.setStatus("QUEUED");
        dispatch.setTargetClusterId(runRuntimeClusterId(run));
        dispatch.setResourceRevision(task == null || task.getPublishedVersion() == null
                ? null : String.valueOf(task.getPublishedVersion()));
        dispatch.setScheduledFireTime(scheduledFireTime);
        dispatch.setAttempts(0);
        dispatch.setMaxRetries(3);
        dispatch.setTriggeredByUserId(securityService.currentUserId());
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("fileTransferRunId", run.getId());
        if (run.getTaskId() != null) {
            config.put("fileTransferTaskId", run.getTaskId());
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executionType", DispatchExecutionType.FILE_TRANSFER.name());
        payload.put("nodeType", NodeType.FILE_TRANSFER.name());
        payload.put("fileTransferRunId", run.getId());
        if (run.getTaskId() != null) {
            payload.put("fileTransferTaskId", run.getTaskId());
        }
        payload.put("projectId", run.getProjectId());
        payload.put("runtimeClusterId", runRuntimeClusterId(run));
        payload.put("config", config);
        dispatch.setPayloadJson(payload);
        return dispatch;
    }

    private FileTransferRunEntity requireReadableRun(Long runId) {
        FileTransferRunEntity run = runMapper.selectById(runId);
        if (run == null || !Objects.equals(run.getTenantId(), securityService.currentTenantId())
                || !Objects.equals(run.getProjectId(), projectResourceAccessService.requireCurrentProjectId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "File transfer run not found: " + runId);
        }
        return run;
    }

    private FileTransferRunEntity requireWritableRun(Long runId) {
        FileTransferRunEntity run = requireReadableRun(runId);
        projectResourceAccessService.assertWritable(run.getProjectId());
        return run;
    }

    FileTransferRunView toRunView(FileTransferRunEntity entity) {
        return toRunViews(List.of(entity)).get(0);
    }

    List<FileTransferRunView> toRunViews(List<FileTransferRunEntity> entities) {
        NameContext names = namesForRuns(entities);
        List<FileTransferRunView> views = new ArrayList<FileTransferRunView>();
        for (FileTransferRunEntity entity : entities) {
            views.add(toRunView(entity, names));
        }
        return views;
    }

    private FileTransferRunView toRunView(FileTransferRunEntity entity, NameContext names) {
        FileTransferRunView view = new FileTransferRunView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(Integer.valueOf(1).equals(entity.getDeleted()));
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setRunRecordId(entity.getRunRecordId());
        view.setTaskId(entity.getTaskId());
        view.setTaskName(entity.getTaskNameSnapshot());
        view.setTriggerType(entity.getTriggerType());
        view.setDirection(entity.getDirection());
        view.setChannel(entity.getChannel());
        view.setStatus(entity.getStatus());
        view.setRuntimeClusterId(runtimeClusterForView(entity));
        view.setSourceRuntimeClusterId(entity.getSourceRuntimeClusterId());
        view.setSourceRuntimeClusterName(clusterName(names, entity.getSourceRuntimeClusterId()));
        view.setSourceDatasourceId(entity.getSourceDatasourceId());
        view.setSourceDatasourceName(datasourceName(names, entity.getSourceDatasourceId()));
        view.setTargetRuntimeClusterId(entity.getTargetRuntimeClusterId());
        view.setTargetRuntimeClusterName(clusterName(names, entity.getTargetRuntimeClusterId()));
        view.setTargetDatasourceId(entity.getTargetDatasourceId());
        view.setTargetDatasourceName(datasourceName(names, entity.getTargetDatasourceId()));
        view.setTotalFiles(entity.getTotalFiles());
        view.setSuccessFiles(entity.getSuccessFiles());
        view.setSkippedFiles(entity.getSkippedFiles());
        view.setFailedFiles(entity.getFailedFiles());
        view.setConflictFiles(entity.getConflictFiles());
        view.setResumedFiles(entity.getResumedFiles());
        view.setPostActionFailedFiles(entity.getPostActionFailedFiles());
        view.setTotalBytes(entity.getTotalBytes());
        view.setTransferredBytes(entity.getTransferredBytes());
        view.setFailedBytes(entity.getFailedBytes());
        view.setResumedBytes(entity.getResumedBytes());
        view.setCurrentBytesPerSecond(entity.getCurrentBytesPerSecond());
        view.setPeakBytesPerSecond(entity.getPeakBytesPerSecond());
        view.setActiveFiles(entity.getActiveFiles());
        view.setRetryCount(entity.getRetryCount());
        view.setMessage(entity.getMessage());
        view.setStartedAt(entity.getStartedAt());
        view.setEndedAt(entity.getEndedAt());
        view.setResolvedSpec(copy(entity.getResolvedSpecJson()));
        return view;
    }

    FileTransferRunItemView toItemView(FileTransferRunItemEntity entity) {
        return toItemViews(List.of(entity)).get(0);
    }

    List<FileTransferRunItemView> toItemViews(List<FileTransferRunItemEntity> entities) {
        NameContext names = namesForItems(entities);
        Map<Long, VerificationPolicyView> verificationPolicies = verificationPolicies(entities);
        List<FileTransferRunItemView> views = new ArrayList<FileTransferRunItemView>();
        for (FileTransferRunItemEntity entity : entities) {
            views.add(toItemView(entity, names, verificationPolicies.get(entity.getRunId())));
        }
        return views;
    }

    private FileTransferRunItemView toItemView(FileTransferRunItemEntity entity, NameContext names,
                                               VerificationPolicyView verificationPolicy) {
        FileTransferRunItemView view = new FileTransferRunItemView();
        view.setId(entity.getId());
        view.setRunId(entity.getRunId());
        view.setCoreItemId(entity.getCoreItemId());
        view.setDirection(entity.getDirection());
        view.setChannel(entity.getChannel());
        view.setRuntimeClusterId(runtimeClusterForView(entity));
        view.setSourceRuntimeClusterId(entity.getSourceRuntimeClusterId());
        view.setSourceRuntimeClusterName(clusterName(names, entity.getSourceRuntimeClusterId()));
        view.setSourceDatasourceId(entity.getSourceDatasourceId());
        view.setSourceDatasourceName(datasourceName(names, entity.getSourceDatasourceId()));
        view.setSourcePath(entity.getSourcePath());
        view.setTargetRuntimeClusterId(entity.getTargetRuntimeClusterId());
        view.setTargetRuntimeClusterName(clusterName(names, entity.getTargetRuntimeClusterId()));
        view.setTargetDatasourceId(entity.getTargetDatasourceId());
        view.setTargetDatasourceName(datasourceName(names, entity.getTargetDatasourceId()));
        view.setTargetPath(entity.getTargetPath());
        view.setTemporaryPath(entity.getTemporaryPath());
        view.setStatus(entity.getStatus());
        view.setFileSize(entity.getFileSize());
        view.setTransferredBytes(entity.getTransferredBytes());
        view.setResumedBytes(entity.getResumedBytes());
        view.setCurrentBytesPerSecond(entity.getCurrentBytesPerSecond());
        if (verificationPolicy != null) {
            view.setVerificationModeConfigured(verificationPolicy.configuredMode());
            view.setVerificationModeEffective(verificationPolicy.effectiveMode(
                    entity.getFileSize() == null ? 0L : Math.max(0L, entity.getFileSize())));
            view.setVerificationFrameCount(verificationPolicy.frameCount());
            view.setVerificationFrameSizeBytes(verificationPolicy.frameSizeBytes());
        }
        view.setSourceChecksum(entity.getSourceChecksum());
        view.setTargetChecksum(entity.getTargetChecksum());
        view.setAttempts(entity.getAttempts());
        view.setErrorCode(entity.getErrorCode());
        view.setErrorMessage(entity.getErrorMessage());
        view.setConflictAction(entity.getConflictAction());
        view.setSourceAction(entity.getSourceAction());
        view.setPostActionStatus(entity.getPostActionStatus());
        view.setStartedAt(entity.getStartedAt());
        view.setEndedAt(entity.getEndedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        return view;
    }

    private Map<Long, VerificationPolicyView> verificationPolicies(List<FileTransferRunItemEntity> entities) {
        Map<Long, VerificationPolicyView> result = new LinkedHashMap<Long, VerificationPolicyView>();
        for (FileTransferRunItemEntity entity : entities) {
            Long runId = entity.getRunId();
            if (runId == null || result.containsKey(runId)) {
                continue;
            }
            FileTransferRunEntity run = runMapper.selectById(runId);
            if (run == null) {
                continue;
            }
            Object rawPolicy = run.getResolvedSpecJson() == null
                    ? null : run.getResolvedSpecJson().get("policy");
            Map<String, Object> policy = rawPolicy instanceof Map<?, ?> raw
                    ? objectMapper.convertValue(raw,
                            new TypeReference<LinkedHashMap<String, Object>>() { })
                    : new LinkedHashMap<String, Object>();
            Map<String, Object> normalized = FileTransferPolicyNormalizer.normalize(policy);
            result.put(runId, new VerificationPolicyView(
                    String.valueOf(normalized.get("verificationMode")),
                    ((Number) normalized.get("verificationFrameCount")).intValue(),
                    ((Number) normalized.get("verificationFrameSizeBytes")).longValue()));
        }
        return result;
    }

    private NameContext namesForRuns(List<FileTransferRunEntity> entities) {
        Set<Long> clusterIds = new LinkedHashSet<Long>();
        Set<Long> datasourceIds = new LinkedHashSet<Long>();
        for (FileTransferRunEntity entity : entities) {
            add(clusterIds, entity.getSourceRuntimeClusterId());
            add(clusterIds, entity.getTargetRuntimeClusterId());
            add(datasourceIds, entity.getSourceDatasourceId());
            add(datasourceIds, entity.getTargetDatasourceId());
        }
        return resolveNames(clusterIds, datasourceIds);
    }

    private NameContext namesForItems(List<FileTransferRunItemEntity> entities) {
        Set<Long> clusterIds = new LinkedHashSet<Long>();
        Set<Long> datasourceIds = new LinkedHashSet<Long>();
        for (FileTransferRunItemEntity entity : entities) {
            add(clusterIds, entity.getSourceRuntimeClusterId());
            add(clusterIds, entity.getTargetRuntimeClusterId());
            add(datasourceIds, entity.getSourceDatasourceId());
            add(datasourceIds, entity.getTargetDatasourceId());
        }
        return resolveNames(clusterIds, datasourceIds);
    }

    private NameContext resolveNames(Set<Long> clusterIds, Set<Long> datasourceIds) {
        Map<Long, String> clusterNames = runtimeClusterSelectionService.runtimeClusterNames(clusterIds);
        Map<Long, String> datasourceNames = dataSourceService.listBasicNameMap(datasourceIds);
        return new NameContext(clusterNames == null ? Map.of() : clusterNames,
                datasourceNames == null ? Map.of() : datasourceNames);
    }

    private void add(Set<Long> values, Long value) {
        if (value != null) {
            values.add(value);
        }
    }

    private String clusterName(NameContext names, Long id) {
        return id == null ? null : names.clusterNames.getOrDefault(id, "未知运行集群");
    }

    private String datasourceName(NameContext names, Long id) {
        return id == null ? null : names.datasourceNames.getOrDefault(id, "未知数据源");
    }

    private record NameContext(Map<Long, String> clusterNames,
                               Map<Long, String> datasourceNames) {
    }

    private record VerificationPolicyView(String configuredMode,
                                          int frameCount,
                                          long frameSizeBytes) {
        private String effectiveMode(long fileSize) {
            if (!"PARTIAL".equals(configuredMode)) {
                return configuredMode;
            }
            long sampleBytes = Math.multiplyExact((long) frameCount, frameSizeBytes);
            return fileSize <= 0L || sampleBytes >= fileSize ? "STRONG" : "PARTIAL";
        }
    }

    private Map<String, Object> manualSnapshot(FileTransferManualRunRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("runtimeClusterId", request.getRuntimeClusterId());
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (FileTransferManualItemRequest item : request.getItems()) {
            items.add(objectMapper.convertValue(item, new TypeReference<LinkedHashMap<String, Object>>() { }));
        }
        snapshot.put("manualItems", items);
        snapshot.put("policy", FileTransferPolicyNormalizer.normalize(request.getPolicy()));
        snapshot.put("runtime", copy(request.getRuntime()));
        snapshot.put("parameters", request.getParameters() == null
                ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(request.getParameters()));
        snapshot.put("timeZone", "Asia/Shanghai");
        return snapshot;
    }

    private Long commonSourceDatasource(List<FileTransferManualItemRequest> items) {
        return commonLong(items, true, false).orElse(null);
    }

    private Long commonTargetDatasource(List<FileTransferManualItemRequest> items) {
        return commonLong(items, false, false).orElse(null);
    }

    private Optional<Long> commonLong(List<FileTransferManualItemRequest> items, boolean source, boolean cluster) {
        Long value = null;
        for (FileTransferManualItemRequest item : items) {
            Long current = source
                    ? (cluster ? item.getSourceRuntimeClusterId() : item.getSourceDatasourceId())
                    : (cluster ? item.getTargetRuntimeClusterId() : item.getTargetDatasourceId());
            if (value == null) {
                value = current;
            } else if (!value.equals(current)) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(value);
    }

    private boolean terminal(String status) {
        return List.of("SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELED").contains(status);
    }

    private void assertManualRun(FileTransferRunEntity run) {
        if (run == null || !"MANUAL".equalsIgnoreCase(run.getTriggerType())) {
            throw bad("Only manual file transfer queue runs can be removed");
        }
    }

    private void cancelQueuedDispatches(FileTransferRunEntity run) {
        dispatchTaskMapper.update(null, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .set(DispatchTaskEntity::getStatus, "CANCELED")
                .eq(DispatchTaskEntity::getTenantId, run.getTenantId())
                .eq(DispatchTaskEntity::getProjectId, run.getProjectId())
                .eq(DispatchTaskEntity::getFileTransferRunId, run.getId())
                .eq(DispatchTaskEntity::getStatus, "QUEUED"));
    }

    private void cancelActiveItems(FileTransferRunEntity run, LocalDateTime canceledAt) {
        LambdaQueryWrapper<FileTransferRunItemEntity> activeQuery = new LambdaQueryWrapper<FileTransferRunItemEntity>()
                .eq(FileTransferRunItemEntity::getTenantId, run.getTenantId())
                .eq(FileTransferRunItemEntity::getProjectId, run.getProjectId())
                .eq(FileTransferRunItemEntity::getRunId, run.getId())
                .in(FileTransferRunItemEntity::getStatus, ACTIVE_ITEM_STATUSES);
        List<Long> itemIds = itemMapper.selectList(activeQuery).stream()
                .map(FileTransferRunItemEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        LambdaUpdateWrapper<FileTransferRunItemEntity> update = new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getStatus, "CANCELED")
                .set(FileTransferRunItemEntity::getCurrentBytesPerSecond, 0L)
                .set(FileTransferRunItemEntity::getErrorCode, "CANCELED")
                .set(FileTransferRunItemEntity::getErrorMessage, "Canceled by user")
                .set(FileTransferRunItemEntity::getEndedAt, canceledAt)
                .set(FileTransferRunItemEntity::getUpdatedAt, canceledAt)
                .eq(FileTransferRunItemEntity::getTenantId, run.getTenantId())
                .eq(FileTransferRunItemEntity::getProjectId, run.getProjectId())
                .eq(FileTransferRunItemEntity::getRunId, run.getId())
                .in(FileTransferRunItemEntity::getStatus, ACTIVE_ITEM_STATUSES);
        mutationService().updateItemsAndEvents(run.getId(), update, itemIds);
    }

    private boolean hasClaimedDispatch(FileTransferRunEntity run) {
        Long count = dispatchTaskMapper.selectCount(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, run.getTenantId())
                .eq(DispatchTaskEntity::getProjectId, run.getProjectId())
                .eq(DispatchTaskEntity::getFileTransferRunId, run.getId())
                .eq(DispatchTaskEntity::getStatus, "RUNNING"));
        return count != null && count.longValue() > 0L;
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }

    private Map<String, Object> copy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return objectMapper.convertValue(values, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?>)) {
            return new ArrayList<Map<String, Object>>();
        }
        return objectMapper.convertValue(value, new TypeReference<ArrayList<Map<String, Object>>>() { });
    }

    private StudioException bad(String message) {
        return new StudioException(StudioErrorCode.BAD_REQUEST, message);
    }

    private Long runRuntimeClusterId(FileTransferRunEntity run) {
        return resolveSingleCluster(run.getRuntimeClusterId(), run.getSourceRuntimeClusterId(),
                run.getTargetRuntimeClusterId());
    }

    private Long taskRuntimeClusterId(FileTransferTaskDefinitionEntity task) {
        return resolveSingleCluster(task.getRuntimeClusterId(), task.getSourceRuntimeClusterId(),
                task.getTargetRuntimeClusterId());
    }

    private Long runtimeClusterForView(FileTransferRunEntity run) {
        return compatibleCluster(run.getRuntimeClusterId(), run.getSourceRuntimeClusterId(),
                run.getTargetRuntimeClusterId());
    }

    private Long runtimeClusterForView(FileTransferRunItemEntity item) {
        return compatibleCluster(item.getRuntimeClusterId(), item.getSourceRuntimeClusterId(),
                item.getTargetRuntimeClusterId());
    }

    private Long resolveSingleCluster(Long runtimeClusterId, Long sourceRuntimeClusterId,
                                      Long targetRuntimeClusterId) {
        Long resolved = compatibleCluster(runtimeClusterId, sourceRuntimeClusterId, targetRuntimeClusterId);
        if (resolved == null) {
            if (runtimeClusterId == null && sourceRuntimeClusterId == null && targetRuntimeClusterId == null) {
                throw bad("Runtime cluster is required");
            }
            throw new StudioException(StudioErrorCode.FILE_TRANSFER_CROSS_CLUSTER_DISABLED,
                    "File transfer only supports source and target datasources in the same runtime cluster");
        }
        return resolved;
    }

    private Long compatibleCluster(Long runtimeClusterId, Long sourceRuntimeClusterId,
                                   Long targetRuntimeClusterId) {
        Long resolved = runtimeClusterId != null ? runtimeClusterId
                : sourceRuntimeClusterId != null ? sourceRuntimeClusterId : targetRuntimeClusterId;
        if (resolved == null) {
            return null;
        }
        if ((sourceRuntimeClusterId != null && !Objects.equals(resolved, sourceRuntimeClusterId))
                || (targetRuntimeClusterId != null && !Objects.equals(resolved, targetRuntimeClusterId))) {
            return null;
        }
        return resolved;
    }

    private void assertSingleClusterExecutable(FileTransferRunEntity run) {
        resolveSingleCluster(run.getRuntimeClusterId(), run.getSourceRuntimeClusterId(),
                run.getTargetRuntimeClusterId());
    }

    private void requestNormalizeRuntimeCluster(List<FileTransferManualItemRequest> items, Long runtimeClusterId) {
        for (FileTransferManualItemRequest item : items) {
            item.setRuntimeClusterId(runtimeClusterId);
            item.setSourceRuntimeClusterId(runtimeClusterId);
            item.setTargetRuntimeClusterId(runtimeClusterId);
        }
    }
}
