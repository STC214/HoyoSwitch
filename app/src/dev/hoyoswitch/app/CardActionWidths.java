package dev.hoyoswitch.app;

/** Allocates measured card width without squeezing the secondary touch targets together. */
final class CardActionWidths {
    final int rows;
    final int primary;
    final int credentials;
    final int more;

    private CardActionWidths(int rows, int primary, int credentials, int more) {
        this.rows = rows;
        this.primary = primary;
        this.credentials = credentials;
        this.more = more;
    }

    static CardActionWidths fit(int width, int primaryMin, int credentialsMin, int moreMin, int gap) {
        int secondaryMin = credentialsMin + moreMin + gap;
        if (width >= primaryMin + secondaryMin + gap) {
            return new CardActionWidths(1, width - secondaryMin - gap, credentialsMin, moreMin);
        }
        if (width >= secondaryMin) {
            return new CardActionWidths(2, width, width - moreMin - gap, moreMin);
        }
        return new CardActionWidths(3, width, width, Math.min(width, moreMin));
    }
}
