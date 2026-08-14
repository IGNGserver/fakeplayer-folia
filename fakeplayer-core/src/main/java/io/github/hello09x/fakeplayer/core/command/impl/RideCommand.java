package io.github.hello09x.fakeplayer.core.command.impl;

import com.google.inject.Singleton;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import dev.jorel.commandapi.executors.CommandArguments;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.jetbrains.annotations.NotNull;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static net.kyori.adventure.text.format.NamedTextColor.WHITE;

@Singleton
public class RideCommand extends AbstractCommand {

    /**
     * 骑最近的实体
     */
    public void rideAnything(@NotNull CommandSender sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);
        onFake(fake, () -> {
            var entities = fake.getNearbyEntities(4.5, 4.5, 4.5);
            if (entities.isEmpty()) {
                return;
            }
            var entity = entities.stream().filter(e -> e != fake).findAny().orElse(null);
            if (entity != null) {
                startRiding(sender, fake, entity);
            }
        });
    }

    /**
     * 骑目标实体
     */
    public void rideTarget(@NotNull CommandSender sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);
        onFake(fake, () -> {
            var entity = fake.getTargetEntity(5);
            if (entity != null) {
                startRiding(sender, fake, entity);
            }
        });
    }

    public void rideEntity(@NotNull CommandSender sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);
        var entity = (Entity) args.get("entity");
        if (entity == null) {
            return;
        }

        if (entity == fake) {
            send(sender, translatable("fakeplayer.command.ride.entity.error.ride-self").color(RED));
            return;
        }

        if (Tasks.isFolia()) {
            Tasks.call(Main.getInstance(), entity, () -> {
                if (entity.isDead()) {
                    return null;
                }
                var location = entity.getLocation();
                return new EntitySnapshot(location.getWorld(), location.getX(), location.getY(), location.getZ());
            }).thenAccept(entitySnapshot -> onFake(fake, () -> rideEntity(sender, fake, entity, entitySnapshot)));
            return;
        }

        onFake(fake, () -> {
            if (entity.isDead()) {
                return;
            }
            var entityLocation = entity.getLocation();
            if (entityLocation.getWorld() != fake.getWorld()
                    || entityLocation.distance(fake.getLocation()) > 24) {
                send(sender, translatable("fakeplayer.command.ride.entity.error.too-far", text(fake.getName(), WHITE)).color(RED));
                return;
            }

            startRiding(sender, fake, entity);
        });
    }

    private void rideEntity(
            @NotNull CommandSender sender,
            @NotNull Player fake,
            @NotNull Entity entity,
            EntitySnapshot entitySnapshot
    ) {
        if (entitySnapshot == null || entitySnapshot.world() != fake.getWorld()
                || entitySnapshot.distanceSquared(fake.getLocation()) > 24 * 24) {
            send(sender, translatable("fakeplayer.command.ride.entity.error.too-far", text(fake.getName(), WHITE)).color(RED));
            return;
        }

        startRiding(sender, fake, entity);
    }

    /**
     * 骑正常可以骑的附近实体
     */
    public void rideVehicle(@NotNull CommandSender sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);
        onFake(fake, () -> {
            var entity = fake.getNearbyEntities(4.5, 4.5, 4.5)
                             .stream()
                             .filter(e -> e instanceof RideableMinecart || e instanceof Boat || e instanceof AbstractHorse)
                             .findFirst()
                             .orElse(null);

            if (entity != null) {
                startRiding(sender, fake, entity);
            }
        });
    }

    /**
     * 骑创建者
     */
    public void rideMe(@NotNull Player sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);
        if (Tasks.isFolia()) {
            Tasks.call(Main.getInstance(), sender, () -> sender.getLocation().clone()).thenAccept(senderLocation ->
                    onFake(fake, () -> {
                        if (!fake.getWorld().equals(senderLocation.getWorld())
                                || fake.getLocation().distance(senderLocation) > 20) {
                            send(sender, translatable(
                                    "fakeplayer.command.ride.me.error.too-far",
                                    text(fake.getName(), WHITE)).color(RED)
                            );
                            return;
                        }

                        startRiding(sender, fake, sender);
                    })
            );
            return;
        }

        onFake(fake, () -> {
            if (!fake.getWorld().equals(sender.getWorld()) || fake.getLocation().distance(sender.getLocation()) > 20) {
                send(sender, translatable(
                        "fakeplayer.command.ride.me.error.too-far",
                        text(fake.getName(), WHITE)).color(RED)
                );
                return;
            }

            startRiding(sender, fake, sender);
        });
    }

    /**
     * 停止骑行
     */
    public void stopRiding(@NotNull CommandSender sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var fake = getFakeplayer(sender, args);
        onFake(fake, () -> bridge.fromPlayer(fake).stopRiding());
    }

    private void onFake(@NotNull Player fake, @NotNull Runnable action) {
        if (Tasks.isFolia()) {
            Tasks.run(Main.getInstance(), fake, action);
        } else {
            action.run();
        }
    }

    private void startRiding(@NotNull CommandSender sender, @NotNull Player fake, @NotNull Entity target) {
        if (Tasks.isFolia() && !Bukkit.isOwnedByCurrentRegion(target)) {
            send(sender, translatable("fakeplayer.command.ride.error.cross-region").color(RED));
            return;
        }

        try {
            bridge.fromPlayer(fake).startRiding(target, true);
        } catch (RuntimeException failure) {
            // A target can migrate or be retired between the snapshot and this
            // region task. Convert the Folia ownership failure into a command
            // result instead of leaking an exception to the scheduler.
            if (Tasks.isFolia()) {
                send(sender, translatable("fakeplayer.command.ride.error.cross-region").color(RED));
                return;
            }
            throw failure;
        }
    }

    private void send(@NotNull CommandSender sender, @NotNull Component message) {
        if (Tasks.isFolia() && sender instanceof Player player) {
            Tasks.run(Main.getInstance(), player, () -> sender.sendMessage(message));
        } else {
            sender.sendMessage(message);
        }
    }

    private record EntitySnapshot(org.bukkit.World world, double x, double y, double z) {

        private double distanceSquared(@NotNull org.bukkit.Location location) {
            var dx = x - location.getX();
            var dy = y - location.getY();
            var dz = z - location.getZ();
            return dx * dx + dy * dy + dz * dz;
        }
    }

}
