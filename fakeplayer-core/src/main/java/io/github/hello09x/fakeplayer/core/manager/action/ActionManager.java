package io.github.hello09x.fakeplayer.core.manager.action;

import com.google.common.base.Throwables;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.api.spi.ActionSetting;
import io.github.hello09x.fakeplayer.api.spi.ActionTicker;
import io.github.hello09x.fakeplayer.api.spi.ActionType;
import io.github.hello09x.fakeplayer.api.spi.NMSBridge;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Singleton
public class ActionManager {

    private final static Logger log = Main.getInstance().getLogger();

    /**
     * 每个 fake player 一个 timer, 在 Folia 上由 EntityScheduler 调度到该实体所属区域线程
     * <p>value 为 null 表示该 player 的 timer 已被取消</p>
     */
    private final Map<UUID, Tasks.Task> timers = new ConcurrentHashMap<>();

    private final Map<UUID, Map<ActionType, ActionTicker>> managers = new ConcurrentHashMap<>();

    /** Player handles are published when the action is installed on its region. */
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<String, Long> errorLogAt = new ConcurrentHashMap<>();
    private final Tasks.Task reapTask;
    private volatile boolean shuttingDown;

    private static final long ACTION_ERROR_LOG_INTERVAL_MILLIS = 60_000L;

    private final NMSBridge bridge;


    @Inject
    public ActionManager(NMSBridge bridge) {
        this.bridge = bridge;
        // 低频清理已下线/失效的 fake player, 避免 EntityScheduler 回收后条目残留
        this.reapTask = Tasks.runAtFixedRateGlobal(Main.getInstance(), this::reap, 0, 100);
    }

    public boolean hasActiveAction(
            @NotNull Player player,
            @NotNull ActionType action
    ) {
        return Optional.ofNullable(this.managers.get(player.getUniqueId()))
                       .map(manager -> manager.get(action))
                       .filter(ac -> ac.getSetting().remains > 0)
                       .isPresent();
    }

    public @NotNull @Unmodifiable Set<ActionType> getActiveActions(@NotNull Player player) {
        var manager = this.managers.get(player.getUniqueId());
        if (manager == null || manager.isEmpty()) {
            return Collections.emptySet();
        }

        return manager.entrySet()
                      .stream()
                      .filter(actions -> actions.getValue().getSetting().remains > 0)
                      .map(Map.Entry::getKey)
                      .collect(Collectors.toSet());
    }

    public void setAction(
            @NotNull Player player,
            @NotNull ActionType action,
            @NotNull ActionSetting setting
    ) {
        if (this.shuttingDown) {
            return;
        }
        if (Tasks.isFolia()) {
            Tasks.run(Main.getInstance(), player, () -> setActionOnEntity(player, action, setting));
            return;
        }
        setActionOnEntity(player, action, setting);
    }

