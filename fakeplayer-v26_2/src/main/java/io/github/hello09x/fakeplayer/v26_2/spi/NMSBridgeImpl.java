package io.github.hello09x.fakeplayer.v26_2.spi;

import io.github.hello09x.fakeplayer.api.spi.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;

/**
 * Minecraft 26.2 bridge.
 *
 * <p>26.2 keeps the unversioned CraftBukkit packages and the Mojang-name
 * runtime layout of the 26.x line. It delegates to the reflection-backed
 * adapter shared with the 26.1.2 module, while keeping 26.2 as a distinct
 * ServiceLoader provider gated on its own explicitly verified runtime.</p>
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
        // Reflection cannot prove that an untested patch preserves every
        // constructor, field, and protocol method. Fail closed until a patch
        // has been explicitly verified against this adapter; verifyRuntime()
        // re-checks the full member contract on the live server.
        return "26.2".equals(Bukkit.getMinecraftVersion());
    }

    @Override
    public void verifyRuntime() {
        delegate.verifyRuntime();
    }

    @Override
    public @NotNull ActionTicker createAction(@NotNull Player player, @NotNull ActionType action, @NotNull ActionSetting setting) {
        return delegate.createAction(player, action, setting);
    }

}
