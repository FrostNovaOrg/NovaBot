package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.SessionQuietHoursService.Mode;
import org.frostnova.nova.core.service.SessionQuietHoursService.Setting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话级静音时段的存取
 * <p>
 * 没设过的会话必须读成「跟全局」：这是与这一项出现之前一模一样的那一档，
 * 读成别的哪一档，升级的那一刻起就有群开始收不到（或半夜被吵）。
 */
@DisplayName("会话级静音时段的存取")
class SessionQuietHoursServiceTest {
    private static final String PLATFORM = "qq-onebot";

    @TempDir
    Path dir;

    @Test
    @DisplayName("没设过的会话读成「跟全局」")
    void unsetSessionFollowsGlobal() {
        SessionQuietHoursService service = service(new NovaStateStore(new NovaCoreProperties()));

        Setting setting = service.get(PLATFORM, 10000003L);
        assertEquals(Mode.FOLLOW, setting.mode());
        assertTrue(service.all().isEmpty(), "没设过的会话不该出现在清单里");
    }

    @Test
    @DisplayName("三档存进去读回来一致")
    void threeModesRoundTrip() {
        SessionQuietHoursService service = service(new NovaStateStore(new NovaCoreProperties()));

        service.set(PLATFORM, 1L, Mode.CUSTOM, "23:00", "08:00");
        service.set(PLATFORM, 2L, Mode.OFF, null, null);
        service.set(PLATFORM, 3L, Mode.FOLLOW, null, null);

        Setting custom = service.get(PLATFORM, 1L);
        assertEquals(Mode.CUSTOM, custom.mode());
        assertEquals("23:00", custom.start());
        assertEquals("08:00", custom.end());

        Setting off = service.get(PLATFORM, 2L);
        assertEquals(Mode.OFF, off.mode());

        assertEquals(Mode.FOLLOW, service.get(PLATFORM, 3L).mode());

        List<Setting> all = service.all();
        assertEquals(2, all.size(), "跟全局那一档不占记录: " + all);
        assertEquals(1L, all.get(0).num());
        assertEquals(2L, all.get(1).num());
    }

    @Test
    @DisplayName("清除即回到跟全局")
    void clearingFallsBackToGlobal() {
        SessionQuietHoursService service = service(new NovaStateStore(new NovaCoreProperties()));

        service.set(PLATFORM, 1L, Mode.CUSTOM, "23:00", "08:00");
        service.set(PLATFORM, 1L, Mode.FOLLOW, "23:00", "08:00");
        assertEquals(Mode.FOLLOW, service.get(PLATFORM, 1L).mode());

        service.set(PLATFORM, 1L, Mode.OFF, null, null);
        service.set(PLATFORM, 1L, null, null, null);
        assertEquals(Mode.FOLLOW, service.get(PLATFORM, 1L).mode());
        assertTrue(service.all().isEmpty());
    }

    @Test
    @DisplayName("只作用于指定会话，平台不同算两个会话")
    void settingIsPerSession() {
        SessionQuietHoursService service = service(new NovaStateStore(new NovaCoreProperties()));

        service.set(PLATFORM, 1L, Mode.OFF, null, null);

        assertEquals(Mode.OFF, service.get(PLATFORM, 1L).mode());
        assertEquals(Mode.FOLLOW, service.get(PLATFORM, 2L).mode());
        assertEquals(Mode.FOLLOW, service.get("other-bot", 1L).mode());
    }

