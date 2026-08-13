package io.github.hello09x.fakeplayer.v26_1_2.spi;

import io.github.hello09x.fakeplayer.api.spi.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;
import java.util.Set;

/**
 * Minecraft 26.1.2 (new version format) bridge scaffold.
 *
 * <p>This bridge intentionally depends on no spigot NMS artifacts and references
 * no {@code CraftBukkit} classes, so it loads cleanly on a 26.1.2 server and is
 * selected by the {@link NMSBridge#isSupported()} check. Until the real 26.1.2
 * NMS surface is implemented (see the module {@code pom.xml} for the build steps),
 * the NMS factory methods throw a descriptive error rather than failing with an
 * opaque {@link NoClassDefFoundError}.</p>
 */
public class NMSBridgeImpl implements NMSBridge {

    private final static Set<String> SUPPORTS = Set.of("26.1.2");

    private static UnsupportedOperationException notYetImplemented() {
        return new UnsupportedOperationException(
                "fakeplayer-folia: NMS implementation for Minecraft " + Bukkit.getMinecraftVersion()
                        + " is not yet available. Build the 26.1.2 remapped NMS artifacts "
                        + "(java -jar BuildTools.jar --rev 26.1.2 --remapped) and fill in the "
                        + "impl classes under fakeplayer-v26_1_2, then replace the stub bridge."
        );
    }

    @Override
    public @NotNull NMSEntity fromEntity(@NotNull Entity entity) {
        throw notYetImplemented();
    }

    @Override
    public @NotNull NMSServer fromServer(@NotNull Server server) {
        throw notYetImplemented();
    }

    @Override
    public @NotNull NMSServerLevel fromWorld(@NotNull World world) {
        throw notYetImplemented();
    }

    @Override
    public @NotNull NMSServerPlayer fromPlayer(@NotNull Player player) {
        throw notYetImplemented();
    }

    @Override
    public @NotNull NMSNetwork createNetwork(@NotNull InetAddress address) {
        throw notYetImplemented();
    }

    @Override
    public boolean isSupported() {
        return SUPPORTS.contains(Bukkit.getMinecraftVersion());
    }

    @Override
    public @NotNull ActionTicker createAction(@NotNull Player player, @NotNull ActionType action, @NotNull ActionSetting setting) {
        throw notYetImplemented();
    }

}