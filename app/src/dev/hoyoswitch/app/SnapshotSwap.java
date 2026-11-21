package dev.hoyoswitch.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.BiConsumer;

/** A persisted intent makes same-filesystem directory swaps recoverable after process death. */
final class SnapshotSwap {
    static final String HISTORY = ".history";
    static final String STAGING = ".staging";
    static final String MANIFEST = "manifest.json";
    static final String PREPARED_MANIFEST = "manifest.swap";
    private static final String PREFIX = ".swap-";

    private final File live;
    private final File replacement;
    private final File archive;
    private final File record;

    private SnapshotSwap(File base, String id, String folder, String staged, String backedUp)
            throws IOException {
        if (!(STAGING.equals(folder) || HISTORY.equals(folder)) || !component(id)
                || !component(staged) || !component(backedUp) || !backedUp.startsWith(id + "-")) {
            throw new IOException("快照交换记录无效: " + id);
        }
        live = new File(base, id);
        replacement = new File(new File(base, folder), staged);
        archive = new File(new File(base, HISTORY), backedUp);
        record = recordFor(base, id);
        if (replacement.equals(archive)) {
            throw new IOException("待恢复版本与归档目标相同: " + id);
        }
    }

    static File recordFor(File base, String id) {
        return new File(base, PREFIX + id);
    }

    static boolean isRecord(File file) {
        return file.getName().startsWith(PREFIX) && !file.getName().endsWith(".tmp");
    }

    private static boolean component(String value) {
        return value.matches("[A-Za-z0-9_-]+(?:\\.[0-9]+)?");
    }

    static SnapshotSwap begin(File live, File replacement, File archive) throws IOException {
        File base = live.getParentFile();
        String folder = replacement.getParentFile().getName();
        SnapshotSwap swap = new SnapshotSwap(base, live.getName(), folder,
                replacement.getName(), archive.getName());
        if (!swap.replacement.equals(replacement) || !swap.archive.equals(archive)
                || swap.record.exists() || archive.exists() || !hasManifest(live)
                || !hasManifest(replacement)
                || (HISTORY.equals(folder) && !new File(replacement, PREPARED_MANIFEST).isFile())) {
            throw new IOException("快照交换前置检查失败: " + live.getName());
        }
        File temp = new File(base, swap.record.getName() + ".tmp");
        String data = folder + "\n" + replacement.getName() + "\n" + archive.getName() + "\n";
        try (FileOutputStream stream = new FileOutputStream(temp)) {
            stream.write(data.getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        Files.move(temp.toPath(), swap.record.toPath(), StandardCopyOption.ATOMIC_MOVE);
        return swap;
    }

    void publish(BiConsumer<String, File> afterCommit) throws IOException {
        if (!live.renameTo(archive)) {
            recoverState(afterCommit);
            throw new IOException("无法归档原快照: " + live.getName());
        }
        if (!replacement.renameTo(live)) {
            recoverState(afterCommit);
            throw new IOException("无法替换快照，已恢复原版本: " + live.getName());
        }
        recoverState(afterCommit);
    }

    static void recover(File record, BiConsumer<String, File> afterCommit) throws IOException {
        if (!isRecord(record) || !record.isFile()) {
            throw new IOException("快照交换记录损坏: " + record.getName());
        }
        List<String> values = Files.readAllLines(record.toPath(), StandardCharsets.UTF_8);
        if (values.size() != 3) {
            throw new IOException("快照交换记录损坏: " + record.getName());
        }
        new SnapshotSwap(record.getParentFile(), record.getName().substring(PREFIX.length()),
                values.get(0), values.get(1), values.get(2)).recoverState(afterCommit);
    }

    private void recoverState(BiConsumer<String, File> afterCommit) throws IOException {
        if (!live.exists()) {
            if (!hasManifest(archive) || !archive.renameTo(live)) {
                throw new IOException("无法恢复原快照，已保留交换记录: " + record.getName());
            }
        } else if (!hasManifest(live) || archive.exists() == replacement.exists()) {
            throw new IOException("快照交换状态不一致，已保留交换记录: " + record.getName());
        } else if (archive.exists()) {
            if (!hasManifest(archive)) {
                throw new IOException("快照交换状态不一致，已保留原归档: " + record.getName());
            }
            File prepared = new File(live, PREPARED_MANIFEST);
            if (prepared.exists()) {
                Files.move(prepared.toPath(), new File(live, MANIFEST).toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            afterCommit.accept(live.getName(), archive);
        }
        Files.delete(record.toPath());
    }

    private static boolean hasManifest(File directory) {
        return new File(directory, MANIFEST).isFile();
    }
}
