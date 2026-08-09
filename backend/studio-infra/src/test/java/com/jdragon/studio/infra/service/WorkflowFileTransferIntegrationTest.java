package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkflowEdgeMapper;
import com.jdragon.studio.infra.mapper.WorkflowNodeMapper;
import com.jdragon.studio.infra.mapper.WorkflowScheduleMapper;
import com.jdragon.studio.infra.mapper.WorkflowVersionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowFileTransferIntegrationTest {

    @Test
    void validatesBothPlacementsAndPinsPublishedRevision() {
        RuntimeClusterSelectionService placement = mock(RuntimeClusterSelectionService.class);
        FileTransferTaskDefinitionMapper fileTransferMapper = mock(FileTransferTaskDefinitionMapper.class);
        WorkflowService service = workflowService(placement, fileTransferMapper);
        when(fileTransferMapper.selectById(700L)).thenReturn(task());

        WorkflowNodeDefinition node = fileTransferNode();
        ReflectionTestUtils.invokeMethod(service, "validateNodeRuntimeClusters",
                300L, 22L, List.of(node), true);

        verify(placement).assertExistingResourceRunnable(300L, 11L, List.of(101L));
        verify(placement).assertExistingResourceRunnable(300L, 22L, List.of(202L));
        verify(placement).validateDatasourceSelection(eq(300L), eq(22L), any());
        assertThat(node.getConfig().get("_resourceRevision"))
                .isEqualTo("file-transfer:700:4");
    }

    @Test
    void rejectsWorkflowPlacementDifferentFromTransferTarget() {
        RuntimeClusterSelectionService placement = mock(RuntimeClusterSelectionService.class);
        FileTransferTaskDefinitionMapper fileTransferMapper = mock(FileTransferTaskDefinitionMapper.class);
        WorkflowService service = workflowService(placement, fileTransferMapper);
        when(fileTransferMapper.selectById(700L)).thenReturn(task());

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service,
                "validateNodeRuntimeClusters", 300L, 11L, List.of(fileTransferNode()), true))
                .hasMessageContaining("same runtime cluster as the workflow");
    }

    @Test
    void workflowDispatchCreatesAndReferencesFileTransferRun() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        FileTransferRunService runService = mock(FileTransferRunService.class);
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(900L);
        when(runService.createWorkflowRunSkeleton(700L, 300L, 22L, "WORKFLOW",
                "file-transfer:700:4")).thenReturn(run);
        DispatchService service = dispatchService(dispatchTaskMapper);
        service.setFileTransferRunService(runService);

        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(400L);
        workflow.setVersionId(401L);
        workflow.setTenantId("tenant-a");
        WorkflowNodeDefinition node = fileTransferNode();
        node.getConfig().put("_resourceRevision", "file-transfer:700:4");

        DispatchTaskEntity task = ReflectionTestUtils.invokeMethod(service, "buildWorkflowNodeTask",
                workflow, 800L, node, 300L, 22L, null);

        assertThat(task).isNotNull();
        assertThat(task.getFileTransferTaskId()).isEqualTo(700L);
        assertThat(task.getFileTransferRunId()).isEqualTo(900L);
        assertThat(task.getResourceRevision()).isEqualTo("file-transfer:700:4");
        assertThat(task.getPayloadJson()).containsEntry("fileTransferRunId", 900L);
        assertThat(task.getPayloadJson().get("config"))
                .isEqualTo(Map.of("fileTransferTaskId", 700L, "fileTransferRunId", 900L));
    }

    private WorkflowService workflowService(RuntimeClusterSelectionService placement,
                                            FileTransferTaskDefinitionMapper fileTransferMapper) {
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        WorkflowService service = new WorkflowService(
                mock(WorkflowDefinitionMapper.class), mock(WorkflowVersionMapper.class),
                mock(WorkflowNodeMapper.class), mock(WorkflowEdgeMapper.class),
                mock(WorkflowScheduleMapper.class), mock(DispatchTaskMapper.class),
                mock(RunRecordMapper.class), security, mock(ProjectResourceAccessService.class));
        service.setRuntimeClusterSelectionService(placement);
        service.setWorkflowResourceMappers(mock(CollectionTaskDefinitionMapper.class),
                mock(QualityTaskDefinitionMapper.class), mock(DataDevelopmentScriptMapper.class),
                mock(DataModelMapper.class), fileTransferMapper);
        return service;
    }

    private DispatchService dispatchService(DispatchTaskMapper dispatchTaskMapper) {
        return new DispatchService(dispatchTaskMapper, mock(RunRecordMapper.class),
                mock(WorkflowDefinitionMapper.class), mock(WorkflowService.class),
                mock(CollectionTaskService.class), mock(QualityTaskService.class),
                mock(StudioSecurityService.class), mock(WorkerAuthorizationService.class),
                mock(StaleExecutionRecoveryService.class), mock(ClusterLockService.class));
    }

    private WorkflowNodeDefinition fileTransferNode() {
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeCode("transfer-files");
        node.setNodeName("Transfer files");
        node.setNodeType(NodeType.FILE_TRANSFER);
        node.setConfig(new LinkedHashMap<String, Object>(Map.of("fileTransferTaskId", 700L)));
        return node;
    }

    private FileTransferTaskDefinitionEntity task() {
        FileTransferTaskDefinitionEntity task = new FileTransferTaskDefinitionEntity();
        task.setId(700L);
        task.setTenantId("tenant-a");
        task.setProjectId(300L);
        task.setStatus("ONLINE");
        task.setPublishedVersion(4);
        task.setPublishedSnapshotJson(new LinkedHashMap<String, Object>(Map.of("schemaVersion", 1)));
        task.setSourceRuntimeClusterId(11L);
        task.setSourceDatasourceId(101L);
        task.setTargetRuntimeClusterId(22L);
        task.setTargetDatasourceId(202L);
        return task;
    }
}
