package io.github.hello09x.fakeplayer.core.lifecycle;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the complete FakePlayer shutdown sequence.
 *
 * <p>The coordinator is deliberately not created by Guice. It is owned by
 * {@code Main}, registered before any injectable enable step can fail, and
 * only receives cleanup actions for components that were successfully
 * initialized. This makes partial enable failures safe without asking Guice
 * to lazily construct runtime services while the plugin is already disabled.</p>
 */
public final class FakeplayerShutdownCoordinator implements Listener {

    public enum Phase {
        STOP_ACCEPTING,
        STOP_ASYNC,
        RECOVER_JOURNAL,
        CLEANUP
    }

    private final Plugin plugin;
    private final Logger logger;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final Object lock = new Object();
    private final Object shutdownLock = new Object();
    private final EnumMap<Phase, LinkedHashMap<String, Runnable>> steps = new EnumMap<>(Phase.class);
    private final EnumMap<Phase, java.util.Set<String>> completed = new EnumMap<>(Phase.class);

    public FakeplayerShutdownCoordinator(@NotNull Plugin plugin) {
        this(Objects.requireNonNull(plugin, "plugin"), plugin.getLogger());
    }

    private FakeplayerShutdownCoordinator(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = Objects.requireNonNull(logger, "logger");
        for (var phase : Phase.values()) {
            this.steps.put(phase, new LinkedHashMap<>());
            this.completed.put(phase, new java.util.HashSet<>());
        }
    }

    static FakeplayerShutdownCoordinator forTesting() {
        return new FakeplayerShutdownCoordinator(null, Logger.getLogger(FakeplayerShutdownCoordinator.class.getName()));
    }

    /** Register the coordinator before Guice, CommandAPI, or any manager is initialized. */
    public void register() {
        Objects.requireNonNull(this.plugin, "plugin");
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    /**
     * Add a cleanup action for an already initialized component.
     *
     * <p>The name is unique so accidental duplicate registration cannot make
     * cleanup run twice. Registration after shutdown has started is rejected;
     * a component initialized that late is outside the plugin lifecycle and
     * must be handled by its own constructor failure path.</p>
     */
    public void add(
            @NotNull Phase phase,
            @NotNull String name,
            @NotNull Runnable action
    ) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        synchronized (this.lock) {
            if (this.shutdownStarted.get()) {
                throw new IllegalStateException("Cannot register shutdown action after shutdown started: " + name);
            }
            var previous = this.steps.get(phase).putIfAbsent(name, action);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate shutdown action: " + name);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPluginDisable(@NotNull PluginDisableEvent event) {
        if (this.plugin != null && event.getPlugin() == this.plugin) {
            this.shutdown();
        }
    }

    /**
     * Execute the shutdown sequence and return whether every registered step
     * completed. Successful steps are not repeated, while failed steps remain
     * retryable for a later lifecycle callback. In particular, a failed
     * {@link Phase#STOP_ASYNC} is a hard barrier: journal recovery is skipped
     * until a later call confirms that all owned executors have terminated.
     */
    public boolean shutdown() {
        synchronized (this.shutdownLock) {
            this.shutdownStarted.set(true);

            var acceptingComplete = this.runPhase(Phase.STOP_ACCEPTING);
            var asyncComplete = this.runPhase(Phase.STOP_ASYNC);
            if (asyncComplete) {
                this.runPhase(Phase.RECOVER_JOURNAL);
            } else {
                this.logger.severe(
                        "FakePlayer shutdown async barrier is still open; lifecycle journal recovery is deferred"
                );
            }
            var cleanupComplete = this.runPhase(Phase.CLEANUP);
            return acceptingComplete && asyncComplete && this.isPhaseComplete(Phase.RECOVER_JOURNAL)
                    && cleanupComplete;
        }
    }

    private boolean runPhase(@NotNull Phase phase) {
        var actions = this.pendingActions(phase);
        var phaseComplete = true;
        for (var action : actions) {
            try {
                action.action().run();
                synchronized (this.lock) {
                    this.completed.get(phase).add(action.name());
                }
            } catch (Throwable failure) {
                phaseComplete = false;
                this.logger.log(
                        Level.SEVERE,
                        "FakePlayer shutdown step failed (" + phase + ", " + action.name() + ")",
                        failure
                );
            }
        }
        return phaseComplete && this.isPhaseComplete(phase);
    }

    private @NotNull List<NamedAction> pendingActions(@NotNull Phase phase) {
        synchronized (this.lock) {
            var done = this.completed.get(phase);
            var actions = new ArrayList<NamedAction>();
            this.steps.get(phase).forEach((name, action) -> {
                if (!done.contains(name)) {
                    actions.add(new NamedAction(name, action));
                }
            });
            return List.copyOf(actions);
        }
    }

    private boolean isPhaseComplete(@NotNull Phase phase) {
        synchronized (this.lock) {
            return this.completed.get(phase).containsAll(this.steps.get(phase).keySet());
        }
    }

    private record NamedAction(@NotNull String name, @NotNull Runnable action) {
    }
}
