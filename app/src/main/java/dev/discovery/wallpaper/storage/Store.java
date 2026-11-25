package dev.discovery.wallpaper.storage;

import android.content.Context;
import android.util.AtomicFile;

import dev.discovery.wallpaper.cards.CardReferenceJson;
import dev.discovery.wallpaper.model.Discovery;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Store {
    public interface Edit {
        void apply(Discovery.State state) throws IOException;
    }

    public static synchronized Discovery.State read(Context context) throws IOException {
        byte[] bytes;
        try {
            bytes = file(context).readFully();
        } catch (FileNotFoundException absent) {
            return SampleCards.create(context);
        }
        try {
            return decode(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
        } catch (JSONException error) {
            throw new IOException("无法读取卡片数据", error);
        }
    }

    public static Discovery.State decode(JSONObject data) throws IOException {
        try {
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
                            source.optString("url"), source.optString("fileUri"), source.optString("pdfHash")));
                }
                state.topics.put(topic.id, topic);
            }
            JSONArray cards = data.getJSONArray("cards");
            for (int i = 0; i < cards.length(); i++) {
                JSONObject row = cards.getJSONObject(i);
                Discovery.Card card = new Discovery.Card(row.getString("id"), row.getString("topic"),
                        row.getString("title"), row.getString("body"), row.optString("task"),
                        sourceIds(row), row.optString("photo"), row.optString("audio"),
                        row.optString("palette", "forest"), CardReferenceJson.read(row));
                state.cards.put(card.id, card);
                state.setObserved(card.id, row.optBoolean("observed"));
                state.setFavorite(card.id, row.optBoolean("favorite"));
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
            state.nextRotationAt = data.optLong("nextRotationAt");
            state.status = data.optString("status");
            state.randomRotation = data.optBoolean("randomRotation");
            state.randomBrowse = data.optBoolean("randomBrowse", state.randomRotation);
            state.followingHintDismissed = data.optBoolean("followingHintDismissed");
            JSONArray disabled = data.optJSONArray("disabledCards");
            if (disabled != null) {
                for (int i = 0; i < disabled.length(); i++) state.setCardEnabled(disabled.optString(i), false);
            }
            JSONArray shuffled = data.optJSONArray("shuffledLocks");
            if (shuffled != null) {
                for (int i = 0; i < shuffled.length(); i++) {
                    String id = shuffled.optString(i);
                    if (state.eligible(id) != null) state.shuffledLocks.add(id);
                }
            }
            JSONArray recent = data.optJSONArray("recentLocks");
            if (recent == null) state.restoreLegacyLock();
            else for (int i = 0; i < Math.min(recent.length(), Discovery.RECENT_LIMIT); i++) {
                JSONObject row = recent.getJSONObject(i);
                state.recentLocks.add(new Discovery.LockEntry(row.getString("card"), row.getString("title"),
                        row.getString("topic"), row.getLong("at"), row.optString("image")));
            }
            return state;
        } catch (JSONException error) {
            throw new IOException("无法读取卡片数据", error);
        }
    }

    private static List<String> sourceIds(JSONObject row) throws JSONException {
        JSONArray array = row.optJSONArray("sourceIds");
        List<String> ids = new ArrayList<>();
        if (array == null) {
            String legacy = row.optString("source");
            if (!legacy.isEmpty()) ids.add(legacy);
        } else {
            for (int i = 0; i < array.length(); i++) ids.add(array.getString(i));
        }
        return ids;
    }

    public static synchronized void edit(Context context, Edit edit) throws IOException {
        Discovery.State state = read(context);
        edit.apply(state);
        save(context, state);
    }

    public static JSONObject snapshot(Discovery.State state) throws IOException {
        JSONObject data = new JSONObject();
        try {
            JSONArray topics = new JSONArray();
            for (Discovery.Topic topic : state.topics.values()) {
                JSONArray sources = new JSONArray();
                for (Discovery.Source source : topic.sources) {
                    sources.put(new JSONObject().put("id", source.id).put("label", source.label)
                            .put("url", source.url).put("fileUri", source.fileUri).put("pdfHash", source.pdfHash));
                }
                JSONObject row = new JSONObject().put("id", topic.id).put("name", topic.name)
                        .put("enabled", topic.enabled).put("context", topic.context).put("sources", sources);
                topics.put(row);
            }
            JSONArray cards = new JSONArray();
            for (Discovery.Card card : state.cards.values()) {
                JSONObject row = new JSONObject().put("id", card.id).put("topic", card.topicId)
                        .put("title", card.title).put("body", card.body).put("task", card.task)
                        .put("sourceIds", new JSONArray(card.sourceIds))
                        .put("photo", card.photoUri).put("audio", card.audioUri)
                        .put("observed", state.observed(card.id)).put("favorite", state.favorite(card.id))
                        .put("palette", card.palette);
                cards.put(CardReferenceJson.write(row, card.references));
            }
            JSONObject seen = new JSONObject();
            for (Map.Entry<String, Long> entry : state.lastShown.entrySet()) {
                seen.put(entry.getKey(), entry.getValue());
            }
            JSONArray recent = new JSONArray();
            for (Discovery.LockEntry entry : state.recentLocks) {
                recent.put(new JSONObject().put("card", entry.cardId()).put("title", entry.title())
                        .put("topic", entry.topic()).put("at", entry.appliedAt()).put("image", entry.image()));
            }
            data.put("topics", topics).put("cards", cards).put("seen", seen)
                    .put("automatic", state.automatic).put("minutes", state.minutes)
                    .put("randomRotation", state.randomRotation).put("randomBrowse", state.randomBrowse)
                    .put("followingHintDismissed", state.followingHintDismissed)
                    .put("disabledCards", new JSONArray(state.disabledCards))
                    .put("shuffledLocks", new JSONArray(state.shuffledLocks)).put("recentLocks", recent)
                    .put("last", state.lastCardId).put("sequence", state.sequence)
                    .put("appliedAt", state.lastAppliedAt).put("nextRotationAt", state.nextRotationAt)
                    .put("status", state.status);
        } catch (JSONException error) {
            throw new IOException("无法保存卡片数据", error);
        }
        return data;
    }

    public static synchronized void save(Context context, Discovery.State state) throws IOException {
        JSONObject data = snapshot(state);
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
