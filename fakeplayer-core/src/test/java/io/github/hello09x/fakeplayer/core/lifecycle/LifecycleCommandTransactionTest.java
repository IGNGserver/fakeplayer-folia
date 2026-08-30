package io.github.hello09x.fakeplayer.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LifecycleCommandTransactionTest {

    @Test
    void rejectsRollbackCheckpointForCommandThatWasNeverAttempted() {
        assertThrows(IllegalArgumentException.class, () -> transaction(
                LifecycleCommandTransaction.State.ROLLING_BACK,
                0,
                0
        ));
    }

    @Test
    void rejectsActiveTransactionWithIncompletePreSpawnProgress() {
        assertThrows(IllegalArgumentException.class, () -> transaction(
                LifecycleCommandTransaction.State.ACTIVE,
                0,
                -1
        ));
    }

    private static LifecycleCommandTransaction transaction(
            LifecycleCommandTransaction.State state,
            int preAttempted,
            int rollbackNext
    ) {
        return new LifecycleCommandTransaction(
                UUID.randomUUID(),
                "Fake_1",
                UUID.randomUUID().toString(),
                "Creator",
                state,
                List.of("whitelist remove Fake_1"),
                preAttempted,
                rollbackNext,
                List.of(),
                0,
                List.of(),
                0
        );
    }
}
