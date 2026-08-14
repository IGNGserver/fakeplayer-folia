package io.github.hello09x.fakeplayer.core.manager.invsee;

import io.github.hello09x.devtools.core.utils.ComponentUtils;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerList;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerManager;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

/**
 * @author tanyaofei
 * @since 2024/8/12
 **/
public abstract class AbstractInvseeManager implements InvseeManager {

    protected final FakeplayerManager manager;
    protected final FakeplayerList fakeplayerList;

    protected AbstractInvseeManager(FakeplayerManager manager, FakeplayerList fakeplayerList) {
        this.manager = manager;
        this.fakeplayerList = fakeplayerList;
    }

    @Override
    public boolean invsee(@NotNull Player viewer, @NotNull Player whom) {
        var fp = fakeplayerList.getByUUID(whom.getUniqueId());
        if (fp == null) {
            return false;
        }
        if (!viewer.isOp() && !fp.isCreatedBy(viewer)) {
            return false;
        }
        if (Tasks.isFolia() && !Bukkit.isOwnedByCurrentRegion(whom)) {
            // Inventory views are owned by the viewer's region, while the
            // inventory being read is owned by the fake player's region. The
            // Bukkit inventory APIs cannot safely bridge those regions.
            viewer.sendMessage(translatable("fakeplayer.command.invsee.error.cross-region"));
            return false;
        }
        var view = this.openInventory(viewer, whom);
        if (view == null) {
            return false;
        }
        if (Tasks.isFolia()) {
            Tasks.call(Main.getInstance(), whom, () -> whom.getLocation().clone()).thenAccept(location ->
                    location.getWorld().playSound(
                            location,
                            Sound.BLOCK_CHEST_OPEN,
                            SoundCategory.BLOCKS,
                            0.3F, 1.0F
                    )
            );
        } else {
            whom.getLocation().getWorld().playSound(
                    whom.getLocation(),
                    Sound.BLOCK_CHEST_OPEN,
                    SoundCategory.BLOCKS,
                    0.3F, 1.0F
            );
        }
        view.setTitle(ComponentUtils.toString(translatable(
                "fakeplayer.manager.inventory.title",
                text(whom.getName())
        ), viewer.locale()));
        return true;
    }

    protected abstract @Nullable InventoryView openInventory(@NotNull Player viewer, @NotNull Player whom);

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void rightClickToInvsee(@NotNull PlayerInteractAtEntityEvent event) {
        if (!((event.getRightClicked()) instanceof Player whom)) {
            return;
        }

        if (Tasks.isFolia()) {
            // Inventory views belong to the viewer's region. The fake player's
            // inventory is still validated by invsee(), but the open operation
            // itself must be queued on the viewer entity scheduler.
            Tasks.run(Main.getInstance(), event.getPlayer(), () -> this.invsee(event.getPlayer(), whom));
        } else {
            this.invsee(event.getPlayer(), whom);   // fakeplayer check here
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void fixDragInventory(@NotNull InventoryDragEvent event) {
        var top = event.getView().getTopInventory();
        if (top.getType() == InventoryType.PLAYER && top.getHolder() instanceof Player whom && manager.isFake(whom)) {
            if (event.getNewItems().keySet().stream().anyMatch(slot -> slot > 35)) {    // > 35 表示从假人背包拖动到玩家背包, 这种操作会出现问题
                event.setCancelled(true);
            }
        }
    }
}
