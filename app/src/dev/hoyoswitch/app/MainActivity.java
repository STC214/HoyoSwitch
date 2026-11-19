package dev.hoyoswitch.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One screen, one section per game: status, capture and account cards for the selected game. */
public final class MainActivity extends Activity {
    private interface Op {
        void run() throws Exception;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Button> gameButtons = new ArrayList<>();
    private SnapshotStore store;
    private GameProfile game = GameProfile.STARRAIL;
    private TextView statusView;
    private EditText nameInput;
    private LinearLayout cardList;
    private TextView logView;
    private ScrollView logScroll;
    private Button captureButton;
    private Button launchButton;
    private TextView logToggle;
    private int refreshGeneration;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new SnapshotStore(this);
        setContentView(buildLayout());
        String selftest = getIntent().getStringExtra("selftest");
        if ("cardops".equals(selftest)) {
            main.postDelayed(this::runCardOpsTest, 1200);
        } else if (getIntent().getBooleanExtra("selftest", false)) {
            main.postDelayed(this::runSelfTest, 1500);
        }
    }

    /** Command-driven check of rename, delete and history listing; used instead of blind taps. */
    private void runCardOpsTest() {
        runOp("卡片操作自检", () -> {
            GameProfile selected = game;
            if (selected.isRunning()) {
                selected.forceStop();
            }
            List<SnapshotStore.Snapshot> snapshots = store.list(selected);
            if (snapshots.isEmpty()) {
                throw new IOException("没有可用于自检的快照");
            }
            SnapshotStore.Snapshot first = snapshots.get(0);
            String original = first.name;
            boolean renameOk;
            try {
                store.rename(first, original + "-改名自检");
                renameOk = (original + "-改名自检").equals(store.load(selected, first.id).name);
            } finally {
                store.rename(store.load(selected, first.id), original);
            }

            String tempId = store.capture(selected, "删除自检临时快照");
            store.delete(store.load(selected, tempId));
            boolean deleteOk = true;
            for (SnapshotStore.Snapshot snapshot : store.list(selected)) {
                if (snapshot.id.equals(tempId)) {
                    deleteOk = false;
                    break;
                }
            }

            int historyCount = 0;
            SnapshotStore.Snapshot historyOwner = null;
            SnapshotStore.HistoryEntry historyEntry = null;
            for (SnapshotStore.Snapshot snapshot : store.list(selected)) {
                List<SnapshotStore.HistoryEntry> entries = store.history(snapshot);
                historyCount += entries.size();
                if (historyOwner == null && !entries.isEmpty()) {
                    historyOwner = snapshot;
                    historyEntry = entries.get(0);
                }
            }
            boolean historyOk = true;
            if (historyOwner != null) {
                store.restoreHistory(historyOwner, historyEntry);
                historyOk = store.load(selected, historyOwner.id).dir.isDirectory();
            }

            String verdict = renameOk && deleteOk && historyOk ? "PASS" : "FAIL";
            Log.i("hoyoswitch", "CARDOPS " + verdict + " rename=" + renameOk
                    + " delete=" + deleteOk + " history_restore=" + historyOk
                    + " history_entries=" + historyCount);
            appendLog("卡片操作自检：" + verdict + "（重命名=" + (renameOk ? "通过" : "失败")
                    + "，删除=" + (deleteOk ? "通过" : "失败")
                    + "，历史回滚=" + (historyOk ? "通过" : "失败")
                    + "，历史版本 " + historyCount + " 个）");
        });
    }

