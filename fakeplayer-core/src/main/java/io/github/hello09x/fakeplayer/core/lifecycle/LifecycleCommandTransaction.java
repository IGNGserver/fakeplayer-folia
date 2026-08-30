package io.github.hello09x.fakeplayer.core.lifecycle;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Durable state for lifecycle commands whose external effects cannot take
 * part in the plugin's SQLite transaction. Progress is deliberately stored as
 * at-least-once delivery; configured compensation/finalizer commands must be
 * idempotent so a crash between dispatch and checkpoint is safe to recover.
 */
public record LifecycleCommandTransaction(
        @NotNull UUID id,
        @NotNull String fakeName,
        @NotNull String fakeUuid,
        @NotNull String creatorName,
        @NotNull State state,
        @NotNull List<String> rollbackCommands,
        int preAttempted,
        int rollbackNext,
        @NotNull List<String> postQuitCommands,
        int postQuitNext,
        @NotNull List<String> afterQuitCommands,
        int afterQuitNext
) {

    public LifecycleCommandTransaction {
        rollbackCommands = List.copyOf(rollbackCommands);
        postQuitCommands = List.copyOf(postQuitCommands);
        afterQuitCommands = List.copyOf(afterQuitCommands);
        requireRange("preAttempted", preAttempted, 0, rollbackCommands.size());
        requireRange("rollbackNext", rollbackNext, -1, rollbackCommands.size() - 1);
        requireRange("postQuitNext", postQuitNext, 0, postQuitCommands.size());
        requireRange("afterQuitNext", afterQuitNext, 0, afterQuitCommands.size());
        if (state == State.ROLLING_BACK && rollbackNext >= preAttempted) {
            throw new IllegalArgumentException(
                    "rollbackNext cannot reference a command that was not attempted: " + rollbackNext
            );
        }
        if ((state == State.ACTIVE || state == State.QUITTING)
                && preAttempted != rollbackCommands.size()) {
            throw new IllegalArgumentException(state + " lifecycle transaction has incomplete pre-spawn progress");
        }
    }

    private static void requireRange(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    field + " is outside [" + minimum + ", " + maximum + "]: " + value
            );
        }
    }

    public enum State {
        SPAWNING,
        ACTIVE,
        QUITTING,
        ROLLING_BACK
    }
}
