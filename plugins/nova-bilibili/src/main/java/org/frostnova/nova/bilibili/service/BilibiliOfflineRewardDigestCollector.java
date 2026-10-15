package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.event.live.BilibiliCaptainEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliCommanderEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliGovernorEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliPaidGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliRandomGiftEvent;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 下播时段打赏汇总器
 * <p>
 * 主播不在播时收到的上舰与礼物先攒一小阵，停下来后合成一条汇总事件
 * （见 {@link BilibiliOfflineRewardDigestEvent}）。状态只放在本类的内存表里，
 * 程序重启时没播完的一阵直接丢掉——重启后补发一条旧闻没有意义。
 * <p>
 * 一阵的两个时限：从最后一份起安静 {@link #IDLE_WINDOW} 就播；
 * 从第一份起最多攒 {@link #MAX_WINDOW}，到了就先播这一阵、之后来的算下一阵。
 * 金额门槛由推送处理器按会话筛选，这里保留完整名单。
 * 在播时保留最近两分钟的原始打赏，下播后独立提醒最后十秒内的礼物。
 */
@Slf4j
@NovaComponent
public class BilibiliOfflineRewardDigestCollector {
    /**
     * 一阵停下来的标准：最后一份之后安静多久就播
     */
    static final Duration IDLE_WINDOW = Duration.ofMinutes(1);

    /**
     * 一阵的硬上限：从第一份算起最多攒多久，到了就先播这一阵，之后来的算下一阵
     */
    static final Duration MAX_WINDOW = Duration.ofMinutes(10);

    static final Duration RECENT_WINDOW = Duration.ofMinutes(2);

    static final Duration CLOSING_WINDOW = Duration.ofSeconds(10);

    static final Duration REMINDER_DELAY = Duration.ofSeconds(15);

    private final ApplicationEventPublisher publisher;

    private final BilibiliLiveStateGate stateGate;

    private final Clock clock;

    /**
     * 各主播手上攒着的那一阵。播报可能走到网络发送，因此发布一律在锁外做
     */
    private final Map<String, Batch> batches = new HashMap<>();

    private final Map<String, List<Reward>> recent = new HashMap<>();

    private final Map<String, List<Closing>> closings = new HashMap<>();

    @Autowired
    public BilibiliOfflineRewardDigestCollector(ApplicationEventPublisher publisher, BilibiliLiveStateGate stateGate) {
        this(publisher, stateGate, Clock.systemDefaultZone());
    }

    public BilibiliOfflineRewardDigestCollector(ApplicationEventPublisher publisher, BilibiliLiveStateGate stateGate, Clock clock) {
        this.publisher = publisher;
        this.stateGate = stateGate;
        this.clock = clock;
    }

    /**
     * 直播间事件流入口
     * <p>
     * 收哪几类写成 instanceof 链而不是拆成几个监听方法：口径里「背包礼物不计、
     * 醒目留言不算、银瓜子不算」说的是<b>收什么、不收什么</b>，写在一处才看得见边界。
     */
    @Order(-9000)
    @EventListener(NovaBaseLiveEvent.class)
    public void onLiveEvent(NovaBaseLiveEvent event) {
        if (event instanceof BilibiliLiveOffEvent off) {
            close(off);
        } else if (event instanceof BilibiliPaidGiftEvent gift) {
            // 背包礼物实付为 0、不计。认 fromBag 字段而不是拿金额反推，见 NovaLiveGiftEvent 的契约
            if (gift.isFromBag()) {
                return;
            }
            accumulateGift(event, gift.getSender(), gift.getGiftInfo(), paidFenOf(gift.getCharged(), gift.getValue()));
        } else if (event instanceof BilibiliRandomGiftEvent box) {
            // 背包里送出的盲盒同样整条不计：没花钱，不进合计、不列出来、也不算一份把等待续上
            if (box.isFromBag()) {
                return;
            }
            // 盲盒按买盒子花的钱：price 与 charged 是盒子的价，名字记的也是盒子本身
            accumulateGift(event, box.getSender(), box.getRandomGiftInfo(), paidFenOf(box.getCharged(), box.getPrice()));
        } else if (event instanceof BilibiliGovernorEvent guard) {
            accumulateGuard(event, guard.getSender(), 1, guard.getOperateType(), guard.getValue());
        } else if (event instanceof BilibiliCommanderEvent guard) {
            accumulateGuard(event, guard.getSender(), 2, guard.getOperateType(), guard.getValue());
        } else if (event instanceof BilibiliCaptainEvent guard) {
            accumulateGuard(event, guard.getSender(), 3, guard.getOperateType(), guard.getValue());
        }
        // 其余一律不算：醒目留言不在内，银瓜子礼物没有实付也不算
    }

    /**
     * 到点的各阵播出去。生产上由定时器每秒带一次，测试直接调、不睡真实时间
     */
    @Scheduled(fixedDelay = 1000)
    public void sweep() {
        Instant now = clock.instant();
        List<Batch> due = new ArrayList<>();
        synchronized (batches) {
            pruneRecent(now);
            for (List<Closing> windows : closings.values()) {
                for (Closing closing : windows) {
                    if (!closing.sent && !now.isBefore(closing.readyAt)) {
                        closing.sent = true;
                        due.add(closing.batch);
                    }
                }
                // 留住最新下播窗口，以识别重复的下播事件。
                Closing latest = windows.get(windows.size() - 1);
                windows.removeIf(window -> window.sent && window != latest
                        && now.isAfter(window.readyAt.plus(RECENT_WINDOW)));
            }
            Iterator<Batch> iterator = batches.values().iterator();
            while (iterator.hasNext()) {
                Batch batch = iterator.next();
                if (!now.isBefore(batch.lastAt.plus(IDLE_WINDOW)) || !now.isBefore(batch.firstAt.plus(MAX_WINDOW))) {
                    iterator.remove();
                    due.add(batch);
                }
            }
        }
        due.forEach(this::publish);
    }

    private void accumulateGift(NovaBaseLiveEvent event, UserInfo sender, GiftInfo gift, long amountFen) {
        if (gift == null || StringUtil.isBlank(gift.getName())) {
            return;
        }
        String name = gift.getName();
        int count = gift.getCount() == null || gift.getCount() < 1 ? 1 : gift.getCount();
        Instant sentAt = sentAt(event);
        record(event, sender, sentAt, person -> {
            person.giftAmountFen += amountFen;
            GiftAcc acc = person.gifts.computeIfAbsent(name, key -> new GiftAcc());
            acc.count += count;
            acc.amountFen += amountFen;
            if (acc.firstSentAt == null || sentAt.isBefore(acc.firstSentAt)) {
                acc.firstSentAt = sentAt;
            }
            if (acc.lastSentAt == null || sentAt.isAfter(acc.lastSentAt)) {
                acc.lastSentAt = sentAt;
            }
        });
    }

    private void accumulateGuard(NovaBaseLiveEvent event, UserInfo sender, int level, GuardOperateType operateType, Double amount) {
        // 上舰不论金额都播报，因此不看合计、只挂到人身上
        Instant sentAt = sentAt(event);
        record(event, sender, sentAt, person -> {
            if (person.guardSentAt != null && sentAt.isBefore(person.guardSentAt)) {
                return;
            }
            person.guardSentAt = sentAt;
            person.guardLevel = level;
            person.operateType = operateType;
            person.guardAmountFen = amount == null ? null : Math.round(amount * 100);
        });
    }

    private Instant sentAt(NovaBaseLiveEvent event) {
        return event.getTimestamp() > 0 ? Instant.ofEpochMilli(event.getTimestamp()) : clock.instant();
    }

    /**
     * 下播时刻与本场 setLiveEndTime 使用同一事件时间；排在同步绘制报告之前收好窗口。
     */
    private void close(BilibiliLiveOffEvent event) {
        if (event.getSource() == null || event.getSource().getUid() == null) {
            return;
        }
        Instant now = clock.instant();
        String key = keyOf(event);
        synchronized (batches) {
            pruneRecent(now);
            Closing closing = new Closing(event.getSource(), sentAt(event), now.plus(REMINDER_DELAY));
            List<Closing> windows = closings.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (windows.stream().anyMatch(window -> window.endAt.equals(closing.endAt))) {
                return;
            }
            windows.add(closing);
            List<Reward> rewards = recent.remove(key);
            if (rewards != null) {
                rewards.stream().filter(reward -> closing.contains(reward.sentAt)).forEach(closing.batch.rewards::add);
            }
            // 闸门先变为下播、下播事件稍后才送达时，收进普通阵的礼物也要移到提醒。
            Batch batch = batches.get(key);
            if (batch != null) {
                batch.rewards.removeIf(reward -> closing.contains(reward.sentAt));
                if (batch.rewards.isEmpty()) {
                    batches.remove(key);
                } else {
                    batch.firstAt = batch.rewards.get(0).receivedAt;
                    batch.lastAt = batch.rewards.get(batch.rewards.size() - 1).receivedAt;
                }
            }
        }
    }

    private static String keyOf(NovaBaseLiveEvent event) {
        return event.getPlatform() + "|" + event.getSource().getUid();
    }

    private void pruneRecent(Instant now) {
        recent.values().forEach(rewards -> rewards.removeIf(reward -> reward.receivedAt.isBefore(now.minus(RECENT_WINDOW))));
        recent.values().removeIf(List::isEmpty);
    }

    private void record(NovaBaseLiveEvent event, UserInfo sender, Instant sentAt, Consumer<Person> mutation) {
        LiveStreamerInfo source = event.getSource();
        if (source == null || source.getUid() == null || sender == null || !isIdentified(sender.getUid())) {
            return;
        }
        Instant now = clock.instant();
        String key = keyOf(event);
        Reward reward = new Reward(sentAt, now, sender.getUid(), sender.getUname(), mutation);
        List<Batch> due = new ArrayList<>(1);
        synchronized (batches) {
            for (Closing closing : closings.getOrDefault(key, List.of())) {
                if (!closing.sent && closing.contains(sentAt)) {
                    closing.batch.rewards.add(reward);
                    return;
                }
            }
            recent.computeIfAbsent(key, ignored -> new ArrayList<>()).add(reward);
            if (stateGate.isLiving(source.getUid())) {
                return;
            }
            Batch batch = batches.get(key);
            if (batch != null && !now.isBefore(batch.firstAt.plus(MAX_WINDOW))) {
                batches.remove(key);
                due.add(batch);
                batch = null;
            }
            if (batch == null) {
                batch = new Batch(source, now, false);
                batches.put(key, batch);
            }
            batch.lastAt = now;
            batch.rewards.add(reward);
        }
        due.forEach(this::publish);
    }

    private void publish(Batch batch) {
        Map<Long, Person> people = new LinkedHashMap<>();
        for (Reward reward : batch.rewards) {
            Person person = people.computeIfAbsent(reward.uid, uid -> new Person(uid, reward.uname));
            if (StringUtil.isNotBlank(reward.uname)) {
                person.uname = reward.uname;
            }
            reward.mutation.accept(person);
        }
        batch.rewards.clear();
        if (people.isEmpty()) {
            return;
        }
        var event = new BilibiliOfflineRewardDigestEvent(batch.source,
                people.values().stream().map(Person::toContribution).toList(), clock.instant());
        event.setClosingReminder(batch.closingReminder);
        publisher.publishEvent(event);
    }

    /**
     * 实付（分）：平台给了实扣就用实扣，没给回退到面值，而不是当作 0——
     * 把「不知道」记成「没花钱」会让合计凭空少一截，而且不会有任何报错。
     * 换算成「分」在记账的这一头就做完，合计是整数加法，不带小数误差
     */
    private static long paidFenOf(Double charged, Double fallback) {
        if (charged != null) {
            return Math.round(charged * 100);
        }
        return fallback == null ? 0L : Math.round(fallback * 100);
    }

    /**
     * 0 不是某一位观众，而是「平台没说是谁」，与 BilibiliLiveStatsAggregator 同一条界线
     */
    private static boolean isIdentified(Long uid) {
        return uid != null && uid > 0;
    }

    private record Reward(Instant sentAt, Instant receivedAt, Long uid, String uname, Consumer<Person> mutation) {
    }

    private static final class Closing {
        private final Instant endAt;
        private final Instant readyAt;
        private final Batch batch;
        private boolean sent;

        private Closing(LiveStreamerInfo source, Instant endAt, Instant readyAt) {
            this.endAt = endAt;
            this.readyAt = readyAt;
            this.batch = new Batch(source, readyAt, true);
        }

        private boolean contains(Instant sentAt) {
            return !sentAt.isBefore(endAt.minus(CLOSING_WINDOW)) && !sentAt.isAfter(endAt);
        }
    }

    private static final class Batch {
        private final LiveStreamerInfo source;
        private Instant firstAt;
        private Instant lastAt;
        private final boolean closingReminder;
        private final List<Reward> rewards = new ArrayList<>();

        private Batch(LiveStreamerInfo source, Instant firstAt, boolean closingReminder) {
            this.source = source;
            this.firstAt = firstAt;
            this.lastAt = firstAt;
            this.closingReminder = closingReminder;
        }
    }

    /**
     * 一位观众在这一阵里的记账
     */
    private static final class Person {
        private final Long uid;

        private String uname;

        private long giftAmountFen;

        private final Map<String, GiftAcc> gifts = new LinkedHashMap<>();

        private Integer guardLevel;

        private GuardOperateType operateType;

        private Long guardAmountFen;

        private Instant guardSentAt;

        private Person(Long uid, String uname) {
            this.uid = uid;
            this.uname = uname;
        }

        private BilibiliOfflineRewardDigestEvent.Contribution toContribution() {
            BilibiliOfflineRewardDigestEvent.Contribution contribution = new BilibiliOfflineRewardDigestEvent.Contribution();
            contribution.setUid(uid);
            contribution.setUname(uname);
            contribution.setGuardLevel(guardLevel);
            contribution.setOperateType(operateType);
            contribution.setGuardAmountFen(guardAmountFen);
            contribution.setGuardSentAt(guardSentAt);
            if (!gifts.isEmpty()) {
                contribution.setGiftAmountFen(giftAmountFen);
                contribution.setGifts(gifts.entrySet().stream()
                        .map(entry -> new BilibiliOfflineRewardDigestEvent.GiftLine(
                                entry.getKey(), entry.getValue().count, entry.getValue().amountFen,
                                entry.getValue().firstSentAt, entry.getValue().lastSentAt))
                        .toList());
            }
            return contribution;
        }
    }

    /**
     * 一种礼物在这一阵里的数量与实付合计
     */
    private static final class GiftAcc {
        private int count;

        private long amountFen;

        private Instant firstSentAt;

        private Instant lastSentAt;
    }
}
