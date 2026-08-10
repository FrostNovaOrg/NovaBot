package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 断线摘要
 * <p>
 * <b>逐次一行日志在断线风暴里恰好是最没用的形式。</b>十个房间各断五次，日志里就是
 * 五十行「连接已断开 (1006)，将尝试重连」夹在正常消息中间——看的人既数不清有多少次，
 * 也看不出是集中在一个房间还是所有房间一起断，而这两者的处理方式完全不同。
 * <p>
 * 所以逐次那行降到 DEBUG，改由本类按窗口汇总：这段时间断了几次、分别归因于什么、
 * 是集中在个别房间还是普遍现象、连接平均活了多久。
 * <p>
 * <b>没有断线的窗口不打日志</b>——一条「本窗口 0 次断线」每十分钟出现一次，
 * 只会让人把这类日志整个滤掉。
 */
@Slf4j
@StarBotComponent
public class BilibiliDisconnectDigest {
    private final StarBotBilibiliProperties properties;

    private final TaskScheduler scheduler;

    /**
     * 本窗口的记录。逐条留着而不是只留计数，是因为摘要要能说出
     * 「集中在哪个房间」——只有计数就答不出这句
     */
    private final List<Entry> window = new ArrayList<>();

    @Autowired
    public BilibiliDisconnectDigest(StarBotBilibiliProperties properties,
                                    @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler) {
        this.properties = properties;
        this.scheduler = scheduler;
    }

    /**
     * 启动周期汇总
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        int interval = properties.getLive().getDisconnectDigestInterval();
        if (interval <= 0) {
            log.debug("断线摘要已关闭");
            return;
        }

        Duration period = Duration.ofSeconds(interval);
        scheduler.scheduleAtFixedRate(this::flush, period);
        log.info("断线摘要已启动, 每 {} 秒汇总一次", interval);
    }

    /**
     * 记一次断线
     * @param roomId 直播间号
     * @param cause 归因
     * @param lived 这条连接活了多久，未知时传 null
     */
    public void record(long roomId, @NonNull BilibiliDisconnectCause cause, Duration lived) {
        // 主动关闭是我们自己干的，不算故障，别把停止监听算进断线率
        if (cause == BilibiliDisconnectCause.BY_US) {
            return;
        }

        synchronized (window) {
            window.add(new Entry(roomId, cause, lived == null ? -1 : lived.toSeconds()));
        }
    }

    /**
     * 输出一个窗口的摘要并清空
     * <p>
     * <b>包内可见而非私有</b>：测试要能确定地驱动一个窗口，而不是等定时任务。
     */
    void flush() {
        List<Entry> batch;
        synchronized (window) {
            if (window.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(window);
            window.clear();
        }

        Map<BilibiliDisconnectCause, Integer> byCause = new EnumMap<>(BilibiliDisconnectCause.class);
        Map<Long, Integer> byRoom = new LinkedHashMap<>();
        long livedSum = 0;
        int livedKnown = 0;

        for (Entry entry : batch) {
            byCause.merge(entry.cause(), 1, Integer::sum);
            byRoom.merge(entry.roomId(), 1, Integer::sum);
            if (entry.livedSeconds() >= 0) {
                livedSum += entry.livedSeconds();
                livedKnown++;
            }
        }

        String causes = byCause.entrySet().stream()
                .map(e -> e.getKey().getLabel() + " " + e.getValue() + " 次")
                .collect(Collectors.joining("、"));

        // 只列断得最多的三个房间：十个房间全列出来又变成一行读不完的东西
        String rooms = byRoom.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(e -> e.getKey() + " (" + e.getValue() + " 次)")
                .collect(Collectors.joining("、"));
        String roomsTail = byRoom.size() > 3 ? "，另有 " + (byRoom.size() - 3) + " 个房间" : "";

        String lived = livedKnown == 0 ? "未知" : (livedSum / livedKnown) + " 秒";

        log.warn("断线摘要：{} 秒内共 {} 次，涉及 {} 个房间。归因：{}。集中在：{}{}。连接平均存活 {}",
                properties.getLive().getDisconnectDigestInterval(), batch.size(), byRoom.size(),
                causes, rooms, roomsTail, lived);

        // 处理建议只按本窗口出现过的归因给，且每种只说一次
        byCause.keySet().stream()
                .filter(cause -> cause != BilibiliDisconnectCause.NETWORK_FLAP || batch.size() > 1)
                .forEach(cause -> log.warn("  {}：{}", cause.getLabel(), cause.getHint()));
    }

    /**
     * 待汇总的条数，供测试观察
     */
    int pendingCount() {
        synchronized (window) {
            return window.size();
        }
    }

    /**
     * 一次断线
     * @param roomId 直播间号
     * @param cause 归因
     * @param livedSeconds 连接存活秒数，未知为 -1
     */
    private record Entry(long roomId, BilibiliDisconnectCause cause, long livedSeconds) {
    }
}
