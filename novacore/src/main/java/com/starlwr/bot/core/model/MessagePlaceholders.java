package com.starlwr.bot.core.model;

import java.util.regex.Pattern;

/**
 * 消息占位符的单一来源
 * <p>
 * 这套占位符语言<b>事实上已经是 core 与各平台适配器的共用约定</b>：core 认识
 * {@code {next}}（{@link Message#create} 按它分条）与 {@code {at=all}}
 * （发送器按每日配额摘取），适配器认识图片与表情那几个。
 * 把常量收进这一处，是把已经存在的隐式耦合<b>变成显式的一处</b>，
 * 而不是新增耦合——散在两个模块里的字面量改一处漏一处才是真正的风险。
 * <p>
 * ⚠️ <b>这里的解析口径必须与适配器一致</b>：适配器取的是「从 <code>{</code> 到
 * <b>最近一个</b> <code>}</code>」，所以本类的正则一律用非贪婪，
 * 与 {@link Message#getDisplay()} 的写法也是同一套。
 *
 * @see com.starlwr.bot.core.sender.StarBotMessageSender 含图消息失败后的纯文字兜底
 */
public final class MessagePlaceholders {
    private MessagePlaceholders() {
    }

    /**
     * 消息分条
     */
    public static final String NEXT = "{next}";

    /**
     * @全体成员
     */
    public static final String AT_ALL = "{at=all}";

    /**
     * 网络图片
     */
    public static final String IMAGE_URL_PREFIX = "{image_url=";

    /**
     * 本地图片
     */
    public static final String IMAGE_PATH_PREFIX = "{image_path=";

    /**
     * Base64 图片
     */
    public static final String IMAGE_BASE64_PREFIX = "{image_base64=";

    /**
     * 三种图片占位符的整段匹配
     */
    private static final Pattern IMAGE_SEGMENT = Pattern.compile("\\{image_(?:url|path|base64)=.*?}", Pattern.DOTALL);

    /**
     * 内容里有没有图片段
     */
    public static boolean containsImage(String content) {
        return content != null && IMAGE_SEGMENT.matcher(content).find();
    }

    /**
     * 剥掉全部图片段
     * <p>
     * <b>剥完可能什么都不剩</b>——开播模板默认就把封面单独放在一条里，
     * 那一条剥完是空的。调用方必须自己判断剩余内容是否为空，别把空消息发出去。
     * @return 剥掉图片段后的内容，原样保留其余占位符与文字
     */
    public static String stripImages(String content) {
        return replaceImages(content, "");
    }

    /**
     * 把全部图片段替换成给定文本
     * @param replacement 替换文本，例如展示用的 {@code [图片]}
     */
    public static String replaceImages(String content, String replacement) {
        return content == null ? null : IMAGE_SEGMENT.matcher(content).replaceAll(replacement);
    }
}
