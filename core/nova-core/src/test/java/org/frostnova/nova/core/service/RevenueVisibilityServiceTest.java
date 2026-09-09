package org.frostnova.nova.core.service;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话级金额可见性测试
 * <p>
 * 默认值是这里最要紧的一条：默认值取错时，错误的形式是「已经发到群里了」，没有补救手段。
 */
@DisplayName("会话级金额可见性")
class RevenueVisibilityServiceTest {
    private static final String PLATFORM = "qq-onebot";

    private RevenueVisibilityService service;

    @BeforeEach
    void setUp() {
        service = new RevenueVisibilityService(new NovaStateStore(new NovaCoreProperties()));
    }

    @Test
    @DisplayName("群聊默认不展示金额")
    void hidesInGroupByDefault() {
        assertFalse(service.isVisible(PLATFORM, PushTargetType.GROUP, 10000003L));
    }

    @Test
    @DisplayName("私聊默认展示金额")
    void showsInFriendByDefault() {
        assertTrue(service.isVisible(PLATFORM, PushTargetType.FRIEND, 2000000002L));
    }

    @Test
    @DisplayName("会话类型未知时按群聊处理")
    void treatsUnknownTypeAsGroup() {
        assertFalse(service.isVisible(PLATFORM, null, 1L));
        assertFalse(service.isVisible(PLATFORM, PushTargetType.UNKNOWN, 1L));
    }

    @Test
    @DisplayName("显式设置应覆盖默认值，两个方向都要能覆盖")
    void explicitSettingOverridesDefault() {
        service.set(PLATFORM, 10000003L, true);
        assertTrue(service.isVisible(PLATFORM, PushTargetType.GROUP, 10000003L));

        service.set(PLATFORM, 2000000002L, false);
        assertFalse(service.isVisible(PLATFORM, PushTargetType.FRIEND, 2000000002L));
    }

    @Test
    @DisplayName("传 null 应清除设置并回到默认值")
    void clearingRestoresDefault() {
        service.set(PLATFORM, 10000003L, true);
        service.set(PLATFORM, 10000003L, null);

        assertNull(service.explicit(PLATFORM, 10000003L));
        assertFalse(service.isVisible(PLATFORM, PushTargetType.GROUP, 10000003L));
    }

    @Test
    @DisplayName("设置只作用于指定会话")
    void settingIsPerSession() {
        service.set(PLATFORM, 10000003L, true);

        assertTrue(service.isVisible(PLATFORM, PushTargetType.GROUP, 10000003L));
        assertFalse(service.isVisible(PLATFORM, PushTargetType.GROUP, 10000001L));
        assertFalse(service.isVisible("other-bot", PushTargetType.GROUP, 10000003L));
    }

    @Test
    @DisplayName("清单只列显式设置过的会话")
    void listsOnlyExplicitSettings() {
        service.set(PLATFORM, 10000001L, true);
        service.set(PLATFORM, 10000003L, false);
        // 从未设置过的会话即便被查询过，也不该出现在清单里
        service.isVisible(PLATFORM, PushTargetType.GROUP, 123L);

        List<RevenueVisibilityService.Setting> all = service.all();

        assertEquals(2, all.size());
        assertEquals(10000001L, all.get(0).num());
        assertTrue(all.get(0).visible());
        assertEquals(10000003L, all.get(1).num());
        assertFalse(all.get(1).visible());
    }

    @Test
    @DisplayName("平台名含连字符时仍能正确还原会话")
    void parsesPlatformWithHyphen() {
        service.set("qq-onebot", 10000003L, true);

        List<RevenueVisibilityService.Setting> all = service.all();

        assertEquals(1, all.size());
        assertEquals("qq-onebot", all.get(0).platform());
        assertEquals(10000003L, all.get(0).num());
    }
}
