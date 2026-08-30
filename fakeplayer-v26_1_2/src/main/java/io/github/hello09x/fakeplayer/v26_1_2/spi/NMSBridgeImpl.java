package io.github.hello09x.fakeplayer.v26_1_2.spi;

import io.github.hello09x.fakeplayer.api.spi.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;

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
        // Reflection cannot prove that an untested patch preserves every
        // constructor, field, and protocol method. Fail closed until a patch
        // has been explicitly verified against this adapter.
        return "26.1.2".equals(Bukkit.getMinecraftVersion());
    }

    @Override
    public void verifyRuntime() {
        var required = new String[]{
                "net.minecraft.server.MinecraftServer",
                "net.minecraft.server.level.ServerPlayer",
                "net.minecraft.server.players.PlayerList",
                "net.minecraft.server.network.CommonListenerCookie",
                "net.minecraft.network.Connection",
                "net.minecraft.network.protocol.PacketFlow",
                "net.minecraft.network.protocol.game.GameProtocols",
                "net.minecraft.network.RegistryFriendlyByteBuf",
                "net.minecraft.server.level.ClientInformation",
                "com.mojang.authlib.GameProfile",
                "io.netty.channel.embedded.EmbeddedChannel"
        };
        for (var className : required) {
            NmsAccess.classForName(className);
        }

        NmsAccess.requireConstructor("com.mojang.authlib.GameProfile", 2);
        NmsAccess.requireConstructor("net.minecraft.server.level.ServerPlayer", 4);
        NmsAccess.requireConstructor("net.minecraft.network.Connection", 1);
        NmsAccess.requireConstructor("net.minecraft.world.phys.Vec3", 3);
        NmsAccess.requireMethod("net.minecraft.server.MinecraftServer", "getPlayerList", 0);
        NmsAccess.requireMethod("net.minecraft.server.MinecraftServer", "registryAccess", 0);
        NmsAccess.requireMethod("net.minecraft.server.players.PlayerList", "placeNewPlayer", 3);
        NmsAccess.requireMethod("net.minecraft.server.players.PlayerList", "remove", 1);
        NmsAccess.requireMethod("net.minecraft.server.level.ServerPlayer", "getBukkitEntity", 0);
        NmsAccess.requireMethod("net.minecraft.server.level.ServerPlayer", "getInventory", 0);
        NmsAccess.requireMethod("net.minecraft.server.level.ServerPlayer", "setPos", 3);
        NmsAccess.requireMethod("net.minecraft.server.level.ServerPlayer", "snapTo", 3);
        NmsAccess.requireMethod("net.minecraft.network.Connection", "configureSerialization", 4);
        NmsAccess.requireMethod("net.minecraft.network.Connection", "configurePacketHandler", 1);
        NmsAccess.requireMethod("net.minecraft.network.Connection", "setupOutboundProtocol", 1);
        NmsAccess.requireMethod("net.minecraft.network.Connection", "disconnect", 1);
        NmsAccess.requireMethod("net.minecraft.network.Connection", "handleDisconnection", 0);
        NmsAccess.requireMethod("net.minecraft.network.RegistryFriendlyByteBuf", "decorator", 1);
        NmsAccess.requireMethod("net.minecraft.server.network.CommonListenerCookie", "createInitial", 2);
        NmsAccess.requireMethod("net.minecraft.server.level.ClientInformation", "createDefault", 0);
        NmsAccess.requireField("net.minecraft.network.protocol.game.GameProtocols", "CLIENTBOUND_TEMPLATE");
        NmsAccess.requireField("net.minecraft.network.Connection", "channel");
        NmsAccess.requireField("net.minecraft.network.Connection", "address");
        NmsAccess.requireField("net.minecraft.server.level.ServerPlayer", "connection");
    }

    @Override
    public @NotNull ActionTicker createAction(@NotNull Player player, @NotNull ActionType action, @NotNull ActionSetting setting) {
        return new ActionTickerImpl(this, player, action, setting);
    }

}
