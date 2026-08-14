package io.github.hello09x.fakeplayer.core.command.impl;

import com.google.inject.Singleton;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import dev.jorel.commandapi.executors.CommandArguments;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

@Singleton
public class SneakCommand extends AbstractCommand {

    /**
     * 设置潜行
     */
    public void sneak(@NotNull CommandSender sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);
        var requested = args
                .getOptional("sneaking")
                .map(String.class::cast)
                .map(Boolean::valueOf)
                .orElse(null);
        runOnFake(fake, () -> fake.setSneaking(requested == null ? !fake.isSneaking() : requested));
    }

}
