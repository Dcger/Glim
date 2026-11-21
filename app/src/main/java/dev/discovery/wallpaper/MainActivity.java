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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff111612;
    private static final int PANEL = 0xff1e2720;
    private static final int PREVIEW = 0xff29342a;
    private static final int ACCENT = 0xffd5ee92;
    private static final int TEXT = 0xfff4f5eb;
    private static final int MUTED = 0xffb5c3b3;
    private static final int PICK_PHOTO = 1;
    private static final int PICK_AUDIO = 2;
    private static final int PICK_IMPORT_ZIP = 4;

    private LinearLayout root;
    private LinearLayout content;
    private ScrollView scroll;
    private Discovery.State state;
    private boolean showTopics;
    private boolean applying;
    private boolean scheduleOk;
    private String previewId = "";
    private Bitmap previewBitmap;
    private String previewKey = "";
    private String pendingPreviewKey = "";
    private ImageView previewView;
    private LinearLayout cardDetails;
    private Button nextCard;
    private Button applyCard;
    private Switch rotationTest;
    private TextView lastApplied;
    private TextView rotationStatus;
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private Future<?> previewTask;
    private final ExecutorService importWorker = Executors.newSingleThreadExecutor();
    private Future<?> zipRead;
    private ImportTarget pendingZip;
    private AlertDialog importEntry;
    private CardZipImport.Bundle reviewedZip;
    private CardDraft draft;
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
            String topicId = saved.getString("zipTopic");
            if (topicId != null) pendingZip = new ImportTarget(topicId, saved.getString("zipSource", ""));
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
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("topics", showTopics);
        out.putString("preview", previewId);
        if (pendingZip != null) {
            out.putString("zipTopic", pendingZip.topicId());
            out.putString("zipSource", pendingZip.sourceId());
        }
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
            scheduleOk = RotationJob.sync(this, state);
        } catch (IOException | RuntimeException error) {
            Log.w("DiscoveryWallpaper", "Could not load the home screen", error);
        }
        previewView = null;
        rotationTest = null;
        content.removeAllViews();
        gapText(content, "随处发现", 30, TEXT, true, 0);
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
    }

    private void changeTab(boolean topics) {
        if (showTopics == topics) return;
        stopAudio();
        showTopics = topics;
        refresh(false);
    }

    private void cards() {
        Discovery.Card card = state.eligible(previewId);
        if (card == null) card = state.next(state.lastCardId);
        if (card != null) {
            showCard(card);
        } else {
            LinearLayout box = panel(content, 18);
            gapText(box, "当前没有可轮换的卡片", 18, TEXT, true, 0);
            top(box, button("管理主题", () -> changeTab(true), true), 10);
        }

        LinearLayout settings = panel(content, 18);
        Switch automatic = toggle("自动更换锁屏", state.automatic);
        settings.addView(automatic);
        automatic.setOnCheckedChangeListener((control, enabled) -> {
            if (commit(value -> value.automatic = enabled)) refresh(true);
            else {
                control.setOnCheckedChangeListener(null);
                control.setChecked(!enabled);
                refresh(true);
            }
        });
        top(settings, button("轮换间隔 · " + state.minutes + " 分钟", this::interval, false), 8);
        if (state.automatic) {
            gapText(settings, scheduleOk ? "系统可能在省电时延后换图。"
                    : "系统拒绝了排程。可关闭自动更换后再开启。", 13, MUTED, false, 8);
        }
        rotationTest = toggle("每 10 秒换锁屏（测试）", RotationTestService.isActive());
        top(settings, rotationTest, 8);
        rotationTest.setOnCheckedChangeListener((control, enabled) -> {
            if (enabled != RotationTestService.isActive()) setRotationTest(enabled);
        });
        gapText(settings, "锁屏后可观察换图，2 分钟后自动结束。", 13, MUTED, false, 0);
        lastApplied = gapText(settings, "", 13, MUTED, false, 10);
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
        Discovery.Card previous = latest.cards.get(latest.lastCardId);
        lastApplied.setVisibility(previous == null ? View.GONE : View.VISIBLE);
        if (previous != null) {
            String when = DateFormat.format("MM/dd HH:mm:ss", new Date(latest.lastAppliedAt)).toString();
            lastApplied.setText("上次应用 · " + when + " · " + previous.title);
        }
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

        cardDetails = panel(content, 10);
        LinearLayout actions = row();
        top(content, actions, 12);
        nextCard = button("下一张", () -> {
            Discovery.Card following = state.browse(previewId);
            if (following == null || following.id.equals(previewId)) return;
            stopAudio();
            bindCard(following, size);
        }, false);
        equal(actions, nextCard, 0);
        applyCard = button("设为锁屏", () -> {
            Discovery.Card selected = state.eligible(previewId);
            if (selected != null) apply(selected);
        }, true);
        equal(actions, applyCard, 8);
        bindCard(card, size);
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
        Discovery.Source source = state.source(card);
        if (source != null) {
            if (source.url.isEmpty()) gapText(details, source.label, 13, MUTED, false, 8);
            else top(details, button(source.label + " ↗", () -> openLink(source), false), 8);
        }
        if (!card.audioUri.isEmpty()) {
            top(details, button(card.audioUri.equals("sample") ? "播放合成音对比" : "播放音频",
                    () -> play(card.audioUri), false), 8);
        }

        updateCardActions();
    }

    private void updateCardActions() {
        Discovery.Card following = state.browse(previewId);
        nextCard.setEnabled(!applying && following != null && !following.id.equals(previewId));
        applyCard.setText(applying ? "正在换图" : "设为锁屏");
        applyCard.setEnabled(!applying);
    }

    private void loadPreview(ImageView image, Discovery.Card card, String topic, Size size) {
        previewView = image;
        String key = card.id + "|" + topic + "|" + size;
        if (key.equals(previewKey)) {
            pendingPreviewKey = "";
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
                pendingPreviewKey = "";
                previewKey = key;
                previewBitmap = bitmap;
                if (previewView != null) previewView.setImageBitmap(bitmap);
            });
        });
    }

    private void apply(Discovery.Card card) {
        applying = true;
        updateCardActions();
        WallpaperDelivery.run(this, WallpaperDelivery.Trigger.MANUAL, card.id, () -> false, message -> {
            applying = false;
            if (isDestroyed()) return;
            notice(message.isEmpty() ? "当前卡片已退出轮换" : message);
            if (rotationTest != null && previewView != null) updateCardActions();
        });
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
                    if (commit(value -> value.minutes = Discovery.INTERVALS[index])) {
                        dialog.dismiss();
                        refresh(true);
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void topics() {
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
            equal(actions, button("添加卡片", () -> addCard(topic), false), 8);
            top(box, button("批量导入卡片", () -> importCards(topic), false), 8);
        }
        top(content, button("新建主题", this::newTopic, true), 14);
    }

    private void newTopic() {
        LinearLayout fields = fields();
        EditText name = field(fields, "主题名", "", 32, 1);
        EditText notes = field(fields, "本地背景或备注（可选）", "", 900, 3);
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
        LinearLayout fields = fields();
        EditText name = field(fields, "主题名", topic.name, 32, 1);
        EditText notes = field(fields, "本地背景或备注", topic.context, 900, 3);
        for (Discovery.Source source : topic.sources) {
            if (source.url.isEmpty()) gapText(fields, source.label, 14, TEXT, false, 10);
            else top(fields, button(source.label + " ↗", () -> openLink(source), false), 8);
        }
        AlertDialog dialog = dialog("资料与备注", fields);
        Button add = button("添加资料", () -> {
            if (saveTopic(topic, name, notes)) {
                dialog.dismiss();
                addSource(topic.id);
            }
        }, false);
        top(fields, add, 12);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存", (dialogInterface, which) -> {});
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (saveTopic(topic, name, notes)) dialog.dismiss();
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

    private void addSource(String topicId) {
        LinearLayout fields = fields();
        EditText label = field(fields, "书名、视频标题或资料名", "", 130, 1);
        EditText address = field(fields, "网页链接（可选）", "", 1500, 1);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form("添加资料", fields, () -> {
            if (value(label).isEmpty()) return missing(label, "填写资料名");
            String url = value(address);
            Uri parsed = Uri.parse(url);
            if (!url.isEmpty() && (!("https".equalsIgnoreCase(parsed.getScheme())
                    || "http".equalsIgnoreCase(parsed.getScheme())) || parsed.getHost() == null)) {
                return missing(address, "填写完整的网页链接");
            }
            return commit(value -> value.topics.get(topicId).sources.add(
                    new Discovery.Source(uuid(), value(label), url)));
        });
    }

    private void addCard(Discovery.Topic topic) {
        LinearLayout fields = fields();
        EditText title = field(fields, "卡片标题", "", Discovery.MAX_TITLE_LENGTH, 1);
        EditText body = field(fields, "观察或发现", "", Discovery.MAX_BODY_LENGTH, 4);
        EditText task = field(fields, "可以马上做的小任务（可选）", "", Discovery.MAX_TASK_LENGTH, 2);
        Spinner sources = sourcePicker(fields, topic);

        CardDraft editing = new CardDraft();
        draft = editing;
        editing.photo = button("添加图片", () -> pick("image/*", PICK_PHOTO), false);
        editing.audio = button("添加音频", () -> pick("audio/*", PICK_AUDIO), false);
        top(fields, editing.photo, 8);
        top(fields, editing.audio, 8);
        AlertDialog dialog = dialog("添加卡片 · " + topic.name, fields);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存", (dialogInterface, which) -> {});
        dialog.setOnDismissListener(ignored -> {
            if (draft == editing) draft = null;
            if (!editing.saved) {
                releaseUnused(editing.photoUri);
                releaseUnused(editing.audioUri);
            }
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (value(title).isEmpty()) {
                missing(title, "填写卡片标题");
                return;
            }
            if (value(body).isEmpty()) {
                missing(body, "填写一条发现");
                return;
            }
            Discovery.Source selected = selectedSource(sources, topic);
            String sourceId = selected == null ? "" : selected.id;
            String id = uuid();
            Discovery.Card card = new Discovery.Card(id, topic.id, value(title), value(body), value(task),
                    sourceId, editing.photoUri, editing.audioUri, "forest");
            if (commit(state -> state.cards.put(id, card))) {
                editing.saved = true;
                previewId = id;
                dialog.dismiss();
                refresh(true);
            }
        });
    }

    private Spinner sourcePicker(LinearLayout fields, Discovery.Topic topic) {
        Spinner sources = new Spinner(this);
        ArrayList<String> labels = new ArrayList<>();
        labels.add("选择资料（可选）");
        for (Discovery.Source source : topic.sources) labels.add(source.label);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sources.setAdapter(adapter);
        sources.setMinimumHeight(dp(48));
        fields.addView(sources);
        return sources;
    }

    private Discovery.Source selectedSource(Spinner picker, Discovery.Topic topic) {
        int index = picker.getSelectedItemPosition();
        return index <= 0 ? null : topic.sources.get(index - 1);
    }

    private void importCards(Discovery.Topic topic) {
        LinearLayout fields = fields();
        Spinner sources = sourcePicker(fields, topic);
        top(fields, button("复制给 Agent 的提示", () ->
                copyImportPrompt(topic, selectedSource(sources, topic)), false), 8);
        Button zip = button("选择 ZIP 文件", () -> pickImportZip(importTarget(topic, sources)), false);
        top(fields, zip, 8);
        EditText input = field(fields, "粘贴卡片 JSON", "", CardImport.MAX_INPUT_LENGTH + 1, 5);
        input.setMaxLines(8);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        AlertDialog entry = dialog("批量导入 · " + topic.name, fields);
        importEntry = entry;
        entry.setOnDismissListener(ignored -> {
            if (importEntry == entry) importEntry = null;
        });
        entry.setButton(AlertDialog.BUTTON_POSITIVE, "审阅卡片", (dialog, which) -> {});
        entry.show();
        entry.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                List<CardImport.Draft> drafts = CardImport.parse(value(input), false);
                List<CardImport.Draft> unique = CardImport.unique(state, topic.id, drafts);
                if (unique.isEmpty()) {
                    missing(input, "这些卡片已在本主题中。");
                    return;
                }
                reviewImport(entry, importTarget(topic, sources), unique, drafts.size() - unique.size(), null);
            } catch (IOException error) {
                missing(input, error.getMessage());
            }
        });
    }

    private record ImportTarget(String topicId, String sourceId) {}

    private ImportTarget importTarget(Discovery.Topic topic, Spinner sources) {
        Discovery.Source source = selectedSource(sources, topic);
        return new ImportTarget(topic.id, source == null ? "" : source.id);
    }

    private void pickImportZip(ImportTarget target) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try {
            pendingZip = target;
            startActivityForResult(intent, PICK_IMPORT_ZIP);
        } catch (ActivityNotFoundException error) {
            pendingZip = null;
            notice("系统文件选择器不可用");
        }
    }

    private void loadImportZip(AlertDialog entry, ImportTarget target, Uri uri) {
        AlertDialog loading = new AlertDialog.Builder(this)
                .setMessage("正在读取 ZIP 和媒体文件…")
                .setNegativeButton("取消", (dialog, which) -> dialog.cancel())
                .show();
        Context app = getApplicationContext();
        Future<?> task = importWorker.submit(() -> {
            try {
                CardZipImport.Bundle bundle = CardZipImport.read(app, uri);
                runOnUiThread(() -> {
                    if (isDestroyed() || !loading.isShowing()) {
                        bundle.close();
                        return;
                    }
                    loading.dismiss();
                    if (state == null || !state.topics.containsKey(target.topicId())) {
                        bundle.close();
                        notice("无法读取当前主题。");
                        return;
                    }
                    List<CardImport.Draft> unique = CardImport.unique(state, target.topicId(), bundle.drafts);
                    if (unique.isEmpty()) {
                        bundle.close();
                        notice("这些卡片已在本主题中");
                        return;
                    }
                    reviewedZip = bundle;
                    reviewImport(entry, target, unique, bundle.drafts.size() - unique.size(), bundle);
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

    private void copyImportPrompt(Discovery.Topic topic, Discovery.Source source) {
        String prompt = "根据我随附的资料，为「" + topic.name + "」写适合手机锁屏阅读的发现卡片。"
                + "每张写一件能在当下观察或尝试的发现，事实以材料所支持的内容为准。"
                + "\n只输出 JSON 对象，不加 Markdown 代码围栏。结构示例："
                + "\n{\"cards\":[{\"title\":\"标题\",\"body\":\"一条观察或发现\",\"task\":\"可选的小任务\"}]}"
                + "\n每批最多 " + CardImport.MAX_CARDS + " 张。title 最多 " + Discovery.MAX_TITLE_LENGTH
                + " 字符，body 最多 " + Discovery.MAX_BODY_LENGTH
                + " 字符，task 可省略且最多 " + Discovery.MAX_TASK_LENGTH + " 字符。";
        if (!topic.context.isEmpty()) prompt += "\n主题背景：" + topic.context;
        if (source != null) {
            prompt += "\n本批资料：" + source.label;
            if (!source.url.isEmpty()) prompt += "\n" + source.url;
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("卡片生成提示", prompt));
        if (Build.VERSION.SDK_INT < 33) notice("已复制提示");
    }

    private void reviewImport(AlertDialog entry, ImportTarget target, List<CardImport.Draft> drafts,
                              int duplicates, CardZipImport.Bundle bundle) {
        String[] labels = new String[drafts.size()];
        boolean[] checked = new boolean[drafts.size()];
        for (int i = 0; i < drafts.size(); i++) {
            labels[i] = drafts.get(i).reviewText();
            checked[i] = true;
        }
        String heading = "审阅 " + drafts.size() + " 张卡片";
        if (duplicates > 0) heading += " · 跳过 " + duplicates + " 张重复";
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(heading)
                .setNegativeButton("返回", null)
                .setPositiveButton("导入选中卡片", null);
        if (bundle == null) {
            builder.setMultiChoiceItems(labels, checked, (dialog, index, selected) -> checked[index] = selected);
        } else {
            builder.setView(mediaReview(drafts, checked, bundle));
        }
        AlertDialog review = builder.show();
        if (bundle != null) {
            review.setOnDismissListener(ignored -> {
                stopAudio();
                if (reviewedZip == bundle) {
                    reviewedZip = null;
                    bundle.close();
                }
            });
        }
        review.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            List<CardImport.Draft> selected = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                if (checked[i]) selected.add(drafts.get(i));
            }
            if (selected.isEmpty()) {
                notice("请至少选中一张卡片");
                return;
            }
            if (bundle != null) {
                saveImportZip(entry, review, target, selected, bundle);
                return;
            }
            List<Discovery.Card> added = new ArrayList<>();
            if (commit(latest -> added.addAll(CardImport.add(latest, target.topicId(), target.sourceId(), selected)))) {
                finishImport(entry, review, added);
            }
        });
    }

    private ScrollView mediaReview(List<CardImport.Draft> drafts, boolean[] checked, CardZipImport.Bundle bundle) {
        ScrollView container = new ScrollView(this);
        LinearLayout rows = fields();
        container.addView(rows);
        for (int i = 0; i < drafts.size(); i++) {
            int index = i;
            CardImport.Draft card = drafts.get(i);
            CheckBox check = new CheckBox(this);
            check.setText(card.reviewText());
            check.setTextColor(TEXT);
            check.setMinimumHeight(dp(48));
            check.setChecked(checked[index]);
            check.setOnCheckedChangeListener((control, selected) -> checked[index] = selected);
            top(rows, check, i == 0 ? 0 : 12);
            if (card.image().isEmpty() && card.audio().isEmpty()) continue;
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
        return container;
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
                List<Discovery.Card> added = bundle.save(app, target.topicId(), target.sourceId(), selected);
                bundle.close();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    saving.dismiss();
                    finishImport(entry, review, added);
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

    private void finishImport(AlertDialog entry, AlertDialog review, List<Discovery.Card> added) {
        review.dismiss();
        if (entry != null) entry.dismiss();
        if (!added.isEmpty()) previewId = added.get(0).id;
        notice(added.isEmpty() ? "这些卡片已在本主题中" : "已导入 " + added.size() + " 张卡片");
        refresh(true);
    }

    private void importError(Throwable error) {
        Log.w(WallpaperDelivery.TAG, "Could not import card bundle", error);
        String detail = error instanceof IOException ? error.getMessage() : null;
        new AlertDialog.Builder(this).setTitle("导入失败")
                .setMessage(detail == null || detail.isEmpty() ? "无法读取或保存 ZIP。" : detail)
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

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_IMPORT_ZIP) {
            ImportTarget target = pendingZip;
            pendingZip = null;
            if (target != null && result == RESULT_OK && data != null && data.getData() != null) {
                loadImportZip(importEntry, target, data.getData());
            }
            return;
        }
        if (draft == null || result != RESULT_OK || data == null || data.getData() == null) return;
        if (request != PICK_PHOTO && request != PICK_AUDIO) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String old;
            if (request == PICK_PHOTO) {
                old = draft.photoUri;
                draft.photoUri = uri.toString();
                draft.photo.setText("已选图片 · 更换");
            } else {
                old = draft.audioUri;
                draft.audioUri = uri.toString();
                draft.audio.setText("已选音频 · 更换");
            }
            if (!old.equals(uri.toString())) releaseUnused(old);
        } catch (SecurityException error) {
            notice("这个文件无法保留访问权限，请选择设备上的文件。");
        }
    }

    private void releaseUnused(String uri) {
        if (uri.isEmpty() || state == null) return;
        for (Discovery.Card card : state.cards.values()) {
            if (uri.equals(card.photoUri) || uri.equals(card.audioUri)) return;
        }
        if (draft != null && (uri.equals(draft.photoUri) || uri.equals(draft.audioUri))) return;
        try {
            getContentResolver().releasePersistableUriPermission(
                    Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
            Log.w("DiscoveryWallpaper", "Could not save an edit", error);
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

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static final class CardDraft {
        String photoUri = "";
        String audioUri = "";
        Button photo;
        Button audio;
        boolean saved;
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
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{0xff6b7c6b, primary ? BACKGROUND : TEXT}));
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setStateListAnimator(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x45899f78),
                rounded(primary ? ACCENT : PREVIEW, 13), null));
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
