package org.frostnova.nova.bilibili.timeline;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.TimelineController;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.timeline.TimelineCategory;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直播类记事的端到端
 * <p>
 * 「直播」这一大类此前在 {@link TimelineCategory} 里声明着，却一条记事都没有，
 * 药丸上根本不出现——看起来与「这台机器没发生过开播」一模一样。
 * 这一格走完整条路：<b>开播事件真的到达监听者 → 真的 {@link TimelineStore} 落盘 →
 * {@link TimelineController} 下发 → 药丸清单里出现「直播」这一类</b>。
 * 少掉第一环，落盘再对也没有东西可落；少掉后两环，记下来的事在屏幕上筛不到。
 */
@DisplayName("直播类记事端到端")
class LiveCategoryEndToEndTest {
    @TempDir
    Path dir;

    private TimelineStore store;

    private TimelineController controller;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        store = new TimelineStore(properties);
        store.load();
        controller = new TimelineController(store);
    }

    @Test
    @DisplayName("开播：主播开了播, 日志页上出现「直播」这一类")
    void liveOnLandsOnTheLogPage() {
        new BilibiliLiveTimelineRecorder(store)
                .onLiveOn(new BilibiliLiveOnEvent(new LiveStreamerInfo(10001L, "主播甲", 20002L)));

        TimelineEvent event = assertLanded(TimelineCategory.LIVE, TimelineEventType.LIVE_ON);
        assertEquals("主播甲", event.channel(), "哪个主播开的播, 是这一条最要紧的一栏");
    }

    @Test
    @DisplayName("下播: 主播下了播, 日志页的「直播」类里多一条下播")
    void liveOffLandsOnTheLogPage() {
        new BilibiliLiveTimelineRecorder(store)
                .onLiveOff(new BilibiliLiveOffEvent(new LiveStreamerInfo(10001L, "主播甲", 20002L)));

        TimelineEvent event = assertLanded(TimelineCategory.LIVE, TimelineEventType.LIVE_OFF);
        assertEquals("主播甲", event.channel(), "哪个主播下的播, 是这一条最要紧的一栏");
    }

    /**
     * 走完整条路：落盘的那一份读得回来、接口下发得出去、药丸清单里有这一类
     * @param category 该出现的大类
     * @param type 该出现的类型
     * @return 接口下发的那一条
     */
    private TimelineEvent assertLanded(TimelineCategory category, TimelineEventType type) {
        List<TimelineEvent> hit = store.query(new TimelineStore.Filter(
                        null, false, category, type, null, null, null, 0), null).events();
        assertEquals(1, hit.size(),
                type + " 没有落进时间线, 或落进去了读不回来: " + store.recent());

        JSONObject result = controller.timeline(null, false, category.name(), null, null, null, null, 0, null);
        assertTrue(result.getBooleanValue("success"), result.getString("message"));
        assertEquals(1, result.getIntValue("matched"), "接口按这一类筛不到它");
        assertEquals(type.name(), result.getJSONArray("events").getJSONObject(0).getString("type"));

        JSONArray categories = result.getJSONArray("categories");
        List<String> pills = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            pills.add(categories.getJSONObject(i).getString("name"));
        }
        assertTrue(pills.contains(category.name()),
                category + " 那枚药丸不出现, 记下来的事在屏幕上筛不到: " + pills);

        return hit.get(0);
    }
}
