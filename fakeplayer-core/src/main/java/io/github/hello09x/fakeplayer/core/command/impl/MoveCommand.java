package io.github.hello09x.fakeplayer.core.command.impl;

import com.google.inject.Singleton;
import dev.jorel.commandapi.executors.CommandExecutor;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.jetbrains.annotations.Range;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class MoveCommand extends AbstractCommand {

    private final Map<UUID, Tasks.Task> stopTasks = new ConcurrentHashMap<>();

    /**
     * 假人移动
     */
    public CommandExecutor move(@Range(from = 0, to = 1) float forward, @Range(from = 0, to = 1) float strafing) {
        return (sender, args) -> {
            var fake = getFakeplayer(sender, args);
            var previous = stopTasks.remove(fake.getUniqueId());
            if (previous != null) {
                previous.cancel();
            }

            var fakeId = fake.getUniqueId();
            runOnFake(fake, () -> {
                var handle = bridge.fromPlayer(fake);
                float vel = fake.isSneaking() ? 0.3F : 1.0F;
                if (forward != 0.0F) {
                    handle.setZza(vel * forward);
                }
                if (strafing != 0.0F) {
                    handle.setXxa(vel * strafing);
                }

                final Tasks.Task[] ref = new Tasks.Task[1];
                Runnable stopping = () -> {
                    handle.setXxa(0);
                    handle.setZza(0);
                    var self = stopTasks.get(fakeId);
                    if (self == ref[0]) {
                        stopTasks.remove(fakeId);
                    }
                };
                ref[0] = Tasks.runDelayed(Main.getInstance(), fake, stopping, fake.isSprinting() ? 40 : 20);
                this.stopTasks.put(fakeId, ref[0]);
            });
        };
    }


}
