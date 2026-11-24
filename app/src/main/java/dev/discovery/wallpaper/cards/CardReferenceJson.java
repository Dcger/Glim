package dev.discovery.wallpaper.cards;

import dev.discovery.wallpaper.model.Discovery;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class CardReferenceJson {
    public static final int MAX_CITATIONS = 12;
    public static final int MAX_CONCEPTS = 10;
    public static final int MAX_LOCATOR = 320;
    public static final int MAX_TERM = 80;
    public static final int MAX_EXPLANATION = 900;

    public static Discovery.References read(JSONObject row) throws JSONException {
        List<Discovery.Concept> concepts = new ArrayList<>();
        JSONArray entries = array(row, "concepts", MAX_CONCEPTS);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.getJSONObject(i);
            concepts.add(new Discovery.Concept(requiredText(item, "term", MAX_TERM),
                    requiredText(item, "explanation", MAX_EXPLANATION), citations(item)));
        }
        return new Discovery.References(citations(row), concepts);
    }

    public static JSONObject write(JSONObject row, Discovery.References references) throws JSONException {
        if (!references.citations().isEmpty()) row.put("citations", citationsJson(references.citations()));
        if (!references.concepts().isEmpty()) {
            JSONArray concepts = new JSONArray();
            for (Discovery.Concept concept : references.concepts()) {
                JSONObject entry = new JSONObject().put("term", concept.term()).put("explanation", concept.explanation());
                if (!concept.citations().isEmpty()) entry.put("citations", citationsJson(concept.citations()));
                concepts.put(entry);
            }
            row.put("concepts", concepts);
        }
        return row;
    }

    public static List<String> readSourceIds(JSONObject row) throws JSONException {
        JSONArray values = array(row, "sourceIds", Integer.MAX_VALUE);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < values.length(); i++) ids.add(requiredText(values.get(i), "sourceIds", 160));
        return new ArrayList<>(ids);
    }

    private static List<Discovery.Citation> citations(JSONObject row) throws JSONException {
        LinkedHashSet<Discovery.Citation> citations = new LinkedHashSet<>();
        JSONArray entries = array(row, "citations", MAX_CITATIONS);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            citations.add(new Discovery.Citation(requiredText(entry, "sourceId", 160),
                    requiredText(entry, "locator", MAX_LOCATOR)));
        }
        return new ArrayList<>(citations);
    }

    private static JSONArray citationsJson(List<Discovery.Citation> values) throws JSONException {
        JSONArray entries = new JSONArray();
        for (Discovery.Citation entry : values) {
            entries.put(new JSONObject().put("sourceId", entry.sourceId()).put("locator", entry.locator()));
        }
        return entries;
    }

    private static JSONArray array(JSONObject row, String key, int limit) throws JSONException {
        if (!row.has(key)) return new JSONArray();
        JSONArray array = row.optJSONArray(key);
        if (array == null) throw new JSONException(key + " 应为数组。");
        if (array.length() > limit) throw new JSONException(key + " 最多 " + limit + " 项。");
        return array;
    }

    private static String requiredText(JSONObject row, String key, int limit) throws JSONException {
        return requiredText(row.opt(key), key, limit);
    }

    private static String requiredText(Object value, String key, int limit) throws JSONException {
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            throw new JSONException(key + " 应为非空文字。");
        }
        String trimmed = text.trim();
        if (trimmed.length() > limit) throw new JSONException(key + " 最多 " + limit + " 字。");
        return trimmed;
    }
}
