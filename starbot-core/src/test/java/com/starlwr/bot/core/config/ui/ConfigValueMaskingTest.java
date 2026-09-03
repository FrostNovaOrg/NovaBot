package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.EventStreamProperties;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.service.EventStreamTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 控制台读数里的机密遮蔽：该遮的与不该遮的两个方向
 *
 * <h2>它治的是哪一种病</h2>
 * 遮蔽此前只看配置项名字里有没有 token / password 这类字眼。
 * <b>而「要不要口令」本身是个开关</b>——{@code starbot.core.event-stream.require-token} 的值只有
 * true 与 false 两种，名字里却带着 token。它被当成机密遮成占位值之后，
 * 界面上的开关拿占位值去比 {@code 'true'}，比不中，于是<b>恒显「已关闭」</b>。
 * 同一形态还有第二项：{@code starbot.core.config-ui.auth.operator-token}
 * ——「忘记口令」的启动令牌通道开着，界面上却写着已关闭。
 * <p>
 * 两项都不是功能坏了，是<b>界面读数骗人</b>：使用者照着界面判断「这道门关着」，
 * 而门开着。装了反向代理却以为开了口令校验，等于没有鉴权。
 *
 * <h2>为什么判据落在端点而不是判定函数上</h2>
 * 病发的位置是「界面拿到的那份读数」，不是某个布尔函数的返回值。
 * 判定函数的签名可以变，而<b>「端点回给界面的值与配置文件里写的是同一个」这句话不该变</b>，
 * 因此判据照着这句话写。
 *
 * <h2>安全相邻：反方向必须同时钉住</h2>
 * 放宽遮蔽是安全动作，只钉「不该遮的别遮」等于给自己留了放宽的余地。
 * 因此每一条<b>真机密</b>逐条列在下面，与上面那两条开关同一次跑：
 * 口令、口令哈希、二次验证密钥、控制台令牌、邮箱与 Redis 口令。
 */
@DisplayName("控制台读数的机密遮蔽")
class ConfigValueMaskingTest {
    /**
     * 现行位置写 require-token，且把在册的真机密逐条摆进来
     */
    private static final String CURRENT_POSITION = """
            server:
              port: 7827
            spring:
              mail:
                password: mail-secret-value
              data:
                redis:
                  password: redis-secret-value
            starbot:
              core:
                config-ui:
                  enabled: true
                  token: console-operator-token-value
                  auth:
                    password: bcrypt-hash-value
                    totp-secret: totp-secret-value
                    operator-token: true
                  napcat:
                    token: napcat-token-value
                    token-hash: napcat-token-hash-value
                    totp-secret: napcat-totp-secret-value
                event-stream:
                  enabled: true
                  require-token: true
            """;

    /**
     * 只写旧位置的既有部署：改名前的配置文件长这样，而程序照旧认得
     */
    private static final String LEGACY_POSITION = """
            starbot:
              core:
                config-ui:
                  enabled: true
              bilibili:
                event-stream:
                  enabled: true
                  require-token: true
            """;

    /**
     * 在册的真机密，逐条列出——每一条都必须继续遮住
     */
    private static final List<String> REAL_SECRETS = List.of(
            "starbot.core.config-ui.token",
            "starbot.core.config-ui.auth.password",
            "starbot.core.config-ui.auth.totp-secret",
            "starbot.core.config-ui.napcat.token",
            "starbot.core.config-ui.napcat.token-hash",
            "starbot.core.config-ui.napcat.totp-secret",
            "spring.mail.password",
            "spring.data.redis.password");

    private static final String REQUIRE_TOKEN = EventStreamProperties.PREFIX + ".require-token";

    private static final String LEGACY_REQUIRE_TOKEN = EventStreamProperties.LEGACY_PREFIX + ".require-token";

    private static final String OPERATOR_TOKEN = "starbot.core.config-ui.auth.operator-token";

    @TempDir
    Path dir;

    private Path config;

    private ConfigUiController controller;

    @BeforeEach
    void setUp() {
        config = dir.resolve("application.yml");
    }

