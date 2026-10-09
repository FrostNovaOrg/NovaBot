package org.frostnova.nova.core.service;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.SessionQuietHoursService.Mode;
import org.frostnova.nova.core.service.SessionQuietHoursService.Setting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private SessionQuietHoursService service(NovaStateStore store) {
        return new SessionQuietHoursService(store);
    }
}
