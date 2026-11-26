package org.frostnova.nova.interact.speaker;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.service.BilibiliCredentialStore;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.interact.config.InteractProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 哔哩哔哩发言账号
 * <p>
 * 另一个哔哩哔哩账号，专门用来在直播间发言，与监听直播间用的那个账号完全分开。
 * 分开的理由只有一条：发言有被平台限制的风险，不能让这个风险连累到监听、开播通知和直播报告。
 * <p>
 * 所以这里的一切都是自己的一份——接口工具（凭据、设备标识、签名、风控冷却）、凭据文件、
 * 风控指标、干活的线程。扫码、复检、续期、存盘照搬监听账号那一套实现，只换了一份 {@link BilibiliAccountService.Profile}。
 * <p>
 * <b>不登录就不向哔哩哔哩发任何请求。</b>没有保存的凭据时，启动不取设备标识、不取签名、不备二维码，
 * 复检也不跑；有人在连接页上点了「扫码登录」才开始取码，二维码过期了没人扫就停。
 */
@Slf4j
@NovaComponent
public class BilibiliSpeakerAccount {
    /**
     * 账号标识，同时是连接卡的页标识
     */
    public static final String ID = "bilibili-speaker";

    /**
     * 显示名
     */
    public static final String DISPLAY_NAME = "哔哩哔哩发言账号";

    /**
     * 扫出来的就是监听账号时，说给使用者的话
     */
    static final String SAME_AS_LISTENER = "扫码登录的是监听直播间用的那个账号。发言账号必须另用一个号："
            + "同一个号被平台限制时，监听、开播通知和直播报告会一起受影响。这次登录已作废，请换一个账号扫码";

    /**
     * 登上之后认不出是谁时，说给使用者的话
     */
    static final String IDENTITY_UNKNOWN = "登录后没能确认这个账号是谁，也就无法确认它不是监听账号，这次登录已作废。请稍后再扫一次";

    /**
     * 运行中才发现与监听账号是同一个号（例如监听账号后来换成了它）
     */
    static final String SAME_AS_LISTENER_NOW = "发言账号与监听账号是同一个号，发言已停用。请退出发言账号，换一个号登录";

    /**
     * 一次扫码只给一张二维码：过期了没人扫就停，不再替没人在场的界面一张张换码
     */
    static final int QR_CODE_ROUNDS = 1;

    private static final String LOG_PREFIX = "发言账号：";

    private static final String LOGGED_OUT_WARNING =
            "登录凭据已失效, 发言已停用; 监听账号、推送与直播报告不受影响。请在连接页的发言账号卡上重新扫码登录";

    /**
     * 凭据暂未确认时，隔多久先补问一次
     */
    private static final Duration UNVERIFIED_REASK = Duration.ofSeconds(60);

    private final BilibiliApiUtil api;

    private final BilibiliCredentialStore store;

    private final BilibiliAccountService account;

    private final BilibiliAccountService listener;

    private final NovaBilibiliProperties.Account settings;

    private final ScheduledExecutorService worker;

    /**
     * 本机有没有这个号的凭据：登录过、而且没有退出登录。凭据失效了它仍为真——
     * 「登录过、后来失效」与「从没登录过」要分开说，前者才算故障
     */
    private volatile boolean hasCredentials;

    /**
     * 是否已为这个号取过设备标识与签名。没登录过就不取：那也是向平台发的请求
     */
    private volatile boolean prepared;

    /**
     * 已经排上、还没跑完的那一轮扫码。两下连点只排一轮
     */
    private final AtomicBoolean loginQueued = new AtomicBoolean();

    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * 每退出一次加一。排上了还没开始的那一轮扫码，开始前对一下：退出登录之后排上的才算数
     */
    private final AtomicInteger generation = new AtomicInteger();

    @Autowired
    public BilibiliSpeakerAccount(HttpUtil http, NovaBilibiliProperties bilibili, InteractProperties interact,
                                  BilibiliAccountService listener) {
        this(new BilibiliApiUtil(http, bilibili, new BilibiliRiskMetrics()),
                new SpeakerAccountSettings(bilibili.getAccount(), interact.getAccount()),
                null, listener, newWorker());
    }

