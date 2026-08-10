package com.starlwr.bot.core.service;

import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 停机与崩溃的场次善后
 * <p>
 * 处理同一件事的两个后果：<b>程序在一场直播进行中停了</b>。
 * <ul>
 *     <li><b>缺口</b>：停机期间到达的弹幕与礼物没有任何实例在接收，事后也补不回来——
 *         平台不提供回溯。能做的是如实记下缺了多久，让报告里的数字带上「这是下界」的说明</li>
 *     <li><b>未闭合场次</b>：崩溃时那一场没有下播事件，因此从没被归档。等主播下一次开播，
 *         本场数据就会被清零，那一场连同它的全部统计一起消失</li>
 * </ul>
 *
 * <h2>为什么修在开播这一刻，而不是启动那一刻</h2>
 *
 * 启动时我们并不知道那一场结束了没有：主播可能还在播（此时不该归档，那一场还在继续），
 * 也可能早就下播了（此时必须归档）。问平台要当前状态需要各平台各自实现一套解析，
 * 而备用轮询是<b>可以关掉</b>的，指望它就等于指望一个可选组件。
 * <p>
 * 但有一个时刻是<b>必然到来且信息完备</b>的：下一次开播。开播必然先于那句清零，
 * 而「状态还是在播 + 开播时间不是这一次的」这个组合只可能来自一场没闭合的旧场次。
 * 在清零之前把它落档，数据就一条都不会丢。
 *
 * <h2>时长为什么只是下界</h2>
 *
 * 未闭合场次的结束时刻取<b>最后一次落盘的时刻</b>，那是我们能证明的最后一刻。
 * 主播实际播到几点无从得知，所以这类场次一律标 {@link LiveEndReason#UNCLOSED}，
 * 不参与趋势对比。
 */
@Slf4j
@Service
public class LiveSessionRecovery {
    private final LiveDataService liveDataService;

    private final LiveSessionArchive archive;

    @Autowired
    public LiveSessionRecovery(LiveDataService liveDataService, LiveSessionArchive archive) {
        this.liveDataService = liveDataService;
        this.archive = archive;
    }

    /**
     * 启动时把「上次落盘 ~ 现在」记成一段停机
     * <p>
     * 这段区间无论崩溃还是正常重启都成立，而且<b>两种情况的起点都该是上次落盘时刻</b>：
     * 崩溃时，上次落盘之后收到的消息随进程一起没了，那部分同样没进任何统计。
     * <p>
     * 顺序在 {@code -9999}：必须等 {@link DefaultLiveDataService} 在 {@code -10000}
     * 把数据文件读进来，否则水位线还没取到。
     */
    @Order(-9999)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        Optional<Long> lastSave = liveDataService.getLastSaveTime();
        if (lastSave.isEmpty()) {
            return;
        }

        long from = lastSave.get();
        long now = System.currentTimeMillis();
        if (now <= from) {
            // 时钟回拨，记不出有意义的区间，宁可不记
            log.warn("上次落盘时刻 {} 不早于当前时刻 {}, 本次不记停机区间", from, now);
            return;
        }

        liveDataService.recordDowntime(from, now);
        log.info("本次停机 {} 秒未采集, 期间在播场次的报告会标注缺口", (now - from) / 1000);
    }

    /**
     * 若上一场从未闭合，在本场清零之前把它归档
     * <p>
     * <b>必须在 {@code setLiveStatus(true)} 之前调用</b>——那一句一旦执行，
     * 「上一场还挂着」这个唯一的判据就被抹掉了。
     * @param platform 直播平台
     * @param source 主播信息
     * @param newStartTime 本次开播时刻（毫秒）
     * @return 是否归档了一场未闭合的旧场次
     */
    public boolean archiveUnclosedIfAny(@NonNull String platform, @NonNull LiveStreamerInfo source, long newStartTime) {
        if (!liveDataService.getLiveStatus(platform, source.getUid()).orElse(false)) {
            return false;
        }

        Optional<Long> previousStart = liveDataService.getLiveStartTime(platform, source.getUid());
        if (previousStart.isEmpty()) {
            // 状态挂在在播但没有开播时间，多为程序在直播中途才启动过。
            // 没有起点就算不出时长也归不进任何统计周期，与下播路径的处理保持一致
            log.info("{} 有一场未闭合的直播但没有开播时间, 不归档", source.getUname());
            return false;
        }

        long start = previousStart.get();
        if (start >= newStartTime) {
            // 同一场（开播事件重复下发）或时钟异常，不是两场
            return false;
        }

        long endTime = endTimeOf(platform, source, start);
        long duration = Math.max(0, (endTime - start) / 1000);
        long gap = liveDataService.downtimeWithin(start, endTime) / 1000;

        archive.append(new LiveSession(
                platform,
                source.getUid(),
                source.getUname(),
                source.getRoomId(),
                start,
                endTime,
                duration,
                liveDataService.getLiveMetrics(platform, source.getUid()),
                liveDataService.getLiveMetricUserCounts(platform, source.getUid()),
                LiveEndReason.UNCLOSED,
                // 崩溃后标题轨迹只存在内存里，已经没了。这里<b>不能</b>拿当前标题顶上，
                // 那会把这一场的标题记到上一场头上
                List.of(),
                gap));

        log.warn("{} 上一场直播未闭合（程序在直播中途停过），已按未闭合归档: 时长下界 {} 秒, 其中 {} 秒未采集",
                source.getUname(), duration, gap);

        // 累计数据同样只在下播时并入，这一场也得补上，否则它在累计里也不存在
        liveDataService.mergeLiveDataIntoTotal(platform, source.getUid());
        return true;
    }

    /**
     * 未闭合场次的结束时刻
     * <p>
     * 首选最后一次落盘时刻——那是我们能证明的最后一刻。它可能早于开播（进程停了很久、
     * 主播在停机期间开了又关），这种情况下时长确实无从得知，退回开播时刻让时长为 0，
     * 并把这一场留在归档里：<b>指标比时长值钱，一条没有时长的记录仍然记得住那一场收了多少弹幕</b>。
     */
    private long endTimeOf(String platform, LiveStreamerInfo source, long start) {
        Optional<Long> lastSave = liveDataService.getLastSaveTime();
        if (lastSave.isPresent() && lastSave.get() > start) {
            return lastSave.get();
        }

        log.warn("{} 的未闭合场次算不出结束时刻（上次落盘 {} 不晚于开播 {}）, 时长记 0",
                source.getUname(), lastSave.orElse(null), start);
        return start;
    }
}
