package io.github.hello09x.fakeplayer.v26_1_2.spi;

import io.github.hello09x.fakeplayer.api.spi.Action;
import io.github.hello09x.fakeplayer.api.spi.ActionType;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * NMS action implementation without a compile-time server dependency.
 *
 * <p>The operations intentionally mirror the 1.21 implementation: the ray
 * trace is performed by the server, block breaking goes through
 * ServerPlayerGameMode, and item/entity interactions go through the normal
 * server interaction methods. Only the names are resolved at runtime.</p>
 */
final class ReflectiveAction implements Action {

    private final Object player;
    private final ActionType type;
    private final Current mine = new Current();
    private int useFreeze;

    ReflectiveAction(@NotNull org.bukkit.entity.Player player, @NotNull ActionType type) {
        this.player = NmsAccess.handle(player);
        this.type = type;
    }

    @Override
    public boolean tick() {
        return switch (type) {
            case ATTACK -> attack();
            case MINE -> mine();
            case USE -> use();
            default -> false;
        };
    }

    @Override
    public void inactiveTick() {
        if (type == ActionType.MINE) {
            stopMine();
        }
    }

    @Override
    public void stop() {
        if (type == ActionType.MINE) {
            stopMine();
        } else if (type == ActionType.USE) {
            useFreeze = 0;
            NmsAccess.invokeOptional(player, "releaseUsingItem");
        }
    }

    private boolean attack() {
        Object hit = trace();
        if (hit == null || !hitType(hit, "ENTITY")) {
            return false;
        }

        Object entity = NmsAccess.invoke(hit, "getEntity");
        NmsAccess.invoke(player, "attack", entity);
        NmsAccess.invoke(player, "swing", hand("MAIN_HAND"));
        NmsAccess.invokeOptional(player, "resetAttackStrengthTicker");
        NmsAccess.invoke(player, "resetLastActionTime");
        return true;
    }

    private boolean mine() {
        Object hit = trace();
        if (hit == null || !hitType(hit, "BLOCK")) {
            return false;
        }

        if (mine.freeze > 0) {
            mine.freeze--;
            return false;
        }

        Object blockPos = NmsAccess.invoke(hit, "getBlockPos");
        Object direction = NmsAccess.invoke(hit, "getDirection");
        Object level = NmsAccess.invoke(player, "level");
        Object gameMode = NmsAccess.getField(player, "gameMode");
        Object gameType = NmsAccess.invoke(gameMode, "getGameModeForPlayer");

        if (NmsAccess.boolOrFalse(NmsAccess.invokeOptional(player, "blockActionRestricted", level, blockPos, gameType))) {
            return false;
        }

        if (mine.pos != null && NmsAccess.bool(NmsAccess.invoke(
                NmsAccess.invoke(level, "getBlockState", mine.pos), "isAir"))) {
            mine.clear();
            return false;
        }

        Object state = NmsAccess.invoke(level, "getBlockState", blockPos);
        boolean broken = false;
        if (NmsAccess.bool(NmsAccess.invoke(gameMode, "isCreative"))) {
            breakBlock(gameMode, blockPos, "START_DESTROY_BLOCK", direction, level);
            mine.freeze = 5;
            broken = true;
        } else if (mine.pos == null || !Objects.equals(mine.pos, blockPos)) {
            if (mine.pos != null) {
                breakBlock(gameMode, mine.pos, "ABORT_DESTROY_BLOCK", direction, level);
            }

            breakBlock(gameMode, blockPos, "START_DESTROY_BLOCK", direction, level);
            if (!isAir(state) && mine.progress == 0) {
                NmsAccess.invokeOptional(state, "attack", level, blockPos, player);
            }

            if (!isAir(state) && destroyProgress(state, level, blockPos) >= 1.0f) {
                mine.clear();
                broken = true;
            } else {
                mine.pos = blockPos;
                mine.progress = 0;
            }
        } else {
            mine.progress += destroyProgress(state, level, blockPos);
            if (mine.progress >= 1.0f) {
                breakBlock(gameMode, blockPos, "STOP_DESTROY_BLOCK", direction, level);
                mine.clear();
                mine.freeze = 5;
                broken = true;
            }
            NmsAccess.invokeOptional(level, "destroyBlockProgress", -1, blockPos, (int) (mine.progress * 10));
        }

        NmsAccess.invoke(player, "resetLastActionTime");
        NmsAccess.invoke(player, "swing", hand("MAIN_HAND"));
        return broken;
    }

