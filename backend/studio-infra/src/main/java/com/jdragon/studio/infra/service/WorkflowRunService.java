package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeRunView;
import com.jdragon.studio.dto.model.WorkflowRunDetailView;
import com.jdragon.studio.dto.model.WorkflowRunSummaryView;
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
    private final WorkflowService workflowService;

    public WorkflowRunService(RunRecordMapper runRecordMapper,
                              DispatchTaskMapper dispatchTaskMapper,
                              WorkflowDefinitionMapper workflowDefinitionMapper,
                              WorkflowService workflowService) {
        this.runRecordMapper = runRecordMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.workflowService = workflowService;
    }

    public List<WorkflowRunSummaryView> list(Long workflowDefinitionId,
                                             LocalDateTime startTime,
                                             LocalDateTime endTime) {
        List<RunRecordEntity> records = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                .isNotNull(RunRecordEntity::getWorkflowRunId)
                .eq(workflowDefinitionId != null, RunRecordEntity::getWorkflowDefinitionId, workflowDefinitionId)
                .orderByDesc(RunRecordEntity::getCreatedAt));
        List<DispatchTaskEntity> tasks = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .isNotNull(DispatchTaskEntity::getWorkflowRunId)
                .eq(workflowDefinitionId != null, DispatchTaskEntity::getWorkflowDefinitionId, workflowDefinitionId)
                .orderByDesc(DispatchTaskEntity::getCreatedAt));

        Map<Long, List<RunRecordEntity>> recordsByRun = groupRunRecords(records, startTime, endTime);
        Map<Long, List<DispatchTaskEntity>> tasksByRun = groupDispatchTasks(tasks, startTime, endTime);
        Set<Long> workflowRunIds = new LinkedHashSet<Long>();
        workflowRunIds.addAll(recordsByRun.keySet());
        workflowRunIds.addAll(tasksByRun.keySet());

        Map<Long, String> workflowNames = workflowNames();
        List<WorkflowRunSummaryView> result = new ArrayList<WorkflowRunSummaryView>();
        for (Long workflowRunId : workflowRunIds) {
            result.add(buildSummary(workflowRunId, recordsByRun.get(workflowRunId), tasksByRun.get(workflowRunId), workflowNames));
        }
        result.sort(new Comparator<WorkflowRunSummaryView>() {
            @Override
            public int compare(WorkflowRunSummaryView left, WorkflowRunSummaryView right) {
                LocalDateTime leftTime = left.getStartedAt() != null ? left.getStartedAt() : left.getEndedAt();
                LocalDateTime rightTime = right.getStartedAt() != null ? right.getStartedAt() : right.getEndedAt();
                if (leftTime == null && rightTime == null) {
                    return String.valueOf(right.getWorkflowRunId()).compareTo(String.valueOf(left.getWorkflowRunId()));
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
        return result;
    }

    public WorkflowRunDetailView get(Long workflowRunId) {
        List<RunRecordEntity> records = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getWorkflowRunId, workflowRunId)
                .orderByDesc(RunRecordEntity::getCreatedAt));
        List<DispatchTaskEntity> tasks = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getWorkflowRunId, workflowRunId)
                .orderByDesc(DispatchTaskEntity::getCreatedAt));
        if (records.isEmpty() && tasks.isEmpty()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow run not found: " + workflowRunId);
        }

        Map<Long, String> workflowNames = workflowNames();
        WorkflowRunDetailView detail = new WorkflowRunDetailView();
        WorkflowRunSummaryView summary = buildSummary(workflowRunId, records, tasks, workflowNames);
        copySummary(summary, detail);

        Long workflowDefinitionId = summary.getWorkflowDefinitionId();
        WorkflowDefinitionView workflow = workflowDefinitionId == null ? null : workflowService.get(workflowDefinitionId);
        detail.setWorkflow(workflow);

        Map<String, RunRecordEntity> latestRecordByNode = latestRecordByNode(records);
        Map<String, DispatchTaskEntity> latestTaskByNode = latestTaskByNode(tasks);
        Set<String> consumedNodeCodes = new LinkedHashSet<String>();
        List<WorkflowNodeRunView> nodeRuns = new ArrayList<WorkflowNodeRunView>();

        if (workflow != null) {
            for (WorkflowNodeDefinition node : workflow.getNodes()) {
                WorkflowNodeRunView nodeRun = buildNodeRunView(summary, node.getNodeCode(), node.getNodeName(),
                        node.getNodeType() == null ? null : node.getNodeType().name(),
                        latestRecordByNode.get(node.getNodeCode()),
                        latestTaskByNode.get(node.getNodeCode()));
                nodeRuns.add(nodeRun);
                consumedNodeCodes.add(node.getNodeCode());
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

    private Map<Long, String> workflowNames() {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .orderByAsc(WorkflowDefinitionEntity::getCode));
        for (WorkflowDefinitionEntity definition : definitions) {
            if (definition.getId() != null) {
                result.put(definition.getId(), definition.getName());
            }
        }
        return result;
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
                                                Map<Long, String> workflowNames) {
        List<RunRecordEntity> safeRecords = records == null ? new ArrayList<RunRecordEntity>() : records;
        List<DispatchTaskEntity> safeTasks = tasks == null ? new ArrayList<DispatchTaskEntity>() : tasks;

        Long workflowDefinitionId = firstNonNullWorkflowDefinitionId(safeRecords, safeTasks);
        WorkflowDefinitionView workflow = workflowDefinitionId == null ? null : workflowService.get(workflowDefinitionId);
        Map<String, RunRecordEntity> latestRecordByNode = latestRecordByNode(safeRecords);
        Map<String, DispatchTaskEntity> latestTaskByNode = latestTaskByNode(safeTasks);
        List<WorkflowNodeRunView> nodeRuns = new ArrayList<WorkflowNodeRunView>();
        Set<String> consumedNodeCodes = new LinkedHashSet<String>();

        if (workflow != null) {
            for (WorkflowNodeDefinition node : workflow.getNodes()) {
                nodeRuns.add(buildNodeRunView(workflowRunId, workflowDefinitionId, workflowNames.get(workflowDefinitionId),
                        node.getNodeCode(), node.getNodeName(),
                        node.getNodeType() == null ? null : node.getNodeType().name(),
                        latestRecordByNode.get(node.getNodeCode()),
                        latestTaskByNode.get(node.getNodeCode())));
                consumedNodeCodes.add(node.getNodeCode());
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
        summary.setWorkflowRunId(workflowRunId);
        summary.setWorkflowDefinitionId(workflowDefinitionId);
        summary.setWorkflowVersionId(firstNonNullWorkflowVersionId(safeRecords, safeTasks));
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

    private void copySummary(WorkflowRunSummaryView source, WorkflowRunDetailView target) {
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
            nodeRun.setWorkerCode(record.getWorkerCode());
            nodeRun.setMessage(record.getMessage());
            nodeRun.setStartedAt(record.getStartedAt());
            nodeRun.setEndedAt(record.getEndedAt());
            nodeRun.setDurationMs(resolveDuration(record.getStartedAt(), record.getEndedAt()));
            nodeRun.setLogAvailable(record.getLogFilePath() != null && !record.getLogFilePath().trim().isEmpty());
            return nodeRun;
        }
        if (task != null) {
            nodeRun.setStatus(task.getStatus() == null ? "NOT_RUN" : task.getStatus());
            nodeRun.setWorkerCode(task.getLeaseOwner());
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
}
