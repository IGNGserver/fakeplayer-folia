package io.github.hello09x.fakeplayer.core.manager;

import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.entity.Fakeplayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

@Singleton
public class FakeplayerList {

    // Player events and Folia region tasks can update this registry from
    // different region threads. The old HashMaps were safe only on Paper's
    // single server thread.
    private final Map<String, Fakeplayer> playersByName = new ConcurrentHashMap<>();
    private final Map<UUID, Fakeplayer> playersByUUID = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Fakeplayer>> playersByCreator = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();

    /**
     * 添加一个假人到假人清单
     *
     * @param player 假人
     */
    public boolean add(@NotNull Fakeplayer player) {
        synchronized (registryLock) {
            if (this.playersByName.putIfAbsent(player.getName(), player) != null) {
                return false;
            }
            if (this.playersByUUID.putIfAbsent(player.getUUID(), player) != null) {
                this.playersByName.remove(player.getName(), player);
                return false;
            }
            this.playersByCreator.computeIfAbsent(player.getCreator().getName(), key -> new CopyOnWriteArrayList<>()).add(player);
            return true;
        }
    }

    /**
     * 通过假人的名称获取假人
     *
     * @param name 名称
     * @return 假人
     */
    public @Nullable Fakeplayer getByName(@NotNull String name) {
        return this.playersByName.get(name);
    }

    /**
     * 通过 UUID 获取假人
     *
     * @param uuid UUID
     * @return 假人
     */
    public @Nullable Fakeplayer getByUUID(@NotNull UUID uuid) {
        return this.playersByUUID.get(uuid);
    }

    /**
     * 获取创建者创建的所有假人
     *
     * @param creator 创建者
     * @return 假人
     */
    public @NotNull @Unmodifiable List<Fakeplayer> getByCreator(@NotNull String creator) {
        synchronized (registryLock) {
            return Optional.ofNullable(this.playersByCreator.get(creator))
                    .map(List::copyOf)
                    .map(Collections::unmodifiableList)
                    .orElse(Collections.emptyList());
        }
    }

    /**
     * 移除一个假人
     *
     * @param player 假人
     */
    public boolean remove(@NotNull Fakeplayer player) {
        synchronized (registryLock) {
            // Remove by identity. A failed spawn can overlap with a later spawn
            // using the same name/UUID, and an unconditional key removal would
            // delete the newer record from the registry.
            boolean removed = this.playersByName.remove(player.getName(), player);
            removed |= this.playersByUUID.remove(player.getUUID(), player);
            Optional.ofNullable(this.playersByCreator.get(player.getCreator().getName())).ifPresent(players -> {
                players.remove(player);
                if (players.isEmpty()) {
                    this.playersByCreator.remove(player.getCreator().getName(), players);
                }
            });
            return removed;
        }
    }

    /**
     * 通过 UUID 移除假人
     *
     * @param uuid UUID
     * @return 被移除的假人
     */
    public @Nullable Fakeplayer removeByUUID(@NotNull UUID uuid) {
        synchronized (registryLock) {
            // Cleanup is called from PlayerQuitEvent, where Bukkit may already
            // report the entity as offline. Do not route through getByUUID(),
            // whose Paper-only stale-online check would erase the record before
            // the manager can unregister its name and release its network.
            var player = this.playersByUUID.get(uuid);
            if (player == null) {
                return null;
            }
            return this.remove(player) ? player : null;
        }
    }

    /**
     * 获取创建的数量
     *
     * @param creator 玩家
     * @return 数量
     */
    public int countByCreator(@NotNull String creator) {
        synchronized (registryLock) {
            return Optional
                    .ofNullable(this.playersByCreator.get(creator))
                    .map(List::size)
                    .orElse(0);
        }
    }

    /**
     * 获取所有假人
     *
     * @return 假人
     */
    public @NotNull @Unmodifiable List<Fakeplayer> getAll() {
        synchronized (registryLock) {
            return List.copyOf(this.playersByUUID.values());
        }
    }

    public @NotNull Stream<Fakeplayer> stream() {
        return this.getAll().stream();
    }

    public int getSize() {
        synchronized (registryLock) {
            return this.playersByUUID.size();
        }
    }

}
