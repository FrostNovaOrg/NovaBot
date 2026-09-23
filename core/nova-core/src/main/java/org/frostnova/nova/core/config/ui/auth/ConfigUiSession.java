package org.frostnova.nova.core.config.ui.auth;

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

    /**
     * 改口令时这把会话连着核对了几趟旧口令，算上还没比完的那几趟
     * <p>
     * 记在会话上而不是按来源 IP 记：猜的人握着的就是这一把，注销它就收回了接着猜的资格，
     * 主人的登录与别处的会话一概不受牵连。按来源记的话换个地址就能接着猜，
     * 还会把同一出口后面主人的登录一起锁住。
     */
    @Getter(lombok.AccessLevel.NONE)
    private int passwordChecks;

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
     * 记下一趟旧口令核对，<b>比对之前</b>就记
     * <p>
     * 比完再记的话，同一时刻并发打进来的几十趟都会先过「还没到次数」那一问，
     * 每一趟都真比一次，次数上限就只拦得住一趟一趟来的人。
     * @return 算上这一趟一共几趟
     */
    synchronized int countPasswordCheck() {
        return ++passwordChecks;
    }

    /**
     * 旧口令输对了，此前连错的次数作废
     */
    synchronized void clearPasswordChecks() {
        passwordChecks = 0;
    }

    /**
     * 同一次登录换一把新标识与新 CSRF 令牌
     * <p>
     * 换的只有这两个值：登录时刻、绝对期限、来源、通道、「已按掉绑定提示」与旧口令的连错次数一概照旧。
     * 绝对期限若借此重算，被偷的会话每办成一件换会话的事就多活一轮；连错次数若借此清零，
     * 猜的人办成一件不要旧口令的事（比如绑验证器）就又白得几次再猜的机会。
     * @param id 新标识
     * @param csrfToken 新 CSRF 令牌
     * @param now 当前时刻，记作最近一次使用
     * @return 新的那一把
     */
    synchronized ConfigUiSession renew(String id, String csrfToken, Instant now) {
        ConfigUiSession renewed = new ConfigUiSession(id, csrfToken, issuedAt, expiresAt, clientIp, channel);
        renewed.lastSeenAt = now;
        renewed.totpSetupDismissed = totpSetupDismissed;
        renewed.passwordChecks = passwordChecks;
        return renewed;
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
        OPERATOR_TOKEN("operator-token"),

        /**
         * 用通行密钥验过身进来的
         * <p>
         * 与 {@link #PASSWORD} 分开记而不是并进去：这条路<b>不经二次验证</b>，
         * 而使用协议的同意记录里那一行要说得出「当时是怎么进来的」。
         */
        PASSKEY("passkey");

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
