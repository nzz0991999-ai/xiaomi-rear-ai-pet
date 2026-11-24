package com.mirearpet;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Lightweight foreground guard service: when enabled, automatically re-pins
 * RearPetActivity onto Xiaomi's rear display whenever HyperOS wakes or resets it.
 */
public class RearGuardService extends Service {
    public static final String ACTION_START = "com.mirearpet.GUARD_START";
    public static final String ACTION_STOP = "com.mirearpet.GUARD_STOP";
    public static final String ACTION_REPIN_NOW = "com.mirearpet.GUARD_REPIN_NOW";
    public static volatile boolean isRunning = false;

    private static final String CHANNEL_ID = "mi_rear_pet_guard";
    private static final int NOTIF_ID = 8765;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager displayManager;
    private String guardUrl = "http://127.0.0.1:8765/";
    private int guardDisplayId = 1;
    private long lastRepinAt = 0L;

    private final Runnable checkLoop = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            maybeRepin(false);
            handler.postDelayed(this, 3500);
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            if (displayId == guardDisplayId) maybeRepin(false);
        }

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == guardDisplayId) maybeRepin(false);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String act = intent != null ? intent.getAction() : "";
            if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                // When front screen locks/turns off, immediately tell our persistent KeepAlive daemon
                // (which holds a Display-1-only SCREEN_BRIGHT_WAKE_LOCK) to keep Display 1 awake & on top!
                if (RearPetActivity.hasRearInstance() && ShizukuBridge.isPermissionGranted()) {
                    handler.postDelayed(() -> ShizukuBridge.sendKeepAliveCommand("refront"), 150);
                    handler.postDelayed(() -> ShizukuBridge.sendKeepAliveCommand("refront"), 500);
                }
                return;
            }
            if (Intent.ACTION_USER_PRESENT.equals(act)) {
                // On unlock, check after 400ms; if RearPetActivity is already visible on Display 1, do NOT restart it!
                handler.postDelayed(() -> maybeRepin(false), 400);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, handler);
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            isRunning = false;
            ShizukuBridge.stopRearKeepAlive();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            String u = intent.getStringExtra("url");
            if (u != null && !u.trim().isEmpty()) guardUrl = u.trim();
            guardDisplayId = intent.getIntExtra("display_id", guardDisplayId);
        } else {
            guardUrl = getSharedPreferences("mi_rear_pet", MODE_PRIVATE)
                    .getString("url", guardUrl);
            guardDisplayId = getSharedPreferences("mi_rear_pet", MODE_PRIVATE)
                    .getInt("display_id", 1);
        }

        isRunning = true;
        startForeground(NOTIF_ID, buildNotification());
        ShizukuBridge.startRearKeepAlive(this, guardDisplayId);

        if (ACTION_REPIN_NOW.equals(action)) {
            maybeRepin(true);
        } else {
            handler.removeCallbacks(checkLoop);
            handler.postDelayed(checkLoop, 4000);
        }
        return START_STICKY;
    }

    private void maybeRepin(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastRepinAt) < 2500) return;
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            return;
        }
        if (!force && RearPetActivity.isVisible && RearPetActivity.activeDisplayId == guardDisplayId) {
            return;
        }
        if (!force && !ShizukuBridge.isDisplayAwake(this, guardDisplayId)) {
            return;
        }
        if (!ShizukuBridge.isPermissionGranted()) {
            return;
        }
        lastRepinAt = now;
        ShizukuBridge.launchOnRearDisplay(this, guardUrl, guardDisplayId, null);
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "背屏 AI 桌宠守护", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("背屏亮起或切回默认壁纸时自动重新投射 AI 桌宠");
            nm.createNotificationChannel(ch);
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openApp = PendingIntent.getActivity(
                this, 1, new Intent(this, MainActivity.class), piFlags);
        PendingIntent repinIntent = PendingIntent.getService(
                this, 2, new Intent(this, RearGuardService.class).setAction(ACTION_REPIN_NOW), piFlags);
        PendingIntent stopIntent = PendingIntent.getService(
                this, 3, new Intent(this, RearGuardService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setContentTitle("背屏 AI 桌宠守护中")
                .setContentText("目标背屏 displayId=" + guardDisplayId + " · " + guardUrl)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(openApp)
                .addAction(new Notification.Action.Builder(null, "立即拉回背屏", repinIntent).build())
                .addAction(new Notification.Action.Builder(null, "停止守护", stopIntent).build())
                .build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        ShizukuBridge.stopRearKeepAlive();
        handler.removeCallbacks(checkLoop);
        try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
