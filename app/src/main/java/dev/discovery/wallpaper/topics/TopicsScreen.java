package dev.discovery.wallpaper.topics;

import static dev.discovery.wallpaper.ui.MainActivity.PICK_DATA_BACKUP;
import static dev.discovery.wallpaper.ui.Ui.DELETE_ACCENT;
import static dev.discovery.wallpaper.ui.Ui.MUTED;
import static dev.discovery.wallpaper.ui.Ui.TOPIC_NOTES_HINT;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;

import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.storage.SampleCards;
import dev.discovery.wallpaper.ui.MainActivity;
import dev.discovery.wallpaper.ui.Ui;

import java.util.HashSet;
import java.util.Set;

public final class TopicsScreen {
    private static final String FIRST_TOPIC = "firstTopic";

    private final MainActivity activity;
    private final Ui ui;

    public TopicsScreen(MainActivity activity, Ui ui) {
        this.activity = activity;
        this.ui = ui;
    }

    private boolean hasUserTopics() {
        return activity.state().topics.keySet().stream().anyMatch(id -> !SampleCards.isBuiltIn(id));
    }

    public void render(LinearLayout content, ScrollView scroll) {
        LinearLayout backupActions = ui.row();
        ui.top(content, backupActions, 12);
        ui.equal(backupActions, ui.button("导出数据", activity.transfer::chooseDataExport, false), 0);
        ui.equal(backupActions, ui.button("导入备份", () -> activity.pick("application/zip", PICK_DATA_BACKUP), false), 8);
        String firstTopic = activity.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString(FIRST_TOPIC, "");
        for (Discovery.Topic topic : activity.state().topics.values()) {
            LinearLayout box = ui.panel(content, 12);
            Switch enabled = ui.toggle(topic.name, topic.enabled);
            box.addView(enabled);
            enabled.setOnCheckedChangeListener((control, checked) -> {
                activity.commit(value -> value.topics.get(topic.id).enabled = checked);
                if (!checked) activity.cards.stopAudio();
                activity.refresh(true);
            });
            if (!topic.context.isEmpty()) ui.gapText(box, topic.context, 14, MUTED, false, 8);
            long count = 0;
            for (Discovery.Card card : activity.state().cards.values()) {
                if (topic.id.equals(card.topicId)) count++;
            }
            ui.gapText(box, count + " 张卡片 · " + topic.sources.size() + " 份资料", 13, MUTED, false, 8);
            LinearLayout actions = ui.row();
            ui.top(box, actions, 10);
            ui.equal(actions, ui.button("资料与备注", () -> activity.sources.topicDetails(topic), false), 0);
            if (count == 0) {
                ui.equal(actions, ui.button("添加卡片", () -> activity.cards.chooseCardEntry(topic), false), 8);
                if (topic.id.equals(firstTopic)) ui.guideToButton(box, "在这里添加第一张卡片", true);
            } else {
                ui.equal(actions, ui.button("管理卡片", () -> activity.cards.manageCards(topic), false), 8);
            }
        }
        if (activity.state().topics.isEmpty()) {
            ui.gapText(content, "还没有主题", 16, MUTED, false, 20);
        }
        ui.top(content, ui.button("新建主题", () -> newTopic(content, scroll), true), 14);
        if (activity.state().topics.isEmpty()) ui.guideToButton(content, "在这里添加你的主题", false);
    }

    private void newTopic(LinearLayout content, ScrollView scroll) {
        LinearLayout fields = ui.fields();
        EditText name = ui.labeledField(fields, "标题", "", 32, 1);
        EditText notes = ui.labeledField(fields, "背景或备注", "", 900, 3);
        notes.setHint(TOPIC_NOTES_HINT);
        ui.gapText(fields, "这里的信息可以在你为本主题批量生成卡片时，作为提示词的一部分，发送给你的Agent。",
                13, MUTED, false, 6);
        AlertDialog editor = ui.dialog("新建主题", fields);
        editor.setButton(AlertDialog.BUTTON_POSITIVE, "保存", (dialog, which) -> {});
        editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (Ui.value(name).isEmpty()) {
                Ui.missing(name, "填写主题名");
                return;
            }
            Discovery.Topic topic = new Discovery.Topic(Ui.uuid(), Ui.value(name), true);
            topic.context = Ui.value(notes);
            var preferences = activity.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
            boolean first = !hasUserTopics() && !preferences.contains(FIRST_TOPIC);
            if (!activity.commit(latest -> latest.topics.put(topic.id, topic))) return;
            if (first) preferences.edit().putString(FIRST_TOPIC, topic.id).apply();
            editor.dismiss();
            activity.refresh(true);
            scroll.post(() -> scroll.smoothScrollTo(0, content.getHeight()));
        });
    }

    public void confirmTopicDeletion(String topicId, AlertDialog details) {
        Discovery.Topic topic = activity.state() == null ? null : activity.state().topics.get(topicId);
        if (topic == null) return;
        long cards = activity.state().cards.values().stream().filter(card -> topicId.equals(card.topicId)).count();
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("删除「" + topic.name + "」？")
                .setMessage("将删除此主题及其中的 " + cards + " 张卡片、" + topic.sources.size()
                        + " 份资料记录。设备上的原文件会保留。")
                .setNegativeButton("取消", null).setPositiveButton("删除主题", null).show();
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        confirm.setTextColor(DELETE_ACCENT);
        confirm.setOnClickListener(view -> {
            Set<String> released = new HashSet<>();
            if (!activity.commit(latest -> latest.removeTopic(topicId, released))) return;
            dialog.dismiss();
            details.dismiss();
            activity.imports.topicDeleted(topicId);
            activity.refresh(true);
            for (String uri : released) activity.releaseUnused(uri);
            ui.notice("主题已删除");
        });
    }
}
