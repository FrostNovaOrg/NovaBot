package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 下播打赏播报图绘制测试
 * <p>
 * 与下播报告绘制测试同一套桩：内置字体、占位头像，不依赖网络与本机字体。
 */
@DisplayName("下播打赏播报图绘制")
class BilibiliOfflineRewardDigestPainterTest {
    private static final LiveStreamerInfo STREAMER =
            new LiveStreamerInfo(10001L, "主播甲", 20002L, "https://pic.example/face.jpg");

    private BilibiliOfflineRewardDigestPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        // 使用核心内置的字体，避免测试结果依赖运行环境已安装的字体
        coreProperties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        // 字体在 @PostConstruct 中加载，脱离 Spring 容器时需手动触发
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "org.frostnova.nova");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");

        NovaCommonPainterFactory factory =
                new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 640, 360);
        graphics.setColor(new Color(90, 140, 190));
        graphics.fillOval(180, 60, 280, 240);
        graphics.dispose();

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        // 昵称接口不可用时回退到事件携带的昵称，测试不关心接口路径
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));

        painter = new BilibiliOfflineRewardDigestPainter(factory, api, new NovaBilibiliProperties());
    }

    @Test
    @DisplayName("金额可见关着时，画进图里的每一行都不带金额")
    void hidesAmountsInEveryLineWhenNotVisible() {
        // 抓的用户故障：金额不可见的群里收到的播报图上出现了金额。
        // 图上的字全从 bodyLines 来，这一行行验过，图上才真的一个金额都没有
        BilibiliOfflineRewardDigestEvent event = sampleEvent();
        assertEquals(3, event.getContributions().size(), "样例该是三个人");
        for (BilibiliOfflineRewardDigestEvent.Contribution person : event.getContributions()) {
            List<String> lines = BilibiliOfflineRewardDigestPainter.bodyLines(person, false);
            assertFalse(lines.isEmpty(), "每人至少要有一行，空着说明这一个人整段没画: " + person.getUname());
            for (String line : lines) {
                assertFalse(line.contains("¥"), "金额不可见的群，这一行不该出现金额: " + line);
            }
        }
    }

    @Test
    @DisplayName("金额可见时逐行带金额，上舰按等级与开通续费用词")
    void showsAmountsAndGuardWordingWhenVisible() {
        assertEquals(List.of("王一", "开通了舰长（¥198）"),
                BilibiliOfflineRewardDigestPainter.bodyLines(guardPerson("王一", 3, GuardOperateType.ACTIVATION, 198.0), true));
        assertEquals(List.of("王五", "续费了提督（¥398）"),
                BilibiliOfflineRewardDigestPainter.bodyLines(guardPerson("王五", 2, GuardOperateType.RENEWAL, 398.0), true));
        assertEquals(List.of("李三", "送了 小花花×10（合计 ¥10）", "送了 甜梦花×3（合计 ¥30）", "礼物合计 ¥40"),
                BilibiliOfflineRewardDigestPainter.bodyLines(giftPerson("李三", 40.0,
                        new BilibiliOfflineRewardDigestEvent.GiftLine("小花花", 10, 1000L),
                        new BilibiliOfflineRewardDigestEvent.GiftLine("甜梦花", 3, 3000L)), true));
        // 不可见时同一批人行数一样、字里没有括号金额——谁做了什么两种都写
        assertEquals(List.of("李三", "送了 小花花×10", "送了 甜梦花×3"),
                BilibiliOfflineRewardDigestPainter.bodyLines(giftPerson("李三", 40.0,
                        new BilibiliOfflineRewardDigestEvent.GiftLine("小花花", 10, 1000L),
                        new BilibiliOfflineRewardDigestEvent.GiftLine("甜梦花", 3, 3000L)), false));
    }

    @Test
    @DisplayName("画得出一张与下播报告同宽的播报图")
    void paintsImageAtReportWidth() throws Exception {
        // 抓的用户故障：群里收到的是一张图、不是文字感谢——图得先画得出来
        Optional<String> base64 = painter.paint(sampleEvent(), true);
        assertTrue(base64.isPresent(), "合成事件画图不该失败");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64.get())));
        assertEquals(900, image.getWidth(), "与下播报告同一套模板，同宽");
        assertTrue(image.getHeight() > 200, "页头加三个人，高度不该贴地: " + image.getHeight());
    }

    @Test
    @DisplayName("样图两张：金额可见与不可见各一张")
    void writesSampleImages(@TempDir Path dir) throws Exception {
        writeSample(dir.resolve("打赏播报-金额可见.png"), true);
        writeSample(dir.resolve("打赏播报-金额不可见.png"), false);
        writesTimedSamples();
    }

    private void writeSample(Path file, boolean showRevenue) throws Exception {
        Optional<String> base64 = painter.paint(sampleEvent(), showRevenue);
        assertTrue(base64.isPresent(), "样图不该画失败: " + file);
        Files.write(file, Base64.getDecoder().decode(base64.get()));
        BufferedImage image = ImageIO.read(file.toFile());
        assertEquals(900, image.getWidth(), file + " 该与下播报告同宽");
        assertTrue(image.getHeight() > 200, file + " 高度不该贴地");
    }

    private BilibiliOfflineRewardDigestEvent timedEvent(boolean reminder,
            BilibiliOfflineRewardDigestEvent.GiftLine... gifts) {
        var person = giftPerson("李三", 120.0, gifts);
        var event = new BilibiliOfflineRewardDigestEvent(STREAMER, List.of(person),
                Instant.parse("2026-10-15T12:32:00Z"));
        event.setClosingReminder(reminder);
        return event;
    }

    private BilibiliOfflineRewardDigestEvent.GiftLine timedGift(String name, int count, String first, String last) {
        return new BilibiliOfflineRewardDigestEvent.GiftLine(name, count, 6000L,
                Instant.parse(first), Instant.parse(last));
    }

    @Test
    @DisplayName("逐种礼物显示各自送出分钟")
    void showsEachGiftMinute() {
        var event = timedEvent(false,
                timedGift("小花花", 5, "2026-10-15T12:15:03Z", "2026-10-15T12:15:59Z"),
                timedGift("甜梦花", 1, "2026-10-15T12:31:00Z", "2026-10-15T12:31:00Z"));
        String text = painter.textDigest(event, true);
        assertTrue(text.contains("20:15 送了 小花花×5"), "小花花要显示 20:15");
        assertTrue(text.contains("20:31 送了 甜梦花×1"));
        assertFalse(text.contains("20:15–20:15"), "同一分钟不写重复区间");
    }

    @Test
    @DisplayName("同名礼物跨分钟显示时间范围")
    void showsMergedMinuteRange() {
        var event = timedEvent(false,
                timedGift("小花花", 2, "2026-10-15T12:15:00Z", "2026-10-15T12:22:00Z"));
        assertTrue(painter.renderList(event, false).contains("20:15–20:22"), "合并礼物要显示起止分钟");
    }

    @Test
    @DisplayName("跨天的礼物显示送出日期")
    void showsPreviousDay() {
        var event = new BilibiliOfflineRewardDigestEvent(STREAMER,
                timedEvent(false, timedGift("小花花", 1, "2026-10-15T15:58:00Z", "2026-10-15T15:58:00Z"))
                        .getContributions(), Instant.parse("2026-10-15T16:05:00Z"));
        assertTrue(painter.textDigest(event, false).contains("10-15 23:58"), "跨天要带送出日期");
    }

    @Test
    @DisplayName("上舰显示自己的送出分钟")
    void showsGuardMinute() {
        var person = guardPerson("王一", 3, GuardOperateType.ACTIVATION, 198);
        person.setGuardSentAt(Instant.parse("2026-10-15T12:18:00Z"));
        var event = new BilibiliOfflineRewardDigestEvent(STREAMER, List.of(person),
                Instant.parse("2026-10-15T12:32:00Z"));
        assertTrue(painter.renderList(event, false).contains("20:18 开通了舰长"), "上舰要带自己的时间");
    }

    @Test
    @DisplayName("最后十秒提醒标题及省略汇总遵守金额可见性")
    void reminderTitleAndSummary() {
        var event = timedEvent(true,
                timedGift("小花花", 5, "2026-10-15T12:15:03Z", "2026-10-15T12:15:59Z"));
        event.setOmittedCount(5);
        event.setOmittedAmountFen(15000);
        String visible = painter.textDigest(event, true);
        assertTrue(visible.contains("下播前 10 秒内"), "提醒要写明最后十秒");
        assertTrue(visible.contains("另有 5 人，合计 ¥150"));
        String hidden = painter.textDigest(event, false);
        assertTrue(hidden.contains("另有 5 人"));
        assertFalse(hidden.contains("¥"));
    }

    private void writesTimedSamples() throws Exception {
        var event = timedEvent(false,
                timedGift("小花花", 5, "2026-10-15T12:15:03Z", "2026-10-15T12:15:59Z"),
                timedGift("甜梦花", 2, "2026-10-15T12:22:00Z", "2026-10-15T12:31:00Z"));
        var guard = guardPerson("王一", 3, GuardOperateType.ACTIVATION, 198);
        guard.setGuardSentAt(Instant.parse("2026-10-15T12:18:00Z"));
        event.setContributions(List.of(guard, event.getContributions().get(0),
                giftPerson("张二", 60, timedGift("心动盲盒", 2,
                        "2026-10-15T12:20:00Z", "2026-10-15T12:20:00Z"))));
        Path dir = Path.of("target", "offline-reward-samples");
        Files.createDirectories(dir);
        writeTimedSample(dir.resolve("ordinary-digest.png"), event);

        List<BilibiliOfflineRewardDigestEvent.Contribution> people = new java.util.ArrayList<>();
        for (int i = 25; i >= 6; i--) {
            var gift = new BilibiliOfflineRewardDigestEvent.GiftLine("小花花", 1, i * 100L,
                    Instant.parse("2026-10-15T12:31:53Z"), Instant.parse("2026-10-15T12:31:57Z"));
            people.add(giftPerson(String.format("观众%02d", i), i, gift));
        }
        var reminder = new BilibiliOfflineRewardDigestEvent(STREAMER, people, Instant.parse("2026-10-15T12:32:15Z"));
        reminder.setClosingReminder(true);
        reminder.setOmittedCount(5);
        reminder.setOmittedAmountFen(1500);
        writeTimedSample(dir.resolve("closing-reminder.png"), reminder);
        var hidden = painter.textDigest(reminder, false);
        assertFalse(hidden.contains("¥"));
    }

    private void writeTimedSample(Path file, BilibiliOfflineRewardDigestEvent event) throws Exception {
        Files.write(file, Base64.getDecoder().decode(painter.paint(event, true).orElseThrow()));
        var image = ImageIO.read(file.toFile());
        assertEquals(900, image.getWidth());
        System.out.println("Sample image: " + file.toAbsolutePath() + " " + image.getWidth() + "x" + image.getHeight());
    }

    // —— 以下为夹具 ——

    /**
     * 三个人：一位开通舰长、一位送盲盒、一位送了几样礼物
     */
    private BilibiliOfflineRewardDigestEvent sampleEvent() {
        return new BilibiliOfflineRewardDigestEvent(STREAMER, List.of(
                guardPerson("王一", 3, GuardOperateType.ACTIVATION, 198.0),
                giftPerson("张二", 8.0, new BilibiliOfflineRewardDigestEvent.GiftLine("心动盲盒", 2, 800L)),
                giftPerson("李三", 40.0,
                        new BilibiliOfflineRewardDigestEvent.GiftLine("小花花", 10, 1000L),
                        new BilibiliOfflineRewardDigestEvent.GiftLine("甜梦花", 3, 3000L))));
    }

    /**
     * 一位上过舰的人。入参金额按元写，事件里按分记
     */
    private BilibiliOfflineRewardDigestEvent.Contribution guardPerson(String uname, int level,
                                                                      GuardOperateType operateType, double amountYuan) {
        BilibiliOfflineRewardDigestEvent.Contribution person = new BilibiliOfflineRewardDigestEvent.Contribution();
        person.setUid(50001L);
        person.setUname(uname);
        person.setGuardLevel(level);
        person.setOperateType(operateType);
        person.setGuardAmountFen(Math.round(amountYuan * 100));
        return person;
    }

    /**
     * 一位送过礼物的人。入参金额按元写，事件里按分记
     */
    private BilibiliOfflineRewardDigestEvent.Contribution giftPerson(String uname, double amountYuan,
                                                                     BilibiliOfflineRewardDigestEvent.GiftLine... gifts) {
        BilibiliOfflineRewardDigestEvent.Contribution person = new BilibiliOfflineRewardDigestEvent.Contribution();
        person.setUid(50002L);
        person.setUname(uname);
        person.setGiftAmountFen(Math.round(amountYuan * 100));
        person.setGifts(List.of(gifts));
        return person;
    }
}
