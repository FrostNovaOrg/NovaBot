package com.starlwr.bot.bilibili.painter;

import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.util.FontUtil;
import org.springframework.boot.info.BuildProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 下播报告绘制测试
 * <p>
 * 与动态图片绘制测试同一套桩：内置字体、占位头像，不依赖网络与本机字体。
 */
@DisplayName("下播报告绘制")
class BilibiliLiveReportPainterTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

    private DefaultLiveDataService liveDataService;

    private BilibiliLiveReportPainter painter;

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
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");

        StarBotCommonPainterFactory factory =
                new StarBotCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 640, 360);
        graphics.setColor(new Color(90, 140, 190));
        graphics.fillOval(180, 60, 280, 240);
        graphics.dispose();
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));

        // 直播间信息返回带封面的房间，覆盖封面横幅版式
        Room room = new Room();
        room.setTitle("测试直播间");
        room.setCover("https://pic.example/cover.jpg");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);

        liveDataService = new DefaultLiveDataService(new StarBotCoreProperties());
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil);
    }

    @Test
    @DisplayName("有完整数据时应绘制出报告")
    void paintsFullReport() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 2 * 3600_000 + 128_000);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, 106);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_USERS, 1L);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_USERS, 2L);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, 52.0);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 1L);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_COUNT, 5);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_PROFIT, -2.3);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_COUNT, 2);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE, 80.0);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.CAPTAIN_COUNT, 1);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_VALUE, 138.0);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.FOLLOW_COUNT, 5);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.ENTER_USERS, 3L);
        liveDataService.maxLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.LIKE_TOTAL, 1024);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SHARE_COUNT, 3);

        // 词频喂满词云的最低词数门槛，覆盖词云版式
        String[] words = {"晚上好", "唱歌", "好听", "打游戏", "厉害", "加油", "可爱", "再来一首", "笑死", "太强了", "岁月史书", "下次一定"};
        for (int i = 0; i < words.length; i++) {
            for (int j = 0; j <= i * 2; j++) {
                liveDataService.incrementLiveWordFrequency(PLATFORM, STREAMER.getUid(), words[i]);
            }
        }

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER);

        assertTrue(base64.isPresent(), "应生成报告图片");
        assertFalse(base64.get().isBlank());
        dump("full", base64.get());
    }

    @Test
    @DisplayName("零数据的冷清场次也应能绘制出报告")
    void paintsEmptyReport() {
        Optional<String> base64 = painter.paint(PLATFORM, STREAMER);

        assertTrue(base64.isPresent(), "无数据也应生成报告图片");
        dump("empty", base64.get());
    }

    @Test
    @DisplayName("有排行榜数据时应画出榜单")
    void paintsRankings() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);

        String[] names = {"甲乙丙", "丁戊己", "庚辛壬", "癸子丑", "寅卯辰", "巳午未"};
        for (int i = 0; i < names.length; i++) {
            long uid = 100L + i;
            double weight = names.length - i;
            liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), uid, names[i]);
            liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_USERS, uid, weight * 7);
            liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, uid, weight * 13.5);
            liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, uid, weight * 30);
        }
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_USERS, 100L, 1);
        // 总量与按用户计分要一起喂：生产中聚合器同时写两者，样张也应自洽
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, 147);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, 283.5);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_COUNT, 6);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE, 630.0);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.CAPTAIN_COUNT, 1);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_VALUE, 138.0);

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null));

        assertTrue(base64.isPresent());
        dump("rankings", base64.get());
    }

    @Test
    @DisplayName("关闭全部区块时应只剩概览，不应绘制失败")
    void paintsWithAllSectionsDisabled() {
        com.alibaba.fastjson2.JSONObject params = new com.alibaba.fastjson2.JSONObject();
        params.put("cover", false);
        params.put("cards", false);
        params.put("danmu_ranking", 0);
        params.put("gift_ranking", 0);
        params.put("super_chat_ranking", 0);
        params.put("guard_list", false);
        params.put("danmu_cloud", false);

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(params));

        assertTrue(base64.isPresent(), "全部关闭也应能出图");
        dump("minimal", base64.get());
    }

    /**
     * 把绘制结果另存为 PNG，便于人工核对版面
     */
    private void dump(String name, String base64) {
        try {
            Path dir = Path.of("target", "painter-output");
            Files.createDirectories(dir);
            Files.write(dir.resolve("report-" + name + ".png"), Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            // 仅用于人工核对，失败不影响测试结论
        }
    }
}
