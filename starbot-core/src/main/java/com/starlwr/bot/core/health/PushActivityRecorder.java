package com.starlwr.bot.core.health;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 推送活动记录
 * <p>
 * 记录最近一次推送成功与失败的时间，供健康探针判断「系统是不是还在正常干活」。
 * 只有连接状态是不够的：连接全部正常但一条也没推出去，同样是故障。
 */
@Component
public class PushActivityRecorder {
    /**
     * 保留的推送记录条数
     */
    private static final int HISTORY_CAPACITY = 50;

    /**
     * 最近一次推送成功的时间
     */
    @Getter
    private volatile Instant lastSuccessAt;

    /**
     * 最近一次推送失败的时间
     */
    @Getter
    private volatile Instant lastFailureAt;

    /**
     * 最近一次推送失败的原因
     */
    @Getter
    private volatile String lastFailureReason;

    private final AtomicLong successCount = new AtomicLong();

    private final AtomicLong failureCount = new AtomicLong();

    /**
     * 最近的推送记录，容量固定
     * <p>
     * 「刚才那条推了吗」「为什么没推」此前只能翻 journalctl。此处保留最近若干条供界面查看，
     * 容量固定以免长期运行后无界增长——推送记录是排障线索，不是需要持久化的业务数据。
     */
    private final Deque<PushRecord> history = new ArrayDeque<>();

    /**
     * 记录一次推送成功
     * @param platform 推送平台
     * @param target 推送目标描述
     * @param summary 消息摘要
     */
    public void recordSuccess(String platform, String target, String summary) {
        lastSuccessAt = Instant.now();
        successCount.incrementAndGet();
        append(new PushRecord(lastSuccessAt, platform, target, summary, true, null));
    }

    /**
     * 记录一次推送失败
     * @param platform 推送平台
     * @param target 推送目标描述
     * @param summary 消息摘要
     * @param reason 失败原因
     */
    public void recordFailure(String platform, String target, String summary, String reason) {
        lastFailureAt = Instant.now();
        lastFailureReason = reason;
        failureCount.incrementAndGet();
        append(new PushRecord(lastFailureAt, platform, target, summary, false, reason));
    }

    /**
     * 获取最近的推送记录，按时间倒序
     * @return 推送记录列表
     */
    public List<PushRecord> getHistory() {
        synchronized (history) {
            List<PushRecord> records = new ArrayList<>(history);
            Collections.reverse(records);
            return records;
        }
    }

    private void append(PushRecord record) {
        synchronized (history) {
            if (history.size() >= HISTORY_CAPACITY) {
                history.removeFirst();
            }
            history.addLast(record);
        }
    }

    /**
     * 单条推送记录
     *
     * @param at 时间
     * @param platform 推送平台
     * @param target 推送目标描述，例如「群 12345」
     * @param summary 消息摘要
     * @param success 是否成功
     * @param reason 失败原因，成功时为空
     */
    public record PushRecord(Instant at, String platform, String target, String summary, boolean success, String reason) {
    }

    /**
     * 累计推送成功次数
     * @return 成功次数
     */
    public long getSuccessCount() {
        return successCount.get();
    }

    /**
     * 累计推送失败次数
     * @return 失败次数
     */
    public long getFailureCount() {
        return failureCount.get();
    }
}
