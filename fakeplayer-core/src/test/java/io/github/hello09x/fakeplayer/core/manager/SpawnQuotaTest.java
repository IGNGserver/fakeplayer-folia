package io.github.hello09x.fakeplayer.core.manager;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpawnQuotaTest {

    @Test
    void countsInFlightReservationsAgainstAllLimits() throws Exception {
        var quota = new SpawnQuota();
        var first = quota.reserve("alice", "10.0.0.1", true, 2, 3, 0, 0, 0, false);
        var second = quota.reserve("alice", "10.0.0.1", true, 2, 3, 0, 0, 0, false);

        assertThrows(SpawnQuota.LimitExceededException.class,
                () -> quota.reserve("alice", "10.0.0.1", true, 2, 3, 0, 0, 0, false));

        first.close();
        var replacement = quota.reserve("alice", "10.0.0.1", true, 2, 3, 0, 0, 0, false);
        second.close();
        replacement.close();
    }

    @Test
    void commitPublishesOnceAndReleasesReservation() throws Exception {
        var quota = new SpawnQuota();
        var reservation = quota.reserve("alice", "10.0.0.1", false, 2, 2, 0, 0, 0, false);

        assertEquals(true, quota.commit(reservation, () -> true));
        assertEquals(false, quota.commit(reservation, () -> true));

        var next = quota.reserve("alice", "10.0.0.1", false, 2, 2, 1, 1, 0, false);
        next.close();
    }

    @Test
    void clearingQuotaInvalidatesLateReservationsWithoutUnderflow() throws Exception {
        var quota = new SpawnQuota();
        var reservation = quota.reserve("alice", "10.0.0.1", true, 1, 1, 0, 0, 0, false);

        quota.clear();
        reservation.close();

        assertThrows(IllegalStateException.class,
                () -> quota.reserve("alice", "10.0.0.1", true, 1, 1, 0, 0, 0, false));
    }

    @Test
    void concurrentReservationsNeverExceedServerLimit() throws Exception {
        var quota = new SpawnQuota();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(16);
        var futures = new ArrayList<java.util.concurrent.Future<SpawnQuota.Reservation>>();
        try {
            for (int i = 0; i < 64; i++) {
                var creator = "creator-" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return quota.reserve(creator, "10.0.0." + creator, false,
                                100, 8, 0, 0, 0, false);
                    } catch (SpawnQuota.LimitExceededException rejected) {
                        return null;
                    }
                }));
            }
            start.countDown();

            var accepted = new ArrayList<SpawnQuota.Reservation>();
            for (var future : futures) {
                var reservation = future.get();
                if (reservation != null) {
                    accepted.add(reservation);
                }
            }
            assertEquals(8, accepted.size());
            accepted.forEach(SpawnQuota.Reservation::close);
        } finally {
            executor.shutdownNow();
        }
    }
}
