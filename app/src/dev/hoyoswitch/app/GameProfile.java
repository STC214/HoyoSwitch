package dev.hoyoswitch.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One supported game. File lists are only filled in for games whose switching was verified. */
final class GameProfile {
    final String label;
    final String pkg;
    final String activity;
    final List<String> files;
    final List<String> switchFiles;
    final boolean supported;
    /** Switching only works after its file set has been verified on a real account. */
    final boolean switchVerified;
    /** Prefs file holding {@code login_status}; null when the game does not use it. */
    final String loginPrefs;
    /** Prefs file holding the account widget ({@code mid}); null when absent. */
    final String identityPrefs;
    /** Prefs file holding the account token, keyed by mid; null when absent. */
    final String tokenPrefs;
    /** Account database whose newest row is the current account; null when absent. */
    final String accountDb;

    private GameProfile(String label, String pkg, String activity, List<String> files,
                        List<String> switchFiles, boolean supported, boolean switchVerified,
                        String loginPrefs, String identityPrefs, String tokenPrefs,
                        String accountDb) {
        this.label = label;
        this.pkg = pkg;
        this.activity = activity;
        this.files = files;
        this.switchFiles = switchFiles;
        this.supported = supported;
        this.switchVerified = switchVerified;
        this.loginPrefs = loginPrefs;
        this.identityPrefs = identityPrefs;
        this.tokenPrefs = tokenPrefs;
        this.accountDb = accountDb;
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
                    "databases/PORTE_ACCOUNT_PRODUCT.db-wal",
                    "databases/PORTE_ACCOUNT_PRODUCT.db-shm",
                    "databases/mihoyo_account",
                    "databases/mihoyo_account-journal"),
            Arrays.asList(
                    "shared_prefs/porte_sdk_token_PRODUCT.xml",
                    "shared_prefs/mihoyo_sdk_preference.xml",
                    "shared_prefs/combo_widget.xml",
                    "databases/PORTE_ACCOUNT_PRODUCT.db"),
            true,
            true,
            "shared_prefs/porte_sdk_account_PRODUCT.xml",
            "shared_prefs/combo_widget.xml",
            null,
            null);

    /** 原神 7.2：无 combo_widget，账号 token 存在按 mid 命名的 prefs key 里。 */
    static final GameProfile GENSHIN = new GameProfile(
            "原神", "com.miHoYo.Yuanshen",
            "com.miHoYo.Yuanshen/com.miHoYo.GetMobileInfo.MainActivity",
            Arrays.asList(
                    "shared_prefs/porte_sdk_account_PRODUCT.xml",
                    "shared_prefs/porte_sdk_token_PRODUCT.xml",
                    "shared_prefs/mihoyo_sdk_preference.xml",
                    "shared_prefs/mhy_plat_porte_crypto.xml",
                    "shared_prefs/mdk_passport_sp.xml",
                    "shared_prefs/com.miHoYo.Yuanshen.v2.playerprefs.xml",
                    "databases/PORTE_ACCOUNT_PRODUCT.db",
                    "databases/PORTE_ACCOUNT_PRODUCT.db-journal",
                    "databases/PORTE_ACCOUNT_PRODUCT.db-wal",
                    "databases/PORTE_ACCOUNT_PRODUCT.db-shm",
                    "databases/mihoyo_account",
                    "databases/mihoyo_account-journal"),
            Arrays.asList(
                    "shared_prefs/porte_sdk_token_PRODUCT.xml",
                    "shared_prefs/mihoyo_sdk_preference.xml",
                    "databases/PORTE_ACCOUNT_PRODUCT.db"),
            true,
            true,
            null,
            null,
            "shared_prefs/porte_sdk_token_PRODUCT.xml",
            "databases/PORTE_ACCOUNT_PRODUCT.db");

    /**
     * 绝区零 3.3：同样没有 combo_widget，账号行在 PORTE_ACCOUNT_PRODUCT.db、token 在 token prefs，
     * 因此与原神共用一套识别逻辑；切换集合待真机双向验证。
     */
    static final GameProfile ZZZ = new GameProfile(
            "绝区零", "com.miHoYo.Nap",
            "com.miHoYo.Nap/com.mihoyo.combosdk.ComboSDKActivity",
            Arrays.asList(
                    "shared_prefs/porte_sdk_account_PRODUCT.xml",
                    "shared_prefs/porte_sdk_token_PRODUCT.xml",
                    "shared_prefs/mihoyo_sdk_preference.xml",
                    "shared_prefs/mhy_plat_porte_crypto.xml",
                    "shared_prefs/mdk_passport_sp.xml",
                    "shared_prefs/com.miHoYo.Nap.v2.playerprefs.xml",
                    "databases/PORTE_ACCOUNT_PRODUCT.db",
                    "databases/PORTE_ACCOUNT_PRODUCT.db-journal",
                    "databases/PORTE_ACCOUNT_PRODUCT.db-wal",
                    "databases/PORTE_ACCOUNT_PRODUCT.db-shm",
                    "databases/mihoyo_account",
                    "databases/mihoyo_account-journal"),
            Arrays.asList(
                    "shared_prefs/porte_sdk_token_PRODUCT.xml",
                    "shared_prefs/mihoyo_sdk_preference.xml",
                    "databases/PORTE_ACCOUNT_PRODUCT.db"),
            true,
            true,
            "shared_prefs/porte_sdk_account_PRODUCT.xml",
            null,
            "shared_prefs/porte_sdk_token_PRODUCT.xml",
            "databases/PORTE_ACCOUNT_PRODUCT.db");

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
        // am returns before the process is gone; switching right after would see it as running.
        for (int attempt = 0; attempt < 40; attempt++) {
            if (!isRunning()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("停止游戏被中断", error);
            }
        }
        throw new IOException("停止" + label + "超时，请手动结束游戏后重试");
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
