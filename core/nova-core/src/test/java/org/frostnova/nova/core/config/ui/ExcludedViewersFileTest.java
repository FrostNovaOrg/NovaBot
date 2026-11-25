package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.assistant.AssistantConfiguration;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.StandbyPhases;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ExcludedViewersFileTest {
    private static final String KEY = "novabot.assistant.excluded-uids";
    @TempDir Path dir;
    private Path config;
    private Path exported;

    @BeforeEach
    void setUp() throws Exception {
        config = dir.resolve("application.yml");
        Files.writeString(config, "server:\n  port: 7827\n");
        exported = dir.resolve("records/excluded-viewers.json");
        Files.createDirectories(exported.getParent());
    }

    @Test
    @DisplayName("启动覆盖旧名单：防止外部把上次运行的拒绝名单当成当前设置")
    void startupExportsCurrentSettingsWithoutOtherProperties() throws Exception {
        Files.writeString(exported, "{\"version\":1,\"updatedAt\":1,\"excludedUids\":[99]}");
        long before = System.currentTimeMillis();
        try (var context = start(List.of(33L, 11L, 33L))) {
            JSONObject json = read();
            assertEquals(Set.of("version", "updatedAt", "excludedUids"), json.keySet());
            assertEquals(1, json.getIntValue("version"));
            assertEquals(List.of(11L, 33L), json.getList("excludedUids", Long.class));
            assertTrue(json.getLongValue("updatedAt") >= before);
            assertTrue(json.getLongValue("updatedAt") <= System.currentTimeMillis());
            assertFalse(context.getBean(AssistantProperties.class).isEnabled());
        }
    }

    @Test
    @DisplayName("首次启动空名单也建文件：防止外部误认为当前版本不支持名单查询")
    void startupCreatesEmptyFile() throws Exception {
        try (var context = start(List.of())) {
            assertEquals(List.of(), read().getList("excludedUids", Long.class));
        }
    }

    @Test
    @DisplayName("启动核对配置后再写：防止候命实例提前覆盖正在运行的名单")
    void startupWaitsForConfigurationRecheck() throws Exception {
        try (var context = start(List.of(11L), candidate -> candidate.registerBean("configurationRecheck",
                SmartLifecycle.class, () -> new SmartLifecycle() {
                    private boolean running;
                    @Override public void start() {
                        assertFalse(Files.exists(exported));
                        candidate.getBean(AssistantProperties.class).setExcludedUids(List.of(33L));
                        running = true;
                    }
                    @Override public void stop() { running = false; }
                    @Override public boolean isRunning() { return running; }
                    @Override public int getPhase() { return StandbyPhases.RECHECK; }
                }))) {
            assertEquals(List.of(33L), read().getList("excludedUids", Long.class));
        }
    }

    @Test
    @DisplayName("控制台加人后立即更新：防止外部继续读到旧名单而漏标观众")
    void consoleSaveImmediatelyExportsAppliedSettings() throws Exception {
        try (var context = start(List.of(11L))) {
            var response = controller(context).save(Map.of(KEY, "33\n11"));
            assertTrue(response.getBody().getBooleanValue("success"));
            assertEquals(List.of(), response.getBody().getList("restartRequired", String.class));
            assertEquals(List.of(33L, 11L), context.getBean(AssistantProperties.class).getExcludedUids());
            assertEquals(List.of(11L, 33L), read().getList("excludedUids", Long.class));
        }
    }

    @Test
    @DisplayName("清空后保留空数组文件：防止外部仍把已移出名单的观众标为不分析")
    void clearingListKeepsFileWithEmptyArray() throws Exception {
        try (var context = start(List.of(11L))) {
            var controller = controller(context);
            controller.save(Map.of(KEY, "11"));
            assertTrue(controller.save(Map.of(KEY, "")).getBody().getBooleanValue("success"));
            assertTrue(Files.isRegularFile(exported));
            assertEquals(List.of(), read().getList("excludedUids", Long.class));
            assertEquals(List.of(), context.getBean(AssistantProperties.class).getExcludedUids());
        }
    }

    @Test
    @DisplayName("名单相同不换文件：防止重复保存、换序或重复编号造成虚假的更新时间")
    void equivalentListsDoNotRewriteFile() throws Exception {
        try (var context = start(List.of(11L, 33L))) {
            String original = Files.readString(exported);
            FileTime stamp = FileTime.fromMillis(123456000);
            Files.setLastModifiedTime(exported, stamp);
            var controller = controller(context);
            controller.save(Map.of(KEY, "33\n11\n33"));
            controller.save(Map.of(KEY, "33\n11\n33"));
            assertEquals(original, Files.readString(exported));
            assertEquals(stamp, Files.getLastModifiedTime(exported));
        }
    }

    @Test
    @DisplayName("设置没变时重启不换文件：防止外部误把每次重启当成名单变更")
    void restartWithSameSettingsDoesNotRewriteFile() throws Exception {
        try (var context = start(List.of(11L))) {
            assertEquals(List.of(11L), read().getList("excludedUids", Long.class));
        }
        String original = Files.readString(exported);
        FileTime stamp = FileTime.fromMillis(123456000);
        Files.setLastModifiedTime(exported, stamp);
        try (var context = start(List.of(11L))) {
            assertEquals(original, Files.readString(exported));
            assertEquals(stamp, Files.getLastModifiedTime(exported));
        }
    }

    @Test
    @DisplayName("写文件失败只告警：防止外部文件写不进导致控制台设置无法生效或旧文件损坏")
    void exportFailureWarnsAndKeepsAppliedSettingsAndPreviousFile() throws Exception {
        try (var context = start(List.of(11L))) {
            String original = Files.readString(exported);
            Files.createDirectory(exported.resolveSibling("excluded-viewers.json.tmp"));
            Logger logger = (Logger) LoggerFactory.getLogger("org.frostnova.nova.core.assistant.ExcludedViewersStore");
            var logs = new ListAppender<ILoggingEvent>();
            logs.start();
            logger.addAppender(logs);
            try {
                var response = controller(context).save(Map.of(KEY, "33"));
                assertTrue(response.getBody().getBooleanValue("success"));
                assertEquals(List.of(), response.getBody().getList("restartRequired", String.class));
                assertEquals(List.of(33L), context.getBean(AssistantProperties.class).getExcludedUids());
                assertEquals(original, Files.readString(exported));
                assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("excluded-viewers.json")));
            } finally {
                logger.detachAppender(logs);
                logs.stop();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "11\n33.5"})
    @DisplayName("无效编号整批拒绝：防止保存后名单不能生效或重启失败")
    void invalidUidsDoNotChangeSettingsOrExport(String value) throws Exception {
        try (var context = start(List.of(11L))) {
            String originalConfig = Files.readString(config);
            String originalExport = Files.readString(exported);
            assertEquals(400, controller(context).save(Map.of(KEY, value)).getStatusCode().value());
            assertEquals(originalConfig, Files.readString(config));
            assertEquals(originalExport, Files.readString(exported));
            assertEquals(List.of(11L), context.getBean(AssistantProperties.class).getExcludedUids());
        }
    }

    /** 抓的使用者故障：控制台把含空白的管理员号码保存为另一个号码，还提示保存成功。 */
    @ParameterizedTest
    @ValueSource(strings = {"6 7", "6\t7"})
    @DisplayName("控制台拒绝管理员号码中间的空白并指出第二项，整批不写入")
    void consoleRejectsAdminItemWithInternalWhitespace(String invalid) throws Exception {
        try (var context = start(List.of(11L))) {
            String key = "novabot.core.command.admins";
            byte[] before = Files.readAllBytes(config);

            var response = controller(context).save(Map.of(key, "5\n" + invalid, "server.port", "7828"));

            assertFalse(response.getBody().getBooleanValue("success"));
            String message = response.getBody().getString("message");
            assertTrue(message.contains(key), message);
            assertTrue(message.contains("第 2 项「" + invalid + "」"), message);
            assertTrue(message.contains("不是有效的数字"), message);
            assertTrue(message.contains("本批全部未保存"), message);
            assertArrayEquals(before, Files.readAllBytes(config));
        }
    }

    /** 抓的使用者故障：观众拒绝名单报错没有位置和原文，多行名单中找不到填错的号码。 */
    @ParameterizedTest
    @ValueSource(strings = {"6 7", "6\t7", "abc", "0"})
    @DisplayName("观众拒绝名单校验指出实际行号和原文，设置与文件保持不动")
    void invalidUidMessageIdentifiesLineAndOriginalValue(String invalid) throws Exception {
        try (var context = start(List.of(11L))) {
            byte[] originalConfig = Files.readAllBytes(config);
            String originalExport = Files.readString(exported);

            var response = controller(context).save(Map.of(KEY, "5\n\n" + invalid));

            assertEquals(400, response.getStatusCode().value());
            String message = response.getBody().getString("message");
            assertTrue(message.contains("观众拒绝名单第 3 行「" + invalid + "」"), message);
            assertTrue(message.contains("不是正整数编号"), message);
            assertArrayEquals(originalConfig, Files.readAllBytes(config));
            assertEquals(originalExport, Files.readString(exported));
            assertEquals(List.of(11L), context.getBean(AssistantProperties.class).getExcludedUids());
        }
    }

    private JSONObject read() throws Exception {
        assertTrue(Files.isRegularFile(exported), "数据目录中应有当前拒绝名单文件");
        return JSON.parseObject(Files.readString(exported));
    }

    private AnnotationConfigApplicationContext start(List<Long> excluded) {
        return start(excluded, context -> { });
    }

    private AnnotationConfigApplicationContext start(List<Long> excluded,
            java.util.function.Consumer<AnnotationConfigApplicationContext> customize) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("settings", Map.of(KEY, excluded)));
        context.registerBean(NovaCoreProperties.class, () -> {
            var properties = new NovaCoreProperties();
            properties.getLive().setLiveDataPath(exported.getParent().resolve("data.json").toString());
            return properties;
        });
        context.register(AssistantConfiguration.class);
        customize.accept(context);
        context.refresh();
        return context;
    }

    private ConfigUiController controller(AnnotationConfigApplicationContext context) {
        return controller(context, config);
    }

    @SuppressWarnings("unchecked")
    static ConfigUiController controller(AnnotationConfigApplicationContext context, Path config) {
        var properties = context.getBean(NovaCoreProperties.class);
        var applier = RuntimeConfigurationApplier.bench(properties)
                .contributors(context.getBeansOfType(RuntimeConfigurationApplierContributor.class).values()).build();
        return new ConfigUiController(new ConfigurationMetadataService(), new ConfigurationFileService(config), properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class), mock(ObjectProvider.class),
                mock(ConfigurationValidator.class), mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class), mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                new ConfigurationLevelResolver(context), new ConfigurationLabelResolver(context),
                new ConfigurationEffectResolver(context), new ConfigurationDangerResolver(context), applier,
                mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.protocol.EventStreamTokenService.class), mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class), mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(properties), mock(UpdateCheckService.class));
    }
}
