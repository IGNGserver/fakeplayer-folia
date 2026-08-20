package io.github.hello09x.fakeplayer.v26_1_2.spi;

import io.github.hello09x.fakeplayer.api.spi.NMSNetwork;
import io.github.hello09x.fakeplayer.api.spi.NMSServerGamePacketListener;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerManager;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * Creates the in-memory connection used by a fake player on the 26.x server.
 *
 * <p>The server's {@code Connection} is concrete and its channel is public in
 * Folia's patched 26.x runtime. An {@code EmbeddedChannel} gives the normal
 * PlayerList login code a real, active Netty channel without binding a port.
 * A tiny outbound handler keeps the two behaviours the old fake listener
 * supplied: forwarding BungeeCord payloads and applying server-side motion.</p>
 */
final class NMSNetworkImpl implements NMSNetwork {

    private static final Logger LOG = Main.getInstance().getLogger();

    private final InetAddress address;
    private final Queue<Object> outbound = new ConcurrentLinkedQueue<>();

    private volatile Object connection;
    private volatile Object serverHandle;
    private volatile Object playerHandle;
    private volatile Player player;
    private volatile NMSServerGamePacketListener listener;
    private volatile Tasks.Task outboundTask;

    NMSNetworkImpl(@NotNull InetAddress address) {
        this.address = address;
    }

    @Override
    public @NotNull NMSServerGamePacketListener placeNewPlayer(
            @NotNull Server server,
            @NotNull Player player
    ) {
        return placeNewPlayer(server, player, player.getLocation());
    }

    @Override
    public @NotNull NMSServerGamePacketListener placeNewPlayer(
            @NotNull Server server,
            @NotNull Player player,
            @NotNull Location spawnAt
    ) {
        if (this.listener != null) {
            return this.listener;
        }

        this.player = player;
        try {
            Object serverHandle = NmsAccess.serverHandle(server);
            this.serverHandle = serverHandle;
            Object playerHandle = NmsAccess.handle(player);
            this.playerHandle = playerHandle;
            prepareSpawnWorld(playerHandle, spawnAt);
            this.connection = createConnection(serverHandle);
            // PlayerList.placeNewPlayer emits the initial login/play packets
            // synchronously. Install the outbound observer before entering it so
            // those packets follow the same path as later packets.
            installOutboundCapture();

            Object profile = NmsAccess.invokeOptional(playerHandle, "getGameProfile");
            if (profile == null) {
                profile = NmsAccess.newInstance(
                        "com.mojang.authlib.GameProfile",
                        player.getUniqueId(),
                        player.getName()
                );
            }
            Object cookie = NmsAccess.invokeStatic(
                    "net.minecraft.server.network.CommonListenerCookie",
                    "createInitial",
                    profile,
                    false
            );

            Object playerList = NmsAccess.invoke(serverHandle, "getPlayerList");
            NmsAccess.invoke(playerList, "placeNewPlayer", connection, playerHandle, cookie);

            this.listener = new Listener(NmsAccess.getField(playerHandle, "connection"));
            // Preserve the channel registration performed by the legacy listener.
            // Some proxy/plugin-message integrations inspect the player's registered
            // channels before sending the BungeeCord payload.
            NmsAccess.invokeOptional(player, "addChannel", NMSServerGamePacketListener.BUNGEE_CORD_CORRECTED_CHANNEL);
            this.outboundTask = Tasks.runAtFixedRate(
                    Main.getInstance(),
                    player,
                    this::drainOutbound,
                    0,
                    1
            );
            return this.listener;
        } catch (Throwable throwable) {
            // PlayerList.placeNewPlayer may fail after allocating the connection
            // but before the registry receives PlayerQuitEvent. Release the
            // synthetic channel here so the failed spawn cannot leak a task or
            // an EmbeddedChannel.
            this.close();
            throw NmsAccess.rethrow(throwable);
        }
    }

    private static void prepareSpawnWorld(Object playerHandle, Location spawnAt) {
        Object worldHandle = NmsAccess.handle(spawnAt.getWorld());
        NmsAccess.invokeOptional(playerHandle, "setServerLevel", worldHandle);
        // ServerPlayer.snapTo() sends a position update through connection and
        // therefore throws on 1.21.11 before PlayerList.placeNewPlayer has
        // installed ServerGamePacketListenerImpl. Set the raw entity position
        // during pre-login; the login path will publish it to the client.
        try {
            NmsAccess.invoke(playerHandle, "setPos", spawnAt.getX(), spawnAt.getY(), spawnAt.getZ());
        } catch (RuntimeException ignored) {
            // Keep compatibility with transitional Mojang mappings that do not
            // expose the raw three-coordinate setter.
            NmsAccess.invoke(playerHandle, "snapTo", spawnAt.getX(), spawnAt.getY(), spawnAt.getZ());
        }
        NmsAccess.invokeOptional(playerHandle, "setYRot", spawnAt.getYaw());
        NmsAccess.invokeOptional(playerHandle, "setXRot", spawnAt.getPitch());
    }

