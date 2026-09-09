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

import java.util.ArrayList;
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

    private static final int CROSS_REGION_TOP_SLOTS = 36;

    protected final FakeplayerManager manager;
    protected final FakeplayerList fakeplayerList;
    private final Map<UUID, CrossRegionSession> crossRegionSessions = new ConcurrentHashMap<>();
    private final Object crossRegionSessionLock = new Object();

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
        return true;
    }

    protected abstract @Nullable InventoryView openInventory(@NotNull Player viewer, @NotNull Player whom);

    /**
     * Titles are part of menu construction. Applying a title after opening a
     * typed inventory can make the server change its menu declaration without
     * changing the already prepared slot payload.
     */
    protected final @NotNull String inventoryTitle(@NotNull Player viewer, @NotNull Player whom) {
        return ComponentUtils.toString(translatable(
                "fakeplayer.manager.inventory.title",
                text(whom.getName())
        ), viewer.locale());
    }

    /**
     * Folia does not allow a viewer-region inventory view to directly expose a
     * PlayerInventory owned by another region. Use a viewer-owned mirror and
     * keep the view read-only because the fake player may continue changing
     * its inventory while the viewer is in another region.
     *
     * <p>The mirror is deliberately read-only. A snapshot cannot safely be
     * written back after the fake player has continued consuming, picking up,
     * or otherwise changing its inventory on another region thread.</p>
     */
    private boolean openCrossRegion(@NotNull Player viewer, @NotNull Player whom) {
        Tasks.call(Main.getInstance(), whom, () -> copyContents(whom))
                .thenAccept(contents -> Tasks.run(Main.getInstance(), viewer, () -> {
                    var target = fakeplayerList.getByUUID(whom.getUniqueId());
                    if (!viewer.isOnline() || target == null || target.getPlayer() != whom) {
                        return;
                    }

                    var inventory = Bukkit.createInventory(
                            null,
                            CROSS_REGION_TOP_SLOTS,
                            inventoryTitle(viewer, whom)
                    );
                    try {
                        // Keep the existing cross-region presentation (the
                        // first 36 slots) while making the client menu shape
                        // explicit instead of relying on PLAYER's 43-slot
                        // storage container being rendered as a 4-row menu.
                        inventory.setContents(Arrays.copyOf(contents, CROSS_REGION_TOP_SLOTS));
                    } catch (IllegalArgumentException failure) {
                        viewer.sendMessage(translatable("fakeplayer.command.invsee.error.cross-region"));
                        return;
                    }

                    var session = new CrossRegionSession(viewer, whom, inventory);
                    synchronized (crossRegionSessionLock) {
                        if (!viewer.isOnline()
                                || fakeplayerList.getByUUID(whom.getUniqueId()) != target) {
                            return;
                        }
                        crossRegionSessions.put(viewer.getUniqueId(), session);
                    }

                    // The target can quit between the snapshot and the viewer
                    // task. Recheck immediately before opening; the quit
                    // handler also removes any session already published.
                    if (fakeplayerList.getByUUID(whom.getUniqueId()) != target) {
                        crossRegionSessions.remove(viewer.getUniqueId(), session);
                        return;
                    }

                    var view = viewer.openInventory(inventory);
                    if (view == null) {
                        crossRegionSessions.remove(viewer.getUniqueId(), session);
                    }
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

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void preventCrossRegionClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        var session = sessionFor(viewer, event.getView());
        // The cross-region inventory is a snapshot. Cancel every click mode,
        // including number/drop/off-hand actions on the bottom inventory, so
        // the viewer cannot mutate local state while looking at a stale mirror.
        if (session != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void preventCrossRegionDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        var session = sessionFor(viewer, event.getView());
        if (session != null) {
            event.setCancelled(true);
        }
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
    }

    @Override
    public void onDisable() {
        var sessionsToClose = new ArrayList<CrossRegionSession>();
        synchronized (crossRegionSessionLock) {
            sessionsToClose.addAll(crossRegionSessions.values());
            crossRegionSessions.clear();
        }
        sessionsToClose.forEach(this::closeSession);
    }

    @EventHandler
    public void quitCrossRegion(@NotNull PlayerQuitEvent event) {
        var quitting = event.getPlayer();
        var sessionsToClose = new ArrayList<CrossRegionSession>();
        synchronized (crossRegionSessionLock) {
            // A viewer quit invalidates its own local mirror.
            crossRegionSessions.remove(quitting.getUniqueId());

            // A target quit invalidates every viewer-owned snapshot of that
            // exact Player instance. Compare by identity so a later fake
            // player reusing the UUID cannot be closed by an old quit event.
            for (var entry : crossRegionSessions.entrySet()) {
                var session = entry.getValue();
                if (session.target() == quitting
                        && crossRegionSessions.remove(entry.getKey(), session)) {
                    sessionsToClose.add(session);
                }
            }
        }

        // Inventory operations belong to the viewer's entity scheduler on
        // Folia. The map entry is removed above before scheduling, making this
        // safe if the viewer closes or opens another inventory first.
        sessionsToClose.forEach(this::closeSession);
    }

    private void closeSession(@NotNull CrossRegionSession session) {
        var viewer = session.viewer();
        if (!viewer.isOnline()) {
            return;
        }
        try {
            Tasks.run(Main.getInstance(), viewer, () -> {
                if (!viewer.isOnline()) {
                    return;
                }
                var view = viewer.getOpenInventory();
                if (view.getTopInventory() == session.inventory()) {
                    viewer.closeInventory();
                }
            });
        } catch (Throwable ignored) {
            // Plugin shutdown or a retired viewer scheduler may reject the
            // close; the session was already removed and cannot be reused.
        }
    }

    private record CrossRegionSession(
            @NotNull Player viewer,
            @NotNull Player target,
            @NotNull Inventory inventory
    ) {
    }
}
