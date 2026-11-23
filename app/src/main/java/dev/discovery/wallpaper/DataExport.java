package dev.discovery.wallpaper;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DataExport {
    record Result(int topics, int cards, int files, int missing) {}

    static Result write(Context app, Uri destination) throws IOException {
        File prepared = File.createTempFile("glim-export-", ".zip", app.getCacheDir());
        try {
            Result result = writeArchive(app, prepared);
            try (InputStream input = new BufferedInputStream(new FileInputStream(prepared));
                 OutputStream output = app.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("无法写入所选位置");
                copy(input, output);
            }
            return result;
        } finally {
            prepared.delete();
        }
    }

    static Result writeArchive(Context app, File destination) throws IOException {
        JSONObject data = Store.snapshot(Store.read(app));
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(destination)))) {
            Assets assets = new Assets(app, zip);
            JSONArray topics = data.getJSONArray("topics");
            JSONArray cards = data.getJSONArray("cards");
            for (int i = 0; i < topics.length(); i++) {
                JSONArray sources = topics.getJSONObject(i).getJSONArray("sources");
                for (int j = 0; j < sources.length(); j++) {
                    JSONObject source = sources.getJSONObject(j);
                    assets.reference(source, "fileUri", "pdf", source.getString("label"));
                }
            }
            for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.getJSONObject(i);
                assets.reference(card, "photo", "image", card.getString("title"));
                assets.reference(card, "audio", "audio", card.getString("title"));
            }
            JSONArray recent = data.getJSONArray("recentLocks");
            for (int i = 0; i < recent.length(); i++) {
                JSONObject entry = recent.getJSONObject(i);
                String name = entry.getString("image");
                if (name.isEmpty()) continue;
                if (!name.matches("lock-[a-zA-Z0-9_-]+\\.png")) throw new IOException("无效的锁屏图片文件名");
                String path = "recent-locks/" + name;
                Uri media = Uri.fromFile(new File(app.getFilesDir(), path));
                entry.put("image", assets.add(media, path) ? path : "");
                if (entry.getString("image").isEmpty()) assets.missing("wallpaper", entry.getString("title"));
            }
            json(zip, "data.json", data);
            json(zip, "manifest.json", new JSONObject().put("format", DataRestore.FORMAT).put("version", DataRestore.VERSION)
                    .put("exportedAt", System.currentTimeMillis()).put("missing", assets.missing));
            return new Result(topics.length(), cards.length(), assets.files, assets.missing.length());
        } catch (JSONException error) {
            throw new IOException("无法生成数据导出文件", error);
        }
    }

    private static final class Assets {
        final Context app;
        final ZipOutputStream zip;
        final Map<String, String> paths = new HashMap<>();
        final JSONArray missing = new JSONArray();
        final Set<String> written = new HashSet<>();
        int files;

        Assets(Context app, ZipOutputStream zip) {
            this.app = app;
            this.zip = zip;
        }

        void reference(JSONObject item, String key, String kind, String label)
                throws IOException, JSONException {
            String value = item.getString(key);
            if (value.isEmpty() || value.equals("sample")) return;
            String path = paths.get(value);
            if (path == null) {
                Uri uri = Uri.parse(value);
                path = "assets/" + kind + "-" + (paths.size() + 1) + extension(uri, kind);
                if (!add(uri, path)) path = "";
                paths.put(value, path);
            }
            if (path.isEmpty()) missing(kind, label);
            item.put(key, path);
        }

        boolean add(Uri uri, String path) throws IOException {
            if (written.contains(path)) return true;
            InputStream input;
            try {
                input = app.getContentResolver().openInputStream(uri);
            } catch (IOException | RuntimeException error) {
                return false;
            }
            if (input == null) return false;
            try (InputStream source = new BufferedInputStream(input)) {
                zip.putNextEntry(new ZipEntry(path));
                copy(source, zip);
                zip.closeEntry();
                written.add(path);
                files++;
            }
            return true;
        }

        void missing(String kind, String label) throws JSONException {
            missing.put(new JSONObject().put("kind", kind).put("label", label));
        }

        private String extension(Uri uri, String kind) {
            if (kind.equals("pdf")) return ".pdf";
            String name;
            try {
                name = AgentShare.filename(app, uri);
            } catch (RuntimeException error) {
                name = "";
            }
            if (name.isEmpty()) name = uri.getLastPathSegment();
            if (name == null) return "";
            int dot = name.lastIndexOf('.');
            String suffix = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
            return suffix.matches("\\.[a-z0-9]{1,8}") ? suffix : "";
        }
    }

    private static void json(ZipOutputStream zip, String path, JSONObject data) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(data.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }
}
