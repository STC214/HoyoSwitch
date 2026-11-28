package dev.hoyoswitch.app;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Persistent user choices, independent of the current activity or service instance. */
final class UiSettings {
    private static final String SHOW_LOG = "show_operation_log";
    private static final String FLOATING_ENABLED_GAME = "floating_enabled_game";
    // The activity and service share a process. These values are accessed on its main thread.
    private static final List<Consumer<Boolean>> observers = new ArrayList<>();
    private static String pendingKey;
    private static Object previousValue;

    private UiSettings() {}

    static boolean showLog(SharedPreferences prefs) {
        if (SHOW_LOG.equals(pendingKey)) {
            return (boolean) previousValue;
        }
        try {
            return prefs.getBoolean(SHOW_LOG, false);
        } catch (ClassCastException invalidPreference) {
            return false;
        }
    }

    static boolean setShowLog(SharedPreferences prefs, boolean visible, Executor main,
                              Consumer<Boolean> done) {
        return save(prefs, SHOW_LOG, showLog(prefs), visible, main, done);
    }

    static GameProfile floatingGame(SharedPreferences prefs) {
        String pkg = savedFloatingPackage(prefs);
        for (GameProfile game : GameProfile.ALL) {
            if (game.supported && game.pkg.equals(pkg)) {
                return game;
            }
        }
        return null;
    }

    static boolean setFloatingGame(SharedPreferences prefs, GameProfile game, Executor main,
                                   Consumer<Boolean> done) {
        // The same key records both whether the feature is on and which game it belongs to.
        return save(prefs, FLOATING_ENABLED_GAME, savedFloatingPackage(prefs),
                game == null ? null : game.pkg, main, done);
    }

    private static String savedFloatingPackage(SharedPreferences prefs) {
        if (FLOATING_ENABLED_GAME.equals(pendingKey)) {
            return (String) previousValue;
        }
        try {
            return prefs.getString(FLOATING_ENABLED_GAME, null);
        } catch (ClassCastException invalidPreference) {
            // A restored/rewritten preference file must not crash the page on every resume.
            return null;
        }
    }

    static void addObserver(Consumer<Boolean> observer) {
        observers.add(observer);
    }

    static void removeObserver(Consumer<Boolean> observer) {
        observers.remove(observer);
    }

    static boolean isSaving() {
        return pendingKey != null;
    }

    private static boolean save(SharedPreferences prefs, String key, Object oldValue, Object value,
                                Executor main, Consumer<Boolean> done) {
        if (isSaving()) {
            return false;
        }
        pendingKey = key;
        previousValue = oldValue;
        new Thread(() -> {
            boolean saved = false;
            try {
                saved = put(prefs.edit(), key, value).commit();
            } catch (RuntimeException error) {
                Log.w("hoyoswitch", "保存界面设置失败", error);
            }
            if (!saved) {
                // A failed commit can still update the preference's in-memory value.
                try {
                    put(prefs.edit(), key, oldValue).apply();
                } catch (RuntimeException error) {
                    Log.w("hoyoswitch", "恢复原界面设置失败", error);
                }
            }
            boolean result = saved;
            main.execute(() -> {
                pendingKey = null;
                previousValue = null;
                if (result) {
                    for (Consumer<Boolean> observer : new ArrayList<>(observers)) {
                        observer.accept(FLOATING_ENABLED_GAME.equals(key));
                    }
                }
                done.accept(result);
            });
        }, "ui-settings-write").start();
        return true;
    }

    private static SharedPreferences.Editor put(SharedPreferences.Editor editor, String key, Object value) {
        if (SHOW_LOG.equals(key)) {
            return editor.putBoolean(key, (boolean) value);
        }
        return editor.putString(key, (String) value);
    }
}
