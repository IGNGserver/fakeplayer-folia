package io.github.hello09x.fakeplayer.core.manager.naming;

import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.repository.FakeplayerProfileRepository;
import io.github.hello09x.fakeplayer.core.repository.UsedIdRepository;
import io.github.hello09x.fakeplayer.core.util.async.PluginAsyncExecutor;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class NameManagerTest {

    private static final long WAIT_SECONDS = 5;
    private static final Unsafe UNSAFE = loadUnsafe();
    private static TestProfileRepository profileRepository;
    private static TestUsedIdRepository usedIdRepository;
    private static TestServerState serverState;

    @BeforeAll
    static void installBukkitFixture() throws Exception {
        var main = allocate(Main.class);
        var dataFolder = Files.createTempDirectory("fakeplayer-name-manager-test").toFile();
        setField(main, JavaPlugin.class, "dataFolder", dataFolder);
        setField(main, JavaPlugin.class, "logger", Logger.getLogger("fakeplayer-name-manager-test"));
        setStaticField(Main.class, "instance", main);

        profileRepository = allocate(TestProfileRepository.class);
        usedIdRepository = allocate(TestUsedIdRepository.class);
        serverState = new TestServerState();
        Bukkit.setServer(serverState.proxy());
    }

    @Test
    void regularSequenceDoesNotPublishNameQuarantinedDuringUuidLookup() throws Exception {
        try (var context = new TestContext("Bot")) {
            var gate = profileRepository.blockLookup(name -> name.equals("Bot_1"));
            var future = context.manager().getRegularNameAsync(sender("Creator"));

            awaitLookup(gate);
            context.manager().quarantine(new SequenceName("Bot", 0, UUID.randomUUID(), "Bot_1"));
            profileRepository.releaseLookup();

            var result = future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("Bot_2", result.name());
        }
    }

    @Test
    void regularProfileUuidLookupDoesNotPublishNameQuarantinedDuringLookup() throws Exception {
        try (var context = new TestContext("Bot")) {
            var existingUuid = UUID.randomUUID();
            profileRepository.put("Bot_1", existingUuid);
            var gate = profileRepository.blockLookup(name -> name.equals("Bot_1"));
            var future = context.manager().getRegularNameAsync(sender("Creator"));

            awaitLookup(gate);
            context.manager().quarantine(new SequenceName("Bot", 0, existingUuid, "Bot_1"));
            profileRepository.releaseLookup();

            var result = future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("Bot_2", result.name());
            assertNotEquals("Bot_1", result.name());
        }
    }

    @Test
    void randomFallbackDoesNotPublishNameQuarantinedDuringUuidLookup() throws Exception {
        try (var context = new TestContext("Bot")) {
            profileRepository.malformedNames(Set.of(
                    "Bot_1", "Bot_2", "Bot_3", "Bot_4", "Bot_5",
                    "Bot_6", "Bot_7", "Bot_8", "Bot_9", "Bot_10"
            ));
            var gate = profileRepository.blockLookup(name -> !name.startsWith("Bot_"));
            var future = context.manager().getRegularNameAsync(sender("Creator"));

            awaitLookup(gate);
            var quarantinedName = gate.lookupName();
            context.manager().quarantine(new SequenceName("random", 0, UUID.randomUUID(), quarantinedName));
            profileRepository.releaseLookup();

            var result = future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotEquals(quarantinedName, result.name());
            assertEquals("random", result.group());
        }
    }

    @Test
    void customNameDoesNotPublishAfterUuidLookupQuarantine() throws Exception {
        try (var context = new TestContext("Bot")) {
            var gate = profileRepository.blockLookup(name -> name.equals("Custom"));
            var future = context.manager().getSpecifiedNameAsync("Custom");

            awaitLookup(gate);
            context.manager().quarantine(new SequenceName("custom", 0, UUID.randomUUID(), "Custom"));
            profileRepository.releaseLookup();

            var failure = assertFutureFailure(future);
            assertTrue(failure.getMessage().contains("quarantined"));
        }
    }

    @Test
    void alreadyQuarantinedNameCannotBeAllocatedAgain() throws Exception {
        try (var context = new TestContext("Bot")) {
            context.manager().quarantine(new SequenceName("Bot", 0, UUID.randomUUID(), "Bot_1"));

            var result = context.manager()
                    .getRegularNameAsync(sender("Creator"))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertEquals("Bot_2", result.name());
            assertNotEquals("Bot_1", result.name());
        }
    }

    private static void awaitLookup(LookupGate gate) throws InterruptedException {
        assertTrue(gate.started().await(WAIT_SECONDS, TimeUnit.SECONDS), "UUID lookup did not start");
    }

    private static Throwable assertFutureFailure(CompletableFuture<?> future) throws Exception {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class,
                () -> future.get(WAIT_SECONDS, TimeUnit.SECONDS)
        );
        return failure.getCause() == null ? failure : failure.getCause();
    }

    private static CommandSender sender(String name) {
        return proxy(CommandSender.class, (method, args) -> {
            if (method.getName().equals("getName")) {
                return name;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static FakeplayerConfig config(String template) {
        var config = allocate(FakeplayerConfig.class);
        setField(config, FakeplayerConfig.class, "nameTemplate", template);
        setField(config, FakeplayerConfig.class, "namePrefix", "");
        setField(config, FakeplayerConfig.class, "playerLimit", 32);
        setField(config, FakeplayerConfig.class, "namePattern", Pattern.compile("^[a-zA-Z0-9_]+$"));
        return config;
    }

    private static Unsafe loadUnsafe() {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            return (T) UNSAFE.allocateInstance(type);
        } catch (InstantiationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setStaticField(Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void setField(Object target, Class<?> declaringType, String name, Object value) {
        try {
            var field = declaringType.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        InvocationHandler invocationHandler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + "TestProxy";
                    default -> null;
                };
            }
            return handler.invoke(method, args == null ? new Object[0] : args);
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, invocationHandler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private record TestContext(NameManager manager, PluginAsyncExecutor executor) implements AutoCloseable {

        private TestContext(String template) {
            this(new NameManager(
                            usedIdRepository,
                            profileRepository,
                            config(template),
                            new PluginAsyncExecutor()
                    ),
                    null);
        }

        private TestContext {
            if (executor == null) {
                executor = findExecutor(manager);
            }
        }

        @Override
        public void close() {
            profileRepository.releaseLookup();
            assertTrue(executor.shutdown(), "plugin async executor did not terminate");
            profileRepository.reset();
            serverState.reset();
        }

        private static PluginAsyncExecutor findExecutor(NameManager manager) {
            try {
                var field = NameManager.class.getDeclaredField("asyncExecutor");
                field.setAccessible(true);
                return (PluginAsyncExecutor) field.get(manager);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class TestProfileRepository extends FakeplayerProfileRepository {
        private static final Map<String, UUID> PROFILES = new ConcurrentHashMap<>();
        private static final Set<String> MALFORMED_NAMES = ConcurrentHashMap.newKeySet();
        private static volatile LookupGate lookupGate;

        private TestProfileRepository() {
            super(null);
        }

        @Override
        public UUID selectUUIDByName(String name) {
            var gate = lookupGate;
            if (gate != null && gate.matches(name) && gate.entered().compareAndSet(false, true)) {
                gate.lookupName(name);
                gate.started().countDown();
                try {
                    gate.release().await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test lookup interrupted", interruption);
                }
            }
            if (MALFORMED_NAMES.contains(name)) {
                throw new MalformedProfileException(name, "not-a-uuid", new IllegalArgumentException("test malformed uuid"));
            }
            return PROFILES.get(name);
        }

        @Override
        public boolean existsByUUID(UUID uuid) {
            return PROFILES.containsValue(uuid);
        }

        @Override
        public void insert(String name, UUID uuid) {
            PROFILES.put(name, uuid);
        }

        private void put(String name, UUID uuid) {
            PROFILES.put(name, uuid);
        }

        private LookupGate blockLookup(Predicate<String> predicate) {
            var gate = new LookupGate(predicate);
            lookupGate = gate;
            return gate;
        }

        private void malformedNames(Set<String> names) {
            MALFORMED_NAMES.addAll(names);
        }

        private void releaseLookup() {
            var gate = lookupGate;
            if (gate != null) {
                gate.release().countDown();
            }
        }

        private void reset() {
            releaseLookup();
            lookupGate = null;
            PROFILES.clear();
            MALFORMED_NAMES.clear();
        }
    }

    private static final class TestUsedIdRepository extends UsedIdRepository {
        private static final Set<UUID> IDS = ConcurrentHashMap.newKeySet();

        private TestUsedIdRepository() {
            super();
        }

        @Override
        public boolean contains(UUID uuid) {
            return IDS.contains(uuid);
        }

        @Override
        public boolean removeIfPresent(UUID uuid) {
            return IDS.remove(uuid);
        }

        @Override
        public void add(UUID uuid) {
            IDS.add(uuid);
        }
    }

    private static final class LookupGate {
        private final Predicate<String> predicate;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean entered = new AtomicBoolean();
        private volatile String lookupName;

        private LookupGate(Predicate<String> predicate) {
            this.predicate = predicate;
        }

        private boolean matches(String name) {
            return predicate.test(name);
        }

        private CountDownLatch started() {
            return started;
        }

        private CountDownLatch release() {
            return release;
        }

        private AtomicBoolean entered() {
            return entered;
        }

        private void lookupName(String name) {
            this.lookupName = name;
        }

        private String lookupName() {
            return lookupName;
        }
    }

    private static final class TestServerState {
        private final Map<String, Player> players = new ConcurrentHashMap<>();
        private volatile Set<String> onlineNames = Set.of();
        private final GlobalRegionScheduler globalScheduler = NameManagerTest.proxy(GlobalRegionScheduler.class, this::invokeScheduler);
        private final AsyncScheduler asyncScheduler = NameManagerTest.proxy(AsyncScheduler.class, this::invokeScheduler);
        private final RegionScheduler regionScheduler = NameManagerTest.proxy(RegionScheduler.class, this::invokeScheduler);

        private Server proxy() {
            return NameManagerTest.proxy(Server.class, this::invokeServer);
        }

        private Object invokeServer(java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "getGlobalRegionScheduler" -> globalScheduler;
                case "getAsyncScheduler" -> asyncScheduler;
                case "getRegionScheduler" -> regionScheduler;
                case "getOnlinePlayers" -> onlineNames.stream().map(this::player).toList();
                case "getPlayerExact" -> args.length == 0 ? null : players.get(args[0]);
                case "getOfflinePlayer" -> offlinePlayer(args[0]);
                case "getName" -> "NameManagerTestServer";
                case "getLogger" -> Logger.getLogger("fakeplayer-name-manager-test-server");
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object invokeScheduler(java.lang.reflect.Method method, Object[] args) {
            if (method.getName().equals("execute")) {
                ((Runnable) args[args.length - 1]).run();
                return null;
            }
            if (method.getName().equals("runNow")
                    || method.getName().equals("run")
                    || method.getName().equals("runDelayed")
                    || method.getName().equals("runAtFixedRate")) {
                var consumer = (java.util.function.Consumer<Object>) args[args.length - 1 - (method.getName().equals("runAtFixedRate") ? 2 : method.getName().equals("runDelayed") ? 1 : 0)];
                consumer.accept(null);
                return null;
            }
            return defaultValue(method.getReturnType());
        }

        private OfflinePlayer offlinePlayer(Object key) {
            if (key instanceof UUID uuid) {
                return offlinePlayer("offline-" + uuid, uuid);
            }
            var name = String.valueOf(key);
            return offlinePlayer(name, UUID.nameUUIDFromBytes(("offline:" + name).getBytes()));
        }

        private OfflinePlayer offlinePlayer(String name, UUID uuid) {
            return NameManagerTest.proxy(OfflinePlayer.class, (method, args) -> switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> name;
                case "hasPlayedBefore" -> false;
                case "isOnline", "isConnected" -> onlineNames.contains(name);
                case "getPlayer" -> players.get(name);
                default -> defaultValue(method.getReturnType());
            });
        }

        private Player player(String name) {
            return players.computeIfAbsent(name, key -> NameManagerTest.proxy(Player.class, (method, args) -> switch (method.getName()) {
                case "getName", "getDisplayName", "getPlayerListName" -> key;
                case "isOnline", "isConnected" -> true;
                case "getUniqueId" -> UUID.nameUUIDFromBytes(("player:" + key).getBytes());
                default -> defaultValue(method.getReturnType());
            }));
        }

        private void reset() {
            onlineNames = Set.of();
            players.clear();
        }
    }
}
