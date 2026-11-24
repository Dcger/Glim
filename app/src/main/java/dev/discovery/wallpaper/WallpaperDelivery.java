package dev.discovery.wallpaper;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

final class WallpaperDelivery {
    static final String TAG = "Glim";
    static final String CHANGED = "dev.discovery.wallpaper.ROTATION_CHANGED";
    private static final String APPLIED = "已设为锁屏壁纸";
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Random RANDOM = new Random();

    interface Finished {
        void done(String message);
    }

    enum Trigger {
        MANUAL, SCHEDULED;

        boolean allowed(Discovery.State state) {
            return this == MANUAL || state.rotationDue(System.currentTimeMillis());
        }
    }

    static void signalChange(Context context) {
        context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName()));
    }

    static void run(Context context, Trigger trigger, String requestedCard, BooleanSupplier cancelled,
                    Finished finished) {
        enqueue(context, trigger, requestedCard, null, cancelled, finished);
    }

    static void reapply(Context context, Discovery.LockEntry entry, Finished finished) {
        enqueue(context, Trigger.MANUAL, "", entry, () -> false, finished);
    }

    private static void enqueue(Context context, Trigger trigger, String requestedCard,
                                Discovery.LockEntry previous, BooleanSupplier cancelled, Finished finished) {
        Context app = context.getApplicationContext();
        QUEUE.execute(() -> {
            String message = apply(app, trigger, requestedCard, previous, cancelled);
            if (APPLIED.equals(message)) {
                try {
                    RotationAlarm.sync(app, Store.read(app));
                } catch (IOException error) {
                    Log.w(TAG, "Could not reload rotation deadline", error);
                }
            }
            if (!message.isEmpty()) signalChange(app);
            MAIN.post(() -> finished.done(message));
        });
    }

    private static String apply(Context context, Trigger trigger, String requested,
                                Discovery.LockEntry previous, BooleanSupplier cancelled) {
        boolean automatic = trigger != Trigger.MANUAL;
        if (cancelled.getAsBoolean()) return "";
        Bitmap bitmap = null;
        File encoded = null;
        String created = "";
        boolean saved = false;
        try {
            Discovery.State snapshot = Store.read(context);
            if (!trigger.allowed(snapshot)) return "";
            Discovery.Card candidate = previous != null ? null
                    : automatic ? snapshot.next(RANDOM) : snapshot.cards.get(requested);
            if (previous != null && !snapshot.recentLocks.contains(previous)) return "这条记录已移出最近锁屏";
            if (previous == null && (candidate == null || !snapshot.topics.containsKey(candidate.topicId))) {
                return automatic ? "" : "当前卡片已不在此主题";
            }
            WallpaperManager wallpaper = WallpaperManager.getInstance(context);
            if (!wallpaper.isWallpaperSupported() || !wallpaper.isSetWallpaperAllowed()) {
                throw new IOException("系统当前禁止设置壁纸");
            }
            String cardId = previous == null ? candidate.id : previous.cardId();
            String title = previous == null ? candidate.title : previous.title();
            String topicName = previous == null ? snapshot.topics.get(candidate.topicId).name : previous.topic();
            bitmap = previous == null
                    ? WallpaperArt.render(context, candidate, topicName, WallpaperArt.dimensions(context, 1440))
                    : RecentWallpapers.load(context, previous, snapshot);
            String imageName = previous == null ? "" : previous.image();
            if (imageName.isEmpty()) {
                try {
                    created = RecentWallpapers.save(context, bitmap);
                    imageName = created;
                } catch (IOException error) {
                    Log.w(TAG, "Could not keep a lock wallpaper snapshot", error);
                }
            }
            encoded = File.createTempFile("set-lock-", ".jpg", context.getCacheDir());
            try (FileOutputStream destination = new FileOutputStream(encoded)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, destination)) {
                    throw new IOException("无法编码完整锁屏图片");
                }
            }
            Rect crop = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            bitmap.recycle();
            bitmap = null;

            Discovery.State latest = Store.read(context);
            if (cancelled.getAsBoolean() || !trigger.allowed(latest)) return "";
            if (automatic && (latest.randomRotation != snapshot.randomRotation
                    || !latest.lastCardId.equals(snapshot.lastCardId))) return "";
            if (trigger == Trigger.SCHEDULED && latest.nextRotationAt != snapshot.nextRotationAt) return "";
            if (previous != null) {
                if (!latest.recentLocks.contains(previous)) return "";
            } else {
                Discovery.Card card = automatic ? latest.eligible(cardId) : latest.cards.get(cardId);
                Discovery.Topic topic = card == null ? null : latest.topics.get(card.topicId);
                if (card == null || !card.artwork().equals(candidate.artwork())
                        || topic == null || !topic.name.equals(topicName)) return "";
            }

            int wallpaperId;
            try (FileInputStream source = new FileInputStream(encoded)) {
                long started = android.os.SystemClock.elapsedRealtime();
                wallpaperId = wallpaper.setStream(source, crop, false, WallpaperManager.FLAG_LOCK);
                Log.i(TAG, trigger + " setStream returned id=" + wallpaperId + " in "
                        + (android.os.SystemClock.elapsedRealtime() - started) + "ms");
            }
            if (wallpaperId <= 0) throw new IOException("系统未返回锁屏壁纸 ID");

            Discovery.LockEntry entry = new Discovery.LockEntry(cardId, title, topicName,
                    System.currentTimeMillis(), imageName);
            List<Discovery.LockEntry> history = new ArrayList<>(Discovery.RECENT_LIMIT);
            Store.edit(context, state -> {
                state.applied(entry, automatic);
                history.addAll(state.recentLocks);
            });
            Log.i(TAG, trigger + " applied card=" + cardId + " wallpaperId=" + wallpaperId);
            saved = !history.isEmpty();
            if (saved) RecentWallpapers.prune(context);
            return saved ? APPLIED : "";
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
            if (!saved) RecentWallpapers.discard(context, created);
            if (bitmap != null) bitmap.recycle();
            if (encoded != null) encoded.delete();
        }
    }
}
