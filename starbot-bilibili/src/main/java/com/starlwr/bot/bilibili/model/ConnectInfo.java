package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播间长连接信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ConnectInfo {
    /**
     * 认证令牌
     */
    private String token;

    /**
     * 可用的服务器地址列表
     */
    private List<ConnectAddress> addresses = new ArrayList<>();

    /**
     * 取这个 token 时的登录身份，未登录为 0
     * <p>
     * <b>握手包里的 uid 必须用这个值，不能在握手完成后重新去读一次当前登录态。</b>
     * token 由 getDanmuInfo 签发、与请求时的身份绑定：已登录时该请求带着 SESSDATA，
     * 拿到的是绑定该账号的 token。声称的 uid 与 token 的身份不一致时，
     * 服务端会在握手完成后立刻切断连接且不发关闭帧——表现为 1006。
     * <p>
     * 这不是假想：2026-08-04 14:21:32 扫码登录成功后 206 毫秒发起首次建连，
     * 取 token 在登录完成之前、读 uid 在之后，于是连续 96 次 1006，
     * 32 秒内把自己打进了 -352 限流。**取 token 与记录身份必须是同一个瞬间。**
     */
    private Long uid;

    /**
     * 判断连接信息是否可用
     * @return 连接信息是否可用
     */
    public boolean isAvailable() {
        return token != null && !token.isBlank() && addresses != null && !addresses.isEmpty();
    }
}
