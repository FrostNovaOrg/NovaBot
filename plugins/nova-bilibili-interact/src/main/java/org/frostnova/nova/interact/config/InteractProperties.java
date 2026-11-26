package org.frostnova.nova.interact.config;

import lombok.Getter;
import lombok.Setter;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.properties.ConfigChoices;
import org.frostnova.nova.core.properties.ConfigEffect;
import org.frostnova.nova.core.properties.ConfigFormat;
import org.frostnova.nova.core.properties.ConfigLabel;
import org.frostnova.nova.core.properties.ConfigLevel;
import org.frostnova.nova.core.properties.ConfigSummary;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 直播间互动插件的配置
 * <p>
 * 发言账号只有凭据放在哪两项。复检间隔、自动续期、扫码方式、加密与否都跟监听账号走同一份配置：
 * 两个账号按同样的节奏核验与续期，存盘的加密方式也一样。
 * <p>
 * 弹幕那三项保存后当场生效（见 {@link InteractRuntimeAppliers}），用到时现读：
 * 下一次发弹幕就按新值选账号、算间隔与暂停。
 */
@Getter
@Setter
@Configuration
@NovaComponent
@ConfigurationProperties(prefix = "novabot.bilibili.interact")
public class InteractProperties {
    private final Account account = new Account();

    private final Danmaku danmaku = new Danmaku();

    /**
     * 发言账号
     */
    @Getter
    @Setter
    public static class Account {
        /**
         * 发言账号登录凭据的存储文件路径，与监听账号的凭据文件分开存放。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("发言账号 · 凭据文件")
        @ConfigLevel(ConfigLevel.Level.ADVANCED)
        @ConfigSummary(value = "", details = false)
        @ConfigFormat(ConfigFormat.Format.PATH)
        private String cookiePath = "speaker-cookies.json";

        /**
         * 发言账号凭据加密密钥的存储文件路径；密钥丢了只能重新扫码登录发言账号。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("发言账号 · 密钥文件")
        @ConfigLevel(ConfigLevel.Level.ADVANCED)
        @ConfigSummary("密钥文件丢失后，已加密的发言账号凭据无法解密，需重新扫码登录。")
        @ConfigFormat(ConfigFormat.Format.PATH)
        private String keyPath = "speaker-cookies.key";
    }

    /**
     * 往直播间发弹幕
     */
    @Getter
    @Setter
    public static class Danmaku {
        /**
         * 发弹幕用哪个账号：speaker 是单独的发言账号（默认），listener 是监听直播间用的那个账号。选 listener 时，这个号如果因为发弹幕被平台限制，所有主播的监听、开播通知和直播报告会一起受影响。
         */
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        @ConfigLabel("发弹幕用哪个账号")
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigSummary("选监听账号时，它若因发弹幕被平台限制，所有主播的监听、开播通知和直播报告会一起受影响。")
        @ConfigChoices(value = {"speaker", "listener"}, labels = {"单独的发言账号", "和监听直播间用的同一个账号"})
        private volatile String account = "speaker";

        /**
         * 同一个账号两条弹幕之间至少隔多少秒，最少 1 秒；间隔内再发直接回「发得太快」，不排队。
         */
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        @ConfigLabel("弹幕 · 最短间隔")
        @ConfigLevel(ConfigLevel.Level.ADVANCED)
        @ConfigSummary(value = "单位：秒，最少 1 秒。", details = false)
        private volatile int minInterval = 3;

        /**
         * 平台回「发送太频繁」或风控信号后，这个账号暂停发弹幕多少分钟，最少 1 分钟；暂停期内一律不发。
         */
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        @ConfigLabel("弹幕 · 被限制后暂停")
        @ConfigLevel(ConfigLevel.Level.ADVANCED)
        @ConfigSummary(value = "单位：分钟，最少 1 分钟。", details = false)
        private volatile int pauseMinutes = 5;
    }
}
