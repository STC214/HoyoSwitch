package dev.hoyoswitch.app;

import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.lang.reflect.Proxy;

public final class AppearanceTest {
    public static void main(String[] args) {
        AppAppearance.Mode system = AppAppearance.Mode.SYSTEM;
        AppAppearance.Mode dark = AppAppearance.Mode.DARK;
        AppAppearance.Mode light = AppAppearance.Mode.LIGHT;
        if (AppAppearance.Mode.fromKey(null) != system || AppAppearance.Mode.fromKey("unknown") != system) {
            throw new AssertionError("unknown preference should follow the system");
        }
        for (AppAppearance.Mode mode : AppAppearance.Mode.values()) {
            if (AppAppearance.Mode.fromKey(mode.key) != mode) {
                throw new AssertionError("mode preference did not round-trip");
            }
        }
        if (AppAppearance.readMode(prefs("dark")) != dark
                || AppAppearance.readMode(prefs("obsolete")) != system
                || AppAppearance.readMode(prefs(false)) != system) {
            throw new AssertionError("invalid stored theme should fall back without changing preferences");
        }
        int type = Configuration.UI_MODE_TYPE_DESK;
        int systemLight = type | Configuration.UI_MODE_NIGHT_NO;
        int systemDark = type | Configuration.UI_MODE_NIGHT_YES;
        check(systemLight, system, systemLight);
        check(systemDark, system, systemDark);
        check(systemLight, dark, systemDark);
        check(systemDark, dark, systemDark);
        check(systemDark, light, systemLight);
        check(systemLight, light, systemLight);
        System.out.println("Appearance selection regression: PASS");
    }

    private static SharedPreferences prefs(Object value) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    if (!"getString".equals(method.getName()) || !AppAppearance.MODE_KEY.equals(args[0])) {
                        throw new AssertionError("unexpected preferences access: " + method.getName());
                    }
                    return (String) value;
                });
    }

    private static void check(int base, AppAppearance.Mode mode, int expected) {
        if (AppAppearance.themedUiMode(base, mode) != expected) {
            throw new AssertionError("theme did not preserve the expected device configuration: " + mode);
        }
    }
}
