package org.frostnova.nova.core.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已撤销配置项提醒测试
 * <p>
 * 这条提醒是撤项时唯一响的东西：老配置文件里那一行不报错、不生效，
 * 没有它，使用者会一直以为自己还配着。所以「在该响的时候响、不该响时不响」
 * 本身就得有人量——一条永远不响的告警与没写它没有区别。
 */
@DisplayName("已撤销配置项提醒")
class RetiredConfigurationKeyCheckTest {
    private static final String RETIRED_KEY = "starbot.core.command.prefix";

    private Logger logger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(RetiredConfigurationKeyCheck.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("配置文件里还留着撤销掉的键时，报一行并说清现在怎么办")
    void warnsOnceForRetiredKey() {
        new RetiredConfigurationKeyCheck(new MockEnvironment().withProperty(RETIRED_KEY, "/")).check();

        List<String> warnings = warnings();
        assertEquals(1, warnings.size(), "应当只报一行, 实际: " + warnings);
        assertTrue(warnings.get(0).contains(RETIRED_KEY), warnings.get(0));
        // 只说「已废弃」等于没说：看见的人还得去翻更新日志才知道下一步该做什么
        assertTrue(warnings.get(0).contains("@"), "得写清现在靠什么触发命令: " + warnings.get(0));
    }

    @Test
    @DisplayName("键留空（形如「prefix:」）时同样要报——空串也是「这一行还在」")
    void warnsWhenRetiredKeyIsBlank() {
        new RetiredConfigurationKeyCheck(new MockEnvironment().withProperty(RETIRED_KEY, "")).check();

        assertEquals(1, warnings().size());
    }

    @Test
    @DisplayName("配置文件里没有这些键时一个字都不说")
    void staysQuietWhenNothingRetiredIsConfigured() {
        new RetiredConfigurationKeyCheck(new MockEnvironment()).check();

        assertEquals(List.of(), warnings());
    }

    @Test
    @DisplayName("配置还写在 5.4 以前的旧根键 starbot: 下时报一行：说清新根键、整棵没被读、要手工改，不贴值")
    void warnsOnceForLegacyRootTree() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("application.yml", Map.of(
                "starbot.adapter.onebot.senders[0].name", "legacy-sender-value",
                "starbot.core.push.quiet-start", "legacy-flag-value")));

        new RetiredConfigurationKeyCheck(environment).check();

        List<String> warnings = warnings();
        assertEquals(1, warnings.size(), "应当只报一行, 实际: " + warnings);
        String line = warnings.get(0);
        for (String part : List.of("2 项", "starbot:", "5.4", "novabot:", "没有被读取", "手工")) {
            assertTrue(line.contains(part), "缺「" + part + "」: " + line);
        }
        assertFalse(line.contains("legacy-sender-value") || line.contains("legacy-flag-value"), "不得贴出配置值: " + line);
        assertFalse(line.contains("口令"), "旧树里没有口令类设置时不提口令: " + line);
    }

    @Test
    @DisplayName("旧根键写在哪一层都认：命令行参数与排在后面的配置文件都算，两层同名只算一项")
    void countsLegacyRootAcrossEverySource() {
        MockEnvironment environment = new MockEnvironment().withProperty("novabot.core.push.quiet-start", "true");
        environment.getPropertySources().addFirst(
                new SimpleCommandLinePropertySource("--starbot.core.log.event-log=true"));
        environment.getPropertySources().addLast(new MapPropertySource("application.yml", Map.of(
                "starbot.core.log.event-log", "true",
                "starbot.bilibili.account.anonymous", "true")));

        new RetiredConfigurationKeyCheck(environment).check();

        List<String> warnings = warnings();
        assertEquals(1, warnings.size(), "应当只报一行, 实际: " + warnings);
        assertTrue(warnings.get(0).contains("2 项"), "应数到 2 项: " + warnings.get(0));
    }

    @Test
    @DisplayName("旧树里留着口令类设置时补一句改完请删掉，不贴值；只有开关时不提")
    void mentionsLeftoverSecretWithoutValue() {
        new RetiredConfigurationKeyCheck(new MockEnvironment()
                .withProperty("starbot.adapter.onebot.senders[0].token", "legacy-secret-value")).check();
        new RetiredConfigurationKeyCheck(new MockEnvironment()
                .withProperty("starbot.core.event-stream.require-token", "true")).check();

        List<String> warnings = warnings();
        assertEquals(2, warnings.size(), "两份配置应各报一行, 实际: " + warnings);
        assertTrue(warnings.get(0).contains("旧位置还留着口令类设置，改完请删掉"), warnings.get(0));
        assertFalse(warnings.get(0).contains("legacy-secret-value"), "不得贴出口令值: " + warnings.get(0));
        assertFalse(warnings.get(1).contains("口令"), "只有开关时不提口令: " + warnings.get(1));
    }

    @Test
    @DisplayName("阴性：只有新根键 novabot: 时一个字都不说")
    void staysQuietWithCurrentRootOnly() {
        new RetiredConfigurationKeyCheck(new MockEnvironment()
                .withProperty("novabot.adapter.onebot.senders[0].name", "bot-a")
                .withProperty("novabot.core.push.quiet-start", "true")).check();

        assertEquals(List.of(), warnings());
    }

    @Test
    @DisplayName("阴性：键名带 starbot 但不以 starbot. 开头时不出声，环境变量形 STARBOT_ 也不认")
    void staysQuietWhenStarbotIsNotTheRoot() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("novabot.core.starbot.note", "x")
                .withProperty("starbot-legacy.core.push", "x")
                .withProperty("mystarbot.core.push", "x")
                .withProperty("starbot", "");
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("STARBOT_CORE_PUSH_QUIET_START", "true")));

        new RetiredConfigurationKeyCheck(environment).check();

        assertEquals(List.of(), warnings());
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
