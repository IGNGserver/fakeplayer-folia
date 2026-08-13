package io.github.hello09x.fakeplayer.core.util.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Scheduler abstraction that is compatible with both Paper/Purpur and Folia.
 *
 * <p>The vanilla fakeplayer code base was written for Paper, where every task is
 * executed on a single main thread via {@link org.bukkit.scheduler.BukkitScheduler}.
 * Folia removes the main thread and schedules jobs on per-region threads, which
 * means {@link org.bukkit.scheduler.BukkitScheduler#runTask(Plugin, Runnable)} and
 * friends throw on Folia. To support both platforms this utility inspects each
 * task and routes it to the correct scheduler:</p>
 *
 * <ul>
 *     <li>tasks that mutate a specific entity -> the entity's {@code EntityScheduler}
 *         (the region that owns the entity)</li>
 *     <li>tasks that operate on a specific location -> the {@code RegionScheduler}
 *         for that location</li>
 *     <li>global / cross-region tasks -> the {@code GlobalRegionScheduler}</li>
 *     <li>asynchronous tasks -> the {@code AsyncScheduler}</li>
 * </ul>
 *
 * <p>On Paper every variant falls back to the matching {@link org.bukkit.scheduler.BukkitScheduler}
 * call so behaviour is identical to upstream.</p>
 *
 * <p>The Folia scheduler classes ({@code RegionizedServer}, {@code GlobalRegionScheduler},
 * ...) are not part of {@code paper-api}, against which this project compiles. They are
 * therefore resolved via reflection. Folia is detected at runtime by probing for the
 * presence of {@code io.papermc.paper.threadedregions.RegionizedServer}.</p>
 */
public final class Tasks {

/**
     * A handle to a scheduled, repeating task that may be cancelled.
     */
    public interface Task {
        void cancel();
    }

    public static final boolean FOLIA;

    private static final Runnable EMPTY_RUNNABLE = () -> {};

    // --- reflective handles, only populated when running on Folia ---
    private static Method server_getGlobalRegionScheduler;
    private static Method server_getRegionScheduler;
    private static Method server_getAsyncScheduler;
    private static Method entity_getScheduler;

    private static Method global_execute;                // (Plugin, Runnable)
    private static Method global_runDelayed;             // (Plugin, Consumer, long)
    private static Method global_runAtFixedRate;         // (Plugin, Consumer, long, long)

    private static Method region_execute;                   // (Plugin, Location, Runnable)

    private static Method entity_run;                    // (Plugin, Consumer, Runnable)
    private static Method entity_runDelayed;             // (Plugin, Consumer, Runnable, long)
    private static Method entity_runAtFixedRate;         // (Plugin, Consumer, Runnable, long, long)

    private static Method async_runNow;                 // (Plugin, Consumer)

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException notFolia) {
            folia = false;
        }
        if (folia) {
            try {
                initFoliaHandles();
            } catch (Throwable t) {
                // Detected Folia but the API surface has changed; fall back to Paper scheduling.
                System.err.println("[fakeplayer-folia] Failed to bind Folia scheduler handles; "
                        + "falling back to Paper scheduling. Reason: " + t);
                folia = false;
            }
        }
        FOLIA = folia;
    }

    private Tasks() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    // ---------------------------------------------------------------------
    // Asynchronous
    // ---------------------------------------------------------------------

    public static CompletableFuture<Void> runAsync(@NotNull Plugin plugin, @NotNull Runnable runnable) {
        var future = new CompletableFuture<Void>();
        if (FOLIA) {
            var async = invoke(server_getAsyncScheduler, Bukkit.getServer());
            invoke(async_runNow, async, plugin, wrapping(runnable, future));
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, wrap(runnable, future));
        }
        return future;
    }

    // ---------------------------------------------------------------------
    // One-shot sync (completing futures)
    // ---------------------------------------------------------------------

    public static <T> CompletableFuture<T> call(@NotNull Plugin plugin, @NotNull Entity entity, @NotNull Supplier<T> supplier) {
        var future = new CompletableFuture<T>();
        if (FOLIA) {
            var es = invoke(entity_getScheduler, entity);
            invoke(entity_run, es, plugin, wrapping(supplier, future), EMPTY_RUNNABLE);
        } else {
            Bukkit.getScheduler().runTask(plugin, wrap(supplier, future));
        }
        return future;
    }

    public static <T> CompletableFuture<T> callAt(@NotNull Plugin plugin, @NotNull Location location, @NotNull Supplier<T> supplier) {
        var future = new CompletableFuture<T>();
        if (FOLIA) {
            var rs = invoke(server_getRegionScheduler, Bukkit.getServer());
            invoke(region_execute, rs, plugin, location, (Runnable) () -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, wrap(supplier, future));
        }
        return future;
    }

    public static <T> CompletableFuture<T> callGlobal(@NotNull Plugin plugin, @NotNull Supplier<T> supplier) {
        var future = new CompletableFuture<T>();
        if (FOLIA) {
            var grs = invoke(server_getGlobalRegionScheduler, Bukkit.getServer());
            invoke(global_execute, grs, plugin, (Runnable) () -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, wrap(supplier, future));
        }
        return future;
    }

    // ---------------------------------------------------------------------
    // One-shot sync (fire-and-forget)
    // ---------------------------------------------------------------------

    public static void run(@NotNull Plugin plugin, @NotNull Entity entity, @NotNull Runnable runnable) {
        if (FOLIA) {
            var es = invoke(entity_getScheduler, entity);
            invoke(entity_run, es, plugin, (Consumer<Object>) t -> runnable.run(), EMPTY_RUNNABLE);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public static void runGlobal(@NotNull Plugin plugin, @NotNull Runnable runnable) {
        if (FOLIA) {
            var grs = invoke(server_getGlobalRegionScheduler, Bukkit.getServer());
            invoke(global_execute, grs, plugin, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public static void runDelayed(@NotNull Plugin plugin, @NotNull Entity entity, @NotNull Runnable runnable, long delayTicks) {
        if (FOLIA) {
            var es = invoke(entity_getScheduler, entity);
            invoke(entity_runDelayed, es, plugin, (Consumer<Object>) t -> runnable.run(), EMPTY_RUNNABLE, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        }
    }

    public static void runGlobalDelayed(@NotNull Plugin plugin, @NotNull Runnable runnable, long delayTicks) {
        if (FOLIA) {
            var grs = invoke(server_getGlobalRegionScheduler, Bukkit.getServer());
            invoke(global_runDelayed, grs, plugin, (Consumer<Object>) t -> runnable.run(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        }
    }

    // ---------------------------------------------------------------------
    // Repeating tasks
    // ---------------------------------------------------------------------

    public static Task runAtFixedRate(@NotNull Plugin plugin, @NotNull Entity entity, @NotNull Runnable runnable, long delayTicks, long periodTicks) {
        if (FOLIA) {
            var es = invoke(entity_getScheduler, entity);
            var scheduled = invoke(entity_runAtFixedRate, es, plugin, (Consumer<Object>) t -> runnable.run(), EMPTY_RUNNABLE, delayTicks, periodTicks);
            return new FoliaTask(scheduled);
        }
        return new PaperTask(Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks));
    }

    public static Task runAtFixedRateGlobal(@NotNull Plugin plugin, @NotNull Runnable runnable, long delayTicks, long periodTicks) {
        if (FOLIA) {
            var grs = invoke(server_getGlobalRegionScheduler, Bukkit.getServer());
            var scheduled = invoke(global_runAtFixedRate, grs, plugin, (Consumer<Object>) t -> runnable.run(), delayTicks, periodTicks);
            return new FoliaTask(scheduled);
        }
        return new PaperTask(Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks));
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    private static <T> Consumer<Object> wrapping(@NotNull Supplier<T> supplier, @NotNull CompletableFuture<T> future) {
        return t -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        };
    }

    private static Consumer<Object> wrapping(@NotNull Runnable runnable, @NotNull CompletableFuture<Void> future) {
        return t -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        };
    }

    private static <T> Runnable wrap(@NotNull Supplier<T> supplier, @NotNull CompletableFuture<T> future) {
        return () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        };
    }

    private static Runnable wrap(@NotNull Runnable runnable, @NotNull CompletableFuture<Void> future) {
        return () -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        };
    }

    private static Object invoke(@NotNull Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke Folia scheduler method " + method.getName(), e);
        }
    }

    private static void initFoliaHandles() throws NoSuchMethodException, ClassNotFoundException {
        server_getGlobalRegionScheduler = Server.class.getMethod("getGlobalRegionScheduler");
        server_getRegionScheduler = Server.class.getMethod("getRegionScheduler");
        server_getAsyncScheduler = Server.class.getMethod("getAsyncScheduler");
        entity_getScheduler = Entity.class.getMethod("getScheduler");

        Class<?> grs = server_getGlobalRegionScheduler.getReturnType();
        global_execute = grs.getMethod("execute", Plugin.class, Runnable.class);
        global_runDelayed = grs.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
        global_runAtFixedRate = grs.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);

        Class<?> rs = server_getRegionScheduler.getReturnType();
        region_execute = rs.getMethod("execute", Plugin.class, Location.class, Runnable.class);

        Class<?> es = entity_getScheduler.getReturnType();
        entity_run = es.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
        entity_runDelayed = es.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
        entity_runAtFixedRate = es.getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);

        Class<?> async = server_getAsyncScheduler.getReturnType();
        async_runNow = async.getMethod("runNow", Plugin.class, Consumer.class);
    }

    private static final class PaperTask implements Task {
        private final BukkitTask task;

        PaperTask(BukkitTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            task.cancel();
        }
    }

    private static final class FoliaTask implements Task {
        private final Object scheduled;
        private volatile boolean cancelled;

        FoliaTask(Object scheduled) {
            this.scheduled = scheduled;
        }

        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            if (scheduled == null) {
                return;
            }
            try {
                scheduled.getClass().getMethod("cancel").invoke(scheduled);
            } catch (Throwable ignored) {
            }
        }
    }

}