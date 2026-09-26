package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 动态绘制测试
 * <p>
 * 绘制过程不发起任何网络请求：图片获取由桩实现返回固定的纯色位图，
 * 因此测试关注的是版面能否在各类动态结构下正常生成，而非图片内容本身。
 */
@DisplayName("动态图片绘制")
class BilibiliDynamicPainterTest {
    private static final String OPUS_BODY = "图文动态的整段正文在这里，标题那一栏是空的。";

    private static final String WORD_BODY = "纯文字动态只有这一段，不在 desc 里。";

    private static final String ORIGIN_BODY = "被转发的纯文字原动态正文。";

    private static final String FORWARD_NOTE = "转发评语写在外层。";

    private static final String OPUS_TITLE = "只是截断的标题";

    private static final String FULL_BODY = "截断标题后面还有整段正文，图上不该只剩标题。";

    private static final String LIVE_TITLE = "今晚直播的标题";

    private static final String LIVE_COVER = "https://cover.example/live.jpg";

    private BilibiliDynamicPainter painter;

    /**
     * 画进正文的那几行。头像旁的昵称、时间、版权行不走这条，不记。
     */
    private final List<String> drawnText = new ArrayList<>();

    /**
     * 取过的图片地址。头像也在里面，封面有没有画看地址里有没有封面。
     */
    private final List<String> imageUrls = new ArrayList<>();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        drawnText.clear();
        imageUrls.clear();

