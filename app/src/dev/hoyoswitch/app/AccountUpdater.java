package dev.hoyoswitch.app;

import java.io.IOException;

/** Refreshes a stored snapshot with the state the selected game is currently on. */
final class AccountUpdater {
    private AccountUpdater() {
    }

    /** Updates the snapshot that matches whichever account is currently logged in. */
    static String updateCurrent(SnapshotStore store, GameProfile game,
                                SnapshotStore.Progress progress) throws IOException {
        progress.report("正在读取当前状态（游戏运行中也可以更新）");
        String id = store.updateCurrentAccount(game);
        progress.report("已用当前状态更新快照 " + id);
        return id;
    }

    /**
     * Updates one specific card. Refuses when the client is on a different account, so a card can
     * never be overwritten with somebody else's state.
     */
    static String updateTarget(SnapshotStore store, SnapshotStore.Snapshot target,
                               SnapshotStore.Progress progress) throws IOException {
        progress.report("正在读取当前状态（游戏运行中也可以更新）");
        String id = store.updateAccountIfCurrent(target);
        progress.report("已用当前状态更新快照 " + id);
        return id;
    }
}