    private void setActionOnEntity(
            @NotNull Player player,
            @NotNull ActionType action,
            @NotNull ActionSetting setting
    ) {
        if (this.shuttingDown) {
            return;
        }
        var uuid = player.getUniqueId();
        if (setting.equals(ActionSetting.stop())) {
            var manager = this.managers.get(uuid);
            if (manager == null) {
                return;
            }
            var previous = manager.remove(action);
            if (previous != null) {
                this.stopTicker(action, previous);
            }
            if (manager.isEmpty()) {
                this.softCleanup(uuid);
            }
            return;
        }

        // Construct the replacement before publishing it. If construction
        // fails, do not publish an empty manager/player entry. Install the
        // entity timer before swapping state as well, so a retired scheduler
        // cannot leave a live action with no ticker.
        var replacement = bridge.createAction(player, action, setting);
        if (this.shuttingDown) {
            this.stopTicker(action, replacement);
            return;
        }
        var manager = this.managers.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>());
        this.players.put(uuid, player);
        try {
            this.timers.computeIfAbsent(uuid, key -> Tasks.runAtFixedRate(
                    Main.getInstance(), player, () -> this.tickPlayer(uuid), 0, 1)
            );
        } catch (Throwable schedulingFailure) {
            this.stopTicker(action, replacement);
            this.softCleanup(uuid);
            throw schedulingFailure;
        }
        var previous = manager.put(action, replacement);
        if (previous != null) {
            this.stopTicker(action, previous);
        }
    }

    public void stop(@NotNull Player player) {
        if (Tasks.isFolia()) {
            Tasks.run(Main.getInstance(), player, () -> stopOnEntity(player));
            return;
        }
        stopOnEntity(player);
    }

    /** Remove all action state and ticker handles for a retired fake player. */
    public void cleanup(@NotNull Player player) {
        if (this.shuttingDown) {
            return;
        }
        if (Tasks.isFolia()) {
            Tasks.run(Main.getInstance(), player, () -> cleanupOnEntity(player.getUniqueId()));
            return;
        }
        cleanupOnEntity(player.getUniqueId());
    }

    private void stopOnEntity(@NotNull Player player) {
        if (this.shuttingDown) {
            return;
        }
        var manager = this.managers.get(player.getUniqueId());
        if (manager == null) {
            return;
        }
        if (manager.isEmpty()) {
            this.softCleanup(player.getUniqueId());
            return;
        }

        for (var entry : new ArrayList<>(manager.entrySet())) {
            if (manager.remove(entry.getKey(), entry.getValue())) {
                this.stopTicker(entry.getKey(), entry.getValue());
            }
        }
        this.softCleanup(player.getUniqueId());
    }

    private void cleanupOnEntity(@NotNull UUID uuid) {
        if (this.shuttingDown) {
            this.removeState(uuid);
            return;
        }
        var manager = this.managers.get(uuid);
        if (manager != null) {
            this.hardCleanup(uuid, manager);
        } else {
            this.removeState(uuid);
        }
    }

    /**
     * 为指定 fake player 时刻计算, 由该实体所属的区域线程触发
     */
    private void tickPlayer(@NotNull UUID uuid) {
        if (this.shuttingDown) {
            return;
        }
        var manager = this.managers.get(uuid);
        if (manager == null || manager.isEmpty()) {
            this.softCleanup(uuid);
            return;
        }

        // This method is invoked by the entity scheduler. Looking the player up
        // through Bukkit here would read global server state from a region
        // thread; use the handle captured when the action was installed.
        var player = this.players.get(uuid);
        if (player == null || !player.isValid()) {
            // 假人下线或者死亡
            this.hardCleanup(uuid, manager);
            return;
        }

        var itr = manager.entrySet().iterator();
        while (itr.hasNext()) {
            if (this.shuttingDown) {
                return;
            }
            var entry = itr.next();
            var action = entry.getKey();
            var ticker = entry.getValue();
            try {
                if (ticker.tick()) {
                    manager.remove(action, ticker);
                }
            } catch (Throwable e) {
                // Isolate a broken action immediately. Keeping it in the map
                // turns a version/plugin error into an unbounded per-tick
                // retry and log flood, and can leave state such as an
                // in-progress block break behind.
                if (manager.remove(action, ticker)) {
                    this.stopTicker(action, ticker);
                }
                this.logActionFailure(action, e);
            }
        }
        if (manager.isEmpty()) {
            this.softCleanup(uuid);
        }
    }

    /**
     * 清理已下线/失效的条目, 由全局区域线程周期触发
     */
    private void reap() {
        if (this.shuttingDown) {
            return;
        }
        for (var uuid : this.managers.keySet()) {
            var player = Bukkit.getPlayer(uuid);
            var manager = this.managers.get(uuid);
            if (manager == null) {
                continue;
            }
            if (player == null) {
                this.removeState(uuid);
                continue;
            }

            // A global-region callback must not invoke NMS action methods
            // directly. Let the entity scheduler perform the final check and
            // stop on the owning region.
            Tasks.run(Main.getInstance(), player, () -> {
                if (!player.isValid()) {
                    this.hardCleanup(uuid, manager);
                }
            });
        }
    }

    /**
     * 软清理: 仅当该玩家此刻动作表为空时才丢弃, 避免与并发的 setAction 竞争丢动作
     */
    private void softCleanup(@NotNull UUID uuid) {
        // 仅当同名动作表仍为空时才移除映射; 若此刻有新动作被放入则保留它和对应 timer
        this.managers.computeIfPresent(uuid, (k, v) -> v.isEmpty() ? null : v);
        if (!this.managers.containsKey(uuid)) {
            var timer = this.timers.remove(uuid);
            if (timer != null) {
                timer.cancel();
            }
            this.players.remove(uuid);
        }
    }

    /**
     * 硬清理: 玩家已下线/失效, 停止所有动作并强制丢弃
     */
    private void hardCleanup(@NotNull UUID uuid, @NotNull Map<ActionType, ActionTicker> manager) {
        if (this.shuttingDown) {
            this.removeState(uuid);
            return;
        }
        for (var ticker : manager.values()) {
            try {
                ticker.stop();
            } catch (Throwable e) {
                log.warning(Throwables.getStackTraceAsString(e));
            }
        }
        this.managers.remove(uuid);
        this.players.remove(uuid);
        var timer = this.timers.remove(uuid);
        if (timer != null) {
            timer.cancel();
        }
    }

    private void removeState(@NotNull UUID uuid) {
        var manager = this.managers.remove(uuid);
        if (manager != null) {
            // This path is used only when the entity scheduler is already
            // retired (or during plugin shutdown). ActionTicker.stop() may
            // call version-specific NMS mutators, so it must not be invoked
            // from the global reaper or Folia's plugin-disable thread.
            manager.clear();
        }
        this.players.remove(uuid);
        var timer = this.timers.remove(uuid);
        if (timer != null) {
            timer.cancel();
        }
    }

    private void stopTicker(@NotNull ActionType action, @NotNull ActionTicker ticker) {
        try {
            ticker.stop();
        } catch (Throwable failure) {
            this.logActionFailure(action, failure);
        }
    }

    private void logActionFailure(@NotNull ActionType action, @NotNull Throwable failure) {
        var key = action.name() + ":" + failure.getClass().getName();
        var now = System.currentTimeMillis();
        this.errorLogAt.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous >= ACTION_ERROR_LOG_INTERVAL_MILLIS) {
                var message = Objects.toString(failure.getMessage(), "").replace('\n', ' ').replace('\r', ' ');
                if (message.length() > 200) {
                    message = message.substring(0, 200);
                }
                log.log(Level.WARNING, "Isolated failed fake-player action " + action +
                        " (" + failure.getClass().getSimpleName() +
                        (message.isBlank() ? "" : ": " + message) + ")", failure);
                return now;
            }
            return previous;
        });
    }

    /** Cancel the global reaper and release every remaining action state. */
    public void onDisable() {
        this.shuttingDown = true;
        this.reapTask.cancel();
        for (var uuid : new ArrayList<>(this.managers.keySet())) {
            this.removeState(uuid);
        }
        for (var timer : this.timers.values()) {
            timer.cancel();
        }
        this.timers.clear();
        this.players.clear();
        this.errorLogAt.clear();
    }

}