    /**
     * Self test for the rollback path: switch to another account's snapshot with an injected
     * verification failure, then require the device to be back at the pre-test state.
     */
    private void runSelfTest() {
        runOp("回滚自检", () -> {
            GameProfile target0 = game;
            if (target0.isRunning()) {
                target0.forceStop();
            }
            String baselineId = store.capture(target0, "回滚自检基线");
            SnapshotStore.Snapshot baseline = store.load(target0, baselineId);
            String baselineMid = store.accountMid(baseline);
            SnapshotStore.Snapshot other = null;
            for (SnapshotStore.Snapshot snapshot : store.list(target0)) {
                String mid = store.accountMid(snapshot);
                if (!snapshot.id.equals(baselineId) && !mid.isEmpty() && !mid.equals(baselineMid)) {
                    other = snapshot;
                    break;
                }
            }
            if (other == null) {
                throw new IOException("没有可用于自检的其他账号快照");
            }
            String outcome;
            SnapshotStore.failAfterWrites = 1;
            try {
                store.switchTo(other, false, message -> Log.i("hoyoswitch", message));
                outcome = "切换没有被注入的故障打断";
            } catch (IOException error) {
                outcome = error.getMessage();
            } finally {
                SnapshotStore.failAfterWrites = -1;
            }
            boolean rolledBack = true;
            for (String relative : target0.switchFiles) {
                SnapshotStore.Entry entry = baseline.entry(relative);
                if (entry == null || !entry.present) {
                    continue;
                }
                byte[] current = store.readDeviceForTest(target0.path(relative));
                if (current == null
                        || !SnapshotStore.sha256(current).equals(entry.sha256)) {
                    rolledBack = false;
                    break;
                }
            }
            String verdict = rolledBack ? "PASS" : "FAIL";
            Log.i("hoyoswitch", "SELFTEST " + verdict + " :: " + outcome);
            appendLog("自检结果：" + (rolledBack ? "回滚成功" : "回滚失败") + "；" + outcome);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private LinearLayout buildLayout() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(14), pad, dp(14));
        // targetSdk 36 draws edge to edge; add the system bar insets on top of our own padding.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(pad, dp(14) + insets.getSystemWindowInsetTop(),
                    pad, dp(14) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.app_icon);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setColor(getColor(R.color.card_bg));
        avatarBg.setCornerRadius(dp(18));
        avatar.setBackground(avatarBg);
        avatar.setClipToOutline(true);
        headerRow.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout headerText = new LinearLayout(this);
        headerText.setOrientation(LinearLayout.VERTICAL);
        headerText.setPadding(dp(14), 0, 0, 0);
        TextView appName = new TextView(this);
        appName.setText("账号切换");
        appName.setTextSize(22);
        appName.setTypeface(null, Typeface.BOLD);
        appName.setTextColor(getColor(R.color.text_primary));
        if (Build.VERSION.SDK_INT >= 28) {
            appName.setAccessibilityHeading(true);
        }
        headerText.addView(appName, matchWrap());
        TextView appSubtitle = new TextView(this);
        appSubtitle.setText("米哈游账号快照 · 一键切换");
        appSubtitle.setTextSize(12);
        appSubtitle.setTextColor(getColor(R.color.text_muted));
        headerText.addView(appSubtitle, matchWrap());
        headerRow.addView(headerText, weightWrap());

        TextView versionChip = new TextView(this);
        versionChip.setText(currentVersion());
        versionChip.setTextSize(12);
        versionChip.setTextColor(getColor(R.color.accent));
        versionChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        versionChip.setBackground(surface(R.color.pill_bg, R.color.pill_bg, 20));
        headerRow.addView(versionChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(headerRow, withBottomMargin(matchWrap(), dp(22)));

        TextView headline = new TextView(this);
        headline.setText("切号，轻一点。");
        headline.setTextSize(28);
        headline.setTypeface(null, Typeface.BOLD);
        headline.setTextColor(getColor(R.color.text_primary));
        root.addView(headline, matchWrap());

        TextView subline = new TextView(this);
        subline.setText("拍摄当前账号，切换时只写必要文件。");
        subline.setTextSize(13);
        subline.setTextColor(getColor(R.color.text_muted));
        subline.setPadding(0, dp(6), 0, dp(16));
        root.addView(subline, matchWrap());

        LinearLayout statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusPill.setBackground(surface(R.color.pill_bg, R.color.pill_bg, 24));
        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(getColor(R.color.accent));
        dot.setBackground(dotBg);
        statusPill.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        statusView = new TextView(this);
        statusView.setText("正在检测 Root 与游戏状态…");
        statusView.setTextSize(13);
        statusView.setTextColor(getColor(R.color.accent));
        statusView.setPadding(dp(10), 0, 0, 0);
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusPill.addView(statusView, weightWrap());
        root.addView(statusPill, withBottomMargin(matchWrap(), dp(6)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        content.addView(section("01 / 游戏分栏"), matchWrap());
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(surface(R.color.card_bg, R.color.card_bg, 26));
        tabs.setPadding(dp(8), dp(8), dp(8), dp(8));
        for (GameProfile profile : GameProfile.ALL) {
            Button tab = pill(profile.label, false);
            tab.setOnClickListener(view -> selectGame(profile));
            tabs.addView(tab, weightWrap(1f, dp(3)));
            gameButtons.add(tab);
        }
        content.addView(tabs, withBottomMargin(matchWrap(), dp(4)));
        renderTabs();

        content.addView(section("02 / 拍摄与切换"), matchWrap());
        LinearLayout controlCard = new LinearLayout(this);
        controlCard.setOrientation(LinearLayout.VERTICAL);
        controlCard.setBackground(surface(R.color.card_bg, R.color.card_bg, 26));
        controlCard.setPadding(dp(20), dp(18), dp(20), dp(18));

        nameInput = new EditText(this);
        nameInput.setHint("账号名称（可选）");
        nameInput.setTextSize(14);
        nameInput.setBackground(null);
        controlCard.addView(nameInput, withBottomMargin(matchWrap(), dp(8)));

        LinearLayout primaryRow = new LinearLayout(this);
        primaryRow.setOrientation(LinearLayout.HORIZONTAL);
        captureButton = pill("拍摄快照", true);
        captureButton.setOnClickListener(view -> capture());
        launchButton = pill("启动游戏", false);
        launchButton.setOnClickListener(view -> runOp("启动 " + game.label, game::launch));
        Button refreshButton = pill("刷新", false);
        refreshButton.setOnClickListener(view -> refresh());
        primaryRow.addView(captureButton, weightWrap(1.4f, dp(4)));
        primaryRow.addView(launchButton, weightWrap(1f, dp(4)));
        primaryRow.addView(refreshButton, weightWrap(1f, dp(4)));
        controlCard.addView(primaryRow, withBottomMargin(matchWrap(), dp(8)));

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        Button floatingButton = pill("悬浮按钮", false);
        floatingButton.setOnClickListener(view -> toggleFloating());
        logToggle = pill("隐藏日志", false);
        logToggle.setOnClickListener(view -> toggleLog());
        utilityRow.addView(floatingButton, weightWrap(1f, dp(4)));
        utilityRow.addView(logToggle, weightWrap(1f, dp(4)));
        controlCard.addView(utilityRow, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("拍摄与切换前请先停止游戏；切换只写该游戏的账号文件并自动备份。"
                + "切换后等游戏加载完成，再在该账号卡片上点「更新此快照」。");
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.text_muted));
        hint.setPadding(0, dp(12), 0, 0);
        controlCard.addView(hint, matchWrap());
        content.addView(controlCard, withBottomMargin(matchWrap(), dp(4)));

        content.addView(section("03 / 账号快照"), matchWrap());
        cardList = new LinearLayout(this);
        cardList.setOrientation(LinearLayout.VERTICAL);
        content.addView(cardList, matchWrap());

        content.addView(section("04 / 操作日志"), matchWrap());
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(getColor(R.color.text_muted));
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        logScroll.setBackground(surface(R.color.log_bg, R.color.log_bg, 22));
        logScroll.setPadding(dp(16), dp(14), dp(16), dp(14));
        content.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11);
        view.setTextColor(getColor(R.color.text_muted));
        view.setLetterSpacing(0.08f);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private Button pill(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(getColor(primary ? R.color.on_accent : R.color.accent));
        button.setBackground(surface(primary ? R.color.accent : R.color.accent_tonal,
                primary ? R.color.accent : R.color.accent_tonal, 24));
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "0.0";
        }
    }

    private void toggleLog() {
        if (logScroll == null) {
            return;
        }
        boolean visible = logScroll.getVisibility() == View.VISIBLE;
        logScroll.setVisibility(visible ? View.GONE : View.VISIBLE);
        logToggle.setText(visible ? "显示日志" : "隐藏日志");
    }

    private GradientDrawable surface(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(getColor(fill));
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), getColor(stroke));
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

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

