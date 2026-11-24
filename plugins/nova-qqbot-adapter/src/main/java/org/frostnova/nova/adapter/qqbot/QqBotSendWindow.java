package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.plugin.NovaComponent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 核心投递和进群欢迎共用的群消息额度；上传文件不占消息额度。 */
@NovaComponent
final class QqBotSendWindow {
    private static final long MINUTE = Duration.ofMinutes(1).toMillis();
    private static final int LIMIT = 20;
    private final Clock clock;
    private final LongSupplier elapsedMillis;
    private final Sleeper sleeper;
    // 按上一次取得额度的时间排列，便于移除已经空闲的群。
    private final LinkedHashMap<Group, ArrayDeque<Long>> windows = new LinkedHashMap<>();

    QqBotSendWindow() {
        this(Clock.systemUTC(), () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), Thread::sleep);
    }

    QqBotSendWindow(Clock clock, LongSupplier elapsedMillis, Sleeper sleeper) {
        this.clock = clock;
        this.elapsedMillis = elapsedMillis;
        this.sleeper = sleeper;
    }

    Instant now() {
        return clock.instant();
    }

    <T> T retryWhenLimited(Supplier<T> request) {
        return retryWhenLimited(request, false);
    }

    <T> T retryWhenLimited(Supplier<T> request, boolean memberNotice) {
        try {
            return request.get();
        } catch (QqBotApiException e) {
            if (!rateLimited(e, memberNotice)) throw e;
        }
        pause(5000);
        try {
            return request.get();
        } catch (QqBotApiException e) {
            if (!rateLimited(e, memberNotice)) throw e;
            throw failure("发得太快被平台限流，等 5 秒重发仍未发出");
        }
    }

    private static boolean rateLimited(QqBotApiException e, boolean memberNotice) {
        if (memberNotice && (e.markdownPermissionDenied() || e.markdownFormatRejected())) return false;
        // 平台已经判重或明确拒绝事件回复时，即使附带 429 也不能再发。
        return !e.duplicateMessage() && !e.eventReplyRejected() && !e.atAllLimitReached()
                && (e.httpStatus() == 429 || e.code() == 40034100);
    }

    void acquire(String appId, long number) {
        Group group = new Group(appId, number);
        long deadline = elapsedMillis.getAsLong() + MINUTE;
        long remaining = MINUTE;
        while (true) {
            long delay = reserveOrDelay(group, deadline, remaining);
            if (delay == 0) return;
            pause(delay);
            remaining -= delay;
        }
    }

    private synchronized long reserveOrDelay(Group group, long deadline, long remaining) {
        long now = elapsedMillis.getAsLong();
        var idle = windows.values().iterator();
        while (idle.hasNext() && now - idle.next().getLast() >= MINUTE) idle.remove();
        var recent = windows.get(group);
        if (recent == null) recent = new ArrayDeque<>();
        while (!recent.isEmpty() && now - recent.getFirst() >= MINUTE) recent.removeFirst();
        if (recent.size() == LIMIT) {
            long available = recent.getFirst() + MINUTE;
            if (available > deadline || available - now > remaining) {
                throw failure("这个群一分钟内发了太多条，已暂停发送");
            }
            return available - now;
        }
        recent.addLast(now);
        windows.remove(group);
        windows.put(group, recent);
        return 0;
    }

    private void pause(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("等待发送已中断");
        }
    }

    private static QqBotApiException failure(String reason) {
        return new QqBotApiException(0, reason, "", 0);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private record Group(String appId, long number) {}
}
