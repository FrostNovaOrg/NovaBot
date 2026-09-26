package org.frostnova.nova.adapter.onebot.extension.napcat.config;

import org.frostnova.nova.core.properties.ConfigEffect;
import org.frostnova.nova.core.properties.ConfigLabel;
import org.frostnova.nova.core.plugin.NovaComponent;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * NapCat 扩展插件的配置
 * <p>
 * 只有一项开关。「打给谁、用哪个 Token」不在这里配：那几项跟着推送平台自己的配置走
 * （见 {@code OneBotSender}），一台机器上连着几个 OneBot 实例就各有各的一份，
 * 提到这里来只会剩一份。
 */
@Getter
@Setter
@Configuration
@NovaComponent
@ConfigurationProperties(prefix = "novabot.adapter.onebot.extension.napcat")
public class OneBotAdapterNapcatExtensionPluginProperties {
    /**
     * @全体成员 次数用完时改发群待办。默认开着——只在 @ 本来就发不出去时才起作用；关掉要重启才生效。
     */
    // 不开的结果是那条开播通知照常淹在聊天记录里，开着没有额外代价。程序只在启动时
    // 决定做不做这件事，运行中改了要到下次启动才起作用。
    // 键名要与 BackupAtAllAspect 上的 @ConditionalOnProperty 一起改。只改一处的话，开关看着还在，拨过去却没有任何反应。
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("@全体成员 · 用完改发待办")
    private boolean enableBackupAtAll = true;
}
