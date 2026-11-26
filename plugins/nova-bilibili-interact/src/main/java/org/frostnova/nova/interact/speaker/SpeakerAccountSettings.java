package org.frostnova.nova.interact.speaker;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.interact.config.InteractProperties;

/**
 * 发言账号的账号配置
 * <p>
 * 凭据放在哪两项读本插件自己的配置，别的项一律读监听账号那一份的<b>此刻值</b>：
 * 复检间隔、退避上限、自动续期、扫码方式、加密与否，两个账号同一个节奏、同一种存法。
 * 读此刻值而不是启动时抄一份：自动续期、扫码方式是改了当场生效的项，抄下来的那份不会跟着变。
 * <p>
 * 匿名模式恒为关：那是监听账号的选择，监听账号免登录时发言账号照样可以登录。
 */
final class SpeakerAccountSettings extends NovaBilibiliProperties.Account {
    private final NovaBilibiliProperties.Account listener;

    private final InteractProperties.Account own;

    SpeakerAccountSettings(NovaBilibiliProperties.Account listener, InteractProperties.Account own) {
        this.listener = listener;
        this.own = own;
    }

    @Override
    public boolean isAnonymous() {
        return false;
    }

    @Override
    public String getCookiePath() {
        return own.getCookiePath();
    }

    @Override
    public String getKeyPath() {
        return own.getKeyPath();
    }

    @Override
    public boolean isEncrypt() {
        return listener.isEncrypt();
    }

    @Override
    public int getVerifyInterval() {
        return listener.getVerifyInterval();
    }

    @Override
    public int getMaintenanceBackoffCap() {
        return listener.getMaintenanceBackoffCap();
    }

    @Override
    public boolean isAutoRefreshCookie() {
        return listener.isAutoRefreshCookie();
    }

    @Override
    public String getQrCodeLoginMode() {
        return listener.getQrCodeLoginMode();
    }
}
