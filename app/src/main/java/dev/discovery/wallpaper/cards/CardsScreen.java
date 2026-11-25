package dev.discovery.wallpaper.cards;

import static android.app.Activity.RESULT_OK;
import static dev.discovery.wallpaper.ui.MainActivity.PICK_AUDIO;
import static dev.discovery.wallpaper.ui.MainActivity.PICK_PHOTO;
import static dev.discovery.wallpaper.ui.Ui.ACCENT;
import static dev.discovery.wallpaper.ui.Ui.BACKGROUND;
import static dev.discovery.wallpaper.ui.Ui.DELETE_ACCENT;
import static dev.discovery.wallpaper.ui.Ui.MUTED;
import static dev.discovery.wallpaper.ui.Ui.PREVIEW;
import static dev.discovery.wallpaper.ui.Ui.TEXT;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import dev.discovery.wallpaper.R;
import dev.discovery.wallpaper.lockscreen.WallpaperArt;
import dev.discovery.wallpaper.lockscreen.WallpaperDelivery;
import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.sources.SourcesScreen;
import dev.discovery.wallpaper.ui.MainActivity;
import dev.discovery.wallpaper.ui.Ui;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class CardsScreen {
    private static final int MEDIA_INACTIVE = 0xff89958a;
    private static final int MEDIA_IMAGE = 0xffa8dd98;
    private CardDraft draft;
    private Bundle restoreCardDraft;
    private CardManager cardManager;
    private String restoreManagerTopic;
    private ArrayList<String> restoreManagerSelection;
    private MediaPlayer player;

    private final MainActivity activity;
    private final Ui ui;

    public CardsScreen(MainActivity activity, Ui ui) {
        this.activity = activity;
        this.ui = ui;
    }

    public void restoreState(Bundle saved) {
        restoreCardDraft = saved.getBundle("cardDraft");
        restoreManagerTopic = saved.getString("managedTopic");
        restoreManagerSelection = saved.getStringArrayList("managedSelection");
    }

    public void saveState(Bundle out) {
        if (draft != null) out.putBundle("cardDraft", draft.snapshot());
        if (cardManager != null) {
            out.putString("managedTopic", cardManager.topicId);
            if (cardManager.selecting) {
                out.putStringArrayList("managedSelection", new ArrayList<>(cardManager.selected));
            }
        }
    }

    public boolean usesUri(String uri) {
        return draft != null && (uri.equals(draft.photoUri) || uri.equals(draft.audioUri));
    }

    public void updateManager() {
        if (cardManager != null) cardManager.update();
    }

    public void onMediaResult(boolean photo, int result, Intent data) {
        restoreCardUi();
        if (draft == null || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            setCardMedia(draft, photo, uri.toString());
        } catch (SecurityException error) {
            ui.notice("这个文件无法保留访问权限，请选择设备上的文件。");
        }
    }

    public void chooseCardEntry(Discovery.Topic topic) {
        new AlertDialog.Builder(activity).setTitle("添加卡片 · " + topic.name)
                .setItems(new String[]{"手动添加卡片", "批量导入卡片（可由Agent辅助生成）"}, (dialog, which) -> {
                    if (which == 0) editCard(topic, null, null);
                    else activity.imports.importCards(topic);
                })
                .setNegativeButton("取消", null).show();
    }

    public void manageCards(Discovery.Topic topic) {
        manageCards(topic, null);
    }

    public void manageCards(Discovery.Topic topic, List<String> selection) {
        cardManager = new CardManager(topic, selection);
        cardManager.update();
    }

    private final class CardManager {
        final String topicId;
        final ArrayList<Discovery.Card> cards = new ArrayList<>();
        final Set<String> selected = new HashSet<>();
        boolean selecting;
        final ArrayAdapter<Discovery.Card> adapter = cardRows(cards,
                card -> selecting ? selected.contains(card.id) : activity.state().cardEnabled(card.id),
                () -> selecting ? "选中删除" : "参与浏览与轮换", this::setChecked);

        private void setChecked(Discovery.Card card, boolean checked) {
            if (selecting) {
                changeSelection(card.id, checked);
            } else if (activity.commit(latest -> latest.setCardEnabled(card.id, checked))) {
                activity.discovery.cardEnabledChanged(card.id, checked);
                activity.refresh(true);
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
            LinearLayout content = ui.fields();
            actions = ui.row();
            ui.top(content, actions, 0);
            ui.equal(actions, ui.button("添加卡片", () -> {
                Discovery.Topic current = activity.state().topics.get(topicId);
                if (current != null) chooseCardEntry(current);
            }, true), 0);
            select = ui.button("批量删除", () -> setSelecting(true), false);
            ui.equal(actions, select, 8);
            enableActions = ui.row();
            ui.top(content, enableActions, 6);
            enableAll = ui.button("全部启用", () -> setAllEnabled(true), false);
            disableAll = ui.button("全部停用", () -> setAllEnabled(false), false);
            ui.equal(enableActions, enableAll, 0);
            ui.equal(enableActions, disableAll, 8);
            deleteActions = ui.row();
            ui.top(content, deleteActions, 0);
            ui.equal(deleteActions, ui.button("退出选择", () -> setSelecting(false), false), 0);
            delete = ui.button("删除选中", () -> confirmCardDeletion(topicId, new HashSet<>(selected),
                            () -> setSelecting(false)),
                    DELETE_ACCENT, BACKGROUND);
            ui.equal(deleteActions, delete, 8);
            selectAll = ui.button("全选", () -> {
                if (selected.size() == cards.size()) selected.clear();
                else for (Discovery.Card card : cards) selected.add(card.id);
                updateActions();
                adapter.notifyDataSetChanged();
            }, false);
            ui.top(content, selectAll, 6);
            hint = ui.gapText(content, "", 13, MUTED, false, 10);
            TextView empty = ui.gapText(content, "此主题还没有卡片。", 14, MUTED, false, 10);
            ListView list = new ListView(activity);
            ui.top(content, list, 8);
            list.setAdapter(adapter);
            list.setEmptyView(empty);
            list.setOnItemClickListener((parent, view, position, id) -> {
                Discovery.Card card = cards.get(position);
                if (selecting) {
                    ((CardRow) view.getTag()).check().setChecked(!selected.contains(card.id));
                    return;
                }
                Discovery.Topic current = activity.state().topics.get(topicId);
                if (current != null) editCard(current, card, null);
            });
            manager = new AlertDialog.Builder(activity)
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
            if (!activity.commit(latest -> {
                for (Discovery.Card card : latest.cards.values()) {
                    if (topicId.equals(card.topicId)) latest.setCardEnabled(card.id, enabled);
                }
            })) return;
            for (Discovery.Card card : cards) {
                activity.discovery.cardEnabledChanged(card.id, enabled);
            }
            activity.refresh(true);
        }

        void updateActions() {
            actions.setVisibility(selecting ? View.GONE : View.VISIBLE);
            enableActions.setVisibility(selecting || cards.isEmpty() ? View.GONE : View.VISIBLE);
            int enabled = 0;
            for (Discovery.Card card : cards) {
                if (activity.state().cardEnabled(card.id)) enabled++;
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
            for (Discovery.Card card : activity.state().cards.values()) {
                if (topicId.equals(card.topicId)) cards.add(card);
            }
            selected.removeIf(id -> !activity.state().cards.containsKey(id));
            adapter.notifyDataSetChanged();
            updateActions();
        }
    }

    ArrayAdapter<Discovery.Card> cardRows(List<Discovery.Card> cards, Predicate<Discovery.Card> selected,
                                                    Supplier<String> action, BiConsumer<Discovery.Card, Boolean> change) {
        return new ArrayAdapter<>(activity, 0, cards) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    LinearLayout line = ui.row();
                    line.setGravity(Gravity.CENTER_VERTICAL);
                    line.setMinimumHeight(ui.dp(48));
                    line.setPadding(0, 0, ui.dp(10), 0);
                    CheckBox check = new CheckBox(activity);
                    check.setFocusable(false);
                    line.addView(check, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
                    TextView title = new TextView(activity);
                    title.setTextColor(TEXT);
                    title.setTextSize(16);
                    title.setMaxLines(2);
                    title.setEllipsize(TextUtils.TruncateAt.END);
                    line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView image = mediaTag(line, "图");
                    TextView audio = mediaTag(line, "音");
                    TextView observed = mediaTag(line, "观");
                    observed.setTextColor(Ui.OBSERVED);
                    observed.setContentDescription("已观察");
                    line.setTag(new CardRow(check, title, image, audio, observed));
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
                boolean observed = activity.state().observed(card.id);
                views.title().setTextColor(observed ? Ui.OBSERVED : TEXT);
                views.observed().setVisibility(observed ? View.VISIBLE : View.GONE);
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

    record CardRow(CheckBox check, TextView title, TextView image, TextView audio, TextView observed) {}

    private TextView mediaTag(LinearLayout row, String value) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(label, new LinearLayout.LayoutParams(ui.dp(28), -2));
        return label;
    }

    public void confirmCardDeletion(String topicId, Set<String> ids, Runnable afterDeleted) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("删除 " + ids.size() + " 张卡片？")
                .setMessage("删除后，这些卡片会从主题列表和壁纸轮换中移除。")
                .setNegativeButton("取消", null).setPositiveButton("删除", null).show();
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        confirm.setTextColor(DELETE_ACCENT);
        confirm.setOnClickListener(view -> {
            List<Discovery.Card> removed = new ArrayList<>();
            if (!activity.commit(latest -> removed.addAll(latest.removeCards(topicId, ids)))) return;
            stopAudio();
            activity.refresh(true);
            dialog.dismiss();
            if (afterDeleted != null) afterDeleted.run();
            for (Discovery.Card card : removed) {
                activity.releaseUnused(card.photoUri);
                activity.releaseUnused(card.audioUri);
            }
            ui.notice("已删除 " + removed.size() + " 张卡片");
        });
    }

    public void editCard(Discovery.Topic topic, Discovery.Card existing, Bundle saved) {
        LinearLayout fields = ui.fields();
        Button show = ui.button("保存并显示在发现页", () -> {}, false);
        ui.top(fields, show, 0);
        EditText title = ui.labeledField(fields, "标题", cardField(saved, "title", existing == null ? "" : existing.title),
                Discovery.MAX_TITLE_LENGTH, 1);
        EditText body = ui.labeledField(fields, "描述", cardField(saved, "body", existing == null ? "" : existing.body),
                Discovery.MAX_BODY_LENGTH, 4);
        EditText task = ui.labeledField(fields, "小任务（可选）",
                cardField(saved, "task", existing == null ? "" : existing.task), Discovery.MAX_TASK_LENGTH, 2);
        CardDraft editing = new CardDraft(topic.id, existing == null ? "" : existing.id, title, body, task);
        editing.references = draftReferences(saved, existing);
        Button references = ui.button("原文引用与概念", () -> editReferences(topic, editing), false);
        ui.top(fields, references, 8);
        editing.photoUri = cardField(saved, "photo", existing == null ? "" : existing.photoUri);
        editing.audioUri = cardField(saved, "audio", existing == null ? "" : existing.audioUri);
        draft = editing;
        cardMediaControls(fields, editing, true);
        cardMediaControls(fields, editing, false);
        AlertDialog dialog = ui.dialog((existing == null ? "添加卡片 · " : "编辑卡片 · ") + topic.name, fields);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存", (dialogInterface, which) -> {});
        if (existing != null) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "删除卡片", (view, which) -> {});
        dialog.setOnDismissListener(ignored -> {
            if (editing.thumbnailTask != null) editing.thumbnailTask.cancel(false);
            if (draft == editing) draft = null;
            if (!activity.isChangingConfigurations()) {
                activity.releaseUnused(editing.photoUri);
                activity.releaseUnused(editing.audioUri);
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
        if (Ui.value(editing.title).isEmpty()) {
            Ui.missing(editing.title, "填写卡片标题");
            return;
        }
        if (Ui.value(editing.body).isEmpty()) {
            Ui.missing(editing.body, "填写一条发现");
            return;
        }
        String id = existing == null ? Ui.uuid() : existing.id;
        if (!activity.commit(latest -> {
            Discovery.Topic current = latest.topics.get(topic.id);
            Discovery.Card previous = latest.cards.get(id);
            if (current == null || (existing != null && previous == null)) {
                throw new IOException("卡片或主题已不存在。");
            }
            for (String sourceId : editing.references.sourceIds()) {
                if (current.source(sourceId) == null) throw new IOException("引用资料已不存在。");
            }
            latest.cards.put(id, new Discovery.Card(id, topic.id, Ui.value(editing.title), Ui.value(editing.body),
                    Ui.value(editing.task), previous == null ? Collections.emptyList() : previous.sourceIds,
                    editing.photoUri, editing.audioUri,
                    previous == null ? "forest" : previous.palette, editing.references));
        })) return;
        activity.discovery.selectPreview(id, showDiscovery);
        if (showDiscovery) {
            stopAudio();
            activity.selectDiscoveryTab();
            if (cardManager != null) cardManager.manager.dismiss();
        }
        activity.refresh(!showDiscovery);
        dialog.dismiss();
        if (existing != null) {
            activity.releaseUnused(existing.photoUri);
            activity.releaseUnused(existing.audioUri);
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
        LinearLayout fields = ui.fields();
        ArrayList<Discovery.Citation> citations = new ArrayList<>(editing.references.citations());
        ArrayList<Discovery.Concept> concepts = new ArrayList<>(editing.references.concepts());
        citationEditor(fields, topic, citations);
        ui.gapText(fields, "涉及的概念", 14, TEXT, true, 13);
        LinearLayout items = ui.column();
        ui.top(fields, items, 0);
        conceptRows(items, topic, concepts);
        referenceForm("原文引用与概念", fields, () -> {
            editing.references = new Discovery.References(citations, concepts);
            return true;
        }, null);
    }

    private void citationEditor(LinearLayout fields, Discovery.Topic topic, ArrayList<Discovery.Citation> citations) {
        ui.gapText(fields, "原文位置", 14, TEXT, true, 10);
        LinearLayout items = ui.column();
        ui.top(fields, items, 0);
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
            ui.top(items, ui.button(label + " · " + citation.locator(),
                    () -> editCitation(topic, citations, index, update), false), 5);
        }
        if (citations.size() < CardReferenceJson.MAX_CITATIONS) {
            ui.top(items, ui.button("添加原文位置", () -> editCitation(topic, citations, -1, update), false), 5);
        }
    }

    private void editCitation(Discovery.Topic topic, ArrayList<Discovery.Citation> citations, int index, Runnable update) {
        if (topic.sources.isEmpty()) {
            ui.notice("可先在「资料与备注」里添加资料。");
            return;
        }
        boolean creating = index < 0;
        Discovery.Citation existing = creating ? null : citations.get(index);
        LinearLayout fields = ui.fields();
        ui.gapText(fields, "资料", 14, TEXT, true, 9);
        List<Discovery.Source> sources = new ArrayList<>(topic.sources);
        ArrayList<String> labels = new ArrayList<>();
        int initial = 0;
        for (int i = 0; i < sources.size(); i++) {
            Discovery.Source source = sources.get(i);
            labels.add(SourcesScreen.sourceLabel(source));
            if (existing != null && source.id.equals(existing.sourceId())) initial = i;
        }
        Spinner choose = new Spinner(activity);
        ArrayAdapter<String> choices = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, labels);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        choose.setAdapter(choices);
        choose.setSelection(initial);
        ui.top(fields, choose, 5);
        EditText locator = ui.labeledField(fields, "原文位置", creating ? "" : existing.locator(),
                CardReferenceJson.MAX_LOCATOR, 2);
        locator.setHint("章节、小节、页码、图号或可搜索的短句");
        referenceForm(creating ? "添加原文位置" : "编辑原文位置", fields, () -> {
            if (Ui.value(locator).isEmpty()) return Ui.missing(locator, "填写可核对的原文位置");
            Discovery.Citation citation = new Discovery.Citation(
                    sources.get(choose.getSelectedItemPosition()).id, Ui.value(locator));
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
            ui.top(items, ui.button(concepts.get(index).term(), () -> editConcept(topic, concepts, index, update), false), 5);
        }
        if (concepts.size() < CardReferenceJson.MAX_CONCEPTS) {
            ui.top(items, ui.button("添加概念", () -> editConcept(topic, concepts, -1, update), false), 5);
        }
    }

    private void editConcept(Discovery.Topic topic, ArrayList<Discovery.Concept> concepts, int index, Runnable update) {
        boolean creating = index < 0;
        Discovery.Concept existing = creating ? null : concepts.get(index);
        LinearLayout fields = ui.fields();
        EditText term = ui.labeledField(fields, "概念", creating ? "" : existing.term(), CardReferenceJson.MAX_TERM, 1);
        EditText explanation = ui.labeledField(fields, "解释", creating ? "" : existing.explanation(),
                CardReferenceJson.MAX_EXPLANATION, 4);
        ArrayList<Discovery.Citation> citations = creating ? new ArrayList<>() : new ArrayList<>(existing.citations());
        citationEditor(fields, topic, citations);
        referenceForm(creating ? "添加概念" : "编辑概念", fields, () -> {
            if (Ui.value(term).isEmpty()) return Ui.missing(term, "填写概念名");
            if (Ui.value(explanation).isEmpty()) return Ui.missing(explanation, "填写解释");
            Discovery.Concept concept = new Discovery.Concept(Ui.value(term), Ui.value(explanation), citations);
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
        AlertDialog editor = ui.dialog(title, fields);
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
            ImageView image = new ImageView(activity);
            image.setVisibility(View.GONE);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackground(ui.rounded(PREVIEW, 10));
            image.setContentDescription("配图缩略图");
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, ui.dp(140));
            size.topMargin = ui.dp(10);
            fields.addView(image, size);
            editing.thumbnail = image;
        }
        Button pick = ui.button("", () -> activity.pick(photo ? "image/*" : "audio/*", photo ? PICK_PHOTO : PICK_AUDIO), false);
        ui.top(fields, pick, 8);
        LinearLayout actions = ui.row();
        actions.setBaselineAligned(false);
        ui.top(fields, actions, 2);
        ui.equal(actions, ui.cardAction("保存" + kind, ACCENT,
                () -> activity.transfer.exportCardMedia(Ui.value(editing.title), editing.media(photo), photo)), 0);
        ui.equal(actions, ui.cardAction("移除" + kind, DELETE_ACCENT, () -> setCardMedia(editing, photo, "")), 8);
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
        if (!previous.equals(uri)) activity.releaseUnused(previous);
    }

    private void updateThumbnail(CardDraft editing) {
        if (editing.thumbnailTask != null) editing.thumbnailTask.cancel(false);
        ImageView image = editing.thumbnail;
        image.setImageDrawable(null);
        image.setVisibility(View.GONE);
        String uri = editing.photoUri;
        if (uri.isEmpty()) return;
        Context app = activity.getApplicationContext();
        int width = ui.dp(340);
        int height = ui.dp(140);
        editing.thumbnailTask = activity.previewWorker().submit(() -> {
            Bitmap bitmap = WallpaperArt.readPhoto(app, uri, width, height);
            if (bitmap == null) return;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || draft != editing || !uri.equals(editing.photoUri)) {
                    bitmap.recycle();
                    return;
                }
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
            });
        });
    }

    public void restoreCardUi() {
        if (restoreManagerTopic == null && restoreCardDraft == null) return;
        String managerTopic = restoreManagerTopic;
        ArrayList<String> selection = restoreManagerSelection;
        Bundle saved = restoreCardDraft;
        restoreManagerTopic = null;
        restoreManagerSelection = null;
        restoreCardDraft = null;
        if (activity.state() == null) activity.refresh(true);
        if (activity.state() == null) return;
        Discovery.Topic manager = activity.state().topics.get(managerTopic);
        if (manager != null) manageCards(manager, selection);
        if (saved == null) return;
        Discovery.Topic topic = activity.state().topics.get(saved.getString("topic"));
        String id = saved.getString("id", "");
        Discovery.Card existing = activity.state().cards.get(id);
        if (topic != null && (id.isEmpty() || existing != null)) editCard(topic, existing, saved);
        else {
            activity.releaseUnused(saved.getString("photo", ""));
            activity.releaseUnused(saved.getString("audio", ""));
        }
    }

    public void play(String uri) {
        stopAudio();
        try {
            MediaPlayer playing = uri.equals("sample")
                    ? MediaPlayer.create(activity, R.raw.timbre_comparison) : new MediaPlayer();
            if (playing == null) throw new IOException("Audio resource unavailable");
            player = playing;
            playing.setOnCompletionListener(ended -> {
                if (player == ended) stopAudio();
            });
            playing.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) stopAudio();
                ui.notice("无法播放这段音频");
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
                    playing.setDataSource(activity, source);
                }
                playing.setOnPreparedListener(ready -> {
                    if (player == ready) ready.start();
                });
                playing.prepareAsync();
            }
        } catch (IOException | RuntimeException error) {
            stopAudio();
            ui.notice("无法播放这段音频");
        }
    }

    public void stopAudio() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private interface Submit {
        boolean save();
    }

    private static final class CardDraft {
        final String topicId;
        final String id;
        final EditText title;
        final EditText body;
        final EditText task;
        Discovery.References references;
        String photoUri = "";
        String audioUri = "";
        Button photo;
        Button audio;
        LinearLayout photoActions;
        LinearLayout audioActions;
        ImageView thumbnail;
        Future<?> thumbnailTask;

        CardDraft(String topicId, String id, EditText title, EditText body, EditText task) {
            this.topicId = topicId;
            this.id = id;
            this.title = title;
            this.body = body;
            this.task = task;
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
            saved.putString("title", Ui.value(title));
            saved.putString("body", Ui.value(body));
            saved.putString("task", Ui.value(task));
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
}
