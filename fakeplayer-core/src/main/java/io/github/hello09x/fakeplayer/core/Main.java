package io.github.hello09x.fakeplayer.core;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.github.hello09x.devtools.command.CommandModule;
import io.github.hello09x.devtools.core.TranslationModule;
import io.github.hello09x.devtools.core.translation.TranslationConfig;
import io.github.hello09x.devtools.core.translation.TranslatorUtils;
import io.github.hello09x.devtools.core.utils.Exceptions;
import io.github.hello09x.devtools.database.DatabaseModule;
import io.github.hello09x.fakeplayer.core.command.CommandRegistry;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.listener.FakeplayerLifecycleListener;
import io.github.hello09x.fakeplayer.core.listener.FakeplayerListener;
import io.github.hello09x.fakeplayer.core.listener.PlayerListener;
import io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandCoordinator;
import io.github.hello09x.fakeplayer.core.lifecycle.FakeplayerShutdownCoordinator;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerAutofishManager;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerManager;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerReplenishManager;
import io.github.hello09x.fakeplayer.core.manager.WildFakeplayerManager;
import io.github.hello09x.fakeplayer.core.manager.action.ActionManager;
import io.github.hello09x.fakeplayer.core.manager.invsee.InvseeManager;
import io.github.hello09x.fakeplayer.core.placeholder.FakeplayerPlaceholderExpansion;
import io.github.hello09x.fakeplayer.core.repository.UsedIdRepository;
import io.github.hello09x.fakeplayer.core.util.async.PluginAsyncExecutor;
import io.github.hello09x.fakeplayer.core.util.update.UpdateChecker;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class Main extends JavaPlugin {

    @Getter
    private static Main instance;

    private Injector injector;
    private FakeplayerShutdownCoordinator shutdownCoordinator;

    private long loadAt;

    @Override
    public void onLoad() {
        loadAt = System.currentTimeMillis();
        instance = this;
    }

    @Override
    public void onEnable() {
        // Bukkit can invoke onEnable again after a plugin reload. A shutdown
        // coordinator is intentionally one-shot, so each enable cycle gets a
        // fresh Main-owned instance and a fresh set of initialized actions.
        var coordinator = new FakeplayerShutdownCoordinator(this);
        this.shutdownCoordinator = coordinator;
        try {
            // This listener is Main-owned and has no Guice dependencies. It
            // must be present before any injectable enable step can fail.
            coordinator.register();

            injector = Guice.createInjector(
                    new FakeplayerModule(),
                    new CommandModule(),
                    new DatabaseModule(),
                    new TranslationModule(new TranslationConfig(
                            "message/message",
                            TranslatorUtils.getDefaultLocale(Main.getInstance())))
            );

            var config = injector.getInstance(FakeplayerConfig.class);
            var asyncExecutor = injector.getInstance(PluginAsyncExecutor.class);
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.STOP_ASYNC,
                    "plugin async executor",
                    () -> {
                        if (!asyncExecutor.shutdown()) {
                            throw new IllegalStateException(
                                    "fakeplayer async executor did not terminate within the shutdown barrier"
                            );
                        }
                    }
            );

            var lifecycleCoordinator = injector.getInstance(LifecycleCommandCoordinator.class);
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.STOP_ACCEPTING,
                    "lifecycle coordinator",
                    lifecycleCoordinator::beginShutdown
            );
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.RECOVER_JOURNAL,
                    "lifecycle journal",
                    lifecycleCoordinator::recoverPendingSynchronously
            );

            // Recover write-ahead lifecycle finalizers before commands,
            // listeners, or plugin messaging can create new externally visible
            // state. A failed recovery aborts enable and retains its journal.
            lifecycleCoordinator.recoverPendingSynchronously();

            // Eagerly initialize and register every component that owns a
            // shutdown action before CommandAPI or later enable steps run.
            // Shutdown never calls injector.getInstance(), so partial enable
            // failures cannot lazily create a scheduler after disable.
            var fakeplayerManager = injector.getInstance(FakeplayerManager.class);
            var lifecycleListener = injector.getInstance(FakeplayerLifecycleListener.class);
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.STOP_ACCEPTING,
                    "lifecycle delayed tasks",
                    lifecycleListener::onDisable
            );
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.STOP_ACCEPTING,
                    "fake-player manager",
                    fakeplayerManager::beginShutdown
            );
            var invseeManager = injector.getInstance(InvseeManager.class);
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.CLEANUP,
                    "invsee sessions",
                    invseeManager::onDisable
            );
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.CLEANUP,
                    "fake-player manager resources",
                    fakeplayerManager::onDisable
            );

            var actionManager = injector.getInstance(ActionManager.class);
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.CLEANUP,
                    "action manager",
                    actionManager::onDisable
            );

            // Start authoritative local cleanup before command registration so
            // a failed command registration still has a tracked cleanup path.
            var wildFakeplayerManager = injector.getInstance(WildFakeplayerManager.class);
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.CLEANUP,
                    "wild fake-player manager",
                    wildFakeplayerManager::onDisable
            );

            var usedIdRepository = injector.getInstance(UsedIdRepository.class);
            coordinator.add(
                    FakeplayerShutdownCoordinator.Phase.CLEANUP,
                    "used-id repository",
                    usedIdRepository::onDisable
            );

            injector.getInstance(CommandRegistry.class).register();
            {
                var messenger = getServer().getMessenger();
                messenger.registerOutgoingPluginChannel(this, "BungeeCord");
            }

            {
                var manager = getServer().getPluginManager();
                manager.registerEvents(injector.getInstance(PlayerListener.class), this);
                manager.registerEvents(lifecycleListener, this);
                manager.registerEvents(injector.getInstance(FakeplayerListener.class), this);
                manager.registerEvents(injector.getInstance(FakeplayerAutofishManager.class), this);
                manager.registerEvents(injector.getInstance(FakeplayerReplenishManager.class), this);
                manager.registerEvents(invseeManager, this);
            }

            {
                var placeholderExpansion = injector.getInstance(FakeplayerPlaceholderExpansion.class);
                if (placeholderExpansion != null) {
                    if (placeholderExpansion.register()) {
                        getServer().getPluginManager().registerEvents(placeholderExpansion, this);
                        getLogger().info("Successfully registered PlaceholderExpansion");
                    }
                }
            }

            if (config.isCheckForUpdates()) {
                checkForUpdatesAsync();
            }

            getLogger().info("Enabled in %d ms".formatted(System.currentTimeMillis() - loadAt));
        } catch (Throwable failure) {
            // Handle an enable failure while the plugin is still enabled, then
            // let Paper's subsequent PluginDisableEvent call the same idempotent
            // coordinator as a fallback.
            coordinator.shutdown();
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Fakeplayer failed to enable", failure);
        }
    }

    public void checkForUpdatesAsync() {
        this.injector.getInstance(PluginAsyncExecutor.class).runAsync(() -> {
            var meta = this.getPluginMeta();
            var checker = new UpdateChecker("IGNGserver", "fakeplayer-folia");
            try {
                var release = checker.getLastRelease();

                var current = meta.getVersion();
                var other = release.getTagName();
                if (other.charAt(0) == 'v') {
                    other = other.substring(1);
                }

                if (UpdateChecker.isNew(current, other)) {
                    var log = getLogger();
                    log.info("New version: " + release.getTagName());
                    log.info("Address: " + meta.getWebsite());
                    log.info("Update Log");
                    var body = java.util.Objects.requireNonNullElse(release.getBody(), "");
                    var lines = body.split("\n", 128);
                    for (var line : lines) {
                        log.info("\t" + line.substring(0, Math.min(line.length(), 512)));
                    }
                }

            } catch (Throwable e) {
                getLogger().warning("Error on checking for updates: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        // PluginDisableEvent invokes this same coordinator at LOWEST before
        // dependency listeners close the datasource. The call is idempotent
        // for runtimes that invoke JavaPlugin#onDisable afterwards.
        var coordinator = this.shutdownCoordinator;
        if (coordinator != null) {
            coordinator.shutdown();
        }
        {
            Exceptions.suppress(this, () -> {
                var messenger = getServer().getMessenger();
                messenger.unregisterIncomingPluginChannel(this);
                messenger.unregisterOutgoingPluginChannel(this);
            });
        }
    }

    public static @NotNull Injector getInjector() {
        return instance.injector;
    }

}
