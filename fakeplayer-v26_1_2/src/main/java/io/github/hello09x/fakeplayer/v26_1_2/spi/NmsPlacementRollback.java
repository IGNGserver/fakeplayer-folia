package io.github.hello09x.fakeplayer.v26_1_2.spi;

/**
 * Rollback boundary for failures after {@code PlayerList.placeNewPlayer}.
 *
 * <p>Removal is attempted first through the canonical player-list path. If a
 * partial removal remains, the connection disconnect path gets a chance to
 * finish it. Resource release and the final invariant check always run, so a
 * fault in one cleanup operation cannot leave the synthetic channel or task
 * behind silently.</p>
 */
final class NmsPlacementRollback {

    enum Status {
        CLEAN,
        RESIDUAL
    }

    record Outcome(Status status, Throwable failure) {

        boolean clean() {
            return this.status == Status.CLEAN;
        }
    }

    interface Hooks {
        boolean isRegistered();

        void removeRegisteredPlayer();

        void disconnectRegisteredPlayer();

        void releaseResources();

        boolean isClean();
    }

    private NmsPlacementRollback() {
    }

    static Outcome rollback(Hooks hooks) {
        Throwable failure = null;
        try {
            if (hooks.isRegistered()) {
                hooks.removeRegisteredPlayer();
            }
        } catch (Throwable cleanupFailure) {
            failure = cleanupFailure;
        }

        try {
            if (hooks.isRegistered()) {
                hooks.disconnectRegisteredPlayer();
            }
        } catch (Throwable cleanupFailure) {
            failure = append(failure, cleanupFailure);
        }

        try {
            // A disconnect callback may remove the player itself. If it did
            // not, retry the canonical PlayerList removal exactly once before
            // releasing the synthetic connection.
            if (hooks.isRegistered()) {
                hooks.removeRegisteredPlayer();
            }
        } catch (Throwable cleanupFailure) {
            failure = append(failure, cleanupFailure);
        }

        try {
            hooks.releaseResources();
        } catch (Throwable cleanupFailure) {
            failure = append(failure, cleanupFailure);
        }

        boolean clean;
        try {
            clean = hooks.isClean();
        } catch (Throwable invariantFailure) {
            failure = append(failure, invariantFailure);
            clean = false;
        }
        if (!clean) {
            failure = append(
                    failure,
                    new IllegalStateException("NMS fake-player placement rollback left residual state")
            );
        }

        // A failed first removal is acceptable when the disconnect/retry path
        // proves that the final state is clean. The failure is returned so the
        // placement exception can retain it as suppressed evidence.
        return new Outcome(clean ? Status.CLEAN : Status.RESIDUAL, failure);
    }

    private static Throwable append(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }
}
