package com.starlwr.bot.core.health;

import com.starlwr.bot.core.sender.StarBotMessageSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 推送活动健康探针
 * <p>
 * 各项连接都正常、却一条消息也发不出去，同样是故障。此探针关注的是「最近有没有真的推成功过」。
 */
@Component
public class PushActivityHealthProbe implements HealthProbe {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * 队列积压达到该条数即视为异常
     */
    private static final int PENDING_WARN_THRESHOLD = 50;

    private final PushActivityRecorder recorder;

    /**
     * 以 ObjectProvider 注入，避免与发送器之间形成构造期的循环依赖
     */
    private final ObjectProvider<StarBotMessageSender> messageSender;

    @Autowired
    public PushActivityHealthProbe(PushActivityRecorder recorder, ObjectProvider<StarBotMessageSender> messageSender) {
        this.recorder = recorder;
        this.messageSender = messageSender;
    }

    @Override
    public String name() {
        return "推送活动";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public HealthStatus check() {
        Instant success = recorder.getLastSuccessAt();
        Instant failure = recorder.getLastFailureAt();

        if (success == null && failure == null) {
            // 开播、动态本就是低频事件，启动后长时间没有推送属正常现象，不应报成异常
            return HealthStatus.ok("启动后尚无推送");
        }

        String summary = "成功 " + recorder.getSuccessCount() + " 次，失败 " + recorder.getFailureCount() + " 次";
        if (success != null) {
            summary += "；最近成功于 " + TIME.format(success);
        }

        // 队列积压说明消息发得比来得慢，多半是 OneBot 侧出了问题；
        // 已经开始丢弃则意味着确实丢了消息，必须让人知道
        long dropped = messageSender.getObject().getDroppedCount();
        int pending = messageSender.getObject().getPendingCount();

        if (dropped > 0) {
            return HealthStatus.degraded(summary + "；队列积压 " + pending + " 条，已累计丢弃 " + dropped + " 条",
                    "发送速度跟不上产生速度，通常是 OneBot 侧不可用或响应过慢，请检查机器人连接");
        }

        if (pending > PENDING_WARN_THRESHOLD) {
            return HealthStatus.degraded(summary + "；队列积压 " + pending + " 条",
                    "消息正在堆积，请检查机器人连接是否正常");
        }

        // 最后一次动作是失败，且此后再没成功过，说明问题很可能仍在持续
        if (failure != null && (success == null || failure.isAfter(success))) {
            return HealthStatus.degraded(summary + "；最近失败于 " + TIME.format(failure),
                    "最近一次推送失败且此后未再成功，原因：" + recorder.getLastFailureReason()
                            + "。可用「发送测试消息」确认当前是否已恢复");
        }

        return HealthStatus.ok(summary);
    }
}
