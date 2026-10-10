package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 醒目留言原文带换行时，报告图上那段字不溢出
 * <p>
 * 画法见了 {@code \n} 就另起一行，而原文那一格只留了一行的高度：原文里的换行会把后半句
 * 压到下一位的名字上。这一组走整张报告的出图路径，收下真画出去的每一段字来量，
 * 不另抄一遍截断的写法——抄来的那份在画法改了以后照样绿。
 */
@DisplayName("醒目留言原文带换行时不溢出")
class SuperChatLineBreakTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "测试主播", 20002L, null);

    /** 换行类字符：报告图上不许出现任何一个 */
    private static final String LINE_BREAKS = "\r\n  \u0085\t";

    private FontUtil fontUtil;

    private DefaultLiveDataService liveDataService;

    private final List<TextWithStyle> drawn = new ArrayList<>();

    private BilibiliLiveReportPainter painter;

    private CommonPainter measurer;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");
        fontUtil = new FontUtil(new org.springframework.core.io.DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("name", "NovaBot");
        BuildProperties build = new BuildProperties(buildInfo);
        NovaCommonPainterFactory factory = new NovaCommonPainterFactory(build, coreProperties, fontUtil) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                return new CommonPainter(build, coreProperties, fontUtil, width, height, autoExpand) {
                    @Override
                    public CommonPainter drawTextWithStyle(List<TextWithStyle> texts, Point drawLocation,
                                                           boolean autoWrap, int marginRight) {
                        drawn.addAll(texts);
                        return super.drawTextWithStyle(texts, drawLocation, autoWrap, marginRight);
                    }
                };
            }
        };
        measurer = new CommonPainter(build, coreProperties, fontUtil, ReportSharedStyle.WIDTH, 400, false);

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.empty());
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(new Room());
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

        liveDataService = new DefaultLiveDataService(new NovaCoreProperties());
        long base = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), base);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), base + 3_600_000L);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, 1L, 30);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, 2L, 50);
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 1L, "上一位");
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 2L, "换行\n昵称");

        List<DanmuRecord> records = List.of(
                new DanmuRecord(base + 60_000L, 2L, "换行\n昵称",
                        "原文甲第一行\n第二行\r\n第三行 第四行 第五行\u0085第六行\t第七行"
                                + "，后面再拖一长串让它一行放不下：主播今天唱得真好听下次还来听",
                        DanmuRecord.Type.SUPER_CHAT),
                new DanmuRecord(base + 120_000L, 2L, "换行\n昵称", "\n\n原文甲第二条\n\n", DanmuRecord.Type.SUPER_CHAT),
                new DanmuRecord(base + 180_000L, 1L, "上一位", "原文乙", DanmuRecord.Type.SUPER_CHAT));
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new NovaBilibiliProperties(), new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties()))) {
            @Override
            protected List<DanmuRecord> superChatRecords(String platform, Long uid) {
                return records;
            }
        };
    }

    @Test
    @DisplayName("🔴 原文带换行的醒目留言：画出去的那段字不含换行，宽度不超过版心")
    void superChatTextStaysOnOneLineWithinContentWidth() {
        assertTrue(painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, true)).isPresent(),
                "报告没出图");

        List<TextWithStyle> superChatTexts = drawn.stream()
                .filter(text -> text.getText() != null && text.getText().contains("原文甲"))
                .toList();
        // 先证量得到东西：一段都没收到时，下面的「都不含换行」会白白通过
        assertFalse(superChatTexts.isEmpty(), "醒目留言原文一段都没画，这一格什么都没量到");

        List<String> red = new ArrayList<>();
        for (TextWithStyle text : superChatTexts) {
            String value = text.getText();
            if (value.chars().anyMatch(c -> LINE_BREAKS.indexOf(c) >= 0)) {
                red.add("① 画出去的原文带换行类字符：" + escape(value));
            }
            int width = measurer.getStringWidthAndHeight(text).getFirst();
            if (width > BilibiliLiveReportPainter.CONTENT_WIDTH) {
                red.add("② 原文宽 " + width + " 超过版心 " + BilibiliLiveReportPainter.CONTENT_WIDTH + "：" + escape(value));
            }
        }
        List<TextWithStyle> names = drawn.stream()
                .filter(text -> text.getText() != null && text.getText().contains("昵称"))
                .toList();
        if (names.isEmpty()) {
            red.add("③ 带换行的昵称一处都没画，量不到");
        }
        for (TextWithStyle name : names) {
            if (name.getText().chars().anyMatch(c -> LINE_BREAKS.indexOf(c) >= 0)) {
                red.add("④ 画出去的昵称带换行类字符：" + escape(name.getText()));
            }
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 处红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("收成一行：各类换行与制表符换成一个空格，连着的并成一个，首尾去掉")
    void singleLineMergesBreaksAndTrims() {
        assertEquals("甲 乙 丙 丁 戊 己 庚", UserText.singleLine("甲\n乙\r\n丙 丁 戊\u0085己\t庚"));
        assertEquals("甲 乙", UserText.singleLine("  \n\r\n甲 \n\n\t 乙\n  "));
        assertEquals("甲  乙", UserText.singleLine("甲  乙"), "不挨着换行的空格是原文自己的，不动");
        assertEquals("", UserText.singleLine("\n\r\n"));
        assertNull(UserText.singleLine(null));
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            out.append(LINE_BREAKS.indexOf(c) >= 0 ? String.format("\\u%04X", (int) c) : String.valueOf(c));
        }
        return out.toString();
    }
}
