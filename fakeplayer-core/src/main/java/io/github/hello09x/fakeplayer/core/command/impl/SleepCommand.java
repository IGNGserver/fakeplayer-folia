package io.github.hello09x.fakeplayer.core.command.impl;

import com.google.inject.Singleton;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import dev.jorel.commandapi.executors.CommandArguments;
import io.github.hello09x.devtools.core.utils.BlockUtils;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

@Singleton
public class SleepCommand extends AbstractCommand {

    /**
     * 睡觉
     */
    public void sleep(@NotNull CommandSender sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);
        runOnFake(fake, () -> {
            var bed = BlockUtils.getNearbyBlock(fake.getLocation(), 4, block -> {
                if (!(block.getBlockData() instanceof Bed data)) {
                    return false;
                }

                return !data.isOccupied() && data.getPart() == Bed.Part.HEAD;
            });
            if (bed != null) {
                fake.sleep(bed.getLocation(), false);
            }
        });
    }

    /**
     * 起床
     */
    public void wakeup(@NotNull CommandSender sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var target = getFakeplayer(sender, args);
        runOnFake(target, () -> {
            if (target.isSleeping()) {
                target.wakeup(true);
            }
        });
    }
}
