package dev.hoyoswitch.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Pins the rollback snapshot across a process restart until switching or rollback is verified. */
final class PendingSwitch {
    private static final String RECORD = ".pending-switch";

    interface Recovery {
        void restore(String snapshotId) throws IOException;
    }

    private PendingSwitch() {}

    static boolean exists(File base) {
        return new File(base, RECORD).exists();
    }

    static void begin(File base, String snapshotId) throws IOException {
        if (exists(base) || !validId(snapshotId)) {
            throw new IOException("无法记录本次切换的安全快照，未写入游戏文件");
        }
        File temp = new File(base, RECORD + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temp)) {
            stream.write((snapshotId + "\n").getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        Files.move(temp.toPath(), new File(base, RECORD).toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    static void recover(File base, Recovery action) throws IOException {
        if (!exists(base)) {
            return;
        }
        List<String> lines = Files.readAllLines(new File(base, RECORD).toPath(), StandardCharsets.UTF_8);
        if (lines.size() != 1 || !validId(lines.get(0))) {
            throw new IOException("切换恢复记录无效，已保留记录和安全快照");
        }
        action.restore(lines.get(0));
        commit(base);
    }

    static void commit(File base) throws IOException {
        Files.delete(new File(base, RECORD).toPath());
    }

    private static boolean validId(String id) {
        return id.matches("[A-Za-z0-9_-]+");
    }
}
