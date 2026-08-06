package com.starlwr.bot.core.config.ui.auth;

import com.starlwr.bot.core.util.SecureToken;
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
     * @return 新会话
     */
    public ConfigUiSession issue(String clientIp, Instant now) {
        sweep(now);
        evictOldestIfFull();

        ConfigUiSession session = new ConfigUiSession(
                SecureToken.generate(), SecureToken.generate(), now, now.plus(ttl), clientIp);
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
