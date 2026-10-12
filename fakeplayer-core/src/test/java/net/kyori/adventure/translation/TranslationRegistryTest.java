package net.kyori.adventure.translation;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranslationRegistryTest {

    private static ResourceBundle bundle(String pattern) {
        return new ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return new Object[][]{{"demo.hello", pattern}};
            }
        };
    }

    @Test
    void resolvesExactThenLanguageThenDefaultLocale() {
        var registry = TranslationRegistry.create(Key.key("fakeplayer"));
        registry.defaultLocale(Locale.FRANCE);
        registry.registerAll(Locale.GERMANY, bundle("Hallo aus DE {0}"), false);
        registry.registerAll(Locale.forLanguageTag("de"), bundle("Hallo {0}"), false);
        registry.registerAll(Locale.FRANCE, bundle("Bonjour {0}"), false);

        assertEquals("Hallo aus DE {0}", registry.translate("demo.hello", Locale.GERMANY).toPattern());
        assertEquals("Hallo {0}", registry.translate("demo.hello", Locale.forLanguageTag("de-AT")).toPattern());
        assertEquals("Bonjour {0}", registry.translate("demo.hello", Locale.ITALY).toPattern());
        assertNull(registry.translate("demo.missing", Locale.GERMANY));
    }

    @Test
    void rejectsDuplicateRegistration() {
        var registry = TranslationRegistry.create(Key.key("fakeplayer"));
        registry.registerAll(Locale.US, bundle("Hello {0}"), false);

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerAll(Locale.US, bundle("Hello again {0}"), false));
    }

    @Test
    void escapesSingleQuotesWhenRequested() {
        var registry = TranslationRegistry.create(Key.key("fakeplayer"));
        registry.registerAll(Locale.US, bundle("It's {0}"), true);

        assertEquals("It''s {0}", registry.translate("demo.hello", Locale.US).toPattern());
    }
}
