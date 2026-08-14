package io.github.hello09x.fakeplayer.api.spi;

import org.jetbrains.annotations.NotNull;
import org.bukkit.World;

import java.util.UUID;

public interface NMSServer {

    /**
     * 创建一名新的玩家加入游戏
     *
     * @param uuid UUID
     * @param name 名称
     * @return 假人
     */
    @NotNull NMSServerPlayer newPlayer(@NotNull UUID uuid, @NotNull String name);

    /**
     * Creates a player with its initial server level selected explicitly. The
     * default keeps older version modules source and binary compatible.
     */
    default @NotNull NMSServerPlayer newPlayer(
            @NotNull UUID uuid,
            @NotNull String name,
            @NotNull World world
    ) {
        return newPlayer(uuid, name);
    }


}
