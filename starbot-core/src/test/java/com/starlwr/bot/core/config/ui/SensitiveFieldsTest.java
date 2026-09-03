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
 * 这里要守住三件事：<b>该遮的一个都不能漏</b>（漏一个就是把它摆进直播画面）、
 * <b>开关一个都不许遮</b>（遮了界面上的开关就恒显「已关闭」，读数骗人比少一项更糟），
 * 以及<b>占位值绝不能被写回配置文件</b>（写回去等于当场把口令和密钥全废掉）。
 */
@DisplayName("机密配置项")
class SensitiveFieldsTest {
    /**
     * 文本类型，绝大多数机密项都是它
     */
    private static final String STRING = "java.lang.String";

    /**
     * 布尔类型，界面上渲染成开关的那一类
     */
    private static final String BOOLEAN = "java.lang.Boolean";

    /**
     * 本用例用到的类型表，与配置元数据里的写法一致
     */
    private static final Map<String, String> TYPES = Map.of(
            "starbot.core.config-ui.token", STRING,
            "starbot.core.config-ui.auth.password", STRING,
            "starbot.core.event-stream.require-token", BOOLEAN,
            "spring.mail.password", STRING,
            "server.port", "java.lang.Integer",
            "starbot.core.push.quiet-start", STRING);

    @Test
    @DisplayName("口令、令牌与密钥都要认出来")
    void recognizesSecrets() {
        assertTrue(SensitiveFields.isSensitive("starbot.core.config-ui.token", STRING));
        assertTrue(SensitiveFields.isSensitive("starbot.core.config-ui.auth.password", STRING));
        assertTrue(SensitiveFields.isSensitive("starbot.core.config-ui.auth.totp-secret", STRING));
        assertTrue(SensitiveFields.isSensitive("spring.mail.password", STRING));
        assertTrue(SensitiveFields.isSensitive("spring.data.redis.password", STRING));
        assertTrue(SensitiveFields.isSensitive("starbot.adapter.onebot.senders.one-bot-http-token", STRING));
        assertTrue(SensitiveFields.isSensitive("starbot.adapter.onebot.senders.one-bot-websocket-token", STRING));
        assertTrue(SensitiveFields.isSensitive("starbot.adapter.onebot.security.api-token", STRING));
    }

    @Test
    @DisplayName("类型未知时照旧按名字遮住：判不出来就往安全的方向失败")
    void masksByNameWhenTypeUnknown() {
        assertTrue(SensitiveFields.isSensitive("starbot.core.config-ui.token", null));
        assertTrue(SensitiveFields.isSensitive("spring.mail.password", null));
        assertTrue(SensitiveFields.isSensitive("starbot.plugin.not-yet-loaded.api-key", null),
                "插件的配置项在插件加载前查不到类型, 这时更不能当成普通字段放出去");
    }

    @Test
    @DisplayName("布尔开关一律不遮，名字里带 token 也不遮")
    void booleanSwitchesAreNeverSecrets() {
        // 值只有 true 与 false 两种, 遮它藏不住任何东西, 却让界面上的开关恒显「已关闭」
        assertFalse(SensitiveFields.isSensitive("starbot.core.event-stream.require-token", BOOLEAN));
        assertFalse(SensitiveFields.isSensitive("starbot.core.config-ui.auth.operator-token", BOOLEAN));
        assertFalse(SensitiveFields.isSensitive("starbot.bilibili.event-stream.require-token", BOOLEAN));
        // 原始类型与包装类型同样处理: 元数据两种写法都出得来
        assertFalse(SensitiveFields.isSensitive("starbot.core.config-ui.auth.operator-token", "boolean"));
    }

    @Test
    @DisplayName("数字不在放行之列：数字是可以保密的，布尔不可以")
    void numbersAreStillJudgedByName() {
        assertTrue(SensitiveFields.isSensitive("starbot.plugin.some-thing.numeric-token", "java.lang.Long"),
                "PIN 与纯数字的密钥都是数字, 放行数字就是放宽遮蔽");
    }

    @Test
    @DisplayName("普通配置项不应被误伤")
    void leavesOrdinaryFieldsAlone() {
        assertFalse(SensitiveFields.isSensitive("starbot.core.config-ui.enabled", BOOLEAN));
        assertFalse(SensitiveFields.isSensitive("starbot.core.config-ui.allow-ips", "java.util.List<java.lang.String>"));
        assertFalse(SensitiveFields.isSensitive("starbot.bilibili.dynamic.auto-follow", BOOLEAN));
        assertFalse(SensitiveFields.isSensitive("server.port", "java.lang.Integer"));
    }

    @Test
    @DisplayName("名字里带 failures / minutes 的限流项不是机密")
    void throttleSettingsAreNotSecrets() {
        // 遮起来只会让人以为设错了。这两项曾经写在一张具名例外表里，
        // 而按名字判本来就判不中它们——例外表是多余的，删掉之后本用例照样绿
        assertFalse(SensitiveFields.isSensitive("starbot.core.config-ui.auth.max-failures", "java.lang.Integer"));
        assertFalse(SensitiveFields.isSensitive("starbot.core.config-ui.auth.lockout-minutes", "java.lang.Integer"));
    }

    @Test
    @DisplayName("有值的机密项换成占位值，没值的保持为空，开关照原值出去")
    void masksOnlyNonEmptySecrets() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("starbot.core.config-ui.token", "真实令牌");
        values.put("spring.mail.password", "");
        values.put("server.port", "7827");
        values.put("starbot.core.event-stream.require-token", "true");

        Map<String, String> masked = SensitiveFields.mask(values, TYPES::get);

        assertEquals(SensitiveFields.MASK, masked.get("starbot.core.config-ui.token"));
        assertEquals("", masked.get("spring.mail.password"), "空值要保持为空，否则分不清「设过」与「没设」");
        assertEquals("7827", masked.get("server.port"));
        assertEquals("true", masked.get("starbot.core.event-stream.require-token"),
                "开关照原值出去，否则界面上那个开关恒显「已关闭」");
    }

    @Test
    @DisplayName("查不到类型表时按名字判，也就是照旧遮住")
    void masksWithoutTypeTable() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("starbot.core.config-ui.token", "真实令牌");

        assertEquals(SensitiveFields.MASK, SensitiveFields.mask(values, null).get("starbot.core.config-ui.token"));
    }

    @Test
    @DisplayName("原样送回的占位值必须被剔除，不能写进配置文件")
    void dropsUnchangedPlaceholders() {
        Map<String, String> changes = new HashMap<>();
        changes.put("starbot.core.config-ui.token", SensitiveFields.MASK);
        changes.put("starbot.core.config-ui.auth.password", "新口令");
        changes.put("server.port", "8000");

        SensitiveFields.dropUnchanged(changes, TYPES::get);

        assertFalse(changes.containsKey("starbot.core.config-ui.token"), "没改过的机密项不该出现在写入集合里");
        assertEquals("新口令", changes.get("starbot.core.config-ui.auth.password"), "真的改了就要写进去");
        assertEquals("8000", changes.get("server.port"));
    }

    @Test
    @DisplayName("普通字段即使凑巧等于占位值也照常保存")
    void keepsOrdinaryFieldEqualToMask() {
        Map<String, String> changes = new HashMap<>();
        changes.put("starbot.core.push.quiet-start", SensitiveFields.MASK);

        SensitiveFields.dropUnchanged(changes, TYPES::get);

        assertEquals(SensitiveFields.MASK, changes.get("starbot.core.push.quiet-start"));
    }
}
