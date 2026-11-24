package dev.discovery.wallpaper.cards;

import static android.app.Activity.RESULT_OK;
import static dev.discovery.wallpaper.ui.MainActivity.PICK_CARD_FILE;
import static dev.discovery.wallpaper.ui.MainActivity.PICK_RELINK_PDF;
import static dev.discovery.wallpaper.ui.Ui.ACCENT;
import static dev.discovery.wallpaper.ui.Ui.MUTED;
import static dev.discovery.wallpaper.ui.Ui.TEXT;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.InputType;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import dev.discovery.wallpaper.agent.AgentShare;
import dev.discovery.wallpaper.lockscreen.WallpaperArt;
import dev.discovery.wallpaper.lockscreen.WallpaperDelivery;
import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.sources.PdfSources;
import dev.discovery.wallpaper.sources.SourcesScreen;
import dev.discovery.wallpaper.storage.Store;
import dev.discovery.wallpaper.ui.MainActivity;
import dev.discovery.wallpaper.ui.Ui;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

public final class ImportScreen {
    private Future<?> zipRead;
    private ImportTarget pendingCardFile;
    private AlertDialog importEntry;
    private Button importSourcesButton;
    private boolean restoreImport;
    private PdfRelink pendingRelink;
    private CardZipImport.Bundle reviewedZip;
    private String importTopicId = "";
    private final ArrayList<String> importSourceIds = new ArrayList<>();
    private final ArrayList<String> agentCardIds = new ArrayList<>();
    private boolean includeTopicNotes;
    private boolean updateAgentCards;
    private String customAgentNotes = "";

    private final MainActivity activity;
    private final Ui ui;

    public ImportScreen(MainActivity activity, Ui ui) {
        this.activity = activity;
        this.ui = ui;
    }

    public void restoreState(Bundle saved) {
        String topicId = saved.getString("zipTopic");
        if (topicId != null) pendingCardFile = new ImportTarget(topicId, saved.getStringArrayList("zipSources"));
        importTopicId = saved.getString("importTopic", "");
        ArrayList<String> selected = saved.getStringArrayList("importSources");
        if (selected != null) importSourceIds.addAll(selected);
        selected = saved.getStringArrayList("agentCards");
        if (selected != null) agentCardIds.addAll(selected);
        String repairTopic = saved.getString("relinkTopic");
        if (repairTopic != null) pendingRelink = new PdfRelink(repairTopic, saved.getString("relinkSource"));
        restoreImport = saved.getBoolean("importOpen");
        updateAgentCards = saved.getBoolean("updateAgentCards");
        includeTopicNotes = saved.getBoolean("includeTopicNotes");
        customAgentNotes = saved.getString("customAgentNotes", "");
    }

    public void saveState(Bundle out) {
        if (pendingCardFile != null) {
            out.putString("zipTopic", pendingCardFile.topicId());
            out.putStringArrayList("zipSources", new ArrayList<>(pendingCardFile.sourceIds()));
        }
        out.putBoolean("importOpen", importEntry != null);
        out.putBoolean("updateAgentCards", updateAgentCards);
        out.putString("importTopic", importTopicId);
        out.putStringArrayList("importSources", new ArrayList<>(importSourceIds));
        out.putStringArrayList("agentCards", new ArrayList<>(agentCardIds));
        if (pendingRelink != null) {
            out.putString("relinkTopic", pendingRelink.topicId());
            out.putString("relinkSource", pendingRelink.sourceId());
        }
        out.putBoolean("includeTopicNotes", includeTopicNotes);
        out.putString("customAgentNotes", customAgentNotes);
    }

    public void close() {
        if (zipRead != null) zipRead.cancel(true);
        if (reviewedZip != null) reviewedZip.close();
    }

    public void topicDeleted(String topicId) {
        if (topicId.equals(importTopicId)) {
            importTopicId = "";
            resetAgentOptions();
        }
    }

    public void reopenSourcePicker(String topicId) {
        if (importEntry == null || !topicId.equals(importTopicId) || activity.state() == null) return;
        Discovery.Topic topic = activity.state().topics.get(topicId);
        if (topic == null) return;
        importSourceIds.removeIf(id -> topic.source(id) == null);
        updateImportSourcesButton(topic);
        chooseImportSources(topicId);
    }