    @Override
    public @NotNull NMSServerGamePacketListener getServerGamePacketListener() throws IllegalStateException {
        if (this.listener == null) {
            throw new IllegalStateException("not initialized");
        }
        return this.listener;
    }

    @Override
    public void close() {
        var task = this.outboundTask;
        this.outboundTask = null;
        if (task != null) {
            task.cancel();
        }
        this.outbound.clear();

        var currentConnection = this.connection;
        this.connection = null;
        var currentServerHandle = this.serverHandle;
        this.serverHandle = null;
        var currentPlayerHandle = this.playerHandle;
        // On Folia this method is normally reached from PlayerQuitEvent while
        // the native disconnect routine is still removing the entity. Calling
        // Connection#disconnect or handleDisconnection again would re-enter
        // PlayerList.remove and retire the entity scheduler twice. The native
        // path owns the connection teardown there; this method only releases
        // fakeplayer-side state. Paper 26 needs the explicit fallback because
        // its synthetic EmbeddedChannel may not invoke the native callback.
        // Paper 1.21.11 already completes Player#kick through the native
        // connection and PlayerQuitEvent path. Re-entering Connection#disconnect
        // from that callback retires the same entity scheduler twice. The
        // embedded-channel fallback is only required by the 26.x Paper runtime;
        // the 1.21.11 provider delegates here only for shared protocol code.
        if (currentConnection != null && !Tasks.isFolia() && isPaper26OrLater()) {
            try {
                // Player#kick delegates to the packet listener. On Paper 26
                // the EmbeddedChannel's outbound completion listener can be
                // bypassed by the in-memory pipeline, so force the vanilla
                // Connection disconnect transition as well. This closes the
                // channel and records DisconnectionDetails before the
                // idempotent handleDisconnection() call below.
                var genericReason = NmsAccess.invokeStatic(
                        "net.minecraft.network.chat.Component",
                        "translatable",
                        "multiplayer.disconnect.generic"
                );
                NmsAccess.invoke(currentConnection, "disconnect", genericReason);
            } catch (Throwable ignored) {
                // Keep the raw channel close as a fallback for transitional
                // 26.x mappings that do not expose the same overload.
            }
            var channel = NmsAccess.getFieldOptional(currentConnection, "channel");
            NmsAccess.invokeOptional(channel, "close");
            NmsAccess.invokeOptional(channel, "finishAndReleaseAll");
            try {
                // EmbeddedChannel#close does not reliably drive Connection's
                // channelInactive callback on Paper 26. That leaves the
                // ServerGamePacketListenerImpl attached to PlayerList even
                // though Bukkit#kick has already sent its disconnect packet.
                // Invoke the vanilla idempotent disconnect hook explicitly so
                // PlayerList removal, PlayerQuitEvent and plugin integrations
                // follow the same path as a real client disconnect.
                NmsAccess.invoke(currentConnection, "handleDisconnection");
            } catch (Throwable throwable) {
                LOG.warning("Failed to complete fake-player disconnect: " + throwable.getMessage());
            }
            forcePlayerListenerDisconnect(currentServerHandle, currentPlayerHandle);
        }
        if (currentPlayerHandle != null) {
            NmsAccess.cleanupAdvancementSink(currentPlayerHandle);
            try {
                // Player removal is normally synchronous, but Folia may defer
                // part of the disconnect callback by one global tick.
                Tasks.runGlobalDelayed(
                        Main.getInstance(),
                        () -> NmsAccess.cleanupAdvancementSink(currentPlayerHandle),
                        1
                );
            } catch (Throwable ignored) {
                // The immediate cleanup above is sufficient during shutdown.
            }
        }
        this.listener = null;
        this.playerHandle = null;
        this.player = null;
    }

    private static boolean isPaper26OrLater() {
        String version = Bukkit.getMinecraftVersion();
        return version != null && version.startsWith("26.");
    }

