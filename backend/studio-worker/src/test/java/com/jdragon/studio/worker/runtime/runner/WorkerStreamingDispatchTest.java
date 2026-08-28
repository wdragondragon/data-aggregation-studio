package com.jdragon.studio.worker.runtime.runner;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.worker.runtime.WorkflowDispatchNodeResolver;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import com.jdragon.studio.worker.runtime.streaming.StreamingTaskWorkerExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerStreamingDispatchTest {

    @Test
    void streamingDispatchIsNotClaimedWithoutStreamingExecutor() {
        Fixture fixture = fixture();
        try {
            when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(List.of(streamingTask(1L)));

            fixture.runner.pollAndExecute();

            verify(fixture.dispatchTaskMapper, never()).update(
                    any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class));
        } finally {
            fixture.runner.shutdown();
        }
    }

    @Test
    void defaultStreamingSlotsLimitConcurrentAttemptsWithoutUsingFileTransferSlots() throws Exception {
        Fixture fixture = fixture();
        StreamingTaskWorkerExecutor executor = mock(StreamingTaskWorkerExecutor.class);
        fixture.runner.setStreamingTaskWorkerExecutor(executor);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        doAnswer(invocation -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } finally {
                finished.countDown();
            }
            return null;
        }).when(executor).execute(any(DispatchTaskEntity.class), any());
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(List.of(
                streamingTask(11L), streamingTask(12L), streamingTask(13L)));
        when(fixture.dispatchTaskMapper.update(
                any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);

        try {
            fixture.runner.pollAndExecute();

            assertTrue(started.await(2, TimeUnit.SECONDS));
            verify(executor, times(2)).execute(any(DispatchTaskEntity.class), any());
            verify(fixture.dispatchTaskMapper, times(2)).update(
                    any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class));
            Semaphore streamingSlots = (Semaphore) ReflectionTestUtils.getField(fixture.runner, "streamingSlots");
            Semaphore fileTransferSlots = (Semaphore) ReflectionTestUtils.getField(fixture.runner, "fileTransferSlots");
            assertEquals(0, streamingSlots.availablePermits());
            assertEquals(4, fileTransferSlots.availablePermits());
        } finally {
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            fixture.runner.shutdown();
        }
    }

    @Test
    void terminationRequestInvokesGracefulCallbackWithoutImmediateThreadInterrupt() throws Exception {
        Fixture fixture = fixture();
        StreamingTaskWorkerExecutor executor = mock(StreamingTaskWorkerExecutor.class);
        fixture.runner.setStreamingTaskWorkerExecutor(executor);
        DispatchTaskEntity task = streamingTask(21L);
        DispatchTaskEntity termination = streamingTask(21L);
        termination.setStatus("RUNNING");
        termination.setTerminationRequested(1);
        termination.setWorkerGroupCode("group-50");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger gracefulCalls = new AtomicInteger();
        AtomicBoolean interruptedAfterGracefulCallback = new AtomicBoolean(true);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<Runnable> registrar = invocation.getArgument(1);
            registrar.accept(() -> {
                gracefulCalls.incrementAndGet();
                release.countDown();
            });
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            interruptedAfterGracefulCallback.set(Thread.currentThread().isInterrupted());
            finished.countDown();
            return null;
        }).when(executor).execute(any(DispatchTaskEntity.class), any());
        when(fixture.dispatchTaskMapper.selectList(any()))
                .thenReturn(List.of(task), List.of(termination));
        when(fixture.dispatchTaskMapper.update(
                any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);

        try {
            fixture.runner.pollAndExecute();
            assertTrue(started.await(2, TimeUnit.SECONDS));

            fixture.runner.pollTerminationRequests();

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(1, gracefulCalls.get());
            assertFalse(interruptedAfterGracefulCallback.get());
        } finally {
            release.countDown();
            fixture.runner.shutdown();
        }
    }

    @Test
    void oldBootStreamingDispatchUsesCoordinatorRecoveryPath() {
        Fixture fixture = fixture();
        StreamingTaskWorkerExecutor executor = mock(StreamingTaskWorkerExecutor.class);
        fixture.runner.setStreamingTaskWorkerExecutor(executor);
        DispatchTaskEntity interrupted = streamingTask(31L);
        interrupted.setStatus("RUNNING");
        interrupted.setWorkerInstanceId(fixture.identity.instanceId());
        interrupted.setWorkerBootId("old-boot");
        interrupted.setWorkerGroupCode("group-50");
        when(fixture.dispatchTaskMapper.selectList(any()))
                .thenReturn(List.of(interrupted), Collections.emptyList(), Collections.emptyList());

        try {
            fixture.runner.recoverLeasedRunningTasks();

            verify(executor).recoverInterrupted(interrupted);
        } finally {
            fixture.runner.shutdown();
        }
    }

    private Fixture fixture() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("C50");
        properties.setWorkerGroupCode("group-50");
        properties.setWorkerCode("worker-50");
        properties.setInstanceId("instance-50");
        properties.getWorker().getStreaming().setMaxConcurrent(2);
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        WorkerAuthorizationService authorization = mock(WorkerAuthorizationService.class);
        when(authorization.isProjectRuntimeClusterGrantEnabled(any(), any(), any())).thenReturn(true);
        when(authorization.isRuntimeClusterAuthorizedForProject(any(), any(), any())).thenReturn(true);
        when(workerLeaseMapper.selectOne(any())).thenReturn(activeLease());
        ClusterInstanceIdentity identity = new ClusterInstanceIdentity(properties);
        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                dispatchTaskMapper,
                workerLeaseMapper,
                mock(RunRecordMapper.class),
                Collections.<NodeExecutor>emptyList(),
                mock(ExecutionEventPublisher.class),
                properties,
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                mock(CollectionTaskAssemblerService.class),
                mock(RunLogFileService.class),
                authorization,
                identity,
                mock(WorkflowDispatchNodeResolver.class));
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        when(clusterMapper.selectList(any())).thenReturn(List.of(runtimeCluster()));
        runner.setRuntimeClusterMapper(clusterMapper);
        ReflectionTestUtils.setField(runner, "acceptingTasks", true);
        return new Fixture(runner, dispatchTaskMapper, workerLeaseMapper, identity);
    }

    private DispatchTaskEntity streamingTask(Long id) {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(id);
        task.setTenantId("tenant-a");
        task.setProjectId(10L);
        task.setStatus("QUEUED");
        task.setTerminationRequested(0);
        task.setExecutionType(DispatchExecutionType.STREAMING_COLLECTION_TASK.name());
        task.setTargetClusterId(50L);
        task.setPayloadJson(new LinkedHashMap<String, Object>());
        return task;
    }

    private RuntimeClusterEntity runtimeCluster() {
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(50L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C50");
        cluster.setEnabled(1);
        return cluster;
    }

    private WorkerLeaseEntity activeLease() {
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setStatus("ONLINE");
        lease.setLastHeartbeatAt(LocalDateTime.now());
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        return lease;
    }

    private static final class Fixture {
        private final WorkerLifecycleRunner runner;
        private final DispatchTaskMapper dispatchTaskMapper;
        private final WorkerLeaseMapper workerLeaseMapper;
        private final ClusterInstanceIdentity identity;

        private Fixture(WorkerLifecycleRunner runner,
                        DispatchTaskMapper dispatchTaskMapper,
                        WorkerLeaseMapper workerLeaseMapper,
                        ClusterInstanceIdentity identity) {
            this.runner = runner;
            this.dispatchTaskMapper = dispatchTaskMapper;
            this.workerLeaseMapper = workerLeaseMapper;
            this.identity = identity;
        }
    }
}
