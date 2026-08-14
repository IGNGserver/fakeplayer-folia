package io.github.hello09x.fakeplayer.v26_1_2.spi;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Comparator;

/**
 * Small, deliberately isolated reflection layer for the 26.x server.
 *
 * <p>Minecraft 26 no longer exposes the old Spigot-remapped server artifacts
 * that the older modules compile against. Keeping every NMS reference behind
 * this class lets the module compile against the public Paper API while using
 * the Mojang-named runtime classes supplied by Paper/Folia 26.x.</p>
 */
final class NmsAccess {

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
