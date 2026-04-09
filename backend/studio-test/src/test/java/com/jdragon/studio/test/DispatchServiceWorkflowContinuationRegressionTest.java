package com.jdragon.studio.test;

import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.enums.EdgeCondition;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowEdgeDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.infra.service.WorkflowService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatchServiceWorkflowContinuationRegressionTest {

    @Test
    void shouldQueueDirectDownstreamNodeAfterSuccessfulPredecessor() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkflowService workflowService = mock(WorkflowService.class);

        WorkflowDefinitionView workflow = workflow(10L, 101L,
                node("A"), node("B"),
                edge("A", "B", EdgeCondition.ON_SUCCESS));

        when(workflowService.get(eq(10L))).thenReturn(workflow);
        when(dispatchTaskMapper.selectCount(any())).thenReturn(0L);
        when(runRecordMapper.selectCount(any())).thenReturn(0L);
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task(1000L, "A", "SUCCESS")));
        when(runRecordMapper.selectList(any())).thenReturn(Collections.singletonList(record(1000L, "A", "SUCCESS")));

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                workflowService,
                mock(CollectionTaskService.class),
                mock(StudioSecurityService.class),
                mock(WorkerAuthorizationService.class)
        );

        dispatchService.continueWorkflowRun(successEvent(1000L, 10L, "A"));

        verify(dispatchTaskMapper).insert(argThat((DispatchTaskEntity task) ->
                "B".equals(task.getNodeCode())
                        && Long.valueOf(1000L).equals(task.getWorkflowRunId())
                        && "QUEUED".equals(task.getStatus())));
    }

    @Test
    void shouldWaitForAllActivatedInboundNodesBeforeQueueingJoinNode() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkflowService workflowService = mock(WorkflowService.class);

        WorkflowDefinitionView workflow = workflow(10L, 101L,
                node("A"), node("B"), node("C"),
                edge("A", "C", EdgeCondition.ON_SUCCESS),
                edge("B", "C", EdgeCondition.ON_SUCCESS));

        when(workflowService.get(eq(10L))).thenReturn(workflow);
        when(dispatchTaskMapper.selectCount(any())).thenReturn(0L);
        when(runRecordMapper.selectCount(any())).thenReturn(0L);
        when(dispatchTaskMapper.selectList(any())).thenReturn(Arrays.asList(
                task(2000L, "A", "SUCCESS"),
                task(2000L, "B", "RUNNING")));
        when(runRecordMapper.selectList(any())).thenReturn(Collections.singletonList(record(2000L, "A", "SUCCESS")));

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                workflowService,
                mock(CollectionTaskService.class),
                mock(StudioSecurityService.class),
                mock(WorkerAuthorizationService.class)
        );

        dispatchService.continueWorkflowRun(successEvent(2000L, 10L, "A"));

        verify(dispatchTaskMapper, never()).insert(org.mockito.ArgumentMatchers.<DispatchTaskEntity>any());
    }

    @Test
    void shouldIgnoreInactiveBranchThatWasNeverActivated() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkflowService workflowService = mock(WorkflowService.class);

        WorkflowDefinitionView workflow = workflow(10L, 101L,
                node("A"), node("B"), node("C"), node("D"),
                edge("A", "B", EdgeCondition.ON_SUCCESS),
                edge("A", "C", EdgeCondition.ON_FAILURE),
                edge("B", "D", EdgeCondition.ON_SUCCESS),
                edge("C", "D", EdgeCondition.ON_SUCCESS));

        when(workflowService.get(eq(10L))).thenReturn(workflow);
        when(dispatchTaskMapper.selectCount(any())).thenReturn(0L);
        when(runRecordMapper.selectCount(any())).thenReturn(0L);
        when(dispatchTaskMapper.selectList(any())).thenReturn(Arrays.asList(
                task(3000L, "A", "SUCCESS"),
                task(3000L, "B", "SUCCESS")));
        when(runRecordMapper.selectList(any())).thenReturn(Arrays.asList(
                record(3000L, "B", "SUCCESS"),
                record(3000L, "A", "SUCCESS")));

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                workflowService,
                mock(CollectionTaskService.class),
                mock(StudioSecurityService.class),
                mock(WorkerAuthorizationService.class)
        );

        dispatchService.continueWorkflowRun(successEvent(3000L, 10L, "B"));

        verify(dispatchTaskMapper).insert(argThat((DispatchTaskEntity task) ->
                "D".equals(task.getNodeCode())
                        && Long.valueOf(3000L).equals(task.getWorkflowRunId())));
    }

    private WorkflowDefinitionView workflow(Long id,
                                            Long versionId,
                                            WorkflowNodeDefinition firstNode,
                                            WorkflowNodeDefinition secondNode,
                                            WorkflowEdgeDefinition firstEdge) {
        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(id);
        workflow.setVersionId(versionId);
        workflow.setNodes(Arrays.asList(firstNode, secondNode));
        workflow.setEdges(Collections.singletonList(firstEdge));
        return workflow;
    }

    private WorkflowDefinitionView workflow(Long id,
                                            Long versionId,
                                            WorkflowNodeDefinition firstNode,
                                            WorkflowNodeDefinition secondNode,
                                            WorkflowNodeDefinition thirdNode,
                                            WorkflowEdgeDefinition firstEdge,
                                            WorkflowEdgeDefinition secondEdge) {
        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(id);
        workflow.setVersionId(versionId);
        workflow.setNodes(Arrays.asList(firstNode, secondNode, thirdNode));
        workflow.setEdges(Arrays.asList(firstEdge, secondEdge));
        return workflow;
    }

    private WorkflowDefinitionView workflow(Long id,
                                            Long versionId,
                                            WorkflowNodeDefinition firstNode,
                                            WorkflowNodeDefinition secondNode,
                                            WorkflowNodeDefinition thirdNode,
                                            WorkflowNodeDefinition fourthNode,
                                            WorkflowEdgeDefinition firstEdge,
                                            WorkflowEdgeDefinition secondEdge,
                                            WorkflowEdgeDefinition thirdEdge,
                                            WorkflowEdgeDefinition fourthEdge) {
        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(id);
        workflow.setVersionId(versionId);
        List<WorkflowNodeDefinition> nodes = new ArrayList<WorkflowNodeDefinition>();
        nodes.add(firstNode);
        nodes.add(secondNode);
        nodes.add(thirdNode);
        nodes.add(fourthNode);
        workflow.setNodes(nodes);
        workflow.setEdges(Arrays.asList(firstEdge, secondEdge, thirdEdge, fourthEdge));
        return workflow;
    }

    private WorkflowNodeDefinition node(String nodeCode) {
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeCode(nodeCode);
        node.setNodeName(nodeCode);
        node.setNodeType(NodeType.COLLECTION_TASK);
        return node;
    }

    private WorkflowEdgeDefinition edge(String fromNodeCode, String toNodeCode, EdgeCondition condition) {
        WorkflowEdgeDefinition edge = new WorkflowEdgeDefinition();
        edge.setFromNodeCode(fromNodeCode);
        edge.setToNodeCode(toNodeCode);
        edge.setCondition(condition);
        return edge;
    }

    private DispatchTaskEntity task(Long workflowRunId, String nodeCode, String status) {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setWorkflowRunId(workflowRunId);
        task.setNodeCode(nodeCode);
        task.setStatus(status);
        task.setCreatedAt(LocalDateTime.now());
        return task;
    }

    private RunRecordEntity record(Long workflowRunId, String nodeCode, String status) {
        RunRecordEntity record = new RunRecordEntity();
        record.setWorkflowRunId(workflowRunId);
        record.setNodeCode(nodeCode);
        record.setStatus(status);
        record.setStartedAt(LocalDateTime.now());
        return record;
    }

    private ExecutionEvent successEvent(Long workflowRunId, Long workflowDefinitionId, String nodeCode) {
        ExecutionEvent event = new ExecutionEvent();
        event.setExecutionType(DispatchExecutionType.WORKFLOW_NODE);
        event.setEventType("SUCCESS");
        event.setWorkflowRunId(workflowRunId);
        event.setWorkflowDefinitionId(workflowDefinitionId);
        event.setNodeCode(nodeCode);
        return event;
    }
}