    public void onDocumentResult(int request, int result, Intent data) {
        if (request == PICK_RELINK_PDF) {
            resumePdfRelink(result, data);
            return;
        }
        ImportTarget target = pendingCardFile;
        pendingCardFile = null;
        if (target != null && result == RESULT_OK && data != null && data.getData() != null) {
            activity.cards.restoreCardUi();
            restoreImportUi();
            loadCardFile(importEntry, target, data.getData());
        }
    }

    private List<Discovery.Source> selectedAgentSources(Discovery.Topic topic) {
        List<Discovery.Source> selected = new ArrayList<>();
        for (Discovery.Source source : topic.sources) {
            if (importSourceIds.contains(source.id)) selected.add(source);
        }
        return selected;
    }

    private void updateImportSourcesButton(Discovery.Topic topic) {
        if (importSourcesButton == null) return;
        String label = topic.sources.isEmpty() ? "添加资料（可选）" : "选择资料（可选）";
        if (!importSourceIds.isEmpty()) label += " · 已选 " + importSourceIds.size() + " 份";
        importSourcesButton.setText(label);
    }

    private void chooseImportSources(String topicId) {
        Discovery.Topic topic = activity.state() == null ? null : activity.state().topics.get(topicId);
        if (topic == null) return;
        activity.sources.showSourceChoices("选择资料（可选）", topic.sources, importSourceIds, false,
                (picker, selected) -> {
                    rememberImportSources(topic, selected);
                    picker.dismiss();
                }, (picker, selected) -> {
                    rememberImportSources(topic, selected);
                    picker.dismiss();
                    addImportSource(topicId);
                }, (picker, source, selected) -> {
                    rememberImportSources(topic, selected);
                    picker.dismiss();
                    Bundle options = new Bundle();
                    options.putBoolean("forImport", true);
                    activity.sources.editSource(topicId, source, options);
                });
    }

    private void rememberImportSources(Discovery.Topic topic, Set<String> selected) {
        importSourceIds.clear();
        importSourceIds.addAll(selected);
        updateImportSourcesButton(topic);
    }

    private void addImportSource(String topicId) {
        activity.sources.chooseSourceEntry(() -> {
            Bundle options = new Bundle();
            options.putBoolean("forImport", true);
            activity.sources.editSource(topicId, null, options);
        }, () -> activity.sources.pickPdfBatch(topicId, true));
    }

    public void selectAddedImportSources(String topicId, List<String> added) {
        if (!topicId.equals(importTopicId) || activity.state() == null) return;
        Discovery.Topic topic = activity.state().topics.get(topicId);
        if (topic == null) return;
        for (String id : added) {
            if (topic.source(id) != null && !importSourceIds.contains(id)) importSourceIds.add(id);
        }
        updateImportSourcesButton(topic);
    }

    public void restoreImportUi() {
        if (!restoreImport) return;
        if (activity.state() == null) activity.refresh(true);
        if (activity.state() == null) return;
        restoreImport = false;
        Discovery.Topic topic = activity.state().topics.get(importTopicId);
        if (topic != null) importCards(topic);
    }

