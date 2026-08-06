package com.starlwr.bot.core.config.ui.auth;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 配置界面的登录校验
 * <p>
 * <b>口令登录是可选的，默认不开。</b>不配口令时面板维持原样：仅回环地址可达、地址栏带令牌即进，
 * 单机使用不需要为此多输一次密码。填了口令才切换到「登录换会话」的模式，
 * 那是把面板暴露到公网时才需要的形态。
 * <p>
 * 开启后<b>令牌不再作为凭据</b>：公网地址栏里带着长期令牌等于把钥匙贴在门上，
 * 而会话有期限、可注销、可因改口令而全部失效，令牌这三样都做不到。
 */
@Slf4j
public class ConfigUiAuthService {
    /**
     * 失败提示一律用这一句
     * <p>
     * 不区分「口令错」与「验证码错」：分开说等于告诉攻击者口令已经猜对了，
     * 二次验证就只剩六位数字要试。
     */
    private static final String INVALID = "口令或验证码不正确";

    private final ConfigUiSessionStore sessions;

    private final LoginThrottle throttle;

    /**
     * 口令哈希，未启用口令登录时为 null
     */
    private final String passwordHash;

    /**
     * 是否要求二次验证
     */
    private final boolean totpEnabled;

    /**
     * 已绑定的 2FA 密钥，尚未绑定时为 null
     * <p>
     * 绑定可以在运行期完成，因此是可变的
     */
    private volatile String totpSecret;

    /**
     * 绑定引导中待确认的密钥
     * <p>
     * 只在内存里存到确认为止。<b>不能一生成就写进配置</b>：那样刷新一次页面就换一个密钥，
     * 用户扫了旧的却对不上，而配置里躺着一个谁也没绑定的密钥。
     */
    private volatile String pendingSecret;

    /**
     * 是否启用了口令登录
     */
    @Getter
    private final boolean enabled;

    public ConfigUiAuthService(StarBotCoreProperties.ConfigUi.Auth properties, ConfigUiSessionStore sessions, LoginThrottle throttle) {
        this.sessions = sessions;
        this.throttle = throttle;
        this.passwordHash = resolvePasswordHash(properties.getPassword());
        this.totpEnabled = properties.isTotp();
        this.totpSecret = blankToNull(properties.getTotpSecret());
        this.enabled = passwordHash != null;

        if (this.enabled && this.totpEnabled && this.totpSecret == null) {
            log.warn("配置界面已启用口令登录但尚未绑定验证器, 请在界面上完成绑定");
        }
    }

    /**
     * 登录时是否要输验证码
     */
    public boolean totpRequired() {
        return totpEnabled && totpSecret != null;
    }

    /**
     * 是否该提示用户去绑定验证器
     */
    public boolean totpPending() {
        return enabled && totpEnabled && totpSecret == null;
    }

    /**
     * 取出绑定引导用的密钥
     * <p>
     * 同一个进程内始终返回同一个，刷新页面不会换：换了的话先扫的那个二维码就作废了。
     * @return Base32 密钥
     */
    public synchronized String pendingSecret() {
        if (pendingSecret == null) {
            pendingSecret = TotpGenerator.generateSecret();
        }
        return pendingSecret;
    }

    /**
     * 校验绑定引导中输入的验证码
     * <p>
     * 只校验、不落盘：密钥要先写进配置文件成功，才能真正启用。顺序反过来的话，
     * 写文件失败就会出现「界面说绑好了，重启后却又要重新绑」，而中间这段时间登录要输的
     * 是一个没人记得的密钥。
     * @param code 用户输入的验证码
     * @return 校验通过时返回待启用的密钥
     */
    public Optional<String> verifyPending(String code) {
        String secret = pendingSecret();
        return TotpGenerator.verify(secret, code, Instant.now()) ? Optional.of(secret) : Optional.empty();
    }

    /**
     * 启用已确认的密钥
     * @param secret Base32 密钥
     */
    public void activateTotp(String secret) {
        this.totpSecret = secret;
        this.pendingSecret = null;
        log.info("配置界面已绑定验证器, 之后登录需要额外输入动态验证码");
    }

    /**
     * 校验一个会话是否有效
     * @param sessionId 会话标识
     * @return 有效会话，无效时为空
     */
    public Optional<ConfigUiSession> validate(String sessionId) {
        return sessions.validate(sessionId, Instant.now());
    }