    @Test
    @DisplayName("重启（新建服务对象读同一份状态）后仍在")
    void survivesRestart() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("live.json").toString());

        NovaStateStore first = new NovaStateStore(properties);
        SessionQuietHoursService before = service(first);
        before.set(PLATFORM, 1L, Mode.CUSTOM, "22:30", "07:15");
        before.set(PLATFORM, 2L, Mode.OFF, null, null);
        first.save();

        NovaStateStore second = new NovaStateStore(properties);
        second.onApplicationReadyEvent();
        try {
            SessionQuietHoursService after = service(second);
            Setting custom = after.get(PLATFORM, 1L);
            assertEquals(Mode.CUSTOM, custom.mode());
            assertEquals("22:30", custom.start());
            assertEquals("07:15", custom.end());
            assertEquals(Mode.OFF, after.get(PLATFORM, 2L).mode());
            assertEquals(Mode.FOLLOW, after.get(PLATFORM, 3L).mode());
        } finally {
            second.onContextClosedEvent();
        }
    }

    @Test
    @DisplayName("状态件里这一条读不出（手改坏、或新版写的形状）：当跟全局，不抛")
    void unreadableRecordFollowsGlobal() {
        NovaStateStore store = new NovaStateStore(new NovaCoreProperties());
        store.write("SessionQuietHours", data -> {
            data.put(PLATFORM + ":1", "off");
            data.put(PLATFORM + ":2", 42);
            data.put(PLATFORM + ":3", List.of("custom", "22:00", "07:00"));
            data.put(PLATFORM + ":4", JSON.parseObject("{\"mode\":\"someday\"}"));
            data.put(PLATFORM + ":5", JSON.parseObject("{\"mode\":{\"kind\":\"custom\"}}"));
        });
        SessionQuietHoursService service = service(store);

        for (long num = 1; num <= 5; num++) {
            long current = num;
            Setting setting = assertDoesNotThrow(() -> service.get(PLATFORM, current), "第 " + num + " 条");
            assertEquals(Mode.FOLLOW, setting.mode(), "第 " + num + " 条");
        }
        assertTrue(service.all().isEmpty(), "读不出的记录不该出现在清单里: " + service.all());
    }

    @Test
    @DisplayName("记录不是对象（数字、数组、真假、null、字符串）：每一种都 warn 一句并当跟全局；键根本没有时不 warn")
    void nonObjectRecordWarns() {
        Object[] shapes = {42, 12.5, List.of(1, 2), true, false, null, "off"};
        NovaStateStore store = new NovaStateStore(new NovaCoreProperties());
        store.write("SessionQuietHours", data -> {
            for (int i = 0; i < shapes.length; i++) {
                data.put(PLATFORM + ":" + (i + 1), shapes[i]);
            }
        });
        SessionQuietHoursService service = service(store);

        for (int i = 0; i < shapes.length; i++) {
            long num = i + 1;
            Setting[] read = new Setting[1];
            List<String> warns = captureWarns(() -> read[0] = service.get(PLATFORM, num));
            assertEquals(Mode.FOLLOW, read[0].mode(), "形状 " + shapes[i]);
            assertEquals(1, warns.size(), "形状 " + shapes[i] + " 该 warn 一句: " + warns);
            assertTrue(warns.get(0).contains("读不出，按跟全局处理"), warns.get(0));
        }

        List<String> absent = captureWarns(() -> assertEquals(Mode.FOLLOW, service.get(PLATFORM, 999L).mode()));
        assertTrue(absent.isEmpty(), "从没设过的会话不该 warn: " + absent);
    }

    @Test
    @DisplayName("整个命名空间坏了（字符串、数组、数字）：all 不抛，warn 一句，当没有任何会话设过")
    void brokenNamespaceListsNothing() throws Exception {
        for (String shape : new String[]{"\"oops\"", "[1,2]", "42"}) {
            Path sub = Files.createDirectories(dir.resolve("case" + Math.abs(shape.hashCode())));
            Files.writeString(sub.resolve("state.json"), "{\"SessionQuietHours\":" + shape + "}");
            NovaCoreProperties properties = new NovaCoreProperties();
            properties.getLive().setLiveDataPath(sub.resolve("live.json").toString());
            NovaStateStore store = new NovaStateStore(properties);
            store.onApplicationReadyEvent();
            try {
                SessionQuietHoursService service = service(store);
                List<Setting>[] listed = new List[1];
                List<String> warns = captureWarns(() -> listed[0] = assertDoesNotThrow(service::all, shape));
                assertTrue(listed[0].isEmpty(), shape + ": " + listed[0]);
                assertEquals(1, warns.size(), shape + " 该 warn 一句: " + warns);
                assertEquals(Mode.FOLLOW, assertDoesNotThrow(() -> service.get(PLATFORM, 1L)).mode(), shape);
            } finally {
                store.onContextClosedEvent();
            }
        }
    }

    /**
     * 摘下服务落下的全部 WARN 原文（格式化后）
     */
    private static List<String> captureWarns(Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SessionQuietHoursService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream()
                    .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();
        } finally {
            logger.detachAppender(appender);
        }
    }

    private SessionQuietHoursService service(NovaStateStore store) {
        return new SessionQuietHoursService(store);
    }
}
