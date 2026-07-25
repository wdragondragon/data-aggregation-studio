package com.jdragon.studio.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchProtectedPayloadService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.RuntimeResourceRevisionService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.worker.runtime.WorkflowDispatchNodeResolver;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import com.jdragon.studio.worker.runtime.runner.WorkerLifecycleRunner;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerResourceRevisionGuardTest {

    @Test
    void shouldRejectQueuedCollectionTaskWhenCompositeRevisionChanged() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        RuntimeResourceRevisionService revisionService = mock(RuntimeResourceRevisionService.class);
        WorkerLifecycleRunner runner = runner(collectionTaskService, mock(QualityTaskService.class), revisionService);
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setId(101L);
        definition.setName("customer collection");
        when(collectionTaskService.requireOnlineForExecution(101L)).thenReturn(definition);
        when(revisionService.collectionTaskRevision(101L)).thenReturn("current-composite-revision");

        DispatchTaskEntity queuedTask = queuedTask(NodeType.COLLECTION_TASK, "collectionTaskId", 101L);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(runner, "toNode", queuedTask))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Resource configuration changed after dispatch");
    }

    @Test
    void shouldRejectQueuedQualityTaskWhenCompositeRevisionChanged() {
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        RuntimeResourceRevisionService revisionService = mock(RuntimeResourceRevisionService.class);
        WorkerLifecycleRunner runner = runner(mock(CollectionTaskService.class), qualityTaskService, revisionService);
        QualityTaskDefinitionView definition = new QualityTaskDefinitionView();
        definition.setId(102L);
        definition.setTaskName("customer quality");
        when(qualityTaskService.requireOnlineForExecution(102L)).thenReturn(definition);
        when(revisionService.qualityTaskRevision(102L)).thenReturn("current-composite-revision");

        DispatchTaskEntity queuedTask = queuedTask(NodeType.QUALITY_TASK, "qualityTaskId", 102L);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(runner, "toNode", queuedTask))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Resource configuration changed after dispatch");
    }

    @Test
    void shouldResolveQueuedResourceInsideTaskTenantAndProjectContext() {
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        RuntimeResourceRevisionService revisionService = mock(RuntimeResourceRevisionService.class);
        NodeExecutor executor = mock(NodeExecutor.class);
        WorkerLifecycleRunner runner = runner(mock(CollectionTaskService.class), qualityTaskService,
                revisionService, Collections.singletonList(executor));
        QualityTaskDefinitionView definition = new QualityTaskDefinitionView();
        definition.setId(102L);
        definition.setTaskName("customer quality");
        when(qualityTaskService.requireOnlineForExecution(102L)).thenAnswer(invocation -> {
            assertEquals("tenant-b", StudioRequestContextHolder.getContext().getTenantId());
            assertEquals(202L, StudioRequestContextHolder.getContext().getProjectId());
            return definition;
        });
        when(revisionService.qualityTaskRevision(102L)).thenReturn("queued-composite-revision");
        when(executor.supports(any())).thenReturn(true);
        when(executor.execute(any(), any())).thenReturn(new LinkedHashMap<String, Object>());
        DispatchTaskEntity queuedTask = queuedTask(NodeType.QUALITY_TASK, "qualityTaskId", 102L);
        queuedTask.setTenantId("tenant-b");
        queuedTask.setProjectId(202L);

        ReflectionTestUtils.invokeMethod(runner, "executeWithTaskContext", queuedTask,
                new LinkedHashMap<String, Object>());

        assertNull(StudioRequestContextHolder.getContext());
    }

    @Test
    void shouldBuildDirectScriptNodeWithoutWorkflowSnapshotLookup() {
        NodeExecutor executor = mock(NodeExecutor.class);
        when(executor.supports(any())).thenReturn(true);
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            com.jdragon.studio.dto.model.WorkflowNodeDefinition node = invocation.getArgument(0);
            assertEquals("print('protected')", node.getConfig().get("content"));
            assertEquals("secret-token", ((Map<?, ?>) node.getConfig().get("arguments")).get("accessToken"));
            return new LinkedHashMap<String, Object>();
        });
        WorkerLifecycleRunner runner = runner(mock(CollectionTaskService.class), mock(QualityTaskService.class),
                mock(RuntimeResourceRevisionService.class), Collections.singletonList(executor));
        WorkflowDispatchNodeResolver resolver = (WorkflowDispatchNodeResolver) ReflectionTestUtils.getField(
                runner, "workflowDispatchNodeResolver");
        StudioPlatformProperties encryptionProperties = new StudioPlatformProperties();
        encryptionProperties.setEncryptionSecret("worker-protected-payload-test-secret");
        DispatchProtectedPayloadService protectedPayloadService = new DispatchProtectedPayloadService(
                new EncryptionService(encryptionProperties), new ObjectMapper());
        ReflectionTestUtils.invokeMethod(runner, "setDispatchProtectedPayloadService", protectedPayloadService);
        DispatchTaskMapper dispatchTaskMapper = (DispatchTaskMapper) ReflectionTestUtils.getField(
                runner, "dispatchTaskMapper");
        when(dispatchTaskMapper.update(any(DispatchTaskEntity.class), any())).thenReturn(1);
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(1001L);
        task.setTenantId("tenant-a");
        task.setProjectId(10L);
        task.setExecutionType("DATA_SCRIPT_TEST");
        task.setNodeCode("data_script_test_101");
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("scriptId", 101L);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("nodeType", NodeType.DATA_SCRIPT.name());
        payload.put("config", config);
        task.setPayloadJson(payload);
        Map<String, Object> protectedConfig = new LinkedHashMap<String, Object>();
        protectedConfig.put("content", "print('protected')");
        protectedConfig.put("arguments", Map.of("accessToken", "secret-token"));
        task.setProtectedPayloadCiphertext(protectedPayloadService.protect(protectedConfig));

        ReflectionTestUtils.invokeMethod(runner, "executeWithTaskContext", task,
                new LinkedHashMap<String, Object>());

        assertNull(task.getProtectedPayloadCiphertext());
        verify(resolver, never()).resolve(any());
        verify(dispatchTaskMapper).update(any(DispatchTaskEntity.class), any());
    }

    private WorkerLifecycleRunner runner(CollectionTaskService collectionTaskService,
                                         QualityTaskService qualityTaskService,
                                         RuntimeResourceRevisionService revisionService) {
        return runner(collectionTaskService, qualityTaskService, revisionService,
                Collections.<NodeExecutor>emptyList());
    }

    private WorkerLifecycleRunner runner(CollectionTaskService collectionTaskService,
                                         QualityTaskService qualityTaskService,
                                         RuntimeResourceRevisionService revisionService,
                                         java.util.List<NodeExecutor> executors) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setWorkerCode("worker-50");
        WorkflowDispatchNodeResolver nodeResolver = mock(WorkflowDispatchNodeResolver.class);
        when(nodeResolver.resolve(any())).thenAnswer(invocation -> {
            DispatchTaskEntity task = invocation.getArgument(0);
            com.jdragon.studio.dto.model.WorkflowNodeDefinition node =
                    new com.jdragon.studio.dto.model.WorkflowNodeDefinition();
            node.setNodeCode(task.getNodeCode());
            node.setNodeType(task.getCollectionTaskId() == null
                    ? NodeType.QUALITY_TASK : NodeType.COLLECTION_TASK);
            return node;
        });
        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                mock(DispatchTaskMapper.class),
                mock(WorkerLeaseMapper.class),
                mock(RunRecordMapper.class),
                executors,
                mock(ExecutionEventPublisher.class),
                properties,
                collectionTaskService,
                qualityTaskService,
                mock(CollectionTaskAssemblerService.class),
                mock(RunLogFileService.class),
                mock(WorkerAuthorizationService.class),
                new ClusterInstanceIdentity(properties),
                nodeResolver);
        ReflectionTestUtils.invokeMethod(runner, "setRuntimeResourceRevisionService", revisionService);
        return runner;
    }

    private DispatchTaskEntity queuedTask(NodeType nodeType, String idKey, Long resourceId) {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(1001L);
        task.setNodeCode("queued-resource");
        task.setResourceRevision("queued-composite-revision");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("nodeType", nodeType.name());
        if (nodeType == NodeType.COLLECTION_TASK) {
            task.setExecutionType("COLLECTION_TASK");
            task.setCollectionTaskId(resourceId);
        } else {
            task.setExecutionType("QUALITY_TASK");
            task.setQualityTaskId(resourceId);
        }
        task.setPayloadJson(payload);
        return task;
    }
}
