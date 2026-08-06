package com.starlwr.bot.core.config.ui;

import java.util.Map;
import java.util.Set;

/**
 * 判断一个配置项是否属于机密
 * <p>
 * <b>为什么需要这个。</b>设置页把所有字段一视同仁地渲染成明文输入框，
 * 而其中躺着登录口令、二次验证密钥、访问令牌、邮箱与 Redis 口令、机器人的两个 Token。
 * 主播在直播中打开面板，这些就直接进了画面——
 * <b>二次验证密钥一旦泄漏，二次验证就永久失效，而且当事人不会察觉</b>。
 * <p>
 * <b>为什么按名字判断而不是加注解。</b>其中一半的配置项（{@code spring.mail.password}、
 * {@code spring.data.redis.password}）来自框架，我们没有那些类可以标注。
 * 按名字判断还有个好处：<b>将来新增的机密字段默认就是遮住的</b>，
 * 而注解方案里漏标一次就是泄漏一次——这类判断必须往安全的方向失败。
 * <p>
 * 误判的代价是把一个不敏感的字段也遮起来，点一下「显示」即可，无伤大雅。
 */
public final class SensitiveFields {
    /**
     * 回传给界面的占位值
     * <p>
     * 界面拿不到真值，保存时把它原样送回来即表示「这一项没动」。
     */
    public static final String MASK = "********";

    /**
     * 命中即视为机密的名称片段
     */
    private static final Set<String> MARKERS = Set.of("password", "token", "secret", "credential");

    /**
     * 名字看着像机密、实际不是的配置项
     * <p>
     * 这些要么是开关要么是超时，值本身没有保密价值，遮起来只会让人以为设错了。
     */
    private static final Set<String> NOT_SENSITIVE = Set.of(
            "starbot.core.config-ui.auth.max-failures",
            "starbot.core.config-ui.auth.lockout-minutes");

    private SensitiveFields() {
    }

    /**
     * 判断配置项是否属于机密
     * @param name 配置项完整路径
     * @return 是否属于机密
     */
    public static boolean isSensitive(String name) {
        if (name == null || NOT_SENSITIVE.contains(name)) {
            return false;
        }

        String leaf = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        if (MARKERS.stream().anyMatch(leaf::contains)) {
            return true;
        }

        // 单独判 key：叫 xxx-key 的多半是密钥，而 keyword、key-name 之类只是碰巧带这三个字母
        return leaf.equals("key") || leaf.endsWith("-key");
    }

    /**
     * 把机密项的值换成占位值
     * <p>
     * 空值保持为空：界面要能区分「设过但看不见」与「压根没设」，
     * 否则使用者无从判断一项必填配置到底填没填。
     * @param values 原始键值
     * @return 处理后的键值
     */
    public static Map<String, String> mask(Map<String, String> values) {
        values.replaceAll((name, value) ->
                isSensitive(name) && value != null && !value.isBlank() ? MASK : value);
        return values;
    }

    /**
     * 剔除界面原样送回的占位值
     * <p>
     * 不剔除的话，用户改了别的字段一起保存，就会把 {@code ********} 写进配置文件，
     * <b>口令、令牌与密钥当场全部失效</b>。
     * @param changes 待保存的键值
     */
    public static void dropUnchanged(Map<String, String> changes) {
        changes.entrySet().removeIf(entry -> isSensitive(entry.getKey()) && MASK.equals(entry.getValue()));
    }
}
