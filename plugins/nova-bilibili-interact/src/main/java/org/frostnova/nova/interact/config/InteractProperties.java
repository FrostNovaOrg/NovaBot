package org.frostnova.nova.interact.config;

import lombok.Getter;
import lombok.Setter;
import org.frostnova.nova.core.plugin.NovaComponent;
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
 * 只有发言账号凭据放在哪两项。复检间隔、自动续期、扫码方式、加密与否都跟监听账号走同一份配置：
 * 两个账号按同样的节奏核验与续期，存盘的加密方式也一样。
 */
@Getter
@Setter
@Configuration
@NovaComponent
@ConfigurationProperties(prefix = "novabot.bilibili.interact")
public class InteractProperties {
    private final Account account = new Account();

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
}
