package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.service.EventStreamTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 控制台的只读口令签发／吊销接口
 * <p>
 * 这里最要紧的一条不是「功能能用」，而是<b>响应里绝不能出现哈希或明文</b>——
 * 控制台可能在直播画面里被打开过，这不是假设，配置界面的机密项遮蔽就是为此存在的。
 */
@DisplayName("控制台只读口令接口")
class EventTokenEndpointsTest {
    @TempDir
    Path dir;

    private EventStreamTokenService tokens;

    private ConfigUiController controller;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        tokens = new EventStreamTokenService(properties.getLive());

        // 构造器只做赋值，其余依赖对本组用例毫无参与，全部给桩
        controller = new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
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
                tokens);
    }

    @Test
    @DisplayName("🔒 会话校验端点：只回 2xx 且不带响应体")
    void sessionCheckReturnsNoContent() {
        var response = controller.sessionCheck();

        // nginx 的 auth_request 只看状态码：2xx 放行、401/403 拒绝。
        // 这一位是契约——改成「200 带一段 body」也照样能用，
        // 但那段 body 会被 auth_request 直接丢掉，白白多一次序列化，
        // 而且会诱使后来人往里塞信息，那些信息谁也看不到
        assertTrue(response.getStatusCode().is2xxSuccessful(), "2xx 才会被 auth_request 当成放行");
        assertNull(response.getBody(), "校验端点唯一的输出就是状态码");
    }

    @Test
    @DisplayName("签发应返回明文，并明说这是唯一一次显示")
    void issueReturnsPlaintextOnce() {
        JSONObject result = controller.issueEventToken(Map.of("label", "面板-甲"));

        assertTrue(result.getBooleanValue("success"));
        String token = result.getString("token");
        assertNotNull(token);
        assertTrue(token.length() >= 32);
        assertTrue(result.getString("message").contains("唯一一次"),
                "必须把「关掉就再也看不到」讲清楚，否则会被当成缺陷");
    }

    @Test
    @DisplayName("没填「签给谁」应当拒绝签发")
    void refusesToIssueWithoutLabel() {
        assertFalse(controller.issueEventToken(Map.of()).getBooleanValue("success"));
        assertFalse(controller.issueEventToken(Map.of("label", "  ")).getBooleanValue("success"));
        // 没有标签就只能一次全撤，等于退回复用控制台令牌时的粒度
        assertEquals(0, tokens.list().size(), "拒绝之后不该留下半条记录");
    }

    @Test
    @DisplayName("🔴 守卫：清单绝不能带出哈希或明文")
    void listNeverLeaksSecrets() {
        String token = controller.issueEventToken(Map.of("label", "面板-甲")).getString("token");
        String hash = tokens.list().get(0).hash();

        JSONArray items = controller.listEventTokens().getJSONArray("tokens");
        String serialized = items.toJSONString();

        assertFalse(serialized.contains(token), "明文绝不能出现在清单里");
        assertFalse(serialized.contains(hash), "哈希同样不该送到浏览器里");
        // 阳性对照：这条断言得能分辨——指纹是该出现的，出现了才说明我们确实在看这份数据
        assertTrue(serialized.contains(tokens.fingerprintOf(tokens.list().get(0))),
                "指纹该在，否则上面两条「不含」可能只是因为清单是空的");
    }

    @Test
    @DisplayName("清单要列出已吊销的，那是审计事实")
    void listIncludesRevoked() {
        controller.issueEventToken(Map.of("label", "面板-甲"));
        String fingerprint = tokens.fingerprintOf(tokens.list().get(0));

        controller.revokeEventToken(fingerprint);

        JSONArray items = controller.listEventTokens().getJSONArray("tokens");
        assertEquals(1, items.size(), "撤了不等于消失");
        JSONObject item = items.getJSONObject(0);
        assertFalse(item.getBooleanValue("active"));
        assertTrue(item.getLongValue("revokedAt") > 0);
        assertEquals("面板-甲", item.getString("label"), "签给谁要留着，否则不知道撤的是谁");
    }

    @Test
    @DisplayName("吊销的回执必须提醒「已建连接不会自动断」")
    void revokeWarnsAboutLiveConnections() {
        controller.issueEventToken(Map.of("label", "面板-甲"));
        String fingerprint = tokens.fingerprintOf(tokens.list().get(0));

        JSONObject result = controller.revokeEventToken(fingerprint);

        assertTrue(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains("不会自动断开"),
                "这个坑换了机制也还在，回执里必须说");
    }

    @Test
    @DisplayName("撤一把不影响另一把")
    void revokeIsTargeted() {
        controller.issueEventToken(Map.of("label", "面板-甲"));
        controller.issueEventToken(Map.of("label", "面板-乙"));
        String first = tokens.fingerprintOf(tokens.list().get(0));

        controller.revokeEventToken(first);

        JSONArray items = controller.listEventTokens().getJSONArray("tokens");
        assertFalse(items.getJSONObject(0).getBooleanValue("active"));
        assertTrue(items.getJSONObject(1).getBooleanValue("active"));
    }
}
