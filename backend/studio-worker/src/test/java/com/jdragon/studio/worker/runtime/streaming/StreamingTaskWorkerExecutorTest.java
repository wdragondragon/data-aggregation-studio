package com.jdragon.studio.worker.runtime.streaming;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.aggregation.core.statistics.communication.Communication;
import com.jdragon.aggregation.core.streaming.job.StreamingBatch;
import com.jdragon.aggregation.core.streaming.job.StreamingCheckpoint;
import com.jdragon.aggregation.core.streaming.job.StreamingJobContainer;
import com.jdragon.aggregation.core.streaming.job.StreamingJobListener;
import com.jdragon.aggregation.core.streaming.job.StreamingMetricsSnapshot;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskStreamingOptions;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.RuntimeResourceRevisionService;
import com.jdragon.studio.infra.service.StreamingTaskCoordinatorService;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingTaskWorkerExecutorTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(DispatchTaskEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "streaming-worker-test"),
                    DispatchTaskEntity.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void resetOffsetIsEnabledOnlyForFirstAttemptOfLogicalRun() {
        CollectionTaskAssemblerService assembler = mock(CollectionTaskAssemblerService.class);
        StreamingTaskWorkerExecutor executor = executor(assembler);
        CollectionTaskDefinitionView task = new CollectionTaskDefinitionView();
        CollectionTaskStreamingOptions options = new CollectionTaskStreamingOptions();
        options.setResetOffset(true);
        task.setStreamingOptions(options);
        Map<String, Object> readerConfig = new LinkedHashMap<String, Object>();
        Map<String, Object> reader = new LinkedHashMap<String, Object>();
        reader.put("type", "kafka");
        reader.put("config", readerConfig);
        Map<String, Object> assembled = new LinkedHashMap<String, Object>();
        assembled.put("reader", reader);
        when(assembler.assemble(task)).thenReturn(assembled);

        try {
            Map<String, Object> first = ReflectionTestUtils.invokeMethod(
                    executor, "configureStreamingJob", task, dispatch(1));
            Map<String, Object> firstConfig = (Map<String, Object>)
                    ((Map<String, Object>) first.get("reader")).get("config");
            assertEquals(Boolean.TRUE, firstConfig.get("resetOffset"));

            Map<String, Object> recovered = ReflectionTestUtils.invokeMethod(
                    executor, "configureStreamingJob", task, dispatch(2));
            Map<String, Object> recoveredConfig = (Map<String, Object>)
                    ((Map<String, Object>) recovered.get("reader")).get("config");
            assertEquals(Boolean.FALSE, recoveredConfig.get("resetOffset"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void stopTimeoutForcesFormalContainerCancellation() throws Exception {
        StreamingTaskWorkerExecutor executor = executor(mock(CollectionTaskAssemblerService.class));
        StreamingJobContainer container = mock(StreamingJobContainer.class);
        Class<?> activeType = Class.forName(
                StreamingTaskWorkerExecutor.class.getName() + "$ActiveStreamingAttempt");
        Constructor<?> constructor = activeType.getDeclaredConstructor(
                StreamingTaskWorkerExecutor.class, Long.class);
        constructor.setAccessible(true);
        Object active = constructor.newInstance(executor, 88L);

        try {
            ReflectionTestUtils.invokeMethod(active, "attach", container, 10L);
            ReflectionTestUtils.invokeMethod(active, "requestStop");

            verify(container).requestStop();
            verify(container, timeout(1_000L)).cancel();
        } finally {
            ReflectionTestUtils.invokeMethod(active, "close");
            executor.shutdown();
        }
    }

    @Test
    void checkpointReportRetriesUntilCoordinatorAcceptsCommittedOffset() {
        StreamingTaskCoordinatorService coordinator = mock(StreamingTaskCoordinatorService.class);
        when(coordinator.batchCommitted(anyLong(), any()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(true);
        StreamingTaskWorkerExecutor executor = executor(
                mock(CollectionTaskAssemblerService.class), coordinator);
        StreamingJobContainer container = mock(StreamingJobContainer.class);
        when(container.getSourceMetrics()).thenReturn(StreamingMetricsSnapshot.empty());
        StreamingJobListener listener = listener(executor, 91L, container);

        try {
            listener.onBatchCommitted(batch("batch-91"), mock(Communication.class), 10L);

            verify(coordinator, times(3)).batchCommitted(anyLong(), any());
            verify(container, times(0)).requestStop();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void stopRequestCancelsCheckpointReportRetryAfterKafkaCommit() {
        StreamingTaskCoordinatorService coordinator = mock(StreamingTaskCoordinatorService.class);
        when(coordinator.batchCommitted(anyLong(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        StreamingTaskWorkerExecutor executor = executor(
                mock(CollectionTaskAssemblerService.class), coordinator);
        StreamingJobContainer container = mock(StreamingJobContainer.class);
        when(container.isStopRequested()).thenReturn(false, true);
        StreamingJobListener listener = listener(executor, 92L, container);

        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> listener.onBatchCommitted(batch("batch-92"), mock(Communication.class), 10L));
            assertTrue(failure.getMessage().contains("checkpoint report stopped"));
            verify(coordinator).batchCommitted(anyLong(), any());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectedCheckpointFencesAttemptAndStopsFurtherConsumption() {
        StreamingTaskCoordinatorService coordinator = mock(StreamingTaskCoordinatorService.class);
        when(coordinator.batchCommitted(anyLong(), any())).thenReturn(false);
        StreamingTaskWorkerExecutor executor = executor(
                mock(CollectionTaskAssemblerService.class), coordinator);
        StreamingJobContainer container = mock(StreamingJobContainer.class);
        StreamingJobListener listener = listener(executor, 93L, container);

        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> listener.onBatchCommitted(batch("batch-93"), mock(Communication.class), 10L));
            assertTrue(failure.getMessage().contains("fenced after Kafka checkpoint commit"));
            verify(container).requestStop();
            verify(coordinator).batchCommitted(anyLong(), any());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void failedAttemptIsRemovedBeforeCoordinatorFailureReport() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        StreamingTaskCoordinatorService coordinator = mock(StreamingTaskCoordinatorService.class);
        AtomicReference<StreamingTaskWorkerExecutor> executorRef =
                new AtomicReference<StreamingTaskWorkerExecutor>();
        doAnswer(invocation -> {
            assertEquals(0, executorRef.get().activeAttemptCount());
            return null;
        }).when(coordinator).attemptFailed(anyLong(), anyString(), anyString());
        StreamingTaskWorkerExecutor executor = executor(
                dispatchTaskMapper, runRecordMapper,
                mock(CollectionTaskAssemblerService.class), coordinator,
                mock(RunLogFileService.class));
        executorRef.set(executor);
        DispatchTaskEntity task = dispatch(1);
        task.setId(501L);
        task.setCollectionTaskId(601L);
        task.setTenantId("default");
        task.setProjectId(701L);
        task.getPayloadJson().put("streamAttemptId", 801L);
        task.getPayloadJson().put("streamRunId", 901L);
        task.getPayloadJson().put("streamGeneration", 1L);

        try {
            executor.execute(task, null);

            ArgumentCaptor<String> errorCode = ArgumentCaptor.forClass(String.class);
            verify(coordinator).attemptFailed(anyLong(), errorCode.capture(), anyString());
            assertEquals("IllegalStateException", errorCode.getValue());
            assertEquals(0, executor.activeAttemptCount());
        } finally {
            executor.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private StreamingJobListener listener(StreamingTaskWorkerExecutor executor,
                                          Long attemptId,
                                          StreamingJobContainer container) {
        return ReflectionTestUtils.invokeMethod(executor, "listener", attemptId,
                new StreamingMetricsAccumulator(), container);
    }

    private StreamingBatch batch(String batchId) {
        return new StreamingBatch(Collections.emptyList(),
                new StreamingCheckpoint(batchId, Map.<String, Object>of("offset", 1L)),
                1_700_000_000_000L, 0L);
    }

    private StreamingTaskWorkerExecutor executor(CollectionTaskAssemblerService assembler) {
        return executor(assembler, mock(StreamingTaskCoordinatorService.class));
    }

    private StreamingTaskWorkerExecutor executor(CollectionTaskAssemblerService assembler,
                                                  StreamingTaskCoordinatorService coordinator) {
        return executor(mock(DispatchTaskMapper.class), mock(RunRecordMapper.class),
                assembler, coordinator, mock(RunLogFileService.class));
    }

    private StreamingTaskWorkerExecutor executor(DispatchTaskMapper dispatchTaskMapper,
                                                  RunRecordMapper runRecordMapper,
                                                  CollectionTaskAssemblerService assembler,
                                                  StreamingTaskCoordinatorService coordinator,
                                                  RunLogFileService runLogFileService) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInstanceId("instance-m4");
        return new StreamingTaskWorkerExecutor(
                dispatchTaskMapper,
                runRecordMapper,
                mock(CollectionTaskService.class),
                assembler,
                mock(RuntimeResourceRevisionService.class),
                coordinator,
                runLogFileService,
                properties,
                new ClusterInstanceIdentity(properties)) {
            @Override
            protected boolean waitForCheckpointRetry(StreamingJobContainer container, long delayMillis) {
                return !container.isStopRequested() && !container.isCancellationRequested();
            }
        };
    }

    private DispatchTaskEntity dispatch(int attemptNo) {
        DispatchTaskEntity dispatch = new DispatchTaskEntity();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("streamAttemptNo", attemptNo);
        payload.put("groupId", "studio.default.100");
        dispatch.setPayloadJson(payload);
        return dispatch;
    }
}
