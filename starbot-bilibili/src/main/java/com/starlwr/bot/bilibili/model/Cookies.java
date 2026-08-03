package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 哔哩哔哩登录凭据
 * <p>
 * 其中 SESSDATA 与 bili_jct 等同于账号的完整控制权，任何位置都不应将其原样输出至日志，
 * 因此本类刻意不实现 toString，避免在日志或异常堆栈中被意外打印。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Cookies {
    /**
     * 登录态凭据
     */
    private String sessData;

    /**
     * CSRF 令牌，调用写操作接口时必需
     */
    private String biliJct;

    /**
     * 设备标识
     */
    private String buvid3;

    /**
     * 判断凭据是否完整可用
     * @return 凭据是否完整
     */
    public boolean isComplete() {
        return isNotBlank(sessData) && isNotBlank(biliJct) && isNotBlank(buvid3);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 刻意不输出凭据内容，防止在日志中泄露
     * @return 脱敏后的描述
     */
    @Override
    public String toString() {
        return "Cookies(已" + (isComplete() ? "" : "部分") + "加载, 内容已脱敏)";
    }
}
