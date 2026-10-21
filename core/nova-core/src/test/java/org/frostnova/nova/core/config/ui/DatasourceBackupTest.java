package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制台保存推送配置时的带时间戳备份
 */
@DisplayName("控制台推送配置的备份")
class DatasourceBackupTest {

    private static final Instant START = Instant.parse("2026-09-05T10:00:00Z");

    @TempDir
    Path dir;

    private Path datasource;

    private NovaCoreProperties properties;

    private ConfigUiController controller;

    private TimelineStore timeline;

    private ConfigurationValidator validator;

    private org.frostnova.nova.core.service.NovaEventHandlerService handlerService;

    private final AtomicInteger ticks = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        datasource = dir.resolve("datasource.json");
        Files.writeString(datasource, "[]", StandardCharsets.UTF_8);

        properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(datasource.toString());

        validator = mock(ConfigurationValidator.class);
        when(validator.validateDatasource(anyString(), any())).thenReturn(List.of());

        NovaSenderService senderService = mock(NovaSenderService.class);
        when(senderService.getSenderNames()).thenReturn(Set.of());

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ObjectProvider<HealthProbe> healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenReturn(Stream.empty());

        timeline = mock(TimelineStore.class);
        handlerService = mock(org.frostnova.nova.core.service.NovaEventHandlerService.class);

        Clock clock = mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenAnswer(invocation -> START.plusSeconds(ticks.get()));

