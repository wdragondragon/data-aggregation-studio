package com.jdragon.studio.test;

import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.service.DataModelIndexRebuildQueueService;
import com.jdragon.studio.infra.service.DataModelSearchIndexService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataModelIndexRebuildQueueServiceRegressionTest {

    @Test
    void shouldMergeRepeatedModelRebuildCommandsBeforeQueueing() throws Exception {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataModelIndexRebuildQueueService service = newService(dataModelMapper);

        service.enqueueModelRebuild(101L);
        service.enqueueModelRebuild(101L);

        assertThat(queueSize(service)).isEqualTo(1);
        assertThat(service.currentStatus().getPendingRebuildCount()).isEqualTo(1);
        assertThat(service.currentStatus().getQueuedCommandCount()).isEqualTo(1);
        assertThat(service.currentStatus().getBusy()).isTrue();
        verifyNoInteractions(dataModelMapper);
    }

    @Test
    void shouldMergeModelRebuildIntoQueuedModelDelete() throws Exception {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataModelIndexRebuildQueueService service = newService(dataModelMapper);

        service.enqueueModelDelete(101L);
        service.enqueueModelRebuild(101L);

        assertThat(queueSize(service)).isEqualTo(1);
        verifyNoInteractions(dataModelMapper);
    }

    @Test
    void shouldMergeModelRebuildIntoQueuedDatasourceDelete() throws Exception {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataModelEntity model = new DataModelEntity();
        model.setId(101L);
        model.setDatasourceId(201L);
        when(dataModelMapper.selectById(101L)).thenReturn(model);

        DataModelIndexRebuildQueueService service = newService(dataModelMapper);

        service.enqueueDatasourceDelete(201L);
        service.enqueueModelRebuild(101L);

        assertThat(queueSize(service)).isEqualTo(1);
    }

    @Test
    void shouldScheduleFiniteWorkersAndAllowLaterBatches() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataModelSearchIndexService searchIndexService = mock(DataModelSearchIndexService.class);
        RecordingExecutor executor = new RecordingExecutor();
        DataModelIndexRebuildQueueService service = new DataModelIndexRebuildQueueService(
                dataModelMapper,
                mock(DatasourceMapper.class),
                searchIndexService,
                executor
        );

        service.enqueueModelDelete(101L);
        service.enqueueModelDelete(102L);

        assertThat(executor.pendingTaskCount()).isEqualTo(1);
        assertThat(service.currentStatus().getBusy()).isTrue();

        executor.runNext();

        assertThat(service.awaitIdle(Duration.ofSeconds(1))).isTrue();
        assertThat(service.currentStatus().getBusy()).isFalse();
        verify(searchIndexService).deleteByModelId(101L);
        verify(searchIndexService).deleteByModelId(102L);

        service.enqueueModelDelete(103L);

        assertThat(executor.pendingTaskCount()).isEqualTo(1);
    }

    private DataModelIndexRebuildQueueService newService(DataModelMapper dataModelMapper) {
        Executor noopExecutor = new Executor() {
            @Override
            public void execute(Runnable command) {
                // Keep the worker dormant; these tests only assert queue-time coalescing.
            }
        };
        return new DataModelIndexRebuildQueueService(
                dataModelMapper,
                mock(DatasourceMapper.class),
                mock(DataModelSearchIndexService.class),
                noopExecutor
        );
    }

    private int queueSize(DataModelIndexRebuildQueueService service) throws Exception {
        Field field = DataModelIndexRebuildQueueService.class.getDeclaredField("commandQueue");
        field.setAccessible(true);
        BlockingQueue<?> queue = (BlockingQueue<?>) field.get(service);
        return queue.size();
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private int pendingTaskCount() {
            return tasks.size();
        }

        private void runNext() {
            tasks.remove(0).run();
        }
    }
}
