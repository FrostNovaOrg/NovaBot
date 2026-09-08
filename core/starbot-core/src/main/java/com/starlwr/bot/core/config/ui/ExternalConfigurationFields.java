package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.properties.ConfigEffect;
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
public final class ExternalConfigurationFields {
    /**
     * 配置项及其重要程度与生效时机
     * <p>
     * 用有序表以保证界面上的先后固定：地址在前、端口在后，与人填写的顺序一致。
     */
    private static final Map<ConfigurationMetadataService.ConfigurationField, Marks> FIELDS =
            new LinkedHashMap<>();

    static {
        // ---- 累计数据存储 ----
        // 这四项即时生效：认这几个值的是 TotalDataStorage，它按新参数就地换一个后端
        put("spring.data.redis.host", "java.lang.String", ConfigLevel.Level.COMMON, ConfigEffect.Effect.IMMEDIATE,
                "累计数据存储的 Redis 地址，填了才有跨场次的累计数据。"
                        + "留空时本场数据完整可用，但「我的总数据」「直播间总数据」「总数据排行榜」"
                        + "会明确提示不可用——那类数据随时间无限增长，放在文件里迟早撑不住。"
                        + "只需本机可达，切勿暴露到公网。填完即时生效，不用重启；"
                        + "Redis 中途挂了会自动降级为只有本场数据，连回来自己恢复");
        put("spring.data.redis.port", "java.lang.Integer", ConfigLevel.Level.ADVANCED, ConfigEffect.Effect.IMMEDIATE,
                6379, "Redis 端口，默认 6379");
        put("spring.data.redis.password", "java.lang.String", ConfigLevel.Level.ADVANCED, ConfigEffect.Effect.IMMEDIATE,
                "Redis 密码，未设密码时留空");
        put("spring.data.redis.database", "java.lang.Integer", ConfigLevel.Level.ADVANCED, ConfigEffect.Effect.IMMEDIATE,
                0, "Redis 库号，默认 0。与其他程序共用同一实例时可换一个库避免键冲突。改完即时生效，不用重启");

        // ---- 邮件告警的发件服务 ----
        // 收件人是 starbot.core.mail.default-to，在界面上找得到；
        // 但没有下面这几项，那一项配了也发不出去
        put("spring.mail.host", "java.lang.String", ConfigLevel.Level.ADVANCED, ConfigEffect.Effect.RESTART,
                "邮件告警的 SMTP 服务器地址，如 smtp.qq.com。不用邮件告警时留空");
        put("spring.mail.port", "java.lang.Integer", ConfigLevel.Level.ADVANCED, ConfigEffect.Effect.RESTART,
                "SMTP 端口，如 465（SSL）或 587（STARTTLS）");
        put("spring.mail.username", "java.lang.String", ConfigLevel.Level.ADVANCED, ConfigEffect.Effect.RESTART,
                "SMTP 登录账号，通常就是发件邮箱地址");
        put("spring.mail.password", "java.lang.String", ConfigLevel.Level.ADVANCED, ConfigEffect.Effect.RESTART,
                "SMTP 密码或授权码。多数邮箱服务要求的是「授权码」而非登录密码");

        // ---- 控制台自己怎么被端出来 ----
        // 监听地址是四个危险项之一，界面上得有它才谈得上围栏；此前它压根不在界面上，
        // 于是「把接口暴露到网络」这件事只能在服务器上改文件完成，控制台连提醒的机会都没有
        put("server.port", "java.lang.Integer", ConfigLevel.Level.ADVANCED, ConfigEffect.Effect.RESTART,
                7827, "控制台与事件流共用的服务端口，默认 7827");
        put("server.address", "java.lang.String", ConfigLevel.Level.ADVANCED, ConfigEffect.Effect.RESTART,
                "127.0.0.1", "只监听哪个地址。默认 127.0.0.1 表示只有本机连得上；"
                        + "改成 0.0.0.0 会把控制台与推送接口暴露到网络，"
                        + "此时务必配好反向代理、来源 IP 白名单与登录口令");
    }

