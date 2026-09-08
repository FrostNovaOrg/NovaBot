package com.starlwr.bot.core.timeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间线大类的判据
 * <p>
 * 大类是给筛选用的：屏幕上一排药丸，一类一枚。它与事件类型是两层——类型答的是
 * 「这一条是什么事」，大类答的是「这一条归谁管」。
 * <p>
 * 这里钉两件事。一是<b>每个类型都归了一个大类</b>：漏标的表现不是报错，
 * 而是那一类事件在任何一枚药丸下都筛不到，<b>而它在不筛的时候照常显示</b>——
 * 没有人会因此觉得哪里不对。这一条同时由构造参数在编译期兜着（新类型不标就编不过），
 * 这里再量一遍是因为编译期只管「标了」，管不了「标的是不是 null」。
 * <p>
 * 二是<b>清单只列有类型归属的大类</b>：一枚点下去必然空空如也的药丸，
 * 比没有那枚药丸更糟——使用者会以为是这台机器没发生过这类事，而实际上是这一类还没人记。
 */
@DisplayName("时间线大类")
class TimelineCategoryTest {
    @Test
    @DisplayName("每个事件类型都归了一个大类, 且每个大类都有中文说明")
    void everyTypeBelongsToACategory() {
        for (TimelineEventType type : TimelineEventType.values()) {
            assertNotNull(type.getCategory(), type + " 没有归到任何大类, 它在药丸上筛不到");
        }
        for (TimelineCategory category : TimelineCategory.values()) {
            assertNotNull(category.getDescription(), category + " 没有中文说明");
            assertFalse(category.getDescription().isBlank(), category + " 的中文说明是空的");
        }
    }

    @Test
    @DisplayName("推送那五类归推送, 状态变化与登录失效归连接")
    void mapsTheTypesInUseToday() {
        // 这几条是本笔定下的归属本身，不是从盘面上抄来的现状：改了它们就是改了筛选的口径
        assertEquals(TimelineCategory.PUSH, TimelineEventType.PUSH_MUTED.getCategory());
        assertEquals(TimelineCategory.PUSH, TimelineEventType.PUSH_PAUSED.getCategory());
        assertEquals(TimelineCategory.PUSH, TimelineEventType.PUSH_SENT.getCategory());
        assertEquals(TimelineCategory.PUSH, TimelineEventType.PUSH_FAILED.getCategory());
        assertEquals(TimelineCategory.PUSH, TimelineEventType.AT_ALL_SKIPPED.getCategory());
        assertEquals(TimelineCategory.LINK, TimelineEventType.PROBE_CHANGED.getCategory());
        assertEquals(TimelineCategory.LINK, TimelineEventType.LOGIN_LOST.getCategory());
    }

    /**
     * 阴性对照喂的是<b>造出来的一小撮类型</b>，不是这台机器此刻有哪几类
     * <p>
     * 拿现状当对照的话，这一格量的就成了「今天恰好还有大类是空的」——
     * 丙线把直播那几类记事加进来的那天它会变红，而变红的不是判法，是盘面。
     */
    @Test
    @DisplayName("大类清单只列有类型归属的那些")
    void listsOnlyCategoriesThatHaveTypes() {
        // 阳性：给两类，得两个大类，按大类自己的声明顺序
        assertEquals(List.of(TimelineCategory.PUSH, TimelineCategory.LINK),
                TimelineCategory.inUse(List.of(TimelineEventType.PUSH_SENT,
                        TimelineEventType.PROBE_CHANGED)),
                "顺序按大类的声明顺序, 不按谁先被喂进来");

        // 阴性：只给推送那一类，连接就不该出现在清单里
        List<TimelineCategory> onlyPush = TimelineCategory.inUse(List.of(TimelineEventType.PUSH_SENT));
        assertEquals(List.of(TimelineCategory.PUSH), onlyPush);
        assertFalse(onlyPush.contains(TimelineCategory.LINK),
                "一枚点下去必然空空如也的药丸, 比没有那枚药丸更糟");

        assertTrue(TimelineCategory.inUse(List.of()).isEmpty(), "一个类型都没有时清单是空的");

        // 同一个大类喂两次不该出现两枚药丸
        assertEquals(List.of(TimelineCategory.PUSH),
                TimelineCategory.inUse(List.of(TimelineEventType.PUSH_SENT,
                        TimelineEventType.PUSH_FAILED)));
    }

    /**
     * 除直播外，每个大类都得有类型往里记
     * <p>
     * 大类表是一次写全的，类型是一项一项接进来的，于是「声明了一个大类」与
     * 「真有事情记进那个大类」之间会长期空着一截。空着的那一截在屏幕上<b>什么都不显示</b>——
     * {@link TimelineCategory#inUse()} 把空大类摘掉，药丸那一排看起来整整齐齐，
     * 没有任何东西说「命令那一类其实一条都没接」。
     * <p>
     * 直播暂不在射程内：那几类记事由报告与数据那一侧记，接进来之前它本就是空的。
     * 丙线把直播接进来之后，把这里的例外去掉即可。
     */
    @Test
    @DisplayName("除直播外每个大类都至少有一个类型往里记")
    void everyCategoryExceptLiveHasTypes() {
        // 阳性：这台机器认得的全部类型喂进去，除直播外不该剩下任何空大类
        assertEquals(List.of(), categoriesWithoutTypes(Arrays.asList(TimelineEventType.values())),
                "这几个大类一条记事都没有, 药丸上根本不出现, 而那与「没发生过这类事」长得一样");

        // 阴性：只喂推送那一类，判法就该把其余几类如数报出来——
        // 恒返回空表的判法在上面那一问下同样是绿的
        assertEquals(List.of(TimelineCategory.LINK, TimelineCategory.COMMAND, TimelineCategory.ALERT,
                        TimelineCategory.SETTINGS, TimelineCategory.SYSTEM),
                categoriesWithoutTypes(List.of(TimelineEventType.PUSH_SENT)),
                "少了谁得报得出来, 报不出来的判法在阳性那一问下也是绿的");
    }

    /**
     * 除直播外，哪几个大类一个类型都没有，按声明顺序
     */
    private static List<TimelineCategory> categoriesWithoutTypes(Collection<TimelineEventType> types) {
        List<TimelineCategory> missing = new ArrayList<>(Arrays.asList(TimelineCategory.values()));
        missing.remove(TimelineCategory.LIVE);
        missing.removeAll(TimelineCategory.inUse(types));
        return missing;
    }

    @Test
    @DisplayName("不带参数的清单答的是这台机器认得的全部类型")
    void defaultListCoversEveryKnownType() {
        Set<TimelineCategory> expected = EnumSet.noneOf(TimelineCategory.class);
        for (TimelineEventType type : TimelineEventType.values()) {
            expected.add(type.getCategory());
        }

        assertEquals(List.copyOf(expected), TimelineCategory.inUse(),
                "两头都从类型表现算, 不许有人另抄一张会漏项的表");
    }

    @Test
    @DisplayName("按名称解析, 认不出时给 null 而不是当成不筛")
    void parseIsStrict() {
        assertEquals(TimelineCategory.PUSH, TimelineCategory.parse("PUSH"));
        assertEquals(TimelineCategory.PUSH, TimelineCategory.parse(" push "));
        assertNull(TimelineCategory.parse("PUSHY"), "认不出的名字不许当成不筛");
        assertNull(TimelineCategory.parse(""));
        assertNull(TimelineCategory.parse(null));
    }
}
