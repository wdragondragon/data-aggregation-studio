package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.infra.entity.FileTransferMetricSampleEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.service.FileTransferOutboxWriter;
import com.jdragon.studio.infra.service.FileTransferStateMutationService;
import com.jdragon.aggregation.transfer.model.TransferEvent;
import com.jdragon.aggregation.transfer.model.TransferEventType;
import com.jdragon.aggregation.transfer.model.TransferItemStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudioTransferEventListenerTest {

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

    @Test
    void retrySampleKeepsProgressFromFilesOutsideTheRetrySelection() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferRunEntity run = run(901L, 30L);
        when(runMapper.selectById(901L)).thenReturn(run);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                item("completed-item", 20L), item("retry-item", 10L)));

        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper,
                mutationService(runMapper, itemMapper, metricMapper));
        listener.flushSample();

        ArgumentCaptor<FileTransferMetricSampleEntity> sample =
                ArgumentCaptor.forClass(FileTransferMetricSampleEntity.class);
        verify(metricMapper).insert(sample.capture());
        assertThat(sample.getValue().getTransferredBytes()).isEqualTo(30L);
    }

    @Test
    void resumedInitialProgressDoesNotCreateAnArtificialSpeedSample() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferRunEntity run = run(904L, 0L);
        when(runMapper.selectById(904L)).thenReturn(run);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("resume-item", 4_096L)));

        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper,
                mutationService(runMapper, itemMapper, metricMapper));
        listener.flushSample();

        ArgumentCaptor<FileTransferMetricSampleEntity> sample =
                ArgumentCaptor.forClass(FileTransferMetricSampleEntity.class);
        verify(metricMapper).insert(sample.capture());
        assertThat(sample.getValue().getTransferredBytes()).isEqualTo(4_096L);
        assertThat(sample.getValue().getBytesPerSecond()).isZero();
    }

    @Test
    void finalSampleUsesPersistedRunCountersWithoutTreatingHistoricalBytesAsSpeed() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferRunEntity run = run(902L, 0L);
        run.setStatus("SUCCESS");
        run.setSuccessFiles(2L);
        run.setSkippedFiles(1L);
        run.setFailedFiles(1L);
        when(runMapper.selectById(902L)).thenReturn(run);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("completed-item", 30L)));

        new StudioTransferEventListener(run, runMapper, itemMapper, metricMapper,
                mutationService(runMapper, itemMapper, metricMapper)).flushSample();

        ArgumentCaptor<FileTransferMetricSampleEntity> sample =
                ArgumentCaptor.forClass(FileTransferMetricSampleEntity.class);
        verify(metricMapper).insert(sample.capture());
        assertThat(sample.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(sample.getValue().getBytesPerSecond()).isZero();
        assertThat(sample.getValue().getCompletedFiles()).isEqualTo(3L);
        assertThat(sample.getValue().getFailedFiles()).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunEntity>> update =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runMapper).update(isNull(), update.capture());
        assertThat(updateValue(update.getValue(), "current_bytes_per_second")).isEqualTo(0L);
    }

    @Test
    void terminalItemStatusClearsCurrentSpeed() throws Exception {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferRunEntity run = run(903L, 0L);
        when(runMapper.selectById(903L)).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("item-1", 0L)));
        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper,
                mutationService(runMapper, itemMapper, metricMapper));

        listener.onEvent(event(TransferItemStatus.TRANSFERRING, 10L));
        Thread.sleep(5L);
        listener.onEvent(event(TransferItemStatus.SUCCESS, 20L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunItemEntity>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(itemMapper, times(4)).update(isNull(), updates.capture());
        LambdaUpdateWrapper<FileTransferRunItemEntity> finalProgress =
                updates.getAllValues().get(updates.getAllValues().size() - 1);
        assertThat(updateValue(finalProgress, "current_bytes_per_second")).isEqualTo(0L);
    }

    @Test
    void resumedStatusPersistsRetainedBytesWithoutTreatingThemAsSpeed() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferRunEntity run = run(905L, 4_096L);
        when(runMapper.selectById(905L)).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("item-1", 4_096L)));
        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper,
                mutationService(runMapper, itemMapper, metricMapper));

        listener.onEvent(new TransferEvent(TransferEventType.ITEM_STATUS_CHANGED, Instant.now(),
                "run-1", "item-1", TransferItemStatus.TRANSFERRING, 4_096L, 8_192L, 1,
                "checkpoint restored; rebuilding checksum before transfer resumes", null,
                Map.of("resumedBytes", 4_096L, "resumePhase", "REBUILDING_CHECKSUM")));
        listener.flushSample();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunItemEntity>> itemUpdates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(itemMapper, times(2)).update(isNull(), itemUpdates.capture());
        assertThat(updateValue(itemUpdates.getAllValues().get(0), "resumed_bytes")).isEqualTo(4_096L);
        assertThat(updateValue(itemUpdates.getAllValues().get(1), "current_bytes_per_second")).isEqualTo(0L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunEntity>> runUpdates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runMapper, times(2)).update(isNull(), runUpdates.capture());
        LambdaUpdateWrapper<FileTransferRunEntity> sampleUpdate = runUpdates.getAllValues().get(1);
        assertThat(updateValue(sampleUpdate, "resumed_bytes")).isEqualTo(4_096L);
        assertThat(updateValue(sampleUpdate, "resumed_files")).isEqualTo(1L);
        assertThat(updateValue(sampleUpdate, "current_bytes_per_second")).isEqualTo(0L);
    }

    @Test
    void liveProgressUpdatesSpeedWithoutAdvancingConfirmedBytes() throws Exception {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferOutboxWriter outboxWriter = mock(FileTransferOutboxWriter.class);
        FileTransferRunEntity run = run(906L, 0L);
        FileTransferRunItemEntity item = item("item-1", 0L);
        item.setId(1_001L);
        item.setRunId(906L);
        when(runMapper.selectById(906L)).thenReturn(run);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(itemMapper.selectOne(any())).thenReturn(item);
        when(itemMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper,
                new FileTransferStateMutationService(runMapper, itemMapper, metricMapper, outboxWriter));

        listener.onEvent(event(TransferItemStatus.TRANSFERRING, 0L));
        Thread.sleep(5L);
        listener.onEvent(new TransferEvent(TransferEventType.ITEM_PROGRESS, Instant.now(),
                "run-1", "item-1", TransferItemStatus.TRANSFERRING, 0L, 8_192L, 1,
                "file block transfer in progress", null,
                Map.of("live", true, "confirmedBytes", 0L, "observedBytes", 4_096L)));
        listener.flushSample();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunItemEntity>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(itemMapper, times(3)).update(isNull(), updates.capture());
        LambdaUpdateWrapper<FileTransferRunItemEntity> liveUpdate = updates.getAllValues().get(2);
        assertThat(updateValue(liveUpdate, "transferred_bytes")).isNull();
        assertThat((Long) updateValue(liveUpdate, "current_bytes_per_second")).isPositive();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunEntity>> runUpdates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runMapper, times(2)).update(isNull(), runUpdates.capture());
        LambdaUpdateWrapper<FileTransferRunEntity> sampleUpdate = runUpdates.getAllValues().get(1);
        assertThat(updateValue(sampleUpdate, "transferred_bytes")).isEqualTo(0L);
        assertThat((Long) updateValue(sampleUpdate, "current_bytes_per_second")).isPositive();
        assertThat((Long) updateValue(sampleUpdate, "peak_bytes_per_second")).isPositive();

        ArgumentCaptor<com.jdragon.studio.infra.model.FileTransferEventIntent> intents =
                ArgumentCaptor.forClass(com.jdragon.studio.infra.model.FileTransferEventIntent.class);
        verify(outboxWriter, atLeastOnce()).appendProgress(intents.capture(), any(), anyBoolean());
        assertThat(intents.getAllValues()).anySatisfy(intent -> {
            assertThat(intent.getPayload()).containsEntry("live", true);
            assertThat(intent.getPayload()).containsEntry("confirmedBytes", 0L);
            assertThat(intent.getPayload()).containsEntry("observedBytes", 4_096L);
        });
    }

    @Test
    void checkpointChecksumActivityUpdatesSpeedWithoutAdvancingProgress() throws Exception {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferOutboxWriter outboxWriter = mock(FileTransferOutboxWriter.class);
        FileTransferRunEntity run = run(907L, 4_096L);
        FileTransferRunItemEntity item = item("item-1", 4_096L);
        item.setId(1_002L);
        item.setRunId(907L);
        when(runMapper.selectById(907L)).thenReturn(run);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(itemMapper.selectOne(any())).thenReturn(item);
        when(itemMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper,
                new FileTransferStateMutationService(runMapper, itemMapper, metricMapper, outboxWriter));

        listener.onEvent(new TransferEvent(TransferEventType.ITEM_STATUS_CHANGED, Instant.now(),
                "run-1", "item-1", TransferItemStatus.TRANSFERRING, 4_096L, 8_192L, 1,
                "checkpoint restored; rebuilding checksum before transfer resumes", null,
                Map.of("resumedBytes", 4_096L, "resumePhase", "REBUILDING_CHECKSUM",
                        "activityBytes", 4_096L)));
        Thread.sleep(5L);
        listener.onEvent(new TransferEvent(TransferEventType.ITEM_PROGRESS, Instant.now(),
                "run-1", "item-1", TransferItemStatus.TRANSFERRING, 4_096L, 8_192L, 1,
                "validating retained checkpoint checksum", null,
                Map.of("live", true, "confirmedBytes", 4_096L, "observedBytes", 4_096L,
                        "activityBytes", 8_192L, "resumePhase", "REBUILDING_CHECKSUM")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunItemEntity>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(itemMapper, times(3)).update(isNull(), updates.capture());
        LambdaUpdateWrapper<FileTransferRunItemEntity> activityUpdate = updates.getAllValues().get(2);
        assertThat(updateValue(activityUpdate, "transferred_bytes")).isNull();
        assertThat((Long) updateValue(activityUpdate, "current_bytes_per_second")).isPositive();

        ArgumentCaptor<com.jdragon.studio.infra.model.FileTransferEventIntent> intents =
                ArgumentCaptor.forClass(com.jdragon.studio.infra.model.FileTransferEventIntent.class);
        verify(outboxWriter, atLeastOnce()).appendProgress(intents.capture(), any(), anyBoolean());
        assertThat(intents.getAllValues()).anySatisfy(intent -> {
            assertThat(intent.getPayload()).containsEntry("confirmedBytes", 4_096L);
            assertThat(intent.getPayload()).containsEntry("observedBytes", 4_096L);
            assertThat(intent.getPayload()).containsEntry("activityBytes", 8_192L);
            assertThat(intent.getPayload()).containsEntry("resumePhase", "REBUILDING_CHECKSUM");
            assertThat((Long) intent.getPayload().get("liveBytesPerSecond")).isPositive();
        });
    }

    @Test
    void targetSampleProgressCarriesModesWithoutAdvancingConfirmedBytes() throws Exception {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferOutboxWriter outboxWriter = mock(FileTransferOutboxWriter.class);
        FileTransferRunEntity run = run(910L, 8_192L);
        FileTransferRunItemEntity item = item("item-1", 8_192L);
        item.setId(1_003L);
        item.setRunId(910L);
        when(runMapper.selectById(910L)).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(itemMapper.selectOne(any())).thenReturn(item);
        when(itemMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper,
                new FileTransferStateMutationService(runMapper, itemMapper, metricMapper, outboxWriter));

        listener.onEvent(new TransferEvent(TransferEventType.ITEM_STATUS_CHANGED, Instant.now(),
                "run-1", "item-1", TransferItemStatus.VERIFYING, 8_192L, 8_192L, 1,
                "verifying target sample", null,
                Map.of("activityBytes", 8_192L, "verificationBytes", 0L,
                        "verificationTotalBytes", 8_192L, "verificationPhase", "TARGET_SAMPLE",
                        "verificationModeConfigured", "PARTIAL",
                        "verificationModeEffective", "PARTIAL")));
        Thread.sleep(5L);
        listener.onEvent(new TransferEvent(TransferEventType.ITEM_PROGRESS, Instant.now(),
                "run-1", "item-1", TransferItemStatus.VERIFYING, 8_192L, 8_192L, 1,
                "target sample verification in progress", null,
                Map.of("live", true, "confirmedBytes", 8_192L, "observedBytes", 8_192L,
                        "activityBytes", 12_288L, "verificationBytes", 4_096L,
                        "verificationTotalBytes", 8_192L, "verificationPhase", "TARGET_SAMPLE",
                        "verificationModeConfigured", "PARTIAL",
                        "verificationModeEffective", "PARTIAL")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunItemEntity>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(itemMapper, times(3)).update(isNull(), updates.capture());
        LambdaUpdateWrapper<FileTransferRunItemEntity> verificationUpdate =
                updates.getAllValues().get(updates.getAllValues().size() - 1);
        assertThat(updateValue(verificationUpdate, "transferred_bytes")).isNull();
        assertThat((Long) updateValue(verificationUpdate, "current_bytes_per_second")).isPositive();

        ArgumentCaptor<com.jdragon.studio.infra.model.FileTransferEventIntent> intents =
                ArgumentCaptor.forClass(com.jdragon.studio.infra.model.FileTransferEventIntent.class);
        verify(outboxWriter, atLeastOnce()).appendProgress(intents.capture(), any(), anyBoolean());
        assertThat(intents.getAllValues()).anySatisfy(intent -> {
            assertThat(intent.getPayload()).containsEntry("verificationPhase", "TARGET_SAMPLE");
            assertThat(intent.getPayload()).containsEntry("verificationModeConfigured", "PARTIAL");
            assertThat(intent.getPayload()).containsEntry("verificationModeEffective", "PARTIAL");
            assertThat(intent.getPayload()).containsEntry("verificationBytes", 4_096L);
            assertThat(intent.getPayload()).containsEntry("verificationTotalBytes", 8_192L);
            assertThat((Long) intent.getPayload().get("liveBytesPerSecond")).isPositive();
        });
    }

    @Test
    void staleTransferringEventCannotResumeAnAlreadyPausedRun() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferRunEntity run = run(908L, 4_096L);
        run.setStatus("PAUSED");
        when(runMapper.selectById(908L)).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("item-1", 4_096L)));
        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper,
                mutationService(runMapper, itemMapper, metricMapper));

        listener.onEvent(new TransferEvent(TransferEventType.ITEM_STATUS_CHANGED, Instant.now(),
                "run-1", "item-1", TransferItemStatus.TRANSFERRING, 4_096L, 8_192L, 1,
                "stale resume event", null, Map.of("activityBytes", 4_096L)));

        verify(itemMapper, org.mockito.Mockito.never())
                .update(isNull(), any(LambdaUpdateWrapper.class));
        verify(runMapper, org.mockito.Mockito.never())
                .update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void restartFromZeroClearsRetainedProgressAndResumedBytes() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferRunEntity run = run(909L, 4_096L);
        when(runMapper.selectById(909L)).thenReturn(run);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("item-1", 4_096L)));
        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper,
                mutationService(runMapper, itemMapper, metricMapper));

        listener.onEvent(new TransferEvent(TransferEventType.ITEM_STATUS_CHANGED, Instant.now(),
                "run-1", "item-1", TransferItemStatus.TRANSFERRING, 0L, 8_192L, 1,
                "legacy checkpoint has no resumable checksum state; transfer restarted", null,
                Map.of("resumePhase", "RESTARTED_FROM_ZERO", "restartReason", "legacy checkpoint")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunItemEntity>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(itemMapper, times(2)).update(isNull(), updates.capture());
        LambdaUpdateWrapper<FileTransferRunItemEntity> statusUpdate = updates.getAllValues().get(0);
        assertThat(updateValue(statusUpdate, "transferred_bytes")).isEqualTo(0L);
        assertThat(updateValue(statusUpdate, "resumed_bytes")).isEqualTo(0L);
        listener.flushSample();
        ArgumentCaptor<FileTransferMetricSampleEntity> sample =
                ArgumentCaptor.forClass(FileTransferMetricSampleEntity.class);
        verify(metricMapper).insert(sample.capture());
        assertThat(sample.getValue().getTransferredBytes()).isZero();
    }

    private FileTransferRunEntity run(Long id, Long transferredBytes) {
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(id);
        run.setTenantId("tenant-a");
        run.setProjectId(10L);
        run.setTransferredBytes(transferredBytes);
        run.setStatus("RUNNING");
        return run;
    }

    private FileTransferStateMutationService mutationService(FileTransferRunMapper runMapper,
                                                               FileTransferRunItemMapper itemMapper,
                                                               FileTransferMetricSampleMapper metricMapper) {
        return new FileTransferStateMutationService(runMapper, itemMapper, metricMapper,
                mock(FileTransferOutboxWriter.class));
    }

    private FileTransferRunItemEntity item(String coreItemId, Long transferredBytes) {
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setCoreItemId(coreItemId);
        item.setTransferredBytes(transferredBytes);
        return item;
    }

    private TransferEvent event(TransferItemStatus status, long transferredBytes) {
        return new TransferEvent(TransferEventType.ITEM_STATUS_CHANGED, Instant.now(), "run-1", "item-1",
                status, transferredBytes, 20L, 1, status.name(), null, Map.of());
    }

    private Object updateValue(LambdaUpdateWrapper<?> update, String column) {
        for (String assignment : update.getSqlSet().split(",")) {
            if (!assignment.contains(column)) {
                continue;
            }
            Matcher matcher = Pattern.compile("MPGENVAL\\d+").matcher(assignment);
            if (matcher.find()) {
                return update.getParamNameValuePairs().get(matcher.group());
            }
        }
        return null;
    }
}
