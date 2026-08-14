package io.github.hello09x.fakeplayer.core.manager;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.devtools.command.exception.CommandException;
import io.github.hello09x.devtools.core.utils.Exceptions;
import io.github.hello09x.devtools.core.utils.MetadataUtils;
import io.github.hello09x.fakeplayer.api.spi.ActionSetting;
import io.github.hello09x.fakeplayer.api.spi.ActionType;
import io.github.hello09x.fakeplayer.api.spi.NMSBridge;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.constant.MetadataKeys;
import io.github.hello09x.fakeplayer.core.entity.Fakeplayer;
import io.github.hello09x.fakeplayer.core.entity.SpawnOption;
import io.github.hello09x.fakeplayer.core.manager.feature.FakeplayerFeatureManager;
import io.github.hello09x.fakeplayer.core.manager.naming.NameManager;
import io.github.hello09x.fakeplayer.core.repository.model.Feature;
import io.github.hello09x.fakeplayer.core.util.AddressUtils;
import io.github.hello09x.fakeplayer.core.util.Commands;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static net.kyori.adventure.text.Component.*;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.TextDecoration.ITALIC;

@Singleton
public class FakeplayerManager {

    public final static String REMOVAL_REASON_PREFIX = "[fakeplayer] ";

    private final static Logger log = Main.getInstance().getLogger();

    private final NameManager nameManager;
    private final FakeplayerList playerList;
    private final FakeplayerFeatureManager featureManager;
    private final NMSBridge nms;
    private final FakeplayerConfig config;
    private final Tasks.Task lagMonitor;
    /**
     * Console commands are executed on Folia's global region. Keep a tail per
     * fake player so post-spawn/post-quit hooks cannot overtake one another.
     */
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> commandChains = new ConcurrentHashMap<>();

    @Inject
    public FakeplayerManager(NameManager nameManager, FakeplayerList playerList, FakeplayerFeatureManager featureManager, NMSBridge nms, FakeplayerConfig config) {
        this.nameManager = nameManager;
        this.playerList = playerList;
        this.featureManager = featureManager;
        this.nms = nms;
        this.config = config;

        // Bukkit#getTPS is global server state. On Folia it must be read from the global
        // region; an ordinary ScheduledExecutorService is neither a valid region context nor
        // a safe place to kick players from multiple regions.
        this.lagMonitor = Tasks.runAtFixedRateGlobal(
                Main.getInstance(),
                () -> {
                    if (Bukkit.getServer().getTPS()[1] < config.getKaleTps()) {
                        if (this.removeAll("low tps") > 0) {
                            Bukkit.broadcast(translatable("fakeplayer.manager.remove-all-on-low-tps", GRAY, ITALIC));
                        }
                    }
                },
                0,
                20 * 60
        );
    }

    /**
     * 创建一个假人
     *
     * @param creator 创建者
     * @param spawnAt 生成地点
     */
    public @NotNull CompletableFuture<Player> spawnAsync(
            @NotNull CommandSender creator,
            @Nullable String name,
            @NotNull Location spawnAt,
            long lifespan
    ) {
        var spawnLocation = spawnAt.clone();
        return this.checkLimitAsync(creator).thenCompose(limitIgnored -> {
            var sequenceName = name == null
                    ? nameManager.getRegularNameAsync(creator)
                    : nameManager.getSpecifiedNameAsync(name);

            // ServerPlayer construction touches the target world/region. On Folia it
            // must happen in the region selected by the spawn location, not on the
            // command sender's region or on an arbitrary completion thread.
            return sequenceName.thenCompose(sn -> AddressUtils.getAddressAsync(creator)
                    .thenCompose(creatorIp -> {
                        log.info("UUID of fake player %s is %s".formatted(sn.name(), sn.uuid()));
                        return Tasks.callAt(Main.getInstance(), spawnLocation, () -> new Fakeplayer(
                                creator,
                                creatorIp,
                                sn,
                                lifespan,
                                spawnLocation
                        ));
                    }))
                    .thenCompose(fp -> {
                        this.playerList.add(fp);
                        return this.dispatchCommandsEarly(fp, this.config.getPreSpawnCommands())
                                .thenCompose(ignored -> {
                                    var configsFuture = featureManager.getFeaturesAsync(creator);
                                    return configsFuture.thenApply(configs -> new SpawnOption(
                                            spawnLocation,
                                            configs.get(Feature.invulnerable).asBoolean(),
                                            configs.get(Feature.collidable).asBoolean(),
                                            configs.get(Feature.look_at_entity).asBoolean(),
                                            configs.get(Feature.pickup_items).asBoolean(),
                                            configs.get(Feature.skin).asBoolean(),
                                            configs.get(Feature.replenish).asBoolean(),
                                            configs.get(Feature.autofish).asBoolean(),
                                            configs.get(Feature.wolverine).asBoolean()
                                    ));
                                })
                                .thenComposeAsync(fp::spawnAsync)
                                .thenApply(ignored -> fp.getPlayer())
                                .whenComplete((ignored, throwable) -> {
                                    if (throwable != null) {
                                        this.rollbackSpawn(fp, throwable);
                                    }
                                });
                    });
        });
    }

