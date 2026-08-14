package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.infra.entity.FileTransferMetricSampleEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferEventOutboxEntity;
import com.jdragon.studio.infra.mapper.FileTransferEventOutboxMapper;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferStateMutationServiceTest {

    @BeforeAll
    static void initializeLambdaMetadata() {
        if (TableInfoHelper.getTableInfo(FileTransferRunEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "run-mutation-test"),
                    FileTransferRunEntity.class);
        }
        if (TableInfoHelper.getTableInfo(FileTransferRunItemEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "item-mutation-test"),
                    FileTransferRunItemEntity.class);
        }
    }

    @Test
    void outboxFailurePropagatesFromAtomicMutationBoundary() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferEventOutboxMapper outboxMapper = mock(FileTransferEventOutboxMapper.class);
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(100L);
        run.setTenantId("tenant-a");
        run.setProjectId(10L);
        when(runMapper.insert(run)).thenReturn(1);
        when(outboxMapper.insert(any(FileTransferEventOutboxEntity.class)))
                .thenThrow(new IllegalStateException("outbox unavailable"));
        FileTransferOutboxWriter writer = new FileTransferOutboxWriter(outboxMapper, new StudioPlatformProperties(),
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        FileTransferStateMutationService service = new FileTransferStateMutationService(
                runMapper, itemMapper, metricMapper, writer);

        assertThrows(IllegalStateException.class, () -> service.insertRunAndEvent(run));
    }

    @Test
    void missingBusinessRowsDoNotCreateMetricOrOutboxRecords() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferOutboxWriter writer = mock(FileTransferOutboxWriter.class);
        FileTransferStateMutationService service = new FileTransferStateMutationService(
                runMapper, itemMapper, metricMapper, writer);
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setId(200L);
        item.setRunId(100L);

        service.insertOrUpdatePlanItemAndEvent(item, false);
        service.persistMetricSampleAndRunEvent(100L,
                new LambdaUpdateWrapper<FileTransferRunEntity>()
                        .set(FileTransferRunEntity::getStatus, "RUNNING")
                        .eq(FileTransferRunEntity::getId, 100L),
                new FileTransferMetricSampleEntity());

        verify(metricMapper, never()).insert(any(FileTransferMetricSampleEntity.class));
        verify(writer, never()).append(any());
        verify(writer, never()).appendProgress(any(), anyString(), anyBoolean());
    }

    @Test
    void existingPlanItemBindsSourceSnapshotAsJson() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferOutboxWriter writer = mock(FileTransferOutboxWriter.class);
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(100L);
        run.setTenantId("tenant-a");
        run.setProjectId(10L);
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setId(200L);
        item.setRunId(100L);
        item.setSourceSnapshotJson(java.util.Map.of("path", "/resume.bin", "size", 1024L));
        when(itemMapper.update(org.mockito.ArgumentMatchers.isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(runMapper.selectById(100L)).thenReturn(run);
        FileTransferStateMutationService service = new FileTransferStateMutationService(
                runMapper, itemMapper, metricMapper, writer);

        service.insertOrUpdatePlanItemAndEvent(item, false);

        org.mockito.ArgumentCaptor<LambdaUpdateWrapper<FileTransferRunItemEntity>> update =
                org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(itemMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("JacksonTypeHandler");
    }
}
