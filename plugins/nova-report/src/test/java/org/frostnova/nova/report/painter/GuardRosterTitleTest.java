package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.GuardMember;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
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
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 大航海名单的标题：被「全名单人数」截掉时写「前 N 名」，全部列出时不带括号
 * <p>
 * 用户会看到的错：人多被截掉了，标题还写「全部」，读的人以为舰队就这么些人；
 * 一个没截的名单标题却带着括号。图与文字版各量一遍。
 */
@DisplayName("大航海名单标题")
class GuardRosterTitleTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER =
            new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

    private static final List<GuardMember> THREE = List.of(
            new GuardMember(11L, "甲总督", 1, 300),
            new GuardMember(22L, "乙提督", 2, 200),
            new GuardMember(33L, "丙舰长", 3, 100));

    private BuildProperties build;

    private NovaCoreProperties coreProperties;

    private FontUtil fontUtil;

    private BilibiliApiUtil api;

    private final List<String> drawn = new ArrayList<>();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");
        fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "org.frostnova.nova");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        build = new BuildProperties(buildInfo);

        api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.empty());
        Room room = new Room();
        room.setTitle("测试直播间");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
    }

    @Test
    @DisplayName("三位舰长、全名单人数 2：图与文字版标题都写「大航海名单（前 2 名）」")
    void truncatedRosterSaysTopN() {
        BilibiliLiveReportPainter painter = painterWith(Optional.of(THREE));
        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(paramsWithLimit(2), true);

        List<String> red = new ArrayList<>();
        try {
            assertTrue(painter.paint(PLATFORM, STREAMER, options).isPresent(), "报告没画出来");
            assertTrue(rosterTitles().equals(List.of("大航海名单（前 2 名）")),
                    "图上名单被截到 2 人，标题该写「前 2 名」，实际：" + rosterTitles());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        String text = painter.textReport(PLATFORM, STREAMER, options);
        try {
            assertTrue(text.contains("\n大航海名单（前 2 名）\n"),
                    "文字版名单被截到 2 人，标题该写「前 2 名」：" + text);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("没被截掉（人数不超过限额，或限额 0）与拉不到名单时，标题只写「大航海名单」不带括号")
    void untruncatedRosterHasNoBracket() {
        List<String> red = new ArrayList<>();
        int[] limits = {3, 30, 0};
        for (int limit : limits) {
            BilibiliLiveReportPainter painter = painterWith(Optional.of(THREE));
            BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(paramsWithLimit(limit), true);
            check(red, "限额 " + limit, painter, options);
        }
        check(red, "拉不到名单", painterWith(Optional.empty()),
                BilibiliLiveReportOptions.of(paramsWithLimit(30), true));
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private void check(List<String> red, String label, BilibiliLiveReportPainter painter,
                       BilibiliLiveReportOptions options) {
        try {
            assertTrue(painter.paint(PLATFORM, STREAMER, options).isPresent(), "报告没画出来");
            assertTrue(rosterTitles().equals(List.of("大航海名单")),
                    "图上标题该只写「大航海名单」，实际：" + rosterTitles());
        } catch (Throwable t) {
            red.add(label + " 图：" + t.getMessage());
        }
        String text = painter.textReport(PLATFORM, STREAMER, options);
        try {
            assertTrue(text.contains("\n大航海名单\n") && !text.contains("大航海名单（"),
                    "文字版标题该只写「大航海名单」：" + text);
        } catch (Throwable t) {
            red.add(label + " 文字版：" + t.getMessage());
        }
    }

    private List<String> rosterTitles() {
        return drawn.stream().filter(text -> text.startsWith("大航海名单")).toList();
    }

    private BilibiliLiveReportPainter painterWith(Optional<List<GuardMember>> members) {
        drawn.clear();
        DefaultLiveDataService data = new DefaultLiveDataService(new NovaCoreProperties());
        data.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        data.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);
        NovaCommonPainterFactory factory = new NovaCommonPainterFactory(build, coreProperties, fontUtil) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                return new CommonPainter(build, coreProperties, fontUtil, width, height, autoExpand) {
                    @Override
                    public CommonPainter drawTextWithStyle(List<TextWithStyle> texts, Point drawLocation,
                                                           boolean autoWrap, int marginRight) {
                        for (TextWithStyle text : texts) {
                            if (text.getText() != null) {
                                drawn.add(text.getText());
                            }
                        }
                        return super.drawTextWithStyle(texts, drawLocation, autoWrap, marginRight);
                    }
                };
            }
        };
        return new BilibiliLiveReportPainter(factory, api, data, fontUtil, new NovaBilibiliProperties(),
                new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties()))) {
            @Override
            protected Optional<List<GuardMember>> guardList(Long roomId, Long uid) {
                return members;
            }

            @Override
            protected BufferedImage guardIcon(String url) {
                return null;
            }
        };
    }

    private static JSONObject paramsWithLimit(int limit) {
        JSONObject params = new JSONObject();
        params.put("cover", false);
        params.put("cards", false);
        params.put("guard_list_all", true);
        params.put("guard_list_limit", limit);
        return params;
    }
}
