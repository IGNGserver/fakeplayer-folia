package io.github.hello09x.fakeplayer.core.command.impl;

import com.google.inject.Singleton;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import dev.jorel.commandapi.executors.CommandArguments;
import io.github.hello09x.devtools.core.utils.EntityUtils;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

@Singleton
public class TeleportCommand extends AbstractCommand {

    /**
     * 传送到假人
     */
    public void tp(@NotNull Player sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);
        if (Tasks.isFolia()) {
            this.teleportFolia(sender, sender, fake);
        } else {
            this.teleport(sender, sender, fake);
        }
    }

    /**
     * 将假人传送过来
     */
    public void tphere(@NotNull Player sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);
        if (Tasks.isFolia()) {
            this.teleportFolia(sender, fake, sender);
        } else {
            this.teleport(sender, fake, sender);
        }
    }

    /**
     * 与假人交换位置
     */
    public void tps(@NotNull Player sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);

        if (Tasks.isFolia()) {
            var senderLocation = Tasks.call(Main.getInstance(), sender, () -> sender.getLocation().clone());
            var fakeLocation = Tasks.call(Main.getInstance(), fake, () -> fake.getLocation().clone());
            senderLocation.thenCombine(fakeLocation, (l1, l2) -> fake.teleportAsync(l1)
                    .thenCombine(sender.teleportAsync(l2), (fakeSuccess, senderSuccess) -> fakeSuccess && senderSuccess))
            .thenCompose(future -> future).thenAccept(success -> {
                if (!success) {
                    sendCanceled(sender);
                }
            }).exceptionally(error -> {
                sendCanceled(sender);
                return null;
            });
            return;
        }

        var l1 = sender.getLocation();
        var l2 = fake.getLocation();

        EntityUtils.teleportAndSound(fake, l1);
        EntityUtils.teleportAndSound(sender, l2);
    }

    private void teleport(@NotNull CommandSender sender, @NotNull Player from, @NotNull Player to) {
        if (!EntityUtils.teleportAndSound(from, to.getLocation())) {
            sender.sendMessage(translatable("fakeplayer.command.teleport.error.canceled", RED));
        }
    }

    private void teleportFolia(@NotNull Player sender, @NotNull Player from, @NotNull Player to) {
        Tasks.call(Main.getInstance(), to, () -> to.getLocation().clone())
                .thenCompose(from::teleportAsync)
                .thenAccept(success -> {
                    if (!success) {
                        sendCanceled(sender);
                    }
                })
                .exceptionally(error -> {
                    sendCanceled(sender);
                    return null;
                });
    }

    private void sendCanceled(@NotNull Player sender) {
        Tasks.run(Main.getInstance(), sender, () -> sender.sendMessage(
                translatable("fakeplayer.command.teleport.error.canceled", RED)
        ));
    }

}
