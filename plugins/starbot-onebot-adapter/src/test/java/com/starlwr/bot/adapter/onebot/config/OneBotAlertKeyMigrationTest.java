package com.starlwr.bot.adapter.onebot.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.starlwr.bot.adapter.onebot.alert.QqAlertChannel;
import com.starlwr.bot.core.config.ui.ConfigurationGroups;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.properties.NovaBotPrefixes;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 告警三键从核心迁到适配器之后的兼容、覆盖与申报
 */
@DisplayName("告警三键迁适配器")
class OneBotAlertKeyMigrationTest {
    private static final String CURRENT_PLATFORM = "novabot.adapter.onebot.alert.platform";

    private static final String CURRENT_TYPE = "novabot.adapter.onebot.alert.type";

    private static final String CURRENT_NUM = "novabot.adapter.onebot.alert.num";

    private static final String LEGACY_PLATFORM = "starbot.core.alert.qq-platform";

    private static final String LEGACY_TYPE = "starbot.core.alert.qq-type";

    private static final String LEGACY_NUM = "starbot.core.alert.qq-num";

    @Test
    @DisplayName("只写旧键仍可用")
    void legacyKeysStillWork() {
        List<String> red = new ArrayList<>();
        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(LEGACY_PLATFORM, "qq-onebot");
        environment.setProperty(LEGACY_TYPE, "1");
        environment.setProperty(LEGACY_NUM, "12345");

        OneBotAlertPropertiesBinder.apply(environment, properties.getAlert());
        QqAlertChannel channel = new QqAlertChannel(properties, mock(StarBotMessageSender.class));

        try {
            assertEquals("qq-onebot", properties.getAlert().getPlatform(), "旧平台键应写进现行字段");
            assertEquals(1, properties.getAlert().getType(), "旧类型键应写进现行字段");
            assertEquals(12345L, properties.getAlert().getNum(), "旧号码键应写进现行字段");
        } catch (AssertionError e) {
            red.add("① " + e.getMessage());
        }

        try {
            assertTrue(channel.isAvailable(), "只写旧键时通道应可用");
        } catch (AssertionError e) {
            red.add("② " + e.getMessage());
        }

        try {
            StarBotMessageSender sender = mock(StarBotMessageSender.class);
            new QqAlertChannel(properties, sender).send("标题", "正文");
            ArgumentCaptor<Message> captured = ArgumentCaptor.forClass(Message.class);
            verify(sender, atLeastOnce()).send(captured.capture());
            Message message = captured.getValue();
            assertEquals("qq-onebot", message.getPlatform());
            assertEquals(PushTargetType.GROUP, message.getType());
            assertEquals(12345L, message.getNum());
        } catch (AssertionError e) {
            red.add("③ " + e.getMessage());
        }

        if (!red.isEmpty()) {
            fail("只写旧键仍可用三问中 " + red.size() + " 问未销: " + String.join("; ", red));
        }
    }

    @Test
    @DisplayName("新键压旧键")
    void currentKeysOverrideLegacy() {
        List<String> red = new ArrayList<>();

        try {
            OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty(LEGACY_PLATFORM, "old-platform");
            environment.setProperty(LEGACY_TYPE, "0");
            environment.setProperty(LEGACY_NUM, "111");
            environment.setProperty(CURRENT_PLATFORM, "new-platform");
            environment.setProperty(CURRENT_TYPE, "1");
            environment.setProperty(CURRENT_NUM, "222");
            OneBotAlertPropertiesBinder.apply(environment, properties.getAlert());
            assertEquals("new-platform", properties.getAlert().getPlatform(), "两套都写时平台取新键");
            assertEquals(1, properties.getAlert().getType(), "两套都写时类型取新键");
            assertEquals(222L, properties.getAlert().getNum(), "两套都写时号码取新键");
        } catch (AssertionError e) {
            red.add("① " + e.getMessage());
        }

        try {
            OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty(LEGACY_PLATFORM, "legacy-only");
            environment.setProperty(LEGACY_TYPE, "1");
            environment.setProperty(LEGACY_NUM, "333");
            environment.setProperty(CURRENT_PLATFORM, "current-platform");
            OneBotAlertPropertiesBinder.apply(environment, properties.getAlert());
            assertEquals("current-platform", properties.getAlert().getPlatform(), "新键在场的项取新键");
            assertEquals(1, properties.getAlert().getType(), "新键没写到的类型应保留旧键");
            assertEquals(333L, properties.getAlert().getNum(), "新键没写到的号码应保留旧键");
        } catch (AssertionError e) {
            red.add("② " + e.getMessage());
        }

        try {
            OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
            MockEnvironment environment = new MockEnvironment();
            OneBotAlertPropertiesBinder.apply(environment, properties.getAlert());
            assertEquals("", properties.getAlert().getPlatform(), "都没写时平台保持默认空串");
            assertEquals(0, properties.getAlert().getType(), "都没写时类型保持默认 0");
            assertEquals(null, properties.getAlert().getNum(), "都没写时号码保持默认空");
        } catch (AssertionError e) {
            red.add("③ " + e.getMessage());
        }

        if (!red.isEmpty()) {
            fail("新键压旧键三问中 " + red.size() + " 问未销: " + String.join("; ", red));
        }
    }

