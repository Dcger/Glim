package dev.discovery.wallpaper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

public final class RotationTestService extends Service {
    private static final long INTERVAL_MS = 10_000;
    private static final long DURATION_MS = 120_000;
    private static final int NOTIFICATION_ID = 2302;
    private static final String CHANNEL = "rotation_test";
    private static final String STOP = "dev.discovery.wallpaper.STOP_ROTATION_TEST";
    private static volatile boolean active;

    private final Handler timer = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private boolean applying;
    private long deadline;
    private PowerManager.WakeLock wakeLock;
    private final Runnable tick = this::rotate;

    static boolean isActive() {
        return active;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        try {
            startForeground(NOTIFICATION_ID, notification());
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "DiscoveryWallpaper:RotationTest");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(DURATION_MS);
            deadline = SystemClock.elapsedRealtime() + DURATION_MS;
            running = true;
            active = true;
            Log.i(WallpaperDelivery.TAG, "Rotation test started");
            timer.postDelayed(tick, INTERVAL_MS);
            timer.postDelayed(this::stopSelf, DURATION_MS);
            WallpaperDelivery.signalChange(this);
        } catch (RuntimeException error) {
            Log.w(WallpaperDelivery.TAG, "Could not start rotation test", error);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void rotate() {
        if (!running) return;
        if (SystemClock.elapsedRealtime() >= deadline) {
            stopSelf();
            return;
        }
        if (!applying) {
            applying = true;
            WallpaperDelivery.run(this, WallpaperDelivery.Trigger.TEST, null,
                    () -> !running || SystemClock.elapsedRealtime() >= deadline,
                    message -> applying = false);
        }
        timer.postDelayed(tick, INTERVAL_MS);
    }

    private Notification notification() {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, RotationTestService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "锁屏换图测试", NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        return builder.setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("锁屏换图测试")
                .setContentText("每 10 秒换图，2 分钟后自动结束")
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "结束测试", stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        active = false;
        timer.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.i(WallpaperDelivery.TAG, "Rotation test stopped");
        WallpaperDelivery.signalChange(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
