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

    ConfigUiSession(String id, String csrfToken, Instant issuedAt, Instant expiresAt, String clientIp) {
        this.id = id;
        this.csrfToken = csrfToken;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.clientIp = clientIp;
        this.lastSeenAt = issuedAt;
    }

    void touch(Instant now) {
        this.lastSeenAt = now;
    }
}
