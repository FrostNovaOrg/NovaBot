package com.starlwr.bot.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 数学工具类
 */
public class MathUtil {
    /**
     * 加法计算，四舍五入后保留两位小数
     * @param a 被加数
     * @param b 加数
     * @return 和
     */
    public static double add(double a, double b) {
        return add(a, b, 2);
    }

    /**
     * 加法计算，四舍五入后保留指定位数
     * @param a 被加数
     * @param b 加数
     * @param scale 保留小数位数
     * @return 和
     */
    public static double add(double a, double b, int scale) {
        return BigDecimal.valueOf(a)
                .add(BigDecimal.valueOf(b))
                .setScale(scale, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .doubleValue();
    }

    /**
     * 减法计算，四舍五入后保留指定位数
     * @param a 被减数
     * @param b 减数
     * @return 差
     */
    public static double subtract(double a, double b) {
        return subtract(a, b, 2);
    }

    /**
     * 减法计算，四舍五入后保留指定位数
     * @param a 被减数
     * @param b 减数
     * @param scale 保留小数位数
     * @return 差
     */
    public static double subtract(double a, double b, int scale) {
        return BigDecimal.valueOf(a)
                .subtract(BigDecimal.valueOf(b))
                .setScale(scale, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .doubleValue();
    }

    /**
     * 乘法计算，四舍五入后保留两位小数
     * @param a 被乘数
     * @param b 乘数
     * @return 乘积
     */
    public static double multiply(double a, int b) {
        return multiply(a, b, 2);
    }

    /**
     * 乘法计算，四舍五入后保留指定位数
     * @param a 被乘数
     * @param b 乘数
     * @param scale 保留小数位数
     * @return 乘积
     */
    public static double multiply(double a, int b, int scale) {
        return BigDecimal.valueOf(a)
                .multiply(BigDecimal.valueOf(b))
                .setScale(scale, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .doubleValue();
    }

    /**
     * 除法计算，四舍五入后保留两位小数
     * @param a 被除数
     * @param b 除数
     * @return 商
     */
    public static double divide(double a, double b) {
        return divide(a, b, 2);
    }

    /**
     * 除法计算，四舍五入后保留指定位数
     * @param a 被除数
     * @param b 除数
     * @param scale 保留小数位数
     * @return 商
     */
    public static double divide(double a, double b, int scale) {
        return BigDecimal.valueOf(a)
                .divide(BigDecimal.valueOf(b), scale, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .doubleValue();
    }
}
