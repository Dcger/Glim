package dev.discovery.wallpaper;

import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;

public final class RotationCheck {
    public static void main(String[] args) {
        checkSourceRemoval();
        checkTopicRemoval();
        Discovery.State state = SampleCards.create();
        require(!state.automatic, "automatic changes require opt-in");
        require(state.minutes == 30, "default interval");
        require(state.topics.size() == 3, "sample topics");
        for (Discovery.Card card : state.cards.values()) {
            require(state.sources(card).size() == 1, "sample card has a source");
        }

        Set<String> seen = new HashSet<>();
        String after = "";
        for (int i = 0; i < state.cards.size(); i++) {
            Discovery.Card card = state.browse(after);
            require(seen.add(card.id), "preview visits every card");
            after = card.id;
        }
        require(state.browse(after).id.equals("drum"), "preview wraps");
        require(state.lastShown.isEmpty(), "preview leaves delivery history untouched");

        seen.clear();
        for (int i = 0; i < state.cards.size(); i++) {
            Discovery.Card card = state.next(state.lastCardId);
            require(seen.add(card.id), "rotate before repeating");
            state.applied(card, 1000 + i);
        }
        require(state.next(state.lastCardId).id.equals("drum"), "oldest delivery wins");
        require(state.lastAppliedAt == 1003, "persistable delivery timestamp");
        state.topics.get("birds").enabled = false;
        require(state.next(state.lastCardId).id.equals("yellow"), "excluded disabled topic");
        require(state.eligible("drum") == null, "manual delivery excludes disabled topic");
        state.topics.get("plants").enabled = false;
        require(state.browse("timbre").id.equals("timbre"), "one-card preview");
        require(state.next("timbre").id.equals("timbre"), "one-card rotation");
        state.topics.get("music").enabled = false;
        require(state.next("timbre") == null, "no eligible deliveries");
        require(state.browse("timbre") == null, "empty preview");
        System.out.println("RotationCheck passed");
    }

    private static void checkTopicRemoval() {
        Discovery.State state = SampleCards.create();
        Discovery.Card bird = state.cards.get("drum");
        String shared = "content://test/pdf";
        state.topics.get("birds").sources.add(new Discovery.Source("bird-pdf", "PDF", "", shared));
        state.topics.get("plants").sources.add(new Discovery.Source("plant-pdf", "PDF", "", shared));
        state.applied(bird, 2000);
        Set<String> assets = new HashSet<>();
        state.removeTopic("birds", assets);
        require(!state.topics.containsKey("birds") && !state.cards.containsKey(bird.id), "topic and its cards removed");
        require(!state.lastShown.containsKey(bird.id) && state.lastCardId.isEmpty(), "deleted card leaves rotation");
        require(state.cards.containsKey("yellow") && state.usesUri(shared) && assets.contains(shared),
                "assets still linked elsewhere are retained");
    }

    private static void checkSourceRemoval() {
        Discovery.State state = SampleCards.create();
        Discovery.Card original = state.cards.get("drum");
        state.topics.get("birds").sources.add(new Discovery.Source("chosen", "Chosen", ""));
        state.topics.get("birds").sources.add(new Discovery.Source("keep", "Keep", ""));
        state.cards.put(original.id, new Discovery.Card(original.id, original.topicId, original.title,
                original.body, original.task, Arrays.asList("cornell", "chosen", "keep"),
                original.photoUri, original.audioUri, original.palette));
        state.applied(original, 2000);
        Set<String> deleted = new HashSet<>(Arrays.asList("cornell", "chosen", "noaa"));
        require(state.removeSources("birds", deleted).size() == 2, "only selected sources in the topic removed");
        Discovery.Card updated = state.cards.get(original.id);
        require(updated.sourceIds.equals(Arrays.asList("keep")), "only deleted source links detached");
        require(updated.title.equals(original.title) && updated.body.equals(original.body), "card preserved");
        require(state.eligible(original.id) == updated && state.lastAppliedAt == 2000, "rotation history preserved");
        require(state.sources(state.cards.get("yellow")).size() == 1, "another topic's links preserved");
        require(state.removeSources("birds", deleted).isEmpty(), "repeated deletion is harmless");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
