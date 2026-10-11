package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.napcat.NapCatCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * token 过门写回前的比对：与起动时同样的规整、按标准写法找不到时的提醒
 * <p>
 * 手写配置常是 token: " xxx " 这种带引号、首尾留空白的写法，起动那一路读到的值带着空白、
 * 规整（去首尾空白）之后才当 token 用；写回前的核对若拿规整过的明文与文件里的原值逐字比，
 * 永远对不上，明文就一直留在文件里。键用了宽松写法（如 napCat）时，写回按标准写法在
 * 文件里取不到这一项，明文同样换不成哈希——这要让使用者看出来，而不是说成「候命期间被改过」。
 * <p>
 * 与 {@link NapCatTokenBackupTest} 同包：文件服务的构造器只在这个包里可见，
 * 台架因此走真文件服务，量的是盘上的文件与日志，不是桩记下的调用。
 */
@DisplayName("token 写回前的比对与宽松写法提醒")
class NapCatTokenWriteBackComparisonTest {

    /** 文件里写的、起动时绑上的那份带首尾空白的明文（引号在读文件那一路会去掉） */
    private static final String SPACED_TOKEN = " 带空白的令牌 555 ";

    /** 宽松写法（驼峰段名）在文件里的字面键 */
    private static final String RELAXED_KEY = "novabot.adapter.onebot.napCat.token";

    private static final String RELAXED_TOKEN = "宽松键的令牌 111";

    @TempDir
    Path dir;

    private Path config;