    private static LinearLayout.LayoutParams weightWrap(float weight, int sideMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.leftMargin = sideMargin;
        params.rightMargin = sideMargin;
        return params;
    }

    private void selectGame(GameProfile profile) {
        if (busy || profile == game) {
            return;
        }
        game = profile;
        appendLog("切换到分栏：" + profile.label);
        refresh();
    }

    private void refresh() {
        final GameProfile selected = game;
        final int generation = ++refreshGeneration;
        new Thread(() -> {
            boolean rooted = RootShell.isRooted();
            boolean running;
            String version;
            List<SnapshotStore.Snapshot> snapshots;
            SnapshotStore.Current current;
            try {
                running = selected.isRunning();
                version = selected.version();
            } catch (IOException error) {
                running = false;
                version = "";
            }
            snapshots = store.list(selected);
            try {
                current = store.detectCurrent(selected);
            } catch (IOException error) {
                current = null;
            }
            final boolean rootedNow = rooted;
            final boolean runningNow = running;
            final String versionNow = version;
            final List<SnapshotStore.Snapshot> listed = snapshots;
            final SnapshotStore.Current currentNow = current;
            main.post(() -> {
                if (generation != refreshGeneration) {
                    return;
                }
                SnapshotStore.Snapshot matched = match(currentNow, listed);
                String rootText = rootedNow ? "Root 正常" : "未取得 Root：" + RootShell.lastError();
                String gameText = versionNow.isEmpty()
                        ? "未检测到" + selected.label
                        : selected.label + " " + versionNow;
                String accountText = selected.supported
                        ? " · 当前账号：" + describe(currentNow, matched)
                        : " · 尚未适配";
                statusView.setText(rootText + " · " + gameText + " · "
                        + (runningNow ? "游戏运行中" : "游戏已停止") + accountText);
                captureButton.setEnabled(rootedNow && selected.supported && !runningNow && !busy);
                launchButton.setEnabled(!selected.activity.isEmpty() && !busy);
                renderTabs();
                renderCards(listed, matched == null ? null : matched.id, selected);
            });
        }).start();
    }

