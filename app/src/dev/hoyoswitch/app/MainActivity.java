package dev.hoyoswitch.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Single-screen prototype: status, capture, account cards, switch. */
public final class MainActivity extends Activity {
    private interface Op {
        void run() throws Exception;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SnapshotStore store;
    private TextView statusView;
    private EditText nameInput;
    private LinearLayout cardList;
    private TextView logView;
    private Button captureButton;
    private Button launchButton;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new SnapshotStore(this);
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private LinearLayout buildLayout() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("米哈游账号切换（原型）");
        title.setTextSize(22);
        root.addView(title, matchWrap());

        statusView = new TextView(this);
        statusView.setText("正在检测 Root 与游戏状态…");
        statusView.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(statusView, matchWrap());

        nameInput = new EditText(this);
        nameInput.setHint("账号名称（可选）");
        root.addView(nameInput, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        captureButton = new Button(this);
        captureButton.setText("拍摄快照");
        captureButton.setOnClickListener(view -> capture());
        launchButton = new Button(this);
        launchButton.setText("启动游戏");
        launchButton.setOnClickListener(view -> runOp("启动游戏", StarRailProfile::launch));
        Button refreshButton = new Button(this);
        refreshButton.setText("刷新");
        refreshButton.setOnClickListener(view -> refresh());
        actions.addView(captureButton, weightWrap());
        actions.addView(launchButton, weightWrap());
        actions.addView(refreshButton, weightWrap());
        root.addView(actions, matchWrap());

        LinearLayout extra = new LinearLayout(this);
        extra.setOrientation(LinearLayout.HORIZONTAL);
        Button floatingButton = new Button(this);
        floatingButton.setText("悬浮按钮");
        floatingButton.setOnClickListener(view -> toggleFloating());
        extra.addView(floatingButton, weightWrap());
        root.addView(extra, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("拍摄与切换前请先停止游戏；切换只写 4 个文件并自动备份。"
                + "切换后等游戏加载完成，再在该账号卡片上点「更新此快照」，或用悬浮按钮刷新当前账号。");
        hint.setTextSize(12);
        hint.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(hint, matchWrap());

        ScrollView scroll = new ScrollView(this);
        cardList = new LinearLayout(this);
        cardList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(cardList);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        logView = new TextView(this);
        logView.setTextSize(12);
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView);
        logScroll.setPadding(0, pad / 2, 0, 0);
        int logHeight = (int) (60 * getResources().getDisplayMetrics().density);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, logHeight));
        logScrollRef = logScroll;
        return root;
    }

    private ScrollView logScrollRef;

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weightWrap() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static LinearLayout.LayoutParams weightWrap(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    private void refresh() {
        new Thread(() -> {
            boolean rooted = RootShell.isRooted();
            boolean running;
            String version;
            List<SnapshotStore.Snapshot> snapshots;
            SnapshotStore.Current current;
            try {
                running = StarRailProfile.isRunning();
                version = StarRailProfile.version();
            } catch (IOException error) {
                running = false;
                version = "";
            }
            snapshots = store.list();
            try {
                current = store.detectCurrent();
            } catch (IOException error) {
                current = null;
            }
            final boolean rootedNow = rooted;
            final boolean runningNow = running;
            final String versionNow = version;
            final List<SnapshotStore.Snapshot> listed = snapshots;
            final SnapshotStore.Current currentNow = current;
            main.post(() -> {
                SnapshotStore.Snapshot matched = match(currentNow, listed);
                String rootText = rootedNow ? "Root 正常" : "未取得 Root：" + RootShell.lastError();
                String gameText = versionNow.isEmpty() ? "未检测到星铁" : "星铁 " + versionNow;
                statusView.setText(rootText + " · " + gameText + " · "
                        + (runningNow ? "游戏运行中" : "游戏已停止")
                        + " · 当前账号：" + describe(currentNow, matched));
                captureButton.setEnabled(rootedNow && !runningNow && !busy);
                renderCards(listed, matched == null ? null : matched.id);
            });
        }).start();
    }

    private SnapshotStore.Snapshot match(SnapshotStore.Current current,
                                         List<SnapshotStore.Snapshot> snapshots) {
        if (current == null || !current.loggedIn || current.mid.isEmpty()) {
            return null;
        }
        for (SnapshotStore.Snapshot snapshot : snapshots) {
            if (current.mid.equals(store.accountMid(snapshot))) {
                return snapshot;
            }
        }
        return null;
    }

    private String describe(SnapshotStore.Current current, SnapshotStore.Snapshot matched) {
        if (current == null) {
            return "无法判定";
        }
        if (!current.loggedIn) {
            return "未登录";
        }
        if (matched != null) {
            return matched.name;
        }
        return current.mid.isEmpty()
                ? (current.note.isEmpty() ? "已登录，无法识别" : current.note)
                : "已登录，但不在任何快照中";
    }

    private void renderCards(List<SnapshotStore.Snapshot> snapshots, String currentId) {
        cardList.removeAllViews();
        if (snapshots.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有快照。登录游戏后停止游戏，点「拍摄快照」。");
            empty.setPadding(0, 24, 0, 0);
            cardList.addView(empty, matchWrap());
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (SnapshotStore.Snapshot snapshot : snapshots) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(0, 20, 0, 20);

            TextView label = new TextView(this);
            boolean isCurrent = snapshot.id.equals(currentId);
            label.setText(isCurrent ? snapshot.name + "　（当前登录）" : snapshot.name);
            label.setTextSize(16);
            card.addView(label, matchWrap());

            TextView meta = new TextView(this);
            int present = 0;
            for (SnapshotStore.Entry entry : snapshot.entries.values()) {
                if (entry.present) {
                    present++;
                }
            }
            meta.setText(format.format(new Date(snapshot.createdAt)) + " · "
                    + present + "/" + snapshot.entries.size() + " 个状态文件存在");
            meta.setTextSize(12);
            card.addView(meta, matchWrap());

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Button switchButton = new Button(this);
            switchButton.setText("切换到此账号");
            switchButton.setOnClickListener(view -> confirmSwitch(snapshot));
            Button updateButton = new Button(this);
            updateButton.setText("更新此快照");
            updateButton.setOnClickListener(view -> runOp("更新快照 " + snapshot.name,
                    () -> AccountUpdater.updateTarget(store, snapshot, this::appendLog)));
            row.addView(switchButton, weightWrap(2f));
            row.addView(updateButton, weightWrap(1f));
            card.addView(row, matchWrap());

            cardList.addView(card, matchWrap());
        }
    }

    private void confirmSwitch(SnapshotStore.Snapshot target) {
        if (busy) {
            toast("上一个操作还没结束");
            return;
        }
        busy = true;
        appendLog("准备切换到 " + target.name);
        new Thread(() -> {
            try {
                if (StarRailProfile.isRunning()) {
                    StarRailProfile.forceStop();
                    appendLog("已停止游戏");
                }
                SnapshotStore.Current current = store.detectCurrent();
                List<SnapshotStore.Snapshot> snapshots = store.list();
                String currentLabel = describe(current, match(current, snapshots));
                main.post(() -> askSwitch(target, currentLabel));
            } catch (Exception error) {
                appendLog("失败：" + error.getMessage());
                busy = false;
                refresh();
            }
        }).start();
    }

    private void askSwitch(SnapshotStore.Snapshot target, String currentLabel) {
        new AlertDialog.Builder(this)
                .setTitle("切换账号")
                .setMessage("当前账号：" + currentLabel
                        + "\n目标账号：" + target.name
                        + "\n\n将写入 4 个账号文件，并自动保存切换前状态。继续？")
                .setNegativeButton("取消", (dialog, which) -> {
                    busy = false;
                    refresh();
                })
                .setPositiveButton("切换", (dialog, which) -> new Thread(() -> {
                    try {
                        store.switchTo(target, true, this::appendLog);
                    } catch (Exception error) {
                        appendLog("失败：" + error.getMessage());
                    } finally {
                        busy = false;
                        refresh();
                    }
                }).start())
                .show();
    }

    private void capture() {
        String name = nameInput.getText().toString();
        runOp("拍摄快照", () -> {
            String id = store.capture(name);
            appendLog("已保存快照 " + id);
            main.post(() -> nameInput.setText(""));
        });
    }

    private void toggleFloating() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先允许悬浮窗权限，授权后回到 App 再点一次");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (FloatingService.isRunning()) {
            stopService(new Intent(this, FloatingService.class));
            toast("悬浮按钮已关闭");
        } else {
            startForegroundService(new Intent(this, FloatingService.class));
            toast("悬浮按钮已开启：可拖动，点它更新快照");
        }
    }

    private void runOp(String label, Op operation) {
        if (busy) {
            toast("上一个操作还没结束");
            return;
        }
        busy = true;
        appendLog("开始：" + label);
        statusView.setText("执行中：" + label);
        new Thread(() -> {
            try {
                operation.run();
            } catch (Exception error) {
                appendLog("失败：" + error.getMessage());
            } finally {
                busy = false;
                refresh();
            }
        }).start();
    }

    private void appendLog(String message) {
        main.post(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
            String text = logView.getText().toString();
            String line = time + "  " + message;
            if (text.isEmpty()) {
                logView.setText(line);
            } else {
                logView.setText(text + "\n" + line);
            }
            if (logScrollRef != null) {
                logScrollRef.post(() -> logScrollRef.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
