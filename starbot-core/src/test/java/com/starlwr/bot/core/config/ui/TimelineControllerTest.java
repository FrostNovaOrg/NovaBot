package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.timeline.TimelineCategory;
import com.starlwr.bot.core.timeline.TimelineEvent;
import com.starlwr.bot.core.timeline.TimelineEventType;
import com.starlwr.bot.core.timeline.TimelineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件时间线接口测试
 * <p>
 * 接口本身只做三件事：解析参数、转交存储、把结果写成 JSON。
 * 这里钉的是前后两件——尤其是<b>参数认不出时不许默默退回「不筛」</b>：
 * 那会把「筛了个不存在的类型」显示成一大堆记录，看起来像是筛选没生效。
 */
@DisplayName("事件时间线接口")
class TimelineControllerTest {
    @TempDir
    Path dir;

    private TimelineStore store;

    private TimelineController controller;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        store = new TimelineStore(properties);
        store.load();
        controller = new TimelineController(store);
    }

    @Test
    @DisplayName("没有任何记录时应返回空表并说明保留天数")
    void emptyIsNotAnError() {
        JSONObject result = query(null, false, null, null);

        assertTrue(result.getBooleanValue("success"));
        assertTrue(result.getJSONArray("events").isEmpty());
        assertEquals(0, result.getIntValue("matched"));
        assertEquals(14, result.getIntValue("retentionDays"));

        assertTrue(controller.days().getJSONArray("days").isEmpty());
    }

    @Test
    @DisplayName("查到的每条都带类型说明, 且类型闭集随结果一起给出")
    void exposesTypeCatalog() {
        store.record(TimelineEvent.of(TimelineEventType.PUSH_MUTED, TimelineEvent.Level.WARN)
                .channel("群 12345").text("静音时段，丢弃了发往群 12345 的一条消息").build());

        JSONObject result = query(null, false, null, null);

        JSONObject event = result.getJSONArray("events").getJSONObject(0);
        assertEquals("PUSH_MUTED", event.getString("type"));
        assertEquals("静音丢弃", event.getString("typeText"));
        assertEquals("warn", event.getString("level"));
        assertEquals("群 12345", event.getString("channel"));

        JSONArray types = result.getJSONArray("types");
        assertEquals(TimelineEventType.values().length, types.size(),
                "闭集由接口给出, 界面不必自己写一张会漏项的表");
    }

    @Test
    @DisplayName("大类清单随结果一起给出, 且只列有类型归属的大类")
    void exposesCategoryCatalog() {
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .text("一条").build());

        for (JSONObject result : List.of(query(null, false, null, null), controller.days())) {
            JSONArray categories = result.getJSONArray("categories");
            assertNotNull(categories, "药丸那一排由接口给, 界面不自己写一张会漏项的表");

            List<String> listed = new ArrayList<>();
            for (int i = 0; i < categories.size(); i++) {
                JSONObject item = categories.getJSONObject(i);
                assertNotNull(item.getString("text"), "每个大类都要带中文说明");
                listed.add(item.getString("name"));
            }

            // 两个方向都查：只查「列出来的都有类型」的话，一个什么都不列的接口也是绿的；
            // 只查「有类型的都列了」的话，把七个大类全列出来也是绿的——而那几枚药丸点下去必然是空的
            for (String name : listed) {
                TimelineCategory category = TimelineCategory.parse(name);
                assertNotNull(category, "清单里出现了认不出的大类: " + name);
                assertTrue(Arrays.stream(TimelineEventType.values())
                                .anyMatch(type -> type.getCategory() == category),
                        category + " 一个类型都没有, 那枚药丸点下去必然是空的");
            }
            for (TimelineEventType type : TimelineEventType.values()) {
                assertTrue(listed.contains(type.getCategory().name()),
                        type + " 归的大类 " + type.getCategory() + " 没出现在清单里, 它筛不到");
            }
        }
    }

    @Test
    @DisplayName("按大类筛应筛得动, 且与按类型筛同时生效")
    void filtersByCategory() {
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .text("推送这条").build());
        store.record(TimelineEvent.of(TimelineEventType.PROBE_CHANGED, TimelineEvent.Level.WARN)
                .text("连接那条").build());

        assertEquals(2, byCategory(null).getIntValue("matched"));
        assertEquals(1, byCategory("PUSH").getIntValue("matched"));
        assertEquals("推送这条",
                byCategory("PUSH").getJSONArray("events").getJSONObject(0).getString("text"));
        assertEquals(1, byCategory("LINK").getIntValue("matched"));
        assertEquals(0, byCategory("SYSTEM").getIntValue("matched"),
                "一个类型都没归到它名下的大类, 筛出来就该是空的");

        // 旧地址里带着 type= 的仍认，两项一起给时同时满足：
        // 只保留其中一项的话，贴过来的地址打开是一张筛得不一样的页
        assertEquals(1, controller.timeline(null, false, "PUSH", "PUSH_SENT", null, null, null, 0, null)
                .getIntValue("matched"));
        assertEquals(0, controller.timeline(null, false, "LINK", "PUSH_SENT", null, null, null, 0, null)
                .getIntValue("matched"), "大类与类型对不上时应当一条都不剩");
    }

    @Test
    @DisplayName("认不出的大类应明说, 而不是当成不筛")
    void rejectsUnknownCategory() {
        JSONObject result = byCategory("PUSHY");

        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains("PUSHY"));
        assertTrue(result.getJSONArray("events") == null, "失败时不该顺手返回全部记录");
    }

    @Test
    @DisplayName("每条事件都带着它的大类, 界面不必再算一遍")
    void eventsCarryTheirCategory() {
        store.record(TimelineEvent.of(TimelineEventType.PROBE_CHANGED, TimelineEvent.Level.WARN)
                .text("一条").build());

        JSONObject event = query(null, false, null, null).getJSONArray("events").getJSONObject(0);
        assertEquals("LINK", event.getString("category"));
        assertEquals("连接", event.getString("categoryText"));
    }

    @Test
    @DisplayName("按日期查应只返回那一天, 空的那天返回空表")
    void filtersByDate() {
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .text("今天这条").build());

        LocalDate today = LocalDate.now();
        assertEquals(1, query(today.toString(), false, null, null).getIntValue("matched"));
        assertEquals(0, query(today.minusDays(1).toString(), false, null, null).getIntValue("matched"));

        JSONArray days = controller.days().getJSONArray("days");
        assertEquals(1, days.size());
        assertEquals(today.toString(), days.getJSONObject(0).getString("date"));
        assertEquals(1, days.getJSONObject(0).getIntValue("count"));
    }

    @Test
    @DisplayName("日期格式不对应明说, 而不是当成没填去查全部")
    void rejectsBadDate() {
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .text("一条").build());

        JSONObject result = query("2026年9月4日", false, null, null);

        assertFalse(result.getBooleanValue("success"));
        assertNotNull(result.getString("message"));
        assertTrue(result.getJSONArray("events") == null, "失败时不该顺手返回全部记录");
    }

    @Test
    @DisplayName("认不出的类型名应明说, 而不是当成不筛")
    void rejectsUnknownType() {
        JSONObject result = query(null, false, "PUSH_EXPLODED", null);

        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains("PUSH_EXPLODED"));
    }

    @Test
    @DisplayName("只看问题与关键词应传到存储那一层")
    void passesFiltersThrough() {
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .text("已推送：开播了").build());
        store.record(TimelineEvent.of(TimelineEventType.PUSH_FAILED, TimelineEvent.Level.ERROR)
                .text("推送失败：群号不存在").build());

        assertEquals(1, query(null, true, null, null).getIntValue("matched"));
        assertEquals(1, query(null, false, null, "开播").getIntValue("matched"));
        assertEquals(2, query(null, false, null, null).getIntValue("matched"));
    }

    @Test
    @DisplayName("主播与通道两项也应传到存储那一层, 并把可选项一起给出")
    void passesStreamerAndChannelThrough() {
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .streamer("甲主播").channel("群 111").text("甲的").build());
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .streamer("乙主播").channel("群 222").text("乙的").build());

        assertEquals(1, controller.timeline(null, false, null, null, "甲主播", null, null, 0, null)
                .getIntValue("matched"));
        assertEquals(1, controller.timeline(null, false, null, null, null, "群 222", null, 0, null)
                .getIntValue("matched"));
        assertEquals(0, controller.timeline(null, false, null, null, "甲主播", "群 222", null, 0, null)
                .getIntValue("matched"), "两项都给时应同时满足");

        // 筛选框里有哪几位主播、哪几个通道，由这里给而不是让界面从这一页事件里凑：
        // 凑出来的那张表在结果被截断时缺项，而缺了谁只有想筛它的人才看得见
        JSONObject all = query(null, false, null, null);
        assertEquals(List.of("乙主播", "甲主播"), all.getJSONArray("streamers").toJavaList(String.class),
                "两栏按字符序给，与谁最近出现过无关");
        assertEquals(List.of("群 111", "群 222"), all.getJSONArray("channels").toJavaList(String.class));
    }

    @Test
    @DisplayName("翻页游标应传下去, 并回出接着翻的那个游标")
    void pagesWithCursor() {
        for (int i = 0; i < 3; i++) {
            store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                    .text("第 " + i + " 条").build());
        }

        JSONObject first = controller.timeline(null, false, null, null, null, null, null, 2, null);
        assertEquals(2, first.getJSONArray("events").size());
        assertEquals(3, first.getIntValue("matched"));
        assertTrue(first.getBooleanValue("truncated"));
        String cursor = first.getString("nextCursor");
        assertNotNull(cursor);

        JSONObject second = controller.timeline(null, false, null, null, null, null, null, 2, cursor);
        assertEquals(1, second.getJSONArray("events").size());
        assertEquals("第 0 条", second.getJSONArray("events").getJSONObject(0).getString("text"));
        assertFalse(second.getBooleanValue("truncated"));
        assertNull(second.getString("nextCursor"), "翻到底就不该再给游标");
    }

    @Test
    @DisplayName("认不出的游标应明说, 而不是当成没给去从头翻")
    void rejectsBadCursor() {
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .text("一条").build());

        // 当成「没给」的话，「看更早」会一直翻回第一页，而屏幕上看起来只是「没有更早的了」
        JSONObject result = controller.timeline(null, false, null, null, null, null, null, 0, "第二页");

        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains("第二页"));
        assertTrue(result.getJSONArray("events") == null, "失败时不该顺手返回全部记录");
    }

    private JSONObject query(String date, boolean problems, String type, String keyword) {
        return controller.timeline(date, problems, null, type, null, null, keyword, 0, null);
    }

    private JSONObject byCategory(String category) {
        return controller.timeline(null, false, category, null, null, null, null, 0, null);
    }
}
