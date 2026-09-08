package com.starlwr.bot.adapter.onebot.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.converter.OneBotMessageConverter;
import com.starlwr.bot.adapter.onebot.dto.MessageDTO;
import com.starlwr.bot.adapter.onebot.health.OneBotConnectionState;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapter;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapterProxy;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 平台名写错、或适配层还没 register 时，send 必须明说「未注册」，
 * 不能把空指针兜成「未知异常」。注册后走通的阳性对照在
 * {@link OneBotAtAllImageSplitTest}，此处不重复。
 */
@DisplayName("推送平台未注册时明报")
class OneBotHttpServiceUnregisteredPlatformTest {
    private static final String PLATFORM = "missing-platform";

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
    }

    @AfterEach
    void tearDown() {
        http.close();
        executor.shutdown();
    }

    @Test
    @DisplayName("不 register 直接 send: code 8, 文案含平台名与未注册")
    void unregisteredPlatformReturnsCode8WithName() {
        JSONObject result = service.send(group("hello"));

        assertEquals(8, result.getIntValue("code"), result.toJSONString());
        String message = result.getString("message");
        assertTrue(message.contains(PLATFORM), result.toJSONString());
        assertTrue(message.contains("未注册"), result.toJSONString());
        assertNull(result.get("id"), result.toJSONString());
    }

    @Test
    @DisplayName("不 register 直接 send: 日志里没有空指针")
    void unregisteredPlatformDoesNotLogNullPointerException() {
        Logger logger = (Logger) LoggerFactory.getLogger(OneBotHttpService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.send(group("hello"));

            List<String> npeHits = npeHits(appender);
            assertEquals(List.of(), npeHits, "日志不该出现 NullPointerException: " + npeHits);
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

    private List<String> npeHits(ListAppender<ILoggingEvent> appender) {
        List<String> hits = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            if (mentionsNpe(event.getFormattedMessage())) {
                hits.add(event.getFormattedMessage());
            }
            IThrowableProxy throwable = event.getThrowableProxy();
            while (throwable != null) {
                if (mentionsNpe(throwable.getClassName()) || mentionsNpe(throwable.getMessage())) {
                    hits.add(throwable.getClassName() + ": " + throwable.getMessage());
                }
                throwable = throwable.getCause();
            }
        }
        return hits;
    }

    private boolean mentionsNpe(String text) {
        return text != null && text.contains("NullPointerException");
    }
}