    /**
     * 按给定内容起一份配置，并接上控制台
     * <p>
     * <b>元数据服务用真的，不给桩。</b>「这一项是不是开关」这句话的答案出自编译期生成的配置元数据，
     * 桩一份类型表等于把判据要问的那件事自己答了：类型表写错、配置项改名、字段从开关改成别的类型，
     * 桩都照样绿。其余依赖与本目录下的控制台用例一致，一律给桩。
     */
    @SuppressWarnings("unchecked")
    private void start(String yaml) throws IOException {
        Files.writeString(config, yaml, StandardCharsets.UTF_8);

        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        controller = new ConfigUiController(
                new ConfigurationMetadataService(),
                new ConfigurationFileService(config),
                properties,
                mock(com.starlwr.bot.core.datasource.AbstractDataSource.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(com.starlwr.bot.core.service.StarBotSenderService.class),
                mock(com.starlwr.bot.core.sender.StarBotMessageSender.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                mock(com.starlwr.bot.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    private JSONObject values() {
        JSONObject result = controller.values();
        assertTrue(result.getBooleanValue("success"), "读配置本身不该失败");
        return result.getJSONObject("values");
    }

    @Test
    @DisplayName("写在现行位置的布尔开关照原值回，不遮")
    void booleanSwitchAtCurrentPositionIsNotMasked() throws IOException {
        start(CURRENT_POSITION);

        JSONObject values = values();

        assertEquals("true", values.getString(REQUIRE_TOKEN),
                "开关的值只有 true 与 false 两种, 遮起来藏不住任何东西, 却让界面恒显「已关闭」");
        assertEquals("true", values.getString(OPERATOR_TOKEN),
                "启动令牌通道开着就得显示开着——界面说它关了, 使用者就不会去关它");
        assertEquals("true", values.getString(EventStreamProperties.PREFIX + ".enabled"),
                "同一段里名字不带 token 的开关本来就没被遮, 顺带钉住");
    }

    @Test
    @DisplayName("只写旧位置时，落回来的布尔开关同样不遮")
    void booleanSwitchAtLegacyPositionIsNotMasked() throws IOException {
        start(LEGACY_POSITION);

        JSONObject values = values();

        assertEquals("true", values.getString(REQUIRE_TOKEN),
                "落回旧位置的读数是给界面上那个开关用的, 遮了它界面照样骗人");
        assertEquals("true", values.getString(LEGACY_REQUIRE_TOKEN),
                "旧位置那一行本身也在读数里, 元数据只有现行键, 但它俩是同一项配置");
    }

    @Test
    @DisplayName("在册的真机密逐条仍遮，一条都不许放宽")
    void everyRealSecretStaysMasked() throws IOException {
        start(CURRENT_POSITION);

        JSONObject values = values();

        for (String name : REAL_SECRETS) {
            assertEquals(SensitiveFields.MASK, values.getString(name),
                    name + " 是真机密, 面板可能正开在直播画面上");
        }
    }

    @Test
    @DisplayName("元数据里没有的机密项照旧遮住：判不出类型就往安全的方向失败")
    void unknownFieldFallsBackToMasking() throws IOException {
        start("""
                starbot:
                  core:
                    config-ui:
                      enabled: true
                  plugin:
                    some-unreleased-thing:
                      access-token: unknown-secret-value
                """);

        JSONObject values = values();

        assertEquals(SensitiveFields.MASK, values.getString("starbot.plugin.some-unreleased-thing.access-token"),
                "元数据里查不到类型时不许当成普通字段放出去, 插件的配置项随时可能是新的");
    }

    @Test
    @DisplayName("普通字段与旧位置来源标记的行为都不受影响")
    void ordinaryFieldsAndLegacyMarkerUnchanged() throws IOException {
        start(LEGACY_POSITION);

        JSONObject result = controller.values();
        assertEquals(LEGACY_REQUIRE_TOKEN, result.getJSONObject("legacy").getString(REQUIRE_TOKEN),
                "得说清这一项是从旧位置读来的, 这条是既有行为");

        start(CURRENT_POSITION);
        JSONObject values = values();
        assertEquals("7827", values.getString("server.port"), "普通字段照原样回");
        assertNull(values.getString("starbot.core.event-stream.path"), "文件里没写的项不该凭空多出一个值");
        assertNotEquals(SensitiveFields.MASK, values.getString("starbot.core.config-ui.enabled"),
                "开关一律不遮, 与名字无关");
    }
}
