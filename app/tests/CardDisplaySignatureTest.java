package dev.hoyoswitch.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CardDisplaySignatureTest {
    public static void main(String[] args) {
        SnapshotStore.Snapshot card = new SnapshotStore.Snapshot();
        card.game = GameProfile.STARRAIL;
        card.id = "fixture";
        card.name = "fixture-name";
        List<Object> original = stamp(card, false, true);
        List<Object> expectedOriginal = new ArrayList<>(original);
        check(original.equals(stamp(card, false, true)), "unchanged card should reuse views");
        check(!original.equals(stamp(card, true, true)), "preview must become live");
        check(!original.equals(stamp(card, false, false)), "action availability must refresh");
        card.name = "renamed";
        check(!original.equals(stamp(card, false, true)), "rename must refresh");
        card.name = "fixture-name";
        card.passwordEnc = "ciphertext-fixture";
        check(!original.equals(stamp(card, false, true)), "credential changes must refresh");
        card.passwordEnc = "";
        SnapshotStore.Entry entry = new SnapshotStore.Entry();
        entry.path = "fixture.xml";
        card.entries.put(entry.path, entry);
        List<Object> withEntry = stamp(card, false, true);
        entry.sha256 = "new-digest";
        check(!withEntry.equals(stamp(card, false, true)), "file changes must refresh");
        check(expectedOriginal.equals(original), "signature should be immutable by card edits");
        System.out.println("Card display signature: PASS");
    }

    private static List<Object> stamp(SnapshotStore.Snapshot card, boolean preview, boolean available) {
        return MainActivity.cardDisplaySignature(Collections.singletonList(card), null,
                GameProfile.STARRAIL, Collections.singletonMap(card.id, true), preview, available, false, 0);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
