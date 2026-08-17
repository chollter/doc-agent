package com.gcll.ticketagent.learning;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Learning test: connect JVM lock concepts to agent execution.
 *
 * <p>The point is not to observe JVM mark words directly. The project has parallel
 * worker execution and run-level locking. A shared synchronized section serializes
 * otherwise parallel work, which is the practical reason to care about lock
 * contention, blocking, and lock scope.
 */
class JvmLockContentionConceptTest {

    @Test
    void sharedSynchronizedSectionSerializesParallelWorkers() throws Exception {
        int workers = 4;
        Duration workCost = Duration.ofMillis(80);

        long lockedMs = runWorkers(workers, () -> lockedWork(workCost));
        long independentMs = runWorkers(workers, () -> independentWork(workCost));

        assertThat(lockedMs)
                .as("a shared synchronized section makes worker work close to serial")
                .isGreaterThan(independentMs * 2);
    }

    private final Object sharedLock = new Object();

    private void lockedWork(Duration cost) {
        synchronized (sharedLock) {
            sleep(cost);
        }
    }

    private void independentWork(Duration cost) {
        sleep(cost);
    }

    private long runWorkers(int workers, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                task.run();
                return null;
            }));
        }

        long begin = System.nanoTime();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdownNow();
        return Duration.ofNanos(System.nanoTime() - begin).toMillis();
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
