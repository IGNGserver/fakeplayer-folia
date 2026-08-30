package io.github.hello09x.fakeplayer.core.manager;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Removes fake players only from locally authoritative presence data. */
@Singleton
public class WildFakeplayerManager {

    private static final Logger LOG = Main.getInstance().getLogger();
    private static final boolean IS_BUNGEECORD = Bukkit
            .getServer()
            .spigot()
            .getSpigotConfig()
            .getBoolean("settings.bungeecord", false);
    private static final int CLEANUP_THRESHOLD = 2;
    private static final int CLEANUP_PERIOD = 6000;
    private static final long CLEANUP_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(
            CLEANUP_PERIOD * CLEANUP_THRESHOLD * 50L
    );

    private final FakeplayerManager manager;
    private final FakeplayerConfig config;
    private final Map<String, Long> offline = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;
    private Tasks.Task cleanupTask;

    @Inject
    public WildFakeplayerManager(FakeplayerManager manager, FakeplayerConfig config) {
        this.manager = manager;
        this.config = config;
        this.cleanupTask = Tasks.runAtFixedRateGlobal(
                Main.getInstance(),
                this::cleanup,
                0,
                CLEANUP_PERIOD
        );
        if (IS_BUNGEECORD && config.isFollowQuiting()) {
            LOG.warning("follow-quiting is disabled on BungeeCord: the built-in PlayerList response "
                    + "has no nonce or authentication and is not allowed to drive destructive deletion. "
                    + "Use explicit fake-player removal until an authenticated proxy companion is installed.");
        }
    }

    /**
     * Apply the grace period only to a locally authoritative online-player
     * snapshot. This method is intentionally never called from plugin-message
     * input.
     */
    public void cleanup0(@NotNull Set<String> online) {
        if (this.shuttingDown) {
            return;
        }
        var group = manager.getAll()
                .stream()
                .filter(target -> manager.getCreatorName(target) != null)
                .collect(Collectors.groupingBy(manager::getCreatorName));

        for (var creator : offline.keySet()) {
            if (!group.containsKey(creator) || online.contains(creator)) {
                offline.remove(creator);
            }
        }

        var now = System.nanoTime();
        for (var entry : group.entrySet()) {
            var creator = entry.getKey();
            if (creator == null || creator.equals("CONSOLE")) {
                continue;
            }
            var targets = entry.getValue();
            if (targets.isEmpty() || online.contains(creator)) {
                offline.remove(creator);
                continue;
            }

            var missingSince = offline.computeIfAbsent(creator, ignored -> now);
            if (now - missingSince < CLEANUP_GRACE_NANOS) {
                continue;
            }
            for (var target : targets) {
                manager.remove(target.getName(), "Creator offline");
            }
            offline.remove(creator);
            LOG.info("%s is locally offline more than %d ticks, removing %d fake players".formatted(
                    creator,
                    CLEANUP_PERIOD * CLEANUP_THRESHOLD,
                    targets.size()
            ));
        }
    }

    public void cleanup() {
        if (this.shuttingDown || !config.isFollowQuiting()) {
            return;
        }
        if (IS_BUNGEECORD) {
            // Fail closed: BungeeCord PlayerList cannot authenticate a response
            // or associate it with a nonce-bearing request. In particular, an
            // empty/stale response must never authorize deletion.
            return;
        }
        this.cleanup0(Bukkit.getOnlinePlayers().stream()
                .filter(manager::isNotFake)
                .map(Player::getName)
                .collect(Collectors.toSet()));
    }

    public void onDisable() {
        this.shuttingDown = true;
        var task = this.cleanupTask;
        if (task != null) {
            task.cancel();
            this.cleanupTask = null;
        }
        this.offline.clear();
    }
}
