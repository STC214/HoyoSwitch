package dev.hoyoswitch.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standalone regression for process detection and checked snapshot deletion. */
public final class SnapshotSafetyTest {
    public static void main(String[] args) throws IOException {
        if (!GameProfile.parseRunningPids(result(0, "123 456", ""))
                .equals(Arrays.asList("123", "456"))) {
            throw new AssertionError("missing PIDs");
        }
        if (!GameProfile.parseRunningPids(result(1, "", "")).isEmpty()) {
            throw new AssertionError("no matching PID must be distinguishable");
        }
        expectIo(() -> GameProfile.parseRunningPids(result(127, "", "")), "127");
        expectIo(() -> GameProfile.parseRunningPids(result(1, "", "diagnostic")), "diagnostic");
        expectIo(() -> GameProfile.parseRunningPids(result(0, "", "")), "pidof");

        Node manifest = new Node("manifest.json");
        Node payload = new Node("payload.enc");
        Node snapshot = new Node("snapshot", manifest, payload);
        payload.deletable = false;
        expectIo(() -> SnapshotStore.deleteChecked(snapshot), "payload.enc");
        if (!manifest.exists() || !snapshot.exists() || !payload.exists()) {
            throw new AssertionError("failed payload deletion hid the snapshot");
        }
        payload.deletable = true;
        SnapshotStore.deleteChecked(snapshot);
        if (manifest.exists() || snapshot.exists() || payload.exists()) {
            throw new AssertionError("retry did not remove all entries");
        }
        System.out.println("Snapshot safety regression: PASS");
    }

    private static RootShell.Result result(int code, String out, String error) {
        return new RootShell.Result(code, out.getBytes(StandardCharsets.UTF_8), error);
    }

    private interface Operation {
        void run() throws IOException;
    }

    private static void expectIo(Operation action, String detail) throws IOException {
        try {
            action.run();
        } catch (IOException error) {
            if (!error.getMessage().contains(detail)) {
                throw error;
            }
            return;
        }
        throw new AssertionError("expected failure: " + detail);
    }

    private static final class Node extends File {
        final File[] children;
        boolean present = true;
        boolean deletable = true;

        Node(String name, File... children) {
            super(name);
            this.children = children.length == 0 ? null : children;
        }

        @Override public boolean isDirectory() { return children != null; }
        @Override public File[] listFiles() { return children; }
        @Override public boolean exists() { return present; }
        @Override public boolean delete() {
            if (deletable) {
                present = false;
            }
            return deletable;
        }
    }
}
