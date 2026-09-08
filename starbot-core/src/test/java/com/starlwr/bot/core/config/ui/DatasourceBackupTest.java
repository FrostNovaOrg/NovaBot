package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.service.PushTemplateDefaults;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.timeline.TimelineEvent;
import com.starlwr.bot.core.timeline.TimelineEventType;
import com.starlwr.bot.core.timeline.TimelineStore;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private StarBotCoreProperties properties;

    private ConfigUiController controller;

    private TimelineStore timeline;

    private final AtomicInteger ticks = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        datasource = dir.resolve("datasource.json");
        Files.writeString(datasource, "[]", StandardCharsets.UTF_8);

        properties = new StarBotCoreProperties();
        properties.getDatasource().setJsonPath(datasource.toString());

        ConfigurationValidator validator = mock(ConfigurationValidator.class);
        when(validator.validateDatasource(anyString(), any())).thenReturn(List.of());

        StarBotSenderService senderService = mock(StarBotSenderService.class);
        when(senderService.getSenderNames()).thenReturn(Set.of());

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ObjectProvider<HealthProbe> healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenReturn(Stream.empty());

        timeline = mock(TimelineStore.class);

        controller = new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                dataSource,
                healthProbes,
                validator,
                senderService,
                mock(com.starlwr.bot.core.sender.StarBotMessageSender.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                mock(com.starlwr.bot.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.protocol.EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.sender.PushGate.class),
                mock(com.starlwr.bot.core.service.LiveDataService.class),
                timeline,
                mock(com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new StarBotCoreProperties()),
                mock(UpdateCheckService.class));
        controller.backupClock = Clock.fixed(START, ZoneOffset.UTC);
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

    private void tick() {
        controller.backupClock = Clock.fixed(START.plusSeconds(ticks.incrementAndGet()), ZoneOffset.UTC);
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
