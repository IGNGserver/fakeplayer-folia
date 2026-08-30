package io.github.hello09x.fakeplayer.core.lifecycle;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Validates the positional forward/compensation command contract. */
public final class LifecycleCommandPairing {

    private LifecycleCommandPairing() {
    }

    /**
     * Blank pairs are ignored, but a one-sided pair is rejected. Commands are
     * normalized for console dispatch by trimming whitespace and a leading
     * slash while preserving their positional compensation relationship.
     */
    public static @NotNull Pair normalize(
            @NotNull List<String> forwardRaw,
            @NotNull List<String> rollbackRaw
    ) {
        var forward = new ArrayList<String>();
        var rollback = new ArrayList<String>();
        var entries = Math.max(forwardRaw.size(), rollbackRaw.size());

        for (int i = 0; i < entries; i++) {
            var forwardCommand = normalizeCommand(i < forwardRaw.size() ? forwardRaw.get(i) : "");
            var rollbackCommand = normalizeCommand(i < rollbackRaw.size() ? rollbackRaw.get(i) : "");
            if (forwardCommand == null && rollbackCommand == null) {
                continue;
            }
            if (forwardCommand == null || rollbackCommand == null) {
                throw new IllegalStateException(
                        "Unsafe lifecycle command configuration at list index " + i
                                + ": every non-empty pre-spawn-commands entry requires a non-empty, idempotent "
                                + "pre-spawn-rollback-commands entry at the same index"
                );
            }
            forward.add(forwardCommand);
            rollback.add(rollbackCommand);
        }
        return new Pair(List.copyOf(forward), List.copyOf(rollback));
    }

    private static @Nullable String normalizeCommand(@Nullable String command) {
        if (command == null) {
            return null;
        }
        var normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized.isBlank() ? null : normalized;
    }

    public record Pair(@NotNull List<String> forward, @NotNull List<String> rollback) {
    }
}
