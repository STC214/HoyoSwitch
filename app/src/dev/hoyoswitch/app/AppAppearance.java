package dev.hoyoswitch.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** App-wide theme preference, independent of the phone's setting when explicitly overridden. */
final class AppAppearance {
    static final String MODE_KEY = "appearance";

    enum Mode {
        SYSTEM("system", "跟随系统", Configuration.UI_MODE_NIGHT_UNDEFINED),
        DARK("dark", "暗色模式", Configuration.UI_MODE_NIGHT_YES),
        LIGHT("light", "亮色模式", Configuration.UI_MODE_NIGHT_NO);

        final String key;
        final String title;
        final int nightMode;

        Mode(String key, String title, int nightMode) {
            this.key = key;
            this.title = title;
            this.nightMode = nightMode;
        }

        static Mode fromKey(String key) {
            for (Mode candidate : values()) {
                if (candidate.key.equals(key)) {
                    return candidate;
                }
            }
            return SYSTEM;
        }
    }

    private AppAppearance() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE);
    }

    static Mode mode(Context context) {
        return readMode(preferences(context));
    }

    static Mode readMode(SharedPreferences prefs) {
        try {
            return Mode.fromKey(prefs.getString(MODE_KEY, Mode.SYSTEM.key));
        } catch (ClassCastException invalidPreference) {
            return Mode.SYSTEM;
        }
    }

    static void select(Context context, Mode mode) {
        preferences(context).edit().putString(MODE_KEY, mode.key).apply();
    }

    static int themedUiMode(int baseUiMode, Mode mode) {
        return mode == Mode.SYSTEM ? baseUiMode
                : (baseUiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode.nightMode;
    }

    static Context wrap(Context base) {
        Mode selected = mode(base);
        if (selected == Mode.SYSTEM) {
            return base;
        }
        Configuration override = new Configuration();
        override.uiMode = themedUiMode(base.getResources().getConfiguration().uiMode, selected);
        return base.createConfigurationContext(override);
    }
}
