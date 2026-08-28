package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.RunRecordPageView;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.dto.model.QueuedTaskListView;
import com.jdragon.studio.dto.model.RunListView;
import com.jdragon.studio.dto.model.RunRecordListView;
import com.jdragon.studio.dto.model.RunRecordView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RunService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 500;

    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final CollectionTaskService collectionTaskService;
    private final QualityTaskService qualityTaskService;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final StudioSecurityService securityService;
    private final RunMetricSummaryMapper runMetricSummaryMapper;

    public RunService(DispatchTaskMapper dispatchTaskMapper,
                      RunRecordMapper runRecordMapper,
                      CollectionTaskService collectionTaskService,
                      QualityTaskService qualityTaskService,
                      WorkflowDefinitionMapper workflowDefinitionMapper,
                      StudioSecurityService securityService,
                      RunMetricSummaryMapper runMetricSummaryMapper) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.collectionTaskService = collectionTaskService;
        this.qualityTaskService = qualityTaskService;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.securityService = securityService;
        this.runMetricSummaryMapper = runMetricSummaryMapper;
    }

    public RunRecordPageView listRunRecords(Long collectionTaskId,
                                            Long qualityTaskId,
                                            Long workflowDefinitionId,
                                            Boolean collectionTaskOnly,
                                            Boolean qualityTaskOnly,
                                            String status,
                                            LocalDateTime startTime,
                                            LocalDateTime endTime,
                                            Integer pageNo,
                                            Integer pageSize) {
        return listRunRecords(collectionTaskId, qualityTaskId, workflowDefinitionId,
                collectionTaskOnly, qualityTaskOnly, status, null, null,
                startTime, endTime, pageNo, pageSize);
    }

    public RunRecordPageView listRunRecords(Long collectionTaskId,
                                            Long qualityTaskId,
                                            Long workflowDefinitionId,
                                            Boolean collectionTaskOnly,
                                            Boolean qualityTaskOnly,
                                            String status,
                                            Long requestedClusterId,
                                            Long actualClusterId,
                                            LocalDateTime startTime,
                                            LocalDateTime endTime,
                                            Integer pageNo,
                                            Integer pageSize) {
        int current = normalizePageNo(pageNo);
        int size = normalizePageSize(pageSize);
        String normalizedStatus = normalizeStatus(status);
        Page<RunRecordEntity> page = new Page<RunRecordEntity>(current, size);
        Page<RunRecordEntity> entityPage = runRecordMapper.selectPage(page, runRecordListQuery(
                collectionTaskId,
                qualityTaskId,
                workflowDefinitionId,
                collectionTaskOnly,
                qualityTaskOnly,
                normalizedStatus,
                requestedClusterId,
                actualClusterId,
                startTime,
                endTime)
                .orderByDesc(RunRecordEntity::getCreatedAt)
                .orderByDesc(RunRecordEntity::getId));
        List<RunRecordEntity> records = entityPage.getRecords();
        Map<Long, String> collectionTaskNames = collectionTaskNamesForPage(records, collectionTaskOnly, qualityTaskOnly);
        Map<Long, String> qualityTaskNames = qualityTaskNamesForPage(records, collectionTaskOnly, qualityTaskOnly);
        Map<Long, String> workflowNames = workflowNamesForPage(records, collectionTaskOnly, qualityTaskOnly);
        List<RunRecordListView> items = new ArrayList<RunRecordListView>();
        for (RunRecordEntity entity : records) {
            items.add(toRunRecordListView(entity, collectionTaskNames, qualityTaskNames, workflowNames));
        }

        RunRecordPageView view = new RunRecordPageView();
        view.setPageNo(current);
        view.setPageSize(size);
        view.setTotal(entityPage.getTotal());
        view.setItems(items);
        view.setFailedCount(countRunRecords(collectionTaskId, qualityTaskId, workflowDefinitionId,
                collectionTaskOnly, qualityTaskOnly, normalizedStatus, requestedClusterId, actualClusterId,
                startTime, endTime, "FAILED"));
        view.setRunningCount(countRunRecords(collectionTaskId, qualityTaskId, workflowDefinitionId,
                collectionTaskOnly, qualityTaskOnly, normalizedStatus, requestedClusterId, actualClusterId,
                startTime, endTime, "RUNNING"));
        view.setSuccessCount(countRunRecords(collectionTaskId, qualityTaskId, workflowDefinitionId,
                collectionTaskOnly, qualityTaskOnly, normalizedStatus, requestedClusterId, actualClusterId,
                startTime, endTime, "SUCCESS"));
        return view;
    }

    public RunListView list(Long collectionTaskId,
                            Long qualityTaskId,
                            Long workflowDefinitionId,
                            LocalDateTime startTime,
                            LocalDateTime endTime,
                            Boolean includeRunRecords) {
        return list(collectionTaskId, qualityTaskId, workflowDefinitionId, null, null,
                startTime, endTime, includeRunRecords);
    }

    public RunListView list(Long collectionTaskId,
                            Long qualityTaskId,
                            Long workflowDefinitionId,
                            Long requestedClusterId,
                            Long actualClusterId,
                            LocalDateTime startTime,
                            LocalDateTime endTime,
                            Boolean includeRunRecords) {
        RunListView view = new RunListView();
        Map<Long, String> collectionTaskNames = collectionTaskNames();
        Map<Long, String> qualityTaskNames = qualityTaskNames();
        Map<Long, String> workflowNames = workflowNames();
        String currentTenantId = securityService.currentTenantId();
        Long currentProjectId = securityService.currentProjectId();
        List<DispatchTaskEntity> queued = actualClusterId == null
                ? dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .select(DispatchTaskEntity::getId,
                        DispatchTaskEntity::getTenantId,
                        DispatchTaskEntity::getProjectId,
                        DispatchTaskEntity::getDeleted,
                        DispatchTaskEntity::getCreatedAt,
                        DispatchTaskEntity::getUpdatedAt,
                        DispatchTaskEntity::getExecutionType,
                        DispatchTaskEntity::getWorkflowRunId,
                        DispatchTaskEntity::getWorkflowDefinitionId,
                        DispatchTaskEntity::getWorkflowVersionId,
                        DispatchTaskEntity::getCollectionTaskId,
                        DispatchTaskEntity::getQualityTaskId,
                        DispatchTaskEntity::getNodeCode,
                        DispatchTaskEntity::getStatus,
                        DispatchTaskEntity::getTargetClusterId,
                        DispatchTaskEntity::getWorkerGroupCode,
                        DispatchTaskEntity::getLeaseOwner,
                        DispatchTaskEntity::getAttempts,
                        DispatchTaskEntity::getMaxRetries)
                .eq(DispatchTaskEntity::getTenantId, currentTenantId)
                .eq(collectionTaskId != null, DispatchTaskEntity::getCollectionTaskId, collectionTaskId)
                .eq(qualityTaskId != null, DispatchTaskEntity::getQualityTaskId, qualityTaskId)
                .eq(workflowDefinitionId != null, DispatchTaskEntity::getWorkflowDefinitionId, workflowDefinitionId)
                .eq(requestedClusterId != null, DispatchTaskEntity::getTargetClusterId, requestedClusterId)
                .eq(currentProjectId != null, DispatchTaskEntity::getProjectId, currentProjectId)
                .ge(startTime != null, DispatchTaskEntity::getCreatedAt, startTime)
                .le(endTime != null, DispatchTaskEntity::getCreatedAt, endTime)
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING")
                .orderByDesc(DispatchTaskEntity::getCreatedAt))
                : new ArrayList<DispatchTaskEntity>();
        for (DispatchTaskEntity entity : queued) {
            view.getQueuedTasks().add(toQueuedTaskListView(entity, collectionTaskNames, qualityTaskNames, workflowNames));
        }
        if (Boolean.FALSE.equals(includeRunRecords)) {
            return view;
        }
        List<RunRecordEntity> records = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                .select(RunRecordEntity::getId,
                        RunRecordEntity::getTenantId,
                        RunRecordEntity::getProjectId,
                        RunRecordEntity::getDeleted,
                        RunRecordEntity::getCreatedAt,
                        RunRecordEntity::getUpdatedAt,
                        RunRecordEntity::getExecutionType,
                        RunRecordEntity::getWorkflowRunId,
                        RunRecordEntity::getWorkflowDefinitionId,
                        RunRecordEntity::getWorkflowVersionId,
                        RunRecordEntity::getCollectionTaskId,
                        RunRecordEntity::getQualityTaskId,
                        RunRecordEntity::getNodeCode,
                        RunRecordEntity::getRequestedClusterId,
                        RunRecordEntity::getActualClusterId,
                        RunRecordEntity::getActualClusterCode,
                        RunRecordEntity::getWorkerGroupCode,
                        RunRecordEntity::getWorkerCode,
                        RunRecordEntity::getWorkerInstanceId,
                        RunRecordEntity::getWorkerBootId,
                        RunRecordEntity::getWorkerPodName,
                        RunRecordEntity::getWorkerNodeName,
                        RunRecordEntity::getStatus,
                        RunRecordEntity::getMessage,
                        RunRecordEntity::getStartedAt,
                        RunRecordEntity::getEndedAt,
                        RunRecordEntity::getCollectedRecords,
                        RunRecordEntity::getReadSucceedRecords,
                        RunRecordEntity::getReadFailedRecords,
                        RunRecordEntity::getWriteSucceedRecords,
                        RunRecordEntity::getWriteFailedRecords,
                        RunRecordEntity::getFailedRecords,
                        RunRecordEntity::getSuccessRecords,
                        RunRecordEntity::getTransformerTotalRecords,
                        RunRecordEntity::getTransformerSuccessRecords,
                        RunRecordEntity::getTransformerFailedRecords,
                        RunRecordEntity::getTransformerFilterRecords)
                .eq(RunRecordEntity::getTenantId, currentTenantId)
                .eq(collectionTaskId != null, RunRecordEntity::getCollectionTaskId, collectionTaskId)
                .eq(qualityTaskId != null, RunRecordEntity::getQualityTaskId, qualityTaskId)
                .eq(workflowDefinitionId != null, RunRecordEntity::getWorkflowDefinitionId, workflowDefinitionId)
                .eq(requestedClusterId != null, RunRecordEntity::getRequestedClusterId, requestedClusterId)
                .eq(actualClusterId != null, RunRecordEntity::getActualClusterId, actualClusterId)
                .eq(currentProjectId != null, RunRecordEntity::getProjectId, currentProjectId)
                .ge(startTime != null, RunRecordEntity::getCreatedAt, startTime)
                .le(endTime != null, RunRecordEntity::getCreatedAt, endTime)
                .orderByDesc(RunRecordEntity::getCreatedAt));
        for (RunRecordEntity entity : records) {
            view.getRunRecords().add(toRunRecordListView(entity, collectionTaskNames, qualityTaskNames, workflowNames));
        }
        return view;
    }

    public RunRecordView get(Long runRecordId) {
        RunRecordEntity entity = getEntity(runRecordId);
        return toRunRecordView(entity, collectionTaskNames(), qualityTaskNames(), workflowNames());
    }

    public RunRecordListView getSummary(Long runRecordId) {
        RunRecordEntity entity = runRecordMapper.selectOne(runRecordSummaryQuery(runRecordId).last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record not found: " + runRecordId);
        }
        return toRunRecordListView(entity,
                singleNameMap(entity.getCollectionTaskId(), collectionTaskService.getAccessibleName(entity.getCollectionTaskId())),
                singleNameMap(entity.getQualityTaskId(), qualityTaskService.getAccessibleName(entity.getQualityTaskId())),
                singleNameMap(entity.getWorkflowDefinitionId(), workflowName(entity.getWorkflowDefinitionId())));
    }

    public RunRecordEntity getEntity(Long runRecordId) {
        RunRecordEntity entity = runRecordMapper.selectById(runRecordId);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record not found: " + runRecordId);
        }
        if (!securityService.currentTenantId().equals(entity.getTenantId())
                || (securityService.currentProjectId() != null
                && !securityService.currentProjectId().equals(entity.getProjectId()))) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record not found: " + runRecordId);
        }
        return entity;
    }

    public RunRecordEntity getLogPointer(Long runRecordId) {
        RunRecordEntity entity = runRecordMapper.selectOne(runRecordLogPointerQuery(runRecordId).last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record not found: " + runRecordId);
        }
        return entity;
    }

    public Long countQueuedTasks() {
        Long count = dispatchTaskMapper.selectCount(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, securityService.currentTenantId())
                .eq(securityService.currentProjectId() != null, DispatchTaskEntity::getProjectId, securityService.currentProjectId())
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING"));
        return count == null ? 0L : count;
    }

    public RunLogView buildHistoricalFallback(RunRecordEntity entity) {
        RunLogView view = new RunLogView();
        view.setRunRecordId(entity.getId());
        view.setCharset(entity.getLogCharset() == null ? "UTF-8" : entity.getLogCharset());
        view.setContentType("text/plain;charset=UTF-8");
        view.setDownloadName("run-" + entity.getId() + "-summary.log");
        view.setSizeBytes(0L);
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setHistoricalFallback(true);
        view.setTruncated(false);
        view.setPaged(false);
        view.setPageNo(1);
        view.setTotalPages(1);
        view.setPageSizeBytes(0);
        view.setContent(buildFallbackContent(entity));
        return view;
    }

    private long countRunRecords(Long collectionTaskId,
                                 Long qualityTaskId,
                                 Long workflowDefinitionId,
                                 Boolean collectionTaskOnly,
                                 Boolean qualityTaskOnly,
                                 String status,
                                 Long requestedClusterId,
                                 Long actualClusterId,
                                 LocalDateTime startTime,
                                 LocalDateTime endTime,
                                 String statusGroup) {
        LambdaQueryWrapper<RunRecordEntity> query = runRecordBaseQuery(
                collectionTaskId,
                qualityTaskId,
                workflowDefinitionId,
                collectionTaskOnly,
                qualityTaskOnly,
                status,
                requestedClusterId,
                actualClusterId,
                startTime,
                endTime);
        if ("FAILED".equals(statusGroup)) {
            query.like(RunRecordEntity::getStatus, "FAIL");
        } else if ("RUNNING".equals(statusGroup)) {
            query.like(RunRecordEntity::getStatus, "RUN");
        } else if ("SUCCESS".equals(statusGroup)) {
            query.eq(RunRecordEntity::getStatus, "SUCCESS");
        }
        Long count = runRecordMapper.selectCount(query);
        return count == null ? 0L : count.longValue();
    }

    private LambdaQueryWrapper<RunRecordEntity> runRecordListQuery(Long collectionTaskId,
                                                                   Long qualityTaskId,
                                                                   Long workflowDefinitionId,
                                                                   Boolean collectionTaskOnly,
                                                                   Boolean qualityTaskOnly,
                                                                   String status,
                                                                   Long requestedClusterId,
                                                                   Long actualClusterId,
                                                                   LocalDateTime startTime,
                                                                   LocalDateTime endTime) {
        return selectRunRecordSummaryColumns(runRecordBaseQuery(
                collectionTaskId,
                qualityTaskId,
                workflowDefinitionId,
                collectionTaskOnly,
                qualityTaskOnly,
                status,
                requestedClusterId,
                actualClusterId,
                startTime,
                endTime));
    }

    private LambdaQueryWrapper<RunRecordEntity> runRecordBaseQuery(Long collectionTaskId,
                                                                   Long qualityTaskId,
                                                                   Long workflowDefinitionId,
                                                                   Boolean collectionTaskOnly,
                                                                   Boolean qualityTaskOnly,
                                                                   String status,
                                                                   Long requestedClusterId,
                                                                   Long actualClusterId,
                                                                   LocalDateTime startTime,
                                                                   LocalDateTime endTime) {
        return new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getTenantId, securityService.currentTenantId())
                .eq(collectionTaskId != null, RunRecordEntity::getCollectionTaskId, collectionTaskId)
                .eq(qualityTaskId != null, RunRecordEntity::getQualityTaskId, qualityTaskId)
                .eq(workflowDefinitionId != null, RunRecordEntity::getWorkflowDefinitionId, workflowDefinitionId)
                .isNotNull(Boolean.TRUE.equals(collectionTaskOnly), RunRecordEntity::getCollectionTaskId)
                .isNotNull(Boolean.TRUE.equals(qualityTaskOnly), RunRecordEntity::getQualityTaskId)
                .eq(hasText(status), RunRecordEntity::getStatus, status)
                .eq(requestedClusterId != null, RunRecordEntity::getRequestedClusterId, requestedClusterId)
                .eq(actualClusterId != null, RunRecordEntity::getActualClusterId, actualClusterId)
                .eq(securityService.currentProjectId() != null, RunRecordEntity::getProjectId, securityService.currentProjectId())
                .ge(startTime != null, RunRecordEntity::getCreatedAt, startTime)
                .le(endTime != null, RunRecordEntity::getCreatedAt, endTime);
    }

    private LambdaQueryWrapper<RunRecordEntity> runRecordSummaryQuery(Long runRecordId) {
        return selectRunRecordSummaryColumns(new LambdaQueryWrapper<RunRecordEntity>())
                .eq(RunRecordEntity::getId, runRecordId)
                .eq(RunRecordEntity::getTenantId, securityService.currentTenantId())
                .eq(securityService.currentProjectId() != null, RunRecordEntity::getProjectId, securityService.currentProjectId());
    }

    private LambdaQueryWrapper<RunRecordEntity> runRecordLogPointerQuery(Long runRecordId) {
        return new LambdaQueryWrapper<RunRecordEntity>()
                .select(RunRecordEntity::getId,
                        RunRecordEntity::getTenantId,
                        RunRecordEntity::getProjectId,
                        RunRecordEntity::getDeleted,
                        RunRecordEntity::getCreatedAt,
                        RunRecordEntity::getUpdatedAt,
                        RunRecordEntity::getNodeCode,
                        RunRecordEntity::getCollectionTaskId,
                        RunRecordEntity::getActualClusterId,
                        RunRecordEntity::getActualClusterCode,
                        RunRecordEntity::getWorkerGroupCode,
                        RunRecordEntity::getWorkerCode,
                        RunRecordEntity::getWorkerInstanceId,
                        RunRecordEntity::getWorkerBootId,
                        RunRecordEntity::getStatus,
                        RunRecordEntity::getMessage,
                        RunRecordEntity::getStartedAt,
                        RunRecordEntity::getEndedAt,
                        RunRecordEntity::getLogFilePath,
                        RunRecordEntity::getLogSizeBytes,
                        RunRecordEntity::getLogCharset,
                        RunRecordEntity::getLogStorageType,
                        RunRecordEntity::getLogObjectBucket,
                        RunRecordEntity::getLogObjectKey,
                        RunRecordEntity::getLogChunkCount,
                        RunRecordEntity::getLogStatus,
                        RunRecordEntity::getLogErrorSummary)
                .eq(RunRecordEntity::getId, runRecordId)
                .eq(RunRecordEntity::getTenantId, securityService.currentTenantId())
                .eq(securityService.currentProjectId() != null, RunRecordEntity::getProjectId, securityService.currentProjectId());
    }

    private LambdaQueryWrapper<RunRecordEntity> selectRunRecordSummaryColumns(LambdaQueryWrapper<RunRecordEntity> query) {
        return query.select(RunRecordEntity::getId,
                RunRecordEntity::getTenantId,
                RunRecordEntity::getProjectId,
                RunRecordEntity::getDeleted,
                RunRecordEntity::getCreatedAt,
                RunRecordEntity::getUpdatedAt,
                RunRecordEntity::getExecutionType,
                RunRecordEntity::getWorkflowRunId,
                RunRecordEntity::getWorkflowDefinitionId,
                RunRecordEntity::getWorkflowVersionId,
                RunRecordEntity::getCollectionTaskId,
                RunRecordEntity::getQualityTaskId,
                RunRecordEntity::getNodeCode,
                RunRecordEntity::getRequestedClusterId,
                RunRecordEntity::getActualClusterId,
                RunRecordEntity::getActualClusterCode,
                RunRecordEntity::getWorkerGroupCode,
                RunRecordEntity::getWorkerCode,
                RunRecordEntity::getWorkerInstanceId,
                RunRecordEntity::getWorkerBootId,
                RunRecordEntity::getWorkerPodName,
                RunRecordEntity::getWorkerNodeName,
                RunRecordEntity::getStatus,
                RunRecordEntity::getMessage,
                RunRecordEntity::getStartedAt,
                RunRecordEntity::getEndedAt,
                RunRecordEntity::getCollectedRecords,
                RunRecordEntity::getReadSucceedRecords,
                RunRecordEntity::getReadFailedRecords,
                RunRecordEntity::getWriteSucceedRecords,
                RunRecordEntity::getWriteFailedRecords,
                RunRecordEntity::getFailedRecords,
                RunRecordEntity::getSuccessRecords,
                RunRecordEntity::getTransformerTotalRecords,
                RunRecordEntity::getTransformerSuccessRecords,
                RunRecordEntity::getTransformerFailedRecords,
                RunRecordEntity::getTransformerFilterRecords);
    }

    private int normalizePageNo(Integer pageNo) {
        if (pageNo == null || pageNo.intValue() < 1) {
            return DEFAULT_PAGE_NO;
        }
        return pageNo.intValue();
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize.intValue() < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize.intValue(), MAX_PAGE_SIZE);
    }

    private String normalizeStatus(String status) {
        if (!hasText(status)) {
            return null;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private Map<Long, String> collectionTaskNames() {
        return collectionTaskService.listAccessibleNames();
    }

    private Map<Long, String> qualityTaskNames() {
        return qualityTaskService.listAccessibleNames();
    }

    private Map<Long, String> workflowNames() {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .select(WorkflowDefinitionEntity::getId,
                        WorkflowDefinitionEntity::getName)
                .eq(WorkflowDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(securityService.currentProjectId() != null, WorkflowDefinitionEntity::getProjectId, securityService.currentProjectId())
                .orderByAsc(WorkflowDefinitionEntity::getCode));
        for (WorkflowDefinitionEntity definition : definitions) {
            if (definition.getId() != null) {
                result.put(definition.getId(), definition.getName());
            }
        }
        return result;
    }

    private String workflowName(Long workflowDefinitionId) {
        if (workflowDefinitionId == null) {
            return null;
        }
        WorkflowDefinitionEntity definition = workflowDefinitionMapper.selectOne(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .select(WorkflowDefinitionEntity::getId,
                        WorkflowDefinitionEntity::getName)
                .eq(WorkflowDefinitionEntity::getId, workflowDefinitionId)
                .eq(WorkflowDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(securityService.currentProjectId() != null, WorkflowDefinitionEntity::getProjectId, securityService.currentProjectId())
                .last("limit 1"));
        return definition == null ? null : definition.getName();
    }

    private Map<Long, String> singleNameMap(Long id, String name) {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        if (id != null && name != null) {
            result.put(id, name);
        }
        return result;
    }

    private Map<Long, String> collectionTaskNamesForPage(List<RunRecordEntity> records,
                                                         Boolean collectionTaskOnly,
                                                         Boolean qualityTaskOnly) {
        if (Boolean.TRUE.equals(qualityTaskOnly)) {
            return new LinkedHashMap<Long, String>();
        }
        Set<Long> ids = collectionTaskIds(records);
        if (ids.isEmpty()) {
            return new LinkedHashMap<Long, String>();
        }
        return collectionTaskService.listAccessibleNamesByIds(ids);
    }

    private Map<Long, String> qualityTaskNamesForPage(List<RunRecordEntity> records,
                                                      Boolean collectionTaskOnly,
                                                      Boolean qualityTaskOnly) {
        if (Boolean.TRUE.equals(collectionTaskOnly)) {
            return new LinkedHashMap<Long, String>();
        }
        Set<Long> ids = qualityTaskIds(records);
        if (ids.isEmpty()) {
            return new LinkedHashMap<Long, String>();
        }
        return qualityTaskService.listAccessibleNamesByIds(ids);
    }

    private Map<Long, String> workflowNamesForPage(List<RunRecordEntity> records,
                                                   Boolean collectionTaskOnly,
                                                   Boolean qualityTaskOnly) {
        if (Boolean.TRUE.equals(collectionTaskOnly) || Boolean.TRUE.equals(qualityTaskOnly)) {
            return new LinkedHashMap<Long, String>();
        }
        Set<Long> ids = workflowDefinitionIds(records);
        if (ids.isEmpty()) {
            return new LinkedHashMap<Long, String>();
        }
        return workflowNames(ids);
    }

    private Map<Long, String> workflowNames(Collection<Long> workflowDefinitionIds) {
        Set<Long> ids = normalizeIds(workflowDefinitionIds);
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        if (ids.isEmpty()) {
            return result;
        }
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .select(WorkflowDefinitionEntity::getId,
                        WorkflowDefinitionEntity::getName)
                .eq(WorkflowDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(securityService.currentProjectId() != null, WorkflowDefinitionEntity::getProjectId, securityService.currentProjectId())
                .in(WorkflowDefinitionEntity::getId, ids)
                .orderByAsc(WorkflowDefinitionEntity::getCode));
        for (WorkflowDefinitionEntity definition : definitions) {
            if (definition.getId() != null) {
                result.put(definition.getId(), definition.getName());
            }
        }
        return result;
    }

    private Set<Long> collectionTaskIds(Collection<RunRecordEntity> records) {
        Set<Long> result = new HashSet<Long>();
        if (records == null) {
            return result;
        }
        for (RunRecordEntity record : records) {
            if (record != null && record.getCollectionTaskId() != null) {
                result.add(record.getCollectionTaskId());
            }
        }
        return result;
    }

    private Set<Long> qualityTaskIds(Collection<RunRecordEntity> records) {
        Set<Long> result = new HashSet<Long>();
        if (records == null) {
            return result;
        }
        for (RunRecordEntity record : records) {
            if (record != null && record.getQualityTaskId() != null) {
                result.add(record.getQualityTaskId());
            }
        }
        return result;
    }

    private Set<Long> workflowDefinitionIds(Collection<RunRecordEntity> records) {
        Set<Long> result = new HashSet<Long>();
        if (records == null) {
            return result;
        }
        for (RunRecordEntity record : records) {
            if (record != null && record.getWorkflowDefinitionId() != null) {
                result.add(record.getWorkflowDefinitionId());
            }
        }
        return result;
    }

    private Set<Long> normalizeIds(Collection<Long> ids) {
        Set<Long> result = new HashSet<Long>();
        if (ids == null) {
            return result;
        }
        for (Long id : ids) {
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private QueuedTaskListView toQueuedTaskListView(DispatchTaskEntity entity,
                                                    Map<Long, String> collectionTaskNames,
                                                    Map<Long, String> qualityTaskNames,
                                                    Map<Long, String> workflowNames) {
        QueuedTaskListView view = new QueuedTaskListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setExecutionType(entity.getExecutionType());
        view.setWorkflowRunId(entity.getWorkflowRunId());
        view.setWorkflowDefinitionId(entity.getWorkflowDefinitionId());
        view.setWorkflowVersionId(entity.getWorkflowVersionId());
        view.setWorkflowName(resolveWorkflowName(entity.getWorkflowDefinitionId(), workflowNames));
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setCollectionTaskName(resolveCollectionTaskName(entity.getCollectionTaskId(), collectionTaskNames));
        view.setQualityTaskId(entity.getQualityTaskId());
        view.setQualityTaskName(resolveQualityTaskName(entity.getQualityTaskId(), qualityTaskNames));
        view.setNodeCode(entity.getNodeCode());
        view.setStatus(entity.getStatus());
        view.setTargetClusterId(entity.getTargetClusterId());
        view.setWorkerGroupCode(entity.getWorkerGroupCode());
        view.setLeaseOwner(entity.getLeaseOwner());
        view.setAttempts(entity.getAttempts());
        view.setMaxRetries(entity.getMaxRetries());
        return view;
    }

    private RunRecordListView toRunRecordListView(RunRecordEntity entity,
                                                  Map<Long, String> collectionTaskNames,
                                                  Map<Long, String> qualityTaskNames,
                                                  Map<Long, String> workflowNames) {
        RunRecordListView view = new RunRecordListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setExecutionType(entity.getExecutionType());
        view.setWorkflowRunId(entity.getWorkflowRunId());
        view.setWorkflowDefinitionId(entity.getWorkflowDefinitionId());
        view.setWorkflowVersionId(entity.getWorkflowVersionId());
        view.setWorkflowName(resolveWorkflowName(entity.getWorkflowDefinitionId(), workflowNames));
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setCollectionTaskName(resolveCollectionTaskName(entity.getCollectionTaskId(), collectionTaskNames));
        view.setQualityTaskId(entity.getQualityTaskId());
        view.setQualityTaskName(resolveQualityTaskName(entity.getQualityTaskId(), qualityTaskNames));
        view.setNodeCode(entity.getNodeCode());
        view.setRequestedClusterId(entity.getRequestedClusterId());
        view.setActualClusterId(entity.getActualClusterId());
        view.setActualClusterCode(entity.getActualClusterCode());
        view.setWorkerGroupCode(entity.getWorkerGroupCode());
        view.setWorkerCode(entity.getWorkerCode());
        view.setWorkerInstanceId(entity.getWorkerInstanceId());
        view.setWorkerBootId(entity.getWorkerBootId());
        view.setWorkerPodName(entity.getWorkerPodName());
        view.setWorkerNodeName(entity.getWorkerNodeName());
        view.setStatus(entity.getStatus());
        view.setMessage(RunRecordMessageSanitizer.sanitizeAndTruncateMessage(entity.getMessage()));
        view.setStartedAt(entity.getStartedAt());
        view.setEndedAt(entity.getEndedAt());
        view.setDurationMs(resolveListDurationMs(entity));
        view.setMetricSummary(runMetricSummaryMapper.fromEntity(entity));
        return view;
    }

    private RunRecordView toRunRecordView(RunRecordEntity entity,
                                          Map<Long, String> collectionTaskNames,
                                          Map<Long, String> qualityTaskNames,
                                          Map<Long, String> workflowNames) {
        RunRecordView view = new RunRecordView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setExecutionType(entity.getExecutionType());
        view.setWorkflowRunId(entity.getWorkflowRunId());
        view.setWorkflowDefinitionId(entity.getWorkflowDefinitionId());
        view.setWorkflowVersionId(entity.getWorkflowVersionId());
        view.setWorkflowName(resolveWorkflowName(entity.getWorkflowDefinitionId(), workflowNames));
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setCollectionTaskName(resolveCollectionTaskName(entity.getCollectionTaskId(), collectionTaskNames));
        view.setQualityTaskId(entity.getQualityTaskId());
        view.setQualityTaskName(resolveQualityTaskName(entity.getQualityTaskId(), qualityTaskNames));
        view.setNodeCode(entity.getNodeCode());
        view.setRequestedClusterId(entity.getRequestedClusterId());
        view.setActualClusterId(entity.getActualClusterId());
        view.setActualClusterCode(entity.getActualClusterCode());
        view.setWorkerGroupCode(entity.getWorkerGroupCode());
        view.setWorkerCode(entity.getWorkerCode());
        view.setWorkerInstanceId(entity.getWorkerInstanceId());
        view.setWorkerBootId(entity.getWorkerBootId());
        view.setWorkerPodName(entity.getWorkerPodName());
        view.setWorkerNodeName(entity.getWorkerNodeName());
        view.setStatus(entity.getStatus());
        view.setMessage(RunRecordMessageSanitizer.sanitizeAndTruncateMessage(entity.getMessage()));
        view.setStartedAt(entity.getStartedAt());
        view.setEndedAt(entity.getEndedAt());
        view.setLogFilePath(entity.getLogFilePath());
        view.setLogSizeBytes(entity.getLogSizeBytes());
        view.setLogCharset(entity.getLogCharset());
        view.setLogStorageType(entity.getLogStorageType());
        view.setLogStatus(entity.getLogStatus());
        view.setLogErrorSummary(RunRecordMessageSanitizer.sanitizeAndTruncateMessage(entity.getLogErrorSummary()));
        view.setMetricSummary(runMetricSummaryMapper.fromEntity(entity));
        view.setPayloadJson(RunRecordMessageSanitizer.sanitizePayloadOrEmpty(entity.getPayloadJson()));
        view.setResultJson(RunRecordMessageSanitizer.sanitizePayloadOrEmpty(entity.getResultJson()));
        return view;
    }

    private Long resolveListDurationMs(RunRecordEntity entity) {
        if (entity.getStartedAt() == null || entity.getEndedAt() == null) {
            return null;
        }
        long duration = Duration.between(entity.getStartedAt(), entity.getEndedAt()).toMillis();
        return duration < 0L ? null : Long.valueOf(duration);
    }

    private Long resolveDurationMs(RunRecordEntity entity) {
        Long resultDuration = readDurationMs(entity.getResultJson());
        if (resultDuration != null) {
            return resultDuration;
        }
        Long payloadDuration = readDurationMs(entity.getPayloadJson());
        if (payloadDuration != null) {
            return payloadDuration;
        }
        if (entity.getStartedAt() == null || entity.getEndedAt() == null) {
            return null;
        }
        long duration = Duration.between(entity.getStartedAt(), entity.getEndedAt()).toMillis();
        return duration < 0L ? null : Long.valueOf(duration);
    }

    private Long readDurationMs(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get("durationMs");
        if (value instanceof Number) {
            long duration = ((Number) value).longValue();
            return duration >= 0L ? Long.valueOf(duration) : null;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                long duration = Long.parseLong(text);
                return duration >= 0L ? Long.valueOf(duration) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveCollectionTaskName(Long collectionTaskId, Map<Long, String> collectionTaskNames) {
        if (collectionTaskId == null) {
            return null;
        }
        return collectionTaskNames.get(collectionTaskId);
    }

    private String resolveQualityTaskName(Long qualityTaskId, Map<Long, String> qualityTaskNames) {
        if (qualityTaskId == null) {
            return null;
        }
        return qualityTaskNames.get(qualityTaskId);
    }

    private String resolveWorkflowName(Long workflowDefinitionId, Map<Long, String> workflowNames) {
        if (workflowDefinitionId == null) {
            return null;
        }
        return workflowNames.get(workflowDefinitionId);
    }

    private String buildFallbackContent(RunRecordEntity entity) {
        StringBuilder builder = new StringBuilder();
        String safeMessage = RunRecordMessageSanitizer.sanitizeAndTruncateMessage(entity.getMessage());
        builder.append("Run Record Summary").append('\n');
        builder.append("==================").append('\n');
        builder.append("Run Record ID: ").append(entity.getId()).append('\n');
        builder.append("Status: ").append(entity.getStatus()).append('\n');
        builder.append("Worker Group: ").append(firstText(entity.getWorkerGroupCode(), entity.getWorkerCode())).append('\n');
        builder.append("Worker Instance: ").append(entity.getWorkerInstanceId()).append('\n');
        builder.append("Node: ").append(entity.getNodeCode()).append('\n');
        builder.append("Started At: ").append(entity.getStartedAt()).append('\n');
        builder.append("Ended At: ").append(entity.getEndedAt()).append('\n');
        builder.append('\n').append("Message").append('\n');
        builder.append("-------").append('\n');
        builder.append(safeMessage == null ? "" : safeMessage).append('\n');
        builder.append('\n').append("Payload").append('\n');
        builder.append("-------").append('\n');
        builder.append(toJsonBlock(RunRecordMessageSanitizer.sanitizePayloadOrEmpty(entity.getPayloadJson()))).append('\n');
        builder.append('\n').append("Result").append('\n');
        builder.append("------").append('\n');
        builder.append(toJsonBlock(RunRecordMessageSanitizer.sanitizePayloadOrEmpty(entity.getResultJson()))).append('\n');
        return builder.toString();
    }

    private String toJsonBlock(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "(none)";
        }
        return String.valueOf(value);
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