    void importCards(Discovery.Topic topic) {
        LinearLayout fields = ui.fields();
        if (!topic.id.equals(importTopicId)) resetAgentOptions();
        importTopicId = topic.id;
        if (topic.context.isEmpty()) includeTopicNotes = false;
        importSourceIds.removeIf(id -> topic.source(id) == null);
        ui.gapText(fields, "第 1 步 · 选择给Agent的信息", 16, ACCENT, true, 0);
        importSourcesButton = ui.button("", () -> chooseImportSources(topic.id), false);
        updateImportSourcesButton(topic);
        ui.top(fields, importSourcesButton, 8);
        Button notes = ui.button(agentNotesLabel(), () -> {}, false);
        notes.setOnClickListener(view -> chooseAgentNotes(topic, notes));
        ui.top(fields, notes, 8);
        agentCardIds.removeIf(id -> {
            Discovery.Card card = activity.state().cards.get(id);
            return card == null || !card.topicId.equals(topic.id);
        });
        Button existing = ui.button(agentCardLabel(), () -> {}, false);
        existing.setOnClickListener(view -> chooseAgentCards(topic.id, existing));
        ui.top(fields, existing, 8);

        importArrow(fields);
        ui.gapText(fields, "第 2 步 · 发送给Agent生成卡片", 16, ACCENT, true, 0);
        Button share = ui.button("点击唤起Agent或复制提示", () -> {}, true);
        share.setOnClickListener(view -> shareWithAgent(share, topic.id));
        share.setOnLongClickListener(view -> {
            AgentRequest request = agentRequest(topic.id);
            if (request != null) copyAgentPrompt(request.prompt());
            return true;
        });
        ui.top(fields, share, 8);

        importArrow(fields);
        ui.gapText(fields, "第 3 步 · 导入卡片", 16, ACCENT, true, 0);
        LinearLayout methods = ui.row();
        methods.setBaselineAligned(false);
        ui.top(fields, methods, 8);
        ui.equal(methods, ui.button("导入 ZIP / JSON 文件", () -> pickCardFile(importTarget(topic.id)), true), 0);
        ui.equal(methods, ui.button("粘贴卡片 JSON", () -> pasteCards(importTarget(topic.id)), true), 8);
        methods.getChildAt(0).getLayoutParams().height = LinearLayout.LayoutParams.MATCH_PARENT;
        methods.getChildAt(1).getLayoutParams().height = LinearLayout.LayoutParams.MATCH_PARENT;
        ui.gapText(fields, "已有卡片时，可以直接选择导入方式。", 13, MUTED, false, 10);
        AlertDialog entry = ui.dialog("批量导入 · " + topic.name, fields);
        importEntry = entry;
        entry.setOnDismissListener(ignored -> {
            if (importEntry == entry) {
                importEntry = null;
                importSourcesButton = null;
                if (!activity.isChangingConfigurations()) {
                    importTopicId = "";
                    resetAgentOptions();
                }
            }
        });
        entry.show();
    }

    private ImportTarget importTarget(String topicId) {
        return new ImportTarget(topicId, new ArrayList<>(importSourceIds));
    }

    private void resetAgentOptions() {
        importSourceIds.clear();
        agentCardIds.clear();
        updateAgentCards = false;
        includeTopicNotes = false;
        customAgentNotes = "";
    }

    private String agentNotesLabel() {
        return includeTopicNotes || !customAgentNotes.isEmpty() ? "备注说明 · 已设置" : "备注说明（可选）";
    }

