package io.github.hello09x.fakeplayer.core.listener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.entity.Fakeplayer;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerManager;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author tanyaofei
 * @since 2024/8/7
 **/
@Singleton
public class FakeplayerLifecycleListener implements Listener {

    private final FakeplayerManager manager;
    private final Map<UUID, QuitContext> quitting = new ConcurrentHashMap<>();
    private final Map<Fakeplayer, Tasks.Task> afterQuitTasks = new ConcurrentHashMap<>();

    @Inject
    public FakeplayerLifecycleListener(FakeplayerManager manager) {
        this.manager = manager;
    }


    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPostQuit(@NotNull PlayerQuitEvent event) {
        var player = event.getPlayer();
        var fake = this.manager.getRecord(player);
        if (fake == null) {
            // Not a fake player
            return;
        }

        this.quitting.put(fake.getUUID(), new QuitContext(fake, manager.startQuitLifecycle(fake)));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onAfterQuit(@NotNull PlayerQuitEvent event) {
        var player = event.getPlayer();
        var context = this.quitting.remove(player.getUniqueId());
        if (context == null) {
            // Not a fake player
            return;
        }

        var task = Tasks.runGlobalDelayed(Main.getInstance(), () -> {
            afterQuitTasks.remove(context.fakeplayer());
            // Wait for the LOWEST-phase finalizer to settle. finishQuitLifecycle
            // retries an unfinished post command from its durable checkpoint
            // before executing after-quit.
            context.postQuit()
                    .handle((ignored, throwable) -> null)
                    .thenCompose(ignored -> manager.finishQuitLifecycle(context.fakeplayer()))
                    .exceptionally(throwable -> {
                        Main.getInstance().getLogger().severe(
                                "Lifecycle finalizer remains pending for " + context.fakeplayer().getName()
                                        + " and will be recovered on restart: " + throwable
                        );
                        return null;
                    });
        }, 20);
        afterQuitTasks.put(context.fakeplayer(), task);
    }

    /** Cancel delayed lifecycle hooks when the plugin is being unloaded. */
    public void onDisable() {
        afterQuitTasks.values().forEach(Tasks.Task::cancel);
        afterQuitTasks.clear();
        quitting.clear();
    }

    private record QuitContext(
            @NotNull Fakeplayer fakeplayer,
            @NotNull CompletableFuture<Void> postQuit
    ) {
    }

}