    private static void forcePlayerListenerDisconnect(Object serverHandle, Object playerHandle) {
        if (serverHandle == null || playerHandle == null) {
            LOG.warning("Cannot finalize fake-player server entry: missing server/player handle");
            return;
        }

        try {
            Object playerList = NmsAccess.invoke(serverHandle, "getPlayerList");
            Object uuid = NmsAccess.invokeOptional(playerHandle, "getUUID");
            if (uuid == null || NmsAccess.invokeOptional(playerList, "getPlayer", uuid) == null) {
                // The normal Connection#handleDisconnection path already removed
                // the player. Avoid firing PlayerQuitEvent a second time.
                return;
            }
            // Paper 26's Connection can be closed without reaching its
            // packet listener when the outbound pipeline is synthetic. The
            // Paper PlayerList.remove overload is the canonical finalization
            // path here: it fires PlayerQuitEvent, closes open inventories,
            // saves state, and removes the player from the server registry.
            NmsAccess.invoke(playerList, "remove", playerHandle);
        } catch (Throwable throwable) {
            LOG.warning("Failed to remove fake player from the server player list: " + throwable.getMessage());
        }
    }

    private Object createConnection(Object serverHandle) {
        Object connection = NmsAccess.newInstance(
                "net.minecraft.network.Connection",
                NmsAccess.enumValue("net.minecraft.network.protocol.PacketFlow", "SERVERBOUND")
        );
        Object channel = NmsAccess.newInstance("io.netty.channel.embedded.EmbeddedChannel");
        NmsAccess.setField(connection, "channel", channel);
        NmsAccess.setField(connection, "address", new InetSocketAddress(address, 25565));
        Object pipeline = NmsAccess.invoke(channel, "pipeline");
        Object flow = NmsAccess.enumValue("net.minecraft.network.protocol.PacketFlow", "SERVERBOUND");
        NmsAccess.invokeStatic(
                "net.minecraft.network.Connection",
                "configureSerialization",
                pipeline,
                flow,
                false,
                null
        );
        NmsAccess.invoke(connection, "configurePacketHandler", pipeline);
        // EmbeddedChannel#channelActive lets Connection derive its address
        // from the channel. Restore the synthetic client address afterwards so
        // plugins observing the NMS connection see the same value as the login
        // events created by Fakeplayer.
        NmsAccess.setField(connection, "address", new InetSocketAddress(address, 25565));
        // PlayerList.placeNewPlayer sends play packets immediately. Switch the
        // outbound side before invoking it; the inbound side is switched by
        // PlayerList itself when it installs ServerGamePacketListenerImpl.
        Object clientboundTemplate = NmsAccess.getStaticField(
                "net.minecraft.network.protocol.game.GameProtocols",
                "CLIENTBOUND_TEMPLATE"
        );
        Object registryAccess = NmsAccess.invoke(serverHandle, "registryAccess");
        Object decorator = NmsAccess.invokeStatic(
                "net.minecraft.network.RegistryFriendlyByteBuf",
                "decorator",
                registryAccess
        );
        Object clientbound = NmsAccess.invoke(clientboundTemplate, "bind", decorator);
        NmsAccess.invoke(connection, "setupOutboundProtocol", clientbound);
        // Folia's Connection.send drops packets only after the connection is no
        // longer preparing. EmbeddedChannel is already active, so this mirrors
        // the post-channelActive state of a normal connection.
        NmsAccess.setFieldIfPresent(connection, "preparing", false);
        return connection;
    }

    private void installOutboundCapture() {
        Object channel = NmsAccess.getField(connection, "channel");
        Object pipeline = NmsAccess.invoke(channel, "pipeline");
        Class<?> handlerType = NmsAccess.classForName("io.netty.channel.ChannelOutboundHandler");
        InvocationHandler handler = this::captureInvocation;
        Object proxy = Proxy.newProxyInstance(
                handlerType.getClassLoader(),
                new Class<?>[]{handlerType},
                handler
        );
        NmsAccess.invoke(pipeline, "addLast", "fakeplayer-outbound-capture", proxy);
    }

    private Object captureInvocation(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if (name.equals("toString")) {
            return "FakeplayerOutboundCapture";
        }
        if (name.equals("hashCode")) {
            return System.identityHashCode(proxy);
        }
        if (name.equals("equals")) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        if (name.equals("write") && args != null && args.length >= 3) {
            if (isDisconnectPacket(args[1])) {
                // Paper 26 disconnects through a clientbound disconnect packet
                // whose completion listener closes the channel. Let it pass
                // through the real pipeline so PlayerQuitEvent and the normal
                // fake-player cleanup path are still fired.
                return NmsAccess.invoke(args[0], "write", args[1], args[2]);
            }
            if (!isMinecraftPacket(args[1])) {
                // Connection.setupOutboundProtocol sends an internal
                // configuration task through the same pipeline. It must reach
                // the vanilla unconfigured handler or the encoder will never
                // switch to the clientbound play protocol.
                return NmsAccess.invoke(args[0], "write", args[1], args[2]);
            }
            outbound.offer(args[1]);
            // There is no client-side channel to consume the encoded bytes.
            // Mark the promise successful and stop the message here; forwarding
            // it to EmbeddedChannel would retain every packet in its outbound
            // queue for the lifetime of the fake player.
            NmsAccess.invokeOptional(args[2], "setSuccess");
            return null;
        }
        if (args != null && args.length > 0 && args[0] != null) {
            Object[] forwarded = java.util.Arrays.copyOfRange(args, 1, args.length);
            try {
                return NmsAccess.invoke(args[0], name, forwarded);
            } catch (RuntimeException ignored) {
                // A handler callback which is irrelevant to an embedded fake
                // channel should not make a login packet fail.
                return defaultValue(method.getReturnType());
            }
        }
        if (method.getReturnType() == boolean.class) {
            return true;
        }
        return defaultValue(method.getReturnType());
    }

