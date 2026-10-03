package org.frostnova.nova.core.config;

import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 在配置数据载入之后立刻记下 application.yml 的内容，并在容器里注册这一份。
 */
public class StandbyBoundConfigCapture implements EnvironmentPostProcessor, Ordered,
        ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Path CONFIG = Path.of("application.yml");

    private final Log log;

    public StandbyBoundConfigCapture() {
        this.log = null;
    }

    public StandbyBoundConfigCapture(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(StandbyBoundConfigCapture.class);
    }

    @Override
    public void postProcessEnvironment(org.springframework.core.env.ConfigurableEnvironment environment,
                                       SpringApplication application) {
        try {
            StandbyBoundConfig.remember(ConfigurationFileService.loadFile(CONFIG));
        } catch (IOException e) {
            if (log != null) {
                log.warn("绑定配置时没能读下 application.yml: " + e);
            }
        }
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        StandbyBoundConfig snapshot = StandbyBoundConfig.take();
        if (snapshot != null) {
            context.getBeanFactory().registerSingleton("novabotStandbyBoundConfig", snapshot);
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
