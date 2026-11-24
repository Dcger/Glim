package dev.discovery.wallpaper.cards;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import dev.discovery.wallpaper.R;
import dev.discovery.wallpaper.agent.AgentShare;
import dev.discovery.wallpaper.storage.SampleCards;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class CardMediaExport {
    public static Intent destination(Context context, String title, String media, boolean image) {
        String mime = null;
        String extension = "";
        if ("sample".equals(media)) {
            mime = "audio/wav";
            extension = "wav";
        } else {
            Uri uri = Uri.parse(media);
            MimeTypeMap types = MimeTypeMap.getSingleton();
            boolean bundled = SampleCards.assetName(uri) != null;
            String name = bundled || "file".equals(uri.getScheme())
                    ? uri.getLastPathSegment() : AgentShare.displayName(context, uri);
            if (name != null && name.lastIndexOf('.') >= 0) {
                String suffix = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                if (suffix.matches("[a-z0-9]{1,10}")) extension = suffix;
            }
            mime = bundled ? null : context.getContentResolver().getType(uri);
            if (mime == null || "application/octet-stream".equals(mime)) {
                mime = types.getMimeTypeFromExtension(extension);
            }
            if (extension.isEmpty() && mime != null) {
                String guessed = types.getExtensionFromMimeType(mime);
                if (guessed != null) extension = guessed;
            }
        }
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safeTitle.isEmpty()) safeTitle = "发现卡片";
        String filename = safeTitle + (image ? "-图片" : "-音频");
        if (!extension.isEmpty()) filename += "." + extension;
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime == null ? "application/octet-stream" : mime)
                .putExtra(Intent.EXTRA_TITLE, filename);
    }

    public static InputStream open(Context app, Uri uri) throws IOException {
        String asset = SampleCards.assetName(uri);
        return asset == null ? app.getContentResolver().openInputStream(uri) : app.getAssets().open(asset);
    }

    public static void copy(Context app, String media, Uri destination) throws IOException {
        try (InputStream input = "sample".equals(media)
                ? app.getResources().openRawResource(R.raw.timbre_comparison)
                : open(app, Uri.parse(media))) {
            if (input == null) throw new IOException("无法读取卡片媒体。");
            try (OutputStream output = app.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("无法写入所选位置。");
                byte[] buffer = new byte[64 * 1024];
                int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            }
        }
    }
}
