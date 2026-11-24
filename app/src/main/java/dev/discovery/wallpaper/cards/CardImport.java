package dev.discovery.wallpaper.cards;

import dev.discovery.wallpaper.model.Discovery;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class CardImport {
    static final int MAX_INPUT_LENGTH = 65_536;

    record Draft(String title, String body, String task, String image, String audio,
                 List<String> sourceIds, Discovery.References references, String cardId,
                 Set<String> supplied) {
        String reviewText() {
            return title + "\n" + body + (task.isEmpty() ? "" : "\n试一试 · " + task);
        }

        boolean inheritsSources() {
            return sourceIds == null;
        }

        boolean isUpdate() {
            return !cardId.isEmpty();
        }

        Discovery.Card existing(Discovery.State state, String topicId) {
            Discovery.Card card = state.cards.get(cardId);
            return card != null && card.topicId.equals(topicId) ? card : null;
        }

        Draft withExistingSources(Discovery.Card card) {
            return card == null || !inheritsSources() ? this
                    : new Draft(title, body, task, image, audio, card.sourceIds, card.references, cardId, supplied);
        }

        List<String> plainSources(List<String> batchSources) {
            return inheritsSources() ? batchSources : sourceIds;
        }

        Set<String> relatedSources(List<String> batchSources) {
            Set<String> ids = new LinkedHashSet<>(plainSources(batchSources));
            ids.addAll(references.sourceIds());
            return ids;
        }

        List<String> missingSources(Discovery.Topic topic, List<String> batchSources) {
            List<String> missing = new ArrayList<>();
            for (String id : relatedSources(batchSources)) {
                if (topic == null || topic.source(id) == null) missing.add(id);
            }
            return missing;
        }

        Draft remapSources(List<String> batchSources, Map<String, String> mapping) {
            if (mapping.isEmpty() || (isUpdate() && inheritsSources())) return this;
            List<String> original = plainSources(batchSources);
            boolean remapPlain = original.stream().anyMatch(mapping::containsKey);
            List<String> ids = sourceIds;
            if (remapPlain) {
                Set<String> remapped = new LinkedHashSet<>();
                for (String id : original) {
                    String replacement = mapping.getOrDefault(id, id);
                    if (!replacement.isEmpty()) remapped.add(replacement);
                }
                ids = new ArrayList<>(remapped);
            }
            List<Discovery.Concept> concepts = new ArrayList<>();
            for (Discovery.Concept concept : references.concepts()) {
                concepts.add(new Discovery.Concept(concept.term(), concept.explanation(),
                        remapCitations(concept.citations(), mapping)));
            }
            return new Draft(title, body, task, image, audio, ids,
                    new Discovery.References(remapCitations(references.citations(), mapping), concepts),
                    cardId, supplied);
        }

        private static List<Discovery.Citation> remapCitations(List<Discovery.Citation> citations,
                                                                Map<String, String> mapping) {
            Set<Discovery.Citation> remapped = new LinkedHashSet<>();
            for (Discovery.Citation citation : citations) {
                String replacement = mapping.getOrDefault(citation.sourceId(), citation.sourceId());
                if (!replacement.isEmpty()) remapped.add(new Discovery.Citation(replacement, citation.locator()));
            }
            return new ArrayList<>(remapped);
        }
    }

    private record Content(String title, String body) {}

    record Result(List<Discovery.Card> cards, int updated, Set<String> replacedMedia) {}

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
        List<Draft> drafts = new ArrayList<>();
        Set<String> updateIds = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            int number = i + 1;
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw new IOException("第 " + number + " 张卡片应为 JSON 对象。");
            String image = field(row, number, "image", 240, false);
            String audio = field(row, number, "audio", 240, false);
            String cardId = field(row, number, "cardId", 160, row.has("cardId"));
            if (!cardId.isEmpty() && !updateIds.add(cardId)) throw invalid(number, "cardId 与本批另一张更新重复。");
            Set<String> supplied = new HashSet<>();
            for (String key : new String[]{"task", "image", "audio"}) {
                if (row.has(key)) supplied.add(key);
            }
            if (!fromZip && (!image.isEmpty() || !audio.isEmpty())) {
                throw new IOException("带图片或音频的卡片请打包为 ZIP。");
            }
            try {
                boolean explicitSources = row.has("sourceIds") || row.has("citations") || row.has("concepts");
                drafts.add(new Draft(
                        field(row, number, "title", Discovery.MAX_TITLE_LENGTH, true),
                        field(row, number, "body", Discovery.MAX_BODY_LENGTH, true),
                        field(row, number, "task", Discovery.MAX_TASK_LENGTH, false),
                        image, audio, explicitSources ? CardReferenceJson.readSourceIds(row) : null,
                        CardReferenceJson.read(row), cardId, supplied));
            } catch (JSONException error) {
                throw invalid(number, error.getMessage());
            }
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
        for (Draft draft : drafts) {
            if (draft.isUpdate() && draft.existing(state, topicId) != null) {
                seen.add(new Content(draft.title(), draft.body()));
            }
        }
        List<Draft> unique = new ArrayList<>();
        for (Draft draft : drafts) {
            if (draft.isUpdate() || seen.add(new Content(draft.title(), draft.body()))) unique.add(draft);
        }
        return unique;
    }

    static Result add(Discovery.State state, String topicId, List<String> sourceIds,
                                    List<Draft> drafts) throws IOException {
        return add(state, topicId, sourceIds, drafts, path -> {
            throw new IOException("找不到媒体文件。");
        });
    }

    static Result add(Discovery.State state, String topicId, List<String> sourceIds,
                                    List<Draft> drafts, MediaResolver media) throws IOException {
        Discovery.Topic topic = state.topics.get(topicId);
        if (topic == null) throw new IOException("当前主题已不存在。");
        List<Draft> unique = unique(state, topicId, drafts);
        Set<String> updateIds = new HashSet<>();
        for (Draft draft : unique) {
            Discovery.Card old = draft.existing(state, topicId);
            if (draft.isUpdate() && (old == null || !updateIds.add(draft.cardId()))) {
                throw new IOException("「" + draft.title() + "」的更新目标不在此主题，或在本批重复出现。");
            }
            List<String> missing = draft.withExistingSources(old).missingSources(topic, sourceIds);
            if (!missing.isEmpty()) {
                throw new IOException("「" + draft.title() + "」有资料 ID 无法匹配：" + missing.get(0));
            }
        }
        List<Discovery.Card> saved = new ArrayList<>();
        Set<String> replaced = new LinkedHashSet<>();
        int updated = 0;
        for (Draft draft : unique) {
            Discovery.Card old = draft.existing(state, topicId);
            Draft content = draft.withExistingSources(old);
            String image = resolveMedia(draft.image(), "image", draft.supplied(), old == null ? "" : old.photoUri, media);
            String audio = resolveMedia(draft.audio(), "audio", draft.supplied(), old == null ? "" : old.audioUri, media);
            Discovery.Card card = new Discovery.Card(old == null ? UUID.randomUUID().toString() : old.id,
                    topicId, draft.title(), draft.body(),
                    old != null && !draft.supplied().contains("task") ? old.task : draft.task(),
                    content.plainSources(sourceIds), image, audio, old == null ? "forest" : old.palette,
                    content.references());
            if (old != null) {
                updated++;
                if (!old.photoUri.equals(image)) replaced.add(old.photoUri);
                if (!old.audioUri.equals(audio)) replaced.add(old.audioUri);
            }
            saved.add(card);
        }
        for (Discovery.Card card : saved) state.cards.put(card.id, card);
        replaced.remove("");
        return new Result(saved, updated, replaced);
    }

    private static String resolveMedia(String value, String key, Set<String> supplied, String previous,
                                       MediaResolver media) throws IOException {
        if (!supplied.contains(key)) return previous;
        return value.isEmpty() ? "" : media.resolve(value);
    }
}