    private void chooseAgentNotes(Discovery.Topic topic, Button control) {
        LinearLayout fields = ui.fields();
        CheckBox include = new CheckBox(activity);
        include.setText("附上「资料与备注」的内容");
        include.setTextColor(TEXT);
        include.setEnabled(!topic.context.isEmpty());
        include.setChecked(include.isEnabled() && includeTopicNotes);
        ui.top(fields, include, 0);
        ui.gapText(fields, topic.context.isEmpty() ? "此主题还没有备注。" : topic.context, 14, MUTED, false, 4);
        EditText notes = ui.field(fields, "本次给 Agent 的说明（可选）", customAgentNotes, 3000, 3);
        AlertDialog dialog = ui.dialog("备注说明", fields);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "确定", (view, which) -> {
            includeTopicNotes = include.isChecked();
            customAgentNotes = Ui.value(notes);
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
        Discovery.Topic topic = activity.state() == null ? null : activity.state().topics.get(topicId);
        if (topic == null) return;
        List<Discovery.Card> available = new ArrayList<>();
        for (Discovery.Card card : activity.state().cards.values()) {
            if (card.topicId.equals(topicId)) available.add(card);
        }
        if (available.isEmpty()) {
            ui.notice("此主题还没有卡片");
            return;
        }
        Set<String> previouslySelected = new HashSet<>(agentCardIds);
        Set<String> ids = new LinkedHashSet<>();
        for (Discovery.Card card : available) {
            if (previouslySelected.contains(card.id)) ids.add(card.id);
        }
        Map<String, Set<String>> cardsBySource = cardsBySource(available);
        List<Discovery.Source> materials = new ArrayList<>();
        for (Discovery.Source source : topic.sources) {
            if (cardsBySource.containsKey(source.id)) materials.add(source);
        }

        LinearLayout content = ui.fields();
        String hint = "可以在备注说明中给Agent提供更加精细的提示";
        int start = hint.indexOf("备注说明");
        SpannableString styledHint = new SpannableString(hint);
        styledHint.setSpan(new StyleSpan(Typeface.BOLD), start, start + "备注说明".length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ui.gapText(content, "", 14, MUTED, false, 0).setText(styledHint);
        CheckBox updateSelected = new CheckBox(activity);
        updateSelected.setText("让Agent同时更新选中的卡片");
        updateSelected.setTextColor(TEXT);
        updateSelected.setChecked(updateAgentCards);
        ui.top(content, updateSelected, 4);
        TextView count = ui.gapText(content, "", 13, MUTED, false, 6);
        LinearLayout selectionActions = ui.row();
        ui.top(content, selectionActions, 6);
        Button all = ui.button("", () -> {}, false);
        ui.equal(selectionActions, all, 0);
        ListView list = new ListView(activity);
        ui.top(content, list, 8);
        Runnable update = () -> {
            count.setText("已选 " + ids.size() + " / " + available.size() + " 张");
            all.setText(ids.size() == available.size() ? "取消全选" : "全选");
        };
        ArrayAdapter<Discovery.Card> adapter = activity.cards.cardRows(available, card -> ids.contains(card.id),
                () -> "选给 Agent", (card, checked) -> {
                    if (checked) ids.add(card.id);
                    else ids.remove(card.id);
                    update.run();
                });
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> ((CardsScreen.CardRow) view.getTag()).check().toggle());
        all.setOnClickListener(view -> {
            if (ids.size() == available.size()) ids.clear();
            else for (Discovery.Card card : available) ids.add(card.id);
            adapter.notifyDataSetChanged();
            update.run();
        });
        if (!materials.isEmpty()) {
            ui.equal(selectionActions, ui.button("按引用资料选择",
                    () -> chooseCardSources(materials, cardsBySource, ids, () -> {
                        adapter.notifyDataSetChanged();
                        update.run();
                    }), false), 8);
        }
        update.run();
        new AlertDialog.Builder(activity).setTitle("选择已有卡片")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("完成", (dialog, which) -> {
                    agentCardIds.clear();
                    agentCardIds.addAll(ids);
                    updateAgentCards = !ids.isEmpty() && updateSelected.isChecked();
                    control.setText(agentCardLabel());
                }).show();
    }

    static Map<String, Set<String>> cardsBySource(List<Discovery.Card> cards) {
        Map<String, Set<String>> matching = new LinkedHashMap<>();
        for (Discovery.Card card : cards) {
            Set<String> related = new LinkedHashSet<>(card.sourceIds);
            related.addAll(card.references.sourceIds());
            for (String sourceId : related) {
                matching.computeIfAbsent(sourceId, ignored -> new LinkedHashSet<>()).add(card.id);
            }
        }
        return matching;
    }

    private void chooseCardSources(List<Discovery.Source> materials, Map<String, Set<String>> cardsBySource,
                                   Set<String> selected, Runnable updateCards) {
        List<String> labels = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels);
        Runnable updateLabels = () -> {
            labels.clear();
            for (Discovery.Source source : materials) {
                Set<String> related = cardsBySource.get(source.id);
                long checked = related.stream().filter(selected::contains).count();
                String action = checked == related.size() ? "取消勾选 · " : "勾选 · ";
                labels.add(action + source.label + "（" + checked + " / " + related.size() + " 张）");
            }
            adapter.notifyDataSetChanged();
        };
        updateLabels.run();
        AlertDialog picker = new AlertDialog.Builder(activity).setTitle("按引用资料选择")
                .setAdapter(adapter, null).setPositiveButton("完成", null).show();
        picker.getListView().setOnItemClickListener((parent, row, position, id) -> {
            Set<String> related = cardsBySource.get(materials.get(position).id);
            if (selected.containsAll(related)) selected.removeAll(related);
            else selected.addAll(related);
            updateCards.run();
            updateLabels.run();
        });
    }

    private record AgentRequest(String prompt, List<Discovery.Source> sources) {}

    private AgentRequest agentRequest(String topicId) {
        Discovery.Topic topic = activity.state() == null ? null : activity.state().topics.get(topicId);
        if (topic == null) {
            ui.notice("主题已不存在");
            return null;
        }
        List<Discovery.Source> sources = selectedAgentSources(topic);
        List<Discovery.Card> existing = new ArrayList<>();
        for (String id : agentCardIds) {
            Discovery.Card card = activity.state().cards.get(id);
            if (card != null && card.topicId.equals(topicId)) existing.add(card);
        }
        Size wallpaper = WallpaperArt.dimensions(activity, 1080);
        String prompt = AgentShare.prompt(topic, sources, includeTopicNotes, customAgentNotes, existing,
                updateAgentCards, wallpaper.getWidth(), wallpaper.getHeight());
        if (prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > AgentShare.MAX_PROMPT_BYTES) {
            ui.notice("提示内容太长，请减少已有卡片或资料后重试。");
            return null;
        }
        return new AgentRequest(prompt, sources);
    }

