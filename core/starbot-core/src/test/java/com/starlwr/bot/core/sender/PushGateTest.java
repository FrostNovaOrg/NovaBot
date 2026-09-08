package com.starlwr.bot.core.sender;

import com.starlwr.bot.core.config.NovaCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 推送闸门测试
 * <p>
 * 静音时段最容易出错的是跨零点的情形：23:00 ~ 08:00 表示当晚到次日，
 * 判定条件与不跨零点时正好相反。
 */
@DisplayName("推送闸门")
class PushGateTest {
    @Test
    @DisplayName("默认允许推送")
    void allowsByDefault() {
        assertTrue(gate(new NovaCoreProperties()).allowed());
    }

    @Test
    @DisplayName("全局开关关闭时一律拦截")
    void blocksWhenGloballyDisabled() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setEnabled(false);

        PushGate gate = gate(properties);
        assertFalse(gate.allowedAt(LocalTime.of(12, 0)));
        assertTrue(gate.blockReason().contains("全局"), gate.blockReason());
    }

    @Test
    @DisplayName("跨零点的静音时段应正确判定")
    void handlesQuietHoursCrossingMidnight() {
        PushGate gate = gate(quiet("23:00", "08:00"));

        assertFalse(gate.allowedAt(LocalTime.of(23, 30)), "当晚 23:30 应静音");
        assertFalse(gate.allowedAt(LocalTime.of(3, 0)), "次日 03:00 应静音");
        assertFalse(gate.allowedAt(LocalTime.of(23, 0)), "起始时刻应包含在内");
        assertTrue(gate.allowedAt(LocalTime.of(8, 0)), "结束时刻应已解除");
        assertTrue(gate.allowedAt(LocalTime.of(12, 0)), "白天应允许推送");
    }

    @Test
    @DisplayName("不跨零点的静音时段应正确判定")
    void handlesQuietHoursWithinDay() {
        PushGate gate = gate(quiet("12:00", "14:00"));

        assertFalse(gate.allowedAt(LocalTime.of(13, 0)));
        assertTrue(gate.allowedAt(LocalTime.of(11, 59)));
        assertTrue(gate.allowedAt(LocalTime.of(14, 0)));
        assertTrue(gate.allowedAt(LocalTime.of(23, 0)));
    }

    @Test
    @DisplayName("时段配置不完整或格式非法时不应静音")
    void ignoresIncompleteOrInvalidQuietHours() {
        // 宁可漏静音也不能误静音：后者会让使用者以为推送坏了
        assertTrue(gate(quiet("23:00", "")).allowedAt(LocalTime.of(23, 30)));
        assertTrue(gate(quiet("", "08:00")).allowedAt(LocalTime.of(3, 0)));
        assertTrue(gate(quiet("晚上", "早上")).allowedAt(LocalTime.of(3, 0)));
    }

    @Test
    @DisplayName("起止时间相同视为未设置, 而非全天静音")
    void treatsEqualBoundsAsDisabled() {
        assertTrue(gate(quiet("09:00", "09:00")).allowedAt(LocalTime.of(9, 0)));
    }

    private NovaCoreProperties quiet(String start, String end) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setQuietStart(start);
        properties.getPush().setQuietEnd(end);
        return properties;
    }

    private PushGate gate(NovaCoreProperties properties) {
        return new PushGate(properties);
    }
}
