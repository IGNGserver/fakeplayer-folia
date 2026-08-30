package io.github.hello09x.fakeplayer.core.manager;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.repository.FakeplayerSkinRepository;
import io.github.hello09x.fakeplayer.core.repository.model.FakePlayerSkin;
import io.github.hello09x.fakeplayer.core.util.async.PluginAsyncExecutor;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * @author tanyaofei
 * @since 2024/8/8
 **/
@Singleton
public class FakeplayerSkinManager {

    private final static Logger log = Main.getInstance().getLogger();
    private final FakeplayerSkinRepository repository;
    private final FakeplayerConfig config;
    private final PluginAsyncExecutor asyncExecutor;
    private final Cache<UUID, PlayerProfile> profileCache = CacheBuilder
            .newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    @Inject
    public FakeplayerSkinManager(
            FakeplayerSkinRepository repository,
            FakeplayerConfig config,
            PluginAsyncExecutor asyncExecutor
    ) {
        this.repository = repository;
        this.config = config;
        this.asyncExecutor = asyncExecutor;
    }

    @CanIgnoreReturnValue
    public boolean rememberSkin(@NotNull CommandSender creator, @NotNull Player to, @NotNull OfflinePlayer from) {
        if (!(creator instanceof Player p)) {
            return false;
        }

        repository.insertOrUpdate(new FakePlayerSkin(
                to.getUniqueId(),
                p.getUniqueId(),
                from.getUniqueId()
        ));
        return true;
    }

    /**
     * Folia-safe persistence path for the skin command. UUID reads from live
     * players are captured on their owning entity schedulers before the JDBC
     * write is moved to an asynchronous executor.
     */
    public @NotNull CompletableFuture<Boolean> rememberSkinAsync(
            @NotNull CommandSender creator,
            @NotNull Player to,
            @NotNull OfflinePlayer from
    ) {
        var sourceId = from instanceof Player source && Tasks.isFolia()
                ? Tasks.call(Main.getInstance(), source, source::getUniqueId)
                : CompletableFuture.completedFuture(from.getUniqueId());
        var targetId = Tasks.isFolia()
                ? Tasks.call(Main.getInstance(), to, to::getUniqueId)
                : CompletableFuture.completedFuture(to.getUniqueId());
        var creatorId = creator instanceof Player player && Tasks.isFolia()
                ? Tasks.call(Main.getInstance(), player, player::getUniqueId)
                : CompletableFuture.completedFuture(creator instanceof Player player ? player.getUniqueId() : null);

        return sourceId.thenCombine(targetId, SkinIds::newTarget)
                .thenCombine(creatorId, (ids, creatorUuid) -> {
                    if (creatorUuid == null) {
                        return null;
                    }
                    return new FakePlayerSkin(ids.targetId(), creatorUuid, ids.sourceId());
                })
                .thenCompose(skin -> {
                    if (skin == null) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return asyncExecutor.supplyAsync(() -> {
                        repository.insertOrUpdate(skin);
                        return true;
                    });
                });
    }

    /**
     * Folia-safe variant used during spawning. Repository access is blocking
     * JDBC work and must not run on the fake player's region thread.
     */
    public @NotNull CompletableFuture<Void> useDefaultSkinAsync(
            @NotNull CommandSender creator,
            @NotNull Player to
    ) {
        var targetName = to.getName();
        var targetId = to.getUniqueId();
        if (!(creator instanceof Player p)) {
            if (!config.isDefaultOnlineSkin()) {
                return CompletableFuture.completedFuture(null);
            }
            return this.getOfflinePlayerAsync(targetName)
                    .thenCompose(source -> this.useOnlineSkinAsync(to, source))
                    .thenApply(ignored -> null);
        }

        var creatorId = Tasks.isFolia()
                ? Tasks.call(Main.getInstance(), p, p::getUniqueId)
                : CompletableFuture.completedFuture(p.getUniqueId());
        return creatorId
                .thenCompose(id -> asyncExecutor.supplyAsync(() -> repository.selectByCreatorIdAndPlayerId(id, targetId)))
                .thenCompose(skin -> {
                    if (skin != null) {
                        return this.getOfflinePlayerAsync(skin.targetId())
                                .thenCompose(source -> this.useOnlineSkinAsync(to, source))
                                .thenApply(ignored -> null);
                    }
                    if (config.isDefaultOnlineSkin()) {
                        return this.getOfflinePlayerAsync(targetName)
                                .thenCompose(source -> this.useOnlineSkinAsync(to, source))
                                .thenApply(ignored -> null);
                    }
                    return this.useOnlineSkinAsync(to, p).thenApply(ignored -> null);
                });
    }

    private @NotNull CompletableFuture<OfflinePlayer> getOfflinePlayerAsync(@NotNull UUID uuid) {
        if (Tasks.isFolia()) {
            return Tasks.callGlobal(Main.getInstance(), () -> Bukkit.getOfflinePlayer(uuid));
        }
        return CompletableFuture.completedFuture(Bukkit.getOfflinePlayer(uuid));
    }

    private @NotNull CompletableFuture<OfflinePlayer> getOfflinePlayerAsync(@NotNull String name) {
        if (Tasks.isFolia()) {
            return Tasks.callGlobal(Main.getInstance(), () -> Bukkit.getOfflinePlayer(name));
        }
        return CompletableFuture.completedFuture(Bukkit.getOfflinePlayer(name));
    }

