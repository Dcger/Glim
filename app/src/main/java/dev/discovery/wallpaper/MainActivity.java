package dev.discovery.wallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff111612;
    private static final int PANEL = 0xff1e2720;
    private static final int PREVIEW = 0xff29342a;
    private static final int ACCENT = 0xffd5ee92;
    private static final int TEXT = 0xfff4f5eb;
    private static final int MUTED = 0xffb5c3b3;
    private static final int DELETE_ACCENT = 0xffffb5aa;
    private static final String TOPIC_NOTES_HINT =
            "（可选）描述任何和主题相关的补充信息，如知识了解程度、本地周围生态、个人兴趣等";
    private static final int MEDIA_INACTIVE = 0xff89958a;
    private static final int MEDIA_IMAGE = 0xffa8dd98;
    private static final int PICK_PHOTO = 1;
    private static final int PICK_AUDIO = 2;
    private static final int PICK_CARD_FILE = 4;
    private static final int PICK_SOURCE_PDF = 5;
    private static final int PICK_PDF_BATCH = 6;
    private static final int SAVE_CARD_MEDIA = 7;
    private static final int PICK_RELINK_PDF = 8;
    private static final int SAVE_DATA_EXPORT = 9;
    private static final int PICK_DATA_BACKUP = 10;

    private LinearLayout root;
    private LinearLayout content;
    private ScrollView scroll;
    private Discovery.State state;
    private boolean showTopics;
    private boolean applying;
    private boolean scheduleOk;
    private String previewId = "";
    private boolean recentPreview;
    private String expandedCardId = "";
    private final Set<Integer> expandedConcepts = new HashSet<>();
    private final Set<String> shuffledPreviews = new LinkedHashSet<>();
    private final Random previewRandom = new Random();
    private Bitmap previewBitmap;
    private PreviewKey previewKey;
    private PreviewKey pendingPreviewKey;
    private ImageView previewView;
    private LinearLayout cardDetails;
    private Button nextCard;
    private Button applyCard;
    private Switch rotationTest;
    private TextView rotationStatus;
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private Future<?> previewTask;
    private final ExecutorService importWorker = Executors.newSingleThreadExecutor();
    private Future<?> zipRead;
    private ImportTarget pendingCardFile;
    private AlertDialog importEntry;
    private Button agentShareButton;
    private PdfRelink pendingRelink;
    private String pendingPdfTopic;
    private CardZipImport.Bundle reviewedZip;
    private CardDraft draft;
    private Bundle restoreCardDraft;
    private CardManager cardManager;
    private String restoreManagerTopic;
    private ArrayList<String> restoreManagerSelection;
    private String pendingMediaExport = "";
    private SourceDraft sourceDraft;
    private Bundle restoreSourceDraft;
    private String importTopicId = "";
    private final ArrayList<String> importSourceIds = new ArrayList<>();
    private final ArrayList<String> agentCardIds = new ArrayList<>();
    private boolean includeTopicNotes;
    private String customAgentNotes = "";
    private MediaPlayer player;
    private final BroadcastReceiver rotationUpdates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (rotationTest != null) {
                rotationTest.setChecked(RotationTestService.isActive());
                updateRotationStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            showTopics = saved.getBoolean("topics");
            previewId = saved.getString("preview", "");
            recentPreview = saved.getBoolean("recentPreview");
            ArrayList<String> shown = saved.getStringArrayList("shuffledPreviews");
            if (shown != null) shuffledPreviews.addAll(shown);
            String topicId = saved.getString("zipTopic");
            if (topicId != null) pendingCardFile =
                    new ImportTarget(topicId, saved.getStringArrayList("zipSources"));
            pendingPdfTopic = saved.getString("pendingPdfTopic");
            restoreSourceDraft = saved.getBundle("sourceDraft");
            restoreCardDraft = saved.getBundle("cardDraft");
            restoreManagerTopic = saved.getString("managedTopic");
            restoreManagerSelection = saved.getStringArrayList("managedSelection");
            pendingMediaExport = saved.getString("mediaExport", "");
            importTopicId = saved.getString("importTopic", "");
            ArrayList<String> selected = saved.getStringArrayList("importSources");
            if (selected != null) importSourceIds.addAll(selected);
            selected = saved.getStringArrayList("agentCards");
            if (selected != null) agentCardIds.addAll(selected);
            String repairTopic = saved.getString("relinkTopic");
            if (repairTopic != null) pendingRelink = new PdfRelink(repairTopic, saved.getString("relinkSource"));
            includeTopicNotes = saved.getBoolean("includeTopicNotes");
            customAgentNotes = saved.getString("customAgentNotes", "");
        }
        root = column();
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
        content = column();
        content.setPadding(dp(22), dp(20), dp(22), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(WallpaperDelivery.CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rotationUpdates, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(rotationUpdates, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(true);
        restoreSourceEditor();
        restoreCardUi();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("topics", showTopics);
        out.putString("preview", previewId);
        out.putBoolean("recentPreview", recentPreview);
        out.putStringArrayList("shuffledPreviews", new ArrayList<>(shuffledPreviews));
        if (pendingCardFile != null) {
            out.putString("zipTopic", pendingCardFile.topicId());
            out.putStringArrayList("zipSources", new ArrayList<>(pendingCardFile.sourceIds()));
        }
        if (sourceDraft != null) out.putBundle("sourceDraft", sourceDraft.snapshot());
        if (draft != null) out.putBundle("cardDraft", draft.snapshot());
        if (cardManager != null) {
            out.putString("managedTopic", cardManager.topicId);
            if (cardManager.selecting) {
                out.putStringArrayList("managedSelection", new ArrayList<>(cardManager.selected));
            }
        }
        out.putString("mediaExport", pendingMediaExport);
        out.putString("pendingPdfTopic", pendingPdfTopic);
        out.putString("importTopic", importTopicId);
        out.putStringArrayList("importSources", new ArrayList<>(importSourceIds));
        out.putStringArrayList("agentCards", new ArrayList<>(agentCardIds));
        if (pendingRelink != null) {
            out.putString("relinkTopic", pendingRelink.topicId());
            out.putString("relinkSource", pendingRelink.sourceId());
        }
        out.putBoolean("includeTopicNotes", includeTopicNotes);
        out.putString("customAgentNotes", customAgentNotes);
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(rotationUpdates);
        stopAudio();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        previewWorker.shutdownNow();
        if (zipRead != null) zipRead.cancel(true);
        importWorker.shutdown();
        if (reviewedZip != null) reviewedZip.close();
        super.onDestroy();
    }

    private void refresh(boolean keepScroll) {
        if (!keepScroll) scroll.scrollTo(0, 0);
        state = null;
        try {
            state = Store.read(this);
            scheduleOk = RotationAlarm.sync(this, state);
        } catch (IOException | RuntimeException error) {
            Log.w(WallpaperDelivery.TAG, "Could not load the home screen", error);
        }
        previewView = null;
        rotationTest = null;
        content.removeAllViews();
        gapText(content, getString(R.string.app_name), 30, TEXT, true, 0);
        if (state == null) {
            gapText(content, "读取卡片失败。", 16, TEXT, false, 22);
            content.addView(button("重试", () -> refresh(false), true));
            return;
        }
        LinearLayout nav = row();
        top(content, nav, 16);
        equal(nav, button("发现卡片", () -> changeTab(false), !showTopics), 0);
        equal(nav, button("主题与资料", () -> changeTab(true), showTopics), 8);
        if (showTopics) topics();
        else cards();
        if (cardManager != null) cardManager.update();
    }

    private void changeTab(boolean topics) {
        if (showTopics == topics) return;
        stopAudio();
        showTopics = topics;
        refresh(false);
    }

    private void cards() {
        Discovery.Card card = recentPreview ? state.cards.get(previewId) : state.eligible(previewId);
        if (card == null) {
            recentPreview = false;
            card = state.nextPreview(previewId, shuffledPreviews, previewRandom);
        }
        if (card != null) {
            showCard(card);
        } else {
            LinearLayout box = panel(content, 18);
            gapText(box, "当前没有已启用的卡片", 18, TEXT, true, 0);
            top(box, button("管理主题", () -> changeTab(true), true), 10);
            top(content, browseModeButton(), 8);
        }


        LinearLayout settings = panel(content, 18);
        Switch automatic = toggle("自动更换锁屏", state.automatic);
        settings.addView(automatic);
        automatic.setOnCheckedChangeListener((control, enabled) -> {
            if (commit(value -> value.configureRotation(enabled, value.minutes, System.currentTimeMillis()))) refresh(true);
            else {
                control.setOnCheckedChangeListener(null);
                control.setChecked(!enabled);
                refresh(true);
            }
        });
        top(settings, button("轮换间隔 · " + state.minutes + " 分钟", this::interval, false), 8);
        top(settings, button("锁屏轮换方式 · " + modeName(state.randomRotation), () -> chooseMode(false), false), 8);
        if (state.automatic) {
            gapText(settings, scheduleOk ? "省电时换图可能延后。若在最近任务清理 App 后停止，重新打开即可恢复排程。"
                    : "系统拒绝了排程。可关闭自动更换后再开启。", 13, MUTED, false, 8);
        }
        rotationTest = toggle("每 10 秒换锁屏（测试）", RotationTestService.isActive());
        top(settings, rotationTest, 8);
        rotationTest.setOnCheckedChangeListener((control, enabled) -> {
            if (enabled != RotationTestService.isActive()) setRotationTest(enabled);
        });
        gapText(settings, "锁屏后可观察换图，2 分钟后自动结束。", 13, MUTED, false, 0);
        top(settings, button("最近锁屏", this::recentLocks, false), 10);
        rotationStatus = gapText(settings, "", 13, ACCENT, false, 8);
        showRotationStatus(state);
    }

    private void setRotationTest(boolean enabled) {
        Intent intent = new Intent(this, RotationTestService.class);
        try {
            if (!enabled) {
                stopService(intent);
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                var preferences = getPreferences(MODE_PRIVATE);
                if (!preferences.getBoolean("askedForTestNotifications", false)) {
                    preferences.edit().putBoolean("askedForTestNotifications", true).apply();
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
                }
            }
        } catch (RuntimeException error) {
            Log.w(WallpaperDelivery.TAG, "Could not change rotation test", error);
            rotationTest.setChecked(RotationTestService.isActive());
            notice("无法开启或结束换图测试。");
        }
    }

    private void updateRotationStatus() {
        try {
            showRotationStatus(Store.read(this));
        } catch (IOException error) {
            Log.w(WallpaperDelivery.TAG, "Could not read rotation status", error);
        }
    }

    private void showRotationStatus(Discovery.State latest) {
        rotationStatus.setVisibility(latest.status.isEmpty() ? View.GONE : View.VISIBLE);
        rotationStatus.setText(latest.status);
    }

    private void showCard(Discovery.Card card) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(rounded(PREVIEW, 24));
        frame.setPadding(dp(15), dp(15), dp(15), dp(15));
        ImageView image = new ImageView(this);
        previewView = image;
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(rounded(BACKGROUND, 16));
        image.setClipToOutline(true);
        image.setContentDescription("锁屏壁纸预览");
        Size size = WallpaperArt.dimensions(this, 440);
        int imageWidth = Math.min(dp(176), Math.round(
                (float) dp(369) / size.getHeight() * size.getWidth()));
        int imageHeight = Math.round((float) imageWidth * size.getHeight() / size.getWidth());
        frame.addView(image, new FrameLayout.LayoutParams(imageWidth, imageHeight, Gravity.CENTER));
        top(content, frame, 14);
        if (previewBitmap != null) image.setImageBitmap(previewBitmap);
        LinearLayout actions = row();
        top(content, actions, 8);
        nextCard = button("下一张", () -> {
            Discovery.Card following = state.nextPreview(previewId, shuffledPreviews, previewRandom);
            if (following == null || following.id.equals(previewId)) return;
            if (state.randomBrowse) state.rememberShuffle(shuffledPreviews, previewId, following.id);
            recentPreview = false;
            stopAudio();
            bindCard(following, size);
        }, false);
        equal(actions, nextCard, 0);
        applyCard = button("设为锁屏", () -> {
            Discovery.Card selected = recentPreview ? state.cards.get(previewId) : state.eligible(previewId);
            if (selected != null) apply(selected);
        }, true);
        equal(actions, applyCard, 8);
        top(content, browseModeButton(), 8);
        cardDetails = panel(content, 10);
        bindCard(card, size);
    }

    private Button browseModeButton() {
        return button("发现浏览顺序 · " + modeName(state.randomBrowse), () -> chooseMode(true), false);
    }

    private void bindCard(Discovery.Card card, Size size) {
        previewId = card.id;
        Discovery.Topic topic = state.topics.get(card.topicId);
        loadPreview(previewView, card, topic.name, size);
        LinearLayout details = cardDetails;
        details.removeAllViews();
        gapText(details, topic.name, 13, ACCENT, true, 0);
        gapText(details, card.title, 22, TEXT, true, 6);
        gapText(details, card.body, 15, TEXT, false, 8);
        if (!card.task.isEmpty()) gapText(details, "试一试 · " + card.task, 14, ACCENT, false, 10);
        showReferences(details, topic, card);
        for (Discovery.Source source : state.sources(card)) {
            if (source.url.isEmpty() && source.fileUri.isEmpty()) {
                gapText(details, source.label, 13, MUTED, false, 8);
            }
            if (!source.fileUri.isEmpty()) {
                top(details, button(source.label + " · PDF", () -> openSourcePdf(source), false), 8);
            }
            if (!source.url.isEmpty()) {
                top(details, button(source.label + " ↗", () -> openLink(source), false), 8);
            }
        }
        if (!card.audioUri.isEmpty()) {
            top(details, button(card.audioUri.equals("sample") ? "播放合成音对比" : "播放音频",
                    () -> play(card.audioUri), false), 8);
        }
        LinearLayout management = row();
        top(details, management, 8);
        equal(management, cardAction("编辑", ACCENT, () -> editCard(topic, card, null)), 0);
        equal(management, cardAction("删除", DELETE_ACCENT,
                () -> confirmCardDeletion(topic.id, Collections.singleton(card.id), null)), 8);

        updateCardActions();
    }

    private void showReferences(LinearLayout details, Discovery.Topic topic, Discovery.Card card) {
        if (!card.id.equals(expandedCardId)) {
            expandedCardId = card.id;
            expandedConcepts.clear();
        }
        if (!card.references.citations().isEmpty()) {
            gapText(details, "正文出处", 14, ACCENT, true, 12);
            showCitations(details, topic, card.references.citations());
        }
        if (!card.references.concepts().isEmpty()) {
            gapText(details, "涉及的概念", 14, ACCENT, true, 12);
        }
        for (int i = 0; i < card.references.concepts().size(); i++) {
            int index = i;
            Discovery.Concept concept = card.references.concepts().get(i);
            Button toggle = button("", () -> {}, false);
            top(details, toggle, 6);
            LinearLayout explanation = column();
            top(details, explanation, 0);
            gapText(explanation, concept.explanation(), 15, TEXT, false, 6);
            showCitations(explanation, topic, concept.citations());
            Runnable update = () -> {
                boolean open = expandedConcepts.contains(index);
                toggle.setText((open ? "收起 · " : "展开 · ") + concept.term());
                explanation.setVisibility(open ? View.VISIBLE : View.GONE);
            };
            toggle.setOnClickListener(view -> {
                if (!expandedConcepts.add(index)) expandedConcepts.remove(index);
                update.run();
            });
            update.run();
        }
    }

    private void showCitations(LinearLayout fields, Discovery.Topic topic, List<Discovery.Citation> citations) {
        for (Discovery.Citation citation : citations) {
            Discovery.Source source = topic.source(citation.sourceId());
            if (source == null) continue;
            gapText(fields, source.label, 14, TEXT, true, 8);
            gapText(fields, citation.locator(), 14, MUTED, false, 3).setTextIsSelectable(true);
            if (source.fileUri.isEmpty() && source.url.isEmpty()) continue;
            LinearLayout links = row();
            top(fields, links, 3);
            if (!source.fileUri.isEmpty()) {
                int page = PdfLocator.page(citation.locator());
                equal(links, cardAction(page > 0 ? "PDF 第 " + page + " 页" : "打开 PDF", ACCENT, () -> {
                    if (page > 0) showPdfPage(source, page);
                    else openSourcePdf(source);
                }), 0);
            }
            if (!source.url.isEmpty()) {
                equal(links, cardAction("打开网页", ACCENT, () -> openLink(source)),
                        source.fileUri.isEmpty() ? 0 : 8);
            }
        }
    }

    private void updateCardActions() {
        Discovery.Card following = state.browse(previewId);
        nextCard.setEnabled(!applying && following != null && !following.id.equals(previewId));
        applyCard.setText(applying ? "正在换图" : "设为锁屏");
        applyCard.setEnabled(!applying && (recentPreview || state.eligible(previewId) != null));
    }

    private record PreviewKey(String cardId, Discovery.Artwork artwork, String topic, Size size) {}

    private void loadPreview(ImageView image, Discovery.Card card, String topic, Size size) {
        previewView = image;
        PreviewKey key = new PreviewKey(card.id, card.artwork(), topic, size);
        if (key.equals(previewKey)) {
            pendingPreviewKey = null;
            if (previewTask != null) previewTask.cancel(false);
            image.setImageBitmap(previewBitmap);
            return;
        }
        if (key.equals(pendingPreviewKey)) return;
        pendingPreviewKey = key;
        if (previewTask != null) previewTask.cancel(false);
        previewTask = previewWorker.submit(() -> {
            Bitmap bitmap = WallpaperArt.render(getApplicationContext(), card, topic, size);
            runOnUiThread(() -> {
                if (isDestroyed() || !key.equals(pendingPreviewKey)) {
                    bitmap.recycle();
                    return;
                }
                pendingPreviewKey = null;
                previewKey = key;
                previewBitmap = bitmap;
                if (previewView != null) previewView.setImageBitmap(bitmap);
            });
        });
    }

    private void apply(Discovery.Card card) {
        applyWallpaper(done -> WallpaperDelivery.run(this, WallpaperDelivery.Trigger.MANUAL,
                card.id, () -> false, done));
    }

    private void applyWallpaper(Consumer<WallpaperDelivery.Finished> start) {
        if (applying) {
            notice("正在换图");
            return;
        }
        applying = true;
        if (previewView != null) updateCardActions();
        start.accept(message -> {
            applying = false;
            if (isDestroyed()) return;
            notice(message.isEmpty() ? "当前卡片已退出轮换" : message);
            if (rotationTest != null && previewView != null) updateCardActions();
        });
    }

    private static String modeName(boolean random) {
        return random ? "随机" : "顺序";
    }

    private void chooseMode(boolean browse) {
        boolean current = browse ? state.randomBrowse : state.randomRotation;
        new AlertDialog.Builder(this).setTitle(browse ? "发现浏览顺序" : "锁屏轮换方式")
                .setSingleChoiceItems(new String[]{"顺序", "随机"}, current ? 1 : 0,
                        (dialog, index) -> {
                            boolean random = index == 1;
                            if (random == current) {
                                dialog.dismiss();
                                return;
                            }
                            if (commit(latest -> {
                                if (browse) {
                                    latest.randomBrowse = random;
                                } else {
                                    latest.randomRotation = random;
                                    latest.shuffledLocks.clear();
                                }
                            })) {
                                if (browse) shuffledPreviews.clear();
                                dialog.dismiss();
                                refresh(true);
                            }
                        })
                .setNegativeButton("取消", null).show();
    }

    private void recentLocks() {
        try {
            List<Discovery.LockEntry> history = Store.read(this).recentLocks;
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("最近锁屏");
            if (history.isEmpty()) {
                dialog.setMessage("应用锁屏壁纸后，最近的记录会显示在这里。")
                        .setPositiveButton("知道了", null).show();
                return;
            }
            String[] labels = new String[history.size()];
            for (int i = 0; i < labels.length; i++) {
                Discovery.LockEntry entry = history.get(i);
                String when = DateFormat.format("MM/dd HH:mm:ss", new Date(entry.appliedAt())).toString();
                labels[i] = entry.title() + "\n" + when + " · " + entry.topic();
            }
            dialog.setItems(labels, (list, index) -> recentLockActions(history.get(index)))
                    .setNegativeButton("关闭", null).show();
        } catch (IOException error) {
            notice("无法读取最近锁屏");
        }
    }

    private void recentLockActions(Discovery.LockEntry entry) {
        new AlertDialog.Builder(this).setTitle(entry.title())
                .setItems(new String[]{"查看这张卡片", "保存图片", "重新设为锁屏"}, (dialog, index) -> {
                    switch (index) {
                        case 0 -> viewRecentCard(entry);
                        case 1 -> exportRecentLock(entry);
                        case 2 -> applyWallpaper(done -> WallpaperDelivery.reapply(this, entry, done));
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void viewRecentCard(Discovery.LockEntry entry) {
        try {
            Discovery.State current = Store.read(this);
            if (current.cards.containsKey(entry.cardId())) {
                stopAudio();
                showTopics = false;
                previewId = entry.cardId();
                recentPreview = true;
                refresh(false);
                return;
            }
            Context app = getApplicationContext();
            previewWorker.execute(() -> {
                try {
                    Bitmap bitmap = RecentWallpapers.load(app, entry, current);
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) {
                            bitmap.recycle();
                            return;
                        }
                        ImageView image = new ImageView(this);
                        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        image.setContentDescription("历史锁屏图片");
                        image.setImageBitmap(bitmap);
                        FrameLayout frame = new FrameLayout(this);
                        frame.addView(image, new FrameLayout.LayoutParams(-1, dp(465)));
                        new AlertDialog.Builder(this).setTitle(entry.title()).setView(frame)
                                .setPositiveButton("关闭", null)
                                .setOnDismissListener(ignored -> {
                                    image.setImageDrawable(null);
                                    bitmap.recycle();
                                }).show();
                    });
                } catch (IOException | RuntimeException error) {
                    runOnUiThread(() -> notice("无法查看这张卡片的锁屏图片"));
                }
            });
        } catch (IOException error) {
            notice("无法读取卡片");
        }
    }

    private void interval() {
        String[] items = new String[Discovery.INTERVALS.length];
        int selected = -1;
        for (int i = 0; i < items.length; i++) {
            items[i] = Discovery.INTERVALS[i] + " 分钟";
            if (Discovery.INTERVALS[i] == state.minutes) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("轮换间隔")
                .setSingleChoiceItems(items, selected, (dialog, index) -> {
                    if (commit(value -> value.configureRotation(value.automatic, Discovery.INTERVALS[index],
                            System.currentTimeMillis()))) {
                        dialog.dismiss();
                        refresh(true);
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void topics() {
        LinearLayout backupActions = row();
        top(content, backupActions, 12);
        equal(backupActions, button("导出数据", this::chooseDataExport, false), 0);
        equal(backupActions, button("导入备份", () -> pick("application/zip", PICK_DATA_BACKUP), false), 8);
        for (Discovery.Topic topic : state.topics.values()) {
            LinearLayout box = panel(content, 12);
            Switch enabled = toggle(topic.name, topic.enabled);
            box.addView(enabled);
            enabled.setOnCheckedChangeListener((control, checked) -> {
                commit(value -> value.topics.get(topic.id).enabled = checked);
                if (!checked) stopAudio();
                refresh(true);
            });
            if (!topic.context.isEmpty()) gapText(box, topic.context, 14, MUTED, false, 8);
            long count = 0;
            for (Discovery.Card card : state.cards.values()) {
                if (topic.id.equals(card.topicId)) count++;
            }
            gapText(box, count + " 张卡片 · " + topic.sources.size() + " 份资料", 13, MUTED, false, 8);
            LinearLayout actions = row();
            top(box, actions, 10);
            equal(actions, button("资料与备注", () -> topicDetails(topic), false), 0);
            equal(actions, button("管理卡片", () -> manageCards(topic), false), 8);
        }
        top(content, button("新建主题", this::newTopic, true), 14);
    }

    private void newTopic() {
        LinearLayout fields = fields();
        EditText name = labeledField(fields, "标题", "", 32, 1);
        EditText notes = labeledField(fields, "背景或备注", "", 900, 3);
        notes.setHint(TOPIC_NOTES_HINT);
        form("新建主题", fields, () -> {
            if (value(name).isEmpty()) return missing(name, "填写主题名");
            return commit(value -> {
                Discovery.Topic topic = new Discovery.Topic(uuid(), value(name), true);
                topic.context = value(notes);
                value.topics.put(topic.id, topic);
            });
        });
    }

    private void topicDetails(Discovery.Topic topic) {
        LinearLayout layout = column();
        LinearLayout actions = row();
        actions.setPadding(dp(22), dp(6), dp(22), dp(8));
        layout.addView(actions);
        ScrollView body = new ScrollView(this);
        layout.addView(body, new LinearLayout.LayoutParams(-1, -2, 1));
        LinearLayout fields = fields();
        body.addView(fields);
        EditText name = labeledField(fields, "标题", topic.name, 32, 1);
        EditText notes = labeledField(fields, "背景或备注", topic.context, 900, 3);
        notes.setHint(TOPIC_NOTES_HINT);
        LinearLayout list = column();
        top(fields, list, 12);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("资料与备注")
                .setView(layout).setNegativeButton("取消", null).create();
        Consumer<Discovery.Source> edit = source -> {
            if (saveTopic(topic, name, notes)) {
                dialog.dismiss();
                editSource(topic.id, source, null);
            }
        };
        equal(actions, button("添加资料", () -> new AlertDialog.Builder(this).setTitle("添加资料")
                .setItems(new String[]{"添加单份资料", "批量导入 PDF"}, (menu, which) -> {
                    if (which == 0) edit.accept(null);
                    else if (saveTopic(topic, name, notes)) {
                        dialog.dismiss();
                        pickPdfBatch(topic.id);
                    }
                }).setNegativeButton("取消", null).show(), true), 0);
        Button delete = button("删除资料", () -> {}, DELETE_ACCENT, BACKGROUND);
        delete.setOnClickListener(view -> {
            Discovery.Topic current = state == null ? null : state.topics.get(topic.id);
            if (current == null || current.sources.isEmpty()) return;
            chooseSourcesToDelete(current, updated -> {
                renderTopicSources(list, updated, edit);
                delete.setEnabled(!updated.sources.isEmpty());
            });
        });
        delete.setEnabled(!topic.sources.isEmpty());
        equal(actions, delete, 8);
        renderTopicSources(list, topic, edit);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存", (dialogInterface, which) -> {});
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "删除主题", (dialogInterface, which) -> {});
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (saveTopic(topic, name, notes)) dialog.dismiss();
        });
        Button deleteTopic = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        deleteTopic.setTextColor(DELETE_ACCENT);
        deleteTopic.setOnClickListener(view -> confirmTopicDeletion(topic.id, dialog));
    }

    private void renderTopicSources(LinearLayout list, Discovery.Topic topic, Consumer<Discovery.Source> edit) {
        list.removeAllViews();
        if (topic.sources.isEmpty()) {
            gapText(list, "还没有资料", 14, MUTED, false, 0);
            return;
        }
        for (Discovery.Source source : topic.sources) {
            top(list, button(sourceLabel(source), () -> edit.accept(source), false), 8);
        }
    }

    private void chooseSourcesToDelete(Discovery.Topic topic, Consumer<Discovery.Topic> onDeleted) {
        showSourceChoices("选择要删除的资料", topic.sources, Collections.emptyList(), true,
                (picker, selected) -> confirmSourceDeletion(topic.id, selected, picker, onDeleted));
    }

    private void showSourceChoices(String title, List<Discovery.Source> sources, List<String> current,
                                   boolean deleting, BiConsumer<AlertDialog, Set<String>> confirm) {
        String[] labels = new String[sources.size()];
        boolean[] checked = new boolean[labels.length];
        Set<String> selected = new LinkedHashSet<>();
        for (int i = 0; i < labels.length; i++) {
            Discovery.Source source = sources.get(i);
            labels[i] = sourceLabel(source);
            checked[i] = current.contains(source.id);
            if (checked[i]) selected.add(source.id);
        }
        AlertDialog picker = new AlertDialog.Builder(this).setTitle(title)
                .setMultiChoiceItems(labels, checked, (dialog, index, on) -> {
                    String id = sources.get(index).id;
                    if (on) selected.add(id);
                    else selected.remove(id);
                    updateSourceChoices((AlertDialog) dialog, selected.size(), sources.size(), deleting);
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("全选", null)
                .setPositiveButton(deleting ? "删除" : "完成", null).show();
        picker.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            boolean select = selected.size() < sources.size();
            selected.clear();
            for (int i = 0; i < sources.size(); i++) {
                checked[i] = select;
                picker.getListView().setItemChecked(i, select);
                if (select) selected.add(sources.get(i).id);
            }
            updateSourceChoices(picker, selected.size(), sources.size(), deleting);
        });
        picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
                confirm.accept(picker, new LinkedHashSet<>(selected)));
        updateSourceChoices(picker, selected.size(), sources.size(), deleting);
    }

    private static void updateSourceChoices(AlertDialog dialog, int selected, int total, boolean deleting) {
        Button all = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        all.setEnabled(total > 0);
        all.setText(total > 0 && selected == total ? "取消全选" : "全选");
        if (deleting) {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setText(selected == 0 ? "删除" : "删除 " + selected + " 份");
            confirm.setEnabled(selected > 0);
        }
    }

    private void confirmSourceDeletion(String topicId, Set<String> ids, AlertDialog picker,
                                       Consumer<Discovery.Topic> onDeleted) {
        AlertDialog confirmation = new AlertDialog.Builder(this)
                .setTitle("删除 " + ids.size() + " 份资料？")
                .setMessage("删除后，卡片上的相关资料链接也会移除。卡片和原 PDF 会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", null).show();
        Button confirm = confirmation.getButton(AlertDialog.BUTTON_POSITIVE);
        confirm.setTextColor(DELETE_ACCENT);
        confirm.setOnClickListener(view -> {
            List<Discovery.Source> removed = new ArrayList<>();
            if (!commit(latest -> removed.addAll(latest.removeSources(topicId, ids)))) return;
            confirmation.dismiss();
            if (picker != null) picker.dismiss();
            refresh(true);
            Discovery.Topic updated = state == null ? null : state.topics.get(topicId);
            if (updated != null) onDeleted.accept(updated);
            for (Discovery.Source source : removed) releaseUnused(source.fileUri);
            notice("已删除 " + removed.size() + " 份资料");
        });
    }

    private void confirmTopicDeletion(String topicId, AlertDialog details) {
        Discovery.Topic topic = state == null ? null : state.topics.get(topicId);
        if (topic == null) return;
        long cards = state.cards.values().stream().filter(card -> topicId.equals(card.topicId)).count();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("删除「" + topic.name + "」？")
                .setMessage("将删除此主题及其中的 " + cards + " 张卡片、" + topic.sources.size()
                        + " 份资料记录。设备上的原文件会保留。")
                .setNegativeButton("取消", null).setPositiveButton("删除主题", null).show();
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        confirm.setTextColor(DELETE_ACCENT);
        confirm.setOnClickListener(view -> {
            Set<String> released = new HashSet<>();
            if (!commit(latest -> latest.removeTopic(topicId, released))) return;
            dialog.dismiss();
            details.dismiss();
            if (topicId.equals(importTopicId)) {
                importTopicId = "";
                resetAgentOptions();
            }
            refresh(true);
            for (String uri : released) releaseUnused(uri);
            notice("主题已删除");
        });
    }

    private static String sourceLabel(Discovery.Source source) {
        return source.fileUri.isEmpty() ? source.label : source.label + " · PDF";
    }

    private void chooseCardEntry(Discovery.Topic topic) {
        new AlertDialog.Builder(this).setTitle("添加卡片 · " + topic.name)
                .setItems(new String[]{"手动添加卡片", "批量导入卡片"}, (dialog, which) -> {
                    if (which == 0) editCard(topic, null, null);
                    else importCards(topic);
                })
                .setNegativeButton("取消", null).show();
    }

    private void manageCards(Discovery.Topic topic) {
        manageCards(topic, null);
    }

    private void manageCards(Discovery.Topic topic, List<String> selection) {
        cardManager = new CardManager(topic, selection);
        cardManager.update();
    }

    private final class CardManager {
        final String topicId;
        final ArrayList<Discovery.Card> cards = new ArrayList<>();
        final Set<String> selected = new HashSet<>();
        boolean selecting;
        final ArrayAdapter<Discovery.Card> adapter = cardRows(cards,
                card -> selecting ? selected.contains(card.id) : state.cardEnabled(card.id),
                () -> selecting ? "选中删除" : "参与浏览与轮换", this::setChecked);

        private void setChecked(Discovery.Card card, boolean checked) {
            if (selecting) {
                changeSelection(card.id, checked);
            } else if (commit(latest -> latest.setCardEnabled(card.id, checked))) {
                shuffledPreviews.remove(card.id);
                if (!checked && card.id.equals(previewId)) stopAudio();
                refresh(true);
            } else {
                adapter.notifyDataSetChanged();
            }
        }

        final LinearLayout actions;
        final LinearLayout enableActions;
        final LinearLayout deleteActions;
        final TextView hint;
        final Button select;
        final Button enableAll;
        final Button disableAll;
        final Button delete;
        final Button selectAll;
        final Button done;
        final AlertDialog manager;

        CardManager(Discovery.Topic topic, List<String> selection) {
            topicId = topic.id;
            selecting = selection != null;
            if (selection != null) selected.addAll(selection);
            LinearLayout content = fields();
            actions = row();
            top(content, actions, 0);
            equal(actions, button("添加卡片", () -> {
                Discovery.Topic current = state.topics.get(topicId);
                if (current != null) chooseCardEntry(current);
            }, true), 0);
            select = button("批量删除", () -> setSelecting(true), false);
            equal(actions, select, 8);
            enableActions = row();
            top(content, enableActions, 6);
            enableAll = button("全部启用", () -> setAllEnabled(true), false);
            disableAll = button("全部停用", () -> setAllEnabled(false), false);
            equal(enableActions, enableAll, 0);
            equal(enableActions, disableAll, 8);
            deleteActions = row();
            top(content, deleteActions, 0);
            equal(deleteActions, button("退出选择", () -> setSelecting(false), false), 0);
            delete = button("删除选中", () -> confirmCardDeletion(topicId, new HashSet<>(selected),
                            () -> setSelecting(false)),
                    DELETE_ACCENT, BACKGROUND);
            equal(deleteActions, delete, 8);
            selectAll = button("全选", () -> {
                if (selected.size() == cards.size()) selected.clear();
                else for (Discovery.Card card : cards) selected.add(card.id);
                updateActions();
                adapter.notifyDataSetChanged();
            }, false);
            top(content, selectAll, 6);
            hint = gapText(content, "", 13, MUTED, false, 10);
            TextView empty = gapText(content, "此主题还没有卡片。", 14, MUTED, false, 10);
            ListView list = new ListView(MainActivity.this);
            top(content, list, 8);
            list.setAdapter(adapter);
            list.setEmptyView(empty);
            list.setOnItemClickListener((parent, view, position, id) -> {
                Discovery.Card card = cards.get(position);
                if (selecting) {
                    ((CardRow) view.getTag()).check().setChecked(!selected.contains(card.id));
                    return;
                }
                Discovery.Topic current = state.topics.get(topicId);
                if (current != null) editCard(current, card, null);
            });
            manager = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("管理卡片 · " + topic.name)
                    .setView(content)
                    .setPositiveButton("完成", null).show();
            done = manager.getButton(AlertDialog.BUTTON_POSITIVE);
            manager.setOnDismissListener(dialog -> {
                if (cardManager == this) cardManager = null;
            });
        }

        void setSelecting(boolean enabled) {
            selecting = enabled;
            selected.clear();
            updateActions();
            adapter.notifyDataSetChanged();
        }

        void changeSelection(String id, boolean checked) {
            if (checked) selected.add(id);
            else selected.remove(id);
            updateActions();
        }

        private void setAllEnabled(boolean enabled) {
            if (!commit(latest -> {
                for (Discovery.Card card : latest.cards.values()) {
                    if (topicId.equals(card.topicId)) latest.setCardEnabled(card.id, enabled);
                }
            })) return;
            for (Discovery.Card card : cards) {
                shuffledPreviews.remove(card.id);
                if (!enabled && card.id.equals(previewId)) stopAudio();
            }
            refresh(true);
        }

        void updateActions() {
            actions.setVisibility(selecting ? View.GONE : View.VISIBLE);
            enableActions.setVisibility(selecting || cards.isEmpty() ? View.GONE : View.VISIBLE);
            int enabled = 0;
            for (Discovery.Card card : cards) {
                if (state.cardEnabled(card.id)) enabled++;
            }
            enableAll.setEnabled(enabled < cards.size());
            disableAll.setEnabled(enabled > 0);
            deleteActions.setVisibility(selecting ? View.VISIBLE : View.GONE);
            selectAll.setVisibility(selecting ? View.VISIBLE : View.GONE);
            select.setEnabled(!cards.isEmpty());
            selectAll.setEnabled(!cards.isEmpty());
            selectAll.setText(!cards.isEmpty() && selected.size() == cards.size() ? "取消全选" : "全选");
            delete.setEnabled(!selected.isEmpty());
            delete.setText(selected.isEmpty() ? "删除选中" : "删除 " + selected.size() + " 张");
            done.setEnabled(!selecting || selected.isEmpty());
            hint.setText(selecting ? "勾选要删除的卡片。" : "勾选的卡片参与发现浏览和锁屏轮换。");
            hint.setVisibility(cards.isEmpty() && !selecting ? View.GONE : View.VISIBLE);
        }

        void update() {
            cards.clear();
            for (Discovery.Card card : state.cards.values()) {
                if (topicId.equals(card.topicId)) cards.add(card);
            }
            selected.removeIf(id -> !state.cards.containsKey(id));
            adapter.notifyDataSetChanged();
            updateActions();
        }
    }

    private ArrayAdapter<Discovery.Card> cardRows(List<Discovery.Card> cards, Predicate<Discovery.Card> selected,
                                                    Supplier<String> action, BiConsumer<Discovery.Card, Boolean> change) {
        return new ArrayAdapter<>(this, 0, cards) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    LinearLayout line = row();
                    line.setGravity(Gravity.CENTER_VERTICAL);
                    line.setMinimumHeight(dp(48));
                    line.setPadding(0, 0, dp(10), 0);
                    CheckBox check = new CheckBox(MainActivity.this);
                    check.setFocusable(false);
                    line.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
                    TextView title = new TextView(MainActivity.this);
                    title.setTextColor(TEXT);
                    title.setTextSize(16);
                    title.setMaxLines(2);
                    title.setEllipsize(TextUtils.TruncateAt.END);
                    line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                    line.setTag(new CardRow(check, title, mediaTag(line, "图"), mediaTag(line, "音")));
                    convertView = line;
                }
                CardRow views = (CardRow) convertView.getTag();
                Discovery.Card card = getItem(position);
                CheckBox check = views.check();
                check.setOnCheckedChangeListener(null);
                check.setChecked(selected.test(card));
                check.setContentDescription(action.get() + " · " + card.title);
                check.setOnCheckedChangeListener((view, checked) -> change.accept(card, checked));
                views.title().setText(card.title);
                boolean picture = !card.photoUri.isEmpty();
                views.image().setTextColor(picture ? MEDIA_IMAGE : MEDIA_INACTIVE);
                views.image().setContentDescription(picture ? "有配图" : "无配图");
                boolean audio = !card.audioUri.isEmpty();
                views.audio().setTextColor(audio ? DELETE_ACCENT : MEDIA_INACTIVE);
                views.audio().setContentDescription(audio ? "有音频" : "无音频");
                return convertView;
            }
        };
    }

    private record CardRow(CheckBox check, TextView title, TextView image, TextView audio) {}

    private TextView mediaTag(LinearLayout row, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(label, new LinearLayout.LayoutParams(dp(28), -2));
        return label;
    }

    private void confirmCardDeletion(String topicId, Set<String> ids, Runnable afterDeleted) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("删除 " + ids.size() + " 张卡片？")
                .setMessage("删除后，这些卡片会从主题列表和壁纸轮换中移除。")
                .setNegativeButton("取消", null).setPositiveButton("删除", null).show();
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        confirm.setTextColor(DELETE_ACCENT);
        confirm.setOnClickListener(view -> {
            List<Discovery.Card> removed = new ArrayList<>();
            if (!commit(latest -> removed.addAll(latest.removeCards(topicId, ids)))) return;
            stopAudio();
            refresh(true);
            dialog.dismiss();
            if (afterDeleted != null) afterDeleted.run();
            for (Discovery.Card card : removed) {
                releaseUnused(card.photoUri);
                releaseUnused(card.audioUri);
            }
            notice("已删除 " + removed.size() + " 张卡片");
        });
    }

    private boolean saveTopic(Discovery.Topic topic, EditText name, EditText notes) {
        if (value(name).isEmpty()) return missing(name, "填写主题名");
        boolean saved = commit(state -> {
            Discovery.Topic item = state.topics.get(topic.id);
            item.name = value(name);
            item.context = value(notes);
        });
        if (saved) refresh(true);
        return saved;
    }

    private void editSource(String topicId, Discovery.Source existing, Bundle saved) {
        LinearLayout fields = fields();
        String labelText = saved != null ? saved.getString("label", "") : existing == null ? "" : existing.label;
        String urlText = saved != null ? saved.getString("url", "") : existing == null ? "" : existing.url;
        EditText label = field(fields, "书名、视频标题或资料名", labelText, 255, 1);
        EditText address = field(fields, "网页链接（可选）", urlText, 1500, 1);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        SourceDraft editing = new SourceDraft(topicId, existing, label, address);
        if (saved != null) {
            editing.fileUri = saved.getString("fileUri", "");
            editing.filename = saved.getString("filename", "");
        } else if (!editing.fileUri.isEmpty()) {
            editing.filename = AgentShare.filename(this, Uri.parse(editing.fileUri));
        }
        sourceDraft = editing;
        top(fields, button("选择 PDF 文件", () -> pick(AgentShare.PDF, PICK_SOURCE_PDF), false), 8);
        editing.pdf = gapText(fields, "", 14, TEXT, false, 8);
        Button remove = button("移除 PDF 附件", () -> {
            String old = editing.fileUri;
            editing.fileUri = "";
            editing.filename = "";
            editing.showFile();
            releaseUnused(old);
        }, false);
        editing.remove = remove;
        top(fields, remove, 8);
        editing.showFile();
        if (existing != null && !existing.url.isEmpty()) {
            top(fields, button("打开原网页", () -> openLink(existing), false), 8);
        }
        AlertDialog editor = dialog(existing == null ? "添加资料" : "编辑资料", fields);
        editor.setOnDismissListener(ignored -> {
            if (sourceDraft == editing) sourceDraft = null;
            if (!isChangingConfigurations()) releaseUnused(editing.fileUri);
        });
        editor.setButton(AlertDialog.BUTTON_POSITIVE, "保存", (dialog, which) -> {});
        if (existing != null) {
            editor.setButton(AlertDialog.BUTTON_NEUTRAL, "删除资料", (dialog, which) -> {});
        }
        editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> saveSource(editing, editor));
        if (existing != null) {
            Button delete = editor.getButton(AlertDialog.BUTTON_NEUTRAL);
            delete.setTextColor(DELETE_ACCENT);
            delete.setOnClickListener(view -> confirmSourceDeletion(topicId,
                    java.util.Collections.singleton(existing.id), null, updated -> editor.dismiss()));
        }
    }

    private void restoreSourceEditor() {
        Bundle saved = restoreSourceDraft;
        if (saved == null) return;
        restoreSourceDraft = null;
        try {
            Discovery.Topic topic = Store.read(this).topics.get(saved.getString("topic"));
            if (topic == null) return;
            String id = saved.getString("id", "");
            Discovery.Source source = topic.sources.stream().filter(item -> item.id.equals(id))
                    .findFirst().orElse(null);
            if (id.isEmpty() || source != null) editSource(topic.id, source, saved);
        } catch (IOException error) {
            notice("无法恢复资料编辑。");
        }
    }

    private void saveSource(SourceDraft editing, AlertDialog editor) {
        String label = value(editing.label);
        if (label.isEmpty()) {
            missing(editing.label, "填写资料名");
            return;
        }
        String url = value(editing.address);
        Uri parsed = Uri.parse(url);
        if (!url.isEmpty() && (!("https".equalsIgnoreCase(parsed.getScheme())
                || "http".equalsIgnoreCase(parsed.getScheme())) || parsed.getHost() == null)) {
            missing(editing.address, "填写完整的网页链接");
            return;
        }
        String id = editing.existing == null ? "" : editing.existing.id;
        String uri = editing.fileUri;
        runSourceWork("正在保存资料…",
                app -> PdfSources.save(app, editing.topicId, id, label, url, uri), previousUri -> {
            refresh(true);
            editor.dismiss();
            releaseUnused(previousUri);
        });
    }

    private void pickPdfBatch(String topicId) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(AgentShare.PDF)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pendingPdfTopic = topicId;
        try {
            startActivityForResult(intent, PICK_PDF_BATCH);
        } catch (ActivityNotFoundException error) {
            pendingPdfTopic = null;
            notice("系统文件选择器不可用");
        }
    }

    private void importPdfBatch(String topicId, Intent data) {
        Set<Uri> files = new LinkedHashSet<>();
        if (data.getData() != null) files.add(data.getData());
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null) files.add(uri);
        }
        if (files.isEmpty()) return;
        runSourceWork("正在检查 PDF…", app -> PdfSources.addAll(app, topicId, new ArrayList<>(files)), result -> {
            refresh(true);
            Discovery.Topic topic = state == null ? null : state.topics.get(topicId);
            if (topic != null) topicDetails(topic);
            String summary = "已导入 " + result.added() + " 份，跳过重复 " + result.duplicates() + " 份";
            if (result.failed() > 0) summary += "，无法导入 " + result.failed() + " 份"
                    + "\n请检查文件访问权限，例如「" + result.exampleFailure() + "」。";
            new AlertDialog.Builder(this).setTitle("PDF 导入结果").setMessage(summary)
                    .setPositiveButton("确定", null).show();
        });
    }

    private interface SourceWork<T> {
        T run(Context app) throws IOException;
    }

    private <T> void runSourceWork(String label, SourceWork<T> work, Consumer<T> done) {
        AlertDialog loading = new AlertDialog.Builder(this).setMessage(label).setCancelable(false).show();
        Context app = getApplicationContext();
        importWorker.execute(() -> {
            try {
                T result = work.run(app);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    loading.dismiss();
                    done.accept(result);
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    loading.dismiss();
                    new AlertDialog.Builder(this).setTitle("资料保存失败")
                            .setMessage(error instanceof IOException ? error.getMessage() : "请重试。")
                            .setPositiveButton("返回", null).show();
                });
            }
        });
    }
    private static final class SourceDraft {
        final String topicId;
        final Discovery.Source existing;
        final EditText label;
        final EditText address;
        TextView pdf;
        Button remove;
        String fileUri;
        String filename = "";

        SourceDraft(String topicId, Discovery.Source existing, EditText label, EditText address) {
            this.topicId = topicId;
            this.existing = existing;
            this.label = label;
            this.address = address;
            fileUri = existing == null ? "" : existing.fileUri;
        }

        Bundle snapshot() {
            Bundle saved = new Bundle();
            saved.putString("topic", topicId);
            saved.putString("id", existing == null ? "" : existing.id);
            saved.putString("label", value(label));
            saved.putString("url", value(address));
            saved.putString("fileUri", fileUri);
            saved.putString("filename", filename);
            return saved;
        }

        void showFile() {
            pdf.setVisibility(fileUri.isEmpty() ? View.GONE : View.VISIBLE);
            remove.setVisibility(fileUri.isEmpty() ? View.GONE : View.VISIBLE);
            pdf.setText(filename);
        }
    }

    private void editCard(Discovery.Topic topic, Discovery.Card existing, Bundle saved) {
        LinearLayout fields = fields();
        Button show = button("保存并显示在发现页", () -> {}, false);
        top(fields, show, 0);
        EditText title = labeledField(fields, "标题", cardField(saved, "title", existing == null ? "" : existing.title),
                Discovery.MAX_TITLE_LENGTH, 1);
        EditText body = labeledField(fields, "描述", cardField(saved, "body", existing == null ? "" : existing.body),
                Discovery.MAX_BODY_LENGTH, 4);
        EditText task = labeledField(fields, "小任务（可选）",
                cardField(saved, "task", existing == null ? "" : existing.task), Discovery.MAX_TASK_LENGTH, 2);
        ArrayList<String> sourceIds = saved != null ? saved.getStringArrayList("sources")
                : existing == null ? new ArrayList<>() : new ArrayList<>(existing.sourceIds);
        sourceIds.removeIf(id -> topic.sources.stream().noneMatch(source -> source.id.equals(id)));
        sourcePicker(fields, topic, sourceIds, false);
        CardDraft editing = new CardDraft(topic.id, existing == null ? "" : existing.id, title, body, task, sourceIds);
        editing.references = draftReferences(saved, existing);
        Button references = button("原文引用与概念", () -> editReferences(topic, editing), false);
        top(fields, references, 8);
        editing.photoUri = cardField(saved, "photo", existing == null ? "" : existing.photoUri);
        editing.audioUri = cardField(saved, "audio", existing == null ? "" : existing.audioUri);
        draft = editing;
        cardMediaControls(fields, editing, true);
        cardMediaControls(fields, editing, false);
        AlertDialog dialog = dialog((existing == null ? "添加卡片 · " : "编辑卡片 · ") + topic.name, fields);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存", (dialogInterface, which) -> {});
        if (existing != null) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "删除卡片", (view, which) -> {});
        dialog.setOnDismissListener(ignored -> {
            if (editing.thumbnailTask != null) editing.thumbnailTask.cancel(false);
            if (draft == editing) draft = null;
            if (!isChangingConfigurations()) {
                releaseUnused(editing.photoUri);
                releaseUnused(editing.audioUri);
            }
        });
        dialog.show();
        if (existing != null) {
            Button delete = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            delete.setTextColor(DELETE_ACCENT);
            delete.setOnClickListener(view ->
                    confirmCardDeletion(topic.id, Collections.singleton(existing.id), dialog::dismiss));
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
                saveCardDraft(topic, existing, editing, dialog, false));
        show.setOnClickListener(view -> saveCardDraft(topic, existing, editing, dialog, true));
    }

    private void saveCardDraft(Discovery.Topic topic, Discovery.Card existing, CardDraft editing,
                               AlertDialog dialog, boolean showDiscovery) {
        if (value(editing.title).isEmpty()) {
            missing(editing.title, "填写卡片标题");
            return;
        }
        if (value(editing.body).isEmpty()) {
            missing(editing.body, "填写一条发现");
            return;
        }
        String id = existing == null ? uuid() : existing.id;
        if (!commit(latest -> {
            Discovery.Topic current = latest.topics.get(topic.id);
            Discovery.Card previous = latest.cards.get(id);
            if (current == null || (existing != null && previous == null)) {
                throw new IOException("卡片或主题已不存在。");
            }
            for (String sourceId : editing.references.sourceIds()) {
                if (current.source(sourceId) == null) throw new IOException("引用资料已不存在。");
            }
            latest.cards.put(id, new Discovery.Card(id, topic.id, value(editing.title), value(editing.body),
                    value(editing.task), editing.sourceIds, editing.photoUri, editing.audioUri,
                    previous == null ? "forest" : previous.palette, editing.references));
        })) return;
        previewId = id;
        recentPreview = showDiscovery;
        if (showDiscovery) {
            stopAudio();
            showTopics = false;
            if (cardManager != null) cardManager.manager.dismiss();
        }
        refresh(!showDiscovery);
        dialog.dismiss();
        if (existing != null) {
            releaseUnused(existing.photoUri);
            releaseUnused(existing.audioUri);
        }
    }

    private static String cardField(Bundle saved, String key, String original) {
        return saved == null ? original : saved.getString(key, original);
    }

    private static Discovery.References draftReferences(Bundle saved, Discovery.Card existing) {
        if (saved != null && saved.containsKey("references")) {
            try {
                return CardReferenceJson.read(new JSONObject(saved.getString("references")));
            } catch (JSONException error) {
                Log.w(WallpaperDelivery.TAG, "Could not restore card references", error);
            }
        }
        return existing == null ? Discovery.References.EMPTY : existing.references;
    }

    private void editReferences(Discovery.Topic topic, CardDraft editing) {
        LinearLayout fields = fields();
        ArrayList<Discovery.Citation> citations = new ArrayList<>(editing.references.citations());
        ArrayList<Discovery.Concept> concepts = new ArrayList<>(editing.references.concepts());
        citationEditor(fields, topic, citations);
        gapText(fields, "涉及的概念", 14, TEXT, true, 13);
        LinearLayout items = column();
        top(fields, items, 0);
        conceptRows(items, topic, concepts);
        referenceForm("原文引用与概念", fields, () -> {
            editing.references = new Discovery.References(citations, concepts);
            return true;
        }, null);
    }

    private void citationEditor(LinearLayout fields, Discovery.Topic topic, ArrayList<Discovery.Citation> citations) {
        gapText(fields, "原文位置", 14, TEXT, true, 10);
        LinearLayout items = column();
        top(fields, items, 0);
        citationRows(items, topic, citations);
    }

    private void citationRows(LinearLayout items, Discovery.Topic topic, ArrayList<Discovery.Citation> citations) {
        items.removeAllViews();
        Runnable update = () -> citationRows(items, topic, citations);
        for (int i = 0; i < citations.size(); i++) {
            int index = i;
            Discovery.Citation citation = citations.get(index);
            Discovery.Source source = topic.source(citation.sourceId());
            String label = source == null ? "资料已移除" : source.label;
            top(items, button(label + " · " + citation.locator(),
                    () -> editCitation(topic, citations, index, update), false), 5);
        }
        if (citations.size() < CardReferenceJson.MAX_CITATIONS) {
            top(items, button("添加原文位置", () -> editCitation(topic, citations, -1, update), false), 5);
        }
    }

    private void editCitation(Discovery.Topic topic, ArrayList<Discovery.Citation> citations, int index, Runnable update) {
        if (topic.sources.isEmpty()) {
            notice("可先在「资料与备注」里添加资料。");
            return;
        }
        boolean creating = index < 0;
        Discovery.Citation existing = creating ? null : citations.get(index);
        LinearLayout fields = fields();
        gapText(fields, "资料", 14, TEXT, true, 9);
        List<Discovery.Source> sources = new ArrayList<>(topic.sources);
        ArrayList<String> labels = new ArrayList<>();
        int initial = 0;
        for (int i = 0; i < sources.size(); i++) {
            Discovery.Source source = sources.get(i);
            labels.add(sourceLabel(source));
            if (existing != null && source.id.equals(existing.sourceId())) initial = i;
        }
        Spinner choose = new Spinner(this);
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        choose.setAdapter(choices);
        choose.setSelection(initial);
        top(fields, choose, 5);
        EditText locator = labeledField(fields, "原文位置", creating ? "" : existing.locator(),
                CardReferenceJson.MAX_LOCATOR, 2);
        locator.setHint("章节、小节、页码、图号或可搜索的短句");
        referenceForm(creating ? "添加原文位置" : "编辑原文位置", fields, () -> {
            if (value(locator).isEmpty()) return missing(locator, "填写可核对的原文位置");
            Discovery.Citation citation = new Discovery.Citation(
                    sources.get(choose.getSelectedItemPosition()).id, value(locator));
            if (creating) citations.add(citation);
            else citations.set(index, citation);
            update.run();
            return true;
        }, creating ? null : () -> {
            citations.remove(index);
            update.run();
        });
    }

    private void conceptRows(LinearLayout items, Discovery.Topic topic, ArrayList<Discovery.Concept> concepts) {
        items.removeAllViews();
        Runnable update = () -> conceptRows(items, topic, concepts);
        for (int i = 0; i < concepts.size(); i++) {
            int index = i;
            top(items, button(concepts.get(index).term(), () -> editConcept(topic, concepts, index, update), false), 5);
        }
        if (concepts.size() < CardReferenceJson.MAX_CONCEPTS) {
            top(items, button("添加概念", () -> editConcept(topic, concepts, -1, update), false), 5);
        }
    }

    private void editConcept(Discovery.Topic topic, ArrayList<Discovery.Concept> concepts, int index, Runnable update) {
        boolean creating = index < 0;
        Discovery.Concept existing = creating ? null : concepts.get(index);
        LinearLayout fields = fields();
        EditText term = labeledField(fields, "概念", creating ? "" : existing.term(), CardReferenceJson.MAX_TERM, 1);
        EditText explanation = labeledField(fields, "解释", creating ? "" : existing.explanation(),
                CardReferenceJson.MAX_EXPLANATION, 4);
        ArrayList<Discovery.Citation> citations = creating ? new ArrayList<>() : new ArrayList<>(existing.citations());
        citationEditor(fields, topic, citations);
        referenceForm(creating ? "添加概念" : "编辑概念", fields, () -> {
            if (value(term).isEmpty()) return missing(term, "填写概念名");
            if (value(explanation).isEmpty()) return missing(explanation, "填写解释");
            Discovery.Concept concept = new Discovery.Concept(value(term), value(explanation), citations);
            if (creating) concepts.add(concept);
            else concepts.set(index, concept);
            update.run();
            return true;
        }, creating ? null : () -> {
            concepts.remove(index);
            update.run();
        });
    }

    private void referenceForm(String title, LinearLayout fields, Submit submit, Runnable remove) {
        AlertDialog editor = dialog(title, fields);
        editor.setButton(AlertDialog.BUTTON_POSITIVE, "保存", (dialog, which) -> {});
        if (remove != null) {
            editor.setButton(AlertDialog.BUTTON_NEUTRAL, "移除", (dialog, which) -> remove.run());
        }
        editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (submit.save()) editor.dismiss();
        });
    }

    private void cardMediaControls(LinearLayout fields, CardDraft editing, boolean photo) {
        String kind = photo ? "图片" : "音频";
        if (photo) {
            ImageView image = new ImageView(this);
            image.setVisibility(View.GONE);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackground(rounded(PREVIEW, 10));
            image.setContentDescription("配图缩略图");
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(140));
            size.topMargin = dp(10);
            fields.addView(image, size);
            editing.thumbnail = image;
        }
        Button pick = button("", () -> pick(photo ? "image/*" : "audio/*", photo ? PICK_PHOTO : PICK_AUDIO), false);
        top(fields, pick, 8);
        LinearLayout actions = row();
        actions.setBaselineAligned(false);
        top(fields, actions, 2);
        equal(actions, cardAction("保存" + kind, ACCENT,
                () -> exportCardMedia(value(editing.title), editing.media(photo), photo)), 0);
        equal(actions, cardAction("移除" + kind, DELETE_ACCENT, () -> setCardMedia(editing, photo, "")), 8);
        if (photo) {
            editing.photo = pick;
            editing.photoActions = actions;
        } else {
            editing.audio = pick;
            editing.audioActions = actions;
        }
        editing.showMedia(photo);
        if (photo) updateThumbnail(editing);
    }

    private void setCardMedia(CardDraft editing, boolean photo, String uri) {
        String previous = editing.media(photo);
        if (photo) editing.photoUri = uri;
        else editing.audioUri = uri;
        editing.showMedia(photo);
        if (photo) updateThumbnail(editing);
        if (!previous.equals(uri)) releaseUnused(previous);
    }

    private void updateThumbnail(CardDraft editing) {
        if (editing.thumbnailTask != null) editing.thumbnailTask.cancel(false);
        ImageView image = editing.thumbnail;
        image.setImageDrawable(null);
        image.setVisibility(View.GONE);
        String uri = editing.photoUri;
        if (uri.isEmpty()) return;
        Context app = getApplicationContext();
        int width = dp(340);
        int height = dp(140);
        editing.thumbnailTask = previewWorker.submit(() -> {
            Bitmap bitmap = WallpaperArt.readPhoto(app, uri, width, height);
            if (bitmap == null) return;
            runOnUiThread(() -> {
                if (isDestroyed() || draft != editing || !uri.equals(editing.photoUri)) {
                    bitmap.recycle();
                    return;
                }
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
            });
        });
    }

    private void restoreCardUi() {
        if (restoreManagerTopic == null && restoreCardDraft == null) return;
        String managerTopic = restoreManagerTopic;
        ArrayList<String> selection = restoreManagerSelection;
        Bundle saved = restoreCardDraft;
        restoreManagerTopic = null;
        restoreManagerSelection = null;
        restoreCardDraft = null;
        if (state == null) refresh(true);
        if (state == null) return;
        Discovery.Topic manager = state.topics.get(managerTopic);
        if (manager != null) manageCards(manager, selection);
        if (saved == null) return;
        Discovery.Topic topic = state.topics.get(saved.getString("topic"));
        String id = saved.getString("id", "");
        Discovery.Card existing = state.cards.get(id);
        if (topic != null && (id.isEmpty() || existing != null)) editCard(topic, existing, saved);
        else {
            releaseUnused(saved.getString("photo", ""));
            releaseUnused(saved.getString("audio", ""));
        }
    }

    private SourceSelection sourcePicker(LinearLayout fields, Discovery.Topic topic, ArrayList<String> ids,
                                         boolean checkAttachments) {
        SourceSelection selection = new SourceSelection(topic, ids);
        Button button = button(sourceSelectionLabel(ids), () -> {}, false);
        button.setOnClickListener(view -> {
            Discovery.Topic current = state == null ? null : state.topics.get(topic.id);
            if (current == null || current.sources.isEmpty()) {
                notice("可先在「资料与备注」里添加资料。");
                return;
            }
            List<Discovery.Source> available = new ArrayList<>(current.sources);
            showSourceChoices("选择资料", available, ids, false, (picker, selected) -> {
                ids.clear();
                for (Discovery.Source source : available) {
                    if (selected.contains(source.id)) ids.add(source.id);
                }
                button.setText(sourceSelectionLabel(ids));
                picker.dismiss();
                if (checkAttachments) checkAgentPdfs(topic.id);
            });
        });
        top(fields, button, 8);
        return selection;
    }

    private String sourceSelectionLabel(List<String> ids) {
        return ids.isEmpty() ? "选择资料（可选）" : "选择资料 · 已选 " + ids.size() + " 份";
    }

    private record SourceSelection(Discovery.Topic topic, ArrayList<String> ids) {
        List<Discovery.Source> sources() {
            List<Discovery.Source> selected = new ArrayList<>();
            for (Discovery.Source item : topic.sources) {
                if (ids.contains(item.id)) selected.add(item);
            }
            return selected;
        }

        ImportTarget target() {
            return new ImportTarget(topic.id, new ArrayList<>(ids));
        }
    }

    private void importCards(Discovery.Topic topic) {
        LinearLayout fields = fields();
        if (!topic.id.equals(importTopicId)) resetAgentOptions();
        importTopicId = topic.id;
        if (topic.context.isEmpty()) includeTopicNotes = false;
        importSourceIds.removeIf(id -> topic.sources.stream().noneMatch(source -> source.id.equals(id)));
        Button notes = button(agentNotesLabel(), () -> {}, false);
        notes.setOnClickListener(view -> chooseAgentNotes(topic, notes));
        top(fields, notes, 0);
        SourceSelection sources = sourcePicker(fields, topic, importSourceIds, true);
        agentCardIds.removeIf(id -> {
            Discovery.Card card = state.cards.get(id);
            return card == null || !card.topicId.equals(topic.id);
        });
        Button existing = button(agentCardLabel(), () -> {}, false);
        existing.setOnClickListener(view -> chooseAgentCards(topic.id, existing));
        top(fields, existing, 8);
        importArrow(fields);
        Button share = button("发给 Agent 生成卡片", () -> {}, false);
        agentShareButton = share;
        share.setOnClickListener(view -> shareWithAgent(share, topic.id));
        share.setOnLongClickListener(view -> {
            AgentRequest request = agentRequest(topic.id);
            if (request != null) copyAgentPrompt(request.prompt());
            return true;
        });
        top(fields, share, 0);
        importArrow(fields);
        LinearLayout methods = row();
        methods.setBaselineAligned(false);
        top(fields, methods, 0);
        equal(methods, button("导入 ZIP / JSON 文件", () -> pickCardFile(sources.target()), true), 0);
        equal(methods, button("粘贴卡片 JSON", () -> pasteCards(sources.target()), true), 8);
        methods.getChildAt(0).getLayoutParams().height = LinearLayout.LayoutParams.MATCH_PARENT;
        methods.getChildAt(1).getLayoutParams().height = LinearLayout.LayoutParams.MATCH_PARENT;
        gapText(fields, "已有卡片时，可以直接选择导入方式。", 13, MUTED, false, 10);
        AlertDialog entry = dialog("批量导入 · " + topic.name, fields);
        importEntry = entry;
        entry.setOnDismissListener(ignored -> {
            if (importEntry == entry) {
                importEntry = null;
                agentShareButton = null;
            }
        });
        entry.show();
    }

    private void resetAgentOptions() {
        importSourceIds.clear();
        agentCardIds.clear();
        includeTopicNotes = false;
        customAgentNotes = "";
    }

    private String agentNotesLabel() {
        return includeTopicNotes || !customAgentNotes.isEmpty() ? "备注说明 · 已设置" : "备注说明（可选）";
    }

    private void chooseAgentNotes(Discovery.Topic topic, Button control) {
        LinearLayout fields = fields();
        CheckBox include = new CheckBox(this);
        include.setText("附上「资料与备注」的内容");
        include.setTextColor(TEXT);
        include.setEnabled(!topic.context.isEmpty());
        include.setChecked(include.isEnabled() && includeTopicNotes);
        top(fields, include, 0);
        gapText(fields, topic.context.isEmpty() ? "此主题还没有备注。" : topic.context, 14, MUTED, false, 4);
        EditText notes = field(fields, "本次给 Agent 的说明（可选）", customAgentNotes, 3000, 3);
        AlertDialog dialog = dialog("备注说明", fields);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "确定", (view, which) -> {
            includeTopicNotes = include.isChecked();
            customAgentNotes = value(notes);
            control.setText(agentNotesLabel());
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    private String agentCardLabel() {
        return agentCardIds.isEmpty() ? "选择已有卡片（可选）" : "选择已有卡片 · 已选 " + agentCardIds.size() + " 张";
    }

    private void chooseAgentCards(String topicId, Button control) {
        List<Discovery.Card> available = new ArrayList<>();
        for (Discovery.Card card : state.cards.values()) {
            if (card.topicId.equals(topicId)) available.add(card);
        }
        if (available.isEmpty()) {
            notice("此主题还没有卡片");
            return;
        }
        Set<String> previouslySelected = new HashSet<>(agentCardIds);
        Set<String> ids = new LinkedHashSet<>();
        for (Discovery.Card card : available) {
            if (previouslySelected.contains(card.id)) ids.add(card.id);
        }
        LinearLayout content = fields();
        TextView count = gapText(content, "", 13, MUTED, false, 0);
        Button all = button("", () -> {}, false);
        top(content, all, 6);
        ListView list = new ListView(this);
        top(content, list, 8);
        Runnable update = () -> {
            count.setText("已选 " + ids.size() + " / " + available.size() + " 张");
            all.setText(ids.size() == available.size() ? "取消全选" : "全选");
        };
        ArrayAdapter<Discovery.Card> adapter = cardRows(available, card -> ids.contains(card.id),
                () -> "选给 Agent", (card, checked) -> {
                    if (checked) ids.add(card.id);
                    else ids.remove(card.id);
                    update.run();
                });
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> ((CardRow) view.getTag()).check().toggle());
        all.setOnClickListener(view -> {
            if (ids.size() == available.size()) ids.clear();
            else for (Discovery.Card card : available) ids.add(card.id);
            adapter.notifyDataSetChanged();
            update.run();
        });
        update.run();
        new AlertDialog.Builder(this).setTitle("选择给 Agent 参考或更新的卡片")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("完成", (dialog, which) -> {
                    agentCardIds.clear();
                    agentCardIds.addAll(ids);
                    control.setText(agentCardLabel());
                }).show();
    }

    private record AgentRequest(String prompt, List<Discovery.Source> sources) {}

    private AgentRequest agentRequest(String topicId) {
        Discovery.Topic topic = state == null ? null : state.topics.get(topicId);
        if (topic == null) {
            notice("主题已不存在");
            return null;
        }
        List<Discovery.Source> sources = new SourceSelection(topic, importSourceIds).sources();
        List<Discovery.Card> existing = new ArrayList<>();
        for (String id : agentCardIds) {
            Discovery.Card card = state.cards.get(id);
            if (card != null && card.topicId.equals(topicId)) existing.add(card);
        }
        Size wallpaper = WallpaperArt.dimensions(this, 1080);
        String prompt = AgentShare.prompt(topic, sources, includeTopicNotes, customAgentNotes, existing,
                wallpaper.getWidth(), wallpaper.getHeight());
        if (prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > AgentShare.MAX_PROMPT_BYTES) {
            notice("提示内容太长，请减少已有卡片或资料后重试。");
            return null;
        }
        return new AgentRequest(prompt, sources);
    }

    private void pasteCards(ImportTarget target) {
        LinearLayout fields = fields();
        EditText input = field(fields, "粘贴卡片 JSON", "", CardImport.MAX_INPUT_LENGTH + 1, 5);
        input.setMaxLines(8);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        AlertDialog entry = dialog("粘贴卡片 JSON", fields);
        entry.setButton(AlertDialog.BUTTON_POSITIVE, "审阅卡片", (dialog, which) -> {});
        entry.show();
        entry.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                List<CardImport.Draft> drafts = CardImport.parse(value(input), false);
                if (state == null || !state.topics.containsKey(target.topicId())) {
                    throw new IOException("主题已不存在。");
                }
                List<CardImport.Draft> unique = CardImport.unique(state, target.topicId(), drafts);
                if (unique.isEmpty()) {
                    missing(input, "这些卡片已在本主题中。");
                    return;
                }
                reviewImport(entry, target, unique, drafts.size() - unique.size(), null);
            } catch (IOException error) {
                missing(input, error.getMessage());
            }
        });
    }

    private void importArrow(LinearLayout fields) {
        TextView arrow = gapText(fields, "↓", 18, ACCENT, false, 0);
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private record ImportTarget(String topicId, List<String> sourceIds) {}

    private void pickCardFile(ImportTarget target) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/zip", "application/x-zip-compressed", "application/json",
                        "text/plain", "application/octet-stream"});
        try {
            pendingCardFile = target;
            startActivityForResult(intent, PICK_CARD_FILE);
        } catch (ActivityNotFoundException error) {
            pendingCardFile = null;
            notice("系统文件选择器不可用");
        }
    }

    private void loadCardFile(AlertDialog entry, ImportTarget target, Uri uri) {
        AlertDialog loading = new AlertDialog.Builder(this)
                .setMessage("正在读取卡片文件…")
                .setNegativeButton("取消", (dialog, which) -> dialog.cancel())
                .show();
        Context app = getApplicationContext();
        Future<?> task = importWorker.submit(() -> {
            try {
                CardZipImport.Bundle zip = null;
                List<CardImport.Draft> drafts;
                try (InputStream stream = app.getContentResolver().openInputStream(uri)) {
                    if (stream == null) throw new IOException("无法读取所选文件。");
                    BufferedInputStream input = new BufferedInputStream(stream);
                    input.mark(4);
                    boolean zipHeader = input.read() == 'P' && input.read() == 'K';
                    input.reset();
                    if (zipHeader) {
                        zip = CardZipImport.read(input, app.getCacheDir());
                        drafts = zip.drafts;
                    } else {
                        drafts = CardImport.readJson(input, false);
                    }
                } catch (IOException | RuntimeException error) {
                    if (zip != null) zip.close();
                    throw error;
                }
                CardZipImport.Bundle bundle = zip;
                runOnUiThread(() -> {
                    if (isDestroyed() || !loading.isShowing()) {
                        if (bundle != null) bundle.close();
                        return;
                    }
                    loading.dismiss();
                    if (state == null || !state.topics.containsKey(target.topicId())) {
                        if (bundle != null) bundle.close();
                        notice("无法读取当前主题。");
                        return;
                    }
                    List<CardImport.Draft> unique = CardImport.unique(state, target.topicId(), drafts);
                    if (unique.isEmpty()) {
                        if (bundle != null) bundle.close();
                        notice("这些卡片已在本主题中");
                        return;
                    }
                    reviewedZip = bundle;
                    reviewImport(entry, target, unique, drafts.size() - unique.size(), bundle);
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || !loading.isShowing()) return;
                    loading.dismiss();
                    importError(error);
                });
            }
        });
        zipRead = task;
        loading.setOnCancelListener(ignored -> task.cancel(true));
    }

    private void copyAgentPrompt(String prompt) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("卡片生成提示", prompt));
        if (Build.VERSION.SDK_INT < 33) notice("已复制提示");
    }

    private void shareWithAgent(Button button, String topicId) {
        AgentRequest request = agentRequest(topicId);
        if (request == null) return;
        String prompt = request.prompt();
        button.setEnabled(false);
        Context app = getApplicationContext();
        importWorker.execute(() -> {
            try {
                Intent intent = AgentShare.intent(app, prompt, request.sources());
                List<AgentShare.Target> targets = AgentShare.targets(app, intent);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    button.setEnabled(true);
                    if (!button.isAttachedToWindow()) return;
                    chooseAgent(intent, targets, prompt);
                });
            } catch (AgentShare.MissingPdfs error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    button.setEnabled(true);
                    if (button.isAttachedToWindow()) showPdfRelinks(topicId, error.sources);
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    button.setEnabled(true);
                    if (button.isAttachedToWindow()) agentShareError(error.getMessage(), prompt);
                });
            }
        });
    }

    private record PdfRelink(String topicId, String sourceId) {}

    private void checkAgentPdfs(String topicId) {
        Discovery.Topic topic = state == null ? null : state.topics.get(topicId);
        Button share = agentShareButton;
        if (topic == null || share == null) return;
        List<Discovery.Source> sources = new SourceSelection(topic, importSourceIds).sources();
        if (sources.stream().noneMatch(source -> !source.fileUri.isEmpty())) return;
        AlertDialog progress = new AlertDialog.Builder(this).setMessage("正在检查所选 PDF…")
                .setCancelable(false).show();
        Context app = getApplicationContext();
        importWorker.execute(() -> {
            List<Discovery.Source> missing;
            try {
                AgentShare.checkedPdfs(app, sources);
                missing = Collections.emptyList();
            } catch (AgentShare.MissingPdfs error) {
                missing = error.sources;
            }
            List<Discovery.Source> unreadable = missing;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                progress.dismiss();
                if (share == agentShareButton && !unreadable.isEmpty()) showPdfRelinks(topicId, unreadable);
            });
        });
    }

    private void showPdfRelinks(String topicId, List<Discovery.Source> missing) {
        String[] labels = missing.stream().map(source -> "重选 PDF · " + source.label).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("部分 PDF 无法读取，选择要重新关联的资料")
                .setItems(labels, (dialog, index) -> {
                    pendingRelink = new PdfRelink(topicId, missing.get(index).id);
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(AgentShare.PDF)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        startActivityForResult(picker, PICK_RELINK_PDF);
                    } catch (ActivityNotFoundException error) {
                        pendingRelink = null;
                        notice("系统文件选择器不可用");
                    }
                }).setNegativeButton("返回", null).show();
    }

    private void resumePdfRelink(int result, Intent data) {
        PdfRelink relink = pendingRelink;
        pendingRelink = null;
        if (relink == null) return;
        if (state == null) refresh(true);
        Discovery.Topic topic = state == null ? null : state.topics.get(relink.topicId());
        if (topic == null) return;
        if (importEntry == null) {
            restoreCardUi();
            importCards(topic);
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri selected = data.getData();
        runSourceWork("正在核对并重新关联 PDF…",
                app -> PdfSources.relink(app, relink.topicId(), relink.sourceId(), selected), old -> {
                    refresh(true);
                    releaseUnused(old);
                    notice("已重新关联 PDF");
                    checkAgentPdfs(relink.topicId());
                });
    }

    private void chooseAgent(Intent share, List<AgentShare.Target> agents, String prompt) {
        if (agents.isEmpty()) {
            launchAgent(Intent.createChooser(share, "发给 Agent 生成卡片"), prompt);
            return;
        }
        String[] choices = new String[agents.size() + 1];
        for (int i = 0; i < agents.size(); i++) choices[i] = agents.get(i).label();
        choices[agents.size()] = "其他应用 · 系统分享";
        new AlertDialog.Builder(this).setTitle("发给 Agent 生成卡片")
                .setItems(choices, (dialog, which) -> {
                    Intent target = which == agents.size()
                            ? Intent.createChooser(share, "发给 Agent 生成卡片")
                            : new Intent(share).setPackage(agents.get(which).packageName());
                    launchAgent(target, prompt);
                })
                .setNeutralButton("复制提示", (dialog, which) -> copyAgentPrompt(prompt))
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchAgent(Intent intent, String prompt) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            agentShareError("无法打开目标应用。", prompt);
        }
    }

    private void agentShareError(String message, String prompt) {
        new AlertDialog.Builder(this).setTitle("无法分享")
                .setMessage(message)
                .setNegativeButton("返回", null)
                .setPositiveButton("复制提示", (dialog, which) -> copyAgentPrompt(prompt))
                .show();
    }

    private void reviewImport(AlertDialog entry, ImportTarget target, List<CardImport.Draft> drafts,
                              int duplicates, CardZipImport.Bundle bundle) {
        ImportReview cards = new ImportReview(target, drafts, bundle);
        String heading = "审阅 " + drafts.size() + " 张卡片";
        if (duplicates > 0) heading += " · 跳过 " + duplicates + " 张重复";
        AlertDialog review = new AlertDialog.Builder(this).setTitle(heading)
                .setView(cards.layout)
                .setNegativeButton("返回", null)
                .setPositiveButton("导入选中卡片", null).show();
        cards.confirm = review.getButton(AlertDialog.BUTTON_POSITIVE);
        cards.updateRows();
        if (bundle != null) {
            review.setOnDismissListener(ignored -> {
                stopAudio();
                if (reviewedZip == bundle) {
                    reviewedZip = null;
                    bundle.close();
                }
            });
        }
        cards.confirm.setOnClickListener(view -> {
            List<CardImport.Draft> selected = cards.selection();
            if (selected.isEmpty()) return;
            Runnable save = () -> saveReviewedCards(entry, review, target, selected, bundle);
            long updates = selected.stream().filter(CardImport.Draft::isUpdate).count();
            if (updates > 0) {
                new AlertDialog.Builder(this).setTitle("覆盖更新 " + updates + " 张已有卡片？")
                        .setMessage("将按卡片 ID 更新你勾选的已有卡片。卡片的位置、启用状态和锁屏历史会保留。")
                        .setNegativeButton("返回", null)
                        .setPositiveButton("确认更新", (dialog, which) -> save.run()).show();
            } else {
                save.run();
            }
        });
    }

    private void saveReviewedCards(AlertDialog entry, AlertDialog review, ImportTarget target,
                                   List<CardImport.Draft> selected, CardZipImport.Bundle bundle) {
        if (bundle != null) {
            saveImportZip(entry, review, target, selected, bundle);
            return;
        }
        try {
            CardImport.Result[] result = new CardImport.Result[1];
            Store.edit(this, latest -> result[0] = CardImport.add(latest, target.topicId(), target.sourceIds(), selected));
            finishImport(entry, review, result[0]);
        } catch (IOException error) {
            importError(error);
        }
    }

    private final class ImportReview {
        final ImportTarget target;
        final List<CardImport.Draft> drafts;
        final Map<String, String> mapping = new LinkedHashMap<>();
        final List<CheckBox> checks = new ArrayList<>();
        final LinearLayout layout = fields();
        final Button selectAll;
        final Button match;
        Button confirm;
        boolean updating;

        ImportReview(ImportTarget target, List<CardImport.Draft> drafts, CardZipImport.Bundle bundle) {
            this.target = target;
            this.drafts = drafts;
            selectAll = button("全选", this::toggleAll, false);
            top(layout, selectAll, 0);
            match = button("匹配资料", this::matchSources, false);
            top(layout, match, 6);
            ScrollView container = new ScrollView(MainActivity.this);
            LinearLayout rows = column();
            container.addView(rows);
            layout.addView(container, new LinearLayout.LayoutParams(-1, -2, 1));
            for (CardImport.Draft card : drafts) {
                CheckBox check = new CheckBox(MainActivity.this);
                check.setTextColor(TEXT);
                check.setMinimumHeight(dp(48));
                check.setEnabled(false);
                check.setOnCheckedChangeListener((view, on) -> {
                    if (!updating) updateActions();
                });
                checks.add(check);
                top(rows, check, 8);
                if (bundle != null) addReviewMedia(rows, bundle, card);
            }
        }

        Discovery.Topic topic() {
            return state == null ? null : state.topics.get(target.topicId());
        }

        CardImport.Draft resolved(CardImport.Draft draft) {
            return draft.remapSources(target.sourceIds(), mapping);
        }

        Discovery.Card existing(CardImport.Draft draft) {
            return state == null || !draft.isUpdate() ? null : draft.existing(state, target.topicId());
        }

        List<CardImport.Draft> selection() {
            List<CardImport.Draft> selected = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                if (checks.get(i).isChecked()) selected.add(resolved(drafts.get(i)));
            }
            return selected;
        }

        Set<String> originalMissing(Discovery.Topic topic) {
            Set<String> ids = new LinkedHashSet<>();
            for (CardImport.Draft card : drafts) {
                if (!card.isUpdate() || existing(card) != null) {
                    ids.addAll(card.withExistingSources(existing(card)).missingSources(topic, target.sourceIds()));
                }
            }
            return ids;
        }

        void updateRows() {
            Discovery.Topic topic = topic();
            updating = true;
            for (int i = 0; i < drafts.size(); i++) {
                CardImport.Draft card = resolved(drafts.get(i));
                Discovery.Card original = existing(card);
                CardImport.Draft effective = card.withExistingSources(original);
                CheckBox check = checks.get(i);
                List<String> missing = effective.missingSources(topic, target.sourceIds());
                boolean available = topic != null && missing.isEmpty() && (!card.isUpdate() || original != null);
                boolean selected = available && (check.isEnabled() ? check.isChecked() : !card.isUpdate());
                check.setText(importReviewText(effective, topic, target.sourceIds(), missing, original));
                check.setEnabled(available);
                check.setChecked(selected);
            }
            updating = false;
            Set<String> original = originalMissing(topic);
            int remaining = 0;
            for (String id : original) {
                if (!mapping.containsKey(id)) remaining++;
            }
            match.setVisibility(original.isEmpty() ? View.GONE : View.VISIBLE);
            match.setText(remaining == 0 ? "调整资料匹配" : "匹配资料 · " + remaining + " 个待处理");
            match.setEnabled(topic != null);
            updateActions();
        }

        void updateActions() {
            boolean any = false;
            boolean all = true;
            boolean available = false;
            for (CheckBox check : checks) {
                if (!check.isEnabled()) continue;
                available = true;
                any |= check.isChecked();
                all &= check.isChecked();
            }
            selectAll.setText(available && all ? "取消全选" : "全选");
            selectAll.setEnabled(available);
            confirm.setEnabled(any);
        }

        void toggleAll() {
            boolean select = checks.stream().anyMatch(check -> check.isEnabled() && !check.isChecked());
            updating = true;
            for (CheckBox check : checks) check.setChecked(select && check.isEnabled());
            updating = false;
            updateActions();
        }

        void matchSources() {
            Discovery.Topic topic = topic();
            if (topic == null) return;
            LinearLayout fields = fields();
            gapText(fields, "每个外部 ID 只需处理一次。选同一份资料可保留原文位置。选择「导入时移除此关联」会移除该 ID 的引用和原文位置，卡片和概念解释仍会导入。",
                    14, MUTED, false, 0);
            List<Discovery.Source> sources = new ArrayList<>(topic.sources);
            ArrayList<String> names = new ArrayList<>();
            names.add("暂不处理");
            names.add("导入时移除此关联");
            for (Discovery.Source source : sources) names.add(sourceLabel(source));
            Map<String, Spinner> inputs = new LinkedHashMap<>();
            for (String id : originalMissing(topic)) {
                gapText(fields, id, 14, TEXT, true, 12);
                Spinner spinner = new Spinner(MainActivity.this);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this,
                        android.R.layout.simple_spinner_item, names);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);
                String previous = mapping.get(id);
                if ("".equals(previous)) spinner.setSelection(1);
                for (int i = 0; i < sources.size(); i++) {
                    if (sources.get(i).id.equals(previous)) spinner.setSelection(i + 2);
                }
                top(fields, spinner, 4);
                inputs.put(id, spinner);
            }
            AlertDialog dialog = dialog("匹配资料", fields);
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, "应用匹配", (view, which) -> {
                mapping.clear();
                for (Map.Entry<String, Spinner> item : inputs.entrySet()) {
                    int index = item.getValue().getSelectedItemPosition();
                    if (index == 1) mapping.put(item.getKey(), "");
                    else if (index >= 2) mapping.put(item.getKey(), sources.get(index - 2).id);
                }
                updateRows();
            });
            dialog.show();
        }
    }

    private String importReviewText(CardImport.Draft card, Discovery.Topic topic, List<String> batchSources,
                                    List<String> missing, Discovery.Card original) {
        StringBuilder text = new StringBuilder();
        if (card.isUpdate()) {
            text.append(original == null ? "更新目标不在此主题" : "更新原卡 · " + original.title).append('\n');
        }
        text.append(card.reviewText());
        ArrayList<String> names = new ArrayList<>();
        for (String id : card.relatedSources(batchSources)) {
            Discovery.Source source = topic == null ? null : topic.source(id);
            if (source != null) names.add(source.label);
        }
        if (!names.isEmpty()) {
            text.append(card.inheritsSources() ? "\n旧格式，沿用本批资料 · " : "\n本卡资料 · ");
            text.append(String.join("、", names));
        } else if (!card.inheritsSources() && missing.isEmpty()) {
            text.append("\n本卡资料 · 无");
        }
        if (!card.references().concepts().isEmpty()) {
            text.append("\n涉及概念 · ").append(card.references().concepts().size()).append(" 个");
        }
        if (!missing.isEmpty()) {
            text.append("\n资料 ID 无法匹配 · ").append(String.join("、", missing));
        }
        return text.toString();
    }

    private void addReviewMedia(LinearLayout rows, CardZipImport.Bundle bundle, CardImport.Draft card) {
        if (card.image().isEmpty() && card.audio().isEmpty()) return;
        LinearLayout actions = row();
        top(rows, actions, 4);
        if (!card.image().isEmpty()) {
            equal(actions, button("查看图片", () -> showImportImage(bundle, card), false), 0);
        }
        if (!card.audio().isEmpty()) {
            equal(actions, button("试听音频", () -> play(bundle.previewUri(card.audio())), false),
                    card.image().isEmpty() ? 0 : 8);
        }
    }

    private void showImportImage(CardZipImport.Bundle bundle, CardImport.Draft card) {
        Context app = getApplicationContext();
        previewWorker.execute(() -> {
            Bitmap bitmap = WallpaperArt.readPhoto(app, bundle.previewUri(card.image()), 640, 640);
            runOnUiThread(() -> {
                if (isDestroyed() || reviewedZip != bundle) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    notice("无法显示图片。");
                    return;
                }
                ImageView image = new ImageView(this);
                image.setAdjustViewBounds(true);
                image.setMaxHeight(dp(440));
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(bitmap);
                new AlertDialog.Builder(this).setTitle(card.title()).setView(image)
                        .setPositiveButton("返回", null).show();
            });
        });
    }

    private void saveImportZip(AlertDialog entry, AlertDialog review, ImportTarget target,
                               List<CardImport.Draft> selected, CardZipImport.Bundle bundle) {
        stopAudio();
        AlertDialog saving = new AlertDialog.Builder(this)
                .setMessage("正在保存卡片和媒体…")
                .setCancelable(false)
                .show();
        reviewedZip = null;
        Context app = getApplicationContext();
        importWorker.execute(() -> {
            try {
                CardImport.Result result = bundle.save(app, target.topicId(), target.sourceIds(), selected);
                bundle.close();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    saving.dismiss();
                    finishImport(entry, review, result);
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        bundle.close();
                        return;
                    }
                    reviewedZip = bundle;
                    saving.dismiss();
                    importError(error);
                });
            }
        });
    }

    private void finishImport(AlertDialog entry, AlertDialog review, CardImport.Result result) {
        review.dismiss();
        if (entry != null) entry.dismiss();
        if (importEntry != null && importEntry != entry) importEntry.dismiss();
        int added = result.cards().size() - result.updated();
        if (!result.cards().isEmpty()) {
            previewId = result.cards().get(0).id;
            recentPreview = false;
        }
        if (result.updated() > 0) notice("已更新 " + result.updated() + " 张，新增 " + added + " 张");
        else notice(added == 0 ? "这些卡片已在本主题中" : "已导入 " + added + " 张卡片");
        refresh(true);
        for (String uri : result.replacedMedia()) releaseUnused(uri);
    }

    private void importError(Throwable error) {
        Log.w(WallpaperDelivery.TAG, "Could not import card bundle", error);
        String detail = error instanceof IOException ? error.getMessage() : null;
        new AlertDialog.Builder(this).setTitle("导入失败")
                .setMessage(detail == null || detail.isEmpty() ? "无法读取或保存卡片文件。" : detail)
                .setPositiveButton("返回", null).show();
    }

    private void pick(String mimeType, int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mimeType)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException error) {
            notice("系统文件选择器不可用");
        }
    }

    private void confirmDataRestore(Uri uri) {
        new AlertDialog.Builder(this).setTitle("恢复 Glim 备份")
                .setMessage("恢复会替换这台手机上当前的主题、卡片、资料、轮换设置和最近锁屏记录。"
                        + "如果这台手机上也有要保留的数据，请先导出备份。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (dialog, which) -> restoreData(uri)).show();
    }

    private void restoreData(Uri uri) {
        AlertDialog restoring = new AlertDialog.Builder(this).setMessage("正在恢复备份…")
                .setCancelable(false).show();
        Context app = getApplicationContext();
        importWorker.execute(() -> {
            DataRestore.Result result;
            try {
                result = DataRestore.read(app, uri);
                RotationAlarm.sync(app, Store.read(app));
            } catch (IOException | RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not restore app data", error);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    restoring.dismiss();
                    importError(error);
                });
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                restoring.dismiss();
                stopAudio();
                previewId = "";
                recentPreview = false;
                shuffledPreviews.clear();
                refresh(false);
                String message = "已恢复 " + result.topics() + " 个主题、" + result.cards() + " 张卡片。";
                if (result.missing() > 0) message += "备份导出时有 " + result.missing() + " 个附件未能读取。";
                new AlertDialog.Builder(this).setTitle("备份已恢复").setMessage(message)
                        .setPositiveButton("确定", null).show();
            });
        });
    }

    private void chooseDataExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "Glim-data-" + new java.text.SimpleDateFormat(
                        "yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".zip");
        try {
            startActivityForResult(intent, SAVE_DATA_EXPORT);
        } catch (ActivityNotFoundException error) {
            notice("系统文件选择器不可用");
        }
    }

    private void saveDataExport(Uri destination) {
        AlertDialog saving = new AlertDialog.Builder(this).setMessage("正在导出主题、卡片和附件…")
                .setCancelable(false).show();
        Context app = getApplicationContext();
        importWorker.execute(() -> {
            String message;
            try {
                DataExport.Result result = DataExport.write(app, destination);
                message = "已导出 " + result.topics() + " 个主题、" + result.cards()
                        + " 张卡片、" + result.files() + " 个文件。";
                if (result.missing() > 0) {
                    message += "\n有 " + result.missing() + " 个原文件无法读取，详情见 ZIP 中的 manifest.json。";
                }
            } catch (IOException | RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not export app data", error);
                message = "导出失败，请检查可用空间和保存位置后重试。";
            }
            String summary = message;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                saving.dismiss();
                new AlertDialog.Builder(this).setTitle("数据导出").setMessage(summary)
                        .setPositiveButton("确定", null).show();
            });
        });
    }

    private void exportCardMedia(String title, String media, boolean photo) {
        if (media.isEmpty()) return;
        try {
            Intent intent = CardMediaExport.destination(this, title, media, photo);
            pendingMediaExport = media;
            startActivityForResult(intent, SAVE_CARD_MEDIA);
        } catch (ActivityNotFoundException error) {
            pendingMediaExport = "";
            RecentWallpapers.discardExport(this, media);
            notice("系统文件选择器不可用");
        } catch (RuntimeException error) {
            pendingMediaExport = "";
            RecentWallpapers.discardExport(this, media);
            notice("无法读取卡片媒体。");
        }
    }

    private void exportRecentLock(Discovery.LockEntry entry) {
        AlertDialog preparing = new AlertDialog.Builder(this).setMessage("正在准备锁屏图片…")
                .setCancelable(false).show();
        Context app = getApplicationContext();
        importWorker.execute(() -> {
            try {
                String image = RecentWallpapers.prepareExport(app, entry).toString();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) {
                        RecentWallpapers.discardExport(app, image);
                        return;
                    }
                    preparing.dismiss();
                    exportCardMedia(entry.title() + "-锁屏", image, true);
                });
            } catch (IOException | RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not prepare lock wallpaper export", error);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    preparing.dismiss();
                    notice("无法保存这条记录的锁屏图片");
                });
            }
        });
    }

    private void saveCardMedia(String media, Uri destination) {
        AlertDialog saving = new AlertDialog.Builder(this).setMessage("正在保存媒体…")
                .setCancelable(false).show();
        Context app = getApplicationContext();
        importWorker.execute(() -> {
            String result = "已保存到所选位置";
            try {
                CardMediaExport.copy(app, media, destination);
            } catch (IOException | RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not export card media", error);
                result = "无法保存媒体，请检查文件和保存位置。";
            } finally {
                RecentWallpapers.discardExport(app, media);
            }
            String message = result;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                saving.dismiss();
                notice(message);
            });
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_DATA_BACKUP) {
            if (result == RESULT_OK && data != null && data.getData() != null) confirmDataRestore(data.getData());
            return;
        }
        if (request == SAVE_DATA_EXPORT) {
            if (result == RESULT_OK && data != null && data.getData() != null) saveDataExport(data.getData());
            return;
        }
        if (request == PICK_RELINK_PDF) {
            resumePdfRelink(result, data);
            return;
        }
        if (request == SAVE_CARD_MEDIA) {
            String media = pendingMediaExport;
            pendingMediaExport = "";
            if (!media.isEmpty() && result == RESULT_OK && data != null && data.getData() != null) {
                restoreCardUi();
                saveCardMedia(media, data.getData());
            } else if (!media.isEmpty()) {
                RecentWallpapers.discardExport(this, media);
            }
            return;
        }
        if (request == PICK_PDF_BATCH) {
            String topicId = pendingPdfTopic;
            pendingPdfTopic = null;
            if (topicId != null && result == RESULT_OK && data != null) importPdfBatch(topicId, data);
            return;
        }
        if (request == PICK_SOURCE_PDF) {
            restoreSourceEditor();
            if (sourceDraft != null && result == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    String old = sourceDraft.fileUri;
                    String currentLabel = value(sourceDraft.label);
                    boolean autofill = sourceDraft.existing == null
                            && (currentLabel.isEmpty() || currentLabel.equals(sourceDraft.filename));
                    sourceDraft.fileUri = uri.toString();
                    sourceDraft.filename = AgentShare.filename(this, uri);
                    if (autofill) {
                        sourceDraft.label.setText(sourceDraft.filename);
                        sourceDraft.label.setSelection(sourceDraft.label.length());
                    }
                    sourceDraft.showFile();
                    if (!old.equals(sourceDraft.fileUri)) releaseUnused(old);
                } catch (SecurityException error) {
                    notice("无法保留 PDF 访问权限，请重新选择文件。");
                }
            }
            return;
        }
        if (request == PICK_CARD_FILE) {
            ImportTarget target = pendingCardFile;
            pendingCardFile = null;
            if (target != null && result == RESULT_OK && data != null && data.getData() != null) {
                loadCardFile(importEntry, target, data.getData());
            }
            return;
        }
        if (request != PICK_PHOTO && request != PICK_AUDIO) return;
        restoreCardUi();
        if (draft == null || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            setCardMedia(draft, request == PICK_PHOTO, uri.toString());
        } catch (SecurityException error) {
            notice("这个文件无法保留访问权限，请选择设备上的文件。");
        }
    }

    private void releaseUnused(String uri) {
        if (uri.isEmpty() || state == null || state.usesUri(uri)) return;
        if (draft != null && (uri.equals(draft.photoUri) || uri.equals(draft.audioUri))) return;
        if (sourceDraft != null && uri.equals(sourceDraft.fileUri)) return;
        if (uri.equals(pendingMediaExport)) return;
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

    private void openLink(Discovery.Source source) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(source.url)));
        } catch (ActivityNotFoundException error) {
            notice("系统没有可打开链接的应用");
        }
    }

    private void openSourcePdf(Discovery.Source source) {
        try {
            Uri uri = RestoredPdfProvider.forReader(this, Uri.parse(source.fileUri));
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, AgentShare.PDF)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException error) {
            notice("系统没有可打开 PDF 的应用");
        } catch (SecurityException | IllegalArgumentException error) {
            notice("无法打开 PDF，请重新选择文件");
        }
    }

    private void showPdfPage(Discovery.Source source, int initialPage) {
        LinearLayout panel = fields();
        TextView position = gapText(panel, "", 14, MUTED, false, 0);
        ScrollView pageScroll = new ScrollView(this);
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pageScroll.addView(image, new ScrollView.LayoutParams(-1, -2));
        int previewHeight = Math.min(dp(600), getResources().getDisplayMetrics().heightPixels / 2);
        panel.addView(pageScroll, new LinearLayout.LayoutParams(-1, previewHeight));
        LinearLayout navigation = row();
        top(panel, navigation, 6);
        Button previous = button("上一页", () -> {}, false);
        Button next = button("下一页", () -> {}, false);
        equal(navigation, previous, 0);
        equal(navigation, next, 8);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(source.label)
                .setView(panel)
                .setNeutralButton("用阅读器打开 PDF", (view, which) -> openSourcePdf(source))
                .setPositiveButton("关闭", null).show();
        Context app = getApplicationContext();
        class PageLoader {
            int displayed;
            int total;

            void updateNavigation() {
                previous.setEnabled(displayed > 1);
                next.setEnabled(displayed > 0 && displayed < total);
            }

            void load(int number) {
                previous.setEnabled(false);
                next.setEnabled(false);
                position.setText("正在读取 PDF 第 " + number + " 页…");
                importWorker.execute(() -> {
                    try {
                        PdfSources.Page page = PdfSources.renderPage(app, source.fileUri, number);
                        runOnUiThread(() -> {
                            if (isDestroyed() || !dialog.isShowing()) {
                                page.bitmap().recycle();
                                return;
                            }
                            displayed = number;
                            total = page.total();
                            image.setImageBitmap(page.bitmap());
                            image.setContentDescription("PDF 第 " + number + " 页");
                            pageScroll.scrollTo(0, 0);
                            position.setText("PDF 第 " + number + " / " + page.total() + " 页");
                            updateNavigation();
                        });
                    } catch (IOException error) {
                        runOnUiThread(() -> {
                            if (isDestroyed() || !dialog.isShowing()) return;
                            position.setText(error.getMessage());
                            updateNavigation();
                        });
                    }
                });
            }
        }
        PageLoader loader = new PageLoader();
        previous.setOnClickListener(view -> loader.load(loader.displayed - 1));
        next.setOnClickListener(view -> loader.load(loader.displayed + 1));
        loader.load(initialPage);
    }

    private void play(String uri) {
        stopAudio();
        try {
            MediaPlayer playing = uri.equals("sample")
                    ? MediaPlayer.create(this, R.raw.timbre_comparison) : new MediaPlayer();
            if (playing == null) throw new IOException("Audio resource unavailable");
            player = playing;
            playing.setOnCompletionListener(ended -> {
                if (player == ended) stopAudio();
            });
            playing.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) stopAudio();
                notice("无法播放这段音频");
                return true;
            });
            if (uri.equals("sample")) playing.start();
            else {
                Uri source = Uri.parse(uri);
                if ("file".equals(source.getScheme())) {
                    try (FileInputStream input = new FileInputStream(source.getPath())) {
                        playing.setDataSource(input.getFD());
                    }
                } else {
                    playing.setDataSource(this, source);
                }
                playing.setOnPreparedListener(ready -> {
                    if (player == ready) ready.start();
                });
                playing.prepareAsync();
            }
        } catch (IOException | RuntimeException error) {
            stopAudio();
            notice("无法播放这段音频");
        }
    }

    private void stopAudio() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private boolean commit(Store.Edit edit) {
        try {
            Store.edit(this, edit);
            return true;
        } catch (IOException error) {
            Log.w(WallpaperDelivery.TAG, "Could not save an edit", error);
            notice("保存失败，请重试");
            return false;
        }
    }

    private interface Submit {
        boolean save();
    }

    private void form(String title, LinearLayout fields, Submit submit) {
        AlertDialog dialog = dialog(title, fields);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存", (dialogInterface, which) -> {});
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (submit.save()) {
                dialog.dismiss();
                refresh(true);
            }
        });
    }

    private AlertDialog dialog(String title, LinearLayout fields) {
        ScrollView container = new ScrollView(this);
        container.addView(fields);
        return new AlertDialog.Builder(this).setTitle(title).setView(container)
                .setNegativeButton("取消", null).create();
    }

    private static boolean missing(EditText input, String message) {
        input.setError(message);
        input.requestFocus();
        return false;
    }

    private LinearLayout fields() {
        LinearLayout layout = column();
        layout.setPadding(dp(22), dp(6), dp(22), dp(8));
        return layout;
    }

    private EditText field(LinearLayout box, String hint, String value, int length, int lines) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setTextSize(16);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)});
        input.setInputType(InputType.TYPE_CLASS_TEXT | (lines > 1
                ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        input.setMinLines(lines);
        if (lines == 1) input.setSingleLine(true);
        top(box, input, 6);
        return input;
    }

    private EditText labeledField(LinearLayout box, String label, String value, int length, int lines) {
        TextView caption = gapText(box, label, 14, TEXT, true, 9);
        EditText input = field(box, "", value, length, lines);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        return input;
    }

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static final class CardDraft {
        final String topicId;
        final String id;
        final EditText title;
        final EditText body;
        final EditText task;
        final ArrayList<String> sourceIds;
        Discovery.References references;
        String photoUri = "";
        String audioUri = "";
        Button photo;
        Button audio;
        LinearLayout photoActions;
        LinearLayout audioActions;
        ImageView thumbnail;
        Future<?> thumbnailTask;

        CardDraft(String topicId, String id, EditText title, EditText body, EditText task,
                  ArrayList<String> sourceIds) {
            this.topicId = topicId;
            this.id = id;
            this.title = title;
            this.body = body;
            this.task = task;
            this.sourceIds = sourceIds;
        }

        String media(boolean image) {
            return image ? photoUri : audioUri;
        }

        void showMedia(boolean image) {
            String kind = image ? "图片" : "音频";
            boolean present = !media(image).isEmpty();
            (image ? photo : audio).setText((present ? "更换" : "添加") + kind);
            (image ? photoActions : audioActions).setVisibility(present ? View.VISIBLE : View.GONE);
        }

        Bundle snapshot() {
            Bundle saved = new Bundle();
            saved.putString("topic", topicId);
            saved.putString("id", id);
            saved.putString("title", value(title));
            saved.putString("body", value(body));
            saved.putString("task", value(task));
            saved.putStringArrayList("sources", new ArrayList<>(sourceIds));
            try {
                saved.putString("references", CardReferenceJson.write(new JSONObject(), references).toString());
            } catch (JSONException error) {
                Log.w(WallpaperDelivery.TAG, "Could not save card reference draft", error);
            }
            saved.putString("photo", photoUri);
            saved.putString("audio", audioUri);
            return saved;
        }
    }

    private LinearLayout panel(LinearLayout parent, int margin) {
        LinearLayout box = column();
        box.setBackground(rounded(PANEL, 20));
        box.setPadding(dp(16), dp(15), dp(16), dp(15));
        top(parent, box, margin);
        return box;
    }

    private TextView gapText(LinearLayout parent, String value, int sp, int color, boolean bold, int margin) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(sp);
        label.setLineSpacing(dp(2), 1);
        label.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        top(parent, label, margin);
        return label;
    }

    private Button button(String label, Runnable action, boolean primary) {
        return button(label, action, primary ? ACCENT : PREVIEW, primary ? BACKGROUND : TEXT);
    }

    private Button cardAction(String label, int ink, Runnable action) {
        Button view = button(label, action, false);
        view.setTextColor(ink);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x45899f78),
                rounded(Color.TRANSPARENT, 13), rounded(0xffffffff, 13)));
        return view;
    }

    private Button button(String label, Runnable action, int fill, int ink) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{fill == DELETE_ACCENT ? 0xff5c403b : 0xff6b7c6b, ink}));
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setStateListAnimator(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x45899f78),
                rounded(fill == DELETE_ACCENT
                        ? new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                                new int[]{0xff46332e, fill}) : ColorStateList.valueOf(fill), 13), null));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private Switch toggle(String label, boolean on) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setTextSize(16);
        view.setTextColor(TEXT);
        view.setMinimumHeight(dp(48));
        view.setChecked(on);
        return view;
    }

    private GradientDrawable rounded(int color, int radius) {
        return rounded(ColorStateList.valueOf(color), radius);
    }

    private GradientDrawable rounded(ColorStateList color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        return new LinearLayout(this);
    }

    private void top(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    private void equal(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(dp(margin));
        parent.addView(child, params);
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private void notice(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
