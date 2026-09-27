package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.event.live.BilibiliCaptainEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliCommanderEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliFreeGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliPaidGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliRandomGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliSuperChatEvent;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.bilibili.handler.BilibiliOfflineRewardDigestPushHandler;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下播打赏汇总器测试
 * <p>
 * 时钟全部注入，不睡真实时间：一阵的「等 1 分钟」「最多 10 分钟」都靠推进测试时钟触发。
 */
@DisplayName("下播打赏汇总器")
class BilibiliOfflineRewardDigestCollectorTest {
    private static final long STREAMER_UID = 10001L;

    private static final long ROOM_ID = 20002L;

    private final List<Object> published = new ArrayList<>();

    private TestClock clock;

    private BilibiliLiveStateGate stateGate;

    private BilibiliOfflineRewardDigestCollector collector;

    @BeforeEach
    void setUp() {
        published.clear();
        clock = new TestClock(Instant.parse("2026-09-27T08:00:00Z"));
        stateGate = mock(BilibiliLiveStateGate.class);
        collector = new BilibiliOfflineRewardDigestCollector(new ApplicationEventPublisher() {
            @Override
            public void publishEvent(ApplicationEvent event) {
                published.add(event);
            }

            @Override
            public void publishEvent(Object event) {
                published.add(event);
            }
        }, stateGate, clock);
    }

    @Test
    @DisplayName("下播状态来一次上舰、之后一分钟无礼物，应发出一条播报、含此人与舰长")
    void broadcastsCaptainAfterQuietMinute() {
        collector.onLiveEvent(captain(50001L, "张三"));

        clock.advance(Duration.ofSeconds(59));
        collector.sweep();
        assertTrue(digests().isEmpty(), "还没安静够一分钟，先不播");

        clock.advance(Duration.ofSeconds(2));
        collector.sweep();

        assertEquals(1, digests().size(), "一阵只播一条");
        String content = render(digests().get(0));
        assertTrue(content.contains("张三"), "要说清是谁: " + content);
        assertTrue(content.contains("舰长"), "要说清上了什么: " + content);
    }

    @Test
    @DisplayName("间隔 50 秒的两份应并成一条播报")
    void mergesGiftsWithinWindow() {
        collector.onLiveEvent(gift(50001L, "张三", "心动盲盒", 60.0));
        clock.advance(Duration.ofSeconds(50));
        collector.onLiveEvent(gift(50001L, "张三", "辣条", 60.0));

        clock.advance(Duration.ofSeconds(59));
        collector.sweep();
        assertTrue(digests().isEmpty(), "第二份把一阵又续上了 1 分钟");

        clock.advance(Duration.ofSeconds(2));
        collector.sweep();

        assertEquals(1, digests().size(), "两份并成一条");
        BilibiliOfflineRewardDigestEvent.Contribution person = onlyPerson();
        assertEquals(2, person.getGifts().size(), "两种礼物都列出来");
        assertEquals(12000L, person.getGiftAmountFen());
    }

    @Test
    @DisplayName("每 50 秒一份不停时，第 10 分钟封顶播一条")
    void capsBatchAtTenMinutes() {
        for (int i = 0; i < 12; i++) {
            collector.onLiveEvent(gift(50001L, "张三", "辣条", 20.0));
            collector.sweep();
            assertTrue(digests().isEmpty(), "第 " + i + " 份之后都还在一阵里");
            clock.advance(Duration.ofSeconds(50));
        }

        collector.sweep();
        assertEquals(1, digests().size(), "到 10 分钟上限就先播这一阵");
        assertEquals(24000L, onlyPerson().getGiftAmountFen());
    }

    @Test
    @DisplayName("上限播完之后来的算下一阵")
    void laterRewardsFormNextBatch() {
        collector.onLiveEvent(gift(50001L, "张三", "辣条", 120.0));
        clock.advance(Duration.ofMinutes(10));
        collector.sweep();
        assertEquals(1, digests().size(), "第一阵按上限播");

        collector.onLiveEvent(gift(50001L, "李四", "心动盲盒", 120.0));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertEquals(2, digests().size(), "之后来的是第二阵");
    }

    @Test
    @DisplayName("单人合计 99 元不播")
    void skipsBelowThreshold() {
        collector.onLiveEvent(gift(50001L, "张三", "心动盲盒", 99.0));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertTrue(digests().isEmpty(), "不够 100 元就不发");
    }