    @Test
    @DisplayName("旧键在场只 warn 一次")
    void legacyKeysWarnOnce() {
        List<String> red = new ArrayList<>();
        Logger logger = (Logger) LoggerFactory.getLogger(OneBotAlertPropertiesBinder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            try {
                OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
                MockEnvironment environment = new MockEnvironment();
                environment.setProperty(LEGACY_PLATFORM, "qq-onebot");
                environment.setProperty(LEGACY_NUM, "10001");
                OneBotAlertPropertiesBinder.apply(environment, properties.getAlert());
                assertEquals(1, warnCount(appender),
                        "旧键在场启动时应 warn 一次, 实际=" + warnMessages(appender));
            } catch (AssertionError e) {
                red.add("① " + e.getMessage());
            }

            try {
                OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
                properties.getAlert().setPlatform("qq-onebot");
                properties.getAlert().setType(0);
                properties.getAlert().setNum(10001L);
                QqAlertChannel channel = new QqAlertChannel(properties, mock(StarBotMessageSender.class));
                channel.isAvailable();
                channel.isAvailable();
                channel.send("标题", "正文");
                assertEquals(1, warnCount(appender),
                        "发送与可用性重读不得再刷 warn, 实际=" + warnMessages(appender));
            } catch (AssertionError e) {
                red.add("② " + e.getMessage());
            }

            try {
                int before = warnCount(appender);
                OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
                MockEnvironment environment = new MockEnvironment();
                environment.setProperty(CURRENT_PLATFORM, "qq-onebot");
                environment.setProperty(CURRENT_NUM, "10001");
                OneBotAlertPropertiesBinder.apply(environment, properties.getAlert());
                assertEquals(before, warnCount(appender),
                        "只写新键不应再 warn, 实际=" + warnMessages(appender));
            } catch (AssertionError e) {
                red.add("③ " + e.getMessage());
            }
        } finally {
            logger.detachAppender(appender);
        }

        if (!red.isEmpty()) {
            fail("旧键在场只 warn 一次三问中 " + red.size() + " 问未销: " + String.join("; ", red));
        }
    }

    @Test
    @DisplayName("适配器申报三条别名与三条应用器")
    void adapterDeclaresThreeAliasesAndThreeAppliers() {
        List<String> red = new ArrayList<>();

        try {
            Map<String, String> renamed = new OneBotConfigurationKeyAliases().renamed();
            assertEquals(LEGACY_PLATFORM, renamed.get(CURRENT_PLATFORM), "告警 platform 别名");
            assertEquals(LEGACY_TYPE, renamed.get(CURRENT_TYPE), "告警 type 别名");
            assertEquals(LEGACY_NUM, renamed.get(CURRENT_NUM), "告警 num 别名");
            assertEquals(NovaBotPrefixes.ADAPTER_LEGACY, renamed.get(NovaBotPrefixes.ADAPTER),
                    "产品前缀上一档");
            assertEquals(8, renamed.size(), "须申报告警三条加代登录四条再加产品前缀一条");
        } catch (AssertionError e) {
            red.add("① " + e.getMessage());
        }

        try {
            OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
            OneBotRuntimeAppliers appliers = new OneBotRuntimeAppliers(properties);
            assertEquals(List.of(CURRENT_PLATFORM, CURRENT_TYPE, CURRENT_NUM),
                    List.copyOf(appliers.appliers().keySet()), "须申报三条应用器且顺序为 platform／type／num");
            appliers.appliers().get(CURRENT_PLATFORM).accept("onebot");
            appliers.appliers().get(CURRENT_TYPE).accept("1");
            appliers.appliers().get(CURRENT_NUM).accept("10001");
            assertEquals("onebot", properties.getAlert().getPlatform());
            assertEquals(1, properties.getAlert().getType());
            assertEquals(10001L, properties.getAlert().getNum());
            appliers.appliers().get(CURRENT_NUM).accept("");
            assertEquals(null, properties.getAlert().getNum(), "号码留空应写成空而不是把空串塞进数字");
        } catch (AssertionError e) {
            red.add("② " + e.getMessage());
        }

        try {
            Map<String, ConfigurationGroups.Group> prefixes = new OneBotConfigurationGroups().prefixes();
            assertEquals(9, prefixes.size(), "申报须恰 9 条");
            assertEquals(ConfigurationGroups.ALERT, prefixes.get("novabot.adapter.onebot.alert"),
                    "alert 前缀须落告警组");
        } catch (AssertionError e) {
            red.add("③ " + e.getMessage());
        }

        if (!red.isEmpty()) {
            fail("适配器申报三问中 " + red.size() + " 问未销: " + String.join("; ", red));
        }
    }

    private static int warnCount(ListAppender<ILoggingEvent> appender) {
        return (int) appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
    }

    private static List<String> warnMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
