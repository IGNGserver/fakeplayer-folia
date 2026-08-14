package io.github.hello09x.fakeplayer.v26_1_2.spi;

import io.github.hello09x.fakeplayer.api.spi.NMSEntity;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

final class NMSEntityImpl implements NMSEntity {

    private final Object handle;

    NMSEntityImpl(@NotNull Entity entity) {
        this.handle = NmsAccess.handle(entity);
    }

    Object handle() {
        return handle;
    }
}
