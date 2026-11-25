package dev.discovery.wallpaper.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

public final class Discovery {
    public static final int DEFAULT_MINUTES = 30;
    public static final int[] INTERVALS = {15, DEFAULT_MINUTES, 60, 120};
    public static final int MAX_TITLE_LENGTH = 42;
    public static final int MAX_BODY_LENGTH = 155;
    public static final int MAX_TASK_LENGTH = 70;
    public static final int RECENT_LIMIT = 5;

    public static final class Topic {
        public final String id;
        public String name;
        public boolean enabled;
        public String context = "";
        public final ArrayList<Source> sources = new ArrayList<>();

        public Topic(String id, String name, boolean enabled) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
        }

        public Source source(String id) {
            for (Source entry : sources) {
                if (entry.id.equals(id)) return entry;
            }
            return null;
        }
    }

    public static final class Source {
        public final String id;
        public final String label;
        public final String url;
        public final String fileUri;
        public final String pdfHash;

        public Source(String id, String label, String url) {
            this(id, label, url, "");
        }

        public Source(String id, String label, String url, String fileUri) {
            this(id, label, url, fileUri, "");
        }

        public Source(String id, String label, String url, String fileUri, String pdfHash) {
            this.id = id;
            this.label = label;
            this.url = url;
            this.fileUri = fileUri;
            this.pdfHash = pdfHash;
        }
    }

    public static final class Card {
        public final String id;
        public final String topicId;
        public final String title;
        public final String body;
        public final String task;
        public final List<String> sourceIds;
        public final String photoUri;
        public final String audioUri;
        public final String palette;
        public final References references;

        public Card(String id, String topicId, String title, String body, String task,
             String sourceId, String photoUri, String audioUri, String palette) {
            this(id, topicId, title, body, task, sourceId.isEmpty()
                            ? Collections.emptyList() : Collections.singletonList(sourceId),
                    photoUri, audioUri, palette);
        }

        public Card(String id, String topicId, String title, String body, String task,
             List<String> sourceIds, String photoUri, String audioUri, String palette) {
            this(id, topicId, title, body, task, sourceIds, photoUri, audioUri, palette, References.EMPTY);
        }

        public Card(String id, String topicId, String title, String body, String task,
             List<String> sourceIds, String photoUri, String audioUri, String palette, References references) {
            this.id = id;
            this.topicId = topicId;
            this.title = title;
            this.body = body;
            this.task = task;
            this.sourceIds = Collections.unmodifiableList(new ArrayList<>(sourceIds));
            this.photoUri = photoUri;
            this.audioUri = audioUri;
            this.palette = palette;
            this.references = references;
        }

        public Artwork artwork() {
            return new Artwork(title, body, task, photoUri, palette);
        }
    }

    public record Citation(String sourceId, String locator) {}

    public record Concept(String term, String explanation, List<Citation> citations) {
        public Concept {
            citations = Collections.unmodifiableList(new ArrayList<>(citations));
        }
    }

    public record References(List<Citation> citations, List<Concept> concepts) {
        public static final References EMPTY = new References(Collections.emptyList(), Collections.emptyList());

        public References {
            citations = Collections.unmodifiableList(new ArrayList<>(citations));
            concepts = Collections.unmodifiableList(new ArrayList<>(concepts));
        }

        public Set<String> sourceIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (Citation citation : citations) ids.add(citation.sourceId());
            for (Concept concept : concepts) {
                for (Citation citation : concept.citations()) ids.add(citation.sourceId());
            }
            return ids;
        }

        public References withoutSources(Set<String> removed) {
            List<Concept> remaining = new ArrayList<>();
            for (Concept concept : concepts) {
                remaining.add(new Concept(concept.term(), concept.explanation(), keep(concept.citations(), removed)));
            }
            return new References(keep(citations, removed), remaining);
        }

        private static List<Citation> keep(List<Citation> citations, Set<String> removed) {
            List<Citation> remaining = new ArrayList<>(citations);
            remaining.removeIf(citation -> removed.contains(citation.sourceId()));
            return remaining;
        }
    }

    public record Artwork(String title, String body, String task, String photoUri, String palette) {}

    public record LockEntry(String cardId, String title, String topic, long appliedAt, String image) {}

    public static final class State {
        public final Map<String, Topic> topics = new LinkedHashMap<>();
        public final Map<String, Card> cards = new LinkedHashMap<>();
        public final Map<String, Long> lastShown = new LinkedHashMap<>();
        public final List<LockEntry> recentLocks = new ArrayList<>();
        public final Set<String> shuffledLocks = new LinkedHashSet<>();
        public final Set<String> disabledCards = new LinkedHashSet<>();
        private final Set<String> observedCards = new LinkedHashSet<>();
        private final Set<String> favoriteCards = new LinkedHashSet<>();
        public boolean followingHintDismissed;
        public String lastCardId = "";
        public long lastAppliedAt;
        public long nextRotationAt;
        public long sequence;
        public String status = "";
        public boolean automatic;
        public boolean randomRotation;
        public boolean randomBrowse;
        public int minutes = DEFAULT_MINUTES;

        public Card eligible(String cardId) {
            Card card = cards.get(cardId);
            Topic topic = card == null ? null : topics.get(card.topicId);
            return topic != null && topic.enabled && cardEnabled(cardId) ? card : null;
        }

        public boolean cardEnabled(String cardId) {
            return !disabledCards.contains(cardId);
        }

        public boolean observed(String cardId) {
            return observedCards.contains(cardId);
        }

        public boolean favorite(String cardId) {
            return favoriteCards.contains(cardId);
        }

        public boolean hasFollowedCards() {
            return !observedCards.isEmpty() || !favoriteCards.isEmpty();
        }

        public void setObserved(String cardId, boolean value) {
            setCardStatus(observedCards, cardId, value);
        }

        public void setFavorite(String cardId, boolean value) {
            setCardStatus(favoriteCards, cardId, value);
        }

        private void setCardStatus(Set<String> status, String cardId, boolean value) {
            if (!value) status.remove(cardId);
            else if (cards.containsKey(cardId)) status.add(cardId);
        }

        public void setCardEnabled(String cardId, boolean enabled) {
            if (!cards.containsKey(cardId)) return;
            if (enabled) {
                disabledCards.remove(cardId);
            } else {
                disabledCards.add(cardId);
                shuffledLocks.remove(cardId);
            }
        }

        public Card next(Random random) {
            return randomRotation ? shuffled(lastCardId, shuffledLocks, random) : browse(lastCardId);
        }

        public Card nextPreview(String afterId, Set<String> shown, Random random) {
            return randomBrowse ? shuffled(afterId, shown, random) : browse(afterId);
        }

        private Card shuffled(String afterId, Set<String> shown, Random random) {
            List<Card> available = new ArrayList<>();
            List<Card> unseen = new ArrayList<>();
            for (Card card : cards.values()) {
                if (card.id.equals(afterId) || eligible(card.id) == null) continue;
                available.add(card);
                if (!shown.contains(card.id)) unseen.add(card);
            }
            List<Card> choices = unseen.isEmpty() ? available : unseen;
            return choices.isEmpty() ? eligible(afterId) : choices.get(random.nextInt(choices.size()));
        }

        public void rememberShuffle(Set<String> shown, String previous, String selected) {
            shown.removeIf(id -> eligible(id) == null);
            if (selected.equals(previous)) {
                if (eligible(selected) != null) shown.add(selected);
                return;
            }
            if (shown.contains(selected)) shown.clear();
            else if (eligible(previous) != null) shown.add(previous);
            if (eligible(selected) != null) shown.add(selected);
        }

        public Card browse(String afterId) {
            Card first = null;
            boolean returnNext = false;
            for (Card card : cards.values()) {
                if (eligible(card.id) != null) {
                    if (first == null) first = card;
                    if (returnNext) return card;
                }
                if (card.id.equals(afterId)) returnNext = true;
            }
            return first;
        }

        public long rotationIntervalMs() {
            return Math.max(INTERVALS[0], minutes) * 60_000L;
        }

        public void configureRotation(boolean enabled, int interval, long now) {
            if (automatic == enabled && minutes == interval) return;
            automatic = enabled;
            minutes = interval;
            nextRotationAt = enabled ? now + rotationIntervalMs() : 0;
        }

        public boolean rotationDue(long now) {
            return automatic && nextRotationAt > 0 && now >= nextRotationAt;
        }

        public void applied(LockEntry entry, boolean rotated) {
            String id = entry.cardId();
            if (randomRotation) {
                if (rotated) rememberShuffle(shuffledLocks, lastCardId, id);
                else if (eligible(id) != null) shuffledLocks.add(id);
            }
            sequence++;
            if (cards.containsKey(id)) lastShown.put(id, sequence);
            lastCardId = id;
            lastAppliedAt = entry.appliedAt();
            if (automatic) nextRotationAt = lastAppliedAt + rotationIntervalMs();
            recentLocks.add(0, entry);
            if (recentLocks.size() > RECENT_LIMIT) recentLocks.remove(RECENT_LIMIT);
            status = "";
        }

        public void restoreLegacyLock() {
            Card card = cards.get(lastCardId);
            Topic topic = card == null ? null : topics.get(card.topicId);
            if (topic != null && recentLocks.isEmpty() && lastAppliedAt > 0) {
                recentLocks.add(new LockEntry(card.id, card.title, topic.name, lastAppliedAt, ""));
            }
        }

        public List<Source> sources(Card card) {
            List<Source> sources = new ArrayList<>();
            Topic topic = topics.get(card.topicId);
            if (topic != null) {
                for (Source source : topic.sources) {
                    if (card.sourceIds.contains(source.id)) sources.add(source);
                }
            }
            return sources;
        }

        public List<Source> removeSources(String topicId, Set<String> ids) {
            List<Source> removed = new ArrayList<>();
            Topic topic = topics.get(topicId);
            if (topic == null || ids.isEmpty()) return removed;

            topic.sources.removeIf(source -> {
                if (!ids.contains(source.id)) return false;
                removed.add(source);
                return true;
            });
            cards.replaceAll((id, card) -> {
                if (!topicId.equals(card.topicId) || (Collections.disjoint(card.sourceIds, ids)
                        && Collections.disjoint(card.references.sourceIds(), ids))) {
                    return card;
                }
                List<String> remaining = new ArrayList<>(card.sourceIds);
                remaining.removeAll(ids);
                return new Card(card.id, card.topicId, card.title, card.body, card.task, remaining,
                        card.photoUri, card.audioUri, card.palette, card.references.withoutSources(ids));
            });
            return removed;
        }

        public void removeTopic(String topicId, Set<String> releasedUris) {
            Topic removed = topics.remove(topicId);
            if (removed == null) return;
            for (Source source : removed.sources) releasedUris.add(source.fileUri);
            for (Card card : removeCards(topicId, card -> true)) {
                releasedUris.add(card.photoUri);
                releasedUris.add(card.audioUri);
            }
        }

        public List<Card> removeCards(String topicId, Set<String> ids) {
            return removeCards(topicId, card -> ids.contains(card.id));
        }

        private List<Card> removeCards(String topicId, Predicate<Card> selected) {
            List<Card> removed = new ArrayList<>();
            cards.values().removeIf(card -> {
                if (!card.topicId.equals(topicId) || !selected.test(card)) return false;
                removed.add(card);
                lastShown.remove(card.id);
                shuffledLocks.remove(card.id);
                disabledCards.remove(card.id);
                observedCards.remove(card.id);
                favoriteCards.remove(card.id);
                if (card.id.equals(lastCardId)) {
                    lastCardId = "";
                    lastAppliedAt = 0;
                    status = "";
                }
                return true;
            });
            return removed;
        }

        public boolean usesUri(String uri) {
            for (Card card : cards.values()) {
                if (uri.equals(card.photoUri) || uri.equals(card.audioUri)) return true;
            }
            for (Topic topic : topics.values()) {
                for (Source source : topic.sources) {
                    if (uri.equals(source.fileUri)) return true;
                }
            }
            return false;
        }
    }
}
