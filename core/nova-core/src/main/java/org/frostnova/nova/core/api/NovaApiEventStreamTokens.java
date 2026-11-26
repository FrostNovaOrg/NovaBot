package org.frostnova.nova.core.api;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.protocol.EventStreamAccessTokens;
import org.frostnova.nova.core.protocol.EventStreamTokenService.Verdict;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 让事件输出（{@code /nova/events}）的首帧也认访问令牌
 * <p>
 * 事件输出在核心里，令牌与账号在外壳里；核心定了 {@link EventStreamAccessTokens} 这几个口子，这里照着实现。
 * 端点仍在 {@code /nova/events}、仍只接受本机连接，不经统一接口的那道过滤器：那道过滤器是一问一答的，
 * 接不了长连接。来源名单、「从别的机器访问」的逐组开关都与事件流无关。
 *
 * <h2>认哪一把</h2>
 * 勾了 {@value #READ}、未吊销、未过期，所属账号此刻存在、启用着、是管理员。这一项范围仅管理员：
 * 一条事件流里是全部主播的事件，不按账号分；成员签发时勾不了，伪造进表里的也不认。
 *
 * <h2>回什么</h2>
 * <ul>
 *   <li>表里没有、或没勾 {@value #READ} 的（不论吊销没吊销、过期没过期）：{@link Check#notMine()}。
 *       端点把它交给只读口令去核，回应与出示一串乱码相同——看不出「令牌存在、只是没这个范围」。</li>
 *   <li>勾了，已吊销：{@code revoked}；已过期：{@code expired}。账号停用、删除时名下令牌一并吊销，所以通常也是 {@code revoked}。</li>
 *   <li>勾了，所属账号不在、没启用、此刻不是管理员：{@code bad_token}。</li>
 * </ul>
 *
 * <h2>连着时失效</h2>
 * 端点按编号定时复核（{@link #recheck}）；吊销一把、一并吊销账号名下的、账号表的每一次改动，
 * 都当场通知端点再核一轮（{@link #addChangeListener}）。
 *
 * <h2>不出现令牌本身</h2>
 * 日志与 {@link Grant#label()} 只写账号的显示名、令牌的名字与编号，没有令牌本身，也没有摘要。
 */
@Slf4j
public class NovaApiEventStreamTokens implements EventStreamAccessTokens {
    /** 范围标识 */
    public static final String READ = "events:read";

    /** 这一项范围：底座自带，不属于哪一组接口 */
    public static final NovaApiScope SCOPE = NovaApiScope.adminOnly(READ, "读取事件流",
            "在本机连事件输出（/nova/events），实时接收全部主播的直播间事件；"
                    + "事件输出只接受本机连接，「从别的机器访问」的开关管不到它");

    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private final NovaApiTokenStore tokens;

    private final ConfigUiAccountStore accounts;

    /**
     * @param tokens   访问令牌
     * @param accounts 账号表，控制台开着时与控制台是同一个对象
     */
    public NovaApiEventStreamTokens(NovaApiTokenStore tokens, ConfigUiAccountStore accounts) {
        this.tokens = tokens;
        this.accounts = accounts;
    }

    @Override
    public boolean claims(String presented) {
        return presented != null && presented.startsWith(NovaApiTokenStore.PREFIX);
    }

    @Override
    public Check check(String presented) {
        Optional<NovaApiToken> matched = tokens.match(presented);
        if (matched.isEmpty()) {
            return Check.notMine();
        }
        NovaApiToken token = matched.get();
        if (!token.scopes().contains(READ)) {
            log.info("事件流: 访问令牌 编号 {} 没有勾选「{}」, 事件流不认它", token.id(), SCOPE.name());
            return Check.notMine();
        }
        Optional<ConfigUiAccount> owner = accounts.find(token.accountId());
        Verdict verdict = verdict(token, owner);
        if (verdict != Verdict.OK) {
            log.warn("事件流拒绝了一把访问令牌: 编号 {}, {}", token.id(), why(verdict, owner));
            return Check.refused(verdict);
        }
        tokens.used(token);
        return Check.granted(new Grant(token.id(), label(token, owner.get())));
    }

    @Override
    public Verdict recheck(Grant grant) {
        Optional<NovaApiToken> found = tokens.find(grant.id());
        if (found.isEmpty() || !found.get().scopes().contains(READ)) {
            return Verdict.BAD_TOKEN;
        }
        NovaApiToken token = found.get();
        Verdict verdict = verdict(token, accounts.find(token.accountId()));
        if (verdict == Verdict.OK) {
            // 连着就是在用：列表里的「最后使用」不该停在连上的那一刻
            tokens.used(token);
        }
        return verdict;
    }

    @Override
    public void addChangeListener(Runnable listener) {
        tokens.addChangeListener(listener);
        accounts.addChangeListener(listener);
    }

    @Override
    public void removeChangeListener(Runnable listener) {
        tokens.removeChangeListener(listener);
        accounts.removeChangeListener(listener);
    }

    /**
     * 勾了 {@value #READ} 的这一把此刻能不能连
     * <p>
     * 判法与统一接口认不认令牌、认不认仅管理员的范围同一个口径；账号每次现取
     */
    private Verdict verdict(NovaApiToken token, Optional<ConfigUiAccount> owner) {
        if (token.revoked()) {
            return Verdict.REVOKED;
        }
        if (token.expired(tokens.now())) {
            return Verdict.EXPIRED;
        }
        if (owner.isEmpty() || owner.get().status() != ConfigUiAccount.Status.ENABLED
                || owner.get().role() != ConfigUiAccount.Role.ADMIN) {
            return Verdict.BAD_TOKEN;
        }
        return Verdict.OK;
    }

    /** 拒绝的原因，只进日志 */
    private static String why(Verdict verdict, Optional<ConfigUiAccount> owner) {
        return switch (verdict) {
            case REVOKED -> "已吊销";
            case EXPIRED -> "已过期";
            default -> owner.isEmpty() ? "所属账号已删除"
                    : owner.get().status() != ConfigUiAccount.Status.ENABLED ? "所属账号没有启用"
                    : "所属账号此刻不是管理员";
        };
    }

    /** 哪个账号的哪一把：显示名与令牌名字去掉控制字符，免得一个名字在日志里另起一行 */
    private static String label(NovaApiToken token, ConfigUiAccount owner) {
        String who = ConfigUiAccount.LOCAL_ID.equals(owner.id()) ? "本地管理员" : String.valueOf(owner.displayName());
        return "账号 " + clean(who) + " 的访问令牌「" + clean(token.name()) + "」（编号 " + token.id() + "）";
    }

    private static String clean(String text) {
        return text == null ? "" : CONTROL.matcher(text).replaceAll(" ");
    }
}
