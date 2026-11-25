package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.alert.AlertChannel;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandFollowUp;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.NovaCommand;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 核心设置经控制台保存后，运行中的程序读到的就是新值
 * <p>
 * 抓的是「页面说已生效，运行中的值却没变」：设置页按字段上的生效标记说话，
 * 而真正把值写回运行中配置对象的是保存口后面那张套用表。两边任一边漏了，下面的表就有一行红。
 */
@DisplayName("核心设置保存即生效")
class CoreSettingsSaveTest {
    private static final ConfigUiAccount ADMIN = ConfigUiAccount.local(Instant.EPOCH);
    private static final String PLATFORM = "qq-onebot";
    private static final List<Long> GROUPS = List.of(30001L, 30002L, 30003L);

    /**
     * 每个登记为当场生效的核心键：存什么、运行中该读到什么（与默认值不同，改没改看得出来）
     */
    private static final Map<String, Change> TABLE = table(
            change("novabot.core.push.enabled", "false", false),
            change("novabot.core.push.first-push-tip", "true", true),
            change("novabot.core.push.quiet-start", "23:00", "23:00"),
            change("novabot.core.push.quiet-end", "07:30", "07:30"),
            change("novabot.core.push.at-all-daily-limit", "3", 3),
            change("novabot.core.push.at-all-session-daily-limit", "0", 0),
            change("novabot.core.alert.enabled", "false", false),
            change("novabot.core.alert.convergence-interval", "120", 120),
            change("novabot.core.alert.webhook-url", "https://hooks.example.invalid/push", "https://hooks.example.invalid/push"),
            change("novabot.core.alert.webhook-method", "GET", "GET"),
            change("novabot.core.alert.webhook-title-field", "text", "text"),
            change("novabot.core.alert.webhook-content-field", "desp", "desp"),
            change("novabot.core.alert.retry-max-attempts", "2", 2),
            change("novabot.core.alert.retry-queue-size", "5", 5),
            change("novabot.core.mail.default-to", "ops@example.com", "ops@example.com"),
            change("novabot.core.command.admins", "11\n22", List.of(11L, 22L)),
            change("novabot.core.live.reconnect-interval", "90", 90),
            change("novabot.core.paint.auto-expand-height", "800", 800),
            change("novabot.core.config-ui.backup-keep", "7", 7),
            change("novabot.core.config-ui.update.enabled", "false", false),
            change("novabot.core.config-ui.update.source", "https://mirror.example.invalid/releases/latest",
                    "https://mirror.example.invalid/releases/latest"),
            change("novabot.core.log.network-log", "true", true),
            change("novabot.core.log.network-log-suppress-window", "0", 0),
            change("novabot.core.timeline.retention-days", "30", 30),
            change("novabot.core.exec.enabled", "true", true),
            change("novabot.core.exec.timeout", "5", 5),
            change("novabot.core.log.console", "WARN", ch.qos.logback.classic.Level.WARN),
            change("novabot.core.log.file", "debug", ch.qos.logback.classic.Level.DEBUG),
            // 保存的人从本机来：新名单要留着本机，否则按设计拒收
            change("novabot.core.config-ui.allow-ips", "127.0.0.1/32\n10.0.0.0/8", List.of("127.0.0.1/32", "10.0.0.0/8")),
            change("novabot.core.config-ui.auth.session-hours", "24", 24),
            change("novabot.core.config-ui.auth.idle-hours", "2", 2),
            change("novabot.core.config-ui.auth.max-failures", "3", 3),
            change("novabot.core.config-ui.auth.lockout-minutes", "5", 5),
            change("novabot.core.paint.fonts", "Noto Serif CJK SC\n内置", List.of("Noto Serif CJK SC", "内置")),
            change("novabot.core.live.auto-save-live-data-interval", "60", 60),
            change("novabot.core.alert.retry-interval", "120", 120));

    @TempDir Path directory;
    private AnnotationConfigApplicationContext context;
    private Path config;
    private MockMvc mvc;
    private ConfigUiController settings;
    private NovaCoreProperties properties;

    @BeforeEach void start() throws IOException {
        config = directory.resolve("application.yml");
        Files.writeString(config, """
                novabot:
                  core:
                    push:
                      enabled: true
                """);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(NovaCoreProperties.class, NovaCoreProperties::new);
        context.refresh();
        properties = context.getBean(NovaCoreProperties.class);
        settings = ExcludedViewersFileTest.controller(context, config);
        mvc = MockMvcBuilders.standaloneSetup(settings).build();
    }