    private ConfigurationFileService fileService;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        fileService = new ConfigurationFileService(config);
    }

    private StandardEnvironment fileEnvironment() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("mainConfiguration", new FileSystemResource(config))
                .forEach(environment.getPropertySources()::addLast);
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    private NapCatCredentialService service(String token) throws IOException {
        OneBotAdapterPluginProperties.NapCat properties = new OneBotAdapterPluginProperties.NapCat();
        properties.setToken(token);
        properties.setTokenHash("");
        properties.setTotpSecret("");
        properties.setAddress("http://127.0.0.1:6099");
        NapCatCredentialService service = new NapCatCredentialService(properties, fileService, mock(RestTemplate.class));
        service.setEnvironment(fileEnvironment());
        return service;
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "old-file-token-392", "external-token-842"})
    @DisplayName("环境变量令牌启动时不提示改写法或候命期间被改过，也不改配置文件")
    void environmentTokenDoesNotWarnOrWriteBack(String fileToken) throws IOException {
        // 用户故障：从环境变量给令牌时，启动日志不得叫人改写法，也不得说「候命期间被改过」，配置文件不得被改动。
        String token = "external-token-842";
        Files.writeString(config, fileToken.isEmpty() ? "novabot: {}\n" : """
                novabot:
                  adapter:
                    onebot:
                      napcat:
                        token: %s
                """.formatted(fileToken), StandardCharsets.UTF_8);
        String before = Files.readString(config, StandardCharsets.UTF_8);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("NOVABOT_ADAPTER_ONEBOT_NAPCAT_TOKEN", token)));
        new YamlPropertySourceLoader().load("mainConfiguration", new FileSystemResource(config))
                .forEach(environment.getPropertySources()::addLast);
        OneBotAdapterPluginProperties.NapCat properties = new Binder(ConfigurationPropertySources.get(environment))
                .bind("novabot.adapter.onebot.napcat", OneBotAdapterPluginProperties.NapCat.class).get();

        Logger logger = (Logger) LoggerFactory.getLogger(NapCatCredentialService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.setEnvironment(environment);
            context.registerBean(NapCatCredentialService.class,
                    () -> new NapCatCredentialService(properties, fileService, mock(RestTemplate.class)));
            context.refresh();
            assertTrue(context.getBean(NapCatCredentialService.class).isConfigured());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertAll(
                () -> assertTrue(messages.stream().noneMatch(line -> line.contains("按标准写法找不到")),
                        "环境变量令牌不应提示改写法"),
                () -> assertTrue(messages.stream().noneMatch(line -> line.contains("候命期间被改过")),
                        "环境变量令牌不应说成候命期间被改过"),
                () -> assertEquals(before, Files.readString(config, StandardCharsets.UTF_8)),
                () -> assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().contains("NapCat 的 token 来自环境变量或命令行")
                        && event.getFormattedMessage().contains("不写回配置文件"))),
                () -> assertTrue(messages.stream().noneMatch(line -> line.contains(token)
                        || line.matches(".*[0-9a-f]{64}.*") || (!fileToken.isEmpty() && line.contains(fileToken))),
                        "日志不得包含令牌或哈希"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"commandLine", "unknown", "otherFile", "empty", "opaque", "null", "broken"})
    @DisplayName("命令行及无法确认的来源不写回，也不泄露令牌或误报文件被改过")
    void externalOrUnknownSourceDoesNotWriteBack(String sourceKind) throws IOException {
        // 用户故障：外部令牌或来源认不准时，启动日志不得叫人改写法，也不得说「候命期间被改过」，配置文件不得被改动。
        String token = "external-token-842";
        Files.writeString(config, """
                novabot:
                  adapter:
                    onebot:
                      napcat:
                        token: external-token-842
                """, StandardCharsets.UTF_8);
        String before = Files.readString(config, StandardCharsets.UTF_8);
        StandardEnvironment configurable = fileEnvironment();
        switch (sourceKind) {
            case "commandLine" -> configurable.getPropertySources().addFirst(
                    new SimpleCommandLinePropertySource("--" + NapCatCredentialService.TOKEN_PROPERTY + "=" + token));
            case "unknown" -> configurable.getPropertySources().addFirst(new MapPropertySource("unknownSource",
                    Map.of(NapCatCredentialService.TOKEN_PROPERTY, token)));
            case "otherFile" -> {
                Path other = dir.resolve("other/application.yml");
                Files.createDirectories(other.getParent());
                Files.writeString(other, before, StandardCharsets.UTF_8);
                new YamlPropertySourceLoader().load("otherConfiguration", new FileSystemResource(other))
                        .forEach(configurable.getPropertySources()::addFirst);
            }
            case "empty" -> configurable.getPropertySources().remove("mainConfiguration");
            case "broken" -> configurable.getPropertySources().addFirst(new PropertySource<>("brokenSource") {
                @Override
                public Object getProperty(String name) {
                    throw new IllegalStateException(token);
                }
            });
            default -> { }
        }
        Environment environment = "null".equals(sourceKind) ? null
                : "opaque".equals(sourceKind) ? mock(Environment.class) : configurable;
        NapCatCredentialService service = service(token);
        service.setEnvironment(environment);

        Logger logger = (Logger) LoggerFactory.getLogger(NapCatCredentialService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.start();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8));
        assertEquals(List.of("NapCat 的 token 来自环境变量或命令行等其他配置来源，不写回配置文件"),
                appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertTrue(appender.list.stream().allMatch(event -> event.getLevel() == Level.INFO));
    }

    @Test
    @DisplayName("带首尾空白的引文明文照旧换成哈希，明文清掉")
    void quotedPlaintextWithSpacesIsStillHashedAndCleared() throws IOException {
        Files.writeString(config, """
                novabot:
                  adapter:
                    onebot:
                      napcat:
                        address: http://127.0.0.1:6099
                        token: " 带空白的令牌 555 "
                        token-hash: ""
                """, StandardCharsets.UTF_8);
        NapCatCredentialService service = service(SPACED_TOKEN);

        // 阳性对照：文件里那一项读出来确实带着首尾空白（引号去了、空白留着）。
        // 这一条不成立，后面两句在本件就量不到写回那一侧的毛病
        assertEquals(SPACED_TOKEN, fileService.read().get(NapCatCredentialService.TOKEN_PROPERTY),
                "对照：文件里那一项应原样带着首尾空白");

        service.start();

        String saved = fileService.read().get(NapCatCredentialService.TOKEN_HASH_PROPERTY);
        assertTrue(saved != null && saved.matches("[0-9a-f]{64}"),
                "token-hash 应已换成哈希: " + saved);
        assertFalse(Files.readString(config, StandardCharsets.UTF_8).contains("带空白的令牌 555"),
                "文件里不该再有明文");
    }

    @Test
    @DisplayName("键用宽松写法时记一句找不到的 warn，不写回也不说成被改过")
    void relaxedKeyPlaintextGetsWarnInsteadOfChangedInfo() throws IOException {
        Files.writeString(config, """
                novabot:
                  adapter:
                    onebot:
                      napCat:
                        address: http://127.0.0.1:6099
                        token: 宽松键的令牌 111
                """, StandardCharsets.UTF_8);

        Logger logger = (Logger) LoggerFactory.getLogger(NapCatCredentialService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service(RELAXED_TOKEN).start();
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
        assertEquals(RELAXED_TOKEN, fileService.read().get(RELAXED_KEY),
                "宽松写法那一行要原样留着");
        assertNull(fileService.read().get(NapCatCredentialService.TOKEN_PROPERTY),
                "标准写法那一项不该多出来（没写回）");
        assertFalse(Files.readString(config, StandardCharsets.UTF_8).contains("token-hash"),
                "没写回就不该多出 token-hash 那一行");
    }
}
