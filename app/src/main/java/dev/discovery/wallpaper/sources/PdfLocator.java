package dev.discovery.wallpaper.sources;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PdfLocator {
    private static final Pattern FILE_PAGE = Pattern.compile(
            "(?i)(?<![a-z0-9])PDF\\s*(?:(?:文件\\s*)?第\\s*([0-9]{1,9})"
                    + "(?:\\s*[-–—~～至到]\\s*[0-9]{1,9})?\\s*页"
                    + "|(?:页(?:序|码)?\\s*[:：]?|pp?\\.?|pages?\\s+)\\s*([0-9]{1,9})(?![0-9]))");

    public static int page(String locator) {
        Matcher match = FILE_PAGE.matcher(locator);
        if (!match.find()) return 0;
        return Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2));
    }
}
