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

    private long loadAt;

    @Override
    public void onLoad() {
        loadAt = System.currentTimeMillis();
        instance = this;
    }

    @Override
    public void onEnable() {
        injector = Guice.createInjector(
                new FakeplayerModule(),
                new CommandModule(),
                new DatabaseModule(),
                new TranslationModule(new TranslationConfig(
                        "message/message",
                        TranslatorUtils.getDefaultLocale(Main.getInstance())))
        );

        // Recover write-ahead lifecycle finalizers before commands, listeners,
        // or plugin messaging can create new externally visible state. A
        // failed recovery aborts enable and retains its journal for retry.
        injector.getInstance(FakeplayerConfig.class);
        injector.getInstance(LifecycleCommandCoordinator.class).recoverPendingSynchronously();

        injector.getInstance(CommandRegistry.class).register();
        {
            var messenger = getServer().getMessenger();
            messenger.registerOutgoingPluginChannel(this, "BungeeCord");
            // Starts authoritative local cleanup. On BungeeCord the manager
            // deliberately fail-closes without registering an incoming
            // PlayerList listener.
            injector.getInstance(WildFakeplayerManager.class);
        }

        {
            var manager = getServer().getPluginManager();
            manager.registerEvents(injector.getInstance(PlayerListener.class), this);
            manager.registerEvents(injector.getInstance(FakeplayerLifecycleListener.class), this);
            manager.registerEvents(injector.getInstance(FakeplayerListener.class), this);
            manager.registerEvents(injector.getInstance(FakeplayerAutofishManager.class), this);
            manager.registerEvents(injector.getInstance(FakeplayerReplenishManager.class), this);
            manager.registerEvents(injector.getInstance(InvseeManager.class), this);
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

        if (injector.getInstance(FakeplayerConfig.class).isCheckForUpdates()) {
            checkForUpdatesAsync();
        }

        getLogger().info("Enabled in %d ms".formatted(System.currentTimeMillis() - loadAt));
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
        var currentInjector = this.injector;
        if (currentInjector != null) {
            Exceptions.suppress(this, () -> currentInjector.getInstance(FakeplayerLifecycleListener.class).onDisable());
            var fakeplayerManager = currentInjector.getInstance(FakeplayerManager.class);
            Exceptions.suppress(this, fakeplayerManager::beginShutdown);
            // Cancel/interrupt database continuations before synchronously
            // recovering their write-ahead journal.
            Exceptions.suppress(this, () -> currentInjector.getInstance(PluginAsyncExecutor.class).shutdown());
            Exceptions.suppress(this, fakeplayerManager::onDisable);
            Exceptions.suppress(this, () -> currentInjector.getInstance(ActionManager.class).onDisable());
            Exceptions.suppress(this, () -> currentInjector.getInstance(WildFakeplayerManager.class).onDisable());
            Exceptions.suppress(this, () -> currentInjector.getInstance(UsedIdRepository.class).onDisable());
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