    /**
     * 这几项里改到某个值之后后果不小的
     * <p>
     * 这几项没有字段可标 {@link com.starlwr.bot.core.config.ConfigDanger}，声明只能写在这里。
     * 表与上面那张字段表分开，因此可能落单——一条指向界面上已不存在之物的危险声明，
     * 界面上看不出任何异常。{@code ConfigurationConsistencyTest} 里有一格盯着这件事。
     */
    private static final Map<String, ConfigurationDangerResolver.Danger> DANGERS = Map.of(
            "server.address", new ConfigurationDangerResolver.Danger("0.0.0.0",
                    "监听地址改成 0.0.0.0？",
                    "会把控制台与推送接口暴露到网络上，任何能连到这台机器的人都够得着。"
                            + "没有配好反向代理、来源 IP 白名单与登录口令就别开。"));

    /**
     * 这几项的重要程度与生效时机
     * <p>
     * 两者<b>都是必填的构造参数</b>，而不是各一张可以只写一半的表。这几项没有字段可标注，
     * 构建期那道「每个配置项都标了生效时机」的判据够不着它们；新加一项时唯一还拦得住
     * 「忘了标」的，就是这里少写一个参数编译不过。
     */
    private record Marks(ConfigLevel.Level level, ConfigEffect.Effect effect) {
    }

    private static void put(String name, String type, ConfigLevel.Level level, ConfigEffect.Effect effect,
                            String description) {
        put(name, type, level, effect, null, description);
    }

    /**
     * 带默认值的那一支
     * <p>
     * 框架元数据里这几项本来是有默认值的，只是被这张表覆盖掉了。设置页上「默认：X · 恢复默认」
     * 那一行照默认值显示，缺了它这几项会写成「默认：未设」——而 {@code server.address}
     * 的默认值恰恰是那个安全的 127.0.0.1，「恢复默认」在它身上最该管用。
     */
    private static void put(String name, String type, ConfigLevel.Level level, ConfigEffect.Effect effect,
                            Object defaultValue, String description) {
        FIELDS.put(new ConfigurationMetadataService.ConfigurationField(name, type, description, defaultValue),
                new Marks(level, effect));
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
     * 额外展示的配置项名
     * <p>
     * 公开的只有名字这一栏，供构建期那道「每个配置项都归了组」的判据现算分母用。
     * 界面上摆着的配置项<b>不止 starbot 命名空间那一批</b>，分母漏掉这几项的话，
     * 少归一组的正好是没人会想起来的那几个。
     * @return 配置项名，顺序与界面一致
     */
    public static List<String> names() {
        return FIELDS.keySet().stream().map(ConfigurationMetadataService.ConfigurationField::name).toList();
    }

    /**
     * 这些配置项的重要程度
     */
    static Map<String, ConfigLevel.Level> levels() {
        Map<String, ConfigLevel.Level> result = new LinkedHashMap<>();
        FIELDS.forEach((field, marks) -> result.put(field.name(), marks.level()));
        return result;
    }

    /**
     * 这些配置项的生效时机
     * <p>
     * 累计存储那四项即时生效：认它们的 {@code TotalDataStorage} 会按新参数就地换一个后端。
     * 其余仍要重启——邮件发件服务与服务端口都是启动时装配一次的 bean，改了配置对象也换不掉它们。
     */
    static Map<String, ConfigEffect.Effect> effects() {
        Map<String, ConfigEffect.Effect> result = new LinkedHashMap<>();
        FIELDS.forEach((field, marks) -> result.put(field.name(), marks.effect()));
        return result;
    }

    /**
     * 这几项里标了即时生效的那些，供构建期那道「标了即时生效就真的会被写回」的判据现算分母用
     * <p>
     * 那道判据的分母原先只有 {@code @ConfigurationProperties} 那一批——<b>这张表里的项一个都不在其中</b>，
     * 它们没有字段可标注，也就不会出现在配置元数据里。于是这几项一旦接进即时生效通道，
     * 判据会反过来报「这个键会被写回运行中的配置，却没标成即时生效」：<b>标了，只是它看不见</b>。
     * @return 配置项名，顺序与界面一致
     */
    public static List<String> immediateNames() {
        return FIELDS.entrySet().stream()
                .filter(entry -> entry.getValue().effect() == ConfigEffect.Effect.IMMEDIATE)
                .map(entry -> entry.getKey().name())
                .toList();
    }

    /**
     * 这几项里的危险项
     */
    static Map<String, ConfigurationDangerResolver.Danger> dangers() {
        return DANGERS;
    }

    /**
     * 危险声明覆盖到的配置项名，供构建期那道判据核对
     * @return 配置项名
     */
    public static List<String> dangerousNames() {
        return List.copyOf(DANGERS.keySet());
    }
}
