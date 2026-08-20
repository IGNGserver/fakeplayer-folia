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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

/**
 * @author tanyaofei
 * @since 2024/8/12
 **/
public abstract class AbstractInvseeManager implements InvseeManager {

    protected final FakeplayerManager manager;
    protected final FakeplayerList fakeplayerList;
    private final Map<UUID, CrossRegionSession> crossRegionSessions = new ConcurrentHashMap<>();

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
            return this.openCrossRegion(viewer, whom);
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

    /**
     * Folia does not allow a viewer-region inventory view to directly expose a
     * PlayerInventory owned by another region. Use a viewer-owned mirror and
     * synchronize snapshots back to the fake player on its entity scheduler.
     */
    private boolean openCrossRegion(@NotNull Player viewer, @NotNull Player whom) {
        Tasks.call(Main.getInstance(), whom, () -> copyContents(whom))
                .thenAccept(contents -> Tasks.run(Main.getInstance(), viewer, () -> {
                    if (!viewer.isOnline() || fakeplayerList.getByUUID(whom.getUniqueId()) == null) {
                        return;
                    }

                    var inventory = Bukkit.createInventory(null, InventoryType.PLAYER);
                    try {
                        inventory.setContents(contents);
                    } catch (IllegalArgumentException failure) {
                        viewer.sendMessage(translatable("fakeplayer.command.invsee.error.cross-region"));
                        return;
                    }

                    var previous = crossRegionSessions.put(
                            viewer.getUniqueId(),
                            new CrossRegionSession(whom.getUniqueId(), inventory)
                    );
                    if (previous != null) {
                        syncToFake(previous);
                    }

                    var view = viewer.openInventory(inventory);
                    if (view == null) {
                        crossRegionSessions.remove(viewer.getUniqueId());
                        return;
                    }
                    view.setTitle(ComponentUtils.toString(translatable(
                            "fakeplayer.manager.inventory.title",
                            text(whom.getName())
                    ), viewer.locale()));
                }))
                .exceptionally(throwable -> {
                    Tasks.run(Main.getInstance(), viewer, () -> viewer.sendMessage(
                            translatable("fakeplayer.command.invsee.error.cross-region")
                    ));
                    return null;
                });
        return true;
    }

    private static @NotNull ItemStack[] copyContents(@NotNull Player player) {
        return copyContents(player.getInventory());
    }

    private static @NotNull ItemStack[] copyContents(@NotNull Inventory inventory) {
        return Arrays.stream(inventory.getContents())
                .map(item -> item == null ? null : item.clone())
                .toArray(ItemStack[]::new);
    }

    private void syncToFake(@NotNull CrossRegionSession session) {
        var fake = fakeplayerList.getByUUID(session.fakePlayerId());
        if (fake == null) {
            return;
        }
        var contents = copyContents(session.inventory());
        Tasks.run(Main.getInstance(), fake.getPlayer(), () -> {
            if (fakeplayerList.getByUUID(session.fakePlayerId()) == fake) {
                fake.getPlayer().getInventory().setContents(contents);
            }
        });
    }

    private @Nullable CrossRegionSession sessionFor(@NotNull Player viewer) {
        return sessionFor(viewer, viewer.getOpenInventory());
    }

    private @Nullable CrossRegionSession sessionFor(@NotNull Player viewer, @NotNull InventoryView view) {
        var session = crossRegionSessions.get(viewer.getUniqueId());
        return session == null || session.inventory() != view.getTopInventory()
                ? null
                : session;
    }

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

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void syncCrossRegionClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        var session = sessionFor(viewer, event.getView());
        if (session != null) {
            scheduleSync(viewer, session);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void syncCrossRegionDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)
                || event.getRawSlots().stream().noneMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            return;
        }
        var session = sessionFor(viewer, event.getView());
        if (session != null) {
            scheduleSync(viewer, session);
        }
    }

    private void scheduleSync(@NotNull Player viewer, @NotNull CrossRegionSession session) {
        Tasks.runDelayed(Main.getInstance(), viewer, () -> {
            if (crossRegionSessions.get(viewer.getUniqueId()) == session) {
                syncToFake(session);
            }
        }, 1);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void closeCrossRegion(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) {
            return;
        }
        var session = sessionFor(viewer, event.getView());
        if (session == null) {
            return;
        }
        crossRegionSessions.remove(viewer.getUniqueId(), session);
        syncToFake(session);
    }

    @EventHandler
    public void quitCrossRegion(@NotNull PlayerQuitEvent event) {
        var session = crossRegionSessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            syncToFake(session);
        }
    }

    private record CrossRegionSession(
            @NotNull UUID fakePlayerId,
            @NotNull Inventory inventory
    ) {
    }
}
