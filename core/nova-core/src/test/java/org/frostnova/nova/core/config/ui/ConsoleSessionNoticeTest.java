package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ConsoleSessionNoticeTest {
    @Test
    void readyEventDoesNotProvideConnectionInstructions() {
        var logger = (Logger) LoggerFactory.getLogger(ConfigUiApiInterceptor.class);
        var entries = new ListAppender<ILoggingEvent>();
        entries.start();
        logger.addAppender(entries);
        var output = new ByteArrayOutputStream();
        var original = System.err;
        try (var console = new PrintStream(output, true, StandardCharsets.UTF_8);
             var context = new AnnotationConfigApplicationContext()) {
            System.setErr(console);
            context.registerBean(ConfigUiApiInterceptor.class,
                    () -> new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class)));
            context.refresh();
            context.publishEvent(mock(ApplicationReadyEvent.class));
            assertTrue(entries.list.stream().noneMatch(entry -> instructions(entry.getFormattedMessage())));
            assertFalse(instructions(output.toString(StandardCharsets.UTF_8)));
        } finally {
            System.setErr(original);
            logger.detachAppender(entries);
            entries.stop();
        }
    }

    private static boolean instructions(String text) {
        return text.contains("/config/api/admin-session-check");
    }
}
