package dev.hoyoswitch.app;

import java.io.IOException;
import java.util.List;

/** Refreshes a stored snapshot with the state the client is currently on. */
final class AccountUpdater {
    private AccountUpdater() {
    }

    /** Updates the snapshot that matches whichever account is currently logged in. */
    static String updateCurrent(SnapshotStore store, SnapshotStore.Progress progress) throws IOException {
        boolean wasRunning = stopForCapture(progress);
        SnapshotStore.Current current = store.detectCurrent();
        if (!current.loggedIn) {
            throw new IOException("当前未登录，没有可更新的账号快照");
        }
        if (current.mid.isEmpty()) {
            throw new IOException("读不到当前账号标识，无法确认是哪个快照");
        }
        SnapshotStore.Snapshot matched = null;
        for (SnapshotStore.Snapshot snapshot : store.list()) {
            if (current.mid.equals(store.accountMid(snapshot))) {
                matched = snapshot;
                break;
            }
        }
        if (matched == null) {
            throw new IOException("当前账号不在任何快照中，请先在 App 里拍一张快照");
        }
        return write(store, matched, wasRunning, progress);
    }

    /**
     * Updates one specific card. Refuses when the client is on a different account, so a card can
     * never be overwritten with somebody else's state.
     */
    static String updateTarget(SnapshotStore store, SnapshotStore.Snapshot target,
                               SnapshotStore.Progress progress) throws IOException {
        boolean wasRunning = stopForCapture(progress);
        SnapshotStore.Current current = store.detectCurrent();
        if (!current.loggedIn) {
            throw new IOException("当前未登录，无法更新该快照");
        }
        if (current.mid.isEmpty()) {
            throw new IOException("读不到当前账号标识，无法确认就是这个账号");
        }
        if (!current.mid.equals(store.accountMid(target))) {
            throw new IOException("当前登录的不是这个账号，未更新快照");
        }
        return write(store, target, wasRunning, progress);
    }

    private static boolean stopForCapture(SnapshotStore.Progress progress) throws IOException {
        boolean wasRunning = StarRailProfile.isRunning();
        if (wasRunning) {
            StarRailProfile.forceStop();
            progress.report("已停止游戏以便一致读取");
        }
        return wasRunning;
    }

    private static String write(SnapshotStore store, SnapshotStore.Snapshot target,
                                boolean relaunch, SnapshotStore.Progress progress) throws IOException {
        String id = store.updateSnapshot(target);
        progress.report("已用当前状态更新快照 " + id);
        if (relaunch) {
            StarRailProfile.launch();
            progress.report("已重新启动游戏");
        }
        return id;
    }

    static SnapshotStore.Snapshot find(SnapshotStore store, String mid) {
        List<SnapshotStore.Snapshot> snapshots = store.list();
        for (SnapshotStore.Snapshot snapshot : snapshots) {
            if (!mid.isEmpty() && mid.equals(store.accountMid(snapshot))) {
                return snapshot;
            }
        }
        return null;
    }
}
