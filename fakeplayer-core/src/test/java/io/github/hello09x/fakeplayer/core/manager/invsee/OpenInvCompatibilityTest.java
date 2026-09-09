package io.github.hello09x.fakeplayer.core.manager.invsee;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenInvCompatibilityTest {

    @Test
    void acceptsVersionsWithTheFoliaAndModernMenuFixes() {
        assertTrue(OpenInvCompatibility.isSupported("5.3.2"));
        assertTrue(OpenInvCompatibility.isSupported("5.3.3"));
        assertTrue(OpenInvCompatibility.isSupported("6.0.0"));
    }

    @Test
    void rejectsOlderOrUnparseableVersions() {
        assertFalse(OpenInvCompatibility.isSupported("5.3.1"));
        assertFalse(OpenInvCompatibility.isSupported("5.3"));
        assertFalse(OpenInvCompatibility.isSupported("unknown"));
        assertFalse(OpenInvCompatibility.isSupported("999999999999999999999.0.0"));
        assertFalse(OpenInvCompatibility.isSupported(null));
    }
}
