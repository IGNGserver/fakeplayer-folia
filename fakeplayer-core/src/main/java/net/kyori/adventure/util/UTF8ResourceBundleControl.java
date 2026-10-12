/*
 * Compat shim derived from adventure 4.26.1 (net/kyori/adventure/util/UTF8ResourceBundleControl.java).
 * adventure is licensed under the MIT License, Copyright (c) 2017-2025 KyoriPowered.
 *
 * Minecraft 26.2 ships adventure-api 5.2.0, which no longer provides this class,
 * while devtools-core (loaded during plugin enable) still instantiates it to read
 * plugin resource bundles. Re-providing the 4.x class keeps that code path working;
 * on servers that still bundle the original class this copy is simply never loaded.
 * See TranslationRegistry in this distribution for the matching shim rationale.
 */
package net.kyori.adventure.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

public final class UTF8ResourceBundleControl extends ResourceBundle.Control {

    @Override
    public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload)
            throws IllegalAccessException, InstantiationException, IOException {
        if (format.equals("java.properties")) {
            String bundle = this.toBundleName(baseName, locale);
            String resource = this.toResourceName(bundle, "properties");
            InputStream is = null;
            if (reload) {
                URL url = loader.getResource(resource);
                if (url != null) {
                    URLConnection connection = url.openConnection();
                    if (connection != null) {
                        connection.setUseCaches(false);
                        is = connection.getInputStream();
                    }
                }
            } else {
                is = loader.getResourceAsStream(resource);
            }

            if (is == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return new PropertyResourceBundle(reader);
            }
        }
        return super.newBundle(baseName, locale, format, loader, reload);
    }
}
