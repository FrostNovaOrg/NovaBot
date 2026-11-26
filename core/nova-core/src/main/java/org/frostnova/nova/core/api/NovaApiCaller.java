package org.frostnova.nova.core.api;

import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 已经认证好的调用者
 * <p>
 * 底座在交给插件之前已经核过：令牌存在、未吊销、未过期，所属账号此刻是启用的
 * （是成员的，此刻还登得进控制台），令牌勾了这个接口要求的范围；那项范围是
 * {@link NovaApiScope#adminOnly() 仅管理员}的，账号此刻还得是管理员。
 * 账号是<b>这一个请求开头现取的</b>——管理员刚改了成员名下的主播、刚改了角色，这里看到的就是新的那份。
 */
public final class NovaApiCaller {
    private final ConfigUiAccount account;

    private final NovaApiToken token;

    private final List<String> platforms;

    private final Predicate<String> adminOnly;

    NovaApiCaller(ConfigUiAccount account, NovaApiToken token, List<String> platforms, Predicate<String> adminOnly) {
        this.account = account;
        this.token = token;
        this.platforms = List.copyOf(platforms);
        this.adminOnly = adminOnly;
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

    /** 这把令牌签发时勾选的范围 */
    public Set<String> scopes() {
        return Set.copyOf(token.scopes());
    }

    /**
     * 这把令牌此刻用得了某项范围
     * <p>
     * 勾了它；它是{@link NovaApiScope#adminOnly() 仅管理员}的，账号此刻还得是管理员。
     * 管理员降成成员之后，这个账号以前签的令牌对这种范围回 false。
     * @param scope 范围标识
     * @return 用得了时为 true
     */
    public boolean hasScope(String scope) {
        return token.scopes().contains(scope) && (admin() || !adminOnly.test(scope));
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
