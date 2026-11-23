package dev.discovery.wallpaper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RotationAlarm extends BroadcastReceiver {
    private static final String ACTION = "dev.discovery.wallpaper.ROTATE_LOCK";
    private static final int LEGACY_JOB_ID = 2301;
    private static final long RETRY_DELAY = 5 * 60_000L;
    private static final long DISPATCH_TIMEOUT = 40_000L;
    private static final ExecutorService DISPATCH = Executors.newSingleThreadExecutor();
    private static long armedDeadline;

    static synchronized boolean sync(Context context, Discovery.State state) {
        try {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs.getPendingJob(LEGACY_JOB_ID) != null) jobs.cancel(LEGACY_JOB_ID);
            if (!state.automatic || state.browse("") == null) {
                PendingIntent existing = pending(context, PendingIntent.FLAG_NO_CREATE);
                if (existing != null) {
                    context.getSystemService(AlarmManager.class).cancel(existing);
                    existing.cancel();
                }
                armedDeadline = 0;
                return true;
            }

            long now = System.currentTimeMillis();
            long deadline = state.nextRotationAt;
            if (deadline <= 0) {
                long[] initialized = {0};
                Store.edit(context, current -> {
                    if (!current.automatic || current.browse("") == null) return;
                    if (current.nextRotationAt <= 0) {
                        long start = current.lastAppliedAt > 0 ? current.lastAppliedAt : now;
                        current.nextRotationAt = start + current.rotationIntervalMs();
                    }
                    initialized[0] = current.nextRotationAt;
                });
                deadline = initialized[0];
            }
            if (deadline > 0 && (deadline != armedDeadline || pending(context, PendingIntent.FLAG_NO_CREATE) == null)) {
                arm(context, deadline, deadline);
            }
            return true;
        } catch (IOException | RuntimeException error) {
            Log.w(WallpaperDelivery.TAG, "Could not schedule lock rotation", error);
            return false;
        }
    }

    private static PendingIntent pending(Context context, int flags) {
        Intent intent = new Intent(context, RotationAlarm.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context, 0, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void arm(Context context, long deadline, long attemptAt) {
        context.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                attemptAt, pending(context, PendingIntent.FLAG_UPDATE_CURRENT));
        armedDeadline = deadline;
        Log.i(WallpaperDelivery.TAG, "Rotation alarm armed for " + attemptAt + " (deadline " + deadline + ")");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        boolean rotate = ACTION.equals(intent.getAction());
        if (rotate) {
            synchronized (RotationAlarm.class) {
                armedDeadline = 0;
            }
        }
        long expires = SystemClock.elapsedRealtime() + DISPATCH_TIMEOUT;
        PendingResult finished = goAsync();
        PowerManager power = app.getSystemService(PowerManager.class);
        PowerManager.WakeLock awake = null;
        if (rotate) {
            try {
                awake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Glim:Rotation");
                awake.setReferenceCounted(false);
                awake.acquire(DISPATCH_TIMEOUT);
            } catch (RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not wake for rotation", error);
            }
        }
        PowerManager.WakeLock wakeLock = awake;
        DISPATCH.execute(() -> {
            boolean queued = false;
            try {
                synchronized (RotationAlarm.class) {
                    Discovery.State state = Store.read(app);
                    long now = System.currentTimeMillis();
                    if (!rotate || !state.rotationDue(now) || state.browse("") == null) {
                        sync(app, state);
                        return;
                    }
                    arm(app, state.nextRotationAt, now + RETRY_DELAY);
                }
                if (SystemClock.elapsedRealtime() >= expires) return;
                Log.i(WallpaperDelivery.TAG, "Scheduled rotation alarm delivered");
                WallpaperDelivery.run(app, WallpaperDelivery.Trigger.SCHEDULED, null,
                        () -> SystemClock.elapsedRealtime() >= expires,
                        message -> finish(finished, wakeLock));
                queued = true;
            } catch (IOException | RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not dispatch lock rotation", error);
            } finally {
                if (!queued) finish(finished, wakeLock);
            }
        });
    }

    private static void finish(PendingResult result, PowerManager.WakeLock awake) {
        try {
            if (awake != null && awake.isHeld()) awake.release();
        } finally {
            result.finish();
        }
    }
}
