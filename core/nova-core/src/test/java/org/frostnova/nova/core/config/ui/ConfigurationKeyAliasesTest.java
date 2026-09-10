package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 控制台读配置时对旧位置的兼容
 * <p>
 * <b>病在「界面读数骗人」，不在功能。</b>配置键改名后，绑定侧可由插件申报同时认得新旧位置；
 * 但控制台是拿配置文件里的扁平键值表去与元数据配对的，只写旧位置的既有部署，
 * 元数据里那几项一个也配不上——界面于是显示<b>默认值</b>，而程序正按旧位置的值在跑。
 * 使用者看到默认值，实际却按旧键在跑，这比功能坏掉更难查。
 * <p>
 * 因此这里的判据不是「绑定侧跑得对」，而是
 * <b>控制台端点回给界面的那份读数，与实际生效的值是同一个</b>，且要说清这一项是从哪里来的。
 * <p>
 * 优先级必须与绑定顺序<b>逐项一致</b>：
 * 现行键写了就用现行键、没写才落回旧位置、两处都没写才是默认值。
 * 核心表已空，本类用虚构贡献者申报的键把这三条语义钉住。
 */
@DisplayName("控制台认旧配置键")
class ConfigurationKeyAliasesTest {
    private static final String CURRENT_PREFIX = "novabot.demo.event-stream";

    private static final String DEMO_LEGACY = "legacy.demo.event-stream";

    /**
     * 只写旧位置的既有部署
     */
    private static final String LEGACY_ONLY = """
            legacy:
              demo:
                event-stream:
                  enabled: true
                  buffer-size: 4000
            novabot:
              core:
                config-ui:
                  enabled: true
            """;

    /**
     * 两处都写，且现行位置只写了其中一项
     */
    private static final String BOTH_PRESENT = """
            legacy:
              demo:
                event-stream:
                  enabled: true
                  buffer-size: 4000
            novabot:
              demo:
                event-stream:
                  enabled: false
            """;

    private static final String CURRENT_KEY = CURRENT_PREFIX + ".enabled";

    private static final String LEGACY_KEY = DEMO_LEGACY + ".enabled";

    private static final ConfigurationKeyAliasContributor DEMO_ALIASES =
            () -> Map.of(CURRENT_PREFIX, DEMO_LEGACY);

    @TempDir
    Path dir;

    private Path config;

