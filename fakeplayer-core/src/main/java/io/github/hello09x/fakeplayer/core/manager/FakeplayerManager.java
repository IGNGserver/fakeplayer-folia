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
import io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandCoordinator;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
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
    private final LifecycleCommandCoordinator lifecycleCoordinator;
    private final Tasks.Task lagMonitor;
    private final SpawnQuota spawnQuota = new SpawnQuota();
    private volatile boolean shuttingDown;
    private volatile boolean shutdownFinalized;
    /**
     * Console commands are executed on Folia's global region. Keep a tail per
     * fake player so post-spawn/post-quit hooks cannot overtake one another.
     */
    // A persistent fake-player UUID can be reused after a quit. Key the
    // lifecycle chain by the concrete spawn instance so a delayed after-quit
    // hook from an older instance cannot clear or precede a newer one.
    private final ConcurrentHashMap<Fakeplayer, CompletableFuture<Void>> commandChains = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Fakeplayer, LifecycleCommandCoordinator.Handle> lifecycleTransactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Fakeplayer, Tasks.Task> afterSpawnTasks = new ConcurrentHashMap<>();
    private final Set<Fakeplayer> quittingFakeplayers = ConcurrentHashMap.newKeySet();

    @Inject
    public FakeplayerManager(
            NameManager nameManager,
            FakeplayerList playerList,
            FakeplayerFeatureManager featureManager,
            NMSBridge nms,
            FakeplayerConfig config,
            LifecycleCommandCoordinator lifecycleCoordinator
    ) {
        this.nameManager = nameManager;
        this.playerList = playerList;
        this.featureManager = featureManager;
        this.nms = nms;
        this.config = config;
        this.lifecycleCoordinator = lifecycleCoordinator;

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
        if (this.shuttingDown) {
            return CompletableFuture.failedFuture(new IllegalStateException("Fakeplayer is shutting down"));
        }
        var spawnLocation = spawnAt.clone();
        return this.reserveSpawnAsync(creator).thenCompose(reservation -> {
            var sequenceRef = new AtomicReference<io.github.hello09x.fakeplayer.core.manager.naming.SequenceName>();
            var fakeplayerRef = new AtomicReference<Fakeplayer>();
            var sequenceName = name == null
                    ? nameManager.getRegularNameAsync(creator)
                    : nameManager.getSpecifiedNameAsync(name);

            // ServerPlayer construction touches the target world/region. On Folia it
            // must happen in the region selected by the spawn location, not on the
            // command sender's region or on an arbitrary completion thread.
            return sequenceName.thenCompose(sn -> {
                        sequenceRef.set(sn);
                        log.info("UUID of fake player %s is %s".formatted(sn.name(), sn.uuid()));
                        return Tasks.callAt(Main.getInstance(), spawnLocation, () -> new Fakeplayer(
                                creator,
                                reservation.address(),
                                sn,
                                lifespan,
                                spawnLocation
                        ));
                    })
                    .thenCompose(fp -> {
                        fakeplayerRef.set(fp);
                        var handleRef = new AtomicReference<LifecycleCommandCoordinator.Handle>();
                        return this.lifecycleCoordinator.prepareAsync(
                                        fp,
                                        this.config.getPreSpawnCommands(),
                                        this.config.getPreSpawnRollbackCommands(),
                                        this.config.getPostQuitCommands(),
                                        this.config.getAfterQuitCommands()
                                )
                                .thenCompose(handle -> {
                                    handleRef.set(handle);
                                    this.lifecycleTransactions.put(fp, handle);
                                    return this.lifecycleCoordinator.runPreSpawnAsync(
                                                    handle,
                                                    fp,
                                                    this.config.getPreSpawnCommands()
                                            )
                                            .thenCompose(ignored -> {
                                                if (this.shuttingDown) {
                                                    throw new IllegalStateException("Fakeplayer is shutting down");
                                                }
                                                if (!this.spawnQuota.commit(reservation.reservation(), () -> this.playerList.add(fp))) {
                                                    throw new IllegalStateException("Fake-player registry rejected duplicate name or UUID: " + fp.getName());
                                                }
                                                return featureManager.getFeaturesAsync(creator).thenApply(configs -> new SpawnOption(
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
                                            .thenCompose(fp::spawnAsync)
                                            // The durable transaction becomes ACTIVE only
                                            // after every fallible spawn/login/teleport stage
                                            // has completed. PlayerJoinEvent no longer owns
                                            // external lifecycle command dispatch.
                                            .thenCompose(ignored -> this.lifecycleCoordinator.activateAsync(handle))
                                            .thenApply(ignored -> {
                                                this.runCommittedSpawnHooks(fp);
                                                return fp.getPlayer();
                                            });
                                })
                                .<CompletableFuture<Player>>handle((player, throwable) -> {
                                    if (throwable == null) {
                                        return CompletableFuture.completedFuture(player);
                                    }
                                    var failure = LifecycleCommandCoordinator.unwrap(throwable);
                                    var handle = handleRef.get();
                                    var compensation = handle == null
                                            ? CompletableFuture.<Void>completedFuture(null)
                                            : this.lifecycleCoordinator.rollbackAsync(handle);
                                    return compensation.handle((ignored, compensationFailure) -> {
                                        this.lifecycleTransactions.remove(fp);
                                        if (compensationFailure != null) {
                                            failure.addSuppressed(LifecycleCommandCoordinator.unwrap(compensationFailure));
                                        }
                                        this.rollbackSpawn(fp, failure);
                                        throw new CompletionException(failure);
                                    });
                                })
                                .thenCompose(future -> future);
                    })
                    .whenComplete((ignored, throwable) -> {
                        try {
                            if (throwable != null && fakeplayerRef.get() == null) {
                                var sequence = sequenceRef.get();
                                if (sequence != null) {
                                    this.nameManager.unregister(sequence);
                                }
                            }
                        } finally {
                            reservation.reservation().close();
                        }
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
        try {
            this.nameManager.unregister(fakeplayer.getSequenceName());
            if (config.isDropInventoryOnQuiting()) {
                this.nms.createAction(
                        fakeplayer.getPlayer(),
                        ActionType.DROP_INVENTORY,
                        ActionSetting.once()
                ).tick();
            }
        } finally {
            // PlayerQuitEvent can be followed by a retired Folia entity scheduler on
            // any exception in name or inventory cleanup. Always release the network.
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

    /**
     * PlayerJoinEvent is emitted inside the NMS login routine, before the
     * region-aware teleport future has necessarily succeeded. Run external
     * post-spawn hooks only after the durable spawn transaction is ACTIVE.
     */
    private void runCommittedSpawnHooks(@NotNull Fakeplayer fakeplayer) {
        this.dispatchCommandsAsync(fakeplayer, config.getPostSpawnCommands())
                .exceptionally(throwable -> {
                    log.warning("Failed to run post-spawn commands for " + fakeplayer.getName() + ": " + throwable);
                    return null;
                });

        try {
            var task = Tasks.runDelayed(Main.getInstance(), fakeplayer.getPlayer(), () -> {
                this.afterSpawnTasks.remove(fakeplayer);
                var player = fakeplayer.getPlayer();
                if (!this.shuttingDown && player.isOnline() && this.getRecord(player) == fakeplayer) {
                    this.dispatchCommandsAsync(fakeplayer, config.getAfterSpawnCommands())
                            .thenRun(() -> {
                                // Quit can begin after the outer check but
                                // before this continuation is appended.
                                if (!this.quittingFakeplayers.contains(fakeplayer)
                                        && !this.shuttingDown) {
                                    // issueCommands performs its live-record
                                    // check on the entity thread on Folia.
                                    this.issueCommands(player, config.getSelfCommands());
                                }
                            })
                            .exceptionally(throwable -> {
                                log.warning("Failed to run after-spawn commands for "
                                        + fakeplayer.getName() + ": " + throwable);
                                return null;
                            });
                }
            }, 20);
            var previous = this.afterSpawnTasks.put(fakeplayer, task);
            if (previous != null) {
                previous.cancel();
            }
        } catch (Throwable schedulingFailure) {
            log.warning("Failed to schedule after-spawn commands for " + fakeplayer.getName()
                    + ": " + schedulingFailure);
        }
    }

    /** Start the durable exit finalizer while PlayerQuitEvent still owns the record. */
    public @NotNull CompletableFuture<Void> startQuitLifecycle(@NotNull Fakeplayer fakeplayer) {
        // Publish the state before cancelling the delayed hook. If that hook is
        // already running, dispatchCommandsAsync checks this state again while
        // atomically appending to the same per-instance command chain.
        this.quittingFakeplayers.add(fakeplayer);
        var task = this.afterSpawnTasks.remove(fakeplayer);
        if (task != null) {
            task.cancel();
        }
        var handle = this.lifecycleTransactions.get(fakeplayer);
        return this.commandChains.compute(fakeplayer, (key, tail) -> {
            var ready = tail == null
                    ? CompletableFuture.<Void>completedFuture(null)
                    : tail.handle((ignored, throwable) -> (Void) null);
            return handle == null
                    ? ready
                    : ready.thenCompose(ignored -> this.lifecycleCoordinator.runPostQuitAsync(handle));
        });
    }

    /** Retry post-quit if necessary, run after-quit, then commit journal deletion. */
    public @NotNull CompletableFuture<Void> finishQuitLifecycle(@NotNull Fakeplayer fakeplayer) {
        var handle = this.lifecycleTransactions.get(fakeplayer);
        if (handle == null) {
            this.commandChains.remove(fakeplayer);
            this.quittingFakeplayers.remove(fakeplayer);
            return CompletableFuture.completedFuture(null);
        }
        return this.lifecycleCoordinator.finishQuitAsync(handle)
                .whenComplete((ignored, throwable) -> {
                    if (throwable == null) {
                        this.lifecycleTransactions.remove(fakeplayer, handle);
                        this.commandChains.remove(fakeplayer);
                        this.quittingFakeplayers.remove(fakeplayer);
                    }
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
        if (this.shuttingDown) {
            return CompletableFuture.completedFuture(null);
        }
        var previous = this.commandChains.get(fakeplayer);
        if (commands.isEmpty()) {
            return previous == null ? CompletableFuture.completedFuture(null) : previous.handle((ignored, throwable) -> null);
        }

        var p = fakeplayer.getName();
        var u = fakeplayer.getUUID().toString();
        var c = fakeplayer.getCreator().getName();
        final List<String> formatted;
        try {
            formatted = Commands.formatCommands(List.copyOf(commands), "%p", p, "%u", u, "%c", c);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return this.commandChains.compute(fakeplayer, (key, tail) -> {
            var ready = tail == null
                    ? CompletableFuture.<Void>completedFuture(null)
                    : tail.handle((ignoredTail, throwable) -> (Void) null);
            if (this.quittingFakeplayers.contains(key)) {
                // No spawn hook may be appended behind the durable quit
                // finalizer. Returning the normalized existing tail also
                // contains an earlier hook failure for callers.
                return ready;
            }
            return ready.thenCompose(ignoredReady -> this.dispatchConsoleCommands(formatted, p));
        });
    }

    private @NotNull CompletableFuture<Void> dispatchConsoleCommands(@NotNull List<String> commands, @NotNull String playerName) {
        Runnable dispatch = () -> {
            if (this.shuttingDown) {
                throw new IllegalStateException("Fakeplayer is shutting down");
            }
            var server = Bukkit.getServer();
            var sender = Bukkit.getConsoleSender();
            IllegalStateException unhandled = null;
            for (var cmd : commands) {
                if (!server.dispatchCommand(sender, cmd)) {
                    log.warning("Failed to execute command for %s: %s".formatted(playerName, cmd));
                    if (unhandled == null) {
                        unhandled = new IllegalStateException("Command was not handled for %s: %s".formatted(playerName, cmd));
                    }
                } else {
                    log.info("Dispatched command: " + cmd);
                }
            }
            if (unhandled != null) {
                throw unhandled;
            }
        };
        // CompletableFuture continuations can arrive here on the plugin IO
        // executor even on Paper. Always route console dispatch through the
        // server's main/global scheduler.
        return Tasks.callGlobal(Main.getInstance(), () -> {
            dispatch.run();
            return null;
        });
    }

    /**
     * Reserve all configured spawn quotas before doing any asynchronous name,
     * database, or entity work. The address is read once and reused by the
     * fake-player record, so the IP check and the created record cannot drift.
     */
    private @NotNull CompletableFuture<SpawnContext> reserveSpawnAsync(@NotNull CommandSender creator) {
        var creatorName = creator.getName();
        var bypassLimits = creator.isOp();
        return AddressUtils.getAddressAsync(creator).thenApply(address -> {
            try {
                var reservation = this.spawnQuota.reserve(
                        creatorName,
                        address,
                        this.config.isDetectIp(),
                        this.config.getPlayerLimit(),
                        this.config.getServerLimit(),
                        this.playerList.getSize(),
                        this.playerList.countByCreator(creatorName),
                        this.countByAddress(address),
                        bypassLimits
                );
                return new SpawnContext(address, reservation);
            } catch (SpawnQuota.LimitExceededException failure) {
                throw new CompletionException(this.limitException(failure.limit()));
            }
        });
    }

    private @NotNull CommandException limitException(@NotNull SpawnQuota.Limit limit) {
        var key = switch (limit) {
            case SERVER -> "fakeplayer.command.spawn.error.server-limit";
            case PLAYER -> "fakeplayer.command.spawn.error.player-limit";
            case IP -> "fakeplayer.command.spawn.error.ip-limit";
        };
        return new CommandException(translatable(key));
    }

    /** Stop accepting work before the owned executors are interrupted. */
    public void beginShutdown() {
        if (this.shuttingDown) {
            return;
        }
        this.shuttingDown = true;
        this.lifecycleCoordinator.beginShutdown();
        Exceptions.suppress(Main.getInstance(), this.lagMonitor::cancel);
        this.afterSpawnTasks.values().forEach(Tasks.Task::cancel);
        this.afterSpawnTasks.clear();
    }

    /**
     * Finalize durable external state after async workers have stopped, then
     * release in-memory/native resources. Recovery runs synchronously because
     * schedulers cannot be relied upon once plugin disable has begun.
     */
    public void onDisable() {
        this.beginShutdown();
        if (this.shutdownFinalized) {
            return;
        }
        this.shutdownFinalized = true;
        try {
            this.lifecycleCoordinator.recoverPendingSynchronously();
        } catch (Throwable recoveryFailure) {
            // The journal is intentionally retained. A later enable refuses
            // unsafe startup until every idempotent finalizer succeeds.
            log.severe("Lifecycle finalization remains pending and will be retried on next startup: "
                    + recoveryFailure);
        }

        var reason = text(REMOVAL_REASON_PREFIX + "Plugin disabled");
        for (var fakeplayer : this.playerList.getAll()) {
            if (!this.playerList.remove(fakeplayer)) {
                continue;
            }
            try {
                this.nameManager.unregister(fakeplayer.getSequenceName());
            } catch (Throwable unregisterFailure) {
                log.warning("Failed to release fake-player name " + fakeplayer.getName() + ": " + unregisterFailure);
            }
            this.commandChains.remove(fakeplayer);
            try {
                var player = fakeplayer.getPlayer();
                // onDisable itself runs on the server lifecycle thread and no
                // new plugin task is guaranteed to execute. Attempt the native
                // disconnect immediately; the server's own stop path remains
                // the final owner if a Folia runtime rejects entity mutation.
                player.kick(reason);
            } catch (Throwable kickFailure) {
                log.warning("Failed to remove fake player " + fakeplayer.getName() + ": " + kickFailure);
            } finally {
                try {
                    // Kick first so a just-closed synthetic channel cannot
                    // make Player#kick a no-op. Close still releases all
                    // plugin-owned network/action state below.
                    fakeplayer.close();
                } catch (Throwable closeFailure) {
                    log.warning("Failed to close fake-player resources for " + fakeplayer.getName() + ": " + closeFailure);
                }
            }
        }
        this.lifecycleTransactions.clear();
        this.commandChains.clear();
        this.quittingFakeplayers.clear();
        this.spawnQuota.clear();
    }

    private void rollbackSpawn(@NotNull Fakeplayer fakeplayer, @NotNull Throwable throwable) {
        this.playerList.remove(fakeplayer);
        this.lifecycleTransactions.remove(fakeplayer);
        this.commandChains.remove(fakeplayer);
        this.quittingFakeplayers.remove(fakeplayer);
        try {
            this.nameManager.unregister(fakeplayer.getSequenceName());
        } catch (Throwable unregisterFailure) {
            log.warning("Failed to release fake-player name " + fakeplayer.getName() + ": " + unregisterFailure);
        }

        var player = fakeplayer.getPlayer();
        var reason = text("[fakeplayer] spawn failed");
        try {
            // The failure continuation can be running on an IO executor. Use
            // the entity/main scheduler on every platform, kick before
            // closing the synthetic channel, and handle a retired Folia
            // scheduler by releasing plugin-owned state in the completion.
            Tasks.call(Main.getInstance(), player, () -> {
                try {
                    // Kick even when the login pipeline failed halfway
                    // through and Bukkit has not published isOnline() yet;
                    // CraftPlayer#kick is also the cleanup path for a
                    // partially inserted PlayerList entry.
                    player.kick(reason);
                } finally {
                    fakeplayer.close();
                }
                return null;
            }).exceptionally(cleanupFailure -> {
                log.warning("Failed to remove failed fake player " + fakeplayer.getName() + ": "
                        + LifecycleCommandCoordinator.unwrap(cleanupFailure));
                fakeplayer.close();
                return null;
            });
        } catch (Throwable kickFailure) {
            log.warning("Failed to remove failed fake player " + fakeplayer.getName() + ": " + kickFailure);
            fakeplayer.close();
        }
        log.warning("Failed to spawn fake player " + fakeplayer.getName() + ": " + throwable);
    }

    private record SpawnContext(
            @NotNull String address,
            @NotNull SpawnQuota.Reservation reservation
    ) {
    }

}
