package io.github.hello09x.fakeplayer.core.manager;

import com.google.common.io.ByteStreams;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Strict parser for the BungeeCord PlayerList plugin message.
 */
public final class BungeePlayerListParser {

    public static final int MAX_MESSAGE_BYTES = 32 * 1024;
    private static final String SUB_CHANNEL = "PlayerList";

    private BungeePlayerListParser() {
    }

    /**
     * Returns empty for a different Bungee subchannel or mode. Malformed
     * payloads are rejected without leaking Guava's unchecked parser errors.
     */
    public static @NotNull Optional<Set<String>> parse(@NotNull byte[] message) {
        if (message.length == 0 || message.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("invalid message length: " + message.length);
        }

        try {
            @SuppressWarnings("UnstableApiUsage")
            var in = ByteStreams.newDataInput(message);
            if (!SUB_CHANNEL.equals(in.readUTF()) || !"ALL".equals(in.readUTF())) {
                return Optional.empty();
            }

            var players = new HashSet<String>();
            var payload = in.readUTF();
            if (!payload.isBlank()) {
                players.addAll(Arrays.asList(payload.split(", ", 4096)));
            }
            return Optional.of(Collections.unmodifiableSet(players));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("invalid BungeeCord PlayerList payload", malformed);
        }
    }
}
