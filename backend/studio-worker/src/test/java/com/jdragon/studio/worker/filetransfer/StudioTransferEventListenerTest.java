package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.infra.entity.FileTransferMetricSampleEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
