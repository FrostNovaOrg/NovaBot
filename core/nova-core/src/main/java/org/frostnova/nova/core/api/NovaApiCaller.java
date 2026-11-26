package org.frostnova.nova.core.api;

import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 已经认证好的调用者
 * <p>
 * 底座在交给插件之前已经核过：令牌存在、未吊销、未过期，所属账号此刻是启用的，
 * 令牌勾了这个接口要求的范围。账号是<b>这一个请求开头现取的</b>——
 * 管理员刚改了成员名下的主播，这里看到的就是新的那份名单。
 */
public final class NovaApiCaller {
    private final ConfigUiAccount account;

    private final NovaApiToken token;

    private final List<String> platforms;

    NovaApiCaller(ConfigUiAccount account, NovaApiToken token, List<String> platforms) {
        this.account = account;
        this.token = token;
        this.platforms = List.copyOf(platforms);
    }

    /** 所属账号的编号 */
    public String accountId() {
        return account.id();
    }

    /** 所属账号的显示名 */
    public String displayName() {
        return account.displayName();
    }

    /** 所属账号的角色 */
    public ConfigUiAccount.Role role() {
        return account.role();
    }

    public boolean admin() {
        return account.role() == ConfigUiAccount.Role.ADMIN;
    }

    /** 这把令牌的名字 */
    public String tokenName() {
        return token.name();
    }

    /** 这把令牌的编号（不是令牌本身，也不是摘要） */
    public String tokenId() {
        return token.id();
    }

    /** 这把令牌勾选的范围 */
    public Set<String> scopes() {
        return Set.copyOf(token.scopes());
    }

    public boolean hasScope(String scope) {
        return token.scopes().contains(scope);
    }

    /** 这把令牌的到期时刻，不过期为 null */
    public Instant expiresAt() {
        return token.expiresAt();
    }

    /**
     * 某平台某主播对这个调用者是否可见
     * <p>
     * 管理员全部可见；成员只见自己名下的，判法与控制台给成员过滤主播的那一处是同一段代码。
     * @param platform 直播平台
     * @param uid      主播 uid
     * @return 可见时为 true
     */
    public boolean canSee(String platform, long uid) {
        return admin() || ConfigUiStreamerId.owns(account, platform, uid, platforms);
    }
}
