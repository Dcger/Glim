package dev.discovery.wallpaper;

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
import android.graphics.Rect;
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

import java.io.IOException;
import java.io.InputStream;

final class WallpaperArt {
    private static final int WHITE = 0xfff5f5ea;
    private static final int INK = 0xff101710;
    private static final int LIGHT_GREEN = 0xffdeefa7;

    static Size dimensions(Context context, int maxWidth) {
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
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap photo = readPhoto(context, card.photoUri, outputWidth, outputHeight);
        if (photo == null) {
            backdrop(canvas, paint, card.palette, width, height);
        } else {
            crop(canvas, photo, paint, width, height);
            photo.recycle();
        }
        paint.setShader(new LinearGradient(0, 0, 0, height,
                new int[]{0x7108100b, 0x0008100b, 0x2008100b, 0xe50c150e, INK, INK},
                new float[]{0, .25f, .44f, .64f, .84f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);

        float margin = 78;
        int textWidth = (int) (width - margin * 2);
        float contentScale = Math.min(1f, height / 1900f);
        StaticLayout label = text(topic, 29 * contentScale, LIGHT_GREEN, true, textWidth, 1);
        StaticLayout title = text(card.title, 64 * contentScale, WHITE, true, textWidth, 2);
        StaticLayout body = text(card.body, 37 * contentScale, WHITE, false, textWidth, 5);
        String prompt = card.task.isEmpty() ? ""
                : "试一试  ·  " + card.task;
        StaticLayout task = text(prompt, 32 * contentScale, LIGHT_GREEN, false, textWidth, 3);
        float gap = 26 * contentScale;
        float total = label.getHeight() + title.getHeight() + body.getHeight() + gap * 2;
        if (!prompt.isEmpty()) total += gap + task.getHeight();
        float y = height * .85f - total;
        y = draw(canvas, label, margin, y) + gap;
        y = draw(canvas, title, margin, y) + gap;
        y = draw(canvas, body, margin, y);
        if (!prompt.isEmpty()) draw(canvas, task, margin, y + gap);
        return bitmap;
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

    private static Bitmap readPhoto(Context context, String value, int width, int height) {
        if (value.isEmpty()) return null;
        Uri uri = Uri.parse(value);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, input) -> {
                    Size original = info.getSize();
                    decoder.setTargetSampleSize(sampleSize(original.getWidth(), original.getHeight(), width, height));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
            try (InputStream bounds = context.getContentResolver().openInputStream(uri)) {
                if (bounds == null) return null;
                BitmapFactory.decodeStream(bounds, null, options);
            }
            options.inSampleSize = sampleSize(options.outWidth, options.outHeight, width, height);
            options.inJustDecodeBounds = false;
            try (InputStream source = context.getContentResolver().openInputStream(uri)) {
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

    private static void crop(Canvas canvas, Bitmap photo, Paint paint, float width, float height) {
        float scale = Math.max(width / photo.getWidth(), height / photo.getHeight());
        int visibleWidth = Math.min(photo.getWidth(), Math.round(width / scale));
        int visibleHeight = Math.min(photo.getHeight(), Math.round(height / scale));
        int x = (photo.getWidth() - visibleWidth) / 2;
        int y = (photo.getHeight() - visibleHeight) / 3;
        canvas.drawBitmap(photo, new Rect(x, y, x + visibleWidth, y + visibleHeight),
                new RectF(0, 0, width, height), paint);
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
