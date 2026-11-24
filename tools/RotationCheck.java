package dev.discovery.wallpaper.model;

import dev.discovery.wallpaper.sources.PdfLocator;

import java.util.HashSet;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;

public final class RotationCheck {
    public static void main(String[] args) {
        checkSourceRemoval();
        checkTopicRemoval();
        checkCardRemoval();
        checkShuffle();
        checkIndependentModesAndCardEnablement();
        checkRecentLocks();
        checkRotationDeadline();
        checkPdfPageLocators();
        Discovery.State state = fixture();
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
        Random rng = new Random(23);
        for (int i = 0; i < state.cards.size(); i++) {
            Discovery.Card card = state.next(rng);
            require(seen.add(card.id), "rotate before repeating");
            require(card == state.browse(state.lastCardId), "ordered delivery follows list order");
            state.applied(lock(state, card, 1000 + i), true);
        }
        require(state.next(rng).id.equals("drum"), "ordered delivery wraps");
        require(state.lastAppliedAt == 1003, "persistable delivery timestamp");
        state.topics.get("birds").enabled = false;
        require(state.next(rng).id.equals("yellow"), "excluded disabled topic");
        require(state.eligible("drum") == null, "manual delivery excludes disabled topic");
        state.topics.get("plants").enabled = false;
        require(state.browse("timbre").id.equals("timbre"), "one-card preview");
        require(state.next(rng).id.equals("timbre"), "one-card rotation");
        state.topics.get("music").enabled = false;
        require(state.next(rng) == null, "no eligible deliveries");
        require(state.browse("timbre") == null, "empty preview");
        System.out.println("RotationCheck passed");
    }

    private static Discovery.State fixture() {
        Discovery.State state = new Discovery.State();
        for (String[] row : new String[][]{{"birds", "cornell"}, {"plants", "noaa"}, {"music", "unsw"}}) {
            Discovery.Topic topic = new Discovery.Topic(row[0], row[0], true);
            topic.sources.add(new Discovery.Source(row[1], row[1], ""));
            state.topics.put(topic.id, topic);
        }
        for (String[] row : new String[][]{
                {"drum", "birds"}, {"yellow", "plants"}, {"timbre", "music"}, {"rhythm", "birds"}}) {
            Discovery.Topic topic = state.topics.get(row[1]);
            Discovery.Card card = new Discovery.Card(row[0], topic.id, row[0], "Body",
                    "", topic.sources.get(0).id, "", "", "forest");
            state.cards.put(card.id, card);
        }
        return state;
    }

    private static void checkRotationDeadline() {
        Discovery.State state = fixture();
        long entered = 1_000_000L;
        state.configureRotation(true, 15, entered);
        long due = entered + 900_000L;
        require(!state.rotationDue(due - 1) && state.rotationDue(due), "one interval before first scheduled delivery");
        state.configureRotation(true, 15, entered + 1_000L);
        require(state.nextRotationAt == due, "reopening with unchanged settings does not move the deadline");

        long delivered = due + 120_000L;
        state.applied(lock(state, state.cards.get("drum"), delivered), true);
        require(!state.rotationDue(delivered) && !state.rotationDue(delivered + 1_000), "no queued catch-up delivery");
        require(state.rotationDue(delivered + 900_000L), "schedule next from successful delivery");
        state.applied(lock(state, state.cards.get("yellow"), delivered + 895_000L), false);
        require(!state.rotationDue(delivered + 900_000L), "manual delivery also removes an imminent automatic repeat");
        state.configureRotation(true, 60, delivered + 900_000L);
        require(state.nextRotationAt == delivered + 4_500_000L, "changing interval places a fresh deadline");
        state.configureRotation(false, 60, delivered);
        require(state.nextRotationAt == 0 && !state.rotationDue(Long.MAX_VALUE), "disabled rotation clears the deadline");
    }

