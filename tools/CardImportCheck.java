package dev.discovery.wallpaper.cards;

import dev.discovery.wallpaper.agent.AgentShare;
import dev.discovery.wallpaper.model.Discovery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CardImportCheck {
    public static void main(String[] args) throws Exception {
        Discovery.State state = new Discovery.State();
        Discovery.Topic topic = new Discovery.Topic("optics", "Light", true);
        topic.sources.add(new Discovery.Source("a", "Book A", ""));
        topic.sources.add(new Discovery.Source("b", "Book B", ""));
        state.topics.put(topic.id, topic);
        String json = """
                {"cards":[
                  {"title":"Across chapters","body":"First observation","citations":[
                    {"sourceId":"a","locator":"Chapter 1, p. 5"},
                    {"sourceId":"a","locator":"Chapter 8, Figure 2"}],
                    "concepts":[
                      {"term":"A","explanation":"An explanation","citations":[{"sourceId":"a","locator":"§1.2"}]},
                      {"term":"B","explanation":"Another explanation","citations":[{"sourceId":"b","locator":"§3"}]}]},
                  {"title":"Only one source","body":"Second observation","sourceIds":["b"]},
                  {"title":"No sources","body":"Third observation","sourceIds":[]},
                  {"title":"Legacy","body":"Fourth observation"}]}
                """;
        List<String> batch = List.of("a", "b");
        List<CardImport.Draft> drafts = CardImport.parse(json, false);
        require(drafts.get(0).relatedSources(batch).equals(Set.of("a", "b")), "concept references count");
        require(drafts.get(1).relatedSources(batch).equals(Set.of("b")), "uses only card-specific source");
        require(drafts.get(2).relatedSources(batch).isEmpty(), "explicit empty does not inherit");
        require(drafts.get(3).relatedSources(batch).equals(Set.of("a", "b")), "legacy batch fallback");
        List<Discovery.Card> cards = CardImport.add(state, topic.id, batch, drafts).cards();
        Discovery.Card crossChapter = cards.get(0);
        require(crossChapter.sourceIds.isEmpty() && crossChapter.references.citations().size() == 2,
                "supports multiple locations in one source");
        require(crossChapter.references.concepts().size() == 2, "keeps both concept explanations");
        JSONObject saved = CardReferenceJson.write(new JSONObject(), crossChapter.references);
        require(CardReferenceJson.read(new JSONObject(saved.toString())).equals(crossChapter.references),
                "reference JSON round-trip");
        String unknown = """
                {"cards":[{"title":"Broken","body":"Source unavailable","concepts":[
                  {"term":"A","explanation":"Explanation","citations":[{"sourceId":"foreign","locator":"§3"}]}]}]}
                """;
        List<CardImport.Draft> bad = CardImport.parse(unknown, true);
        require(bad.get(0).missingSources(topic, batch).equals(List.of("foreign")), "detects unresolved concept source");
        try {
            CardImport.add(state, topic.id, batch, bad);
            throw new AssertionError("unknown reference accepted");
        } catch (IOException error) {
            require(error.getMessage().contains("foreign"), "error shows the unresolved ID");
        }
        require(state.cards.size() == 4, "failed import preserves prior cards");
        try {
            CardImport.parse(unknown.replace("\"locator\":\"§3\"", "\"locator\":7"), false);
            throw new AssertionError("invalid locator accepted");
        } catch (IOException error) {
            require(error.getMessage().contains("locator"), "invalid field identified");
        }
        checkPrompt(topic, batch);
        checkSourceMatching(topic, batch);
        checkUpdates(topic, batch);
        checkBatchOverThirty();
        System.out.println("CardImportCheck passed");
    }

    private static void checkSourceMatching(Discovery.Topic topic, List<String> batch) throws IOException {
        String input = """
                {"cards":[
                  {"title":"First","body":"Observation","sourceIds":["foreign","missing"],"citations":[
                    {"sourceId":"foreign","locator":"Chapter 2, p. 17"},
                    {"sourceId":"foreign","locator":"Figure 9"}],
                    "concepts":[{"term":"Optics","explanation":"First explanation","citations":[
                      {"sourceId":"missing","locator":"Section 2"},
                      {"sourceId":"foreign","locator":"Section 3"}]}]},
                  {"title":"Second","body":"Another observation","citations":[
                    {"sourceId":"foreign","locator":"Chapter 5"}]},
                  {"title":"Existing","body":"Existing reference","sourceIds":["b"]}]}
                """;
        List<CardImport.Draft> drafts = CardImport.parse(input, false);
        require(drafts.get(0).missingSources(topic, batch).equals(List.of("foreign", "missing")),
                "detect all unresolved IDs, including concept references");
        require(drafts.get(1).missingSources(topic, batch).equals(List.of("foreign")),
                "same foreign ID is recognized on another card");
        CardImport.Draft partial = drafts.get(0).remapSources(batch, Map.of("foreign", "a"));
        require(partial.missingSources(topic, batch).equals(List.of("missing")), "unprocessed ID remains unresolved");
        Map<String, String> mapping = Map.of("foreign", "a", "missing", "");
        List<CardImport.Draft> repaired = drafts.stream().map(draft -> draft.remapSources(batch, mapping)).toList();
        require(repaired.stream().allMatch(draft -> draft.missingSources(topic, batch).isEmpty()),
                "a batch can be imported once its references are resolved");
        Discovery.State store = new Discovery.State();
        store.topics.put(topic.id, topic);
        List<Discovery.Card> cards = CardImport.add(store, topic.id, batch, repaired).cards();
        require(cards.get(0).sourceIds.equals(List.of("a")), "mapped plain link saved");
        require(cards.get(0).references.citations().equals(List.of(
                new Discovery.Citation("a", "Chapter 2, p. 17"), new Discovery.Citation("a", "Figure 9"))),
                "matched locations are retained");
        Discovery.Concept concept = cards.get(0).references.concepts().get(0);
        require(concept.explanation().equals("First explanation")
                && concept.citations().equals(List.of(new Discovery.Citation("a", "Section 3"))),
                "explicitly detached citation is removed while concept and matched citation are retained");
        require(cards.get(1).references.citations().get(0).sourceId().equals("a"),
                "one mapping applies across cards");
        require(cards.get(2).sourceIds.equals(List.of("b")), "already valid links are kept");
        CardImport.Draft old = CardImport.parse("{\"cards\":[{\"title\":\"Old\",\"body\":\"Legacy\"}]}", false).get(0);
        CardImport.Draft detached = old.remapSources(List.of("missing"), mapping);
        require(!detached.inheritsSources() && detached.relatedSources(List.of("missing")).isEmpty(),
                "detaching a stale legacy batch source does not reintroduce it");
    }

    private static void checkPrompt(Discovery.Topic topic, List<String> batch) throws IOException {
        String prompt = AgentShare.prompt(topic, topic.sources, false, "", List.of(), false, 1080, 2400);
        require(prompt.contains("每项必填 term 和 explanation"), "prompt names the required concept fields");
        require(prompt.contains("引文时写在 locator 内"), "locator retains searchable quotes");
        require(prompt.contains("PDF 第 23 页（从文件第一页起数）"), "prompt describes physical PDF pages");
        require(prompt.contains("长（高）2400 像素、宽 1080 像素")
                && prompt.contains("按普通手机壁纸构图") && prompt.contains("图片下方不要放文字")
                && prompt.contains("半透明渐变蒙版") && !prompt.contains("高约 1600"),
                "prompt uses full-screen wallpaper dimensions and reserves the text overlay area");
        CardImport.Draft withSource = parsePromptExample(prompt);
        require(withSource.relatedSources(batch).equals(Set.of("a")), "example uses a real selected source only");
        require(withSource.references().citations().size() == 1, "example contains a card-level citation");
        Discovery.Concept concept = withSource.references().concepts().get(0);
        require(!concept.term().isEmpty() && !concept.explanation().isEmpty()
                && concept.citations().size() == 1, "concept example survives the actual importer");
        require(prompt.indexOf("优先裁切或拼接") >= 0
                && prompt.indexOf("再绘制原创示意图") > prompt.indexOf("优先裁切或拼接"),
                "prompt prioritizes source artwork over original illustrations");
        String noSources = AgentShare.prompt(topic, List.of(), false, "", List.of(), false, 1080, 2400);
        require(noSources.contains("每项必填 term 和 explanation"), "schema stays visible without sources");
        CardImport.Draft withoutSource = parsePromptExample(noSources);
        require(withoutSource.relatedSources(batch).isEmpty() && !withoutSource.inheritsSources(),
                "no-source example has no references and cannot inherit the batch");
        require(withoutSource.references().concepts().get(0).citations().isEmpty(),
                "no-source concept shows an empty citation array");
    }

    private static void checkUpdates(Discovery.Topic topic, List<String> batch) throws IOException {
        Discovery.State state = new Discovery.State();
        state.topics.put(topic.id, topic);
        var citation = new Discovery.Citation("b", "Chapter VIII");
        var refs = new Discovery.References(List.of(citation), List.of());
        var original = new Discovery.Card("stable-card", topic.id, "Existing", "Before", "Original task",
                List.of("b"), "file:///old.png", "content://audio/1", "night", refs);
        state.cards.put(original.id, original);
        state.setCardEnabled(original.id, false);
        state.lastShown.put(original.id, 5L);
        String prompt = AgentShare.prompt(topic, List.of(topic.source("a")), false, "", List.of(original), true, 1080, 2400);
        require(prompt.contains("\"cardId\":\"stable-card\"") && prompt.contains("Chapter VIII")
                && prompt.contains("原卡引用的其他资料（本次未附文件）"), "prompt includes selected card context and old IDs");

        String referenceOnly = AgentShare.prompt(topic, List.of(), false, "", List.of(original), false, 1080, 2400);
        require(referenceOnly.contains("仅供写作参考和去重") && referenceOnly.contains("Existing")
                && !referenceOnly.contains("stable-card") && !referenceOnly.contains("已有卡片与更新规则"),
                "reference-only prompts omit update rules and stable card IDs");

        String text = """
                {"cards":[{"cardId":"stable-card","title":"Edited","body":"After"}]}
                """;
        var draft = CardImport.parse(text.trim(), true).get(0);
        require(draft.remapSources(batch, Map.of("b", "")).withExistingSources(original)
                .references().equals(refs), "update with omitted references preserves existing references");
        var result = CardImport.add(state, topic.id, batch, List.of(draft));
        var saved = result.cards().get(0);
        require(result.updated() == 1 && state.cards.size() == 1 && saved.id.equals(original.id),
                "updates stable ID instead of adding a card");
        require(!state.cardEnabled(saved.id) && state.lastShown.get(saved.id) == 5L,
                "preserves enablement and wallpaper history");
        require(saved.references.equals(refs) && saved.sourceIds.equals(List.of("b"))
                && saved.audioUri.equals(original.audioUri) && saved.photoUri.equals(original.photoUri)
                && saved.task.equals(original.task)
                && saved.palette.equals(original.palette),
                "omitted fields and palette are retained");

        String replacement = """
                {"cards":[{"cardId":"stable-card","title":"Edited","body":"After","task":"",
                  "image":"assets/new.png","audio":"","sourceIds":[],
                  "concepts":[{"term":"Optics","explanation":"Explanation"}]}]}
                """;
        var replaced = CardImport.add(state, topic.id, batch, CardImport.parse(replacement.trim(), true),
                path -> "file:///new.png");
        saved = replaced.cards().get(0);
        require(replaced.updated() == 1 && saved.photoUri.equals("file:///new.png")
                && saved.audioUri.isEmpty() && saved.task.isEmpty(), "ZIP can replace image and clear optional values");
        require(replaced.replacedMedia().equals(Set.of(original.photoUri, original.audioUri))
                && saved.references.citations().isEmpty() && saved.references.concepts().get(0).term().equals("Optics"),
                "returns obsolete media and applies explicit reference replacement");
        try {
            CardImport.add(state, topic.id, batch,
                    CardImport.parse(text.replace("stable-card", "unknown").trim(), true));
            throw new AssertionError("unknown update target accepted");
        } catch (IOException error) {
            require(error.getMessage().contains("更新目标"), "reports unknown target without creating a new card");
        }
        require(state.cards.size() == 1 && state.cards.get("stable-card") == saved,
                "bad update leaves data intact");
        String duplicate = "{\"cards\":[{\"cardId\":\"stable-card\",\"title\":\"One\",\"body\":\"One\"},"
                + "{\"cardId\":\"stable-card\",\"title\":\"Two\",\"body\":\"Two\"}]}";
        try {
            CardImport.parse(duplicate, true);
            throw new AssertionError("duplicate update target accepted");
        } catch (IOException error) {
            require(error.getMessage().contains("cardId"), "identifies duplicate card IDs");
        }
    }

    private static CardImport.Draft parsePromptExample(String prompt) throws IOException {
        String json = prompt.lines().filter(line -> line.startsWith("{\"cards\":")).findFirst()
                .orElseThrow(() -> new AssertionError("missing JSON example"));
        List<CardImport.Draft> drafts = CardImport.parse(json, false);
        require(drafts.equals(CardImport.parse(json, true)), "prompt example also works inside a ZIP");
        return drafts.get(0);
    }

    private static void checkBatchOverThirty() throws Exception {
        JSONArray cards = new JSONArray();
        for (int i = 1; i <= 45; i++) {
            cards.put(new JSONObject().put("title", "Card " + i).put("body", "Observation " + i)
                    .put("image", "images/" + i + ".jpg"));
        }
        String json = new JSONObject().put("cards", cards).toString();
        var drafts = CardImport.parse(json, true);
        Discovery.State state = new Discovery.State();
        Discovery.Topic topic = new Discovery.Topic("bulk", "Bulk", true);
        state.topics.put(topic.id, topic);
        var result = CardImport.add(state, topic.id, List.of(), drafts, path -> "file:///" + path);
        require(result.cards().size() == 45 && state.cards.size() == 45,
                "ZIP imports more than 30 illustrated cards");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
