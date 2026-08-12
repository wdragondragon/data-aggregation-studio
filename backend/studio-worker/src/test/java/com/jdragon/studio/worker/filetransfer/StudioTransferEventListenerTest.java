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
import static org.mockito.ArgumentMatchers.isNull;
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
        when(itemMapper.selectList(any())).thenReturn(List.of(
                item("completed-item", 20L), item("retry-item", 10L)));

        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper);
        listener.flushSample();

        ArgumentCaptor<FileTransferMetricSampleEntity> sample =
                ArgumentCaptor.forClass(FileTransferMetricSampleEntity.class);
        verify(metricMapper).insert(sample.capture());
        assertThat(sample.getValue().getTransferredBytes()).isEqualTo(30L);
    }

    @Test
    void finalSampleUsesPersistedRunCounters() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferRunEntity run = run(902L, 0L);
        run.setStatus("SUCCESS");
        run.setSuccessFiles(2L);
        run.setSkippedFiles(1L);
        run.setFailedFiles(1L);
        when(runMapper.selectById(902L)).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("completed-item", 30L)));

        new StudioTransferEventListener(run, runMapper, itemMapper, metricMapper).flushSample();

        ArgumentCaptor<FileTransferMetricSampleEntity> sample =
                ArgumentCaptor.forClass(FileTransferMetricSampleEntity.class);
        verify(metricMapper).insert(sample.capture());
        assertThat(sample.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(sample.getValue().getBytesPerSecond()).isPositive();
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
        when(itemMapper.selectList(any())).thenReturn(List.of(item("item-1", 0L)));
        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper);

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

    private FileTransferRunEntity run(Long id, Long transferredBytes) {
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(id);
        run.setTenantId("tenant-a");
        run.setProjectId(10L);
        run.setTransferredBytes(transferredBytes);
        run.setStatus("RUNNING");
        return run;
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
