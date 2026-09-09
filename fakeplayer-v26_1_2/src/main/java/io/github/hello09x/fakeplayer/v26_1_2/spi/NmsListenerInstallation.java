package io.github.hello09x.fakeplayer.v26_1_2.spi;

/**
 * Installs the fake-player listener as one reversible state transition.
 *
 * <p>The generated listener constructor writes {@code ServerPlayer.connection}
 * before it returns. Capturing both original references before construction
 * lets a constructor failure restore that write as well as failures during
 * either explicit field assignment.</p>
 */
final class NmsListenerInstallation {

    interface State {
        Object playerListener();

        Object connectionListener();

        Object createFakeListener();

        void setPlayerListener(Object listener);

        void setConnectionListener(Object listener);
    }

    private NmsListenerInstallation() {
    }

    static Object install(State state) {
        Object originalPlayerListener = null;
        Object originalConnectionListener = null;
        boolean playerCaptured = false;
        boolean connectionCaptured = false;
        try {
            originalPlayerListener = state.playerListener();
            playerCaptured = true;
            originalConnectionListener = state.connectionListener();
            connectionCaptured = true;

            var fakeListener = state.createFakeListener();
            state.setPlayerListener(fakeListener);
            state.setConnectionListener(fakeListener);
            if (state.playerListener() != fakeListener
                    || state.connectionListener() != fakeListener) {
                throw new IllegalStateException(
                        "Fake-player NMS listener installation was not visible atomically"
                );
            }
            return fakeListener;
        } catch (Throwable failure) {
            if (connectionCaptured) {
                try {
                    state.setConnectionListener(originalConnectionListener);
                } catch (Throwable restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            if (playerCaptured) {
                try {
                    state.setPlayerListener(originalPlayerListener);
                } catch (Throwable restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw NmsAccess.rethrow(failure);
        }
    }
}