    private void renderTabs() {
        for (int index = 0; index < gameButtons.size(); index++) {
            Button button = gameButtons.get(index);
            GameProfile profile = GameProfile.ALL.get(index);
            boolean selected = profile == game;
            button.setText(profile.label);
            button.setSelected(selected);
            button.setAlpha(profile.supported ? 1f : 0.55f);
            button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            button.setTextColor(getColor(selected ? R.color.on_accent : R.color.text_muted));
            button.setBackground(selected
                    ? surface(R.color.accent, R.color.accent, 20)
                    : surface(R.color.card_bg, R.color.card_bg, 20));
        }
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

    private void renderCards(List<SnapshotStore.Snapshot> snapshots, String currentId,
                             GameProfile selected) {
        cardList.removeAllViews();
        if (!selected.supported) {
            TextView unsupported = new TextView(this);
            unsupported.setText(selected.label + " 尚未适配：状态文件清单还在调研，暂不能拍摄或切换。");
            unsupported.setPadding(0, 24, 0, 0);
            cardList.addView(unsupported, matchWrap());
            return;
        }
        if (snapshots.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("这个分栏还没有快照。登录 " + selected.label + " 后停止游戏，点「拍摄快照」。");
            empty.setPadding(0, 24, 0, 0);
            cardList.addView(empty, matchWrap());
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (SnapshotStore.Snapshot snapshot : snapshots) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            boolean isCurrent = snapshot.id.equals(currentId);
            card.setBackground(isCurrent
                    ? surface(R.color.card_bg, R.color.accent, 26)
                    : surface(R.color.card_bg, R.color.card_bg, 26));
            int cardPad = dp(20);
            card.setPadding(cardPad, dp(18), cardPad, dp(18));

            TextView label = new TextView(this);
            label.setTypeface(null, Typeface.BOLD);
            label.setText(isCurrent ? snapshot.name + "　（当前登录）" : snapshot.name);
            label.setTextSize(18);
            label.setTextColor(getColor(R.color.text_primary));
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
            meta.setTextColor(getColor(R.color.text_muted));
            meta.setPadding(0, dp(4), 0, dp(14));
            card.addView(meta, matchWrap());

            Button switchButton = pill("切换到此账号", true);
            switchButton.setOnClickListener(view -> confirmSwitch(snapshot));
            card.addView(switchButton, withBottomMargin(matchWrap(), dp(8)));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Button updateButton = pill("更新此快照", false);
            updateButton.setOnClickListener(view -> runOp("更新快照 " + snapshot.name,
                    () -> AccountUpdater.updateTarget(store, snapshot, this::appendLog)));
            Button moreButton = pill("⋯", false);
            moreButton.setContentDescription("更多操作：" + snapshot.name);
            row.addView(updateButton, weightWrap(1f, dp(4)));
            LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(56),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            moreParams.leftMargin = dp(4);
            moreParams.rightMargin = dp(4);
            row.addView(moreButton, moreParams);
            card.addView(row, matchWrap());

            cardList.addView(card, withBottomMargin(matchWrap(), dp(14)));
        }
    }

    private static LinearLayout.LayoutParams withBottomMargin(LinearLayout.LayoutParams params,
                                                              int margin) {
        params.bottomMargin = margin;
        return params;
    }

    private void showCardMenu(SnapshotStore.Snapshot snapshot) {
        String[] items = {"重命名", "删除快照", "历史版本"};
        new AlertDialog.Builder(this)
                .setTitle(snapshot.name)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        renameDialog(snapshot);
                    } else if (which == 1) {
                        deleteDialog(snapshot);
                    } else {
                        historyDialog(snapshot);
                    }
                })
                .show();
    }

    private void renameDialog(SnapshotStore.Snapshot snapshot) {
        EditText input = new EditText(this);
        input.setText(snapshot.name);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("重命名快照")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> runOp("重命名",
                        () -> store.rename(snapshot, input.getText().toString())))
                .show();
    }

    private void deleteDialog(SnapshotStore.Snapshot snapshot) {
        new AlertDialog.Builder(this)
                .setTitle("删除快照")
                .setMessage("将删除「" + snapshot.name + "」及其历史版本。不会影响游戏内的账号。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> runOp("删除快照",
                        () -> store.delete(snapshot)))
                .show();
    }

    private void historyDialog(SnapshotStore.Snapshot snapshot) {
        List<SnapshotStore.HistoryEntry> entries = store.history(snapshot);
        if (entries.isEmpty()) {
            toast("这个快照还没有历史版本");
            return;
        }
        String[] labels = new String[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            labels[index] = entries.get(index).label;
        }
        new AlertDialog.Builder(this)
                .setTitle("历史版本 · " + snapshot.name)
                .setItems(labels, (dialog, which) -> confirmHistory(snapshot, entries.get(which)))
                .show();
    }

    private void confirmHistory(SnapshotStore.Snapshot snapshot, SnapshotStore.HistoryEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("回滚到 " + entry.label)
                .setMessage("快照内容会替换为该历史版本，当前版本先归档保留。继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("回滚", (dialog, which) -> runOp("回滚历史版本",
                        () -> store.restoreHistory(snapshot, entry)))
                .show();
    }

    private void confirmSwitch(SnapshotStore.Snapshot target) {
        if (busy) {
            toast("上一个操作还没结束");
            return;
        }
        busy = true;
        appendLog("读取当前账号…");
        new Thread(() -> {
            try {
                SnapshotStore.Current current = store.detectCurrent(target.game);
                List<SnapshotStore.Snapshot> snapshots = store.list(target.game);
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
                .setTitle("切换" + target.game.label + "账号")
                .setMessage("当前账号：" + currentLabel
                        + "\n目标账号：" + target.name
                        + "\n\n将先停止游戏，再写入该游戏的 4 个账号文件，并自动保存切换前状态。继续？")
                .setNegativeButton("取消", (dialog, which) -> {
                    busy = false;
                    refresh();
                })
                .setOnCancelListener(dialog -> {
                    busy = false;
                    refresh();
                })
                .setPositiveButton("切换", (dialog, which) -> new Thread(() -> {
                    try {
                        if (target.game.isRunning()) {
                            target.game.forceStop();
                            appendLog("已停止" + target.game.label);
                        }
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
        final GameProfile selected = game;
        String name = nameInput.getText().toString();
        runOp("拍摄快照", () -> {
            String id = store.capture(selected, name);
            appendLog("已保存快照 " + id);
            main.post(() -> nameInput.setText(""));
        });
    }

    private void toggleFloating() {
        if (!game.supported) {
            toast(game.label + " 尚未适配，悬浮按钮只支持已验证的游戏");
            return;
        }
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
        if (isFloatingRunning()) {
            stopService(new Intent(this, FloatingService.class));
            toast("悬浮按钮已关闭");
        } else {
            Intent intent = new Intent(this, FloatingService.class);
            intent.putExtra(FloatingService.EXTRA_PACKAGE, game.pkg);
            startForegroundService(intent);
            toast("悬浮按钮已开启（" + game.label + "）：可拖动，点它更新快照");
        }
    }

    /** The static flag can go stale if the process was killed; confirm with the system. */
    private boolean isFloatingRunning() {
        try {
            android.app.ActivityManager manager =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningServiceInfo info
                    : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (FloatingService.class.getName().equals(info.service.getClassName())
                        && info.pid == android.os.Process.myPid()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException error) {
            return FloatingService.isRunning();
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
            String line = time + "  " + message;
            String text = logView.getText().toString();
            String next = text.isEmpty() ? line : text + "\n" + line;
            String[] lines = next.split("\n");
            if (lines.length > 200) {
                StringBuilder builder = new StringBuilder();
                for (int index = lines.length - 200; index < lines.length; index++) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(lines[index]);
                }
                next = builder.toString();
            }
            logView.setText(next);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
