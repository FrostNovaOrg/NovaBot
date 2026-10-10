package org.frostnova.nova.report.painter;

import java.util.regex.Pattern;

/**
 * 用户自己写的文字（醒目留言与弹幕原文、昵称、粉丝牌名等）画进图之前的整理
 * <p>
 * 图上给这些字留的都是一行的位置，而画法见了 {@code \n} 就另起一行：原文里带个换行，
 * 后半句就压到下一行的内容上，或越出它那一格。截断量宽也是按一行量的，换行先得去掉。
 */
public final class UserText {
    /**
     * 换行类字符与制表符，连同挨着它们的空格算一整段
     * <p>
     * 除 CR、LF 外还有 U+2028、U+2029、U+0085 与 U+000B、U+000C：
     * 它们在别处被当成换行，在这里画出来不是换行就是一个方块
     */
    private static final Pattern BREAKS = Pattern.compile("[ ]*[\\r\\n\\u2028\\u2029\\u0085\\t\\u000B\\f][\\r\\n\\u2028\\u2029\\u0085\\t\\u000B\\f ]*");

    private UserText() {
    }

    /**
     * 收成一行：换行类字符与制表符一律换成一个空格，连着的并成一个，首尾空白去掉
     * <p>
     * 截断要在这之后做：带着换行量出来的宽度不是画出来的宽度
     *
     * @param text 原文，{@code null} 原样返回
     * @return 一行的文字
     */
    public static String singleLine(String text) {
        if (text == null) {
            return null;
        }
        return BREAKS.matcher(text).replaceAll(" ").strip();
    }
}
