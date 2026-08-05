package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.ConfigLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 界面额外展示的框架配置项
 * <p>
 * 配置界面原则上只展示 {@code starbot.} 命名空间下的配置项，框架自身成千上万的配置
 * 不该淹没使用者。<b>但有几项例外：NovaBot 的功能实实在在依赖它们。</b>
 * <p>
 * 不展示的后果很具体：健康自检提示「累计查询需配置 spring.data.redis.host」，
 * 而使用者到设置页去搜，什么也搜不到——一条指向界面上不存在之物的提示。
 * 邮件告警同理，说明里让人填 SMTP，界面上却没有可填的地方。
 * <p>
 * 说明由此处自行撰写而非取自框架元数据：框架的说明是英文的，且讲的是它自己的用途，
 * 不会告诉使用者「配了这个，NovaBot 会多出什么能力」——而后者才是他们要判断的事。
 */
final class ExternalConfigurationFields {
    /**
     * 配置项及其重要程度
     * <p>
     * 用有序表以保证界面上的先后固定：地址在前、端口在后，与人填写的顺序一致。
     */
    private static final Map<ConfigurationMetadataService.ConfigurationField, ConfigLevel.Level> FIELDS =
            new LinkedHashMap<>();

    static {
        // ---- 累计数据存储 ----
        put("spring.data.redis.host", "java.lang.String", ConfigLevel.Level.COMMON,
                "累计数据存储的 Redis 地址，填了才有跨场次的累计数据。"
                        + "留空时本场数据完整可用，但「我的总数据」「直播间总数据」「总数据排行榜」"
                        + "会明确提示不可用——那类数据随时间无限增长，放在文件里迟早撑不住。"
                        + "只需本机可达，切勿暴露到公网。改完需重启");
        put("spring.data.redis.port", "java.lang.Integer", ConfigLevel.Level.ADVANCED,
                "Redis 端口，默认 6379");
        put("spring.data.redis.password", "java.lang.String", ConfigLevel.Level.ADVANCED,
                "Redis 密码，未设密码时留空");
        put("spring.data.redis.database", "java.lang.Integer", ConfigLevel.Level.ADVANCED,
                "Redis 库号，默认 0。与其他程序共用同一实例时可换一个库避免键冲突");

        // ---- 邮件告警的发件服务 ----
        // 收件人是 starbot.core.mail.default-to，在界面上找得到；
        // 但没有下面这几项，那一项配了也发不出去
        put("spring.mail.host", "java.lang.String", ConfigLevel.Level.ADVANCED,
                "邮件告警的 SMTP 服务器地址，如 smtp.qq.com。不用邮件告警时留空");
        put("spring.mail.port", "java.lang.Integer", ConfigLevel.Level.ADVANCED,
                "SMTP 端口，如 465（SSL）或 587（STARTTLS）");
        put("spring.mail.username", "java.lang.String", ConfigLevel.Level.ADVANCED,
                "SMTP 登录账号，通常就是发件邮箱地址");
        put("spring.mail.password", "java.lang.String", ConfigLevel.Level.ADVANCED,
                "SMTP 密码或授权码。多数邮箱服务要求的是「授权码」而非登录密码");
    }

    private static void put(String name, String type, ConfigLevel.Level level, String description) {
        FIELDS.put(new ConfigurationMetadataService.ConfigurationField(name, type, description, null), level);
    }

    private ExternalConfigurationFields() {
    }

    /**
     * 额外展示的配置项
     */
    static List<ConfigurationMetadataService.ConfigurationField> fields() {
        return List.copyOf(FIELDS.keySet());
    }

    /**
     * 这些配置项的重要程度
     */
    static Map<String, ConfigLevel.Level> levels() {
        Map<String, ConfigLevel.Level> result = new LinkedHashMap<>();
        FIELDS.forEach((field, level) -> result.put(field.name(), level));
        return result;
    }
}
