package io.github.hello09x.fakeplayer.core.manager;

import com.google.common.io.ByteStreams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BungeePlayerListParserTest {

    @Test
    void parsesOnlyThePlayerListAllMessage() {
        @SuppressWarnings("UnstableApiUsage")
        var out = ByteStreams.newDataOutput();
        out.writeUTF("PlayerList");
        out.writeUTF("ALL");
        out.writeUTF("Alice, Bob");

        var parsed = BungeePlayerListParser.parse(out.toByteArray()).orElseThrow();
        assertEquals(java.util.Set.of("Alice", "Bob"), parsed);
    }

    @Test
    void rejectsTruncatedAndOversizedPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> BungeePlayerListParser.parse(new byte[]{0, 12, 'P'}));
        assertThrows(IllegalArgumentException.class,
                () -> BungeePlayerListParser.parse(new byte[BungeePlayerListParser.MAX_MESSAGE_BYTES + 1]));
    }
}
