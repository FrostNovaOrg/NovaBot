package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.core.config.ui.ConfigurationGroups;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 告警三键从核心迁到适配器之后的申报
 */
@DisplayName("告警三键迁适配器")
class OneBotAlertKeyMigrationTest {
    private static final String CURRENT_PLATFORM = "novabot.adapter.onebot.alert.platform";

    private static final String CURRENT_TYPE = "novabot.adapter.onebot.alert.type";

    private static final String CURRENT_NUM = "novabot.adapter.onebot.alert.num";

    @Test
    @DisplayName("适配器申报三条应用器与九条分组")
    void adapterDeclaresThreeAliasesAndThreeAppliers() {
        List<String> red = new ArrayList<>();

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
            fail("适配器申报两问中 " + red.size() + " 问未销: " + String.join("; ", red));
        }
    }

    @Test
    @DisplayName("不经 EPP 只写旧 alert 键不绑")
    void oldAlertKeysDoNotBindWithoutEpp() {
        List<String> red = new ArrayList<>();
        MockEnvironment environment = new MockEnvironment();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("starbot.adapter.onebot.alert.platform", "qq-onebot");
        values.put("starbot.adapter.onebot.alert.type", "1");
        values.put("starbot.adapter.onebot.alert.num", "12345");
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", values));
        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        Binder.get(environment).bind(
                OneBotAdapterPluginProperties.class.getAnnotation(ConfigurationProperties.class).prefix(),
                Bindable.ofInstance(properties));

        try {
            assertEquals("", properties.getAlert().getPlatform(),
                    "旧 platform 键不应写入, 实际=" + properties.getAlert().getPlatform());
        } catch (AssertionError e) {
            red.add("① " + e.getMessage());
        }
        try {
            assertEquals(0, properties.getAlert().getType(),
                    "旧 type 键不应写入, 实际=" + properties.getAlert().getType());
        } catch (AssertionError e) {
            red.add("② " + e.getMessage());
        }
        try {
            assertEquals(null, properties.getAlert().getNum(),
                    "旧 num 键不应写入, 实际=" + properties.getAlert().getNum());
        } catch (AssertionError e) {
            red.add("③ " + e.getMessage());
        }

        if (!red.isEmpty()) {
            fail("不经 EPP 只写旧 alert 键不绑三问中 " + red.size() + " 问未销: "
                    + String.join("; ", red));
        }
    }
}
