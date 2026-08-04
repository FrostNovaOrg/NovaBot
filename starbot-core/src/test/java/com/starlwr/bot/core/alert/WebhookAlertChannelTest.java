package com.starlwr.bot.core.alert;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Webhook 告警通道测试
 * <p>
 * 这条通道的存在意义是「QQ 掉线时仍能把告警送出去」，因此它自身不能有隐性依赖，
 * 用例覆盖启用判据、两种请求方式与字段名可配三件事。
 */
@DisplayName("Webhook 告警通道")
class WebhookAlertChannelTest {
    private StarBotCoreProperties properties;

    private HttpUtil http;

    private WebhookAlertChannel channel;

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
        http = mock(HttpUtil.class);
        channel = new WebhookAlertChannel(properties, http);
    }

    @Test
    @DisplayName("未配置地址时应判定为不可用")
    void unavailableWithoutUrl() {
        assertFalse(channel.isAvailable());
    }

    @Test
    @DisplayName("配置地址后应判定为可用")
    void availableWithUrl() {
        properties.getAlert().setWebhookUrl("https://example.invalid/push");

        assertTrue(channel.isAvailable());
    }

    @Test
    @DisplayName("默认应以 POST 提交 JSON")
    void postsJsonByDefault() {
        properties.getAlert().setWebhookUrl("https://example.invalid/push");

        channel.send("标题", "内容");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        JSONObject json = (JSONObject) body.getValue();
        assertEquals("标题", json.getString("title"));
        assertEquals("内容", json.getString("content"));
    }

    @Test
    @DisplayName("字段名应可配置以适配不同服务")
    void usesConfiguredFieldNames() {
        properties.getAlert().setWebhookUrl("https://example.invalid/push");
        properties.getAlert().setWebhookTitleField("text");
        properties.getAlert().setWebhookContentField("desp");

        channel.send("标题", "内容");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        JSONObject json = (JSONObject) body.getValue();
        assertEquals("标题", json.getString("text"));
        assertEquals("内容", json.getString("desp"));
    }

    @Test
    @DisplayName("GET 方式应把标题与内容编码进查询串")
    void appendsQueryForGet() {
        properties.getAlert().setWebhookUrl("https://example.invalid/push");
        properties.getAlert().setWebhookMethod("GET");

        channel.send("直播间断线", "请重新扫码");

        URI uri = capturedUri();
        assertTrue(uri.toString().startsWith("https://example.invalid/push?"), "实际: " + uri);
        assertTrue(uri.toString().contains("title=" + URLEncoder.encode("直播间断线", StandardCharsets.UTF_8)));
        assertTrue(uri.toString().contains("content="));
        verify(http, never()).post(anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("GET 方式必须以 URI 传入，避免中文被二次编码")
    void passesUriToAvoidDoubleEncoding() {
        properties.getAlert().setWebhookUrl("https://example.invalid/push");
        properties.getAlert().setWebhookMethod("GET");

        channel.send("异常告警", "机器人连接");

        // 传字符串会被 RestTemplate 当作 URI 模板再编码一次，接收方收到的是字面的 %E5%BC%82…
        // 真机上正是这样：手机推送里显示的是一串百分号转义而非中文
        URI uri = capturedUri();
        assertFalse(uri.toString().contains("%25"), "查询串中不应出现二次编码的 %25，实际: " + uri);
        assertEquals("异常告警", queryValue(uri, "title"));
        assertEquals("机器人连接", queryValue(uri, "content"));
    }

    @Test
    @DisplayName("地址已带查询参数时应用 & 续接")
    void appendsWithAmpersandWhenQueryExists() {
        properties.getAlert().setWebhookUrl("https://example.invalid/push?group=bot");
        properties.getAlert().setWebhookMethod("GET");

        channel.send("标题", "内容");

        assertTrue(capturedUri().toString().startsWith("https://example.invalid/push?group=bot&"),
                "实际: " + capturedUri());
    }

    /**
     * 取出实际请求的 URI
     */
    private URI capturedUri() {
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(http, org.mockito.Mockito.atLeastOnce()).get(uri.capture(), anyMap());
        return uri.getValue();
    }

    /**
     * 解出查询串中某个参数的原始取值
     */
    private String queryValue(URI uri, String key) {
        for (String pair : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    @DisplayName("配置的附加请求头应随请求发出")
    void sendsConfiguredHeaders() {
        properties.getAlert().setWebhookUrl("https://example.invalid/push");
        properties.getAlert().getWebhookHeaders().put("Authorization", "Bearer token");

        channel.send("标题", "内容");

        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.captor();
        verify(http).post(anyString(), headers.capture(), any());
        assertEquals("Bearer token", headers.getValue().get("Authorization"));
    }
}
