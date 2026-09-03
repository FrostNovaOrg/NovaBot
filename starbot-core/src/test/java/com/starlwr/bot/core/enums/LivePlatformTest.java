package com.starlwr.bot.core.enums;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 直播平台标识测试
 * <p>
 * 这里量的是<b>标识串一路上不许变形</b>。标识串是配置文件、数据源与数据库里
 * 按平台分键的那个值：中途被规整一次（去了空格、转了小写、认不得就换成默认值），
 * 症状不是报错，而是既有部署的推送用户与直播数据<b>整片读不回来</b>——
 * 看着像数据没了，其实是存在另一个键下面。
 * <p>
 * 本类刻意不写任何真实平台的标识串：核心不认识任何一个具体平台，
 * 认得哪几个平台是插件那一侧的事，那一侧另有回归用例钉着各自的串。
 */
@DisplayName("直播平台标识")
class LivePlatformTest {
    private static LiveStreamerInfo source() {
        return new LiveStreamerInfo(10001L, "测试主播", 20002L);
    }

    @Test
    @DisplayName("同一标识串取到的是同一个实例")
    void sameIdSameInstance() {
        assertSame(LivePlatform.of("plat-same"), LivePlatform.of("plat-same"));
    }

    @Test
    @DisplayName("标识串原样保留，不做去空格转小写一类的规整")
    void idKeptVerbatim() {
        assertEquals("Plat-Mixed_Case", LivePlatform.of("Plat-Mixed_Case").id());
        assertEquals(" plat-pad ", LivePlatform.of(" plat-pad ").id());
        assertEquals("平台-中文串", LivePlatform.of("平台-中文串").id());
    }

    @Test
    @DisplayName("未登记的标识串照原串给出实例，不抛也不丢")
    void unregisteredIdIsKeptNotRejected() {
        LivePlatform unknown = LivePlatform.of("plat-never-registered");

        assertEquals("plat-never-registered", unknown.id());
        assertEquals("plat-never-registered", unknown.displayName());
    }

    @Test
    @DisplayName("登记方给的显示名生效，标识串不受影响")
    void displayNameComesFromRegistrant() {
        LivePlatform platform = LivePlatform.of("plat-shown", "有显示名的平台");

        assertEquals("plat-shown", platform.id());
        assertEquals("有显示名的平台", platform.displayName());
    }

    @Test
    @DisplayName("先按串取用过、后来才登记的，显示名补得上")
    void lateRegistrationFillsDisplayName() {
        LivePlatform early = LivePlatform.of("plat-late");
        assertEquals("plat-late", early.displayName());

        LivePlatform registered = LivePlatform.of("plat-late", "迟到的显示名");

        assertSame(early, registered);
        assertEquals("迟到的显示名", early.displayName());
        assertEquals("plat-late", early.id());
    }

    @Test
    @DisplayName("显示名给空白时退回标识串，不会把显示名抹成空")
    void blankDisplayNameFallsBackToId() {
        LivePlatform platform = LivePlatform.of("plat-blank", "原显示名");
        LivePlatform again = LivePlatform.of("plat-blank", "   ");

        assertSame(platform, again);
        assertEquals("原显示名", again.displayName());
        assertEquals("plat-blank-only", LivePlatform.of("plat-blank-only", "  ").id());
        assertEquals("plat-blank-only", LivePlatform.of("plat-blank-only").displayName());
    }

    @Test
    @DisplayName("相等与哈希按标识串判")
    void equalityByIdOnly() {
        LivePlatform a = LivePlatform.of("plat-eq", "甲显示名");
        LivePlatform b = LivePlatform.of("plat-eq");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, LivePlatform.of("plat-eq-other"));
    }

    @Test
    @DisplayName("空标识串不许登记")
    void blankIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> LivePlatform.of(null));
        assertThrows(IllegalArgumentException.class, () -> LivePlatform.of(""));
        assertThrows(IllegalArgumentException.class, () -> LivePlatform.of("   "));
    }

    @Test
    @DisplayName("打印出来的就是标识串本身")
    void toStringIsId() {
        assertEquals("plat-print", LivePlatform.of("plat-print", "打印用显示名").toString());
    }

    @Test
    @DisplayName("事件上的平台字段与标识串一字不差")
    void eventCarriesIdVerbatim() {
        LivePlatform platform = LivePlatform.of("plat-event", "事件用显示名");

        LiveOffEvent event = new LiveOffEvent(platform, source(), Instant.parse("2026-09-03T00:00:00Z"));

        assertEquals("plat-event", event.getPlatform());
    }

    @Test
    @DisplayName("事件序列化后 platform 字段仍是标识串，反序列化取回同一串")
    void eventSerializationKeepsId() {
        LivePlatform platform = LivePlatform.of("plat-json", "序列化用显示名");
        LiveOffEvent event = new LiveOffEvent(platform, source(), Instant.parse("2026-09-03T00:00:00Z"));

        String json = JSON.toJSONString(event);
        JSONObject parsed = JSON.parseObject(json);

        assertEquals("plat-json", parsed.getString("platform"));
        assertEquals("plat-json", JSON.parseObject(json, LiveOffEvent.class).getPlatform());
    }

    @Test
    @DisplayName("用标识串构造与用平台实例构造，落到事件上的字节一样")
    void stringAndPlatformConstructorsAgree() {
        Instant at = Instant.parse("2026-09-03T00:00:00Z");

        LiveOffEvent byString = new LiveOffEvent("plat-both", source(), at);
        LiveOffEvent byPlatform = new LiveOffEvent(LivePlatform.of("plat-both", "两路构造"), source(), at);

        assertEquals(JSON.toJSONString(byString), JSON.toJSONString(byPlatform));
    }
}
