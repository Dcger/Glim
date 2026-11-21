package dev.discovery.wallpaper;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

final class WallpaperDelivery {
    static final String TAG = "DiscoveryWallpaper";
    static final String CHANGED = "dev.discovery.wallpaper.ROTATION_CHANGED";
    private static final String APPLIED = "已设为锁屏壁纸";
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Finished {
        void done(String message);
    }

    enum Trigger {
        MANUAL, SCHEDULED, TEST;

        boolean allowed(Discovery.State state) {
            return this != SCHEDULED || (state.automatic && !RotationTestService.isActive());
        }
    }

    static void signalChange(Context context) {
        context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName()));
    }

    static void run(Context context, Trigger trigger, String requestedCard, BooleanSupplier cancelled,
                    Finished finished) {
        Context app = context.getApplicationContext();
        QUEUE.execute(() -> {
            String message = apply(app, trigger, requestedCard, cancelled);
            if (!message.isEmpty()) signalChange(app);
            MAIN.post(() -> finished.done(message));
        });
    }

    private static String apply(Context context, Trigger trigger, String requested,
                                BooleanSupplier cancelled) {
        boolean automatic = trigger != Trigger.MANUAL;
        if (cancelled.getAsBoolean()) return "";
        Bitmap bitmap = null;
        try {
            Discovery.State snapshot = Store.read(context);
            if (!trigger.allowed(snapshot)) return "";
            Discovery.Card candidate = automatic ? snapshot.next(snapshot.lastCardId)
                    : snapshot.eligible(requested);
            if (candidate == null) return automatic ? "" : "当前卡片已退出轮换";
            WallpaperManager wallpaper = WallpaperManager.getInstance(context);
            if (!wallpaper.isWallpaperSupported() || !wallpaper.isSetWallpaperAllowed()) {
                throw new IOException("系统当前禁止设置壁纸");
            }
            String topicName = snapshot.topics.get(candidate.topicId).name;
            bitmap = WallpaperArt.render(context, candidate, topicName, WallpaperArt.dimensions(context, 1440));
            Bitmap image = bitmap;
            boolean[] applied = {false};
            Store.edit(context, state -> {
                Discovery.Card latest = state.eligible(candidate.id);
                if (cancelled.getAsBoolean() || !trigger.allowed(state) || latest == null
                        || !latest.artwork().equals(candidate.artwork())
                        || !state.topics.get(latest.topicId).name.equals(topicName)) return;
                int wallpaperId = wallpaper.setBitmap(image, null, false, WallpaperManager.FLAG_LOCK);
                if (wallpaperId <= 0) {
                    throw new IOException("系统未返回锁屏壁纸 ID");
                }
                applied[0] = true;
                state.applied(latest, System.currentTimeMillis());
                Log.i(TAG, trigger + " applied card=" + candidate.id + " wallpaperId=" + wallpaperId);
            });
            return applied[0] ? APPLIED : "";
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, trigger + " wallpaper rotation failed", error);
            String message = "换图失败。请查看系统是否允许应用设置锁屏壁纸。";
            try {
                Store.edit(context, state -> state.status = message);
            } catch (IOException storageError) {
                Log.w(TAG, "Could not record rotation error", storageError);
            }
            return message;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }
}
