package dev.hoyoswitch.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Floating "更新快照" button. The user taps it after the game has loaded the switched account,
 * so the snapshot is refreshed only when they decide the login is real.
 */
public final class FloatingService extends Service {
    private static final String CHANNEL = "floating";
    private static final int NOTIFICATION_ID = 1;
    private static boolean running;
    // Main-thread only. Activity observers are registered just while the page is resumed.
    private static final List<Consumer<GameProfile>> snapshotObservers = new ArrayList<>();

    static void addSnapshotObserver(Consumer<GameProfile> observer) {
        snapshotObservers.add(observer);
    }

    static void removeSnapshotObserver(Consumer<GameProfile> observer) {
        snapshotObservers.remove(observer);
    }

    static boolean isRunning() {
        return running;
    }

    private GameProfile game = GameProfile.STARRAIL;
    private volatile boolean updating;

    private WindowManager windowManager;
    private TextView button;
    private final Consumer<Boolean> settingsObserver = floatingChanged -> {
        if (floatingChanged && running
                && UiSettings.floatingGame(AppAppearance.preferences(this)) == null) {
            stopSelf();
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener appearanceListener = (prefs, key) -> {
        if ((key == null || AppAppearance.MODE_KEY.equals(key)) && button != null) {
            applyButtonTheme(button);
        }
    };
    private WindowManager.LayoutParams params;
    private boolean moving;
    private int downX;
    private int downY;
    private int startX;
    private int startY;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        AppAppearance.preferences(this).registerOnSharedPreferenceChangeListener(appearanceListener);
        UiSettings.addObserver(settingsObserver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Both explicit starts and system restarts must honour the latest switch value.
        GameProfile savedGame = UiSettings.floatingGame(AppAppearance.preferences(this));
        boolean showButton = savedGame != null && Settings.canDrawOverlays(this);
        if (showButton) {
            game = savedGame;
        }
        try {
            // Even a cancelled start must satisfy startForegroundService's promotion contract.
            startAsForeground(showButton);
            if (!showButton) {
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            if (button == null) {
                addButton();
            }
            running = true;
            return START_STICKY;
        } catch (RuntimeException error) {
            Log.w("hoyoswitch", "无法显示悬浮按钮", error);
            toast("悬浮按钮暂未显示，请检查悬浮窗权限");
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        UiSettings.removeObserver(settingsObserver);
        AppAppearance.preferences(this).unregisterOnSharedPreferenceChangeListener(appearanceListener);
        running = false;
        if (button != null && windowManager != null) {
            try {
                windowManager.removeView(button);
            } catch (IllegalArgumentException ignored) {
                // Already detached.
            }
            button = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (button != null) {
            applyButtonTheme(button);
        }
    }

    private void startAsForeground(boolean showButton) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "悬浮按钮",
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        startForeground(NOTIFICATION_ID, buildNotification(showButton));
    }

    private Notification buildNotification(boolean showButton) {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(showButton ? "米游账号切换 · " + game.label : "米游账号切换")
                .setContentText(showButton ? "悬浮按钮已开启：切换后点它更新快照" : "正在停止悬浮服务")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setOngoing(true)
                .build();
    }

    private void addButton() {
        TextView view = new TextView(this);
        view.setText("更新快照");
        view.setTextSize(14);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setContentDescription("更新当前账号快照");
        int padH = dp(14);
        int padV = dp(9);
        view.setPadding(padH, padV, padH, padV);
        applyButtonTheme(view);
        view.setElevation(dp(6));

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(200);

        view.setOnTouchListener((touched, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    moving = false;
                    downX = (int) event.getRawX();
                    downY = (int) event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int deltaX = (int) event.getRawX() - downX;
                    int deltaY = (int) event.getRawY() - downY;
                    if (Math.abs(deltaX) > dp(8) || Math.abs(deltaY) > dp(8)) {
                        moving = true;
                    }
                    if (moving) {
                        clampPosition(view, startX + deltaX, startY + deltaY);
                        windowManager.updateViewLayout(button, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moving) {
                        updateSnapshot();
                    }
                    return true;
                default:
                    return false;
            }
        });

        windowManager.addView(view, params);
        button = view;
        view.post(() -> {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            Log.i("hoyoswitch", "floating button at " + location[0] + "," + location[1]
                    + " size " + view.getWidth() + "x" + view.getHeight());
        });
    }

    private void applyButtonTheme(TextView view) {
        Context palette = AppAppearance.wrap(getApplicationContext());
        view.setTextColor(palette.getColor(R.color.on_accent));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(24));
        shape.setColor(palette.getColor(R.color.accent));
        view.setBackground(shape);
    }

    /** Keeps the button fully on screen while dragging. */
    private void clampPosition(View view, int x, int y) {
        int width = view.getWidth() > 0 ? view.getWidth() : dp(120);
        int height = view.getHeight() > 0 ? view.getHeight() : dp(44);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        params.x = Math.max(0, Math.min(x, screenWidth - width));
        params.y = Math.max(0, Math.min(y, screenHeight - height));
    }

    private void updateSnapshot() {
        if (updating) {
            toast("正在更新，请稍候…");
            return;
        }
        updating = true;
        toast("正在更新快照…");
        final GameProfile selected = game;
        final SnapshotStore store = new SnapshotStore(this);
        new Thread(() -> {
            try {
                String id = AccountUpdater.updateCurrent(store, selected,
                        message -> Log.i("hoyoswitch", message));
                toast("已更新快照 " + id);
                new android.os.Handler(getMainLooper()).post(() -> {
                    for (Consumer<GameProfile> observer : new ArrayList<>(snapshotObservers)) {
                        observer.accept(selected);
                    }
                });
            } catch (Exception error) {
                toast("更新失败：" + error.getMessage());
            } finally {
                updating = false;
            }
        }).start();
    }

    private void toast(String message) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
