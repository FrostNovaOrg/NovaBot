package com.starlwr.bot.adapter.onebot.service;

import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.controller.OneBotController;
import com.starlwr.bot.adapter.onebot.health.OneBotConnectionState;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.adapter.onebot.security.PushApiTokenStore;
import com.starlwr.bot.core.service.StarBotSenderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 空 OneBot Token 的两条提醒必须是 WARN，且说清后果
 * <p>
 * 「还没配 Token」在全新安装上是<b>必经的一站</b>，不是故障：出厂配置里两把 Token 都留空，
 * 首次启动必然走到这两条分支。此前它们按 ERROR 打，使用者第一天打开日志看到的就是两条红——
 * 而「配好之前推送不可用」这句话里没有一件事是错的。级别要跟着「这是不是故障」走：
 * 提醒待办用 WARN，真正的失败才用 ERROR。
 * <p>
 * 两条断言各钉一头：恰一条 WARN 且文案含「配好前推送不可用」（说清后果），
 * 以及该文案不出现在 ERROR 级——后者防的是「WARN 加上了、ERROR 忘了摘」，
 * 那种改法在只看 WARN 的断言下照样绿。
 */
@DisplayName("空 OneBot Token 日志级别")
class OneBotEmptyTokenLoggingTest {
    /**
     * HTTP 路与 Websocket 路各走到自己的「没配」分支：两把 Token 都留空（空串与纯空白各占一边，
     * {@code isBlank} 对两者同样判空），Websocket 开着，免得在更早的「未启用」分支就返回
     */
    private OneBotSender senderWithBlankTokens() {
        OneBotSender sender = new OneBotSender();
        sender.setName("qq-onebot");
        sender.setWebsocket(true);
        sender.setOneBotHttpToken("");
        sender.setOneBotWebsocketToken(" ");
        return sender;
    }

    /**
     * 把目标类上这条日志的两头各量一遍：WARN 里该有恰一条，ERROR 里一条都不许有
     */
    private void assertSingleWarnNoError(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender,
            String what) {
        List<String> warnings = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(what))
                .toList();
        assertEquals(1, warnings.size(), "恰一条 WARN, 实际: " + warnings);
        assertTrue(warnings.get(0).contains("配好前推送不可用"), "要说清后果: " + warnings.get(0));
        assertTrue(warnings.get(0).contains("qq-onebot"), "要带上平台名: " + warnings.get(0));

        List<String> errors = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(what))
                .toList();
        assertEquals(0, errors.size(), "同一条提醒不许再按 ERROR 打: " + errors);
    }

    @Test
    @DisplayName("HTTP Token 空: 恰一条 WARN 说配好前推送不可用")
    void httpTokenBlankLogsWarnNotError() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OneBotController.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // 空 Token 在第一个分支就返回, 后面的依赖一概用不上, 但构造仍按真签名给全
            OneBotController controller = new OneBotController(
                    mock(WebServerApplicationContext.class),
                    mock(RequestMappingHandlerMapping.class),
                    new OneBotAdapterPluginProperties(),
                    mock(StarBotSenderService.class),
                    mock(OneBotHttpService.class),
                    new PushApiTokenStore());

            OneBotSender sender = senderWithBlankTokens();
            assertFalse(controller.register(sender), "缺 Token 挂不上推送接口");

            assertSingleWarnNoError(appender, "尚未配置 OneBot HTTP Token");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("Websocket Token 空: 恰一条 WARN 说配好前推送不可用")
    void websocketTokenBlankLogsWarnNotError() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OneBotWebsocketService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OneBotWebsocketService service = new OneBotWebsocketService(
                    mock(TaskScheduler.class), mock(ThreadPoolTaskExecutor.class),
                    new OneBotAdapterPluginProperties(), new OneBotConnectionState(),
                    mock(ApplicationEventPublisher.class));

            service.start(senderWithBlankTokens());

            assertSingleWarnNoError(appender, "尚未配置 OneBot Websocket Token");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
