package io.github.hello09x.fakeplayer.api.spi;

import org.bukkit.Server;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public interface NMSNetwork {

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
     * 获取服务侧游戏数据包监听器
     * <p>在获取之前需要先执行了 {@link #placeNewPlayer(Server, Player)} 才会初始化值</p>
     */
    @NotNull
    NMSServerGamePacketListener getServerGamePacketListener() throws IllegalStateException;

}
