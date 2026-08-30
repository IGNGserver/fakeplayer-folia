package io.github.hello09x.fakeplayer.core.lifecycle;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Length-prefixed command-list codec for storage in a portable TEXT column. */
public final class LifecycleCommandCodec {

    private static final int MAX_COMMANDS = 1_024;
    private static final int MAX_COMMAND_BYTES = 1_048_576;

    private LifecycleCommandCodec() {
    }

    public static @NotNull String encode(@NotNull List<String> commands) {
        if (commands.size() > MAX_COMMANDS) {
            throw new IllegalArgumentException("Too many lifecycle commands: " + commands.size());
        }
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(commands.size());
                for (var command : commands) {
                    var encoded = command.getBytes(StandardCharsets.UTF_8);
                    if (encoded.length > MAX_COMMAND_BYTES
                            || bytes.size() > MAX_COMMAND_BYTES - Integer.BYTES - encoded.length) {
                        throw new IllegalArgumentException("Lifecycle command journal exceeds size limit");
                    }
                    out.writeInt(encoded.length);
                    out.write(encoded);
                }
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    public static @NotNull List<String> decode(@NotNull String encoded) {
        try {
            // Base64 uses four characters per three payload bytes. Reject an
            // oversized corrupted value before allocating its decoded copy.
            var maxEncodedLength = ((MAX_COMMAND_BYTES + 2L) / 3L) * 4L;
            if (encoded.length() > maxEncodedLength) {
                throw new IllegalArgumentException("Lifecycle command journal exceeds size limit");
            }
            var bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > MAX_COMMAND_BYTES) {
                throw new IllegalArgumentException("Lifecycle command journal exceeds size limit");
            }
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                var count = in.readInt();
                if (count < 0 || count > MAX_COMMANDS) {
                    throw new IllegalArgumentException("Invalid lifecycle command count: " + count);
                }
                var commands = new ArrayList<String>(count);
                for (int i = 0; i < count; i++) {
                    var length = in.readInt();
                    if (length < 0 || length > MAX_COMMAND_BYTES || length > in.available()) {
                        throw new IllegalArgumentException("Invalid lifecycle command length: " + length);
                    }
                    commands.add(new String(in.readNBytes(length), StandardCharsets.UTF_8));
                }
                if (in.available() != 0) {
                    throw new IllegalArgumentException("Trailing data in lifecycle command journal");
                }
                return List.copyOf(commands);
            }
        } catch (IOException malformed) {
            throw new UncheckedIOException("Malformed lifecycle command journal", malformed);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("Malformed lifecycle command journal", malformed);
        }
    }
}
