package dev.hoyoswitch.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SnapshotReorderTest {
    public static void main(String[] args) {
        expect("b", "d", true, true, "a", "c", "d", "b");
        expect("d", "a", false, true, "d", "a", "b", "c");
        expect("a", "c", false, true, "b", "a", "c", "d");
        expect("d", "b", true, true, "a", "b", "d", "c");
        expect("b", "a", true, false, "a", "b", "c", "d");
        expect("b", "c", false, false, "a", "b", "c", "d");
        expect("b", "b", true, false, "a", "b", "c", "d");
        expect("missing", "b", true, false, "a", "b", "c", "d");
        expect("b", "missing", false, false, "a", "b", "c", "d");
        System.out.println("SnapshotReorderTest passed (9 cases)");
    }

    private static void expect(String source, String target, boolean after,
                               boolean changed, String... expected) {
        List<String> order = new ArrayList<>(Arrays.asList("a", "b", "c", "d"));
        if (SnapshotReorder.move(order, source, target, after) != changed
                || !order.equals(Arrays.asList(expected))) {
            throw new AssertionError("Unexpected position for " + source + " beside " + target + ": " + order);
        }
    }
}
