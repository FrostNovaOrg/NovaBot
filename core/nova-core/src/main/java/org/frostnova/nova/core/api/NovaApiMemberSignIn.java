package org.frostnova.nova.core.api;

/**
 * 成员账号此刻能不能登录控制台
 * <p>
 * 访问令牌跟着账号走：成员此刻登不进控制台，成员名下的令牌也不认，登得进了下一个请求就恢复，不用重签。
 * 令牌校验（{@link NovaApiFilter}）与控制台的令牌列表都问这一处，两边说的是同一句话。
 * 管理员的令牌不问这里。
 * <p>
 * 怎么判由 {@link NovaApiConfiguration} 接上：控制台开着时问
 * {@link org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService#memberSignInOpen()}，每次现问；
 * 控制台关着时谁也登不进，这时按配置里单点登录开没开判，与「控制台关掉后这个前缀照常服务」一致。
 */
@FunctionalInterface
public interface NovaApiMemberSignIn {
    /**
     * @return 成员此刻能登录控制台时为 true
     */
    boolean open();
}
