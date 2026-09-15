package org.frostnova.nova.adapter.onebot.napcat;

import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * NapCat 代登录必须在容器里真的装得起来
 * <p>
 * 三参构造只有编译期保证。容器里少一个协作对象、或插件配置没登记成 bean，
 * 要到真起进程才知道。没配 token 时它自己报未配置，界面据此不显示入口——
 * 装不起来会变成 404，和「路径写错了」分不清。
 */
@DisplayName("NapCat 代登录的容器装配")
class NapCatConfigurationWiringTest {

    @Test
    @DisplayName("三参齐全时能装出代登录 bean，默认未配置")
    void startsWithCollaboratorsAndReportsUnconfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(NapCatConfiguration.class)
                .withBean(OneBotAdapterPluginProperties.class)
                .withBean(ConfigurationFileService.class, () -> mock(ConfigurationFileService.class))
                .withBean(RestTemplate.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(NapCatCredentialService.class);
                    assertThat(ctx.getBean(NapCatCredentialService.class).isConfigured()).isFalse();
                });
    }

    @Test
    @DisplayName("少配置文件服务就起不来")
    void failsWithoutConfigurationFileService() {
        new ApplicationContextRunner()
                .withUserConfiguration(NapCatConfiguration.class)
                .withBean(OneBotAdapterPluginProperties.class)
                .withBean(RestTemplate.class)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(failureChain(ctx.getStartupFailure())).contains("ConfigurationFileService");
                });
    }

    @Test
    @DisplayName("明文 token 经装配后算已配置")
    void configuredWhenTokenSet() {
        new ApplicationContextRunner()
                .withUserConfiguration(NapCatConfiguration.class)
                .withBean(OneBotAdapterPluginProperties.class, () -> {
                    OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
                    properties.getNapcat().setToken("t");
                    return properties;
                })
                .withBean(ConfigurationFileService.class, () -> mock(ConfigurationFileService.class))
                .withBean(RestTemplate.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(NapCatCredentialService.class).isConfigured()).isTrue();
                });
    }

    private static String failureChain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append(current.getClass().getName())
                    .append(": ")
                    .append(current.getMessage())
                    .append('\n');
        }
        return text.toString();
    }
}
