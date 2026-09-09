package io.github.hello09x.fakeplayer.v26_1_2.spi;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.invoke.MethodHandles;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small, deliberately isolated reflection layer for the 26.x server.
 *
 * <p>Minecraft 26 no longer exposes the old Spigot-remapped server artifacts
 * that the older modules compile against. Keeping every NMS reference behind
 * this class lets the module compile against the public Paper API while using
 * the Mojang-named runtime classes supplied by Paper/Folia 26.x.</p>
 */
final class NmsAccess {

    private static final String SERVER_GAME_PACKET_LISTENER =
            "net.minecraft.server.network.ServerGamePacketListenerImpl";
    private static final String FAKE_PLAYER_PACKET_LISTENER =
            "net.minecraft.server.network.FakeplayerServerGamePacketListenerImpl_IGNG";
    private static final Map<Class<?>, Class<?>> GENERATED_PACKET_LISTENERS = new ConcurrentHashMap<>();

    private NmsAccess() {
    }

    static Object handle(@NotNull Object bukkitObject) {
        try {
            return invoke(bukkitObject, "getHandle");
        } catch (RuntimeException ignored) {
            // This makes the helper useful for callers that already hold an NMS
            // object, while still failing clearly for an unrelated Bukkit type.
            if (!bukkitObject.getClass().getName().startsWith("org.bukkit.")) {
                return bukkitObject;
            }
            throw ignored;
        }
    }

    /**
     * CraftServer.getHandle() is the player list on recent Folia builds,
     * whereas the NMS operations used here require the MinecraftServer
     * instance. Prefer CraftServer.getServer() and retain the old fallback for
     * server implementations that do not expose that accessor.
     */
    static Object serverHandle(@NotNull Object server) {
        try {
            return invoke(server, "getServer");
        } catch (RuntimeException ignored) {
            return handle(server);
        }
    }

    static Object invoke(@NotNull Object target, @NotNull String name, Object... args) {
        return invoke(target.getClass(), target, name, args);
    }

    static Object invokeStatic(@NotNull String className, @NotNull String name, Object... args) {
        return invoke(classForName(className), null, name, args);
    }

