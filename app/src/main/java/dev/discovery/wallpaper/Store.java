package dev.discovery.wallpaper;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Store {
    interface Edit {
        void apply(Discovery.State state) throws IOException;
    }

    static synchronized Discovery.State read(Context context) throws IOException {
        byte[] bytes;
        try {
            bytes = file(context).readFully();
        } catch (FileNotFoundException absent) {
            return SampleCards.create();
        }
        try {
            JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Discovery.State state = new Discovery.State();
            JSONArray topics = data.getJSONArray("topics");
            for (int i = 0; i < topics.length(); i++) {
                JSONObject row = topics.getJSONObject(i);
                Discovery.Topic topic = new Discovery.Topic(row.getString("id"), row.getString("name"),
                        row.getBoolean("enabled"));
                topic.context = row.optString("context");
                JSONArray sources = row.getJSONArray("sources");
                for (int j = 0; j < sources.length(); j++) {
                    JSONObject source = sources.getJSONObject(j);
                    topic.sources.add(new Discovery.Source(source.getString("id"), source.getString("label"),
                            source.optString("url")));
                }
                state.topics.put(topic.id, topic);
            }
            JSONArray cards = data.getJSONArray("cards");
            for (int i = 0; i < cards.length(); i++) {
                JSONObject row = cards.getJSONObject(i);
                Discovery.Card card = new Discovery.Card(row.getString("id"), row.getString("topic"),
                        row.getString("title"), row.getString("body"), row.optString("task"),
                        row.optString("source"), row.optString("photo"), row.optString("audio"),
                        row.optString("palette", "forest"));
                state.cards.put(card.id, card);
            }
            JSONObject seen = data.getJSONObject("seen");
            for (java.util.Iterator<String> keys = seen.keys(); keys.hasNext();) {
                String key = keys.next();
                state.lastShown.put(key, seen.getLong(key));
            }
            state.automatic = data.optBoolean("automatic");
            state.minutes = data.optInt("minutes", Discovery.DEFAULT_MINUTES);
            state.lastCardId = data.optString("last");
            state.sequence = data.optLong("sequence");
            state.lastAppliedAt = data.optLong("appliedAt");
            state.status = data.optString("status");
            return state;
        } catch (JSONException error) {
            throw new IOException("无法读取卡片数据", error);
        }
    }

    static synchronized void edit(Context context, Edit edit) throws IOException {
        Discovery.State state = read(context);
        edit.apply(state);
        save(context, state);
    }

    private static void save(Context context, Discovery.State state) throws IOException {
        JSONObject data = new JSONObject();
        try {
            JSONArray topics = new JSONArray();
            for (Discovery.Topic topic : state.topics.values()) {
                JSONArray sources = new JSONArray();
                for (Discovery.Source source : topic.sources) {
                    sources.put(new JSONObject().put("id", source.id).put("label", source.label)
                            .put("url", source.url));
                }
                topics.put(new JSONObject().put("id", topic.id).put("name", topic.name)
                        .put("enabled", topic.enabled).put("context", topic.context).put("sources", sources));
            }
            JSONArray cards = new JSONArray();
            for (Discovery.Card card : state.cards.values()) {
                cards.put(new JSONObject().put("id", card.id).put("topic", card.topicId)
                        .put("title", card.title).put("body", card.body).put("task", card.task)
                        .put("source", card.sourceId).put("photo", card.photoUri).put("audio", card.audioUri)
                        .put("palette", card.palette));
            }
            JSONObject seen = new JSONObject();
            for (Map.Entry<String, Long> entry : state.lastShown.entrySet()) {
                seen.put(entry.getKey(), entry.getValue());
            }
            data.put("topics", topics).put("cards", cards).put("seen", seen)
                    .put("automatic", state.automatic).put("minutes", state.minutes)
                    .put("last", state.lastCardId).put("sequence", state.sequence)
                    .put("appliedAt", state.lastAppliedAt).put("status", state.status);
        } catch (JSONException error) {
            throw new IOException("无法保存卡片数据", error);
        }
        AtomicFile target = file(context);
        FileOutputStream stream = target.startWrite();
        try {
            stream.write(data.toString().getBytes(StandardCharsets.UTF_8));
            target.finishWrite(stream);
        } catch (IOException error) {
            target.failWrite(stream);
            throw error;
        }
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "discovery.json"));
    }
}
