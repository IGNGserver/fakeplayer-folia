package io.github.hello09x.fakeplayer.core.manager;

import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Singleton
public class WildFakeplayerManager implements PluginMessageListener {

    private final static Logger log = Main.getInstance().getLogger();
    private final static boolean IS_BUNGEECORD = Bukkit
            .getServer()
            .spigot()
            .getSpigotConfig()
            .getBoolean("settings.bungeecord", false);
    private final static String CHANNEL = "BungeeCord";
    private final static String SUB_CHANNEL = "PlayerList";

    /**
     * 定义探测到连续 n 次不在线时进行清理
     * <br>
     * 仅在 {@link #IS_BUNGEECORD} 为 {@code true} 时生效
     */
    private final static int CLEANUP_THRESHOLD = 2;
    private final static int CLEANUP_PERIOD = 6000;
    private final static long MALFORMED_LOG_INTERVAL_MILLIS = 60_000L;

    private final FakeplayerManager manager;
    private final FakeplayerConfig config;
    private final Map<String, AtomicInteger> offline = new ConcurrentHashMap<>();
    private final AtomicLong malformedMessages = new AtomicLong();
    private volatile long malformedLogAt;
    private Tasks.Task cleanupTask;

    @Inject
    public WildFakeplayerManager(FakeplayerManager manager, FakeplayerConfig config) {
        this.manager = manager;
        this.config = config;
        this.cleanupTask = Tasks.runAtFixedRateGlobal(Main.getInstance(), this::cleanup, 0, CLEANUP_PERIOD);
    }

    @Override
    public void onPluginMessageReceived(
            @NotNull String channel,
            @NotNull Player player,
            byte @NotNull [] message
    ) {
        if (!channel.equals(CHANNEL)) {
            return;
        }

        try {
            var parsed = BungeePlayerListParser.parse(message);
            if (parsed.isEmpty()) {
                return;
            }
            var players = new HashSet<>(parsed.get());
            if (Tasks.isFolia()) {
                // Plugin-message callbacks run on the sender's region. The online
                // player set is global state, so collect it and perform cleanup on
                // the global region instead of reading it here.
                Tasks.runGlobal(Main.getInstance(), () -> {
                    players.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                    this.cleanup0(players);
                });
            } else {
                players.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                this.cleanup0(players);
            }
        } catch (RuntimeException malformed) {
            this.logMalformed(malformed.getMessage());
        }
    }

    private void logMalformed(@NotNull String reason) {
        var now = System.currentTimeMillis();
        var count = malformedMessages.incrementAndGet();
        if (now - malformedLogAt >= MALFORMED_LOG_INTERVAL_MILLIS) {
            malformedLogAt = now;
            log.warning("Ignored malformed BungeeCord plugin message (" + reason + ", count=" + count + ")");
        }
    }

    /**
     * 清除所有不在 {@code online} 列表中的玩家的假人
     *
     * @param online 在线玩家
     */
    public void cleanup0(@NotNull Set<String> online) {
        @SuppressWarnings("all")
        var group = manager.getAll()
                           .stream()
                           .filter(target -> manager.getCreatorName(target) != null)
                           .collect(Collectors.groupingBy(manager::getCreatorName));

        for (var entry : group.entrySet()) {
            var creator = entry.getKey();
            if (creator == null || creator.equals("CONSOLE")) {
                continue;
            }

            var targets = entry.getValue();
            if (targets.isEmpty() || online.contains(creator)) {
                continue;
            }

            if (offline.computeIfAbsent(creator, x -> new AtomicInteger()).incrementAndGet() < CLEANUP_THRESHOLD) {
                continue;
            }

            for (var target : targets) {
                manager.remove(target.getName(), "Creator offline");
            }
            log.info("%s is offline more than %d ticks, removing %d fake players".formatted(
                    creator,
                    CLEANUP_PERIOD * CLEANUP_THRESHOLD,
                    targets.size())
            );
        }

        for (var player : online) {
            offline.remove(player);
        }
    }

    /**
     * 清理召唤者下线的假人
     */
    public void cleanup() {
        if (!config.isFollowQuiting()) {
            return;
        }

        // 非 bungeeCord 服务器立即清理
        if (!IS_BUNGEECORD) {
            this.cleanup0(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toSet()));
            return;
        }

        // BungeeCord 服务器请求获取所有服务器在线玩家后
        // 在接收到在线列表后再进行清理
        var recipient = Bukkit
                .getServer()
                .getOnlinePlayers()
                .stream()
                .filter(manager::isNotFake)
                .findAny()
                .orElse(null);

        if (recipient == null) {
            return;
        }

        @SuppressWarnings("UnstableApiUsage")
        var out = ByteStreams.newDataOutput();
        out.writeUTF(SUB_CHANNEL);
        out.writeUTF("ALL");
        byte[] message = out.toByteArray();
        Tasks.run(Main.getInstance(), recipient, () -> recipient.sendPluginMessage(
                Main.getInstance(),
                CHANNEL,
                message
        ));
    }

}
