/*
 * Compat shim derived from adventure 4.26.1 (net/kyori/adventure/translation).
 * adventure is licensed under the MIT License, Copyright (c) 2017-2025 KyoriPowered.
 *
 * Minecraft 26.2 ships adventure-api 5.2.0, which replaced TranslationRegistry with
 * TranslationStore, while devtools-core (loaded during plugin enable) is compiled
 * against the 4.x registry API. This self-contained port of the 4.x semantics keeps
 * that code path working without depending on either adventure generation; on servers
 * that still bundle the original class this copy is simply never loaded.
 */
package net.kyori.adventure.translation;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;

import static java.util.Objects.requireNonNull;

public interface TranslationRegistry {

    Pattern SINGLE_QUOTE_PATTERN = Pattern.compile("'");

    static TranslationRegistry create(Key name) {
        return new Store(name);
    }

    MessageFormat translate(String key, Locale locale);

    void defaultLocale(Locale locale);

    void registerAll(Locale locale, ResourceBundle bundle, boolean escapeSingleQuotes);

    final class Store implements TranslationRegistry {

        private final Map<String, Map<Locale, MessageFormat>> translations = new ConcurrentHashMap<>();
        private volatile Locale defaultLocale = Locale.US;

        private Store(Key name) {
            requireNonNull(name, "name");
        }

        @Override
        public MessageFormat translate(String key, Locale locale) {
            Map<Locale, MessageFormat> formats = this.translations.get(requireNonNull(key, "key"));
            if (formats == null) {
                return null;
            }
            requireNonNull(locale, "locale");
            MessageFormat format = formats.get(locale);
            if (format == null) {
                format = formats.get(Locale.forLanguageTag(locale.getLanguage()));
                if (format == null) {
                    format = formats.get(this.defaultLocale);
                    if (format == null) {
                        format = formats.get(globalLocale());
                    }
                }
            }
            return format;
        }

        @Override
        public void defaultLocale(Locale locale) {
            this.defaultLocale = requireNonNull(locale, "locale");
        }

        @Override
        public void registerAll(Locale locale, ResourceBundle bundle, boolean escapeSingleQuotes) {
            this.registerAll(locale, bundle.keySet(), key -> {
                String format = bundle.getString(key);
                return new MessageFormat(escapeSingleQuotes
                        ? SINGLE_QUOTE_PATTERN.matcher(format).replaceAll("''")
                        : format, locale);
            });
        }

        private void registerAll(Locale locale, Set<String> keys, Function<String, MessageFormat> function) {
            IllegalArgumentException firstError = null;
            int errorCount = 0;
            for (String key : keys) {
                try {
                    this.register(key, locale, function.apply(key));
                } catch (IllegalArgumentException e) {
                    if (firstError == null) {
                        firstError = e;
                    }
                    errorCount++;
                }
            }
            if (firstError != null) {
                if (errorCount == 1) {
                    throw firstError;
                }
                throw new IllegalArgumentException(String.format("Invalid key (and %d more)", errorCount - 1), firstError);
            }
        }

        private void register(String key, Locale locale, MessageFormat format) {
            MessageFormat previous = this.translations
                    .computeIfAbsent(requireNonNull(key, "key"), ignored -> new ConcurrentHashMap<>())
                    .putIfAbsent(requireNonNull(locale, "locale"), requireNonNull(format, "format"));
            if (previous != null) {
                throw new IllegalArgumentException(String.format("Translation already exists: %s for %s", key, locale));
            }
        }

        private static Locale globalLocale() {
            String property = System.getProperty("net.kyori.adventure.defaultTranslationLocale");
            if (property == null || property.isEmpty()) {
                return Locale.US;
            }
            if (property.equals("system")) {
                return Locale.getDefault();
            }
            return Locale.forLanguageTag(property.replace('_', '-'));
        }
    }
}