    @Test
    @DisplayName("单人合计 100 元播一条")
    void broadcastsAtThreshold() {
        collector.onLiveEvent(gift(50001L, "张三", "心动盲盒", 100.0));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertEquals(1, digests().size(), "够 100 元就发");
    }

    @Test
    @DisplayName("两人各 60 元不播：够格按人算，不按全场合计")
    void thresholdIsPerPerson() {
        collector.onLiveEvent(gift(50001L, "张三", "辣条", 60.0));
        collector.onLiveEvent(gift(50002L, "李四", "辣条", 60.0));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertTrue(digests().isEmpty(), "两个人凑 120 也不算谁够格");
    }

    @Test
    @DisplayName("在播时的礼物与上舰一律不进这一类")
    void ignoresWhileLiving() {
        when(stateGate.isLiving(STREAMER_UID)).thenReturn(true);
        collector.onLiveEvent(captain(50001L, "张三"));
        collector.onLiveEvent(gift(50002L, "李四", "心动盲盒", 500.0));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertTrue(digests().isEmpty(), "在播时的一律不进这一类");
    }

    @Test
    @DisplayName("背包礼物实付为 0，不计")
    void ignoresBagGifts() {
        BilibiliPaidGiftEvent bag = gift(50001L, "张三", "小花花", 200.0);
        bag.setFromBag(true);
        collector.onLiveEvent(bag);
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertTrue(digests().isEmpty(), "背包礼物白来的，不算打赏");
    }

    @Test
    @DisplayName("醒目留言不算在内")
    void ignoresSuperChat() {
        collector.onLiveEvent(new BilibiliSuperChatEvent(source(), sender(50001L, "张三"), "加油", 300.0, clock.instant()));
        collector.onLiveEvent(gift(50001L, "张三", "辣条", 60.0));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertTrue(digests().isEmpty(), "醒目留言的钱不算进礼物合计");
    }

    @Test
    @DisplayName("免费礼物不算在内")
    void ignoresFreeGifts() {
        collector.onLiveEvent(new BilibiliFreeGiftEvent(source(), sender(50001L, "张三"),
                new GiftInfo(2L, "小心心", 0.0, 300, null), clock.instant()));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertTrue(digests().isEmpty(), "银瓜子礼物没有实付，不算");
    }

    @Test
    @DisplayName("背包送出的盲盒整条不计：不起阵、不列名、不续等")
    void ignoresBlindBoxFromBag() {
        // 只有背包盲盒：白来的，连一阵都不起
        BilibiliRandomGiftEvent onlyBagBox = blindBox();
        onlyBagBox.setFromBag(true);
        collector.onLiveEvent(onlyBagBox);
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertTrue(digests().isEmpty(), "背包里的盲盒不算打赏");

        // 混在够格礼物里：照样不列名
        BilibiliRandomGiftEvent bagBox = blindBox();
        bagBox.setFromBag(true);
        collector.onLiveEvent(bagBox);
        collector.onLiveEvent(gift(50001L, "张三", "辣条", 100.0));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertEquals(1, digests().size(), "够格的礼物照播");
        BilibiliOfflineRewardDigestEvent.Contribution person = onlyPerson();
        assertEquals(1, person.getGifts().size(), "背包盲盒不列名");
        assertEquals("辣条", person.getGifts().get(0).name());

        // 中途来的背包盲盒不算一份，不把等待续上
        published.clear();
        collector.onLiveEvent(gift(50001L, "张三", "辣条", 100.0));
        clock.advance(Duration.ofSeconds(50));
        BilibiliRandomGiftEvent laterBagBox = blindBox();
        laterBagBox.setFromBag(true);
        collector.onLiveEvent(laterBagBox);
        clock.advance(Duration.ofSeconds(11));
        collector.sweep();
        assertEquals(1, digests().size(), "背包盲盒不该把这一阵的等待续上");
    }

    @Test
    @DisplayName("1000 份 0.1 元合计正好 100 元要播")
    void addsUpToOneHundredYuanInCents() {
        for (int i = 0; i < 1000; i++) {
            collector.onLiveEvent(gift(50001L, "张三", "辣条", 0.1));
        }
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertEquals(1, digests().size(), "一千份一角钱正好凑够 100 元，不能少算成不够格");
        assertEquals(10000L, onlyPerson().getGiftAmountFen(), "合计正好 10000 分");
    }

