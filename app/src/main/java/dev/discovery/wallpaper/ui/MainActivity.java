package dev.discovery.wallpaper.ui;

import static dev.discovery.wallpaper.ui.Ui.BACKGROUND;
import static dev.discovery.wallpaper.ui.Ui.TEXT;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import dev.discovery.wallpaper.R;
import dev.discovery.wallpaper.backup.TransferScreen;
import dev.discovery.wallpaper.cards.CardZipImport;
import dev.discovery.wallpaper.cards.CardsScreen;
import dev.discovery.wallpaper.cards.ImportScreen;
import dev.discovery.wallpaper.lockscreen.DiscoveryScreen;
import dev.discovery.wallpaper.lockscreen.RotationAlarm;
import dev.discovery.wallpaper.lockscreen.WallpaperDelivery;
import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.sources.SourcesScreen;
import dev.discovery.wallpaper.storage.Store;
import dev.discovery.wallpaper.topics.TopicsScreen;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    public static final int PICK_PHOTO = 1;
    public static final int PICK_AUDIO = 2;
    public static final int PICK_CARD_FILE = 4;
    public static final int PICK_SOURCE_PDF = 5;
    public static final int PICK_PDF_BATCH = 6;
    public static final int SAVE_CARD_MEDIA = 7;
    public static final int PICK_RELINK_PDF = 8;
    public static final int SAVE_DATA_EXPORT = 9;
    public static final int PICK_DATA_BACKUP = 10;
    private LinearLayout content;
    private ScrollView scroll;
    private Discovery.State state;
    private boolean showTopics;
    private boolean scheduleOk;
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService ioWorker = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver rotationUpdates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            discovery.updateRotationStatus();
        }
    };

    private final Ui ui = new Ui(this);
    public final DiscoveryScreen discovery = new DiscoveryScreen(this, ui);
    public final TopicsScreen topics = new TopicsScreen(this, ui);
    public final SourcesScreen sources = new SourcesScreen(this, ui);
    public final CardsScreen cards = new CardsScreen(this, ui);
    public final ImportScreen imports = new ImportScreen(this, ui);
    public final TransferScreen transfer = new TransferScreen(this, ui);

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            showTopics = saved.getBoolean("topics");
            discovery.restoreState(saved);
            cards.restoreState(saved);
            imports.restoreState(saved);
            sources.restoreState(saved);
            transfer.restoreState(saved);
        }
        LinearLayout root = ui.column();
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = ui.column();
        content.setPadding(ui.dp(22), ui.dp(20), ui.dp(22), ui.dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Pre-33 registration uses a signature permission.
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(WallpaperDelivery.CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rotationUpdates, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(rotationUpdates, filter, getPackageName() + ".permission.ROTATION_CHANGED", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(true);
        cards.restoreCardUi();
        imports.restoreImportUi();
        sources.restoreSourceEditor();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("topics", showTopics);
        discovery.saveState(out);
        cards.saveState(out);
        imports.saveState(out);
        sources.saveState(out);
        transfer.saveState(out);
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(rotationUpdates);
        cards.stopAudio();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        previewWorker.shutdownNow();
        imports.close();
        ioWorker.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        switch (request) {
            case PICK_DATA_BACKUP, SAVE_DATA_EXPORT, SAVE_CARD_MEDIA ->
                    transfer.onDocumentResult(request, result, data);
            case PICK_SOURCE_PDF, PICK_PDF_BATCH -> sources.onDocumentResult(request, result, data);
            case PICK_CARD_FILE, PICK_RELINK_PDF -> imports.onDocumentResult(request, result, data);
            case PICK_PHOTO, PICK_AUDIO -> cards.onMediaResult(request == PICK_PHOTO, result, data);
        }
    }

    public Discovery.State state() {
        return state;
    }

    public boolean scheduleOk() {
        return scheduleOk;
    }

    public ExecutorService previewWorker() {
        return previewWorker;
    }

    public ExecutorService ioWorker() {
        return ioWorker;
    }

    public void selectDiscoveryTab() {
        showTopics = false;
    }

    public void refresh(boolean keepScroll) {
        if (!keepScroll) scroll.scrollTo(0, 0);
        state = null;
        try {
            state = Store.read(this);
            scheduleOk = RotationAlarm.sync(this, state);
        } catch (IOException | RuntimeException error) {
            Log.w(WallpaperDelivery.TAG, "Could not load the home screen", error);
        }
        discovery.clearViews();
        content.removeAllViews();
        ui.gapText(content, getString(R.string.app_name), 30, TEXT, true, 0);
        if (state == null) {
            ui.gapText(content, "读取卡片失败。", 16, TEXT, false, 22);
            content.addView(ui.button("重试", () -> refresh(false), true));
            return;
        }
        LinearLayout nav = ui.row();
        ui.top(content, nav, 16);
        ui.equal(nav, ui.button("发现卡片", () -> changeTab(false), !showTopics), 0);
        boolean guide = !showTopics && state.topics.isEmpty();
        ui.equal(nav, ui.button(guide ? "添加主题与资料" : "主题与资料", () -> changeTab(true), showTopics), 8);
        if (guide) ui.guideToButton(content, "在这里添加你的主题", true);
        if (showTopics) topics.render(content, scroll);
        else discovery.render(content);
        cards.updateManager();
    }

    public void changeTab(boolean topics) {
        if (showTopics == topics) return;
        cards.stopAudio();
        showTopics = topics;
        refresh(false);
    }

    public void pick(String mimeType, int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mimeType)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException error) {
            ui.notice("系统文件选择器不可用");
        }
    }

    public void releaseUnused(String uri) {
        if (uri.isEmpty() || state == null || state.usesUri(uri)) return;
        if (cards.usesUri(uri) || sources.usesUri(uri) || transfer.usesUri(uri)) return;
        Uri parsed = Uri.parse(uri);
        if ("file".equals(parsed.getScheme())) {
            CardZipImport.releaseUnused(this, parsed);
            return;
        }
        if (!"content".equals(parsed.getScheme())) return;
        try {
            getContentResolver().releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The provider can revoke a grant at any time.
        }
    }

    public boolean commit(Store.Edit edit) {
        try {
            Store.edit(this, edit);
            return true;
        } catch (IOException error) {
            Log.w(WallpaperDelivery.TAG, "Could not save an edit", error);
            ui.notice("保存失败，请重试");
            return false;
        }
    }
}
