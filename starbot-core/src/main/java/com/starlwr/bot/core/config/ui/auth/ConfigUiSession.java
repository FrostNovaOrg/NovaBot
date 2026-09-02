package com.starlwr.bot.core.config.ui.auth;

import lombok.Getter;

import java.time.Instant;

/**
 * 一次配置界面的登录会话
 * <p>
 * 会话只存在于内存中，进程重启即全部失效。这对单用户面板是有意为之：
 * 会话凭据一旦落盘就成了另一份需要保护的长期机密，而重启后重新登录一次的代价很小。
 */
@Getter
public class ConfigUiSession {
    /**
     * 会话标识，写入 Cookie
     */
    private final String id;

    /**
     * 该会话的 CSRF 令牌
     * <p>
     * 与会话标识分开：Cookie 会被浏览器自动附加到跨站请求上，而这个值只能由页面脚本读出并放进请求头，
     * 跨站页面拿不到它。两者相同就等于没有防护。
     */
    private final String csrfToken;

    /**
     * 登录时刻
     */
    private final Instant issuedAt;

    /**
     * 绝对过期时刻，无论是否活跃都在此时失效
     */
    private final Instant expiresAt;

    /**
     * 登录来源 IP，仅用于日志与会话列表
     */
    private final String clientIp;

    /**
     * 这把钥匙是从哪条通道换来的
     * <p>
     * 记在会话上而不是用的时候现推：换会话与用会话是两次请求，第二次请求手上只有一枚 Cookie，
     * 再也看不出它当初是怎么来的。而「使用者已同意使用协议」那行记录要写明通道，靠的正是这一位。
     */
    private final Channel channel;

    /**
     * 最近一次使用时刻，用于闲置超时
     */
    private volatile Instant lastSeenAt;

    /**
     * 本次登录是否已经把「去绑定验证器」的提示按掉了
     * <p>
     * 记在会话上而不是配置里：跳过应当只对这一次登录有效。写进配置就成了永久关闭，
     * 而那是一个该显式做出的决定，不该由一次「等会儿再说」代劳。
     */
    @lombok.Setter
    private volatile boolean totpSetupDismissed;

    ConfigUiSession(String id, String csrfToken, Instant issuedAt, Instant expiresAt, String clientIp, Channel channel) {
        this.id = id;
        this.csrfToken = csrfToken;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.clientIp = clientIp;
        this.channel = channel;
        this.lastSeenAt = issuedAt;
    }

    void touch(Instant now) {
        this.lastSeenAt = now;
    }

    /**
     * 进入控制台的通道
     * <p>
     * 这几个名字会被<b>原样写进配置文件</b>（使用协议的同意记录里那一项），
     * 因此它们是对外契约的一部分：改了名字，盘上此前那些记录就再也对不上号。
     */
    public enum Channel {
        /**
         * 输过登录口令（以及二次验证码，若已绑定）
         */
        PASSWORD("password"),

        /**
         * 凭启动日志里那个令牌进来的。未配口令时它就是这套面板唯一的凭据
         */
        OPERATOR_TOKEN("operator-token");

        private final String wire;

        Channel(String wire) {
            this.wire = wire;
        }

        /**
         * @return 写进配置文件与接口响应里的那个名字
         */
        public String wire() {
            return wire;
        }

        /**
         * 按名字找回通道
         * <p>
         * 认不出来时返回 null，而不是随便挑一个：这个值最终要写进配置文件，
         * 让一个来路不明的字符串落到盘上，日后就没人说得清它是什么意思了。
         * @param wire 通道名
         * @return 对应的通道，认不出时为 null
         */
        public static Channel fromWire(String wire) {
            for (Channel channel : values()) {
                if (channel.wire.equals(wire)) {
                    return channel;
                }
            }

            return null;
        }
    }
}
