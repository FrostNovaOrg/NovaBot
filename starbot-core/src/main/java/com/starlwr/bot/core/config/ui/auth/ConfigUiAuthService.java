package com.starlwr.bot.core.config.ui.auth;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.ConfigurationFileService;
import com.starlwr.bot.core.config.ui.ConfigurationKeyAliases;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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

    /**
     * 登录口令所在的配置项，明文会在启动时哈希后写回此处
     */
    public static final String PASSWORD_PROPERTY = "starbot.core.config-ui.auth.password";

    /**
     * 二次验证开关所在的配置项
     */
    public static final String TOTP_PROPERTY = "starbot.core.config-ui.auth.totp";

    /**
     * 二次验证密钥所在的配置项
     */
    public static final String TOTP_SECRET_PROPERTY = "starbot.core.config-ui.auth.totp-secret";

    /**
     * 「忘记口令」启动令牌通道开关所在的配置项
     */
    public static final String OPERATOR_TOKEN_PROPERTY = "starbot.core.config-ui.auth.operator-token";

    /**
     * 这一项是不是只能走专用口的认证键
     * <p>
     * 闭集只有四项：口令、二次验证开关、二次验证密钥、「忘记口令」的启动令牌通道。
     * 口令与二次验证改的时候要过旧口令或当前动态码。启动令牌通道打开后，
     * 启动日志会打印一个绕过口令与二次验证的直进地址。
     * 通用保存若直接改，一枚已登录会话就能换掉门或打开那条通道。
     * 同一前缀下日后新增的机密键不会自动算进来——要进专用口，须写进本方法并改对应测试。
     * @param name 配置项名，现行键或旧位置均可
     * @return 是专用口那几项时为 true
     */
    public static boolean isDedicatedAuthKey(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        String current = ConfigurationKeyAliases.currentName(name);
        return PASSWORD_PROPERTY.equals(current)
                || TOTP_PROPERTY.equals(current)
                || TOTP_SECRET_PROPERTY.equals(current)
                || OPERATOR_TOKEN_PROPERTY.equals(current);
    }

    /**
     * 这批键里有没有只能走专用口的认证项
     * @param names 配置项名
     * @return 有则为 true
     */
    public static boolean containsDedicatedAuthKey(Iterable<String> names) {
        if (names == null) {
            return false;
        }
        for (String name : names) {
            if (isDedicatedAuthKey(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 并发闸门拦下时建议等待的时长
     * <p>
     * 这是瞬时拥塞，不是锁定，所以给的是「几秒钟」而不是「几分钟」——
     * 两者若共用一个数量级，界面上就分不出「服务器忙」和「你被锁了」。
     */
    private static final Duration BUSY_RETRY_AFTER = Duration.ofSeconds(5);

    private final ConfigUiSessionStore sessions;

    private final LoginThrottle throttle;

    /**
     * 用于把哈希写回配置文件。测试中可为 null，此时只提示不落盘
     */
    private final ConfigurationFileService fileService;

    /**
     * 口令哈希，未启用口令登录时为 null
     * <p>
     * 可变的：改口令要<b>当场</b>生效。等下次重启才认新口令的话，中间这段时间里
     * 界面说「已改」而门上认的还是旧的那一把——而这件事从界面上完全看不出来。
     */
    private volatile String passwordHash;

    /**
     * 是否要求二次验证
     * <p>
     * 可变的：在设置页开关它要当场生效，理由同上。开与关都得先过一次验证器
     * （开要绑定并输一次码，关要输一次现码），因此这一位不会被误拨。
     */
    private volatile boolean totpEnabled;

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
     * 已用过的动态码时间步。单用户面板只有一个账号，登录与代签发共用这一格。
     * <p>
     * {@code null} 表示还没用过。消费时按单调递增拦：同一格或更早的格一律拒。
     */
    private Long lastUsedTotpStep;

    /**
     * 消费时间步时用的锁，避免两趟同时通过校验后都写上同一格
     */
    private final Object totpConsumeLock = new Object();

    /**
     * 取当前时刻。构造时注入是为了判据能把时间往前拨，
     * <b>而不是靠 sleep 去等一个真实的窗口</b>——那种判据慢到最后一定会被人关掉。
     * <p>
     * 🔴 还有一层更要紧的：不注入的话，<b>判据量的是「这台机器有多快」</b>。
     * 全局桶按传进来的时刻线性回满，而口令校验走的是 PBKDF2，几次校验就是一两秒；
     * 那一两秒足够让桶悄悄回上半个令牌，于是同一条判据在快机器上绿、在慢机器上红。
     * 它当初是靠「记下测试开始前的时刻，最后拿那个更早的时刻去抽桶」绕过去的——
     * 绕得过一次，但那条判据从此量的就不是它声称要量的东西了。
     */
    private final Supplier<Instant> clock;

    public ConfigUiAuthService(StarBotCoreProperties.ConfigUi.Auth properties, ConfigUiSessionStore sessions,
                               LoginThrottle throttle, ConfigurationFileService fileService) {
        this(properties, sessions, throttle, fileService, Instant::now);
    }

    ConfigUiAuthService(StarBotCoreProperties.ConfigUi.Auth properties, ConfigUiSessionStore sessions,
                        LoginThrottle throttle, ConfigurationFileService fileService,
                        Supplier<Instant> clock) {
        this.sessions = sessions;
        this.throttle = throttle;
        this.fileService = fileService;
        this.clock = clock;
        this.passwordHash = resolvePasswordHash(properties.getPassword());
        this.totpEnabled = properties.isTotp();
        this.totpSecret = blankToNull(properties.getTotpSecret());

        if (isEnabled() && this.totpEnabled && this.totpSecret == null) {
            log.warn("配置界面已启用口令登录但尚未绑定验证器, 请在界面上完成绑定");
        }
    }

    /**
     * 是否启用了口令登录
     * <p>
     * 🔴 <b>现算，不是启动时定死的一位。</b>此前它在构造时就固定了，于是在一台还没上锁的机器上
     * 设下第一把口令<b>不会让门换成登录形态</b>——界面说「已上锁」，而访问令牌照旧进得来，
     * 且这件事从界面上看不出任何异常。免配置起步之后这是常态：装好的实例本来就没有口令，
     * <b>第一把口令一定是在运行期设下的</b>。
     * <p>
     * 口令哈希在不在，就是这道门开着还是关着——不另存一位，两处迟早说两种话。
     * @return 启用时为 true
     */
    public boolean isEnabled() {
        return passwordHash != null;
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
        return isEnabled() && totpEnabled && totpSecret == null;
    }

    /**
     * 现在能不能走一遍绑定验证器
     * <p>
     * 与 {@link #totpPending()} 分开：那一问答的是「要不要主动提示他去绑」，
     * 而这一问答的是「他自己点了开关时，绑定这条路走不走得通」。
     * <b>不看 {@code totpEnabled}</b>——设置页里把二次验证从关拨到开，正是要先绑上再开，
     * 拿那一位当前提的话，人得先重启一次才绑得了，而重启会断开全部直播间长连接。
     * @return 可以绑定时为 true
     */
    public boolean canEnrollTotp() {
        return isEnabled() && totpSecret == null;
    }

    /**
     * 二次验证此刻开着没有
     * <p>
     * 与 {@link #totpRequired()} 分开：那一问答的是「这次登录要不要输码」（还得有密钥才算数），
     * 而这一问答的是设置页上那个开关的位置。
     * @return 开着时为 true
     */
    public boolean totpEnabled() {
        return totpEnabled;
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
        return TotpGenerator.verify(secret, code, clock.get()) ? Optional.of(secret) : Optional.empty();
    }

    /**
     * 启用已确认的密钥
     * <p>
     * 顺带把二次验证这一位拨到开：绑定这个动作本身就是「我要用二次验证」的意思，
     * 而两者分开落的话，会出现「绑好了却还是不问码」——那是最难被发现的一种失效，
     * 因为它看起来一切正常。
     * @param secret Base32 密钥
     */
    public void activateTotp(String secret) {
        this.totpSecret = secret;
        this.pendingSecret = null;
        this.totpEnabled = true;
        resetTotpConsume();
        log.info("配置界面已绑定验证器, 之后登录需要额外输入动态验证码");
    }

    /**
     * 校验一个当前有效的验证码
     * <p>
     * 给「关掉二次验证」那条路用：关掉这道防线的人得先证明他此刻手里就有那个验证器。
     * 少了这一步，一枚被偷走的会话 Cookie 就能把二次验证卸掉。
     * @param code 使用者输入的验证码
     * @return 密钥还没绑定或验证码不对时为 false
     */
    public boolean verifyCurrentCode(String code) {
        String secret = totpSecret;
        return secret != null && TotpGenerator.verify(secret, code, clock.get());
    }

    /**
     * 关掉二次验证
     * <p>
     * <b>连密钥一起清掉</b>，不只是把那一位拨到关：留着一个谁也不再用的密钥躺在配置里，
     * 下次重新开启时它会被直接沿用，而使用者以为自己是新绑了一把——
     * 那把「新」的其实是几个月前那把，中间它一直明文躺在盘上。
     */
    public void disableTotp() {
        this.totpSecret = null;
        this.pendingSecret = null;
        this.totpEnabled = false;
        resetTotpConsume();
        log.warn("配置界面已关闭二次验证, 之后登录只校验口令");
    }

    /**
     * 换一个登录口令
     * <p>
     * 只改内存里认的那一把，<b>落盘由调用方办</b>：写文件失败时该不该认新口令，
     * 是一个要由上层决定的问题（认了就出现「重启后又变回旧口令」，不认则界面白改一场），
     * 藏在这里做主的话，两种后果都没人说得清是谁选的。
     * @param hashed 新口令的哈希
     */
    public void applyPasswordHash(String hashed) {
        this.passwordHash = hashed;
        log.info("配置界面的登录口令已更换");
    }

    /**
     * 这一串是不是当前的登录口令
     * <p>
     * 不走 {@link #checkCredentials}：那一支会连二次验证码一起要，而改口令时手边未必有验证器；
     * 它还会消耗猜口令的全局预算，让一次正常的改口令挤掉别人的登录额度。
     * <p>
     * 不另计失败次数是有意的：走到这里的人已经持有一把有效会话，
     * 挡在他前面的那道门是会话本身，不是这一次比对。
     * @param password 明文口令
     * @return 相符时为 true
     */
    public boolean matchesPassword(char[] password) {
        String hash = passwordHash;
        return hash != null && PasswordHash.verify(password, hash);
    }

    /**
     * 注销除某一把之外的全部会话
     * <p>
     * 改口令之后必须走一趟：旧口令下建立的会话仍然畅通的话，「改了口令」这个动作
     * 就没能把可能已经泄漏的访问权收回来。留下当前这一把是为了不把刚改完口令的人
     * 当场踢出去——他手上那一把恰恰是刚刚被验过的。
     * @param keepId 留下的会话标识
     * @return 被注销的会话数
     */
    public int logoutOthers(String keepId) {
        int count = sessions.revokeAllExcept(keepId);
        if (count > 0) {
            log.info("配置界面已注销其余 {} 个登录会话", count);
        }
        return count;
    }

    /**
     * 校验一个会话是否有效
     * @param sessionId 会话标识
     * @return 有效会话，无效时为空
     */
    public Optional<ConfigUiSession> validate(String sessionId) {
        return sessions.validate(sessionId, clock.get());
    }

    /**
     * 为持有启动令牌的运维通道签发会话
     * <p>
     * 不过限流也不记失败：调用方已经验过令牌，走到这里就说明来人能读到本机的启动日志。
     * @param clientIp 来源 IP
     * @return 新会话
     */
    public ConfigUiSession issueForOperator(String clientIp) {
        return sessions.issue(clientIp, clock.get(), ConfigUiSession.Channel.OPERATOR_TOKEN);
    }

    /**
     * 为刚刚设下第一把口令的人签发会话
     * <p>
     * 这一趟他已经写下了口令，不必再登一次。不签发的话，上锁这一刻过滤器切到口令形态，
     * 而令牌形态从来没有过会话 Cookie，下一步接口一律 401，整页刷新落到登录页——
     * 初始设置就断在第一步。
     * @param clientIp 来源 IP
     * @return 新会话
     */
    public ConfigUiSession issueForPassword(String clientIp) {
        return sessions.issue(clientIp, clock.get(), ConfigUiSession.Channel.PASSWORD);
    }

    /**
     * 为通行密钥验过身的人签发会话
     * <p>
     * <b>不经二次验证，这是有意的。</b>二次验证要补的是「口令可能被撞库、被键盘记录、被肩窥」这件事，
     * 而通行密钥的私钥从未离开过使用者的设备，且用它签名之前设备本身已经问过一次指纹或面容——
     * 那一步比一串六位数字强。再要一次验证码，换来的只是「因为麻烦所以干脆不用通行密钥」。
     * <p>
     * 顺带清掉这个来源的失败记录，与口令登录成功时同法：人已经用一把真钥匙证明了身份，
     * 之前那几次输错的口令不该继续压着他。
     * @param clientIp 来源 IP
     * @return 新会话
     */
    public ConfigUiSession issueForPasskey(String clientIp) {
        throttle.recordSuccess(clientIp);
        return sessions.issue(clientIp, clock.get(), ConfigUiSession.Channel.PASSKEY);
    }

    /**
     * 这个来源还要等多久才能再试
     * <p>
     * 通行密钥那条路也要问一次：<b>锁定是按来源计的，不是按凭据种类计的</b>。
     * 各算各的话，正在被爆破口令的那个地址可以转头去猜通行密钥，一次锁定也触发不了。
     * @param clientIp 来源 IP
     * @return 剩余锁定时长，未锁定时为 {@link Duration#ZERO}
     */
    public Duration remainingLockout(String clientIp) {
        return throttle.remainingLockout(clientIp, clock.get());
    }

    /**
     * 记一次失败
     * <p>
     * 给口令之外的登录路子用。与口令那条路<b>共用同一份计数</b>：
     * 分开计等于把同一个来源的可试次数翻倍。
     * @param clientIp 来源 IP
     */
    public void recordFailedAttempt(String clientIp) {
        throttle.recordFailure(clientIp, clock.get());
    }

    /**
     * 二次验证敏感操作开工闸：锁定与全局速率与登录共用同一把桶
     * <p>
     * 关掉、绑定确认都要先过这一关。已登录会话反复猜六位码，不跟登录共用的话
     * 等于给同一把锁开了第二扇门。
     * @param clientIp 来源 IP
     * @return 未锁定且拿到全局额度时 {@link Verdict#OK}
     */
    public CredentialCheck beginSensitiveTotp(String clientIp) {
        Instant now = clock.get();
        Duration lockout = throttle.remainingLockout(clientIp, now);
        if (!lockout.isZero()) {
            return new CredentialCheck(Verdict.LOCKED_OUT, lockout);
        }
        if (!throttle.tryAcquireGlobal(now)) {
            return new CredentialCheck(Verdict.BUSY, BUSY_RETRY_AFTER);
        }
        return new CredentialCheck(Verdict.OK, Duration.ZERO);
    }

    /**
     * 二次验证敏感操作猜错：计入失败，可能触发锁定
     * @param clientIp 来源 IP
     */
    public void failSensitiveTotp(String clientIp) {
        throttle.recordFailure(clientIp, clock.get());
        log.warn("配置界面二次验证敏感操作失败, 来源: {}", clientIp);
    }

    /**
     * 二次验证敏感操作猜对：清掉该来源的失败记录，并把全局额度退还
     * @param clientIp 来源 IP
     */
    public void succeedSensitiveTotp(String clientIp) {
        throttle.recordSuccess(clientIp);
        throttle.refundGlobal();
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
     * 只校验凭据，<b>不签发会话</b>
     * <p>
     * 🔴 存在的理由就是那个「不」字。代签发端点（§五① 契约）要拿口令换一把<b>只读</b>口令，
     * 它若图省事调 {@link #login}，那个方法会<b>顺手签发一个控制台会话并下发 Cookie</b>——
     * 于是只读通道被悄悄升级成完整控制台权限，<b>而功能上完全看不出来</b>（口令照样能用）。
     * <p>
     * 限流、锁定、失败日志与 {@link #login} <b>共用同一份</b>：口令是同一个，
     * 分开计数等于把它的可猜次数翻倍。
     * @param password 明文口令
     * @param code 二次验证码，未启用二次验证时忽略；契约规定不传（字段缺席）而非空串
     * @param clientIp 来源 IP。<b>反代必须转发真实来源</b>，否则所有失败都记进同一个桶，
     *                 而 {@link LoginThrottle} 恰好是刻意不做全局锁定的
     * @return 判定与需要等待的时长
     */
    public CredentialCheck checkCredentials(char[] password, String code, String clientIp) {
        if (!isEnabled()) {
            return new CredentialCheck(Verdict.AUTH_DISABLED, Duration.ZERO);
        }

        Instant now = clock.get();

        Duration lockout = throttle.remainingLockout(clientIp, now);
        if (!lockout.isZero()) {
            return new CredentialCheck(Verdict.LOCKED_OUT, lockout);
        }

        // 全局速率限制，拦的是换着 IP 来的分布式猜口令：每个地址只试两三次，
        // 按 IP 的锁定一次也触发不了。放在锁定判断之后——已经被锁的地址不该再消耗全局预算
        if (!throttle.tryAcquireGlobal(now)) {
            // 用 BUSY 而不是新加一个判定值：Verdict 是对外契约的一部分，
            // 而这确实就是「服务器忙，稍后重试」的语义——桶几秒钟就回，不是锁定
            return new CredentialCheck(Verdict.BUSY, BUSY_RETRY_AFTER);
        }

        // 校验本身很吃 CPU，抢不到名额时直接拒绝而不是排队，否则排队本身就是放大器
        if (!throttle.tryAcquireSlot()) {
            log.warn("配置界面同时进行的登录校验过多, 已拒绝来自 {} 的请求", clientIp);
            // 没真的校验，额度退回去，否则「服务器忙」也在扣猜口令的预算
            throttle.refundGlobal();
            return new CredentialCheck(Verdict.BUSY, BUSY_RETRY_AFTER);
        }

        try {
            String secret = totpEnabled ? totpSecret : null;
            // 两个都算完再判：短路会让「口令对不对」体现在耗时上
            boolean passwordOk = PasswordHash.verify(password, passwordHash);
            Long step = secret == null ? null : TotpGenerator.matchingStep(secret, code, now);
            boolean codeOk = secret == null || step != null;

            if (!passwordOk || !codeOk) {
                throttle.recordFailure(clientIp, now);
                log.warn("配置界面登录失败, 来源: {}", clientIp);
                return new CredentialCheck(Verdict.BAD_CREDENTIALS, Duration.ZERO);
            }

            // 口令错的那一趟不能把码烧掉：上面两个都过了才消费这一格
            if (step != null && !consumeTotpStep(step)) {
                throttle.recordFailure(clientIp, now);
                log.warn("配置界面登录失败, 来源: {}", clientIp);
                return new CredentialCheck(Verdict.BAD_CREDENTIALS, Duration.ZERO);
            }
        } finally {
            throttle.releaseSlot();
        }

        throttle.recordSuccess(clientIp);
        // 校验通过的这一次不算进猜口令的预算：一个人反复进出面板不该把额度耗掉
        throttle.refundGlobal();
        return new CredentialCheck(Verdict.OK, Duration.ZERO);
    }

    /**
     * 登录
     * @param password 明文口令
     * @param code 二次验证码，未启用二次验证时忽略
     * @param clientIp 来源 IP
     * @return 登录结果
     */
    public LoginResult login(char[] password, String code, String clientIp) {
        CredentialCheck check = checkCredentials(password, code, clientIp);

        return switch (check.verdict()) {
            case OK -> {
                ConfigUiSession session = sessions.issue(clientIp, clock.get(), ConfigUiSession.Channel.PASSWORD);
                log.info("配置界面登录成功, 来源: {}", clientIp);
                yield LoginResult.success(session);
            }
            case LOCKED_OUT -> LoginResult.lockedOut(check.retryAfter());
            case BUSY -> LoginResult.busy();
            // AUTH_DISABLED 落到这里只可能是调用方没先问 isEnabled()。
            // 按凭据不符处理——没有校验对象绝不是放行的理由
            default -> LoginResult.failure();
        };
    }

    /**
     * 凭据校验的判定
     * <p>
     * 这几个值里除 {@link #OK} 外都是<b>对外契约的一部分</b>：代签发端点按 {@link #wire()}
     * 原样回给客户端，对侧照它分文案。<b>改动即为契约变更。</b>
     * <p>
     * 🔴 「口令错」与「验证码错」<b>故意合并</b>成 {@link #BAD_CREDENTIALS} 一个值：
     * 分开说等于告诉攻击者口令已经猜对了，二次验证就只剩六位数字要试。
     * <p>
     * 🔴 但 {@link #LOCKED_OUT} <b>必须与它分开</b>：锁定期内输对的口令<b>也会被拒</b>，
     * 此时若显示「口令不对」，人会去重置一个根本没问题的口令。
     */
    public enum Verdict {
        OK(null),
        BAD_CREDENTIALS("bad_credentials"),
        LOCKED_OUT("locked_out"),
        BUSY("busy"),
        AUTH_DISABLED("auth_disabled");

        private final String wire;

        Verdict(String wire) {
            this.wire = wire;
        }

        /**
         * @return 契约里约定的字符串，{@link #OK} 无对应值
         */
        public String wire() {
            return wire;
        }
    }

    /**
     * 凭据校验结果
     * @param verdict 判定
     * @param retryAfter 建议等待时长，无需等待时为 {@link Duration#ZERO}
     */
    public record CredentialCheck(Verdict verdict, Duration retryAfter) {
        public boolean ok() {
            return verdict == Verdict.OK;
        }
    }

    /**
     * 解析配置中的口令
     * <p>
     * 配置文件里既接受哈希串也接受明文。填明文是为了让人直接写个密码进去就能用起来——
     * 启动时会当场哈希掉<b>并写回配置文件</b>，明文不会留在盘上。
     * <p>
     * <b>不能只打一行日志让使用者自己去抄。</b>那串东西有 80 个字符，
     * 手工复制少一位就再也登不进去，而登录时的报错与「口令输错了」一模一样——
     * 人只会以为是自己记错了密码。这个坑真踩过，掉的就是最后一位。
     * @param configured 配置值
     * @return 口令哈希，未配置时为 null
     */
    private String resolvePasswordHash(String configured) {
        String password = blankToNull(configured);
        if (password == null) {
            return null;
        }

        if (PasswordHash.isHashed(password)) {
            String problem = PasswordHash.describeProblem(password);
            if (problem != null) {
                // 不能因此把口令登录关掉——那等于把已开到公网的面板整个敞开。
                // 但也不能一声不吭：残缺的哈希与任何口令都对不上，
                // 而登录时的报错与「口令输错了」一模一样
                log.error("配置界面的口令哈希不完整（{}），任何口令都无法登录", problem);
                log.error("请在配置界面重新设置一次登录口令；");
                log.error("此时仍可凭启动令牌进入面板，地址见下方日志");
            }
            return password;
        }

        String hashed = PasswordHash.hash(password.toCharArray());
        persistHash(hashed);

        return hashed;
    }

    /**
     * 把哈希写回配置文件，替换掉那份明文
     * <p>
     * 写不进去也要继续跑：口令本身是有效的，登录不受影响，
     * 只是文件里还留着明文——那是要提醒使用者的事，不是要拦住启动的事。
     */
    private void persistHash(String hashed) {
        if (fileService == null) {
            log.warn("配置界面的登录口令仍以明文保存在配置文件中");
            return;
        }

        try {
            fileService.write(Map.of(PASSWORD_PROPERTY, hashed));
            log.info("配置界面的登录口令已改为哈希保存, 配置文件中不再有明文");
        } catch (Exception e) {
            log.warn("配置界面的登录口令未能改为哈希保存, 文件中仍是明文: {}", e.getMessage());
        }
    }

    /**
     * 记下这一格已经用过。同一格或更早的格再来一律拒。
     * @param step 命中的时间步
     * @return 消费成功为 true，已经用过为 false
     */
    private boolean consumeTotpStep(long step) {
        synchronized (totpConsumeLock) {
            if (lastUsedTotpStep != null && step <= lastUsedTotpStep) {
                return false;
            }
            lastUsedTotpStep = step;
            return true;
        }
    }

    /**
     * 密钥换了或二次验证关了，已用过的格作废
     */
    private void resetTotpConsume() {
        synchronized (totpConsumeLock) {
            lastUsedTotpStep = null;
        }
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
            return new LoginResult(null, "服务器正忙，请稍后重试", BUSY_RETRY_AFTER);
        }
    }
}
