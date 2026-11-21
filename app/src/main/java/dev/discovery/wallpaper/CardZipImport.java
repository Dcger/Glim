package dev.discovery.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

final class CardZipImport {
    private static final long MIB = 1024L * 1024;
    private static final long MAX_BYTES = 64 * MIB;
    private static final int MAX_ENTRIES = 256;

    static final class Bundle implements AutoCloseable {
        final List<CardImport.Draft> drafts;
        private final File temporary;
        private final Map<String, File> files;

        Bundle(List<CardImport.Draft> drafts, File temporary, Map<String, File> files) {
            this.drafts = drafts;
            this.temporary = temporary;
            this.files = files;
        }

        String previewUri(String path) {
            return Uri.fromFile(files.get(path)).toString();
        }

        List<Discovery.Card> save(Context app, String topicId, List<String> sourceIds,
                                  List<CardImport.Draft> selected) throws IOException {
            File destination = new File(app.getFilesDir(), "card-media-" + UUID.randomUUID());
            Map<String, String> stored = new HashMap<>();
            List<Discovery.Card> added = new ArrayList<>();
            boolean committed = false;
            try {
                Store.edit(app, state -> added.addAll(CardImport.add(state, topicId, sourceIds, selected,
                        path -> {
                            String previous = stored.get(path);
                            if (previous != null) return previous;
                            if (!destination.isDirectory() && !destination.mkdir()) {
                                throw new IOException("无法创建卡片媒体目录。");
                            }
                            File source = files.get(path);
                            if (source == null) throw new IOException("找不到媒体文件 · " + path);
                            File saved = new File(destination, source.getName());
                            try (InputStream input = new FileInputStream(source);
                                 FileOutputStream output = new FileOutputStream(saved)) {
                                transfer(input, output, MAX_BYTES, "媒体文件过大。");
                                output.getFD().sync();
                            }
                            String uri = Uri.fromFile(saved).toString();
                            stored.put(path, uri);
                            return uri;
                        })));
                committed = true;
                return added;
            } finally {
                if (!committed) remove(destination);
            }
        }

        @Override
        public void close() {
            remove(temporary);
        }
    }

    static Bundle read(InputStream input, File cache) throws IOException {
        File temporary = new File(cache, "card-zip-" + UUID.randomUUID());
        if (!temporary.mkdir()) throw new IOException("无法创建导入缓存。");
        File archive = new File(temporary, "archive.zip");
        boolean ready = false;
        try {
            try (OutputStream output = new FileOutputStream(archive)) {
                transfer(input, output, MAX_BYTES, "ZIP 最大为 64 MiB。");
            }
            List<CardImport.Draft> drafts;
            Map<String, File> files = new HashMap<>();
            try (ZipFile zip = new ZipFile(archive)) {
                Map<String, ZipEntry> entries = entries(zip);
                drafts = readCards(zip, entries.get("cards.json"));
                long total = 0;
                for (CardImport.Draft draft : drafts) {
                    total += extract(zip, entries, files, temporary, draft.image(), true, MAX_BYTES - total);
                    total += extract(zip, entries, files, temporary, draft.audio(), false, MAX_BYTES - total);
                }
            }
            delete(archive);
            ready = true;
            return new Bundle(drafts, temporary, files);
        } catch (ZipException | IllegalArgumentException error) {
            throw new IOException("ZIP 格式有误，或包含无效的文件路径。", error);
        } finally {
            if (!ready) remove(temporary);
        }
    }

    private static Map<String, ZipEntry> entries(ZipFile zip) throws IOException {
        if (zip.size() > MAX_ENTRIES) throw new IOException("ZIP 最多包含 256 项。");
        Map<String, ZipEntry> files = new HashMap<>();
        var items = zip.entries();
        while (items.hasMoreElements()) {
            ZipEntry item = items.nextElement();
            if (item.isDirectory()) continue;
            if (files.put(item.getName(), item) != null) {
                throw new IOException("ZIP 包含同名文件 · " + item.getName());
            }
        }
        return files;
    }

