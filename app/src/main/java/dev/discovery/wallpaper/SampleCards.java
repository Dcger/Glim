package dev.discovery.wallpaper;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.stream.Collectors;

final class SampleCards {
    private static final String MUSIC = "music";
    private static final String LIGHT = "sample-light";
    private static final String ASSET_DIR = "samples/";
    private static final String ASSET_SCHEME = "asset";

    static boolean isBuiltIn(String topicId) {
        return MUSIC.equals(topicId) || LIGHT.equals(topicId)
                || "birds".equals(topicId) || "plants".equals(topicId);
    }

    static String assetName(Uri uri) {
        String path = uri.getPath();
        if (!ASSET_SCHEME.equals(uri.getScheme()) || path == null
                || !path.startsWith("/" + ASSET_DIR)) return null;
        return path.substring(1);
    }

    static Discovery.State create(Context context) throws IOException {
        Discovery.State state = new Discovery.State();
        state.topics.put(MUSIC, new Discovery.Topic(MUSIC, "听音乐", true));
        state.topics.put(LIGHT, new Discovery.Topic(LIGHT, "光与影", true));
        state.cards.put("timbre", new Discovery.Card("timbre", MUSIC, "同一个音，也有不同音色",
                "同一个音高可以有不同的泛音组合。各成分的强弱不同，会让同一个音听起来有不同质感。",
                "试听两个合成音，留意它们的音高和音色各有什么变化。", "", "", "sample", "blue"));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(ASSET_DIR + "light.json"), StandardCharsets.UTF_8))) {
            JSONArray cards = new JSONArray(reader.lines().collect(Collectors.joining("\n")));
            for (int i = 0; i < cards.length(); i++) {
                JSONObject row = cards.getJSONObject(i);
                String id = LIGHT + "-" + (i + 1);
                String image = ASSET_SCHEME + ":///" + ASSET_DIR + row.getString("image");
                state.cards.put(id, new Discovery.Card(id, LIGHT, row.getString("title"),
                        row.getString("body"), row.optString("task"), Collections.emptyList(),
                        image, "", "blue", CardReferenceJson.read(row)));
            }
        } catch (JSONException error) {
            throw new IOException("无法读取内置卡片", error);
        }
        return state;
    }
}
