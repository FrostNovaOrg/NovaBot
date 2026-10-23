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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCommandContractTest {
    @TempDir
    Path dir;

    private NovaCoreProperties properties;
    private EventCommandRunner runner;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        runner = new EventCommandRunner(properties);
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

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"{event}", "{platform}", "{uid}", "{uname}", "{room_id}", "{timestamp}", "{json}"})
    void checksProgramTemplate(String field) {
        for (String program : List.of(field, "/opt/" + field + "/tool")) {
            assertTrue(runner.resolve(Arrays.asList(null, program, "--", "value"), event("sample")).isEmpty());
        }
    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"-sample", "--sample=value", "--"})
    void checksArgumentValues(String value) {
        for (String argument : List.of("{uname}", "-{uname}", "--{uname}=value")) {
            assertTrue(runner.resolve(List.of("tool", "--mode", "plain", argument, "--"), event(value)).isEmpty());
        }
    }

    @Test
    void checksFieldValues() {
        LiveOnEvent event = new LiveOnEvent("-sample", new LiveStreamerInfo(-11L, "sample", -22L), Instant.ofEpochMilli(-123L));
        for (String field : List.of("{platform}", "{uid}", "{room_id}", "{timestamp}")) {
            assertTrue(runner.resolve(List.of("tool", field), event).isEmpty());
        }
    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"--{platform}={uname}", "--{timestamp}={event}", "--{room_id}={platform}"})
    void checksCombinedValues(String argument) {
        assertTrue(runner.resolve(List.of("tool", argument), event("sample")).isEmpty());
    }

    @Test
    void checksArgumentsBeforeSubmitting() {
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(List.of("tool", "{uname}")));
        runner.onEvent(event("-sample"));
        ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(runner, "executor");
        assertEquals(0, executor.getTaskCount());
        assertTrue(warnings().stream().anyMatch(line -> line.contains("command")));
    }

    @Test
    void acceptsFollowingRules() throws Exception {
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(List.of("tool", "{uname}")));
        Path output = dir.resolve("result.json");
        properties.getExec().getRules().add(rule(command(output, "--", "{uname}")));
        runner.onEvent(event("-sample"));
        assertEquals(List.of("--", "-sample"), JSON.parseObject(awaitOutput(output)).getList("args", String.class));
    }

    @Test
    void reportsConfigurationAtStartup() {
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(Arrays.asList(null, "{uname}", "value")));
        properties.getExec().getRules().add(rule(List.of("tool", "{uname}")));
        publishReady();
        assertEquals(2, warnings().size());
        assertTrue(warnings().get(0).contains("第一项"));
        assertTrue(!warnings().get(1).contains("第一项"));
        for (String line : warnings()) {
            assertTrue(line.contains("novabot.core.exec.rules["));
            assertTrue(line.contains("command"));
            assertTrue(line.contains("附录 B"));
        }
    }

    @Test
    void listsRulesToAdjustAfterStartup() {
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(List.of("tool", "--", "{uname}")));
        properties.getExec().getRules().add(rule(Arrays.asList(null, "{uname}", "value")));
        properties.getExec().getRules().add(rule(List.of("tool", "{uname}")));
        NovaCoreProperties.ExecRule disabled = rule(List.of("tool", "{uname}"));
        disabled.setEnabled(false);
        properties.getExec().getRules().add(disabled);
        properties.getExec().getRules().add(rule(List.of("tool", "--name={uname}")));
        assertEquals(List.of(), runner.rulesToAdjust());
        publishReady();
        assertEquals(List.of(new EventCommandRunner.RuleToAdjust(1, "LiveOnEvent"),
                new EventCommandRunner.RuleToAdjust(2, "LiveOnEvent")), runner.rulesToAdjust());
    }

    @Test
    void listsNothingForCurrentConfiguration() {
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(List.of("tool", "-q", "--", "{uname}")));
        properties.getExec().getRules().add(rule(List.of("tool", "--name={uname}")));
        properties.getExec().getRules().add(rule(null));
        publishReady();
        assertEquals(List.of(), runner.rulesToAdjust());
    }

    @Test
    void listsNothingWhileDisabled() {
        properties.getExec().getRules().add(rule(List.of("{uname}")));
        publishReady();
        assertEquals(List.of(), runner.rulesToAdjust());
    }

    @Test
    void logsSkippedRuleOncePerRun() {
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(List.of("{uname}")));
        properties.getExec().getRules().add(rule(List.of("tool", "{uname}")));
        for (int i = 0; i < 3; i++) {
            runner.onEvent(event("-sample"));
        }
        List<String> skipped = warnings();
        assertEquals(2, skipped.size());
        assertTrue(skipped.get(0).contains("novabot.core.exec.rules[0]"));
        assertTrue(skipped.get(1).contains("novabot.core.exec.rules[1]"));
        for (String line : skipped) {
            assertTrue(line.contains("跳过"));
            assertTrue(line.contains("附录 B"));
        }
        ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(runner, "executor");
        assertEquals(0, executor.getTaskCount());
    }

    static Stream<List<String>> formsToAdjust() {
        return Stream.of(
                List.of("tool", "{uname}"),
                List.of("tool", "{room_id}", "{uname}"),
                List.of("tool", "--name", "{uname}"),
                List.of("sh", "/opt/scripts/record.sh", "{room_id}", "{uname}"),
                List.of("tool", "{uname}.log"),
                List.of("tool", "--name{uid}"),
                List.of("tool", "-{uid}"),
                List.of("tool", "--{event}={uname}"),
                List.of("tool", "--={uname}"));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("formsToAdjust")
    void reportsFormsToAdjust(List<String> template) {
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(template));
        publishReady();
        assertEquals(1, warnings().size());
        assertTrue(warnings().get(0).contains("novabot.core.exec.rules[0].command"));
        assertTrue(runner.resolve(template, event("-sample")).isEmpty());
    }

    @Test
    void usesOneNoticeForArgumentForms() {
        properties.getExec().setEnabled(true);
        formsToAdjust().forEach(form -> properties.getExec().getRules().add(rule(form)));
        publishReady();
        List<String> notices = warnings().stream()
                .map(line -> line.replaceFirst("rules\\[\\d+]", "rules[]")).distinct().toList();
        assertEquals(1, notices.size());
        assertEquals(formsToAdjust().count(), warnings().size());
    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"--name={uname}", "-n={uname}", "-n{uname}", "-nq{uname}"})
    void acceptsFixedOptionNames(String argument) {
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(List.of("tool", argument)));
        publishReady();
        assertTrue(warnings().isEmpty());
        for (String value : List.of("-sample", "--sample=value", "--", "sample")) {
            String expected = argument.replace("{uname}", value);
            assertEquals(List.of("tool", expected), runner.resolve(List.of("tool", argument), event(value)));
        }
    }

    @Test
    void leavesInactiveConfigurationQuiet() {
        properties.getExec().getRules().add(rule(List.of("{uname}")));
        publishReady();
        assertTrue(warnings().isEmpty());
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().get(0).setEnabled(false);
        publishReady();
        assertTrue(warnings().isEmpty());
    }

    @Test
    void leavesCurrentConfigurationQuiet() {
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(List.of("tool", "-q", "--", "{uname}")));
        properties.getExec().getRules().add(rule(List.of("tool", "--name={uname}")));
        properties.getExec().getRules().add(rule(List.of("tool", "./{uname}")));
        publishReady();
        assertTrue(warnings().isEmpty());
    }

    @Test
    void leavesEmptyConfigurationQuiet() {
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(null));
        properties.getExec().getRules().add(rule(List.of()));
        properties.getExec().getRules().add(rule(Arrays.asList(null, null)));
        publishReady();
        assertTrue(warnings().isEmpty());
    }

    @Test
    void suppliesConfiguredEnvironment() throws Exception {
        Path output = dir.resolve("result.json");
        NovaCoreProperties.ExecRule rule = rule(command(output, "tail"));
        new Binder(new MapConfigurationPropertySource(Map.of("rule.event-environment", "true")))
                .bind("rule", Bindable.ofInstance(rule));
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule);
        LiveOnEvent event = event("测试 主播\u0000");
        runner.onEvent(event);
        var result = JSON.parseObject(awaitOutput(output));
        assertEquals(List.of("tail"), result.getList("args", String.class));
        assertEquals(JSON.toJSONString(event), result.getString("event"));
    }

    @Test
    void boundsConfiguredEnvironment() throws Exception {
        Path output = dir.resolve("result.json");
        NovaCoreProperties.ExecRule rule = rule(command(output));
        new Binder(new MapConfigurationPropertySource(Map.of("rule.event-environment", "true")))
                .bind("rule", Bindable.ofInstance(rule));
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule);
        runner.onEvent(event("a".repeat(9000)));
        var result = JSON.parseObject(awaitOutput(output));
        assertTrue(result.getBooleanValue("present"));
        assertEquals("", result.getString("event"));
    }

    @Test
    void retainsDefaultEnvironment() throws Exception {
        Path output = dir.resolve("result.json");
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule(command(output, "{uname}")));
        runner.onEvent(event("测试 主播"));
        var result = JSON.parseObject(awaitOutput(output));
        assertEquals(List.of("测试 主播"), result.getList("args", String.class));
        assertEquals(System.getenv().containsKey("NOVABOT_EVENT_JSON"), result.getBooleanValue("present"));
        assertEquals(System.getenv().getOrDefault("NOVABOT_EVENT_JSON", ""), result.getString("event"));
    }

    @Test
    void leavesOptionalContentUnusedForFixedCommand() {
        ContentEvent event = new ContentEvent();
        assertEquals(List.of("tool", "literal"), runner.resolve(List.of("tool", "literal"), event));
        assertEquals(0, event.reads.get());
    }

    @Test
    void leavesOptionalContentUnusedForFieldValues() {
        ContentEvent event = new ContentEvent();
        assertEquals(List.of("tool", "sample"), runner.resolve(List.of("tool", "{uname}"), event));
        assertEquals(0, event.reads.get());
    }

    @Test
    void readsConfiguredContent() {
        ContentEvent event = new ContentEvent();
        var result = runner.resolve(List.of("tool", "{json}"), event);
        assertEquals("sample", JSON.parseObject(result.get(1)).getString("detail"));
        assertTrue(event.reads.get() > 0);
    }

    private List<String> warnings() {
        return appender.list.stream().filter(entry -> entry.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void publishReady() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(EventCommandRunner.class, () -> runner);
            context.refresh();
            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
        }
    }

    private static NovaCoreProperties.ExecRule rule(List<String> command) {
        var rule = new NovaCoreProperties.ExecRule();
        rule.setEvent("LiveOnEvent");
        rule.setCommand(command);
        return rule;
    }

    private List<String> command(Path output, String... args) {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Writer.class.getName(), output.toString()));
        command.addAll(List.of(args));
        return command;
    }

    private String awaitOutput(Path output) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(output) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(output));
        return Files.readString(output);
    }

    private static LiveOnEvent event(String name) {
        return new LiveOnEvent("sample", new LiveStreamerInfo(11L, name, 22L));
    }

    public static class Writer {
        public static void main(String[] args) throws Exception {
            Path output = Path.of(args[0]);
            Path pending = output.resolveSibling("pending.json");
            Files.writeString(pending, JSON.toJSONString(Map.of(
                    "args", Arrays.asList(args).subList(1, args.length),
                    "event", System.getenv().getOrDefault("NOVABOT_EVENT_JSON", ""),
                    "present", System.getenv().containsKey("NOVABOT_EVENT_JSON"))));
            Files.move(pending, output);
        }
    }

    public static class ContentEvent extends LiveOnEvent {
        private final AtomicInteger reads = new AtomicInteger();

        public ContentEvent() {
            super("sample", new LiveStreamerInfo(11L, "sample", 22L));
        }

        public String getDetail() {
            reads.incrementAndGet();
            return "sample";
        }
    }
}
