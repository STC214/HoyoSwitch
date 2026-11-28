package dev.hoyoswitch.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/** Standalone regression for process detection and checked snapshot deletion. */
public final class SnapshotSafetyTest {
    public static void main(String[] args) throws IOException {
        SnapshotStore.Snapshot primaryNotes = new SnapshotStore.Snapshot();
        SnapshotStore.Snapshot olderNotes = new SnapshotStore.Snapshot();
        primaryNotes.loginName = "already-named";
        olderNotes.passwordEnc = "password-only-fixture";
        olderNotes.lastUsedAt = 42;
        if (!SnapshotStore.inheritCardNotes(primaryNotes, olderNotes)
                || !"password-only-fixture".equals(primaryNotes.passwordEnc)
                || !"already-named".equals(primaryNotes.loginName) || primaryNotes.lastUsedAt != 42) {
            throw new AssertionError("password-only note or last-used timestamp was lost");
        }
        olderNotes.passwordEnc = "different-password";
        if (SnapshotStore.inheritCardNotes(primaryNotes, olderNotes)
                || !"password-only-fixture".equals(primaryNotes.passwordEnc)) {
            throw new AssertionError("newer password was overwritten");
        }
        for (GameProfile game : GameProfile.ALL) {
            for (String relative : game.files) {
                SnapshotStore.validateManifestEntry(game, relative, false);
                expectIo(() -> SnapshotStore.validateManifestEntry(game, relative, true), "重复");
            }
            for (String relative : new String[]{"../outside.xml", "/data/system/fixture",
                    "shared_prefs/unlisted.xml", "shared_prefs//fixture.xml"}) {
                expectIo(() -> SnapshotStore.validateManifestEntry(game, relative, false), "非预设");
            }
        }
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
        checkHistoryMerge();
        for (String folder : new String[]{SnapshotSwap.STAGING, SnapshotSwap.HISTORY}) {
            for (int phase = 0; phase < 5; phase++) {
                checkSwapRecovery(folder, phase);
            }
        }
        checkPendingSwitch();
        System.out.println("Snapshot safety regression: PASS");
    }

    private static void checkPendingSwitch() throws IOException {
        Path base = Files.createTempDirectory("hoyoswitch-switch-test-");
        try {
            File dir = base.toFile();
            byte[] device = {1, 2};
            PendingSwitch.begin(dir, "safety-card");
            device[0] = 3; // First device file was overwritten before the process died.
            expectIo(() -> PendingSwitch.begin(dir, "another-card"), "安全快照");
            expectIo(() -> PendingSwitch.recover(dir, id -> {
                if (!"safety-card".equals(id)) {
                    throw new AssertionError("recovery selected the wrong snapshot");
                }
                throw new IOException("game still running");
            }), "game still running");
            if (!PendingSwitch.exists(dir) || device[0] != 3) {
                throw new AssertionError("blocked recovery discarded the journal or wrote files");
            }
            PendingSwitch.recover(dir, id -> {
                if (!"safety-card".equals(id)) {
                    throw new AssertionError("retry lost the rollback snapshot");
                }
                device[0] = 1;
            });
            if (PendingSwitch.exists(dir) || !Arrays.equals(device, new byte[]{1, 2})) {
                throw new AssertionError("interrupted switch did not restore the original state");
            }
            PendingSwitch.begin(dir, "safety-card");
            PendingSwitch.commit(dir);
            PendingSwitch.recover(dir, id -> { throw new AssertionError("committed switch was undone"); });
        } finally {
            Files.deleteIfExists(base.resolve(".pending-switch"));
            Files.deleteIfExists(base.resolve(".pending-switch.tmp"));
            Files.deleteIfExists(base);
        }
    }

