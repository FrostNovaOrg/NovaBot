package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.config.ui.auth.PasswordHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
            novabot:
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
        NovaCoreProperties.ConfigUi.Auth properties = new NovaCoreProperties.ConfigUi.Auth();
        properties.setPassword(password);
        properties.setTotp(false);

        ConfigUiAuthService service = new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), fileService);
        service.start();
        return service;
    }

    /**
     * 配置目录里还留着这段明文的文件名，一份没有时为空表
     */
    private List<String> filesHolding(String plaintext) throws IOException {
        List<String> left = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isRegularFile(file)
                        && Files.readString(file, StandardCharsets.UTF_8).contains(plaintext)) {
                    left.add(file.getFileName().toString());
                }
            }
        }
        return left;
    }

    @Test
    @DisplayName("写回哈希那一次，同目录不留下含明文的备份")
    void hashWriteBackLeavesNoPlaintextBehind() throws IOException {
        // 抓的用户故障：手写明文口令启动一次以后，配置目录里的备份文件还留着明文口令。
        // 手写的那一份原样躺在盘上，谁拿到配置目录就拿到了登录口令。
        List<String> red = new ArrayList<>();

        List<String> before = filesHolding("我的口令");
        try {
            // 对照：还没写回时这把扫描要看得见手写那一份明文。看不见就等于它只会报空表，
            // 后面那句「一份都没有」在它瞎掉的时候同样是绿的
            assertFalse(before.isEmpty(), "对照：写回前要在配置目录里找得到明文，得到：" + before);
        } catch (AssertionError e) {
            red.add("①" + e.getMessage());
        }

        service("我的口令");

        try {
            List<String> left = filesHolding("我的口令");
            assertTrue(left.isEmpty(),
                    "手写明文口令启动一次以后，配置目录里的备份文件还留着明文口令，留下的是：" + left);
        } catch (AssertionError e) {
            red.add("②" + e.getMessage());
        }

        if (!red.isEmpty()) {
            fail("写回不留明文备份两问中 " + red.size() + " 问未销：" + String.join("；", red));
        }
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

        // 第二次启动读到的已经是哈希，不该再动它——白重写一遍配置文件毫无必要
        service(first);
        assertTrue(first.equals(stored()), "已是哈希时不应重写");
        assertTrue(service(first).login("我的口令".toCharArray(), null, "1.2.3.4").success());
    }

    @Test
    @DisplayName("候命期间在旧版里改过的口令不被写回盖掉")
    void passwordChangedDuringStandbyIsNotOverwritten() throws IOException {
        // 热升级候命那一会儿旧版还开着，设置页若改了口令，旧版会把新值存进文件；
        // 过门后的写回照写的话，新口令被盖回起动时那份明文的哈希，重启后新口令不认
        NovaCoreProperties.ConfigUi.Auth properties = new NovaCoreProperties.ConfigUi.Auth();
        properties.setPassword("起动时的口令");
        properties.setTotp(false);
        ConfigUiAuthService service = new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), fileService);

        // 构造之后、写回之前，把文件里那一项换成新值（模拟旧版在候命期里的保存）
        fileService.writeWithoutBackup(Map.of(ConfigUiAuthService.PASSWORD_PROPERTY, "候命期改的口令"));

        service.start();

        assertEquals("候命期改的口令", stored(),
                "候命期间被改过的那一项要原样留着，等下次启动读到新值再换哈希");
    }

    @Test
    @DisplayName("带首尾空白的引文明文照旧换成哈希，明文清掉")
    void quotedPlaintextWithSpacesIsStillHashedAndCleared() throws IOException {
        // 手写的配置常是 password: " 口令 " 这种带引号、首尾留空白的写法：
        // 起动那一路读到的值带着空白，规整（去首尾空白）之后才当口令用。
        // 写回前的核对若拿规整过的口令与文件里的原值逐字比，永远对不上，
        // 明文就一直留在文件里——核对要用与起动时同样的规整再比
        Files.writeString(config, """
                novabot:
                  core:
                    config-ui:
                      enabled: true
                      auth:
                        password: " 带空白的口令 "
                """, StandardCharsets.UTF_8);

        // 阳性对照：文件里那一项读出来确实带着首尾空白（引号去了、空白留着）。
        // 这一条不成立，后面两句在本件就量不到写回那一侧的毛病
        assertEquals(" 带空白的口令 ", stored(), "对照：文件里那一项应原样带着首尾空白");

        ConfigUiAuthService service = service(" 带空白的口令 ");

        assertTrue(PasswordHash.isHashed(stored()), "配置里应已是哈希: " + stored());
        assertFalse(Files.readString(config, StandardCharsets.UTF_8).contains("带空白的口令"),
                "文件里不该再有明文");
        assertTrue(service.login("带空白的口令".toCharArray(), null, "1.2.3.4").success(),
                "规整后的口令本身要照常可用");
    }

    @Test
    @DisplayName("键用宽松写法时记一句找不到的 warn，不写回也不说成被改过")
    void relaxedKeyPlaintextGetsWarnInsteadOfChangedInfo() throws IOException {
        // configUi 这种驼峰写法起动时照常绑上，但写回按标准写法在文件里取不到这一项，
        // 明文永远换不成哈希：这得让使用者看出来，而不是说成「候命期间被改过」
        Files.writeString(config, """
                novabot:
                  core:
                    configUi:
                      enabled: true
                      auth:
                        password: 宽松键的口令
                """, StandardCharsets.UTF_8);

        Logger logger = (Logger) LoggerFactory.getLogger(ConfigUiAuthService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service("宽松键的口令");
        } finally {
            logger.detachAppender(appender);
        }

        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        List<String> warns = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertTrue(warns.stream().anyMatch(line -> line.contains("按标准写法找不到这一项")),
                "按标准写法找不到时应有一句 warn 说明这次没换成哈希, 实有: " + messages);
        assertTrue(messages.stream().noneMatch(line -> line.contains("候命期间被改过")),
                "没被改过，不该说成候命期间被改过, 实有: " + messages);
        assertEquals("宽松键的口令", fileService.read().get("novabot.core.configUi.auth.password"),
                "宽松写法那一行要原样留着");
        assertNull(stored(), "标准写法那一项不该多出来（没写回）");
    }

    @Test
    @DisplayName("写不进配置文件时也要能正常登录")
    void survivesUnwritableConfig() throws IOException {
        Files.delete(config);

        NovaCoreProperties.ConfigUi.Auth properties = new NovaCoreProperties.ConfigUi.Auth();
        properties.setPassword("我的口令");
        properties.setTotp(false);

        // 落盘失败只是「文件里还留着明文」，不该连登录都不让用
        ConfigUiAuthService service = new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), fileService);
        service.start();

        assertTrue(service.login("我的口令".toCharArray(), null, "1.2.3.4").success());
    }
}