    @AfterEach void close() {
        if (context != null) context.close();
    }

    @Test
    @DisplayName("表上的键正是设置页标着当场生效的核心键，防止新标的一项没人核对")
    void tableCoversEveryImmediateCoreKey() {
        assertEquals(new TreeSet<>(TABLE.keySet()), new TreeSet<>(immediateCoreKeys()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("逐项保存后运行中读到新值，且与重启后按文件读到的相同")
    void savedValueReachesTheRunningProgram(String key) throws Exception {
        Change change = TABLE.get(key);
        assertNotEquals(change.expected(), runtimeValue(new NovaCoreProperties(), key), "表上的新值应与默认值不同");

        JSONObject saved = save(Map.of(key, change.saved()), 200);

        assertAll(
                () -> assertTrue(saved.getBooleanValue("success"), saved::toJSONString),
                () -> assertEquals(List.of(), saved.getList("restartRequired", String.class), saved::toJSONString),
                () -> assertEquals(List.of(), saved.getList("restartPending", String.class), saved::toJSONString),
                () -> assertEquals(change.expected(), runtimeValue(properties, key), "运行中的值"),
                () -> assertEquals(runtimeValue(boundFromFile(), key), runtimeValue(properties, key),
                        "运行中的值应与重启后读到的相同"));
    }

    @Test
    @DisplayName("一批里有一项取值不对时整批拒收，防止配置文件和运行中的值只改一半")
    void invalidValueRejectsTheWholeBatch() throws Exception {
        String before = Files.readString(config);
        Map<String, String> batch = new LinkedHashMap<>();
        batch.put("novabot.core.alert.convergence-interval", "120");
        batch.put("novabot.core.alert.webhook-method", "GET");
        batch.put("novabot.core.exec.timeout", "ten");

        JSONObject rejected = save(batch, 400);

        assertAll(
                () -> assertFalse(rejected.getBooleanValue("success")),
                () -> assertTrue(rejected.getString("message").contains("novabot.core.exec.timeout"),
                        rejected::toJSONString),
                () -> assertTrue(rejected.getString("message").contains("整数"), rejected::toJSONString),
                () -> assertEquals(before, Files.readString(config), "配置文件不应改动"),
                () -> assertEquals(3600, properties.getAlert().getConvergenceInterval()),
                () -> assertEquals("POST", properties.getAlert().getWebhookMethod()));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidValues")
    @DisplayName("读不懂的取值当场拒收，旧值不变")
    void invalidValueIsRejected(String key, String value, int status) throws Exception {
        Object before = runtimeValue(properties, key);
        String file = Files.readString(config);

        JSONObject rejected = save(Map.of(key, value), status);

        assertAll(
                () -> assertFalse(rejected.getBooleanValue("success"), rejected::toJSONString),
                () -> assertTrue(rejected.getString("message").contains(key), rejected::toJSONString),
                () -> assertEquals(file, Files.readString(config)),
                () -> assertEquals(before, runtimeValue(properties, key)));
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("novabot.core.push.at-all-daily-limit", "ten", 400),
                Arguments.of("novabot.core.alert.enabled", "maybe", 400),
                Arguments.of("novabot.core.alert.webhook-method", "PUT", 400),
                Arguments.of("novabot.core.alert.webhook-title-field", " ", 400),
                // 数字名单沿用写文件那一步的逐项检查：回包是 200 加 success=false，整批未写
                Arguments.of("novabot.core.command.admins", "11\nabc", 200),
                Arguments.of("novabot.core.paint.auto-expand-height", "0", 400),
                Arguments.of("novabot.core.log.network-log-suppress-window", "1.5", 400),
                Arguments.of("novabot.core.log.console", "LOUD", 400),
                Arguments.of("novabot.core.log.file", "", 400),
                Arguments.of("novabot.core.config-ui.allow-ips", "127.0.0.1/32\n10.0.0.300", 400),
                Arguments.of("novabot.core.config-ui.allow-ips", "", 400),
                Arguments.of("novabot.core.config-ui.auth.session-hours", "0", 400),
                Arguments.of("novabot.core.config-ui.auth.idle-hours", "-1", 400),
                Arguments.of("novabot.core.config-ui.auth.max-failures", "abc", 400),
                Arguments.of("novabot.core.config-ui.auth.lockout-minutes", "", 400),
                Arguments.of("novabot.core.live.auto-save-live-data-interval", "0", 400),
                Arguments.of("novabot.core.alert.retry-interval", "-5", 400),
                Arguments.of("novabot.core.config-ui.update.source", "ftp://mirror.example.invalid/latest", 400));
    }

    @Test
    @DisplayName("Webhook 三项并发改读，防止一条告警拿到新的请求方式配旧的字段名")
    void webhookFormatIsNeverHalfUpdated() throws Exception {
        RuntimeConfigurationApplier applier = RuntimeConfigurationApplier.bench(properties).build();
        Map<String, String> first = Map.of("novabot.core.alert.webhook-method", "GET",
                "novabot.core.alert.webhook-title-field", "a", "novabot.core.alert.webhook-content-field", "a");
        Map<String, String> second = Map.of("novabot.core.alert.webhook-method", "POST",
                "novabot.core.alert.webhook-title-field", "b", "novabot.core.alert.webhook-content-field", "b");
        applier.applyAndTrack(first);

        List<String> torn = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 5_000; i++) applier.applyAndTrack(i % 2 == 0 ? second : first);
        });
        writer.start();
        while (writer.isAlive()) {
            var format = properties.getAlert().webhookFormat();
            boolean consistent = format.titleField().equals(format.contentField())
                    && format.get() == "a".equals(format.titleField());
            if (!consistent) torn.add(format.toString());
        }
        writer.join(30_000);
        assertEquals(List.of(), torn.stream().limit(3).toList());
    }

