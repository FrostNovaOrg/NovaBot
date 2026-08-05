package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @全体成员 每日配额测试
 */
@DisplayName("@全体成员 每日配额")
class AtAllQuotaServiceTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 10000003L;

    private AtAllQuotaService service(int limit) {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setAtAllDailyLimit(limit);
        return new AtAllQuotaService(properties);
    }

    @Test
    @DisplayName("额度内应放行，用满后拒绝")
    void allowsUpToLimit() {
        AtAllQuotaService service = service(3);

        assertTrue(service.tryConsume(PLATFORM, GROUP));
        assertTrue(service.tryConsume(PLATFORM, GROUP));
        assertTrue(service.tryConsume(PLATFORM, GROUP));
        assertFalse(service.tryConsume(PLATFORM, GROUP), "第 4 次应超出上限");
    }

    @Test
    @DisplayName("配额按会话分别计算")
    void quotaIsPerSession() {
        AtAllQuotaService service = service(1);

        assertTrue(service.tryConsume(PLATFORM, GROUP));
        assertFalse(service.tryConsume(PLATFORM, GROUP));
        // 另一个群有自己的额度，不该被上一个群用掉
        assertTrue(service.tryConsume(PLATFORM, 30003L));
    }

    @Test
    @DisplayName("不同推送平台的同一群号也应各自计算")
    void quotaIsPerPlatform() {
        AtAllQuotaService service = service(1);

        assertTrue(service.tryConsume(PLATFORM, GROUP));
        assertTrue(service.tryConsume("another-bot", GROUP));
    }

    @Test
    @DisplayName("上限为 0 或负数时不限制")
    void zeroMeansUnlimited() {
        AtAllQuotaService unlimited = service(0);
        for (int i = 0; i < 50; i++) {
            assertTrue(unlimited.tryConsume(PLATFORM, GROUP));
        }

        assertTrue(service(-1).tryConsume(PLATFORM, GROUP));
    }

    @Test
    @DisplayName("已用次数应可查，未用过时为 0")
    void reportsUsage() {
        AtAllQuotaService service = service(10);

        assertEquals(0, service.used(PLATFORM, GROUP));
        service.tryConsume(PLATFORM, GROUP);
        service.tryConsume(PLATFORM, GROUP);
        assertEquals(2, service.used(PLATFORM, GROUP));
    }

    @Test
    @DisplayName("超出上限后计数仍继续累加，不影响后续判定")
    void keepsRejectingAfterLimit() {
        AtAllQuotaService service = service(1);

        assertTrue(service.tryConsume(PLATFORM, GROUP));
        assertFalse(service.tryConsume(PLATFORM, GROUP));
        assertFalse(service.tryConsume(PLATFORM, GROUP), "超限后不应因计数溢出而意外放行");
    }
}
