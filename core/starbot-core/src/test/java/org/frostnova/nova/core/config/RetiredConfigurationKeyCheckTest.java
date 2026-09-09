package org.frostnova.nova.core.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
