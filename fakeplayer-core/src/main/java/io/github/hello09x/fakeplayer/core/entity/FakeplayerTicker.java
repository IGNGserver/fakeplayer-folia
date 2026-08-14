package io.github.hello09x.fakeplayer.core.entity;

import io.github.hello09x.fakeplayer.api.spi.NMSServerPlayer;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerManager;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Per-tick driver for a fake player.
 *
 * <p>Originally a {@link org.bukkit.scheduler.BukkitRunnable}, the ticker now
 * schedules itself through {@link Tasks} so it runs on the region that owns the
 * fake player under Folia (and remains on the main thread under Paper).</p>
 */
public class FakeplayerTicker {

    public final static long NON_REMOVE_AT = -1;

    @NotNull
    private final Fakeplayer player;

    /**
     * 移除时间
     * <p>如果不需要定时移除则为 0</p>
     */
    private final long removeAt;

    /**
     * 是否是第一次 tick
     */
    private boolean firstTick;

    /**
     * 调度任务句柄
     */
    private Tasks.Task task;

    public FakeplayerTicker(
            @NotNull Fakeplayer player,
            long lifespan
    ) {
        this.player = player;
        this.removeAt = lifespan > 0 ? System.currentTimeMillis() + lifespan : NON_REMOVE_AT;
        this.firstTick = true;
    }

    /**
     * 开始按 tick 调度
     */
    public void start(@NotNull Plugin plugin) {
        if (this.task != null) {
            return;
        }
        this.task = Tasks.runAtFixedRate(plugin, this.player.getPlayer(), this::tick, 0, 1);
    }

    /**
     * 停止调度
     */
    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void tick() {
        if (!player.isOnline()) {
            this.stop();
            return;
        }

        // Publish a safe snapshot for global-region commands and integrations.
        player.updateLocationSnapshot();

        if (this.removeAt != NON_REMOVE_AT && this.player.getTickCount() % 20 == 0 && System.currentTimeMillis() > removeAt) {
            Main.getInjector().getInstance(FakeplayerManager.class).remove(player.getName(), "lifespan ends");
            this.stop();
            return;
        }

        // 真实的玩家是通过 ServerGamePacketListenerImpl#tick() 进行时刻运算的
        // 这个方法会修复第一次 tick 坐标错误的问题
        // 但是这个方法会导致强制修正坐标为客户端坐标, 然而假人的连接并不会发送任何坐标
        // 因此这里自行修复第一次 tick 的坐标, 并直接调用 ServerPlayer#doTick() 来进行时刻运算
        if (this.firstTick) {
            this.doFirstTick();
        } else {
            this.doTick();
        }
    }

    /**
     * 处理第一次 tick
     * <p>在这里在 {@link NMSServerPlayer#doTick()} 之后, 强行设置一次坐标解决被其他插件干预导致随机传送</p>
     * <p>似乎是 clearfog 或者 multiverse 插件导致的</p>
     */
    private void doFirstTick() {
        var handle = this.player.getHandle();
        var player = this.player.getPlayer();
        var x = handle.getX();
        var y = handle.getY();
        var z = handle.getZ();

        // 将本 tick 的移动取消
        handle.setXo(x);
        handle.setYo(y);
        handle.setZo(z);

        handle.doTick();

        // clearFog 插件会在第一次传送的时候改变了玩家的位置, 因此必须进行一次传送.
        // Folia requires the asynchronous entity teleport API for this operation.
        var location = new Location(player.getWorld(), x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());
        if (Tasks.isFolia()) {
            player.teleportAsync(location).thenAccept(success -> {
                if (success) {
                    Tasks.run(Main.getInstance(), player, () -> handle.absMoveTo(x, y, z, location.getYaw(), location.getPitch()));
                }
            });
        } else {
            player.teleport(location);
            handle.absMoveTo(x, y, z, location.getYaw(), location.getPitch());
        }
        this.firstTick = false;
    }

    private void doTick() {
        var handle = this.player.getHandle();
        handle.doTick();
    }

}