    static Object invokeOptional(Object target, @NotNull String name, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            return invoke(target, name, args);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static boolean hasDeclaredCompatibleMethod(@NotNull Object target, @NotNull String name, Object... args) {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (!method.isBridge()
                    && method.getName().equals(name)
                    && compatible(method.getParameterTypes(), args)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read a Mojang value that may be represented as a public record-style
     * field (for example {@code Vec3.x}) or as an accessor method in a later
     * mapping. The 26.x server uses both shapes in different packet/value
     * classes, so callers should not assume one representation.
     */
    static Object component(@NotNull Object target, @NotNull String name) {
        try {
            return getField(target, name);
        } catch (RuntimeException ignored) {
            return invoke(target, name);
        }
    }

    static Object newInstance(@NotNull String className, Object... args) {
        Class<?> type = classForName(className);
        Constructor<?> constructor = findConstructor(type, args);
        try {
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance(args);
        } catch (Throwable e) {
            throw failure("construct " + className, e);
        }
    }

    static Object enumValue(@NotNull String className, @NotNull String name) {
        Object[] values = classForName(className).getEnumConstants();
        if (values != null) {
            for (Object value : values) {
                if (((Enum<?>) value).name().equals(name)) {
                    return value;
                }
            }
        }
        throw new IllegalArgumentException("Unknown " + className + " constant " + name);
    }

    static Object getField(@NotNull Object target, @NotNull String name) {
        try {
            Field field = findField(target.getClass(), name);
            if (!field.canAccess(target)) {
                field.setAccessible(true);
            }
            return field.get(target);
        } catch (Throwable e) {
            throw failure("read " + target.getClass().getName() + "." + name, e);
        }
    }

    static Object getFieldOptional(Object target, @NotNull String name) {
        try {
            return getField(target, name);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static Object getStaticField(@NotNull String className, @NotNull String name) {
        try {
            Field field = findField(classForName(className), name);
            if (!field.canAccess(null)) {
                field.setAccessible(true);
            }
            return field.get(null);
        } catch (Throwable e) {
            throw failure("read " + className + "." + name, e);
        }
    }

    static void setField(@NotNull Object target, @NotNull String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            if (!field.canAccess(target)) {
                field.setAccessible(true);
            }
            field.set(target, value);
        } catch (Throwable e) {
            throw failure("write " + target.getClass().getName() + "." + name, e);
        }
    }

    static void setFieldIfPresent(Object target, @NotNull String name, Object value) {
        try {
            setField(target, name, value);
        } catch (RuntimeException ignored) {
        }
    }

    static void cleanupAdvancementSink(Object playerHandle) {
        try {
            Object advancements = invokeOptional(playerHandle, "getAdvancements");
            Object path = getFieldOptional(advancements, "playerSavePath");
            if (path instanceof java.nio.file.Path sink
                    && sink.getFileName() != null
                    && sink.getFileName().toString().startsWith(".fakeplayer-advancements-")) {
                java.nio.file.Files.deleteIfExists(sink);
            }
        } catch (Throwable ignored) {
            // Cleanup must never interfere with disconnecting the fake player.
        }
    }

    static Class<?> classForName(@NotNull String name) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            try {
                return Class.forName(name, true, context);
            } catch (ClassNotFoundException ignored) {
            }
        }

        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Minecraft 26.x class is unavailable: " + name, e);
        }
    }

    /** Fail during bridge selection instead of failing on the first spawn. */
    static void requireMethod(@NotNull String className, @NotNull String name, int parameterCount) {
        if (allMethods(classForName(className)).noneMatch(method -> method.getName().equals(name)
                && method.getParameterCount() == parameterCount)) {
            throw new IllegalStateException("Minecraft 26.x method is unavailable: " + className + "."
                    + name + "(" + parameterCount + " args)");
        }
    }

    /** Fail during bridge selection when a required reflective field moved. */
    static void requireField(@NotNull String className, @NotNull String name) {
        try {
            findField(classForName(className), name);
        } catch (NoSuchFieldException missing) {
            throw new IllegalStateException("Minecraft 26.x field is unavailable: " + className + "." + name, missing);
        }
    }

    /** Validate constructor arity used by the adapter without instantiating NMS objects. */
    static void requireConstructor(@NotNull String className, int parameterCount) {
        var constructors = classForName(className).getDeclaredConstructors();
        for (var constructor : constructors) {
            if (constructor.getParameterCount() == parameterCount) {
                return;
            }
        }
        throw new IllegalStateException("Minecraft 26.x constructor is unavailable: " + className + "(" + parameterCount + " args)");
    }

    /**
     * Validate and materialise the real NMS listener used by fake players.
     *
     * <p>{@code Connection#tick()} only applies its listener tick contract to
     * a real {@code ServerCommonPacketListenerImpl}. A JDK proxy or a marker
     * object can therefore never be a safe replacement: it would either be
     * skipped by the connection or retain the normal keep-alive and player
     * tick behaviour. The generated class is a minimal concrete subclass so
     * the rest of the vanilla listener API remains available to kick and
     * packet handling code.</p>
     */
    static void verifyFakePlayerPacketListener() {
        Class<?> listener = fakePlayerPacketListenerClass();
        Class<?> base = classForName(SERVER_GAME_PACKET_LISTENER);
        if (listener.getSuperclass() != base || !base.isAssignableFrom(listener)) {
            throw new IllegalStateException("Generated fake-player listener does not extend " + base.getName());
        }

        try {
            Constructor<?> constructor = listener.getDeclaredConstructor(
                    classForName("net.minecraft.server.MinecraftServer"),
                    classForName("net.minecraft.network.Connection"),
                    classForName("net.minecraft.server.level.ServerPlayer"),
                    classForName("net.minecraft.server.network.CommonListenerCookie")
            );
            Method tick = listener.getDeclaredMethod("tick");
            Method hasClientLoaded = listener.getDeclaredMethod("hasClientLoaded");
            if (!Modifier.isPublic(constructor.getModifiers())
                    || tick.getReturnType() != void.class
                    || tick.getParameterCount() != 0
                    || hasClientLoaded.getReturnType() != boolean.class
                    || hasClientLoaded.getParameterCount() != 0) {
                throw new IllegalStateException("Generated fake-player listener has an invalid NMS contract");
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Generated fake-player listener is not constructible", failure);
        }
    }

    /** Construct a listener with the exact server-runtime constructor signature. */
    static Object newFakePlayerPacketListener(
            @NotNull Object server,
            @NotNull Object connection,
            @NotNull Object player,
            @NotNull Object cookie
    ) {
        Class<?> listener = fakePlayerPacketListenerClass();
        try {
            Constructor<?> constructor = listener.getDeclaredConstructor(
                    classForName("net.minecraft.server.MinecraftServer"),
                    classForName("net.minecraft.network.Connection"),
                    classForName("net.minecraft.server.level.ServerPlayer"),
                    classForName("net.minecraft.server.network.CommonListenerCookie")
            );
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance(server, connection, player, cookie);
        } catch (Throwable cause) {
            throw failure("construct " + listener.getName(), cause);
        }
    }

    private static Class<?> fakePlayerPacketListenerClass() {
        Class<?> base = classForName(SERVER_GAME_PACKET_LISTENER);
        return GENERATED_PACKET_LISTENERS.computeIfAbsent(base, NmsAccess::defineFakePlayerPacketListener);
    }

    private static Class<?> defineFakePlayerPacketListener(Class<?> base) {
        try {
            // A server reload can leave the generated class in the server class
            // loader while this plugin class is reloaded. Reuse it if present.
            return Class.forName(FAKE_PLAYER_PACKET_LISTENER, false, base.getClassLoader());
        } catch (ClassNotFoundException ignored) {
            try {
                var lookup = MethodHandles.privateLookupIn(base, MethodHandles.lookup());
                return lookup.defineClass(fakePlayerPacketListenerBytecode());
            } catch (Throwable failure) {
                throw new IllegalStateException(
                        "Cannot define the 26.x fake-player ServerGamePacketListenerImpl subclass",
                        failure
                );
            }
        }
    }

    /**
     * Emit a Java 17 class file so the tiny adapter stays independent of the
     * server's bundled ASM version and of the plugin's optional dependencies.
     */
    private static byte[] fakePlayerPacketListenerBytecode() {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);       // minor version
            out.writeShort(61);      // Java 17; accepted by Java 21+ servers
            out.writeShort(14);      // constant-pool count

            utf8(out, "net/minecraft/server/network/FakeplayerServerGamePacketListenerImpl_IGNG"); // #1
            out.writeByte(7);
            out.writeShort(1);       // #2: this class
            utf8(out, "net/minecraft/server/network/ServerGamePacketListenerImpl"); // #3
            out.writeByte(7);
            out.writeShort(3);       // #4: super class
            utf8(out, "<init>");     // #5
            utf8(out, "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V"); // #6
            out.writeByte(12);
            out.writeShort(5);
            out.writeShort(6);       // #7: super constructor name/type
            out.writeByte(10);
            out.writeShort(4);
            out.writeShort(7);       // #8: super constructor method
            utf8(out, "Code");      // #9
            utf8(out, "tick");      // #10
            utf8(out, "()V");       // #11
            utf8(out, "hasClientLoaded"); // #12
            utf8(out, "()Z");       // #13

            out.writeShort(0x0021);  // public + ACC_SUPER
            out.writeShort(2);       // this_class
            out.writeShort(4);       // super_class
            out.writeShort(0);       // interfaces
            out.writeShort(0);       // fields
            out.writeShort(3);       // methods

            writeCodeMethod(out, 0x0001, 5, 6, 5, 5,
                    new byte[]{0x2A, 0x2B, 0x2C, 0x2D, 0x19, 0x04, (byte) 0xB7, 0x00, 0x08, (byte) 0xB1});
            writeCodeMethod(out, 0x0001, 10, 11, 0, 1, new byte[]{(byte) 0xB1});
            writeCodeMethod(out, 0x0001, 12, 13, 1, 1, new byte[]{0x04, (byte) 0xAC});

            out.writeShort(0);       // class attributes
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot build fake-player listener bytecode", failure);
        }
    }

