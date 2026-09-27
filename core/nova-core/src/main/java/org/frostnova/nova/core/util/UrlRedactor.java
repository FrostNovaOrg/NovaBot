package org.frostnova.nova.core.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
        return redact(text, (String[]) null);
    }

    /**
     * 先按「这一次请求用到的地址」原字面把地址整条换成主机名，再走原剥法
     * <p>
     * 原剥法靠正则收边界（免得把中文叙述吃进地址），在 {@code '} {@code (} {@code )}
     * 全角{@code ，} 这类字符上停。这类字符混在推送密钥前面时 URI 照收、请求照发，
     * 而网络出错的异常原文里，停下那个字符后面那段（正是密钥待的地方）原样留下。
     * 调用方手上有这一次真正用到的地址，按字面换掉它就不用猜哪一段像密钥。
     * <p>
     * 印文里的地址未必是原形：失败原文长什么样由网络栈与 spring-web 说了算，实测这一版
     * 只带 {@code scheme://主机/路径}、<b>不带查询串</b>。所以每条已知地址连它导出的
     * 「去查询串那一形」「URI 的 toASCIIString 形」一并按字面换，长的先换
     * （短的先换会把长的截断，剩下的尾巴照样漏）。都是从这条地址自身导出的写法，
     * 不是从形里猜密钥。
     * <p>
     * 已知地址为 null 或空时与 {@link #redact(String)} 完全一样。
     *
     * @param text 印文
     * @param knownAddresses 这一次请求真正用到的地址（可空、可含 null 项）
     * @return 剥掉地址后的印文
     */
    public static String redact(String text, String... knownAddresses) {
        if (text == null) {
            return null;
        }
        String stripped = replaceKnownAddresses(text, knownAddresses);
        Matcher matcher = URL.matcher(stripped);
        StringBuilder out = new StringBuilder(stripped.length());
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
        return redact(throwable, (String[]) null);
    }

    /**
     * 同 {@link #redact(Throwable)}，另带这一次请求真正用到的地址：
     * 每层印文先按字面把它换成主机名，再走原剥法
     */
    public static Throwable redact(Throwable throwable, String... knownAddresses) {
        return redact(throwable, Collections.newSetFromMap(new IdentityHashMap<>()), knownAddresses);
    }

    /**
     * 记着这条链上已经走过的异常（按对象身份认），走到见过的对象就截断
     */
    private static Throwable redact(Throwable throwable, Set<Throwable> seen, String[] knownAddresses) {
        if (throwable == null) {
            return null;
        }
        if (!seen.add(throwable)) {
            return null;
        }
        Stripped copy = new Stripped(redact(throwable.toString(), knownAddresses),
                redact(throwable.getCause(), seen, knownAddresses));
        copy.setStackTrace(throwable.getStackTrace());
        return copy;
    }

    /**
     * 已知地址按字面换成主机名：换完才轮到正则
     * <p>
     * 每条地址的几种写法一并算上、长的先换；单条或整组为空时原样交回
     */
    private static String replaceKnownAddresses(String text, String[] knownAddresses) {
        if (knownAddresses == null || knownAddresses.length == 0) {
            return text;
        }
        List<String[]> forms = new ArrayList<>();
        for (String address : knownAddresses) {
            if (address == null || address.isEmpty()) {
                continue;
            }
            String host = hostOf(address);
            if (host.isEmpty()) {
                continue;
            }
            for (String form : renderingsOf(address)) {
                forms.add(new String[]{form, host});
            }
        }
        forms.sort((a, b) -> b[0].length() - a[0].length());
        String out = text;
        for (String[] form : forms) {
            out = out.replace(form[0], form[1]);
        }
        return out;
    }

    /**
     * 一条地址在印文里可能长成的样子：整条、去查询串、{@code toASCIIString} 形、它去查询串那一形
     * <p>
     * 都是从这条地址自身导出的写法：失败原文可能截掉查询串，也可能把非 ASCII 换成百分号转义
     * （POST 那一路 RestTemplate 就这么拼）。
     */
    private static List<String> renderingsOf(String address) {
        String ascii;
        try {
            ascii = URI.create(address).toASCIIString();
        } catch (RuntimeException e) {
            // 地址写法 URI 不收（有空白之类）时没有 ASCII 形，拿原形接着换
            ascii = address;
        }
        Set<String> renderings = new LinkedHashSet<>();
        renderings.add(address);
        renderings.add(withoutQuery(address));
        renderings.add(ascii);
        renderings.add(withoutQuery(ascii));
        return new ArrayList<>(renderings);
    }

    /**
     * 去掉查询串那一段
     */
    private static String withoutQuery(String address) {
        int cut = address.indexOf('?');
        return cut >= 0 ? address.substring(0, cut) : address;
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
