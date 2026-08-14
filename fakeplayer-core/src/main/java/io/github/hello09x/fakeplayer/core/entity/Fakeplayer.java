package io.github.hello09x.fakeplayer.core.entity;

import io.github.hello09x.devtools.command.exception.CommandException;
import io.github.hello09x.devtools.core.utils.EntityUtils;
import io.github.hello09x.devtools.core.utils.WorldUtils;
import io.github.hello09x.fakeplayer.api.spi.*;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.config.PreventKicking;
import io.github.hello09x.fakeplayer.core.constant.MetadataKeys;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerAutofishManager;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerReplenishManager;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerSkinManager;
import io.github.hello09x.fakeplayer.core.manager.action.ActionManager;
import io.github.hello09x.fakeplayer.core.manager.naming.SequenceName;
import io.github.hello09x.fakeplayer.core.repository.model.Feature;
import io.github.hello09x.fakeplayer.core.util.Attributes;
import io.github.hello09x.fakeplayer.core.util.InternalAddressGenerator;
import lombok.Getter;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnknownNullability;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public class Fakeplayer {

    private final static InternalAddressGenerator ipGen = new InternalAddressGenerator();
    private final static FakeplayerConfig config = Main.getInjector().getInstance(FakeplayerConfig.class);
    private final static NMSBridge bridge = Main.getInjector().getInstance(NMSBridge.class);
    private final static FakeplayerSkinManager skinManager = Main.getInjector().getInstance(FakeplayerSkinManager.class);
    private final static FakeplayerReplenishManager replenishManager = Main.getInjector().getInstance(FakeplayerReplenishManager.class);
    private final static FakeplayerAutofishManager autofishManager = Main.getInjector().getInstance(FakeplayerAutofishManager.class);
    private final static ActionManager actionManager = Main.getInjector().getInstance(ActionManager.class);


    @NotNull
    @Getter
    private final CommandSender creator;

    @NotNull
    @Getter
    private final NMSServerPlayer handle;

    @NotNull
    @Getter
    private final Player player;

    @NotNull
    @Getter
    private final String creatorIp;

    @NotNull
    @Getter
    private final SequenceName sequenceName;

    @NotNull
    private final FakeplayerTicker ticker;

    @Getter
    @NotNull
    private final String name;

    @NotNull
    private final UUID uuid;

    @Getter
    @UnknownNullability
    private volatile NMSNetwork network;

    /** Last region-thread-owned location made available to global/command code. */
    private volatile Location locationSnapshot;

    /**
     * @param creator      创建者
     * @param creatorIp    创建者 IP
     * @param sequenceName 序列名
     * @param lifespan     存活时间
     */
    public Fakeplayer(
            @NotNull CommandSender creator,
            @NotNull String creatorIp,
            @NotNull SequenceName sequenceName,
            long lifespan
    ) {
        this(creator, creatorIp, sequenceName, lifespan, Bukkit.getWorlds().get(0).getSpawnLocation());
    }

    public Fakeplayer(
            @NotNull CommandSender creator,
            @NotNull String creatorIp,
            @NotNull SequenceName sequenceName,
            long lifespan,
            @NotNull Location initialLocation
    ) {
        this.name = sequenceName.name();
        this.uuid = sequenceName.uuid();

        this.creator = creator;
        this.creatorIp = creatorIp;
        this.sequenceName = sequenceName;
        this.handle = bridge.fromServer(Bukkit.getServer()).newPlayer(uuid, name, initialLocation.getWorld());
        this.player = handle.getPlayer();
        this.locationSnapshot = initialLocation.clone();

        this.ticker = new FakeplayerTicker(this, lifespan);
    }

    /**
     * 让假人诞生
     */
    public CompletableFuture<Void> spawnAsync(@NotNull SpawnOption option) {
        var address = ipGen.next();
        return Tasks
                .runAsync(Main.getInstance(), () -> {
                    var event = this.callPreLoginEvent(address);
                    if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                        throw new CommandException(translatable(
                                "fakeplayer.command.spawn.error.disallowed",
                                text(player.getName(), WHITE),
                                event.kickMessage()
                        ).color(RED));
                    }
                })
                .thenComposeAsync(nul -> Tasks.<CompletableFuture<Void>>callAt(Main.getInstance(), option.spawnAt(), () -> {
                    this.player.setPersistent(config.isPersistData());
                    this.player.setSleepingIgnored(true);
                    this.handle.setPlayBefore(); // 可避免一些插件的第一次入服欢迎信息
                    this.handle.disableAdvancements(Main.getInstance()); // 不提示成就信息
                    this.player.setMetadata(MetadataKeys.SPAWNED_AT, new FixedMetadataValue(Main.getInstance(), Bukkit.getCurrentTick()));

                    {
                        var event = this.callLoginEvent(address);
                        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED && config.getPreventKicking().ordinal() < PreventKicking.ON_SPAWNING.ordinal()) {
                            throw new CommandException(translatable(
                                    "fakeplayer.command.spawn.error.disallowed", RED,
                                    text(player.getName(), WHITE),
                                    event.kickMessage()
                            ));
                        }
                    }

                    if (config.isDropInventoryOnQuiting()) {
                        // 跨服背包同步插件可能导致假人既丢弃了一份到地上，在重新生成的时候又回来了
                        // 因此在生成的时候清空一次背包
                        // 但无法解决登陆后延迟同步背包的情况
                        this.player.getInventory().clear();
                    }

                    this.player.setInvulnerable(option.invulnerable());
                    this.player.setCollidable(option.collidable());
                    this.player.setCanPickupItems(option.pickupItems());
                    if (option.lookAtEntity()) {
                        actionManager.setAction(player, ActionType.LOOK_AT_NEAREST_ENTITY, ActionSetting.continuous());
                    }
                    if (option.wolverine()) {
                        Feature.wolverine.getModifier().accept(player, "true");
                    }
                    if (option.skin()) {
                        if (Tasks.isFolia()) {
                            skinManager.useDefaultSkinAsync(creator, player);
                        } else {
                            skinManager.useDefaultSkin(creator, player);
                        }
                    }
                    if (option.replenish()) {
                        replenishManager.setReplenish(player, true);
                    }
                    if (option.autofish()) {
                        autofishManager.setAutofish(player, true);
                    }

                    this.network = bridge.createNetwork(address);
                    this.network.placeNewPlayer(Bukkit.getServer(), this.player, option.spawnAt());
                    this.player.setHealth(Optional.ofNullable(this.player.getAttribute(Attributes.maxHealth()))
                                                  .map(AttributeInstance::getValue)
                                                  .orElse(20D));    // 恢复生命值
                    this.player.setFoodLevel(20);
                    this.setupName();
                    this.handle.setupClientOptions();   // 处理皮肤设置问题

                    return this.teleportToSpawnpoint(option.spawnAt().clone()).thenCompose(success -> {
                        if (!success) {
                            return CompletableFuture.failedFuture(new CommandException(translatable(
                                    "fakeplayer.command.spawn.error.teleport-failed",
                                    text(player.getName(), WHITE)
                            ).color(RED)));
                        }

                        // A failed teleport must not leave a live ticker behind.
                        // The completion may run after the entity has moved to a
                        // different Folia region, so schedule the start again
                        // through the entity scheduler.
                        Tasks.run(Main.getInstance(), this.player, () -> this.ticker.start(Main.getInstance()));
                        return CompletableFuture.completedFuture(null);
                    });
                })).thenCompose(future -> future);
    }

    /**
     * 将假人传送到指定位置
     *
     * @param to 目标位置
     */
    private CompletableFuture<Boolean> teleportToSpawnpoint(@NotNull Location to) {
        if (Tasks.isFolia()) {
            return this.teleportFolia(to);
        }

        var from = this.player.getLocation();
        if (from.getWorld().equals(to.getWorld())) {
            // 如果生成世界等于目的世界, 则需要穿越一次维度才能获取刷怪能力
            var otherWorld = WorldUtils.getOtherWorld(from.getWorld());
            if (otherWorld == null || !player.teleport(otherWorld.getSpawnLocation())) {
                this.sendCreatorMessage(translatable(
                        "fakeplayer.command.spawn.error.no-mob-spawning-ability",
                        text(player.getName(), WHITE)
                ).color(GRAY));
            }
        }

        return Tasks.call(Main.getInstance(), this.player, () -> {
            if (!EntityUtils.teleportAndSound(player, to)) {
                this.sendCreatorMessage(translatable(
                        "fakeplayer.command.spawn.error.teleport-failed",
                        text(player.getName(), WHITE)
                ).color(GRAY));
                return false;
            }
            return true;
        });
    }

    /**
     * Entity#teleport is not a supported cross-region operation on Folia.
     * Use the region-aware asynchronous API and only report the result after
     * the entity has been placed in its destination region.
     */
    private CompletableFuture<Boolean> teleportFolia(@NotNull Location to) {
        var from = this.player.getLocation();
        CompletableFuture<Boolean> movement;
        if (from.getWorld().equals(to.getWorld())) {
            var otherWorld = WorldUtils.getOtherWorld(from.getWorld());
            if (otherWorld == null) {
                this.sendCreatorMessage(translatable(
                        "fakeplayer.command.spawn.error.no-mob-spawning-ability",
                        text(player.getName(), WHITE)
                ).color(GRAY));
                return CompletableFuture.completedFuture(false);
            }

            movement = this.player.teleportAsync(otherWorld.getSpawnLocation())
                    .thenCompose(success -> success
                            ? this.player.teleportAsync(to)
                            : CompletableFuture.completedFuture(false));
        } else {
            movement = this.player.teleportAsync(to);
        }

        movement.thenAccept(success -> {
            if (!success) {
                this.sendCreatorMessage(translatable(
                        "fakeplayer.command.spawn.error.teleport-failed",
                        text(player.getName(), WHITE)
                ).color(GRAY));
            }
        });
        return movement;
    }

    private void sendCreatorMessage(@NotNull net.kyori.adventure.text.Component message) {
        if (this.creator instanceof Player creatorPlayer && Tasks.isFolia()) {
            Tasks.run(Main.getInstance(), creatorPlayer, () -> this.creator.sendMessage(message));
        } else {
            this.creator.sendMessage(message);
        }
    }

    public boolean isOnline() {
        return this.player.isOnline();
    }

    /**
     * Stop region work and release the synthetic network. This method contains
     * no Bukkit entity mutation and is therefore safe to call from a failed
     * asynchronous spawn completion as well as from the quit cleanup path.
     */
    public void close() {
        this.ticker.stop();
        try {
            actionManager.cleanup(this.player);
        } catch (Throwable ignored) {
            // A retired Folia entity scheduler must not prevent the network
            // itself from being released below.
        }
        var currentNetwork = this.network;
        this.network = null;
        if (currentNetwork != null) {
            try {
                currentNetwork.close();
            } catch (Throwable ignored) {
                // Cleanup is best-effort after a failed login/quit.
            }
        }
    }

    public @NotNull Location getLocationSnapshot() {
        return this.locationSnapshot.clone();
    }

    /** Called only from the fake player's owning region thread. */
    public void updateLocationSnapshot() {
        this.locationSnapshot = this.player.getLocation().clone();
    }

    public int getTickCount() {
        return handle.getTickCount();
    }

    private @NotNull AsyncPlayerPreLoginEvent callPreLoginEvent(@NotNull InetAddress address) {
        var event = new AsyncPlayerPreLoginEvent(
                this.name,
                address,
                this.uuid
        );
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    private @NotNull PlayerLoginEvent callLoginEvent(@NotNull InetAddress address) {
        var event = new PlayerLoginEvent(
                player,
                address.getHostAddress(),
                address
        );
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    /**
     * 判断是否是创建者
     * <p>如果玩家下线再重新登陆, entityID 将会不一样导致 {@link Object#equals(Object)} 返回 {@code false}</p>
     *
     * @param sender 命令执行者
     * @return 是否是创建者
     */
    public boolean isCreatedBy(@NotNull CommandSender sender) {
        if (this.creator instanceof Player pc && sender instanceof Player ps) {
            return pc.getUniqueId().equals(ps.getUniqueId());
        }
        return creator.getClass() == sender.getClass() && creator.getName().equals(sender.getName());
    }

    public @NotNull UUID getUUID() {
        return uuid;
    }

    private void setupName() {
        var displayName = text(player.getName(), config.getNameStyleColor(), config.getNameStyleDecorations().toArray(new TextDecoration[0]));
        player.playerListName(displayName);
        player.displayName(displayName);
        player.customName(displayName);
    }
}
