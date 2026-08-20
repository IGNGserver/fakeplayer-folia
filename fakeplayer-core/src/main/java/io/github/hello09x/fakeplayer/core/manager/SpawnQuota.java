package io.github.hello09x.fakeplayer.core.manager;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Atomic reservations for fake-player spawn limits.
 *
 * <p>Spawn construction is asynchronous on Folia. Checking the current list
 * and adding the player later leaves a TOCTOU window in which many commands can
 * all pass the same limit. This class counts in-flight reservations together
 * with the live counts supplied by {@link FakeplayerList} and serializes both
 * reservation and commit.</p>
 */
public final class SpawnQuota {

    private final Object lock = new Object();
    private final Map<String, Integer> pendingByCreator = new HashMap<>();
    private final Map<String, Integer> pendingByAddress = new HashMap<>();
    private final Set<Reservation> activeReservations = Collections.newSetFromMap(new IdentityHashMap<>());
    private int pendingTotal;
    private boolean closed;

    public @NotNull Reservation reserve(
            @NotNull String creator,
            @NotNull String address,
            boolean detectIp,
            int playerLimit,
            int serverLimit,
            int liveTotal,
            int liveByCreator,
            long liveByAddress,
            boolean bypassLimits
    ) throws LimitExceededException {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Spawn quota is closed");
            }
            if (bypassLimits) {
                var reservation = new Reservation(this, creator, address, detectIp, false);
                activeReservations.add(reservation);
                return reservation;
            }

            if ((long) liveTotal + pendingTotal >= serverLimit) {
                throw new LimitExceededException(Limit.SERVER);
            }

            var creatorPending = pendingByCreator.getOrDefault(creator, 0);
            if ((long) liveByCreator + creatorPending >= playerLimit) {
                throw new LimitExceededException(Limit.PLAYER);
            }

            var addressPending = pendingByAddress.getOrDefault(address, 0);
            if (detectIp && liveByAddress + addressPending >= playerLimit) {
                throw new LimitExceededException(Limit.IP);
            }

            pendingTotal++;
            pendingByCreator.merge(creator, 1, Integer::sum);
            if (detectIp) {
                pendingByAddress.merge(address, 1, Integer::sum);
            }
            var reservation = new Reservation(this, creator, address, detectIp, true);
            activeReservations.add(reservation);
            return reservation;
        }
    }

    /**
     * Atomically publishes the newly-created fake player and consumes its
     * reservation. The callback must only mutate the live player registry.
     */
    public boolean commit(@NotNull Reservation reservation, @NotNull BooleanSupplier publish) {
        synchronized (lock) {
            if (!reservation.active) {
                return false;
            }
            boolean published;
            try {
                published = publish.getAsBoolean();
            } catch (RuntimeException | Error failure) {
                reservation.releaseLocked();
                throw failure;
            }
            reservation.releaseLocked();
            reservation.active = false;
            return published;
        }
    }

    public void clear() {
        synchronized (lock) {
            closed = true;
            activeReservations.forEach(reservation -> reservation.active = false);
            activeReservations.clear();
            pendingTotal = 0;
            pendingByCreator.clear();
            pendingByAddress.clear();
        }
    }

    private void release(@NotNull Reservation reservation) {
        synchronized (lock) {
            reservation.releaseLocked();
        }
    }

    private void decrement(@NotNull Map<String, Integer> counts, @NotNull String key) {
        counts.computeIfPresent(key, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    public enum Limit {
        SERVER,
        PLAYER,
        IP
    }

    public static final class LimitExceededException extends Exception {
        private final Limit limit;

        public LimitExceededException(@NotNull Limit limit) {
            super(limit.name());
            this.limit = limit;
        }

        public @NotNull Limit limit() {
            return limit;
        }
    }

    public static final class Reservation implements AutoCloseable {
        private final SpawnQuota owner;
        private final String creator;
        private final String address;
        private final boolean detectIp;
        private final boolean counted;
        private boolean active = true;

        private Reservation(
                @NotNull SpawnQuota owner,
                @NotNull String creator,
                @NotNull String address,
                boolean detectIp,
                boolean counted
        ) {
            this.owner = owner;
            this.creator = creator;
            this.address = address;
            this.detectIp = detectIp;
            this.counted = counted;
        }

        private void releaseLocked() {
            if (!active) {
                return;
            }
            if (counted) {
                owner.pendingTotal--;
                owner.decrement(owner.pendingByCreator, creator);
                if (detectIp) {
                    owner.decrement(owner.pendingByAddress, address);
                }
            }
            active = false;
            owner.activeReservations.remove(this);
        }

        @Override
        public void close() {
            owner.release(this);
        }
    }
}
