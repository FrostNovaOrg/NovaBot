package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「@我」订阅测试
 */
@DisplayName("@我订阅")
class AtSubscriptionServiceTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private static final Long STREAMER = 10001L;

    private AtSubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new AtSubscriptionService(new StarBotStateStore(new StarBotCoreProperties()));
    }

    @Test
    @DisplayName("订阅后应出现在名单中")
    void subscribeAddsToList() {
        assertEquals(AtSubscriptionService.Result.OK,
                service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L));

        assertTrue(service.contains(PLATFORM, GROUP, STREAMER, "live", 1L));
        assertEquals(List.of(1L), service.list(PLATFORM, GROUP, STREAMER, "live"));
    }

    @Test
    @DisplayName("重复订阅应被识别而非重复记录")
    void detectsDuplicateSubscription() {
        service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L);

        assertEquals(AtSubscriptionService.Result.ALREADY,
                service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L));
        assertEquals(1, service.list(PLATFORM, GROUP, STREAMER, "live").size());
    }

    @Test
    @DisplayName("取消订阅后应从名单移除")
    void unsubscribeRemovesFromList() {
        service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L);

        assertEquals(AtSubscriptionService.Result.OK,
                service.unsubscribe(PLATFORM, GROUP, STREAMER, "live", 1L));
        assertFalse(service.contains(PLATFORM, GROUP, STREAMER, "live", 1L));
    }

    @Test
    @DisplayName("未订阅时取消应被识别")
    void detectsUnsubscribeWithoutSubscription() {
        assertEquals(AtSubscriptionService.Result.ALREADY,
                service.unsubscribe(PLATFORM, GROUP, STREAMER, "live", 1L));
    }

    @Test
    @DisplayName("开播与动态的订阅应彼此独立")
    void typesAreIndependent() {
        service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L);

        assertTrue(service.contains(PLATFORM, GROUP, STREAMER, "live", 1L));
        assertFalse(service.contains(PLATFORM, GROUP, STREAMER, "dynamic", 1L),
                "只订阅了开播不应连带订阅动态");
    }

    @Test
    @DisplayName("不同群与不同主播的订阅应彼此独立")
    void groupsAndStreamersAreIndependent() {
        service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L);

        assertFalse(service.contains(PLATFORM, 40004L, STREAMER, "live", 1L));
        assertFalse(service.contains(PLATFORM, GROUP, 20002L, "live", 1L));
    }

    @Test
    @DisplayName("未订阅任何人时名单应为空而非报错")
    void listsEmptyWhenNoSubscription() {
        assertTrue(service.list(PLATFORM, GROUP, STREAMER, "live").isEmpty());
    }

    @Test
    @DisplayName("清空名单应移除全部订阅并返回人数")
    void clearRemovesEveryone() {
        service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L);
        service.subscribe(PLATFORM, GROUP, STREAMER, "live", 2L);
        service.subscribe(PLATFORM, GROUP, STREAMER, "dynamic", 3L);

        assertEquals(2, service.clear(PLATFORM, GROUP, STREAMER, "live"));

        assertTrue(service.list(PLATFORM, GROUP, STREAMER, "live").isEmpty());
        assertEquals(List.of(3L), service.list(PLATFORM, GROUP, STREAMER, "dynamic"),
                "清空一份名单不该波及同一主播的另一种订阅");
    }

    @Test
    @DisplayName("清空不存在的名单应返回 0 而非报错")
    void clearIsSafeWhenAbsent() {
        assertEquals(0, service.clear(PLATFORM, GROUP, STREAMER, "live"));
    }

    @Test
    @DisplayName("全量列出时应把键还原成平台、会话、主播与类型")
    void listsAllWithFieldsRestored() {
        service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L);

        List<AtSubscriptionService.Subscription> all = service.all();

        assertEquals(1, all.size());
        AtSubscriptionService.Subscription item = all.get(0);
        // 平台名含连字符，若切分方式写错会把 qq 与 onebot 拆开
        assertEquals(PLATFORM, item.platform());
        assertEquals(GROUP.longValue(), item.num());
        assertEquals(STREAMER.longValue(), item.streamerUid());
        assertEquals("live", item.type());
        assertEquals(List.of(1L), item.users());
    }

    @Test
    @DisplayName("全量列出的结果应能直接拿去取消订阅")
    void listedItemsCanBeUnsubscribed() {
        service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L);
        service.subscribe(PLATFORM, 40004L, 20002L, "dynamic", 2L);

        // 管理后台正是这么用的：把列出来的字段原样回传。一旦某个字段错位，
        // 界面上点「移除」会删掉另一份名单里的人，且当事人毫不知情
        for (AtSubscriptionService.Subscription item : service.all()) {
            for (Long user : item.users()) {
                assertEquals(AtSubscriptionService.Result.OK, service.unsubscribe(
                        item.platform(), item.num(), item.streamerUid(), item.type(), user));
            }
        }

        assertTrue(service.all().isEmpty());
    }

    @Test
    @DisplayName("人走光后残留的空名单不应出现在全量列表里")
    void skipsEmptyLeftovers() {
        service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L);
        service.unsubscribe(PLATFORM, GROUP, STREAMER, "live", 1L);

        // 取消订阅只移除人，空对象会留在状态文件里
        assertTrue(service.all().isEmpty(), "界面上不该出现一份 0 人的名单");
    }

    @Test
    @DisplayName("多份名单应按会话与主播排序")
    void sortsBySessionAndStreamer() {
        service.subscribe(PLATFORM, 40004L, STREAMER, "live", 1L);
        service.subscribe(PLATFORM, GROUP, 20002L, "live", 1L);
        service.subscribe(PLATFORM, GROUP, STREAMER, "live", 1L);

        assertEquals(List.of(GROUP + ":" + STREAMER, GROUP + ":20002", "40004:" + STREAMER),
                service.all().stream().map(s -> s.num() + ":" + s.streamerUid()).toList());
    }
}
