package dev.discovery.wallpaper.backup;

import android.content.Context;
import android.net.Uri;

import dev.discovery.wallpaper.lockscreen.RecentWallpapers;
import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.storage.Store;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class DataRestore {
    static final String FORMAT = "glim-data";
    static final int VERSION = 1;
    private static final Pattern ASSET = Pattern.compile("assets/(?:pdf|image|audio)-[0-9]+(?:\\.[a-zA-Z0-9]{1,8})?");
    private static final Pattern HISTORY = Pattern.compile("recent-locks/lock-[a-zA-Z0-9_-]+\\.png");
    private static final int MAX_ENTRIES = 20_000;
    private static final int MAX_JSON = 16 * 1024 * 1024;
    private static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;

    public record Result(int topics, int cards, int missing) {}

    public static Result read(Context app, Uri uri) throws IOException {
        try (InputStream input = app.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取备份文件。");
            return restore(app, input);
        }
    }

    static Result restore(Context app, InputStream input) throws IOException {
        File directory = new File(app.getFilesDir(), "card-media-" + UUID.randomUUID());
        if (!directory.mkdir()) throw new IOException("无法建立附件恢复目录。");
        Map<String, File> entries = new HashMap<>();
        Map<String, File> snapshots = new HashMap<>();
        boolean committed = false;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            byte[] buffer = new byte[64 * 1024];
            JSONObject data = null, manifest = null;
            long remaining = MAX_BYTES;
            int count = 0;
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++count > MAX_ENTRIES) throw new IOException("备份文件数量超过上限。");
                String path = entry.getName();
                if (path.equals("data.json") && data == null) {
                    data = readJson(zip, buffer);
                } else if (path.equals("manifest.json") && manifest == null) {
                    manifest = readJson(zip, buffer);
                } else if (!entries.containsKey(path) && (ASSET.matcher(path).matches() || HISTORY.matcher(path).matches())) {
                    int slash = path.lastIndexOf('/');
                    File staged = new File(directory, "asset-" + path.substring(slash + 1));
                    try (FileOutputStream output = new FileOutputStream(staged)) {
                        long size = copy(zip, output, buffer, remaining);
                        if (size == 0) throw new IOException("备份中有空附件。");
                        remaining -= size;
                    }
                    entries.put(path, staged);
                } else {
                    throw new IOException("备份中含有无效或重复的文件路径。");
                }
                zip.closeEntry();
            }
            if (data == null || manifest == null || !FORMAT.equals(manifest.optString("format"))
                    || manifest.optInt("version") != VERSION) {
                throw new IOException("请选择 Glim 导出的备份 ZIP。");
            }

            for (int i = 0; i < data.getJSONArray("topics").length(); i++) {
                JSONArray sources = data.getJSONArray("topics").getJSONObject(i).getJSONArray("sources");
                for (int j = 0; j < sources.length(); j++) {
                    rebase(sources.getJSONObject(j), "fileUri", "assets/pdf-", entries);
                }
            }
            JSONArray cards = data.getJSONArray("cards");
            for (int i = 0; i < cards.length(); i++) {
                rebase(cards.getJSONObject(i), "photo", "assets/image-", entries);
                rebase(cards.getJSONObject(i), "audio", "assets/audio-", entries);
            }
            JSONArray recent = data.getJSONArray("recentLocks");
            if (recent.length() > Discovery.RECENT_LIMIT) throw new IOException("备份中的锁屏历史无效。");
            File history = RecentWallpapers.directory(app);
            for (int i = 0; i < recent.length(); i++) {
                JSONObject row = recent.getJSONObject(i);
                String path = row.getString("image");
                if (path.isEmpty()) continue;
                if (!HISTORY.matcher(path).matches() || !entries.containsKey(path)) {
                    throw new IOException("备份缺少锁屏快照。");
                }
                File target = snapshots.computeIfAbsent(path,
                        key -> new File(history, "lock-" + UUID.randomUUID() + ".png"));
                row.put("image", target.getName());
            }

            Discovery.State restored = Store.decode(data);
            int missing = manifest.getJSONArray("missing").length();
            restored.nextRotationAt = restored.automatic
                    ? System.currentTimeMillis() + restored.rotationIntervalMs() : 0;
            restored.status = "";
            synchronized (Store.class) {
                if (!snapshots.isEmpty() && !history.isDirectory() && !history.mkdirs()) {
                    throw new IOException("无法建立锁屏历史目录。");
                }
                for (Map.Entry<String, File> snapshot : snapshots.entrySet()) {
                    if (!entries.get(snapshot.getKey()).renameTo(snapshot.getValue())) {
                        throw new IOException("无法保存锁屏快照。");
                    }
                }
                Store.save(app, restored);
                committed = true;
            }
            return new Result(restored.topics.size(), restored.cards.size(), missing);
        } catch (JSONException error) {
            throw new IOException("备份内容不完整或格式有误。", error);
        } finally {
            if (!committed) {
                for (File target : snapshots.values()) target.delete();
                File[] files = directory.listFiles();
                if (files != null) for (File file : files) file.delete();
            }
            directory.delete();
        }
    }

    private static void rebase(JSONObject row, String key, String prefix, Map<String, File> entries)
            throws JSONException, IOException {
        String path = row.getString(key);
        if (path.isEmpty() || (key.equals("audio") && path.equals("sample"))) return;
        File file = entries.get(path);
        if (file == null || !path.startsWith(prefix)) throw new IOException("备份缺少对应附件。");
        row.put(key, Uri.fromFile(file).toString());
    }

    private static JSONObject readJson(InputStream input, byte[] buffer) throws IOException, JSONException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        copy(input, bytes, buffer, MAX_JSON);
        return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
    }

    private static long copy(InputStream input, OutputStream output, byte[] buffer, long limit) throws IOException {
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1; ) {
            total += count;
            if (total > limit) throw new IOException("备份解压大小超过上限。");
            output.write(buffer, 0, count);
        }
        return total;
    }
}