    /**
     * 获取一个假人
     *
     * @param creator 创建者
     * @param name    假人名称
     * @return 假人
     */
    public @Nullable Player get(@NotNull CommandSender creator, @NotNull String name) {
        return Optional
                .ofNullable(this.playerList.getByName(name))
                .filter(p -> p.isCreatedBy(creator))
                .map(Fakeplayer::getPlayer)
                .orElse(null);
    }

    /**
     * 根据名称获取假人
     *
     * @param name 名称
     * @return 假人
     */
    public @Nullable Player get(@NotNull String name) {
        return Optional
                .ofNullable(this.playerList.getByName(name))
                .map(Fakeplayer::getPlayer)
                .orElse(null);
    }

    /**
     * 获取一个假人的创建者, 如果这个玩家不是假人, 则为 {@code null}
     *
     * @param target 假人
     * @return 假人的创建者
     */
    public @Nullable String getCreatorName(@NotNull Player target) {
        return Optional
                .ofNullable(this.playerList.getByUUID(target.getUniqueId()))
                .map(Fakeplayer::getCreator)
                .map(CommandSender::getName)
                .orElse(null);
    }

    /**
     * 获取假人的创建者
     *
     * @param target 假人
     * @return 创建者
     */
    public @Nullable CommandSender getCreator(@NotNull Player target) {
        return Optional.ofNullable(this.playerList.getByUUID(target.getUniqueId()))
                       .map(Fakeplayer::getCreator)
                       .map(creator -> {
                           if (creator instanceof Player p) {
                               return Bukkit.getPlayer(p.getUniqueId());
                           } else {
                               return creator;
                           }
                       })
                       .orElse(null);
    }

    /**
     * Return the registry record for a fake player without touching the live
     * entity. Quit handlers use this snapshot before Folia retires the entity
     * scheduler, so delayed command hooks can still be formatted safely.
     */
    public @Nullable Fakeplayer getRecord(@NotNull Player target) {
        return this.playerList.getByUUID(target.getUniqueId());
    }

    /**
     * 根据名称删除假人
     *
     * @param name   名称
     * @param reason 原因
     * @return 是否删除成功
     */
    public boolean remove(@NotNull String name, @Nullable String reason) {
        return this.remove(name, reason == null ? null : text(reason));
    }

    /**
     * 根据名称删除假人
     *
     * @param name   名称
     * @param reason 原因
     * @return 是否移除成功
     */
    public boolean remove(@NotNull String name, @Nullable Component reason) {
        var target = this.get(name);
        if (target == null) {
            return false;
        }

        this.kick(target, textOfChildren(
                text("[fakeplayer] "),
                reason == null ? text("removed") : reason
        ));
        return true;
    }

    /**
     * 移除所有假人
     *
     * @return 移除的假人数量
     */
    public int removeAll(@Nullable String reason) {
        var targets = getAll();
        var message = text(REMOVAL_REASON_PREFIX + (reason == null ? "removed" : reason));
        for (var target : targets) {
            this.kick(target, message);
        }
        return targets.size();
    }

    /**
     * Kick a player on the region that owns the player entity. The Paper path stays
     * synchronous so existing command behaviour is preserved there.
     */
    private void kick(@NotNull Player target, @NotNull Component reason) {
        var fakeplayer = this.playerList.getByUUID(target.getUniqueId());
        if (Tasks.isFolia()) {
            Tasks.run(Main.getInstance(), target, () -> {
                if (target.isOnline()) {
                    target.kick(reason);
                }
                // PlayerQuitEvent owns cleanup on Folia. Calling close here as
                // well would re-enter the native PlayerList.remove path while
                // Folia is retiring the entity scheduler.
            });
        } else {
            target.kick(reason);
            // Paper 26 can leave an EmbeddedChannel open after Bukkit's
            // Player#kick has sent the disconnect request. Close the
            // synthetic network explicitly as a deterministic fallback; on
            // normal Paper versions PlayerQuitEvent has already made this a
            // harmless second close.
            if (fakeplayer != null) {
                fakeplayer.close();
            }
        }
    }

