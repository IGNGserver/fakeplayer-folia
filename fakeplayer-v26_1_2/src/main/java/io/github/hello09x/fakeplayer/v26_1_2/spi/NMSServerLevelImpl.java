package io.github.hello09x.fakeplayer.v26_1_2.spi;

import io.github.hello09x.fakeplayer.api.spi.NMSServerLevel;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

final class NMSServerLevelImpl implements NMSServerLevel {

    private final Object handle;

    NMSServerLevelImpl(@NotNull World world) {
        this.handle = NmsAccess.handle(world);
    }

    Object handle() {
        return handle;
    }
}
