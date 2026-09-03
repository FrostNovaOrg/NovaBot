package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.timeline.TimelineEvent;
import com.starlwr.bot.core.timeline.TimelineEventType;
import com.starlwr.bot.core.timeline.TimelineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private JSONObject query(String date, boolean problems, String type, String keyword) {
        return controller.timeline(date, problems, type, null, null, keyword, 0);
    }
}
