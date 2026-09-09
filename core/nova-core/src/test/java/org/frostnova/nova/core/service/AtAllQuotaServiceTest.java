package org.frostnova.nova.core.service;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @全体成员 每日配额测试
 * <p>
 * 两个维度是 2026-08-05 用 OneBot 的 get_group_at_all_remain 实测出来的：
 * 往一个群发一次，该群的群额度减一，而**所有群看到的账号额度都减一**。
 * 起初只按群计数、上限 10 是错的，推多个群时守卫等于没守住。
 */
@DisplayName("@全体成员 每日配额")
class AtAllQuotaServiceTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 10000003L;

    private static final Long OTHER_GROUP = 10000001L;

    private AtAllQuotaService service(int botLimit, int sessionLimit) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setAtAllDailyLimit(botLimit);
        properties.getPush().setAtAllSessionDailyLimit(sessionLimit);
        return new AtAllQuotaService(properties);
    }

    @Test
    @DisplayName("账号额度由全部会话共享，这是真正先卡住的一道")
    void botQuotaIsSharedAcrossSessions() {
        AtAllQuotaService service = service(2, 100);

        assertTrue(service.tryConsume(PLATFORM, GROUP));
        assertTrue(service.tryConsume(PLATFORM, OTHER_GROUP));
        // 两个不同的群，各用一次就把账号额度用光了
        assertFalse(service.tryConsume(PLATFORM, 30003L), "账号额度应跨会话共享");
    }

    @Test
    @DisplayName("群额度各群独立")
    void sessionQuotaIsPerSession() {
        AtAllQuotaService service = service(100, 1);

        assertTrue(service.tryConsume(PLATFORM, GROUP));
        assertFalse(service.tryConsume(PLATFORM, GROUP));
        // 另一个群有自己的群额度
        assertTrue(service.tryConsume(PLATFORM, OTHER_GROUP));
    }

    @Test
    @DisplayName("被拒的那次不应吃掉另一维度的额度")
    void rejectedAttemptDoesNotConsumeOtherDimension() {
        AtAllQuotaService service = service(100, 1);

        assertTrue(service.tryConsume(PLATFORM, GROUP));
        // 这一次会因群额度用尽被拒
        assertFalse(service.tryConsume(PLATFORM, GROUP));
        assertFalse(service.tryConsume(PLATFORM, GROUP));

        // 账号维度只应记到那一次成功的，被拒的三次不能算进去
        assertEquals(1, service.usedByBot(PLATFORM));
    }

    @Test
    @DisplayName("两个维度都有余额才放行，先耗尽的那个说了算")
    void needsBothDimensions() {
        // 账号额度更紧：第 2 次就该被账号维度拦下，尽管群额度还剩很多
        AtAllQuotaService botTight = service(1, 100);
        assertTrue(botTight.tryConsume(PLATFORM, GROUP));
        assertFalse(botTight.tryConsume(PLATFORM, GROUP), "账号额度已满，即使群额度有余也应被拒");

        // 群额度更紧：换成群维度先拦
        AtAllQuotaService sessionTight = service(100, 1);
        assertTrue(sessionTight.tryConsume(PLATFORM, GROUP));
        assertFalse(sessionTight.tryConsume(PLATFORM, GROUP), "群额度已满，即使账号额度有余也应被拒");

        // 两者相同时，用满即止
        AtAllQuotaService even = service(5, 5);
        for (int i = 0; i < 5; i++) {
            assertTrue(even.tryConsume(PLATFORM, GROUP), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(even.tryConsume(PLATFORM, GROUP));
    }

    @Test
    @DisplayName("不同推送平台各自计算")
    void quotaIsPerPlatform() {
        AtAllQuotaService service = service(1, 100);

        assertTrue(service.tryConsume(PLATFORM, GROUP));
        assertTrue(service.tryConsume("another-bot", GROUP));
    }

    @Test
    @DisplayName("平台名带冒号时，账号维度与会话维度不混在一条账上")
    void colonInPlatformNameKeepsDimensionsApart() {
        AtAllQuotaService service = service(100, 100);

        // 平台 "a:1" 的账号维度用一次；平台 a 在群 1 的会话维度用一次。
        // 计数键若按 platform + ":" + num 拼字符串，这两个维度会拼出同一个 "a:1"，
        // 一条账被两个维度各记一次——这正是配额要分维度记时最不能有的混法
        assertTrue(service.tryConsume("a:1", 9L));
        assertTrue(service.tryConsume("a", 1L));

        assertEquals(1, service.usedByBot("a:1"), "平台 a:1 的账号额度只记自己花的那次");
        assertEquals(1, service.used("a", 1L), "平台 a 群 1 的会话额度只记自己花的那次");
    }

    @Test
    @DisplayName("上限为 0 或负数时该维度不限制")
    void zeroMeansUnlimited() {
        AtAllQuotaService unlimited = service(0, 0);
        for (int i = 0; i < 50; i++) {
            assertTrue(unlimited.tryConsume(PLATFORM, GROUP));
        }

        assertTrue(service(-1, -1).tryConsume(PLATFORM, GROUP));
    }

    @Test
    @DisplayName("已用次数应可查，未用过时为 0")
    void reportsUsage() {
        AtAllQuotaService service = service(10, 20);

        assertEquals(0, service.used(PLATFORM, GROUP));
        assertEquals(0, service.usedByBot(PLATFORM));

        service.tryConsume(PLATFORM, GROUP);
        service.tryConsume(PLATFORM, OTHER_GROUP);

        assertEquals(1, service.used(PLATFORM, GROUP));
        assertEquals(2, service.usedByBot(PLATFORM), "账号维度应把两个群的都算上");
    }
}