    private static void checkShuffle() {
        Discovery.State state = fixture();
        state.randomRotation = true;
        Random rng = new Random(42);
        boolean differsFromOrder = false;
        for (int round = 0; round < 4; round++) {
            Set<String> shown = new HashSet<>();
            for (int i = 0; i < state.cards.size(); i++) {
                Discovery.Card next = state.next(rng);
                require(!next.id.equals(state.lastCardId), "no consecutive repeats across shuffled rounds");
                require(shown.add(next.id), "shuffle visits every eligible card once per round");
                differsFromOrder |= next != state.browse(state.lastCardId);
                state.applied(lock(state, next, 1000 + round * state.cards.size() + i), true);
            }
        }
        require(differsFromOrder, "shuffle differs from list order");
        state.topics.get("birds").enabled = false;
        state.topics.get("plants").enabled = false;
        require(state.next(rng).id.equals("timbre"), "shuffle still works with one eligible card");
        state.topics.get("music").enabled = false;
        require(state.next(rng) == null, "shuffle does not pick a disabled card");
    }

    private static void checkRecentLocks() {
        Discovery.State state = fixture();
        Discovery.Card card = state.cards.get("drum");
        state.lastCardId = card.id;
        state.lastAppliedAt = 1000;
        state.restoreLegacyLock();
        require(state.recentLocks.size() == 1 && state.recentLocks.get(0).image().isEmpty(),
                "legacy last application migrates without a saved file");
        for (int i = 1; i <= 7; i++) state.applied(lock(state, card, 1000 + i), false);
        require(state.recentLocks.size() == 5, "exactly five successful applications retained");
        require(state.recentLocks.get(0).appliedAt() == 1007
                && state.recentLocks.get(4).appliedAt() == 1003, "newest historical records first");
        state.removeCards(card.topicId, Set.of(card.id));
        require(state.recentLocks.get(0).title().equals(card.title), "card deletion keeps historical records");
        Discovery.LockEntry older = state.recentLocks.get(4);
        state.applied(new Discovery.LockEntry(older.cardId(), older.title(), older.topic(), 2000, older.image()),
                false);
        require(state.recentLocks.get(0).image().equals(older.image()) && !state.lastShown.containsKey(card.id),
                "reapplying a snapshot keeps it outside the deleted card's rotation history");
    }

    private static void checkIndependentModesAndCardEnablement() {
        Discovery.State state = fixture();
        state.lastCardId = "drum";
        state.randomBrowse = true;
        Set<String> previews = new HashSet<>();
        require(state.next(new Random(42)).id.equals("yellow"), "random browsing leaves lock rotation ordered");
        require(!state.nextPreview("drum", previews, new Random(42)).id.equals("yellow"),
                "preview uses its own random mode");
        state.randomRotation = true;
        state.randomBrowse = false;
        require(state.nextPreview("drum", previews, new Random(42)).id.equals("yellow"),
                "random lock rotation leaves preview ordered");
        require(!state.next(new Random(42)).id.equals("yellow"), "wallpaper uses its own random mode");

        state.shuffledLocks.add("yellow");
        state.setCardEnabled("yellow", false);
        require(state.cards.containsKey("yellow") && state.eligible("yellow") == null,
                "disabled cards remain in their topic and leave rotation");
        require(!state.shuffledLocks.contains("yellow"), "disabled cards leave the current shuffle round");
        require(state.nextPreview("drum", previews, new Random(42)).id.equals("timbre"),
                "preview skips disabled cards");
        for (int i = 0; i < 15; i++) {
            require(!state.next(new Random(i)).id.equals("yellow"), "random rotation skips disabled cards");
        }
        state.setCardEnabled("yellow", true);
        require(state.eligible("yellow") != null, "card can be re-enabled");
        state.setCardEnabled("yellow", false);
        state.removeCards("plants", Set.of("yellow"));
        require(!state.disabledCards.contains("yellow"), "card deletion removes the saved enablement state");
    }

    private static Discovery.LockEntry lock(Discovery.State state, Discovery.Card card, long time) {
        return new Discovery.LockEntry(card.id, card.title, state.topics.get(card.topicId).name, time,
                "lock-" + time + ".png");
    }

