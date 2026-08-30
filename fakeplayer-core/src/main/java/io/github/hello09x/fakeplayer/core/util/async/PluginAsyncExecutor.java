package io.github.hello09x.fakeplayer.core.util.async;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Plugin-owned executors for blocking persistence/network work and small
 * asynchronous transformations. A bounded queue prevents a burst of spawn or
 * skin requests from becoming an unbounded JVM common-pool backlog.
 */
@Singleton
public final class PluginAsyncExecutor {

    private static final int QUEUE_CAPACITY = 256;

    private final ThreadPoolExecutor io;
    private final ThreadPoolExecutor cpu;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    @Inject
    public PluginAsyncExecutor() {
        this.io = new ThreadPoolExecutor(
                2,
                4,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new NamedThreadFactory("io"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        var processors = Runtime.getRuntime().availableProcessors();
        var cpuThreads = Math.max(2, Math.min(4, processors));
        this.cpu = new ThreadPoolExecutor(
                cpuThreads,
                cpuThreads,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new NamedThreadFactory("cpu"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public @NotNull <T> CompletableFuture<T> supplyAsync(@NotNull Supplier<T> supplier) {
        return this.submit(this.io, supplier);
    }

    public @NotNull <T> CompletableFuture<T> supplyCpuAsync(@NotNull Supplier<T> supplier) {
        return this.submit(this.cpu, supplier);
    }

    public @NotNull CompletableFuture<Void> runAsync(@NotNull Runnable runnable) {
        return this.submit(this.io, () -> {
            runnable.run();
            return null;
        });
    }

    public void shutdown() {
        if (!this.shuttingDown.compareAndSet(false, true)) {
            return;
        }
        for (var future : this.pending) {
            future.cancel(false);
        }
        this.pending.clear();
        this.io.shutdownNow();
        this.cpu.shutdownNow();
        awaitTermination(this.io);
        awaitTermination(this.cpu);
    }

    private static void awaitTermination(@NotNull ThreadPoolExecutor executor) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("fakeplayer async executor did not terminate within 5 seconds");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping fakeplayer async executor", interrupted);
        }
    }

    private @NotNull <T> CompletableFuture<T> submit(
            @NotNull ThreadPoolExecutor executor,
            @NotNull Supplier<T> supplier
    ) {
        var future = new CompletableFuture<T>();
        if (this.shuttingDown.get()) {
            future.completeExceptionally(new RejectedExecutionException("fakeplayer async executor is shut down"));
            return future;
        }

        this.pending.add(future);
        try {
            executor.execute(() -> {
                try {
                    if (!future.isCancelled()) {
                        future.complete(supplier.get());
                    }
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                } finally {
                    this.pending.remove(future);
                }
            });
        } catch (RejectedExecutionException rejected) {
            this.pending.remove(future);
            future.completeExceptionally(rejected);
        }
        return future;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private NamedThreadFactory(@NotNull String pool) {
            this.prefix = "fakeplayer-" + pool + "-";
        }

        @Override
        public Thread newThread(@NotNull Runnable runnable) {
            var thread = new Thread(runnable, this.prefix + this.sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
