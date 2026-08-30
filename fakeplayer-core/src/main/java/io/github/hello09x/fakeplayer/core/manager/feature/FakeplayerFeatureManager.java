package io.github.hello09x.fakeplayer.core.manager.feature;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.repository.UserConfigRepository;
import io.github.hello09x.fakeplayer.core.repository.model.Feature;
import io.github.hello09x.fakeplayer.core.repository.model.UserConfig;
import io.github.hello09x.fakeplayer.core.util.async.PluginAsyncExecutor;
import io.github.hello09x.fakeplayer.core.util.scheduler.Tasks;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class FakeplayerFeatureManager {

    private final UserConfigRepository repository;
    private final FakeplayerConfig config;
    private final PluginAsyncExecutor asyncExecutor;

    @Inject
    public FakeplayerFeatureManager(
            UserConfigRepository repository,
            FakeplayerConfig config,
            PluginAsyncExecutor asyncExecutor
    ) {
        this.repository = repository;
        this.config = config;
        this.asyncExecutor = asyncExecutor;
    }

    private @NotNull String getDefaultOption(@NotNull Feature key) {
        return Optional.ofNullable(config.getDefaultFeatures().get(key)).filter(option -> key.getOptions().contains(option)).orElse(key.getDefaultOption());
    }

    public @NotNull Map<Feature, FeatureInstance> getFeatures(@NotNull CommandSender sender) {
        Map<Feature, UserConfig> userConfigs;
        if (sender instanceof Player player) {
            userConfigs = repository.selectByPlayerId(player.getUniqueId()).stream().collect(Collectors.toMap(UserConfig::key, Function.identity()));
        } else {
            userConfigs = Collections.emptyMap();
        }

        var configs = new LinkedHashMap<Feature, FeatureInstance>(Feature.values().length, 1.0F);
        for (var key : Feature.values()) {
            String value;
            if (!key.testPermissions(sender)) {
                value = this.getDefaultOption(key);
            } else {
                value = Optional.ofNullable(userConfigs.get(key)).map(UserConfig::value).orElseGet(() -> this.getDefaultOption(key));
            }
            configs.put(key, new FeatureInstance(key, value));
        }

        return configs;
    }

    /**
     * Read feature configuration without running JDBC work on a Folia region
     * thread. Player permission state is captured on the player's region first;
     * the repository query and immutable map assembly then run asynchronously.
     */
    public @NotNull CompletableFuture<Map<Feature, FeatureInstance>> getFeaturesAsync(@NotNull CommandSender sender) {
        if (sender instanceof Player player) {
            var snapshot = Tasks.isFolia()
                    ? Tasks.call(Main.getInstance(), player, () -> snapshotPermissions(player))
                    : CompletableFuture.completedFuture(snapshotPermissions(player));
            return snapshot.thenCompose(value -> asyncExecutor.supplyAsync(() -> buildFeatures(
                    value.playerId(),
                    value.permissions()
            )));
        }
        return asyncExecutor.supplyAsync(() -> getFeatures(sender));
    }

    private @NotNull PermissionSnapshot snapshotPermissions(@NotNull Player player) {
        return new PermissionSnapshot(
                player.getUniqueId(),
                java.util.Arrays.stream(Feature.values()).collect(Collectors.toMap(
                        Function.identity(),
                        feature -> feature.testPermissions(player)
                ))
        );
    }

    private @NotNull Map<Feature, FeatureInstance> buildFeatures(
            @NotNull UUID playerId,
            @NotNull Map<Feature, Boolean> permissions
    ) {
        var userConfigs = repository.selectByPlayerId(playerId).stream()
                .collect(Collectors.toMap(UserConfig::key, Function.identity()));
        var configs = new LinkedHashMap<Feature, FeatureInstance>(Feature.values().length, 1.0F);
        for (var key : Feature.values()) {
            String value;
            if (!permissions.getOrDefault(key, false)) {
                value = this.getDefaultOption(key);
            } else {
                value = Optional.ofNullable(userConfigs.get(key))
                        .map(UserConfig::value)
                        .orElseGet(() -> this.getDefaultOption(key));
            }
            configs.put(key, new FeatureInstance(key, value));
        }
        return configs;
    }

    private record PermissionSnapshot(UUID playerId, Map<Feature, Boolean> permissions) {
    }

    public void setFeature(@NotNull Player player, @NotNull Feature key, @NotNull String value) {
        if (!key.getOptions().contains(value)) {
            throw new IllegalArgumentException("Unsupported option for " + key.name() + ": " + value);
        }
        this.repository.saveOrUpdate(new UserConfig(
                null,
                player.getUniqueId(),
                key,
                value
        ));
    }

    /**
     * Capture the player identity on its region and perform the blocking JDBC
     * write asynchronously. The command layer can safely use this on both
     * Paper and Folia.
     */
    public @NotNull CompletableFuture<Void> setFeatureAsync(
            @NotNull Player player,
            @NotNull Feature key,
            @NotNull String value
    ) {
        if (!key.getOptions().contains(value)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unsupported option for " + key.name() + ": " + value)
            );
        }

        var playerId = Tasks.isFolia()
                ? Tasks.call(Main.getInstance(), player, player::getUniqueId)
                : CompletableFuture.completedFuture(player.getUniqueId());
        return playerId.thenCompose(id -> asyncExecutor.runAsync(() -> this.repository.saveOrUpdate(new UserConfig(
                null,
                id,
                key,
                value
        ))));
    }

}
