package io.github.hello09x.fakeplayer.v26_1_2.spi;

import io.github.hello09x.fakeplayer.api.spi.NMSServer;
import io.github.hello09x.fakeplayer.api.spi.NMSServerPlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

final class NMSServerImpl implements NMSServer {

    private final Server server;
    private final Object handle;

    NMSServerImpl(@NotNull Server server) {
        this.server = server;
        this.handle = NmsAccess.serverHandle(server);
    }

    Object handle() {
        return handle;
    }

    @Override
    public @NotNull NMSServerPlayer newPlayer(@NotNull UUID uuid, @NotNull String name) {
        World world = server.getWorlds().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Cannot create a fake player before a world is loaded"));
        return newPlayer(uuid, name, world);
    }

    @Override
    public @NotNull NMSServerPlayer newPlayer(
            @NotNull UUID uuid,
            @NotNull String name,
            @NotNull World world
    ) {
        Object level = new NMSServerLevelImpl(world).handle();
        Object profile = NmsAccess.newInstance("com.mojang.authlib.GameProfile", uuid, name);
        Object clientInformation = NmsAccess.invokeStatic(
                "net.minecraft.server.level.ClientInformation",
                "createDefault"
        );
        Object nmsPlayer = NmsAccess.newInstance(
                "net.minecraft.server.level.ServerPlayer",
                handle,
                level,
                profile,
                clientInformation
        );
        Object bukkitPlayer = NmsAccess.invoke(nmsPlayer, "getBukkitEntity");
        return new NMSServerPlayerImpl((org.bukkit.entity.Player) bukkitPlayer);
    }
}
