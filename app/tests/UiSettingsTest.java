package dev.hoyoswitch.app;

import android.content.SharedPreferences;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class UiSettingsTest {
    public static void main(String[] args) throws InterruptedException {
        Map<String, Object> saved = new HashMap<>();
        BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
        // Older versions recorded a previous bound game but never recorded the switch state.
        saved.put("floating_game", GameProfile.STARRAIL.pkg);
        SharedPreferences prefs = reopen(saved);
        check(!UiSettings.showLog(prefs), "log should default off");
        check(UiSettings.floatingGame(prefs) == null, "legacy game alone must not enable overlay");

        check(UiSettings.setShowLog(prefs, true, main::add, success()), "log write not scheduled");
        Runnable showLog = take(main);
        check(!UiSettings.showLog(prefs), "pending write must not appear saved");
        check(!UiSettings.setFloatingGame(prefs, GameProfile.GENSHIN, main::add, success()),
                "second toggle must wait for save completion");
        showLog.run();

        check(UiSettings.setFloatingGame(prefs, GameProfile.GENSHIN, main::add, success()),
                "overlay write not scheduled");
        take(main).run();
        saved.put("game", GameProfile.ZZZ.pkg);
        prefs = reopen(saved);
        check(UiSettings.showLog(prefs), "log should retain its on state");
        check(UiSettings.floatingGame(prefs) == GameProfile.GENSHIN, "overlay must retain its own game");

        check(UiSettings.setShowLog(prefs, false, main::add, success()), "log write not scheduled");
        take(main).run();
        check(UiSettings.floatingGame(reopen(saved)) == GameProfile.GENSHIN, "switches are independent");

        saved.put("fail_commit", true);
        check(UiSettings.setFloatingGame(prefs, null, main::add,
                ok -> check(!ok, "write failure must be reported")), "disabling overlay not scheduled");
        take(main).run();
        check(UiSettings.floatingGame(reopen(saved)) == GameProfile.GENSHIN,
                "failed write must restore the previous choice");
        saved.remove("fail_commit");

        int[] changes = {0};
        Consumer<Boolean> observer = floating -> {
            check(floating, "wrong setting reported");
            changes[0]++;
        };
        UiSettings.addObserver(observer);
        check(UiSettings.setFloatingGame(prefs, null, main::add, success()), "overlay write not scheduled");
        Runnable hideOverlay = take(main);
        check(changes[0] == 0, "observer ran before durable completion");
        hideOverlay.run();
        UiSettings.removeObserver(observer);
        check(changes[0] == 1, "overlay change should notify once");
        prefs = reopen(saved);
        check(!UiSettings.showLog(prefs), "log should retain its off state");
        check(UiSettings.floatingGame(prefs) == null, "overlay should retain its off state");

        saved.put("floating_enabled_game", "unknown.game");
        check(UiSettings.floatingGame(reopen(saved)) == null, "unknown game must not rebind overlay");
        System.out.println("UiSettings persistence regression: PASS");
    }

    private static Runnable take(BlockingQueue<Runnable> main) throws InterruptedException {
        Runnable result = main.poll(5, TimeUnit.SECONDS);
        check(result != null, "save never posted completion");
        return result;
    }

    private static Consumer<Boolean> success() {
        return ok -> check(ok, "write should succeed");
    }

    private static SharedPreferences reopen(Map<String, Object> saved) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getBoolean":
                        case "getString":
                            return saved.getOrDefault(args[0], args[1]);
                        case "edit":
                            return editor(saved);
                        default:
                            throw new AssertionError(method.getName());
                    }
                });
    }

    private static SharedPreferences.Editor editor(Map<String, Object> saved) {
        Map<String, Object> pending = new HashMap<>();
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "putBoolean":
                        case "putString":
                            pending.put((String) args[0], args[1]);
                            return proxy;
                        case "commit":
                        case "apply":
                            pending.forEach((key, value) -> {
                                if (value == null) {
                                    saved.remove(key);
                                } else {
                                    saved.put(key, value);
                                }
                            });
                            return method.getName().equals("commit") ? !saved.containsKey("fail_commit") : null;
                        default:
                            throw new AssertionError(method.getName());
                    }
                });
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
