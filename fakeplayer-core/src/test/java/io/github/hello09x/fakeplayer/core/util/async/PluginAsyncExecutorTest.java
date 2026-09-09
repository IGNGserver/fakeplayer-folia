package io.github.hello09x.fakeplayer.core.util.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginAsyncExecutorTest {

    @Test
    void workRunsOnThePluginOwnedExecutor() throws Exception {
        var executor = new PluginAsyncExecutor();
        try {
            var threadName = executor.supplyAsync(() -> Thread.currentThread().getName()).get();
            assertTrue(threadName.startsWith("fakeplayer-io-"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void cpuWorkIsAlsoTrackedByThePluginOwnedExecutor() throws Exception {
        var executor = new PluginAsyncExecutor();
        try {
            var threadName = executor.supplyCpuAsync(() -> Thread.currentThread().getName()).get();
            assertTrue(threadName.startsWith("fakeplayer-cpu-"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shutdownRejectsNewWork() {
        var executor = new PluginAsyncExecutor();
        executor.shutdown();

        var failure = executor.supplyAsync(() -> "not run");
        assertTrue(failure.isCompletedExceptionally());
    }

    @Test
    void shutdownInterruptsRunningContinuationBeforeReturning() throws Exception {
        var executor = new PluginAsyncExecutor();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            executor.runAsync(() -> {
                started.countDown();
                try {
                    Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shutdownWaitsForBothPoolsAndCanBeRetriedAfterInterruptIgnoringTasks() throws Exception {
        var executor = new PluginAsyncExecutor();
        var release = new CountDownLatch(1);
        var ioStarted = new CountDownLatch(1);
        var cpuStarted = new CountDownLatch(1);
        var ioInterrupted = new CountDownLatch(1);
        var cpuInterrupted = new CountDownLatch(1);
        var ioFinished = new CountDownLatch(1);
        var cpuFinished = new CountDownLatch(1);
        try {
            executor.runAsync(() -> ignoreInterruptsUntilReleased(
                    release,
                    ioStarted,
                    ioInterrupted,
                    ioFinished
            ));
            executor.supplyCpuAsync(() -> {
                ignoreInterruptsUntilReleased(
                        release,
                        cpuStarted,
                        cpuInterrupted,
                        cpuFinished
                );
                return null;
            });

            assertTrue(ioStarted.await(5, TimeUnit.SECONDS));
            assertTrue(cpuStarted.await(5, TimeUnit.SECONDS));
            assertFalse(executor.shutdown());
            assertTrue(ioInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(cpuInterrupted.await(1, TimeUnit.SECONDS));
            assertFalse(ioFinished.await(100, TimeUnit.MILLISECONDS));
            assertFalse(cpuFinished.await(100, TimeUnit.MILLISECONDS));

            release.countDown();
            assertTrue(executor.shutdown());
            assertTrue(ioFinished.await(1, TimeUnit.SECONDS));
            assertTrue(cpuFinished.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private static void ignoreInterruptsUntilReleased(
            CountDownLatch release,
            CountDownLatch started,
            CountDownLatch interrupted,
            CountDownLatch finished
    ) {
        started.countDown();
        try {
            while (release.getCount() != 0) {
                try {
                    release.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Deliberately model a stubborn task for the shutdown barrier test.
                    interrupted.countDown();
                }
            }
        } finally {
            finished.countDown();
        }
    }
}
