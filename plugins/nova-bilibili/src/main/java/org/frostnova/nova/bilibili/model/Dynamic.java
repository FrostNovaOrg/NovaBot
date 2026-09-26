package org.frostnova.nova.bilibili.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 动态
 * <p>
 * 动态接口返回的结构随类型差异极大，且字段会随版本变动，因此仅提取稳定的少量字段作为强类型属性，
 * 其余内容保留原始 JSON，由绘制逻辑按需取用。
 */
@Getter
@Setter
@NoArgsConstructor
public class Dynamic {
    /**
     * 动态 ID
     */
    private String id;

    /**
     * 动态类型，例如 DYNAMIC_TYPE_AV、DYNAMIC_TYPE_DRAW、DYNAMIC_TYPE_FORWARD
     */
    private String type;

    /**
     * 动态是否可见
     */
    private Boolean visible;

    /**
     * 动态基础信息
     */
    private JSONObject basic;

    /**
     * 动态各模块内容
     */
    private JSONObject modules;

    /**
     * 被转发的原动态，仅转发动态存在
     */
    private Dynamic origin;

    /**
     * 判断是否为转发动态
     * @return 是否为转发动态
     */
    public boolean isForward() {
        return "DYNAMIC_TYPE_FORWARD".equals(type);
    }

    /**
     * 获取动态发布者的 uid
     * @return 发布者 uid
     */
    public Optional<Long> getAuthorUid() {
        return author().map(author -> author.getLong("mid"));
    }

    /**
     * 获取动态发布者昵称
     * @return 发布者昵称
     */
    public Optional<String> getAuthorName() {
        return author().map(author -> author.getString("name"));
    }

    /**
     * 获取动态发布者头像地址
     * @return 发布者头像地址
     */
    public Optional<String> getAuthorFace() {
        return author().map(author -> author.getString("face"));
    }

    /**
     * 获取动态发布时间
     * @return 发布时间
     */
    public Optional<Instant> getPublishTime() {
        return author()
                .map(author -> author.getLong("pub_ts"))
                .map(Instant::ofEpochSecond);
    }

    /**
     * 获取动态跳转地址
     * @return 跳转地址
     */
    public String getUrl() {
        return "https://t.bilibili.com/" + id;
    }

    /**
     * 取出这条动态身上给人看的文字：正文两处，再加上标题
     * <p>
     * 正文有两处：{@code desc.text}，以及图文、纯文字动态的 {@code major.opus.summary.text}
     * （这种动态的 {@code desc} 经常是 null，正文整段在 summary 里）。两处都要比，
     * 只比其中一处，词写在另一处的动态就挡不住。
     * 标题在 {@code major.opus.title}（没有时为 null）、视频的 archive、专栏的 article。
     * 直播推荐的标题不在 live_rcmd 自己身上：{@code content} 是一段 JSON 字符串，
     * 解析后取 {@code live_play_info.title}；解析失败或没有这一键就当没有标题。
     * <p>
     * 画图不把两处正文都画上，取法见 {@link #linesToPaint()}。封面见 {@link #liveCover()}。
     * 转发动态的原文不在本方法里：原文是 {@link #getOrigin()} 指着的另一条动态，
     * 要不要连它一起看由调用方定。
     * @return 正文与标题，没有文字时为空表
     */
    public List<String> texts() {
        Pieces pieces = readPieces();
        List<String> texts = new ArrayList<>();
        addText(texts, pieces.desc);
        addText(texts, pieces.summary);
        addText(texts, pieces.archiveTitle);
        addText(texts, pieces.articleTitle);
        addText(texts, pieces.opusTitle);
        addText(texts, pieces.liveTitle);
        return texts;
    }