    private static boolean isMinecraftPacket(Object message) {
        return message != null && message.getClass().getName().startsWith("net.minecraft.network.protocol.");
    }

    private static boolean isDisconnectPacket(Object message) {
        if (message == null) {
            return false;
        }
        var className = message.getClass().getName();
        return className.endsWith("ClientboundDisconnectPacket")
                || className.endsWith("ClientboundLoginDisconnectPacket");
    }

    private void drainOutbound() {
        if (player == null || !player.isOnline()) {
            if (outboundTask != null) {
                outboundTask.cancel();
                outboundTask = null;
            }
            outbound.clear();
            return;
        }

        Object packet;
        while ((packet = outbound.poll()) != null) {
            String className = packet.getClass().getName();
            try {
                if (className.endsWith("ClientboundSetEntityMotionPacket")) {
                    handleMotion(packet);
                } else if (className.endsWith("ClientboundCustomPayloadPacket")) {
                    handleCustomPayload(packet);
                }
            } catch (Throwable throwable) {
                LOG.warning("Failed to process an outbound fake-player packet: " + throwable.getMessage());
            }
        }
    }

    private void handleMotion(Object packet) {
        Object id = NmsAccess.invokeOptional(packet, "id");
        if (id == null) {
            id = NmsAccess.invokeOptional(packet, "getId");
        }
        if (id == null || NmsAccess.integer(id) != player.getEntityId()) {
            return;
        }
        Object movement = NmsAccess.invokeOptional(packet, "movement");
        if (movement == null) {
            movement = NmsAccess.invoke(packet, "getMovement");
        }
        if (!NmsAccess.bool(NmsAccess.getFieldOptional(playerHandle, "hurtMarked"))) {
            return;
        }
        NmsAccess.invoke(playerHandle, "lerpMotion", movement);
    }

    private void handleCustomPayload(Object packet) {
        Object payload = NmsAccess.invoke(packet, "payload");
        Object type = NmsAccess.invoke(payload, "type");
        Object id = NmsAccess.invoke(type, "id");
        if (!NMSServerGamePacketListener.BUNGEE_CORD_CORRECTED_CHANNEL.equals(String.valueOf(id))) {
            return;
        }

        Object data = NmsAccess.invokeOptional(payload, "data");
        byte[] bytes = toBytes(data);
        if (bytes == null) {
            return;
        }

        byte[] message = bytes;
        FakeplayerManager manager = Main.getInjector().getInstance(FakeplayerManager.class);
        // Bukkit#getOnlinePlayers is global server state. Resolve the recipient
        // on Folia's global region before switching to that player's region.
        Tasks.callGlobal(Main.getInstance(), () -> Bukkit.getOnlinePlayers().stream()
                        .filter(manager::isNotFake)
                        .findAny()
                        .orElse(null))
                .thenAccept(recipient -> {
                    if (recipient == null) {
                        LOG.warning("Failed to forward a BungeeCord payload: no real players are online");
                        return;
                    }
                    Tasks.run(Main.getInstance(), recipient, () ->
                            recipient.sendPluginMessage(Main.getInstance(), NMSServerGamePacketListener.BUNGEE_CORD_CHANNEL, message)
                    );
                });
    }

    private static byte[] toBytes(Object data) {
        if (data instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (data instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.asReadOnlyBuffer();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return bytes;
        }
        if (data == null) {
            return null;
        }

        // Paper 26 stores DiscardedPayload data as byte[]. This fallback also
        // supports a ByteBuf-shaped payload from a transitional build.
        Object readable = NmsAccess.invokeOptional(data, "readableBytes");
        if (readable == null) {
            return null;
        }
        byte[] bytes = new byte[NmsAccess.integer(readable)];
        Object index = NmsAccess.invokeOptional(data, "readerIndex");
        int start = index == null ? 0 : NmsAccess.integer(index);
        NmsAccess.invoke(data, "getBytes", start, bytes);
        return bytes;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private static final class Listener implements NMSServerGamePacketListener {
        private final Object handle;

        private Listener(Object handle) {
            this.handle = handle;
        }
    }
}
