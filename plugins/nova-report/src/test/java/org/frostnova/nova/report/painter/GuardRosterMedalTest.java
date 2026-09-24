package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.GuardMember;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 大航海名单上的粉丝牌与两列排法
 * <p>
 * 用户会碰到的三件事：接口里带了粉丝牌，报告上却没画出来；长昵称被塞进半栏截断；
 * 图上仍写着「舰长」。文字版报告仍写舰种，不在这里验。
 */
@DisplayName("大航海名单的粉丝牌与两列")
class GuardRosterMedalTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER =
            new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

    private static final String ROSTER_TITLE = "大航海名单（全部）";

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    @DisplayName("接口条目里的粉丝牌要能解析出来：牌名、等级、点亮和四种颜色")
    void parsesMedalNameLevelLitAndColors() {
        BilibiliApiUtil api = apiReturning(v2Captain(), decimalAdmiral(), noMedal());

        List<GuardMember> members = api.getGuardList(20002L, 10001L).orElseThrow();
        GuardMember captain = byUid(members, 11L);
        GuardMember admiral = byUid(members, 22L);
        GuardMember plain = byUid(members, 33L);

        Object v2 = medal(captain);
        assertAll(
                () -> assertNotNull(v2, "带了粉丝牌，解析结果里应有牌子"),
                () -> assertEquals("星云", prop(v2, "name"), "牌名"),
                () -> assertEquals(Integer.valueOf(27), prop(v2, "level"), "等级"),
                () -> assertEquals(Boolean.TRUE, prop(v2, "lit"), "点亮"),
                () -> assertColor(v2, "start", 0x3F, 0xB4, 0xF6, 0x99, "渐变起始含透明度"),
                () -> assertColor(v2, "end", 0x3F, 0xB4, 0xF6, 0x99, "渐变结束含透明度"),
                () -> assertColor(v2, "border", 0x5F, 0xC7, 0xF4, 0xFF, "边色"),
                () -> assertColor(v2, "text", 0xFF, 0xFF, 0xFF, 0xFF, "字色"));

        Object old = medal(admiral);
        assertAll(
                () -> assertNotNull(old, "只有旧版十进制色值时也应解析出牌子"),
                () -> assertEquals("旧牌", prop(old, "name"), "旧版牌名"),
                () -> assertEquals(Integer.valueOf(5), prop(old, "level"), "旧版等级"),
                () -> assertEquals(Boolean.FALSE, prop(old, "lit"), "未点亮"),
                () -> assertColor(old, "start", 0x11, 0x22, 0x33, 0xFF, "旧版起始色"),
                () -> assertColor(old, "end", 0x44, 0x55, 0x66, 0xFF, "旧版结束色"),
                () -> assertColor(old, "border", 0x77, 0x88, 0x99, 0xFF, "旧版边色"),
                () -> assertColor(old, "text", 0xFF, 0xFF, 0xFF, 0xFF, "旧版字色用白色"));

        assertNull(medal(plain), "没有牌子时不应造出一个空牌子");
        assertEquals("丙", plain.name());
        assertEquals(3, plain.level());
        assertEquals(80L, plain.score());
    }

    @Test
    @DisplayName("半栏放不下的长昵称独占一行，而且不被截断")
    void longNicknameTakesItsOwnRowUntruncated() {
        String shortName = "小波";
        String longName = "龙".repeat(18);
        List<Drawn> drawn = paintRoster(List.of(
                new GuardMember(1L, shortName, 3, 200),
                new GuardMember(2L, longName, 3, 100)));

        List<Drawn> roster = afterTitle(drawn);
        Drawn longLine = roster.stream()
                .filter(drawnLine -> drawnLine.text.contains("龙龙龙"))
                .findFirst()
                .orElse(null);
        assertNotNull(longLine, "图上没有这个长昵称");
        assertEquals(longName, longLine.text, "长昵称应完整画在名单上，实际: " + longLine.text);

        Drawn shortLine = roster.stream()
                .filter(drawnLine -> drawnLine.text.contains(shortName))
                .findFirst()
                .orElse(null);
        assertNotNull(shortLine, "图上没有短昵称");
        assertNotEquals(shortLine.y, longLine.y, "长昵称应独占一行，不应和别人挤在同一行");
    }

    @Test
    @DisplayName("名单图上不再写总督、提督、舰长")
    void rosterImageOmitsGuardRankWords() {
        List<Drawn> drawn = paintRoster(List.of(
                new GuardMember(1L, "小舟", 1, 300),
                new GuardMember(2L, "远帆", 2, 200),
                new GuardMember(3L, "星河", 3, 100)));

        List<Drawn> roster = afterTitle(drawn);
        assertTrue(roster.stream().anyMatch(line -> line.text.contains("小舟")), "名单段应画得出昵称");
        for (Drawn line : roster) {
            assertTrue(!line.text.contains("总督") && !line.text.contains("提督") && !line.text.contains("舰长"),
                    "名单段还写着舰种: " + line.text);
        }
    }

    private static void assertColor(Object medal, String channel, int red, int green, int blue, int alpha, String what) {
        Object value = prop(medal, channel);
        assertTrue(value instanceof Color, what + " 应是一种颜色，实际 " + value);
        Color color = (Color) value;
        assertEquals(red, color.getRed(), what + " 红");
        assertEquals(green, color.getGreen(), what + " 绿");
        assertEquals(blue, color.getBlue(), what + " 蓝");
        assertEquals(alpha, color.getAlpha(), what + " 透明度");
    }

    /**
     * 牌子还没挂到成员上时，这里就是空，断言因此失败。
     */
    private static Object medal(GuardMember member) {
        return prop(member, "medal");
    }

    private static Object prop(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static GuardMember byUid(List<GuardMember> members, long uid) {
        return members.stream()
                .filter(member -> member.uid() == uid)
                .findFirst()
                .orElseThrow(() -> new AssertionError("名单里没有 uid " + uid));
    }

    private static BilibiliApiUtil apiReturning(JSONObject... items) {
        return new BilibiliApiUtil(mock(HttpUtil.class), new NovaBilibiliProperties(), mock(BilibiliRiskMetrics.class)) {
            @Override
            public JSONObject requestBilibiliApi(String url) {
                JSONObject data = new JSONObject();
                JSONObject info = new JSONObject();
                info.put("num", items.length);
                info.put("page", 1);
                data.put("info", info);
                JSONArray list = new JSONArray();
                for (JSONObject item : items) {
                    list.add(item);
                }
                data.put("list", list);
                return data;
            }
        };
    }

    private static JSONObject v2Captain() {
        JSONObject medal = new JSONObject();
        medal.put("name", "星云");
        medal.put("level", 27);
        medal.put("guard_level", 3);
        medal.put("is_light", 1);
        medal.put("guard_icon", "");
        medal.put("v2_medal_color_start", "#3FB4F699");
        medal.put("v2_medal_color_end", "#3FB4F699");
        medal.put("v2_medal_color_border", "#5FC7F4");
        medal.put("v2_medal_color_text", "#FFFFFF");
        medal.put("color_start", 0x010203);
        return member(11L, "甲", 3, 300, medal);
    }

    private static JSONObject decimalAdmiral() {
        JSONObject medal = new JSONObject();
        medal.put("name", "旧牌");
        medal.put("level", 5);
        medal.put("guard_level", 2);
        medal.put("is_light", 0);
        medal.put("color_start", 0x112233);
        medal.put("color_end", 0x445566);
        medal.put("color_border", 0x778899);
        return member(22L, "乙", 2, 200, medal);
    }

    private static JSONObject noMedal() {
        return member(33L, "丙", 3, 80, null);
    }

    private static JSONObject member(long uid, String name, int level, long score, JSONObject medal) {
        JSONObject item = new JSONObject();
        JSONObject uinfo = new JSONObject();
        uinfo.put("uid", uid);
        JSONObject base = new JSONObject();
        base.put("name", name);
        uinfo.put("base", base);
        JSONObject guard = new JSONObject();
        guard.put("level", level);
        uinfo.put("guard", guard);
        if (medal != null) {
            uinfo.put("medal", medal);
        }
        item.put("uinfo", uinfo);
        item.put("score", score);
        return item;
    }

    private static List<Drawn> paintRoster(List<GuardMember> members) {
        NovaCoreProperties core = new NovaCoreProperties();
        core.getPaint().getFonts().add("内置");
        core.getLive().setSaveLiveData(false);
        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), core);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "org.frostnova");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        RecordingFactory factory = new RecordingFactory(new BuildProperties(buildInfo), core, fontUtil);

        BufferedImage placeholder = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 64, 64);
        graphics.dispose();

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        when(api.getFansCount(anyLong())).thenReturn(Optional.empty());
        when(api.getFansMedalCount(anyLong())).thenReturn(Optional.empty());
        when(api.getGuardCount(anyLong(), anyLong())).thenReturn(Optional.empty());

        LiveRoomInfoHistory history = new LiveRoomInfoHistory(new NovaStateStore(core));
        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(
                factory, api, new DefaultLiveDataService(core), fontUtil,
                new NovaBilibiliProperties(), history) {
            @Override
            protected Optional<List<GuardMember>> guardList(Long roomId, Long uid) {
                return Optional.of(members);
            }

            @Override
            protected BufferedImage guardIcon(String url) {
                return null;
            }
        };

        assertTrue(painter.paint(PLATFORM, STREAMER, new BilibiliLiveReportOptions()).isPresent(),
                "有名单时应出得了图");
        return factory.drawn;
    }

    private static List<Drawn> afterTitle(List<Drawn> drawn) {
        int title = -1;
        for (int i = 0; i < drawn.size(); i++) {
            if (ROSTER_TITLE.equals(drawn.get(i).text)) {
                title = i;
            }
        }
        assertTrue(title >= 0, "图上没有「" + ROSTER_TITLE + "」");
        return new ArrayList<>(drawn.subList(title + 1, drawn.size()));
    }

    private record Drawn(String text, int y) {
    }

    private static final class RecordingFactory extends NovaCommonPainterFactory {
        private final List<Drawn> drawn = new ArrayList<>();

        private final BuildProperties buildProperties;

        private final NovaCoreProperties properties;

        private final FontUtil fontUtil;

        private RecordingFactory(BuildProperties buildProperties, NovaCoreProperties properties, FontUtil fontUtil) {
            super(buildProperties, properties, fontUtil);
            this.buildProperties = buildProperties;
            this.properties = properties;
            this.fontUtil = fontUtil;
        }

        @Override
        public CommonPainter create(int width, int height, boolean autoExpand) {
            return new CommonPainter(buildProperties, properties, fontUtil, width, height, autoExpand) {
                @Override
                public CommonPainter drawTextWithStyle(List<TextWithStyle> texts, Point drawLocation,
                                                        boolean autoWrap, int marginRight) {
                    int y = drawLocation != null ? drawLocation.y : getY();
                    for (TextWithStyle text : texts) {
                        if (text.getText() != null) {
                            drawn.add(new Drawn(text.getText(), y));
                        }
                    }
                    return super.drawTextWithStyle(texts, drawLocation, autoWrap, marginRight);
                }
            };
        }
    }
}
