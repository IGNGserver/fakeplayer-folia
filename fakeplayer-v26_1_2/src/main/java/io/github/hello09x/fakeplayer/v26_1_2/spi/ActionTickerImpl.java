package io.github.hello09x.fakeplayer.v26_1_2.spi;

import io.github.hello09x.fakeplayer.api.spi.ActionSetting;
import io.github.hello09x.fakeplayer.api.spi.ActionTicker;
import io.github.hello09x.fakeplayer.api.spi.ActionType;
import io.github.hello09x.fakeplayer.api.spi.NMSBridge;
import io.github.hello09x.fakeplayer.core.entity.action.BaseActionTicker;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Action factory for the Mojang-named 26.x runtime. */
final class ActionTickerImpl extends BaseActionTicker implements ActionTicker {

    ActionTickerImpl(
            @NotNull NMSBridge bridge,
            @NotNull Player player,
            @NotNull ActionType action,
            @NotNull ActionSetting setting
    ) {
        super(bridge, player, action, setting);
        if (this.action == null) {
            this.action = new ReflectiveAction(player, action);
        }
    }
}
