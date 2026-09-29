package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置文件没有写权限时，保存的回话要说人话
 * <p>
 * 使用者故障：此前回话是「保存失败: 」直接接异常自己的消息，Windows 上那只是一个路径，
 * 既看不出是没权限还是文件只读，也不知道该去改哪个文件。
 */
@DisplayName("文件写不进时保存回话说清权限")
class SaveFailureWhenFileNotWritableTest {

    private static final String TEMPLATE = """
            server:
              port: 7827
            """;

    @TempDir
    Path dir;

    private Path config;

    private ConfigUiController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);

        NovaCoreProperties properties = new NovaCoreProperties();
        ConfigurationMetadataService metadata = mock(ConfigurationMetadataService.class);
        when(metadata.getKnownTypes()).thenReturn(Map.of("server.port", "java.lang.Integer"));

        controller = new ConfigUiController(
                metadata,
                new ConfigurationFileService(config),
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.protocol.EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(properties),
                mock(UpdateCheckService.class));
    }

    /**
     * 使用者故障：文件只读或没有写权限时，保存失败的回话只给一个路径，
     * 看不出是权限的事、也不知道是哪个文件。目录同时不让建件，是为了让失败
     * 落在直接写那一步上（目录还能建件时换名会先成，POSIX 上换名不受原件自身权限拦）。
     */
    @Test
    @DisplayName("文件只读且目录不让建件：回话说清没有写权限或只读，点出配置文件，原件不动")
    void saveFailureNamesThePermissionProblemAndTheFile() throws IOException {
        Set<PosixFilePermission> originalDir = Files.getPosixFilePermissions(dir);
        Set<PosixFilePermission> originalFile = Files.getPosixFilePermissions(config);

        JSONObject result;
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("r--r--r--"));
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(config),
                    "当前用户无视文件权限（如 root），只读文件设不出来");
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(dir),
                    "当前用户无视目录权限（如 root），不让建新文件的目录设不出来");
            result = controller.save(new LinkedHashMap<>(Map.of("server.port", "7001"))).getBody();
        } finally {
            Files.setPosixFilePermissions(dir, originalDir);
            Files.setPosixFilePermissions(config, originalFile);
        }

        assertFalse(result.getBooleanValue("success"), "写不进时不许回报成功");
        // 整句钉死：说清是没有写权限还是只读，点出是哪个文件；句中用中文标点
        assertEquals("保存失败: 没有写权限或文件被设成了只读，写不进 " + config.toAbsolutePath()
                        + "，请检查运行程序的用户对这个文件和它所在目录的写权限（Windows 上还有文件属性里的只读）",
                result.getString("message"));
        assertEquals(TEMPLATE, Files.readString(config, StandardCharsets.UTF_8), "没存上就不许动原件");
    }
}
