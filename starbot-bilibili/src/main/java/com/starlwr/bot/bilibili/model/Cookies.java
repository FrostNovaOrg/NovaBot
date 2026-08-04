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
     * 持久化刷新口令
     * <p>
     * Web 端登录时存于 localStorage 的 ac_time_value；TV 端登录时为 oauth2 的 refresh_token。
     * 它是 Cookie 续期链路的唯一入口：丢失后只能重新扫码，因此必须与其余凭据一起持久化。
     */
    private String refreshToken;

    /**
     * APP 访问令牌
     * <p>
     * 仅 TV 端扫码登录会返回。它的存在同时也是「续期该走 oauth2 路径而非 Web 路径」的标志：
     * 两条续期链路的接口与参数完全不同，不能混用。
     */
    private String accessToken;

    /**
     * APP 访问令牌的到期时间戳（毫秒）
     * <p>
     * TV 端登录默认给 180 天。续期判断以此为准，不必每次都请求接口探测。
     */
    private Long accessTokenExpiresAt;

    public Cookies(String sessData, String biliJct, String buvid3) {
        this(sessData, biliJct, buvid3, null, null, null);
    }

    public Cookies(String sessData, String biliJct, String buvid3, String refreshToken) {
        this(sessData, biliJct, buvid3, refreshToken, null, null);
    }

    /**
     * 判断凭据是否完整可用
     * <p>
     * 有意不要求 refreshToken：它只决定能否自动续期，缺失时登录态本身依然可用，
     * 且旧版本保存下来的凭据文件中并没有该字段。
     * @return 凭据是否完整
     */
    public boolean isComplete() {
        return isNotBlank(sessData) && isNotBlank(biliJct) && isNotBlank(buvid3);
    }

    /**
     * 判断是否具备自动续期所需的条件
     * @return 是否可自动续期
     */
    public boolean isRefreshable() {
        return isComplete() && isNotBlank(refreshToken);
    }

    /**
     * 判断是否为 TV 端登录取得的凭据，决定走哪条续期链路
     * @return 是否可经 oauth2 续期
     */
    public boolean isAppRefreshable() {
        return isNotBlank(accessToken) && isNotBlank(refreshToken);
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
