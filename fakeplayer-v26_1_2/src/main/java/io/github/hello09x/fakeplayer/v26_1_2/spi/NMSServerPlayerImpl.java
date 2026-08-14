package io.github.hello09x.fakeplayer.v26_1_2.spi;

import io.github.hello09x.fakeplayer.api.spi.NMSServerPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Reflection-backed ServerPlayer wrapper for the unversioned Mojang names in
 * Minecraft 26.x. Every method is invoked on the owning region thread by the
 * core scheduler.
 */
final class NMSServerPlayerImpl implements NMSServerPlayer {

    private final Player player;
    private final Object handle;

    NMSServerPlayerImpl(@NotNull Player player) {
        this.player = player;
        this.handle = NmsAccess.handle(player);
    }

    Object handle() {
        return handle;
    }

    @Override
    public @NotNull Player getPlayer() {
        return player;
    }

    @Override
    public double getX() {
        return NmsAccess.decimal(NmsAccess.invoke(handle, "getX"));
    }

    @Override
    public double getY() {
        return NmsAccess.decimal(NmsAccess.invoke(handle, "getY"));
    }

    @Override
    public double getZ() {
        return NmsAccess.decimal(NmsAccess.invoke(handle, "getZ"));
    }

    @Override
    public void setXo(double xo) {
        NmsAccess.setFieldIfPresent(handle, "xo", xo);
    }

    @Override
    public void setYo(double yo) {
        NmsAccess.setFieldIfPresent(handle, "yo", yo);
    }

    @Override
    public void setZo(double zo) {
        NmsAccess.setFieldIfPresent(handle, "zo", zo);
    }

    @Override
    public void doTick() {
        NmsAccess.invoke(handle, "doTick");
    }

    @Override
    public void absMoveTo(double x, double y, double z, float yRot, float xRot) {
        // absSnapTo was renamed/removed from the 26.x runtime. snapTo keeps the
        // server position authoritative; the rotations are set immediately after.
        try {
            NmsAccess.invoke(handle, "absSnapTo", x, y, z, yRot, xRot);
        } catch (RuntimeException ignored) {
            NmsAccess.invoke(handle, "snapTo", x, y, z);
        }
        setYRot(yRot);
        setXRot(xRot);
    }

    @Override
    public float getYRot() {
        return NmsAccess.floating(NmsAccess.invoke(handle, "getYRot"));
    }

    @Override
    public void setYRot(float yRot) {
        NmsAccess.invoke(handle, "setYRot", yRot);
    }

    @Override
    public float getXRot() {
        return NmsAccess.floating(NmsAccess.invoke(handle, "getXRot"));
    }

    @Override
    public void setXRot(float xRot) {
        NmsAccess.invoke(handle, "setXRot", xRot);
    }

    @Override
    public float getZza() {
        return NmsAccess.floating(NmsAccess.getField(handle, "zza"));
    }

    @Override
    public void setZza(float zza) {
        NmsAccess.setField(handle, "zza", zza);
    }

    @Override
    public float getXxa() {
        return NmsAccess.floating(NmsAccess.getField(handle, "xxa"));
    }

    @Override
    public void setXxa(float xxa) {
        NmsAccess.setField(handle, "xxa", xxa);
    }

    @Override
    public void setDeltaMovement(@NotNull Vector vector) {
        Object movement = NmsAccess.newInstance(
                "net.minecraft.world.phys.Vec3",
                vector.getX(),
                vector.getY(),
                vector.getZ()
        );
        NmsAccess.invoke(handle, "setDeltaMovement", movement);
    }

    @Override
    public boolean startRiding(@NotNull Entity entity, boolean force) {
        return NmsAccess.bool(NmsAccess.invoke(
                handle,
                "startRiding",
                NmsAccess.handle(entity),
                force,
                true
        ));
    }

    @Override
    public void stopRiding() {
        NmsAccess.invoke(handle, "stopRiding");
    }

    @Override
    public void disableAdvancements(@NotNull Plugin plugin) {
        // PlayerAdvancements is a concrete server class in 26.x and cannot be
        // replaced with the old subclass without compiling against the server
        // bundle. Stop trigger listeners and replace the dirty-progress set
        // with a no-op set so later award events cannot cause a save on the
        // fake player's real UUID path.
        Object advancements = NmsAccess.invokeOptional(handle, "getAdvancements");
        NmsAccess.invokeOptional(advancements, "stopListening");
        NmsAccess.setFieldIfPresent(advancements, "progressChanged", new NoopSet());
        // 26.x save() serializes the whole progress map without checking the
        // dirty set first. Point the private save path at an existing directory
        // so any defensive save attempt fails without creating a player data
        // file. The field is version-local and the helper silently falls back
        // on a future mapping where it is absent.
        var sink = plugin.getDataFolder().toPath();
        if (!java.nio.file.Files.isDirectory(sink)) {
            sink = java.nio.file.Path.of(".");
        }
        NmsAccess.setFieldIfPresent(advancements, "playerSavePath", sink);
    }

