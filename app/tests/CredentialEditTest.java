package dev.hoyoswitch.app;

public final class CredentialEditTest {
    public static void main(String[] args) {
        check("saved", "", false, true);
        check("saved", "", true, false);
        check("saved", "new", false, false);
        check("", "", false, false);
        check(null, "", false, false); // Unreadable passwords are handled by the separate re-entry guard.
        System.out.println("Password-note clear confirmation: PASS (5 cases)");
    }

    private static void check(String original, String draft, boolean confirmed, boolean expected) {
        if (MainActivity.needsPasswordClearConfirmation(original, draft, confirmed) != expected) {
            throw new AssertionError("Unexpected clear confirmation policy");
        }
    }
}
