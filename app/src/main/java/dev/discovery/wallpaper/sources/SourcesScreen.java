package dev.discovery.wallpaper.sources;

import static android.app.Activity.RESULT_OK;
import static dev.discovery.wallpaper.ui.MainActivity.PICK_PDF_BATCH;
import static dev.discovery.wallpaper.ui.MainActivity.PICK_SOURCE_PDF;
import static dev.discovery.wallpaper.ui.Ui.BACKGROUND;
import static dev.discovery.wallpaper.ui.Ui.DELETE_ACCENT;
import static dev.discovery.wallpaper.ui.Ui.MUTED;
import static dev.discovery.wallpaper.ui.Ui.TEXT;
import static dev.discovery.wallpaper.ui.Ui.TOPIC_NOTES_HINT;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.discovery.wallpaper.agent.AgentShare;
import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.storage.Store;
import dev.discovery.wallpaper.ui.MainActivity;
import dev.discovery.wallpaper.ui.Ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class SourcesScreen {
    private String pendingPdfTopic;
    private boolean pendingPdfForImport;
    private SourceDraft sourceDraft;
    private Bundle restoreSourceDraft;

    private final MainActivity activity;
    private final Ui ui;

    public SourcesScreen(MainActivity activity, Ui ui) {
        this.activity = activity;
        this.ui = ui;
    }

    public void restoreState(Bundle saved) {
        pendingPdfTopic = saved.getString("pendingPdfTopic");
        pendingPdfForImport = saved.getBoolean("pendingPdfForImport");
        restoreSourceDraft = saved.getBundle("sourceDraft");
    }

    public void saveState(Bundle out) {
        if (sourceDraft != null) out.putBundle("sourceDraft", sourceDraft.snapshot());
        out.putString("pendingPdfTopic", pendingPdfTopic);
        out.putBoolean("pendingPdfForImport", pendingPdfForImport);
    }

    public boolean usesUri(String uri) {
        return sourceDraft != null && uri.equals(sourceDraft.fileUri);
    }

    public void onDocumentResult(int request, int result, Intent data) {
        if (request == PICK_PDF_BATCH) {
            String topicId = pendingPdfTopic;
            boolean forImport = pendingPdfForImport;
            pendingPdfTopic = null;
            pendingPdfForImport = false;
            if (topicId != null && result == RESULT_OK && data != null) {
                activity.cards.restoreCardUi();
                activity.imports.restoreImportUi();
                importPdfBatch(topicId, data, forImport);
            }
            return;
        }
        activity.cards.restoreCardUi();
        activity.imports.restoreImportUi();
        restoreSourceEditor();
        if (sourceDraft == null || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String old = sourceDraft.fileUri;
            String currentLabel = Ui.value(sourceDraft.label);
            boolean autofill = sourceDraft.existing == null
                    && (currentLabel.isEmpty() || currentLabel.equals(sourceDraft.filename));
            sourceDraft.fileUri = uri.toString();
            sourceDraft.filename = AgentShare.filename(activity, uri);
            if (autofill) {
                sourceDraft.label.setText(sourceDraft.filename);
                sourceDraft.label.setSelection(sourceDraft.label.length());
            }
            sourceDraft.showFile();
            if (!old.equals(sourceDraft.fileUri)) activity.releaseUnused(old);
        } catch (SecurityException error) {
            ui.notice("无法保留 PDF 访问权限，请重新选择文件。");
        }
    }

    public void topicDetails(Discovery.Topic topic) {
        LinearLayout actions = ui.row();
        LinearLayout fields = ui.fields();
        EditText name = ui.labeledField(fields, "标题", topic.name, 32, 1);
        EditText notes = ui.labeledField(fields, "背景或备注", topic.context, 900, 3);
        notes.setHint(TOPIC_NOTES_HINT);
        LinearLayout list = ui.column();
        ui.top(fields, list, 12);
        AlertDialog dialog = sourceDialog("资料与备注", actions, fields);
        Consumer<Discovery.Source> edit = source -> {
            if (saveTopic(topic, name, notes)) {
                dialog.dismiss();
                editSource(topic.id, source, null);
            }
        };
        ui.equal(actions, ui.button("添加资料", () -> chooseSourceEntry(() -> edit.accept(null), () -> {
            if (saveTopic(topic, name, notes)) {
                dialog.dismiss();
                pickPdfBatch(topic.id, false);
            }
        }), true), 0);
        Button delete = ui.button("删除资料", () -> {}, DELETE_ACCENT, BACKGROUND);
        delete.setOnClickListener(view -> {
            Discovery.Topic current = activity.state() == null ? null : activity.state().topics.get(topic.id);
            if (current == null || current.sources.isEmpty()) return;
            chooseSourcesToDelete(current, updated -> {
                renderTopicSources(list, updated, edit);
                delete.setEnabled(!updated.sources.isEmpty());
            });
        });
        delete.setEnabled(!topic.sources.isEmpty());
        ui.equal(actions, delete, 8);
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
        deleteTopic.setOnClickListener(view -> activity.topics.confirmTopicDeletion(topic.id, dialog));
    }

    private AlertDialog sourceDialog(String title, LinearLayout actions, LinearLayout fields) {
        LinearLayout layout = ui.column();
        actions.setPadding(ui.dp(22), ui.dp(6), ui.dp(22), ui.dp(8));
        layout.addView(actions);
        ScrollView body = new ScrollView(activity);
        body.addView(fields);
        layout.addView(body, new LinearLayout.LayoutParams(-1, -2, 1));
        return new AlertDialog.Builder(activity).setTitle(title)
                .setView(layout).setNegativeButton("取消", null).create();
    }

    private void renderTopicSources(LinearLayout list, Discovery.Topic topic, Consumer<Discovery.Source> edit) {
        renderSourceList(list, topic.sources, null, edit, () -> {});
    }

    private List<CheckBox> renderSourceList(LinearLayout list, List<Discovery.Source> sources, Set<String> selected,
                                            Consumer<Discovery.Source> open, Runnable selectionChanged) {
        list.removeAllViews();
        List<CheckBox> checks = new ArrayList<>();
        list.setTag(checks);
        if (sources.isEmpty()) {
            ui.gapText(list, "还没有资料", 14, MUTED, false, 0);
            return checks;
        }
        Map<Discovery.Source, LinearLayout> rows = new LinkedHashMap<>();
        for (Discovery.Source source : sources) {
            CheckBox check = null;
            if (selected != null) {
                check = new CheckBox(activity);
                check.setContentDescription("选择资料 · " + source.label);
                check.setChecked(selected.contains(source.id));
                check.setOnCheckedChangeListener((view, checked) -> {
                    if (checked) selected.add(source.id);
                    else selected.remove(source.id);
                    selectionChanged.run();
                });
                checks.add(check);
            }
            Runnable openSource = open == null ? check::toggle : () -> open.accept(source);
            rows.put(source, addSourceRow(list, source, check, openSource));
        }
        Context app = activity.getApplicationContext();
        activity.ioWorker().execute(() -> {
            Map<Discovery.Source, String> problems = SourcePaths.check(app, sources);
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || activity.isFinishing() || list.getTag() != checks) return;
                problems.forEach((source, reason) -> addSourceWarning(rows.get(source), source, reason));
            });
        });
        return checks;
    }

    public LinearLayout addSourceRow(LinearLayout parent, Discovery.Source source, CheckBox check, Runnable open) {
        LinearLayout line = ui.row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        ui.top(parent, line, 8);
        if (check != null) line.addView(check, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        ui.equal(line, ui.button(sourceLabel(source), open, false), 0);
        return line;
    }

    public void addSourceWarning(LinearLayout line, Discovery.Source source, String reason) {
        FrameLayout target = new FrameLayout(activity);
        target.setContentDescription("查看资料路径问题 · " + source.label);
        target.setFocusable(true);
        target.setOnClickListener(view -> new AlertDialog.Builder(activity).setTitle(source.label)
                .setMessage(reason).setPositiveButton("关闭", null).show());
        line.addView(target, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView badge = new TextView(activity);
        badge.setText("!");
        badge.setTextSize(16);
        badge.setTextColor(BACKGROUND);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(ui.rounded(0xffffcf55, 12));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        target.addView(badge, new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
    }

    private void chooseSourcesToDelete(Discovery.Topic topic, Consumer<Discovery.Topic> onDeleted) {
        showSourceChoices("选择要删除的资料", topic.sources, Collections.emptyList(), true,
                (picker, selected) -> confirmSourceDeletion(topic.id, selected, picker, onDeleted), null, null);
    }

    public void chooseSourceEntry(Runnable single, Runnable batch) {
        new AlertDialog.Builder(activity).setTitle("添加资料")
                .setItems(new String[]{"添加单份资料", "批量导入 PDF"}, (menu, which) -> {
                    if (which == 0) single.run();
                    else batch.run();
                }).setNegativeButton("取消", null).show();
    }

    public void showSourceChoices(String title, List<Discovery.Source> sources, List<String> current,
                           boolean deleting, BiConsumer<AlertDialog, Set<String>> confirm,
                           BiConsumer<AlertDialog, Set<String>> add, SourceChoiceEdit edit) {
        List<Discovery.Source> snapshot = new ArrayList<>(sources);
        Set<String> selected = new LinkedHashSet<>();
        for (Discovery.Source source : snapshot) {
            if (current.contains(source.id)) selected.add(source.id);
        }
        LinearLayout fields = ui.fields();
        LinearLayout actions = ui.row();
        AlertDialog picker = add == null ? ui.dialog(title, fields) : sourceDialog(title, actions, fields);
        if (add != null) {
            ui.equal(actions, ui.button("添加资料", () -> add.accept(picker, selected), true), 0);
            if (!snapshot.isEmpty()) {
                ui.gapText(fields, "勾选左侧可选中资料，点名称可编辑。", 13, MUTED, false, 8);
            }
        }
        LinearLayout list = ui.column();
        ui.top(fields, list, snapshot.isEmpty() && add != null ? 10 : 0);
        Runnable update = () -> updateSourceChoices(picker, selected.size(), snapshot.size(), deleting);
        List<CheckBox> checks = renderSourceList(list, snapshot, selected,
                edit == null ? null : source -> edit.open(picker, source, selected), update);
        picker.setButton(AlertDialog.BUTTON_NEUTRAL, "全选", (dialog, which) -> {});
        picker.setButton(AlertDialog.BUTTON_POSITIVE, deleting ? "删除" : "完成", (dialog, which) -> {});
        picker.show();
        picker.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            boolean select = selected.size() < snapshot.size();
            for (CheckBox check : checks) check.setChecked(select);
        });
        picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> confirm.accept(picker, selected));
        update.run();
    }

    public interface SourceChoiceEdit {
        void open(AlertDialog picker, Discovery.Source source, Set<String> selected);
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
        AlertDialog confirmation = new AlertDialog.Builder(activity)
                .setTitle("删除 " + ids.size() + " 份资料？")
                .setMessage("删除后，卡片上的相关资料链接也会移除。卡片和原 PDF 会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", null).show();
        Button confirm = confirmation.getButton(AlertDialog.BUTTON_POSITIVE);
        confirm.setTextColor(DELETE_ACCENT);
        confirm.setOnClickListener(view -> {
            List<Discovery.Source> removed = new ArrayList<>();
            if (!activity.commit(latest -> removed.addAll(latest.removeSources(topicId, ids)))) return;
            confirmation.dismiss();
            if (picker != null) picker.dismiss();
            activity.refresh(true);
            Discovery.Topic updated = activity.state() == null ? null : activity.state().topics.get(topicId);
            if (updated != null) onDeleted.accept(updated);
            for (Discovery.Source source : removed) activity.releaseUnused(source.fileUri);
            ui.notice("已删除 " + removed.size() + " 份资料");
        });
    }

    public static String sourceLabel(Discovery.Source source) {
        return source.fileUri.isEmpty() ? source.label : source.label + " · PDF";
    }

    private boolean saveTopic(Discovery.Topic topic, EditText name, EditText notes) {
        if (Ui.value(name).isEmpty()) return Ui.missing(name, "填写主题名");
        boolean saved = activity.commit(latest -> {
            Discovery.Topic item = latest.topics.get(topic.id);
            item.name = Ui.value(name);
            item.context = Ui.value(notes);
        });
        if (saved) activity.refresh(true);
        return saved;
    }

    public void editSource(String topicId, Discovery.Source existing, Bundle saved) {
        editSource(topicId, existing, saved, null);
    }

    public void editSource(String topicId, Discovery.Source existing, Bundle saved, Runnable onClose) {
        LinearLayout fields = ui.fields();
        String labelText = existing == null ? "" : existing.label;
        String urlText = existing == null ? "" : existing.url;
        if (saved != null) {
            labelText = saved.getString("label", labelText);
            urlText = saved.getString("url", urlText);
        }
        EditText label = ui.field(fields, "书名、视频标题或资料名", labelText, 255, 1);
        EditText address = ui.field(fields, "网页链接（可选）", urlText, 1500, 1);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        SourceDraft editing = new SourceDraft(topicId, existing, label, address);
        editing.forImport = saved != null && saved.getBoolean("forImport");
        if (saved != null) {
            editing.fileUri = saved.getString("fileUri", editing.fileUri);
            editing.filename = saved.getString("filename", "");
        }
        if (editing.filename.isEmpty() && !editing.fileUri.isEmpty()) {
            editing.filename = AgentShare.filename(activity, Uri.parse(editing.fileUri));
        }
        sourceDraft = editing;
        editing.pick = ui.button("", () -> activity.pick(AgentShare.PDF, PICK_SOURCE_PDF), false);
        ui.top(fields, editing.pick, 8);
        editing.pdf = ui.gapText(fields, "", 14, TEXT, false, 8);
        Button remove = ui.button("移除 PDF 附件", () -> {
            String old = editing.fileUri;
            editing.fileUri = "";
            editing.filename = "";
            editing.showFile();
            activity.releaseUnused(old);
        }, false);
        editing.remove = remove;
        ui.top(fields, remove, 8);
        editing.showFile();
        if (existing != null && !existing.url.isEmpty()) {
            ui.top(fields, ui.button("打开原网页", () -> openLink(existing), false), 8);
        }
        AlertDialog editor = ui.dialog(existing == null ? "添加资料" : "编辑资料", fields);
        editor.setOnDismissListener(ignored -> {
            if (sourceDraft == editing) sourceDraft = null;
            if (!activity.isChangingConfigurations()) activity.releaseUnused(editing.fileUri);
            if (activity.isDestroyed() || activity.isFinishing() || activity.isChangingConfigurations()) return;
            if (onClose != null) onClose.run();
            else if (editing.forImport && editing.existing != null) {
                activity.imports.reopenSourcePicker(editing.topicId);
            }
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

    public void restoreSourceEditor() {
        Bundle saved = restoreSourceDraft;
        if (saved == null) return;
        restoreSourceDraft = null;
        try {
            Discovery.Topic topic = Store.read(activity).topics.get(saved.getString("topic"));
            if (topic == null) return;
            String id = saved.getString("id", "");
            Discovery.Source source = topic.sources.stream().filter(item -> item.id.equals(id))
                    .findFirst().orElse(null);
            if (id.isEmpty() || source != null) editSource(topic.id, source, saved);
        } catch (IOException error) {
            ui.notice("无法恢复资料编辑。");
        }
    }

    private void saveSource(SourceDraft editing, AlertDialog editor) {
        String label = Ui.value(editing.label);
        if (label.isEmpty()) {
            Ui.missing(editing.label, "填写资料名");
            return;
        }
        String url = Ui.value(editing.address);
        if (!url.isEmpty() && !SourcePaths.isWebAddress(url)) {
            Ui.missing(editing.address, "填写完整的网页链接");
            return;
        }
        String id = editing.existing == null ? "" : editing.existing.id;
        String newId = id.isEmpty() ? Ui.uuid() : id;
        String uri = editing.fileUri;
        runSourceWork("正在保存资料…",
                app -> PdfSources.save(app, editing.topicId, id, newId, label, url, uri), previousUri -> {
            activity.refresh(true);
            editor.dismiss();
            activity.releaseUnused(previousUri);
            if (editing.forImport && editing.existing == null) {
                activity.imports.selectAddedImportSources(editing.topicId, Collections.singletonList(newId));
            }
        });
    }

    public void pickPdfBatch(String topicId, boolean forImport) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(AgentShare.PDF)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pendingPdfTopic = topicId;
        pendingPdfForImport = forImport;
        try {
            activity.startActivityForResult(intent, PICK_PDF_BATCH);
        } catch (ActivityNotFoundException error) {
            pendingPdfTopic = null;
            pendingPdfForImport = false;
            ui.notice("系统文件选择器不可用");
        }
    }

    private void importPdfBatch(String topicId, Intent data, boolean forImport) {
        Set<Uri> files = new LinkedHashSet<>();
        if (data.getData() != null) files.add(data.getData());
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null) files.add(uri);
        }
        if (files.isEmpty()) return;
        runSourceWork("正在检查 PDF…", app -> PdfSources.addAll(app, topicId, new ArrayList<>(files)), result -> {
            activity.refresh(true);
            Discovery.Topic topic = activity.state() == null ? null : activity.state().topics.get(topicId);
            if (forImport) activity.imports.selectAddedImportSources(topicId, result.addedIds());
            else if (topic != null) topicDetails(topic);
            String summary = "已导入 " + result.addedIds().size() + " 份，跳过重复 " + result.duplicates() + " 份";
            if (result.failed() > 0) summary += "，无法导入 " + result.failed() + " 份"
                    + "\n请检查文件访问权限，例如「" + result.exampleFailure() + "」。";
            new AlertDialog.Builder(activity).setTitle("PDF 导入结果").setMessage(summary)
                    .setPositiveButton("确定", null).show();
        });
    }

    public interface SourceWork<T> {
        T run(Context app) throws IOException;
    }

    public <T> void runSourceWork(String label, SourceWork<T> work, Consumer<T> done) {
        AlertDialog loading = new AlertDialog.Builder(activity).setMessage(label).setCancelable(false).show();
        Context app = activity.getApplicationContext();
        activity.ioWorker().execute(() -> {
            try {
                T result = work.run(app);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    loading.dismiss();
                    done.accept(result);
                });
            } catch (IOException | RuntimeException error) {
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    loading.dismiss();
                    new AlertDialog.Builder(activity).setTitle("资料保存失败")
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
        Button pick;
        Button remove;
        boolean forImport;
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
            saved.putString("label", Ui.value(label));
            saved.putString("url", Ui.value(address));
            saved.putString("fileUri", fileUri);
            saved.putBoolean("forImport", forImport);
            saved.putString("filename", filename);
            return saved;
        }

        void showFile() {
            pick.setText(existing != null || !fileUri.isEmpty() ? "重新选择 PDF 文件" : "选择 PDF 文件");
            pdf.setVisibility(fileUri.isEmpty() ? View.GONE : View.VISIBLE);
            remove.setVisibility(fileUri.isEmpty() ? View.GONE : View.VISIBLE);
            pdf.setText(filename);
        }
    }

    public void openLink(Discovery.Source source) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(source.url)));
        } catch (ActivityNotFoundException error) {
            ui.notice("系统没有可打开链接的应用");
        }
    }

    public void openSourcePdf(Discovery.Source source) {
        try {
            Uri uri = RestoredPdfProvider.forReader(activity, Uri.parse(source.fileUri));
            activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, AgentShare.PDF)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException error) {
            ui.notice("系统没有可打开 PDF 的应用");
        } catch (SecurityException | IllegalArgumentException error) {
            ui.notice("无法打开 PDF，请重新选择文件");
        }
    }

    public void showPdfPage(Discovery.Source source, int initialPage) {
        LinearLayout panel = ui.fields();
        TextView position = ui.gapText(panel, "", 14, MUTED, false, 0);
        ScrollView pageScroll = new ScrollView(activity);
        ImageView image = new ImageView(activity);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pageScroll.addView(image, new ScrollView.LayoutParams(-1, -2));
        int previewHeight = Math.min(ui.dp(600), activity.getResources().getDisplayMetrics().heightPixels / 2);
        panel.addView(pageScroll, new LinearLayout.LayoutParams(-1, previewHeight));
        LinearLayout navigation = ui.row();
        ui.top(panel, navigation, 6);
        Button previous = ui.button("上一页", () -> {}, false);
        Button next = ui.button("下一页", () -> {}, false);
        ui.equal(navigation, previous, 0);
        ui.equal(navigation, next, 8);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(source.label)
                .setView(panel)
                .setNeutralButton("用阅读器打开 PDF", (view, which) -> openSourcePdf(source))
                .setPositiveButton("关闭", null).show();
        Context app = activity.getApplicationContext();
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
                activity.ioWorker().execute(() -> {
                    try {
                        PdfSources.Page page = PdfSources.renderPage(app, source.fileUri, number);
                        activity.runOnUiThread(() -> {
                            if (activity.isDestroyed() || !dialog.isShowing()) {
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
                        activity.runOnUiThread(() -> {
                            if (activity.isDestroyed() || !dialog.isShowing()) return;
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
}
