package dev.discovery.wallpaper.lockscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Display;

import dev.discovery.wallpaper.cards.CardMediaExport;
import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.storage.SampleCards;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public final class WallpaperArt {
    private static final int WHITE = 0xfff5f5ea;
    private static final int INK = 0xff101710;
    private static final int LIGHT_GREEN = 0xffdeefa7;

    public static Size dimensions(Context context, int maxWidth) {
        DisplayMetrics metrics = new DisplayMetrics();
        Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) metrics.setTo(context.getResources().getDisplayMetrics());
        else display.getRealMetrics(metrics);
        int portraitWidth = Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels));
        int portraitHeight = Math.max(portraitWidth, Math.max(metrics.widthPixels, metrics.heightPixels));
        int outputWidth = Math.min(portraitWidth, maxWidth);
        int outputHeight = Math.round((float) portraitHeight / portraitWidth * outputWidth);
        return new Size(outputWidth, outputHeight);
    }

    static Bitmap render(Context context, Discovery.Card card, String topic, Size size) {
        int outputWidth = size.getWidth();
        int outputHeight = size.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float scale = outputWidth / 1080f;
        canvas.scale(scale, scale);
        float width = 1080;
        float height = outputHeight / scale;
        Bitmap photo = readPhoto(context, card.photoUri, outputWidth, outputHeight);
        try {
            if (photo == null) drawBackdrop(canvas, card.palette, width, height);
            else {
                canvas.drawColor(INK);
                drawImage(canvas, photo, width, height);
            }
            drawOverlay(canvas, card, topic, width, height, photo != null);
        } finally {
            if (photo != null) photo.recycle();
        }
        return bitmap;
    }

    static void drawBackdrop(Canvas canvas, String palette, float width, float height) {
        backdrop(canvas, new Paint(Paint.ANTI_ALIAS_FLAG), palette, width, height);
    }

    private static void drawImage(Canvas canvas, Bitmap photo, float width, float height) {
        float scale = Math.max(width / photo.getWidth(), height / photo.getHeight());
        float scaledWidth = photo.getWidth() * scale;
        float scaledHeight = photo.getHeight() * scale;
        float left = (width - scaledWidth) / 2;
        float top = (height - scaledHeight) / 2;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(photo, null, new RectF(left, top, left + scaledWidth, top + scaledHeight), paint);
    }

    static void drawOverlay(Canvas canvas, Discovery.Card card, String topic, float width, float height,
                            boolean hasPhoto) {
        float overlayTop = Math.min(width * 1600 / 1080, height * .68f);
        float textTop = overlayTop + height * .045f;
        float textBottom = height * (hasPhoto ? .92f : .85f);
        float scale = Math.min(1f, height / 1900f);
        float margin = 78;
        int textWidth = (int) (width - margin * 2);
        TextBlock block = textBlock(card, topic, textWidth, scale);
        if (hasPhoto && block.height() > textBottom - textTop) {
            scale *= (textBottom - textTop) / block.height();
            block = textBlock(card, topic, textWidth, scale);
        }

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (hasPhoto) {
            paint.setShader(new LinearGradient(0, overlayTop, 0, height,
                    new int[]{0x0009100d, 0xad09100d, 0xb809100d},
                    new float[]{0, (textTop - overlayTop) / (height - overlayTop), 1},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, overlayTop, width, height, paint);
        } else {
            paint.setShader(new LinearGradient(0, 0, 0, height,
                    new int[]{0x7108100b, 0x0008100b, 0x2008100b, 0xe50c150e, INK, INK},
                    new float[]{0, .25f, .44f, .64f, .84f, 1}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
        }

        float y = textBottom - block.height();
        if (hasPhoto) y = Math.max(y, textTop);
        y = draw(canvas, block.label(), margin, y) + block.gap();
        y = draw(canvas, block.title(), margin, y) + block.gap();
        y = draw(canvas, block.body(), margin, y);
        if (block.task() != null) draw(canvas, block.task(), margin, y + block.gap());
    }

    private record TextBlock(StaticLayout label, StaticLayout title, StaticLayout body, StaticLayout task,
                             float gap) {
        float height() {
            return label.getHeight() + title.getHeight() + body.getHeight() + gap * 2
                    + (task == null ? 0 : task.getHeight() + gap);
        }
    }

    private static TextBlock textBlock(Discovery.Card card, String topic, int width, float scale) {
        StaticLayout label = text(topic, 29 * scale, LIGHT_GREEN, true, width, 1);
        StaticLayout title = text(card.title, 64 * scale, WHITE, true, width, 2);
        StaticLayout body = text(card.body, 37 * scale, WHITE, false, width, 5);
        StaticLayout task = card.task.isEmpty() ? null
                : text("试一试  ·  " + card.task, 32 * scale, LIGHT_GREEN, false, width, 3);
        return new TextBlock(label, title, body, task, 26 * scale);
    }

    private static StaticLayout text(String value, float size, int color, boolean bold,
                                     int width, int maxLines) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return StaticLayout.Builder.obtain(value, 0, value.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true)
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(size * .11f, 1).build();
    }

    private static float draw(Canvas canvas, StaticLayout text, float x, float y) {
        canvas.save();
        canvas.translate(x, y);
        text.draw(canvas);
        canvas.restore();
        return y + text.getHeight();
    }

    public static Bitmap readPhoto(Context context, String value, int width, int height) {
        if (value.isEmpty()) return null;
        Uri uri = Uri.parse(value);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                String asset = SampleCards.assetName(uri);
                ImageDecoder.Source source;
                if (asset != null) source = ImageDecoder.createSource(context.getAssets(), asset);
                else if ("file".equals(uri.getScheme())) source = ImageDecoder.createSource(new File(uri.getPath()));
                else source = ImageDecoder.createSource(context.getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, input) -> {
                    Size original = info.getSize();
                    decoder.setTargetSampleSize(sampleSize(original.getWidth(), original.getHeight(), width, height));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
            try (InputStream bounds = CardMediaExport.open(context, uri)) {
                if (bounds == null) return null;
                BitmapFactory.decodeStream(bounds, null, options);
            }
            options.inSampleSize = sampleSize(options.outWidth, options.outHeight, width, height);
            options.inJustDecodeBounds = false;
            try (InputStream source = CardMediaExport.open(context, uri)) {
                return source == null ? null : BitmapFactory.decodeStream(source, null, options);
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static int sampleSize(int originalWidth, int originalHeight, int width, int height) {
        int factor = 1;
        while ((originalWidth / factor > width * 2 && originalHeight / factor > height * 2)
                || Math.max(originalWidth, originalHeight) / factor > 3600) {
            factor *= 2;
        }
        return factor;
    }

    private static void backdrop(Canvas canvas, Paint paint, String palette, float w, float h) {
        int base;
        int light;
        switch (palette) {
            case "amber":
                base = 0xff45301d;
                light = 0xffc88c3a;
                break;
            case "blue":
                base = 0xff142735;
                light = 0xff547ea0;
                break;
            default:
                base = 0xff172c22;
                light = 0xff6b9651;
                break;
        }
        paint.setShader(new RadialGradient(w * .76f, h * .28f, w * .86f,
                light, base, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f);
        for (int contour = 0; contour < 25; contour++) {
            float offset = contour * 23f;
            Path stroke = new Path();
            stroke.moveTo(-50, h * .5f + offset);
            stroke.cubicTo(w * .29f, h * .22f + offset * .9f, w * .56f, h * .25f - offset * .46f,
                    w + 70, h * .19f + offset * .64f);
            paint.setColor((contour % 4 == 0 ? 0x5a000000 : 0x30000000) | (LIGHT_GREEN & 0xffffff));
            canvas.drawPath(stroke, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
    }
}
