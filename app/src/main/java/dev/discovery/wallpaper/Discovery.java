package dev.discovery.wallpaper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

final class Discovery {
    static final int DEFAULT_MINUTES = 30;
    static final int[] INTERVALS = {15, DEFAULT_MINUTES, 60, 120};
    static final int MAX_TITLE_LENGTH = 42;
    static final int MAX_BODY_LENGTH = 155;
    static final int MAX_TASK_LENGTH = 70;
    static final int RECENT_LIMIT = 5;

    static final class Topic {
        final String id;
        String name;
        boolean enabled;
        String context = "";
        final ArrayList<Source> sources = new ArrayList<>();

        Topic(String id, String name, boolean enabled) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
        }

        Source source(String id) {
            for (Source entry : sources) {
                if (entry.id.equals(id)) return entry;
            }
            return null;
        }
    }

    static final class Source {
        final String id;
        final String label;
        final String url;
        final String fileUri;
        final String pdfHash;

        Source(String id, String label, String url) {
            this(id, label, url, "");
        }

        Source(String id, String label, String url, String fileUri) {
            this(id, label, url, fileUri, "");
        }

        Source(String id, String label, String url, String fileUri, String pdfHash) {
            this.id = id;
            this.label = label;
            this.url = url;
            this.fileUri = fileUri;
            this.pdfHash = pdfHash;
        }
    }

    static final class Card {
        final String id;
        final String topicId;
        final String title;
        final String body;
        final String task;
        final List<String> sourceIds;
        final String photoUri;
        final String audioUri;
        final String palette;
        final References references;

        Card(String id, String topicId, String title, String body, String task,
             String sourceId, String photoUri, String audioUri, String palette) {
            this(id, topicId, title, body, task, sourceId.isEmpty()
                            ? Collections.emptyList() : Collections.singletonList(sourceId),
                    photoUri, audioUri, palette);
        }

        Card(String id, String topicId, String title, String body, String task,
             List<String> sourceIds, String photoUri, String audioUri, String palette) {
            this(id, topicId, title, body, task, sourceIds, photoUri, audioUri, palette, References.EMPTY);
        }

        Card(String id, String topicId, String title, String body, String task,
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

        Artwork artwork() {
            return new Artwork(title, body, task, photoUri, palette);
        }
    }

    record Citation(String sourceId, String locator) {}

    record Concept(String term, String explanation, List<Citation> citations) {
        Concept {
            citations = Collections.unmodifiableList(new ArrayList<>(citations));
        }
    }

    record References(List<Citation> citations, List<Concept> concepts) {
        static final References EMPTY = new References(Collections.emptyList(), Collections.emptyList());

        References {
            citations = Collections.unmodifiableList(new ArrayList<>(citations));
            concepts = Collections.unmodifiableList(new ArrayList<>(concepts));
        }

        Set<String> sourceIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (Citation citation : citations) ids.add(citation.sourceId());
            for (Concept concept : concepts) {
                for (Citation citation : concept.citations()) ids.add(citation.sourceId());
            }
            return ids;
        }

        References withoutSources(Set<String> removed) {
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

    record Artwork(String title, String body, String task, String photoUri, String palette) {}

    record LockEntry(String cardId, String title, String topic, long appliedAt, String image) {}

    static final class State {
        final Map<String, Topic> topics = new LinkedHashMap<>();
        final Map<String, Card> cards = new LinkedHashMap<>();
        final Map<String, Long> lastShown = new LinkedHashMap<>();
        final List<LockEntry> recentLocks = new ArrayList<>();
        final Set<String> shuffledLocks = new LinkedHashSet<>();
        final Set<String> disabledCards = new LinkedHashSet<>();
        String lastCardId = "";
        long lastAppliedAt;
        long nextRotationAt;
        long sequence;
        String status = "";
        boolean automatic;
        boolean randomRotation;
        boolean randomBrowse;
        int minutes = DEFAULT_MINUTES;

        Card eligible(String cardId) {
            Card card = cards.get(cardId);
            Topic topic = card == null ? null : topics.get(card.topicId);
            return topic != null && topic.enabled && cardEnabled(cardId) ? card : null;
        }

        boolean cardEnabled(String cardId) {
            return !disabledCards.contains(cardId);
        }

        void setCardEnabled(String cardId, boolean enabled) {
            if (!cards.containsKey(cardId)) return;
            if (enabled) {
                disabledCards.remove(cardId);
            } else {
                disabledCards.add(cardId);
                shuffledLocks.remove(cardId);
            }
        }

        Card next(Random random) {
            return randomRotation ? shuffled(lastCardId, shuffledLocks, random) : browse(lastCardId);
        }

        Card nextPreview(String afterId, Set<String> shown, Random random) {
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

        void rememberShuffle(Set<String> shown, String previous, String selected) {
            shown.removeIf(id -> eligible(id) == null);
            if (selected.equals(previous)) {
                if (eligible(selected) != null) shown.add(selected);
                return;
            }
            if (shown.contains(selected)) shown.clear();
            else if (eligible(previous) != null) shown.add(previous);
            if (eligible(selected) != null) shown.add(selected);
        }

        Card browse(String afterId) {
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

        long rotationIntervalMs() {
            return Math.max(INTERVALS[0], minutes) * 60_000L;
        }

        void configureRotation(boolean enabled, int interval, long now) {
            if (automatic == enabled && minutes == interval) return;
            automatic = enabled;
            minutes = interval;
            nextRotationAt = enabled ? now + rotationIntervalMs() : 0;
        }

        boolean rotationDue(long now) {
            return automatic && nextRotationAt > 0 && now >= nextRotationAt;
        }

        void applied(LockEntry entry, boolean rotated) {
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

        void restoreLegacyLock() {
            Card card = cards.get(lastCardId);
            Topic topic = card == null ? null : topics.get(card.topicId);
            if (topic != null && recentLocks.isEmpty() && lastAppliedAt > 0) {
                recentLocks.add(new LockEntry(card.id, card.title, topic.name, lastAppliedAt, ""));
            }
        }

        List<Source> sources(Card card) {
            List<Source> sources = new ArrayList<>();
            Topic topic = topics.get(card.topicId);
            if (topic != null) {
                for (Source source : topic.sources) {
                    if (card.sourceIds.contains(source.id)) sources.add(source);
                }
            }
            return sources;
        }

        List<Source> removeSources(String topicId, Set<String> ids) {
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

        void removeTopic(String topicId, Set<String> releasedUris) {
            Topic removed = topics.remove(topicId);
            if (removed == null) return;
            for (Source source : removed.sources) releasedUris.add(source.fileUri);
            for (Card card : removeCards(topicId, card -> true)) {
                releasedUris.add(card.photoUri);
                releasedUris.add(card.audioUri);
            }
        }

        List<Card> removeCards(String topicId, Set<String> ids) {
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
                if (card.id.equals(lastCardId)) {
                    lastCardId = "";
                    lastAppliedAt = 0;
                    status = "";
                }
                return true;
            });
            return removed;
        }

        boolean usesUri(String uri) {
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