    private static void utf8(DataOutputStream out, String value) throws IOException {
        out.writeByte(1);
        out.writeUTF(value);
    }

    private static void writeCodeMethod(
            DataOutputStream out,
            int access,
            int name,
            int descriptor,
            int maxStack,
            int maxLocals,
            byte[] code
    ) throws IOException {
        out.writeShort(access);
        out.writeShort(name);
        out.writeShort(descriptor);
        out.writeShort(1);           // Code attribute
        out.writeShort(9);
        out.writeInt(2 + 2 + 4 + code.length + 2 + 2);
        out.writeShort(maxStack);
        out.writeShort(maxLocals);
        out.writeInt(code.length);
        out.write(code);
        out.writeShort(0);           // exception table
        out.writeShort(0);           // nested attributes
    }

    static boolean bool(Object value) {
        return value instanceof Boolean b && b;
    }

    static boolean boolOrFalse(Object value) {
        return value instanceof Boolean b && b;
    }

    static int integer(Object value) {
        return ((Number) value).intValue();
    }

    static float floating(Object value) {
        return ((Number) value).floatValue();
    }

    static double decimal(Object value) {
        return ((Number) value).doubleValue();
    }

    static RuntimeException rethrow(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause);
    }

    private static Object invoke(Class<?> type, Object target, String name, Object[] args) {
        Method method = findMethod(type, name, args);
        try {
            if (!method.canAccess(target)) {
                method.setAccessible(true);
            }
            return method.invoke(target, args);
        } catch (Throwable e) {
            throw failure("invoke " + type.getName() + "." + name, e);
        }
    }

    private static Method findMethod(Class<?> type, String name, Object[] args) {
        return allMethods(type)
                .filter(method -> method.getName().equals(name))
                .filter(method -> !method.isBridge())
                .filter(method -> compatible(method.getParameterTypes(), args))
                .min(Comparator.comparingInt(method -> score(method.getParameterTypes(), args)))
                .orElseThrow(() -> new IllegalStateException(
                        "No compatible method " + type.getName() + "." + name + "(" + args.length + " args)"
                ));
    }

    private static Constructor<?> findConstructor(Class<?> type, Object[] args) {
        Constructor<?> constructor = java.util.Arrays.stream(type.getDeclaredConstructors())
                .filter(candidate -> compatible(candidate.getParameterTypes(), args))
                .min(Comparator.comparingInt(candidate -> score(candidate.getParameterTypes(), args)))
                .orElse(null);
        if (constructor != null) {
            return constructor;
        }
        var actualTypes = java.util.Arrays.stream(args)
                .map(arg -> arg == null ? "null" : arg.getClass().getName())
                .collect(java.util.stream.Collectors.joining(", "));
        var available = java.util.Arrays.stream(type.getDeclaredConstructors())
                .map(Constructor::toGenericString)
                .collect(java.util.stream.Collectors.joining("; "));
        throw new IllegalStateException(
                "No compatible constructor for " + type.getName() + "(" + args.length + " args); "
                        + "actual types: [" + actualTypes + "]; available: [" + available + "]"
        );
    }

    private static java.util.stream.Stream<Method> allMethods(Class<?> type) {
        java.util.List<Method> methods = new java.util.ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            java.util.Collections.addAll(methods, current.getDeclaredMethods());
        }
        for (Class<?> iface : type.getInterfaces()) {
            java.util.Collections.addAll(methods, iface.getMethods());
        }
        return methods.stream();
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean compatible(Class<?>[] parameterTypes, Object[] args) {
        if (parameterTypes.length != args.length) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (!compatible(parameterTypes[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatible(Class<?> parameterType, Object arg) {
        if (arg == null) {
            return !parameterType.isPrimitive();
        }
        if (parameterType.isPrimitive()) {
            parameterType = wrap(parameterType);
        }
        return parameterType.isAssignableFrom(arg.getClass());
    }

    private static int score(Class<?>[] parameterTypes, Object[] args) {
        int score = 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameter = parameterTypes[i];
            if (parameter.isPrimitive()) {
                parameter = wrap(parameter);
            }
            if (args[i] == null) {
                score += 20;
            } else if (parameter.equals(args[i].getClass())) {
                score += 0;
            } else if (parameter.isAssignableFrom(args[i].getClass())) {
                score += 1;
            } else {
                score += 100;
            }
        }
        return score;
    }

    private static Class<?> wrap(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == short.class) return Short.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        if (primitive == char.class) return Character.class;
        return primitive;
    }

    private static RuntimeException failure(String operation, Throwable throwable) {
        return new IllegalStateException("Minecraft 26.x NMS operation failed: " + operation, unwrap(throwable));
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return unwrap(invocation.getCause());
        }
        return throwable;
    }
}
