package io.github.hello09x.fakeplayer.v26_1_2.spi;

import io.github.hello09x.fakeplayer.api.spi.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;
import java.util.Locale;

/** Mojang-named, reflection-backed bridge for the 26.x server family. */
public class NMSBridgeImpl implements NMSBridge {

    @Override
    public @NotNull NMSEntity fromEntity(@NotNull Entity entity) {
        return new NMSEntityImpl(entity);
    }

    @Override
    public @NotNull NMSServer fromServer(@NotNull Server server) {
        return new NMSServerImpl(server);
    }

    @Override
    public @NotNull NMSServerLevel fromWorld(@NotNull World world) {
        return new NMSServerLevelImpl(world);
    }

    @Override
    public @NotNull NMSServerPlayer fromPlayer(@NotNull Player player) {
        return new NMSServerPlayerImpl(player);
    }

    @Override
    public @NotNull NMSNetwork createNetwork(@NotNull InetAddress address) {
        return new NMSNetworkImpl(address);
    }

    @Override
    public boolean isSupported() {
        // 26.x uses the same unversioned Mojang class names across patch
        // releases, so keep this provider available for newer 26.x releases.
        return Bukkit.getMinecraftVersion().toLowerCase(Locale.ROOT).startsWith("26.");
    }

    @Override
    public @NotNull ActionTicker createAction(@NotNull Player player, @NotNull ActionType action, @NotNull ActionSetting setting) {
        return new ActionTickerImpl(this, player, action, setting);
    }

}
