package org.frostnova.nova.core.secret;

import com.alibaba.fastjson2.JSON;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进程里在用的凭据，任何线程写的任何一行日志里都不许出现
 * <p>
 * 控制台收下的令牌、密钥，之后会被拿去连外面：重连、定时体检、换令牌，都跑在请求线程以外。
 * 这些线程上的客户端库在 DEBUG、TRACE 下会记请求头、请求体或连接地址，
 * 只认「当前请求」的遮盖管不到它们。凭据在来源处登记到这里（控制台收下时、
 * 从配置或密钥文件读出时、换到访问令牌时），日志过滤器对每一行日志都按这里遮盖，
 * 出站客户端不必各自记得打码，新加的连接也照样管得住。
 * <p>
 * 每个来源占一格，按格替换：同一格只留当前值与上一个值（上一个值可能还在途中用着），
 * 因此不会随登录尝试之类的提交无限增长。
 */
public final class SecretLogMask {
    /** 遮盖后留下的记号 */
    public static final String MASK = "***";

    /**
     * 短于这个长度的值不按字面遮：太容易和日志里的普通数字、单词撞上。
     * 请求头样式的 {@code Bearer …}、{@code QQBot …} 不论长短都由 {@link #HEADER_CREDENTIAL} 遮掉
     */
    static final int MIN_LENGTH = 6;

    /** 框架把长值截短记日志时补的尾巴（Spring 的 LogFormatUtils），前面留着凭据的开头一段 */
    private static final String TRUNCATED = " (truncated)";

    /** 鉴权头的值：方案名之后到分隔符为止整段都是凭据 */
    private static final Pattern HEADER_CREDENTIAL = Pattern.compile(
            "(?i)\\b(bearer|qqbot)(\\s+)([^\\s,;\\[\\]{}()\"'\\\\]+)");

    private static final Map<String, List<String>> SLOTS = new HashMap<>();

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private SecretLogMask() {
    }

    /**
     * 登记一个凭据
     * @param slot 来源，同一来源再登记时替换掉更早的值
     * @param value 凭据，空白时不登记
     */
    public static void remember(String slot, String value) {
        if (slot == null || value == null || value.isBlank()) {
            return;
        }
        synchronized (SLOTS) {
            List<String> values = SLOTS.computeIfAbsent(slot, key -> new ArrayList<>(2));
            values.remove(value);
            values.add(0, value);
            while (values.size() > 2) {
                values.remove(values.size() - 1);
            }
            rebuild();
        }
    }

    /**
     * 去掉一个来源登记过的全部凭据
     * @param slot 来源
     */
    public static void forget(String slot) {
        synchronized (SLOTS) {
            if (SLOTS.remove(slot) != null) {
                rebuild();
            }
        }
    }

    /**
     * 把文本里登记过的凭据和鉴权头的值换成掩码
     * @param text 原文，可为 null
     * @return 遮盖后的文本；没有可遮的时候原样交回同一个对象
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Snapshot current = snapshot;
        String out = text;
        for (String literal : current.literals) {
            if (out.contains(literal)) {
                out = out.replace(literal, MASK);
            }
        }
        out = maskTruncated(out, current.values);
        return maskHeaders(out);
    }

    /**
     * 起因链上任何一层的印文带着凭据时，交回逐层遮盖过的副本，栈帧原样保留
     * @param error 原异常，可为 null
     * @return 不带凭据时原样交回同一个对象
     */
    public static Throwable mask(Throwable error) {
        if (error == null) {
            return null;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            String original = cause.toString();
            if (!original.equals(mask(original))) {
                return copy(error, Collections.newSetFromMap(new IdentityHashMap<>()));
            }
        }
        return error;
    }

    private static Throwable copy(Throwable error, Set<Throwable> seen) {
        if (error == null || !seen.add(error)) {
            return null;
        }
        Masked masked = new Masked(mask(error.toString()), copy(error.getCause(), seen));
        masked.setStackTrace(error.getStackTrace());
        for (Throwable suppressed : error.getSuppressed()) {
            Throwable safe = copy(suppressed, seen);
            if (safe != null) {
                masked.addSuppressed(safe);
            }
        }
        return masked;
    }

    private static String maskTruncated(String text, String[] values) {
        int cut = text.indexOf(TRUNCATED);
        if (cut < 0 || values.length == 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text);
        while (cut >= 0) {
            int start = cut;
            for (String value : values) {
                for (int length = Math.min(value.length() - 1, cut); length >= MIN_LENGTH; length--) {
                    if (out.substring(cut - length, cut).equals(value.substring(0, length))) {
                        start = Math.min(start, cut - length);
                        break;
                    }
                }
            }
            if (start < cut) {
                out.replace(start, cut, MASK);
                cut = start + MASK.length();
            }
            cut = out.indexOf(TRUNCATED, cut + TRUNCATED.length());
        }
        return out.toString();
    }

    private static String maskHeaders(String text) {
        Matcher matcher = HEADER_CREDENTIAL.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        do {
            String value = matcher.group(3);
            String replacement = value.chars().allMatch(c -> c == '*') ? matcher.group()
                    : matcher.group(1) + matcher.group(2) + MASK;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }

    private static void rebuild() {
        Set<String> values = new LinkedHashSet<>();
        Set<String> literals = new LinkedHashSet<>();
        SLOTS.values().forEach(slot -> slot.forEach(value -> {
            for (String form : renderings(value)) {
                if (form.length() >= MIN_LENGTH) {
                    literals.add(form);
                }
            }
            if (value.strip().length() >= MIN_LENGTH) {
                values.add(value.strip());
            }
        }));
        List<String> ordered = new ArrayList<>(literals);
        // 长的先换：短的先换会把含着它的长写法截断，剩下的尾巴照样漏
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        snapshot = new Snapshot(ordered.toArray(String[]::new), values.toArray(String[]::new));
    }

    /** 同一个值在日志里可能长成的样子：原样、去首尾空白、JSON 转义、URL 编码 */
    private static Set<String> renderings(String value) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(value);
        forms.add(value.strip());
        String quoted = JSON.toJSONString(value);
        forms.add(quoted.substring(1, quoted.length() - 1));
        forms.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
        forms.add(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
        return forms;
    }

    private record Snapshot(String[] literals, String[] values) {
        private static final Snapshot EMPTY = new Snapshot(new String[0], new String[0]);
    }

    /** 印文换过、栈帧留着的异常副本，{@code toString()} 读起来与原来同一行 */
    private static final class Masked extends Throwable {
        private final String rendered;

        private Masked(String rendered, Throwable cause) {
            super(rendered, cause, true, true);
            this.rendered = rendered;
        }

        @Override
        public String toString() {
            return rendered;
        }
    }
}
