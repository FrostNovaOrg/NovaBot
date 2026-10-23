package org.frostnova.nova.core.exec;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.live.common.LiveOnEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCommandFormsTest {
    private final NovaCoreProperties properties = new NovaCoreProperties();
    private final EventCommandRunner runner = new EventCommandRunner(properties);
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(EventCommandRunner.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void close() {
        runner.shutdown();
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * 已有写法、普通事件下的参数，以及启动时是否保持安静
     */
    static Stream<Arguments> forms() {
        return Stream.of(
                Arguments.of(null, List.of(), true),
                Arguments.of(List.of(), List.of(), true),
                Arguments.of(Arrays.asList(null, null), List.of(), true),
                Arguments.of(Arrays.asList(null, "tool", null, "{uname}", null), List.of("tool", "测试 主播"), false),
                Arguments.of(List.of("tool", "", "{uname}"), List.of("tool", "", "测试 主播"), false),
                Arguments.of(List.of("tool", "-q", "--mode", "plain", "{uname}"),
                        List.of("tool", "-q", "--mode", "plain", "测试 主播"), false),
                Arguments.of(List.of("tool", "-1", "--", "{uname}"), List.of("tool", "-1", "--", "测试 主播"), true),
                Arguments.of(List.of("tool", "--name={uname}"), List.of("tool", "--name=测试 主播"), true),
                Arguments.of(List.of("tool", "-n={uname}"), List.of("tool", "-n=测试 主播"), true),
                Arguments.of(List.of("tool", "-n{uname}"), List.of("tool", "-n测试 主播"), true),
                Arguments.of(List.of("tool", "-r{room_id}", "-u{uid}"), List.of("tool", "-r22", "-u11"), true),
                Arguments.of(List.of("tool", "name:{uname}", "./{uname}"),
                        List.of("tool", "name:测试 主播", "./测试 主播"), true),
                Arguments.of(List.of("/opt/{edition}/tool", "{custom}", "{}", "{uname}"),
                        List.of("/opt/{edition}/tool", "{custom}", "{}", "测试 主播"), false),
                Arguments.of(List.of("/opt/{edition}/tool", "{custom}", "{}", "{room_id}"),
                        List.of("/opt/{edition}/tool", "{custom}", "{}", "22"), true),
                Arguments.of(List.of("tool", "{event}", "{platform}", "{uid}", "{room_id}", "{timestamp}"),
                        List.of("tool", "LiveOnEvent", "sample", "11", "22", "123"), true),
                Arguments.of(List.of("tool", "{room_id}"), List.of("tool", "22"), true),
                Arguments.of(List.of("tool", "{room_id}-{timestamp}.flv", "{uid}{uname}"),
                        List.of("tool", "22-123.flv", "11测试 主播"), true),
                Arguments.of(List.of("tool", "{uname}/{uname}", "--tail"),
                        List.of("tool", "测试 主播/测试 主播", "--tail"), false),
                Arguments.of(List.of("sh", "/opt/scripts/record.sh", "{room_id}", "{uname}"),
                        List.of("sh", "/opt/scripts/record.sh", "22", "测试 主播"), false),
                Arguments.of(List.of("/opt/scripts/record.sh", "{room_id}", "--name={uname}"),
                        List.of("/opt/scripts/record.sh", "22", "--name=测试 主播"), true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("forms")
    void acceptsExistingForms(List<String> template, List<String> expected, boolean quiet) {
        assertEquals(expected, runner.resolve(template, event("测试 主播")));
        if (quiet) {
            assertTrue(startupNotices(template).isEmpty(), "启动时不应提示调整");
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("forms")
    void keepsQuietFormsRunningForEveryName(List<String> template, List<String> expected, boolean quiet) {
        if (!quiet || expected.isEmpty()) {
            return;
        }
        for (String name : List.of("", "-", "-sample", "--", "--sample=value", "a\nb")) {
            assertFalse(runner.resolve(template, event(name)).isEmpty(), name);
        }
    }

    @Test
    void keepsEventContentFormQuiet() {
        List<String> template = List.of("tool", "{json}", "--data={json}");
        LiveOnEvent event = event("测试 主播");
        String json = JSON.toJSONString(event);
        assertEquals(List.of("tool", json, "--data=" + json), runner.resolve(template, event));
        assertTrue(startupNotices(template).isEmpty(), "启动时不应提示调整");
    }

    static Stream<Arguments> values() {
        return Stream.of("", "测试 主播", "a'b\"c;d", "-sample", "--", "--sample=value", "{custom}", "a\nb")
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("values")
    void retainsValuesInConfiguredPositions(String value) {
        LiveOnEvent event = event(value);
        assertEquals(List.of("tool", "--", value, "--tail"),
                runner.resolve(List.of("tool", "--", "{uname}", "--tail"), event));
        assertEquals(List.of("tool", "--name=" + value, "-n=" + value, "-n" + value),
                runner.resolve(List.of("tool", "--name={uname}", "-n={uname}", "-n{uname}"), event));
    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"", "测试 主播", "a'b\"c;d", "-", "{custom}", "a\nb"})
    void acceptsOrdinaryValues(String value) {
        assertEquals(List.of("tool", value), runner.resolve(List.of("tool", "{uname}"), event(value)));
    }

    static Stream<Arguments> absentValues() {
        return Stream.of(Arguments.of(new LiveOnEvent((String) null, null)),
                Arguments.of(new LiveOnEvent((String) null, new LiveStreamerInfo(null, null, null))));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("absentValues")
    void retainsEmptyValues(LiveOnEvent event) {
        assertEquals(List.of("tool", "", "", "", ""),
                runner.resolve(List.of("tool", "{platform}", "{uid}", "{uname}", "{room_id}"), event));
        assertEquals(JSON.toJSONString(event), runner.resolve(List.of("tool", "{json}"), event).get(1));
    }

    private List<String> startupNotices(List<String> template) {
        properties.getExec().setEnabled(true);
        var rule = new NovaCoreProperties.ExecRule();
        rule.setEvent("LiveOnEvent");
        rule.setCommand(template);
        properties.getExec().getRules().add(rule);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(EventCommandRunner.class, () -> runner);
            context.refresh();
            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
        }
        return appender.list.stream().filter(entry -> entry.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static LiveOnEvent event(String name) {
        return new LiveOnEvent("sample", new LiveStreamerInfo(11L, name, 22L), Instant.ofEpochMilli(123L));
    }
}