    // ============ 走真实读处 ============

    @Test
    @DisplayName("保存「启用告警」后不重启，下一条告警就按新开关处理")
    void alertSwitchTakesEffectOnTheNextAlert() throws Exception {
        RecordingChannel channel = new RecordingChannel();
        @SuppressWarnings("unchecked")
        ObjectProvider<AlertChannel> channels = mock(ObjectProvider.class);
        when(channels.orderedStream()).thenAnswer(invocation -> Stream.of(channel));
        AlertService alerts = new AlertService(properties, channels, TimelineWriter.NONE);

        alerts.alert("first", "第一条", "开着");
        assertEquals(List.of("第一条"), channel.subjects);

        save(Map.of("novabot.core.alert.enabled", "false"), 200);
        alerts.alert("second", "第二条", "关了");
        assertEquals(List.of("第一条"), channel.subjects, "关掉之后的告警不应发出");

        save(Map.of("novabot.core.alert.enabled", "true"), 200);
        alerts.alert("third", "第三条", "又开了");
        assertEquals(List.of("第一条", "第三条"), channel.subjects, "重新打开后的告警应发出");
    }

    @Test
    @DisplayName("保存「超级管理员」后不重启，下一条命令就按新名单判权限")
    void adminListTakesEffectOnTheNextCommand() throws Exception {
        AdminOnlyCommand command = new AdminOnlyCommand();
        CommandDispatcher dispatcher = dispatcher(command);

        dispatcher.onRemoteMessage(member(GROUPS.get(0)));
        assertEquals(0, command.executions, "不在名单里的普通成员不应执行");

        save(Map.of("novabot.core.command.admins", "1\n2"), 200);
        dispatcher.onRemoteMessage(member(GROUPS.get(1)));
        assertEquals(1, command.executions, "加进名单后的下一条命令应执行");

        save(Map.of("novabot.core.command.admins", "2"), 200);
        dispatcher.onRemoteMessage(member(GROUPS.get(2)));
        assertEquals(1, command.executions, "移出名单后的下一条命令不应执行");
    }

    // ============ 工具 ============

    private Set<String> immediateCoreKeys() {
        JSONObject schema = settings.schema();
        List<JSONObject> fields = new ArrayList<>();
        schema.getList("groups", JSONObject.class).forEach(group -> fields.addAll(group.getList("fields", JSONObject.class)));
        fields.addAll(schema.getList("ungrouped", JSONObject.class));
        return fields.stream()
                .filter(field -> "IMMEDIATE".equals(field.getString("effect")))
                .map(field -> field.getString("name"))
                .filter(name -> name.startsWith("novabot.core."))
                // 口令与二次验证走登录与安全专用口，通用保存不收
                .filter(name -> !ConfigUiAuthService.isDedicatedAuthKey(name))
                .collect(Collectors.toSet());
    }

