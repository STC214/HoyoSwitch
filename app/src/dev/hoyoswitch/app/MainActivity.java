package dev.hoyoswitch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
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

        TextView hint = new TextView(this);
        hint.setText("拍摄与切换前请先停止游戏；切换只写 4 个账号相关文件，并自动保存切换前状态。");
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
        logView.setPadding(0, pad / 2, 0, 0);
        root.addView(logView, matchWrap());
        return root;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weightWrap() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void refresh() {
        new Thread(() -> {
            boolean rooted = RootShell.isRooted();
            boolean running;
            String version;
            List<SnapshotStore.Snapshot> snapshots;
            try {
                running = StarRailProfile.isRunning();
                version = StarRailProfile.version();
            } catch (IOException error) {
                running = false;
                version = "";
            }
            snapshots = store.list();
            final boolean rootedNow = rooted;
            final boolean runningNow = running;
            final String versionNow = version;
            final List<SnapshotStore.Snapshot> listed = snapshots;
            main.post(() -> {
                String rootText = rootedNow ? "Root 正常" : "未取得 Root：" + RootShell.lastError();
                String gameText = versionNow.isEmpty() ? "未检测到星铁" : "星铁 " + versionNow;
                statusView.setText(rootText + " · " + gameText + " · "
                        + (runningNow ? "游戏运行中" : "游戏已停止"));
                captureButton.setEnabled(rootedNow && !runningNow && !busy);
                renderCards(listed);
            });
        }).start();
    }

    private void renderCards(List<SnapshotStore.Snapshot> snapshots) {
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
            label.setText(snapshot.name);
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
            row.addView(switchButton, weightWrap());
            card.addView(row, matchWrap());

            cardList.addView(card, matchWrap());
        }
    }

    private void confirmSwitch(SnapshotStore.Snapshot snapshot) {
        new AlertDialog.Builder(this)
                .setTitle("切换到 " + snapshot.name)
                .setMessage("将停止游戏（如正在运行），写入账号相关文件，并自动保存切换前状态。继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("切换", (dialog, which) -> runOp("切换到 " + snapshot.name, () -> {
                    if (StarRailProfile.isRunning()) {
                        StarRailProfile.forceStop();
                        appendLog("已停止游戏");
                    }
                    store.switchTo(snapshot, true, this::appendLog);
                }))
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
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