    private static List<CardImport.Draft> readCards(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry == null) throw new IOException("请把 cards.json 放在 ZIP 根目录。");
        try (InputStream input = zip.getInputStream(entry)) {
            return CardImport.readJson(input, true);
        }
    }

    private static long extract(ZipFile zip, Map<String, ZipEntry> entries, Map<String, File> files,
                                File directory, String path, boolean image, long remaining) throws IOException {
        if (path.isEmpty()) return 0;
        if (!relativePath(path)) throw new IOException("媒体路径需指向 ZIP 内的文件 · " + path);
        int dot = path.lastIndexOf('.');
        String extension = dot > path.lastIndexOf('/') + 1
                ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String imageMime = switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "";
        };
        if (image ? imageMime.isEmpty() : !("mp3".equals(extension) || "m4a".equals(extension)
                || "wav".equals(extension))) {
            throw new IOException("媒体格式暂不支持 · " + path);
        }
        if (files.containsKey(path)) return 0;
        ZipEntry entry = entries.get(path);
        if (entry == null) throw new IOException("ZIP 缺少媒体文件 · " + path);
        long fileLimit = (image ? 12 : 24) * MIB;
        File target = new File(directory, "asset-" + files.size() + "." + extension);
        long size;
        try (InputStream input = zip.getInputStream(entry);
             OutputStream output = new FileOutputStream(target)) {
            size = transfer(input, output, Math.min(fileLimit, remaining), remaining < fileLimit
                    ? "ZIP 解包后的媒体总量最大为 64 MiB。" : "媒体文件过大 · " + path);
        }
        if (image) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(target.getPath(), bounds);
            if (!imageMime.equals(bounds.outMimeType) || bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || (long) bounds.outWidth * bounds.outHeight > 100_000_000) {
                throw new IOException("图片无法读取或尺寸过大 · " + path);
            }
            BitmapFactory.Options sample = new BitmapFactory.Options();
            sample.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 256);
            Bitmap bitmap = BitmapFactory.decodeFile(target.getPath(), sample);
            if (bitmap == null) throw new IOException("图片无法读取 · " + path);
            bitmap.recycle();
        } else {
            checkAudio(target, path);
        }
        files.put(path, target);
        return size;
    }

    private static void checkAudio(File file, String path) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        boolean hasAudio = false;
        try (FileInputStream input = new FileInputStream(file)) {
            extractor.setDataSource(input.getFD());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    throw new IOException("音频文件包含视频轨道 · " + path);
                }
                hasAudio |= mime != null && mime.startsWith("audio/");
            }
        } catch (IOException | RuntimeException error) {
            throw new IOException("音频无法读取 · " + path, error);
        } finally {
            extractor.release();
        }
        if (!hasAudio) throw new IOException("音频无法读取 · " + path);
    }

    private static boolean relativePath(String path) {
        if (path.contains("\\") || path.contains(":") || path.contains("\u0000")) return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    private static long transfer(InputStream input, OutputStream output, long limit, String error)
            throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            total += length;
            if (total > limit) throw new IOException(error);
            output.write(buffer, 0, length);
        }
        return total;
    }

    private static void remove(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) delete(file);
        delete(directory);
    }

    static void releaseUnused(Context app, Uri uri) {
        String path = uri.getPath();
        if (path == null) return;
        try {
            File file = new File(path).getCanonicalFile();
            File parent = file.getParentFile();
            if (parent == null || !parent.getName().startsWith("card-media-")
                    || !app.getFilesDir().getCanonicalFile().equals(parent.getParentFile())
                    || !file.getName().startsWith("asset-")) return;
            delete(file);
            String[] remaining = parent.list();
            if (remaining != null && remaining.length == 0) delete(parent);
        } catch (IOException error) {
            Log.w(WallpaperDelivery.TAG, "Could not release unused imported media", error);
        }
    }

    private static void delete(File file) {
        if (!file.delete() && file.exists()) {
            Log.w(WallpaperDelivery.TAG, "Could not remove " + file.getName());
        }
    }
}
