package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.protocol.EventStreamTokenService;
import com.starlwr.bot.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 配置界面读数里的备份保留份数：界面该显示生效值，不是文件原文
 * <p>
 * 文件里写着越界的 500 份时，程序按 1–100 生效。此前上下限那组判据只量
 * getter，读数端点里把它换成生效值的那几行没人看着——去掉那几行，
 * 界面显示 500、落盘只留 100，页面说的和程序做的对不上，而全仓仍是绿的。
 * <p>
 * 台架里 {@code properties} 与配置文件是两处来源：生产上由 Spring 把文件绑进
 * properties，测试里手工设成同一个值，等的就是文件写 500、绑定也到 500 的那次回显。
 */
@DisplayName("配置界面备份份数回显")
class BackupKeepEchoTest {

    private static final String BACKUP_KEEP = "novabot.core.config-ui.backup-keep";

    @TempDir
    Path dir;

    private Path config;

    private ConfigUiController controller;

    @BeforeEach
    void setUp() {
        config = dir.resolve("application.yml");
    }

    /**
     * 写一份配置并接上控制台，依赖接法与同目录的控制台用例一致
     */
    @SuppressWarnings("unchecked")
    private void start(String yaml, int boundBackupKeep) throws IOException {
        Files.writeString(config, yaml, StandardCharsets.UTF_8);

        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getConfigUi().setBackupKeep(boundBackupKeep);
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        controller = new ConfigUiController(
                new ConfigurationMetadataService(),
                new ConfigurationFileService(config),
                properties,
                mock(com.starlwr.bot.core.datasource.AbstractDataSource.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(com.starlwr.bot.core.service.NovaSenderService.class),
                mock(com.starlwr.bot.core.sender.NovaMessageSender.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                mock(com.starlwr.bot.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.starlwr.bot.core.sender.PushGate.class),
                mock(com.starlwr.bot.core.service.LiveDataService.class),
                mock(com.starlwr.bot.core.timeline.TimelineStore.class),
                mock(com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new StarBotCoreProperties()),
                mock(UpdateCheckService.class));
    }

    private JSONObject values() {
        JSONObject result = controller.values();
        assertTrue(result.getBooleanValue("success"), "读配置本身不该失败");
        return result.getJSONObject("values");
    }

    @Test
    @DisplayName("文件写 500 时回显生效的 100，别的键照原文")
    void valuesEchoEffectiveBackupKeepWhenFileOutOfRange() throws IOException {
        start("""
                server:
                  port: 7827
                novabot:
                  core:
                    config-ui:
                      backup-keep: 500
                """, 500);

        JSONObject values = values();

        assertEquals("100", values.getString(BACKUP_KEEP),
                "文件写 500 程序只按 100 生效, 界面显示 500 就是让页面撒谎");
        assertEquals("7827", values.getString("server.port"), "替换只该动越界的那一项, 其它键照原文回");
    }

    @Test
    @DisplayName("文件没写这一项时读数里也不许凭空多出它")
    void absentBackupKeepStaysAbsent() throws IOException {
        start("""
                server:
                  port: 7827
                """, 10);

        JSONObject values = values();

        assertNull(values.getString(BACKUP_KEEP), "文件里没写的项不该被替换逻辑凭空补进读数");
    }
}