    private static void checkSwapRecovery(String folder, int phase) throws IOException {
        Path base = Files.createTempDirectory("hoyoswitch-swap-test-");
        Path history = Files.createDirectory(base.resolve(SnapshotSwap.HISTORY));
        Path replacements = SnapshotSwap.HISTORY.equals(folder) ? history
                : Files.createDirectory(base.resolve(folder));
        Path live = Files.createDirectory(base.resolve("card"));
        Path replacement = Files.createDirectory(replacements.resolve("card-1"));
        Path archive = history.resolve("card-2");
        File record = SnapshotSwap.recordFor(base.toFile(), "card");
        boolean fromHistory = replacements.equals(history);
        try {
            Files.write(live.resolve(SnapshotSwap.MANIFEST), new byte[]{1});
            Files.write(replacement.resolve(SnapshotSwap.MANIFEST), new byte[]{fromHistory ? (byte) 3 : 2});
            if (fromHistory) {
                Files.write(replacement.resolve(SnapshotSwap.PREPARED_MANIFEST), new byte[]{2});
            }
            SnapshotSwap pending = SnapshotSwap.begin(live.toFile(), replacement.toFile(), archive.toFile());
            List<File> committed = new ArrayList<>();
            BiConsumer<String, File> afterCommit = (id, path) -> {
                if (!record.exists() || !"card".equals(id)) {
                    throw new AssertionError("commit lost the recovery record");
                }
                committed.add(path);
            };
            if (phase == 3) {
                pending.publish(afterCommit);
            } else {
                if (phase >= 1) {
                    Files.move(live, archive);
                }
                if (phase >= 2) {
                    Files.move(replacement, live);
                }
                if (phase >= 4 && fromHistory) {
                    Files.move(live.resolve(SnapshotSwap.PREPARED_MANIFEST),
                            live.resolve(SnapshotSwap.MANIFEST), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                SnapshotSwap.recover(record, afterCommit);
            }
            byte[] actual = Files.readAllBytes(live.resolve(SnapshotSwap.MANIFEST));
            boolean published = phase >= 2;
            if (!Arrays.equals(actual, new byte[]{published ? (byte) 2 : 1})
                    || Files.exists(archive) != published || Files.exists(replacement) == published
                    || Files.exists(live.resolve(SnapshotSwap.PREPARED_MANIFEST)) || record.exists()
                    || committed.size() != (published ? 1 : 0)
                    || (published && !archive.toFile().equals(committed.get(0)))) {
                throw new AssertionError("unexpected swap state for " + folder + ", phase " + phase);
            }
        } finally {
            for (Path candidate : new Path[]{live, archive, replacement}) {
                Files.deleteIfExists(candidate.resolve(SnapshotSwap.MANIFEST));
                Files.deleteIfExists(candidate.resolve(SnapshotSwap.PREPARED_MANIFEST));
                Files.deleteIfExists(candidate);
            }
            Files.deleteIfExists(record.toPath());
            Files.deleteIfExists(base.resolve(record.getName() + ".tmp"));
            Files.deleteIfExists(replacements);
            Files.deleteIfExists(history);
            Files.deleteIfExists(base);
        }
    }

    private static void checkHistoryMerge() throws IOException {
        Path root = Files.createTempDirectory("hoyoswitch-merge-test-");
        Path history = Files.createDirectory(root.resolve(".history"));
        Path duplicateDir = Files.createDirectory(root.resolve("duplicate"));
        try {
            SnapshotStore.Snapshot primary = new SnapshotStore.Snapshot();
            primary.id = "primary";
            SnapshotStore.Snapshot duplicate = new SnapshotStore.Snapshot();
            duplicate.id = "duplicate";
            duplicate.createdAt = 4;
            duplicate.dir = duplicateDir.toFile();
            Files.setLastModifiedTime(duplicateDir, FileTime.fromMillis(11000));
            Files.createDirectory(history.resolve("primary-other-99"));
            for (int i = 1; i <= 3; i++) {
                int primaryTime = i == 3 ? 5 : i;
                Files.setLastModifiedTime(Files.createDirectory(history.resolve("primary-" + primaryTime)),
                        FileTime.fromMillis((15 - primaryTime) * 1000));
                Files.setLastModifiedTime(Files.createDirectory(history.resolve("duplicate-" + (i + 4))),
                        FileTime.fromMillis((11 - i) * 1000));
            }
            File blocked = new File(history.resolve("duplicate-5").toString()) {
                @Override public boolean renameTo(File target) { return false; }
            };
            File failingHistory = new File(history.toString()) {
                @Override public File[] listFiles() { return new File[]{blocked}; }
            };
            if (SnapshotStore.mergeHistory(failingHistory, primary, duplicate)
                    || !Files.isDirectory(duplicateDir) || history.toFile().list().length != 7) {
                throw new AssertionError("failed migration lost the source card or history");
            }
            if (!SnapshotStore.mergeHistory(history.toFile(), primary, duplicate)) {
                throw new AssertionError("merge did not succeed on retry");
            }
            String[] versions = history.toFile().list();
            Arrays.sort(versions);
            if (!Arrays.equals(versions, new String[]{"primary-5.1", "primary-6", "primary-7", "primary-other-99"})
                    || Files.exists(duplicateDir)) {
                throw new AssertionError("merge must keep only the newest three: " + Arrays.toString(versions));
            }
        } finally {
            for (File entry : history.toFile().listFiles()) {
                Files.deleteIfExists(entry.toPath());
            }
            Files.deleteIfExists(history);
            Files.deleteIfExists(duplicateDir);
            Files.deleteIfExists(root);
        }
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
