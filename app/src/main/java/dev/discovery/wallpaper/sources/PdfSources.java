package dev.discovery.wallpaper.sources;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import dev.discovery.wallpaper.agent.AgentShare;
import dev.discovery.wallpaper.lockscreen.WallpaperDelivery;
import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.storage.Store;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PdfSources {
    record Result(List<String> addedIds, int duplicates, int failed, String exampleFailure) {}

    record Page(Bitmap bitmap, int total) {}

    static Page renderPage(Context app, String uri, int number) throws IOException {
        ParcelFileDescriptor file;
        try {
            file = app.getContentResolver().openFileDescriptor(Uri.parse(uri), "r");
        } catch (IOException | RuntimeException error) {
            throw new IOException("无法读取 PDF，请检查附件。", error);
        }
        if (file == null) throw new IOException("无法读取 PDF，请检查附件。");

        PdfRenderer renderer;
        try {
            renderer = new PdfRenderer(file);
        } catch (IOException | RuntimeException error) {
            try {
                file.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw new IOException("无法在应用内预览这份 PDF，可用阅读器打开。", error);
        }
        try (PdfRenderer pdf = renderer) {
            int total = pdf.getPageCount();
            if (number < 1 || number > total) {
                throw new IOException("PDF 共 " + total + " 页，引用页码 " + number + " 超出范围。");
            }
            try (PdfRenderer.Page page = pdf.openPage(number - 1)) {
                float scale = Math.min(1600f / page.getWidth(), 2200f / page.getHeight());
                Bitmap image = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                        Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                image.eraseColor(Color.WHITE);
                try {
                    page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return new Page(image, total);
                } catch (RuntimeException error) {
                    image.recycle();
                    throw error;
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("无法显示 PDF 第 " + number + " 页，可用阅读器打开。", error);
        }
    }

    private record Candidate(String label, String uri) {}

    public static String relink(Context app, String topicId, String sourceId, Uri picked) throws IOException {
        String uri = picked.toString();
        Discovery.Topic snapshot = topic(Store.read(app), topicId);
        Discovery.Source before = source(snapshot, sourceId);
        if (before.fileUri.isEmpty()) throw new IOException("这份资料已移除 PDF 附件。");
        Map<String, String> hashes = index(app, snapshot);
        hashes.remove(uri);
        String fingerprint = hash(app, uri, hashes);
        List<String> granted = new ArrayList<>();
        try {
            Store.edit(app, state -> {
                Discovery.Topic current = topic(state, topicId);
                Discovery.Source previous = source(current, sourceId);
                if (!before.fileUri.equals(previous.fileUri) || !before.pdfHash.equals(previous.pdfHash)) {
                    throw new IOException("资料已变更，请重新检查附件。");
                }
                if (!previous.pdfHash.isEmpty() && !previous.pdfHash.equals(fingerprint)) {
                    throw new IOException("选中的 PDF 与原文件内容不同。请重新选择原 PDF，以保留页码定位。");
                }
                if (duplicate(current, sourceId, uri, fingerprint, hashes)) {
                    throw new IOException("此 PDF 已关联本主题中的另一份资料。");
                }
                retain(app, uri, granted);
                current.sources.set(current.sources.indexOf(previous),
                        new Discovery.Source(sourceId, previous.label, previous.url, uri, fingerprint));
            });
        } catch (IOException error) {
            releaseFailed(app, granted);
            throw error;
        }
        return before.fileUri.equals(uri) ? "" : before.fileUri;
    }

    static String save(Context app, String topicId, String existingId, String id, String label,
                       String url, String uri) throws IOException {
        Discovery.Topic snapshot = topic(Store.read(app), topicId);
        Discovery.Source before = source(snapshot, existingId);
        boolean newFile = !uri.isEmpty() && (before == null || !uri.equals(before.fileUri));
        Map<String, String> hashes = newFile ? index(app, snapshot) : new HashMap<>();
        String hash = newFile ? hash(app, uri, hashes) : "";
        String[] oldUri = {""};
        List<String> granted = new ArrayList<>();
        try {
            Store.edit(app, state -> {
                Discovery.Topic current = topic(state, topicId);
                Discovery.Source previous = source(current, existingId);
                int position = previous == null ? -1 : current.sources.indexOf(previous);
                if (previous != null) oldUri[0] = previous.fileUri;
                String fingerprint = "";
                if (!uri.isEmpty()) {
                    if (uri.equals(oldUri[0])) {
                        fingerprint = previous.pdfHash;
                    } else {
                        if (hash.isEmpty()) throw new IOException("资料已变更，请重新打开编辑。");
                        if (duplicate(current, existingId, uri, hash, hashes)) {
                            throw new IOException("此 PDF 已在本主题中。");
                        }
                        retain(app, uri, granted);
                        fingerprint = hash;
                    }
                }
                updateHashes(current, hashes);
                Discovery.Source updated = new Discovery.Source(
                        id, label, url, uri, fingerprint);
                if (position < 0) current.sources.add(updated);
                else current.sources.set(position, updated);
            });
        } catch (IOException error) {
            releaseFailed(app, granted);
            throw error;
        }
        return oldUri[0].equals(uri) ? "" : oldUri[0];
    }

    static Result addAll(Context app, String topicId, List<Uri> selected) throws IOException {
        Discovery.Topic snapshot = topic(Store.read(app), topicId);
        Map<String, String> hashes = index(app, snapshot);
        List<Candidate> candidates = new ArrayList<>();
        List<String> added = new ArrayList<>();
        int[] counts = new int[2];
        String[] failure = {""};
        for (Uri uri : selected) {
            String path = uri.toString();
            String label = AgentShare.filename(app, uri);
            try {
                hash(app, path, hashes);
                candidates.add(new Candidate(label, path));
            } catch (IOException error) {
                if (duplicate(snapshot, "", path, "", hashes)) counts[0]++;
                else failed(counts, failure, label);
            }
        }
        List<String> granted = new ArrayList<>();
        try {
            Store.edit(app, state -> {
                Discovery.Topic current = topic(state, topicId);
                updateHashes(current, hashes);
                for (Candidate pdf : candidates) {
                    String fingerprint = hashes.get(pdf.uri());
                    if (duplicate(current, "", pdf.uri(), fingerprint, hashes)) {
                        counts[0]++;
                        continue;
                    }
                    try {
                        retain(app, pdf.uri(), granted);
                        String id = UUID.randomUUID().toString();
                        current.sources.add(new Discovery.Source(id, pdf.label(), "", pdf.uri(), fingerprint));
                        added.add(id);
                    } catch (IOException error) {
                        failed(counts, failure, pdf.label());
                    }
                }
            });
        } catch (IOException error) {
            releaseFailed(app, granted);
            throw error;
        }
        return new Result(added, counts[0], counts[1], failure[0]);
    }

    private static Discovery.Topic topic(Discovery.State state, String id) throws IOException {
        Discovery.Topic topic = state.topics.get(id);
        if (topic == null) throw new IOException("主题已不存在。");
        return topic;
    }

    private static Discovery.Source source(Discovery.Topic topic, String id) throws IOException {
        if (id.isEmpty()) return null;
        for (Discovery.Source source : topic.sources) if (source.id.equals(id)) return source;
        throw new IOException("资料已不存在。");
    }

    private static Map<String, String> index(Context app, Discovery.Topic topic) {
        Map<String, String> hashes = new HashMap<>();
        for (Discovery.Source source : topic.sources) {
            if (!source.pdfHash.isEmpty()) hashes.put(source.fileUri, source.pdfHash);
        }
        for (Discovery.Source source : topic.sources) {
            if (source.fileUri.isEmpty() || hashes.containsKey(source.fileUri)) continue;
            try {
                hash(app, source.fileUri, hashes);
            } catch (IOException ignored) {
                // Retain URI-based matching for documents the provider no longer serves.
            }
        }
        return hashes;
    }

    private static boolean duplicate(Discovery.Topic topic, String except, String uri, String hash,
                                     Map<String, String> observed) {
        for (Discovery.Source source : topic.sources) {
            if (source.id.equals(except) || source.fileUri.isEmpty()) continue;
            if (uri.equals(source.fileUri) || (!hash.isEmpty() && (hash.equals(source.pdfHash)
                    || hash.equals(observed.get(source.fileUri))))) return true;
        }
        return false;
    }

    private static void updateHashes(Discovery.Topic topic, Map<String, String> hashes) {
        topic.sources.replaceAll(source -> {
            String hash = hashes.get(source.fileUri);
            return source.pdfHash.isEmpty() && hash != null
                    ? new Discovery.Source(source.id, source.label, source.url, source.fileUri, hash) : source;
        });
    }

    private static String hash(Context app, String uri, Map<String, String> hashes) throws IOException {
        String known = hashes.get(uri);
        if (known != null) return known;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        try (InputStream input = app.getContentResolver().openInputStream(Uri.parse(uri))) {
            if (input == null) throw new IOException();
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        } catch (IOException | RuntimeException error) {
            throw new IOException("无法读取所选 PDF。", error);
        }
        // The storage format is internal, so Base64 avoids API-specific hex utilities.
        String result = android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP);
        hashes.put(uri, result);
        return result;
    }

    private static void retain(Context app, String uri, List<String> granted) throws IOException {
        Uri parsed = Uri.parse(uri);
        try {
            boolean already = app.getContentResolver().getPersistedUriPermissions().stream()
                    .anyMatch(permission -> permission.isReadPermission() && parsed.equals(permission.getUri()));
            if (!already) {
                app.getContentResolver().takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                granted.add(uri);
            }
        } catch (RuntimeException error) {
            throw new IOException("无法保留 PDF 访问权限，请重新选择文件。", error);
        }
    }

    private static void releaseFailed(Context app, List<String> uris) {
        for (String uri : uris) {
            try {
                if (!Store.read(app).usesUri(uri)) {
                    app.getContentResolver().releasePersistableUriPermission(
                            Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (IOException | RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not release PDF access after failed save", error);
            }
        }
    }

    private static void failed(int[] counts, String[] failure, String label) {
        counts[1]++;
        if (failure[0].isEmpty()) failure[0] = label;
    }
}
