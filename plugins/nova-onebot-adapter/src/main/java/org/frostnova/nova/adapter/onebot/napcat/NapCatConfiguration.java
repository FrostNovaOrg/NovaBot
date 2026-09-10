package org.frostnova.nova.adapter.onebot.napcat;

import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * NapCat WebUI 代登录组件
 */
@Configuration
@NovaComponent
public class NapCatConfiguration {
    private final OneBotAdapterPluginProperties properties;

    public NapCatConfiguration(OneBotAdapterPluginProperties properties) {
        this.properties = properties;
    }

    /**
     * NapCat WebUI 的代登录
     * <p>
     * 无条件注册：没配 token 时它自己回「未配置」，界面据此不显示入口。
     * 若改用条件注册把这个 Bean 整个去掉，配漏了的表现会变成 404——
     * 那与「路径写错了」长得一模一样，而这两件事该去查的地方完全不同。
     */
    @Bean
    public NapCatCredentialService napCatCredentialService(ConfigurationFileService fileService,
                                                           RestTemplate restTemplate) {
        return new NapCatCredentialService(properties.getNapcat(), fileService, restTemplate);
    }
}
