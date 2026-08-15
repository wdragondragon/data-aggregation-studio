package com.jdragon.studio.worker.filetransfer;

import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileTransferTaskMdcTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void parallelTransferTasksUseCapturedRunLogContextAndRestoreWorkerThreadContext() throws Exception {
        PluginRuntimeSession pluginSession = mock(PluginRuntimeSession.class);
        when(pluginSession.bind(any(Runnable.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicReference<String> firstSeen = new AtomicReference<String>();
        AtomicReference<String> secondSeen = new AtomicReference<String>();
        Runnable first = FileTransferNodeExecutor.bindTransferContext(pluginSession,
                Map.of("runLogId", "run-a", "runLogPath", "a.log"),
                () -> firstSeen.set(MDC.get("runLogId")));
        Runnable second = FileTransferNodeExecutor.bindTransferContext(pluginSession,
                Map.of("runLogId", "run-b", "runLogPath", "b.log"),
                () -> secondSeen.set(MDC.get("runLogId")));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> afterFirst = new AtomicReference<String>();
            AtomicReference<String> afterSecond = new AtomicReference<String>();
            executor.submit(() -> {
                MDC.put("runLogId", "worker-thread");
                first.run();
                afterFirst.set(MDC.get("runLogId"));
                second.run();
                afterSecond.set(MDC.get("runLogId"));
                MDC.clear();
            }).get(5L, TimeUnit.SECONDS);

            assertThat(firstSeen.get()).isEqualTo("run-a");
            assertThat(secondSeen.get()).isEqualTo("run-b");
            assertThat(afterFirst.get()).isEqualTo("worker-thread");
            assertThat(afterSecond.get()).isEqualTo("worker-thread");
        } finally {
            executor.shutdownNow();
        }
    }
}
