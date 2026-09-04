package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.sender.PushGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 即时生效通道测试
 * <p>
 * 每一类各有一条阳性：改完<b>不重启</b>，读这一项的那一侧就该看到新值。
 * 另有一条阴性：不在名单里的配置项<b>不许</b>被悄悄改到运行中的配置上——
 * 那会变成「界面说要重启，实际却已经变了」，比说反了更难查。
 */
@DisplayName("配置项即时生效")
class RuntimeConfigurationApplierTest {
    /**
     * 静音时段的书写格式，与配置项说明一致
     */
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private StarBotCoreProperties properties;

    private RuntimeConfigurationApplier applier;

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
        applier = RuntimeConfigurationApplier.bench(properties).build();
    }

    @Test
    @DisplayName("推送总开关：关掉之后推送闸门当场不再放行")
    void appliesPushSwitch() {
        PushGate gate = new PushGate(properties);
        assertTrue(gate.allowed(), "默认应放行");

        List<String> restart = applier.applyAndTrack(Map.of("starbot.core.push.enabled", "false"));

        assertFalse(gate.allowed(), "关掉总开关后应当场拦下");
        assertEquals(List.of(), restart);
    }

    @Test
    @DisplayName("静音时段：设好之后此刻当场被拦，改到别的时段又当场放行")
    void appliesQuietHours() {
        PushGate gate = new PushGate(properties);
        // 区间按当前时刻现算而不是写死 23:00–08:00：闸门读的是真实的此刻，
        // 写死的区间意味着这条测试的结论取决于跑它的时间，半夜跑与白天跑得出相反的答案
        LocalTime now = LocalTime.now();

        applier.applyAndTrack(Map.of(
                "starbot.core.push.quiet-start", HOUR_MINUTE.format(now.minusMinutes(5)),
                "starbot.core.push.quiet-end", HOUR_MINUTE.format(now.plusMinutes(5))));

        assertFalse(gate.allowed(), "此刻落在静音时段内，应当场被拦");
        assertEquals("处于静音时段", gate.blockReason());

        applier.applyAndTrack(Map.of(
                "starbot.core.push.quiet-start", HOUR_MINUTE.format(now.plusMinutes(10)),
                "starbot.core.push.quiet-end", HOUR_MINUTE.format(now.plusMinutes(20))));

        assertTrue(gate.allowed(), "静音时段挪走之后应当场放行");
    }

    @Test
    @DisplayName("告警接收人：平台、类型与号码三项一起当场换到新地址")
    void appliesAlertRecipient() {
        applier.applyAndTrack(Map.of(
                "starbot.core.alert.qq-platform", "onebot",
                "starbot.core.alert.qq-type", "1",
                "starbot.core.alert.qq-num", "10001"));

        StarBotCoreProperties.Alert alert = properties.getAlert();
        assertEquals("onebot", alert.getQqPlatform());
        assertEquals(1, alert.getQqType());
        assertEquals(10001L, alert.getQqNum());
    }

    @Test
    @DisplayName("告警接收人：Webhook 地址与收件邮箱同样当场生效")
    void appliesWebhookAndMailRecipient() {
        applier.applyAndTrack(Map.of(
                "starbot.core.alert.webhook-url", "https://example.invalid/hook",
                "starbot.core.mail.default-to", "ops@example.invalid"));

        assertEquals("https://example.invalid/hook", properties.getAlert().getWebhookUrl());
        assertEquals("ops@example.invalid", properties.getMail().getDefaultTo());
    }

    @Test
    @DisplayName("告警号码留空即取消 QQ 告警，而不是把空串塞进一个数字里")
    void clearingAlertNumberYieldsNull() {
        applier.applyAndTrack(Map.of("starbot.core.alert.qq-num", "10001"));
        applier.applyAndTrack(Map.of("starbot.core.alert.qq-num", ""));

        assertNull(properties.getAlert().getQqNum());
    }

    @Test
    @DisplayName("⚠️ 阴性：不在名单里的配置项只写文件，运行中的值一动不动")
    void doesNotTouchRestartOnlyProperties() {
        int before = properties.getAlert().getConvergenceInterval();

        List<String> restart = applier.applyAndTrack(Map.of("starbot.core.alert.convergence-interval", "7200"));

        assertEquals(before, properties.getAlert().getConvergenceInterval(),
                "这一项要等重启，此刻不该被改到运行中的配置上");
        assertEquals(List.of("starbot.core.alert.convergence-interval"), restart);
    }

    @Test
    @DisplayName("⚠️ 阴性：值的形式不对时按需重启处理，不当作已生效")
    void unparsableValueCountsAsRestartRequired() {
        int before = properties.getAlert().getQqType();

        List<String> restart = applier.applyAndTrack(Map.of("starbot.core.alert.qq-type", "群聊"));

        assertEquals(before, properties.getAlert().getQqType());
        assertEquals(List.of("starbot.core.alert.qq-type"), restart);
    }

    @Test
    @DisplayName("欠着的那次重启会一直记着，直到进程换一个")
    void tracksPendingRestartAcrossSaves() {
        applier.applyAndTrack(Map.of("starbot.core.alert.convergence-interval", "7200"));
        applier.applyAndTrack(Map.of("starbot.core.push.enabled", "false"));
        applier.applyAndTrack(Map.of("starbot.core.paint.auto-expand-height", "6000"));

        assertEquals(List.of("starbot.core.alert.convergence-interval", "starbot.core.paint.auto-expand-height"),
                applier.getPendingRestart(), "即时生效的那一项不该混进待重启名单");

        // 记录挂在实例上，实例的寿命就是进程的寿命——换一个实例等于程序重启了一次
        assertEquals(List.of(), RuntimeConfigurationApplier.bench(properties).build().getPendingRestart());
    }
}
