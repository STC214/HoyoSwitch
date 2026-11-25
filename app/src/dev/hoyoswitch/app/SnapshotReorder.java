package dev.hoyoswitch.app;

import java.util.List;

final class SnapshotReorder {
    private SnapshotReorder() {}

    /** Moves one card beside another, preserving the relative order of all other cards. */
    static boolean move(List<String> order, String source, String target, boolean after) {
        int from = order.indexOf(source);
        int anchor = order.indexOf(target);
        int to = destination(from, anchor, after);
        if (to < 0) {
            return false;
        }
        order.add(to, order.remove(from));
        return true;
    }

    /** Returns the changed insertion index, or -1 when the indicated slot is a no-op. */
    static int destination(int from, int anchor, boolean after) {
        if (from < 0 || anchor < 0 || from == anchor) {
            return -1;
        }
        int to = anchor + (after ? 1 : 0);
        if (from < to) {
            to--;
        }
        return from == to ? -1 : to;
    }
}
