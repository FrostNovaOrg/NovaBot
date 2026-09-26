package org.frostnova.nova.core.sender;

import org.frostnova.nova.core.config.NovaCoreProperties;
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

    @Test
    @DisplayName("告警不问静音时段, 但全局开关照拦")
    void alertsAllowedIgnoresQuietHoursButNotMasterSwitch() {
        // 静音挡的是「不想被机器人吵」的打扰，而告警恰恰是出了事要叫人的那一条；
        // 全局开关的说明是「关闭后所有推送都会被丢弃」，不含例外
        assertTrue(gate(quiet("23:00", "08:00")).alertsAllowed(), "静音时段配着也不拦告警——它压根不问时刻");

        NovaCoreProperties off = new NovaCoreProperties();
        off.getPush().setEnabled(false);
        assertFalse(gate(off).alertsAllowed(), "全局开关关着时告警照拦");
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
