package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.ResponseCodeException;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.bilibili.util.BilibiliCookieRefreshUtil;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.util.QrCodeUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 哔哩哔哩账号服务
 * <p>
 * 负责登录凭据的加载、扫码登录与凭据持久化。凭据默认加密存储，详见 {@link BilibiliCredentialStore}。
 * <p>
 * 容器里只有一个实例，管的是监听用的那个账号。别的插件要另用一个账号时，自己 new 一个，
 * 配上自己的接口工具、自己的凭据存储与一份 {@link Profile}：扫码、复检、续期、存盘这一整套照搬，
 * 两个实例之间不共享任何状态。
 */
@Slf4j
@NovaComponent
public class BilibiliAccountService {
    /**
     * 账号用途上的几处差别
     * <p>
     * 扫码、复检、续期、存盘对每个哔哩哔哩账号都一样，不一样的只有这几处：日志里怎么称呼它、
     * 凭据失效那一刻的告警说什么、一次扫码最多换几张二维码、扫出来的号要不要先过一道关。
     * 监听账号用 {@link #LISTENER}，行为与这份口径出现之前一字不差。
     * @param logPrefix 每条日志的前缀，监听账号为空串
     * @param loggedOutWarning 凭据失效那一刻的告警
     * @param qrCodeRounds 一次扫码流程最多换几张二维码，0 表示一直换到登录成功为止
     * @param loginGate 扫码成功之后、凭据存盘之前对这个号的检查，入参是它的 uid（取不到时为 null）；
     *                  返回非空即拒绝这次登录，内容是说给使用者的理由。为 null 表示不检查
     */
    public record Profile(String logPrefix, String loggedOutWarning, int qrCodeRounds,
                          Function<Long, Optional<String>> loginGate) {
        public Profile {
            logPrefix = logPrefix == null ? "" : logPrefix;
        }

        /**
         * 监听账号的口径
         */
        public static final Profile LISTENER = new Profile("",
                "哔哩哔哩登录凭据已失效, 动态推送与自动关注将停止; 直播推送不依赖登录态, 不受影响。请重新扫码登录",
                0, null);
    }

    /**
     * 只用已保存的凭据登录的结果，见 {@link #restoreSaved()}
     */
    public enum SavedLogin {
        /**
         * 没有保存的凭据，什么都没做
         */
        NONE,
        /**
         * 凭据有效，已登录
         */
        LOGGED_IN,
        /**
         * 凭据暂时验不了（网络不通之类），已保留并按已登录继续
         */
        UNVERIFIED,
        /**
         * 服务端明确答复未登录：凭据已失效
         */
        EXPIRED
    }
    /**
     * 匿名模式的如实说明
     * <p>
     * <b>启动日志、运行状态页与用户手册用的是同一份文本，就是这一份。</b>
     * 分成三处各写一遍的话，改了一处忘了另一处，界面上说的和文档里说的就会不一样——
     * 而这段话的全部意义就在于它得是真的。
     * <p>
     * 措辞是被要求过的：不许把匿名模式描述成「功能一致的免登录版」。
     * <p>
     * <b>「约一成」是实测数字，不是估计</b>：对独立 HTTP 基准，个人主播的直播间七格
     * 落在 8.8% ~ 12.5%（虚拟、娱乐、网游三个一级分区，2026-08-09 ~ 08-10）。
     * 官方赛事转播房是例外（两格 99%+），但**使用者要监听的几乎一定是个人主播**，
     * 所以这句话按一成说；例外放在手册里讲，不塞进这条通知。
     * <p>
     * 早先这里写的是「部分房间的弹幕可能被服务端限制下发（实测已证实）」——
     * 「部分房间」「可能」都太软，读起来像个别情况下的小概率事件，
     * 而实测是<b>个人房普遍只有一成</b>。给出数字才拦得住「那我就不登录了」这个决定。
     */
    public static final String ANONYMOUS_NOTICE =
            "匿名模式：个人主播的直播间实测只能拿到约一成弹幕（七格实测 8.8%~12.5%），"
                    + "拿得到的弹幕里发送者 uid 会被抹成 0、昵称只留首字（形如 b***）；"
                    + "动态推送与自动关注不可用。需要完整数据请配置登录";

    /**
     * 二维码矩阵边长
     * <p>
     * 该值是矩阵的模块数而非缩放倍数：登录链接约需 45 个模块，加上静默区后至少需要 55，
     * 取 62 留出余量；核心的二维码工具要求此值为偶数，打印时每两行合并为一行字符。
     */
    private static final int QR_CODE_SIZE = 62;

    /**
     * 轮询登录状态的间隔
     */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    /**
     * 单个二维码的有效时长，超时后重新申请
     */
    private static final Duration QR_CODE_TTL = Duration.ofMinutes(3);

    /**
     * TV 端登录令牌的提前续期时长
     * <p>
     * 令牌默认有效 180 天，提前 30 天续期留足冗余：即便程序停机数周，
     * 回来后也仍在可续期的窗口内，不至于沦落到重新扫码。
     */
    private static final Duration APP_TOKEN_REFRESH_AHEAD = Duration.ofDays(30);

