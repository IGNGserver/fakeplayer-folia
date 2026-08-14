package io.github.hello09x.fakeplayer.core.util;

import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * @author tanyaofei
 * @since 2024/7/29
 **/
public class AddressUtils {

    /**
     * 获取 IP 地址
     * <p>如果不是玩家则返回 127.0.0.1, 如果该玩家已离线则返回 0.0.0.0 </p>
     *
     * @param sender 玩家
     * @return IP 地址
     */
    public static @NotNull String getAddress(@NotNull CommandSender sender) {
        if (sender instanceof Player p) {
            return Optional.ofNullable(p.getAddress()).map(InetSocketAddress::getHostString).orElse("0.0.0.0");
        } else {
            return "127.0.0.1";
        }
    }

    /**
     * Read a player's connection address from its owning Folia region. The
     * synchronous method remains available for Paper and legacy integrations.
     */
    public static @NotNull CompletableFuture<String> getAddressAsync(@NotNull CommandSender sender) {
        if (Tasks.isFolia() && sender instanceof Player player) {
            return Tasks.call(Main.getInstance(), player, () -> getAddress(player));
        }
        return CompletableFuture.completedFuture(getAddress(sender));
    }
}
