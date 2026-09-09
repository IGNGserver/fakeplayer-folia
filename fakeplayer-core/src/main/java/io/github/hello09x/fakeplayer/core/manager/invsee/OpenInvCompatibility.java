package io.github.hello09x.fakeplayer.core.manager.invsee;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime gate for OpenInv versions with the modern Folia/menu fixes. */
public final class OpenInvCompatibility {

    public static final String MINIMUM_VERSION = "5.3.2";
    private static final Pattern VERSION = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$"
    );

    private OpenInvCompatibility() {
    }

    public static boolean isSupported(@Nullable String version) {
        if (version == null) {
            return false;
        }
        Matcher matcher = VERSION.matcher(version.trim());
        if (!matcher.matches()) {
            return false;
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = Integer.parseInt(matcher.group(3));
            return major > 5
                    || major == 5 && (minor > 3 || minor == 3 && patch >= 2);
        } catch (NumberFormatException ignored) {
            // A malformed plugin descriptor must take the safe SIMPLE path,
            // rather than preventing the whole FakePlayer plugin from loading.
            return false;
        }
    }
}