        NovaCoreProperties coreProperties = new NovaCoreProperties();
        // 使用核心内置的字体，避免测试结果依赖运行环境已安装的字体
        coreProperties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        // 字体在 @PostConstruct 中加载，脱离 Spring 容器时需手动触发
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "3.0.0");
        buildInfo.setProperty("group", "com." + "starlwr");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");

        BuildProperties buildProperties = new BuildProperties(buildInfo);
        NovaCommonPainterFactory factory = new NovaCommonPainterFactory(buildProperties, coreProperties, fontUtil) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                return new CommonPainter(buildProperties, coreProperties, fontUtil, width, height, autoExpand) {
                    @Override
                    public CommonPainter drawTextMultiLine(String text, int marginRight) {
                        drawnText.add(text);
                        return super.drawTextMultiLine(text, marginRight);
                    }

                    @Override
                    public CommonPainter drawTextMultiLine(String text, Color color, int marginRight) {
                        drawnText.add(text);
                        return super.drawTextMultiLine(text, color, marginRight);
                    }

                    @Override
                    public CommonPainter drawTextMultiLine(String text, java.awt.Point drawLocation, int marginRight) {
                        drawnText.add(text);
                        return super.drawTextMultiLine(text, drawLocation, marginRight);
                    }

                    @Override
                    public CommonPainter drawTextMultiLine(String text, Color color, java.awt.Point drawLocation, int marginRight) {
                        drawnText.add(text);
                        return super.drawTextMultiLine(text, color, drawLocation, marginRight);
                    }
                };
            }
        };

        // 所有图片请求返回同一张占位位图，避免测试依赖网络。填充可见颜色以便人工核对版面
        BufferedImage placeholder = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 200, 200);
        graphics.dispose();
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenAnswer(invocation -> {
            imageUrls.add(invocation.getArgument(0));
            return Optional.of(placeholder);
        });
        when(api.asyncGetBilibiliImages(any()))
                .thenAnswer(invocation -> {
                    List<String> urls = invocation.getArgument(0);
                    return CompletableFuture.completedFuture(urls.stream().map(url -> Optional.of(placeholder)).toList());
                });

        painter = new BilibiliDynamicPainter(factory, api, new NovaBilibiliProperties());
    }

    /**
     * 构造一条动态
     * @param type 动态类型
     * @param major major 节点的 JSON，可为 null
     */
    private Dynamic dynamic(String type, String text, String major) {
        String modules = "{\"module_author\":{\"mid\":123456,\"name\":\"测试主播\",\"face\":\"https://face.example/a.jpg\",\"pub_ts\":1700000000},"
                + "\"module_dynamic\":{\"desc\":{\"text\":\"" + text + "\"}"
                + (major == null ? "" : ",\"major\":" + major)
                + "}}";

        Dynamic dynamic = new Dynamic();
        dynamic.setId("998877665544332211");
        dynamic.setType(type);
        dynamic.setVisible(true);
        dynamic.setModules(JSON.parseObject(modules));

        return dynamic;
    }

    /**
     * 把绘制结果另存为 PNG，便于人工核对版面
     * @param name 文件名
     * @param base64 图片的 Base64 编码
     */
    private void dump(String name, String base64) {
        try {
            Path dir = Path.of("target", "painter-output");
            Files.createDirectories(dir);
            Files.write(dir.resolve(name + ".png"), Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            // 仅用于人工核对，失败不影响测试结论
        }
    }

    @Test
    @DisplayName("纯文字动态可正常绘制")
    void paintTextDynamic() {
        Optional<String> base64 = painter.paint(dynamic("DYNAMIC_TYPE_WORD", "今天也要好好直播呀，晚上八点见！", null));

        assertTrue(base64.isPresent(), "应生成图片");
        assertFalse(base64.get().isBlank());
        dump("text", base64.get());
    }

    @Test
    @DisplayName("单图动态可正常绘制")
    void paintSinglePicture() {
        String major = "{\"type\":\"MAJOR_TYPE_DRAW\",\"draw\":{\"items\":[{\"src\":\"https://pic.example/1.jpg\"}]}}";

        Optional<String> base64 = painter.paint(dynamic("DYNAMIC_TYPE_DRAW", "分享一张图", major));
        assertTrue(base64.isPresent());
        dump("paintSinglePicture", base64.get());
    }

    @Test
    @DisplayName("九图动态按网格排布且可正常绘制")
    void paintNinePictures() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            items.append(i == 0 ? "" : ",").append("{\"src\":\"https://pic.example/").append(i).append(".jpg\"}");
        }
        String major = "{\"type\":\"MAJOR_TYPE_DRAW\",\"draw\":{\"items\":[" + items + "]}}";

        Optional<String> base64 = painter.paint(dynamic("DYNAMIC_TYPE_DRAW", "九宫格", major));
        assertTrue(base64.isPresent());
        dump("paintNinePictures", base64.get());
    }

    @Test
    @DisplayName("视频投稿动态可正常绘制")
    void paintVideoDynamic() {
        String major = "{\"type\":\"MAJOR_TYPE_ARCHIVE\",\"archive\":{\"title\":\"新视频标题\",\"cover\":\"https://pic.example/cover.jpg\"}}";

        Optional<String> base64 = painter.paint(dynamic("DYNAMIC_TYPE_AV", "投稿了新视频", major));
        assertTrue(base64.isPresent());
        dump("paintVideoDynamic", base64.get());
    }

    @Test
    @DisplayName("转发动态可正常绘制并包含原动态")
    void paintForwardDynamic() {
        Dynamic origin = dynamic("DYNAMIC_TYPE_WORD", "这是被转发的原动态内容", null);
        Dynamic forward = dynamic("DYNAMIC_TYPE_FORWARD", "转发一下", null);
        forward.setOrigin(origin);

        Optional<String> base64 = painter.paint(forward);
        assertTrue(base64.isPresent());
        dump("forward", base64.get());
    }

    @Test
    @DisplayName("超长文本可正常换行绘制")
    void paintLongText() {
        String text = "这是一段很长的动态内容用来测试自动换行是否正常工作".repeat(12);

        Optional<String> base64 = painter.paint(dynamic("DYNAMIC_TYPE_WORD", text, null));
        assertTrue(base64.isPresent());
        dump("longText", base64.get());
    }

    @Test
    @DisplayName("modules 缺失时不抛出异常")
    void toleratesMissingModules() {
        Dynamic dynamic = new Dynamic();
        dynamic.setId("1");
        dynamic.setType("DYNAMIC_TYPE_WORD");

        assertTrue(painter.paint(dynamic).isPresent(), "结构不完整时仍应产出图片而非抛出异常");
    }

    @Test
    @DisplayName("未知的动态主体类型不影响其余部分绘制")
    void toleratesUnknownMajorType() {
        String major = "{\"type\":\"MAJOR_TYPE_SOMETHING_NEW\",\"whatever\":{}}";

        assertTrue(painter.paint(dynamic("DYNAMIC_TYPE_UNKNOWN", "新类型的动态", major)).isPresent());
    }

    @Test
    @DisplayName("🔴 图文动态正文在 summary、标题为空，推送图上没有正文")
    void opusBodyMissingWhenDescAndTitleAreNull() {
        Dynamic dynamic = opus("DYNAMIC_TYPE_DRAW", null, OPUS_BODY, null, "https://pic.example/opus.jpg");

        Optional<String> base64 = painter.paint(dynamic);
        assertTrue(base64.isPresent(), "结构齐的图文动态仍应产出图片");
        dump("opus-body", base64.get());

        assertTrue(drawnText.stream().anyMatch(line -> line.contains(OPUS_BODY)),
                "图文动态 desc 为 null、正文在 summary、标题为 null，推送图上没有这段正文。实际画上的字: " + drawnText);
    }

    @Test
    @DisplayName("🔴 有标题的图文动态只画出截断标题，正文丢了")
    void titledOpusDropsTheBody() {
        Dynamic dynamic = opus("DYNAMIC_TYPE_DRAW", null, FULL_BODY, OPUS_TITLE, "https://pic.example/opus.jpg");

        assertTrue(painter.paint(dynamic).isPresent());
        assertAll(
                () -> assertTrue(drawnText.stream().anyMatch(line -> line.contains(OPUS_TITLE)),
                        "图文动态有标题，推送图上却没画标题。实际: " + drawnText),
                () -> assertTrue(drawnText.stream().anyMatch(line -> line.contains(FULL_BODY)),
                        "图文动态只画出截断的标题，summary 里的正文丢了。实际: " + drawnText));
    }

    @Test
    @DisplayName("🔴 纯文字动态正文在 summary，推送图上没有字")
    void wordDynamicHasNoText() {
        Dynamic dynamic = opus("DYNAMIC_TYPE_WORD", null, WORD_BODY, null, null);

        assertTrue(painter.paint(dynamic).isPresent());
        assertTrue(drawnText.stream().anyMatch(line -> line.contains(WORD_BODY)),
                "纯文字动态 desc 为 null、标题为 null，正文只在 summary，推送图上没有字。实际: " + drawnText);
    }

    @Test
    @DisplayName("🔴 转发的纯文字原动态，框里没有正文")
    void forwardedWordOriginHasNoBody() {
        Dynamic origin = opus("DYNAMIC_TYPE_WORD", null, ORIGIN_BODY, null, null);
        Dynamic forward = opus("DYNAMIC_TYPE_FORWARD", FORWARD_NOTE, null, null, null);
        forward.setOrigin(origin);

        assertTrue(painter.paint(forward).isPresent());
        assertAll(
                () -> assertTrue(drawnText.stream().anyMatch(line -> line.contains(FORWARD_NOTE)),
                        "转发者自己写的话应仍画在外层。实际: " + drawnText),
                () -> assertTrue(drawnText.stream().anyMatch(line -> line.contains(ORIGIN_BODY)),
                        "转发的原动态是纯文字，原动态框里没有正文。实际: " + drawnText));
    }

    @Test
    @DisplayName("🔴 直播推荐没有标题，也没有封面")
    void liveRecommendationMissesTitleAndCover() {
        Dynamic dynamic = liveRecommendation(LIVE_TITLE, LIVE_COVER);

        assertTrue(painter.paint(dynamic).isPresent());
        assertAll(
                () -> assertTrue(drawnText.stream().anyMatch(line -> line.contains(LIVE_TITLE)),
                        "直播推荐的标题在 content 里，推送图上没有标题。实际: " + drawnText),
                () -> assertTrue(imageUrls.stream().anyMatch(url -> url.contains("cover.example")),
                        "直播推荐的封面在 live_play_info.cover，推送图没有去取这张封面。实际取过的图: " + imageUrls));
    }

    @Test
    @DisplayName("直播推荐的 content 解析失败时仍出图，不当成标题")
    void brokenLiveContentStillPaints() {
        Dynamic dynamic = liveRecommendationRaw("这不是 JSON {");

        assertTrue(painter.paint(dynamic).isPresent(), "content 解析失败不该把整张图弄丢");
        assertTrue(drawnText.stream().noneMatch(line -> line.contains("这不是 JSON")),
                "解析失败的 content 不该被当成标题画上去。实际: " + drawnText);
        assertFalse(imageUrls.stream().anyMatch(url -> url.contains("cover.example")),
                "解析失败不该去取封面");
    }

    /**
     * 图文或纯文字：desc 可为 null，正文在 major.opus.summary.text，标题在 opus.title（可为 null）。
     * 有图时地址在 opus.pics[].url。
     */
    private Dynamic opus(String type, String descText, String summaryText, String title, String picUrl) {
        JSONObject opus = new JSONObject();
        if (summaryText != null) {
            JSONObject summary = new JSONObject();
            summary.put("text", summaryText);
            opus.put("summary", summary);
        }
        opus.put("title", title);
        JSONArray pics = new JSONArray();
        if (picUrl != null) {
            JSONObject pic = new JSONObject();
            pic.put("url", picUrl);
            pics.add(pic);
        }
        opus.put("pics", pics);

        JSONObject major = new JSONObject();
        major.put("type", "MAJOR_TYPE_OPUS");
        major.put("opus", opus);

        JSONObject moduleDynamic = new JSONObject();
        if (descText != null) {
            JSONObject desc = new JSONObject();
            desc.put("text", descText);
            moduleDynamic.put("desc", desc);
        } else {
            moduleDynamic.put("desc", null);
        }
        moduleDynamic.put("major", major);

        return withAuthor(type, moduleDynamic);
    }

    /**
     * 直播推荐：major.live_rcmd 只有 content（一段 JSON 字符串）和 reserve_type。
     * 标题和封面在 content 解析后的 live_play_info 里。
     */
    private Dynamic liveRecommendation(String title, String cover) {
        JSONObject info = new JSONObject();
        info.put("title", title);
        info.put("cover", cover);
        JSONObject wrapped = new JSONObject();
        wrapped.put("live_play_info", info);

        JSONObject live = new JSONObject();
        live.put("content", wrapped.toJSONString());
        live.put("reserve_type", 0);

        JSONObject major = new JSONObject();
        major.put("type", "MAJOR_TYPE_LIVE_RCMD");
        major.put("live_rcmd", live);

        JSONObject moduleDynamic = new JSONObject();
        moduleDynamic.put("desc", null);
        moduleDynamic.put("major", major);
        return withAuthor("DYNAMIC_TYPE_LIVE_RCMD", moduleDynamic);
    }

    private Dynamic liveRecommendationRaw(String content) {
        JSONObject live = new JSONObject();
        live.put("content", content);
        live.put("reserve_type", 0);
        JSONObject major = new JSONObject();
        major.put("type", "MAJOR_TYPE_LIVE_RCMD");
        major.put("live_rcmd", live);
        JSONObject moduleDynamic = new JSONObject();
        moduleDynamic.put("desc", null);
        moduleDynamic.put("major", major);
        return withAuthor("DYNAMIC_TYPE_LIVE_RCMD", moduleDynamic);
    }

    private Dynamic withAuthor(String type, JSONObject moduleDynamic) {
        JSONObject author = new JSONObject();
        author.put("mid", 123456);
        author.put("name", "测试主播");
        author.put("face", "https://face.example/a.jpg");
        author.put("pub_ts", 1700000000);

        JSONObject modules = new JSONObject();
        modules.put("module_author", author);
        modules.put("module_dynamic", moduleDynamic);

        Dynamic dynamic = new Dynamic();
        dynamic.setId("998877665544332211");
        dynamic.setType(type);
        dynamic.setVisible(true);
        dynamic.setModules(modules);
        return dynamic;
    }
}
