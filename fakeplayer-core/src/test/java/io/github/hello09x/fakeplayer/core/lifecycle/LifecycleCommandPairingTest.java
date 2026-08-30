package io.github.hello09x.fakeplayer.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LifecycleCommandPairingTest {

    @Test
    void normalizesCompletePairsAndSkipsBlankPairs() {
        var pair = LifecycleCommandPairing.normalize(
                List.of(" /whitelist add %p ", "", "lp user %p parent add bot"),
                List.of(" /whitelist remove %p ", "  ", "lp user %p parent remove bot")
        );

        assertEquals(List.of("whitelist add %p", "lp user %p parent add bot"), pair.forward());
        assertEquals(List.of("whitelist remove %p", "lp user %p parent remove bot"), pair.rollback());
    }

    @Test
    void rejectsMissingRollbackAtSameIndex() {
        assertThrows(IllegalStateException.class, () -> LifecycleCommandPairing.normalize(
                List.of("whitelist add %p"),
                List.of()
        ));
    }

    @Test
    void rejectsRollbackWithoutForwardCommand() {
        assertThrows(IllegalStateException.class, () -> LifecycleCommandPairing.normalize(
                List.of(""),
                List.of("whitelist remove %p")
        ));
    }
}
