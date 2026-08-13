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

    private final NMSBridge bridge;


    @Inject
    public ActionManager(NMSBridge bridge) {
        this.bridge = bridge;
        // 低频清理已下线/失效的 fake player, 避免 EntityScheduler 回收后条目残留
        Tasks.runAtFixedRateGlobal(Main.getInstance(), this::reap, 0, 100);
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
        var manager = this.managers.computeIfAbsent(player.getUniqueId(), key -> new ConcurrentHashMap<>());
        manager.put(action, bridge.createAction(player, action, setting));
        this.timers.computeIfAbsent(player.getUniqueId(), key -> Tasks.runAtFixedRate(
                Main.getInstance(), player, () -> this.tickPlayer(player.getUniqueId()), 0, 1)
        );
    }

    public void stop(@NotNull Player player) {
        var manager = this.managers.get(player.getUniqueId());
        if (manager == null || manager.isEmpty()) {
            return;
        }

        for (var entry : manager.entrySet()) {
            if (!entry.getValue().getSetting().equals(ActionSetting.stop())) {
                entry.setValue(bridge.createAction(player, entry.getKey(), ActionSetting.stop()));
            }
        }
    }

    /**
     * 为指定 fake player 时刻计算, 由该实体所属的区域线程触发
     */
    private void tickPlayer(@NotNull UUID uuid) {
        var manager = this.managers.get(uuid);
        if (manager == null || manager.isEmpty()) {
            this.softCleanup(uuid);
            return;
        }

        var player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isValid()) {
            // 假人下线或者死亡
            this.hardCleanup(uuid, manager);
            return;
        }

        var itr = manager.entrySet().iterator();
        while (itr.hasNext()) {
            var entry = itr.next();
            try {
                if (entry.getValue().tick()) {
                    itr.remove();
                }
            } catch (Throwable e) {
                log.warning(Throwables.getStackTraceAsString(e));
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
        for (var uuid : this.managers.keySet()) {
            var player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isValid()) {
                var manager = this.managers.get(uuid);
                if (manager != null) {
                    this.hardCleanup(uuid, manager);
                }
            }
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
        }
    }

    /**
     * 硬清理: 玩家已下线/失效, 停止所有动作并强制丢弃
     */
    private void hardCleanup(@NotNull UUID uuid, @NotNull Map<ActionType, ActionTicker> manager) {
        for (var ticker : manager.values()) {
            try {
                ticker.stop();
            } catch (Throwable e) {
                log.warning(Throwables.getStackTraceAsString(e));
            }
        }
        this.managers.remove(uuid);
        var timer = this.timers.remove(uuid);
        if (timer != null) {
            timer.cancel();
        }
    }

}