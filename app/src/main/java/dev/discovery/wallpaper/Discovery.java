package dev.discovery.wallpaper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

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

        Source(String id, String label, String url) {
            this.id = id;
            this.label = label;
            this.url = url;
        }
    }

    static final class Card {
        final String id;
        final String topicId;
        final String title;
        final String body;
        final String task;
        final String sourceId;
        final String photoUri;
        final String audioUri;
        final String palette;

        Card(String id, String topicId, String title, String body, String task,
             String sourceId, String photoUri, String audioUri, String palette) {
            this.id = id;
            this.topicId = topicId;
            this.title = title;
            this.body = body;
            this.task = task;
            this.sourceId = sourceId;
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

        Source source(Card card) {
            Topic topic = topics.get(card.topicId);
            if (topic != null) {
                for (Source source : topic.sources) {
                    if (source.id.equals(card.sourceId)) return source;
                }
            }
            return null;
        }
    }
}