    private final BilibiliApiUtil api;

    private final BilibiliCredentialStore store;

    private final NovaBilibiliProperties.Account properties;

    /**
     * 本实例的用途口径
     */
    private final Profile profile;

    /**
     * 日志前缀，取自 {@link #profile}
     */
    private final String prefix;

    /**
     * 当前待扫描的二维码内容，供登录页面展示
     */
    @Getter
    private volatile String pendingQrCodeContent;

    /**
     * 最近一次扫码流程为什么没登上，给界面说明用；开始新一轮扫码、或登录成功时清空
     * <p>
     * 只有带次数上限或带检查的口径才会写它：监听账号的扫码一直换码到登录成功为止，用不上这一句。
     */
    @Getter
    private volatile String loginNotice;

    /**
     * 是否有人要求中止正在进行的扫码，见 {@link #cancelQrCodeLogin()}
     */
    private volatile boolean qrCodeCancelled;

    /**
     * 是否已登录
     */
    @Getter
    private volatile boolean loggedIn;

    /**
     * 当前登录账号的 uid
     */
    @Getter
    private volatile Long loginUid;

    /**
     * 停机信号
     * <p>
     * 扫码登录是一个可能持续数分钟的轮询循环，若不感知停机就会一直占着调度线程。
     * Spring 在停止各生命周期 Bean 之前会先发布 {@link ContextClosedEvent}，此处借该事件
     * 放行闭锁，使循环中的等待立即返回，从而在收到 SIGTERM 后迅速退出。
     */
    private final CountDownLatch shutdownSignal = new CountDownLatch(1);

    /**
     * 是否已有扫码登录流程在进行
     */
    private final AtomicBoolean loginInProgress = new AtomicBoolean();

    @Autowired
    public BilibiliAccountService(BilibiliApiUtil api, BilibiliCredentialStore store,
                                  NovaBilibiliProperties properties) {
        this(api, store, properties.getAccount(), Profile.LISTENER);
    }

    /**
     * 另用一个账号时的构造
     * <p>
     * 接口工具与凭据存储都要是这个账号自己的：共用监听账号那一份的话，凭据、设备标识、
     * 签名与风控冷却就成了两个账号共有的一份，一边出事另一边跟着出事。
     * @param api 本账号自己的接口工具
     * @param store 本账号自己的凭据存储
     * @param account 本账号的账号配置（复检间隔、续期、扫码方式等）
     * @param profile 本账号的用途口径
     */
    public BilibiliAccountService(BilibiliApiUtil api, BilibiliCredentialStore store,
                                  NovaBilibiliProperties.Account account, Profile profile) {
        this.api = api;
        this.store = store;
        this.properties = account;
        this.profile = profile == null ? Profile.LISTENER : profile;
        this.prefix = this.profile.logPrefix();

        // 基准取复检间隔：一切正常时两件事都按它走，与改动前的行为一致。
        // 复检间隔配成 0（关闭复检）时这里会退化成 1 秒，但那种配置下
        // 定时任务压根不会被登记，退避也就不会被问到
        Duration base = Duration.ofSeconds(Math.max(1, this.properties.getVerifyInterval()));
        Duration cap = Duration.ofSeconds(Math.max(0, this.properties.getMaintenanceBackoffCap()));
        this.verifyBackoff = new BilibiliStagedBackoff(base, cap);
        this.refreshBackoff = new BilibiliStagedBackoff(base, cap);
    }

    /**
     * 复检自己的退避。与续期那个各记各的失败次数——
     * 出网劣化时续期查不动，不该把复检也拖慢，那是判断「凭据还在不在」的唯一途径
     */
    private final BilibiliStagedBackoff verifyBackoff;

    /**
     * 续期自己的退避
     */
    private final BilibiliStagedBackoff refreshBackoff;

    /**
     * 最近一次续期尝试是否真的完成了续期
     * <p>
     * 只为保住 {@link #refreshCookiesIfNeeded()} 原来的返回语义而存在：
     * 那个方法对外的含义是「有没有完成一次续期」，与退避要的「尝试成不成功」不是一回事
     */
    private boolean refreshed;

    /**
     * 上次成功完成登录态复检的时间，从未复检成功时为空
     */
    @Getter
    private volatile Instant lastVerifiedAt;

    /**
     * 是否正在停机
     * @return 是否正在停机
     */
    public boolean isStopping() {
        return shutdownSignal.getCount() == 0;
    }

    /**
     * 复检登录态
     * <p>
     * 凭据有其有效期，进程长时间运行后可能在无人察觉的情况下失效。此前登录态只在启动时判定一次，
     * 且没有任何地方会将其置回，导致凭据过期后动态推送静默停摆——本方法即为消除该静默失败而设。
     * <p>
     * 仅在服务端明确返回「账号未登录」时才判定为失效。网络故障一律维持原状态，
     * 否则一次抖动就会触发误报，反而稀释了告警的价值。
     * @return 复检后的登录态
     */
    public boolean verify() {
        verifyOnce();
        return loggedIn;
    }

