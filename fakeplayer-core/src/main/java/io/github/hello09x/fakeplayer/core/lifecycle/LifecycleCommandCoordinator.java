package io.github.hello09x.fakeplayer.core.lifecycle;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.entity.Fakeplayer;
import io.github.hello09x.fakeplayer.core.repository.LifecycleCommandTransactionRepository;
import io.github.hello09x.fakeplayer.core.util.Commands;
import io.github.hello09x.fakeplayer.core.util.async.PluginAsyncExecutor;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandTransaction.State;

/**
 * Executes lifecycle console commands against a durable write-ahead journal.
 *
 * <p>External Bukkit commands cannot participate in the SQLite transaction.
 * The coordinator therefore provides at-least-once recovery and requires
 * idempotent compensation/finalizer commands. A checkpoint is written before
 * every pre-spawn command, so an uncertain crash outcome is compensated; exit
 * and rollback checkpoints advance only after dispatch succeeds, so recovery
 * safely retries the current idempotent command.</p>
 */
@Singleton
public final class LifecycleCommandCoordinator {

    private static final Logger LOG = Main.getInstance().getLogger();

    private final LifecycleCommandTransactionRepository repository;
    private final PluginAsyncExecutor asyncExecutor;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    @Inject
    public LifecycleCommandCoordinator(
            @NotNull LifecycleCommandTransactionRepository repository,
            @NotNull PluginAsyncExecutor asyncExecutor
    ) {
        this.repository = repository;
        this.asyncExecutor = asyncExecutor;
    }

    public record Handle(@NotNull UUID id, @NotNull String fakeName) {
    }

