package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.ConfigEffect;
import com.starlwr.bot.core.config.ConfigLevel;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
