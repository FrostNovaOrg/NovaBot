package com.starlwr.bot.adapter.onebot.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.converter.OneBotMessageConverter;
import com.starlwr.bot.adapter.onebot.dto.MessageDTO;
import com.starlwr.bot.adapter.onebot.enums.ResultCode;
import com.starlwr.bot.adapter.onebot.health.OneBotConnectionState;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapter;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapterProxy;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.properties.LogProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * QQ 把 @全体成员 和图片放在同一条里时，会把 @全体成员 降成普通文字。
 * 适配层在这种组合下拆成两次发：文字（含 @全体成员）先走，封面图紧跟一条。
 * 没有 @全体成员、或者没有图，仍一次发完。
 */
@DisplayName("@全体成员与图片同条拆发")
class OneBotAtAllImageSplitTest {
    private static final String PLATFORM = "qq-onebot";

    private static final String COVER = "https://example.com/cover.jpg";

    private FakeOneBotHttpServer http;

    private OneBotHttpService service;

    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        http = new FakeOneBotHttpServer();

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.initialize();

        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.getDetect().setEnableHttpDetect(false);

        OneBotConnectionState state = new OneBotConnectionState();
        OneBotHttpAdapter adapter = (OneBotHttpAdapter) Proxy.newProxyInstance(
                OneBotHttpAdapter.class.getClassLoader(),
                new Class[]{OneBotHttpAdapter.class},
                new OneBotHttpAdapterProxy(new HttpUtil(executor, new RestTemplate(), new LogProperties()), state));

        service = new OneBotHttpService(mock(TaskScheduler.class), executor, properties, adapter,
                new OneBotMessageConverter(), state);

        OneBotSender sender = new OneBotSender();
        sender.setName(PLATFORM);
        sender.setOneBotAddress("127.0.0.1");
        sender.setOneBotHttpPort(http.port());
        sender.setOneBotHttpToken("token");
        service.register(sender);
    }

    @AfterEach
    void tearDown() {
        http.close();
        executor.shutdown();
    }

    @Test
    @DisplayName("[at all][text][image] 群消息拆成两次发, 返回第一条的 id")
    void atAllWithImageSplitsIntoTwoGroupMessages() {
        JSONObject result = service.send(group("{at=all}主播开播了{image_url=" + COVER + "}"));

        assertEquals(ResultCode.SUCCESS.getCode(), result.getIntValue("code"), result.toJSONString());
        assertEquals("1", result.getString("id"), "送达 id 该取第一条（文字与@全体成员）的 message_id");

        List<JSONObject> sent = http.groupMessages();
        assertEquals(2, sent.size(), "该打两次 /send_group_msg, 实际: " + sent);

        assertEquals(List.of("at", "text"), types(sent.get(0)), "第一次该是 @全体成员 加文字: " + sent.get(0));
        assertEquals("all", qq(sent.get(0), 0), "第一次第一条该是 @全体成员");
        assertEquals(List.of("image"), types(sent.get(1)), "第二次该只剩图片: " + sent.get(1));
        assertEquals(COVER, file(sent.get(1), 0));
    }

    @Test
    @DisplayName("[at all][text] 没有图, 仍一次发")
    void atAllWithoutImageStaysOne() {
        JSONObject result = service.send(group("{at=all}主播开播了"));

        assertEquals(ResultCode.SUCCESS.getCode(), result.getIntValue("code"), result.toJSONString());
        assertEquals(1, http.groupMessages().size(), "没有图不该拆: " + http.groupMessages());
        assertEquals(List.of("at", "text"), types(http.groupMessages().get(0)));
    }

    @Test
    @DisplayName("[text][image] 没有 @全体成员, 仍一次发")
    void imageWithoutAtAllStaysOne() {
        JSONObject result = service.send(group("主播开播了{image_url=" + COVER + "}"));

        assertEquals(ResultCode.SUCCESS.getCode(), result.getIntValue("code"), result.toJSONString());
        assertEquals(1, http.groupMessages().size(), "没有 @全体成员 不该拆: " + http.groupMessages());
        assertEquals(List.of("text", "image"), types(http.groupMessages().get(0)));
    }

    @Test
    @DisplayName("第二次（图）失败仍算送达, 只记 WARN")
    void imageFollowUpFailureStillCountsAsDelivered() {
        http.failGroupMessageAt(2);

        Logger logger = (Logger) LoggerFactory.getLogger(OneBotHttpService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            JSONObject result = service.send(group("{at=all}主播开播了{image_url=" + COVER + "}"));

            assertEquals(ResultCode.SUCCESS.getCode(), result.getIntValue("code"),
                    "图没发出去也不该回报失败, 否则文字会被再发一遍: " + result.toJSONString());
            assertEquals("1", result.getString("id"));
            assertEquals(2, http.groupMessages().size(), "第二次仍该打出去, 即使对端回了错: " + http.groupMessages());

            List<String> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("图片补发失败"))
                    .toList();
            assertEquals(1, warnings.size(), "该有一条 WARN 说明图没补上: " + warnMessages(appender));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private MessageDTO group(String content) {
        MessageDTO message = new MessageDTO();
        message.setPlatform(PLATFORM);
        message.setType(PushTargetType.GROUP);
        message.setNum(FakeOneBotHttpServer.GROUP_NUM);
        message.setContent(content);
        return message;
    }

    private List<String> types(JSONObject request) {
        JSONArray message = request.getJSONArray("message");
        List<String> types = new ArrayList<>();
        if (message == null) {
            return types;
        }
        for (int i = 0; i < message.size(); i++) {
            types.add(message.getJSONObject(i).getString("type"));
        }
        return types;
    }

    private String qq(JSONObject request, int index) {
        return request.getJSONArray("message").getJSONObject(index).getJSONObject("data").getString("qq");
    }

    private String file(JSONObject request, int index) {
        return request.getJSONArray("message").getJSONObject(index).getJSONObject("data").getString("file");
    }

    private List<String> warnMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
