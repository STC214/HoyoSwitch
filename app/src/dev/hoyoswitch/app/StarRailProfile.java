package dev.hoyoswitch.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** StarRail-specific paths and probes. Only this class knows the package layout. */
final class StarRailProfile {
    static final String PACKAGE = "com.miHoYo.hkrpg";
    static final String DATA = "/data/user/0/" + PACKAGE;
    static final String ACTIVITY = PACKAGE + "/com.mihoyo.combosdk.ComboSDKActivity";

    /** Candidate state files; verified on device to be sufficient as a group. */
    static final List<String> FILES = Arrays.asList(
            "shared_prefs/porte_sdk_account_PRODUCT.xml",
            "shared_prefs/porte_sdk_token_PRODUCT.xml",
            "shared_prefs/mihoyo_sdk_preference.xml",
            "shared_prefs/combo_widget.xml",
            "shared_prefs/mhy_plat_porte_crypto.xml",
            "shared_prefs/mdk_passport_sp.xml",
            "databases/PORTE_ACCOUNT_PRODUCT.db",
            "databases/PORTE_ACCOUNT_PRODUCT.db-journal",
            "databases/mihoyo_account",
            "databases/mihoyo_account-journal");

    /** The four files that actually carry the account difference between snapshots. */
    static final List<String> SWITCH_FILES = Arrays.asList(
            "shared_prefs/porte_sdk_token_PRODUCT.xml",
            "shared_prefs/mihoyo_sdk_preference.xml",
            "shared_prefs/combo_widget.xml",
            "databases/PORTE_ACCOUNT_PRODUCT.db");

    private StarRailProfile() {
    }

    static String path(String relative) {
        return DATA + "/" + relative;
    }

    static List<String> runningPids() throws IOException {
        RootShell.Result result = RootShell.run("pidof " + PACKAGE);
        String text = result.text();
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.asList(text.split("\\s+"));
    }

    static boolean isRunning() throws IOException {
        return !runningPids().isEmpty();
    }

    static void forceStop() throws IOException {
        RootShell.checked("am force-stop --user 0 " + PACKAGE);
    }

    static void launch() throws IOException {
        RootShell.run("am start -W -n " + ACTIVITY);
    }

    /** Human-readable version, or an empty string when the package is missing. */
    static String version() {
        try {
            RootShell.Result result = RootShell.run("dumpsys package " + PACKAGE
                    + " | grep -m1 versionName");
            String text = result.text();
            int index = text.indexOf('=');
            return index >= 0 ? text.substring(index + 1).trim() : text;
        } catch (IOException error) {
            return "";
        }
    }
}
