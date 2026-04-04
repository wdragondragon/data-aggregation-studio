package com.jdragon.studio.test;

import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import com.jdragon.studio.worker.runtime.runner.WorkerLifecycleRunner;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerLifecycleRunnerRegressionTest {

    @Test
    void shouldResolveCollectionTaskIdFromNodeConfigWhenWorkflowPayloadStoresStringId() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        CollectionTaskAssemblerService assemblerService = mock(CollectionTaskAssemblerService.class);

        CollectionTaskDefinitionView onlineTask = new CollectionTaskDefinitionView();
        onlineTask.setId(2040396020474507266L);
        onlineTask.setName("test");

        Map<String, Object> assembledConfig = new LinkedHashMap<String, Object>();
        assembledConfig.put("reader", Collections.singletonMap("type", "mysql8"));

        when(collectionTaskService.requireOnline(eq(2040396020474507266L))).thenReturn(onlineTask);
        when(assemblerService.assemble(eq(onlineTask))).thenReturn(assembledConfig);

        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                mock(com.jdragon.studio.infra.mapper.DispatchTaskMapper.class),
                mock(com.jdragon.studio.infra.mapper.WorkerLeaseMapper.class),
                mock(com.jdragon.studio.infra.mapper.RunRecordMapper.class),
                Collections.emptyList(),
                mock(com.jdragon.studio.core.spi.ExecutionEventPublisher.class),
                new StudioPlatformProperties(),
                collectionTaskService,
                assemblerService,
                mock(RunLogFileService.class)
        );

        DispatchTaskEntity dispatchTask = new DispatchTaskEntity();
        dispatchTask.setNodeCode("collection_task_1775321775573");
        dispatchTask.setExecutionType("WORKFLOW_NODE");

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("collectionTaskId", "2040396020474507266");
        config.put("collectionTaskName", "test");
        config.put("collectionTaskType", "SINGLE_TABLE");

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("nodeType", NodeType.COLLECTION_TASK.name());
        payload.put("config", config);
        dispatchTask.setPayloadJson(payload);

        WorkflowNodeDefinition node = (WorkflowNodeDefinition) ReflectionTestUtils.invokeMethod(runner, "toNode", dispatchTask);

        assertNotNull(node);
        assertEquals("test", node.getNodeName());
        assertEquals(assembledConfig, node.getConfig());
    }

    @Test
    void shouldRecoverInterruptedRunningTasksOwnedByCurrentWorkerOnStartup() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        ExecutionEventPublisher executionEventPublisher = mock(ExecutionEventPublisher.class);
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        CollectionTaskAssemblerService assemblerService = mock(CollectionTaskAssemblerService.class);
        RunLogFileService runLogFileService = mock(RunLogFileService.class);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setWorkerCode("studio-online-worker-01");

        DispatchTaskEntity staleTask = new DispatchTaskEntity();
        staleTask.setId(1L);
        staleTask.setStatus("RUNNING");
        staleTask.setLeaseOwner("studio-online-worker-01");
        staleTask.setRunRecordId(2L);
        staleTask.setWorkflowRunId(3L);
        staleTask.setWorkflowDefinitionId(4L);
        staleTask.setWorkflowVersionId(5L);
        staleTask.setExecutionType("WORKFLOW_NODE");
        staleTask.setNodeCode("collection_task_1");
        staleTask.setCreatedAt(LocalDateTime.of(2026, 4, 5, 7, 0, 0));
        staleTask.setPayloadJson(new LinkedHashMap<String, Object>());

        RunRecordEntity staleRunRecord = new RunRecordEntity();
        staleRunRecord.setId(2L);
        staleRunRecord.setWorkflowRunId(3L);
        staleRunRecord.setWorkflowDefinitionId(4L);
        staleRunRecord.setWorkflowVersionId(5L);
        staleRunRecord.setExecutionType("WORKFLOW_NODE");
        staleRunRecord.setNodeCode("collection_task_1");
        staleRunRecord.setWorkerCode("studio-online-worker-01");
        staleRunRecord.setStatus("RUNNING");
        staleRunRecord.setStartedAt(LocalDateTime.of(2026, 4, 5, 7, 0, 1));
        staleRunRecord.setLogFilePath("2026-04-05/run-2.log");
        staleRunRecord.setLogCharset("UTF-8");

        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(staleTask));
        when(runRecordMapper.selectById(eq(2L))).thenReturn(staleRunRecord);
        when(runLogFileService.fileSize(eq("2026-04-05/run-2.log"))).thenReturn(158L);
        doNothing().when(executionEventPublisher).publish(any(ExecutionEvent.class));

        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                dispatchTaskMapper,
                workerLeaseMapper,
                runRecordMapper,
                Collections.emptyList(),
                executionEventPublisher,
                properties,
                collectionTaskService,
                assemblerService,
                runLogFileService
        );

        runner.recoverLeasedRunningTasks();

        assertEquals("FAILED", staleTask.getStatus());
        assertEquals(Boolean.TRUE, staleTask.getPayloadJson().get("recovered"));
        verify(dispatchTaskMapper).updateById(eq(staleTask));
        verify(executionEventPublisher).publish(any(ExecutionEvent.class));
    }
}
