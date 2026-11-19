package dev.hoyoswitch.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Floating "更新快照" button. The user taps it after the game has loaded the switched account,
 * so the snapshot is refreshed only when they decide the login is real.
 */
public final class FloatingService extends Service {
    private static final String CHANNEL = "floating";
    private static final int NOTIFICATION_ID = 1;
    private static boolean running;

    static boolean isRunning() {
        return running;
    }

    private WindowManager windowManager;
    private View button;
    private WindowManager.LayoutParams params;
    private boolean moving;
    private int downX;
    private int downY;
    private int startX;
    private int startY;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startAsForeground();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        addButton();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
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

    private void startAsForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "悬浮按钮",
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("账号切换")
                .setContentText("悬浮按钮已开启：切换后点它更新快照")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void addButton() {
        TextView view = new TextView(this);
        view.setText("更新快照");
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(14);
        int pad = dp(10);
        view.setPadding(pad, pad, pad, pad);
        view.setBackgroundColor(0xCC333333);
        view.setElevation(dp(4));

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
                        params.x = startX + deltaX;
                        params.y = startY + deltaY;
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

    private void updateSnapshot() {
        toast("正在更新快照…");
        final SnapshotStore store = new SnapshotStore(this);
        new Thread(() -> {
            try {
                String id = AccountUpdater.updateCurrent(store, message -> Log.i("hoyoswitch", message));
                toast("已更新快照 " + id);
            } catch (Exception error) {
                toast("更新失败：" + error.getMessage());
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
