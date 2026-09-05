package com.starlwr.bot.bilibili.painter;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 报告版式预览
 *
 * <h2>这一组里最要紧的一格</h2>
 * 🔴 <b>预览全程不许碰接口</b>。它不是一句愿望：父类往「向外部要资料的口子」那一段里
 * 添一个新方法而预览painter忘了覆写时，预览就会拿着夹具里那个不存在的 uid 去打真接口，
 * 而<b>图上看不出任何区别</b>。这里把接口与状态存储都换成 mock，断言零交互——
 * 漏覆写的那一个口子在这一格当场红。
 */
@DisplayName("报告版式预览")
class BilibiliLiveReportPreviewPainterTest {
    /**
     * PNG 的文件头，用来证「回的确实是张 PNG」而不是别的字节
     */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private BilibiliApiUtil api;

    private LiveRoomInfoHistory roomInfoHistory;

    private BilibiliLiveReportPreviewPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        StarBotCoreProperties coreProperties = new StarBotCoreProperties();
        // 用核心内置字体，免得结论取决于跑测试这台机器装了什么字体
        coreProperties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "5.1.0");
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");

        StarBotCommonPainterFactory factory =
                new StarBotCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        // 🔴 两个都不打桩：任何一次调用都是「预览联网了」的实证
        api = mock(BilibiliApiUtil.class);
        roomInfoHistory = mock(LiveRoomInfoHistory.class);

        painter = new BilibiliLiveReportPreviewPainter(
                factory, api, fontUtil, new StarBotBilibiliProperties(), roomInfoHistory);
    }

    @Test
    @DisplayName("🔴 全开版式画得出图，且是一张真 PNG")
    void rendersPngWithEverythingOn() throws IOException {
        byte[] png = render(new JSONObject());

        assertTrue(png.length > PNG_MAGIC.length, "回的字节太短，不可能是一张图");
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            assertEquals(PNG_MAGIC[i], png[i], "第 " + i + " 个字节不是 PNG 文件头");
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "PNG 解不出来");
        assertEquals(900, image.getWidth(), "报告宽度写死 900");
        assertTrue(image.getHeight() > 0, "报告高度为 0");

        System.out.println("全开预览　" + image.getWidth() + "×" + image.getHeight()
                + "，" + png.length + " 字节");
    }

    @Test
    @DisplayName("🔴 预览全程不碰接口，也不碰状态存储")
    void previewNeverTouchesTheNetwork() {
        render(new JSONObject());

        verifyNoInteractions(api);
        verifyNoInteractions(roomInfoHistory);
    }

    @Test
    @DisplayName("🔴 预览那份夹具数据不许落盘：默认会盖掉真数据文件")
    void fixtureDataNeverPersists() throws Exception {
        Object data = field(BilibiliLiveReportPainter.class, painter, "liveDataService");
        assertTrue(data instanceof DefaultLiveDataService,
                "预览的数据服务换了实现，这一格量的东西得跟着重判：" + data.getClass());

        StarBotCoreProperties properties =
                (StarBotCoreProperties) field(DefaultLiveDataService.class, data, "properties");

        // 先证这一格量得到东西：默认值是 true，读到 true 才说明这一格真的在看这个开关
        assertTrue(new StarBotCoreProperties().getLive().isSaveLiveData(),
                "默认值不再是 true 了，这一格的意义得重判");

        assertFalse(properties.getLive().isSaveLiveData(),
                "夹具数据服务开着落盘：它的默认落点是 "
                        + properties.getLive().getLiveDataPath() + "，那是真数据所在的文件");
    }

    private static Object field(Class<?> owner, Object target, String name) throws Exception {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    @DisplayName("🔴 关掉区块的图比全开的短：版式开关真的作用到了图上")
    void switchesChangeTheImage() throws IOException {
        int tall = height(render(new JSONObject()));

        JSONObject minimal = new JSONObject();
        minimal.put("cover", false);
        minimal.put("cards", false);
        minimal.put("fans_change", false);
        minimal.put("interaction_curve", false);
        minimal.put("guard_list", false);
        minimal.put("guard_list_all", false);
        minimal.put("danmu_cloud", false);
        minimal.put("highlights", false);
        minimal.put("title_changes", false);
        minimal.put("danmu_ranking", 0);
        minimal.put("gift_ranking", 0);
        minimal.put("super_chat_ranking", 0);
        minimal.put("box_ranking", 0);
        minimal.put("box_profit_ranking", 0);
        int shortImage = height(render(minimal));

        assertTrue(shortImage < tall,
                "全关比全开还高：版式开关没作用到图上（全开 " + tall + "，全关 " + shortImage + "）");

        System.out.println("全开高 " + tall + "，全关高 " + shortImage
                + "，差 " + (tall - shortImage) + " 像素");
    }

    @Test
    @DisplayName("🔴 同一套版式两次预览字节相同：夹具的时刻是写死的")
    void sameOptionsRenderIdenticalBytes() {
        assertArrayEquals(render(new JSONObject()), render(new JSONObject()),
                "同一套版式两次预览字节不同——预览没法拿来比较两套版式了");
    }

    @Test
    @DisplayName("🔴 越界的名次数不该让预览画崩：夹到合法区间")
    void clampsOutOfRangeRanking() {
        JSONObject params = new JSONObject();
        params.put("danmu_ranking", 9999);
        params.put("gift_ranking", -5);

        assertFalse(render(params).length == 0, "越界参数把预览画崩了");
    }

    private byte[] render(JSONObject params) {
        Optional<byte[]> png = painter.render(BilibiliLiveReportOptions.of(params, true));
        assertTrue(png.isPresent(), "预览没画出图");
        return png.get();
    }

    private static int height(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png)).getHeight();
    }
}
