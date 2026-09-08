package com.starlwr.bot.adapter.onebot.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 告警绑器旧键提醒只列告警搬位对，不把代登录键写进去
 */
@DisplayName("告警 warn 只列告警三对")
class OneBotAlertRelocatedWarnCopyTest {
    @Test
    @DisplayName("含旧告警键时提醒含 qq-platform 且不含 config-ui.napcat")
    void warnListsAlertPairsOnly() {
        List<String> red = new ArrayList<>();
        Logger logger = (Logger) LoggerFactory.getLogger(OneBotAlertPropertiesBinder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty("starbot.core.alert.qq-platform", "qq-onebot");
            environment.setProperty("starbot.core.alert.qq-type", "1");
            environment.setProperty("starbot.core.alert.qq-num", "12345");
            OneBotAlertPropertiesBinder.apply(environment, properties.getAlert());
            String joined = String.join(" | ", appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList());

            try {
                assertTrue(joined.contains("qq-platform"),
                        "提醒应含 qq-platform, 实际=" + joined);
            } catch (AssertionError e) {
                red.add("① " + e.getMessage());
            }

            try {
                assertFalse(joined.contains("config-ui.napcat"),
                        "提醒不应含 config-ui.napcat, 实际=" + joined);
            } catch (AssertionError e) {
                red.add("② " + e.getMessage());
            }
        } finally {
            logger.detachAppender(appender);
        }

        if (!red.isEmpty()) {
            fail("告警 warn 只列告警三对两问中 " + red.size() + " 问未销: " + String.join("; ", red));
        }
    }
}
