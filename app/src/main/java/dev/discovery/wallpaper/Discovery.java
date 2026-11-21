package dev.discovery.wallpaper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Discovery {
    static final int DEFAULT_MINUTES = 30;
    static final int[] INTERVALS = {15, DEFAULT_MINUTES, 60, 120};
    static final int MAX_TITLE_LENGTH = 42;
    static final int MAX_BODY_LENGTH = 155;
    static final int MAX_TASK_LENGTH = 70;

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

        Card(String id, String topicId, String title, String body, String task,
             String sourceId, String photoUri, String audioUri, String palette) {
            this(id, topicId, title, body, task, sourceId.isEmpty()
                            ? Collections.emptyList() : Collections.singletonList(sourceId),
                    photoUri, audioUri, palette);
        }

        Card(String id, String topicId, String title, String body, String task,
             List<String> sourceIds, String photoUri, String audioUri, String palette) {
            this.id = id;
            this.topicId = topicId;
            this.title = title;
            this.body = body;
            this.task = task;
            this.sourceIds = Collections.unmodifiableList(new ArrayList<>(sourceIds));
            this.photoUri = photoUri;
            this.audioUri = audioUri;
            this.palette = palette;
        }
    }

    static final class State {
        final Map<String, Topic> topics = new LinkedHashMap<>();
        final Map<String, Card> cards = new LinkedHashMap<>();
        final Map<String, Long> lastShown = new LinkedHashMap<>();
        String lastCardId = "";
        long lastAppliedAt;
        long sequence;
        String status = "";
        boolean automatic;
        int minutes = DEFAULT_MINUTES;

        Card eligible(String cardId) {
            Card card = cards.get(cardId);
            Topic topic = card == null ? null : topics.get(card.topicId);
            return topic != null && topic.enabled ? card : null;
        }

        Card next(String afterId) {
            Card next = null;
            long oldest = Long.MAX_VALUE;
            for (Card card : cards.values()) {
                if (eligible(card.id) == null || card.id.equals(afterId)) continue;
                long rank = lastShown.getOrDefault(card.id, 0L);
                if (next == null || rank < oldest) {
                    next = card;
                    oldest = rank;
                }
            }
            return next == null ? eligible(afterId) : next;
        }

        Card browse(String afterId) {
            Card first = null;
            boolean returnNext = false;
            for (Card card : cards.values()) {
                if (eligible(card.id) == null) continue;
                if (first == null) first = card;
                if (returnNext) return card;
                if (card.id.equals(afterId)) returnNext = true;
            }
            return first;
        }

        void applied(Card card, long timestamp) {
            lastShown.put(card.id, ++sequence);
            lastCardId = card.id;
            lastAppliedAt = timestamp;
            status = "";
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
                if (!topicId.equals(card.topicId) || Collections.disjoint(card.sourceIds, ids)) {
                    return card;
                }
                List<String> remaining = new ArrayList<>(card.sourceIds);
                remaining.removeAll(ids);
                return new Card(card.id, card.topicId, card.title, card.body, card.task, remaining,
                        card.photoUri, card.audioUri, card.palette);
            });
            return removed;
        }

        void removeTopic(String topicId, Set<String> releasedUris) {
            Topic removed = topics.remove(topicId);
            if (removed == null) return;
            for (Source source : removed.sources) releasedUris.add(source.fileUri);
            cards.values().removeIf(card -> {
                if (!card.topicId.equals(topicId)) return false;
                releasedUris.add(card.photoUri);
                releasedUris.add(card.audioUri);
                lastShown.remove(card.id);
                if (card.id.equals(lastCardId)) {
                    lastCardId = "";
                    lastAppliedAt = 0;
                    status = "";
                }
                return true;
            });
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
