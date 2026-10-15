package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.event.live.BilibiliCaptainEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliCommanderEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliFreeGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliPaidGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliRandomGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliSuperChatEvent;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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

        // 播报处理器已搬进报告插件，本模块够不着它；这里验发布的事件把人与等级带全，
        // 「图与文字里逐人写清」由报告插件那一侧的处理器与画图测试盯着
        assertEquals(1, digests().size(), "一阵只播一条");
        BilibiliOfflineRewardDigestEvent.Contribution person = onlyPerson();
        assertEquals("张三", person.getUname(), "要说清是谁");
        assertEquals(3, person.getGuardLevel(), "要说清上了什么: 舰长");
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
    @DisplayName("小额礼物保留给各群按门槛筛选")
    void retainsSmallRewardsForPerGroupThreshold() {
        collector.onLiveEvent(gift(50001L, "张三", "心动盲盒", 99.0));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertEquals(9900L, onlyPerson().getGiftAmountFen(), "金额门槛由各群单独筛选");
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
    @DisplayName("两人各六十元均保留各自合计")
    void thresholdIsPerPerson() {
        collector.onLiveEvent(gift(50001L, "张三", "辣条", 60.0));
        collector.onLiveEvent(gift(50002L, "李四", "辣条", 60.0));
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        collector.sweep();
        assertEquals(List.of(6000L, 6000L), digests().get(0).getContributions().stream()
                .map(BilibiliOfflineRewardDigestEvent.Contribution::getGiftAmountFen).toList());
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
        assertEquals(6000L, onlyPerson().getGiftAmountFen(), "醒目留言的钱不算进礼物合计");
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
        assertEquals(400L, onlyPerson().getGiftAmountFen(), "盲盒按盒价，群设置再筛门槛");
        published.clear();

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

    @Test
    @DisplayName("礼物时间来自送出时刻而非到达时刻")
    void preservesSentTimeInsteadOfArrival() {
        clock.now = Instant.parse("2026-10-15T12:16:10Z");
        Instant sent = Instant.parse("2026-10-15T12:15:30Z");
        BilibiliPaidGiftEvent event = new BilibiliPaidGiftEvent(source(), sender(50001L, "甲"),
                new GiftInfo(1L, "小花花", 120.0, 1, null), 120.0, sent);
        collector.onLiveEvent(event);
        clock.advance(Duration.ofSeconds(61));
        collector.sweep();
        assertEquals(1, digests().size(), "应发布有礼物的播报");
        assertEquals(sent, onlyPerson().getGifts().get(0).firstSentAt(), "送出时间不能换成收到时间");
    }

    @Test
    @DisplayName("同名礼物保留最早与最晚送出时间")
    void mergesTimeRangeEvenOutOfOrder() {
        Instant first = clock.instant().minusSeconds(420);
        Instant last = clock.instant();
        collector.onLiveEvent(new BilibiliPaidGiftEvent(source(), sender(50001L, "甲"),
                new GiftInfo(1L, "小花花", 60.0, 1, null), 60.0, last));
        collector.onLiveEvent(new BilibiliPaidGiftEvent(source(), sender(50001L, "甲"),
                new GiftInfo(1L, "小花花", 60.0, 1, null), 60.0, first));
        clock.advance(Duration.ofSeconds(61));
        collector.sweep();
        var line = onlyPerson().getGifts().get(0);
        assertEquals(first, line.firstSentAt(), "合并后不能丢掉最早送出时间");
        assertEquals(last, line.lastSentAt());
    }

    @Test
    @DisplayName("同分钟同名礼物合并数量并分别保留各礼物时间")
    void preservesMinutesAndMergedCounts() {
        Instant first = Instant.parse("2026-10-15T12:15:03Z");
        clock.now = Instant.parse("2026-10-15T12:32:00Z");
        for (int count : List.of(3, 2)) {
            collector.onLiveEvent(new BilibiliPaidGiftEvent(source(), sender(1L, "甲"),
                    new GiftInfo(1L, "小花花", 60.0, count, null), 60.0,
                    count == 3 ? first : first.plusSeconds(30)));
        }
        collector.onLiveEvent(new BilibiliPaidGiftEvent(source(), sender(1L, "甲"),
                new GiftInfo(2L, "甜梦花", 60.0, 1, null), 60.0, first.plusSeconds(16 * 60)));
        clock.advance(Duration.ofSeconds(61));
        collector.sweep();
        var gifts = onlyPerson().getGifts();
        assertEquals(List.of(5, 1), gifts.stream().map(BilibiliOfflineRewardDigestEvent.GiftLine::count).toList(),
                "同名礼物必须合并数量");
        assertEquals(first, gifts.get(0).firstSentAt());
        assertEquals(first.plusSeconds(30), gifts.get(0).lastSentAt());
        assertEquals(first.plusSeconds(16 * 60), gifts.get(1).firstSentAt());
    }

    @Test
    @DisplayName("六十元礼物保留供默认五十元群播报")
    void retainsSixtyYuanForDefaultThreshold() {
        collector.onLiveEvent(gift(50001L, "甲", "小花花", 60));
        clock.advance(Duration.ofSeconds(61));
        collector.sweep();
        assertEquals(1, digests().size(), "默认门槛降为五十元后六十元不能丢失");
    }

    @Test
    @DisplayName("缺少送出时间时使用到达时刻")
    void fallsBackToArrivalWhenTimestampMissing() {
        var gift = org.mockito.Mockito.spy(gift(50001L, "甲", "小花花", 120));
        when(gift.getTimestamp()).thenReturn(0L);
        Instant arrival = clock.instant();
        collector.onLiveEvent(gift);
        clock.advance(Duration.ofSeconds(61));
        collector.sweep();
        assertEquals(arrival, onlyPerson().getGifts().get(0).firstSentAt(), "缺少平台时间才用到达时刻");
    }

    @Test
    @DisplayName("提醒发出后才到的下播前礼物进入普通播报并保留送出时间")
    void broadcastsRewardsArrivingAfterReminderWasSent() {
        Instant end = clock.instant();
        when(stateGate.isLiving(STREAMER_UID)).thenReturn(true);
        sentGift(1, "先到", 60, end.minusSeconds(8));
        closeLive();
        clock.advance(Duration.ofSeconds(15));
        collector.sweep();
        assertEquals(1, digests().size(), "先到礼物应已发出提醒");
        assertTrue(digests().get(0).isClosingReminder());
        published.clear();
        sentGift(2, "晚到", 100, end.minusSeconds(3));
        clock.advance(Duration.ofSeconds(61));
        collector.sweep();
        assertEquals(1, digests().size(), "提醒发出后才到的礼物不能丢失");
        assertFalse(digests().get(0).isClosingReminder(), "极晚礼物要进入普通播报");
        assertEquals("晚到", onlyPerson().getUname());
        var gift = onlyPerson().getGifts().get(0);
        assertEquals(end.minusSeconds(3), gift.firstSentAt(), "极晚礼物要保留自己的送出时间");
        assertEquals(end.minusSeconds(3), gift.lastSentAt());
    }

    @Test
    @DisplayName("闸门先下播时已暂存的最后十秒礼物移入提醒")
    void movesRacingOfflineRewardIntoReminder() {
        Instant end = clock.instant();
        sentGift(2, "B", 100, end.minusSeconds(3));
        closeLive();
        clock.advance(Duration.ofSeconds(15));
        collector.sweep();
        assertEquals(1, digests().size(), "状态闸门和下播事件之间的礼物也要提醒");
        assertTrue(digests().get(0).isClosingReminder());
        clock.advance(Duration.ofSeconds(61));
        collector.sweep();
        assertEquals(1, digests().size(), "移入提醒后不能再进普通播报");
    }

    @ParameterizedTest(name = "{displayName}（下播事件先到：{0}）")
    @ValueSource(booleans = {true, false})
    @DisplayName("下播后才收到的较早礼物与新礼物一起普通播报")
    void keepsOlderLateLiveGiftAndNewOfflineGift(boolean offEventArrivesFirst) {
        Instant end = clock.instant();
        when(stateGate.isLiving(STREAMER_UID)).thenReturn(false);
        if (offEventArrivesFirst) {
            closeLive();
        }
        clock.advance(Duration.ofSeconds(5));
        sentGift(3, "C", 60, end.minusSeconds(15));
        sentGift(4, "D", 60, end.plusSeconds(1));
        if (!offEventArrivesFirst) {
            collector.onLiveEvent(new BilibiliLiveOffEvent(source(), end));
        }
        clock.advance(BilibiliOfflineRewardDigestCollector.REMINDER_DELAY);
        collector.sweep();
        assertTrue(digests().isEmpty(), "更早在播礼物不能进最后十秒提醒");
        clock.advance(Duration.ofSeconds(61));
        collector.sweep();
        assertEquals(1, digests().size(), "晚到的较早礼物与新礼物应合成一条普通播报");
        assertFalse(digests().get(0).isClosingReminder());
        assertEquals(List.of("C", "D"), digests().get(0).getContributions().stream()
                .map(BilibiliOfflineRewardDigestEvent.Contribution::getUname).toList(),
                "下播后才收到的较早礼物不能丢失");
        var gifts = digests().get(0).getContributions().stream()
                .flatMap(person -> person.getGifts().stream()).toList();
        assertEquals(List.of(end.minusSeconds(15), end.plusSeconds(1)),
                gifts.stream().map(BilibiliOfflineRewardDigestEvent.GiftLine::firstSentAt).toList(),
                "普通播报要保留两份礼物各自的送出时间");
    }

    private void closeLive() {
        collector.onLiveEvent(new BilibiliLiveOffEvent(source(), clock.instant()));
        when(stateGate.isLiving(STREAMER_UID)).thenReturn(false);
    }

    private void sentGift(long uid, String name, double yuan, Instant sent) {
        collector.onLiveEvent(new BilibiliPaidGiftEvent(source(), sender(uid, name),
                new GiftInfo(1L, name, yuan, 1, null), yuan, sent));
    }

    @Test
    @DisplayName("最后十秒提醒收在播及晚到礼物并排除更早礼物")
    void remindsLiveAndLateRewardsOnly() {
        Instant end = clock.instant();
        when(stateGate.isLiving(STREAMER_UID)).thenReturn(true);
        sentGift(1, "A", 60, end.minusSeconds(8));
        sentGift(3, "C", 60, end.minusSeconds(15));
        sentGift(4, "边界", 60, end.minusSeconds(10));
        closeLive();
        clock.advance(Duration.ofSeconds(5));
        sentGift(2, "B", 60, end.minusSeconds(3));
        collector.sweep();
        assertTrue(digests().isEmpty(), "先等晚到礼物");
        clock.advance(Duration.ofSeconds(10));
        collector.sweep();
        assertEquals(1, digests().size(), "下播后应单独提醒最后十秒");
        assertTrue(digests().get(0).isClosingReminder());
        assertEquals(List.of("A", "边界", "B"), digests().get(0).getContributions().stream()
                .map(BilibiliOfflineRewardDigestEvent.Contribution::getUname).toList());
    }

    @Test
    @DisplayName("晚到的最后十秒礼物不会再进普通播报")
    void lateRewardDoesNotRepeatInOrdinaryBatch() {
        Instant end = clock.instant();
        closeLive();
        clock.advance(Duration.ofSeconds(5));
        sentGift(2, "B", 100, end.minusSeconds(3));
        clock.advance(Duration.ofSeconds(10));
        collector.sweep();
        published.clear();
        clock.advance(Duration.ofSeconds(61));
        collector.sweep();
        assertTrue(digests().isEmpty(), "B 只能在提醒里出现一次");
    }

    @Test
    @DisplayName("无人打赏时不发空提醒")
    void doesNotRemindWhenEmpty() {
        closeLive();
        clock.advance(Duration.ofSeconds(15));
        collector.sweep();
        assertTrue(digests().isEmpty(), "没有礼物不能发空提醒");
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
}
