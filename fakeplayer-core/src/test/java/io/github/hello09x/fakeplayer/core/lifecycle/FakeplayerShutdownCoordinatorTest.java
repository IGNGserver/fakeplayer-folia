package io.github.hello09x.fakeplayer.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.hello09x.fakeplayer.core.util.async.PluginAsyncExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeplayerShutdownCoordinatorTest {

    @Test
    void runsRegisteredPhasesInOrderAndOnlyOnce() {
        var calls = new ArrayList<String>();
        var coordinator = FakeplayerShutdownCoordinator.forTesting();
        coordinator.add(FakeplayerShutdownCoordinator.Phase.CLEANUP, "cleanup", () -> calls.add("cleanup"));
        coordinator.add(FakeplayerShutdownCoordinator.Phase.STOP_ASYNC, "async", () -> calls.add("async"));
        coordinator.add(FakeplayerShutdownCoordinator.Phase.STOP_ACCEPTING, "accepting", () -> calls.add("accepting"));
        coordinator.add(FakeplayerShutdownCoordinator.Phase.RECOVER_JOURNAL, "recover", () -> calls.add("recover"));

        coordinator.shutdown();
        coordinator.shutdown();

        assertEquals(List.of("accepting", "async", "recover", "cleanup"), calls);
    }

    @Test
    void continuesAfterAFailedStepAndRejectsLateRegistration() {
        var calls = new ArrayList<String>();
        var coordinator = FakeplayerShutdownCoordinator.forTesting();
        coordinator.add(
                FakeplayerShutdownCoordinator.Phase.STOP_ACCEPTING,
                "failed",
                () -> {
                    calls.add("failed");
                    throw new IllegalStateException("injected");
                }
        );
        coordinator.add(FakeplayerShutdownCoordinator.Phase.CLEANUP, "cleanup", () -> calls.add("cleanup"));

        coordinator.shutdown();

        assertEquals(List.of("failed", "cleanup"), calls);
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.add(FakeplayerShutdownCoordinator.Phase.CLEANUP, "late", () -> {})
        );
    }

    @Test
    void partialEnableOnlyRunsComponentsThatWereActuallyRegistered() {
        var calls = new ArrayList<String>();
        var coordinator = FakeplayerShutdownCoordinator.forTesting();
        coordinator.add(FakeplayerShutdownCoordinator.Phase.STOP_ASYNC, "executor", () -> calls.add("executor"));

        coordinator.shutdown();

        assertEquals(List.of("executor"), calls);
    }

    @Test
    void recoveryRunsOnlyAfterAnOwnedAsyncContinuationHasStopped() throws Exception {
        var executor = new PluginAsyncExecutor();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var coordinator = FakeplayerShutdownCoordinator.forTesting();
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

            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.STOP_ASYNC,
                    "executor",
                    executor::shutdown
            );
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.RECOVER_JOURNAL,
                    "recovery",
                    () -> assertTrue(interrupted.getCount() == 0,
                            "journal recovery started before the async continuation stopped")
            );

            coordinator.shutdown();

            assertEquals(0, interrupted.getCount());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void failedAsyncBarrierDefersRecoveryButAllowsSafeCleanupAndCanRetry() throws Exception {
        var executor = new PluginAsyncExecutor();
        var release = new CountDownLatch(1);
        var ioStarted = new CountDownLatch(1);
        var cpuStarted = new CountDownLatch(1);
        var ioFinished = new CountDownLatch(1);
        var cpuFinished = new CountDownLatch(1);
        var recoveryRan = new AtomicBoolean();
        var cleanupRan = new AtomicBoolean();
        var coordinator = FakeplayerShutdownCoordinator.forTesting();
        try {
            executor.runAsync(() -> stubbornTask(release, ioStarted, ioFinished));
            executor.supplyCpuAsync(() -> {
                stubbornTask(release, cpuStarted, cpuFinished);
                return null;
            });
            assertTrue(ioStarted.await(5, TimeUnit.SECONDS));
            assertTrue(cpuStarted.await(5, TimeUnit.SECONDS));

            coordinator.add(FakeplayerShutdownCoordinator.Phase.STOP_ASYNC, "async", () -> {
                if (!executor.shutdown()) {
                    throw new IllegalStateException("async pool still running");
                }
            });
            coordinator.add(FakeplayerShutdownCoordinator.Phase.RECOVER_JOURNAL, "recovery", () -> {
                recoveryRan.set(true);
                assertTrue(ioFinished.getCount() == 0 && cpuFinished.getCount() == 0,
                        "journal recovery started before both async pools terminated");
            });
            coordinator.add(FakeplayerShutdownCoordinator.Phase.CLEANUP, "cleanup", () -> cleanupRan.set(true));

            assertFalse(coordinator.shutdown());
            assertFalse(recoveryRan.get(), "journal recovery must not cross a failed async barrier");
            assertTrue(cleanupRan.get(), "safe cleanup must still run after a failed async barrier");
            assertFalse(ioFinished.await(100, TimeUnit.MILLISECONDS));
            assertFalse(cpuFinished.await(100, TimeUnit.MILLISECONDS));

            release.countDown();
            assertTrue(coordinator.shutdown());
            assertTrue(recoveryRan.get());
            assertTrue(cleanupRan.get());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private static void stubbornTask(
            CountDownLatch release,
            CountDownLatch started,
            CountDownLatch finished
    ) {
        started.countDown();
        try {
            while (release.getCount() != 0) {
                try {
                    release.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Keep the task alive until the test explicitly releases it.
                }
            }
        } finally {
            finished.countDown();
        }
    }
}