    /**
     * 为持有启动令牌的运维通道签发会话
     * <p>
     * 不过限流也不记失败：调用方已经验过令牌，走到这里就说明来人能读到本机的启动日志。
     * @param clientIp 来源 IP
     * @return 新会话
     */
    public ConfigUiSession issueForOperator(String clientIp) {
        return sessions.issue(clientIp, Instant.now());
    }

    /**
     * 注销会话
     */
    public void logout(String sessionId) {
        sessions.revoke(sessionId);
    }

    /**
     * 注销全部会话
     * <p>
     * 用在「怀疑 Cookie 泄漏了」的时候。没有这个出口的话，唯一的收回手段就是重启进程——
     * 而重启会断开全部直播间长连接，正在直播时代价不小。
     * @return 被注销的会话数
     */
    public int logoutAll() {
        int count = sessions.revokeAll();
        log.info("配置界面已注销全部 {} 个登录会话", count);
        return count;
    }

    /**
     * 登录
     * @param password 明文口令
     * @param code 二次验证码，未启用二次验证时忽略
     * @param clientIp 来源 IP
     * @return 登录结果
     */
    public LoginResult login(char[] password, String code, String clientIp) {
        Instant now = Instant.now();

        Duration lockout = throttle.remainingLockout(clientIp, now);
        if (!lockout.isZero()) {
            return LoginResult.lockedOut(lockout);
        }

        // 校验本身很吃 CPU，抢不到名额时直接拒绝而不是排队，否则排队本身就是放大器
        if (!throttle.tryAcquireSlot()) {
            log.warn("配置界面同时进行的登录校验过多, 已拒绝来自 {} 的请求", clientIp);
            return LoginResult.busy();
        }

        try {
            String secret = totpEnabled ? totpSecret : null;
            boolean passwordOk = PasswordHash.verify(password, passwordHash);
            boolean codeOk = secret == null || TotpGenerator.verify(secret, code, now);

            if (!passwordOk || !codeOk) {
                throttle.recordFailure(clientIp, now);
                log.warn("配置界面登录失败, 来源: {}", clientIp);
                return LoginResult.failure();
            }
        } finally {
            throttle.releaseSlot();
        }

        throttle.recordSuccess(clientIp);
        ConfigUiSession session = sessions.issue(clientIp, now);
        log.info("配置界面登录成功, 来源: {}", clientIp);

        return LoginResult.success(session);
    }

    /**
     * 解析配置中的口令
     * <p>
     * 配置文件里既接受哈希串也接受明文。明文只是为了让人能直接填一个密码进去就用起来，
     * 启动时会当场哈希掉，但<b>文件里那份明文仍然摆在那</b>——所以要在日志里把这件事说清楚，
     * 并把可以替换过去的哈希串一并打出来。
     * @param configured 配置值
     * @return 口令哈希，未配置时为 null
     */
    private String resolvePasswordHash(String configured) {
        String password = blankToNull(configured);
        if (password == null) {
            return null;
        }

        if (PasswordHash.isHashed(password)) {
            return password;
        }

        String hashed = PasswordHash.hash(password.toCharArray());
        log.warn("配置界面的登录口令是以明文保存的, 建议改填下面这串哈希, 效果完全相同:");
        log.warn("  starbot.core.config-ui.auth.password: {}", hashed);

        return hashed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 登录结果
     * @param session 登录成功时的会话
     * @param message 失败原因，成功时为 null
     * @param retryAfter 需要等待的时长，未处于锁定时为 {@link Duration#ZERO}
     */
    public record LoginResult(ConfigUiSession session, String message, Duration retryAfter) {
        public boolean success() {
            return session != null;
        }

        static LoginResult success(ConfigUiSession session) {
            return new LoginResult(session, null, Duration.ZERO);
        }

        static LoginResult failure() {
            return new LoginResult(null, INVALID, Duration.ZERO);
        }

        static LoginResult lockedOut(Duration remaining) {
            long minutes = Math.max(1, remaining.toMinutes());
            return new LoginResult(null, "登录失败次数过多，请在 " + minutes + " 分钟后重试", remaining);
        }

        static LoginResult busy() {
            return new LoginResult(null, "服务器正忙，请稍后重试", Duration.ofSeconds(5));
        }
    }
}