    /**
     * @return 获取所有假人
     */
    public @NotNull List<Player> getAll() {
        return this.getAll((Predicate<Player>) null);
    }

    /**
     * @param predicate 筛选条件
     * @return 经过筛选的假人
     */
    public @NotNull List<Player> getAll(@Nullable Predicate<Player> predicate) {
        var stream = this.playerList.getAll().stream().map(Fakeplayer::getPlayer);
        if (predicate != null) {
            stream = stream.filter(predicate);
        }
        return stream.toList();
    }

    /**
     * Returns the location snapshot published by the fake player's region
     * thread. This is safe to use while formatting global/command output on
     * Folia; callers that need the live entity should use the entity scheduler.
     */
    public @NotNull Location getLocationSnapshot(@NotNull Player target) {
        return Optional.ofNullable(this.playerList.getByUUID(target.getUniqueId()))
                .map(Fakeplayer::getLocationSnapshot)
                .orElseGet(() -> target.getLocation().clone());
    }

    /**
     * 清理假人
     *
     * @param target 假人
     */
    public void cleanup(@NotNull Player target) {
        var fakeplayer = this.playerList.removeByUUID(target.getUniqueId());
        if (fakeplayer == null) {
            return;
        }
        this.nameManager.unregister(fakeplayer.getSequenceName());
        try {
            if (config.isDropInventoryOnQuiting()) {
                this.nms.createAction(
                        fakeplayer.getPlayer(),
                        ActionType.DROP_INVENTORY,
                        ActionSetting.once()
                ).tick();
            }
        } finally {
            // PlayerQuitEvent can be followed by a retired entity scheduler on
            // Folia. Release the synthetic network even when inventory cleanup
            // is rejected by another plugin.
            fakeplayer.close();
        }
    }

    /**
     * 获取创建者创建的所有假人
     *
     * @param creator 创建者
     * @return 创建者创建的假人
     */
    public @NotNull List<Player> getAll(@NotNull CommandSender creator) {
        return this.getAll(creator, null);
    }

    /**
     * 获取筛选过的创建者创建的假人
     *
     * @param creator   创建者
     * @param predicate 筛选条件
     * @return 假人
     */
    public @NotNull List<Player> getAll(@NotNull CommandSender creator, @Nullable Predicate<Player> predicate) {
        var stream = this.playerList.getByCreator(creator.getName()).stream().map(Fakeplayer::getPlayer);
        if (predicate != null) {
            stream = stream.filter(predicate);
        }
        return stream.toList();
    }

    public int getSize() {
        return this.playerList.getSize();
    }

    /**
     * 判断一名玩家是否是假人
     *
     * @param target 玩家
     * @return 是否是假人
     */
    public boolean isFake(@NotNull Player target) {
        return this.playerList.getByUUID(target.getUniqueId()) != null;
    }

    /**
     * 判断一名玩家不是假人
     *
     * @param target 玩家
     * @return 是否不是假人
     */
    public boolean isNotFake(@NotNull Player target) {
        return this.playerList.getByUUID(target.getUniqueId()) == null;
    }

    /**
     * 获取 IP 地址创建着多少个假人
     *
     * @param address IP 地址
     * @return 该 IP 地址创建着多少个假人
     */
    public long countByAddress(@NotNull String address) {
        return this.playerList
                .stream()
                .filter(p -> p.getCreatorIp().equals(address))
                .count();
    }

    /**
     * 获取这个玩家创建了多少个假人
     *
     * @param creator 玩家
     * @return 创建了多少个假人
     */
    public int countByCreator(@NotNull CommandSender creator) {
        return this.playerList.countByCreator(creator.getName());
    }

    /**
     * 设置玩家当前选择的假人
     *
     * @param creator 玩家
     * @param target  假人
     */
    public void setSelection(@NotNull Player creator, @Nullable Player target) {
        if (target == null) {
            creator.removeMetadata(MetadataKeys.SELECTION, Main.getInstance());
            return;
        }

        if (!this.isFake(target)) {
            return;
        }

        creator.setMetadata(MetadataKeys.SELECTION, new FixedMetadataValue(Main.getInstance(), target.getUniqueId()));
    }

