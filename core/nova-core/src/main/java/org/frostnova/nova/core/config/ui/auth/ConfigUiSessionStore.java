package org.frostnova.nova.core.config.ui.auth;

import org.frostnova.nova.core.lang.SecureToken;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置界面的登录会话存储
 * <p>
 * 面板一旦对公网开放，会话 Cookie 就是唯一挡在配置、推送目标与运行状态前面的东西，
 * 因此这里同时设两道期限：
 * <ul>
 *   <li><b>绝对期限</b>——从登录起算，到点必失效。它约束的是「Cookie 被偷走之后还能用多久」，
 *       这个上限不能靠使用行为延长，否则一个被偷的会话可以永久续命</li>
 *   <li><b>闲置期限</b>——从最近一次使用起算。它管的是「在网吧登录完忘了退出」这类情形</li>
 * </ul>
 * <p>
 * 会话只在内存里，不落盘：进程重启即全部失效。单用户面板重新登录一次的代价很小，
 * 而把会话凭据写进磁盘等于凭空多出一份需要保护的长期机密。
 */
@Slf4j
public class ConfigUiSessionStore {
    /**
     * 同时存在的会话数上限
     * <p>
     * 单用户场景下手机、电脑、平板各一个也就三五个，取 32 是留足余量。
     * 设上限是为了让「反复登录」不会把内存撑大——超出时淘汰最早签发的那个。
     */
    private static final int MAX_SESSIONS = 32;

    private final Duration ttl;

    private final Duration idleTimeout;

    private final Map<String, ConfigUiSession> sessions = new ConcurrentHashMap<>();

    public ConfigUiSessionStore(Duration ttl, Duration idleTimeout) {
        this.ttl = ttl;
        this.idleTimeout = idleTimeout;
    }

    /**
     * 签发一个新会话
     * @param clientIp 登录来源 IP
     * @param now 当前时刻
     * @param channel 这一把是从哪条通道换来的。<b>没有默认值是有意的</b>：日后新添一处签发点时，
     *                这个参数会逼着人当场说清它走的是哪条路；给了默认值就会有一处悄悄记成另一条通道，
     *                而那件事从功能上完全看不出来
     * @return 新会话
     */
    public ConfigUiSession issue(String clientIp, Instant now, ConfigUiSession.Channel channel) {
        return issue(clientIp, now, channel, null);
    }

    /**
     * 签发一个新会话，并记下它是哪把通行密钥换来的
     * @param clientIp 登录来源 IP
     * @param now 当前时刻
     * @param channel 这一把是从哪条通道换来的
     * @param passkeyCredentialId 签发这把会话的通行密钥凭据标识；非通行密钥通道传 null
     * @return 新会话
     */
    public ConfigUiSession issue(String clientIp, Instant now, ConfigUiSession.Channel channel,
                                 String passkeyCredentialId) {
        sweep(now);
        evictOldestIfFull();

        ConfigUiSession session = new ConfigUiSession(
                SecureToken.generate(), SecureToken.generate(), now, now.plus(ttl), clientIp, channel,
                passkeyCredentialId);
        sessions.put(session.getId(), session);

        return session;
    }

    /**
     * 校验会话并顺延闲置期限
     * @param id 会话标识，可为 null
     * @param now 当前时刻
     * @return 有效会话，不存在或已过期时为空
     */
    public Optional<ConfigUiSession> validate(String id, Instant now) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        ConfigUiSession session = sessions.get(id);
        if (session == null) {
            return Optional.empty();
        }

        if (expired(session, now)) {
            sessions.remove(id);
            return Optional.empty();
        }

