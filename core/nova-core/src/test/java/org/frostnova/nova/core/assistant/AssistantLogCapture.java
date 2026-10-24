package org.frostnova.nova.core.assistant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 同时捕获框架和项目日志，覆盖不向根日志传递的项目日志配置。 */
final class AssistantLogCapture implements AutoCloseable {
    private final Map<Logger, Level> levels = new LinkedHashMap<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
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
        assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains(MARKER)));
        for (ILoggingEvent event : logs.list) {
            String text = event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
                    : ThrowableProxyUtil.asString(event.getThrowableProxy()));
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