    private ConfigUiController controller;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
    }

    /**
     * 按给定内容起一份配置，并接上控制台
     * <p>
     * 构造器只做赋值，读配置这条路只用得到配置文件服务，其余依赖全部给桩——
     * 与本目录下其他控制台用例的做法一致。别名贡献者注入虚构键，核心表为空时仍能量读侧三条语义。
     */
    @SuppressWarnings("unchecked")
    private void start(String yaml) throws IOException {
        Files.writeString(config, yaml, StandardCharsets.UTF_8);

        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        controller = new ConfigUiController(
                mock(ConfigurationMetadataService.class),
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
                mock(ConfigurationLevelResolver.class),
                // 不 mock 这个具体类：内联 mock 要改写它的字节码，clean 构建下实测会抛「could not instrument」。
                // 给个空上下文即可，本组用例不看生效时机
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
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
                emptyProvider(),
                emptyProvider(),
                providerOf(DEMO_ALIASES),
                mock(AlertService.class),
                emptyProvider());
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new NoSuchBeanDefinitionException(Object.class);
            }

            @Override
            public T getObject(Object... args) {
                throw new NoSuchBeanDefinitionException(Object.class);
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }

            @Override
            public Stream<T> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<T> orderedStream() {
                return Stream.empty();
            }
        };
    }

    private static <T> ObjectProvider<T> providerOf(T bean) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return bean;
            }

            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }

            @Override
            public Stream<T> stream() {
                return Stream.of(bean);
            }

            @Override
            public Stream<T> orderedStream() {
                return Stream.of(bean);
            }
        };
    }

    private JSONObject read() {
        JSONObject result = controller.values();
        assertTrue(result.getBooleanValue("success"), "读配置本身不该失败");
        return result;
    }

    @Test
    @DisplayName("只写旧位置时，界面拿到的是旧位置的值，并标出它来自旧位置")
    void legacyOnlyShowsLegacyValueAndMarksSource() throws IOException {
        start(LEGACY_ONLY);

        JSONObject result = read();

        assertEquals("true", result.getJSONObject("values").getString(CURRENT_KEY),
                "端点按旧位置的 true 在跑, 界面就不该显示默认值");
        assertEquals(LEGACY_KEY, result.getJSONObject("legacy").getString(CURRENT_KEY),
                "得说清这一项是从旧位置读来的, 否则使用者不知道该去哪里改");
        assertEquals("4000", result.getJSONObject("values").getString(CURRENT_PREFIX + ".buffer-size"),
                "落回旧位置是逐项的, 不是整段的");
    }

    @Test
    @DisplayName("现行位置写了就以现行位置为准，且不标来源")
    void currentKeyOverridesLegacy() throws IOException {
        start(BOTH_PRESENT);

        JSONObject result = read();

        assertEquals("false", result.getJSONObject("values").getString(CURRENT_KEY),
                "两处都写时以现行位置为准, 与端点的绑定顺序一致");
        assertNull(result.getJSONObject("legacy").getString(CURRENT_KEY),
                "这一项本来就写在现行位置, 不该提示迁移");
        assertEquals("4000", result.getJSONObject("values").getString(CURRENT_PREFIX + ".buffer-size"),
                "现行位置没写到的项仍落回旧位置");
        assertEquals(DEMO_LEGACY + ".buffer-size",
                result.getJSONObject("legacy").getString(CURRENT_PREFIX + ".buffer-size"),
                "落回旧位置的那一项照样要标出来源");
    }

    @Test
    @DisplayName("两处都没写时不编造读数，界面自己落回默认值")
    void neitherWrittenFabricatesNothing() throws IOException {
        start("""
                novabot:
                  core:
                    config-ui:
                      enabled: true
                """);

        JSONObject result = read();

        assertNull(result.getJSONObject("values").getString(CURRENT_KEY),
                "配置文件里没有的项不该凭空多出一个值, 界面按元数据的默认值显示");
        assertTrue(result.getJSONObject("legacy").isEmpty(), "没有落回旧位置的项");
    }

    @Test
    @DisplayName("保存只写现行位置，旧位置原样留着")
    void saveWritesCurrentKeyOnlyAndLeavesLegacyUntouched() throws IOException {
        start(LEGACY_ONLY);

        JSONObject saved = controller.save(Map.of(CURRENT_KEY, "false"));
        assertTrue(saved.getBooleanValue("success"), saved.getString("message"));

        String yaml = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(yaml.lines().anyMatch(line -> line.strip().equals("enabled: false")),
                "现行位置写的是新值: \n" + yaml);
        assertTrue(yaml.lines().anyMatch(line -> line.strip().equals("enabled: true")),
                "旧位置那一行必须原样留着——删它就是替使用者改他的配置文件: \n" + yaml);
        assertTrue(yaml.lines().anyMatch(line -> line.strip().equals("buffer-size: 4000")),
                "旧位置其余各项同样不许动: \n" + yaml);

        start(Files.readString(config, StandardCharsets.UTF_8));
        JSONObject after = read();
        assertEquals("false", after.getJSONObject("values").getString(CURRENT_KEY), "存完再读, 以现行位置为准");
        assertNull(after.getJSONObject("legacy").getString(CURRENT_KEY), "已迁到现行位置, 提示随之消失");
    }

    @Test
    @DisplayName("贡献者申报的旧键同样解析")
    void contributorLegacyKeysResolve() {
        List<String> reds = new ArrayList<>();
        String current = "novabot.demo.alert.platform";
        String legacy = "legacy.demo.alert.qq-platform";
        ConfigurationKeyAliasContributor contributor = () -> {
            Map<String, String> renamed = new LinkedHashMap<>();
            renamed.put(current, legacy);
            return renamed;
        };
        ConfigurationKeyAliases aliases = ConfigurationKeyAliases.of(List.of(contributor));

        try {
            assertEquals(current, aliases.currentName(legacy),
                    "贡献者申报的旧键应换算成现行键");
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(legacy, "onebot");
            Map<String, String> fallbacks = aliases.resolve(values);
            assertEquals("onebot", values.get(current), "只写旧位置时读数应按现行键补上");
            assertEquals(legacy, fallbacks.get(current), "落回旧位置的项应标出来源");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            assertEquals("starbot.bilibili.event-stream.enabled",
                    aliases.currentName("starbot.bilibili.event-stream.enabled"),
                    "核心表为空时合并结果只含贡献者申报");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    @Test
    @DisplayName("逐键映射不吞邻键（同前缀下未申报的键不受影响）")
    void perKeyMappingDoesNotSwallowNeighbors() {
        List<String> reds = new ArrayList<>();
        String current = "novabot.demo.alert.platform";
        String legacy = "legacy.demo.alert.qq-platform";
        String neighbor = "legacy.demo.alert.qq-type";
        String other = "legacy.demo.alert.qq-num";
        ConfigurationKeyAliasContributor contributor = () -> Map.of(current, legacy);
        ConfigurationKeyAliases aliases = ConfigurationKeyAliases.of(List.of(contributor));

        try {
            assertEquals(current, aliases.currentName(legacy), "申报的那一键应解析");
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            assertEquals(neighbor, aliases.currentName(neighbor), "同前缀未申报的邻键应原样返回");
            assertEquals(other, aliases.currentName(other), "另一邻键同样不受影响");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(legacy, "onebot");
            values.put(neighbor, "1");
            aliases.resolve(values);
            assertEquals("onebot", values.get(current), "申报的那一键应补到现行位置");
            assertEquals("1", values.get(neighbor), "邻键应留在原位");
            assertNull(values.get("novabot.demo.alert.type"), "不得凭空给邻键编一个现行键");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    @Test
    @DisplayName("撞键抛")
    void collidingCurrentKeyThrows() {
        List<String> reds = new ArrayList<>();

        try {
            ConfigurationKeyAliasContributor first = () -> Map.of("novabot.demo.a", "legacy.demo.a");
            ConfigurationKeyAliasContributor second = () -> Map.of("novabot.demo.a", "legacy.demo.b");
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> ConfigurationKeyAliases.of(List.of(first, second)),
                    "两贡献者申报同一条现行键须抛");
            assertTrue(ex.getMessage().contains("novabot.demo.a"),
                    "文案应点名撞上的现行键, 实际=" + ex.getMessage());
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            ConfigurationKeyAliasContributor ok = () -> Map.of("novabot.demo.x", "legacy.demo.x");
            ConfigurationKeyAliases merged = ConfigurationKeyAliases.of(List.of(ok));
            assertEquals("novabot.demo.x", merged.currentName("legacy.demo.x"),
                    "未撞的申报应合并进去");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            ConfigurationKeyAliasContributor only = () -> Map.of("novabot.demo.a", "legacy.demo.a");
            assertEquals("novabot.demo.a",
                    ConfigurationKeyAliases.of(List.of(only)).currentName("legacy.demo.a"),
                    "单贡献者未撞时应合并进去");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }
}