        controller = new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                dataSource,
                healthProbes,
                validator,
                senderService,
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                handlerService,
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.protocol.EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                timeline,
                mock(ConfigUiAuthService.class),
                new PushTemplateDefaults(properties),
                mock(UpdateCheckService.class),
                mock(AlertService.class),
                clock);
    }

    // 抓的问题：合法的三份名单不能保存，或保存甲时改了乙。
    @Test
    void validStreamerListsSaveThroughUserEndpoint() throws IOException {
        seedTwoUsers();
        when(validator.validateDatasource(anyString(), any())).thenAnswer(call ->
                new ConfigurationValidator(handlerService).validateDatasource(call.getArgument(0), Set.of()));
        JSONObject current = controller.datasourceUser("bilibili", "1");
        JSONObject other = controller.datasourceUser("bilibili", "2").getJSONObject("user");
        JSONObject user = current.getJSONObject("user");
        user.put("dynamicBlockWords", java.util.Collections.nCopies(500, "词"));
        user.put("wordCloudBlockWords", List.of("😀".repeat(50)));
        user.put("wordCloudExcludeUids", List.of(" 123 ", "9".repeat(50)));
        var response = saveUser("1", current.getString("version"), user);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().getBooleanValue("success"), response.getBody().toString());
        JSONObject saved = controller.datasourceUser("bilibili", "1").getJSONObject("user");
        for (String field : List.of("dynamicBlockWords", "wordCloudBlockWords", "wordCloudExcludeUids")) {
            assertEquals(user.getJSONArray(field), saved.getJSONArray(field));
        }
        assertEquals(other, controller.datasourceUser("bilibili", "2").getJSONObject("user"));
    }

    // 抓的问题：非法名单显示保存成功，或拒绝后仍改动文件。
    @Test
    void invalidStreamerListsReturn400WithoutWriting() throws IOException {
        seedTwoUsers();
        when(validator.validateDatasource(anyString(), any())).thenAnswer(call ->
                new ConfigurationValidator(handlerService).validateDatasource(call.getArgument(0), Set.of()));
        byte[] before = Files.readAllBytes(datasource);
        JSONObject current = controller.datasourceUser("bilibili", "1");
        String version = current.getString("version");
        for (String field : List.of("dynamicBlockWords", "wordCloudBlockWords", "wordCloudExcludeUids")) {
            List<List<String>> invalid = new java.util.ArrayList<>(List.of(
                    java.util.Collections.nCopies(501, "1"), List.of("a".repeat(51)), List.of("  ")));
            if (field.equals("wordCloudExcludeUids")) invalid.addAll(List.of(List.of("abc"), List.of("0"), List.of("-1")));
            for (List<String> values : invalid) {
                JSONObject user = JSONObject.parseObject(current.getJSONObject("user").toJSONString());
                user.put(field, values);
                ResponseEntity<JSONObject> response = saveUser("1", version, user);
                assertEquals(400, response.getStatusCode().value(), field + values.size());
                String issues = response.getBody().getJSONArray("issues").toString();
                assertTrue(issues.contains("uid 1") && issues.contains(field) && issues.contains("第 "), issues);
                assertArrayEquals(before, Files.readAllBytes(datasource));
            }
        }
    }

    @Test
    @DisplayName("保存两次应留下两份带时间戳的备份")
    void savingTwiceLeavesTwoStampedBackups() throws IOException {
        assertTrue(save(users(1)).getBody().getBooleanValue("success"));
        tick();
        assertTrue(save(users(2)).getBody().getBooleanValue("success"));

        List<String> names = stampedBackupNames();
        assertEquals(2, names.size(), "两次保存应留下两份带时间戳的备份，而不是覆盖同一份");
        assertTrue(names.get(0).startsWith("datasource.json."));
        assertTrue(names.get(0).endsWith(".bak"));
        assertFalse(Files.exists(dir.resolve("datasource.json.bak")),
                "不应再写旧的单份 datasource.json.bak");
    }

    @Test
    @DisplayName("改保留份数为 3 后，第 4 次写入裁到 3 份")
    void keepThreePrunesToThreeOnTheFourthSave() throws IOException {
        properties.getConfigUi().setBackupKeep(3);

        for (int i = 0; i < 4; i++) {
            assertTrue(save(users(i + 1)).getBody().getBooleanValue("success"), "第 " + (i + 1) + " 次保存应成功");
            tick();
        }

        assertEquals(3, stampedBackupNames().size(), "保留 3 份时第 4 次写入应裁到 3");
    }

    @Test
    @DisplayName("盘上已有的旧单份 .bak 不删也不再写")
    void leavesLegacySingleBakUntouched() throws IOException {
        Path legacy = dir.resolve("datasource.json.bak");
        Files.writeString(legacy, "legacy-copy", StandardCharsets.UTF_8);

        assertTrue(save(users(1)).getBody().getBooleanValue("success"));

        assertTrue(Files.exists(legacy), "旧的单份 datasource.json.bak 不应被删");
        assertEquals("legacy-copy", Files.readString(legacy, StandardCharsets.UTF_8),
                "旧的单份 datasource.json.bak 不应被覆盖");
        assertEquals(1, stampedBackupNames().size(), "这一趟应另写一份带时间戳的备份");
    }

    @Test
    @DisplayName("写盘半途失败时推送配置原样、界面回报失败")
    void failedSaveLeavesDatasourceIntact() throws IOException {
        Files.createDirectory(dir.resolve("datasource.json.tmp"));

        ResponseEntity<JSONObject> response = save(users(1));

        assertFalse(response.getBody().getBooleanValue("success"), "写失败时不许回报成功");
        assertEquals("[]", Files.readString(datasource, StandardCharsets.UTF_8),
                "写到一半失败时盘上的推送配置被改掉了");
    }

    /**
     * 推送配置不含秘密，新建时跟系统默认权限走：收得比直接写还紧的话，
     * 同机别的账号或按别的用户跑的脚本（如数据备份）就读不了它
     */
    @Test
    @DisplayName("新建的推送配置文件跟着系统默认权限走")
    void newDatasourceFileFollowsSystemDefault() throws IOException {
        Files.delete(datasource);
        Path probe = dir.resolve("probe.txt");
        Files.writeString(probe, "p\n", StandardCharsets.UTF_8);

        assertTrue(save(users(1)).getBody().getBooleanValue("success"));

        assertEquals(Files.getPosixFilePermissions(probe), Files.getPosixFilePermissions(datasource),
                "不含秘密的件新建时该跟直接写一样宽");
    }

    /**
     * 旧备份被裁掉时应往日志页记一条
     * <p>
     * 配置文件那一路（{@code ConfigurationFileService}）已经在记，推送配置这一路当时没接：
     * 同一件事在日志页上时有时无，比两边都不记更难查——使用者会以为「这次没删」。
     * <p>
     * 阴性对照是<b>没裁掉任何东西的那几次保存</b>：每次保存都记一条「清理了 0 份」的话，
     * 真正删掉东西的那几条会淹在里面，而这一格照样绿。
     */
    @Test
    @DisplayName("裁掉旧备份时记一条「清理旧备份」，没裁到东西的那几次一条不记")
    void prunedBackupsLandOnTheLogPage() throws IOException {
        properties.getConfigUi().setBackupKeep(2);

        // 前两次留在保留份数内，一份也裁不掉
        assertTrue(save(users(1)).getBody().getBooleanValue("success"));
        tick();
        assertTrue(save(users(2)).getBody().getBooleanValue("success"));
        tick();
        verify(timeline, never()).record(any());

        assertTrue(save(users(3)).getBody().getBooleanValue("success"));

        ArgumentCaptor<TimelineEvent> captor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timeline).record(captor.capture());
        TimelineEvent event = captor.getValue();
        assertEquals(TimelineEventType.BACKUP_PRUNED, event.type());
        assertEquals("2", event.detail().get("keep"));
        assertEquals(1, event.detail().get("pruned").split(",").length, event.detail().get("pruned"));
        assertEquals(2, stampedBackupNames().size(), "记下来的那一条得与盘上真剩几份对得上");
    }

    /**
     * 使用者故障：目录不让建新文件、推送配置文件本身可写（只读的容器根上单独挂一个
     * 可写的配置文件就是这种部署）时，此前每次保存都在「同目录建带时间戳的备份」这一步
     * 先被拒，页面只回「保存失败」，配置改不了。改后该照常存上，目录里不添备份件与临时件，
     * 日志页记一条「没留备份」。
     */
    @Test
    @DisplayName("目录不让建文件、文件可写：保存照样成，目录里不添备份与临时件，日志页记「没留备份」")
    void savingSucceedsWithoutBackupWhenDirectoryRefusesNewFiles() throws IOException {
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(dir);
        ResponseEntity<JSONObject> response;
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(dir),
                    "当前用户无视目录权限（如 root），不让建新文件的目录设不出来");
            response = save(users(1));
        } finally {
            Files.setPosixFilePermissions(dir, original);
        }

        assertTrue(response.getBody().getBooleanValue("success"),
                "目录不让建新文件不该挡住保存: " + response.getBody().getString("message"));
        assertEquals(users(1), Files.readString(datasource, StandardCharsets.UTF_8),
                "存上的该正是提交的那份内容");
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("datasource.json"),
                    files.map(path -> path.getFileName().toString()).sorted().toList(),
                    "目录里只该有推送配置本身：跳过的备份建不出、退回直接写也建不出临时件");
        }

        ArgumentCaptor<TimelineEvent> captor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timeline).record(captor.capture());
        TimelineEvent event = captor.getValue();
        // 整句钉死：只说这次没留备份和为什么，不说保存成没成——那由回话说；
        // detail 点出建不出的是哪一份备份件，不带 Java 类名
        assertEquals("推送配置没留备份：所在目录建不出新文件（没有权限或文件系统只读）", event.text());
        assertEquals("建不出备份文件 " + datasource.resolveSibling("datasource.json."
                        + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(START) + ".bak"),
                event.detail().get("reason"), "detail 该说人话并点出是哪一份: " + event.detail());
        assertEquals(TimelineEvent.Level.WARN, event.level());
        assertEquals("没留备份", event.type().getDescription());
    }

    // 手改文件的人只改一个字段时，不应看到整份文件的格式和 null 一起变化。
    @Test
    void savingOneFieldKeepsLegacyFormattingAndNulls() throws Exception {
        String original = """
                [
                  {
                    "uid": 1,
                    "platform": "bilibili",
                    "enabled": true,
                    "targets": [],
                    "extra": null
                  },
                  {
                    "platform": "bilibili",
                    "uid": 2,
                    "targets": [],
                    "extension": {
                      "text": "保留文本与 \\"引号\\"",
                      "values": [
                        null,
                        {},
                        []
                      ]
                    }
                  }
                ]""";
        Files.writeString(datasource, original);
        Path before = dir.resolve("before.json");
        Files.writeString(before, original);
        JSONObject read = controller.datasourceUser("bilibili", "1");
        read.getJSONObject("user").put("enabled", false);

        assertTrue(saveUser("1", read.getString("version"), read.getJSONObject("user"))
                .getBody().getBooleanValue("success"));
        Process diff = new ProcessBuilder("git", "diff", "--no-index", "--", before.toString(), datasource.toString())
                .redirectErrorStream(true).start();
        System.out.print(new String(diff.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(diff.waitFor(10, TimeUnit.SECONDS));
        assertEquals(1, diff.exitValue());
        assertEquals(original.replace("\"enabled\": true", "\"enabled\": false"), Files.readString(datasource));
    }

    // 重复添加应明确说已经添加，避免用户反复重新载入。
    @Test
    void duplicateAdditionExplainsThatStreamerAlreadyExists() throws IOException {
        seedTwoUsers();
        JSONObject user = controller.datasourceUser("bilibili", "1").getJSONObject("user");
        byte[] before = Files.readAllBytes(datasource);
        ResponseEntity<JSONObject> response = controller.addDatasourceUser(new JSONObject().fluentPut("user", user));
        assertEquals(409, response.getStatusCode().value());
        assertEquals("这位主播已经添加过了", response.getBody().getString("message"));
        assertArrayEquals(before, Files.readAllBytes(datasource));
        assertEquals(0, stampedBackupNames().size());
    }

    // 新装或手动清空文件后，推送页应显示空列表，向导应能添加第一位主播。
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " \n\t\r ", "\uFEFF", "\uFEFF \n\t\r "})
    void emptyFileListsNoStreamersAndAcceptsFirstAddition(String content) throws IOException {
        Files.writeString(datasource, content);
        JSONObject directory = controller.datasourceUsers();
        assertTrue(directory.getBooleanValue("success"), directory.toString());
        assertEquals(new JSONArray(), directory.getJSONArray("users"));
        // 旧读取接口仍原样返回文件内容。
        assertEquals(content, controller.datasource().getString("content"));
        JSONObject added = controller.addDatasourceUser(new JSONObject().fluentPut("user", JSONArray.parseArray(users(1)).get(0)))
                .getBody();
        assertTrue(added.getBooleanValue("success"), added.toString());
        assertEquals(1, controller.datasourceUsers().getJSONArray("users").size());
    }

    @Test
    void staleVersionDoesNotWriteAnyBytesOrBackup() throws IOException {
        seedTwoUsers();
        JSONObject read = controller.datasourceUser("bilibili", "1");
        JSONObject changed = read.getJSONObject("user").clone();
        changed.put("enabled", false);
        assertTrue(saveUser("1", read.getString("version"), changed).getBody().getBooleanValue("success"));
        byte[] before = Files.readAllBytes(datasource);
        int backups = stampedBackupNames().size();

        ResponseEntity<JSONObject> rejected = saveUser("1", read.getString("version"), read.getJSONObject("user"));

        assertEquals(409, rejected.getStatusCode().value());
        assertTrue(rejected.getBody().getBooleanValue("conflict"));
        assertEquals("这位主播的配置刚被别人改过，请重新载入", rejected.getBody().getString("message"));
        assertArrayEquals(before, Files.readAllBytes(datasource));
        assertEquals(backups, stampedBackupNames().size());
    }

    @Test
    void replacingOneUserKeepsEveryOtherFieldAndReturnsNewVersion() throws IOException {
        seedTwoUsers();
        JSONObject untouched = JSONArray.parseArray(Files.readString(datasource)).getJSONObject(1);
        JSONObject read = controller.datasourceUser("bilibili", "1");
        JSONObject changed = read.getJSONObject("user");
        changed.getJSONArray("targets").getJSONObject(0).put("enabled", false);
        JSONObject result = saveUser("1", read.getString("version"), changed).getBody();

        assertTrue(result.getBooleanValue("success"));
        assertEquals(untouched, JSONArray.parseArray(Files.readString(datasource)).getJSONObject(1));
        assertNotEquals(read.getString("version"), result.getString("version"));
        assertEquals(result.getString("version"), controller.datasourceUserVersion("bilibili", "1").getString("version"));
        assertEquals(1, stampedBackupNames().size());
    }

    @Test
    void concurrentUpdatesOfDifferentUsersBothPersist() throws Exception {
        seedTwoUsers();
        JSONObject first = controller.datasourceUser("bilibili", "1");
        JSONObject second = controller.datasourceUser("bilibili", "2");
        first.getJSONObject("user").put("enabled", false);
        second.getJSONObject("user").put("enabled", false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return saveUser("1", first.getString("version"), first.getJSONObject("user"));
            });
            var b = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return saveUser("2", second.getString("version"), second.getJSONObject("user"));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(a.get(15, TimeUnit.SECONDS).getBody().getBooleanValue("success"));
            assertTrue(b.get(15, TimeUnit.SECONDS).getBody().getBooleanValue("success"));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        JSONArray users = JSONArray.parseArray(Files.readString(datasource));
        assertFalse(users.getJSONObject(0).getBooleanValue("enabled"));
        assertFalse(users.getJSONObject(1).getBooleanValue("enabled"));
    }

    @Test
    void addAndDeleteUseValidationAndRejectDuplicatesAndStaleDeletion() throws IOException {
        seedTwoUsers();
        JSONObject user = JSONArray.parseArray(users(3)).getJSONObject(0);
        JSONObject added = controller.addDatasourceUser(new JSONObject().fluentPut("user", user)).getBody();
        assertTrue(added.getBooleanValue("success"));
        byte[] before = Files.readAllBytes(datasource);
        assertEquals(409, controller.addDatasourceUser(new JSONObject().fluentPut("user", user)).getStatusCode().value());
        assertEquals(409, controller.deleteDatasourceUser("bilibili", "3", new JSONObject().fluentPut("version", "old")).getStatusCode().value());
        assertArrayEquals(before, Files.readAllBytes(datasource));
        assertTrue(controller.deleteDatasourceUser("bilibili", "3", new JSONObject().fluentPut("version", added.getString("version")))
                .getBody().getBooleanValue("success"));
        assertEquals(2, JSONArray.parseArray(Files.readString(datasource)).size());
    }

    @Test
    void invalidUserAndIdentityChangeDoNotWrite() throws IOException {
        seedTwoUsers();
        JSONObject read = controller.datasourceUser("bilibili", "1");
        byte[] before = Files.readAllBytes(datasource);
        JSONObject user = read.getJSONObject("user");
        user.put("uid", 9);
        assertFalse(saveUser("1", read.getString("version"), user).getBody().getBooleanValue("success"));
        user.put("uid", 1);
        when(validator.validateDatasource(anyString(), any())).thenReturn(List.of("invalid target"));
        JSONObject result = saveUser("1", read.getString("version"), user).getBody();
        assertFalse(result.getBooleanValue("success"));
        assertEquals(List.of("invalid target"), result.getJSONArray("issues"));
        assertArrayEquals(before, Files.readAllBytes(datasource));
        assertEquals(0, stampedBackupNames().size());
    }

    @Test
    void versionIgnoresFormattingAndObjectKeyOrderButIncludesUnknownFields() throws IOException {
        seedTwoUsers();
        String original = controller.datasourceUserVersion("bilibili", "1").getString("version");
        JSONArray users = JSONArray.parseArray(Files.readString(datasource));
        JSONObject user = users.getJSONObject(0);
        JSONObject reordered = new JSONObject();
        user.keySet().stream().sorted(java.util.Comparator.reverseOrder()).forEach(key -> reordered.put(key, user.get(key)));
        users.set(0, reordered);
        Files.writeString(datasource, users.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat));
        assertEquals(original, controller.datasourceUserVersion("bilibili", "1").getString("version"));
        reordered.put("extra", "changed");
        Files.writeString(datasource, users.toJSONString());
        assertNotEquals(original, controller.datasourceUserVersion("bilibili", "1").getString("version"));
    }

    @Test
    void directoryContainsAllUsersWithoutTemplateTextOrUnknownFields() throws IOException {
        seedTwoUsers();
        JSONObject result = controller.datasourceUsers();
        assertTrue(result.getBooleanValue("success"));
        assertEquals(2, result.getJSONArray("users").size());
        String summary = result.toJSONString();
        assertFalse(summary.contains("private-template"));
        assertFalse(summary.contains("extension"));
        assertFalse(summary.contains("params"));
        assertTrue(controller.datasourceUser("bilibili", "1").toJSONString().contains("private-template"));
    }

    @Test
    void summaryReportsCustomTemplatesUsingCurrentDefaultsAndKeepsOnlyDisplayFields() throws IOException {
        seedTwoUsers();
        var handler = mock(org.frostnova.nova.core.handler.NovaEventHandler.class);
        when(handlerService.getHandler("test.Handler")).thenReturn(java.util.Optional.of(handler));
        when(handler.getDefaultParams()).thenReturn(new JSONObject().fluentPut("message", "private-template"));
        JSONObject summary = controller.datasourceUsers().getJSONArray("users").getJSONObject(0);
        JSONObject message = summary.getJSONArray("targets").getJSONObject(0).getJSONArray("messages").getJSONObject(0);
        assertFalse(message.getBooleanValue("_custom"));
        when(handler.getDefaultParams()).thenReturn(new JSONObject().fluentPut("message", "new default"));
        summary = controller.datasourceUsers().getJSONArray("users").getJSONObject(0);
        message = summary.getJSONArray("targets").getJSONObject(0).getJSONArray("messages").getJSONObject(0);
        assertTrue(message.getBooleanValue("_custom"));
        assertFalse(message.containsKey("params"));
        assertFalse(summary.containsKey("version"));
    }

    // 成员只能保存自己的主播，不能借保存、增加、删除或整份覆盖改动别人。
    @Test
    void memberSavesOnlyOwnedStreamerAndKeepsOtherUserIntact() throws Exception {
        seedTwoUsers();
        var member = new ConfigUiAccount("member",
                ConfigUiAccount.Role.MEMBER,
                ConfigUiAccount.Status.ENABLED, "Member", null, null, null,
                List.of("bilibili:1"), Instant.EPOCH, null, true);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class)))
                .build();
        var originalOther = controller.datasourceUser("bilibili", "2").getJSONObject("user");
        var read = controller.datasourceUser("bilibili", "1");
        read.getJSONObject("user").put("enabled", false);
        mvc.perform(post("/config/api/datasource/users/bilibili/1")
                .requestAttr(ConfigUiAccess.ACCOUNT, member).contentType("application/json").content(read.toJSONString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertEquals(originalOther, controller.datasourceUser("bilibili", "2").getJSONObject("user"));
        assertFalse(controller.datasourceUser("bilibili", "1").getJSONObject("user").getBooleanValue("enabled"));
        byte[] before = Files.readAllBytes(datasource);
        for (String uid : List.of("2", "3"))
            mvc.perform(post("/config/api/datasource/users/bilibili/" + uid)
                    .requestAttr(ConfigUiAccess.ACCOUNT, member).contentType("application/json").content(read.toJSONString()))
                    .andExpect(status().isNotFound());
        for (String path : List.of("/config/api/datasource/users", "/config/api/datasource/users/bilibili/2/delete", "/config/api/datasource"))
            mvc.perform(post(path)
                    .requestAttr(ConfigUiAccess.ACCOUNT, member).contentType("application/json").content(read.toJSONString()))
                    .andExpect(status().isForbidden());
        mvc.perform(get("/config/api/datasource")
                .requestAttr(ConfigUiAccess.ACCOUNT, member))
                .andExpect(status().isForbidden());
        var mismatch = controller.datasourceUser("bilibili", "1");
        mismatch.getJSONObject("user").put("uid", 2);
        mvc.perform(post("/config/api/datasource/users/bilibili/1")
                .requestAttr(ConfigUiAccess.ACCOUNT, member).contentType("application/json").content(mismatch.toJSONString()))
                .andExpect(status().isBadRequest());
        assertArrayEquals(before, Files.readAllBytes(datasource));
    }

    private void seedTwoUsers() throws IOException {
        Files.writeString(datasource, """
                [ {"platform":"bilibili","uid":1,"enabled":true,"extension":{"keep":[1,2]},
                   "targets":[{"platform":"qq","type":1,"num":12345,"enabled":true,
                     "messages":[{"handler":"test.Handler","params":{"message":"private-template"}}]}]},
                  {"platform":"bilibili","uid":2,"enabled":true,"extension":{"keep":[3,4]},"targets":[]} ]
                """);
    }

    private ResponseEntity<JSONObject> saveUser(String uid, String version, JSONObject user) {
        return controller.saveDatasourceUser("bilibili", uid, new JSONObject().fluentPut("version", version).fluentPut("user", user));
    }

    private void tick() {
        ticks.incrementAndGet();
    }

    private ResponseEntity<JSONObject> save(String content) {
        return controller.saveDatasource(new JSONObject().fluentPut("content", content));
    }

    private static String users(int uid) {
        return new JSONArray().fluentAdd(new JSONObject()
                .fluentPut("uid", uid)
                .fluentPut("platform", "bilibili")
                .fluentPut("enabled", true)
                .fluentPut("targets", new JSONArray())).toJSONString();
    }

    private List<String> stampedBackupNames() throws IOException {
        String prefix = "datasource.json.";
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".bak"))
                    .filter(name -> name.length() > prefix.length() + ".bak".length())
                    .filter(name -> name.substring(prefix.length(), name.length() - ".bak".length())
                            .matches("\\d{8}-\\d{6}"))
                    .sorted()
                    .toList();
        }
    }
}