    /**
     * 获取当前选中的假人
     *
     * @param creator 创建者
     * @return 选中的假人
     */
    public @Nullable Player getSelection(@NotNull CommandSender creator) {
        if (!(creator instanceof Player p)) {
            return null;
        }
        if (!p.hasMetadata(MetadataKeys.SELECTION)) {
            return null;
        }

        var uuid = MetadataUtils
                .find(Main.getInstance(), p, MetadataKeys.SELECTION, UUID.class)
                .map(MetadataValue::value)
                .map(UUID.class::cast)
                .orElse(null);

        if (uuid == null) {
            return null;
        }

        var target = Optional.ofNullable(this.playerList.getByUUID(uuid)).map(Fakeplayer::getPlayer).orElse(null);
        if (target == null) {
            this.setSelection(p, null);
        }
        return target;
    }

    /**
     * 以假人身份执行命令
     *
     * @param target   假人
     * @param commands 命令
     */
    public void issueCommands(@NotNull Player target, @NotNull List<String> commands) {
        if (commands.isEmpty()) {
            return;
        }
        if (this.isNotFake(target)) {
            return;
        }

        var snapshot = List.copyOf(commands);
        if (Tasks.isFolia()) {
            Tasks.run(Main.getInstance(), target, () -> issueCommandsOnEntity(target, snapshot));
            return;
        }

        issueCommandsOnEntity(target, snapshot);
    }

    private void issueCommandsOnEntity(@NotNull Player target, @NotNull List<String> commands) {
        if (this.isNotFake(target)) {
            return;
        }

        var p = target.getName();
        var u = target.getUniqueId().toString();
        var c = Objects.requireNonNull(this.getCreatorName(target));
        for (var cmd : Commands.formatCommands(commands, "%p", p, "%u", u, "%c", c)) {
            if (!target.performCommand(cmd)) {
                log.warning(target.getName() + " failed to execute command: " + cmd);
            } else {
                log.info(target.getName() + " issued command: " + cmd);
            }
        }
    }

