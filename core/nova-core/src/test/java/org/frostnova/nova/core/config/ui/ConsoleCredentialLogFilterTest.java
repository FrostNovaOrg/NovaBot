package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleCredentialLogFilterTest {
    private static final String CLIENT = "fixture.outbound.Client";

    /** A connection opened from the configuration file after a restart logs on a pool thread, outside any request. */
    @Test
    void configuredCredentialsAreMaskedOnOtherThreadsAndSettingsAreNot() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(CLIENT);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                    "novabot.adapter.onebot.senders[0].one-bot-websocket-token", "fixture-configured-socket",
                    "novabot.adapter.onebot.napcat.totp-secret", "fixture-configured-seed",
                    "spring.mail.password", "fixture-configured-mail",
                    "novabot.core.event-stream.require-token", "true",
                    "novabot.core.assistant.max-output-tokens", "100000",
                    "novabot.core.display-name", "fixture-configured-name")));
            context.register(ConsoleCredentialLogFilter.class);
            context.refresh();

            CompletableFuture.runAsync(() -> logger.trace("headers {} {} {} {} {} {}", "fixture-configured-socket",
                    "fixture-configured-seed", "fixture-configured-mail", "true", "100000",
                    "fixture-configured-name")).get();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(null);
        }
        assertEquals(List.of("headers *** *** *** true 100000 fixture-configured-name"),
                appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void disabledEventsAreLeftToTheLevelCheck() {
        var logger = (Logger) LoggerFactory.getLogger(CLIENT + ".quiet");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try (var context = new AnnotationConfigApplicationContext(ConsoleCredentialLogFilter.class)) {
            logger.debug("Bearer {}", "fixture-quiet-token");
            logger.info("Bearer {}", "fixture-loud-token");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(null);
        }
        assertEquals(List.of("Bearer ***"), appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
}
