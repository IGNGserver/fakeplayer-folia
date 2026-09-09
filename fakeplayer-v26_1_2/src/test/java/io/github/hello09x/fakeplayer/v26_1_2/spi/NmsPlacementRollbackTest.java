package io.github.hello09x.fakeplayer.v26_1_2.spi;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NmsPlacementRollbackTest {

    @Test
    void listenerConstructorFailureRestoresBothNativeReferences() {
        var state = new ListenerState();
        state.constructorFailure = true;

        assertThrows(RuntimeException.class, () -> NmsListenerInstallation.install(state));

        assertSame(state.originalPlayerListener, state.playerListener);
        assertSame(state.originalConnectionListener, state.connectionListener);
        assertPlacementRollbackIsClean(state);
    }

    @Test
    void firstFieldWriteFailureRestoresBothNativeReferences() {
        var state = new ListenerState();
        state.failPlayerWrite = true;

        assertThrows(RuntimeException.class, () -> NmsListenerInstallation.install(state));

        assertSame(state.originalPlayerListener, state.playerListener);
        assertSame(state.originalConnectionListener, state.connectionListener);
        assertPlacementRollbackIsClean(state);
    }

    @Test
    void secondFieldWriteFailureRollsBackTheFirstWrite() {
        var state = new ListenerState();
        state.failConnectionWrite = true;

        assertThrows(RuntimeException.class, () -> NmsListenerInstallation.install(state));

        assertSame(state.originalPlayerListener, state.playerListener);
        assertSame(state.originalConnectionListener, state.connectionListener);
        assertPlacementRollbackIsClean(state);
    }

    @Test
    void drainTaskSchedulingFailureStillRemovesPlayerAndAllResources() {
        var state = new PlacementState();
        state.registered = true;
        state.connectionOpen = true;
        state.drainTask = true;

        var outcome = NmsPlacementRollback.rollback(state);

        assertTrue(outcome.clean());
        assertSame(null, outcome.failure());
        assertFalse(state.registered);
        assertFalse(state.connectionOpen);
        assertFalse(state.drainTask);
        assertFalse(state.halfInstalledListener);
        assertEquals(List.of("remove", "release"), state.calls);
    }

    @Test
    void removalFailureFallsBackToDisconnectBeforeReleasingResources() {
        var state = new PlacementState();
        state.registered = true;
        state.connectionOpen = true;
        state.drainTask = true;
        state.removeFailure = true;

        var outcome = NmsPlacementRollback.rollback(state);

        assertTrue(outcome.clean());
        assertNotNull(outcome.failure());
        assertEquals("PlayerList.remove failed", outcome.failure().getMessage());
        assertFalse(state.registered);
        assertFalse(state.connectionOpen);
        assertFalse(state.drainTask);
        assertFalse(state.halfInstalledListener);
        assertEquals(List.of("remove", "disconnect", "remove", "release"), state.calls);
    }

    @Test
    void persistentRemovalFailureReturnsResidualAndRetainsFailure() {
        var state = new PlacementState();
        state.registered = true;
        state.connectionOpen = true;
        state.failEveryRemove = true;

        var outcome = NmsPlacementRollback.rollback(state);

        assertEquals(NmsPlacementRollback.Status.RESIDUAL, outcome.status());
        assertNotNull(outcome.failure());
        assertTrue(outcome.failure().getSuppressed().length >= 1);
        assertTrue(state.registered);
        assertFalse(state.connectionOpen);
        assertEquals(List.of("remove", "disconnect", "remove", "release"), state.calls);
    }

    @Test
    void persistentDisconnectFailureReturnsResidualAndRetainsFailure() {
        var state = new PlacementState();
        state.registered = true;
        state.connectionOpen = true;
        state.failEveryRemove = true;
        state.failDisconnect = true;

        var outcome = NmsPlacementRollback.rollback(state);

        assertEquals(NmsPlacementRollback.Status.RESIDUAL, outcome.status());
        assertNotNull(outcome.failure());
        assertTrue(containsFailure(outcome.failure(), "disconnect failed"));
        assertTrue(state.registered);
    }

    @Test
    void resourceReleaseFailureReturnsResidual() {
        var state = new PlacementState();
        state.registered = true;
        state.connectionOpen = true;
        state.failRelease = true;

        var outcome = NmsPlacementRollback.rollback(state);

        assertEquals(NmsPlacementRollback.Status.RESIDUAL, outcome.status());
        assertNotNull(outcome.failure());
        assertTrue(containsFailure(outcome.failure(), "resource release failed"));
        assertTrue(state.connectionOpen);
    }

    @Test
    void invariantFailureReturnsResidual() {
        var state = new PlacementState();
        state.registered = true;
        state.throwFromIsClean = true;

        var outcome = NmsPlacementRollback.rollback(state);

        assertEquals(NmsPlacementRollback.Status.RESIDUAL, outcome.status());
        assertNotNull(outcome.failure());
        assertTrue(containsFailure(outcome.failure(), "invariant check failed"));
    }

    @Test
    void cleanupFailureCanBeAttachedToOriginalPlacementFailure() {
        var state = new PlacementState();
        state.registered = true;
        state.failEveryRemove = true;
        var placementFailure = new IllegalStateException("placement failed");

        var outcome = NmsPlacementRollback.rollback(state);
        assertNotNull(outcome.failure());
        placementFailure.addSuppressed(outcome.failure());

        assertSame(outcome.failure(), placementFailure.getSuppressed()[0]);
        assertEquals("placement failed", placementFailure.getMessage());
    }

    @Test
    void syntheticConnectionReleaseRunsEvenWhenCloseFails() {
        var channel = new FakeChannel();
        channel.closeFailure = new IllegalStateException("close failed");

        var failure = assertThrows(
                RuntimeException.class,
                () -> NMSNetworkImpl.closeSyntheticConnection(new FakeConnection(channel))
        );

        assertTrue(containsFailure(failure, "close failed"));
        assertEquals(1, channel.closeCalls);
        assertEquals(1, channel.releaseCalls);
    }

    @Test
    void syntheticConnectionReleaseFailureIsNotSwallowed() {
        var channel = new FakeChannel();
        channel.releaseFailure = new IllegalStateException("release failed");

        var failure = assertThrows(
                RuntimeException.class,
                () -> NMSNetworkImpl.closeSyntheticConnection(new FakeConnection(channel))
        );

        assertTrue(containsFailure(failure, "release failed"));
        assertEquals(1, channel.closeCalls);
        assertEquals(1, channel.releaseCalls);
    }

    @Test
    void syntheticConnectionCloseAndReleaseFailuresPreserveSuppressedCause() {
        var channel = new FakeChannel();
        channel.closeFailure = new IllegalStateException("close failed");
        channel.releaseFailure = new IllegalStateException("release failed");

        var failure = assertThrows(
                RuntimeException.class,
                () -> NMSNetworkImpl.closeSyntheticConnection(new FakeConnection(channel))
        );

        assertTrue(containsFailure(failure, "close failed"));
        assertTrue(containsFailure(failure, "release failed"));
        assertEquals(1, channel.closeCalls);
        assertEquals(1, channel.releaseCalls);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(containsFailure(failure.getSuppressed()[0], "release failed"));
    }

    @Test
    void failedPlacementNativeTeardownIsOwnedByOneTransaction() throws Exception {
        var channel = new FakeChannel();
        var network = new NMSNetworkImpl(InetAddress.getLoopbackAddress());
        var release = NMSNetworkImpl.class.getDeclaredMethod(
                "releaseFailedPlacementResources",
                Object.class,
                Object.class
        );
        release.setAccessible(true);

        release.invoke(network, new FakeConnection(channel), null);
        release.invoke(network, new FakeConnection(channel), null);

        assertEquals(1, channel.closeCalls);
        assertEquals(1, channel.releaseCalls);
    }

    @Test
    void residualPlacementRollbackStateIsStickyAndSkipsNormalClose() throws Exception {
        var network = new NMSNetworkImpl(InetAddress.getLoopbackAddress());
        var state = NMSNetworkImpl.class.getDeclaredField("placementRollbackState");
        state.setAccessible(true);
        state.set(network, io.github.hello09x.fakeplayer.api.spi.NMSNetwork.PlacementRollbackState.RESIDUAL);

        network.close();

        assertTrue(network.hasPlacementRollbackResidual());
        assertEquals(
                io.github.hello09x.fakeplayer.api.spi.NMSNetwork.PlacementRollbackState.RESIDUAL,
                network.getPlacementRollbackState()
        );
    }

    private static void assertPlacementRollbackIsClean(ListenerState listenerState) {
        var placement = new PlacementState();
        placement.registered = true;
        placement.connectionOpen = true;
        placement.drainTask = true;
        placement.halfInstalledListener = listenerState.playerListener != listenerState.originalPlayerListener
                || listenerState.connectionListener != listenerState.originalConnectionListener;

        NmsPlacementRollback.rollback(placement);

        assertFalse(placement.registered);
        assertFalse(placement.connectionOpen);
        assertFalse(placement.drainTask);
        assertFalse(placement.halfInstalledListener);
    }

    private static boolean containsFailure(Throwable failure, String message) {
        return containsFailure(failure, message, new ArrayList<>());
    }

    private static boolean containsFailure(Throwable failure, String message, List<Throwable> visited) {
        if (failure == null || visited.contains(failure)) {
            return false;
        }
        visited.add(failure);
        if (message.equals(failure.getMessage())) {
            return true;
        }
        if (containsFailure(failure.getCause(), message, visited)) {
            return true;
        }
        for (var suppressed : failure.getSuppressed()) {
            if (containsFailure(suppressed, message, visited)) {
                return true;
            }
        }
        return false;
    }

    private static final class ListenerState implements NmsListenerInstallation.State {
        private final Object originalPlayerListener = new Object();
        private final Object originalConnectionListener = new Object();
        private Object playerListener = originalPlayerListener;
        private Object connectionListener = originalConnectionListener;
        private final Object fakeListener = new Object();
        private boolean constructorFailure;
        private boolean failPlayerWrite;
        private boolean failConnectionWrite;

        @Override
        public Object playerListener() {
            return playerListener;
        }

        @Override
        public Object connectionListener() {
            return connectionListener;
        }

        @Override
        public Object createFakeListener() {
            if (constructorFailure) {
                // Model a constructor that published itself to the player
                // before failing, as ServerGamePacketListenerImpl can do.
                playerListener = fakeListener;
                throw new IllegalStateException("listener constructor failed");
            }
            return fakeListener;
        }

        @Override
        public void setPlayerListener(Object listener) {
            if (failPlayerWrite) {
                throw new IllegalStateException("player field write failed");
            }
            playerListener = listener;
        }

        @Override
        public void setConnectionListener(Object listener) {
            if (failConnectionWrite) {
                throw new IllegalStateException("connection field write failed");
            }
            connectionListener = listener;
        }
    }

    private static final class PlacementState implements NmsPlacementRollback.Hooks {
        private final List<String> calls = new ArrayList<>();
        private boolean registered;
        private boolean connectionOpen;
        private boolean drainTask;
        private boolean halfInstalledListener = true;
        private boolean removeFailure;
        private boolean failEveryRemove;
        private boolean failDisconnect;
        private boolean failRelease;
        private boolean throwFromIsClean;

        @Override
        public boolean isRegistered() {
            return registered;
        }

        @Override
        public void removeRegisteredPlayer() {
            calls.add("remove");
            if (removeFailure || failEveryRemove) {
                removeFailure = false;
                throw new IllegalStateException("PlayerList.remove failed");
            }
            registered = false;
        }

        @Override
        public void disconnectRegisteredPlayer() {
            calls.add("disconnect");
            if (failDisconnect) {
                throw new IllegalStateException("disconnect failed");
            }
        }

        @Override
        public void releaseResources() {
            calls.add("release");
            if (failRelease) {
                throw new IllegalStateException("resource release failed");
            }
            connectionOpen = false;
            drainTask = false;
            halfInstalledListener = false;
        }

        @Override
        public boolean isClean() {
            if (throwFromIsClean) {
                throw new IllegalStateException("invariant check failed");
            }
            return !registered && !connectionOpen && !drainTask && !halfInstalledListener;
        }
    }

    private static final class FakeConnection {
        public final FakeChannel channel;

        private FakeConnection(FakeChannel channel) {
            this.channel = channel;
        }
    }

    private static final class FakeChannel {
        private int closeCalls;
        private int releaseCalls;
        private RuntimeException closeFailure;
        private RuntimeException releaseFailure;

        public void close() {
            this.closeCalls++;
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }

        public void finishAndReleaseAll() {
            this.releaseCalls++;
            if (this.releaseFailure != null) {
                throw this.releaseFailure;
            }
        }
    }
}