    public @NotNull CompletableFuture<Handle> prepareAsync(
            @NotNull Fakeplayer fake,
            @NotNull List<String> preSpawn,
            @NotNull List<String> preSpawnRollback,
            @NotNull List<String> postQuit,
            @NotNull List<String> afterQuit
    ) {
        if (this.shuttingDown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Lifecycle coordinator is shutting down"));
        }

        var forward = format(fake, preSpawn);
        var rollback = format(fake, preSpawnRollback);
        if (forward.size() != rollback.size()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Every pre-spawn command requires one idempotent rollback command"
            ));
        }
        var tx = new LifecycleCommandTransaction(
                UUID.randomUUID(),
                fake.getName(),
                fake.getUUID().toString(),
                fake.getCreator().getName(),
                State.SPAWNING,
                rollback,
                0,
                -1,
                format(fake, postQuit),
                0,
                format(fake, afterQuit),
                0
        );
        return this.asyncExecutor.supplyAsync(() -> {
            repository.insert(tx);
            return new Handle(tx.id(), tx.fakeName());
        });
    }

    public @NotNull CompletableFuture<Void> runPreSpawnAsync(
            @NotNull Handle handle,
            @NotNull Fakeplayer fake,
            @NotNull List<String> preSpawn
    ) {
        return this.runPreAt(handle, format(fake, preSpawn), 0);
    }

    public @NotNull CompletableFuture<Void> activateAsync(@NotNull Handle handle) {
        return this.asyncExecutor.runAsync(() -> repository.markActive(handle.id()));
    }

    public @NotNull CompletableFuture<Void> rollbackAsync(@NotNull Handle handle) {
        return this.asyncExecutor.supplyAsync(() -> repository.select(handle.id()))
                .thenCompose(tx -> {
                    if (tx == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    var next = tx.state() == State.ROLLING_BACK
                            ? tx.rollbackNext()
                            : tx.preAttempted() - 1;
                    return this.asyncExecutor.runAsync(() -> repository.markRollingBack(handle.id(), next))
                            .thenCompose(ignored -> this.runRollbackAt(handle, tx.rollbackCommands(), next));
                });
    }

    public @NotNull CompletableFuture<Void> runPostQuitAsync(@NotNull Handle handle) {
        return this.asyncExecutor.runAsync(() -> repository.markQuitting(handle.id()))
                .thenCompose(ignored -> this.load(handle))
                .thenCompose(tx -> this.runPostQuitAt(handle, tx, tx.postQuitNext()));
    }

    /** Retry any unfinished post-quit command, then run after-quit and commit. */
    public @NotNull CompletableFuture<Void> finishQuitAsync(@NotNull Handle handle) {
        return this.asyncExecutor.runAsync(() -> repository.markQuitting(handle.id()))
                .thenCompose(ignored -> this.load(handle))
                .thenCompose(tx -> this.runPostQuitAt(handle, tx, tx.postQuitNext()))
                .thenCompose(ignored -> this.load(handle))
                .thenCompose(tx -> this.runAfterQuitAt(handle, tx, tx.afterQuitNext()))
                // The paired pre-spawn compensation is also the durable
                // release operation for a successful fake-player lifetime.
                // This guarantees that prerequisites such as whitelist or
                // permission grants do not survive a normal quit or crash.
                .thenCompose(ignored -> this.load(handle))
                .thenCompose(tx -> {
                    var next = tx.preAttempted() - 1;
                    return this.asyncExecutor.runAsync(() -> repository.markRollingBack(handle.id(), next))
                            .thenCompose(marked -> this.runRollbackAt(handle, tx.rollbackCommands(), next));
                });
    }

    public void beginShutdown() {
        this.shuttingDown.set(true);
    }

    /**
     * Recover every unfinished transaction on enable or after async executors
     * have stopped during disable. This method must run on the server's
     * lifecycle/global thread because it dispatches commands synchronously.
     */
    public void recoverPendingSynchronously() {
        var failures = new ArrayList<Throwable>();
        for (var tx : repository.selectAll()) {
            try {
                switch (tx.state()) {
                    case SPAWNING, ROLLING_BACK -> rollbackSynchronously(tx);
                    case ACTIVE, QUITTING -> finishQuitSynchronously(tx);
                }
            } catch (Throwable failure) {
                failures.add(failure);
                LOG.severe("Failed to recover lifecycle transaction " + tx.id() + " for "
                        + tx.fakeName() + ": " + failure);
            }
        }
        if (!failures.isEmpty()) {
            var combined = new IllegalStateException(
                    "Failed to recover " + failures.size() + " lifecycle command transaction(s); refusing unsafe startup"
            );
            failures.forEach(combined::addSuppressed);
            throw combined;
        }
    }

    private @NotNull CompletableFuture<Void> runPreAt(
            @NotNull Handle handle,
            @NotNull List<String> commands,
            int index
    ) {
        if (index >= commands.size()) {
            return CompletableFuture.completedFuture(null);
        }
        if (this.shuttingDown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Lifecycle coordinator is shutting down"));
        }
        // Write ahead. If the process dies after this checkpoint, recovery
        // compensates this command whether dispatch completed or not.
        return this.asyncExecutor.runAsync(() -> repository.markPreAttempted(handle.id(), index + 1))
                .thenCompose(ignored -> dispatchAsync(handle.fakeName(), commands.get(index)))
                .thenCompose(ignored -> this.runPreAt(handle, commands, index + 1));
    }

    private @NotNull CompletableFuture<Void> runRollbackAt(
            @NotNull Handle handle,
            @NotNull List<String> commands,
            int index
    ) {
        if (index < 0) {
            return this.asyncExecutor.runAsync(() -> repository.delete(handle.id()));
        }
        return dispatchAsync(handle.fakeName(), commands.get(index))
                .thenCompose(ignored -> this.asyncExecutor.runAsync(
                        () -> repository.updateRollbackNext(handle.id(), index - 1)
                ))
                .thenCompose(ignored -> this.runRollbackAt(handle, commands, index - 1));
    }

    private @NotNull CompletableFuture<Void> runPostQuitAt(
            @NotNull Handle handle,
            @NotNull LifecycleCommandTransaction tx,
            int index
    ) {
        if (index >= tx.postQuitCommands().size()) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatchAsync(handle.fakeName(), tx.postQuitCommands().get(index))
                .thenCompose(ignored -> this.asyncExecutor.runAsync(
                        () -> repository.updatePostQuitNext(handle.id(), index + 1)
                ))
                .thenCompose(ignored -> this.runPostQuitAt(handle, tx, index + 1));
    }

    private @NotNull CompletableFuture<Void> runAfterQuitAt(
            @NotNull Handle handle,
            @NotNull LifecycleCommandTransaction tx,
            int index
    ) {
        if (index >= tx.afterQuitCommands().size()) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatchAsync(handle.fakeName(), tx.afterQuitCommands().get(index))
                .thenCompose(ignored -> this.asyncExecutor.runAsync(
                        () -> repository.updateAfterQuitNext(handle.id(), index + 1)
                ))
                .thenCompose(ignored -> this.runAfterQuitAt(handle, tx, index + 1));
    }

    private @NotNull CompletableFuture<LifecycleCommandTransaction> load(@NotNull Handle handle) {
        return this.asyncExecutor.supplyAsync(() -> Objects.requireNonNull(
                repository.select(handle.id()),
                "Missing lifecycle transaction " + handle.id()
        ));
    }

    private @NotNull CompletableFuture<Void> dispatchAsync(@NotNull String fakeName, @NotNull String command) {
        if (this.shuttingDown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Lifecycle coordinator is shutting down"));
        }
        // Always route through the global/main scheduler. CompletableFuture
        // continuations can run on a database executor even on Paper.
        return Tasks.callGlobal(Main.getInstance(), () -> {
            dispatchDirect(fakeName, command);
            return null;
        });
    }

    private void rollbackSynchronously(@NotNull LifecycleCommandTransaction tx) {
        var next = tx.state() == State.ROLLING_BACK ? tx.rollbackNext() : tx.preAttempted() - 1;
        repository.markRollingBack(tx.id(), next);
        for (int i = next; i >= 0; i--) {
            dispatchDirect(tx.fakeName(), tx.rollbackCommands().get(i));
            repository.updateRollbackNext(tx.id(), i - 1);
        }
        repository.delete(tx.id());
    }

    private void finishQuitSynchronously(@NotNull LifecycleCommandTransaction tx) {
        repository.markQuitting(tx.id());
        for (int i = tx.postQuitNext(); i < tx.postQuitCommands().size(); i++) {
            dispatchDirect(tx.fakeName(), tx.postQuitCommands().get(i));
            repository.updatePostQuitNext(tx.id(), i + 1);
        }
        for (int i = tx.afterQuitNext(); i < tx.afterQuitCommands().size(); i++) {
            dispatchDirect(tx.fakeName(), tx.afterQuitCommands().get(i));
            repository.updateAfterQuitNext(tx.id(), i + 1);
        }
        repository.markRollingBack(tx.id(), tx.preAttempted() - 1);
        for (int i = tx.preAttempted() - 1; i >= 0; i--) {
            dispatchDirect(tx.fakeName(), tx.rollbackCommands().get(i));
            repository.updateRollbackNext(tx.id(), i - 1);
        }
        repository.delete(tx.id());
    }

    private static void dispatchDirect(@NotNull String fakeName, @NotNull String command) {
        if (!Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command)) {
            throw new IllegalStateException("Command was not handled for " + fakeName + ": " + command);
        }
        LOG.info("Dispatched lifecycle command for " + fakeName + ": " + command);
    }

    private static @NotNull List<String> format(@NotNull Fakeplayer fake, @NotNull List<String> commands) {
        return Commands.formatCommands(
                commands,
                "%p", fake.getName(),
                "%u", fake.getUUID().toString(),
                "%c", fake.getCreator().getName()
        );
    }

    public static @NotNull Throwable unwrap(@NotNull Throwable throwable) {
        var current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