    private NovaCoreProperties boundFromFile() throws IOException {
        var source = new MapConfigurationPropertySource(new ConfigurationFileService(config).readAsLoaded());
        return new Binder(source).bind("novabot.core", NovaCoreProperties.class).orElseGet(NovaCoreProperties::new);
    }

    /**
     * 照配置键在配置对象上取值：去掉前缀，各段由连字符写法换成驼峰
     */
    private static Object runtimeValue(NovaCoreProperties target, String key) {
        String path = Stream.of(key.substring("novabot.core.".length()).split("\\."))
                .map(CoreSettingsSaveTest::camel).collect(Collectors.joining("."));
        return new BeanWrapperImpl(target).getPropertyValue(path);
    }

    private static String camel(String segment) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char c : segment.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return result.toString();
    }

    private JSONObject save(Map<String, String> changes, int status) throws Exception {
        var response = mvc.perform(post("/config/api/values").requestAttr(ConfigUiAccess.ACCOUNT, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(new JSONObject(changes).toJSONString()))
                .andReturn().getResponse();
        JSONObject body = JSON.parseObject(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(status, response.getStatus(), body::toJSONString);
        return body;
    }

    private CommandDispatcher dispatcher(NovaCommand command) {
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(streamerPushingToGroups()));
        @SuppressWarnings("unchecked")
        ObjectProvider<NovaCommand> commands = mock(ObjectProvider.class);
        when(commands.iterator()).thenAnswer(invocation -> List.of(command).iterator());
        when(commands.orderedStream()).thenAnswer(invocation -> Stream.of(command));
        @SuppressWarnings("unchecked")
        ObjectProvider<CommandFollowUp> followUps = mock(ObjectProvider.class);
        when(followUps.iterator()).thenAnswer(invocation -> List.<CommandFollowUp>of().iterator());
        return new CommandDispatcher(commands, followUps,
                new CommandSettingsService(new NovaStateStore(new NovaCoreProperties())), dataSource,
                mock(NovaMessageSender.class), properties, TimelineWriter.NONE);
    }

    /**
     * 账号 1 在群里只是普通成员：能不能执行管理命令，只看超级管理员名单
     */
    private static NovaRemoteMessageEvent member(Long group) {
        return new NovaRemoteMessageEvent(PLATFORM, "group", group, 1L, "管理命令", "member", true);
    }

    private static PushUser streamerPushingToGroups() {
        List<PushTarget> targets = new ArrayList<>();
        for (Long group : GROUPS) {
            PushTarget target = new PushTarget();
            target.setPlatform(PLATFORM);
            target.setType(PushTargetType.GROUP);
            target.setNum(group);
            target.setMessages(new ArrayList<>());
            targets.add(target);
        }
        PushUser user = new PushUser();
        user.setUid(10001L);
        user.setUname("主播甲");
        user.setPlatform("bilibili");
        user.setTargets(targets);
        return user;
    }

    static Stream<Arguments> rows() {
        return TABLE.keySet().stream().map(Arguments::of);
    }

    private record Change(String key, String saved, Object expected) {
    }

    private static Change change(String key, String saved, Object expected) {
        return new Change(key, saved, expected);
    }

    private static Map<String, Change> table(Change... changes) {
        Map<String, Change> table = new LinkedHashMap<>();
        for (Change change : changes) table.put(change.key(), change);
        return table;
    }

    private static final class RecordingChannel implements AlertChannel {
        final List<String> subjects = new ArrayList<>();

        @Override public String id() { return "recording"; }
        @Override public String name() { return "记录"; }
        @Override public boolean isAvailable() { return true; }
        @Override public void send(String subject, String content) { subjects.add(subject); }
    }

    private static final class AdminOnlyCommand implements NovaCommand {
        int executions;

        @Override public boolean requiresAdmin() { return true; }
        @Override public String name() { return "管理命令"; }
        @Override public List<String> aliases() { return List.of(); }
        @Override public String description() { return "只给管理员"; }
        @Override public CommandReply execute(CommandContext context) {
            executions++;
            return CommandReply.of("已执行");
        }
    }
}
