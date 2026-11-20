package dev.discovery.wallpaper;

import java.util.HashSet;
import java.util.Set;

public final class RotationCheck {
    public static void main(String[] args) {
        Discovery.State state = SampleCards.create();
        require(!state.automatic, "automatic changes require opt-in");
        require(state.minutes == 30, "default interval");
        require(state.topics.size() == 3, "sample topics");
        for (Discovery.Card card : state.cards.values()) {
            require(state.source(card) != null, "sample card has a source");
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

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
