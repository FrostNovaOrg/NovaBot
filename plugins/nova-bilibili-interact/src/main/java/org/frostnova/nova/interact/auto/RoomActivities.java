package org.frostnova.nova.interact.auto;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 每个天选独立结束。红包只有开始事件，所以只需保留最后一个截止时间。 */
final class RoomActivities {
    record Pause(String reason, Instant until) {}

    // 无编号天选只能等到截止时间，多场共用 null 键并保留最晚的截止时间。
    private final Map<Long, Instant> lotteries = new HashMap<>();
    private Instant redPocketEnd;

    void lotteryStarted(Long id, Instant start, Instant expectedEnd) {
        Instant end = expectedEnd == null ? start.plusSeconds(600) : expectedEnd;
        lotteries.merge(id, end, RoomActivities::later);
    }

    void lotteryEnded(Long id) {
        if (id != null) lotteries.remove(id);
    }

    void redPocketStarted(Instant start) {
        Instant end = start.plusSeconds(60);
        redPocketEnd = redPocketEnd == null ? end : later(redPocketEnd, end);
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    void expire(Instant now) {
        lotteries.values().removeIf(end -> !end.isAfter(now));
    }

    Pause pauseAt(Instant now) {
        Instant lotteryEnd = lotteries.values().stream().max(Instant::compareTo).orElse(null);
        if (redPocketEnd != null && redPocketEnd.isAfter(now) && (lotteryEnd == null || redPocketEnd.isAfter(lotteryEnd))) {
            return new Pause("红包进行中", redPocketEnd);
        }
        return lotteryEnd != null && lotteryEnd.isAfter(now) ? new Pause("天选进行中", lotteryEnd) : null;
    }
}
