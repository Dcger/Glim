package dev.discovery.wallpaper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class CardImport {
    static final int MAX_CARDS = 30;
    static final int MAX_INPUT_LENGTH = 65_536;

    record Draft(String title, String body, String task, String image, String audio) {
        String reviewText() {
            return title + "\n" + body + (task.isEmpty() ? "" : "\n试一试 · " + task);
        }
    }

    private record Content(String title, String body) {}

    interface MediaResolver {
        String resolve(String path) throws IOException;
    }

    static List<Draft> readJson(InputStream input, boolean fromZip) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (output.size() + size > MAX_INPUT_LENGTH * 4 + 3) throw new IOException("JSON 文件内容过长。");
            output.write(buffer, 0, size);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("JSON 文件请使用 UTF-8 编码。", error);
        }
        if (text.startsWith("\ufeff")) text = text.substring(1);
        return parse(text.trim(), fromZip);
    }

    static List<Draft> parse(String text, boolean fromZip) throws IOException {
        if (text.isEmpty()) throw new IOException("请粘贴卡片 JSON。");
        if (text.length() > MAX_INPUT_LENGTH) throw new IOException("内容过长，请分批粘贴。");
        JSONArray rows;
        try {
            JSONTokener reader = new JSONTokener(text);
            Object value = reader.nextValue();
            if (!(value instanceof JSONObject object) || reader.nextClean() != 0) {
                throw new IOException("请粘贴包含 cards 数组的 JSON 对象。");
            }
            rows = object.optJSONArray("cards");
        } catch (JSONException error) {
            throw new IOException("JSON 格式有误。请复制完整 JSON 内容。", error);
        }
        if (rows == null || rows.length() == 0) throw new IOException("cards 数组至少需要一张卡片。");
        if (rows.length() > MAX_CARDS) throw new IOException("每批最多导入 " + MAX_CARDS + " 张卡片。");
        List<Draft> drafts = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            int number = i + 1;
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw new IOException("第 " + number + " 张卡片应为 JSON 对象。");
            String image = field(row, number, "image", 240, false);
            String audio = field(row, number, "audio", 240, false);
            if (!fromZip && (!image.isEmpty() || !audio.isEmpty())) {
                throw new IOException("带图片或音频的卡片请打包为 ZIP。");
            }
            drafts.add(new Draft(
                    field(row, number, "title", Discovery.MAX_TITLE_LENGTH, true),
                    field(row, number, "body", Discovery.MAX_BODY_LENGTH, true),
                    field(row, number, "task", Discovery.MAX_TASK_LENGTH, false),
                    image, audio));
        }
        return drafts;
    }

    private static String field(JSONObject row, int number, String name, int limit, boolean required)
            throws IOException {
        Object value = row.opt(name);
        if (value == null && !required) return "";
        if (!(value instanceof String text)) throw invalid(number, name + " 应为文字。");
        String trimmed = text.trim();
        if (required && trimmed.isEmpty()) throw invalid(number, name + " 不能为空。");
        if (trimmed.length() > limit) throw invalid(number, name + " 最多 " + limit + " 个字符。");
        return trimmed;
    }

    private static IOException invalid(int number, String detail) {
        return new IOException("第 " + number + " 张卡片的" + detail);
    }

    static List<Draft> unique(Discovery.State state, String topicId, List<Draft> drafts) {
        Set<Content> seen = new HashSet<>();
        for (Discovery.Card card : state.cards.values()) {
            if (card.topicId.equals(topicId)) seen.add(new Content(card.title, card.body));
        }
        List<Draft> unique = new ArrayList<>();
        for (Draft draft : drafts) {
            if (seen.add(new Content(draft.title(), draft.body()))) unique.add(draft);
        }
        return unique;
    }

    static List<Discovery.Card> add(Discovery.State state, String topicId, List<String> sourceIds,
                                    List<Draft> drafts) throws IOException {
        return add(state, topicId, sourceIds, drafts, path -> {
            throw new IOException("找不到媒体文件。");
        });
    }

    static List<Discovery.Card> add(Discovery.State state, String topicId, List<String> sourceIds,
                                    List<Draft> drafts, MediaResolver media) throws IOException {
        Discovery.Topic topic = state.topics.get(topicId);
        if (topic == null) throw new IOException("当前主题已不存在。");
        for (String id : sourceIds) {
            if (topic.sources.stream().noneMatch(source -> source.id.equals(id))) {
                throw new IOException("选中的资料已不存在。请重新选择。");
            }
        }
        List<Discovery.Card> added = new ArrayList<>();
        for (Draft draft : unique(state, topicId, drafts)) {
            String id = UUID.randomUUID().toString();
            Discovery.Card card = new Discovery.Card(id, topicId, draft.title(), draft.body(),
                    draft.task(), sourceIds,
                    draft.image().isEmpty() ? "" : media.resolve(draft.image()),
                    draft.audio().isEmpty() ? "" : media.resolve(draft.audio()), "forest");
            state.cards.put(id, card);
            added.add(card);
        }
        return added;
    }
}