    private void pasteCards(ImportTarget target) {
        LinearLayout fields = ui.fields();
        EditText input = ui.field(fields, "粘贴卡片 JSON", "", CardImport.MAX_INPUT_LENGTH + 1, 5);
        input.setMaxLines(8);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        AlertDialog entry = ui.dialog("粘贴卡片 JSON", fields);
        entry.setButton(AlertDialog.BUTTON_POSITIVE, "审阅卡片", (dialog, which) -> {});
        entry.show();
        entry.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                List<CardImport.Draft> drafts = CardImport.parse(Ui.value(input), false);
                if (activity.state() == null || !activity.state().topics.containsKey(target.topicId())) {
                    throw new IOException("主题已不存在。");
                }
                List<CardImport.Draft> unique = CardImport.unique(activity.state(), target.topicId(), drafts);
                if (unique.isEmpty()) {
                    Ui.missing(input, "这些卡片已在本主题中。");
                    return;
                }
                reviewImport(entry, target, unique, drafts.size() - unique.size(), null);
            } catch (IOException error) {
                Ui.missing(input, error.getMessage());
            }
        });
    }

    private void importArrow(LinearLayout fields) {
        TextView arrow = ui.gapText(fields, "↓", 18, ACCENT, false, 0);
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
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
            activity.startActivityForResult(intent, PICK_CARD_FILE);
        } catch (ActivityNotFoundException error) {
            pendingCardFile = null;
            ui.notice("系统文件选择器不可用");
        }
    }

    private void loadCardFile(AlertDialog entry, ImportTarget target, Uri uri) {
        AlertDialog loading = new AlertDialog.Builder(activity)
                .setMessage("正在读取卡片文件…")
                .setNegativeButton("取消", (dialog, which) -> dialog.cancel())
                .show();
        Context app = activity.getApplicationContext();
        Future<?> task = activity.ioWorker().submit(() -> {
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
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || !loading.isShowing()) {
                        if (bundle != null) bundle.close();
                        return;
                    }
                    loading.dismiss();
                    if (activity.state() == null || !activity.state().topics.containsKey(target.topicId())) {
                        if (bundle != null) bundle.close();
                        ui.notice("无法读取当前主题。");
                        return;
                    }
                    List<CardImport.Draft> unique = CardImport.unique(activity.state(), target.topicId(), drafts);
                    if (unique.isEmpty()) {
                        if (bundle != null) bundle.close();
                        ui.notice("这些卡片已在本主题中");
                        return;
                    }
                    reviewedZip = bundle;
                    reviewImport(entry, target, unique, drafts.size() - unique.size(), bundle);
                });
            } catch (IOException | RuntimeException error) {
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || !loading.isShowing()) return;
                    loading.dismiss();
                    importError(error);
                });
            }
        });
        zipRead = task;
        loading.setOnCancelListener(ignored -> task.cancel(true));
    }

    private void copyAgentPrompt(String prompt) {
        activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("卡片生成提示", prompt));
        if (Build.VERSION.SDK_INT < 33) ui.notice("已复制提示");
    }

    private void shareWithAgent(Button button, String topicId) {
        AgentRequest request = agentRequest(topicId);
        if (request == null) return;
        String prompt = request.prompt();
        button.setEnabled(false);
        Context app = activity.getApplicationContext();
        activity.ioWorker().execute(() -> {
            try {
                Intent intent = AgentShare.intent(app, prompt, request.sources());
                List<AgentShare.Target> targets = AgentShare.targets(app, intent);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    button.setEnabled(true);
                    if (!button.isAttachedToWindow()) return;
                    chooseAgent(intent, targets, prompt);
                });
            } catch (AgentShare.MissingPdfs error) {
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    button.setEnabled(true);
                    if (button.isAttachedToWindow()) showPdfRelinks(topicId, error.sources);
                });
            } catch (IOException | RuntimeException error) {
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    button.setEnabled(true);
                    if (button.isAttachedToWindow()) agentShareError(error.getMessage(), prompt);
                });
            }
        });
    }

    private record PdfRelink(String topicId, String sourceId) {}

    private void showPdfRelinks(String topicId, List<Discovery.Source> missing) {
        String[] labels = missing.stream().map(source -> "重选 PDF · " + source.label).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle("部分 PDF 无法读取，选择要重新关联的资料")
                .setItems(labels, (dialog, index) -> {
                    pendingRelink = new PdfRelink(topicId, missing.get(index).id);
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(AgentShare.PDF)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        activity.startActivityForResult(picker, PICK_RELINK_PDF);
                    } catch (ActivityNotFoundException error) {
                        pendingRelink = null;
                        ui.notice("系统文件选择器不可用");
                    }
                }).setNegativeButton("返回", null).show();
    }

    private void resumePdfRelink(int result, Intent data) {
        PdfRelink relink = pendingRelink;
        pendingRelink = null;
        if (relink == null) return;
        if (activity.state() == null) activity.refresh(true);
        activity.cards.restoreCardUi();
        restoreImportUi();
        Discovery.Topic topic = activity.state() == null ? null : activity.state().topics.get(relink.topicId());
        if (topic == null) return;
        if (importEntry == null) importCards(topic);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri selected = data.getData();
        activity.sources.runSourceWork("正在核对并重新关联 PDF…",
                app -> PdfSources.relink(app, relink.topicId(), relink.sourceId(), selected), old -> {
                    activity.refresh(true);
                    activity.releaseUnused(old);
                    ui.notice("已重新关联 PDF");
                    chooseImportSources(relink.topicId());
                });
    }

    private void chooseAgent(Intent share, List<AgentShare.Target> agents, String prompt) {
        String[] choices = new String[agents.size() + 1];
        for (int i = 0; i < agents.size(); i++) choices[i] = agents.get(i).label();
        choices[agents.size()] = "其他应用 · 系统分享";
        new AlertDialog.Builder(activity).setTitle("发给 Agent 生成卡片")
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
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            agentShareError("无法打开目标应用。", prompt);
        }
    }

    private void agentShareError(String message, String prompt) {
        new AlertDialog.Builder(activity).setTitle("无法分享")
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
        AlertDialog review = new AlertDialog.Builder(activity).setTitle(heading)
                .setView(cards.layout)
                .setNegativeButton("返回", null)
                .setPositiveButton("导入选中卡片", null).show();
        cards.confirm = review.getButton(AlertDialog.BUTTON_POSITIVE);
        cards.updateRows();
        if (bundle != null) {
            review.setOnDismissListener(ignored -> {
                activity.cards.stopAudio();
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
                new AlertDialog.Builder(activity).setTitle("覆盖更新 " + updates + " 张已有卡片？")
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
            Store.edit(activity, latest -> result[0] = CardImport.add(latest, target.topicId(), target.sourceIds(), selected));
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
        final LinearLayout layout = ui.fields();
        final Button selectAll;
        final Button match;
        Button confirm;
        boolean updating;

        ImportReview(ImportTarget target, List<CardImport.Draft> drafts, CardZipImport.Bundle bundle) {
            this.target = target;
            this.drafts = drafts;
            selectAll = ui.button("全选", this::toggleAll, false);
            ui.top(layout, selectAll, 0);
            match = ui.button("匹配资料", this::matchSources, false);
            ui.top(layout, match, 6);
            ScrollView container = new ScrollView(activity);
            LinearLayout rows = ui.column();
            container.addView(rows);
            layout.addView(container, new LinearLayout.LayoutParams(-1, -2, 1));
            for (CardImport.Draft card : drafts) {
                CheckBox check = new CheckBox(activity);
                check.setTextColor(TEXT);
                check.setMinimumHeight(ui.dp(48));
                check.setEnabled(false);
                check.setOnCheckedChangeListener((view, on) -> {
                    if (!updating) updateActions();
                });
                checks.add(check);
                ui.top(rows, check, 8);
                if (bundle != null) addReviewMedia(rows, bundle, card);
            }
        }

        Discovery.Topic topic() {
            return activity.state() == null ? null : activity.state().topics.get(target.topicId());
        }

        CardImport.Draft resolved(CardImport.Draft draft) {
            return draft.remapSources(target.sourceIds(), mapping);
        }

        Discovery.Card existing(CardImport.Draft draft) {
            return activity.state() == null || !draft.isUpdate() ? null : draft.existing(activity.state(), target.topicId());
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
            LinearLayout fields = ui.fields();
            ui.gapText(fields, "每个外部 ID 只需处理一次。选同一份资料可保留原文位置。选择「导入时移除此关联」会移除该 ID 的引用和原文位置，卡片和概念解释仍会导入。",
                    14, MUTED, false, 0);
            List<Discovery.Source> sources = new ArrayList<>(topic.sources);
            ArrayList<String> names = new ArrayList<>();
            names.add("暂不处理");
            names.add("导入时移除此关联");
            for (Discovery.Source source : sources) names.add(SourcesScreen.sourceLabel(source));
            Map<String, Spinner> inputs = new LinkedHashMap<>();
            for (String id : originalMissing(topic)) {
                ui.gapText(fields, id, 14, TEXT, true, 12);
                Spinner spinner = new Spinner(activity);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                        android.R.layout.simple_spinner_item, names);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);
                String previous = mapping.get(id);
                if ("".equals(previous)) spinner.setSelection(1);
                for (int i = 0; i < sources.size(); i++) {
                    if (sources.get(i).id.equals(previous)) spinner.setSelection(i + 2);
                }
                ui.top(fields, spinner, 4);
                inputs.put(id, spinner);
            }
            AlertDialog dialog = ui.dialog("匹配资料", fields);
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
        LinearLayout actions = ui.row();
        ui.top(rows, actions, 4);
        if (!card.image().isEmpty()) {
            ui.equal(actions, ui.button("查看图片", () -> showImportImage(bundle, card), false), 0);
        }
        if (!card.audio().isEmpty()) {
            ui.equal(actions, ui.button("试听音频", () -> activity.cards.play(bundle.previewUri(card.audio())), false),
                    card.image().isEmpty() ? 0 : 8);
        }
    }

    private void showImportImage(CardZipImport.Bundle bundle, CardImport.Draft card) {
        Context app = activity.getApplicationContext();
        activity.previewWorker().execute(() -> {
            Bitmap bitmap = WallpaperArt.readPhoto(app, bundle.previewUri(card.image()), 640, 640);
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || reviewedZip != bundle) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    ui.notice("无法显示图片。");
                    return;
                }
                ImageView image = new ImageView(activity);
                image.setAdjustViewBounds(true);
                image.setMaxHeight(ui.dp(440));
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(bitmap);
                new AlertDialog.Builder(activity).setTitle(card.title()).setView(image)
                        .setPositiveButton("返回", null).show();
            });
        });
    }

    private void saveImportZip(AlertDialog entry, AlertDialog review, ImportTarget target,
                               List<CardImport.Draft> selected, CardZipImport.Bundle bundle) {
        activity.cards.stopAudio();
        AlertDialog saving = new AlertDialog.Builder(activity)
                .setMessage("正在保存卡片和媒体…")
                .setCancelable(false)
                .show();
        reviewedZip = null;
        Context app = activity.getApplicationContext();
        activity.ioWorker().execute(() -> {
            try {
                CardImport.Result result = bundle.save(app, target.topicId(), target.sourceIds(), selected);
                bundle.close();
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    saving.dismiss();
                    finishImport(entry, review, result);
                });
            } catch (IOException | RuntimeException error) {
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) {
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
            activity.discovery.selectPreview(result.cards().get(0).id, false);
        }
        if (result.updated() > 0) ui.notice("已更新 " + result.updated() + " 张，新增 " + added + " 张");
        else ui.notice(added == 0 ? "这些卡片已在本主题中" : "已导入 " + added + " 张卡片");
        activity.refresh(true);
        for (String uri : result.replacedMedia()) activity.releaseUnused(uri);
    }

    public void importError(Throwable error) {
        Log.w(WallpaperDelivery.TAG, "Could not import card bundle", error);
        String detail = error instanceof IOException ? error.getMessage() : null;
        new AlertDialog.Builder(activity).setTitle("导入失败")
                .setMessage(detail == null || detail.isEmpty() ? "无法读取或保存卡片文件。" : detail)
                .setPositiveButton("返回", null).show();
    }
}
