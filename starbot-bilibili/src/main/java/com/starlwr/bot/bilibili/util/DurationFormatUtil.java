package com.starlwr.bot.bilibili.util;

/**
 * 时长格式化工具
 */
public final class DurationFormatUtil {
    private DurationFormatUtil() {
    }

    /**
     * 把秒数格式化为「X 时 X 分 X 秒」，为零的单位省略
     * @param seconds 秒数
     * @return 时长描述，非正数时返回空字符串
     */
    public static String format(long seconds) {
        if (seconds <= 0) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long remainder = seconds % 60;

        if (hours > 0) {
            text.append(hours).append(" 时 ");
        }
        if (minutes > 0) {
            text.append(minutes).append(" 分 ");
        }
        if (remainder > 0) {
            text.append(remainder).append(" 秒");
        }

        return text.toString().trim();
    }
}
