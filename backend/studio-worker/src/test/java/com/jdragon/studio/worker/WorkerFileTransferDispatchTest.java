package com.jdragon.studio.worker;

import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.worker.runtime.WorkflowDispatchNodeResolver;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import com.jdragon.studio.worker.runtime.runner.WorkerLifecycleRunner;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class WorkerFileTransferDispatchTest {

    @Test
    void injectsFileTransferRunIdentityIntoImmutableWorkflowSnapshot() {
        WorkflowDispatchNodeResolver resolver = mock(WorkflowDispatchNodeResolver.class);
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeCode("transfer-files");
        node.setNodeType(NodeType.FILE_TRANSFER);
        node.setConfig(new LinkedHashMap<String, Object>(Map.of("fileTransferTaskId", 700L)));
        doReturn(node).when(resolver).resolve(any());
        WorkerLifecycleRunner runner = runner(resolver);
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setExecutionType("WORKFLOW_NODE");
        task.setNodeCode("transfer-files");
        task.setFileTransferTaskId(700L);
        task.setFileTransferRunId(900L);

        WorkflowNodeDefinition resolved = ReflectionTestUtils.invokeMethod(runner, "toNode", task);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getConfig())
                .containsEntry("fileTransferTaskId", 700L)
                .containsEntry("fileTransferRunId", 900L);
    }

    @Test
    void rejectsFileTransferDispatchWithoutRunIdentity() {
        WorkflowDispatchNodeResolver resolver = mock(WorkflowDispatchNodeResolver.class);
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeCode("transfer-files");
        node.setNodeType(NodeType.FILE_TRANSFER);
        node.setConfig(new LinkedHashMap<String, Object>());
        doReturn(node).when(resolver).resolve(any());
        WorkerLifecycleRunner runner = runner(resolver);
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setExecutionType("WORKFLOW_NODE");
        task.setNodeCode("transfer-files");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(runner, "toNode", task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fileTransferRunId is missing");
    }

    private WorkerLifecycleRunner runner(WorkflowDispatchNodeResolver resolver) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setWorkerCode("worker-file-transfer-test");
        return new WorkerLifecycleRunner(mock(DispatchTaskMapper.class), mock(WorkerLeaseMapper.class),
                mock(RunRecordMapper.class), Collections.emptyList(), mock(ExecutionEventPublisher.class),
                properties, mock(CollectionTaskService.class), mock(QualityTaskService.class),
                mock(CollectionTaskAssemblerService.class), mock(RunLogFileService.class),
                mock(WorkerAuthorizationService.class), new ClusterInstanceIdentity(properties), resolver);
    }
}
