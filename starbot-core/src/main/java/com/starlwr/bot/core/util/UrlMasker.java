package com.starlwr.bot.core.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把地址里的凭据参数打码，供落日志前调用
 *
 * <h2>为什么需要它</h2>
 * 有些接口把凭据放在查询串里，而不是请求头或请求体：
 * <ul>
 *   <li>{@code csrf=<bili_jct>}——它是登录凭据的一半</li>
 *   <li>{@code qrcode_key=…}——扫码登录的轮询令牌，有效期内谁拿到谁就能替人轮询，
 *       而轮询成功时下发的正是完整登录态</li>
 * </ul>
 * 网络日志记的是完整地址，于是这些东西会原样落盘。
 *
 * <h2>「默认关着」不等于安全</h2>
 * 网络日志默认是关的，所以这不是「每次运行都在漏」。但那个开关是<b>排障用的</b>，
 * 而排障恰恰是最容易把日志发给别人看、贴进工单、贴进聊天的时候——
 * <b>漏的时机正好是最坏的时机</b>。所以打码要做在写日志这一层，
 * 而不是指望每个调用方都记得不把凭据放进 URL。
 *
 * <h2>为什么是白名单键名而不是「像凭据就打码」</h2>
 * 按熵去猜哪个参数是凭据，会把正常的签名、时间戳、房间号一起打掉，
 * 让日志失去排障价值；而排障价值一旦没了，人就会把这个开关连同保护一起关掉。
 * 键名是有限且已知的，加一个也只是往下面这张表里添一行。
 */
public final class UrlMasker {
    /**
     * 值属于凭据、不许进日志的查询参数名（小写比较）
     * <p>
     * 加新键时请一并在测试里加一条——<b>这张表长了之后，没有测试就没人知道哪些真的生效</b>。
     */
    private static final Set<String> SECRET_KEYS = Set.of(
            "csrf",          // bili_jct，登录凭据的一半
            "qrcode_key",    // 扫码登录轮询令牌
            "access_key",    // 移动端登录态
            "sessdata",      // 登录凭据本体，正常在 Cookie 里，防它某天出现在查询串
            "token",         // 通用
            "api_token",
            "webui_token",   // NapCat WebUI，我们自己禁止这么用，但别人的地址可能带
            "password",
            "secret");

    /**
     * 打码后留下的痕迹。<b>不能整段删掉</b>——删了就看不出这里原本有过参数，
     * 而「地址里有没有带凭据」本身就是排障时要看的信息
     */
    private static final String MASK = "***";

    private static final Pattern QUERY_PARAM = Pattern.compile("([?&])([^=&#\\s]+)=([^&#\\s]*)");

    private UrlMasker() {
    }

    /**
     * 把文本里所有形如 {@code key=value} 的凭据参数替换成掩码
     * <p>
     * 收的是任意文本而不只是 URL：异常的 message 里常常裹着完整地址
     * （{@code I/O error on GET request for "https://…?csrf=…"}），
     * 那条路径同样要堵。
     * @param text 原文，可为 null
     * @return 打码后的文本；null 原样返回
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        Matcher matcher = QUERY_PARAM.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String replacement = SECRET_KEYS.contains(matcher.group(2).toLowerCase())
                    ? matcher.group(1) + matcher.group(2) + "=" + MASK
                    : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);

        return out.toString();
    }
}
