package io.github.hello09x.fakeplayer.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LifecycleCommandCodecTest {

    @Test
    void roundTripsUnicodeAndCommandSeparators() {
        var commands = List.of(
                "whitelist add Fake_1",
                "lp user Fake_1 parent add 测试组",
                "say embedded\\ntext"
        );

        assertEquals(commands, LifecycleCommandCodec.decode(LifecycleCommandCodec.encode(commands)));
    }

    @Test
    void rejectsTrailingJournalData() {
        var valid = Base64.getDecoder().decode(LifecycleCommandCodec.encode(List.of("one")));
        var malformed = ByteBuffer.allocate(valid.length + 1).put(valid).put((byte) 1).array();

        assertThrows(
                IllegalStateException.class,
                () -> LifecycleCommandCodec.decode(Base64.getEncoder().encodeToString(malformed))
        );
    }

    @Test
    void rejectsExcessiveCommandCountBeforeAllocating() {
        var malformed = ByteBuffer.allocate(Integer.BYTES).putInt(1_025).array();

        assertThrows(
                IllegalStateException.class,
                () -> LifecycleCommandCodec.decode(Base64.getEncoder().encodeToString(malformed))
        );
    }

    @Test
    void rejectsExcessiveCommandCountWhenEncoding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LifecycleCommandCodec.encode(java.util.Collections.nCopies(1_025, "say safe"))
        );
    }

    @Test
    void rejectsOversizedEncodedValueBeforeDecoding() {
        var oversized = "A".repeat(1_398_108);

        assertThrows(IllegalStateException.class, () -> LifecycleCommandCodec.decode(oversized));
    }
}
