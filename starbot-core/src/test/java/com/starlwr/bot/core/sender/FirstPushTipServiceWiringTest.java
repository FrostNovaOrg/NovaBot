package com.starlwr.bot.core.sender;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 首次推送提示这一件在没有数据源时也必须装得起来
 * <p>
 * 数据源由平台插件提供，核心自己不起一个。只有核、没有插件时，
 * 若把数据源当成必填依赖，整个进程就起不来——而「还没接平台」
 * 本来就是一种合法的运行形态。就绪回调在没有数据源时应当直接返回，
 * 不能把空依赖当成已经补记过。
 */
@DisplayName("首次推送提示的容器装配")
class FirstPushTipServiceWiringTest {
    @TempDir
    Path stateDir;

    @Test
    @DisplayName("没有数据源 bean 时上下文起得来，就绪回调也不抛")
    void contextStartsWithoutDataSourceBean() {
        WiringConfig.stateDir = stateDir;
        new ApplicationContextRunner()
                .withUserConfiguration(WiringConfig.class)
                .withBean(FirstPushTipService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FirstPushTipService.class);
                    assertDoesNotThrow(() ->
                            context.getBean(FirstPushTipService.class).onApplicationReadyEvent());
                });
    }

    /**
     * 只给状态仓与配置两个侧件，故意不登记数据源
     */
    @Configuration
    static class WiringConfig {
        static Path stateDir;

        @Bean
        StarBotCoreProperties starBotCoreProperties() {
            StarBotCoreProperties properties = new StarBotCoreProperties();
            properties.getLive().setLiveDataPath(stateDir.resolve("data.json").toString());
            return properties;
        }

        @Bean
        StarBotStateStore starBotStateStore(StarBotCoreProperties properties) {
            return new StarBotStateStore(properties);
        }
    }
}