    public void useDefaultSkin(@NotNull CommandSender creator, @NotNull Player to) {
        if (!(creator instanceof Player p)) {
            // 非玩家创建的假人只能采用在线皮肤
            if (config.isDefaultOnlineSkin()) {
                this.useOnlineSkinAsync(to, Bukkit.getOfflinePlayer(to.getName()));
                return;
            }
            return;
        }

        // 使用以前配置过的
        var skin = repository.selectByCreatorIdAndPlayerId(p.getUniqueId(), to.getUniqueId());
        if (skin != null) {
            this.useOnlineSkinAsync(to, Bukkit.getOfflinePlayer(skin.targetId()));
            return;
        }

        if (config.isDefaultOnlineSkin()) {
            // 使用真实皮肤
            this.useOnlineSkinAsync(to, Bukkit.getOfflinePlayer(to.getName()));
        } else {
            // 使用召唤者皮肤
            if (Tasks.isFolia()) {
                this.useOnlineSkinAsync(to, p);
            } else {
                this.useSkin(to, p);
            }
        }
    }

    @CanIgnoreReturnValue
    public boolean useSkin(@NotNull Player to, @NotNull OfflinePlayer from) {
        var profile = from.getPlayerProfile();
        if (!profile.hasTextures()) {
            profile = profileCache.getIfPresent(from.getUniqueId());
        }

        if (profile == null || !profile.hasTextures()) {
            return false;
        }
        this.setTexture(to, profile);
        return true;
    }

    @CanIgnoreReturnValue
    public @NotNull CompletableFuture<Boolean> useOnlineSkinAsync(@NotNull Player to, @NotNull OfflinePlayer from) {
        // Move the profile inspection off the entity/global scheduler without
        // exposing the raw executor to CompletableFuture. Every queued stage is
        // then represented in PluginAsyncExecutor.pending and is cancellable on
        // plugin shutdown.
        return this.readProfileAsync(from)
                .thenCompose(source -> asyncExecutor.supplyCpuAsync(() -> source))
                .thenCompose(source -> {
            var profile = source.profile();
            var profileId = source.uuid();
            if (!profile.hasTextures()) {
                profile = profileCache.getIfPresent(profileId);
            }

            if (profile != null && profile.hasTextures()) {
                var readyProfile = profile;
                return Tasks.call(Main.getInstance(), to, () -> {
                    this.setTexture(to, readyProfile);
                    return true;
                });
            }

            var profileToComplete = profile;
            return asyncExecutor
                    .supplyAsync(() -> {
                        try {
                            return profileToComplete.complete() ? ProfileCompleteResult.SUCCESS : ProfileCompleteResult.FAILED;
                        } catch (Exception e) {
                            log.warning("Failed to update skin of fake player %s since could not fetch online profile from mojang\n%s".formatted(
                                    Optional.ofNullable(source.name()).orElse(profileId.toString()),
                                    Throwables.getStackTraceAsString(e)
                            ));
                            return ProfileCompleteResult.ERROR;
                        }
                    })
                    .thenCompose(result -> {
                        if (result == ProfileCompleteResult.SUCCESS && profileToComplete.hasTextures()) {
                            profileCache.put(profileId, profileToComplete);
                        }

                        return Tasks.call(Main.getInstance(), to, () -> switch (result) {
                            case SUCCESS -> {
                                try {
                                    this.setTexture(to, profileToComplete);
                                    yield true;
                                } catch (Exception e) {
                                    yield false;
                                }
                            }
                            case FAILED -> {
                                log.warning("Failed to update online skin of fakeplayer %s, maybe not a real player".formatted(
                                        Optional.ofNullable(source.name()).orElse(profileId.toString()))
                                );
                                yield false;
                            }
                            case ERROR -> false;
                        });
                    });
        });
    }

    private @NotNull CompletableFuture<ProfileSource> readProfileAsync(@NotNull OfflinePlayer from) {
        if (Tasks.isFolia() && from instanceof Player source) {
            return Tasks.call(Main.getInstance(), source, () -> new ProfileSource(
                    source.getUniqueId(),
                    source.getName(),
                    source.getPlayerProfile()
            ));
        }
        return CompletableFuture.completedFuture(new ProfileSource(
                from.getUniqueId(),
                from.getName(),
                from.getPlayerProfile()
        ));
    }

    private void setTexture(@NotNull Player to, @NotNull PlayerProfile fromProfile) {
        var toProfile = to.getPlayerProfile();
        toProfile.setTextures(fromProfile.getTextures());
        fromProfile.getProperties().stream().filter(p -> p.getName().equals("textures")).findAny().ifPresent(toProfile::setProperty);
        to.setPlayerProfile(toProfile);
    }

    private enum ProfileCompleteResult {
        SUCCESS,
        FAILED,
        ERROR
    }

    private record ProfileSource(UUID uuid, String name, PlayerProfile profile) {
    }

    private record SkinIds(UUID sourceId, UUID targetId) {
        private static SkinIds newTarget(UUID sourceId, UUID targetId) {
            return new SkinIds(sourceId, targetId);
        }
    }


}
