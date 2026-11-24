package dev.discovery.wallpaper.lockscreen;

import static dev.discovery.wallpaper.ui.Ui.ACCENT;
import static dev.discovery.wallpaper.ui.Ui.BACKGROUND;
import static dev.discovery.wallpaper.ui.Ui.DELETE_ACCENT;
import static dev.discovery.wallpaper.ui.Ui.MUTED;
import static dev.discovery.wallpaper.ui.Ui.PREVIEW;
import static dev.discovery.wallpaper.ui.Ui.TEXT;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.sources.PdfLocator;
import dev.discovery.wallpaper.storage.Store;
import dev.discovery.wallpaper.ui.MainActivity;
import dev.discovery.wallpaper.ui.Ui;

import java.io.IOException;
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
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class DiscoveryScreen {
    private boolean applying;
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
    private TextView rotationStatus;
    private Future<?> previewTask;

    private final MainActivity activity;
    private final Ui ui;

    public DiscoveryScreen(MainActivity activity, Ui ui) {
        this.activity = activity;
        this.ui = ui;
    }

    public void restoreState(Bundle saved) {
        previewId = saved.getString("preview", "");
        recentPreview = saved.getBoolean("recentPreview");
        ArrayList<String> shown = saved.getStringArrayList("shuffledPreviews");
        if (shown != null) shuffledPreviews.addAll(shown);
    }

    public void saveState(Bundle out) {
        out.putString("preview", previewId);
        out.putBoolean("recentPreview", recentPreview);
        out.putStringArrayList("shuffledPreviews", new ArrayList<>(shuffledPreviews));
    }

    public void clearViews() {
        previewView = null;
        rotationStatus = null;
    }

    public void selectPreview(String id, boolean allowInactive) {
        previewId = id;
        recentPreview = allowInactive;
    }

    public void resetPreview() {
        selectPreview("", false);
        shuffledPreviews.clear();
    }

    public void cardEnabledChanged(String id, boolean enabled) {
        shuffledPreviews.remove(id);
        if (!enabled && id.equals(previewId)) activity.cards.stopAudio();
    }

    public void render(LinearLayout content) {
        ui.top(content, ui.button("最近锁屏", this::recentLocks, false), 10);
        Discovery.Card card = recentPreview ? activity.state().cards.get(previewId) : activity.state().eligible(previewId);
        if (card == null) {
            recentPreview = false;
            card = activity.state().nextPreview(previewId, shuffledPreviews, previewRandom);
        }
        if (card != null) {
            showCard(content, card);
        } else {
            LinearLayout box = ui.panel(content, 18);
            boolean empty = activity.state().topics.isEmpty();
            boolean anyEnabled = activity.state().topics.values().stream().anyMatch(topic -> topic.enabled);
            ui.gapText(box, empty ? "还没有主题" : "当前没有已启用的卡片", 18, TEXT, true, 0);
            String hint = empty ? "请先添加一个你感兴趣的主题。"
                    : anyEnabled ? "请在主题的「管理卡片」中启用卡片。还没有卡片的主题可以先添加卡片。"
                    : "请先启用一个主题，再在「管理卡片」中启用卡片。";
            ui.gapText(box, hint, 14, MUTED, false, 8);
            ui.top(box, ui.button(empty ? "添加主题" : "打开主题与资料", () -> activity.changeTab(true), true), 10);
            ui.top(content, browseModeButton(), 8);
        }


        LinearLayout settings = ui.panel(content, 18);
        Switch automatic = ui.toggle("自动更换锁屏", activity.state().automatic);
        settings.addView(automatic);
        automatic.setOnCheckedChangeListener((control, enabled) -> {
            if (activity.commit(value -> value.configureRotation(enabled, value.minutes, System.currentTimeMillis()))) activity.refresh(true);
            else {
                control.setOnCheckedChangeListener(null);
                control.setChecked(!enabled);
                activity.refresh(true);
            }
        });
        ui.top(settings, ui.button("轮换间隔 · " + activity.state().minutes + " 分钟", this::interval, false), 8);
        ui.top(settings, ui.button("锁屏轮换方式 · " + modeName(activity.state().randomRotation), () -> chooseMode(false), false), 8);
        if (activity.state().automatic) {
            ui.gapText(settings, activity.scheduleOk() ? "省电时换图可能延后。若在最近任务清理 App 后停止，重新打开即可恢复排程。"
                    : "系统拒绝了排程。可关闭自动更换后再开启。", 13, MUTED, false, 8);
        }
        rotationStatus = ui.gapText(settings, "", 13, ACCENT, false, 8);
        showRotationStatus(activity.state());
    }

    public void updateRotationStatus() {
        if (rotationStatus == null) return;
        try {
            showRotationStatus(Store.read(activity));
        } catch (IOException error) {
            Log.w(WallpaperDelivery.TAG, "Could not read rotation status", error);
        }
    }

    private void showRotationStatus(Discovery.State latest) {
        rotationStatus.setVisibility(latest.status.isEmpty() ? View.GONE : View.VISIBLE);
        rotationStatus.setText(latest.status);
    }

    private void showCard(LinearLayout content, Discovery.Card card) {
        FrameLayout frame = new FrameLayout(activity);
        frame.setBackground(ui.rounded(PREVIEW, 24));
        frame.setPadding(ui.dp(15), ui.dp(15), ui.dp(15), ui.dp(15));
        ImageView image = new ImageView(activity);
        previewView = image;
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(ui.rounded(BACKGROUND, 16));
        image.setClipToOutline(true);
        image.setContentDescription("锁屏壁纸预览");
        Size size = WallpaperArt.dimensions(activity, 440);
        int imageWidth = Math.min(ui.dp(176), Math.round(
                (float) ui.dp(369) / size.getHeight() * size.getWidth()));
        int imageHeight = Math.round((float) imageWidth * size.getHeight() / size.getWidth());
        frame.addView(image, new FrameLayout.LayoutParams(imageWidth, imageHeight, Gravity.CENTER));
        ui.top(content, frame, 14);
        if (previewBitmap != null) image.setImageBitmap(previewBitmap);
        LinearLayout actions = ui.row();
        ui.top(content, actions, 8);
        nextCard = ui.button("下一张", () -> {
            Discovery.Card following = activity.state().nextPreview(previewId, shuffledPreviews, previewRandom);
            if (following == null || following.id.equals(previewId)) return;
            if (activity.state().randomBrowse) activity.state().rememberShuffle(shuffledPreviews, previewId, following.id);
            recentPreview = false;
            activity.cards.stopAudio();
            bindCard(following, size);
        }, false);
        ui.equal(actions, nextCard, 0);
        applyCard = ui.button("设为锁屏", () -> {
            Discovery.Card selected = recentPreview ? activity.state().cards.get(previewId) : activity.state().eligible(previewId);
            if (selected != null) apply(selected);
        }, true);
        ui.equal(actions, applyCard, 8);
        ui.top(content, browseModeButton(), 8);
        cardDetails = ui.panel(content, 10);
        bindCard(card, size);
    }

    private Button browseModeButton() {
        return ui.button("发现浏览顺序 · " + modeName(activity.state().randomBrowse), () -> chooseMode(true), false);
    }

    private void bindCard(Discovery.Card card, Size size) {
        previewId = card.id;
        Discovery.Topic topic = activity.state().topics.get(card.topicId);
        loadPreview(previewView, card, topic.name, size);
        LinearLayout details = cardDetails;
        details.removeAllViews();
        ui.gapText(details, topic.name, 13, ACCENT, true, 0);
        ui.gapText(details, card.title, 22, TEXT, true, 6);
        ui.gapText(details, card.body, 15, TEXT, false, 8);
        if (!card.task.isEmpty()) ui.gapText(details, "试一试 · " + card.task, 14, ACCENT, false, 10);
        showReferences(details, topic, card);
        Set<String> cited = new HashSet<>();
        for (Discovery.Citation citation : card.references.citations()) cited.add(citation.sourceId());
        for (Discovery.Source source : activity.state().sources(card)) {
            if (cited.contains(source.id)) continue;
            if (source.url.isEmpty() && source.fileUri.isEmpty()) {
                ui.gapText(details, source.label, 13, MUTED, false, 8);
            }
            if (!source.fileUri.isEmpty()) {
                ui.top(details, ui.button(source.label + " · PDF", () -> activity.sources.openSourcePdf(source), false), 8);
            }
            if (!source.url.isEmpty()) {
                ui.top(details, ui.button(source.label + " ↗", () -> activity.sources.openLink(source), false), 8);
            }
        }
        if (!card.audioUri.isEmpty()) {
            ui.top(details, ui.button(card.audioUri.equals("sample") ? "播放合成音对比" : "播放音频",
                    () -> activity.cards.play(card.audioUri), false), 8);
        }
        if (!card.photoUri.isEmpty()) {
            ui.top(details, ui.button("保存图片", () -> activity.transfer.exportCardMedia(card.title, card.photoUri, true), false), 8);
        }
        LinearLayout management = ui.row();
        ui.top(details, management, 8);
        ui.equal(management, ui.cardAction("编辑", ACCENT, () -> activity.cards.editCard(topic, card, null)), 0);
        ui.equal(management, ui.cardAction("删除", DELETE_ACCENT,
                () -> activity.cards.confirmCardDeletion(topic.id, Collections.singleton(card.id), null)), 8);

        updateCardActions();
    }

    private void showReferences(LinearLayout details, Discovery.Topic topic, Discovery.Card card) {
        if (!card.id.equals(expandedCardId)) {
            expandedCardId = card.id;
            expandedConcepts.clear();
        }
        if (!card.references.citations().isEmpty()) {
            ui.gapText(details, "正文出处", 14, ACCENT, true, 12);
            showCitations(details, topic, card.references.citations());
        }
        if (!card.references.concepts().isEmpty()) {
            ui.gapText(details, "涉及的概念", 14, ACCENT, true, 12);
        }
        for (int i = 0; i < card.references.concepts().size(); i++) {
            int index = i;
            Discovery.Concept concept = card.references.concepts().get(i);
            Button toggle = ui.button("", () -> {}, false);
            ui.top(details, toggle, 6);
            LinearLayout explanation = ui.column();
            ui.top(details, explanation, 0);
            ui.gapText(explanation, concept.explanation(), 15, TEXT, false, 6);
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
        Map<String, List<Discovery.Citation>> bySource = new LinkedHashMap<>();
        for (Discovery.Citation citation : citations) {
            bySource.computeIfAbsent(citation.sourceId(), id -> new ArrayList<>()).add(citation);
        }
        for (Map.Entry<String, List<Discovery.Citation>> entry : bySource.entrySet()) {
            Discovery.Source source = topic.source(entry.getKey());
            if (source == null) continue;
            ui.gapText(fields, source.label, 14, TEXT, true, 8);
            for (Discovery.Citation citation : entry.getValue()) showCitation(fields, source, citation);
        }
    }

    private void showCitation(LinearLayout fields, Discovery.Source source, Discovery.Citation citation) {
        ui.gapText(fields, citation.locator(), 14, MUTED, false, 3).setTextIsSelectable(true);
        if (source.fileUri.isEmpty() && source.url.isEmpty()) return;
        LinearLayout links = ui.row();
        ui.top(fields, links, 3);
        if (!source.fileUri.isEmpty()) {
            int page = PdfLocator.page(citation.locator());
            ui.equal(links, ui.cardAction(page > 0 ? "PDF 第 " + page + " 页" : "打开 PDF", ACCENT, () -> {
                if (page > 0) activity.sources.showPdfPage(source, page);
                else activity.sources.openSourcePdf(source);
            }), 0);
        }
        if (!source.url.isEmpty()) {
            ui.equal(links, ui.cardAction("打开网页", ACCENT, () -> activity.sources.openLink(source)),
                    source.fileUri.isEmpty() ? 0 : 8);
        }
    }

    private void updateCardActions() {
        Discovery.Card following = activity.state().browse(previewId);
        nextCard.setEnabled(!applying && following != null && !following.id.equals(previewId));
        applyCard.setText(applying ? "正在换图" : "设为锁屏");
        applyCard.setEnabled(!applying && (recentPreview || activity.state().eligible(previewId) != null));
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
        previewTask = activity.previewWorker().submit(() -> {
            Bitmap bitmap = WallpaperArt.render(activity.getApplicationContext(), card, topic, size);
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || !key.equals(pendingPreviewKey)) {
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
        applyWallpaper(done -> WallpaperDelivery.run(activity, WallpaperDelivery.Trigger.MANUAL,
                card.id, () -> false, done));
    }

    private void applyWallpaper(Consumer<WallpaperDelivery.Finished> start) {
        if (applying) {
            ui.notice("正在换图");
            return;
        }
        applying = true;
        if (previewView != null) updateCardActions();
        start.accept(message -> {
            applying = false;
            if (activity.isDestroyed()) return;
            ui.notice(message.isEmpty() ? "当前卡片已退出轮换" : message);
            if (previewView != null) updateCardActions();
        });
    }

    private static String modeName(boolean random) {
        return random ? "随机" : "顺序";
    }

    private void chooseMode(boolean browse) {
        boolean current = browse ? activity.state().randomBrowse : activity.state().randomRotation;
        new AlertDialog.Builder(activity).setTitle(browse ? "发现浏览顺序" : "锁屏轮换方式")
                .setSingleChoiceItems(new String[]{"顺序", "随机"}, current ? 1 : 0,
                        (dialog, index) -> {
                            boolean random = index == 1;
                            if (random == current) {
                                dialog.dismiss();
                                return;
                            }
                            if (activity.commit(latest -> {
                                if (browse) {
                                    latest.randomBrowse = random;
                                } else {
                                    latest.randomRotation = random;
                                    latest.shuffledLocks.clear();
                                }
                            })) {
                                if (browse) shuffledPreviews.clear();
                                dialog.dismiss();
                                activity.refresh(true);
                            }
                        })
                .setNegativeButton("取消", null).show();
    }

    private void recentLocks() {
        try {
            List<Discovery.LockEntry> history = Store.read(activity).recentLocks;
            AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("最近锁屏");
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
            ui.notice("无法读取最近锁屏");
        }
    }

    private void recentLockActions(Discovery.LockEntry entry) {
        new AlertDialog.Builder(activity).setTitle(entry.title())
                .setItems(new String[]{"查看这张卡片", "保存图片", "重新设为锁屏"}, (dialog, index) -> {
                    switch (index) {
                        case 0 -> viewRecentCard(entry);
                        case 1 -> activity.transfer.exportRecentLock(entry);
                        case 2 -> applyWallpaper(done -> WallpaperDelivery.reapply(activity, entry, done));
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void viewRecentCard(Discovery.LockEntry entry) {
        try {
            Discovery.State current = Store.read(activity);
            if (current.cards.containsKey(entry.cardId())) {
                activity.cards.stopAudio();
                activity.selectDiscoveryTab();
                selectPreview(entry.cardId(), true);
                activity.refresh(false);
                return;
            }
            Context app = activity.getApplicationContext();
            activity.previewWorker().execute(() -> {
                try {
                    Bitmap bitmap = RecentWallpapers.load(app, entry, current);
                    activity.runOnUiThread(() -> {
                        if (activity.isDestroyed() || activity.isFinishing()) {
                            bitmap.recycle();
                            return;
                        }
                        ImageView image = new ImageView(activity);
                        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        image.setContentDescription("历史锁屏图片");
                        image.setImageBitmap(bitmap);
                        FrameLayout frame = new FrameLayout(activity);
                        frame.addView(image, new FrameLayout.LayoutParams(-1, ui.dp(465)));
                        new AlertDialog.Builder(activity).setTitle(entry.title()).setView(frame)
                                .setPositiveButton("关闭", null)
                                .setOnDismissListener(ignored -> {
                                    image.setImageDrawable(null);
                                    bitmap.recycle();
                                }).show();
                    });
                } catch (IOException | RuntimeException error) {
                    activity.runOnUiThread(() -> ui.notice("无法查看这张卡片的锁屏图片"));
                }
            });
        } catch (IOException error) {
            ui.notice("无法读取卡片");
        }
    }

    private void interval() {
        String[] items = new String[Discovery.INTERVALS.length];
        int selected = -1;
        for (int i = 0; i < items.length; i++) {
            items[i] = Discovery.INTERVALS[i] + " 分钟";
            if (Discovery.INTERVALS[i] == activity.state().minutes) selected = i;
        }
        new AlertDialog.Builder(activity).setTitle("轮换间隔")
                .setSingleChoiceItems(items, selected, (dialog, index) -> {
                    if (activity.commit(value -> value.configureRotation(value.automatic, Discovery.INTERVALS[index],
                            System.currentTimeMillis()))) {
                        dialog.dismiss();
                        activity.refresh(true);
                    }
                }).setNegativeButton("取消", null).show();
    }
}
