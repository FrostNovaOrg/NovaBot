package org.frostnova.nova.core.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把地址整段缩成主机名，供落日志与对外报错前调用
 *
 * <h2>为什么需要它</h2>
 * 有些投递地址把凭据拼在<b>路径</b>里：Bark 是 {@code https://api.day.app/推送密钥}、
 * Server 酱是 {@code https://sctapi.ftqq.com/推送密钥.send}。而 {@link UrlMasker} 只遮
 * <b>点名的查询参数</b>，路径一律原样——这条路上打码名单天生不够用。
 * <p>
 * 不猜哪一段路径像密钥：<b>路径与查询串整段拿掉，主机名留下</b>。
 * 哪台主机是排障要看的，路径里哪一段是密钥猜错一次就漏一次。
 *
 * <h2>为什么单独成件</h2>
 * 同一条剥法有两处要用：网络日志落盘前（{@code HttpUtil}）、对外报错与起因链
 * （{@code WebhookAlertChannel}）。写两份就是两条会各自漂走的判法，
 * 漂走的那条正好变成漏的那条，所以只留这一处实现。
 */
public final class UrlRedactor {
    /**
     * 地址里允许出现的字符——拿它收边界，免得把中文叙述跟着吃进地址
     */
    private static final Pattern URL = Pattern.compile(
            "https?://[^\\s\"'()<>，。；、）】》]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCHEME = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);

    private UrlRedactor() {
    }

    /**
     * 把文本里每个地址剥成主机名：路径与查询串一概不外带
     */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = URL.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(hostOf(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 起因链逐层拷贝一份，每层印文都剥掉地址，供落日志与往上抛时代替原异常
     * <p>
     * 只处理最外层不够：工程日志会打出整条起因链（{@code Caused by:} 那几行），
     * 起因里带地址也算漏。栈帧原样保留——出错定位全在那里。
     * <p>
     * 起因链成环时（A 的起因是 B、B 的起因又是 A）在环上那条边截断：不截的话，
     * 这一步自己会无限递归成 StackOverflowError，把要看的错误整个吞掉。
     */
    public static Throwable redact(Throwable throwable) {
        return redact(throwable, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * 记着这条链上已经走过的异常（按对象身份认），走到见过的对象就截断
     */
    private static Throwable redact(Throwable throwable, Set<Throwable> seen) {
        if (throwable == null) {
            return null;
        }
        if (!seen.add(throwable)) {
            return null;
        }
        Stripped copy = new Stripped(redact(throwable.toString()), redact(throwable.getCause(), seen));
        copy.setStackTrace(throwable.getStackTrace());
        return copy;
    }

    /**
     * 从地址里取主机名：用户信息、端口、路径、查询串一概去掉
     * <p>
     * 用户信息（{@code https://用户:口令@主机}）同样是凭据，一并去掉。
     */
    public static String hostOf(String url) {
        String rest = SCHEME.matcher(url).replaceFirst("");
        int cut = firstIndexOf(rest, "/?#");
        if (cut >= 0) {
            rest = rest.substring(0, cut);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        if (rest.startsWith("[")) {
            // IPv6 字面量，冒号是地址本身的一部分，不能当端口切
            int end = rest.indexOf(']');
            return end >= 0 ? rest.substring(0, end + 1) : rest;
        }
        int colon = rest.indexOf(':');
        return colon >= 0 ? rest.substring(0, colon) : rest;
    }

    private static int firstIndexOf(String text, String chars) {
        for (int i = 0; i < text.length(); i++) {
            if (chars.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 印文换过、栈帧留着的异常副本
     * <p>
     * {@code toString()} 直接回换过的那一句：日志里这一行读起来与原来同一条线，
     * 只是地址没了——不能让它印成本类的类名，那会让读日志的人以为异常类型变了。
     */
    private static final class Stripped extends Throwable {
        private final String rendered;

        private Stripped(String rendered, Throwable cause) {
            super(rendered, cause, false, true);
            this.rendered = rendered;
        }

        @Override
        public String toString() {
            return rendered;
        }
    }
}