    /**
     * 测试用：接口工具、凭据存储与线程都由调用方给
     * @param api 本账号自己的接口工具
     * @param settings 本账号的账号配置
     * @param store 本账号自己的凭据存储，为 null 时按配置新建
     * @param listener 监听账号的账号服务，只读它的 uid
     * @param worker 干活的线程
     */
    BilibiliSpeakerAccount(BilibiliApiUtil api, NovaBilibiliProperties.Account settings, BilibiliCredentialStore store,
                           BilibiliAccountService listener, ScheduledExecutorService worker) {
        this.api = api;
        this.settings = settings;
        this.store = store == null ? new BilibiliCredentialStore(settings) : store;
        this.listener = listener;
        this.worker = worker;
        this.account = new BilibiliAccountService(api, this.store, settings,
                new BilibiliAccountService.Profile(LOG_PREFIX, LOGGED_OUT_WARNING, QR_CODE_ROUNDS, this::refuse));
    }

    private static ScheduledExecutorService newWorker() {
        // 自己的一条线程：扫码一等就是几分钟，占着哔哩哔哩插件那几条调度线程的话，
        // 监听那一侧的心跳与重连会被它拖住
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bilibili-speaker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 扫码成功、凭据存盘之前的那道关
     * @param uid 扫出来的号，认不出时为 null
     * @return 拒绝的理由，放行时为空
     */
    Optional<String> refuse(Long uid) {
        if (uid == null) {
            return Optional.of(IDENTITY_UNKNOWN);
        }
        Long listening = listener.getLoginUid();
        if (uid.equals(listening)) {
            return Optional.of(SAME_AS_LISTENER);
        }
        return Optional.empty();
    }

    /**
     * 启动：有保存的凭据就验一次，没有就什么都不做
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        worker.execute(this::restore);

        int interval = settings.getVerifyInterval();
        if (interval > 0) {
            worker.scheduleAtFixedRate(this::maintainIfLoggedIn, interval, interval, TimeUnit.SECONDS);
        }
    }

    /**
     * 用保存的凭据登录
     */
    void restore() {
        BilibiliAccountService.SavedLogin result = account.restoreSaved();
        if (result == BilibiliAccountService.SavedLogin.NONE) {
            return;
        }
        prepared = true;
        hasCredentials = true;
        if (result == BilibiliAccountService.SavedLogin.UNVERIFIED) {
            worker.schedule(this::confirmUnverified, UNVERIFIED_REASK.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private void confirmUnverified() {
        if (account.isStopping() || !account.isLoggedIn() || account.getLoginUid() != null) {
            return;
        }
        account.verify();
    }

    /**
     * 例行复检与续期，只在登着时做
     * <p>
     * 没登录、或凭据已失效时不问：问一个没登录的号「我是谁」也是一次请求，而答案早就知道了。
     */
    void maintainIfLoggedIn() {
        try {
            if (account.isLoggedIn() && !account.isQrCodeLoginInProgress()) {
                account.maintain();
            }
        } catch (RuntimeException e) {
            log.warn("{}例行复检出错: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * 开始一轮扫码登录，立即返回
     * @return 说给使用者的一句话
     */
    public String startLogin() {
        if (account.isLoggedIn()) {
            return "发言账号已登录，无需扫码";
        }
        if (account.isQrCodeLoginInProgress() || !loginQueued.compareAndSet(false, true)) {
            return "正在等待扫码";
        }
        int round = generation.get();
        worker.execute(() -> {
            try {
                if (round != generation.get()) {
                    // 排上之后、开始之前有人点了退出登录：这一轮作废，一个请求都不发
                    return;
                }
                if (!prepared) {
                    api.init();
                    prepared = true;
                }
                if (account.loginByQrCode()) {
                    hasCredentials = true;
                }
            } catch (RuntimeException e) {
                log.warn("{}扫码登录出错: {}", LOG_PREFIX, e.getMessage());
            } finally {
                loginQueued.set(false);
            }
        });
        return "正在获取二维码，请稍候";
    }

    /**
     * 退出登录：中止正在进行的扫码，清掉本机保存的发言账号凭据。监听账号一个字节都不碰
     */
    public void logout() {
        generation.incrementAndGet();
        account.cancelQrCodeLogin();
        account.logout();
        hasCredentials = false;
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        account.onContextClosed();
        worker.shutdownNow();
    }

    /**
     * 是否已登录
     * @return 是否已登录
     */
    public boolean isLoggedIn() {
        return account.isLoggedIn();
    }

    /**
     * 本机是否存着这个号的凭据（登录过、没退出；凭据失效了也算）
     * @return 存着时返回 true
     */
    public boolean hasCredentials() {
        return hasCredentials || account.isLoggedIn();
    }

    /**
     * 发言账号的 uid，未登录或暂未确认时为 null
     * @return uid
     */
    public Long uid() {
        return account.getLoginUid();
    }

    /**
     * 是否正在扫码（已排上或正在等人扫）
     * @return 正在扫码时返回 true
     */
    public boolean isLoggingIn() {
        return loginQueued.get() || account.isQrCodeLoginInProgress();
    }

    /**
     * 待扫描的二维码内容
     * @return 二维码内容，无待扫码时为空
     */
    public Optional<String> pendingQrCodeContent() {
        return Optional.ofNullable(account.getPendingQrCodeContent());
    }

    /**
     * 此刻要人知道的那一件事：上一次扫码为什么没登上，或者它与监听账号成了同一个号
     * @return 说明，没有时为空
     */
    public Optional<String> notice() {
        String last = account.getLoginNotice();
        if (last != null) {
            return Optional.of(last);
        }
        return sameAsListener() ? Optional.of(SAME_AS_LISTENER_NOW) : Optional.empty();
    }

    /**
     * 此刻是否与监听账号是同一个号
     * @return 同一个号时返回 true
     */
    boolean sameAsListener() {
        Long own = account.getLoginUid();
        return own != null && own.equals(listener.getLoginUid());
    }

    /**
     * 发言账号此刻为什么用不了，能用时为空
     * @return 理由
     */
    public Optional<String> unusableReason() {
        if (!account.isLoggedIn()) {
            return Optional.of(hasCredentials ? "发言账号的登录已失效，请在连接页重新扫码登录" : "还没有登录发言账号");
        }
        if (account.getLoginUid() == null) {
            return Optional.of("发言账号的登录凭据暂未确认，确认之前不使用它");
        }
        if (sameAsListener()) {
            return Optional.of(SAME_AS_LISTENER_NOW);
        }
        return Optional.empty();
    }

    /**
     * 凭据到期时刻
     * @return 到期时刻
     */
    public Optional<Instant> credentialExpiresAt() {
        return account.credentialExpiresAt();
    }

    /**
     * 续期状况的一句话
     * @return 续期状况
     */
    public String credentialNote() {
        return account.credentialNote();
    }

    /**
     * 是否缺刷新口令而无法自动续期
     * @return 可续期时返回 true
     */
    public boolean isRefreshable() {
        return account.isRefreshable();
    }

    /**
     * 上次成功复检的时间
     * @return 时间，从未复检成功时为 null
     */
    public Instant lastVerifiedAt() {
        return account.getLastVerifiedAt();
    }

    /**
     * 本账号的账号配置
     * @return 账号配置
     */
    NovaBilibiliProperties.Account settings() {
        return settings;
    }

    /**
     * 本账号自己的接口工具。只给本插件里用发言账号发请求的那几处用，不交给别的插件
     * @return 接口工具
     */
    BilibiliApiUtil api() {
        return api;
    }

    /**
     * 本账号此刻的跨站校验口令，没有凭据时为空串。只拿来填请求参数，不写日志、不回给界面
     * @return 口令
     */
    String csrf() {
        Cookies cookies = api.getCookies();
        String csrf = cookies == null ? null : cookies.getBiliJct();
        return csrf == null ? "" : csrf;
    }
}