    private boolean use() {
        if (useFreeze > 0) {
            useFreeze--;
            return false;
        }
        if (NmsAccess.boolOrFalse(NmsAccess.invokeOptional(player, "isUsingItem"))) {
            return true;
        }

        Object hit = trace();
        if (hit == null) {
            return false;
        }

        Object level = NmsAccess.invoke(player, "level");
        Object gameMode = NmsAccess.getField(player, "gameMode");
        for (String handName : new String[]{"MAIN_HAND", "OFF_HAND"}) {
            Object hand = hand(handName);
            if (hitType(hit, "BLOCK")) {
                Object pos = NmsAccess.invoke(hit, "getBlockPos");
                Object side = NmsAccess.invoke(hit, "getDirection");
                int maxY = NmsAccess.integer(NmsAccess.invoke(level, "getMaxY"));
                int y = NmsAccess.integer(NmsAccess.invoke(pos, "getY"));
                if (y < maxY - ("UP".equals(((Enum<?>) side).name()) ? 1 : 0)
                        && mayInteract(level, pos)) {
                    Object item = NmsAccess.invoke(player, "getItemInHand", hand);
                    Object result = NmsAccess.invoke(
                            gameMode,
                            "useItemOn",
                            player,
                            level,
                            item,
                            hand,
                            hit
                    );
                    if (consumes(result)) {
                        NmsAccess.invoke(player, "swing", hand);
                        useFreeze = 3;
                        return true;
                    }
                }
            } else if (hitType(hit, "ENTITY")) {
                Object entity = NmsAccess.invoke(hit, "getEntity");
                Object location = NmsAccess.invoke(hit, "getLocation");
                Object held = NmsAccess.invoke(player, "getItemInHand", hand);
                boolean handWasEmpty = NmsAccess.boolOrFalse(NmsAccess.invokeOptional(held, "isEmpty"));
                boolean itemFrameEmpty = entity.getClass().getName().endsWith("ItemFrame")
                        && NmsAccess.boolOrFalse(NmsAccess.invokeOptional(
                        NmsAccess.invokeOptional(entity, "getItem"), "isEmpty"));
                Object relative = vec3(
                        NmsAccess.decimal(NmsAccess.component(location, "x")) - NmsAccess.decimal(NmsAccess.invoke(entity, "getX")),
                        NmsAccess.decimal(NmsAccess.component(location, "y")) - NmsAccess.decimal(NmsAccess.invoke(entity, "getY")),
                        NmsAccess.decimal(NmsAccess.component(location, "z")) - NmsAccess.decimal(NmsAccess.invoke(entity, "getZ"))
                );
                NmsAccess.invoke(player, "resetLastActionTime");
                Object result = NmsAccess.invokeOptional(entity, "interactAt", player, relative, hand);
                if (consumes(result)) {
                    useFreeze = 3;
                    return true;
                }
                result = NmsAccess.invokeOptional(player, "interactOn", entity, hand);
                if (result == null) {
                    // Keep compatibility with transitional 26.x mappings that
                    // may expose the hit position on this overload.
                    result = NmsAccess.invokeOptional(player, "interactOn", entity, hand, relative);
                }
                if (consumes(result) && !(handWasEmpty && itemFrameEmpty)) {
                    useFreeze = 3;
                    return true;
                }
            }

            Object item = NmsAccess.invoke(player, "getItemInHand", hand);
            Object result = NmsAccess.invoke(gameMode, "useItem", player, level, item, hand);
            if (consumes(result)) {
                NmsAccess.invoke(player, "resetLastActionTime");
                useFreeze = 3;
                return true;
            }
        }
        return false;
    }

