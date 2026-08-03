package com.starlwr.bot.core.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.starlwr.bot.core.sender.StarBotMessageSender;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 推送活动健康探针测试
 */
@DisplayName("推送活动健康探针")
class PushActivityHealthProbeTest {
    @Test
    @DisplayName("启动后尚无推送不应报成异常")
    void treatsNoActivityAsNormal() {
        // 开播、动态本就是低频事件，长时间没有推送属正常现象
        HealthStatus status = probe(new PushActivityRecorder()).check();

        assertEquals(HealthStatus.Level.OK, status.level());
        assertTrue(status.advice().isBlank(), "正常状态不应给出建议");
    }

    @Test
    @DisplayName("推送成功后应判定为正常并计数")
    void reportsOkAfterSuccess() {
        PushActivityRecorder recorder = new PushActivityRecorder();
        recorder.recordSuccess("qq-onebot", "群 12345", "测试消息");
        recorder.recordSuccess("qq-onebot", "群 12345", "测试消息");

        HealthStatus status = probe(recorder).check();

        assertEquals(HealthStatus.Level.OK, status.level());
        assertTrue(status.summary().contains("成功 2 次"), status.summary());
    }

    @Test
    @DisplayName("最近一次为失败且此后未再成功时判定为降级")
    void reportsDegradedWhenLatestIsFailure() {
        PushActivityRecorder recorder = new PushActivityRecorder();
        recorder.recordSuccess("qq-onebot", "群 12345", "测试消息");
        recorder.recordFailure("qq-onebot", "群 12345", "测试消息", "群号不存在");

        HealthStatus status = probe(recorder).check();

        assertEquals(HealthStatus.Level.DEGRADED, status.level());
        assertTrue(status.advice().contains("群号不存在"), "应带上失败原因: " + status.advice());
    }

    @Test
    @DisplayName("失败后又成功应恢复为正常")
    void recoversAfterLaterSuccess() throws InterruptedException {
        PushActivityRecorder recorder = new PushActivityRecorder();
        recorder.recordFailure("qq-onebot", "群 12345", "测试消息", "一次抖动");
        // 两次记录之间需有可分辨的时间差，否则无法判断孰先孰后
        Thread.sleep(5);
        recorder.recordSuccess("qq-onebot", "群 12345", "测试消息");

        assertEquals(HealthStatus.Level.OK, probe(recorder).check().level());
    }

    @Test
    @DisplayName("推送记录按时间倒序保留, 且容量固定")
    void keepsBoundedHistoryInReverseOrder() {
        PushActivityRecorder recorder = new PushActivityRecorder();
        for (int i = 1; i <= 60; i++) {
            recorder.recordSuccess("qq-onebot", "群 " + i, "第 " + i + " 条");
        }

        List<PushActivityRecorder.PushRecord> history = recorder.getHistory();

        // 容量固定，避免长期运行后无界增长
        assertEquals(50, history.size(), "应只保留最近 50 条");
        assertEquals("第 60 条", history.get(0).summary(), "最新的一条应排在最前");
        assertEquals("第 11 条", history.get(49).summary(), "更早的记录应已被挤出");
    }

    @Test
    @DisplayName("失败记录应保留失败原因")
    void keepsFailureReasonInHistory() {
        PushActivityRecorder recorder = new PushActivityRecorder();
        recorder.recordFailure("qq-onebot", "群 12345", "开播啦", "机器人不在该群");

        PushActivityRecorder.PushRecord record = recorder.getHistory().get(0);

        assertFalse(record.success());
        assertEquals("机器人不在该群", record.reason());
        assertEquals("群 12345", record.target());
    }

    /**
     * 构造探针；发送器以桩替身提供，队列相关指标在此保持为零
     */
    private PushActivityHealthProbe probe(PushActivityRecorder recorder) {
        StarBotMessageSender sender = mock(StarBotMessageSender.class);
        when(sender.getDroppedCount()).thenReturn(0L);
        when(sender.getPendingCount()).thenReturn(0);

        ObjectProvider<StarBotMessageSender> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(sender);

        return new PushActivityHealthProbe(recorder, provider);
    }
}
