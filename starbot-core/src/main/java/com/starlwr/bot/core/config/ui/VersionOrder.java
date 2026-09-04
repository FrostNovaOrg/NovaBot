package com.starlwr.bot.core.config.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义版本号比较的纯函数
 * <p>
 * 「有没有新版」这条判断只许有这一份实现。两处最容易错、错了又看不出来的角落：
 * <b>数字段按文本比</b>——{@code 1.9} 与 {@code 1.10} 按文本比是前者大，只有按数值比才轮得到后者；
 * <b>预发版当正式版</b>——发布了 {@code 1.2.0-beta.1} 时，正在跑 {@code 1.2.0} 的机器
 * 不该被告知「有新版」。两种错都不影响其余功能，只是那枚药丸该出的时候不出、
 * 不该出的时候常驻，靠人点开页面看分辨不出来，因此由 {@code VersionOrderTest} 逐条钉着。
 * <p>
 * 认得的形态：可选的 {@code v}/{@code V} 前缀、以点分段的数字主体、
 * 首个 {@code -} 之后的预发标记、{@code +} 之后的构建元数据（按语义版本的规矩不参与比较）。
 * 认不得的段（非数字、非预发字符）按文本比兜底——本类只服务「发布仓里那个 tag 比现在的新不新」
 * 这一个问题，tag 写成什么样由发布流程决定，不为它当格式裁判。
 */
public final class VersionOrder {

    private VersionOrder() {
    }

    /**
     * 比较两个版本号
     * @param left 左边的版本号
     * @param right 右边的版本号
     * @return 负数表示 left 早于 right，0 表示相等，正数表示 left 晚于 right
     */
    public static int compare(String left, String right) {
        long[] leftMain = mainOf(left);
        long[] rightMain = mainOf(right);

        int shared = Math.min(leftMain.length, rightMain.length);
        for (int i = 0; i < shared; i++) {
            if (leftMain[i] != rightMain[i]) {
                return leftMain[i] > rightMain[i] ? 1 : -1;
            }
        }
        // 主体同为前缀关系时，段少的更早：1.10 早于 1.10.1
        if (leftMain.length != rightMain.length) {
            return leftMain.length < rightMain.length ? -1 : 1;
        }

        String leftPre = prereleaseOf(left);
        String rightPre = prereleaseOf(right);

        // 没有预发标记的才是正式版；同一个号上，预发早于正式
        if (leftPre.isEmpty() && rightPre.isEmpty()) {
            return 0;
        }
        if (leftPre.isEmpty()) {
            return 1;
        }
        if (rightPre.isEmpty()) {
            return -1;
        }

        return comparePrerelease(leftPre, rightPre);
    }

    /**
     * 版本号的主体部分（去掉前缀、预发与构建元数据后，以点分段的数字）
     * <p>
     * 认不得的段记 0：取「能比的部分」而不是整个拒绝，让一个写歪了的 tag
     * 退化成「按其余段比」，而不是让检查器当场抛出去。
     * @param version 原始版本号
     * @return 数字段
     */
    private static long[] mainOf(String version) {
        String body = stripPrefix(version);
        int pre = body.indexOf('-');
        if (pre >= 0) {
            body = body.substring(0, pre);
        }

        String[] parts = body.split("\\.");
        List<Long> numbers = new ArrayList<>();
        for (String part : parts) {
            try {
                numbers.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                numbers.add(0L);
            }
        }
        return numbers.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * 版本号的预发标记（首个 {@code -} 之后、{@code +} 之前），没有则为空串
     * @param version 原始版本号
     * @return 预发标记
     */
    private static String prereleaseOf(String version) {
        String body = stripPrefix(version);
        int pre = body.indexOf('-');
        if (pre < 0) {
            return "";
        }
        body = body.substring(pre + 1);
        int build = body.indexOf('+');
        return build >= 0 ? body.substring(0, build) : body;
    }

    private static String stripPrefix(String version) {
        String body = version == null ? "" : version.strip();
        int build = body.indexOf('+');
        if (build >= 0) {
            body = body.substring(0, build);
        }
        if (!body.isEmpty() && (body.charAt(0) == 'v' || body.charAt(0) == 'V')) {
            body = body.substring(1);
        }
        return body;
    }

    /**
     * 预发标记之间按语义版本的规矩比：数字段比数值，纯数字段早于字母段，
     * 字母段比字典序；前缀相同时段少的更早（beta 早于 beta.1）
     * @param left 左边的预发标记
     * @param right 右边的预发标记
     * @return 比较结果
     */
    private static int comparePrerelease(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");

        int shared = Math.min(leftParts.length, rightParts.length);
        for (int i = 0; i < shared; i++) {
            int bySegment = comparePrereleaseSegment(leftParts[i], rightParts[i]);
            if (bySegment != 0) {
                return bySegment;
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static int comparePrereleaseSegment(String left, String right) {
        long leftNumber = parseOrNegative(left);
        long rightNumber = parseOrNegative(right);

        // 纯数字段早于字母段：beta.2 晚于 beta.1，而 alpha 晚于 1（数字优先级高）
        if (leftNumber >= 0 && rightNumber >= 0) {
            return Long.compare(leftNumber, rightNumber);
        }
        if (leftNumber >= 0) {
            return -1;
        }
        if (rightNumber >= 0) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static long parseOrNegative(String segment) {
        try {
            return Long.parseLong(segment);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
