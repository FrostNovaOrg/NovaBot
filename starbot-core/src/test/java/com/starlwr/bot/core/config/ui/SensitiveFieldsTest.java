package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机密配置项判定测试
 * <p>
 * 这里要守住两件事：<b>该遮的一个都不能漏</b>（漏一个就是把它摆进直播画面），
 * 以及<b>占位值绝不能被写回配置文件</b>（写回去等于当场把口令和密钥全废掉）。
 */
@DisplayName("机密配置项")
class SensitiveFieldsTest {
    @Test
    @DisplayName("口令、令牌与密钥都要认出来")
    void recognizesSecrets() {
        assertTrue(SensitiveFields.isSensitive("starbot.core.config-ui.token"));
        assertTrue(SensitiveFields.isSensitive("starbot.core.config-ui.auth.password"));
        assertTrue(SensitiveFields.isSensitive("starbot.core.config-ui.auth.totp-secret"));
        assertTrue(SensitiveFields.isSensitive("spring.mail.password"));
        assertTrue(SensitiveFields.isSensitive("spring.data.redis.password"));
        assertTrue(SensitiveFields.isSensitive("starbot.adapter.onebot.senders.one-bot-http-token"));
        assertTrue(SensitiveFields.isSensitive("starbot.adapter.onebot.senders.one-bot-websocket-token"));
        assertTrue(SensitiveFields.isSensitive("starbot.adapter.onebot.security.api-token"));
    }

    @Test
    @DisplayName("普通配置项不应被误伤")
    void leavesOrdinaryFieldsAlone() {
        assertFalse(SensitiveFields.isSensitive("starbot.core.config-ui.enabled"));
        assertFalse(SensitiveFields.isSensitive("starbot.core.config-ui.allow-ips"));
        assertFalse(SensitiveFields.isSensitive("starbot.bilibili.dynamic.auto-follow"));
        assertFalse(SensitiveFields.isSensitive("server.port"));
    }

    @Test
    @DisplayName("名字里带 failures / minutes 的限流项不是机密")
    void throttleSettingsAreNotSecrets() {
        // 遮起来只会让人以为设错了
        assertFalse(SensitiveFields.isSensitive("starbot.core.config-ui.auth.max-failures"));
        assertFalse(SensitiveFields.isSensitive("starbot.core.config-ui.auth.lockout-minutes"));
    }

    @Test
    @DisplayName("有值的机密项换成占位值，没值的保持为空")
    void masksOnlyNonEmptySecrets() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("starbot.core.config-ui.token", "真实令牌");
        values.put("spring.mail.password", "");
        values.put("server.port", "7827");

        Map<String, String> masked = SensitiveFields.mask(values);

        assertEquals(SensitiveFields.MASK, masked.get("starbot.core.config-ui.token"));
        assertEquals("", masked.get("spring.mail.password"), "空值要保持为空，否则分不清「设过」与「没设」");
        assertEquals("7827", masked.get("server.port"));
    }

    @Test
    @DisplayName("原样送回的占位值必须被剔除，不能写进配置文件")
    void dropsUnchangedPlaceholders() {
        Map<String, String> changes = new HashMap<>();
        changes.put("starbot.core.config-ui.token", SensitiveFields.MASK);
        changes.put("starbot.core.config-ui.auth.password", "新口令");
        changes.put("server.port", "8000");

        SensitiveFields.dropUnchanged(changes);

        assertFalse(changes.containsKey("starbot.core.config-ui.token"), "没改过的机密项不该出现在写入集合里");
        assertEquals("新口令", changes.get("starbot.core.config-ui.auth.password"), "真的改了就要写进去");
        assertEquals("8000", changes.get("server.port"));
    }

    @Test
    @DisplayName("普通字段即使凑巧等于占位值也照常保存")
    void keepsOrdinaryFieldEqualToMask() {
        Map<String, String> changes = new HashMap<>();
        changes.put("starbot.core.push.quiet-start", SensitiveFields.MASK);

        SensitiveFields.dropUnchanged(changes);

        assertEquals(SensitiveFields.MASK, changes.get("starbot.core.push.quiet-start"));
    }
}
