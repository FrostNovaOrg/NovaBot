package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.alert.AlertChannel;
import com.starlwr.bot.core.alert.AlertService;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.timeline.TimelineWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「发一条测试」的判据
 * <p>
 * 这支接口存在的理由是「配好了没有」只有真发一条才答得出，因此它的判据也只关心一件事：
 * <b>四种结局分得开吗</b>——没这一路、还没配好、发不出去、发出去了。分不开的话，
 * 界面上就只剩一句对三种情况都说得通、也都没用的「失败」。
 */
@DisplayName("告警通道测试接口")
class AlertTestControllerTest {
    private List<AlertChannel> channels;

    private AlertTestController controller;

    @BeforeEach
    void setUp() {
        channels = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
        // orderedStream() 每次调用都要拿到一条新的流，用 thenAnswer 而不是 thenReturn
        when(provider.orderedStream()).thenAnswer(invocation -> channels.stream());

        // 「发一条试试」走的是 AlertService#test，那一支不经投递路径，本就不记时间线
        controller = new AlertTestController(
                new AlertService(new StarBotCoreProperties(), provider, TimelineWriter.NONE));
    }

    /**
     * 记下收到什么的假通道
     */
    private static class FakeChannel implements AlertChannel {
        private final String id;
        private final String name;
        boolean available = true;
        boolean failing;
        final List<String> received = new ArrayList<>();

        FakeChannel(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public void send(String subject, String content) {
            if (failing) {
                throw new IllegalStateException("模拟出网中断");
            }
            received.add(subject + "\n" + content);
        }
    }

    private FakeChannel add(String id, String name) {
        FakeChannel channel = new FakeChannel(id, name);
        channels.add(channel);
        return channel;
    }

    @Test
    @DisplayName("三路各发一条：都回 200，且消息真的进了那一路")
    void deliversThroughEachChannel() {
        FakeChannel qq = add("qq", "QQ");
        FakeChannel webhook = add("webhook", "Webhook");
        FakeChannel mail = add("mail", "邮件");

        for (FakeChannel channel : List.of(qq, webhook, mail)) {
            ResponseEntity<JSONObject> response = controller.test(channel.id());

            assertEquals(HttpStatus.OK, response.getStatusCode(), channel.id() + " 这一路应当回 200");
            JSONObject body = response.getBody();
            assertTrue(body.getBooleanValue("success"), channel.id() + " 这一路应当报成功");
            assertEquals("DELIVERED", body.getString("status"));
            assertEquals(channel.name(), body.getString("name"));
            // 「回了成功」与「真的发出去了」是两件事：只看返回值的话，
            // 一支什么都不做、直接回 success 的实现同样能让这一格变绿
            assertEquals(1, channel.received.size(), channel.id() + " 这一路没有真的收到消息");
        }
    }

    @Test
    @DisplayName("没配好的那一路回 200 但不报成功，并说清是没配好")
    void reportsNotConfigured() {
        FakeChannel webhook = add("webhook", "Webhook");
        webhook.available = false;

        ResponseEntity<JSONObject> response = controller.test("webhook");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JSONObject body = response.getBody();
        assertFalse(body.getBooleanValue("success"));
        assertEquals("NOT_CONFIGURED", body.getString("status"));
        assertTrue(webhook.received.isEmpty(), "没配好的通道不该被真的调用");
    }

    @Test
    @DisplayName("发不出去的那一路回 200 但不报成功，并把原因带上")
    void reportsSendFailure() {
        FakeChannel mail = add("mail", "邮件");
        mail.failing = true;

        ResponseEntity<JSONObject> response = controller.test("mail");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JSONObject body = response.getBody();
        assertFalse(body.getBooleanValue("success"));
        assertEquals("FAILED", body.getString("status"));
        // 原因要落到界面上。只回一句「失败」的话，使用者下一步无从做起
        assertTrue(body.getString("message").contains("模拟出网中断"),
                "发送失败的原因没有带回界面: " + body.getString("message"));
    }

    @Test
    @DisplayName("认不出的通道标识回 400，不是回一句含糊的失败")
    void rejectsUnknownChannel() {
        add("qq", "QQ");

        for (String bad : List.of("sms", "", "QQ")) {
            ResponseEntity<JSONObject> response = controller.test(bad);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                    "通道标识 " + bad + " 不该被认出来");
            assertEquals("UNKNOWN", response.getBody().getString("status"));
        }
    }

    @Test
    @DisplayName("一路通道都没装时，任何标识都认不出来")
    void rejectsWhenNoChannelRegistered() {
        ResponseEntity<JSONObject> response = controller.test("qq");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("UNKNOWN", response.getBody().getString("status"));
    }
}
