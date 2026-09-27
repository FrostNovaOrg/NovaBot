package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.alert.AlertChannel;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.alert.WebhookAlertChannel;
import org.frostnova.nova.core.properties.EventStreamProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制台读数里的机密遮蔽：该遮的与不该遮的两个方向
 *
 * <h2>它治的是哪一种病</h2>
 * 遮蔽此前只看配置项名字里有没有 token / password 这类字眼。
 * <b>而「要不要口令」本身是个开关</b>——{@code novabot.core.event-stream.require-token} 的值只有
 * true 与 false 两种，名字里却带着 token。它被当成机密遮成占位值之后，
 * 界面上的开关拿占位值去比 {@code 'true'}，比不中，于是<b>恒显「已关闭」</b>。
 * 同一形态还有第二项：{@code novabot.core.config-ui.auth.operator-token}
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
            novabot:
              adapter:
                onebot:
                  napcat:
                    token: napcat-token-value
                    token-hash: napcat-token-hash-value
                    totp-secret: napcat-totp-secret-value
              core:
                alert:
                  webhook-url: https://api.day.app/AbCdEfPushKey123/
                  webhook-method: GET
                  webhook-title-field: title
                  webhook-content-field: body
                  webhook-headers:
                    Authorization: Bearer header-secret-value
                config-ui:
                  enabled: true
                  token: console-operator-token-value
                  auth:
                    password: bcrypt-hash-value
                    totp-secret: totp-secret-value
                    operator-token: true
                event-stream:
                  enabled: true
                  require-token: true
            """;

    /**
     * Bark 把推送密钥拼在地址里（Server 酱同形，密钥在路径段），<b>地址本身就是凭据</b>
     */
    private static final String BARK_URL = "https://api.day.app/AbCdEfPushKey123/";

    /**
     * 另一枚推送密钥，换地址那一格用
     */
    private static final String NEW_BARK_URL = "https://api.day.app/NewKey456/";

    /**
     * 在册的真机密，逐条列出——每一条都必须继续遮住
     */
    private static final List<String> REAL_SECRETS = List.of(
            "novabot.core.config-ui.token",
            "novabot.core.config-ui.auth.password",
            "novabot.core.config-ui.auth.totp-secret",
            "novabot.adapter.onebot.napcat.token",
            "novabot.adapter.onebot.napcat.token-hash",
            "novabot.adapter.onebot.napcat.totp-secret",
            "spring.mail.password",
            "spring.data.redis.password",
            "novabot.core.alert.webhook-url",
            "novabot.core.alert.webhook-headers.Authorization");

    private static final String REQUIRE_TOKEN = EventStreamProperties.PREFIX + ".require-token";

    private static final String OPERATOR_TOKEN = "novabot.core.config-ui.auth.operator-token";

    @TempDir
    Path dir;

    private Path config;

    private NovaCoreProperties properties;

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

        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        controller = new ConfigUiController(
                new ConfigurationMetadataService(),
                new ConfigurationFileService(config),
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                // 不 mock 这个具体类：内联 mock 要改写它的字节码，clean 构建下实测会抛「could not instrument」。
                // 给个空上下文即可，本组用例不看生效时机
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class));
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
    @DisplayName("旧键名同样遮蔽")
    void legacyNapcatKeysStayMasked() throws IOException {
        List<String> red = new ArrayList<>();
        start("""
                starbot:
                  core:
                    config-ui:
                      enabled: true
                      napcat:
                        token: napcat-legacy-token-value
                        token-hash: napcat-legacy-hash-value
                        totp-secret: napcat-legacy-totp-value
                """);
        JSONObject values = values();
        try {
            assertEquals(SensitiveFields.MASK, values.getString("starbot.core.config-ui.napcat.token"),
                    "旧 token 仍须遮");
        } catch (AssertionError e) {
            red.add("①" + e.getMessage());
        }
        try {
            assertEquals(SensitiveFields.MASK, values.getString("starbot.core.config-ui.napcat.token-hash"),
                    "旧 token-hash 仍须遮");
        } catch (AssertionError e) {
            red.add("②" + e.getMessage());
        }
        try {
            assertEquals(SensitiveFields.MASK, values.getString("starbot.core.config-ui.napcat.totp-secret"),
                    "旧 totp-secret 仍须遮");
        } catch (AssertionError e) {
            red.add("③" + e.getMessage());
        }
        if (!red.isEmpty()) {
            fail("旧键名同样遮蔽三问中 " + red.size() + " 问未销: " + String.join("；", red));
        }
    }

    @Test
    @DisplayName("元数据里没有的机密项照旧遮住：判不出类型就往安全的方向失败")
    void unknownFieldFallsBackToMasking() throws IOException {
        start("""
                novabot:
                  core:
                    config-ui:
                      enabled: true
                  plugin:
                    some-unreleased-thing:
                      access-token: unknown-secret-value
                """);

        JSONObject values = values();

        assertEquals(SensitiveFields.MASK, values.getString("novabot.plugin.some-unreleased-thing.access-token"),
                "元数据里查不到类型时不许当成普通字段放出去, 插件的配置项随时可能是新的");
    }

    @Test
    @DisplayName("普通字段的行为不受影响")
    void ordinaryFieldsUnchanged() throws IOException {
        start(CURRENT_POSITION);
        JSONObject values = values();
        assertEquals("7827", values.getString("server.port"), "普通字段照原样回");
        assertNull(values.getString("novabot.core.event-stream.path"), "文件里没写的项不该凭空多出一个值");
        assertNotEquals(SensitiveFields.MASK, values.getString("novabot.core.config-ui.enabled"),
                "开关一律不遮, 与名字无关");
    }

    /**
     * 同一段里那三项问的是「怎么发」：提交方式与两个字段名。它们不是凭据，
     * 遮住之后使用者连自己配的是 GET 还是 POST 都看不见，对接时每问一次就要去翻一次文件。
     */
    @Test
    @DisplayName("Bark 地址里的推送密钥不许原样回给页面；同段的提交方式与字段名不跟着遮")
    void webhookAddressIsMaskedWhileShapeStaysReadable() throws IOException {
        start(CURRENT_POSITION);

        JSONObject values = values();

        assertEquals(SensitiveFields.MASK, values.getString("novabot.core.alert.webhook-url"),
                "地址里那串就是推送密钥, 面板可能正开在直播画面上");
        assertEquals("GET", values.getString("novabot.core.alert.webhook-method"), "提交方式不是凭据");
        assertEquals("title", values.getString("novabot.core.alert.webhook-title-field"), "标题字段名不是凭据");
        assertEquals("body", values.getString("novabot.core.alert.webhook-content-field"), "内容字段名不是凭据");
    }

    /**
     * 附加请求头存的是 {@code Authorization: Bearer …} 这类鉴权串。条目名由使用者自己起，
     * 叫什么都不影响「整张表存的就是鉴权头」这件事，因此整棵子树一处不漏。
     */
    @Test
    @DisplayName("附加请求头不许原样回给页面")
    void webhookHeadersAreMasked() throws IOException {
        start(CURRENT_POSITION);

        JSONObject values = values();

        assertEquals(SensitiveFields.MASK, values.getString("novabot.core.alert.webhook-headers.Authorization"),
                "这一项的整份值就是鉴权串");
    }

    /**
     * 使用者改了别的字段一起保存时，界面把遮罩原样送回来，就是「这一项没动」。
     * 写回配置文件的话，Bark 地址当场变成一串星号，而告警这条路看起来还配着。
     */
    @Test
    @DisplayName("遮罩原样送回不改盘上的地址")
    void placeholderSaveKeepsAddressOnDisk() throws IOException {
        start(CURRENT_POSITION);
        String before = Files.readString(config, StandardCharsets.UTF_8);

        controller.save(Map.of("novabot.core.alert.webhook-url", SensitiveFields.MASK));

        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8),
                "送回遮罩等于没改, 盘上的地址必须还是原来那个");
    }

    @Test
    @DisplayName("重新填一个新地址照常落盘")
    void newValueSaveReplacesAddress() throws IOException {
        start(CURRENT_POSITION);

        controller.save(Map.of("novabot.core.alert.webhook-url", NEW_BARK_URL));

        String after = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(after.contains(NEW_BARK_URL), "新地址要写进配置文件: " + after);
        assertTrue(!after.contains(BARK_URL), "旧地址要被换掉: " + after);
    }

    /**
     * 「发一条测试」是这套东西唯一的反馈——它必须按<b>盘上的地址</b>往外发。
     * 遮罩只许停在界面上：送回一趟保存之后地址若变成一串星号，测试消息就发去了不存在的地方，
     * 而界面照样报「已发出」，使用者从此以为这一路是通的。
     */
    @Test
    @DisplayName("遮罩送回保存一趟之后，发一条测试仍按真地址发")
    void testSendStillUsesTheAddressOnDisk() throws IOException {
        start(CURRENT_POSITION);
        // 程序启动时绑定好的运行值：与文件里同一个地址、同一种提交方式
        properties.getAlert().setWebhookUrl(BARK_URL);
        properties.getAlert().setWebhookMethod("GET");
        properties.getAlert().getWebhookHeaders().put("Authorization", "Bearer header-secret-value");
        controller.save(Map.of("novabot.core.alert.webhook-url", SensitiveFields.MASK));

        HttpUtil http = mock(HttpUtil.class);
        when(http.getForStatus(any(URI.class), anyMap())).thenReturn(200);
        when(http.postForStatus(anyString(), anyMap(), any())).thenReturn(200);
        @SuppressWarnings("unchecked")
        ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.of(
                new WebhookAlertChannel(properties, http)));
        AlertService alertService = new AlertService(properties, provider, TimelineWriter.NONE);

        AlertService.TestResult result = alertService.test("webhook");

        assertTrue(result.delivered(), "这一路按运行值该发得出去: " + result.message());
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass((Class) Map.class);
        verify(http).getForStatus(uri.capture(), headers.capture());
        assertTrue(uri.getValue().toString().startsWith(BARK_URL),
                "发出去的地址必须还是盘上那个, 实际: " + uri.getValue());
        assertEquals("Bearer header-secret-value", headers.getValue().get("Authorization"),
                "附加请求头也按盘上那个发");
    }
}
