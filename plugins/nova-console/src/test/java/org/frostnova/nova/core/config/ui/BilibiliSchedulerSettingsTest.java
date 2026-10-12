package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.frostnova.nova.bilibili.config.BilibiliConfigurationGroups;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.config.NovaBilibiliThreadPoolConfig;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.RetiredConfigurationKeyCheck;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("B站调度设置兼容旧配置")
class BilibiliSchedulerSettingsTest {
    private static final String PREFIX = "novabot.bilibili.bilibili-thread.";
    private static final List<String> RETIRED = List.of("max-pool-size", "queue-capacity", "keep-alive-seconds");
    private static final String CONFIG = """
            novabot:
              bilibili:
                bilibili-thread:
                  core-pool-size: 7
                  max-pool-size: 32 # keep maximum
                  queue-capacity: 256 # keep queue
                  keep-alive-seconds: 300 # keep idle
              core:
                config-ui:
                  enabled: true
            """;
    @TempDir Path dir;

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NovaBilibiliProperties.class)
    @Import({RetiredConfigurationKeyCheck.class, NovaBilibiliThreadPoolConfig.class})
    static class StartupConfiguration {}

    // 抓升级后旧行导致启动报错，或核心线程数无法绑定的故障。
    @Test
    @DisplayName("旧配置照常启动，核心线程数仍决定调度线程数")
    void oldConfigurationStarts() throws IOException {
        startAndWarnings(CONFIG, 7);
        startAndWarnings(CONFIG.replace("core-pool-size: 7", "core-pool-size: 1"), 2);
    }

    // 抓旧行默默失效、使用者仍以为它生效的故障；同时防止没有旧行时误报。
    @Test
    @DisplayName("三条旧行各提醒从未生效且可删除，没有旧行时不提醒")
    void warnsForEveryUnusedSettingOnlyWhenPresent() throws IOException {
        List<String> warnings = startAndWarnings(CONFIG, 7);
        assertEquals(3, warnings.size(), "三条旧行应各提醒一次");
        for (String key : RETIRED) {
            List<String> matching = warnings.stream().filter(line -> line.contains(PREFIX + key)).toList();
            assertEquals(1, matching.size(), key);
            for (String text : List.of("定时调度器", "核心线程数", "从来没起过作用", "可以删掉")) {
                assertTrue(matching.get(0).contains(text), matching.get(0));
            }
        }
        String currentOnly = CONFIG.lines().filter(line -> RETIRED.stream().noneMatch(line::contains))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(List.of(), startAndWarnings(currentOnly, 7));
    }

    private List<String> startAndWarnings(String content, int poolSize) throws IOException {
        Path config = dir.resolve("startup.yml");
        Files.writeString(config, content);
        Logger logger = (Logger) LoggerFactory.getLogger(RetiredConfigurationKeyCheck.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        try {
            SpringApplication application = new SpringApplication(StartupConfiguration.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setRegisterShutdownHook(false);
            application.setLogStartupInfo(false);
            application.addListeners((org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationStartedEvent>)
                    event -> logger.addAppender(appender));
            try (var context = assertDoesNotThrow(() -> application.run(
                    "--spring.config.location=" + config.toUri(), "--spring.main.banner-mode=off"))) {
                assertEquals(content.contains("core-pool-size: 1") ? 1 : 7,
                        context.getBean(NovaBilibiliProperties.class).getBilibiliThread().getCorePoolSize());
                assertEquals(poolSize, context.getBean(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize());
            }
            return appender.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // 抓设置页继续展示无效项，或剩余项掉进未归组的故障。
    @Test
    @DisplayName("schema 只显示已归组的调度线程数并说明下限")
    void schemaContainsOnlySchedulerSize() throws IOException {
        try (var context = context()) {
            JSONObject schema = controller(context, new ConfigurationMetadataService()).schema();
            List<JSONObject> fields = new ArrayList<>();
            for (Object group : schema.getJSONArray("groups")) {
                for (Object field : ((JSONObject) group).getJSONArray("fields")) {
                    JSONObject item = (JSONObject) field;
                    if (item.getString("name").startsWith(PREFIX)) fields.add(item);
                }
            }
            assertEquals(List.of(PREFIX + "core-pool-size"), fields.stream().map(f -> f.getString("name")).toList());
            assertNotNull(fields.get(0).getString("group"));
            assertEquals("B站定时任务 · 调度线程数", fields.get(0).getString("label"));
            assertTrue(fields.get(0).getString("summary").contains("小于 2 按 2 算"));
            for (Object field : schema.getJSONArray("ungrouped")) {
                assertFalse(((JSONObject) field).getString("name").startsWith(PREFIX));
            }
        }
    }

    // 抓保存别的设置时拒绝旧文件，或偷偷删除、改写旧行的故障。
    @Test
    @DisplayName("控制台保存其他项成功，三条旧行逐字保留")
    void consoleSavePreservesOldLines() throws IOException {
        try (var context = context()) {
            var controller = controller(context, new ConfigurationMetadataService());
            var response = controller.save(Map.of("novabot.core.config-ui.enabled", "false"));
            assertEquals(200, response.getStatusCode().value());
            assertTrue(response.getBody().getBooleanValue("success"), response.getBody().toString());
            String saved = Files.readString(dir.resolve("application.yml"));
            for (String line : CONFIG.lines().filter(line -> RETIRED.stream().anyMatch(line::contains)).toList()) {
                assertTrue(saved.lines().anyMatch(line::equals), line);
            }
            assertTrue(saved.contains("enabled: false"));
        }
    }

    private AnnotationConfigApplicationContext context() {
        return new AnnotationConfigApplicationContext(NovaCoreProperties.class, NovaBilibiliProperties.class,
                BilibiliConfigurationGroups.class);
    }
    @SuppressWarnings("unchecked")
    private ConfigUiController controller(AnnotationConfigApplicationContext context,
                                          ConfigurationMetadataService metadata) throws IOException {
        return controller(context, metadata, new ConfigurationLabelResolver(context));
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller(AnnotationConfigApplicationContext context,
                                          ConfigurationMetadataService metadata,
                                          ConfigurationLabelResolver labelResolver) throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, CONFIG);
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        return new ConfigUiController(
                metadata,
                new ConfigurationFileService(config),
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                new ConfigurationLevelResolver(context),
                labelResolver,
                new ConfigurationEffectResolver(context),
                new ConfigurationDangerResolver(context),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class),
                context.getBeanProvider(ConfigurationGroupContributor.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.alert.AlertService.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
    }

}
