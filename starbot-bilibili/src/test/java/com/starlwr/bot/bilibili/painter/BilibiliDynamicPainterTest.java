package com.starlwr.bot.bilibili.painter;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.util.FontUtil;
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
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

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
    private BilibiliDynamicPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        StarBotCoreProperties coreProperties = new StarBotCoreProperties();
        // 使用核心内置的字体，避免测试结果依赖运行环境已安装的字体
        coreProperties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        // 字体在 @PostConstruct 中加载，脱离 Spring 容器时需手动触发
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "3.0.0");
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");

        StarBotCommonPainterFactory factory =
                new StarBotCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        // 所有图片请求返回同一张占位位图，避免测试依赖网络。填充可见颜色以便人工核对版面
        BufferedImage placeholder = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 200, 200);
        graphics.dispose();
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        when(api.asyncGetBilibiliImages(any()))
                .thenAnswer(invocation -> {
                    List<String> urls = invocation.getArgument(0);
                    return CompletableFuture.completedFuture(urls.stream().map(url -> Optional.of(placeholder)).toList());
                });

        painter = new BilibiliDynamicPainter(factory, api, new StarBotBilibiliProperties());
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
}
