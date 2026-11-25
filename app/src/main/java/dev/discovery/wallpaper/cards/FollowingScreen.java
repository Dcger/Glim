package dev.discovery.wallpaper.cards;

import static dev.discovery.wallpaper.ui.Ui.MUTED;
import static dev.discovery.wallpaper.ui.Ui.OBSERVED;
import static dev.discovery.wallpaper.ui.Ui.TEXT;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;

import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.ui.MainActivity;
import dev.discovery.wallpaper.ui.Ui;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class FollowingScreen {
    private static final String[] OBSERVATION = {"全部", "已观察", "未观察"};
    private static final String[] FAVORITES = {"全部", "已收藏", "未收藏"};
    private static final String[] SORT_ORDER = {"观察状态优先", "主题优先", "收藏状态优先"};

    private final MainActivity activity;
    private final Ui ui;
    private String topicId = "";
    private int observation;
    private int favorite;
    private int sort;

    public FollowingScreen(MainActivity activity, Ui ui) {
        this.activity = activity;
        this.ui = ui;
    }

    public void saveState(Bundle out) {
        out.putString("followingTopic", topicId);
        out.putInt("followingObservation", observation);
        out.putInt("followingFavorite", favorite);
        out.putInt("followingSort", sort);
    }

    public void restoreState(Bundle saved) {
        topicId = saved.getString("followingTopic", "");
        observation = saved.getInt("followingObservation");
        favorite = saved.getInt("followingFavorite");
        sort = saved.getInt("followingSort");
    }

    public void render(LinearLayout content) {
        Discovery.State state = activity.state();
        if (!state.hasFollowedCards()) {
            ui.gapText(content, "已观察到或已收藏的卡片会显示在这里。", 15, MUTED, false, 18);
            return;
        }
        if (!state.topics.containsKey(topicId)) topicId = "";
        ui.top(content, ui.button("筛选与排序", this::options, false), 10);
        List<Discovery.Card> matching = matchingCards(state);
        if (matching.isEmpty()) {
            ui.gapText(content, "没有符合当前筛选条件的卡片。", 15, MUTED, false, 14);
        }
        for (Discovery.Card card : matching) {
            boolean observed = state.observed(card.id);
            String status = Ui.cardStatusText(observed, state.favorite(card.id));
            LinearLayout row = ui.panel(content, 8);
            Button title = ui.button(card.title, () -> activity.openCard(card.id), false);
            title.setTextColor(observed ? OBSERVED : TEXT);
            ui.top(row, title, 0);
            ui.gapText(row, state.topics.get(card.topicId).name + " · " + status,
                    13, observed ? OBSERVED : MUTED, false, 6);
        }
    }

    private List<Discovery.Card> matchingCards(Discovery.State state) {
        List<Discovery.Card> cards = new ArrayList<>();
        for (Discovery.Card card : state.cards.values()) {
            boolean observed = state.observed(card.id);
            boolean saved = state.favorite(card.id);
            if ((!observed && !saved) || !state.topics.containsKey(card.topicId)) continue;
            if (!topicId.isEmpty() && !topicId.equals(card.topicId)) continue;
            if (matches(observation, observed) && matches(favorite, saved)) cards.add(card);
        }
        Collator alphabetic = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        Comparator<Discovery.Card> byTopic = Comparator.comparing(card -> state.topics.get(card.topicId).name,
                alphabetic);
        Comparator<Discovery.Card> byObservation = Comparator.comparing(card -> !state.observed(card.id));
        Comparator<Discovery.Card> byFavorite = Comparator.comparing(card -> !state.favorite(card.id));
        Comparator<Discovery.Card> order = switch (sort) {
            case 1 -> byTopic.thenComparing(byObservation).thenComparing(byFavorite);
            case 2 -> byFavorite.thenComparing(byObservation).thenComparing(byTopic);
            default -> byObservation.thenComparing(byTopic).thenComparing(byFavorite);
        };
        cards.sort(order);
        return cards;
    }

    private static boolean matches(int filter, boolean value) {
        return filter == 0 || value == (filter == 1);
    }

    private void options() {
        List<Discovery.Topic> topics = new ArrayList<>(activity.state().topics.values());
        String[] names = new String[topics.size() + 1];
        names[0] = "全部主题";
        int selectedTopic = 0;
        for (int i = 0; i < topics.size(); i++) {
            Discovery.Topic topic = topics.get(i);
            names[i + 1] = topic.name;
            if (topic.id.equals(topicId)) selectedTopic = i + 1;
        }
        LinearLayout fields = ui.fields();
        Spinner byObservation = choice(fields, "观察状态", OBSERVATION, observation);
        Spinner byTopic = choice(fields, "主题", names, selectedTopic);
        Spinner byFavorite = choice(fields, "收藏状态", FAVORITES, favorite);
        Spinner primary = choice(fields, "排序优先级", SORT_ORDER, sort);
        AlertDialog dialog = ui.dialog("筛选与排序", fields);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "应用", (view, which) -> {
            int topic = byTopic.getSelectedItemPosition();
            topicId = topic == 0 ? "" : topics.get(topic - 1).id;
            observation = byObservation.getSelectedItemPosition();
            favorite = byFavorite.getSelectedItemPosition();
            sort = primary.getSelectedItemPosition();
            activity.refresh(false);
        });
        dialog.show();
    }

    private Spinner choice(LinearLayout fields, String label, String[] values, int selected) {
        Spinner spinner = new Spinner(activity);
        spinner.setId(android.view.View.generateViewId());
        ui.gapText(fields, label, 14, TEXT, true, 8).setLabelFor(spinner.getId());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        ui.top(fields, spinner, 4);
        return spinner;
    }
}
