package com.starlwr.bot.bilibili.account;

import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.core.account.AccountLoginProvider;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.plugin.StarBotComponent;
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

    @Autowired
    public BilibiliAccountLoginProvider(BilibiliAccountService accountService,
                                        @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler) {
        this.accountService = accountService;
        this.scheduler = scheduler;
    }

    @Override
    public String platform() {
        return LivePlatform.BILIBILI.getName();
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
    public Optional<String> pendingQrCodeContent() {
        return Optional.ofNullable(accountService.getPendingQrCodeContent());
    }

    @Override
    public void logout() {
        accountService.logout();

        // 退出后立即发起新一轮扫码，界面上随即就能看到新的二维码；
        // 该流程可能持续数分钟，因此放到调度线程上执行，不阻塞发起退出的那个请求
        scheduler.schedule(accountService::loginByQrCode, Instant.now());
    }
}
