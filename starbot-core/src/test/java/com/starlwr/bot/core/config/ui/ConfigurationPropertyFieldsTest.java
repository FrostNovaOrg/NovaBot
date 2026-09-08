package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.properties.ConfigEffect;
import com.starlwr.bot.core.config.ConfigLevel;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 配置字段扫描测试
 * <p>
 * 「哪个键对应哪个字段」这条规则被两把尺共用：重要程度与生效时机都从它给出的字段上读注解。
 * 它此前没有任何测试——而它坏掉的样子是<b>界面上什么都不报</b>：重要程度整列变成「高级」，
 * 生效时机整列变成 null，两者看起来都像「还没人标」，没有一处会说是扫描漏了。
 */
@DisplayName("配置字段扫描")
class ConfigurationPropertyFieldsTest {
    /**
     * 扫一遍绑定根
     * @return 配置项名到字段
     */
    private Map<String, Field> scan() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(StarBotCoreProperties.class)) {
            return ConfigurationPropertyFields.scan(context);
        }
    }

    @Test
    @DisplayName("驼峰字段名按短横线拼成键名，与配置文件里的写法一致")
    void namesFieldsInKebabCase() {
        assertEquals("quiet-start", ConfigurationPropertyFields.toKebab("quietStart"));
        assertEquals("at-all-daily-limit", ConfigurationPropertyFields.toKebab("atAllDailyLimit"));
        assertEquals("enabled", ConfigurationPropertyFields.toKebab("enabled"));
    }

    @Test
    @DisplayName("嵌套配置类逐层展开，键名带上完整前缀")
    void expandsNestedConfigurationClasses() {
        Map<String, Field> fields = scan();

        assertTrue(fields.containsKey("starbot.core.push.enabled"), "一层嵌套应展开: " + fields.keySet());
        assertTrue(fields.containsKey("starbot.core.config-ui.auth.password"), "两层嵌套应展开");
        // 单独成件、随事件源迁到核心模块的那几节同样要能扫到，否则它们的标注一个也读不到
        assertTrue(fields.containsKey("starbot.core.log.network-log-suppress-window"), "外部件里的那几节应展开");
    }

    @Test
    @DisplayName("扫到的是字段本身，注解从它上面读得出来")
    void yieldsAnnotatableFields() {
        Map<String, Field> fields = scan();

        Field pushEnabled = fields.get("starbot.core.push.enabled");
        assertNotNull(pushEnabled);
        assertEquals(ConfigEffect.Effect.IMMEDIATE, pushEnabled.getAnnotation(ConfigEffect.class).value());
        assertEquals(ConfigLevel.Level.COMMON, pushEnabled.getAnnotation(ConfigLevel.class).value());
    }

    @Test
    @DisplayName("列表元素内部的字段不算配置项：它们没有自己的一级键路径")
    void stopsAtCollections() {
        Map<String, Field> fields = scan();

        assertTrue(fields.containsKey("starbot.core.exec.rules"), "列表本身是配置项");
        assertNull(fields.get("starbot.core.exec.rules.event"), "列表元素内部的字段不该有键路径");
    }

    @Test
    @DisplayName("超过嵌套上限的字段不再展开：上限那层在、再深一层不在")
    void stopsExpandingBeyondNestingLimit() {
        int reds = 0;
        List<String> failures = new ArrayList<>();

        // 问零（常量卫）：上限在本格里写死 6，锚在 ConfigurationPropertyFields 的 MAX_DEPTH（:34）上；
        // 主码哪天改了上限，这一问先红，免得这格跟着新上限静默换锚
        try {
            Field maxDepth = ConfigurationPropertyFields.class.getDeclaredField("MAX_DEPTH");
            maxDepth.setAccessible(true);
            assertEquals(6, maxDepth.getInt(null), "MAX_DEPTH 应仍为 6，本格的层数锚在它上面");
        } catch (AssertionError e) {
            reds++;
            failures.add("问零: " + e.getMessage());
        } catch (ReflectiveOperationException e) {
            reds++;
            failures.add("问零: 读不到 MAX_DEPTH: " + e);
        }

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(L1.class)) {
            Map<String, Field> fields = ConfigurationPropertyFields.scan(context);
            Map<String, Object> values = ConfigurationPropertyFields.values(List.of(context.getBean(L1.class)));

            // ① 阳性锚：深度 6（＝上限）那层的 leaf 要在——键名即 depth 后接 6 个 next 再接 leaf
            try {
                assertTrue(fields.containsKey(leafKey(7)),
                        () -> "深度 6（最后一层被展开）的 leaf 键 " + leafKey(7) + " 应在 fields 里，实际: " + fields.keySet());
            } catch (AssertionError e) {
                reds++;
                failures.add("①: " + e.getMessage());
            }

            // ② 深度 7（＝上限＋1）那层的 leaf 不在：上限不止住，嵌套就会一层层铺下去
            try {
                assertFalse(fields.containsKey(leafKey(8)),
                        () -> "深度 7（上限＋1）的 leaf 键 " + leafKey(8) + " 不应在 fields 里，实际: " + fields.keySet());
            } catch (AssertionError e) {
                reds++;
                failures.add("②: " + e.getMessage());
            }

            // ③ 同一条链、同一个实例走 values（walkValues 那条）：≤上限在、＋1 不在
            try {
                assertTrue(values.containsKey(leafKey(7)),
                        () -> "values 里同样应有深度 6 的 leaf 键 " + leafKey(7) + "，实际: " + values.keySet());
            } catch (AssertionError e) {
                reds++;
                failures.add("③甲: " + e.getMessage());
            }
            try {
                assertFalse(values.containsKey(leafKey(8)),
                        () -> "values 里同样不应有深度 7 的 leaf 键 " + leafKey(8) + "，实际: " + values.keySet());
            } catch (AssertionError e) {
                reds++;
                failures.add("③乙: " + e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            fail("红 " + reds + " 格: " + String.join("; ", failures));
        }
    }

    /**
     * 嵌套链夹具：L1 是标了 {@code @ConfigurationProperties} 的根，L{d} 在 walk 里走到深度 d-1。
     * 写死 MAX_DEPTH＝6 时 L7（深度 6）是最后一层被展开、L8（深度 7）不再展开。
     * 终层 L8 不设 next——真把它接成自引用，上限一旦失守，红的样子会是栈溢出而不是断言红。
     */
    @ConfigurationProperties(prefix = "depth")
    static class L1 {
        private L2 next = new L2();
        private String leaf = "l1";
    }

    static class L2 {
        private L3 next = new L3();
        private String leaf = "l2";
    }

    static class L3 {
        private L4 next = new L4();
        private String leaf = "l3";
    }

    static class L4 {
        private L5 next = new L5();
        private String leaf = "l4";
    }

    static class L5 {
        private L6 next = new L6();
        private String leaf = "l5";
    }

    static class L6 {
        private L7 next = new L7();
        private String leaf = "l6";
    }

    static class L7 {
        private L8 next = new L8();
        private String leaf = "l7";
    }

    static class L8 {
        private String leaf = "l8";
    }

    /**
     * 第 level 层的 leaf 键名：L{d} 的键名＝前缀 depth 后接 d-1 个 next
     */
    private static String leafKey(int level) {
        return "depth" + ".next".repeat(level - 1) + ".leaf";
    }
}
