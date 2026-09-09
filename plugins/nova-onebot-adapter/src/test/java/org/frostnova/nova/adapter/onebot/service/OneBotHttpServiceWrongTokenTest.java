package org.frostnova.nova.adapter.onebot.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter;
import org.frostnova.nova.adapter.onebot.dto.MessageDTO;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapterProxy;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.properties.LogProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.util.HttpUtil;
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
 * Token 配错时 OneBot 回 403，send 必须明说「Token 配置不正确」，
 * 不能把 Forbidden 兜成「未知异常」并打整条栈。注册后走通的阳性对照在
 * {@link OneBotAtAllImageSplitTest}，此处不重复。
 */
@DisplayName("Token 配错时发送口明报")
class OneBotHttpServiceWrongTokenTest {
    private static final String PLATFORM = "qq-onebot";

    private FakeOneBotHttpServer http;

    private OneBotHttpService service;

    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        http = new FakeOneBotHttpServer();
        http.forbidSends();

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
        sender.setOneBotHttpToken("wrong-token");
        service.register(sender);
    }

    @AfterEach
    void tearDown() {
        http.close();
        executor.shutdown();
    }

    @Test
    @DisplayName("register 后 send: code 5, 文案含 Token")
    void wrongTokenReturnsCode5WithTokenMessage() {
        JSONObject result = service.send(group("hello"));

        assertEquals(5, result.getIntValue("code"), result.toJSONString());
        assertTrue(result.getString("message").contains("Token"), result.toJSONString());
        assertNull(result.get("id"), result.toJSONString());
    }

    @Test
    @DisplayName("register 后 send: 日志没有 HttpClientErrorException 栈")
    void wrongTokenDoesNotLogForbiddenStack() {
        Logger logger = (Logger) LoggerFactory.getLogger(OneBotHttpService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.send(group("hello"));

            List<String> stackHits = forbiddenStackHits(appender);
            assertEquals(List.of(), stackHits, "日志不该出现 HttpClientErrorException 栈: " + stackHits);
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

    private List<String> forbiddenStackHits(ListAppender<ILoggingEvent> appender) {
        List<String> hits = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            IThrowableProxy throwable = event.getThrowableProxy();
            if (throwable != null) {
                hits.add(throwable.getClassName() + ": " + throwable.getMessage());
            }
            while (throwable != null) {
                if (mentionsForbidden(throwable.getClassName()) || mentionsForbidden(throwable.getMessage())) {
                    hits.add(throwable.getClassName() + ": " + throwable.getMessage());
                }
                throwable = throwable.getCause();
            }
        }
        return hits;
    }

    private boolean mentionsForbidden(String text) {
        return text != null && text.contains("HttpClientErrorException");
    }
}
