package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.model.Cookies;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.bilibili.util.BilibiliCookieRefreshUtil;
import com.starlwr.bot.core.plugin.StarBotComponent;
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
@StarBotComponent
public class BilibiliAccountService {
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
    }

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
        if (isStopping()) {
            return loggedIn;
        }

        try {
            Long uid = api.fetchLoginUid();
            lastVerifiedAt = Instant.now();

            if (uid == null) {
                markLoggedOut();
                return false;
            }

            this.loginUid = uid;
            if (!loggedIn) {
                this.loggedIn = true;
                log.info("哔哩哔哩登录态已恢复, uid: {}", uid);
            }
            return true;
        } catch (ResponseCodeException e) {
            if (e.getCode() == BilibiliApiUtil.CODE_NOT_LOGGED_IN) {
                lastVerifiedAt = Instant.now();
                markLoggedOut();
                return false;
            }

            log.warn("登录态复检返回未预期的错误代码 {}, 暂维持原状态: {}", e.getCode(), e.getMessage());
            return loggedIn;
        } catch (Exception e) {
            log.debug("登录态复检失败, 疑为网络故障, 暂维持原状态: {}", e.getMessage());
            return loggedIn;
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
     * 例行维护：先复检登录态，登录态正常时再按需续期 Cookie
     * <p>
     * 两件事共用一个周期：续期只在登录态正常时才有意义，掉登录后再怎么续也是徒劳。
     * @return 复检后的登录态
     */
    public boolean maintain() {
        boolean alive = verify();
        if (alive) {
            refreshCookiesIfNeeded();
        }
        return alive;
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
        if (isStopping() || !properties.isAutoRefreshCookie()) {
            return false;
        }

        Cookies current = api.getCookies();
        if (!current.isRefreshable()) {
            // 旧版本保存的凭据里没有刷新口令，只能等下次扫码时补上，不必反复告警
            log.debug("当前凭据缺少持久化刷新口令, 跳过 Cookie 续期");
            return false;
        }

        // TV 端登录取得的凭据走 oauth2 续期，与 Web 端的接口和参数完全不同
        if (current.isAppRefreshable()) {
            return refreshAppTokenIfNeeded(current);
        }

        BilibiliApiUtil.CookieRefreshHint hint;
        try {
            hint = api.checkCookieRefresh();
        } catch (Exception e) {
            log.debug("查询 Cookie 续期状态失败: {}", e.getMessage());
            return false;
        }

        if (!hint.needed()) {
            return false;
        }

        log.info("哔哩哔哩提示当前凭据需要续期, 开始续期");
        return doRefresh(current, hint.timestamp());
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
     * 登录
     * <p>
     * 优先使用已保存的凭据，凭据缺失或已失效时转为扫码登录。
     * @return 是否登录成功，因停机而中止时返回 false
     */
    public boolean login() {
        api.init();

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
                    + "如需自动续期请将 starbot.bilibili.account.qr-code-login-mode 改回 tv");
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
    @StarBotComponent
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
