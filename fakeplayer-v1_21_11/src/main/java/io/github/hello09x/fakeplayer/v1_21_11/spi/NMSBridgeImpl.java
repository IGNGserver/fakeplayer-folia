package io.github.hello09x.fakeplayer.v1_21_11.spi;

import io.github.hello09x.fakeplayer.api.spi.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;

/**
 * Minecraft 1.21.11 bridge.
 *
 * <p>1.21.11 Folia uses unversioned CraftBukkit packages. It delegates to the
 * reflection-backed Mojang-name adapter shared with the 26.x module, while
 * keeping 1.21.11 as a distinct ServiceLoader provider.</p>
 */
public class NMSBridgeImpl implements NMSBridge {

    private final NMSBridge delegate = new io.github.hello09x.fakeplayer.v26_1_2.spi.NMSBridgeImpl();

    @Override
    public @NotNull NMSEntity fromEntity(@NotNull Entity entity) {
        return delegate.fromEntity(entity);
    }

    @Override
    public @NotNull NMSServer fromServer(@NotNull Server server) {
        return delegate.fromServer(server);
    }

    @Override
    public @NotNull NMSServerLevel fromWorld(@NotNull World world) {
        return delegate.fromWorld(world);
    }

    @Override
    public @NotNull NMSServerPlayer fromPlayer(@NotNull Player player) {
        return delegate.fromPlayer(player);
    }

    @Override
    public @NotNull NMSNetwork createNetwork(@NotNull InetAddress address) {
        return delegate.createNetwork(address);
    }

    @Override
    public boolean isSupported() {
        return "1.21.11".equals(Bukkit.getMinecraftVersion());
    }

    @Override
    public @NotNull ActionTicker createAction(@NotNull Player player, @NotNull ActionType action, @NotNull ActionSetting setting) {
        return delegate.createAction(player, action, setting);
    }

}
