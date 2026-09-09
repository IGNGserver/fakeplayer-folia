package io.github.hello09x.fakeplayer.api.spi;

import org.bukkit.Server;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public interface NMSNetwork {

    /**
     * State of the native placement rollback transaction.
     *
     * <p>{@link #NOT_STARTED} is also the compatibility state for version
     * adapters that predate placement rollback. Once a rollback starts it must
     * remain observable as {@link #CLEAN} or {@link #RESIDUAL}; clearing native
     * references must not erase the result.</p>
     */
    enum PlacementRollbackState {
        NOT_STARTED,
        IN_PROGRESS,
        CLEAN,
        RESIDUAL
    }

    /**
     * 绑定一个虚拟的游戏连接
     *
     * @param server 服务器
     * @param player 假人玩家
     */
    @NotNull NMSServerGamePacketListener placeNewPlayer(@NotNull Server server, @NotNull Player player);

    /**
     * Bind a fake player at the requested world before the server's login
     * pipeline adds it to the player list. Older version modules keep their
     * existing behaviour through the default implementation.
     */
    default @NotNull NMSServerGamePacketListener placeNewPlayer(
            @NotNull Server server,
            @NotNull Player player,
            @NotNull Location spawnAt
    ) {
        return placeNewPlayer(server, player);
    }

    /**
     * Close the in-memory connection and release any packet forwarding state.
     * Version adapters that do not allocate a synthetic connection can keep
     * the default no-op implementation.
     */
    default void close() {
    }

    /**
     * Returns the sticky result of the native placement rollback transaction.
     * Older adapters can retain their boolean overrides and are interpreted by
     * this compatibility default.
     */
    default @NotNull PlacementRollbackState getPlacementRollbackState() {
        return PlacementRollbackState.NOT_STARTED;
    }

    /**
     * Returns whether the last native placement rollback could not prove that
     * all server-side state was removed. Core cleanup uses this as a
     * quarantine signal instead of treating the spawn as an ordinary rollback.
     */
    default boolean hasPlacementRollbackResidual() {
        return this.getPlacementRollbackState() == PlacementRollbackState.RESIDUAL;
    }

    /**
     * Returns whether native placement rollback currently owns cleanup. A
     * concurrent PlayerQuitEvent must not close the same connection or return
     * its name before that rollback has reached a final result.
     */
    default boolean isPlacementRollbackInProgress() {
        return this.getPlacementRollbackState() == PlacementRollbackState.IN_PROGRESS;
    }

    /**
     * 获取服务侧游戏数据包监听器
     * <p>在获取之前需要先执行了 {@link #placeNewPlayer(Server, Player)} 才会初始化值</p>
     */
    @NotNull
    NMSServerGamePacketListener getServerGamePacketListener() throws IllegalStateException;

}
