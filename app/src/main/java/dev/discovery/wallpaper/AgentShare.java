package dev.discovery.wallpaper;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

final class AgentShare {
    static final String PDF = "application/pdf";
    static final int MAX_PROMPT_BYTES = 200_000;

    record Target(String label, String packageName) {}

    static final class MissingPdfs extends IOException {
        final List<Discovery.Source> sources;

        MissingPdfs(List<Discovery.Source> sources) {
            super("部分 PDF 无法读取。");
            this.sources = sources;
        }
    }

    private static final Target[] AGENTS = {
            new Target("ChatGPT", "com.openai.chatgpt"),
            new Target("DeepSeek", "com.deepseek.chat"),
            new Target("Gemini", "com.google.android.apps.bard"),
            new Target("Claude", "com.anthropic.claude"),
            new Target("Perplexity", "ai.perplexity.app.android")
    };

    static List<Target> targets(Context context, Intent share) {
        List<Target> available = new ArrayList<>();
        PackageManager manager = context.getPackageManager();
        for (Target target : AGENTS) {
            Intent addressed = new Intent(share).setPackage(target.packageName());
            if (manager.resolveActivity(addressed, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                available.add(target);
            }
        }
        return available;
    }

    static String filename(Context context, Uri uri) {
        String name = displayName(context, uri);
        return name.isEmpty() ? "本地 PDF" : name;
    }

    static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getColumnCount() > 0) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (RuntimeException ignored) {
            // Some providers omit a display name.
        }
        return "";
    }

    static String prompt(Discovery.Topic topic, List<Discovery.Source> sources,
                         boolean includeTopicNotes, String customNotes, List<Discovery.Card> existing,
                         boolean updateExisting, int width, int height) {
        StringBuilder text = new StringBuilder()
                .append("为「").append(topic.name).append("」制作适合手机锁屏的发现卡片。")
                .append("每张写一件能马上观察或尝试的发现，事实以所选资料支持的内容为准。")
                .append("\n\n导入格式")
                .append("\n- 根对象的 cards 是非空数组。内容较多时可分多个 ZIP 或 JSON 文件。")
                .append("\n- 每张卡必填 title（标题，最多 ").append(Discovery.MAX_TITLE_LENGTH)
                .append(" 字）和 body（正文，最多 ").append(Discovery.MAX_BODY_LENGTH)
                .append(" 字），均须为非空字符串。task 是可选的小任务，最多 ")
                .append(Discovery.MAX_TASK_LENGTH).append(" 字。")
                .append("\n- 新增卡片输出 sourceIds、citations、concepts 三个数组，无对应内容时填 []。")
                .append("sourceIds 只列本卡实际用到、暂时缺少具体定位的资料 ID。")
                .append("\n- 每条 citations 必填两个非空字符串，sourceId 是下方资料清单中的应用 ID，")
                .append("locator 是原文位置（最多 ").append(CardReferenceJson.MAX_LOCATOR).append(" 字）。")
                .append("可写章节、小节、页码、图号或网页锚点。需要附可搜索的短引文时写在 locator 内。")
                .append("引用 PDF 时用「PDF 第 23 页（从文件第一页起数）」这样的写法标明文件页序，方便跳页。")
                .append("可同时注明印刷页码。跨章节概括请为各处原文分别列引用。")
                .append("\n- concepts 数组最多 ").append(CardReferenceJson.MAX_CONCEPTS)
                .append(" 项。每项必填 term 和 explanation，均须为非空字符串。")
                .append("term 是概念名，字段名固定为 term（最多 ").append(CardReferenceJson.MAX_TERM)
                .append(" 字）。explanation 用自己的话解释概念及其与本卡观察的关系（最多 ")
                .append(CardReferenceJson.MAX_EXPLANATION).append(" 字）。每个概念单独提供 citations，使用相同的 sourceId 和 locator 结构。")
                .append("正文和每个概念各最多 ").append(CardReferenceJson.MAX_CITATIONS).append(" 条引用。")
                .append("\n- 逐字复制资料清单中的 ID。清单列出的 ID 才能用于 sourceIds 或 sourceId。")
                .append("凡是无法核对的原文定位，只填写能够确认的部分。")
                .append(sources.isEmpty() ? "\n- 本次未选资料，新增卡片的 sourceIds 和各处 citations 均填 []。" : "")
                .append("\n\n配图和交付")
                .append("\n优先提供可下载的 ZIP，根目录放 UTF-8 编码的 cards.json。")
                .append("配图优先裁切或拼接所选资料里与观察直接相关的原图、照片或图表。保留辨认现象所需的细节，")
                .append("并在正文引用的 locator 中注明原图的页码、图号或小节。资料没有合适图片时再绘制原创示意图。")
                .append("\n每张配图交付一张铺满手机锁屏的竖图，长（高）").append(height)
                .append(" 像素、宽 ").append(width).append(" 像素。")
                .append("按普通手机壁纸构图。请交付完整、无透明区域的原图。")
                .append("图片下方不要放文字，App 会在这里叠加半透明渐变蒙版和卡片文字。")
                .append("\n把实际提供的媒体加入 ZIP，image（JPEG、PNG、WebP）或 audio（MP3、M4A、WAV）填写包内相对路径。")
                .append("无法提供 ZIP 时，直接输出可粘贴的 JSON 对象，省略 image 和 audio，保留原文定位及概念解释。")
                .append("\n\nJSON 示例（仅展示结构，正式输出时填入自己的内容与已核对的定位）\n")
                .append(promptExample(sources));
        if (includeTopicNotes && !topic.context.isEmpty()) text.append("\n主题背景：").append(topic.context);
        if (!customNotes.isEmpty()) text.append("\n本次补充说明：").append(customNotes);
        if (!sources.isEmpty()) text.append("\n\n所选资料（本次可查阅）：");
        LinkedHashMap<String, Integer> attachments = new LinkedHashMap<>();
        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
        for (Discovery.Source source : sources) {
            selectedIds.add(source.id);
            text.append("\n- ID ").append(source.id).append(" · ").append(source.label);
            if (!source.url.isEmpty()) text.append(" — ").append(source.url);
            if (!source.fileUri.isEmpty()) {
                int number = attachments.computeIfAbsent(source.fileUri, ignored -> attachments.size() + 1);
                text.append("（PDF 附件 ").append(number).append("）");
            }
        }
        if (!existing.isEmpty()) appendExisting(text, topic, existing, selectedIds, updateExisting);
        return text.toString();
    }

    private static void appendExisting(StringBuilder text, Discovery.Topic topic, List<Discovery.Card> existing,
                                       LinkedHashSet<String> selectedIds, boolean updateExisting) {
        if (updateExisting) {
            text.append("\n\n已有卡片与更新规则")
                .append("\n- 请同时检查下方选中的卡片并更新需要修改的内容。返回的 cards 中原样填写该卡的 cardId。")
                    .append("每个 cardId 最多写一次。只返回实际要更新的卡片。全新的卡片省略 cardId。")
                    .append("\n- 更新时仍填写完整的 title 和 body。省略 task、image、audio 可保留原值，空字符串会清除相应内容。")
                    .append("如要保留原引用与概念，省略 sourceIds、citations 和 concepts 三项。")
                    .append("如修改其中任一项，请输出完整的新引用和概念数组，把要保留的条目也复制过去。")
                    .append("下方旧引用可以原样保留。新增定位以本次实际查阅的资料为准。");
        } else {
            text.append("\n\n已有卡片")
                    .append("\n- 下方卡片仅供写作参考和去重。请生成新卡片，返回的卡片均省略 cardId。");
        }
        text.append("\n已有卡片的图片和音频以有无标记展示，本次附件只包含所选资料的 PDF。");
        LinkedHashSet<String> cited = new LinkedHashSet<>();
        JSONArray rows = new JSONArray();
        try {
            for (Discovery.Card card : existing) {
                cited.addAll(card.sourceIds);
                cited.addAll(card.references.sourceIds());
                JSONObject row = new JSONObject();
                if (updateExisting) row.put("cardId", card.id);
                rows.put(CardReferenceJson.write(row.put("title", card.title).put("body", card.body).put("task", card.task)
                        .put("sourceIds", new JSONArray(card.sourceIds))
                        .put("hasImage", !card.photoUri.isEmpty()).put("hasAudio", !card.audioUri.isEmpty()),
                        card.references));
            }
        } catch (JSONException error) {
            throw new IllegalStateException("Could not serialize selected cards", error);
        }
        cited.removeAll(selectedIds);
        if (!cited.isEmpty()) {
            text.append("\n原卡引用的其他资料（本次未附文件）：");
            for (String id : cited) {
                Discovery.Source source = topic.source(id);
                if (source != null) text.append("\n- ID ").append(source.id).append(" · ").append(source.label);
            }
        }
        text.append("\n已有卡片 JSON（作为输入参考，返回时遵守上面的 cards 格式）：\n").append(rows);
    }

    private static String promptExample(List<Discovery.Source> sources) {
        List<Discovery.Citation> citations = Collections.emptyList();
        List<Discovery.Citation> conceptCitations = Collections.emptyList();
        if (!sources.isEmpty()) {
            String sourceId = sources.get(0).id;
            citations = Collections.singletonList(new Discovery.Citation(sourceId, "实际观察所在的小节、页码或图号"));
            conceptCitations = Collections.singletonList(new Discovery.Citation(sourceId, "概念定义所在的小节或页码"));
        }
        Discovery.Concept concept = new Discovery.Concept("概念名", "解释此概念如何帮助理解观察。", conceptCitations);
        try {
            JSONObject card = CardReferenceJson.write(new JSONObject().put("title", "发现标题")
                    .put("body", "写一条可以观察的发现。").put("task", "写一个可以马上尝试的小任务。")
                    .put("sourceIds", new JSONArray()).put("citations", new JSONArray()),
                    new Discovery.References(citations, Collections.singletonList(concept)));
            if (conceptCitations.isEmpty()) {
                card.getJSONArray("concepts").getJSONObject(0).put("citations", new JSONArray());
            }
            return new JSONObject().put("cards", new JSONArray().put(card)).toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Could not serialize card prompt example", error);
        }
    }

    static Intent intent(Context context, String prompt, List<Discovery.Source> sources) throws IOException {
        ArrayList<Uri> files = checkedPdfs(context, sources);
        boolean multiple = files.size() > 1;
        Intent share = new Intent(multiple ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND)
                .setType(files.isEmpty() ? "text/plain" : PDF)
                .putExtra(Intent.EXTRA_TEXT, prompt);
        if (files.isEmpty()) return share;
        if (multiple) share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
        else share.putExtra(Intent.EXTRA_STREAM, files.get(0));
        ClipData clip = new ClipData("发现卡片资料", new String[]{PDF, "text/plain"},
                new ClipData.Item(prompt, null, files.get(0)));
        for (int i = 1; i < files.size(); i++) clip.addItem(new ClipData.Item(files.get(i)));
        share.setClipData(clip);
        return share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static ArrayList<Uri> checkedPdfs(Context context, List<Discovery.Source> sources) throws MissingPdfs {
        LinkedHashSet<Uri> unique = new LinkedHashSet<>();
        List<Discovery.Source> missing = new ArrayList<>();
        for (Discovery.Source source : sources) {
            if (source.fileUri.isEmpty()) continue;
            try {
                Uri uri = RestoredPdfProvider.forReader(context, Uri.parse(source.fileUri));
                if (unique.contains(uri)) continue;
                try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                    if (stream == null || stream.read() < 0) throw new IOException();
                }
                unique.add(uri);
            } catch (IOException | RuntimeException error) {
                missing.add(source);
            }
        }
        if (!missing.isEmpty()) throw new MissingPdfs(missing);
        return new ArrayList<>(unique);
    }
}