    /**
     * 复检一次并如实报出「这次尝试成不成功」
     * <p>
     * <b>与登录态是两件事。</b>「问出了明确的未登录」是一次<b>成功</b>的复检——
     * 我们拿到了答案；而「网络不通」才是失败。{@link #verify()} 返回的是前者（状态），
     * 退避需要的是后者（尝试结果），拿状态当结果会让「凭据失效」被误当成故障而不断退避，
     * 于是恰好在最该密切观察的时候把复检拉稀。
     * @return 本次尝试的结果
     */
    private MaintenanceOutcome verifyOnce() {
        if (isStopping()) {
            return MaintenanceOutcome.SKIPPED;
        }

        try {
            Long uid = api.fetchLoginUid();
            lastVerifiedAt = Instant.now();

            if (uid == null) {
                markLoggedOut();
                return MaintenanceOutcome.OK;
            }

            Long previousUid = this.loginUid;
            this.loginUid = uid;
            if (!loggedIn) {
                this.loggedIn = true;
                log.info("{}哔哩哔哩登录态已恢复, uid: {}", prefix, uid);
            } else if (previousUid == null) {
                // 凭据暂未确认的那条路上 loggedIn 已是 true，补上 uid 这一刻若无日志，
                // 「已确认」在工程日志里就无声无息
                log.info("{}登录凭据已确认, uid: {}", prefix, uid);
            }
            return MaintenanceOutcome.OK;
        } catch (ResponseCodeException e) {
            if (e.getCode() == BilibiliApiUtil.CODE_NOT_LOGGED_IN) {
                lastVerifiedAt = Instant.now();
                markLoggedOut();
                return MaintenanceOutcome.OK;
            }

            log.warn("{}登录态复检返回未预期的错误代码 {}, 暂维持原状态: {}", prefix, e.getCode(), e.getMessage());
            return MaintenanceOutcome.FAILED;
        } catch (Exception e) {
            log.debug("{}登录态复检失败, 疑为网络故障, 暂维持原状态: {}", prefix, e.getMessage());
            return MaintenanceOutcome.FAILED;
        }
    }

    /**
     * 当前凭据是否具备自动续期的条件
     * <p>
     * 刷新口令只在登录成功那一刻由服务端下发一次，实测确有下发空值的情况。缺失时自动续期会
     * 一直静默跳过，凭据到期后表现为「某天突然掉登录」，因此该状态需要能被健康探针读到。
     * @return 是否可自动续期
     */
    public boolean isRefreshable() {
        return api.getCookies().isRefreshable();
    }

