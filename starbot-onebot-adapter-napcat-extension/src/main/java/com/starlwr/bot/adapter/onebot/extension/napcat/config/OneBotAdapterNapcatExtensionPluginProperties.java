package com.starlwr.bot.adapter.onebot.extension.napcat.config;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * StarBotOneBotAdapterNapcatExtensionPlugin 配置类
 */
@Getter
@Setter
@Configuration
@StarBotComponent
@ConfigurationProperties(prefix = "starbot.adapter.onebot.extension.napcat")
public class OneBotAdapterNapcatExtensionPluginProperties {
    /**
     * 是否启用发送 @全体成员 次数不足时替换为群待办
     */
    private boolean enableBackupAtAll = true;
}
