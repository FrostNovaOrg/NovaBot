package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.event.live.BilibiliCaptainEvent;
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
 * 够不够格是按人的（礼物合计 {@link #PERSON_THRESHOLD_FEN} 分即 100 元、或这一阵上过舰），
 * <b>够格不提前触发播报</b>：哪怕一份就过了 100 元，也要等这一阵停下来再播。
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

    /**
     * 一个人这一阵礼物合计够格播报的金额（分）：100 元＝10000 分。
     * 金额一律按「分」整数记账，用元的小数逐份累加会把整 100 元加成差一点点、判成不够格
     */
    static final long PERSON_THRESHOLD_FEN = 10_000L;

    private final ApplicationEventPublisher publisher;

    private final BilibiliLiveStateGate stateGate;

    private final Clock clock;

    /**
     * 各主播手上攒着的那一阵。播报可能走到网络发送，因此发布一律在锁外做
     */
    private final Map<String, Batch> batches = new HashMap<>();

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
    @EventListener(NovaBaseLiveEvent.class)
    public void onLiveEvent(NovaBaseLiveEvent event) {
        if (event instanceof BilibiliPaidGiftEvent gift) {
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
        record(event, sender, person -> {
            person.giftAmountFen += amountFen;
            GiftAcc acc = person.gifts.computeIfAbsent(name, key -> new GiftAcc());
            acc.count += count;
            acc.amountFen += amountFen;
        });
    }

    private void accumulateGuard(NovaBaseLiveEvent event, UserInfo sender, int level, GuardOperateType operateType, Double amount) {
        // 上舰不论金额都播报，因此不看合计、只挂到人身上
        record(event, sender, person -> {
            person.guardLevel = level;
            person.operateType = operateType;
            person.guardAmountFen = amount == null ? null : Math.round(amount * 100);
        });
    }

    /**
     * 把一笔打赏挂进当前这一阵里这位观众的条目
     * <p>
     * 在播时的礼物与上舰在这里被挡掉：这类通知只算下播时段的打赏。
     * 到了上限的旧阵也在这里先摘下、锁外发布。
     */
    private void record(NovaBaseLiveEvent event, UserInfo sender, Consumer<Person> mutation) {
        LiveStreamerInfo source = event.getSource();
        if (source == null || source.getUid() == null || sender == null || !isIdentified(sender.getUid())) {
            return;
        }
        if (stateGate.isLiving(source.getUid())) {
            return;
        }

        Instant now = clock.instant();
        String key = event.getPlatform() + "|" + source.getUid();

        List<Batch> due = new ArrayList<>(1);
        synchronized (batches) {
            Batch batch = batches.get(key);
            if (batch != null && !now.isBefore(batch.firstAt.plus(MAX_WINDOW))) {
                batches.remove(key);
                due.add(batch);
                batch = null;
            }
            if (batch == null) {
                batch = new Batch(source, now);
                batches.put(key, batch);
            }
            // 每来一份就把一阵续上：这一阵的最后一份之后再安静一分钟才播
            batch.lastAt = now;

            Person person = batch.people.computeIfAbsent(sender.getUid(), uid -> new Person(uid, sender.getUname()));
            if (StringUtil.isNotBlank(sender.getUname())) {
                person.uname = sender.getUname();
            }
            mutation.accept(person);
        }
        due.forEach(this::publish);
    }

    /**
     * 发布一阵的汇总事件。没一个够格的人就什么也不发
     */
    private void publish(Batch batch) {
        List<BilibiliOfflineRewardDigestEvent.Contribution> qualifying = new ArrayList<>();
        for (Person person : batch.people.values()) {
            if (person.guardLevel != null || (!person.gifts.isEmpty() && person.giftAmountFen >= PERSON_THRESHOLD_FEN)) {
                qualifying.add(person.toContribution());
            }
        }
        if (qualifying.isEmpty()) {
            return;
        }
        publisher.publishEvent(new BilibiliOfflineRewardDigestEvent(batch.source, qualifying));
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

    /**
     * 攒着的一阵
     */
    private static final class Batch {
        private final LiveStreamerInfo source;

        private final Instant firstAt;

        private Instant lastAt;

        private final Map<Long, Person> people = new LinkedHashMap<>();

        private Batch(LiveStreamerInfo source, Instant firstAt) {
            this.source = source;
            this.firstAt = firstAt;
            this.lastAt = firstAt;
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
            if (!gifts.isEmpty()) {
                contribution.setGiftAmountFen(giftAmountFen);
                contribution.setGifts(gifts.entrySet().stream()
                        .map(entry -> new BilibiliOfflineRewardDigestEvent.GiftLine(
                                entry.getKey(), entry.getValue().count, entry.getValue().amountFen))
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
    }
}