    public @NotNull CompletableFuture<Void> dispatchCommandsEarly(@NotNull Fakeplayer fp, @NotNull List<String> commands) {
        if (commands.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        var p = fp.getName();
        var u = fp.getUUID().toString();
        var c = fp.getCreator().getName();
        var formatted = Commands.formatCommands(commands, "%p", p, "%u", u, "%c", c);
        return this.dispatchConsoleCommands(formatted, p);
    }

    /**
     * 以控制台身份对玩家执行命令
     *
     * @param player   假人
     * @param commands 命令
     */
    public void dispatchCommands(@NotNull Player player, @NotNull List<String> commands) {
        if (commands.isEmpty()) {
            return;
        }

        // Use the registry object rather than the Bukkit entity. This keeps
        // post-quit command hooks working after Folia retires the entity's
        // scheduler, and all values needed for formatting are immutable.
        var fakeplayer = this.playerList.getByUUID(player.getUniqueId());
        if (fakeplayer == null) {
            return;
        }

        this.dispatchCommands(fakeplayer, commands);
    }

    /**
     * Dispatch commands using the immutable fake-player record. This overload
     * remains usable after PlayerQuitEvent has removed the record from the
     * live-player index.
     */
    public void dispatchCommands(@NotNull Fakeplayer fakeplayer, @NotNull List<String> commands) {
        if (commands.isEmpty()) {
            return;
        }

        this.dispatchCommandsAsync(fakeplayer, commands).exceptionally(throwable -> {
            log.warning("Failed to dispatch fake-player lifecycle commands for " + fakeplayer.getName() + ": " + throwable);
            return null;
        });
    }

    /**
     * Queue console commands behind commands already submitted for this fake
     * player. A failed command batch is contained so a later lifecycle hook is
     * still allowed to run.
     */
    public @NotNull CompletableFuture<Void> dispatchCommandsAsync(
            @NotNull Fakeplayer fakeplayer,
            @NotNull List<String> commands
    ) {
        var previous = this.commandChains.get(fakeplayer.getUUID());
        if (commands.isEmpty()) {
            return previous == null ? CompletableFuture.completedFuture(null) : previous.handle((ignored, throwable) -> null);
        }

        var p = fakeplayer.getName();
        var u = fakeplayer.getUUID().toString();
        var c = fakeplayer.getCreator().getName();
        var formatted = Commands.formatCommands(List.copyOf(commands), "%p", p, "%u", u, "%c", c);
        return this.commandChains.compute(fakeplayer.getUUID(), (uuid, tail) -> {
            var ready = tail == null
                    ? CompletableFuture.<Void>completedFuture(null)
                    : tail.handle((ignored, throwable) -> null);
            return ready.thenCompose(ignored -> this.dispatchConsoleCommands(formatted, p));
        });
    }

    /** Remove a completed lifecycle command chain after the after-quit hook. */
    public void clearCommandChain(@NotNull UUID uuid) {
        this.commandChains.remove(uuid);
    }

    private @NotNull CompletableFuture<Void> dispatchConsoleCommands(@NotNull List<String> commands, @NotNull String playerName) {
        Runnable dispatch = () -> {
            var server = Bukkit.getServer();
            var sender = Bukkit.getConsoleSender();
            for (var cmd : commands) {
                if (!server.dispatchCommand(sender, cmd)) {
                    log.warning("Failed to execute command for %s: ".formatted(playerName) + cmd);
                } else {
                    log.info("Dispatched command: " + cmd);
                }
            }
        };
        if (Tasks.isFolia()) {
            return Tasks.callGlobal(Main.getInstance(), () -> {
                dispatch.run();
                return null;
            });
        }
        dispatch.run();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 检测限制, 不满足条件则抛出异常
     *
     * @param creator 创建者
     */
    private @NotNull CompletableFuture<Void> checkLimitAsync(@NotNull CommandSender creator) {
        if (!Tasks.isFolia()) {
            this.checkLimit(creator);
            return CompletableFuture.completedFuture(null);
        }

        if (creator.isOp()) {
            return CompletableFuture.completedFuture(null);
        }

        if (this.playerList.getSize() >= this.config.getServerLimit()) {
            return CompletableFuture.failedFuture(
                    new CommandException(translatable("fakeplayer.command.spawn.error.server-limit"))
            );
        }

        if (this.playerList.getByCreator(creator.getName()).size() >= this.config.getPlayerLimit()) {
            return CompletableFuture.failedFuture(
                    new CommandException(translatable("fakeplayer.command.spawn.error.player-limit"))
            );
        }

        if (!this.config.isDetectIp()) {
            return CompletableFuture.completedFuture(null);
        }

        return AddressUtils.getAddressAsync(creator).thenAccept(address -> {
            if (this.countByAddress(address) >= this.config.getPlayerLimit()) {
                throw new CommandException(translatable("fakeplayer.command.spawn.error.ip-limit"));
            }
        });
    }

    private void checkLimit(@NotNull CommandSender creator) throws CommandException {
        if (creator.isOp()) {
            return;
        }

        if (this.playerList.getSize() >= this.config.getServerLimit()) {
            throw new CommandException(translatable("fakeplayer.command.spawn.error.server-limit"));
        }

        if (this.playerList.getByCreator(creator.getName()).size() >= this.config.getPlayerLimit()) {
            throw new CommandException(translatable("fakeplayer.command.spawn.error.player-limit"));
        }

        if (this.config.isDetectIp() && this.countByAddress(AddressUtils.getAddress(creator)) >= this.config.getPlayerLimit()) {
            throw new CommandException(translatable("fakeplayer.command.spawn.error.ip-limit"));
        }
    }

    public void onDisable() {
        Exceptions.suppress(Main.getInstance(), () -> this.removeAll("Plugin disabled"));
        Exceptions.suppress(Main.getInstance(), this.lagMonitor::cancel);
        this.commandChains.clear();
    }

    private void rollbackSpawn(@NotNull Fakeplayer fakeplayer, @NotNull Throwable throwable) {
        this.playerList.remove(fakeplayer);
        this.nameManager.unregister(fakeplayer.getSequenceName());
        this.commandChains.remove(fakeplayer.getUUID());

        try {
            fakeplayer.close();
        } catch (Throwable closeFailure) {
            log.warning("Failed to close fake-player resources for " + fakeplayer.getName() + ": " + closeFailure);
        }

        var player = fakeplayer.getPlayer();
        var reason = text("[fakeplayer] spawn failed");
        try {
            if (Tasks.isFolia()) {
                Tasks.run(Main.getInstance(), player, () -> {
                    // Kick even when the login pipeline failed halfway
                    // through and Bukkit has not published isOnline() yet;
                    // CraftPlayer#kick is also the cleanup path for a
                    // partially inserted PlayerList entry.
                    player.kick(reason);
                });
            } else {
                player.kick(reason);
            }
        } catch (Throwable kickFailure) {
            log.warning("Failed to remove failed fake player " + fakeplayer.getName() + ": " + kickFailure);
        }
        log.warning("Failed to spawn fake player " + fakeplayer.getName() + ": " + throwable);
    }

}
