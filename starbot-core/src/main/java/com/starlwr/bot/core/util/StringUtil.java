package com.starlwr.bot.core.util;

/**
 * 字符串工具类
 */
public class StringUtil {
    /**
     * 检查字符串是否为空
     * @param str 要检查的字符串
     * @return 字符串是否为空
     */
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    /**
     * 检查字符串是否不为空
     * @param str 要检查的字符串
     * @return 字符串是否不为空
     */
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    /**
     * 检查字符串是否为空或空白
     * @param str 要检查的字符串
     * @return 字符串是否为空或空白
     */
    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * 检查字符串是否不为空或空白
     * @param str 要检查的字符串
     * @return 字符串是否不为空或空白
     */
    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }

    /**
     * 获取截断后的字符串，如果字符串长度超过指定长度，则在末尾添加省略号
     * @param str 要截断的字符串
     * @param maxLength 最大长度，0 表示不限制长度
     * @return 截断后的字符串
     */
    public static String getOmitString(String str, int maxLength) {
        if (isEmpty(str) || maxLength <= 0) {
            return str;
        }

        if (str.length() <= maxLength) {
            return str;
        }

        return str.substring(0, maxLength) + "...";
    }
}
