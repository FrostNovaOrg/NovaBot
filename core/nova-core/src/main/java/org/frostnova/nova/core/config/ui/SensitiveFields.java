package org.frostnova.nova.core.config.ui;

import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 判断一个配置项是否属于机密
 * <p>
 * <b>为什么需要这个。</b>设置页把所有字段一视同仁地渲染成明文输入框，
 * 而其中躺着登录口令、二次验证密钥、访问令牌、邮箱与 Redis 口令、机器人的两个 Token。
 * 主播在直播中打开面板，这些就直接进了画面——
 * <b>二次验证密钥一旦泄漏，二次验证就永久失效，而且当事人不会察觉</b>。
 *
 * <h2>为什么按名字判断，而不是加注解</h2>
 * 其中一半的配置项（{@code spring.mail.password}、{@code spring.data.redis.password}）来自框架，
 * 我们没有那些类可以标注。按名字判断还有个好处：<b>将来新增的机密字段默认就是遮住的</b>，
 * 而注解方案里漏标一次就是泄漏一次——这类判断必须往安全的方向失败。
 *
 * <h2>为什么名字不能自己说了算：开关一律不遮</h2>
 * 光看名字会把<b>开关</b>也判成机密：{@code novabot.core.event-stream.require-token}
 * 问的是「要不要口令」，值只有 true 与 false 两种，名字里却带着 token；
 * {@code novabot.core.config-ui.auth.operator-token} 问的是「留不留启动令牌通道」，同一形态。
 * 遮住之后界面拿占位值去比 {@code 'true'}，比不中，开关<b>恒显「已关闭」</b>——
 * 而门实际开着。使用者照着界面判断「这道门关着」，装了反向代理却以为开了口令校验，等于没有鉴权。
 * <p>
 * <b>这不是「多遮一个无伤大雅」，是界面读数骗人。</b>遮蔽的代价原本只是「点一下显示」，
 * 那句话仅对文本框成立；渲染成开关的字段没有「显示」这个按钮，遮住就等于显示了一个假读数。
 * <p>
 * 因此判据加一条前置：<b>类型是布尔就一律不遮</b>，与名字无关。理由不在这两个键上，而在类型本身——
 * 布尔项的值只有两种取值，两种都能一眼猜到，遮它藏不住任何东西，却必然让开关显示错误。
 * 「什么算布尔」取自 {@link ConfigurationMetadataService.ConfigurationField#isBoolean}，
 * 与界面挑控件用的是同一份定义：两处分家就会再次出现「控件是开关、值却被遮住」。
 * <p>
 * <b>为什么不写成具名白名单</b>（把这两个键名单列出来）：那只治住今天这两项，
 * 下一个名字带 token 的开关照样中招，而中招时的表现仍然是「界面看着关着、其实开着」——
 * 不会报错，也没人会去查。按类型判是一次把这一类治掉。
 * <p>
 * <b>为什么只放行布尔，不放行数字</b>：数字是可以保密的（PIN、纯数字的密钥都是），
 * 布尔不可以。这条判据挨着安全，放宽只放宽到「值本身不可能承载秘密」为止。
 *
 * <h2>拿不到类型的时候</h2>
 * 元数据里查不到类型（插件尚未加载、使用者手写了一个还没进元数据的键）时类型为 {@code null}，
 * 此时退回纯按名字判断，也就是<b>照旧遮住</b>——判不出来就往安全的方向失败。
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

    private SensitiveFields() {
    }

    /**
     * 判断配置项是否属于机密
     * <p>
     * 类型是<b>必填参数而非可选</b>：本方法此前只收名字，于是每一处调用都无从得知自己漏了什么，
     * 开关被遮成占位值这件事也就一路走到了界面上。签名里留着这个参数，
     * 调用方就必须当场交代「这一项的类型从哪来」，拿不到时显式传 {@code null}。
     * @param name 配置项完整路径
     * @param type 配置项的 Java 类型全限定名，取自配置元数据；拿不到时传 {@code null}
     * @return 是否属于机密
     */
    public static boolean isSensitive(String name, String type) {
        if (name == null) {
            return false;
        }

        // 开关一律不遮：值只有两种取值，遮不住任何东西，却会让界面上的开关显示错误
        if (ConfigurationMetadataService.ConfigurationField.isBoolean(type)) {
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
     * @param types 配置项名到 Java 类型的查表，查不到时返回 {@code null}；整个查表可为 {@code null}
     * @return 处理后的键值
     */
    public static Map<String, String> mask(Map<String, String> values, UnaryOperator<String> types) {
        values.replaceAll((name, value) ->
                isSensitive(name, typeOf(types, name)) && value != null && !value.isBlank() ? MASK : value);
        return values;
    }

    /**
     * 剔除界面原样送回的占位值
     * <p>
     * 不剔除的话，用户改了别的字段一起保存，就会把 {@code ********} 写进配置文件，
     * <b>口令、令牌与密钥当场全部失效</b>。
     * @param changes 待保存的键值
     * @param types 配置项名到 Java 类型的查表，查不到时返回 {@code null}；整个查表可为 {@code null}
     */
    public static void dropUnchanged(Map<String, String> changes, UnaryOperator<String> types) {
        changes.entrySet().removeIf(entry ->
                isSensitive(entry.getKey(), typeOf(types, entry.getKey())) && MASK.equals(entry.getValue()));
    }

    /**
     * 查一个配置项的类型，查表缺席时按「类型未知」处理
     * @param types 查表，可为 {@code null}
     * @param name 配置项完整路径
     * @return 类型全限定名，未知时为 {@code null}
     */
    private static String typeOf(UnaryOperator<String> types, String name) {
        return types == null ? null : types.apply(name);
    }
}