    private Object trace() {
        Object level = NmsAccess.invoke(player, "level");
        Object eye = NmsAccess.invoke(player, "getEyePosition", 1.0f);
        Object view = NmsAccess.invoke(player, "getViewVector", 1.0f);
        double reach = NmsAccess.boolOrFalse(NmsAccess.invokeOptional(
                NmsAccess.getField(player, "gameMode"), "isCreative"
        )) ? 5.0 : 4.5;
        Object end = vec3(
                NmsAccess.decimal(NmsAccess.component(eye, "x")) + NmsAccess.decimal(NmsAccess.component(view, "x")) * reach,
                NmsAccess.decimal(NmsAccess.component(eye, "y")) + NmsAccess.decimal(NmsAccess.component(view, "y")) * reach,
                NmsAccess.decimal(NmsAccess.component(eye, "z")) + NmsAccess.decimal(NmsAccess.component(view, "z")) * reach
        );
        Object clip = NmsAccess.newInstance(
                "net.minecraft.world.level.ClipContext",
                eye,
                end,
                NmsAccess.enumValue("net.minecraft.world.level.ClipContext$Block", "OUTLINE"),
                NmsAccess.enumValue("net.minecraft.world.level.ClipContext$Fluid", "NONE"),
                player
        );
        Object blockHit = NmsAccess.invoke(level, "clip", clip);
        double maxDistance = reach * reach;
        if (blockHit != null && hitType(blockHit, "BLOCK")) {
            Object hitLocation = NmsAccess.invoke(blockHit, "getLocation");
            maxDistance = distanceSquared(hitLocation, eye);
        }

        Object reachVector = NmsAccess.invoke(view, "scale", reach);
        Object searchBox = NmsAccess.invoke(
                NmsAccess.invoke(player, "getBoundingBox"),
                "expandTowards",
                reachVector
        );
        searchBox = NmsAccess.invoke(searchBox, "inflate", 1.0);

        Predicate<Object> pickable = entity ->
                !NmsAccess.boolOrFalse(NmsAccess.invokeOptional(entity, "isSpectator"))
                        && NmsAccess.boolOrFalse(NmsAccess.invokeOptional(entity, "isPickable"));
        Object entities = NmsAccess.invokeOptional(level, "getEntities", player, searchBox, pickable);
        Object entityHit = null;
        if (entities instanceof Iterable<?> iterable) {
            double targetDistance = maxDistance;
            Object target = null;
            Object targetHitPosition = null;
            Object sourceRoot = NmsAccess.invoke(player, "getRootVehicle");
            for (Object current : iterable) {
                Object currentBox = NmsAccess.invoke(current, "getBoundingBox");
                Object pickRadius = NmsAccess.invokeOptional(current, "getPickRadius");
                if (pickRadius != null) {
                    currentBox = NmsAccess.invoke(currentBox, "inflate", NmsAccess.decimal(pickRadius));
                }

                Object currentHit = NmsAccess.invokeOptional(currentBox, "clip", eye, end);
                boolean containsEye = NmsAccess.boolOrFalse(NmsAccess.invokeOptional(currentBox, "contains", eye));
                if (containsEye) {
                    target = current;
                    targetHitPosition = eye;
                    targetDistance = 0;
                    continue;
                }
                if (!(currentHit instanceof Optional<?> optional) || optional.isEmpty()) {
                    continue;
                }

                Object hitPosition = optional.get();
                double currentDistance = distanceSquared(eye, hitPosition);
                if (currentDistance >= targetDistance && targetDistance != 0) {
                    continue;
                }

                Object currentRoot = NmsAccess.invoke(current, "getRootVehicle");
                if (currentRoot == sourceRoot) {
                    if (targetDistance == 0) {
                        target = current;
                        targetHitPosition = hitPosition;
                    }
                    continue;
                }
                target = current;
                targetHitPosition = hitPosition;
                targetDistance = currentDistance;
            }
            if (target != null) {
                entityHit = NmsAccess.newInstance(
                        "net.minecraft.world.phys.EntityHitResult",
                        target,
                        targetHitPosition
                );
            }
        }

        if (entityHit == null) {
            entityHit = bukkitFallback(eye, end, maxDistance, reach);
        }
        return entityHit == null ? blockHit : entityHit;
    }

