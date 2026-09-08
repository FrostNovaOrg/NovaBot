package com.starlwr.bot.bilibili.account;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.account.AccountLoginProvider;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.lang.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Optional;

/**
 * 哔哩哔哩账号登录能力
 * <p>
 * 把账号服务中已有的登录状态与待扫码内容暴露给配置界面。这些状态本就存在，
 * 此前只有终端里的字符画二维码在用。
 */
@StarBotComponent
public class BilibiliAccountLoginProvider implements AccountLoginProvider {
    private final BilibiliAccountService accountService;

    private final TaskScheduler scheduler;

    private final BilibiliApiUtil api;

    /**
     * 昵称只问平台一次。失败也算问过：界面退回只显示 uid，不在每次刷新登录态时再打一遍。
     */
    private volatile boolean nameTried;

    private volatile String name;

    @Autowired
    public BilibiliAccountLoginProvider(BilibiliAccountService accountService,
                                        @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler,
                                        BilibiliApiUtil api) {
        this.accountService = accountService;
        this.scheduler = scheduler;
        this.api = api;
    }

    @Override
    public String platform() {
        return BilibiliPlatform.BILIBILI.id();
    }

    @Override
    public String displayName() {
        return "哔哩哔哩";
    }

    @Override
    public boolean isLoggedIn() {
        return accountService.isLoggedIn();
    }

    @Override
    public Optional<String> accountId() {
        return Optional.ofNullable(accountService.getLoginUid()).map(String::valueOf);
    }

    @Override
    public Optional<String> accountName() {
        Long uid = accountService.getLoginUid();
        if (uid == null || !accountService.isLoggedIn()) {
            return Optional.empty();
        }
        if (!nameTried) {
            nameTried = true;
            try {
                Up up = api.getUpInfoByUid(uid);
                if (up != null && StringUtil.isNotBlank(up.getUname())) {
                    name = up.getUname();
                }
            } catch (Exception ignored) {
                // 失败静默：卡上只显 uid。这里打错误日志的话，首页每次刷新都会刷屏
            }
        }
        return Optional.ofNullable(name);
    }

    @Override
    public Optional<String> pendingQrCodeContent() {
        return Optional.ofNullable(accountService.getPendingQrCodeContent());
    }

    @Override
    public Optional<String> disabledReason() {
        return accountService.isAnonymous() ? Optional.of(BilibiliAccountService.ANONYMOUS_NOTICE) : Optional.empty();
    }

    @Override
    public Optional<Instant> credentialExpiresAt() {
        return accountService.credentialExpiresAt();
    }

    @Override
    public Optional<String> credentialNote() {
        // 未登录时说续期状况没有意义：此时该说的是「去扫码」，而那句话由登录态自己带
        return isLoggedIn() ? Optional.of(accountService.credentialNote()) : Optional.empty();
    }

    @Override
    public void logout() {
        nameTried = false;
        name = null;
        accountService.logout();

        // 退出后立即发起新一轮扫码，界面上随即就能看到新的二维码；
        // 该流程可能持续数分钟，因此放到调度线程上执行，不阻塞发起退出的那个请求
        scheduler.schedule(accountService::loginByQrCode, Instant.now());
    }
}
