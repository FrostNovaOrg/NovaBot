package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.model.Cookies;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.bilibili.util.BilibiliCookieRefreshUtil;
import com.starlwr.bot.core.plugin.NovaComponent;
import com.starlwr.bot.core.util.QrCodeUtil;
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

/**
 * 哔哩哔哩账号服务
 * <p>
 * 负责登录凭据的加载、扫码登录与凭据持久化。凭据默认加密存储，详见 {@link BilibiliCredentialStore}。
 */
@Slf4j
@NovaComponent
public class BilibiliAccountService {
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

    /**
     * TV 端扫码登录方式的配置取值
     */
    private static final String LOGIN_MODE_TV = "tv";

    private final BilibiliApiUtil api;

    private final BilibiliCredentialStore store;

    private final StarBotBilibiliProperties.Account properties;

    /**
     * 当前待扫描的二维码内容，供登录页面展示
     */
    @Getter
    private volatile String pendingQrCodeContent;

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
                                  StarBotBilibiliProperties properties) {
        this.api = api;
        this.store = store;
        this.properties = properties.getAccount();

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

            this.loginUid = uid;
            if (!loggedIn) {
                this.loggedIn = true;
                log.info("哔哩哔哩登录态已恢复, uid: {}", uid);
            }
            return MaintenanceOutcome.OK;
        } catch (ResponseCodeException e) {
            if (e.getCode() == BilibiliApiUtil.CODE_NOT_LOGGED_IN) {
                lastVerifiedAt = Instant.now();
                markLoggedOut();
                return MaintenanceOutcome.OK;
            }

            log.warn("登录态复检返回未预期的错误代码 {}, 暂维持原状态: {}", e.getCode(), e.getMessage());
            return MaintenanceOutcome.FAILED;
        } catch (Exception e) {
            log.debug("登录态复检失败, 疑为网络故障, 暂维持原状态: {}", e.getMessage());
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
                log.warn("{}连续失败 {} 次, {} 秒后再试",
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
            log.debug("当前凭据缺少持久化刷新口令, 跳过 Cookie 续期");
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
            log.debug("查询 Cookie 续期状态失败: {}", e.getMessage());
            return MaintenanceOutcome.FAILED;
        }

        if (!hint.needed()) {
            return MaintenanceOutcome.OK;
        }

        log.info("哔哩哔哩提示当前凭据需要续期, 开始续期");
        refreshed = doRefresh(current, hint.timestamp());
        // 续期没做成是失败：这一步不可回退，做不成时旧凭据仍在手上，
        // 但下次不该立刻再试——真正续不动时反复调这条链路只是给风控送素材
        return refreshed ? MaintenanceOutcome.OK : MaintenanceOutcome.FAILED;
    }

    /**
     * TV 端凭据的 oauth2 续期
     * <p>
     * 令牌默认有效 180 天，在到期前一段时间提前续期即可，无须每次复检都请求接口。
     * 与 Web 端续期一样遵循「验证通过再落盘」的顺序，中途失败不会让账号失去可用凭据。
     * @param current 当前凭据
     * @return 是否完成了一次续期
     */
    private boolean refreshAppTokenIfNeeded(Cookies current) {
        Long expiresAt = current.getAccessTokenExpiresAt();
        if (expiresAt != null && Instant.now().isBefore(Instant.ofEpochMilli(expiresAt).minus(APP_TOKEN_REFRESH_AHEAD))) {
            return false;
        }

        log.info("哔哩哔哩登录令牌即将到期, 开始续期");

        Cookies refreshed;
        try {
            refreshed = api.refreshAppToken();
        } catch (Exception e) {
            // 走到这里旧凭据尚未被动过，仍然可用
            log.warn("登录令牌续期失败, 继续使用原凭据: {}", e.getMessage());
            return false;
        }

        api.setCookies(refreshed);
        try {
            if (api.fetchLoginUid() == null) {
                throw new IllegalStateException("新凭据无法取得账号信息");
            }
        } catch (Exception e) {
            api.setCookies(current);
            log.error("登录令牌续期后的新凭据验证失败, 已回退至原凭据: {}", e.getMessage());
            return false;
        }

        store.save(refreshed);
        log.info("登录令牌续期完成");
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
            log.warn("Cookie 续期失败, 继续使用原凭据: {}", e.getMessage());
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
            log.error("Cookie 续期后的新凭据验证失败, 已回退至原凭据: {}", e.getMessage());
            return false;
        }

        // 先落盘再作废旧凭据：反过来的话，一旦此刻进程退出，新凭据没存下、旧凭据又已失效，只能重新扫码
        store.save(refreshed);

        try {
            api.confirmCookieRefresh(oldRefreshToken);
            log.info("Cookie 续期完成");
        } catch (Exception e) {
            // 新凭据已生效，只是旧凭据没能及时作废。如实说明而不是笼统报失败
            log.warn("Cookie 续期已生效, 但作废旧凭据失败, 旧凭据将保持有效直至自然过期: {}", e.getMessage());
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
        log.warn("哔哩哔哩登录凭据已失效, 动态推送与自动关注将停止; 直播推送不依赖登录态, 不受影响。请重新扫码登录");
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
     * 优先使用已保存的凭据，凭据缺失或已失效时转为扫码登录。
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

            Long uid = api.getLoginUid();
            if (uid != null) {
                this.loginUid = uid;
                this.loggedIn = true;
                log.info("已使用保存的登录凭据登录, uid: {}", uid);
                logCredentialCapability(api.getCookies());
                return true;
            }

            log.warn("保存的登录凭据已失效, 需要重新扫码登录");
            store.clear();
        } else {
            // 扫码之前直播采集是不会启动的，而这一等可以是无限久。只想要直播数据的人
            // 不该被卡在这里却猜不到有别的路——把那条路当场说出来
            log.info("尚无登录凭据。若只需要直播弹幕、礼物等数据而不需要动态推送, "
                    + "可以把 novabot.bilibili.account.anonymous 设为 true 免登录启动, "
                    + "代价见该配置项的说明");
        }

        return loginByQrCode();
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
            log.info("当前为匿名模式, 已忽略扫码登录请求; 要登录请先关闭 novabot.bilibili.account.anonymous");
            return false;
        }

        if (!loginInProgress.compareAndSet(false, true)) {
            log.debug("已有扫码登录流程正在进行, 忽略本次请求");
            return loggedIn;
        }

        try {
            return doLoginByQrCode();
        } finally {
            loginInProgress.set(false);
        }
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
            log.warn("扫码登录方式为 web, 服务端不会下发可用的刷新口令, 凭据到期后需重新扫码; "
                    + "如需自动续期请将 novabot.bilibili.account.qr-code-login-mode 改回 tv");
        }

        while (!loggedIn && !isStopping()) {
            BilibiliApiUtil.QrCodeLogin qrCode;
            try {
                qrCode = tvMode ? api.getTvQrCodeLoginInfo() : api.getQrCodeLoginInfo();
            } catch (Exception e) {
                log.error("获取登录二维码失败, 将在 10 秒后重试: {}", e.getMessage());
                if (!sleep(Duration.ofSeconds(10))) {
                    return false;
                }
                continue;
            }

            this.pendingQrCodeContent = qrCode.url();

            log.info("请使用哔哩哔哩客户端扫描以下二维码登录");
            QrCodeUtil.generateQrCodeAndPrint(qrCode.url(), QR_CODE_SIZE);

            if (pollUntilLoggedIn(qrCode.key(), tvMode)) {
                return true;
            }

            if (isStopping()) {
                return false;
            }

            log.info("登录二维码已过期, 正在重新获取");
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

            if (!(tvMode ? api.getTvQrCodeLoginStatus(key) : api.getQrCodeLoginStatus(key))) {
                continue;
            }

            Cookies logged = api.getCookies();
            store.save(logged);
            this.pendingQrCodeContent = null;
            this.loginUid = api.getLoginUid();
            this.loggedIn = true;

            log.info("登录成功, uid: {}", loginUid);
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
            log.info("已取得可自动续期的登录令牌{}", expiresAt == null ? ""
                    : ", 有效期至 " + LocalDate.ofInstant(Instant.ofEpochMilli(expiresAt), ZoneId.systemDefault()));
        } else if (cookies.isRefreshable()) {
            log.info("已取得网页端刷新口令, 续期将走网页端链路");
        } else {
            log.warn("本次登录未取得任何刷新口令, 凭据到期后需要重新扫码");
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

        log.info("已退出登录并清除本地凭据");
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
        public BilibiliCredentialStore bilibiliCredentialStore(StarBotBilibiliProperties properties) {
            return new BilibiliCredentialStore(properties.getAccount());
        }
    }
}
