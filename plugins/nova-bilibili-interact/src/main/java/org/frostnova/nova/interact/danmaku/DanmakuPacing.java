package org.frostnova.nova.interact.danmaku;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 每个账号的发送节奏：两条之间的最短间隔，以及被平台限制之后的暂停
 * <p>
 * 按账号分账（见 {@link SpeakingAccount#key()}）：发言账号被暂停，不碍监听账号的事，反过来也一样。
 * 只记在内存里，重启后从头算。
 * <p>
 * <b>间隔是先占后发</b>：查与占在同一把锁里，两个几乎同时到的请求只有一个占得到，另一个当场回「太快」——
 * 先查后占的话，两个都查到「可以」，就一起发出去了。
 */
final class DanmakuPacing {
    private final Map<String, Instant> lastAttempt = new HashMap<>();

    private final Map<String, Instant> pausedUntil = new HashMap<>();
    private final Map<String, Instant> loginRejectedUntil = new HashMap<>();

    /**
     * 这个账号还要暂停多久
     * @param key 账号标识
     * @param now 此刻
     * @param automatic 自动发送还须等待登录被拒后的暂停结束
     * @return 剩余的暂停时长，不在暂停期时为空。到点即解除
     */
    synchronized Optional<Duration> pausedFor(String key, Instant now, boolean automatic) {
        Instant until = pauseEnd(pausedUntil, key, now);
        if (automatic) {
            Instant login = pauseEnd(loginRejectedUntil, key, now);
            if (login != null && (until == null || login.isAfter(until))) until = login;
        }
        return until == null ? Optional.empty() : Optional.of(Duration.between(now, until));
    }

    private static Instant pauseEnd(Map<String, Instant> pauses, String key, Instant now) {
        Instant until = pauses.get(key);
        if (until != null && !now.isBefore(until)) {
            pauses.remove(key);
            return null;
        }
        return until;
    }

    /**
     * 离上一条还差多久才到最短间隔
     * @param key 账号标识
     * @param now 此刻
     * @param interval 最短间隔
     * @return 还差的时长，已到间隔时为空
     */
    synchronized Optional<Duration> tooSoon(String key, Instant now, Duration interval) {
        Instant last = lastAttempt.get(key);
        if (last == null) {
            return Optional.empty();
        }
        Instant next = last.plus(interval);
        return now.isBefore(next) ? Optional.of(Duration.between(now, next)) : Optional.empty();
    }

    /**
     * 占下这一次发送：已到间隔、也不在暂停期才占得到
     * @param key 账号标识
     * @param now 此刻
     * @param interval 最短间隔
     * @param automatic 是否为自动发送
     * @return 占到时为 true
     */
    synchronized boolean reserve(String key, Instant now, Duration interval, boolean automatic) {
        if (pausedFor(key, now, automatic).isPresent() || tooSoon(key, now, interval).isPresent()) {
            return false;
        }
        lastAttempt.put(key, now);
        return true;
    }

    /**
     * 暂停这个账号
     * <p>
     * 已在暂停期的不缩短，只往后延。
     * @param key 账号标识
     * @param now 此刻
     * @param length 暂停时长
     * @return 暂停到什么时候
     */
    synchronized Instant pause(String key, Instant now, Duration length) {
        Instant until = now.plus(length);
        Instant current = pausedUntil.get(key);
        if (current != null && current.isAfter(until)) {
            return current;
        }
        pausedUntil.put(key, until);
        return until;
    }

    /** 平台拒绝登录态时只暂停自动发送，管理员重新登录后可以试发。 */
    synchronized void rejectLogin(String key, Instant now, Duration length) {
        loginRejectedUntil.merge(key, now.plus(length), (old, next) -> old.isAfter(next) ? old : next);
    }
}
