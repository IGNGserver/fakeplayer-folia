package io.github.hello09x.fakeplayer.core.util.async;

import org.junit.jupiter.api.Test;

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
}
