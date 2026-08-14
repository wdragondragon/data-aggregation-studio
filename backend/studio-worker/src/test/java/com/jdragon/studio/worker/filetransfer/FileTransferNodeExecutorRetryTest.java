package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.datasource.file.transfer.StorageCapabilities;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileEntry;
import com.jdragon.aggregation.datasource.file.transfer.TransferFilePage;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.aggregation.transfer.PreparedTransfer;
import com.jdragon.aggregation.transfer.TransferEventListener;
import com.jdragon.aggregation.transfer.TransferPlanner;
import com.jdragon.aggregation.transfer.TransferVerificationPlan;
import com.jdragon.aggregation.transfer.TransferVerifier;
import com.jdragon.aggregation.transfer.model.ConflictPolicy;
import com.jdragon.aggregation.transfer.model.SourceSnapshot;
import com.jdragon.aggregation.transfer.model.SourceSuccessAction;
import com.jdragon.aggregation.transfer.model.TransferEndpoint;
import com.jdragon.aggregation.transfer.model.TransferItemStatus;
import com.jdragon.aggregation.transfer.model.TransferMapping;
import com.jdragon.aggregation.transfer.model.TransferPlan;
import com.jdragon.aggregation.transfer.model.TransferPlanItem;
import com.jdragon.aggregation.transfer.model.TransferPolicy;
import com.jdragon.aggregation.transfer.model.TransferResult;
import com.jdragon.aggregation.transfer.model.TransferRuntimeOptions;
import com.jdragon.aggregation.transfer.model.TransferSelection;
import com.jdragon.aggregation.transfer.model.TransferSpec;
import com.jdragon.aggregation.transfer.model.VerificationMode;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import com.jdragon.studio.infra.service.FileTransferOutboxWriter;
import com.jdragon.studio.infra.service.FileTransferStateMutationService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferNodeExecutorRetryTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(FileTransferRunEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    FileTransferRunEntity.class);
        }
        if (TableInfoHelper.getTableInfo(FileTransferRunItemEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    FileTransferRunItemEntity.class);
        }
    }

    private final FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
    private final FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
    private final FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
    private final FileTransferStateMutationService mutationService = new FileTransferStateMutationService(
            runMapper, itemMapper, metricMapper, mock(FileTransferOutboxWriter.class));
    private final FileTransferNodeExecutor executor = new FileTransferNodeExecutor(
            runMapper, itemMapper, metricMapper,
            mock(DataSourceService.class), mock(DatasourceClusterBindingService.class),
            mock(AggregationSourceCapabilityProvider.class), new ObjectMapper(), mutationService);

    @Test
    void transferRetrySelectsOnlyTheRequestedCoreItem() {
        PreparedTransfer prepared = prepared(SourceSuccessAction.KEEP,
                item("completed-item"), item("retry-item"));

        PreparedTransfer selected = executor.selectRetryItem(prepared, "retry-item");

        assertThat(selected.plan().items())
                .extracting(TransferPlanItem::itemId)
                .containsExactly("retry-item");
    }

    @Test
    void postActionRetryWithChangedTargetChecksumDoesNotTransferOrDelete() throws Exception {
        FileTransferRunEntity run = run();
        FileTransferRunItemEntity stored = storedItem("expected-sha");
        when(itemMapper.selectOne(any())).thenReturn(stored);
        TransferFileSystem source = mock(TransferFileSystem.class);
        TransferFileSystem target = mock(TransferFileSystem.class);
        when(target.transferExists("/target/item.bin")).thenReturn(true);
        when(target.checksum(eq("/target/item.bin"), eq("SHA-256"), any())).thenReturn("changed-sha");

        TransferResult result = executor.retryPostAction(run,
                prepared(SourceSuccessAction.DELETE, item("retry-item")),
                () -> source, () -> target);

        assertThat(result.items().get(0).status()).isEqualTo(TransferItemStatus.POST_ACTION_FAILED);
        verify(source, never()).delete(any());
        verify(target, never()).append(any(), anyLong(), any(), anyLong());
        verify(target, never()).commit(any(), anyString(), anyBoolean());
    }

    @Test
    void postActionRetryDeletesSourceAfterCommittedTargetIsVerified() throws Exception {
        FileTransferRunEntity run = run();
        FileTransferRunItemEntity stored = storedItem("expected-sha");
        when(itemMapper.selectOne(any())).thenReturn(stored);
        TransferFileSystem source = mock(TransferFileSystem.class);
        TransferFileSystem target = mock(TransferFileSystem.class);
        when(target.transferExists("/target/item.bin")).thenReturn(true);
        when(target.checksum(eq("/target/item.bin"), eq("SHA-256"), any())).thenReturn("expected-sha");
        when(source.transferExists("/source/item.bin")).thenReturn(true);
        when(source.stat("/source/item.bin")).thenReturn(
                new TransferFileEntry("/source/item.bin", "item.bin", false, 10L, 100L, "etag-1"));
        when(source.checksum(eq("/source/item.bin"), eq("SHA-256"), any())).thenReturn("expected-sha");

        TransferResult result = executor.retryPostAction(run,
                prepared(SourceSuccessAction.DELETE, item("retry-item")),
                () -> source, () -> target);

        assertThat(result.items().get(0).status()).isEqualTo(TransferItemStatus.SUCCESS);
        verify(source).delete("/source/item.bin");
        verify(target, never()).append(any(), anyLong(), any(), anyLong());
        verify(target, never()).commit(any(), anyString(), anyBoolean());
    }

    @Test
    void partialPostActionRetryUsesTheOriginalStableSamplePlan() throws Exception {
        long fileSize = 256L * 1024L;
        TransferPlanItem planItem = item("retry-item", fileSize);
        TransferPolicy policy = new TransferPolicy(ConflictPolicy.FAIL, "SHA-256",
                VerificationMode.PARTIAL, 1, 64L * 1024L, SourceSuccessAction.DELETE, null);
        PreparedTransfer prepared = prepared(policy, planItem);
        TransferVerifier verifier = new TransferVerifier();
        TransferVerificationPlan verificationPlan = verifier.plan(prepared.plan(), planItem, policy);
        byte[] sampledContent = new byte[64 * 1024];
        java.util.Arrays.fill(sampledContent, (byte) 23);

        TransferFileSystem source = mock(TransferFileSystem.class);
        TransferFileSystem target = mock(TransferFileSystem.class);
        StorageCapabilities capabilities = new StorageCapabilities(
                true, true, true, false, false, false, Set.of("SHA-256"), true);
        when(source.capabilities()).thenReturn(capabilities);
        when(target.capabilities()).thenReturn(capabilities);
        when(source.openRead(anyString(), anyLong(), anyLong()))
                .thenAnswer(ignored -> new ByteArrayInputStream(sampledContent));
        when(target.openRead(anyString(), anyLong(), anyLong()))
                .thenAnswer(ignored -> new ByteArrayInputStream(sampledContent));
        String sampledFingerprint = verifier.checksum(source, planItem.sourcePath(),
                verificationPlan, policy.checksumAlgorithm(), ignored -> { });

        FileTransferRunEntity run = run();
        when(itemMapper.selectOne(any())).thenReturn(storedItem(sampledFingerprint));
        when(target.transferExists(planItem.targetPath())).thenReturn(true);
        when(source.transferExists(planItem.sourcePath())).thenReturn(true);
        when(source.stat(planItem.sourcePath())).thenReturn(new TransferFileEntry(
                planItem.sourcePath(), "item.bin", false, fileSize, 100L, "etag-1"));

        TransferResult result = executor.retryPostAction(run, prepared, () -> source, () -> target);

        assertThat(result.items().get(0).status()).isEqualTo(TransferItemStatus.SUCCESS);
        assertThat(result.items().get(0).targetChecksum()).startsWith(
                TransferVerifier.PARTIAL_DIGEST_PREFIX);
        verify(source).delete(planItem.sourcePath());
        verify(target, never()).append(any(), anyLong(), any(), anyLong());
    }

    @Test
    void retryFinishKeepsOtherSuccessfulItemsInRunAggregation() {
        FileTransferRunEntity run = run();
        run.setStatus("RUNNING");
        run.setResolvedSpecJson(Map.of("retryCoreItemId", "retry-item", "retryMode", "TRANSFER"));
        FileTransferRunItemEntity completed = completedItem("completed-item", 20L);
        FileTransferRunItemEntity retried = completedItem("retry-item", 10L);
        when(itemMapper.selectList(any())).thenReturn(List.of(completed, retried));
        when(runMapper.selectById(901L)).thenReturn(run);

        Map<String, Object> summary = executor.finish(run, List.of());

        assertThat(summary).containsEntry("successFiles", 2L)
                .containsEntry("transferredBytes", 30L);
        assertThat(run.getSuccessFiles()).isEqualTo(2L);
        assertThat(run.getTransferredBytes()).isEqualTo(30L);
        assertThat(completed.getStatus()).isEqualTo("SUCCESS");
        assertThat(run.getResolvedSpecJson()).doesNotContainKeys("retryCoreItemId", "retryMode");
        assertThat((Map<String, Object>) summary.get("summary"))
                .containsEntry("collectedRecords", 2)
                .containsEntry("successRecords", 2L)
                .containsEntry("failedRecords", 0L);
    }

    @Test
    void finishKeepsPeakThroughputPersistedByProgressListener() {
        FileTransferRunEntity dispatchSnapshot = run();
        dispatchSnapshot.setStatus("RUNNING");
        dispatchSnapshot.setPeakBytesPerSecond(0L);

        FileTransferRunEntity persisted = run();
        persisted.setStatus("RUNNING");
        persisted.setPeakBytesPerSecond(8_192L);
        when(runMapper.selectById(901L)).thenReturn(persisted);
        when(itemMapper.selectList(any())).thenReturn(List.of(completedItem("item-1", 10L)));

        Map<String, Object> summary = executor.finish(dispatchSnapshot, List.of());

        assertThat(persisted.getStatus()).isEqualTo("SUCCESS");
        assertThat(persisted.getPeakBytesPerSecond()).isEqualTo(8_192L);
        assertThat(persisted.getCurrentBytesPerSecond()).isEqualTo(0L);
        assertThat(summary).containsEntry("fileTransferStatus", "SUCCESS");
        verify(runMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void manualFileSelectionUsesExactTargetPathWhileDirectoryKeepsRelativeChildren() {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(10L);
        datasource.setTypeCode("local");
        datasource.setEnabled(Boolean.TRUE);
        datasource.setExecutable(Boolean.TRUE);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        when(dataSourceService.getInternalForProject(200L, 10L)).thenReturn(datasource);
        FileTransferNodeExecutor manualExecutor = new FileTransferNodeExecutor(
                runMapper, itemMapper, mock(FileTransferMetricSampleMapper.class),
                dataSourceService, mock(DatasourceClusterBindingService.class),
                mock(AggregationSourceCapabilityProvider.class), new ObjectMapper());

        FileTransferRunEntity run = run();
        run.setProjectId(200L);
        List<Map<String, Object>> manualItems = new ArrayList<>();
        manualItems.add(new LinkedHashMap<>(Map.of(
                "sourceRuntimeClusterId", 1L,
                "sourceDatasourceId", 10L,
                "sourcePath", "/source/item.bin",
                "targetRuntimeClusterId", 1L,
                "targetDatasourceId", 10L,
                "targetPath", "/target/item.bin",
                "recursive", false)));
        manualItems.add(new LinkedHashMap<>(Map.of(
                "sourceRuntimeClusterId", 1L,
                "sourceDatasourceId", 10L,
                "sourcePath", "/source/directory",
                "targetRuntimeClusterId", 1L,
                "targetDatasourceId", 10L,
                "targetPath", "/target/directory",
                "recursive", true)));

        List<?> executions = ReflectionTestUtils.invokeMethod(manualExecutor, "manualExecutions",
                run, Map.of(), manualItems);
        assertThat(executions).hasSize(2);
        Map<?, ?> fileSpec = (Map<?, ?>) ReflectionTestUtils.invokeMethod(executions.get(0), "spec");
        Map<?, ?> directorySpec = (Map<?, ?>) ReflectionTestUtils.invokeMethod(executions.get(1), "spec");
        Map<?, ?> fileMapping = (Map<?, ?>) fileSpec.get("mapping");
        assertThat(fileMapping.get("preserveRelativePath")).isEqualTo(false);
        assertThat(fileMapping.get("targetRootPath")).isEqualTo("/target");
        assertThat(fileMapping.get("targetPathTemplate")).isEqualTo("item.bin");
        assertThat(((Map<?, ?>) directorySpec.get("mapping")).get("preserveRelativePath")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void manualDirectorySelectionExpandsLeafFilesWithRelativeTargets() throws Exception {
        DataSourceDefinition datasource = datasource(10L);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        when(dataSourceService.getInternalForProject(200L, 10L)).thenReturn(datasource);
        FileTransferNodeExecutor manualExecutor = new FileTransferNodeExecutor(
                runMapper, itemMapper, mock(FileTransferMetricSampleMapper.class),
                dataSourceService, mock(DatasourceClusterBindingService.class),
                mock(AggregationSourceCapabilityProvider.class), new ObjectMapper());
        Map<String, Object> manualItem = new LinkedHashMap<>(Map.of(
                "runtimeClusterId", 1L,
                "sourceDatasourceId", 10L,
                "sourcePath", "/source/directory",
                "targetDatasourceId", 10L,
                "targetPath", "/target/directory",
                "recursive", true));

        List<?> executions = ReflectionTestUtils.invokeMethod(manualExecutor, "manualExecutions",
                runWithProject(), Map.of(), List.of(manualItem));
        Map<String, Object> rawSpec = (Map<String, Object>) ReflectionTestUtils
                .invokeMethod(executions.get(0), "spec");
        TransferSpec spec = new com.jdragon.aggregation.transfer.plugin.TransferConfigurationMapper()
                .map(com.jdragon.aggregation.commons.util.Configuration.from(rawSpec));
        TransferFileSystem source = mock(TransferFileSystem.class);
        when(source.capabilities()).thenReturn(new StorageCapabilities(
                true, true, true, false, false, false, Set.of("SHA-256"), true));
        when(source.stat("/source/directory")).thenReturn(directory("/source/directory"));
        when(source.listPage("/source/directory", null, 1_000)).thenReturn(new TransferFilePage(List.of(
                file("/source/directory/root.txt", 4L),
                directory("/source/directory/nested")), null, false));
        when(source.listPage("/source/directory/nested", null, 1_000)).thenReturn(new TransferFilePage(
                List.of(file("/source/directory/nested/child.txt", 5L)), null, false));

        PreparedTransfer prepared = new TransferPlanner().prepare(spec, source, "manual-directory",
                Instant.parse("2026-08-09T00:00:00Z"), TransferEventListener.NOOP);

        assertThat(prepared.plan().items())
                .extracting(TransferPlanItem::sourcePath, TransferPlanItem::targetPath)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("/source/directory/root.txt",
                                "/target/directory/root.txt"),
                        org.assertj.core.groups.Tuple.tuple("/source/directory/nested/child.txt",
                                "/target/directory/nested/child.txt"));
    }

    @Test
    void manualRunWithNoDiscoveredFilesIsRejected() {
        Map<String, Object> snapshot = Map.of("manualItems", List.of(Map.of(
                "sourcePath", "/source/empty",
                "targetPath", "/target/empty",
                "recursive", true)));

        assertThatThrownBy(() -> executor.requireManualTransferFiles(snapshot, null, 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILE_TRANSFER_NO_FILES_DISCOVERED");
    }

    @Test
    void scheduledNoMatchRunAndManualRunWithFilesRemainValid() {
        executor.requireManualTransferFiles(Map.of(), null, 0L);
        executor.requireManualTransferFiles(Map.of("manualItems", List.of(Map.of("recursive", true))),
                null, 1L);
    }

    private FileTransferRunEntity run() {
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(901L);
        return run;
    }

    private FileTransferRunEntity runWithProject() {
        FileTransferRunEntity run = run();
        run.setProjectId(200L);
        return run;
    }

    private DataSourceDefinition datasource(Long id) {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(id);
        datasource.setTypeCode("local");
        datasource.setEnabled(Boolean.TRUE);
        datasource.setExecutable(Boolean.TRUE);
        return datasource;
    }

    private TransferFileEntry directory(String path) {
        return new TransferFileEntry(path, path.substring(path.lastIndexOf('/') + 1),
                true, 0L, 0L, null);
    }

    private TransferFileEntry file(String path, long size) {
        return new TransferFileEntry(path, path.substring(path.lastIndexOf('/') + 1),
                false, size, 100L, "etag-" + size);
    }

    private FileTransferRunItemEntity storedItem(String checksum) {
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setAttempts(1);
        item.setSourceChecksum(checksum);
        item.setTargetChecksum(checksum);
        return item;
    }

    private FileTransferRunItemEntity completedItem(String coreItemId, Long transferredBytes) {
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setCoreItemId(coreItemId);
        item.setStatus("SUCCESS");
        item.setTransferredBytes(transferredBytes);
        item.setFileSize(transferredBytes);
        item.setResumedBytes(0L);
        return item;
    }

    private PreparedTransfer prepared(SourceSuccessAction action, TransferPlanItem... items) {
        TransferPolicy policy = new TransferPolicy(ConflictPolicy.FAIL, "SHA-256", action, null);
        return prepared(policy, items);
    }

    private PreparedTransfer prepared(TransferPolicy policy, TransferPlanItem... items) {
        TransferSpec spec = new TransferSpec(1,
                new TransferEndpoint("local", "source", Map.of()),
                new TransferEndpoint("local", "target", Map.of()),
                new TransferSelection("/source", List.of(), true, List.of(), null, List.of(),
                        null, null, null, null, 100),
                new TransferMapping("/target", true, null), policy,
                TransferRuntimeOptions.defaults(), "UTC", Map.of());
        return new PreparedTransfer(spec,
                new TransferPlan("core-run", Instant.parse("2026-08-07T00:00:00Z"), List.of(items)));
    }

    private TransferPlanItem item(String itemId) {
        return item(itemId, 10L);
    }

    private TransferPlanItem item(String itemId, long fileSize) {
        return new TransferPlanItem(itemId, "1:10", "1:20",
                "/source/item.bin", "/target/item.bin", "/target/item.bin.part",
                "item.bin", new SourceSnapshot("/source/item.bin", fileSize, 100L, "etag-1"));
    }
}
