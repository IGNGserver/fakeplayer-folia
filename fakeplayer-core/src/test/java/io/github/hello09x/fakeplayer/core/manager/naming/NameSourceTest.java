package io.github.hello09x.fakeplayer.core.manager.naming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NameSourceTest {

    @Test
    void unlimitedCapacityIsLazyAndReusesSequences() {
        var source = new NameSource(Integer.MAX_VALUE);

        assertEquals(0, source.pop());
        assertEquals(1, source.pop());
        source.push(0);
        source.push(0);
        assertEquals(0, source.pop());
        assertEquals(2, source.pop());
    }
}