    private static void checkCardRemoval() {
        Discovery.State state = fixture();
        Discovery.Card drum = state.cards.get("drum");
        state.applied(lock(state, drum, 2000), false);
        Discovery.Card edited = new Discovery.Card(drum.id, drum.topicId, "Edited", drum.body,
                drum.task, drum.sourceIds, drum.photoUri, drum.audioUri, drum.palette);
        require(!drum.artwork().equals(edited.artwork()), "edited text refreshes the artwork");
        state.cards.put(drum.id, edited);
        require(state.lastShown.containsKey(drum.id), "editing keeps card history");
        Set<String> selected = new HashSet<>(Arrays.asList("drum", "yellow"));
        require(state.removeCards("birds", selected).size() == 1, "card deletion is scoped to a topic");
        require(state.cards.containsKey("yellow") && state.topics.containsKey("birds"), "other records are preserved");
        require(!state.lastShown.containsKey(drum.id) && state.lastCardId.isEmpty(), "deleted card leaves rotation");
        require(state.eligible(drum.id) == null, "deleted card cannot be applied");
    }

    private static void checkTopicRemoval() {
        Discovery.State state = fixture();
        Discovery.Card bird = state.cards.get("drum");
        String shared = "content://test/pdf";
        state.topics.get("birds").sources.add(new Discovery.Source("bird-pdf", "PDF", "", shared));
        state.topics.get("plants").sources.add(new Discovery.Source("plant-pdf", "PDF", "", shared));
        state.applied(lock(state, bird, 2000), false);
        Set<String> assets = new HashSet<>();
        state.removeTopic("birds", assets);
        require(!state.topics.containsKey("birds") && !state.cards.containsKey(bird.id), "topic and its cards removed");
        require(!state.lastShown.containsKey(bird.id) && state.lastCardId.isEmpty(), "deleted card leaves rotation");
        require(state.cards.containsKey("yellow") && state.usesUri(shared) && assets.contains(shared),
                "assets still linked elsewhere are retained");
    }

    private static void checkPdfPageLocators() {
        require(PdfLocator.page("PDF 第 23 页（从首页起数），印刷第 17 页") == 23,
                "file position has priority over print page");
        require(PdfLocator.page("印刷第 17 页；PDF 第 23–25 页") == 23,
                "range opens on the first physical page");
        require(PdfLocator.page("PDF p. 23, section 4") == 23
                && PdfLocator.page("PDF 页序：23") == 23, "accept alternate explicit file locators");
        require(PdfLocator.page("印刷第 17 页，第 3 章") == 0
                && PdfLocator.page("PDF 第 3 章") == 0
                && PdfLocator.page("PDF 第 0 页") == 0, "ignore ambiguous, non-page and invalid locators");
    }

    private static void checkSourceRemoval() {
        Discovery.State state = fixture();
        Discovery.Card original = state.cards.get("drum");
        state.topics.get("birds").sources.add(new Discovery.Source("chosen", "Chosen", ""));
        state.topics.get("birds").sources.add(new Discovery.Source("keep", "Keep", ""));
        Discovery.Citation deletedQuote = new Discovery.Citation("chosen", "Chapter 1");
        Discovery.Citation keptQuote = new Discovery.Citation("keep", "Chapter 2, §3");
        Discovery.References references = new Discovery.References(Arrays.asList(deletedQuote, keptQuote),
                Arrays.asList(new Discovery.Concept("Concept", "Explanation", Arrays.asList(deletedQuote, keptQuote))));
        state.cards.put(original.id, new Discovery.Card(original.id, original.topicId, original.title,
                original.body, original.task, Arrays.asList("cornell", "chosen", "keep"),
                original.photoUri, original.audioUri, original.palette, references));
        state.applied(lock(state, original, 2000), false);
        Set<String> deleted = new HashSet<>(Arrays.asList("cornell", "chosen", "noaa"));
        require(state.removeSources("birds", deleted).size() == 2, "only selected sources in the topic removed");
        Discovery.Card updated = state.cards.get(original.id);
        require(updated.sourceIds.equals(Arrays.asList("keep")), "only deleted source links detached");
        require(updated.references.citations().equals(Arrays.asList(keptQuote)), "deleted card citation detached");
        Discovery.Concept concept = updated.references.concepts().get(0);
        require(concept.explanation().equals("Explanation") && concept.citations().equals(Arrays.asList(keptQuote)),
                "source deletion preserves explanations and valid concept references");
        require(updated.title.equals(original.title) && updated.body.equals(original.body), "card preserved");
        require(state.eligible(original.id) == updated && state.lastAppliedAt == 2000, "rotation history preserved");
        require(state.sources(state.cards.get("yellow")).size() == 1, "another topic's links preserved");
        require(state.removeSources("birds", deleted).isEmpty(), "repeated deletion is harmless");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
