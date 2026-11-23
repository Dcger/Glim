package dev.discovery.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

final class RecentWallpapers {
    static File directory(Context context) {
        return new File(context.getFilesDir(), "recent-locks");
    }

    private static File file(Context context, String name) throws IOException {
        if (!name.matches("lock-[a-zA-Z0-9_-]+\\.png")) throw new IOException("无效的锁屏图片文件名");
        return new File(directory(context), name);
    }

    static String save(Context context, Bitmap bitmap) throws IOException {
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建锁屏历史目录");
        File image = File.createTempFile("lock-", ".png", directory);
        try {
            writePng(bitmap, image);
            return image.getName();
        } catch (IOException | RuntimeException error) {
            image.delete();
            throw error;
        }
    }

    static Bitmap load(Context context, Discovery.LockEntry entry, Discovery.State state) throws IOException {
        if (!entry.image().isEmpty()) {
            Bitmap bitmap = BitmapFactory.decodeFile(file(context, entry.image()).getPath());
            if (bitmap == null) throw new IOException("无法读取这条锁屏记录的图片");
            return bitmap;
        }
        Discovery.Card card = state.cards.get(entry.cardId());
        if (card == null) throw new IOException("这条锁屏记录的图片已不可用");
        return WallpaperArt.render(context, card, entry.topic(), WallpaperArt.dimensions(context, 1440));
    }

    static Uri prepareExport(Context context, Discovery.LockEntry entry) throws IOException {
        File output = File.createTempFile("export-lock-", ".png", context.getCacheDir());
        try {
            if (!entry.image().isEmpty()) {
                try (FileInputStream input = new FileInputStream(file(context, entry.image()));
                     FileOutputStream stream = new FileOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024];
                    int size;
                    while ((size = input.read(buffer)) != -1) stream.write(buffer, 0, size);
                }
            } else {
                Bitmap bitmap = load(context, entry, Store.read(context));
                try {
                    writePng(bitmap, output);
                } finally {
                    bitmap.recycle();
                }
            }
            return Uri.fromFile(output);
        } catch (IOException | RuntimeException error) {
            output.delete();
            throw error;
        }
    }

    static void discardExport(Context context, String value) {
        Uri uri = Uri.parse(value);
        String name = uri.getLastPathSegment();
        if (!"file".equals(uri.getScheme()) || name == null || !name.startsWith("export-lock-")) return;
        File file = new File(context.getCacheDir(), name);
        if (file.getPath().equals(uri.getPath())) file.delete();
    }

    static void discard(Context context, String name) {
        if (!name.isEmpty()) new File(directory(context), name).delete();
    }

    static void prune(Context context) {
        synchronized (Store.class) {
            try {
                Set<String> keep = new HashSet<>();
                for (Discovery.LockEntry entry : Store.read(context).recentLocks) keep.add(entry.image());
                File[] files = directory(context).listFiles((dir, name) -> name.startsWith("lock-") && name.endsWith(".png"));
                if (files == null) return;
                for (File file : files) {
                    if (!keep.contains(file.getName())) file.delete();
                }
            } catch (IOException error) {
                android.util.Log.w(WallpaperDelivery.TAG, "Could not prune wallpaper history", error);
            }
        }
    }

    private static void writePng(Bitmap bitmap, File file) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IOException("无法写入锁屏图片");
            }
        }
    }
}
