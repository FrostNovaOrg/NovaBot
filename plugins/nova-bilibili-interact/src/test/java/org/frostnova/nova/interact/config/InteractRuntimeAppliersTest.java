package org.frostnova.nova.interact.config;

import org.frostnova.nova.core.properties.ConfigEffect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹幕三项的保存与生效
 * <p>
 * 抓的用户故障：改了「发弹幕用哪个账号」要重启才生效；手一滑填了个认不出的值，
 * 存下去之后程序悄悄换成了监听账号；改成监听账号时没有人提醒它会连累监听。
 */
@DisplayName("弹幕设置当场生效")
class InteractRuntimeAppliersTest {
    @Test
    @DisplayName("三项都标成即时生效，也都在写回名单里")
    void declaredAndAppliedAgree() throws NoSuchFieldException {
        for (String field : List.of("account", "minInterval", "pauseMinutes")) {
            ConfigEffect effect = InteractProperties.Danmaku.class.getDeclaredField(field).getAnnotation(ConfigEffect.class);
            assertEquals(ConfigEffect.Effect.IMMEDIATE, effect.value(), field);
        }
        assertEquals(List.of(InteractRuntimeAppliers.ACCOUNT, InteractRuntimeAppliers.MIN_INTERVAL,
                InteractRuntimeAppliers.PAUSE_MINUTES), InteractRuntimeAppliers.keys());
        assertEquals(java.util.Set.copyOf(InteractRuntimeAppliers.keys()), new InteractRuntimeAppliers().batchApplier().keys());
    }

    @Test
    @DisplayName("写回之后运行中的配置当场换成新值；删键回到默认")
    void applyWritesTheRunningConfiguration() {
        InteractProperties properties = new InteractProperties();
        var apply = new InteractRuntimeAppliers(properties).batchApplier().apply();

        apply.accept(Map.of(InteractRuntimeAppliers.ACCOUNT, "listener", InteractRuntimeAppliers.MIN_INTERVAL, "5",
                InteractRuntimeAppliers.PAUSE_MINUTES, "15"));
        assertEquals("listener", properties.getDanmaku().getAccount());
        assertEquals(5, properties.getDanmaku().getMinInterval());
        assertEquals(15, properties.getDanmaku().getPauseMinutes());

        Map<String, String> removed = new HashMap<>();
        removed.put(InteractRuntimeAppliers.ACCOUNT, null);
        removed.put(InteractRuntimeAppliers.MIN_INTERVAL, null);
        apply.accept(removed);
        assertEquals("speaker", properties.getDanmaku().getAccount());
        assertEquals(3, properties.getDanmaku().getMinInterval());
        assertEquals(15, properties.getDanmaku().getPauseMinutes());
    }

    @Test
    @DisplayName("认不出的值在存之前就拒收，说清能填什么；一批里有一项不对就一项也不写")
    void invalidValuesAreRefused() {
        InteractRuntimeAppliers appliers = new InteractRuntimeAppliers(new InteractProperties());
        var validators = appliers.valueValidators();

        assertNull(validators.get(InteractRuntimeAppliers.ACCOUNT).apply("speaker"));
        assertNull(validators.get(InteractRuntimeAppliers.ACCOUNT).apply("listener"));
        String wrong = validators.get(InteractRuntimeAppliers.ACCOUNT).apply("both");
        assertNotNull(wrong);
        assertTrue(wrong.contains("speaker") && wrong.contains("listener"), wrong);
        assertNotNull(validators.get(InteractRuntimeAppliers.MIN_INTERVAL).apply("0"));
        assertNotNull(validators.get(InteractRuntimeAppliers.MIN_INTERVAL).apply("三"));
        assertNotNull(validators.get(InteractRuntimeAppliers.PAUSE_MINUTES).apply("0"));

        InteractProperties properties = new InteractProperties();
        var apply = new InteractRuntimeAppliers(properties).batchApplier().apply();
        assertThrows(IllegalArgumentException.class, () -> apply.accept(Map.of(
                InteractRuntimeAppliers.ACCOUNT, "listener", InteractRuntimeAppliers.MIN_INTERVAL, "-1")));
        assertEquals("speaker", properties.getDanmaku().getAccount(), "一批里有一项不对，别的项也不该写");
    }

    @Test
    @DisplayName("改成监听账号要先确认那句风险，改回发言账号不用问")
    void listenerNeedsConfirmation() {
        var confirm = new InteractRuntimeAppliers().saveConfirmations().get(InteractRuntimeAppliers.ACCOUNT);

        String question = confirm.apply("listener");
        assertNotNull(question);
        assertTrue(question.contains("所有主播的监听、开播通知和直播报告会一起受影响"), question);
        assertNull(confirm.apply("speaker"));
    }
}
