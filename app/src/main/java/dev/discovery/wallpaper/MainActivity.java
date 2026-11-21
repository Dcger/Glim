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
import android.text.InputFilter;
import android.text.InputType;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff111612;
    private static final int PANEL = 0xff1e2720;
    private static final int PREVIEW = 0xff29342a;
    private static final int ACCENT = 0xffd5ee92;
    private static final int TEXT = 0xfff4f5eb;
    private static final int MUTED = 0xffb5c3b3;
    private static final int DELETE_ACCENT = 0xffffb5aa;
    private static final int PICK_PHOTO = 1;
    private static final int PICK_AUDIO = 2;
    private static final int PICK_CARD_FILE = 4;
    private static final int PICK_SOURCE_PDF = 5;
    private static final int PICK_PDF_BATCH = 6;
    private static final int SAVE_CARD_MEDIA = 7;

    private LinearLayout root;
    private LinearLayout content;
    private ScrollView scroll;
    private Discovery.State state;
    private boolean showTopics;
    private boolean applying;
    private boolean scheduleOk;
    private String previewId = "";
    private Bitmap previewBitmap;
    private PreviewKey previewKey;
    private PreviewKey pendingPreviewKey;
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
    private ImportTarget pendingCardFile;
    private AlertDialog importEntry;
    private String pendingPdfTopic;
    private CardZipImport.Bundle reviewedZip;
    private CardDraft draft;
    private Bundle restoreCardDraft;
    private CardManager cardManager;
    private String restoreManagerTopic;
    private String pendingMediaExport = "";
    private SourceDraft sourceDraft;
    private Bundle restoreSourceDraft;
    private String importTopicId = "";
    private final ArrayList<String> importSourceIds = new ArrayList<>();
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
            String topicId = saved.getString("zipTopic");
            if (topicId != null) pendingCardFile =
                    new ImportTarget(topicId, saved.getStringArrayList("zipSources"));
            pendingPdfTopic = saved.getString("pendingPdfTopic");
            restoreSourceDraft = saved.getBundle("sourceDraft");
            restoreCardDraft = saved.getBundle("cardDraft");
            restoreManagerTopic = saved.getString("managedTopic");
            pendingMediaExport = saved.getString("mediaExport", "");
            importTopicId = saved.getString("importTopic", "");
            ArrayList<String> selected = saved.getStringArrayList("importSources");
            if (selected != null) importSourceIds.addAll(selected);
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
        if (pendingCardFile != null) {
            out.putString("zipTopic", pendingCardFile.topicId());
            out.putStringArrayList("zipSources", new ArrayList<>(pendingCardFile.sourceIds()));
        }
        if (sourceDraft != null) out.putBundle("sourceDraft", sourceDraft.snapshot());
        if (draft != null) out.putBundle("cardDraft", draft.snapshot());
        if (cardManager != null) out.putString("managedTopic", cardManager.topicId);
        out.putString("mediaExport", pendingMediaExport);
        out.putString("pendingPdfTopic", pendingPdfTopic);
        out.putString("importTopic", importTopicId);
        out.putStringArrayList("importSources", new ArrayList<>(importSourceIds));
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
        if (cardManager != null) cardManager.update();
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

    private void updateCardActions() {
        Discovery.Card following = state.browse(previewId);
        nextCard.setEnabled(!applying && following != null && !following.id.equals(previewId));
        applyCard.setText(applying ? "正在换图" : "设为锁屏");
        applyCard.setEnabled(!applying);
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
            equal(actions, button("管理卡片", () -> manageCards(topic), false), 8);
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
        LinearLayout layout = column();
        LinearLayout actions = row();
        actions.setPadding(dp(22), dp(6), dp(22), dp(8));
        layout.addView(actions);
        ScrollView body = new ScrollView(this);
        layout.addView(body, new LinearLayout.LayoutParams(-1, -2, 1));
        LinearLayout fields = fields();
        body.addView(fields);
        EditText name = field(fields, "主题名", topic.name, 32, 1);
        EditText notes = field(fields, "本地背景或备注", topic.context, 900, 3);
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
        String[] labels = new String[topic.sources.size()];
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) labels[i] = sourceLabel(topic.sources.get(i));
        Set<String> selected = new HashSet<>();
        AlertDialog picker = new AlertDialog.Builder(this).setTitle("选择要删除的资料")
                .setMultiChoiceItems(labels, checked, (dialog, index, on) -> {
                    String id = topic.sources.get(index).id;
                    if (on) selected.add(id);
                    else selected.remove(id);
                    Button confirm = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);
                    confirm.setText(selected.isEmpty() ? "删除" : "删除 " + selected.size() + " 份");
                    confirm.setEnabled(!selected.isEmpty());
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", null).show();
        Button select = picker.getButton(AlertDialog.BUTTON_POSITIVE);
        select.setEnabled(false);
        select.setOnClickListener(view -> confirmSourceDeletion(topic.id, selected, picker, onDeleted));
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
        cardManager = new CardManager(topic);
        cardManager.update();
    }

    private final class CardManager {
        final String topicId;
        final ArrayList<Discovery.Card> cards = new ArrayList<>();
        final ArrayAdapter<String> titles = new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_list_item_1);
        final Button delete;

        CardManager(Discovery.Topic topic) {
            topicId = topic.id;
            LinearLayout content = fields();
            LinearLayout actions = row();
            top(content, actions, 0);
            equal(actions, button("添加卡片", () -> {
                Discovery.Topic current = state.topics.get(topicId);
                if (current != null) chooseCardEntry(current);
            }, true), 0);
            delete = button("批量删除", () -> chooseCardsToDelete(topicId, new ArrayList<>(cards)),
                    DELETE_ACCENT, BACKGROUND);
            equal(actions, delete, 8);
            TextView empty = gapText(content, "此主题还没有卡片。", 14, MUTED, false, 10);
            ListView list = new ListView(MainActivity.this);
            top(content, list, 8);
            list.setAdapter(titles);
            list.setEmptyView(empty);
            list.setOnItemClickListener((parent, view, position, id) -> {
                Discovery.Topic current = state.topics.get(topicId);
                if (current != null) editCard(current, cards.get(position), null);
            });
            AlertDialog manager = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("管理卡片 · " + topic.name)
                    .setView(content)
                    .setPositiveButton("完成", null).show();
            manager.setOnDismissListener(dialog -> {
                if (cardManager == this) cardManager = null;
            });
        }

        void update() {
            cards.clear();
            ArrayList<String> labels = new ArrayList<>();
            for (Discovery.Card card : state.cards.values()) {
                if (!topicId.equals(card.topicId)) continue;
                cards.add(card);
                labels.add(card.title);
            }
            titles.setNotifyOnChange(false);
            titles.clear();
            titles.addAll(labels);
            titles.notifyDataSetChanged();
            delete.setEnabled(!cards.isEmpty());
        }
    }

    private void chooseCardsToDelete(String topicId, List<Discovery.Card> cards) {
        String[] labels = cards.stream().map(card -> card.title).toArray(String[]::new);
        Set<String> ids = new HashSet<>();
        AlertDialog picker = new AlertDialog.Builder(this).setTitle("选择要删除的卡片")
                .setMultiChoiceItems(labels, new boolean[labels.length], (dialog, index, on) -> {
                    String id = cards.get(index).id;
                    if (on) ids.add(id);
                    else ids.remove(id);
                    Button confirm = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);
                    confirm.setEnabled(!ids.isEmpty());
                    confirm.setText(ids.isEmpty() ? "删除" : "删除 " + ids.size() + " 张");
                }).setNegativeButton("取消", null).setPositiveButton("删除", null).show();
        Button select = picker.getButton(AlertDialog.BUTTON_POSITIVE);
        select.setEnabled(false);
        select.setOnClickListener(view -> confirmCardDeletion(topicId, ids, picker));
    }

    private void confirmCardDeletion(String topicId, Set<String> ids, AlertDialog parent) {
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
            if (parent != null) parent.dismiss();
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
        EditText title = field(fields, "卡片标题", cardField(saved, "title", existing == null ? "" : existing.title),
                Discovery.MAX_TITLE_LENGTH, 1);
        EditText body = field(fields, "观察或发现", cardField(saved, "body", existing == null ? "" : existing.body),
                Discovery.MAX_BODY_LENGTH, 4);
        EditText task = field(fields, "可以马上做的小任务（可选）",
                cardField(saved, "task", existing == null ? "" : existing.task), Discovery.MAX_TASK_LENGTH, 2);
        ArrayList<String> sourceIds = saved != null ? saved.getStringArrayList("sources")
                : existing == null ? new ArrayList<>() : new ArrayList<>(existing.sourceIds);
        sourceIds.removeIf(id -> topic.sources.stream().noneMatch(source -> source.id.equals(id)));
        sourcePicker(fields, topic, sourceIds);
        CardDraft editing = new CardDraft(topic.id, existing == null ? "" : existing.id, title, body, task, sourceIds);
        editing.photoUri = cardField(saved, "photo", existing == null ? "" : existing.photoUri);
        editing.audioUri = cardField(saved, "audio", existing == null ? "" : existing.audioUri);
        draft = editing;
        cardMediaControls(fields, editing, true);
        cardMediaControls(fields, editing, false);
        AlertDialog dialog = dialog((existing == null ? "添加卡片 · " : "编辑卡片 · ") + topic.name, fields);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存", (dialogInterface, which) -> {});
        if (existing != null) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "删除卡片", (view, which) -> {});
        dialog.setOnDismissListener(ignored -> {
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
                    confirmCardDeletion(topic.id, Collections.singleton(existing.id), dialog));
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (value(title).isEmpty()) {
                missing(title, "填写卡片标题");
                return;
            }
            if (value(body).isEmpty()) {
                missing(body, "填写一条发现");
                return;
            }
            String id = existing == null ? uuid() : existing.id;
            Discovery.Card card = new Discovery.Card(id, topic.id, value(title), value(body), value(task),
                    sourceIds, editing.photoUri, editing.audioUri, existing == null ? "forest" : existing.palette);
            if (commit(latest -> {
                if (!latest.topics.containsKey(topic.id) || (existing != null && !latest.cards.containsKey(id))) {
                    throw new IOException("卡片或主题已不存在。");
                }
                latest.cards.put(id, card);
            })) {
                previewId = id;
                refresh(true);
                dialog.dismiss();
                if (existing != null) {
                    releaseUnused(existing.photoUri);
                    releaseUnused(existing.audioUri);
                }
            }
        });
    }

    private static String cardField(Bundle saved, String key, String original) {
        return saved == null ? original : saved.getString(key, original);
    }

    private void cardMediaControls(LinearLayout fields, CardDraft editing, boolean photo) {
        String kind = photo ? "图片" : "音频";
        Button pick = button("", () -> pick(photo ? "image/*" : "audio/*", photo ? PICK_PHOTO : PICK_AUDIO), false);
        top(fields, pick, 8);
        LinearLayout actions = row();
        top(fields, actions, 2);
        equal(actions, cardAction("保存" + kind + "到文件", ACCENT,
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
    }

    private void setCardMedia(CardDraft editing, boolean photo, String uri) {
        String previous = editing.media(photo);
        if (photo) editing.photoUri = uri;
        else editing.audioUri = uri;
        editing.showMedia(photo);
        if (!previous.equals(uri)) releaseUnused(previous);
    }

    private void restoreCardUi() {
        if (restoreManagerTopic == null && restoreCardDraft == null) return;
        String managerTopic = restoreManagerTopic;
        Bundle saved = restoreCardDraft;
        restoreManagerTopic = null;
        restoreCardDraft = null;
        if (state == null) refresh(true);
        if (state == null) return;
        Discovery.Topic manager = state.topics.get(managerTopic);
        if (manager != null) manageCards(manager);
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

    private SourceSelection sourcePicker(LinearLayout fields, Discovery.Topic topic, ArrayList<String> ids) {
        SourceSelection selection = new SourceSelection(topic, ids);
        Button button = button(sourceSelectionLabel(ids), () -> {}, false);
        button.setOnClickListener(view -> {
            if (topic.sources.isEmpty()) {
                notice("可先在「资料与备注」里添加资料。");
                return;
            }
            String[] labels = new String[topic.sources.size()];
            boolean[] checked = new boolean[labels.length];
            for (int i = 0; i < labels.length; i++) {
                Discovery.Source item = topic.sources.get(i);
                labels[i] = sourceLabel(item);
                checked[i] = ids.contains(item.id);
            }
            new AlertDialog.Builder(this).setTitle("选择资料")
                    .setMultiChoiceItems(labels, checked, (dialog, index, on) -> checked[index] = on)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定", (dialog, which) -> {
                        ids.clear();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) ids.add(topic.sources.get(i).id);
                        }
                        button.setText(sourceSelectionLabel(ids));
                    }).show();
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
        SourceSelection sources = sourcePicker(fields, topic, importSourceIds);
        importArrow(fields);
        Button share = button("发给 Agent 生成卡片", () -> {}, false);
        share.setOnClickListener(view -> shareWithAgent(share, sources));
        share.setOnLongClickListener(view -> {
            copyAgentPrompt(agentPrompt(sources));
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
            if (importEntry == entry) importEntry = null;
        });
        entry.show();
    }

    private void resetAgentOptions() {
        importSourceIds.clear();
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

    private String agentPrompt(SourceSelection selection) {
        return AgentShare.prompt(selection.topic(), selection.sources(), includeTopicNotes, customAgentNotes);
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

    private void shareWithAgent(Button button, SourceSelection selection) {
        List<Discovery.Source> sources = selection.sources();
        String prompt = AgentShare.prompt(selection.topic(), sources, includeTopicNotes, customAgentNotes);
        button.setEnabled(false);
        Context app = getApplicationContext();
        importWorker.execute(() -> {
            try {
                Intent intent = AgentShare.intent(app, prompt, sources);
                List<AgentShare.Target> targets = AgentShare.targets(app, intent);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    button.setEnabled(true);
                    if (!button.isAttachedToWindow()) return;
                    chooseAgent(intent, targets, prompt);
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    button.setEnabled(true);
                    if (button.isAttachedToWindow()) agentShareError(error.getMessage(), prompt);
                });
            }
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
            if (commit(latest -> added.addAll(CardImport.add(latest, target.topicId(), target.sourceIds(), selected)))) {
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
                List<Discovery.Card> added = bundle.save(app, target.topicId(), target.sourceIds(), selected);
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
        if (importEntry != null && importEntry != entry) importEntry.dismiss();
        if (!added.isEmpty()) previewId = added.get(0).id;
        notice(added.isEmpty() ? "这些卡片已在本主题中" : "已导入 " + added.size() + " 张卡片");
        refresh(true);
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

    private void exportCardMedia(String title, String media, boolean photo) {
        if (media.isEmpty()) return;
        try {
            Intent intent = CardMediaExport.destination(this, title, media, photo);
            pendingMediaExport = media;
            startActivityForResult(intent, SAVE_CARD_MEDIA);
        } catch (ActivityNotFoundException error) {
            pendingMediaExport = "";
            notice("系统文件选择器不可用");
        } catch (RuntimeException error) {
            pendingMediaExport = "";
            notice("无法读取卡片媒体。");
        }
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
        if (request == SAVE_CARD_MEDIA) {
            String media = pendingMediaExport;
            pendingMediaExport = "";
            if (!media.isEmpty() && result == RESULT_OK && data != null && data.getData() != null) {
                restoreCardUi();
                saveCardMedia(media, data.getData());
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
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(source.fileUri), AgentShare.PDF)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException error) {
            notice("系统没有可打开 PDF 的应用");
        } catch (SecurityException error) {
            notice("无法打开 PDF，请重新选择文件");
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
        final String topicId;
        final String id;
        final EditText title;
        final EditText body;
        final EditText task;
        final ArrayList<String> sourceIds;
        String photoUri = "";
        String audioUri = "";
        Button photo;
        Button audio;
        LinearLayout photoActions;
        LinearLayout audioActions;

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
