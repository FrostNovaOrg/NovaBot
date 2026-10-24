package org.frostnova.nova.core.assistant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 同时捕获框架和项目日志；写入时保存消息和异常，抓住日志参数随后被修改而掩盖的密钥泄露。 */
final class AssistantLogCapture implements AutoCloseable {
    private final Map<Logger, Level> levels = new LinkedHashMap<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final AppenderBase<ILoggingEvent> logs = new AppenderBase<>() {
        @Override protected void append(ILoggingEvent event) {
            messages.add(event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
                    : ThrowableProxyUtil.asString(event.getThrowableProxy())));
        }
    };
    private static final String MARKER = "assistant diagnostic capture active";

    AssistantLogCapture() {
        logs.start();
        for (String name : new String[]{Logger.ROOT_LOGGER_NAME, "org.frostnova.nova"}) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            levels.put(logger, logger.getLevel());
            logger.addAppender(logs);
            logger.setLevel(Level.TRACE);
        }
        LoggerFactory.getLogger("org.frostnova.nova").trace(MARKER);
    }

    void assertNoSecret(String secret) {
        assertTrue(messages.stream().anyMatch(text -> text.contains(MARKER)));
        for (String text : messages) {
            assertFalse(text.contains(secret));
            assertFalse(text.toLowerCase(Locale.ROOT).contains("authorization"));
        }
    }

    @Override public void close() {
        levels.forEach((logger, level) -> {
            logger.setLevel(level);
            logger.detachAppender(logs);
        });
        logs.stop();
    }
}
