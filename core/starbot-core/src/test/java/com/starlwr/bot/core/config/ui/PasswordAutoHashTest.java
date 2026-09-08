package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.config.ui.auth.PasswordHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 明文口令自动哈希落盘的测试
 * <p>
 * <b>不能让使用者自己去抄哈希串。</b>那串东西 80 个字符，手工复制少一位就再也登不进去，
 * 而登录时的报错与「口令输错了」一模一样——人只会以为是自己记错了密码。
 * 这个坑真踩过，掉的就是最后一位。所以明文必须由程序自己换成哈希写回去。
 */
@DisplayName("明文口令自动哈希")
class PasswordAutoHashTest {
    private static final String TEMPLATE = """
            starbot:
              core:
                config-ui:
                  enabled: true
                  auth:
                    password: 我的口令
            """;

    @TempDir
    Path dir;

    private Path config;
    private ConfigurationFileService fileService;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        fileService = new ConfigurationFileService(config);
    }

    private String stored() throws IOException {
        return fileService.read().get(ConfigUiAuthService.PASSWORD_PROPERTY);
    }

    private ConfigUiAuthService service(String password) {
        StarBotCoreProperties.ConfigUi.Auth properties = new StarBotCoreProperties.ConfigUi.Auth();
        properties.setPassword(password);
        properties.setTotp(false);

        return new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), fileService);
    }

    @Test
    @DisplayName("启动时把配置里的明文换成哈希写回，文件里不再有明文")
    void hashesPlainTextOnStartup() throws IOException {
        ConfigUiAuthService service = service("我的口令");

        String saved = stored();
        assertTrue(PasswordHash.isHashed(saved), "配置里应已是哈希: " + saved);
        assertFalse(Files.readString(config, StandardCharsets.UTF_8).contains("我的口令"), "文件里不该再有明文");
        assertTrue(service.login("我的口令".toCharArray(), null, "1.2.3.4").success(), "口令本身要照常可用");
    }

    @Test
    @DisplayName("写回的哈希下次启动能直接用，且不会被反复重写")
    void hashedValueIsStableAcrossRestarts() throws IOException {
        service("我的口令");
        String first = stored();

        // 第二次启动读到的已经是哈希，不该再动它——每次重写都会多留一份备份文件
        service(first);
        assertTrue(first.equals(stored()), "已是哈希时不应重写");
        assertTrue(service(first).login("我的口令".toCharArray(), null, "1.2.3.4").success());
    }

    @Test
    @DisplayName("写不进配置文件时也要能正常登录")
    void survivesUnwritableConfig() throws IOException {
        Files.delete(config);

        StarBotCoreProperties.ConfigUi.Auth properties = new StarBotCoreProperties.ConfigUi.Auth();
        properties.setPassword("我的口令");
        properties.setTotp(false);

        // 落盘失败只是「文件里还留着明文」，不该连登录都不让用
        ConfigUiAuthService service = new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), fileService);

        assertTrue(service.login("我的口令".toCharArray(), null, "1.2.3.4").success());
    }
}
