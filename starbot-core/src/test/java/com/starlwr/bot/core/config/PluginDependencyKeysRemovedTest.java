package com.starlwr.bot.core.config;

import com.starlwr.bot.core.config.ui.ConfigurationGroups;
import com.starlwr.bot.core.config.ui.ConfigurationMetadataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件依赖自动下载那一节已从配置面上撤走
 * <p>
 * 自动下载随插件加载器一起退休之后，{@code starbot.core.plugin} 那一节就是一具死码：
 * 它仍出现在设置页上、仍能被写进 {@code application.yml}，而改它不产生任何效果。
 * <b>「界面上有、改了没用」比「界面上没有」更糟</b>——使用者会以为自己配好了。
 * <p>
 * 撤走一节配置涉及三处，删掉任意两处都不会有人报错，因此三处各设一问：
 * <ul>
 *   <li>① <b>绑定根</b>：{@code StarBotCoreProperties} 里没有 {@code Plugin} 这一节。
 *       字段留着的话，编译期生成的元数据会把那两个键原样长回来。</li>
 *   <li>② <b>分组表</b>：{@code ConfigurationGroups} 的前缀表不含那条前缀。留着它是条死前缀，
 *       会让下一个人以为那一段已经归好了组。</li>
 *   <li>③ <b>键全集</b>：使用者看得见的配置键里，那一节一个不剩。</li>
 * </ul>
 * 三问各自捕获、末尾汇总，先红时一次看清还差哪几处；每问各带一个阳性锚，
 * 免得反射／元数据整个取空时三问一起「绿」。
 *
 * @see StarBotCoreProperties
 */
@DisplayName("插件依赖下载配置节已撤走")
class PluginDependencyKeysRemovedTest {
    /**
     * 已撤走的那一节的配置前缀
     */
    private static final String REMOVED_PREFIX = "starbot.core.plugin";

    /**
     * 阳性锚：仍在册的一节与一个键，用来证明本格的三种取值方式当真取得到东西
     */
    private static final String LIVE_NESTED_SECTION = "Paint";

    private static final String LIVE_PREFIX = "starbot.core.push";

    private static final String LIVE_KEY = "starbot.core.paint.fonts";

    @Test
    @DisplayName("绑定根、分组表、键全集三处都不再有 starbot.core.plugin 那一节")
    void removedSectionLeavesNothingBehind() {
        List<String> unresolved = new ArrayList<>();

        // 问①：绑定根上没有 Plugin 这一节
        try {
            List<String> nested = new ArrayList<>();
            List<String> live = new ArrayList<>();
            for (Class<?> type : StarBotCoreProperties.class.getDeclaredClasses()) {
                if ("Plugin".equals(type.getSimpleName())) {
                    nested.add(type.getSimpleName());
                }
                if (LIVE_NESTED_SECTION.equals(type.getSimpleName())) {
                    live.add(type.getSimpleName());
                }
            }
            assertTrue(!live.isEmpty(), "阳性锚：绑定根里该看得见 " + LIVE_NESTED_SECTION + " 这一节，看不见说明本问什么也没量到");
            assertTrue(nested.isEmpty(), "StarBotCoreProperties 里仍留着内部类 Plugin，那两个键会从元数据里长回来");
        } catch (AssertionError e) {
            unresolved.add("问① " + e.getMessage());
        }

        // 问②：分组表里没有那条前缀
        try {
            List<String> prefixes = ConfigurationGroups.core().prefixes();
            assertTrue(prefixes.contains(LIVE_PREFIX), "阳性锚：核心前缀表须含 " + LIVE_PREFIX);
            assertTrue(!prefixes.contains(REMOVED_PREFIX),
                    "分组表里仍留着死前缀 " + REMOVED_PREFIX + "，它已指不到任何配置项");
        } catch (AssertionError e) {
            unresolved.add("问② " + e.getMessage());
        }

        // 问③：使用者看得见的键里那一节一个不剩
        try {
            List<String> names = new ArrayList<>();
            for (ConfigurationMetadataService.ConfigurationField field : new ConfigurationMetadataService().getFields()) {
                names.add(field.name());
            }
            assertTrue(names.contains(LIVE_KEY), "阳性锚：键全集须含 " + LIVE_KEY + "，不含说明元数据整个取空了");

            List<String> left = new ArrayList<>();
            for (String name : names) {
                if (name.equals(REMOVED_PREFIX) || name.startsWith(REMOVED_PREFIX + ".")) {
                    left.add(name);
                }
            }
            assertTrue(left.isEmpty(), "设置页上仍出得来这一节的键: " + String.join("、", left));
        } catch (AssertionError e) {
            unresolved.add("问③ " + e.getMessage());
        }

        assertTrue(unresolved.isEmpty(),
                () -> "三问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    /**
     * 旧配置容忍：删键不许把既有部署拒之门外
     * <p>
     * 判定未知键要不要拦的是 {@code @ConfigurationProperties} 上的
     * {@code ignoreUnknownFields}——Spring 装配时正是照着它挂
     * {@link NoUnboundElementsBindHandler}。本格照搬那一步而不是自己写死一个默认值，
     * <b>为的是让本格的极性跟着注解走</b>：注解改成 {@code false}，这一格当场红。
     */
    @Test
    @DisplayName("旧 application.yml 里留着那两个键，绑定照旧不抛")
    void legacyKeysStillBind() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put(REMOVED_PREFIX + ".auto-download-dependency", "false");
        legacy.put(REMOVED_PREFIX + ".maven-base-urls[0]", "https://a.example");

        ConfigurationProperties annotation = StarBotCoreProperties.class.getAnnotation(ConfigurationProperties.class);
        assertNotNull(annotation, "阳性锚：绑定根须带 @ConfigurationProperties，否则本格量的不是真绑定");

        BindHandler handler = annotation.ignoreUnknownFields()
                ? BindHandler.DEFAULT
                : new NoUnboundElementsBindHandler(BindHandler.DEFAULT, new UnboundElementsSourceFilter());

        Binder binder = new Binder(new MapConfigurationPropertySource(legacy));
        StarBotCoreProperties properties = new StarBotCoreProperties();

        assertDoesNotThrow(() -> binder.bind(annotation.prefix(), Bindable.ofInstance(properties), handler),
                "旧配置里留着已删的两个键就起不来了——使用者升级时看到的是一次启动失败，而不是一条提醒");
    }
}
