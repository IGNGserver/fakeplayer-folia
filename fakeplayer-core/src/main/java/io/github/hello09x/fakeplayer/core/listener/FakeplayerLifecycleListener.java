package io.github.hello09x.fakeplayer.core.listener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.entity.Fakeplayer;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerManager;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author tanyaofei
 * @since 2024/8/7
 **/
@Singleton
public class FakeplayerLifecycleListener implements Listener {

    private final FakeplayerManager manager;
    private final FakeplayerConfig config;
    private final Map<UUID, Fakeplayer> quitting = new ConcurrentHashMap<>();

    @Inject
    public FakeplayerLifecycleListener(FakeplayerManager manager, FakeplayerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPostSpawn(@NotNull PlayerJoinEvent event) {
        var player = event.getPlayer();
        if (this.manager.isNotFake(player)) {
            // Not a fake player
            return;
        }

        manager.dispatchCommands(player, config.getPostSpawnCommands());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onAfterSpawn(@NotNull PlayerJoinEvent event) {
        var player = event.getPlayer();
        if (this.manager.isNotFake(player)) {
            // Not a fake player
            return;
        }

        Tasks.runDelayed(Main.getInstance(), player, () -> {
            if (player.isOnline()) {
                var fake = manager.getRecord(player);
                if (fake == null) {
                    return;
                }
                manager.dispatchCommandsAsync(fake, config.getAfterSpawnCommands())
                        .thenRun(() -> manager.issueCommands(player, config.getSelfCommands()))
                        .exceptionally(throwable -> {
                            Main.getInstance().getLogger().warning(
                                    "Failed to run after-spawn commands for " + fake.getName() + ": " + throwable
                            );
                            return null;
                        });
            }
        }, 20);
    }


    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPostQuit(@NotNull PlayerQuitEvent event) {
        var player = event.getPlayer();
        var fake = this.manager.getRecord(player);
        if (fake == null) {
            // Not a fake player
            return;
        }

        this.quitting.put(fake.getUUID(), fake);
        manager.dispatchCommands(fake, config.getPostQuitCommands());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onAfterQuit(@NotNull PlayerQuitEvent event) {
        var player = event.getPlayer();
        var fake = this.quitting.remove(player.getUniqueId());
        if (fake == null) {
            // Not a fake player
            return;
        }

        Tasks.runGlobalDelayed(Main.getInstance(), () -> {
            manager.dispatchCommandsAsync(fake, config.getAfterQuitCommands())
                    .whenComplete((ignored, throwable) -> manager.clearCommandChain(fake.getUUID()));
        }, 20);
    }


}