    private Object bukkitFallback(Object eye, Object end, double maxDistance, double reach) {
        org.bukkit.entity.Entity target = ((org.bukkit.entity.Player) NmsAccess.invoke(player, "getBukkitEntity"))
                .getTargetEntity((int) Math.ceil(reach));
        if (target == null) {
            return null;
        }

        Object handle = NmsAccess.handle(target);
        Object box = NmsAccess.invoke(handle, "getBoundingBox");
        Object pickRadius = NmsAccess.invokeOptional(handle, "getPickRadius");
        if (pickRadius != null) {
            box = NmsAccess.invoke(box, "inflate", NmsAccess.decimal(pickRadius));
        }
        Object hit = NmsAccess.invokeOptional(box, "clip", eye, end);
        Object position = eye;
        if (hit instanceof Optional<?> optional && optional.isPresent()) {
            position = optional.get();
        }
        if (distanceSquared(eye, position) > maxDistance && maxDistance != 0) {
            return null;
        }
        return NmsAccess.newInstance(
                "net.minecraft.world.phys.EntityHitResult",
                handle,
                position
        );
    }

    private void breakBlock(Object gameMode, Object pos, String action, Object direction, Object level) {
        Object packetAction = NmsAccess.enumValue(
                "net.minecraft.network.protocol.game.ServerboundPlayerActionPacket$Action",
                action
        );
        NmsAccess.invoke(
                gameMode,
                "handleBlockBreakAction",
                pos,
                packetAction,
                direction,
                NmsAccess.integer(NmsAccess.invoke(level, "getMaxY")),
                -1
        );
    }

    private float destroyProgress(Object state, Object level, Object pos) {
        return NmsAccess.floating(NmsAccess.invoke(state, "getDestroyProgress", player, level, pos));
    }

    private boolean isAir(Object state) {
        return NmsAccess.bool(NmsAccess.invoke(state, "isAir"));
    }

    private boolean mayInteract(Object level, Object pos) {
        Object result = NmsAccess.invokeOptional(player, "mayInteract", level, pos);
        return result == null || NmsAccess.bool(result);
    }

    private static boolean hitType(Object hit, String type) {
        Object hitType = NmsAccess.invoke(hit, "getType");
        return hitType instanceof Enum<?> value && value.name().equals(type);
    }

    private static boolean consumes(Object result) {
        return result != null && NmsAccess.boolOrFalse(NmsAccess.invokeOptional(result, "consumesAction"));
    }

    private static Object hand(String name) {
        return NmsAccess.enumValue("net.minecraft.world.InteractionHand", name);
    }

    private static Object vec3(double x, double y, double z) {
        return NmsAccess.newInstance("net.minecraft.world.phys.Vec3", x, y, z);
    }

    private static double distanceSquared(Object left, Object right) {
        double x = NmsAccess.decimal(NmsAccess.component(left, "x")) - NmsAccess.decimal(NmsAccess.component(right, "x"));
        double y = NmsAccess.decimal(NmsAccess.component(left, "y")) - NmsAccess.decimal(NmsAccess.component(right, "y"));
        double z = NmsAccess.decimal(NmsAccess.component(left, "z")) - NmsAccess.decimal(NmsAccess.component(right, "z"));
        return x * x + y * y + z * z;
    }

    private void stopMine() {
        if (mine.pos == null) {
            return;
        }
        Object level = NmsAccess.invoke(player, "level");
        NmsAccess.invokeOptional(level, "destroyBlockProgress", -1, mine.pos, -1);
        breakBlock(
                NmsAccess.getField(player, "gameMode"),
                mine.pos,
                "ABORT_DESTROY_BLOCK",
                NmsAccess.enumValue("net.minecraft.core.Direction", "DOWN"),
                level
        );
        mine.clear();
    }

    private static final class Current {
        private Object pos;
        private float progress;
        private int freeze;

        private void clear() {
            pos = null;
            progress = 0;
            freeze = 0;
        }
    }
}
