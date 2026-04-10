package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.ModelSyncTaskItemStatus;
import com.jdragon.studio.dto.enums.ModelSyncTaskSource;
import com.jdragon.studio.dto.enums.ModelSyncTaskStatus;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.ModelSyncTaskItemView;
import com.jdragon.studio.dto.model.ModelSyncTaskView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.ModelSyncTaskCreateRequest;
import com.jdragon.studio.infra.entity.ModelSyncTaskEntity;
import com.jdragon.studio.infra.entity.ModelSyncTaskItemEntity;
import com.jdragon.studio.infra.mapper.ModelSyncTaskItemMapper;
import com.jdragon.studio.infra.mapper.ModelSyncTaskMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

@Service
public class ModelSyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(ModelSyncTaskService.class);
    private static final int TASK_FETCH_BATCH_SIZE = 100;

    private final ModelSyncTaskMapper modelSyncTaskMapper;
    private final ModelSyncTaskItemMapper modelSyncTaskItemMapper;
    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final Executor modelSyncTaskExecutor;
    private final Object batchNoLock = new Object();

    public ModelSyncTaskService(ModelSyncTaskMapper modelSyncTaskMapper,
                                ModelSyncTaskItemMapper modelSyncTaskItemMapper,
                                DataSourceService dataSourceService,
                                DataModelService dataModelService,
                                StudioSecurityService securityService,
                                ProjectResourceAccessService projectResourceAccessService,
                                @Qualifier("modelSyncTaskExecutor") Executor modelSyncTaskExecutor) {
        this.modelSyncTaskMapper = modelSyncTaskMapper;
        this.modelSyncTaskItemMapper = modelSyncTaskItemMapper;
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.modelSyncTaskExecutor = modelSyncTaskExecutor;
    }

    @Transactional
    public ModelSyncTaskView createAndStart(ModelSyncTaskCreateRequest request) {
        if (request == null || request.getDatasourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource is required");
        }
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        String currentTenantId = securityService.currentTenantId();
        DataSourceDefinition datasource = dataSourceService.getInternal(request.getDatasourceId());
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + request.getDatasourceId());
        }
        Set<String> locators = normalizeLocators(request.getPhysicalLocators());
        if (locators.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "At least one table must be selected");
        }

        ModelSyncTaskEntity task;
        synchronized (batchNoLock) {
            task = new ModelSyncTaskEntity();
            task.setTenantId(currentTenantId);
            task.setProjectId(currentProjectId);
            task.setDatasourceId(datasource.getId());
            task.setDatasourceType(datasource.getTypeCode());
            task.setDatasourceNameSnapshot(datasource.getName());
            task.setBatchNo(Integer.valueOf(nextBatchNo(currentProjectId, datasource.getId())));
            task.setName(datasource.getName() + " 第" + task.getBatchNo() + "批");
            task.setSource((request.getSource() == null ? ModelSyncTaskSource.MANUAL : request.getSource()).name());
            task.setStatus(ModelSyncTaskStatus.PENDING.name());
            task.setTotalCount(Integer.valueOf(locators.size()));
            task.setSuccessCount(Integer.valueOf(0));
            task.setFailedCount(Integer.valueOf(0));
            task.setStoppedCount(Integer.valueOf(0));
            task.setProgressPercent(Integer.valueOf(0));
            task.setStopRequested(Integer.valueOf(0));
            task.setCreatedBy(securityService.currentUserId());
            modelSyncTaskMapper.insert(task);
        }

        int seqNo = 1;
        for (String locator : locators) {
            ModelSyncTaskItemEntity item = new ModelSyncTaskItemEntity();
            item.setTenantId(currentTenantId);
            item.setProjectId(currentProjectId);
            item.setTaskId(task.getId());
            item.setSeqNo(Integer.valueOf(seqNo++));
            item.setPhysicalLocator(locator);
            item.setModelNameSnapshot(locator);
            item.setStatus(ModelSyncTaskItemStatus.PENDING.name());
            modelSyncTaskItemMapper.insert(item);
        }

        StudioRequestContext requestContext = cloneContext(StudioRequestContextHolder.getContext());
        scheduleAfterCommit(task.getId(), requestContext);
        log.info("Created model sync task. taskId={}, datasourceId={}, totalCount={}, source={}",
                task.getId(), datasource.getId(), task.getTotalCount(), task.getSource());
        return toTaskView(task);
    }

    public PageView<ModelSyncTaskView> list(Integer pageNo,
                                            Integer pageSize,
                                            String datasourceType,
                                            Long datasourceId,
                                            String status) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        Page<ModelSyncTaskEntity> page = new Page<ModelSyncTaskEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<ModelSyncTaskEntity> queryWrapper = new LambdaQueryWrapper<ModelSyncTaskEntity>()
                .eq(ModelSyncTaskEntity::getTenantId, securityService.currentTenantId())
                .eq(ModelSyncTaskEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId())
                .eq(datasourceId != null, ModelSyncTaskEntity::getDatasourceId, datasourceId)
                .eq(hasText(datasourceType), ModelSyncTaskEntity::getDatasourceType, datasourceType == null ? null : datasourceType.trim())
                .eq(hasText(status), ModelSyncTaskEntity::getStatus, status == null ? null : status.trim().toUpperCase())
                .orderByDesc(ModelSyncTaskEntity::getCreatedAt)
                .orderByDesc(ModelSyncTaskEntity::getId);
        Page<ModelSyncTaskEntity> entityPage = modelSyncTaskMapper.selectPage(page, queryWrapper);
        List<ModelSyncTaskView> items = new ArrayList<ModelSyncTaskView>();
        for (ModelSyncTaskEntity entity : entityPage.getRecords()) {
            items.add(toTaskView(entity));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    public ModelSyncTaskView get(Long taskId) {
        return toTaskView(requireTask(taskId));
    }

    public PageView<ModelSyncTaskItemView> listItems(Long taskId,
                                                     Integer pageNo,
                                                     Integer pageSize,
                                                     String keyword,
                                                     String status) {
        ModelSyncTaskEntity task = requireTask(taskId);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        Page<ModelSyncTaskItemEntity> page = new Page<ModelSyncTaskItemEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<ModelSyncTaskItemEntity> queryWrapper = new LambdaQueryWrapper<ModelSyncTaskItemEntity>()
                .eq(ModelSyncTaskItemEntity::getTenantId, task.getTenantId())
                .eq(ModelSyncTaskItemEntity::getProjectId, task.getProjectId())
                .eq(ModelSyncTaskItemEntity::getTaskId, taskId)
                .eq(hasText(status), ModelSyncTaskItemEntity::getStatus, status == null ? null : status.trim().toUpperCase())
                .and(hasText(keyword), wrapper -> wrapper.like(ModelSyncTaskItemEntity::getPhysicalLocator, keyword.trim())
                        .or()
                        .like(ModelSyncTaskItemEntity::getModelNameSnapshot, keyword.trim()))
                .orderByAsc(ModelSyncTaskItemEntity::getSeqNo)
                .orderByAsc(ModelSyncTaskItemEntity::getId);
        Page<ModelSyncTaskItemEntity> entityPage = modelSyncTaskItemMapper.selectPage(page, queryWrapper);
        List<ModelSyncTaskItemView> items = new ArrayList<ModelSyncTaskItemView>();
        for (ModelSyncTaskItemEntity entity : entityPage.getRecords()) {
            items.add(toTaskItemView(entity));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    @Transactional
    public ModelSyncTaskView stop(Long taskId) {
        ModelSyncTaskEntity task = requireTask(taskId);
        if (isTerminalTaskStatus(task.getStatus())) {
            return toTaskView(task);
        }
        task.setStopRequested(Integer.valueOf(1));
        if (ModelSyncTaskStatus.PENDING.name().equalsIgnoreCase(task.getStatus())
                || ModelSyncTaskStatus.RUNNING.name().equalsIgnoreCase(task.getStatus())) {
            task.setStatus(ModelSyncTaskStatus.STOPPING.name());
        }
        modelSyncTaskMapper.updateById(task);
        log.info("Stop requested for model sync task. taskId={}, status={}", task.getId(), task.getStatus());
        return toTaskView(task);
    }

    @Transactional
    public void delete(Long taskId) {
        ModelSyncTaskEntity task = requireTask(taskId);
        if (!isTerminalTaskStatus(task.getStatus())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only completed tasks can be deleted");
        }
        modelSyncTaskItemMapper.delete(new LambdaQueryWrapper<ModelSyncTaskItemEntity>()
                .eq(ModelSyncTaskItemEntity::getTaskId, taskId));
        modelSyncTaskMapper.deleteById(taskId);
        log.info("Deleted model sync task. taskId={}", taskId);
    }

    private void scheduleAfterCommit(Long taskId, StudioRequestContext requestContext) {
        Runnable task = () -> modelSyncTaskExecutor.execute(() -> runTask(taskId, requestContext));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    private void runTask(Long taskId, StudioRequestContext requestContext) {
        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        StudioRequestContextHolder.setContext(cloneContext(requestContext));
        try {
            executeTask(taskId);
        } catch (Exception ex) {
            log.error("Model sync task execution failed. taskId={}, reason={}", taskId, ex.getMessage(), ex);
            failTask(taskId, ex.getMessage());
        } finally {
            if (previous == null) {
                StudioRequestContextHolder.clear();
            } else {
                StudioRequestContextHolder.setContext(previous);
            }
        }
    }

    private void executeTask(Long taskId) {
        ModelSyncTaskEntity task = modelSyncTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        task.setStatus(task.getStopRequested() != null && task.getStopRequested().intValue() == 1
                ? ModelSyncTaskStatus.STOPPING.name()
                : ModelSyncTaskStatus.RUNNING.name());
        if (task.getStartedAt() == null) {
            task.setStartedAt(startedAt);
        }
        task.setFinishedAt(null);
        task.setDurationMs(null);
        modelSyncTaskMapper.updateById(task);
        log.info("Started model sync task. taskId={}, datasourceId={}, totalCount={}",
                task.getId(), task.getDatasourceId(), task.getTotalCount());

        while (true) {
            ModelSyncTaskEntity currentTask = modelSyncTaskMapper.selectById(taskId);
            if (currentTask == null) {
                return;
            }
            if (currentTask.getStopRequested() != null && currentTask.getStopRequested().intValue() == 1) {
                markPendingItemsStopped(taskId, "Stopped by user");
                finishTask(taskId, ModelSyncTaskStatus.STOPPED, null);
                return;
            }
            List<ModelSyncTaskItemEntity> batch = nextPendingBatch(taskId);
            if (batch.isEmpty()) {
                finishTask(taskId, resolveFinalStatus(taskId), null);
                return;
            }
            processBatch(currentTask, batch);
        }
    }

    private List<ModelSyncTaskItemEntity> nextPendingBatch(Long taskId) {
        return modelSyncTaskItemMapper.selectList(new LambdaQueryWrapper<ModelSyncTaskItemEntity>()
                .eq(ModelSyncTaskItemEntity::getTaskId, taskId)
                .eq(ModelSyncTaskItemEntity::getStatus, ModelSyncTaskItemStatus.PENDING.name())
                .orderByAsc(ModelSyncTaskItemEntity::getSeqNo)
                .orderByAsc(ModelSyncTaskItemEntity::getId)
                .last("limit " + TASK_FETCH_BATCH_SIZE));
    }

    private void processBatch(ModelSyncTaskEntity task, List<ModelSyncTaskItemEntity> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (ModelSyncTaskItemEntity item : batch) {
            ModelSyncTaskEntity currentTask = modelSyncTaskMapper.selectById(task.getId());
            if (currentTask == null) {
                return;
            }
            if (currentTask.getStopRequested() != null && currentTask.getStopRequested().intValue() == 1) {
                markPendingItemsStopped(task.getId(), "Stopped by user");
                return;
            }
            processSingleItem(task, item);
        }
    }

    private void processSingleItem(ModelSyncTaskEntity task, ModelSyncTaskItemEntity item) {
        if (task == null || item == null) {
            return;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        item.setStatus(ModelSyncTaskItemStatus.RUNNING.name());
        item.setStartedAt(startedAt);
        modelSyncTaskItemMapper.updateById(item);

        DataModelSyncBatchResult batchResult = dataModelService.syncBatchFromDatasource(
                task.getDatasourceId(),
                java.util.Collections.singletonList(item.getPhysicalLocator()));
        DataModelSyncItemResult itemResult = batchResult.getItems().isEmpty() ? null : batchResult.getItems().get(0);
        LocalDateTime finishedAt = itemResult == null || itemResult.getFinishedAt() == null
                ? LocalDateTime.now()
                : itemResult.getFinishedAt();
        item.setFinishedAt(finishedAt);
        item.setDurationMs(itemResult == null
                ? Long.valueOf(Duration.between(startedAt, finishedAt).toMillis())
                : itemResult.getDurationMs());
        item.setModelNameSnapshot(itemResult != null && hasText(itemResult.getModelName())
                ? itemResult.getModelName()
                : item.getModelNameSnapshot());
        if (itemResult != null && itemResult.isSuccess()) {
            item.setStatus(ModelSyncTaskItemStatus.SUCCESS.name());
            item.setMessage(null);
        } else {
            item.setStatus(ModelSyncTaskItemStatus.FAILED.name());
            item.setMessage(itemResult == null ? "Unknown sync error" : itemResult.getMessage());
        }
        modelSyncTaskItemMapper.updateById(item);
        advanceTaskProgress(task.getId(), parseTaskItemStatus(item.getStatus()), item.getMessage());
        log.info("Completed model sync task item. taskId={}, seqNo={}, physicalLocator={}, status={}, durationMs={}",
                task.getId(), item.getSeqNo(), item.getPhysicalLocator(), item.getStatus(), item.getDurationMs());
    }

    private void refreshTaskProgress(Long taskId, String lastError) {
        ModelSyncTaskEntity task = modelSyncTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        int totalCount = task.getTotalCount() == null ? 0 : task.getTotalCount().intValue();
        int successCount = countItemsByStatus(taskId, ModelSyncTaskItemStatus.SUCCESS);
        int failedCount = countItemsByStatus(taskId, ModelSyncTaskItemStatus.FAILED);
        int stoppedCount = countItemsByStatus(taskId, ModelSyncTaskItemStatus.STOPPED);
        int completedCount = successCount + failedCount + stoppedCount;
        task.setSuccessCount(Integer.valueOf(successCount));
        task.setFailedCount(Integer.valueOf(failedCount));
        task.setStoppedCount(Integer.valueOf(stoppedCount));
        task.setProgressPercent(Integer.valueOf(totalCount <= 0 ? 0 : Math.min(100, (completedCount * 100) / totalCount)));
        if (hasText(lastError)) {
            task.setLastError(lastError.trim());
        }
        if (task.getStartedAt() != null) {
            task.setDurationMs(Long.valueOf(Duration.between(task.getStartedAt(), LocalDateTime.now()).toMillis()));
        }
        modelSyncTaskMapper.updateById(task);
    }

    private void advanceTaskProgress(Long taskId, ModelSyncTaskItemStatus itemStatus, String lastError) {
        ModelSyncTaskEntity task = modelSyncTaskMapper.selectById(taskId);
        if (task == null || itemStatus == null) {
            return;
        }
        if (ModelSyncTaskItemStatus.SUCCESS.equals(itemStatus)) {
            task.setSuccessCount(Integer.valueOf((task.getSuccessCount() == null ? 0 : task.getSuccessCount().intValue()) + 1));
        } else if (ModelSyncTaskItemStatus.FAILED.equals(itemStatus)) {
            task.setFailedCount(Integer.valueOf((task.getFailedCount() == null ? 0 : task.getFailedCount().intValue()) + 1));
        } else if (ModelSyncTaskItemStatus.STOPPED.equals(itemStatus)) {
            task.setStoppedCount(Integer.valueOf((task.getStoppedCount() == null ? 0 : task.getStoppedCount().intValue()) + 1));
        }
        int totalCount = task.getTotalCount() == null ? 0 : task.getTotalCount().intValue();
        int completedCount = (task.getSuccessCount() == null ? 0 : task.getSuccessCount().intValue())
                + (task.getFailedCount() == null ? 0 : task.getFailedCount().intValue())
                + (task.getStoppedCount() == null ? 0 : task.getStoppedCount().intValue());
        task.setProgressPercent(Integer.valueOf(totalCount <= 0 ? 0 : Math.min(100, (completedCount * 100) / totalCount)));
        if (hasText(lastError) && ModelSyncTaskItemStatus.FAILED.equals(itemStatus)) {
            task.setLastError(lastError.trim());
        }
        if (task.getStartedAt() != null) {
            task.setDurationMs(Long.valueOf(Duration.between(task.getStartedAt(), LocalDateTime.now()).toMillis()));
        }
        modelSyncTaskMapper.updateById(task);
    }

    private void finishTask(Long taskId, ModelSyncTaskStatus finalStatus, String lastError) {
        ModelSyncTaskEntity task = modelSyncTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        refreshTaskProgress(taskId, lastError);
        task = modelSyncTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(finalStatus.name());
        task.setFinishedAt(LocalDateTime.now());
        if (task.getStartedAt() != null) {
            task.setDurationMs(Long.valueOf(Duration.between(task.getStartedAt(), task.getFinishedAt()).toMillis()));
        }
        if (hasText(lastError)) {
            task.setLastError(lastError.trim());
        }
        modelSyncTaskMapper.updateById(task);
        log.info("Finished model sync task. taskId={}, status={}, successCount={}, failedCount={}, stoppedCount={}, durationMs={}",
                task.getId(), task.getStatus(), task.getSuccessCount(), task.getFailedCount(), task.getStoppedCount(), task.getDurationMs());
    }

    private void failTask(Long taskId, String errorMessage) {
        markRemainingItemsFailed(taskId, errorMessage);
        finishTask(taskId, ModelSyncTaskStatus.FAILED, errorMessage);
    }

    private void markRemainingItemsFailed(Long taskId, String errorMessage) {
        List<ModelSyncTaskItemEntity> items = modelSyncTaskItemMapper.selectList(new LambdaQueryWrapper<ModelSyncTaskItemEntity>()
                .eq(ModelSyncTaskItemEntity::getTaskId, taskId)
                .and(wrapper -> wrapper.eq(ModelSyncTaskItemEntity::getStatus, ModelSyncTaskItemStatus.PENDING.name())
                        .or()
                        .eq(ModelSyncTaskItemEntity::getStatus, ModelSyncTaskItemStatus.RUNNING.name()))
                .orderByAsc(ModelSyncTaskItemEntity::getSeqNo));
        for (ModelSyncTaskItemEntity item : items) {
            item.setStatus(ModelSyncTaskItemStatus.FAILED.name());
            item.setMessage(errorMessage);
            item.setFinishedAt(LocalDateTime.now());
            if (item.getStartedAt() == null) {
                item.setStartedAt(item.getFinishedAt());
            }
            item.setDurationMs(Long.valueOf(Duration.between(item.getStartedAt(), item.getFinishedAt()).toMillis()));
            modelSyncTaskItemMapper.updateById(item);
        }
    }

    private void markPendingItemsStopped(Long taskId, String message) {
        List<ModelSyncTaskItemEntity> items = modelSyncTaskItemMapper.selectList(new LambdaQueryWrapper<ModelSyncTaskItemEntity>()
                .eq(ModelSyncTaskItemEntity::getTaskId, taskId)
                .eq(ModelSyncTaskItemEntity::getStatus, ModelSyncTaskItemStatus.PENDING.name())
                .orderByAsc(ModelSyncTaskItemEntity::getSeqNo));
        for (ModelSyncTaskItemEntity item : items) {
            LocalDateTime now = LocalDateTime.now();
            item.setStatus(ModelSyncTaskItemStatus.STOPPED.name());
            item.setMessage(message);
            item.setStartedAt(now);
            item.setFinishedAt(now);
            item.setDurationMs(Long.valueOf(0L));
            modelSyncTaskItemMapper.updateById(item);
        }
    }

    private int countItemsByStatus(Long taskId, ModelSyncTaskItemStatus status) {
        Long count = modelSyncTaskItemMapper.selectCount(new LambdaQueryWrapper<ModelSyncTaskItemEntity>()
                .eq(ModelSyncTaskItemEntity::getTaskId, taskId)
                .eq(ModelSyncTaskItemEntity::getStatus, status.name()));
        return count == null ? 0 : count.intValue();
    }

    private ModelSyncTaskStatus resolveFinalStatus(Long taskId) {
        int failedCount = countItemsByStatus(taskId, ModelSyncTaskItemStatus.FAILED);
        int stoppedCount = countItemsByStatus(taskId, ModelSyncTaskItemStatus.STOPPED);
        if (stoppedCount > 0) {
            return ModelSyncTaskStatus.STOPPED;
        }
        if (failedCount > 0) {
            return ModelSyncTaskStatus.FAILED;
        }
        return ModelSyncTaskStatus.SUCCESS;
    }

    private String firstFailureMessage(DataModelSyncBatchResult batchResult) {
        return batchResult == null ? null : batchResult.firstFailureMessage();
    }

    private int nextBatchNo(Long projectId, Long datasourceId) {
        ModelSyncTaskEntity latest = modelSyncTaskMapper.selectOne(new LambdaQueryWrapper<ModelSyncTaskEntity>()
                .eq(ModelSyncTaskEntity::getProjectId, projectId)
                .eq(ModelSyncTaskEntity::getDatasourceId, datasourceId)
                .orderByDesc(ModelSyncTaskEntity::getBatchNo)
                .last("limit 1"));
        return latest == null || latest.getBatchNo() == null ? 1 : latest.getBatchNo().intValue() + 1;
    }

    private ModelSyncTaskEntity requireTask(Long taskId) {
        if (taskId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Task id is required");
        }
        ModelSyncTaskEntity task = modelSyncTaskMapper.selectById(taskId);
        if (task == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Model sync task not found: " + taskId);
        }
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        if (task.getProjectId() == null || task.getProjectId().longValue() != currentProjectId.longValue()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Model sync task not found: " + taskId);
        }
        return task;
    }

    private ModelSyncTaskView toTaskView(ModelSyncTaskEntity entity) {
        ModelSyncTaskView view = new ModelSyncTaskView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setDatasourceId(entity.getDatasourceId());
        view.setDatasourceType(entity.getDatasourceType());
        view.setDatasourceNameSnapshot(entity.getDatasourceNameSnapshot());
        view.setBatchNo(entity.getBatchNo());
        view.setName(entity.getName());
        view.setSource(parseTaskSource(entity.getSource()));
        view.setStatus(parseTaskStatus(entity.getStatus()));
        view.setTotalCount(entity.getTotalCount());
        view.setSuccessCount(entity.getSuccessCount());
        view.setFailedCount(entity.getFailedCount());
        view.setStoppedCount(entity.getStoppedCount());
        view.setProgressPercent(entity.getProgressPercent());
        view.setStopRequested(entity.getStopRequested() != null && entity.getStopRequested().intValue() == 1);
        view.setCreatedBy(entity.getCreatedBy());
        view.setStartedAt(entity.getStartedAt());
        view.setFinishedAt(entity.getFinishedAt());
        view.setDurationMs(entity.getDurationMs());
        view.setLastError(entity.getLastError());
        return view;
    }

    private ModelSyncTaskItemView toTaskItemView(ModelSyncTaskItemEntity entity) {
        ModelSyncTaskItemView view = new ModelSyncTaskItemView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setTaskId(entity.getTaskId());
        view.setSeqNo(entity.getSeqNo());
        view.setPhysicalLocator(entity.getPhysicalLocator());
        view.setModelNameSnapshot(entity.getModelNameSnapshot());
        view.setStatus(parseTaskItemStatus(entity.getStatus()));
        view.setMessage(entity.getMessage());
        view.setStartedAt(entity.getStartedAt());
        view.setFinishedAt(entity.getFinishedAt());
        view.setDurationMs(entity.getDurationMs());
        return view;
    }

    private ModelSyncTaskSource parseTaskSource(String value) {
        if (!hasText(value)) {
            return null;
        }
        return ModelSyncTaskSource.valueOf(value.trim().toUpperCase());
    }

    private ModelSyncTaskStatus parseTaskStatus(String value) {
        if (!hasText(value)) {
            return null;
        }
        return ModelSyncTaskStatus.valueOf(value.trim().toUpperCase());
    }

    private ModelSyncTaskItemStatus parseTaskItemStatus(String value) {
        if (!hasText(value)) {
            return null;
        }
        return ModelSyncTaskItemStatus.valueOf(value.trim().toUpperCase());
    }

    private Set<String> normalizeLocators(List<String> physicalLocators) {
        Set<String> result = new LinkedHashSet<String>();
        if (physicalLocators == null) {
            return result;
        }
        for (String physicalLocator : physicalLocators) {
            if (hasText(physicalLocator)) {
                result.add(physicalLocator.trim());
            }
        }
        return result;
    }

    private StudioRequestContext cloneContext(StudioRequestContext requestContext) {
        StudioRequestContext context = new StudioRequestContext();
        if (requestContext != null) {
            context.setUserId(requestContext.getUserId());
            context.setUsername(requestContext.getUsername());
            context.setDisplayName(requestContext.getDisplayName());
            context.setTenantId(requestContext.getTenantId());
            context.setProjectId(requestContext.getProjectId());
            context.setSystemRoleCodes(requestContext.getSystemRoleCodes());
            context.setEffectiveRoleCodes(requestContext.getEffectiveRoleCodes());
        } else {
            context.setUserId(securityService.currentUserId());
            context.setUsername(securityService.currentUsername());
            context.setTenantId(securityService.currentTenantId());
            context.setProjectId(projectResourceAccessService.currentProjectId());
            context.setEffectiveRoleCodes(securityService.currentRoleCodes());
        }
        return context;
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() < 1 ? 1 : pageNo.intValue();
    }

    private int normalizePageSize(Integer pageSize) {
        int safePageSize = pageSize == null ? 20 : pageSize.intValue();
        if (safePageSize < 1) {
            safePageSize = 20;
        }
        return Math.min(safePageSize, 200);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isTerminalTaskStatus(String status) {
        if (!hasText(status)) {
            return false;
        }
        String normalized = status.trim().toUpperCase();
        return ModelSyncTaskStatus.SUCCESS.name().equals(normalized)
                || ModelSyncTaskStatus.FAILED.name().equals(normalized)
                || ModelSyncTaskStatus.STOPPED.name().equals(normalized);
    }
}
