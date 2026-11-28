package dev.hoyoswitch.app;

import android.view.WindowManager;

public final class DialogWidthTest {
    public static void main(String[] args) {
        expect(1080, 1080, 520);
        expect(360, 1080, 312);
        expect(96, 1080, 72);
        expect(1, 1080, 1);
        expect(0, 360, 312);
        expect(0, 0, WindowManager.LayoutParams.WRAP_CONTENT);
        System.out.println("Dialog window width: PASS (6 cases)");
    }

    private static void expect(int measured, int fallback, int expected) {
        int actual = MainActivity.dialogWidth(measured, fallback, 48, 520);
        if (actual != expected) {
            throw new AssertionError("Dialog width: expected " + expected + ", got " + actual);
        }
    }
}
