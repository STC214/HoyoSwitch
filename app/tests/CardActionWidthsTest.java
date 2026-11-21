package dev.hoyoswitch.app;

public final class CardActionWidthsTest {
    public static void main(String[] args) {
        expect(360, 110, 94, 52, 1, 198, 94, 52);
        expect(272, 110, 94, 52, 1, 110, 94, 52);
        expect(271, 110, 94, 52, 2, 271, 211, 52);
        expect(154, 110, 94, 52, 2, 154, 94, 52);
        expect(153, 110, 94, 52, 3, 153, 153, 52);
        expect(40, 110, 94, 52, 3, 40, 40, 40);
        expect(0, 110, 94, 52, 3, 0, 0, 0);
        expect(300, 165, 145, 58, 2, 300, 234, 58);
        System.out.println("CardActionWidthsTest passed (8 cases)");
    }

    private static void expect(int width, int primaryMin, int credentialsMin, int moreMin,
                               int rows, int primary, int credentials, int more) {
        CardActionWidths result = CardActionWidths.fit(width, primaryMin, credentialsMin, moreMin, 8);
        if (result.rows != rows || result.primary != primary
                || result.credentials != credentials || result.more != more) {
            throw new AssertionError("Unexpected action layout for width " + width);
        }
    }
}
