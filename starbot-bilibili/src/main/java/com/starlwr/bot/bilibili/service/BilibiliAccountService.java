package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Cookies;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.QrCodeUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

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

    private final BilibiliApiUtil api;

    private final BilibiliCredentialStore store;

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

    @Autowired
    public BilibiliAccountService(BilibiliApiUtil api, BilibiliCredentialStore store) {
        this.api = api;
        this.store = store;
    }

    /**
     * 登录
     * <p>
     * 优先使用已保存的凭据，凭据缺失或已失效时转为扫码登录。
     */
    public void login() {
        api.init();

        Optional<Cookies> saved = store.load();
        if (saved.isPresent() && saved.get().isComplete()) {
            api.setCookies(saved.get());

            Long uid = api.getLoginUid();
            if (uid != null) {
                this.loginUid = uid;
                this.loggedIn = true;
                log.info("已使用保存的登录凭据登录, uid: {}", uid);
                return;
            }

            log.warn("保存的登录凭据已失效, 需要重新扫码登录");
            store.clear();
        }

        loginByQrCode();
    }

    /**
     * 扫码登录
     */
    public void loginByQrCode() {
        while (!loggedIn) {
            BilibiliApiUtil.QrCodeLogin qrCode;
            try {
                qrCode = api.getQrCodeLoginInfo();
            } catch (Exception e) {
                log.error("获取登录二维码失败, 将在 10 秒后重试: {}", e.getMessage());
                sleep(Duration.ofSeconds(10));
                continue;
            }

            this.pendingQrCodeContent = qrCode.url();

            log.info("请使用哔哩哔哩客户端扫描以下二维码登录");
            QrCodeUtil.generateQrCodeAndPrint(qrCode.url(), QR_CODE_SIZE);

            if (pollUntilLoggedIn(qrCode.key())) {
                return;
            }

            log.info("登录二维码已过期, 正在重新获取");
        }
    }

    /**
     * 轮询直至登录成功或二维码过期
     * @param key 轮询令牌
     * @return 是否登录成功
     */
    private boolean pollUntilLoggedIn(String key) {
        Instant deadline = Instant.now().plus(QR_CODE_TTL);

        while (Instant.now().isBefore(deadline)) {
            sleep(POLL_INTERVAL);

            if (!api.getQrCodeLoginStatus(key)) {
                continue;
            }

            store.save(api.getCookies());
            this.pendingQrCodeContent = null;
            this.loginUid = api.getLoginUid();
            this.loggedIn = true;

            log.info("登录成功, uid: {}", loginUid);
            return true;
        }

        return false;
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
     * 休眠指定时长
     * @param duration 时长
     */
    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("登录流程被中断", e);
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