    @Override
    public int getTickCount() {
        return NmsAccess.integer(NmsAccess.getField(handle, "tickCount"));
    }

    @Override
    public void drop(int slot, boolean flag, boolean flag1) {
        Object inventory = NmsAccess.invoke(handle, "getInventory");
        Object item = NmsAccess.invoke(inventory, "getItem", slot);
        int count = NmsAccess.integer(NmsAccess.invoke(item, "getCount"));
        Object removed = NmsAccess.invoke(inventory, "removeItem", slot, count);
        if (NmsAccess.hasDeclaredCompatibleMethod(handle, "drop", removed, flag, flag1)) {
            // 26.x keeps the three-argument overload.
            NmsAccess.invoke(handle, "drop", removed, flag, flag1);
        } else {
            // 1.21.11 adds the randomize flag and the Bukkit item callback.
            NmsAccess.invoke(handle, "drop", removed, flag, flag1, false, (Consumer<Object>) ignoredItem -> {
            });
        }
    }

    @Override
    public void drop(boolean allStack) {
        NmsAccess.invoke(handle, "drop", allStack);
    }

    @Override
    public void resetLastActionTime() {
        NmsAccess.invoke(handle, "resetLastActionTime");
    }

    @Override
    public boolean onGround() {
        return NmsAccess.bool(NmsAccess.invoke(handle, "onGround"));
    }

    @Override
    public void jumpFromGround() {
        NmsAccess.invoke(handle, "jumpFromGround");
    }

    @Override
    public void setJumping(boolean jumping) {
        NmsAccess.invoke(handle, "setJumping", jumping);
    }

    @Override
    public boolean isUsingItem() {
        return NmsAccess.bool(NmsAccess.invoke(handle, "isUsingItem"));
    }

    @Override
    public void setPlayBefore() {
        try {
            Object provider = NmsAccess.invokeStatic(
                    "net.minecraft.core.HolderLookup$Provider",
                    "create",
                    Stream.empty()
            );
            Object helper = NmsAccess.newInstance(
                    "net.minecraft.world.level.storage.ValueInputContextHelper",
                    provider,
                    null
            );
            Object empty = NmsAccess.invoke(helper, "empty");
            NmsAccess.invoke(player, "readExtraData", empty);
        } catch (RuntimeException ignored) {
            // This is a compatibility convenience only; a missing persistence
            // hook must not prevent the player from joining.
        }
    }

    @Override
    public void setupClientOptions() {
        Object options = NmsAccess.invokeStatic(
                "net.minecraft.server.level.ClientInformation",
                "createDefault"
        );
        NmsAccess.invoke(handle, "updateOptions", options);
    }

    @Override
    public void respawn() {
        if (!player.isDead()) {
            return;
        }
        Object action = NmsAccess.enumValue(
                "net.minecraft.network.protocol.game.ServerboundClientCommandPacket$Action",
                "PERFORM_RESPAWN"
        );
        Object packet = NmsAccess.newInstance(
                "net.minecraft.network.protocol.game.ServerboundClientCommandPacket",
                action
        );
        Object connection = NmsAccess.getField(handle, "connection");
        NmsAccess.invoke(connection, "handleClientCommand", packet);
    }

    @Override
    public void swapItemWithOffhand() {
        Object action = NmsAccess.enumValue(
                "net.minecraft.network.protocol.game.ServerboundPlayerActionPacket$Action",
                "SWAP_ITEM_WITH_OFFHAND"
        );
        Object pos = NmsAccess.newInstance("net.minecraft.core.BlockPos", 0, 0, 0);
        Object direction = NmsAccess.enumValue("net.minecraft.core.Direction", "DOWN");
        Object packet;
        try {
            packet = NmsAccess.newInstance(
                    "net.minecraft.network.protocol.game.ServerboundPlayerActionPacket",
                    action,
                    pos,
                    direction,
                    0
            );
        } catch (RuntimeException ignored) {
            packet = NmsAccess.newInstance(
                    "net.minecraft.network.protocol.game.ServerboundPlayerActionPacket",
                    action,
                    pos,
                    direction
            );
        }
        Object connection = NmsAccess.getField(handle, "connection");
        NmsAccess.invoke(connection, "handlePlayerAction", packet);
    }

    private static final class NoopSet extends AbstractSet<Object> {

        @Override
        public Iterator<Object> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public boolean add(Object value) {
            return false;
        }

        @Override
        public void clear() {
        }
    }
}
