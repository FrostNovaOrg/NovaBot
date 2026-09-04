package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.ConfigEffect;
import com.starlwr.bot.core.config.EventStreamProperties;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生效时机解析测试
 * <p>
 * 「字段上标了」与「界面拿得到」是两件事：标注在源码里，而界面读的是运行期反射的结果。
 * 中间隔着一次 bean 扫描，扫不到的那个配置类<b>标了也等于没标</b>——
 * 而它的表现是界面上那一项按需重启显示，与「本来就要重启」一模一样。
 */
@DisplayName("配置项生效时机解析")
class ConfigurationEffectResolverTest {
    /**
     * 起一个只含配置类的上下文
     * <p>
     * 事件输出那一节要单独登记：它的绑定不走绑定根，是自己一个 bean
     * （见 {@code NovaEventStreamConfiguration}）。少登记它的话，
     * 这把尺看到的字段表就少了四项，而它不会因此报红。
     */
    private AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(StarBotCoreProperties.class, EventStreamProperties.class);
        context.refresh();
        return context;
    }

    @Test
    @DisplayName("⚠️ 字段表里的每一项都拿得到生效时机，界面不必替谁编一个")
    void everyVisibleFieldHasAnEffect() {
        try (AnnotationConfigApplicationContext context = context()) {
            Map<String, ConfigEffect.Effect> effects = new ConfigurationEffectResolver(context).getEffects();

            List<String> fields = new ConfigurationMetadataService().getFields().stream()
                    .map(ConfigurationMetadataService.ConfigurationField::name)
                    .toList();

            assertFalse(fields.isEmpty(), "字段表是空的，这把尺量的是空集");

            List<String> missing = fields.stream().filter(name -> !effects.containsKey(name)).toList();
            assertTrue(missing.isEmpty(), "字段表共 " + fields.size() + " 项，以下 " + missing.size()
                    + " 项在运行期读不到生效时机:\n  " + String.join("\n  ", missing));
        }
    }

    @Test
    @DisplayName("即时生效的项不进待重启名单，其余的都进")
    void restartRequiredKeepsOnlyTheOnesThatNeedIt() {
        try (AnnotationConfigApplicationContext context = context()) {
            ConfigurationEffectResolver resolver = new ConfigurationEffectResolver(context);

            assertEquals(List.of("starbot.core.alert.convergence-interval"),
                    resolver.restartRequired(List.of(
                            "starbot.core.push.enabled",
                            "starbot.core.alert.convergence-interval")));
        }
    }

    @Test
    @DisplayName("⚠️ 累计存储那四项标的是即时生效，界面不再白让人重启一次")
    void redisConnectionKeysApplyImmediately() {
        try (AnnotationConfigApplicationContext context = context()) {
            Map<String, ConfigEffect.Effect> effects = new ConfigurationEffectResolver(context).getEffects();

            // 这四项没有字段可标 @ConfigEffect，声明写在 ExternalConfigurationFields 那张表里，
            // 因此它们的标注只有走这条运行期的路才量得到
            for (String key : List.of("spring.data.redis.host", "spring.data.redis.port",
                    "spring.data.redis.password", "spring.data.redis.database")) {
                assertEquals(ConfigEffect.Effect.IMMEDIATE, effects.get(key), key);
            }

            // 阴性对照：同一张表里的 SMTP 发件服务没接进那条路，标的仍是重启。
            // 少了这一条，「整张表一律 IMMEDIATE」也会让上面四条全绿
            assertEquals(ConfigEffect.Effect.RESTART, effects.get("spring.mail.host"));
            assertEquals(List.of("spring.mail.host"),
                    new ConfigurationEffectResolver(context).restartRequired(List.of(
                            "spring.data.redis.host",
                            "spring.data.redis.port",
                            "spring.data.redis.password",
                            "spring.data.redis.database",
                            "spring.mail.host")));
        }
    }

    @Test
    @DisplayName("二次验证开关标的是即时生效，界面不再说需重启")
    void totpSwitchAppliesImmediately() {
        try (AnnotationConfigApplicationContext context = context()) {
            Map<String, ConfigEffect.Effect> effects = new ConfigurationEffectResolver(context).getEffects();

            assertEquals(ConfigEffect.Effect.IMMEDIATE, effects.get("starbot.core.config-ui.auth.totp"),
                    "开关当场生效，标成需重启会让界面与行为各说各话");
            assertEquals(List.of(),
                    new ConfigurationEffectResolver(context)
                            .restartRequired(List.of("starbot.core.config-ui.auth.totp")),
                    "这一项不该进待重启名单");
            // 阴性：密钥仍需重启。少了这一条，「整张登录组一律 IMMEDIATE」也会让上面那格绿
            assertEquals(ConfigEffect.Effect.RESTART, effects.get("starbot.core.config-ui.auth.totp-secret"));
        }
    }

    @Test
    @DisplayName("没标过的项按需重启处理，而不是当成即时生效")
    void unknownKeysCountAsRestartRequired() {
        try (AnnotationConfigApplicationContext context = context()) {
            ConfigurationEffectResolver resolver = new ConfigurationEffectResolver(context);

            // 插件是运行期装进来的，构建期那道判据管不到它们；这里走的正是那条路
            assertEquals(List.of("starbot.plugin.never.declared"),
                    resolver.restartRequired(List.of("starbot.plugin.never.declared")));
        }
    }
}
