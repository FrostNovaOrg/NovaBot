package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.config.NovaCoreProperties;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private final AtomicInteger ticks = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        datasource = dir.resolve("datasource.json");
        Files.writeString(datasource, "[]", StandardCharsets.UTF_8);

        properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(datasource.toString());

        ConfigurationValidator validator = mock(ConfigurationValidator.class);
        when(validator.validateDatasource(anyString(), any())).thenReturn(List.of());

        NovaSenderService senderService = mock(NovaSenderService.class);
        when(senderService.getSenderNames()).thenReturn(Set.of());

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ObjectProvider<HealthProbe> healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenReturn(Stream.empty());

        timeline = mock(TimelineStore.class);

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
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
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
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class),
                mock(AlertService.class),
                clock);
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
