package io.github.hello09x.fakeplayer.core.manager.naming;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.manager.naming.exception.IllegalCustomNameException;
import io.github.hello09x.fakeplayer.core.repository.FakeplayerProfileRepository;
import io.github.hello09x.fakeplayer.core.repository.UsedIdRepository;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.logging.Logger;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@Singleton
public class NameManager {

    private final static Logger log = Main.getInstance().getLogger();
    private final static int MAX_LENGTH = 16;   // mojang required
    private final static int MIN_LENGTH = 3; // mojang required

    private final UsedIdRepository legacyUsedIdRepository;
    private final FakeplayerProfileRepository profileRepository;
    private final FakeplayerConfig config;
    private final Map<String, NameSource> nameSources = new ConcurrentHashMap<>();
    /** Coalesce concurrent Folia UUID lookups for the same persistent name. */
    private final Map<String, CompletableFuture<UUID>> asyncUUIDs = new ConcurrentHashMap<>();

    private final String serverId;

    @Inject
    public NameManager(UsedIdRepository legacyUsedIdRepository, FakeplayerProfileRepository profileRepository, FakeplayerConfig config) {
        this.legacyUsedIdRepository = legacyUsedIdRepository;
        this.profileRepository = profileRepository;
        this.config = config;

        var file = new File(Main.getInstance().getDataFolder(), "serverid");
        serverId = Optional.ofNullable(loadServerId(file)).orElseGet(() -> {
            var uuid = UUID.randomUUID().toString();
            try (var out = new FileWriter(file, StandardCharsets.UTF_8)) {
                IOUtils.write(uuid, out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return uuid;
        });
    }

    private static @Nullable String loadServerId(@NotNull File file) {
        if (!file.exists()) {
            return null;
        }

        String serverId;
        try (var in = new FileReader(file, StandardCharsets.UTF_8)) {
            serverId = IOUtils.toString(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return serverId.isBlank() ? null : serverId;
    }

    /**
     * 通过名称生成 UUID
     *
     * @param name 名称
     * @return UUID
     */
    private @NotNull UUID getUUIDFromName(@NotNull String name) {
        {
            var uuid = profileRepository.selectUUIDByName(name);
            if (uuid != null) {
                return uuid;
            }
        }

        // 老数据迁移
        {
            var base = serverId + ":" + name;
            var legacyUUID = UUID.nameUUIDFromBytes(base.getBytes(StandardCharsets.UTF_8));
            if (legacyUsedIdRepository.contains(legacyUUID)) {
                profileRepository.insert(name, legacyUUID);
                legacyUsedIdRepository.remove(legacyUUID);
                return legacyUUID;
            }
        }

        // 新逻辑
        for (int i = 0; i < 10; i++) {
            var uuid = UUID.randomUUID();
            if (legacyUsedIdRepository.contains(uuid) || profileRepository.existsByUUID(uuid) || Bukkit.getOfflinePlayer(uuid).hasPlayedBefore()) {
                continue;
            }
            profileRepository.insert(name, uuid);
            return uuid;
        }

        throw new IllegalStateException("Failed to generate uuid for fake player '%s' after 10 attempts".formatted(name));
    }

    /**
     * Folia-safe version of {@link #getSpecifiedName(String)}. Database work is
     * performed on the asynchronous executor; Bukkit's online/offline player
     * lookups are performed on the global region.
     */
    public @NotNull CompletableFuture<SequenceName> getSpecifiedNameAsync(@NotNull String name) {
        if (!Tasks.isFolia()) {
            return CompletableFuture.completedFuture(this.getSpecifiedName(name));
        }

        final String normalized;
        try {
            normalized = this.normalizeSpecifiedName(name);
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }

        return this.readCustomNameLookupAsync(normalized).thenCompose(lookup -> {
            if (lookup.online()) {
                return CompletableFuture.failedFuture(this.onlineNameError(normalized));
            }

            return CompletableFuture
                    .supplyAsync(() -> lookup.hasPlayedBefore()
                            && !legacyUsedIdRepository.contains(lookup.uuid())
                            && !profileRepository.existsByUUID(lookup.uuid()))
                    .thenCompose(used -> {
                        if (used) {
                            return CompletableFuture.failedFuture(this.usedNameError(normalized, lookup.uuid()));
                        }
                        return this.getUUIDFromNameAsync(normalized)
                                .thenApply(uuid -> new SequenceName("custom", 0, uuid, normalized));
                    });
        });
    }

    /**
     * Folia-safe version of regular name allocation. The sequence reservation
     * remains synchronized, while the persistent UUID lookup is asynchronous.
     */
    public @NotNull CompletableFuture<SequenceName> getRegularNameAsync(@NotNull CommandSender creator) {
        if (!Tasks.isFolia()) {
            return CompletableFuture.completedFuture(this.getRegularName(creator));
        }

        var creatorName = this.readCreatorNameAsync(creator);
        var onlineNames = this.readOnlineNamesAsync();
        var nameConfig = new NameConfig(
                config.getNameTemplate(),
                config.getNamePrefix(),
                config.getPlayerLimit()
        );

        return creatorName.thenCombine(onlineNames, (name, online) -> new RegularNameContext(
                        this.regularSource(name, nameConfig),
                        online,
                        name,
                        nameConfig
                ))
                .thenCompose(context -> this.nextRegularNameAsync(context, 0));
    }

    /**
     * 通过自定义名称获取序列名
     *
     * @param name 自定义名称
     * @return 序列名
     */
    public @NotNull SequenceName getSpecifiedName(@NotNull String name) {
        name = this.normalizeSpecifiedName(name);

        {
            var player = Bukkit.getPlayerExact(name);
            if (player != null) {
                if (Tasks.isFolia()) {
                    // Player#isDead is region-owned on Folia. The generic online
                    // error preserves the name collision check without reading a
                    // different region's entity from the command thread.
                    throw new IllegalCustomNameException(
                            translatable("fakeplayer.spawn.error.name.online", text(name, GOLD)).color(RED)
                    );
                }
                throw new IllegalCustomNameException(
                        player.isDead()
                                ? translatable("fakeplayer.spawn.error.name.online-dead", text(name, GOLD), text("/fp respawn", DARK_GREEN)).color(RED)
                                : translatable("fakeplayer.spawn.error.name.online", text(name, GOLD)).color(RED)
                );
            }
        }

        var player = Bukkit.getOfflinePlayer(name);
        var uuid = player.getUniqueId();
        if (player.hasPlayedBefore() && !legacyUsedIdRepository.contains(uuid) && !profileRepository.existsByUUID(uuid)) {
            throw this.usedNameError(name, uuid);
        }

        return new SequenceName(
                "custom",
                0,
                this.getUUIDFromName(name),
                name
        );
    }

    private @NotNull String normalizeSpecifiedName(@NotNull String name) {
        if (StringUtils.isNotBlank(config.getNamePrefix())) {
            name = config.getNamePrefix().trim() + name;
        }
        if (name.startsWith("-")) {
            throw new IllegalCustomNameException(translatable(
                    "fakeplayer.spawn.error.name.start-with-illegal-character",
                    text("-", WHITE)
            ).color(RED));
        }

        if (name.length() > MAX_LENGTH) {
            throw new IllegalCustomNameException(translatable(
                    "fakeplayer.spawn.error.name.too-long",
                    text(MAX_LENGTH, WHITE)
            ).color(RED));
        }

        if (name.length() < MIN_LENGTH) {
            throw new IllegalCustomNameException(translatable(
                    "fakeplayer.spawn.error.name.too-short",
                    text(MIN_LENGTH, WHITE)
            ).color(RED));
        }

        if (!config.getNamePattern().asPredicate().test(name)) {
            throw new IllegalCustomNameException(translatable("fakeplayer.spawn.error.name.invalid", RED));
        }
        return name;
    }

    private @NotNull IllegalCustomNameException onlineNameError(@NotNull String name) {
        return new IllegalCustomNameException(
                translatable("fakeplayer.spawn.error.name.online", text(name, GOLD)).color(RED)
        );
    }

    private @NotNull IllegalCustomNameException usedNameError(@NotNull String name, @NotNull UUID uuid) {
        return new IllegalCustomNameException(translatable(
                "fakeplayer.spawn.error.name.used",
                text(name, GOLD),
                text(uuid.toString(), GOLD)
        ).color(RED));
    }

    /**
     * 获取一个序列名
     *
     * @param creator 创建者
     * @return 序列名
     */
    public synchronized @NotNull SequenceName getRegularName(@NotNull CommandSender creator) {
        var source = config.getNameTemplate();
        if (source.isBlank()) {
            source = creator.getName();
        }
        source = source.replace("%c", creator.getName());
        if (StringUtils.isNotBlank(config.getNamePrefix())) {
            source = config.getNamePrefix().trim() + source;
        }

        for (int i = 0; i < 10; i++) {
            var seq = nameSources.computeIfAbsent(source, ignored -> new NameSource(config.getPlayerLimit())).pop();
            var suffix = "_" + (seq + 1);

            String name;
            if (source.length() + suffix.length() > MAX_LENGTH) {
                name = source.substring(0, (MAX_LENGTH - suffix.length())) + suffix;
            } else {
                name = source + suffix;
            }

            if (Bukkit.getPlayerExact(name) != null) {
                continue;
            }

            return new SequenceName(source, seq, this.getUUIDFromName(name), name);
        }

        String name;
        for (int i = 0; i < 10; i++) {
            name = RandomStringUtils.randomAlphanumeric(MAX_LENGTH);
            if (Bukkit.getPlayerExact(name) != null) {
                continue;
            }
            log.warning("Failed to generate a regular name for fake player after 10 attempts, using a random name as fallback: " + name);
            return new SequenceName("random", 0, this.getUUIDFromName(name), name);
        }

        throw new IllegalStateException("Failed to generate a name for fake player based on creator '%s'".formatted(creator.getName()));
    }

    private @NotNull CompletableFuture<SequenceName> nextRegularNameAsync(
            @NotNull RegularNameContext context,
            int attempt
    ) {
        if (attempt >= 10) {
            return this.nextRandomNameAsync(context, 0);
        }

        int sequence;
        synchronized (this) {
            sequence = nameSources
                    .computeIfAbsent(context.source(), ignored -> new NameSource(context.nameConfig().playerLimit()))
                    .pop();
        }

        var name = this.sequenceName(context.source(), sequence);
        if (context.onlineNames().contains(name)) {
            return this.nextRegularNameAsync(context, attempt + 1);
        }

        return this.getUUIDFromNameAsync(name)
                .thenApply(uuid -> new SequenceName(context.source(), sequence, uuid, name))
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        // UUID persistence can fail independently of sequence
                        // allocation; do not permanently consume the slot.
                        this.unregister(context.source(), sequence);
                    }
                });
    }

    private @NotNull CompletableFuture<SequenceName> nextRandomNameAsync(
            @NotNull RegularNameContext context,
            int attempt
    ) {
        if (attempt >= 10) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Failed to generate a name for fake player based on creator '%s'".formatted(context.creatorName())
            ));
        }

        var name = RandomStringUtils.randomAlphanumeric(MAX_LENGTH);
        if (context.onlineNames().contains(name)) {
            return this.nextRandomNameAsync(context, attempt + 1);
        }

        log.warning("Failed to generate a regular name for fake player after 10 attempts, using a random name as fallback: " + name);
        return this.getUUIDFromNameAsync(name)
                .thenApply(uuid -> new SequenceName("random", 0, uuid, name));
    }

    private @NotNull String sequenceName(@NotNull String source, int sequence) {
        var suffix = "_" + (sequence + 1);
        if (source.length() + suffix.length() > MAX_LENGTH) {
            return source.substring(0, MAX_LENGTH - suffix.length()) + suffix;
        }
        return source + suffix;
    }

    private @NotNull CompletableFuture<UUID> getUUIDFromNameAsync(@NotNull String name) {
        return asyncUUIDs.computeIfAbsent(name, key -> {
            var future = this.resolveUUIDFromNameAsync(key);
            future.whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    asyncUUIDs.remove(key, future);
                }
            });
            return future;
        });
    }

    private @NotNull CompletableFuture<UUID> resolveUUIDFromNameAsync(@NotNull String name) {
        return CompletableFuture
                .supplyAsync(() -> profileRepository.selectUUIDByName(name))
                .thenCompose(existing -> {
                    if (existing != null) {
                        return CompletableFuture.completedFuture(existing);
                    }

                    var legacyUUID = UUID.nameUUIDFromBytes((serverId + ":" + name).getBytes(StandardCharsets.UTF_8));
                    return CompletableFuture
                            .supplyAsync(() -> {
                                if (!legacyUsedIdRepository.contains(legacyUUID)) {
                                    return null;
                                }
                                profileRepository.insert(name, legacyUUID);
                                legacyUsedIdRepository.remove(legacyUUID);
                                return legacyUUID;
                            })
                            .thenCompose(migrated -> migrated == null
                                    ? this.findRandomUUIDAsync(name, 0)
                                    : CompletableFuture.completedFuture(migrated));
                });
    }

    private @NotNull CompletableFuture<UUID> findRandomUUIDAsync(@NotNull String name, int attempt) {
        if (attempt >= 10) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Failed to generate uuid for fake player '%s' after 10 attempts".formatted(name)
            ));
        }

        var uuid = UUID.randomUUID();
        return CompletableFuture
                .supplyAsync(() -> legacyUsedIdRepository.contains(uuid) || profileRepository.existsByUUID(uuid))
                .thenCompose(used -> {
                    if (used) {
                        return this.findRandomUUIDAsync(name, attempt + 1);
                    }

                    return this.hasPlayedBeforeAsync(uuid).thenCompose(playedBefore -> {
                        if (playedBefore) {
                            return this.findRandomUUIDAsync(name, attempt + 1);
                        }
                        return CompletableFuture.supplyAsync(() -> {
                            profileRepository.insert(name, uuid);
                            return uuid;
                        });
                    });
                });
    }

    private @NotNull CompletableFuture<Boolean> hasPlayedBeforeAsync(@NotNull UUID uuid) {
        if (Tasks.isFolia()) {
            return Tasks.callGlobal(Main.getInstance(), () -> Bukkit.getOfflinePlayer(uuid).hasPlayedBefore());
        }
        return CompletableFuture.completedFuture(Bukkit.getOfflinePlayer(uuid).hasPlayedBefore());
    }

    private @NotNull CompletableFuture<CustomNameLookup> readCustomNameLookupAsync(@NotNull String name) {
        return Tasks.callGlobal(Main.getInstance(), () -> {
            var offlinePlayer = Bukkit.getOfflinePlayer(name);
            return new CustomNameLookup(
                    Bukkit.getPlayerExact(name) != null,
                    offlinePlayer.getUniqueId(),
                    offlinePlayer.hasPlayedBefore()
            );
        });
    }

    private @NotNull CompletableFuture<Set<String>> readOnlineNamesAsync() {
        return Tasks.callGlobal(Main.getInstance(), () -> Bukkit.getOnlinePlayers()
                .stream()
                .map(Player::getName)
                .collect(Collectors.toUnmodifiableSet()));
    }

    private @NotNull CompletableFuture<String> readCreatorNameAsync(@NotNull CommandSender creator) {
        if (creator instanceof Player player && Tasks.isFolia()) {
            return Tasks.call(Main.getInstance(), player, player::getName);
        }
        return CompletableFuture.completedFuture(creator.getName());
    }

    private @NotNull String regularSource(@NotNull String creatorName, @NotNull NameConfig nameConfig) {
        var source = nameConfig.template();
        if (source.isBlank()) {
            source = creatorName;
        }
        source = source.replace("%c", creatorName);
        if (StringUtils.isNotBlank(nameConfig.prefix())) {
            source = nameConfig.prefix().trim() + source;
        }
        return source;
    }

    /**
     * 归还序列名
     *
     * @param group    分组
     * @param sequence 序列
     */
    public synchronized void unregister(@NotNull String group, int sequence) {
        Optional.ofNullable(nameSources.get(group)).ifPresent(ns -> ns.push(sequence));
    }

    /**
     * 归还序列名
     *
     * @param sn 序列名
     */
    public void unregister(@NotNull SequenceName sn) {
        this.unregister(sn.group(), sn.sequence());
    }

    private record CustomNameLookup(boolean online, UUID uuid, boolean hasPlayedBefore) {
    }

    private record NameConfig(String template, String prefix, int playerLimit) {
    }

    private record RegularNameContext(
            String source,
            Set<String> onlineNames,
            String creatorName,
            NameConfig nameConfig
    ) {
    }

}
