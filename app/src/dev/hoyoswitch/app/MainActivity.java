package dev.hoyoswitch.app;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.Log;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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
    private Button recoveryRetry;
    private TextView logSection;
    private LinearLayout cardList;
    private TextView logView;
    private ScrollView logScroll;
    private Button captureButton;
    private Button launchButton;
    private static final String PREFS_NAME = "ui";
    private static final String PREF_GAME = "game";
    private static final String PREF_SORT = "sort_mode";
    private static final String PREF_ORDER = "fixed_order_";
    private static final String SWITCH_ACCOUNT_LABEL = "切换到此账号";
    private static final int SORT_LAST_USED = 0;
    private static final int SORT_FIXED = 1;
    private int sortMode = SORT_LAST_USED;
    private final List<String> fixedOrder = new ArrayList<>();
    private CardDrag cardDrag;
    private boolean cardOrderRenderPending;
    private final Runnable dragScrollStep = this::scrollDuringCardDrag;
    private boolean cardOpActive;
    private int focusedCardIndex = -1;
    private String focusedCardId;
    private ViewGroup logHome;
    private View currentCardView;
    private boolean followCurrentCard;

    private volatile int refreshGeneration;
    private boolean refreshInFlight;
    private boolean pageResumed;
    private SnapshotStore.RefreshData displayedData;
    // Presentation only: cached cards never authorize actions before the fresh read completes.
    private final Map<GameProfile, SnapshotStore.RefreshData> tabPreviews = new HashMap<>();
    private final Map<GameProfile, String> versionLabels = new HashMap<>();
    private int previewGeneration;
    private boolean renderingPreview;
    private List<Object> renderedCardSignature;
    private long tabDisplayStarted;
    private int tabDisplayRequest;
    private String displayedStatusPrefix;
    private String displayedTechnicalStatus;
    private boolean cardActionsAvailable;
    private final Consumer<Boolean> settingsObserver = floatingChanged -> {
        if (floatingChanged) {
            syncSavedFloating();
        } else {
            setLogVisible(UiSettings.showLog(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)));
        }
    };
    private final Consumer<GameProfile> snapshotObserver = selected -> {
        tabPreviews.remove(selected);
        store.invalidateDisplayChecks();
        if (selected == game) {
            refresh();
        }
    };
    private ScrollView pageScroll;
    private ScrollView columnScroll;
    private Dialog visibleDialog;
    /** Process-wide: a long write must stay exclusive even if the page is recreated. */
    private static volatile boolean busy;
    /** Most recently resumed instance, so an operation finishing after a rotation still lands. */
    private static MainActivity current;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppAppearance.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Start on the tab the app was closed on; self tests then run against that game.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        game = GameProfile.byPackage(UiSettings.readString(prefs, PREF_GAME, GameProfile.STARRAIL.pkg));
        if (state != null) {
            game = GameProfile.byPackage(state.getString("ui.game", game.pkg));
        }
        sortMode = readSortMode(prefs);
        loadFixedOrder();
        store = new SnapshotStore(this);
        setContentView(buildLayout());
        updateSystemBarAppearance();
        if (state != null) {
            saveGame();
            String log = state.getString("ui.log", "");
            if (logView != null && !log.isEmpty()) {
                logView.setText(log);
            }
        }
        renderTabs();
        showLoading();
        // Destructive self tests are only honoured by debug builds; a release APK ignores them.
        boolean debuggable = state == null
                && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String selftest = debuggable ? getIntent().getStringExtra("selftest") : null;
        if ("cardops".equals(selftest)) {
            main.postDelayed(this::runCardOpsTest, 1200);
        } else if (debuggable && getIntent().getBooleanExtra("selftest", false)) {
            main.postDelayed(this::runSelfTest, 1500);
        }
    }

    private void updateSystemBarAppearance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
                controller.setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
            }
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
            SnapshotStore.Snapshot baseline = store.currentAccount(target0);
            if (baseline == null) {
                baseline = store.load(target0, store.capture(target0, "回滚自检基线"));
            }
            String baselineId = baseline.id;
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
            SnapshotStore.injectedFailures = 0;
            SnapshotStore.deviceWrites = 0;
            try {
                store.switchTo(other, false, message -> Log.i("hoyoswitch", message));
                outcome = "切换没有被注入的故障打断";
            } catch (IOException error) {
                outcome = error.getMessage();
            } finally {
                SnapshotStore.failAfterWrites = -1;
            }
            boolean triggered = SnapshotStore.injectedFailures == 1;
            boolean wrote = SnapshotStore.deviceWrites > 0;
            boolean rollbackReported = outcome != null && outcome.contains("已回滚");
            boolean rolledBack = true;
            SnapshotStore.Snapshot expected = store.load(target0, baselineId);
            for (String relative : target0.switchFiles) {
                SnapshotStore.Entry entry = expected.entry(relative);
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
            String verdict = rolledBack && triggered && wrote && rollbackReported ? "PASS" : "FAIL";
            Log.i("hoyoswitch", "SELFTEST " + verdict + " :: " + outcome
                    + " :: injected=" + triggered + " writes=" + wrote
                    + " rollback=" + rollbackReported + " bytes=" + rolledBack);
            appendLog("自检结果：" + (rolledBack ? "回滚成功" : "回滚失败") + "；" + outcome);
        });
    }

    @Override
    protected void onDestroy() {
        if (current == this) {
            current = null;
        }
        if (visibleDialog != null) {
            visibleDialog.dismiss();
            visibleDialog = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("ui.game", game.pkg);
        if (logView != null) {
            out.putString("ui.log", logView.getText().toString());
        }
    }

    @Override
    protected void onPause() {
        pageResumed = false;
        ++previewGeneration;
        cancelCardDrag();
        ++refreshGeneration;
        FloatingService.removeSnapshotObserver(snapshotObserver);
        UiSettings.removeObserver(settingsObserver);
        super.onPause();
        saveGame();
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        pageResumed = true;
        // Package updates can occur while the app is in the background.
        versionLabels.clear();
        store.invalidateDisplayChecks();
        FloatingService.addSnapshotObserver(snapshotObserver);
        UiSettings.addObserver(settingsObserver);
        refresh();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        setLogVisible(UiSettings.showLog(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)));
        syncSavedFloating();
    }

    private void syncSavedFloating() {
        if (UiSettings.floatingGame(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)) == null) {
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            startSavedFloating();
        } else if (FloatingService.isRunning()) {
            stopService(new Intent(this, FloatingService.class));
        }
    }

    /** Landscape keeps the controls and account cards in independently scrolling columns. */
    private LinearLayout buildTwoColumns(LinearLayout root, View header, View tabs,
                                         View status, View actions, View accountsHeading) {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(header, withBottomMargin(matchWrap(), dp(12)));
        addQuickControls(left, tabs, status, actions);

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.addView(left);
        leftScroll.setOnTouchListener((view, event) -> cardOpActive);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.95f);
        leftParams.rightMargin = dp(16);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        addAccounts(right, accountsHeading, 180);
        logHome = right;

        ScrollView rightScroll = new LockableScrollView(this);
        columnScroll = rightScroll;
        rightScroll.addView(right);
        // Focus restore can scroll a column down on first layout; start both at the top.
        leftScroll.post(() -> leftScroll.scrollTo(0, 0));
        rightScroll.post(() -> rightScroll.scrollTo(0, 0));

        root.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(leftScroll, leftParams);
        root.addView(rightScroll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.05f));
        return root;
    }

    private LinearLayout buildLayout() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(14), pad, dp(14));
        // targetSdk 36 draws edge to edge; add the system bar insets on top of our own padding.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(pad + insets.getSystemWindowInsetLeft(), dp(14) + insets.getSystemWindowInsetTop(),
                    pad + insets.getSystemWindowInsetRight(), dp(14) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = new ImageView(this);
        // The source icon is 1650px; decode a small copy instead of a ~11 MB bitmap.
        BitmapFactory.Options avatarOptions = new BitmapFactory.Options();
        avatarOptions.inSampleSize = 8;
        android.graphics.Bitmap avatarBitmap =
                BitmapFactory.decodeResource(getResources(), R.drawable.app_icon, avatarOptions);
        if (avatarBitmap != null) {
            avatar.setImageBitmap(avatarBitmap);
        }
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setColor(getColor(R.color.card_bg));
        avatarBg.setCornerRadius(dp(18));
        avatar.setBackground(avatarBg);
        avatar.setClipToOutline(true);
        headerRow.addView(avatar, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout headerText = new LinearLayout(this);
        headerText.setOrientation(LinearLayout.VERTICAL);
        headerText.setPadding(dp(14), 0, 0, 0);
        TextView appName = new TextView(this);
        appName.setText("米游账号切换");
        appName.setTextSize(20);
        appName.setTypeface(null, Typeface.BOLD);
        appName.setTextColor(getColor(R.color.text_primary));
        if (Build.VERSION.SDK_INT >= 28) {
            appName.setAccessibilityHeading(true);
        }
        headerText.addView(appName, matchWrap());
        TextView appSubtitle = new TextView(this);
        appSubtitle.setText("切号，轻一点。");
        appSubtitle.setTextSize(12);
        appSubtitle.setTextColor(getColor(R.color.text_muted));
        headerText.addView(appSubtitle, matchWrap());
        headerRow.addView(headerText, weightWrap());

        TextView versionChip = new TextView(this);
        versionChip.setText("App " + currentVersion());
        versionChip.setTextSize(12);
        versionChip.setTextColor(getColor(R.color.accent));
        versionChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        versionChip.setBackground(surface(R.color.pill_bg, R.color.pill_bg, 20));
        headerRow.addView(versionChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        LinearLayout statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.addView(statusPill, matchWrap());
        recoveryRetry = pill("已停止游戏，重试恢复", false);
        recoveryRetry.setVisibility(View.GONE);
        recoveryRetry.setOnClickListener(view -> refreshAllStatus());
        statusPanel.addView(recoveryRetry, withTopMargin(matchWrap(), dp(4)));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(surface(R.color.card_bg, R.color.card_bg, 26));
        tabs.setPadding(dp(8), dp(8), dp(8), dp(8));
        int tabCount = GameProfile.ALL.size();
        for (int index = 0; index < tabCount; index++) {
            GameProfile profile = GameProfile.ALL.get(index);
            Button tab = pill(profile.label, false);
            tab.setSingleLine(true);
            tab.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tab.setOnClickListener(view -> selectGame(profile));
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (index > 0) {
                tabParams.leftMargin = dp(6);
            }
            tabs.addView(tab, tabParams);
            gameButtons.add(tab);
        }
        renderTabs();

        LinearLayout primaryRow = new LinearLayout(this);
        primaryRow.setOrientation(LinearLayout.HORIZONTAL);
        captureButton = pill("拍摄快照", true);
        captureButton.setOnClickListener(view -> capture());
        launchButton = pill("启动游戏", false);
        launchButton.setOnClickListener(view -> {
            GameProfile selected = game;
            runOp("启动 " + selected.label, () -> store.launchGame(selected));
        });
        Button toolsButton = pill("⋯", false);
        toolsButton.setContentDescription("更多工具");
        toolsButton.setOnClickListener(view -> showTools());
        primaryRow.addView(captureButton, weightWrap(1.4f, dp(4)));
        primaryRow.addView(launchButton, weightWrap(1f, dp(4)));
        primaryRow.addView(toolsButton, new LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView accountsHeading = section("账号快照");
        cardList = new LinearLayout(this);
        cardList.setOrientation(LinearLayout.VERTICAL);
        cardList.setOnDragListener((view, event) -> handleCardDrag(event));

        logSection = section("操作日志");
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(getColor(R.color.text_muted));
        logView.setLineSpacing(dp(2), 1f);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        logScroll.setBackground(surface(R.color.log_bg, R.color.log_bg, 22));
        logScroll.setPadding(dp(16), dp(14), dp(16), dp(14));
        setLogVisible(UiSettings.showLog(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)));

        boolean wide = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (wide) {
            return buildTwoColumns(root, headerRow, tabs, statusPanel, primaryRow, accountsHeading);
        }
        root.addView(headerRow, withBottomMargin(matchWrap(), dp(12)));
        ScrollView scroll = new LockableScrollView(this);
        pageScroll = scroll;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(8));
        logHome = content;
        addQuickControls(content, tabs, statusPanel, primaryRow);
        addAccounts(content, accountsHeading, 120);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void addQuickControls(LinearLayout parent, View tabs, View status, View actions) {
        parent.addView(tabs, withBottomMargin(matchWrap(), dp(8)));
        parent.addView(status, withBottomMargin(matchWrap(), dp(8)));
        parent.addView(actions, matchWrap());
    }

    private void addAccounts(LinearLayout parent, View heading, int logHeight) {
        parent.addView(heading, matchWrap());
        parent.addView(cardList, matchWrap());
        parent.addView(logSection, matchWrap());
        parent.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(logHeight)));
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11);
        view.setTextColor(getColor(R.color.text_muted));
        view.setLetterSpacing(0.08f);
        view.setPadding(0, dp(18), 0, dp(10));
        return view;
    }

    private Button pill(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(getColor(primary ? R.color.on_accent : R.color.accent));
        GradientDrawable fill = surface(primary ? R.color.accent : R.color.accent_tonal,
                primary ? R.color.accent : R.color.accent_tonal, 24);
        button.setBackground(ripple(fill));
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private RippleDrawable ripple(GradientDrawable content) {
        int color = (getColor(R.color.text_primary) & 0x00FFFFFF) | 0x22000000;
        return new RippleDrawable(ColorStateList.valueOf(color), content, null);
    }

    private String currentVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null || version.isEmpty() ? "0.0" : version;
        } catch (PackageManager.NameNotFoundException error) {
            return "0.0";
        }
    }

    private void toggleLog() {
        boolean show = logScroll.getVisibility() != View.VISIBLE;
        if (!UiSettings.setShowLog(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), show,
                this::runOnUiThread, saved -> {
                    if (pageResumed) {
                        if (!saved) {
                            toast("未能保存操作日志设置，请重试");
                        } else if (show) {
                            scrollToView(logSection);
                        }
                    }
                })) {
            toast("正在保存设置，请稍后再试");
        }
    }

    private void setLogVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        logScroll.setVisibility(visibility);
        logSection.setVisibility(visibility);
    }

    private void showTools() {
        List<Action> actions = new ArrayList<>();
        actions.add(new Action(android.R.drawable.ic_menu_info_details, "运行详情",
                "查看 Root、游戏版本和进程状态", false, () -> showInfo("运行详情",
                displayedTechnicalStatus == null ? "尚未读取状态，请刷新。" : displayedTechnicalStatus)));
        actions.add(new Action(android.R.drawable.ic_popup_sync, "刷新", "重新读取账号和游戏状态",
                false, this::refreshAllStatus));
        actions.add(new Action(android.R.drawable.ic_menu_preferences, "主题",
                "当前选择：" + AppAppearance.mode(this).title, false, this::showAppearanceChoices));
        GameProfile floatingGame = UiSettings.floatingGame(getSharedPreferences(PREFS_NAME, MODE_PRIVATE));
        String floatingStatus = floatingGame == null ? "在游戏内更新当前快照"
                : "绑定：" + floatingGame.label + (FloatingService.isRunning() ? "" : " · 待恢复");
        actions.add(new Action(android.R.drawable.ic_menu_preferences,
                floatingGame == null ? "开启悬浮按钮" : "关闭悬浮按钮", floatingStatus,
                false, this::toggleFloating));
        actions.add(new Action(R.drawable.ic_history,
                logScroll.getVisibility() == View.VISIBLE ? "隐藏操作日志" : "查看操作日志",
                "查看最近的操作和错误信息", false, this::toggleLog));
        actions.add(new Action(android.R.drawable.ic_menu_sort_by_size, "切换排序方式",
                sortMode == SORT_LAST_USED ? "当前按操作时间排序" : "当前使用固定顺序",
                false, this::toggleSort));
        actions.add(new Action(android.R.drawable.ic_menu_gallery, "捐赠", "自愿支持项目开发",
                false, this::showDonationPage));
        actions.add(new Action(android.R.drawable.ic_menu_info_details, "关于", "项目地址、使用说明与免责声明",
                false, this::showAboutPage));
        showActionSheet("更多工具", actions);
    }

    private void showDonationPage() {
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogTitle("捐赠"), withBottomMargin(matchWrap(), dp(10)));
        card.addView(dialogMessage(getText(R.string.donation_intro)), matchWrap());
        addDonationCode(card, "微信赞赏", R.drawable.donation_wechat);
        addDonationCode(card, "支付宝", R.drawable.donation_alipay);
        closeInfoPage(dialog, card);
    }

    private void addDonationCode(LinearLayout card, String label, int imageRes) {
        card.addView(section(label), matchWrap());
        ImageView code = new ImageView(this);
        code.setImageResource(imageRes);
        code.setAdjustViewBounds(true);
        int viewportHeight = getResources().getDisplayMetrics().heightPixels;
        code.setMaxHeight(Math.min(dp(460), viewportHeight * 3 / 4));
        code.setScaleType(ImageView.ScaleType.FIT_CENTER);
        code.setContentDescription(label + "捐赠二维码");
        card.addView(code, matchWrap());
    }

    private void showAboutPage() {
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogTitle("免责声明"), withBottomMargin(matchWrap(), dp(10)));
        card.addView(dialogMessage(getText(R.string.about_disclaimer)), matchWrap());

        card.addView(section("关于本项目"), matchWrap());
        String projectUrl = getString(R.string.project_url);
        TextView address = dialogMessage(projectUrl);
        address.setTextIsSelectable(true);
        card.addView(address, withBottomMargin(matchWrap(), dp(8)));
        Button open = pill("打开项目主页", false);
        open.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(projectUrl)));
            } catch (ActivityNotFoundException | SecurityException error) {
                toast("打不开项目主页，可长按复制项目地址");
            }
        });
        card.addView(open, matchWrap());
        card.addView(section("使用说明"), matchWrap());
        card.addView(dialogMessage(getString(R.string.about_quick_start)), matchWrap());
        closeInfoPage(dialog, card);
    }

    private void closeInfoPage(Dialog dialog, LinearLayout card) {
        Button close = pill("关闭", false);
        close.setOnClickListener(view -> dialog.dismiss());
        card.addView(close, withTopMargin(matchWrap(), dp(12)));
        showDialog(dialog, card);
    }

    private void showAppearanceChoices() {
        if (busy) {
            toast("请等当前操作结束后再切换主题");
            return;
        }
        AppAppearance.Mode selected = AppAppearance.mode(this);
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogTitle("主题"), withBottomMargin(matchWrap(), dp(8)));
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (AppAppearance.Mode mode : AppAppearance.Mode.values()) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(mode.title);
            option.setTextColor(getColor(R.color.text_primary));
            option.setMinHeight(dp(48));
            option.setPadding(dp(4), 0, 0, 0);
            option.setButtonTintList(new ColorStateList(new int[][]{
                    {android.R.attr.state_checked}, new int[0]},
                    new int[]{getColor(R.color.accent), getColor(R.color.text_muted)}));
            group.addView(option, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (mode == selected) {
                group.check(option.getId());
            }
            option.setOnClickListener(view -> {
                dialog.dismiss();
                if (busy) {
                    toast("请等当前操作结束后再切换主题");
                } else if (mode != selected) {
                    AppAppearance.select(this, mode);
                    recreate();
                }
            });
        }
        card.addView(group, withBottomMargin(matchWrap(), dp(8)));
        Button close = pill("关闭", false);
        close.setOnClickListener(view -> dialog.dismiss());
        card.addView(close, matchWrap());
        showDialog(dialog, card);
    }

    private void showInfo(String title, String message) {
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogTitle(title), withBottomMargin(matchWrap(), dp(10)));
        card.addView(dialogMessage(message), withBottomMargin(matchWrap(), dp(14)));
        Button done = pill("关闭", false);
        done.setOnClickListener(view -> dialog.dismiss());
        card.addView(done, matchWrap());
        showDialog(dialog, card);
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
        saveGame();
        loadFixedOrder();
        displayedData = null;
        followCurrentCard = false;
        renderTabs();
        showLoading();
        appendLog("切换到分栏：" + profile.label);
        refresh();
    }

    private void showLoading() {
        tabDisplayStarted = SystemClock.uptimeMillis();
        ++tabDisplayRequest;
        final int request = ++previewGeneration;
        displayedStatusPrefix = null;
        displayedTechnicalStatus = null;
        cardActionsAvailable = false;
        recoveryRetry.setVisibility(View.GONE);
        if (statusView != null) {
            statusView.setTextColor(getColor(R.color.accent));
            statusView.setText("正在加载" + game.label + "…");
        }
        if (captureButton != null) {
            captureButton.setEnabled(false);
            captureButton.setAlpha(0.45f);
        }
        if (launchButton != null) {
            launchButton.setEnabled(false);
            launchButton.setAlpha(0.45f);
        }
        if (cardList != null) {
            cancelCardDrag();
            cardList.removeAllViews();
            renderedCardSignature = null;
            SnapshotStore.RefreshData preview = tabPreviews.get(game);
            if (preview != null) {
                renderCardPreview(game, preview.snapshots);
            } else {
                TextView loading = new TextView(this);
                loading.setText("正在读取卡片列表…");
                loading.setTextColor(getColor(R.color.text_muted));
                loading.setPadding(0, dp(20), 0, dp(20));
                cardList.addView(loading, matchWrap());
                final GameProfile selected = game;
                new Thread(() -> {
                    try {
                        List<SnapshotStore.Snapshot> cards = store.readCardPreview(selected);
                        main.post(() -> {
                            if (request == previewGeneration && selected == game
                                    && !isFinishing() && !isDestroyed() && !busy) {
                                renderCardPreview(selected, cards);
                            }
                        });
                    } catch (RuntimeException error) {
                        Log.w("hoyoswitch", "card preview failed", error);
                    }
                }, "card-preview").start();
            }
        }
        currentCardView = null;
    }

    private void renderCardPreview(GameProfile selected, List<SnapshotStore.Snapshot> snapshots) {
        List<SnapshotStore.Snapshot> cards = new ArrayList<>(snapshots);
        sortSnapshots(cards);
        renderingPreview = true;
        try {
            renderCards(cards, null, selected, Collections.emptyMap());
            setPreviewEnabled(cardList, false);
            recordTabFrame("preview", cards.size());
        } finally {
            renderingPreview = false;
        }
    }

    private void recordTabFrame(String phase, int count) {
        final int request = tabDisplayRequest;
        final long started = tabDisplayStarted;
        final String label = game.pkg;
        cardList.postOnAnimation(() -> {
            if (request == tabDisplayRequest && pageResumed && !isDestroyed()) {
                Log.i("HoyoUiTiming", "tab=" + label + " phase=" + phase + " cards=" + count
                        + " frame_callback_ms=" + (SystemClock.uptimeMillis() - started));
            }
        });
    }

    private static void setPreviewEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                setPreviewEnabled(group.getChildAt(index), enabled);
            }
        }
    }

    private void saveGame() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_GAME, game.pkg).apply();
    }

    private void loadFixedOrder() {
        fixedOrder.clear();
        String raw = UiSettings.readString(getSharedPreferences(PREFS_NAME, MODE_PRIVATE),
                PREF_ORDER + game.pkg, "");
        if (raw != null && !raw.isEmpty()) {
            for (String id : raw.split(",")) {
                if (!id.isEmpty()) {
                    fixedOrder.add(id);
                }
            }
        }
    }

    static int readSortMode(SharedPreferences prefs) {
        return UiSettings.readInt(prefs, PREF_SORT, SORT_LAST_USED) == SORT_FIXED
                ? SORT_FIXED : SORT_LAST_USED;
    }

    private void saveFixedOrder(GameProfile selected) {
        StringBuilder builder = new StringBuilder();
        for (String id : fixedOrder) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(id);
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_ORDER + selected.pkg, builder.toString())
                .putInt(PREF_SORT, sortMode).apply();
    }

    private void toggleSort() {
        if (busy) {
            // Re-sorting during an operation would rewrite fixedOrder from stale data.
            toast("上一个操作还没结束");
            return;
        }
        sortMode = sortMode == SORT_LAST_USED ? SORT_FIXED : SORT_LAST_USED;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(PREF_SORT, sortMode).apply();
        renderDisplayedCards(game);
    }

    /** Seeds the fixed order from an already-fetched list, ordered by descending activity time. */
    private void seedFixedOrderFrom(GameProfile selected, List<SnapshotStore.Snapshot> snapshots) {
        snapshots.sort((left, right) -> Long.compare(activityTime(right), activityTime(left)));
        fixedOrder.clear();
        for (SnapshotStore.Snapshot snapshot : snapshots) {
            fixedOrder.add(snapshot.id);
        }
        saveFixedOrder(selected);
    }

    private void sortSnapshots(List<SnapshotStore.Snapshot> snapshots) {
        if (sortMode == SORT_LAST_USED) {
            snapshots.sort((left, right) -> Long.compare(activityTime(right), activityTime(left)));
            return;
        }
        snapshots.sort((left, right) -> {
            int il = fixedOrder.indexOf(left.id);
            int ir = fixedOrder.indexOf(right.id);
            if (il >= 0 && ir >= 0) {
                return Integer.compare(il, ir);
            }
            if (il >= 0) {
                return -1;
            }
            if (ir >= 0) {
                return 1;
            }
            return left.id.compareTo(right.id);
        });
    }

    private static long activityTime(SnapshotStore.Snapshot snapshot) {
        // A capture/update refreshes createdAt while a switch refreshes lastUsedAt; the most
        // recent of the two is the card's latest activity.
        return Math.max(snapshot.lastUsedAt, snapshot.createdAt);
    }

    private ScrollView activeScroll() {
        return columnScroll != null ? columnScroll : pageScroll;
    }

    /** Smooth-scrolls so the currently logged-in card sits at the top of the viewport. */
    private void scrollToCard() {
        scrollToView(currentCardView);
    }

    private void scrollToView(View target) {
        ScrollView scroll = activeScroll();
        if (scroll == null || target == null) {
            return;
        }
        scroll.post(() -> {
            View child = scroll.getChildAt(0);
            if (child == null) {
                return;
            }
            int top = target.getTop();
            View parent = (View) target.getParent();
            while (parent != null && parent != child) {
                top += parent.getTop();
                parent = (View) parent.getParent();
            }
            if (parent == child && pageResumed && !isDestroyed() && !isFinishing()) {
                scroll.smoothScrollTo(0, Math.max(0, top));
            }
        });
    }

    /**
     * Locks the viewport on a card being operated on and moves the log right below it, so one
     * screen shows the card on top and the log underneath. Released as soon as the operation ends.
     */
    private void focusCard(SnapshotStore.Snapshot snapshot) {
        View card = cardViewFor(snapshot.id);
        if (cardOpActive || snapshot.game != game || card == null) {
            return;
        }
        cardOpActive = true;
        setScrollLock(true);
        moveLogBelowCard(snapshot.id);
        scrollToView(card);
    }

    private void unfocusCard() {
        if (!cardOpActive) {
            return;
        }
        restoreLogToBottom();
        setScrollLock(false);
        cardOpActive = false;
    }

    /** Locks the account viewport by disabling scrolling outright, so child views cannot bypass it. */
    private void setScrollLock(boolean locked) {
        ScrollView scroll = activeScroll();
        if (scroll instanceof LockableScrollView) {
            ((LockableScrollView) scroll).setLocked(locked);
        }
    }

    private void moveLogBelowCard(String snapshotId) {
        int cardIndex = cardIndexFor(snapshotId);
        if (cardIndex < 0) {
            return;
        }
        focusedCardIndex = cardIndex;
        focusedCardId = snapshotId;
        if (logSection != null && logSection.getParent() == logHome) {
            logHome.removeView(logSection);
        }
        if (logScroll != null && logScroll.getParent() == logHome) {
            logHome.removeView(logScroll);
        }
        int insertAt = cardIndex + 1;
        if (logSection != null && logSection.getParent() == null) {
            cardList.addView(logSection, insertAt);
        }
        if (logScroll != null && logScroll.getParent() == null) {
            cardList.addView(logScroll, insertAt + 1,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
        }
    }

    private void restoreLogToBottom() {
        if (logSection != null && logSection.getParent() != logHome) {
            ViewGroup parent = (ViewGroup) logSection.getParent();
            if (parent != null) {
                parent.removeView(logSection);
            }
            logHome.addView(logSection, matchWrap());
        }
        if (logScroll != null && logScroll.getParent() != logHome) {
            ViewGroup parent = (ViewGroup) logScroll.getParent();
            if (parent != null) {
                parent.removeView(logScroll);
            }
            logHome.addView(logScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(columnScroll == null ? 120 : 180)));
        }
        focusedCardIndex = -1;
        focusedCardId = null;
    }

    private int cardIndexFor(String snapshotId) {
        for (int index = 0; index < cardList.getChildCount(); index++) {
            View child = cardList.getChildAt(index);
            if (snapshotId.equals(child.getTag())) {
                return index;
            }
        }
        return -1;
    }

    private View cardViewFor(String snapshotId) {
        int index = cardIndexFor(snapshotId);
        return index < 0 ? null : cardList.getChildAt(index);
    }

    private static final class CardDrag {
        final View handle;
        final View source;
        final GameProfile game;
        final int sortMode;
        final List<View> cards;
        final int sourceIndex;
        final int[] listLocation = new int[2];
        final int[] scrollLocation = new int[2];
        View target;
        boolean after;
        float screenY;

        CardDrag(View handle, View source, GameProfile game, int sortMode, List<View> cards) {
            this.handle = handle;
            this.source = source;
            this.game = game;
            this.sortMode = sortMode;
            this.cards = cards;
            this.sourceIndex = cards.indexOf(source);
        }
    }

    private boolean startCardDrag(View handle, View card) {
        if (!pageResumed || busy || cardOpActive || cardDrag != null || cardOrderRenderPending
                || card.getParent() != cardList) {
            return false;
        }
        List<View> cards = new ArrayList<>();
        for (int index = 0; index < cardList.getChildCount(); index++) {
            View child = cardList.getChildAt(index);
            if (child.getTag() instanceof String) {
                cards.add(child);
            }
        }
        if (cards.size() < 2) {
            return false;
        }
        CardDrag drag = new CardDrag(handle, card, game, sortMode, cards);
        cardDrag = drag;
        if (!handle.startDragAndDrop(null, new View.DragShadowBuilder(card), drag, 0)) {
            cardDrag = null;
            return false;
        }
        card.setAlpha(0.55f);
        return true;
    }

    private boolean handleCardDrag(DragEvent event) {
        CardDrag drag = cardDrag;
        if (drag == null || event.getLocalState() != drag) {
            return false;
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
            case DragEvent.ACTION_DRAG_ENTERED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                updateCardDrop(drag, event.getY());
                cardList.getLocationOnScreen(drag.listLocation);
                drag.screenY = drag.listLocation[1] + event.getY();
                main.removeCallbacks(dragScrollStep);
                main.post(dragScrollStep);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                main.removeCallbacks(dragScrollStep);
                markCardDrop(drag, null, false);
                return true;
            case DragEvent.ACTION_DROP:
                updateCardDrop(drag, event.getY());
                boolean moved = applyCardDrop(drag);
                clearCardDrag();
                if (moved) {
                    // Keep the old view order unavailable for another drag until it is redrawn.
                    cardOrderRenderPending = true;
                    main.post(() -> {
                        cardOrderRenderPending = false;
                        if (!isDestroyed() && !isFinishing()) {
                            renderDisplayedCards(drag.game);
                        }
                    });
                }
                return moved;
            case DragEvent.ACTION_DRAG_ENDED:
                clearCardDrag();
                return true;
            default:
                return false;
        }
    }

    private void updateCardDrop(CardDrag drag, float y) {
        int index = 0;
        while (index < drag.cards.size() - 1 && y >= drag.cards.get(index).getBottom()) {
            index++;
        }
        View candidate = drag.cards.get(index);
        boolean after = y >= candidate.getTop() + candidate.getHeight() / 2f;
        boolean moves = SnapshotReorder.destination(drag.sourceIndex, index, after) >= 0;
        markCardDrop(drag, moves ? candidate : null, moves && after);
    }

    private void markCardDrop(CardDrag drag, View target, boolean after) {
        if (drag.target == target && drag.after == after) {
            return;
        }
        if (drag.target != null && drag.target != drag.source) {
            drag.target.setForeground(null);
        }
        drag.target = target;
        drag.after = after;
        if (target != null && target != drag.source) {
            LayerDrawable marker = new LayerDrawable(new ColorDrawable[] {
                    new ColorDrawable(getColor(R.color.accent))
            });
            marker.setLayerHeight(0, dp(3));
            marker.setLayerGravity(0, (after ? Gravity.BOTTOM : Gravity.TOP) | Gravity.FILL_HORIZONTAL);
            target.setForeground(marker);
        }
    }

    private void scrollDuringCardDrag() {
        CardDrag drag = cardDrag;
        ScrollView scroll = activeScroll();
        if (drag == null || scroll == null || busy || cardOpActive) {
            return;
        }
        scroll.getLocationOnScreen(drag.scrollLocation);
        int edge = Math.min(dp(64), scroll.getHeight() / 3);
        float y = drag.screenY - drag.scrollLocation[1];
        int step = y >= 0 && y < edge ? -dp(12)
                : y <= scroll.getHeight() && y > scroll.getHeight() - edge ? dp(12) : 0;
        if (step != 0) {
            cardList.getLocationOnScreen(drag.listLocation);
            int listTop = drag.listLocation[1];
            int viewTop = drag.scrollLocation[1] + scroll.getPaddingTop();
            int viewBottom = drag.scrollLocation[1] + scroll.getHeight() - scroll.getPaddingBottom();
            // Stop at the ends of the card list, even if the page also has a log section.
            step = step < 0 ? Math.max(step, Math.min(0, listTop - viewTop))
                    : Math.min(step, Math.max(0, listTop + cardList.getHeight() - viewBottom));
            int previous = scroll.getScrollY();
            scroll.scrollBy(0, step);
            if (scroll.getScrollY() != previous) {
                cardList.getLocationOnScreen(drag.listLocation);
                updateCardDrop(drag, drag.screenY - drag.listLocation[1]);
                main.postDelayed(dragScrollStep, 16);
            }
        }
    }

    private boolean applyCardDrop(CardDrag drag) {
        if (!pageResumed || busy || cardOpActive || drag.game != game || drag.sortMode != sortMode
                || drag.source.getParent() != cardList || drag.target == null) {
            return false;
        }
        List<String> order = new ArrayList<>();
        for (View card : drag.cards) {
            if (card.getParent() != cardList) {
                return false;
            }
            order.add((String) card.getTag());
        }
        if (!SnapshotReorder.move(order, (String) drag.source.getTag(),
                (String) drag.target.getTag(), drag.after)) {
            return false;
        }
        fixedOrder.clear();
        fixedOrder.addAll(order);
        sortMode = SORT_FIXED;
        saveFixedOrder(drag.game);
        return true;
    }

    private void clearCardDrag() {
        main.removeCallbacks(dragScrollStep);
        if (cardDrag != null) {
            markCardDrop(cardDrag, null, false);
            cardDrag.source.setAlpha(1f);
            cardDrag = null;
        }
    }

    private void cancelCardDrag() {
        CardDrag drag = cardDrag;
        if (drag != null) {
            clearCardDrag();
            drag.handle.cancelDragAndDrop();
        }
    }

    private void moveCard(SnapshotStore.Snapshot snapshot, int delta) {
        if (busy || sortMode != SORT_FIXED || displayedData == null || snapshot.game != game) {
            return;
        }
        int index = fixedOrder.indexOf(snapshot.id);
        if (index < 0) {
            return;
        }
        int target = index + delta;
        if (target < 0 || target >= fixedOrder.size()) {
            return;
        }
        Collections.swap(fixedOrder, index, target);
        saveFixedOrder(snapshot.game);
        renderDisplayedCards(snapshot.game);
    }

    private void ensureFixedOrder(GameProfile selected, List<SnapshotStore.Snapshot> snapshots) {
        Set<String> present = new HashSet<>();
        for (SnapshotStore.Snapshot snapshot : snapshots) {
            present.add(snapshot.id);
        }
        List<String> kept = new ArrayList<>();
        for (String id : fixedOrder) {
            if (present.contains(id) && !kept.contains(id)) {
                kept.add(id);
            }
        }
        for (SnapshotStore.Snapshot snapshot : snapshots) {
            if (!kept.contains(snapshot.id)) {
                kept.add(snapshot.id);
            }
        }
        if (!kept.equals(fixedOrder)) {
            fixedOrder.clear();
            fixedOrder.addAll(kept);
            saveFixedOrder(selected);
        }
    }

    private void refreshAllStatus() {
        store.invalidateDisplayChecks();
        versionLabels.clear();
        refresh();
    }

    private void refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::refresh);
            return;
        }
        if (!pageResumed || isFinishing() || isDestroyed()) {
            return;
        }
        // Invalidate the old request immediately, even if a worker is still finishing.
        ++refreshGeneration;
        // Coalesce quick tab changes before starting expensive status work.
        main.removeCallbacks(refreshDispatch);
        main.postDelayed(refreshDispatch, 60);
    }

    private final Runnable refreshDispatch = this::startRefreshIfIdle;

    private void startRefreshIfIdle() {
        if (refreshInFlight || busy || !pageResumed || isFinishing() || isDestroyed()) {
            return;
        }
        refreshInFlight = true;
        final GameProfile selected = game;
        final int generation = refreshGeneration;
        final String cachedVersion = versionLabels.get(selected);
        new Thread(() -> {
            try {
                boolean rooted = RootShell.isRooted();
                Boolean running;
                String version;
                try {
                    running = selected.isRunning();
                    version = cachedVersion != null ? cachedVersion : selected.version();
                } catch (IOException error) {
                    running = null;
                    version = "";
                }
                if (generation != refreshGeneration) {
                    main.post(() -> finishRefresh(selected, generation, null, false, null, ""));
                    return;
                }
                SnapshotStore.RefreshData data = store.readRefreshData(selected, !busy);
                final Boolean runningNow = running;
                final String versionNow = version;
                main.post(() -> finishRefresh(selected, generation, data, rooted,
                        runningNow, versionNow));
            } catch (Throwable error) {
                // Catch Error too: a worker that dies without posting back would leave
                // refreshInFlight true and silently disable every later refresh.
                Log.w("hoyoswitch", "snapshot refresh failed", error);
                main.post(() -> finishRefresh(selected, generation, null, false, null, ""));
            }
        }, "snapshot-refresh").start();
    }

    private void finishRefresh(GameProfile selected, int generation, SnapshotStore.RefreshData data,
                               boolean rooted, Boolean running, String version) {
        refreshInFlight = false;
        if (!pageResumed || isFinishing() || isDestroyed()) {
            return;
        }
        if (busy) {
            return; // Operation completion starts a fresh read.
        }
        if (generation != refreshGeneration || selected != game) {
            // Only run the latest pending request, not every intervening click.
            startRefreshIfIdle();
            return;
        }
        if (data == null) {
            displayedStatusPrefix = null;
            displayedTechnicalStatus = null;
            cardActionsAvailable = false;
            // A failed read must still offer an in-page way to retry.
            recoveryRetry.setText("点此重试刷新");
            recoveryRetry.setVisibility(View.VISIBLE);
            statusView.setTextColor(getColor(R.color.danger));
            statusView.setText("刷新失败，请重试");
            captureButton.setEnabled(false);
            captureButton.setAlpha(0.45f);
            launchButton.setEnabled(false);
            launchButton.setAlpha(0.45f);
            renderDisplayedCards(selected);
            return;
        }
        displayedData = data;
        ++previewGeneration; // A slow manifest preview must not replace confirmed data.
        if (!version.isEmpty()) {
            versionLabels.put(selected, version);
        }
        tabPreviews.put(selected, data);
        String rootText = rooted ? "Root 正常" : "未取得 Root：" + RootShell.lastError();
        String gameText = version.isEmpty() ? "未检测到" + selected.label
                : selected.label + " " + version;
        String processText = running == null ? "进程状态未知" : (running ? "游戏运行中" : "游戏已停止");
        displayedTechnicalStatus = rootText + "\n" + gameText + "\n" + processText;
        boolean recoveryPending = !data.recoveryError.isEmpty();
        cardActionsAvailable = rooted && running != null && !recoveryPending;
        if (recoveryPending) {
            displayedTechnicalStatus += "\n" + data.recoveryError;
        }
        displayedStatusPrefix = rooted ? processText : "未取得 Root · " + processText;
        recoveryRetry.setText("已停止游戏，重试恢复");
        recoveryRetry.setVisibility(recoveryPending ? View.VISIBLE : View.GONE);
        statusView.setTextColor(getColor(recoveryPending || !rooted ? R.color.danger : R.color.accent));
        renderDisplayedCards(selected);
        captureButton.setEnabled(selected.supported && cardActionsAvailable);
        recordTabFrame("checked", data.snapshots.size());
        captureButton.setAlpha(captureButton.isEnabled() ? 1f : 0.45f);
        launchButton.setEnabled(!selected.activity.isEmpty() && cardActionsAvailable);
        launchButton.setAlpha(launchButton.isEnabled() ? 1f : 0.45f);
        if (followCurrentCard) {
            followCurrentCard = false;
            scrollToCard();
        }
    }

    private void renderDisplayedCards(GameProfile selected) {
        if (displayedData == null || selected != game) {
            return;
        }
        List<SnapshotStore.Snapshot> listed = new ArrayList<>(displayedData.snapshots);
        if (sortMode == SORT_FIXED) {
            if (fixedOrder.isEmpty() && !listed.isEmpty()) {
                seedFixedOrderFrom(selected, listed);
            } else {
                ensureFixedOrder(selected, listed);
            }
        }
        sortSnapshots(listed);
        SnapshotStore.Snapshot matched = match(displayedData.current, listed, displayedData.midById);
        if (displayedStatusPrefix != null) {
            if (!displayedData.recoveryError.isEmpty()) {
                statusView.setText("上次切换尚未恢复。请停止游戏后重试。\n失败原因可在「运行详情」查看。");
            } else {
                String accountText = selected.supported
                        ? "当前账号：" + describe(displayedData.current, matched) : "尚未适配";
                statusView.setText(accountText + " · " + displayedStatusPrefix);
            }
        }
        renderCards(listed, matched == null ? null : matched.id, selected, displayedData.readableById);
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
            GradientDrawable tabFill = selected
                    ? surface(R.color.accent, R.color.accent, 20)
                    : surface(R.color.card_bg, R.color.card_bg, 20);
            button.setBackground(ripple(tabFill));
        }
    }

    /** Worker only: stops as soon as the current account is matched. */
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

    private SnapshotStore.Snapshot match(SnapshotStore.Current current,
                                         List<SnapshotStore.Snapshot> snapshots,
                                         Map<String, String> midById) {
        if (current == null || !current.loggedIn || current.mid.isEmpty()) {
            return null;
        }
        for (SnapshotStore.Snapshot snapshot : snapshots) {
            if (current.mid.equals(midById.get(snapshot.id))) {
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

    /** ScrollView whose scrolling can be fully disabled while a card operation owns the screen. */
    private static final class LockableScrollView extends ScrollView {
        private boolean locked;

        LockableScrollView(Context context) {
            super(context);
        }

        void setLocked(boolean locked) {
            this.locked = locked;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            return !locked && super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return !locked && super.onTouchEvent(event);
        }
    }

    private static final class CardActions extends ViewGroup {
        private final Button primary;
        private final Button credentials;
        private final Button more;
        private final int gap;
        private final int moreMinWidth;
        private int rows;

        CardActions(Context context, Button primary, Button credentials, Button more,
                    int gap, int moreMinWidth) {
            super(context);
            this.primary = primary;
            this.credentials = credentials;
            this.more = more;
            this.gap = gap;
            this.moreMinWidth = moreMinWidth;
            primary.setSingleLine(false);
            credentials.setSingleLine(false);
            addView(primary, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            addView(credentials, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            addView(more, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            // Reserve room for the longer primary label on every card so equally wide
            // cards use the same layout even if only one is the current account.
            int primaryMin = Math.max(labelWidth(primary, primary.getText()),
                    labelWidth(primary, SWITCH_ACCOUNT_LABEL));
            int credentialsMin = Math.max(moreMinWidth, labelWidth(credentials, credentials.getText()));
            int moreMin = Math.max(moreMinWidth, labelWidth(more, more.getText()));
            int inlineMin = primaryMin + credentialsMin + moreMin + 2 * gap;
            int width = resolveSize(inlineMin, widthSpec);
            CardActionWidths widths = CardActionWidths.fit(width, primaryMin, credentialsMin, moreMin, gap);
            rows = widths.rows;
            measureButton(primary, widths.primary);
            measureButton(credentials, widths.credentials);
            measureButton(more, widths.more);
            int height = primary.getMeasuredHeight();
            int secondaryHeight = Math.max(credentials.getMeasuredHeight(), more.getMeasuredHeight());
            if (rows == 1) {
                height = Math.max(height, secondaryHeight);
                equalizeHeight(primary, height);
                equalizeHeight(credentials, height);
                equalizeHeight(more, height);
            } else if (rows == 2) {
                height += gap + secondaryHeight;
                equalizeHeight(credentials, secondaryHeight);
                equalizeHeight(more, secondaryHeight);
            } else {
                height += 2 * gap + credentials.getMeasuredHeight() + more.getMeasuredHeight();
            }
            setMeasuredDimension(width, resolveSize(height, heightSpec));
        }

        private static int labelWidth(Button button, CharSequence label) {
            return (int) Math.ceil(Layout.getDesiredWidth(label, button.getPaint()))
                    + button.getCompoundPaddingLeft() + button.getCompoundPaddingRight();
        }

        private static void measureButton(Button button, int width) {
            button.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }

        private static void equalizeHeight(Button button, int height) {
            if (button.getMeasuredHeight() != height) {
                button.measure(MeasureSpec.makeMeasureSpec(button.getMeasuredWidth(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            layoutFromStart(primary, 0, 0);
            int start = rows == 1 ? primary.getMeasuredWidth() + gap : 0;
            int y = rows == 1 ? 0 : primary.getMeasuredHeight() + gap;
            layoutFromStart(credentials, start, y);
            int moreY = rows == 3 ? y + credentials.getMeasuredHeight() + gap : y;
            layoutFromStart(more, getWidth() - more.getMeasuredWidth(), moreY);
        }

        private void layoutFromStart(View child, int start, int top) {
            int width = child.getMeasuredWidth();
            int left = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() - start - width : start;
            child.layout(left, top, left + width, top + child.getMeasuredHeight());
        }
    }

    private void renderCards(List<SnapshotStore.Snapshot> snapshots, String currentId,
                             GameProfile selected, Map<String, Boolean> readableById) {
        List<Object> signature = cardDisplaySignature(snapshots, currentId, selected, readableById,
                renderingPreview, cardActionsAvailable, busy, sortMode);
        Collections.addAll(signature, cardOpActive, focusedCardId);
        if (signature.equals(renderedCardSignature)) {
            return; // Status-only refresh: keep the existing views, focus and scroll position.
        }
        renderedCardSignature = signature;
        cancelCardDrag();
        ScrollView scroll = columnScroll != null ? columnScroll : pageScroll;
        int savedScrollY = scroll == null ? 0 : scroll.getScrollY();
        currentCardView = null;
        cardList.setEnabled(true);
        cardList.removeAllViews();
        if (!selected.supported) {
            TextView unsupported = new TextView(this);
            unsupported.setText(selected.label + " 尚未适配：状态文件清单还在调研，暂不能拍摄或切换。");
            unsupported.setTextSize(13);
            unsupported.setTextColor(getColor(R.color.text_muted));
            unsupported.setPadding(0, dp(20), 0, 0);
            cardList.addView(unsupported, matchWrap());
            restoreScroll(scroll, savedScrollY);
            return;
        }
        if (snapshots.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(renderingPreview ? "正在确认卡片列表…"
                    : "这个分栏还没有快照。登录 " + selected.label + " 后点「拍摄快照」，无需停止游戏。");
            empty.setTextSize(13);
            empty.setTextColor(getColor(R.color.text_muted));
            empty.setPadding(0, dp(20), 0, 0);
            cardList.addView(empty, matchWrap());
            restoreScroll(scroll, savedScrollY);
            return;
        }
        if (snapshots.size() >= 45) {
            TextView warning = new TextView(this);
            warning.setText("已保存 " + snapshots.size()
                    + " 个账号，接近游戏 SDK 上限（50）：登录新账号时游戏可能删掉最久未使用的账号。");
            warning.setTextSize(12);
            warning.setTextColor(getColor(R.color.danger));
            warning.setPadding(0, dp(4), 0, dp(10));
            cardList.addView(warning, matchWrap());
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (SnapshotStore.Snapshot cached : snapshots) {
            SnapshotStore.Snapshot snapshot = SnapshotStore.copyForAction(cached);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            boolean isCurrent = snapshot.id.equals(currentId);
            card.setTag(snapshot.id);
            if (isCurrent) {
                currentCardView = card;
            }
            boolean readable = Boolean.TRUE.equals(readableById.get(snapshot.id));
            boolean switchable = snapshot.game.switchVerified;
            GradientDrawable cardFill = isCurrent
                    ? surface(R.color.card_bg, R.color.accent, 26)
                    : surface(R.color.card_bg, R.color.card_bg, 26);
            card.setBackground(ripple(cardFill));
            card.setClickable(true);
            card.setOnClickListener(view -> showCardMenu(snapshot, readable));
            int cardPad = dp(14);
            card.setPadding(cardPad, dp(12), cardPad, dp(12));

            LinearLayout heading = new LinearLayout(this);
            heading.setOrientation(LinearLayout.HORIZONTAL);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this);
            label.setTypeface(null, Typeface.BOLD);
            label.setText(snapshot.name);
            label.setTextSize(16);
            label.setTextColor(getColor(R.color.text_primary));
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (Build.VERSION.SDK_INT >= 28) {
                label.setAccessibilityHeading(true);
            }
            heading.addView(label, weightWrap());
            if (isCurrent) {
                TextView currentBadge = new TextView(this);
                currentBadge.setText("当前登录");
                currentBadge.setTextSize(11);
                currentBadge.setTextColor(getColor(R.color.accent));
                currentBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
                currentBadge.setBackground(surface(R.color.pill_bg, R.color.pill_bg, 16));
                LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                badgeParams.leftMargin = dp(8);
                heading.addView(currentBadge, badgeParams);
            }
            if (snapshots.size() > 1) {
                Button handle = pill("↕", false);
                handle.setContentDescription("拖动排序：" + snapshot.name);
                handle.setOnClickListener(view -> toast("长按并拖动手柄可调整快照顺序"));
                handle.setOnLongClickListener(view -> startCardDrag(view, card));
                LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(48),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                handleParams.setMarginStart(dp(8));
                heading.addView(handle, handleParams);
            }
            card.addView(heading, matchWrap());

            TextView meta = new TextView(this);
            meta.setText("更新于 " + format.format(new Date(snapshot.createdAt))
                    + (renderingPreview ? " · 正在检查状态" : readable ? "" : " · 快照无法读取")
                    + (!renderingPreview && readable && !switchable && !isCurrent ? " · 切换待验证" : ""));
            meta.setTextSize(12);
            meta.setTextColor(getColor(renderingPreview || readable ? R.color.text_muted : R.color.danger));
            card.addView(meta, withTopMargin(withBottomMargin(matchWrap(), dp(10)), dp(4)));

            // A broken snapshot keeps its card; its main action becomes a manual update instead of
            // an unusable switch. Details stay reachable from the "⋯" menu.
            Button primary = pill(renderingPreview ? "正在检查状态"
                    : !readable || isCurrent ? "更新快照" : SWITCH_ACCOUNT_LABEL, true);
            if (!readable) {
                primary.setOnClickListener(view -> confirmUpdateUnreadable(snapshot));
            } else if (isCurrent) {
                primary.setOnClickListener(view -> runOp("更新快照 " + snapshot.name, snapshot, () -> {
                    AccountUpdater.updateTarget(store, snapshot, this::appendLog);
                    toast("已更新快照");
                }));
            } else {
                primary.setOnClickListener(view -> confirmSwitch(snapshot));
            }
            // A broken card's update still needs a writable state (root, known process, no pending
            // recovery); otherwise it would be clickable but bound to fail.
            boolean enabled = cardActionsAvailable && (!readable || isCurrent || switchable);
            primary.setEnabled(enabled);
            primary.setAlpha(enabled ? 1f : 0.45f);
            Button credentials = pill("账号与密码", false);
            credentials.setOnClickListener(view -> showAccountCredentials(snapshot));
            Button moreButton = pill("⋯", false);
            moreButton.setContentDescription("更多操作：" + snapshot.name);
            moreButton.setOnClickListener(view -> showCardMenu(snapshot, readable));
            card.addView(new CardActions(this, primary, credentials, moreButton, dp(8), dp(52)),
                    matchWrap());

            cardList.addView(card, withBottomMargin(matchWrap(), dp(10)));
        }
        // A refresh that lands while an operation is focused must keep the log pinned under the
        // target card (removeAllViews above would otherwise detach it).
        if (cardOpActive && focusedCardId != null) {
            moveLogBelowCard(focusedCardId);
            // The card may have moved after a reorder; pin the viewport on it, not the old pixel.
            scrollToView(cardViewFor(focusedCardId));
        } else {
            restoreScroll(scroll, savedScrollY);
        }
    }

    /** Rebuilding the card list must not yank the page back to the top. */
    private void restoreScroll(ScrollView scroll, int y) {
        if (scroll == null || y <= 0) {
            return;
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    static List<Object> cardDisplaySignature(List<SnapshotStore.Snapshot> snapshots, String currentId,
                                             GameProfile selected, Map<String, Boolean> readable,
                                             boolean preview, boolean available, boolean working,
                                             int ordering) {
        List<Object> signature = new ArrayList<>();
        Collections.addAll(signature, selected.pkg, currentId, preview, available, working, ordering);
        for (SnapshotStore.Snapshot snapshot : snapshots) {
            Collections.addAll(signature, snapshot.id, snapshot.name, snapshot.createdAt,
                    snapshot.lastUsedAt, snapshot.loginName, snapshot.passwordEnc,
                    readable.get(snapshot.id), snapshot.entries.size());
            for (SnapshotStore.Entry entry : snapshot.entries.values()) {
                Collections.addAll(signature, entry.path, entry.present, entry.size, entry.sha256,
                        entry.uid, entry.gid, entry.mode, entry.context, entry.mtime);
            }
        }
        return signature;
    }

    private static LinearLayout.LayoutParams withBottomMargin(LinearLayout.LayoutParams params,
                                                              int margin) {
        params.bottomMargin = margin;
        return params;
    }

    private int rippleColor() {
        return (getColor(R.color.text_primary) & 0x00FFFFFF) | 0x22000000;
    }

    private Dialog baseDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.55f;
            window.setAttributes(attributes);
        }
        return dialog;
    }

    private LinearLayout dialogCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(surface(R.color.card_bg, R.color.card_bg, 28));
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        return card;
    }

    private TextView dialogTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        return title;
    }

    private TextView dialogMessage(CharSequence text) {
        TextView message = new TextView(this);
        message.setText(text);
        message.setTextSize(13);
        message.setTextColor(getColor(R.color.text_muted));
        message.setLineSpacing(dp(3), 1f);
        return message;
    }

    private void showDialog(Dialog dialog, LinearLayout card) {
        showDialog(dialog, card, null);
    }

    /**
     * Shows a dialog; {@code state.cancel} runs once if it is dismissed without confirming, which
     * also covers programmatic dismissal during recreation (setOnCancelListener never fires then).
     */
    private void showDialog(Dialog dialog, LinearLayout card, DialogState dialogState) {
        if (visibleDialog != null) {
            visibleDialog.dismiss();
        }
        visibleDialog = dialog;
        dialog.setOnDismissListener(dismissed -> {
            if (visibleDialog == dialog) {
                visibleDialog = null;
            }
            if (dialogState != null && !dialogState.confirmed && dialogState.cancel != null) {
                dialogState.cancel.run();
            }
        });
        ScrollView viewport = new ScrollView(this);
        viewport.addView(card, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(viewport);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            View activityContent = findViewById(android.R.id.content);
            int measured = activityContent == null ? 0 : activityContent.getWidth();
            int width = dialogWidth(measured, getResources().getDisplayMetrics().widthPixels,
                    dp(48), dp(520));
            int state = window.getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
            window.setSoftInputMode(state | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    static int dialogWidth(int measured, int fallback, int margin, int maximum) {
        int available = measured > 0 ? measured : fallback;
        if (available <= 0) {
            return WindowManager.LayoutParams.WRAP_CONTENT;
        }
        return Math.min(available - Math.min(margin, available / 4), Math.max(1, maximum));
    }

    private void showConfirm(String title, String message, String positiveLabel, Runnable onPositive) {
        showConfirm(title, message, positiveLabel, onPositive, null);
    }

    private void showConfirm(String title, String message, String positiveLabel, Runnable onPositive,
                             Runnable onCancel) {
        showConfirm(title, message, positiveLabel, false, onPositive, onCancel);
    }

    private void showConfirm(String title, String message, String positiveLabel,
                             boolean destructive, Runnable onPositive, Runnable onCancel) {
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogTitle(title), matchWrap());
        card.addView(dialogMessage(message), withTopMargin(withBottomMargin(matchWrap(), dp(16)), dp(8)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = pill("取消", false);
        // The cancel path is handled once, by the dismiss listener in showDialog: it also covers
        // programmatic dismissal (onDestroy during recreation), where setOnCancelListener never
        // fires and a pending operation would stay "busy" forever.
        cancel.setOnClickListener(view -> dialog.dismiss());
        Button positive = destructive ? dangerPill(positiveLabel) : pill(positiveLabel, true);
        DialogState state = new DialogState();
        state.cancel = onCancel;
        positive.setOnClickListener(view -> {
            state.confirmed = true;
            dialog.dismiss();
            onPositive.run();
        });
        row.addView(cancel, weightWrap(1f, dp(4)));
        row.addView(positive, weightWrap(1f, dp(4)));
        card.addView(row, withTopMargin(matchWrap(), dp(12)));
        showDialog(dialog, card, state);
    }

    /** Per-dialog state so dismissing one dialog cannot disturb another. */
    private static final class DialogState {
        Runnable cancel;
        boolean confirmed;
    }

    private Button dangerPill(String text) {
        Button button = pill(text, true);
        button.setBackground(ripple(surface(R.color.danger, R.color.danger, 24)));
        button.setTextColor(getColor(R.color.on_accent));
        return button;
    }

    private static LinearLayout.LayoutParams withTopMargin(LinearLayout.LayoutParams params,
                                                           int margin) {
        params.topMargin = margin;
        return params;
    }

    private static final class Action {
        final int icon;
        final String title;
        final String subtitle;
        final boolean destructive;
        final Runnable onClick;

        Action(int icon, String title, String subtitle, boolean destructive, Runnable onClick) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.destructive = destructive;
            this.onClick = onClick;
        }
    }

    private void showActionSheet(String title, List<Action> actions) {
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        TextView titleView = dialogTitle(title);
        titleView.setMaxLines(2);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(titleView, withBottomMargin(matchWrap(), dp(10)));

        for (Action action : actions) {
            card.addView(actionRow(dialog, action), withBottomMargin(matchWrap(), dp(4)));
        }

        Button cancel = pill("取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        card.addView(cancel, withTopMargin(matchWrap(), dp(12)));
        showDialog(dialog, card);
    }

    private LinearLayout actionRow(Dialog dialog, Action action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null, null));
        row.setContentDescription(action.title + "，" + action.subtitle);
        row.setOnClickListener(view -> {
            dialog.dismiss();
            action.onClick.run();
        });

        ImageView icon = new ImageView(this);
        icon.setImageResource(action.icon);
        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setShape(GradientDrawable.OVAL);
        iconBackground.setColor(getColor(action.destructive
                ? R.color.danger_tonal : R.color.accent_tonal));
        icon.setBackground(iconBackground);
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        icon.setColorFilter(getColor(action.destructive ? R.color.danger : R.color.accent));
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, 0, 0);
        TextView titleView = new TextView(this);
        titleView.setText(action.title);
        titleView.setTextSize(15);
        titleView.setTextColor(getColor(action.destructive ? R.color.danger : R.color.text_primary));
        text.addView(titleView, matchWrap());
        TextView subtitleView = new TextView(this);
        subtitleView.setText(action.subtitle);
        subtitleView.setTextSize(12);
        subtitleView.setTextColor(getColor(R.color.text_muted));
        text.addView(subtitleView, matchWrap());
        row.addView(text, weightWrap());
        return row;
    }

    /** Account name + password block; each value copies on its own. */
    private View credentialsBlock(SnapshotStore.Snapshot snapshot) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(surface(R.color.pill_bg, R.color.pill_bg, 18));
        panel.setPadding(dp(14), dp(10), dp(8), dp(10));

        boolean hasAccount = !snapshot.loginName.isEmpty();
        boolean hasPassword = !snapshot.passwordEnc.isEmpty();
        panel.addView(credentialRow("账号", hasAccount ? snapshot.loginName : "未设置",
                hasAccount ? snapshot.loginName : null, snapshot, false), matchWrap());
        panel.addView(credentialRow("密码", hasPassword ? "••••••••" : "未设置",
                hasPassword ? "" : null, snapshot, true), withTopMargin(matchWrap(), dp(4)));

        TextView edit = new TextView(this);
        edit.setText("编辑账号密码");
        edit.setTextSize(12);
        edit.setTextColor(getColor(R.color.accent));
        edit.setPadding(0, dp(8), 0, 0);
        edit.setOnClickListener(view -> showCredentialsDialog(snapshot));
        panel.addView(edit, matchWrap());
        return panel;
    }

    private View credentialRow(String label, String value, String copyValue,
                               SnapshotStore.Snapshot snapshot, boolean password) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView text = new TextView(this);
        text.setText(label + "：" + value);
        text.setTextSize(13);
        text.setTextColor(getColor(copyValue == null ? R.color.text_muted : R.color.text_primary));
        text.setMaxLines(1);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(text, weightWrap());

        Button copy = smallPill("复制");
        copy.setEnabled(copyValue != null);
        copy.setAlpha(copyValue != null ? 1f : 0.45f);
        copy.setContentDescription("复制" + label);
        copy.setOnClickListener(view -> {
            String actual = copyValue;
            if (password) {
                try {
                    actual = store.password(snapshot);
                } catch (IOException error) {
                    toast("密码读取失败：" + error.getMessage());
                    return;
                }
            }
            if (actual == null || actual.isEmpty()) {
                toast("还没有设置" + label);
                return;
            }
            copyToClipboard(label, actual, password);
        });
        row.addView(copy, new LinearLayout.LayoutParams(dp(64), dp(34)));
        return row;
    }

    private Button smallPill(String text) {
        Button button = pill(text, false);
        button.setTextSize(12);
        button.setMinHeight(dp(34));
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private void copyToClipboard(String label, String value, boolean sensitive) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            toast("系统剪贴板不可用");
            return;
        }
        ClipData clip = ClipData.newPlainText(label, value);
        if (sensitive) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        manager.setPrimaryClip(clip);
        toast("已复制" + label);
    }

    private void showCredentialsDialog(SnapshotStore.Snapshot snapshot) {
        final String previousAccount = snapshot.loginName;
        final String previousCiphertext = snapshot.passwordEnc;
        final String storedPassword;
        try {
            storedPassword = store.decodePassword(previousCiphertext);
        } catch (IOException error) {
            showPasswordUnreadableDialog(snapshot, previousAccount, previousCiphertext);
            return;
        }
        editCredentials(snapshot, previousAccount, previousCiphertext, storedPassword);
    }

    /** Shown when the stored password note cannot be decrypted; the old record is left untouched. */
    private void showPasswordUnreadableDialog(SnapshotStore.Snapshot snapshot, String previousAccount,
                                              String previousCiphertext) {
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogTitle("无法读取已保存的密码"), withBottomMargin(matchWrap(), dp(10)));
        card.addView(dialogMessage("原记录尚未更改。如果你知道当前密码，可以手动重新录入。"
                + "只有保存新密码后才会替换旧记录。"), withBottomMargin(matchWrap(), dp(14)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button keep = pill("保留旧记录", false);
        keep.setOnClickListener(view -> dialog.dismiss());
        Button reenter = pill("重新录入", true);
        reenter.setOnClickListener(view -> {
            dialog.dismiss();
            editCredentials(snapshot, previousAccount, previousCiphertext, null);
        });
        row.addView(keep, weightWrap(1f, dp(4)));
        row.addView(reenter, weightWrap(1f, dp(4)));
        card.addView(row, matchWrap());
        showDialog(dialog, card);
    }

    private void editCredentials(SnapshotStore.Snapshot snapshot, String previousAccount,
                                 String previousCiphertext, String storedPassword) {
        boolean replacingUnreadable = storedPassword == null;
        Dialog dialog = baseDialog();
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        LinearLayout card = dialogCard();
        card.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        card.addView(dialogTitle("账号密码 · " + snapshot.name),
                withBottomMargin(matchWrap(), dp(12)));

        EditText account = new EditText(this);
        account.setHint("账号（手机号 / 邮箱 / 用户名）");
        account.setText(previousAccount);
        account.setSingleLine(true);
        styleInput(account);
        card.addView(account, withBottomMargin(matchWrap(), dp(10)));

        EditText password = new EditText(this);
        password.setHint(replacingUnreadable ? "输入新密码以替换旧记录" : "密码（加密保存）");
        password.setSingleLine(true);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        styleInput(password);
        password.setText(storedPassword);

        ImageButton eye = new ImageButton(this);
        eye.setImageResource(R.drawable.ic_eye_off);
        eye.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        eye.setColorFilter(getColor(R.color.accent));
        eye.setPadding(dp(10), dp(10), dp(10), dp(10));
        eye.setFocusable(true);
        eye.setContentDescription("显示密码");
        eye.setBackground(ripple(surface(R.color.pill_bg, R.color.pill_bg, 18)));
        final boolean[] revealed = {false};
        eye.setOnClickListener(view -> {
            revealed[0] = !revealed[0];
            password.setTransformationMethod(revealed[0]
                    ? android.text.method.HideReturnsTransformationMethod.getInstance()
                    : android.text.method.PasswordTransformationMethod.getInstance());
            eye.setImageResource(revealed[0] ? R.drawable.ic_eye : R.drawable.ic_eye_off);
            eye.setContentDescription(revealed[0] ? "隐藏密码" : "显示密码");
            password.setSelection(password.getText().length());
        });

        LinearLayout passwordRow = new LinearLayout(this);
        passwordRow.setOrientation(LinearLayout.HORIZONTAL);
        passwordRow.setGravity(Gravity.CENTER_VERTICAL);
        passwordRow.addView(password, weightWrap());
        LinearLayout.LayoutParams eyeParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        eyeParams.setMarginStart(dp(8));
        passwordRow.addView(eye, eyeParams);
        card.addView(passwordRow, withBottomMargin(matchWrap(), dp(14)));

        CheckBox confirmClear = new CheckBox(this);
        confirmClear.setText("确认清除已保存的密码备注");
        confirmClear.setMinHeight(dp(48));
        confirmClear.setVisibility(View.GONE);
        card.addView(confirmClear, withBottomMargin(matchWrap(), dp(8)));
        password.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                confirmClear.setChecked(false);
                confirmClear.setVisibility(View.GONE);
            }

            @Override
            public void afterTextChanged(Editable text) {}
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = pill("取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        Button save = pill(replacingUnreadable ? "替换旧密码" : "保存", true);
        save.setOnClickListener(view -> {
            if (busy) {
                toast("上一个操作还没结束");
                return;
            }
            String accountText = account.getText().toString();
            String passwordText = password.getText().toString();
            if (replacingUnreadable && passwordText.isEmpty()) {
                password.setError("请输入新密码，旧记录尚未更改");
                return;
            }
            if (needsPasswordClearConfirmation(storedPassword, passwordText, confirmClear.isChecked())) {
                confirmClear.setVisibility(View.VISIBLE);
                toast("清除已有密码需要确认，旧记录尚未更改");
                return;
            }
            dialog.dismiss();
            runLocalCardOp("保存账号密码", snapshot, false, () -> {
                store.setCredentials(snapshot, previousAccount, previousCiphertext,
                        accountText, passwordText);
                appendLog("已保存账号密码：" + snapshot.name);
            });
        });
        row.addView(cancel, weightWrap(1f, dp(4)));
        row.addView(save, weightWrap(1f, dp(4)));
        card.addView(row, matchWrap());
        showDialog(dialog, card);
    }

    static boolean needsPasswordClearConfirmation(String original, String draft, boolean confirmed) {
        return original != null && !original.isEmpty() && draft.isEmpty() && !confirmed;
    }

    private void styleInput(EditText input) {
        input.setTextSize(14);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_muted));
        input.setBackground(surface(R.color.pill_bg, R.color.pill_bg, 18));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
    }

    private void showCardMenu(SnapshotStore.Snapshot snapshot, boolean readable) {
        if (busy) {
            // Accepting a rename/capture dialog now would silently drop the input at runOp.
            toast("上一个操作还没结束");
            return;
        }
        Log.i("hoyoswitch", "card menu: " + snapshot.name);
        List<Action> actions = new ArrayList<>();
        actions.add(new Action(android.R.drawable.ic_menu_info_details, "快照详情", "查看时间、文件状态和账号密码备注",
                false, () -> showSnapshotDetails(snapshot, readable)));
        actions.add(new Action(R.drawable.ic_history, "历史版本", "查看并回滚旧版本", false,
                () -> historyDialog(snapshot)));
        actions.add(new Action(R.drawable.ic_rename, "重命名", "修改卡片显示名称", false,
                () -> renameDialog(snapshot)));
        if (sortMode == SORT_FIXED) {
            actions.add(new Action(android.R.drawable.ic_menu_sort_by_size, "上移卡片", "调整固定顺序",
                    false, () -> moveCard(snapshot, -1)));
            actions.add(new Action(android.R.drawable.ic_menu_sort_by_size, "下移卡片", "调整固定顺序",
                    false, () -> moveCard(snapshot, 1)));
        }
        actions.add(new Action(R.drawable.ic_delete, "删除快照", "连同历史版本一起删除", true,
                () -> deleteDialog(snapshot)));
        showActionSheet(snapshot.name, actions);
    }

    private void showSnapshotDetails(SnapshotStore.Snapshot snapshot, boolean readable) {
        int present = 0;
        for (SnapshotStore.Entry entry : snapshot.entries.values()) {
            if (entry.present) {
                present++;
            }
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        String details = "更新于 " + format.format(new Date(snapshot.createdAt))
                + "\n状态文件：" + present + "/" + snapshot.entries.size()
                + (snapshot.lastUsedAt > 0 ? "\n上次使用 " + format.format(new Date(snapshot.lastUsedAt)) : "")
                + (readable ? "" : "\n快照无法读取，请检查文件或加密密钥。");
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        TextView title = dialogTitle(snapshot.name);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title, withBottomMargin(matchWrap(), dp(8)));
        card.addView(dialogMessage(details), withBottomMargin(matchWrap(), dp(12)));
        card.addView(credentialsBlock(snapshot), withBottomMargin(matchWrap(), dp(12)));
        Button close = pill("关闭", false);
        close.setOnClickListener(view -> dialog.dismiss());
        card.addView(close, matchWrap());
        showDialog(dialog, card);
    }

    /**
     * Overwrites a broken snapshot with the current account state. The card id/name, the password
     * note and the last-used time are preserved; a failure or cancel leaves the old broken card.
     * The broken snapshot cannot be decrypted, so the target account can never be verified here:
     * the user must confirm manually and owns the result.
     */
    private void confirmUpdateUnreadable(SnapshotStore.Snapshot snapshot) {
        showConfirm("更新失效快照",
                "这张快照已损坏，程序无法校验它原本对应哪个账号。\n"
                        + "将用当前登录的账号状态覆盖「" + snapshot.name + "」，"
                        + "名称、备注、账号与密码都会保留。\n"
                        + "请确认当前登录的正是这张卡片对应的账号；确认后的覆盖结果由你负责。",
                "确认更新",
                () -> runOp("更新快照 " + snapshot.name, snapshot, () -> {
                    store.updateSnapshot(snapshot);
                    toast("已更新快照");
                }));
    }

    private void showAccountCredentials(SnapshotStore.Snapshot snapshot) {
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogTitle("账号与密码"), withBottomMargin(matchWrap(), dp(10)));
        card.addView(credentialsBlock(snapshot), withBottomMargin(matchWrap(), dp(12)));
        Button close = pill("关闭", false);
        close.setOnClickListener(view -> dialog.dismiss());
        card.addView(close, matchWrap());
        showDialog(dialog, card);
    }

    private void renameDialog(SnapshotStore.Snapshot snapshot) {
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogTitle("重命名快照"), withBottomMargin(matchWrap(), dp(12)));

        EditText input = new EditText(this);
        input.setText(snapshot.name);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(getColor(R.color.text_primary));
        input.setBackground(surface(R.color.pill_bg, R.color.pill_bg, 18));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setSelection(snapshot.name.length());
        card.addView(input, withBottomMargin(matchWrap(), dp(14)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = pill("取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        Button save = pill("保存", true);
        save.setOnClickListener(view -> {
            String name = input.getText().toString();
            dialog.dismiss();
            runLocalCardOp("重命名", snapshot, false, () -> store.rename(snapshot, name));
        });
        row.addView(cancel, weightWrap(1f, dp(4)));
        row.addView(save, weightWrap(1f, dp(4)));
        card.addView(row, matchWrap());
        showDialog(dialog, card);
    }

    private void deleteDialog(SnapshotStore.Snapshot snapshot) {
        showConfirm("删除快照",
                "将删除「" + snapshot.name + "」及其历史版本。不会影响游戏内的账号。",
                "删除", true,
                () -> runLocalCardOp("删除快照", snapshot, true, () -> store.delete(snapshot)),
                null);
    }

    private void historyDialog(SnapshotStore.Snapshot snapshot) {
        // history() takes the store lock; loading it on the main thread would block behind a
        // running capture/switch (root I/O can take seconds).
        new Thread(() -> {
            final List<SnapshotStore.HistoryEntry> entries;
            try {
                entries = store.history(snapshot);
            } catch (RuntimeException error) {
                main.post(() -> toast("读取历史版本失败：" + error.getMessage()));
                return;
            }
            main.post(() -> showHistorySheet(snapshot, entries));
        }, "history-load").start();
    }

    private void showHistorySheet(SnapshotStore.Snapshot snapshot,
                                  List<SnapshotStore.HistoryEntry> entries) {
        if (entries.isEmpty()) {
            toast("这个快照还没有历史版本");
            return;
        }
        List<Action> actions = new ArrayList<>();
        for (SnapshotStore.HistoryEntry entry : entries) {
            actions.add(new Action(R.drawable.ic_history, entry.label, "回滚到这个版本", false,
                    () -> confirmHistory(snapshot, entry)));
        }
        showActionSheet("历史版本 · " + snapshot.name, actions);
    }

    private void confirmHistory(SnapshotStore.Snapshot snapshot, SnapshotStore.HistoryEntry entry) {
        showConfirm("回滚到 " + entry.label,
                "快照内容会替换为该历史版本，当前版本先归档保留。继续？",
                "回滚",
                () -> runOp("回滚历史版本", snapshot, () -> store.restoreHistory(snapshot, entry)));
    }

    private void confirmSwitch(SnapshotStore.Snapshot target) {
        if (!target.game.switchVerified) {
            toast(target.game.label + " 的切换文件还没验证，先拍摄账号快照做基准");
            return;
        }
        if (busy) {
            toast("上一个操作还没结束");
            return;
        }
        busy = true;
        ++refreshGeneration;
        appendLog("读取当前账号…");
        new Thread(() -> {
            // Only a confirmed hand-off to the dialog may keep busy=true; every other path
            // (including Error/abnormal exit) must release it.
            final boolean[] handedOff = {false};
            try {
                if (!store.decryptable(target)) {
                    appendLog("这张快照无法解密，不能切换");
                    if (pageResumed && !isFinishing() && !isDestroyed()) {
                        toast("这张快照无法解密，请删除后重新拍摄");
                    }
                    return;
                }
                SnapshotStore.Current current = store.detectCurrent(target.game);
                if (current.loggedIn && !current.mid.isEmpty()
                        && current.mid.equals(store.accountMid(target))) {
                    appendLog("当前已经是这个账号，无需切换（不会关闭游戏）");
                    if (pageResumed && !isFinishing() && !isDestroyed()) {
                        toast("当前已经是这个账号，无需切换");
                    }
                    return;
                }
                List<SnapshotStore.Snapshot> snapshots = store.list(target.game);
                String currentLabel = describe(current, match(current, snapshots));
                handedOff[0] = true;
                main.post(() -> {
                    if (pageResumed && !isFinishing() && !isDestroyed()) {
                        askSwitch(target, currentLabel);
                    } else {
                        finishOperation(false);
                    }
                });
            } catch (Exception error) {
                reportOperationError(error);
            } finally {
                if (!handedOff[0]) {
                    main.post(() -> finishOperation(false));
                }
            }
        }).start();
    }

    private void askSwitch(SnapshotStore.Snapshot target, String currentLabel) {
        Runnable reset = () -> finishOperation(false);
        showConfirm("切换" + target.game.label + "账号",
                "当前账号：" + currentLabel
                        + "\n目标账号：" + target.name
                        + "\n\n将先停止游戏，再写入该游戏的 "
                        + target.game.switchFiles.size() + " 个账号文件，并自动保存切换前状态。继续？",
                "切换",
                () -> new Thread(() -> {
                    main.post(() -> {
                        if (pageResumed && !isFinishing() && !isDestroyed()) {
                            focusCard(target);
                        }
                    });
                    try {
                        store.stopAndSwitchTo(target, this::appendLog);
                        toast("已完成账号切换");
                    } catch (Exception error) {
                        reportOperationError(error);
                    } finally {
                        main.post(() -> finishOperation(true));
                    }
                }).start(),
                reset);
    }

    private void capture() {
        final GameProfile selected = game;
        if (!selected.supported) {
            toast(selected.label + " 尚未适配，不能拍摄快照");
            return;
        }
        if (busy) {
            toast("上一个操作还没结束");
            return;
        }
        Dialog dialog = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogTitle("拍摄" + selected.label + "快照"), withBottomMargin(matchWrap(), dp(8)));
        card.addView(dialogMessage("无需停止游戏。留空时，已有快照保留原名称，新快照使用默认名称。"),
                withBottomMargin(matchWrap(), dp(12)));
        EditText input = new EditText(this);
        input.setHint("快照名称（可选）");
        input.setSingleLine(true);
        styleInput(input);
        card.addView(input, withBottomMargin(matchWrap(), dp(14)));
        LinearLayout row = new LinearLayout(this);
        Button cancel = pill("取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        Button save = pill("拍摄", true);
        save.setOnClickListener(view -> {
            String name = input.getText().toString();
            dialog.dismiss();
            runOp("拍摄快照", () -> {
                String id = store.captureAccount(selected, name);
                appendLog("已保存快照 " + id);
                toast("已保存快照");
            });
        });
        row.addView(cancel, weightWrap(1f, dp(4)));
        row.addView(save, weightWrap(1f, dp(4)));
        card.addView(row, matchWrap());
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        showDialog(dialog, card);
    }

    private void toggleFloating() {
        if (UiSettings.isSaving()) {
            toast("正在保存设置，请稍后再试");
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (UiSettings.floatingGame(prefs) != null) {
            UiSettings.setFloatingGame(prefs, null, this::runOnUiThread, saved -> {
                if (pageResumed) {
                    toast(saved ? "悬浮按钮已关闭" : "未能保存悬浮按钮设置，请重试");
                }
            });
            return;
        }
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
        UiSettings.setFloatingGame(prefs, game, this::runOnUiThread, saved -> {
            if (!pageResumed) {
                return;
            }
            if (!saved) {
                toast("未能保存悬浮按钮设置，请重试");
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        });
    }

    private void startSavedFloating() {
        if (FloatingService.isRunning()) {
            return;
        }
        try {
            startForegroundService(new Intent(this, FloatingService.class));
        } catch (IllegalStateException | SecurityException error) {
            Log.w("hoyoswitch", "悬浮按钮启动请求失败", error);
        }
    }

    private void runOp(String label, Op operation) {
        runOp(label, null, operation);
    }

    private void runOp(String label, SnapshotStore.Snapshot card, Op operation) {
        runOpInternal(label, card, false, false, operation);
    }

    private void runLocalCardOp(String label, SnapshotStore.Snapshot card, boolean removed, Op operation) {
        runOpInternal(label, card, true, removed, operation);
    }

    private void runOpInternal(String label, SnapshotStore.Snapshot card, boolean localOnly,
                               boolean removed, Op operation) {
        if (busy) {
            toast("上一个操作还没结束");
            return;
        }
        busy = true;
        // An edit/delete/update may change card metadata; never preview its old copy afterward.
        if (localOnly) {
            tabPreviews.remove(card.game);
        } else {
            tabPreviews.clear();
        }
        ++previewGeneration;
        ++refreshGeneration;
        final int operationGeneration = refreshGeneration;
        if (card != null) {
            focusCard(card);
        }
        appendLog("开始：" + label);
        // Keep the status pill untouched: rewriting it changes its height and shifts the list.
        new Thread(() -> {
            boolean succeeded = false;
            try {
                operation.run();
                succeeded = true;
            } catch (Exception error) {
                reportOperationError(error);
            } finally {
                final boolean completed = succeeded;
                main.post(() -> {
                    if (localOnly && completed && usable(this) && current == this && pageResumed
                            && displayedData != null && card.game == game
                            && operationGeneration == refreshGeneration) {
                        List<SnapshotStore.Snapshot> cards = new ArrayList<>();
                        for (SnapshotStore.Snapshot existing : displayedData.snapshots) {
                            if (!existing.id.equals(card.id)) {
                                cards.add(existing);
                            } else if (!removed) {
                                cards.add(SnapshotStore.copyForAction(card));
                            }
                        }
                        displayedData = new SnapshotStore.RefreshData(displayedData.current, cards,
                                new HashMap<>(displayedData.midById),
                                new HashMap<>(displayedData.readableById), displayedData.recoveryError);
                        tabPreviews.put(game, displayedData);
                        busy = false;
                        unfocusCard();
                        renderDisplayedCards(game);
                    } else {
                        finishOperation(false);
                    }
                });
            }
        }).start();
    }

    private void finishOperation(boolean followCard) {
        busy = false;
        // After a rotation the operation's owner is gone; finish on whichever page is live now.
        MainActivity target = usable(current) ? current : usable(this) ? this : null;
        if (target != null) {
            target.followCurrentCard |= followCard;
            target.unfocusCard();
            target.refresh();
        }
    }

    private static boolean usable(MainActivity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void appendLog(String message) {
        main.post(() -> {
            MainActivity target = usable(current) ? current : this;
            if (!usable(target) || target.logView == null) {
                return;
            }
            String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
            String line = time + "  " + message;
            String text = target.logView.getText().toString();
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
            target.logView.setText(next);
            final ScrollView scroll = target.logScroll;
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> toast(message));
        } else if (!isFinishing() && !isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private void reportOperationError(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        appendLog("失败：" + message);
        toast("操作失败：" + message);
    }
}
