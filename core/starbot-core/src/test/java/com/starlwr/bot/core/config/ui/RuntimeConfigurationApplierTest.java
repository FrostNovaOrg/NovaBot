package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.sender.PushGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private NovaCoreProperties properties;

    private RuntimeConfigurationApplier applier;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        applier = RuntimeConfigurationApplier.bench(properties).build();
    }

    @Test
    @DisplayName("推送总开关：关掉之后推送闸门当场不再放行")
    void appliesPushSwitch() {
        PushGate gate = new PushGate(properties);
        assertTrue(gate.allowed(), "默认应放行");

        List<String> restart = applier.applyAndTrack(Map.of("novabot.core.push.enabled", "false"));

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
                "novabot.core.push.quiet-start", HOUR_MINUTE.format(now.minusMinutes(5)),
                "novabot.core.push.quiet-end", HOUR_MINUTE.format(now.plusMinutes(5))));

        assertFalse(gate.allowed(), "此刻落在静音时段内，应当场被拦");
        assertEquals("处于静音时段", gate.blockReason());

        applier.applyAndTrack(Map.of(
                "novabot.core.push.quiet-start", HOUR_MINUTE.format(now.plusMinutes(10)),
                "novabot.core.push.quiet-end", HOUR_MINUTE.format(now.plusMinutes(20))));

        assertTrue(gate.allowed(), "静音时段挪走之后应当场放行");
    }

    @Test
    @DisplayName("告警接收人：Webhook 地址与收件邮箱同样当场生效")
    void appliesWebhookAndMailRecipient() {
        applier.applyAndTrack(Map.of(
                "novabot.core.alert.webhook-url", "https://example.invalid/hook",
                "novabot.core.mail.default-to", "ops@example.invalid"));

        assertEquals("https://example.invalid/hook", properties.getAlert().getWebhookUrl());
        assertEquals("ops@example.invalid", properties.getMail().getDefaultTo());
    }

    @Test
    @DisplayName("首次推送提示开关：关掉之后当场不再附那句用法提示")
    void appliesFirstPushTipSwitch() {
        assertTrue(properties.getPush().isFirstPushTip(), "默认应开着");

        List<String> restart = applier.applyAndTrack(Map.of("novabot.core.push.first-push-tip", "false"));

        assertFalse(properties.getPush().isFirstPushTip(), "关掉应当场生效");
        assertEquals(List.of(), restart);
    }

    @Test
    @DisplayName("备份保留份数：改完当场写回运行中的配置")
    void appliesBackupKeep() {
        List<String> restart = applier.applyAndTrack(Map.of("novabot.core.config-ui.backup-keep", "3"));

        assertEquals(3, properties.getConfigUi().getBackupKeep());
        assertEquals(List.of(), restart);
    }

    @Test
    @DisplayName("⚠️ 阴性：不在名单里的配置项只写文件，运行中的值一动不动")
    void doesNotTouchRestartOnlyProperties() {
        int before = properties.getAlert().getConvergenceInterval();

        List<String> restart = applier.applyAndTrack(Map.of("novabot.core.alert.convergence-interval", "7200"));

        assertEquals(before, properties.getAlert().getConvergenceInterval(),
                "这一项要等重启，此刻不该被改到运行中的配置上");
        assertEquals(List.of("novabot.core.alert.convergence-interval"), restart);
    }

    @Test
    @DisplayName("⚠️ 阴性：值的形式不对时按需重启处理，不当作已生效")
    void unparsableValueCountsAsRestartRequired() {
        int before = properties.getConfigUi().getBackupKeep();

        List<String> restart = applier.applyAndTrack(Map.of("novabot.core.config-ui.backup-keep", "很多"));

        assertEquals(before, properties.getConfigUi().getBackupKeep());
        assertEquals(List.of("novabot.core.config-ui.backup-keep"), restart);
    }

    @Test
    @DisplayName("欠着的那次重启会一直记着，直到进程换一个")
    void tracksPendingRestartAcrossSaves() {
        applier.applyAndTrack(Map.of("novabot.core.alert.convergence-interval", "7200"));
        applier.applyAndTrack(Map.of("novabot.core.push.enabled", "false"));
        applier.applyAndTrack(Map.of("novabot.core.paint.auto-expand-height", "6000"));

        assertEquals(List.of("novabot.core.alert.convergence-interval", "novabot.core.paint.auto-expand-height"),
                applier.getPendingRestart(), "即时生效的那一项不该混进待重启名单");

        // 记录挂在实例上，实例的寿命就是进程的寿命——换一个实例等于程序重启了一次
        assertEquals(List.of(), RuntimeConfigurationApplier.bench(properties).build().getPendingRestart());
    }

    @Test
    @DisplayName("值的形式改对之后当场生效，待重启名单里那条当场划掉；够不着的键仍留着")
    void correctedValueDropsOutOfPendingRestart() {
        applier.applyAndTrack(Map.of("novabot.core.config-ui.backup-keep", "很多"));
        assertTrue(applier.getPendingRestart().contains("novabot.core.config-ui.backup-keep"),
                "值的形式不对时应记入待重启");

        applier.applyAndTrack(Map.of("novabot.core.config-ui.backup-keep", "3"));

        assertEquals(3, properties.getConfigUi().getBackupKeep(), "改对之后应当场生效");
        assertEquals(List.of(), applier.getPendingRestart(),
                "已生效的那一项不该继续挂在待重启名单上");

        // 阳性对照：够不着运行值的键仍按原样留在名单里，划掉不得误伤它们
        applier.applyAndTrack(Map.of("novabot.core.alert.convergence-interval", "7200"));
        assertEquals(List.of("novabot.core.alert.convergence-interval"), applier.getPendingRestart(),
                "无即时生效口的键仍要留在待重启名单里");
    }

    @Test
    @DisplayName("专用口闭集里每一项，通用即时通道都必须拒写")
    void shrinkingTheDedicatedAuthKeySetMustFail() {
        List<String> dedicated = List.of(
                ConfigUiAuthService.PASSWORD_PROPERTY,
                ConfigUiAuthService.TOTP_PROPERTY,
                ConfigUiAuthService.TOTP_SECRET_PROPERTY,
                ConfigUiAuthService.OPERATOR_TOKEN_PROPERTY);

        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        String passwordBefore = auth.getPassword();
        boolean totpBefore = auth.isTotp();
        String secretBefore = auth.getTotpSecret();
        boolean operatorBefore = auth.isOperatorToken();

        Map<String, String> attempts = new LinkedHashMap<>();
        attempts.put(ConfigUiAuthService.PASSWORD_PROPERTY, "changed-password");
        attempts.put(ConfigUiAuthService.TOTP_PROPERTY, "false");
        attempts.put(ConfigUiAuthService.TOTP_SECRET_PROPERTY, "JBSWY3DPEHPK3PXP");
        attempts.put(ConfigUiAuthService.OPERATOR_TOKEN_PROPERTY, "true");

        for (String key : dedicated) {
            assertTrue(ConfigUiAuthService.isDedicatedAuthKey(key),
                    "闭集缺了 " + key + "：专用口键变少必须红");
        }

        List<String> restart = applier.applyAndTrack(attempts);
        for (String key : dedicated) {
            assertTrue(restart.contains(key),
                    "通用写入必须拒 " + key + ", 实际待重启=" + restart);
        }

        assertEquals(passwordBefore, auth.getPassword(), "口令不得被通用写入改掉");
        assertEquals(totpBefore, auth.isTotp(), "二次验证开关不得被通用写入改掉");
        assertEquals(secretBefore, auth.getTotpSecret(), "二次验证密钥不得被通用写入改掉");
        assertEquals(operatorBefore, auth.isOperatorToken(), "启动令牌通道不得被通用写入改掉");
    }

    @Test
    @DisplayName("认证键即使被登记进即时通道，resolve 开头仍拒写且记入待重启")
    void dedicatedAuthKeyIsRejectedEvenWhenRegisteredAsApplier() throws Exception {
        String key = ConfigUiAuthService.PASSWORD_PROPERTY;
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword("keep-me");

        Field field = RuntimeConfigurationApplier.class.getDeclaredField("APPLIERS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, BiConsumer<NovaCoreProperties, String>> appliers =
                (Map<String, BiConsumer<NovaCoreProperties, String>>) field.get(null);

        BiConsumer<NovaCoreProperties, String> previous = appliers.put(key,
                (props, value) -> props.getConfigUi().getAuth().setPassword(value));
        assertNull(previous, "口令键本就不该出现在即时落地表里");
        try {
            List<String> restart = applier.applyAndTrack(Map.of(key, "changed-password"));

            assertEquals("keep-me", auth.getPassword(),
                    "口令不得被通用即时通道改掉，哪怕该键被登记进了落地表");
            assertTrue(restart.contains(key),
                    "拒写必须记入待重启，实际=" + restart);
        } finally {
            appliers.remove(key);
        }
    }

    @Test
    @DisplayName("贡献者登记的键计入 supportedKeys 且能应用")
    void contributorKeysAreSupportedAndApplied() {
        List<String> reds = new ArrayList<>();
        String key = "starbot.demo.runtime.flag";
        AtomicReference<String> seen = new AtomicReference<>();
        RuntimeConfigurationApplierContributor contributor = () -> {
            Map<String, Consumer<String>> appliers = new LinkedHashMap<>();
            appliers.put(key, seen::set);
            return appliers;
        };
        RuntimeConfigurationApplier with = RuntimeConfigurationApplier.bench(properties)
                .contributors(List.of(contributor))
                .build();

        try {
            assertTrue(with.supportedKeys().contains(key),
                    "贡献者登记的键应计入 supportedKeys");
            assertTrue(with.supportedKeys().contains("novabot.core.push.enabled"),
                    "核心自有键仍须在名单里");
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            List<String> restart = with.applyAndTrack(Map.of(key, "on"));
            assertEquals("on", seen.get(), "贡献者登记的键应当场应用");
            assertEquals(List.of(), restart, "已应用的键不该进待重启");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            RuntimeConfigurationApplierContributor clash = () -> Map.of(
                    "novabot.core.push.enabled", value -> { });
            assertThrows(IllegalStateException.class,
                    () -> RuntimeConfigurationApplier.bench(properties)
                            .contributors(List.of(clash))
                            .build(),
                    "与核心自有表撞键须抛 IllegalStateException");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }
}
