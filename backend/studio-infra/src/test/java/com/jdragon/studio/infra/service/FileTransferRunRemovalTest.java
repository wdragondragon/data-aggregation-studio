package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.FileTransferRunView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.model.FileTransferEventIntent;
import com.jdragon.studio.infra.model.FileTransferOutboxEventType;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferRunRemovalTest {

    @BeforeAll
    static void initializeLambdaMetadata() {
        initialize(DispatchTaskEntity.class);
        initialize(FileTransferRunEntity.class);
        initialize(FileTransferRunItemEntity.class);
    }

    @Test
    void queueRunAndItemShouldUseMybatisPlusLogicalDeleteMetadata() {
        assertEquals("deleted", TableInfoHelper.getTableInfo(FileTransferRunEntity.class)
                .getLogicDeleteFieldInfo().getProperty());
        assertEquals("deleted", TableInfoHelper.getTableInfo(FileTransferRunItemEntity.class)
                .getLogicDeleteFieldInfo().getProperty());
    }

    @Test
    void queuedManualRunShouldCancelPendingDispatchAndBeLogicallyRemoved() {
        Fixture fixture = fixture("QUEUED");

        fixture.service.removeManualRun(100L);

        verify(fixture.runMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(fixture.runMapper).deleteById(100L);
        verify(fixture.itemMapper).delete(any(LambdaQueryWrapper.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<DispatchTaskEntity>> dispatchUpdate =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fixture.dispatchTaskMapper).update(isNull(), dispatchUpdate.capture());
        assertTrue(dispatchUpdate.getValue().getParamNameValuePairs().containsValue("CANCELED"));
    }

    @Test
    void terminalManualRunShouldBeDismissedWithoutDeletingRunHistoryOrItems() {
        Fixture fixture = fixture("SUCCESS");
        when(fixture.runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        fixture.service.dismissManualRunFromQueue(100L);

        verify(fixture.runMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(fixture.runMapper, never()).deleteById(any(Long.class));
        verify(fixture.itemMapper, never()).delete(any(LambdaQueryWrapper.class));
        assertEquals(false, fixture.run.getQueueVisible());
        ArgumentCaptor<FileTransferEventIntent> event = ArgumentCaptor.forClass(FileTransferEventIntent.class);
        verify(fixture.outboxWriter).appendProgress(event.capture(), anyString(), eq(true));
        assertEquals(FileTransferOutboxEventType.RUN_REMOVED, event.getValue().getEventType());
    }

    @Test
    void queueOnlyRunListShouldFilterQueueVisibilityButHistoryListShouldNot() {
        Fixture fixture = fixture("SUCCESS");
        when(fixture.runMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        fixture.service.listPage(1, 20, null, null, null, null, true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<FileTransferRunEntity>> queueQuery =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fixture.runMapper).selectPage(any(Page.class), queueQuery.capture());
        assertTrue(queueQuery.getValue().getSqlSegment().contains("queue_visible"));

        Fixture historyFixture = fixture("SUCCESS");
        when(historyFixture.runMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        historyFixture.service.listPage(1, 20, null, null, null, null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<FileTransferRunEntity>> historyQuery =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(historyFixture.runMapper).selectPage(any(Page.class), historyQuery.capture());
        assertTrue(!historyQuery.getValue().getSqlSegment().contains("queue_visible"));
    }

    @Test
    void runningManualRunShouldRequireCancellationBeforeRemoval() {
        Fixture fixture = fixture("RUNNING");

        assertThrows(StudioException.class, () -> fixture.service.removeManualRun(100L));

        verify(fixture.runMapper, never()).deleteById(any(Long.class));
        verify(fixture.itemMapper, never()).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void cancelingRunShouldCloseActiveItems() {
        Fixture fixture = fixture("RUNNING");

        fixture.service.cancel(100L);

        assertEquals("CANCELED", fixture.run.getStatus());
        assertEquals(0, fixture.run.getActiveFiles());
        assertEquals(0L, fixture.run.getCurrentBytesPerSecond());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunItemEntity>> itemUpdate =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fixture.itemMapper).update(isNull(), itemUpdate.capture());
        assertTrue(itemUpdate.getValue().getParamNameValuePairs().containsValue("CANCELED"));
        assertTrue(itemUpdate.getValue().getParamNameValuePairs().containsValue("Canceled by user"));
    }

    @Test
    void pausingRunShouldPauseActiveItemsAndClearSpeeds() {
        Fixture fixture = fixture("RUNNING");
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setId(200L);
        item.setRunId(100L);
        item.setTenantId("default");
        item.setProjectId(10L);
        item.setStatus("TRANSFERRING");
        when(fixture.itemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(item));
        when(fixture.itemMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        fixture.service.pause(100L);

        assertEquals("PAUSED", fixture.run.getStatus());
        assertEquals(0, fixture.run.getActiveFiles());
        assertEquals(0L, fixture.run.getCurrentBytesPerSecond());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunItemEntity>> itemUpdate =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fixture.itemMapper).update(isNull(), itemUpdate.capture());
        assertTrue(itemUpdate.getValue().getParamNameValuePairs().containsValue("PAUSED"));
        assertTrue(itemUpdate.getValue().getParamNameValuePairs().containsValue(0L));
    }

    @Test
    void pausedRunRecoveryShouldNormalizeStaleActiveItemsWithoutCreatingDispatch() {
        Fixture fixture = fixture("PAUSED");
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setId(200L);
        item.setRunId(100L);
        item.setTenantId("default");
        item.setProjectId(10L);
        item.setStatus("TRANSFERRING");
        item.setTransferredBytes(128L);
        item.setCheckpointJson(Map.of("confirmedOffset", 128L));
        when(fixture.itemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(item));
        when(fixture.runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(fixture.itemMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        FileTransferStateMutationService mutationService = new FileTransferStateMutationService(
                fixture.runMapper, fixture.itemMapper, mock(FileTransferMetricSampleMapper.class),
                mock(FileTransferOutboxWriter.class));
        mutationService.normalizePausedRunItemsAndEvent(fixture.run);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunItemEntity>> itemUpdate =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fixture.itemMapper).update(isNull(), itemUpdate.capture());
        assertTrue(itemUpdate.getValue().getParamNameValuePairs().containsValue("PAUSED"));
        assertTrue(itemUpdate.getValue().getParamNameValuePairs().containsValue(0L));
        assertEquals(128L, item.getTransferredBytes());
        assertEquals(128L, item.getCheckpointJson().get("confirmedOffset"));
        verify(fixture.dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    @Test
    void pausedRunRecoveryShouldNotTouchItemsAfterConcurrentResumeWins() {
        Fixture fixture = fixture("PAUSED");
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setId(200L);
        item.setRunId(100L);
        item.setTenantId("default");
        item.setProjectId(10L);
        item.setStatus("TRANSFERRING");
        when(fixture.itemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(item));
        when(fixture.runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        FileTransferStateMutationService mutationService = new FileTransferStateMutationService(
                fixture.runMapper, fixture.itemMapper, mock(FileTransferMetricSampleMapper.class),
                mock(FileTransferOutboxWriter.class));

        assertEquals(0, mutationService.normalizePausedRunItemsAndEvent(fixture.run));
        verify(fixture.itemMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(fixture.dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    @Test
    void queuedManualRunShouldNotBeRemovedAfterWorkerClaimsDispatch() {
        Fixture fixture = fixture("QUEUED");
        when(fixture.dispatchTaskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThrows(StudioException.class, () -> fixture.service.removeManualRun(100L));

        verify(fixture.runMapper, never()).updateById(any(FileTransferRunEntity.class));
        verify(fixture.runMapper, never()).deleteById(any(Long.class));
        verify(fixture.itemMapper, never()).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void terminalManualItemShouldBeLogicallyRemoved() {
        Fixture fixture = fixture("FAILED");
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setId(200L);
        item.setRunId(100L);
        item.setTenantId("default");
        item.setProjectId(10L);
        item.setStatus("FAILED");
        when(fixture.itemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(item);

        fixture.service.removeManualItem(100L, 200L);

        verify(fixture.itemMapper).deleteById(200L);
        assertEquals(0, fixture.run.getActiveFiles());
        assertEquals(0L, fixture.run.getCurrentBytesPerSecond());
    }

    @Test
    void staleActiveItemFromCanceledRunShouldBeRemovableWithoutClaimedDispatch() {
        Fixture fixture = fixture("CANCELED");
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setId(200L);
        item.setRunId(100L);
        item.setTenantId("default");
        item.setProjectId(10L);
        item.setStatus("TRANSFERRING");
        when(fixture.itemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(item);
        when(fixture.dispatchTaskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        fixture.service.removeManualItem(100L, 200L);

        verify(fixture.itemMapper).deleteById(200L);
    }

    @Test
    void staleActiveItemFromCanceledRunShouldRemainWhileDispatchIsClaimed() {
        Fixture fixture = fixture("CANCELED");
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setId(200L);
        item.setRunId(100L);
        item.setTenantId("default");
        item.setProjectId(10L);
        item.setStatus("TRANSFERRING");
        when(fixture.itemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(item);
        when(fixture.dispatchTaskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThrows(StudioException.class, () -> fixture.service.removeManualItem(100L, 200L));

        verify(fixture.itemMapper, never()).deleteById(200L);
    }

    @Test
    void runViewShouldExposeClusterAndDatasourceNames() {
        Fixture fixture = fixture("SUCCESS");
        fixture.run.setSourceRuntimeClusterId(11L);
        fixture.run.setTargetRuntimeClusterId(12L);
        fixture.run.setSourceDatasourceId(21L);
        fixture.run.setTargetDatasourceId(22L);
        when(fixture.runtimeClusterSelectionService.runtimeClusterNames(any()))
                .thenReturn(Map.of(11L, "采集集群", 12L, "归档集群"));
        when(fixture.dataSourceService.listBasicNameMap(any()))
                .thenReturn(Map.of(21L, "来源 OSS", 22L, "目标 OSS"));

        FileTransferRunView view = fixture.service.toRunView(fixture.run);

        assertEquals("采集集群", view.getSourceRuntimeClusterName());
        assertEquals("来源 OSS", view.getSourceDatasourceName());
        assertEquals("归档集群", view.getTargetRuntimeClusterName());
        assertEquals("目标 OSS", view.getTargetDatasourceName());
    }

    private Fixture fixture(String status) {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        RuntimeClusterSelectionService runtimeClusterSelectionService = mock(RuntimeClusterSelectionService.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        FileTransferOutboxWriter outboxWriter = mock(FileTransferOutboxWriter.class);
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(100L);
        run.setTenantId("default");
        run.setProjectId(10L);
        run.setTriggerType("MANUAL");
        run.setStatus(status);
        run.setQueueVisible(true);
        when(runMapper.selectById(100L)).thenReturn(run);
        when(projectAccess.requireCurrentProjectId()).thenReturn(10L);
        when(securityService.currentTenantId()).thenReturn("default");
        FileTransferStateMutationService mutationService = new FileTransferStateMutationService(
                runMapper, itemMapper, mock(FileTransferMetricSampleMapper.class),
                outboxWriter);
        mutationService.setDispatchTaskMapper(dispatchTaskMapper);
        FileTransferRunService service = new FileTransferRunService(
                runMapper,
                itemMapper,
                dispatchTaskMapper,
                mock(FileTransferTaskService.class),
                dataSourceService,
                runtimeClusterSelectionService,
                projectAccess,
                securityService,
                mock(UnstructuredManagementService.class),
                mock(ClusterLockService.class),
                new ObjectMapper(),
                mutationService);
        return new Fixture(service, runMapper, itemMapper, dispatchTaskMapper,
                runtimeClusterSelectionService, dataSourceService, outboxWriter, run);
    }

    private static void initialize(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "file-transfer-removal-test"),
                    entityType);
        }
    }

    private record Fixture(FileTransferRunService service,
                           FileTransferRunMapper runMapper,
                           FileTransferRunItemMapper itemMapper,
                           DispatchTaskMapper dispatchTaskMapper,
                           RuntimeClusterSelectionService runtimeClusterSelectionService,
                           DataSourceService dataSourceService,
                           FileTransferOutboxWriter outboxWriter,
                           FileTransferRunEntity run) {
    }
}
