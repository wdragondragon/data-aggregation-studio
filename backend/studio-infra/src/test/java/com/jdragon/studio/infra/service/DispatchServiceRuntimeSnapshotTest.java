package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatchServiceRuntimeSnapshotTest {

    @Test
    void shouldRejectDispatchWhenResourceChangesAfterInitialRead() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        RuntimeResourceRevisionService revisionService = mock(RuntimeResourceRevisionService.class);
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        LocalDateTime initialUpdatedAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        LocalDateTime changedUpdatedAt = initialUpdatedAt.plusSeconds(1);
        CollectionTaskDefinitionView initial = definition(initialUpdatedAt);
        CollectionTaskDefinitionView changed = definition(changedUpdatedAt);

        when(collectionTaskService.requireOnline(200L)).thenReturn(initial, changed);
        when(revisionService.collectionTaskRevision(200L, initialUpdatedAt)).thenReturn("revision-before");
        when(revisionService.collectionTaskRevision(200L, changedUpdatedAt)).thenReturn("revision-after");

        DispatchService service = service(dispatchTaskMapper, collectionTaskService, revisionService);

        assertThatThrownBy(() -> service.triggerCollectionTask(200L))
                .hasMessageContaining("Collection task changed while dispatching");

        verify(dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    @Test
    void shouldRejectDispatchWhenDatasourceBindingChangesAfterValidation() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        RuntimeResourceRevisionService revisionService = mock(RuntimeResourceRevisionService.class);
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        CollectionTaskDefinitionView definition = definition(updatedAt);

        when(collectionTaskService.requireOnline(200L)).thenReturn(definition);
        when(revisionService.collectionTaskRevision(200L, updatedAt))
                .thenReturn("binding-before", "binding-after");

        DispatchService service = service(dispatchTaskMapper, collectionTaskService, revisionService);

        assertThatThrownBy(() -> service.triggerCollectionTask(200L))
                .hasMessageContaining("Collection task changed while dispatching");

        verify(dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    @Test
    void shouldTreatAnExplicitMatchingClusterAsNoManualOverride() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        RuntimeResourceRevisionService revisionService = mock(RuntimeResourceRevisionService.class);
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        CollectionTaskDefinitionView definition = definition(LocalDateTime.of(2026, 7, 20, 10, 0));
        when(collectionTaskService.requireOnline(200L)).thenReturn(definition);

        DispatchService service = service(dispatchTaskMapper, collectionTaskService, revisionService);

        service.triggerCollectionTask(200L, 46L);

        verify(collectionTaskService, never()).requireRunnableOnCluster(any(Long.class), any(Long.class));
        verify(dispatchTaskMapper).insert(argThat((DispatchTaskEntity task) ->
                Long.valueOf(46L).equals(task.getTargetClusterId())));
    }

    private DispatchService service(DispatchTaskMapper dispatchTaskMapper,
                                    CollectionTaskService collectionTaskService,
                                    RuntimeResourceRevisionService revisionService) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentProjectId()).thenReturn(300L);
        ClusterLockService clusterLockService = mock(ClusterLockService.class);
        doAnswer(invocation -> invocation.getArgument(3, Supplier.class).get())
                .when(clusterLockService)
                .executeIfAcquiredNonReentrant(any(String.class), eq(10L), eq(false),
                        any(Supplier.class), any(Supplier.class));

        DispatchService service = new DispatchService(
                dispatchTaskMapper,
                mock(RunRecordMapper.class),
                mock(WorkflowDefinitionMapper.class),
                mock(WorkflowService.class),
                collectionTaskService,
                mock(QualityTaskService.class),
                securityService,
                mock(WorkerAuthorizationService.class),
                mock(StaleExecutionRecoveryService.class),
                clusterLockService);
        service.setRuntimeValidationService(mock(RuntimeValidationService.class));
        service.setRuntimeResourceRevisionService(revisionService);
        return service;
    }

    private CollectionTaskDefinitionView definition(LocalDateTime updatedAt) {
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setId(200L);
        definition.setTenantId("default");
        definition.setProjectId(300L);
        definition.setRuntimeClusterId(46L);
        definition.setStatus(CollectionTaskStatus.ONLINE);
        definition.setUpdatedAt(updatedAt);
        return definition;
    }
}
