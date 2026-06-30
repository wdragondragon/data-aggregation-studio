package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.EdgeCondition;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowEdgeDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeRunView;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.WorkflowRunDetailView;
import com.jdragon.studio.dto.model.WorkflowRunSummaryView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.entity.WorkflowEdgeEntity;
import com.jdragon.studio.infra.entity.WorkflowNodeEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkflowEdgeMapper;
import com.jdragon.studio.infra.mapper.WorkflowNodeMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkflowRunService {

    private final RunRecordMapper runRecordMapper;
    private final DispatchTaskMapper dispatchTaskMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowEdgeMapper workflowEdgeMapper;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StudioSecurityService securityService;
    private final StaleExecutionRecoveryService staleExecutionRecoveryService;
    private final WorkflowRunStatusSupport statusSupport = new WorkflowRunStatusSupport();

    public WorkflowRunService(RunRecordMapper runRecordMapper,
                              DispatchTaskMapper dispatchTaskMapper,
                              WorkflowDefinitionMapper workflowDefinitionMapper,
                              WorkflowNodeMapper workflowNodeMapper,
                              WorkflowEdgeMapper workflowEdgeMapper,
                              NamedParameterJdbcTemplate namedParameterJdbcTemplate,
                              StudioSecurityService securityService,
                              StaleExecutionRecoveryService staleExecutionRecoveryService) {
        this.runRecordMapper = runRecordMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.workflowNodeMapper = workflowNodeMapper;
        this.workflowEdgeMapper = workflowEdgeMapper;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.securityService = securityService;
        this.staleExecutionRecoveryService = staleExecutionRecoveryService;
    }

    public PageView<WorkflowRunSummaryView> list(Long workflowDefinitionId,
                                                 String status,
                                                 LocalDateTime startTime,
                                                 LocalDateTime endTime,
                                                 Integer pageNo,
                                                 Integer pageSize) {
        int safePageNo = pageNo == null || pageNo.intValue() < 1 ? 1 : pageNo.intValue();
        int safePageSize = pageSize == null ? 20 : pageSize.intValue();
        if (safePageSize < 1) {
            safePageSize = 20;
        }
        if (safePageSize > 200) {
            safePageSize = 200;
        }
        String normalizedStatus = statusSupport.normalizeSummaryStatus(status);
        if (normalizedStatus != null) {
            return listBySummaryStatus(workflowDefinitionId, normalizedStatus, startTime, endTime, safePageNo, safePageSize);
        }

        long total = countWorkflowRuns(workflowDefinitionId, startTime, endTime);
        if (total <= 0) {
            return PageView.of(safePageNo, safePageSize, 0L, new ArrayList<WorkflowRunSummaryView>());
        }

        List<Long> workflowRunIds = queryWorkflowRunIds(workflowDefinitionId, startTime, endTime, safePageNo, safePageSize);
        if (workflowRunIds.isEmpty()) {
            return PageView.of(safePageNo, safePageSize, total, new ArrayList<WorkflowRunSummaryView>());
        }

        return PageView.of(safePageNo, safePageSize, total, buildSummaryItems(workflowRunIds));
    }

    private PageView<WorkflowRunSummaryView> listBySummaryStatus(Long workflowDefinitionId,
                                                                 String summaryStatus,
                                                                 LocalDateTime startTime,
                                                                 LocalDateTime endTime,
                                                                 int pageNo,
                                                                 int pageSize) {
        long total = countWorkflowRunsBySummaryStatus(workflowDefinitionId, startTime, endTime, summaryStatus);
        if (total <= 0L) {
            return PageView.of(pageNo, pageSize, 0L, new ArrayList<WorkflowRunSummaryView>());
        }

        List<Long> workflowRunIds = queryWorkflowRunIdsBySummaryStatus(workflowDefinitionId, startTime, endTime,
                summaryStatus, pageNo, pageSize);
        if (workflowRunIds.isEmpty()) {
            return PageView.of(pageNo, pageSize, total, new ArrayList<WorkflowRunSummaryView>());
        }
        return PageView.of(pageNo, pageSize, total, buildSummaryItems(workflowRunIds));
    }

    private List<WorkflowRunSummaryView> buildSummaryItems(List<Long> workflowRunIds) {
        if (workflowRunIds == null || workflowRunIds.isEmpty()) {
            return new ArrayList<WorkflowRunSummaryView>();
        }
        String currentTenantId = securityService.currentTenantId();
        List<RunRecordEntity> records = runRecordMapper.selectList(summaryRunRecordQuery(currentTenantId, workflowRunIds));
        List<DispatchTaskEntity> tasks = dispatchTaskMapper.selectList(summaryDispatchTaskQuery(currentTenantId, workflowRunIds));

        Map<Long, List<RunRecordEntity>> recordsByRun = groupRunRecords(records, null, null);
        Map<Long, List<DispatchTaskEntity>> tasksByRun = groupDispatchTasks(tasks, null, null);
        Map<Long, String> workflowNamesCache = workflowNames(resolveWorkflowDefinitionIds(records, tasks));
        Map<Long, List<WorkflowNodeMetadata>> workflowNodesCache = workflowNodes(resolveWorkflowVersionIds(records, tasks));

        List<WorkflowRunSummaryView> items = new ArrayList<WorkflowRunSummaryView>();
        for (Long workflowRunId : workflowRunIds) {
            items.add(buildSummary(workflowRunId,
                    recordsByRun.get(workflowRunId),
                    tasksByRun.get(workflowRunId),
                    workflowNamesCache,
                    workflowNodesCache));
        }
        return items;
    }

    public WorkflowRunDetailView get(Long workflowRunId) {
        String currentTenantId = securityService.currentTenantId();
        List<RunRecordEntity> records = runRecordMapper.selectList(detailRunRecordQuery(currentTenantId, workflowRunId));
        List<DispatchTaskEntity> tasks = dispatchTaskMapper.selectList(detailDispatchTaskQuery(currentTenantId, workflowRunId));
        if (records.isEmpty() && tasks.isEmpty()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow run not found: " + workflowRunId);
        }

        Map<Long, String> workflowNames = workflowNames(resolveWorkflowDefinitionIds(records, tasks));
        Map<Long, List<WorkflowNodeMetadata>> workflowNodes = workflowNodes(resolveWorkflowVersionIds(records, tasks));
        Map<Long, List<WorkflowEdgeMetadata>> workflowEdges = workflowEdges(resolveWorkflowVersionIds(records, tasks));
        WorkflowRunDetailView detail = new WorkflowRunDetailView();
        WorkflowRunSummaryView summary = buildSummary(workflowRunId, records, tasks, workflowNames, workflowNodes);
        copySummary(summary, detail);

        detail.setWorkflow(buildWorkflowSnapshot(summary,
                workflowNodes.get(summary.getWorkflowVersionId()),
                workflowEdges.get(summary.getWorkflowVersionId())));

        Map<String, RunRecordEntity> latestRecordByNode = latestRecordByNode(records);
        Map<String, DispatchTaskEntity> latestTaskByNode = latestTaskByNode(tasks);
        Set<String> consumedNodeCodes = new LinkedHashSet<String>();
        List<WorkflowNodeRunView> nodeRuns = new ArrayList<WorkflowNodeRunView>();

        List<WorkflowNodeMetadata> nodeMetadata = workflowNodes.get(summary.getWorkflowVersionId());
        if (nodeMetadata == null || nodeMetadata.isEmpty()) {
            nodeMetadata = new ArrayList<WorkflowNodeMetadata>();
        }
        for (WorkflowNodeMetadata node : nodeMetadata) {
            if (node.nodeCode != null) {
                WorkflowNodeRunView nodeRun = buildNodeRunView(summary, node.nodeCode, node.nodeName, node.nodeType,
                        latestRecordByNode.get(node.nodeCode),
                        latestTaskByNode.get(node.nodeCode));
                nodeRuns.add(nodeRun);
                consumedNodeCodes.add(node.nodeCode);
            }
        }

        for (Map.Entry<String, RunRecordEntity> entry : latestRecordByNode.entrySet()) {
            if (consumedNodeCodes.contains(entry.getKey())) {
                continue;
            }
            nodeRuns.add(buildNodeRunView(summary, entry.getKey(), entry.getKey(), extractNodeType(entry.getValue()),
                    entry.getValue(), latestTaskByNode.get(entry.getKey())));
        }

        for (Map.Entry<String, DispatchTaskEntity> entry : latestTaskByNode.entrySet()) {
            if (consumedNodeCodes.contains(entry.getKey()) || latestRecordByNode.containsKey(entry.getKey())) {
                continue;
            }
            nodeRuns.add(buildNodeRunView(summary, entry.getKey(), entry.getKey(), extractNodeType(entry.getValue()),
                    null, entry.getValue()));
        }

        nodeRuns.sort(new Comparator<WorkflowNodeRunView>() {
            @Override
            public int compare(WorkflowNodeRunView left, WorkflowNodeRunView right) {
                LocalDateTime leftTime = left.getStartedAt() != null ? left.getStartedAt() : left.getEndedAt();
                LocalDateTime rightTime = right.getStartedAt() != null ? right.getStartedAt() : right.getEndedAt();
                if (leftTime == null && rightTime == null) {
                    return String.valueOf(left.getNodeCode()).compareToIgnoreCase(String.valueOf(right.getNodeCode()));
                }
                if (leftTime == null) {
                    return 1;
                }
                if (rightTime == null) {
                    return -1;
                }
                return rightTime.compareTo(leftTime);
            }
        });
        detail.setNodeRuns(nodeRuns);
        return detail;
    }

    public WorkflowRunDetailView terminate(Long workflowRunId) {
        get(workflowRunId);
        staleExecutionRecoveryService.terminateWorkflowRun(
                securityService.currentTenantId(),
                securityService.currentProjectId(),
                workflowRunId);
        return get(workflowRunId);
    }

    private Map<Long, String> workflowNames(Set<Long> workflowDefinitionIds) {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        if (workflowDefinitionIds == null || workflowDefinitionIds.isEmpty()) {
            return result;
        }
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .select(WorkflowDefinitionEntity::getId,
                        WorkflowDefinitionEntity::getName)
                .eq(WorkflowDefinitionEntity::getTenantId, securityService.currentTenantId())
                .in(WorkflowDefinitionEntity::getId, workflowDefinitionIds)
                .orderByAsc(WorkflowDefinitionEntity::getCode));
        for (WorkflowDefinitionEntity definition : definitions) {
            if (definition.getId() != null) {
                result.put(definition.getId(), definition.getName());
            }
        }
        return result;
    }

    private Map<Long, List<WorkflowNodeMetadata>> workflowNodes(Set<Long> workflowVersionIds) {
        Map<Long, List<WorkflowNodeMetadata>> result = new LinkedHashMap<Long, List<WorkflowNodeMetadata>>();
        if (workflowVersionIds == null || workflowVersionIds.isEmpty()) {
            return result;
        }
        List<WorkflowNodeEntity> nodes = workflowNodeMapper.selectList(new LambdaQueryWrapper<WorkflowNodeEntity>()
                .select(WorkflowNodeEntity::getWorkflowVersionId,
                        WorkflowNodeEntity::getNodeCode,
                        WorkflowNodeEntity::getNodeName,
                        WorkflowNodeEntity::getNodeType)
                .in(WorkflowNodeEntity::getWorkflowVersionId, workflowVersionIds)
                .orderByAsc(WorkflowNodeEntity::getId));
        for (WorkflowNodeEntity node : nodes) {
            if (node.getWorkflowVersionId() == null) {
                continue;
            }
            result.computeIfAbsent(node.getWorkflowVersionId(), key -> new ArrayList<WorkflowNodeMetadata>())
                    .add(new WorkflowNodeMetadata(node.getNodeCode(), node.getNodeName(), node.getNodeType()));
        }
        return result;
    }

    private Map<Long, List<WorkflowEdgeMetadata>> workflowEdges(Set<Long> workflowVersionIds) {
        Map<Long, List<WorkflowEdgeMetadata>> result = new LinkedHashMap<Long, List<WorkflowEdgeMetadata>>();
        if (workflowVersionIds == null || workflowVersionIds.isEmpty()) {
            return result;
        }
        List<WorkflowEdgeEntity> edges = workflowEdgeMapper.selectList(new LambdaQueryWrapper<WorkflowEdgeEntity>()
                .select(WorkflowEdgeEntity::getWorkflowVersionId,
                        WorkflowEdgeEntity::getFromNodeCode,
                        WorkflowEdgeEntity::getToNodeCode,
                        WorkflowEdgeEntity::getConditionType)
                .in(WorkflowEdgeEntity::getWorkflowVersionId, workflowVersionIds)
                .orderByAsc(WorkflowEdgeEntity::getId));
        for (WorkflowEdgeEntity edge : edges) {
            if (edge.getWorkflowVersionId() == null) {
                continue;
            }
            result.computeIfAbsent(edge.getWorkflowVersionId(), key -> new ArrayList<WorkflowEdgeMetadata>())
                    .add(new WorkflowEdgeMetadata(edge.getFromNodeCode(), edge.getToNodeCode(), edge.getConditionType()));
        }
        return result;
    }

    private Set<Long> resolveWorkflowVersionIds(List<RunRecordEntity> records, List<DispatchTaskEntity> tasks) {
        Set<Long> workflowVersionIds = new LinkedHashSet<Long>();
        for (RunRecordEntity record : records) {
            if (record.getWorkflowVersionId() != null) {
                workflowVersionIds.add(record.getWorkflowVersionId());
            }
        }
        for (DispatchTaskEntity task : tasks) {
            if (task.getWorkflowVersionId() != null) {
                workflowVersionIds.add(task.getWorkflowVersionId());
            }
        }
        return workflowVersionIds;
    }

    private Set<Long> resolveWorkflowDefinitionIds(List<RunRecordEntity> records, List<DispatchTaskEntity> tasks) {
        Set<Long> workflowDefinitionIds = new LinkedHashSet<Long>();
        for (RunRecordEntity record : records) {
            if (record.getWorkflowDefinitionId() != null) {
                workflowDefinitionIds.add(record.getWorkflowDefinitionId());
            }
        }
        for (DispatchTaskEntity task : tasks) {
            if (task.getWorkflowDefinitionId() != null) {
                workflowDefinitionIds.add(task.getWorkflowDefinitionId());
            }
        }
        return workflowDefinitionIds;
    }

    private WorkflowDefinitionView buildWorkflowSnapshot(WorkflowRunSummaryView summary,
                                                         List<WorkflowNodeMetadata> nodeMetadata,
                                                         List<WorkflowEdgeMetadata> edgeMetadata) {
        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(summary.getWorkflowDefinitionId());
        workflow.setTenantId(summary.getTenantId());
        workflow.setProjectId(summary.getProjectId());
        workflow.setName(summary.getWorkflowName());
        workflow.setVersionId(summary.getWorkflowVersionId());
        workflow.setNodes(toWorkflowNodes(nodeMetadata));
        workflow.setEdges(toWorkflowEdges(edgeMetadata));
        return workflow;
    }

    private List<WorkflowNodeDefinition> toWorkflowNodes(List<WorkflowNodeMetadata> nodeMetadata) {
        List<WorkflowNodeDefinition> nodes = new ArrayList<WorkflowNodeDefinition>();
        if (nodeMetadata == null) {
            return nodes;
        }
        for (WorkflowNodeMetadata metadata : nodeMetadata) {
            WorkflowNodeDefinition node = new WorkflowNodeDefinition();
            node.setNodeCode(metadata.nodeCode);
            node.setNodeName(metadata.nodeName);
            node.setNodeType(parseNodeType(metadata.nodeType));
            nodes.add(node);
        }
        return nodes;
    }

    private List<WorkflowEdgeDefinition> toWorkflowEdges(List<WorkflowEdgeMetadata> edgeMetadata) {
        List<WorkflowEdgeDefinition> edges = new ArrayList<WorkflowEdgeDefinition>();
        if (edgeMetadata == null) {
            return edges;
        }
        for (WorkflowEdgeMetadata metadata : edgeMetadata) {
            WorkflowEdgeDefinition edge = new WorkflowEdgeDefinition();
            edge.setFromNodeCode(metadata.fromNodeCode);
            edge.setToNodeCode(metadata.toNodeCode);
            edge.setCondition(parseEdgeCondition(metadata.conditionType));
            edges.add(edge);
        }
        return edges;
    }

    private NodeType parseNodeType(String nodeType) {
        if (nodeType == null || nodeType.trim().isEmpty()) {
            return null;
        }
        try {
            return NodeType.valueOf(nodeType.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private EdgeCondition parseEdgeCondition(String conditionType) {
        if (conditionType == null || conditionType.trim().isEmpty()) {
            return EdgeCondition.ON_SUCCESS;
        }
        try {
            return EdgeCondition.valueOf(conditionType.trim());
        } catch (IllegalArgumentException ignored) {
            return EdgeCondition.ON_SUCCESS;
        }
    }

    private Map<Long, List<RunRecordEntity>> groupRunRecords(List<RunRecordEntity> records,
                                                             LocalDateTime startTime,
                                                             LocalDateTime endTime) {
        Map<Long, List<RunRecordEntity>> result = new LinkedHashMap<Long, List<RunRecordEntity>>();
        for (RunRecordEntity record : records) {
            if (record.getWorkflowRunId() == null) {
                continue;
            }
            LocalDateTime occurredAt = record.getStartedAt() != null ? record.getStartedAt() : record.getCreatedAt();
            if (startTime != null && occurredAt != null && occurredAt.isBefore(startTime)) {
                continue;
            }
            if (endTime != null && occurredAt != null && occurredAt.isAfter(endTime)) {
                continue;
            }
            if (endTime != null && occurredAt == null && record.getCreatedAt() != null && record.getCreatedAt().isAfter(endTime)) {
                continue;
            }
            result.computeIfAbsent(record.getWorkflowRunId(), key -> new ArrayList<RunRecordEntity>()).add(record);
        }
        return result;
    }

    private Map<Long, List<DispatchTaskEntity>> groupDispatchTasks(List<DispatchTaskEntity> tasks,
                                                                   LocalDateTime startTime,
                                                                   LocalDateTime endTime) {
        Map<Long, List<DispatchTaskEntity>> result = new LinkedHashMap<Long, List<DispatchTaskEntity>>();
        for (DispatchTaskEntity task : tasks) {
            if (task.getWorkflowRunId() == null) {
                continue;
            }
            LocalDateTime occurredAt = task.getCreatedAt();
            if (startTime != null && occurredAt != null && occurredAt.isBefore(startTime)) {
                continue;
            }
            if (endTime != null && occurredAt != null && occurredAt.isAfter(endTime)) {
                continue;
            }
            result.computeIfAbsent(task.getWorkflowRunId(), key -> new ArrayList<DispatchTaskEntity>()).add(task);
        }
        return result;
    }

    private WorkflowRunSummaryView buildSummary(Long workflowRunId,
                                                List<RunRecordEntity> records,
                                                List<DispatchTaskEntity> tasks,
                                                Map<Long, String> workflowNames,
                                                Map<Long, List<WorkflowNodeMetadata>> workflowNodes) {
        List<RunRecordEntity> safeRecords = records == null ? new ArrayList<RunRecordEntity>() : records;
        List<DispatchTaskEntity> safeTasks = tasks == null ? new ArrayList<DispatchTaskEntity>() : tasks;

        Long workflowDefinitionId = firstNonNullWorkflowDefinitionId(safeRecords, safeTasks);
        Long workflowVersionId = firstNonNullWorkflowVersionId(safeRecords, safeTasks);
        List<WorkflowNodeMetadata> nodeMetadata = workflowNodes.get(workflowVersionId);
        Map<String, RunRecordEntity> latestRecordByNode = latestRecordByNode(safeRecords);
        Map<String, DispatchTaskEntity> latestTaskByNode = latestTaskByNode(safeTasks);
        List<WorkflowNodeRunView> nodeRuns = new ArrayList<WorkflowNodeRunView>();
        Set<String> consumedNodeCodes = new LinkedHashSet<String>();

        if (nodeMetadata != null) {
            for (WorkflowNodeMetadata node : nodeMetadata) {
                if (node.nodeCode == null) {
                    continue;
                }
                nodeRuns.add(buildNodeRunView(workflowRunId, workflowDefinitionId, workflowNames.get(workflowDefinitionId),
                        node.nodeCode, node.nodeName, node.nodeType,
                        latestRecordByNode.get(node.nodeCode),
                        latestTaskByNode.get(node.nodeCode)));
                consumedNodeCodes.add(node.nodeCode);
            }
        }

        for (Map.Entry<String, RunRecordEntity> entry : latestRecordByNode.entrySet()) {
            if (consumedNodeCodes.contains(entry.getKey())) {
                continue;
            }
            nodeRuns.add(buildNodeRunView(workflowRunId, workflowDefinitionId, workflowNames.get(workflowDefinitionId),
                    entry.getKey(), entry.getKey(), extractNodeType(entry.getValue()), entry.getValue(), latestTaskByNode.get(entry.getKey())));
        }
        for (Map.Entry<String, DispatchTaskEntity> entry : latestTaskByNode.entrySet()) {
            if (consumedNodeCodes.contains(entry.getKey()) || latestRecordByNode.containsKey(entry.getKey())) {
                continue;
            }
            nodeRuns.add(buildNodeRunView(workflowRunId, workflowDefinitionId, workflowNames.get(workflowDefinitionId),
                    entry.getKey(), entry.getKey(), extractNodeType(entry.getValue()), null, entry.getValue()));
        }

        WorkflowRunSummaryView summary = new WorkflowRunSummaryView();
        summary.setTenantId(firstNonNullTenantId(safeRecords, safeTasks));
        summary.setProjectId(firstNonNullProjectId(safeRecords, safeTasks));
        summary.setWorkflowRunId(workflowRunId);
        summary.setWorkflowDefinitionId(workflowDefinitionId);
        summary.setWorkflowVersionId(workflowVersionId);
        summary.setWorkflowName(workflowNames.get(workflowDefinitionId));
        summary.setTotalNodes(nodeRuns.size());
        summary.setSuccessNodes(countByStatus(nodeRuns, "SUCCESS"));
        summary.setFailedNodes(countByStatus(nodeRuns, "FAILED"));
        summary.setRunningNodes(countByStatus(nodeRuns, "RUNNING"));
        summary.setQueuedNodes(countByStatus(nodeRuns, "QUEUED"));
        summary.setNotRunNodes(countByStatus(nodeRuns, "NOT_RUN"));
        summary.setStatus(resolveSummaryStatus(nodeRuns));
        summary.setStartedAt(resolveStartedAt(nodeRuns));
        summary.setEndedAt(resolveEndedAt(nodeRuns));
        summary.setDurationMs(resolveDuration(summary.getStartedAt(), summary.getEndedAt()));
        summary.setSummaryMessage(resolveSummaryMessage(summary));
        return summary;
    }

    private String firstNonNullTenantId(List<RunRecordEntity> records,
                                        List<DispatchTaskEntity> tasks) {
        for (RunRecordEntity record : records) {
            if (record != null && record.getTenantId() != null) {
                return record.getTenantId();
            }
        }
        for (DispatchTaskEntity task : tasks) {
            if (task != null && task.getTenantId() != null) {
                return task.getTenantId();
            }
        }
        return null;
    }

    private Long firstNonNullProjectId(List<RunRecordEntity> records,
                                       List<DispatchTaskEntity> tasks) {
        for (RunRecordEntity record : records) {
            if (record != null && record.getProjectId() != null) {
                return record.getProjectId();
            }
        }
        for (DispatchTaskEntity task : tasks) {
            if (task != null && task.getProjectId() != null) {
                return task.getProjectId();
            }
        }
        return null;
    }

    private LambdaQueryWrapper<RunRecordEntity> summaryRunRecordQuery(String currentTenantId, List<Long> workflowRunIds) {
        return new LambdaQueryWrapper<RunRecordEntity>()
                .select(RunRecordEntity::getId,
                        RunRecordEntity::getTenantId,
                        RunRecordEntity::getProjectId,
                        RunRecordEntity::getCreatedAt,
                        RunRecordEntity::getWorkflowRunId,
                        RunRecordEntity::getWorkflowDefinitionId,
                        RunRecordEntity::getWorkflowVersionId,
                        RunRecordEntity::getNodeCode,
                        RunRecordEntity::getStatus,
                        RunRecordEntity::getWorkerGroupCode,
                        RunRecordEntity::getWorkerCode,
                        RunRecordEntity::getWorkerInstanceId,
                        RunRecordEntity::getMessage,
                        RunRecordEntity::getStartedAt,
                        RunRecordEntity::getEndedAt)
                .eq(RunRecordEntity::getTenantId, currentTenantId)
                .in(RunRecordEntity::getWorkflowRunId, workflowRunIds)
                .eq(securityService.currentProjectId() != null, RunRecordEntity::getProjectId, securityService.currentProjectId())
                .orderByDesc(RunRecordEntity::getCreatedAt);
    }

    private LambdaQueryWrapper<RunRecordEntity> detailRunRecordQuery(String currentTenantId, Long workflowRunId) {
        return new LambdaQueryWrapper<RunRecordEntity>()
                .select(RunRecordEntity::getId,
                        RunRecordEntity::getTenantId,
                        RunRecordEntity::getProjectId,
                        RunRecordEntity::getCreatedAt,
                        RunRecordEntity::getWorkflowRunId,
                        RunRecordEntity::getWorkflowDefinitionId,
                        RunRecordEntity::getWorkflowVersionId,
                        RunRecordEntity::getNodeCode,
                        RunRecordEntity::getStatus,
                        RunRecordEntity::getWorkerGroupCode,
                        RunRecordEntity::getWorkerCode,
                        RunRecordEntity::getWorkerInstanceId,
                        RunRecordEntity::getMessage,
                        RunRecordEntity::getStartedAt,
                        RunRecordEntity::getEndedAt,
                        RunRecordEntity::getLogFilePath)
                .eq(RunRecordEntity::getTenantId, currentTenantId)
                .eq(RunRecordEntity::getWorkflowRunId, workflowRunId)
                .eq(securityService.currentProjectId() != null, RunRecordEntity::getProjectId, securityService.currentProjectId())
                .orderByDesc(RunRecordEntity::getCreatedAt);
    }

    private LambdaQueryWrapper<DispatchTaskEntity> summaryDispatchTaskQuery(String currentTenantId, List<Long> workflowRunIds) {
        return new LambdaQueryWrapper<DispatchTaskEntity>()
                .select(DispatchTaskEntity::getId,
                        DispatchTaskEntity::getTenantId,
                        DispatchTaskEntity::getProjectId,
                        DispatchTaskEntity::getCreatedAt,
                        DispatchTaskEntity::getWorkflowRunId,
                        DispatchTaskEntity::getWorkflowDefinitionId,
                        DispatchTaskEntity::getWorkflowVersionId,
                        DispatchTaskEntity::getNodeCode,
                        DispatchTaskEntity::getStatus,
                        DispatchTaskEntity::getWorkerGroupCode,
                        DispatchTaskEntity::getLeaseOwner,
                        DispatchTaskEntity::getWorkerInstanceId)
                .eq(DispatchTaskEntity::getTenantId, currentTenantId)
                .in(DispatchTaskEntity::getWorkflowRunId, workflowRunIds)
                .eq(securityService.currentProjectId() != null, DispatchTaskEntity::getProjectId, securityService.currentProjectId())
                .orderByDesc(DispatchTaskEntity::getCreatedAt);
    }

    private LambdaQueryWrapper<DispatchTaskEntity> detailDispatchTaskQuery(String currentTenantId, Long workflowRunId) {
        return new LambdaQueryWrapper<DispatchTaskEntity>()
                .select(DispatchTaskEntity::getId,
                        DispatchTaskEntity::getTenantId,
                        DispatchTaskEntity::getProjectId,
                        DispatchTaskEntity::getCreatedAt,
                        DispatchTaskEntity::getWorkflowRunId,
                        DispatchTaskEntity::getWorkflowDefinitionId,
                        DispatchTaskEntity::getWorkflowVersionId,
                        DispatchTaskEntity::getNodeCode,
                        DispatchTaskEntity::getStatus,
                        DispatchTaskEntity::getWorkerGroupCode,
                        DispatchTaskEntity::getLeaseOwner,
                        DispatchTaskEntity::getWorkerInstanceId)
                .eq(DispatchTaskEntity::getTenantId, currentTenantId)
                .eq(DispatchTaskEntity::getWorkflowRunId, workflowRunId)
                .eq(securityService.currentProjectId() != null, DispatchTaskEntity::getProjectId, securityService.currentProjectId())
                .orderByDesc(DispatchTaskEntity::getCreatedAt);
    }

    private long countWorkflowRuns(Long workflowDefinitionId,
                                   LocalDateTime startTime,
                                   LocalDateTime endTime) {
        MapSqlParameterSource params = buildWorkflowRunEventParams(workflowDefinitionId, startTime, endTime);
        String workflowRunEventsSql = buildWorkflowRunEventsSql(workflowDefinitionId, startTime, endTime);
        String countSql = "select count(*) from (" +
                "select workflow_run_id from (" + workflowRunEventsSql + ") workflow_events group by workflow_run_id" +
                ") grouped_runs";
        Long total = namedParameterJdbcTemplate.queryForObject(countSql, params, Long.class);
        return total == null ? 0L : total.longValue();
    }

    private List<Long> queryWorkflowRunIds(Long workflowDefinitionId,
                                           LocalDateTime startTime,
                                           LocalDateTime endTime,
                                           int pageNo,
                                           int pageSize) {
        MapSqlParameterSource params = buildWorkflowRunEventParams(workflowDefinitionId, startTime, endTime);
        params.addValue("limit", Integer.valueOf(pageSize));
        params.addValue("offset", Integer.valueOf((pageNo - 1) * pageSize));
        String workflowRunEventsSql = buildWorkflowRunEventsSql(workflowDefinitionId, startTime, endTime);
        String idSql = "select workflow_run_id from (" +
                workflowRunEventsSql +
                ") workflow_events group by workflow_run_id order by max(occurred_at) desc, workflow_run_id desc limit :limit offset :offset";
        return namedParameterJdbcTemplate.queryForList(idSql, params, Long.class);
    }

    private long countWorkflowRunsBySummaryStatus(Long workflowDefinitionId,
                                                  LocalDateTime startTime,
                                                  LocalDateTime endTime,
                                                  String summaryStatus) {
        MapSqlParameterSource params = buildWorkflowRunEventParams(workflowDefinitionId, startTime, endTime);
        params.addValue("summaryStatus", summaryStatus);
        String countSql = buildWorkflowRunStatusCte(workflowDefinitionId, startTime, endTime) +
                " select count(*) from summarized_runs where summary_status = :summaryStatus";
        Long total = namedParameterJdbcTemplate.queryForObject(countSql, params, Long.class);
        return total == null ? 0L : total.longValue();
    }

    private List<Long> queryWorkflowRunIdsBySummaryStatus(Long workflowDefinitionId,
                                                          LocalDateTime startTime,
                                                          LocalDateTime endTime,
                                                          String summaryStatus,
                                                          int pageNo,
                                                          int pageSize) {
        MapSqlParameterSource params = buildWorkflowRunEventParams(workflowDefinitionId, startTime, endTime);
        params.addValue("summaryStatus", summaryStatus);
        params.addValue("limit", Integer.valueOf(pageSize));
        params.addValue("offset", Integer.valueOf((pageNo - 1) * pageSize));
        String idSql = buildWorkflowRunStatusCte(workflowDefinitionId, startTime, endTime) +
                " select workflow_run_id from summarized_runs " +
                "where summary_status = :summaryStatus " +
                "order by latest_at desc, workflow_run_id desc limit :limit offset :offset";
        return namedParameterJdbcTemplate.queryForList(idSql, params, Long.class);
    }

    private MapSqlParameterSource buildWorkflowRunEventParams(Long workflowDefinitionId,
                                                              LocalDateTime startTime,
                                                              LocalDateTime endTime) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("tenantId", securityService.currentTenantId());
        if (securityService.currentProjectId() != null) {
            params.addValue("projectId", securityService.currentProjectId());
        }
        if (workflowDefinitionId != null) {
            params.addValue("workflowDefinitionId", workflowDefinitionId);
        }
        if (startTime != null) {
            params.addValue("startTime", startTime);
        }
        if (endTime != null) {
            params.addValue("endTime", endTime);
        }
        return params;
    }

    private String buildWorkflowRunEventsSql(Long workflowDefinitionId,
                                             LocalDateTime startTime,
                                             LocalDateTime endTime) {
        String tenantId = securityService.currentTenantId();
        Long projectId = securityService.currentProjectId();
        StringBuilder sql = new StringBuilder();
        sql.append("select workflow_run_id, workflow_definition_id, coalesce(started_at, created_at) as occurred_at ");
        sql.append("from run_record where workflow_run_id is not null");
        appendWorkflowRunFilters(sql, workflowDefinitionId, startTime, endTime,
                tenantId, projectId, "tenant_id", "project_id", "workflow_definition_id", "coalesce(started_at, created_at)");
        sql.append(" union all ");
        sql.append("select workflow_run_id, workflow_definition_id, created_at as occurred_at ");
        sql.append("from dispatch_task where workflow_run_id is not null");
        appendWorkflowRunFilters(sql, workflowDefinitionId, startTime, endTime,
                tenantId, projectId, "tenant_id", "project_id", "workflow_definition_id", "created_at");
        return sql.toString();
    }

    private String buildWorkflowRunStatusCte(Long workflowDefinitionId,
                                             LocalDateTime startTime,
                                             LocalDateTime endTime) {
        StringBuilder sql = new StringBuilder();
        sql.append("with candidate_runs as (");
        sql.append("select workflow_run_id, max(occurred_at) as latest_at from (");
        sql.append(buildWorkflowRunEventsSql(workflowDefinitionId, startTime, endTime));
        sql.append(") workflow_events group by workflow_run_id");
        sql.append("), status_events as (");
        sql.append(buildWorkflowRunStatusEventsSql(workflowDefinitionId));
        sql.append("), ranked_node_status as (");
        sql.append("select e.workflow_run_id, e.node_code, e.node_status, ");
        sql.append("row_number() over (partition by e.workflow_run_id, e.node_code ");
        sql.append("order by e.source_priority asc, e.occurred_at desc, e.event_id desc) as rn ");
        sql.append("from status_events e join candidate_runs c on c.workflow_run_id = e.workflow_run_id");
        sql.append("), summarized_runs as (");
        sql.append("select c.workflow_run_id, c.latest_at, ");
        sql.append("case ");
        sql.append("when sum(case when r.rn = 1 and r.node_status = 'FAILED' then 1 else 0 end) > 0 then 'FAILED' ");
        sql.append("when sum(case when r.rn = 1 and r.node_status = 'RUNNING' then 1 else 0 end) > 0 then 'RUNNING' ");
        sql.append("when sum(case when r.rn = 1 and r.node_status = 'QUEUED' then 1 else 0 end) > 0 then 'QUEUED' ");
        sql.append("when sum(case when r.rn = 1 and r.node_status = 'SUCCESS' then 1 else 0 end) > 0 then 'SUCCESS' ");
        sql.append("else 'NOT_RUN' end as summary_status ");
        sql.append("from candidate_runs c ");
        sql.append("left join ranked_node_status r on r.workflow_run_id = c.workflow_run_id and r.rn = 1 ");
        sql.append("group by c.workflow_run_id, c.latest_at");
        sql.append(")");
        return sql.toString();
    }

    private String buildWorkflowRunStatusEventsSql(Long workflowDefinitionId) {
        String tenantId = securityService.currentTenantId();
        Long projectId = securityService.currentProjectId();
        StringBuilder sql = new StringBuilder();
        sql.append("select id as event_id, workflow_run_id, node_code, coalesce(upper(status), 'NOT_RUN') as node_status, ");
        sql.append("coalesce(started_at, created_at) as occurred_at, 1 as source_priority ");
        sql.append("from run_record where workflow_run_id is not null and node_code is not null");
        appendWorkflowRunFilters(sql, workflowDefinitionId, null, null,
                tenantId, projectId, "tenant_id", "project_id", "workflow_definition_id", "coalesce(started_at, created_at)");
        sql.append(" union all ");
        sql.append("select id as event_id, workflow_run_id, node_code, coalesce(upper(status), 'NOT_RUN') as node_status, ");
        sql.append("created_at as occurred_at, 2 as source_priority ");
        sql.append("from dispatch_task where workflow_run_id is not null and node_code is not null");
        appendWorkflowRunFilters(sql, workflowDefinitionId, null, null,
                tenantId, projectId, "tenant_id", "project_id", "workflow_definition_id", "created_at");
        return sql.toString();
    }

    private void appendWorkflowRunFilters(StringBuilder sql,
                                          Long workflowDefinitionId,
                                          LocalDateTime startTime,
                                          LocalDateTime endTime,
                                          String tenantId,
                                          Long projectId,
                                          String tenantColumn,
                                          String projectColumn,
                                          String workflowDefinitionColumn,
                                          String occurredAtExpression) {
        if (tenantId != null) {
            sql.append(" and ").append(tenantColumn).append(" = :tenantId");
        }
        if (projectId != null) {
            sql.append(" and ").append(projectColumn).append(" = :projectId");
        }
        if (workflowDefinitionId != null) {
            sql.append(" and ").append(workflowDefinitionColumn).append(" = :workflowDefinitionId");
        }
        if (startTime != null) {
            sql.append(" and ").append(occurredAtExpression).append(" >= :startTime");
        }
        if (endTime != null) {
            sql.append(" and ").append(occurredAtExpression).append(" <= :endTime");
        }
    }

    private void copySummary(WorkflowRunSummaryView source, WorkflowRunDetailView target) {
        target.setTenantId(source.getTenantId());
        target.setProjectId(source.getProjectId());
        target.setWorkflowRunId(source.getWorkflowRunId());
        target.setWorkflowDefinitionId(source.getWorkflowDefinitionId());
        target.setWorkflowVersionId(source.getWorkflowVersionId());
        target.setWorkflowName(source.getWorkflowName());
        target.setStatus(source.getStatus());
        target.setStartedAt(source.getStartedAt());
        target.setEndedAt(source.getEndedAt());
        target.setDurationMs(source.getDurationMs());
        target.setTotalNodes(source.getTotalNodes());
        target.setSuccessNodes(source.getSuccessNodes());
        target.setFailedNodes(source.getFailedNodes());
        target.setRunningNodes(source.getRunningNodes());
        target.setQueuedNodes(source.getQueuedNodes());
        target.setNotRunNodes(source.getNotRunNodes());
        target.setSummaryMessage(source.getSummaryMessage());
    }

    private WorkflowNodeRunView buildNodeRunView(WorkflowRunSummaryView summary,
                                                 String nodeCode,
                                                 String nodeName,
                                                 String nodeType,
                                                 RunRecordEntity record,
                                                 DispatchTaskEntity task) {
        return buildNodeRunView(summary.getWorkflowRunId(), summary.getWorkflowDefinitionId(), summary.getWorkflowName(),
                nodeCode, nodeName, nodeType, record, task);
    }

    private WorkflowNodeRunView buildNodeRunView(Long workflowRunId,
                                                 Long workflowDefinitionId,
                                                 String workflowName,
                                                 String nodeCode,
                                                 String nodeName,
                                                 String nodeType,
                                                 RunRecordEntity record,
                                                 DispatchTaskEntity task) {
        WorkflowNodeRunView nodeRun = new WorkflowNodeRunView();
        nodeRun.setWorkflowRunId(workflowRunId);
        nodeRun.setWorkflowDefinitionId(workflowDefinitionId);
        nodeRun.setWorkflowName(workflowName);
        nodeRun.setNodeCode(nodeCode);
        nodeRun.setNodeName(nodeName == null ? nodeCode : nodeName);
        nodeRun.setNodeType(nodeType);
        if (record != null) {
            nodeRun.setRunRecordId(record.getId());
            nodeRun.setStatus(record.getStatus());
            nodeRun.setWorkerGroupCode(record.getWorkerGroupCode());
            nodeRun.setWorkerCode(record.getWorkerCode());
            nodeRun.setWorkerInstanceId(record.getWorkerInstanceId());
            nodeRun.setMessage(RunRecordMessageSanitizer.sanitizeAndTruncateMessage(record.getMessage()));
            nodeRun.setStartedAt(record.getStartedAt());
            nodeRun.setEndedAt(record.getEndedAt());
            nodeRun.setDurationMs(resolveDuration(record.getStartedAt(), record.getEndedAt()));
            nodeRun.setLogAvailable(record.getLogFilePath() != null && !record.getLogFilePath().trim().isEmpty());
            return nodeRun;
        }
        if (task != null) {
            nodeRun.setStatus(task.getStatus() == null ? "NOT_RUN" : task.getStatus());
            nodeRun.setWorkerGroupCode(task.getWorkerGroupCode());
            nodeRun.setWorkerCode(task.getLeaseOwner());
            nodeRun.setWorkerInstanceId(task.getWorkerInstanceId());
            nodeRun.setMessage(resolveTaskMessage(task));
            nodeRun.setLogAvailable(false);
            return nodeRun;
        }
        nodeRun.setStatus("NOT_RUN");
        nodeRun.setLogAvailable(false);
        return nodeRun;
    }

    private String resolveTaskMessage(DispatchTaskEntity task) {
        if (task.getStatus() == null) {
            return "Not started";
        }
        if ("QUEUED".equalsIgnoreCase(task.getStatus())) {
            return "Queued and waiting for worker lease";
        }
        if ("RUNNING".equalsIgnoreCase(task.getStatus())) {
            return "Node is running";
        }
        return task.getStatus();
    }

    private Map<String, RunRecordEntity> latestRecordByNode(List<RunRecordEntity> records) {
        Map<String, RunRecordEntity> result = new LinkedHashMap<String, RunRecordEntity>();
        for (RunRecordEntity record : records) {
            if (record.getNodeCode() == null) {
                continue;
            }
            RunRecordEntity current = result.get(record.getNodeCode());
            if (current == null || isRecordAfter(record, current)) {
                result.put(record.getNodeCode(), record);
            }
        }
        return result;
    }

    private Map<String, DispatchTaskEntity> latestTaskByNode(List<DispatchTaskEntity> tasks) {
        Map<String, DispatchTaskEntity> result = new LinkedHashMap<String, DispatchTaskEntity>();
        for (DispatchTaskEntity task : tasks) {
            if (task.getNodeCode() == null) {
                continue;
            }
            DispatchTaskEntity current = result.get(task.getNodeCode());
            if (current == null || isTaskAfter(task, current)) {
                result.put(task.getNodeCode(), task);
            }
        }
        return result;
    }

    private boolean isRecordAfter(RunRecordEntity candidate, RunRecordEntity current) {
        LocalDateTime candidateTime = candidate.getStartedAt() != null ? candidate.getStartedAt() : candidate.getCreatedAt();
        LocalDateTime currentTime = current.getStartedAt() != null ? current.getStartedAt() : current.getCreatedAt();
        if (candidateTime == null) {
            return false;
        }
        if (currentTime == null) {
            return true;
        }
        return candidateTime.isAfter(currentTime);
    }

    private boolean isTaskAfter(DispatchTaskEntity candidate, DispatchTaskEntity current) {
        if (candidate.getCreatedAt() == null) {
            return false;
        }
        if (current.getCreatedAt() == null) {
            return true;
        }
        return candidate.getCreatedAt().isAfter(current.getCreatedAt());
    }

    private Long firstNonNullWorkflowDefinitionId(List<RunRecordEntity> records, List<DispatchTaskEntity> tasks) {
        for (RunRecordEntity record : records) {
            if (record.getWorkflowDefinitionId() != null) {
                return record.getWorkflowDefinitionId();
            }
        }
        for (DispatchTaskEntity task : tasks) {
            if (task.getWorkflowDefinitionId() != null) {
                return task.getWorkflowDefinitionId();
            }
        }
        return null;
    }

    private Long firstNonNullWorkflowVersionId(List<RunRecordEntity> records, List<DispatchTaskEntity> tasks) {
        for (RunRecordEntity record : records) {
            if (record.getWorkflowVersionId() != null) {
                return record.getWorkflowVersionId();
            }
        }
        for (DispatchTaskEntity task : tasks) {
            if (task.getWorkflowVersionId() != null) {
                return task.getWorkflowVersionId();
            }
        }
        return null;
    }

    private int countByStatus(List<WorkflowNodeRunView> nodeRuns, String status) {
        int count = 0;
        for (WorkflowNodeRunView nodeRun : nodeRuns) {
            if (status.equalsIgnoreCase(String.valueOf(nodeRun.getStatus()))) {
                count++;
            }
        }
        return count;
    }

    private String resolveSummaryStatus(List<WorkflowNodeRunView> nodeRuns) {
        if (countByStatus(nodeRuns, "FAILED") > 0) {
            return "FAILED";
        }
        if (countByStatus(nodeRuns, "RUNNING") > 0) {
            return "RUNNING";
        }
        if (countByStatus(nodeRuns, "QUEUED") > 0) {
            return "QUEUED";
        }
        if (countByStatus(nodeRuns, "SUCCESS") > 0) {
            return "SUCCESS";
        }
        return "NOT_RUN";
    }

    private LocalDateTime resolveStartedAt(List<WorkflowNodeRunView> nodeRuns) {
        LocalDateTime result = null;
        for (WorkflowNodeRunView nodeRun : nodeRuns) {
            if (nodeRun.getStartedAt() == null) {
                continue;
            }
            if (result == null || nodeRun.getStartedAt().isBefore(result)) {
                result = nodeRun.getStartedAt();
            }
        }
        return result;
    }

    private LocalDateTime resolveEndedAt(List<WorkflowNodeRunView> nodeRuns) {
        LocalDateTime result = null;
        for (WorkflowNodeRunView nodeRun : nodeRuns) {
            if (nodeRun.getEndedAt() == null) {
                continue;
            }
            if (result == null || nodeRun.getEndedAt().isAfter(result)) {
                result = nodeRun.getEndedAt();
            }
        }
        return result;
    }

    private Long resolveDuration(LocalDateTime startedAt, LocalDateTime endedAt) {
        if (startedAt == null || endedAt == null) {
            return null;
        }
        return Duration.between(startedAt, endedAt).toMillis();
    }

    private String resolveSummaryMessage(WorkflowRunSummaryView summary) {
        if (summary.getFailedNodes() != null && summary.getFailedNodes() > 0) {
            return summary.getFailedNodes() + " node(s) failed";
        }
        if (summary.getRunningNodes() != null && summary.getRunningNodes() > 0) {
            return summary.getRunningNodes() + " node(s) still running";
        }
        if (summary.getQueuedNodes() != null && summary.getQueuedNodes() > 0) {
            return summary.getQueuedNodes() + " node(s) queued";
        }
        if (summary.getSuccessNodes() != null && summary.getSuccessNodes() > 0) {
            return summary.getSuccessNodes() + "/" + summary.getTotalNodes() + " node(s) completed";
        }
        return "No node execution records";
    }

    private String extractNodeType(RunRecordEntity record) {
        if (record == null || record.getPayloadJson() == null) {
            return null;
        }
        Object nodeType = record.getPayloadJson().get("nodeType");
        return nodeType == null ? null : String.valueOf(nodeType);
    }

    private String extractNodeType(DispatchTaskEntity task) {
        if (task == null || task.getPayloadJson() == null) {
            return null;
        }
        Object nodeType = task.getPayloadJson().get("nodeType");
        return nodeType == null ? null : String.valueOf(nodeType);
    }

    private static class WorkflowNodeMetadata {
        private final String nodeCode;
        private final String nodeName;
        private final String nodeType;

        private WorkflowNodeMetadata(String nodeCode, String nodeName, String nodeType) {
            this.nodeCode = nodeCode;
            this.nodeName = nodeName;
            this.nodeType = nodeType;
        }
    }

    private static class WorkflowEdgeMetadata {
        private final String fromNodeCode;
        private final String toNodeCode;
        private final String conditionType;

        private WorkflowEdgeMetadata(String fromNodeCode, String toNodeCode, String conditionType) {
            this.fromNodeCode = fromNodeCode;
            this.toNodeCode = toNodeCode;
            this.conditionType = conditionType;
        }
    }
}
