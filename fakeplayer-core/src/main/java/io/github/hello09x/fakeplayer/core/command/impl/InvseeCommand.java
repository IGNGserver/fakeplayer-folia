package io.github.hello09x.fakeplayer.core.command.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import dev.jorel.commandapi.executors.CommandArguments;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.manager.invsee.InvseeManager;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import static net.kyori.adventure.text.Component.translatable;

@Singleton
public class InvseeCommand extends AbstractCommand {

    private final InvseeManager invseeManager;

    @Inject
    public InvseeCommand(InvseeManager invseeManager) {
        this.invseeManager = invseeManager;
    }

    /**
     * 查看背包
     */
    public void invsee(@NotNull Player sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = super.getFakeplayer(sender, args);
        var senderWorld = sender.getWorld();
        callOnFake(fake, fake::getWorld).thenAccept(fakeWorld -> {
            if (!Objects.equals(senderWorld, fakeWorld)) {
                sendTo(sender, translatable("fakeplayer.command.invsee.error.not-the-same-world"));
                return;
            }
            if (Tasks.isFolia()) {
                Tasks.run(Main.getInstance(), sender, () -> invseeManager.invsee(sender, fake));
            } else {
                invseeManager.invsee(sender, fake);
            }
        });
    }

}
