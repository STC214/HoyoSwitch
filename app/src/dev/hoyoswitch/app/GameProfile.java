package dev.hoyoswitch.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** One supported game. File lists are only filled in for games whose switching was verified. */
final class GameProfile {
    final String label;
    final String pkg;
    final String activity;
    final List<String> files;
    final List<String> switchFiles;
    final boolean supported;

    private GameProfile(String label, String pkg, String activity, List<String> files,
                        List<String> switchFiles, boolean supported) {
        this.label = label;
        this.pkg = pkg;
        this.activity = activity;
        this.files = files;
        this.switchFiles = switchFiles;
        this.supported = supported;
    }

    /** 星铁：唯一验证过 4 文件切换的游戏。 */
    static final GameProfile STARRAIL = new GameProfile(
            "星穹铁道", "com.miHoYo.hkrpg",
            "com.miHoYo.hkrpg/com.mihoyo.combosdk.ComboSDKActivity",
            Arrays.asList(
                    "shared_prefs/porte_sdk_account_PRODUCT.xml",
                    "shared_prefs/porte_sdk_token_PRODUCT.xml",
                    "shared_prefs/mihoyo_sdk_preference.xml",
                    "shared_prefs/combo_widget.xml",
                    "shared_prefs/mhy_plat_porte_crypto.xml",
                    "shared_prefs/mdk_passport_sp.xml",
                    "databases/PORTE_ACCOUNT_PRODUCT.db",
                    "databases/PORTE_ACCOUNT_PRODUCT.db-journal",
                    "databases/mihoyo_account",
                    "databases/mihoyo_account-journal"),
            Arrays.asList(
                    "shared_prefs/porte_sdk_token_PRODUCT.xml",
                    "shared_prefs/mihoyo_sdk_preference.xml",
                    "shared_prefs/combo_widget.xml",
                    "databases/PORTE_ACCOUNT_PRODUCT.db"),
            true);

    /** 原神、绝区零：仅登记包名与入口，文件清单尚未调研，故不支持拍摄与切换。 */
    static final GameProfile GENSHIN = new GameProfile(
            "原神", "com.miHoYo.Yuanshen",
            "com.miHoYo.Yuanshen/com.miHoYo.GetMobileInfo.MainActivity",
            Collections.emptyList(), Collections.emptyList(), false);

    static final GameProfile ZZZ = new GameProfile(
            "绝区零", "com.miHoYo.Nap", "",
            Collections.emptyList(), Collections.emptyList(), false);

    static final List<GameProfile> ALL = Arrays.asList(GENSHIN, STARRAIL, ZZZ);

    static GameProfile byPackage(String pkg) {
        for (GameProfile game : ALL) {
            if (game.pkg.equals(pkg)) {
                return game;
            }
        }
        return STARRAIL;
    }

    String path(String relative) {
        return "/data/user/0/" + pkg + "/" + relative;
    }

    List<String> runningPids() throws IOException {
        RootShell.Result result = RootShell.run("pidof " + pkg);
        String text = result.text();
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.asList(text.split("\\s+"));
    }

    boolean isRunning() throws IOException {
        return !runningPids().isEmpty();
    }

    void forceStop() throws IOException {
        RootShell.checked("am force-stop --user 0 " + pkg);
    }

    void launch() throws IOException {
        if (!activity.isEmpty()) {
            RootShell.run("am start -W -n " + activity);
        }
    }

    /** Human-readable version, or an empty string when the package is missing. */
    String version() {
        try {
            RootShell.Result result = RootShell.run("dumpsys package " + pkg + " | grep -m1 versionName");
            String text = result.text();
            int index = text.indexOf('=');
            return index >= 0 ? text.substring(index + 1).trim() : text;
        } catch (IOException error) {
            return "";
        }
    }
}
