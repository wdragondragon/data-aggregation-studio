package com.jdragon.studio.test;

import com.jdragon.aggregation.core.plugin.AbstractJobPlugin;
import com.jdragon.studio.worker.runtime.JobContainerCancellation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobContainerCancellationCompatibilityTest {

    @Test
    void shouldCancelLegacyContainerWithoutFrameworkCancelMethod() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        LegacyContainer container = new LegacyContainer(started);
        container.startThreads();
        assertTrue(started.await(1, TimeUnit.SECONDS));

        JobContainerCancellation.cancel(container);
        JobContainerCancellation.cancel(container);
        container.readerThread.join(1500L);
        container.writerThread.join(1500L);

        assertFalse(container.readerThread.isAlive());
        assertFalse(container.writerThread.isAlive());
        assertEquals(1, container.readerJobPlugin.destroyCount.get());
        assertEquals(1, container.writerJobPlugin.destroyCount.get());
    }

    private static final class LegacyContainer {
        private final DestroyAwarePlugin readerJobPlugin = new DestroyAwarePlugin();
        private final DestroyAwarePlugin writerJobPlugin = new DestroyAwarePlugin();
        private final Thread readerThread;
        private final Thread writerThread;

        private LegacyContainer(CountDownLatch started) {
            readerThread = interruptibleThread(started);
            writerThread = interruptibleThread(started);
        }

        private void startThreads() {
            readerThread.start();
            writerThread.start();
        }
    }

    private static Thread interruptibleThread(CountDownLatch started) {
        return new Thread(() -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static final class DestroyAwarePlugin extends AbstractJobPlugin {
        private final AtomicInteger destroyCount = new AtomicInteger();

        @Override
        public void destroy() {
            destroyCount.incrementAndGet();
        }
    }
}
