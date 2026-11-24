package dev.discovery.wallpaper.sources;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import dev.discovery.wallpaper.cards.CardMediaExport;
import dev.discovery.wallpaper.model.Discovery;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SourcePaths {
    private SourcePaths() {}

    public static Map<Discovery.Source, String> check(Context app, List<Discovery.Source> sources) {
        Map<Discovery.Source, String> problems = new LinkedHashMap<>();
        Map<String, String> pdfChecks = new HashMap<>();
        for (Discovery.Source source : sources) {
            List<String> reasons = new ArrayList<>(2);
            if (!source.url.isEmpty() && !isWebAddress(source.url)) {
                reasons.add("网页链接格式无效，请填写完整的 HTTP 或 HTTPS 链接。");
            }
            if (!source.fileUri.isEmpty()) {
                String pdf = pdfChecks.computeIfAbsent(source.fileUri, path -> checkPdf(app, path));
                if (!pdf.isEmpty()) reasons.add(pdf);
            } else if (!source.pdfHash.isEmpty()) {
                reasons.add("此资料的 PDF 路径已丢失，请重新选择 PDF 文件。");
            }
            if (!reasons.isEmpty()) problems.put(source, TextUtils.join("\n\n", reasons));
        }
        return problems;
    }

    static boolean isWebAddress(String address) {
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme();
            String host = Uri.parse(address).getHost();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && host != null && !host.isEmpty();
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static String checkPdf(Context app, String path) {
        Uri uri = Uri.parse(path);
        String scheme = uri.getScheme();
        if ("content".equals(scheme)) {
            if (uri.getAuthority() == null || uri.getAuthority().isEmpty()) return invalidPdfPath();
        } else if ("file".equals(scheme)) {
            if (uri.getPath() == null || !uri.getPath().startsWith("/")) return invalidPdfPath();
        } else {
            return invalidPdfPath();
        }
        try (InputStream input = CardMediaExport.open(app, uri)) {
            if (input == null) return "PDF 文件提供方没有返回可读的文件，请重新选择 PDF 文件。";
            if (input.read() == -1) return "PDF 文件为空，请重新选择 PDF 文件。";
            return "";
        } catch (SecurityException error) {
            return "已失去此 PDF 的读取权限，请重新选择 PDF 文件。";
        } catch (FileNotFoundException error) {
            return "找不到 PDF 文件。文件可能已移动或删除，请重新选择 PDF 文件。";
        } catch (IOException | RuntimeException error) {
            return "无法读取 PDF 文件，请确认文件可用后重新选择。";
        }
    }

    private static String invalidPdfPath() {
        return "PDF 文件路径无效，请重新选择 PDF 文件。";
    }
}