    @Test
    @DisplayName("盲盒按买盒子花的钱计，名字记盒子")
    void countsBlindBoxAtBoxPrice() {
        collector.onLiveEvent(blindBox());
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertTrue(digests().isEmpty(), "盒子只花 4 元，开出物面值 200 不算数");

        collector.onLiveEvent(blindBox());
        collector.onLiveEvent(gift(50001L, "张三", "辣条", 100.0));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertEquals(1, digests().size(), "4 元盒子 + 100 元礼物够格");
        BilibiliOfflineRewardDigestEvent.Contribution person = onlyPerson();
        assertEquals(10400L, person.getGiftAmountFen());
        assertEquals("心动盲盒", person.getGifts().get(0).name());
        assertEquals(2, person.getGifts().get(0).count());
    }

    @Test
    @DisplayName("上舰不论金额都播，续费也算")
    void alwaysBroadcastsGuard() {
        BilibiliCaptainEvent renew = captain(50001L, "张三");
        renew.setOperateType(GuardOperateType.RENEWAL);
        collector.onLiveEvent(renew);
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();

        assertEquals(1, digests().size(), "续费也播");
        BilibiliOfflineRewardDigestEvent.Contribution person = onlyPerson();
        assertEquals(3, person.getGuardLevel());
        assertEquals(GuardOperateType.RENEWAL, person.getOperateType());
    }

    // —— 以下为夹具 ——

    /**
     * 推进式测试时钟，不睡真实时间
     */
    private static final class TestClock extends Clock {
        private Instant now;

        private TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private LiveStreamerInfo source() {
        return new LiveStreamerInfo(STREAMER_UID, "主播甲", ROOM_ID);
    }

    private UserInfo sender(long uid, String uname) {
        return new UserInfo(uid, uname);
    }

    private BilibiliPaidGiftEvent gift(long senderUid, String uname, String giftName, double charged) {
        GiftInfo gift = new GiftInfo(1L, giftName, charged, 1, null);
        BilibiliPaidGiftEvent event = new BilibiliPaidGiftEvent(source(), sender(senderUid, uname), gift, charged, clock.instant());
        event.setCharged(charged);
        return event;
    }

    private BilibiliCaptainEvent captain(long senderUid, String uname) {
        BilibiliCaptainEvent event = new BilibiliCaptainEvent(source(), sender(senderUid, uname), 198.0, 1, "月", clock.instant());
        event.setOperateType(GuardOperateType.ACTIVATION);
        return event;
    }

    /**
     * 买了 2 个心动盲盒：盒子 4 元，开出物面值 200 元
     */
    private BilibiliRandomGiftEvent blindBox() {
        GiftInfo box = new GiftInfo(3L, "心动盲盒", 2.0, 2, null);
        GiftInfo opened = new GiftInfo(4L, "幻想之翼", 100.0, 2, null);
        BilibiliRandomGiftEvent event = new BilibiliRandomGiftEvent(source(), sender(50001L, "张三"),
                box, opened, 4.0, 200.0, clock.instant());
        event.setCharged(4.0);
        return event;
    }

    private List<BilibiliOfflineRewardDigestEvent> digests() {
        return published.stream()
                .filter(BilibiliOfflineRewardDigestEvent.class::isInstance)
                .map(BilibiliOfflineRewardDigestEvent.class::cast)
                .toList();
    }

    private BilibiliOfflineRewardDigestEvent.Contribution onlyPerson() {
        BilibiliOfflineRewardDigestEvent digest = digests().get(0);
        assertEquals(1, digest.getContributions().size(), "该是一位够格的人");
        return digest.getContributions().get(0);
    }

    /**
     * 把汇总事件交给人的播报处理器，取出实际发出的消息
     */
    private String render(BilibiliOfflineRewardDigestEvent digest) {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
        NovaMessageSender sender = mock(NovaMessageSender.class);
        RevenueVisibilityService revenueVisibility = mock(RevenueVisibilityService.class);
        when(revenueVisibility.isVisible(anyString(), org.mockito.ArgumentMatchers.any(), anyLong())).thenReturn(true);

        BilibiliOfflineRewardDigestPushHandler handler =
                new BilibiliOfflineRewardDigestPushHandler(api, sender, revenueVisibility);
        handler.handle((NovaExternalBaseEvent) digest, pushMessage(handler));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        return captor.getValue().getContent();
    }

    private PushMessage pushMessage(BilibiliOfflineRewardDigestPushHandler handler) {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(handler.getDefaultParams());
        return message;
    }
}