    /**
     * 当前凭据的到期时刻，答不上时为空
     * <p>
     * 只有 TV 端扫码登录会拿到一个确定的到期时刻（登录令牌，默认 180 天），Web 端那条路
     * 服务端不下发到期时间——此时返回空，而不是估一个出来。界面上「还剩几天」宁可不显示，
     * 也不能显示一个从第一天起就是错的数字：使用者恰恰照着它决定什么时候去重新扫码。
     * @return 到期时刻
     */
    public Optional<Instant> credentialExpiresAt() {
        Long expiresAt = api.getCookies().getAccessTokenExpiresAt();
        return expiresAt == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(expiresAt));
    }

    /**
     * 凭据续期状况的一句话
     * <p>
     * 答的是「到期之后会不会自己续上」。缺刷新口令那一档必须说出来：自动续期会一直静默跳过，
     * 表现是某天突然掉登录，而在那之前界面上一切正常。
     * @return 续期状况
     */
    public String credentialNote() {
        if (!properties.isAutoRefreshCookie()) {
            return "自动续期已关，凭据到期后需要重新扫码";
        }
        if (!isRefreshable()) {
            return "本次登录没拿到刷新口令，自动续期用不上，凭据到期后需要重新扫码";
        }
        return "自动续期已开，到期前会自己续上";
    }

    /**
     * 例行维护：先复检登录态，登录态正常时再按需续期 Cookie
     * <p>
     * 两件事共用一个周期：续期只在登录态正常时才有意义，掉登录后再怎么续也是徒劳。
     * @return 复检后的登录态
     */
    public boolean maintain() {
        return maintain(Instant.now());
    }

    /**
     * 例行维护，时刻由调用方给
     * <p>
     * 供测试用固定时刻推进退避的级数，不必 sleep。
     * @param now 当前时刻
     * @return 复检后的登录态
     */
    boolean maintain(Instant now) {
        if (verifyBackoff.due(now)) {
            record(verifyBackoff, verifyOnce(), now, "登录态复检");
        }

        // 续期只在登录态正常时才有意义：掉登录后再怎么续也是徒劳。
        // 这道门与退避是两回事——门管「该不该做」，退避管「什么时候再试」
        if (loggedIn && refreshBackoff.due(now)) {
            record(refreshBackoff, refreshOnce(), now, "Cookie 续期");
        }

        return loggedIn;
    }

    /**
     * 把一次尝试的结果记进对应的退避
     * <p>
     * {@code SKIPPED} 既不算成功也不算失败：压根没做的事不该改变重试节奏。
     * 若把它当成功，关掉自动续期的部署会每个周期都白跑一遍判断；
     * 若把它当失败，间隔会因为一个「本来就不做」的开关被推到上限。
     */
    private void record(BilibiliStagedBackoff backoff, MaintenanceOutcome outcome, Instant now, String what) {
        switch (outcome) {
            case OK -> backoff.succeeded(now);
            case FAILED -> {
                Duration delay = backoff.failed(now);
                // 连续失败次数与下次间隔一起写出来：只说「失败了」的日志，
                // 看的人无法判断它是偶发抖动还是已经退到上限
                log.warn("{}{}连续失败 {} 次, {} 秒后再试", prefix,
                        what, backoff.getConsecutiveFailures(), delay.toSeconds());
            }
            case SKIPPED -> {
            }
        }
    }

    /**
     * 一次维护尝试的结果，只用于决定「下次什么时候再试」
     * <p>
     * 与业务结果分开：复检问出「已掉登录」是一次成功的复检，续期问出「不需要续期」
     * 也是一次成功的续期检查。<b>把业务结果当尝试结果，是这两处原本的毛病。</b>
     */
    private enum MaintenanceOutcome {
        /**
         * 做成了，或者服务端明确答复「不需要做」
         */
        OK,
        /**
         * 压根没做：停机中、开关关着、缺刷新口令
         */
        SKIPPED,
        /**
         * 试了没成：网络不通、未预期错误码、续期链路失败
         */
        FAILED
    }

    /**
     * 按需续期 Cookie
     * <p>
     * 哔哩哔哩自 2023 年起会随敏感接口调用逐步作废 Web 端凭据，官方页面为此提供了续期链路。
     * 不续期的结果是凭据某天突然失效、动态推送静默停摆，只能重新扫码。
     * <p>
     * <b>整条链路按「失败即维持原状」设计</b>，因为续期不可回退：
     * <ol>
     *   <li>服务端说不需要续期就直接返回，不主动多做</li>
     *   <li>换到新凭据后先做一次真实调用验证，验证不过立刻换回旧凭据</li>
     *   <li>只有验证通过、且新凭据已成功落盘之后，才去作废旧凭据</li>
     * </ol>
     * 因此中途任一步失败，账号都仍持有一份可用的旧凭据。
     * @return 是否完成了一次续期
     */
    public boolean refreshCookiesIfNeeded() {
        return refreshOnce() == MaintenanceOutcome.OK && refreshed;
    }

    /**
     * 按需续期一次并如实报出「这次尝试成不成功」
     * <p>
     * 原来那个 {@code boolean} 返回值把六种情形压成了一个 {@code false}：停机中、
     * 关了自动续期、没有刷新口令、查询失败、服务端说不需要、续期本身失败。
     * <b>其中只有两种是失败</b>，而退避若按 {@code false} 升级，
     * 「服务端说不需要续期」这个最常见的正常情形会把间隔一路推到上限。
     * @return 本次尝试的结果
     */
    private MaintenanceOutcome refreshOnce() {
        refreshed = false;

        if (isStopping() || !properties.isAutoRefreshCookie()) {
            return MaintenanceOutcome.SKIPPED;
        }

        Cookies current = api.getCookies();
        if (!current.isRefreshable()) {
            // 旧版本保存的凭据里没有刷新口令，只能等下次扫码时补上，不必反复告警
            log.debug("{}当前凭据缺少持久化刷新口令, 跳过 Cookie 续期", prefix);
            return MaintenanceOutcome.SKIPPED;
        }

        // TV 端登录取得的凭据走 oauth2 续期，与 Web 端的接口和参数完全不同
        if (current.isAppRefreshable()) {
            refreshed = refreshAppTokenIfNeeded(current);
            return MaintenanceOutcome.OK;
        }

        BilibiliApiUtil.CookieRefreshHint hint;
        try {
            hint = api.checkCookieRefresh();
        } catch (Exception e) {
            log.debug("{}查询 Cookie 续期状态失败: {}", prefix, e.getMessage());
            return MaintenanceOutcome.FAILED;
        }

        if (!hint.needed()) {
            return MaintenanceOutcome.OK;
        }

        log.info("{}哔哩哔哩提示当前凭据需要续期, 开始续期", prefix);
        refreshed = doRefresh(current, hint.timestamp());
        // 续期没做成是失败：这一步不可回退，做不成时旧凭据仍在手上，
        // 但下次不该立刻再试——真正续不动时反复调这条链路只是给风控送素材
        return refreshed ? MaintenanceOutcome.OK : MaintenanceOutcome.FAILED;
    }

    /**
     * TV 端凭据的 oauth2 续期
     * <p>
     * 令牌默认有效 180 天，在到期前一段时间提前续期即可，无须每次复检都请求接口。
     * 与 Web 端续期一样遵循「验证通过再落盘」的顺序，中途失败不会让账号失去可用凭据；
     * 唯独落盘这一步失败时不回退——平台侧已换发，回退可能当场掉线，新令牌留在内存里接着用。
     * @param current 当前凭据
     * @return 是否完成了一次续期（含成功落盘）
     */
    private boolean refreshAppTokenIfNeeded(Cookies current) {
        Long expiresAt = current.getAccessTokenExpiresAt();
        if (expiresAt != null && Instant.now().isBefore(Instant.ofEpochMilli(expiresAt).minus(APP_TOKEN_REFRESH_AHEAD))) {
            return false;
        }

        log.info("{}哔哩哔哩登录令牌即将到期, 开始续期", prefix);

        Cookies refreshed;
        try {
            refreshed = api.refreshAppToken();
        } catch (Exception e) {
            // 走到这里旧凭据尚未被动过，仍然可用
            log.warn("{}登录令牌续期失败, 继续使用原凭据: {}", prefix, e.getMessage());
            return false;
        }

        api.setCookies(refreshed);
        try {
            if (api.fetchLoginUid() == null) {
                throw new IllegalStateException("新凭据无法取得账号信息");
            }
        } catch (Exception e) {
            api.setCookies(current);
            log.error("{}登录令牌续期后的新凭据验证失败, 已回退至原凭据: {}", prefix, e.getMessage());
            return false;
        }

        if (!store.save(refreshed)) {
            // 平台侧已换发新令牌，旧令牌还能不能用无从得知：回退可能当场掉线，所以不回退，
            // 新令牌留在内存里接着用。但盘上仍是旧令牌，重启后要重新扫码。
            // 返回 false（本次续期未完成）而不当成失败去退避：内存里的新令牌尚未到期，
            // 下次检查自然跳过，不会形成紧循环；重启后 load 回旧令牌，到窗口自然会再续
            log.error("{}登录令牌续期的新令牌存盘失败, 重启后可能要重新扫码", prefix);
            return false;
        }
        log.info("{}登录令牌续期完成", prefix);
        return true;
    }

    /**
     * 执行一次续期
     * @param current 当前凭据
     * @param timestamp 服务端返回的毫秒时间戳
     * @return 是否续期成功
     */
    private boolean doRefresh(Cookies current, long timestamp) {
        String oldRefreshToken = current.getRefreshToken();

        Cookies refreshed;
        try {
            String correspondPath = BilibiliCookieRefreshUtil.correspondPath(timestamp);
            String refreshCsrf = api.getRefreshCsrf(correspondPath);
            refreshed = api.refreshCookies(refreshCsrf, oldRefreshToken);
        } catch (Exception e) {
            // 走到这里旧凭据尚未被动过，仍然可用
            log.warn("{}Cookie 续期失败, 继续使用原凭据: {}", prefix, e.getMessage());
            return false;
        }

        api.setCookies(refreshed);
        try {
            if (api.fetchLoginUid() == null) {
                throw new IllegalStateException("新凭据无法取得账号信息");
            }
        } catch (Exception e) {
            // 新凭据不可用，换回旧的。此时旧凭据尚未被作废，账号不会因此掉登录
            api.setCookies(current);
            log.error("{}Cookie 续期后的新凭据验证失败, 已回退至原凭据: {}", prefix, e.getMessage());
            return false;
        }

        // 先落盘再作废旧凭据：反过来的话，一旦此刻进程退出，新凭据没存下、旧凭据又已失效，只能重新扫码
        if (!store.save(refreshed)) {
            // 新凭据没存上盘。此刻不能作废旧凭据——盘上留的只有旧凭据，作废了它，重启后就掉登录。
            // 换回旧凭据继续用，内存与盘上一致，都是还有效的旧凭据；下次维护再试整个续期
            api.setCookies(current);
            log.error("{}Cookie 续期的新凭据存盘失败, 未作废旧凭据, 已回退至原凭据继续使用", prefix);
            return false;
        }

        try {
            api.confirmCookieRefresh(oldRefreshToken);
            log.info("{}Cookie 续期完成", prefix);
        } catch (Exception e) {
            // 新凭据已生效，只是旧凭据没能及时作废。如实说明而不是笼统报失败
            log.warn("{}Cookie 续期已生效, 但作废旧凭据失败, 旧凭据将保持有效直至自然过期: {}", prefix, e.getMessage());
        }

        return true;
    }

    /**
     * 标记为已掉登录，仅在状态发生翻转时告警，避免每个复检周期重复刷屏
     */
    private void markLoggedOut() {
        if (!loggedIn) {
            return;
        }

        this.loggedIn = false;
        this.loginUid = null;
        log.warn("{}{}", prefix, profile.loggedOutWarning());
    }

    /**
     * 接收停机信号，中止仍在进行的登录流程
     */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        shutdownSignal.countDown();
    }

    /**
     * 是否以匿名模式运行
     * @return 是否为匿名模式
     */
    public boolean isAnonymous() {
        return properties.isAnonymous();
    }

    /**
     * 登录
     * <p>
     * 优先使用已保存的凭据：凭据缺失、或服务端明确答复未登录时转为扫码登录；凭据只是暂时
     * 无法验证（如网络故障）时保留凭据按已登录继续，真假交由定期复检确认。
     * <p>
     * 匿名模式下直接返回：<b>连已保存的凭据都不读</b>。读了就不叫匿名了——
     * 开着这个开关跑出来的数据必须与「这台机器上根本没有凭据」完全一致，
     * 否则拿它做的匿名对照实验测的就不是匿名。
     * @return 是否登录成功，匿名模式与因停机而中止时返回 false
     */
    public boolean login() {
        api.init();

        if (isAnonymous()) {
            log.warn(ANONYMOUS_NOTICE);
            return false;
        }

        Optional<Cookies> saved = store.load();
        if (saved.isPresent() && saved.get().isComplete()) {
            api.setCookies(saved.get());

            try {
                Long uid = api.fetchLoginUid();

                if (uid != null) {
                    this.loginUid = uid;
                    this.loggedIn = true;
                    log.info("{}已使用保存的登录凭据登录, uid: {}", prefix, uid);
                    logCredentialCapability(api.getCookies());
                    return true;
                }

                // 接口通了却没给出账号身份，与网络故障一样属于「这次没问到答案」
                return proceedWithUnverifiedCredentials("接口未返回账号身份");
            } catch (ResponseCodeException e) {
                if (e.getCode() != BilibiliApiUtil.CODE_NOT_LOGGED_IN) {
                    return proceedWithUnverifiedCredentials("接口返回未预期的错误代码 " + e.getCode());
                }

                log.warn("{}保存的登录凭据已失效, 需要重新扫码登录", prefix);
                store.clear();
                return loginByQrCode();
            } catch (Exception e) {
                return proceedWithUnverifiedCredentials(
                        "接口调用失败(" + e.getClass().getSimpleName() + "): " + e.getMessage());
            }
        }

        // 扫码之前直播采集是不会启动的，而这一等可以是无限久。只想要直播数据的人
        // 不该被卡在这里却猜不到有别的路——把那条路当场说出来
        log.info("{}尚无登录凭据。若只需要直播弹幕、礼物等数据而不需要动态推送, "
                + "可以把 novabot.bilibili.account.anonymous 设为 true 免登录启动, "
                + "代价见该配置项的说明", prefix);

        return loginByQrCode();
    }

    /**
     * 只用已保存的凭据登录，不扫码
     * <p>
     * 给「平时不登录也行」的账号用。与 {@link #login()} 有三处不同：
     * <ol>
     *   <li>没有保存的凭据时什么都不做，<b>一个请求都不发</b>——连设备标识与签名都不去取</li>
     *   <li>不转扫码：要不要扫码由使用者在界面上决定</li>
     *   <li>服务端明确答复未登录时不删凭据文件：文件留着，才答得出「这个号登录过、后来失效了」，
     *       删不删由使用者退出登录时决定</li>
     * </ol>
     * 凭据只是暂时验不了时的口径与 {@link #login()} 相同：保留，按已登录继续。
     * @return 这次的结果
     */
    public SavedLogin restoreSaved() {
        Optional<Cookies> saved = store.load();
        if (saved.isEmpty() || !saved.get().isComplete()) {
            return SavedLogin.NONE;
        }

        api.init();
        api.setCookies(saved.get());

        try {
            Long uid = api.fetchLoginUid();
            if (uid != null) {
                this.loginUid = uid;
                this.loggedIn = true;
                this.lastVerifiedAt = Instant.now();
                log.info("{}已使用保存的登录凭据登录, uid: {}", prefix, uid);
                logCredentialCapability(api.getCookies());
                return SavedLogin.LOGGED_IN;
            }
            proceedWithUnverifiedCredentials("接口未返回账号身份");
            return SavedLogin.UNVERIFIED;
        } catch (ResponseCodeException e) {
            if (e.getCode() != BilibiliApiUtil.CODE_NOT_LOGGED_IN) {
                proceedWithUnverifiedCredentials("接口返回未预期的错误代码 " + e.getCode());
                return SavedLogin.UNVERIFIED;
            }
            this.lastVerifiedAt = Instant.now();
            log.warn("{}保存的登录凭据已失效, 需要重新扫码登录", prefix);
            return SavedLogin.EXPIRED;
        } catch (Exception e) {
            proceedWithUnverifiedCredentials("接口调用失败(" + e.getClass().getSimpleName() + "): " + e.getMessage());
            return SavedLogin.UNVERIFIED;
        }
    }

    /**
     * 中止正在进行的扫码
     * <p>
     * 下一次轮询（至多几秒后）就停，不再换新的二维码；没有扫码在进行时什么都不做，
     * 不会把下一次扫码也一并作废。
     */
    public void cancelQrCodeLogin() {
        if (loginInProgress.get()) {
            qrCodeCancelled = true;
        }
    }

    /**
     * 是否有扫码流程正在进行
     * @return 有流程在进行时返回 true
     */
    public boolean isQrCodeLoginInProgress() {
        return loginInProgress.get();
    }

    /**
     * 保存的凭据暂时无法验证时的处理：保留凭据，按已登录继续启动
     * <p>
     * 「无法验证」包括网络故障、接口异常与未预期的业务错误代码——它们都只说明这次没问到
     * 答案，不说明凭据失效。凭据文件是扫码登录的唯一成果，删掉它就得重新扫码，而无人值守的
     * 机器上没人扫，动态推送会一直停到有人来。所以凭据的去留只听服务端的明确答复：没有答复
     * 就先留着，按已登录启动。网络恢复后由补问或定期复检给出真实结论，届时若确已失效，自会按
     * 既有告警提示重新扫码。uid 此时未知，首次确认成功后补上。
     * @param reason 验证未成的原因，写进日志
     * @return 恒为 true——按已登录继续
     */
    private boolean proceedWithUnverifiedCredentials(String reason) {
        this.loggedIn = true;
        log.warn("{}暂时无法验证保存的登录凭据（{}）, 已保留凭据并按已登录启动, 网络恢复后会自动确认", prefix, reason);
        // 这次验证同样没问到答案，照旧在「获取登录账号失败」计一笔，口径与 getLoginUid 相同：
        // 只有明确未登录不记，而那一档走的是转扫码那条路，到不了这里
        api.recordLoginUidFailure("MY_INFO_API:unverified:" + reason);
        logCredentialCapability(api.getCookies());
        return true;
    }

    /**
     * 扫码登录
     * <p>
     * 同一时刻只允许一个扫码流程：退出登录后会重新发起登录，若此时已有流程在跑，
     * 两个流程会各自申请二维码并互相覆盖 {@link #pendingQrCodeContent}，界面上就会出现
     * 扫了却不生效的二维码。
     * @return 是否登录成功，因停机或已有流程在进行而中止时返回 false
     */
    public boolean loginByQrCode() {
        if (isAnonymous()) {
            // 界面上的「退出登录」会顺手发起新一轮扫码，匿名模式下必须在这里挡住，
            // 否则点一下就凭空冒出个二维码，扫完还跟配置说的不是一回事
            log.info("{}当前为匿名模式, 已忽略扫码登录请求; 要登录请先关闭 novabot.bilibili.account.anonymous", prefix);
            return false;
        }

        if (!loginInProgress.compareAndSet(false, true)) {
            log.debug("{}已有扫码登录流程正在进行, 忽略本次请求", prefix);
            return loggedIn;
        }

        qrCodeCancelled = false;
        loginNotice = null;
        try {
            return doLoginByQrCode();
        } finally {
            loginInProgress.set(false);
        }
    }

    /**
     * 这一轮扫码是否该就此结束：停机、被中止、或扫出来的号被拒
     * @return 该结束时返回 true
     */
    private boolean qrCodeFlowEnded() {
        return isStopping() || qrCodeCancelled || loginNotice != null;
    }

    /**
     * 当前是否使用 TV 端扫码登录
     * @return 是否为 TV 端方式
     */
    private boolean isTvLoginMode() {
        return !"web".equalsIgnoreCase(properties.getQrCodeLoginMode());
    }

    /**
     * 执行扫码登录
     * @return 是否登录成功
     */
    private boolean doLoginByQrCode() {
        boolean tvMode = isTvLoginMode();
        if (!tvMode) {
            log.warn("{}扫码登录方式为 web, 服务端不会下发可用的刷新口令, 凭据到期后需重新扫码; "
                    + "如需自动续期请将 novabot.bilibili.account.qr-code-login-mode 改回 tv", prefix);
        }

        int rounds = 0;
        while (!loggedIn && !isStopping()) {
            // 带上限的口径换满了码就收手：二维码不再换新，也就不再向平台发请求，
            // 要再登录得有人再点一次。监听账号的上限是 0，照旧一直换到登录成功为止
            if (profile.qrCodeRounds() > 0 && rounds >= profile.qrCodeRounds()) {
                this.pendingQrCodeContent = null;
                this.loginNotice = "二维码已过期，未登录";
                log.info("{}登录二维码已过期, 本次扫码登录结束", prefix);
                return false;
            }
            rounds++;

            BilibiliApiUtil.QrCodeLogin qrCode;
            try {
                qrCode = tvMode ? api.getTvQrCodeLoginInfo() : api.getQrCodeLoginInfo();
            } catch (Exception e) {
                if (profile.qrCodeRounds() > 0 && rounds >= profile.qrCodeRounds()) {
                    this.pendingQrCodeContent = null;
                    this.loginNotice = "没能取到登录二维码，请稍后再试";
                    log.error("{}获取登录二维码失败, 本次扫码登录结束: {}", prefix, e.getMessage());
                    return false;
                }
                log.error("{}获取登录二维码失败, 将在 10 秒后重试: {}", prefix, e.getMessage());
                if (!sleep(Duration.ofSeconds(10))) {
                    return false;
                }
                continue;
            }

            this.pendingQrCodeContent = qrCode.url();

            log.info("{}请使用哔哩哔哩客户端扫描以下二维码登录", prefix);
            QrCodeUtil.generateQrCodeAndPrint(qrCode.url(), QR_CODE_SIZE);

            if (pollUntilLoggedIn(qrCode.key(), tvMode)) {
                return true;
            }

            if (isStopping()) {
                return false;
            }

            if (qrCodeFlowEnded()) {
                this.pendingQrCodeContent = null;
                return false;
            }

            if (profile.qrCodeRounds() <= 0 || rounds < profile.qrCodeRounds()) {
                log.info("{}登录二维码已过期, 正在重新获取", prefix);
            }
        }

        return loggedIn;
    }

    /**
     * 轮询直至登录成功或二维码过期
     * @param key 轮询令牌
     * @return 是否登录成功
     */
    private boolean pollUntilLoggedIn(String key, boolean tvMode) {
        Instant deadline = Instant.now().plus(QR_CODE_TTL);

        while (Instant.now().isBefore(deadline)) {
            if (!sleep(POLL_INTERVAL)) {
                return false;
            }

            if (qrCodeCancelled) {
                log.info("{}扫码登录已中止", prefix);
                return false;
            }

            if (!(tvMode ? api.getTvQrCodeLoginStatus(key) : api.getQrCodeLoginStatus(key))) {
                continue;
            }

            Cookies logged = api.getCookies();

            // 带检查的口径先认人，再存盘：被拒的那一份凭据不落盘，内存里也不留
            Long checkedUid = null;
            if (profile.loginGate() != null) {
                checkedUid = api.getLoginUid();
                Optional<String> refusal = profile.loginGate().apply(checkedUid);
                if (refusal != null && refusal.isPresent()) {
                    api.setCookies(new Cookies());
                    this.pendingQrCodeContent = null;
                    this.loginNotice = refusal.get();
                    log.warn("{}已拒绝这次扫码登录: {}", prefix, refusal.get());
                    return false;
                }
            }

            if (!store.save(logged)) {
                // 登录照常：凭据已在内存里，本次进程内一切可用。只是盘上没有，重启后要重新扫码
                log.error("{}登录凭据存盘失败, 重启后要重新扫码", prefix);
            }
            this.pendingQrCodeContent = null;
            this.loginUid = profile.loginGate() != null ? checkedUid : api.getLoginUid();
            this.loggedIn = true;
            this.loginNotice = null;

            // 当场没拿到账号身份是常有的事，别把 null 印给使用者——那看着就像登录坏了
            if (loginUid == null) {
                log.info("{}登录成功, 登录凭据暂未确认", prefix);
            } else {
                log.info("{}登录成功, uid: {}", prefix, loginUid);
            }
            logCredentialCapability(logged);
            return true;
        }

        return false;
    }

    /**
     * 说明本次取得的凭据具备何种续期能力
     * <p>
     * 「能不能自动续期」直接决定使用者要不要每月重新扫码，登录当下就该讲清楚，
     * 而不是等某天掉登录才发现。遵循本项目既有约定：<b>只输出结构与有效期，不输出任何凭据取值</b>。
     * @param cookies 本次登录取得的凭据
     */
    private void logCredentialCapability(Cookies cookies) {
        // 这只是一条说明性日志，任何情况下都不该影响登录本身
        if (cookies == null) {
            return;
        }

        if (cookies.isAppRefreshable()) {
            Long expiresAt = cookies.getAccessTokenExpiresAt();
            log.info("{}已取得可自动续期的登录令牌{}", prefix, expiresAt == null ? ""
                    : ", 有效期至 " + LocalDate.ofInstant(Instant.ofEpochMilli(expiresAt), ZoneId.systemDefault()));
        } else if (cookies.isRefreshable()) {
            log.info("{}已取得网页端刷新口令, 续期将走网页端链路", prefix);
        } else {
            log.warn("{}本次登录未取得任何刷新口令, 凭据到期后需要重新扫码", prefix);
        }
    }

    /**
     * 退出登录并清除已保存的凭据
     */
    public void logout() {
        store.clear();
        api.setCookies(new Cookies());
        this.loggedIn = false;
        this.loginUid = null;
        this.loginNotice = null;

        log.info("{}已退出登录并清除本地凭据", prefix);
    }

    /**
     * 休眠指定时长，期间若收到停机信号或线程中断则提前结束
     * @param duration 时长
     * @return 是否应继续登录流程，收到停机信号或被中断时返回 false
     */
    private boolean sleep(Duration duration) {
        try {
            // 闭锁被放行意味着收到了停机信号，此时 await 立即返回 true，据此提前结束等待
            return !shutdownSignal.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 中断同样意味着要求停止，恢复标志后交由调用方结束循环
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 登录凭据存储注册器
     */
    @NovaComponent
    public static class CredentialStoreRegistrar {
        /**
         * 注册登录凭据存储
         * @param properties 配置
         * @return 登录凭据存储
         */
        @Bean
        public BilibiliCredentialStore bilibiliCredentialStore(NovaBilibiliProperties properties) {
            return new BilibiliCredentialStore(properties.getAccount());
        }
    }
}
