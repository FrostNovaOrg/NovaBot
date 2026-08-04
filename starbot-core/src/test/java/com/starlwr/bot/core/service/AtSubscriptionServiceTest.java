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
}
