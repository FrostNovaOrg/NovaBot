package com.starlwr.bot.core.alert;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(http).get(url.capture(), anyMap());
        assertTrue(url.getValue().startsWith("https://example.invalid/push?"), "实际: " + url.getValue());
        assertTrue(url.getValue().contains("title=" + java.net.URLEncoder.encode("直播间断线", java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(url.getValue().contains("content="));
        verify(http, never()).post(anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("地址已带查询参数时应用 & 续接")
    void appendsWithAmpersandWhenQueryExists() {
        properties.getAlert().setWebhookUrl("https://example.invalid/push?group=bot");
        properties.getAlert().setWebhookMethod("GET");

        channel.send("标题", "内容");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(http).get(url.capture(), anyMap());
        assertTrue(url.getValue().startsWith("https://example.invalid/push?group=bot&"), "实际: " + url.getValue());
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