        session.touch(now);
        return Optional.of(session);
    }

    /**
     * 把一把会话换成新标识：旧标识当场作废，登录时刻与绝对期限照旧
     * <p>
     * 改口令、开关二次验证之后用。偷到 Cookie 的人与主人握着的可能是<b>同一把</b>，
     * {@link #revokeAllExcept} 留下的恰恰是它；换成新标识、只随这一趟响应交回，旧的那一枚就进不来了。
     * <p>
     * <b>先摘后发</b>：同一个旧标识并发来换，只有摘到它的那一趟拿得到新会话，其余几趟拿到空——
     * 不会一把换出两把，拿到空的那几趟也不会再去注销「其余」。
     * @param id 当前会话标识，可为 null
     * @param now 当前时刻
     * @return 新会话；标识为空、认不出或已过期时为空
     */
    public Optional<ConfigUiSession> rotate(String id, Instant now) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        ConfigUiSession current = sessions.remove(id);
        if (current == null || expired(current, now)) {
            return Optional.empty();
        }

        ConfigUiSession renewed = current.renew(SecureToken.generate(), SecureToken.generate(), now);
        sessions.put(renewed.getId(), renewed);
        return Optional.of(renewed);
    }

    /**
     * 注销一个会话
     * @param id 会话标识
     */
    public void revoke(String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    /**
     * 注销全部会话
     * <p>
     * 改口令或改 2FA 密钥后必须调用：否则在旧口令下建立的会话仍然畅通，
     * 「改了口令」这个动作就没能把可能已经泄漏的访问权收回来。
     * @return 被注销的会话数
     */
    public int revokeAll() {
        int size = sessions.size();
        sessions.clear();
        return size;
    }

    /**
     * 注销除某一把之外的全部会话
     * <p>
     * 改口令时用：那一刻要收回的是「别处那些在旧口令下建立的会话」，
     * 而当前这一把刚刚验过口令，把它一并踢掉只会让人以为改口令失败了。
     * <p>
     * {@code keepId} 为 null 或空白时<b>什么也不注销</b>：调用方没能认出当前这一把，
     * 此时按「其余」动刀会把所有会话一并踢掉，刚办完的人会以为没办成。
     * 要清空整张表请走 {@link #revokeAll()}。
     * @param keepId 留下的会话标识；认不出时传 null，本方法直接返回 0
     * @return 被注销的会话数
     */
    public int revokeAllExcept(String keepId) {
        if (keepId == null || keepId.isBlank()) {
            return 0;
        }

        int before = sessions.size();
        sessions.keySet().removeIf(id -> !id.equals(keepId));
        return before - sessions.size();
    }

    /**
     * 注销由某一把通行密钥签发的会话
     * <p>
     * 删钥匙时用：手机丢了，主人把手机那把钥匙删掉，手机上已经登着的会话必须当即作废——
     * 不然删了也白删。按会话上记着的凭据标识认人，连换过会话（rotate）的那一把也认得出：
     * 换会话时标识随行（见 {@link ConfigUiSession#renew}）。
     * <p>
     * {@code keepId} 指认出的会话留下：删的人在场、刚过了登录，不把自己踢下线。
     * {@code keepId} 为 null 或空白时<b>不留</b>——调用方认不出当前这一把时，宁可全收回来，
     * 也不能把带着这把钥匙的会话漏在场上。与 {@link #revokeAllExcept} 的「认不出就不动刀」相反：
     * 那里多注销会把刚办完的人踢掉，这里少注销会让丢掉的设备继续进得来。
     * @param credentialId 通行密钥凭据标识；为空时什么也不注销
     * @param keepId 留下的会话标识，认不出时传 null
     * @return 被注销的会话数
     */
    public int revokeByPasskey(String credentialId, String keepId) {
        if (credentialId == null || credentialId.isBlank()) {
            return 0;
        }

        int before = sessions.size();
        sessions.entrySet().removeIf(entry ->
                credentialId.equals(entry.getValue().getPasskeyCredentialId())
                        && (keepId == null || keepId.isBlank() || !entry.getKey().equals(keepId)));
        return before - sessions.size();
    }

    private boolean expired(ConfigUiSession session, Instant now) {
        return !now.isBefore(session.getExpiresAt())
                || !now.isBefore(session.getLastSeenAt().plus(idleTimeout));
    }

    /**
     * 清掉已过期的会话
     */
    private void sweep(Instant now) {
        sessions.values().removeIf(session -> expired(session, now));
    }

    private void evictOldestIfFull() {
        while (sessions.size() >= MAX_SESSIONS) {
            Optional<ConfigUiSession> oldest = sessions.values().stream()
                    .min(Comparator.comparing(ConfigUiSession::getIssuedAt));
            if (oldest.isEmpty()) {
                return;
            }

            sessions.remove(oldest.get().getId());
            log.info("配置界面会话数已达上限, 注销最早的一个");
        }
    }
}