    /**
     * 推送图上要画的文字，从上到下
     * <p>
     * 图文和纯文字（{@code MAJOR_TYPE_OPUS}）：有标题就先画标题，再画正文。
     * 正文优先 {@code desc.text}，它是空的才用 {@code opus.summary.text}，
     * 已经带 desc 的动态，画出来的正文与原先一致。
     * 直播推荐画解析出的标题。视频、专栏和其余类型维持原样：
     * {@code desc.text} 有字就只画它，否则画各自的标题。
     * @return 要画的各行，没有文字时为空表
     */
    public List<String> linesToPaint() {
        Pieces pieces = readPieces();
        List<String> lines = new ArrayList<>();
        if ("MAJOR_TYPE_OPUS".equals(pieces.majorType)) {
            addText(lines, pieces.opusTitle);
            addText(lines, firstNonBlank(pieces.desc, pieces.summary));
            return lines;
        }
        if ("MAJOR_TYPE_LIVE_RCMD".equals(pieces.majorType)) {
            addText(lines, pieces.liveTitle);
            if (pieces.desc != null && !pieces.desc.isBlank() && !pieces.desc.equals(pieces.liveTitle)) {
                addText(lines, pieces.desc);
            }
            return lines;
        }
        if (pieces.desc != null && !pieces.desc.isBlank()) {
            addText(lines, pieces.desc);
        } else {
            addText(lines, pieces.archiveTitle);
            if (lines.isEmpty()) {
                addText(lines, pieces.articleTitle);
            }
        }
        return lines;
    }

    /**
     * 直播推荐的封面地址，取 content 里的 {@code live_play_info.cover}
     * @return 封面地址，解析失败或没有这一键时为空
     */
    public Optional<String> liveCover() {
        String cover = readPieces().liveCover;
        if (cover == null || cover.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(cover);
    }

    private Pieces readPieces() {
        Pieces pieces = new Pieces();
        JSONObject moduleDynamic = modules == null ? null : modules.getJSONObject("module_dynamic");
        if (moduleDynamic == null) {
            return pieces;
        }

        JSONObject desc = moduleDynamic.getJSONObject("desc");
        if (desc != null) {
            pieces.desc = desc.getString("text");
        }

        JSONObject major = moduleDynamic.getJSONObject("major");
        if (major == null) {
            return pieces;
        }
        pieces.majorType = major.getString("type");

        JSONObject opus = major.getJSONObject("opus");
        if (opus != null) {
            pieces.opusTitle = opus.getString("title");
            JSONObject summary = opus.getJSONObject("summary");
            if (summary != null) {
                pieces.summary = summary.getString("text");
            }
        }

        JSONObject archive = major.getJSONObject("archive");
        if (archive != null) {
            pieces.archiveTitle = archive.getString("title");
        }
        JSONObject article = major.getJSONObject("article");
        if (article != null) {
            pieces.articleTitle = article.getString("title");
        }
        fillLive(pieces, major.getJSONObject("live_rcmd"));
        return pieces;
    }

    /**
     * content 不是 JSON、缺 live_play_info、或里面没有标题和封面时，这两项留空，不往外抛
     */
    private static void fillLive(Pieces pieces, JSONObject liveRcmd) {
        if (liveRcmd == null) {
            return;
        }
        Object raw = liveRcmd.get("content");
        if (!(raw instanceof String content) || content.isBlank()) {
            return;
        }
        try {
            JSONObject parsed = JSON.parseObject(content);
            if (parsed == null) {
                return;
            }
            JSONObject info = parsed.getJSONObject("live_play_info");
            if (info == null) {
                return;
            }
            pieces.liveTitle = info.getString("title");
            pieces.liveCover = info.getString("cover");
        } catch (RuntimeException ignored) {
            // 解析失败当没有标题、没有封面
        }
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private static void addText(List<String> texts, String text) {
        if (text != null && !text.isBlank()) {
            texts.add(text);
        }
    }

    /**
     * 一条动态上要拿来画、也要拿来比屏蔽词的几段文字，只在这里拆一次
     */
    private static final class Pieces {
        private String desc;

        private String summary;

        private String opusTitle;

        private String archiveTitle;

        private String articleTitle;

        private String liveTitle;

        private String liveCover;

        private String majorType;
    }

    /**
     * 取出作者模块
     * @return 作者模块
     */
    private Optional<JSONObject> author() {
        return Optional.ofNullable(modules).map(m -> m.getJSONObject("module_author"));
    }
}
