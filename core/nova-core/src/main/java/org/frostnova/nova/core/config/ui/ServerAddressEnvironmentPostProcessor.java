package org.frostnova.nova.core.config.ui;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 启动期把 {@code server.address} 的 {@link java.net.InetAddress#toString()} 形态收成裸地址
 * <p>
 * 配置文件里若是 {@code /127.0.0.1}，Spring 绑定 {@code ServerProperties.address} 会直接失败，
 * 进程在任何 Bean 起来之前就停。文件读写服务那时还没装上，必须在环境后处理这一步改。
 * 归一规则只写在 {@link InetAddressText}，这里只负责把它接到启动链上。
 * <p>
 * 🔴 本类是 {@code EnvironmentPostProcessor} 而不是普通的 Bean：绑定发生在容器刷新期间，
 * 任何 {@code @PostConstruct} 都排在它后面，排在后面等于没有。
 */
public class ServerAddressEnvironmentPostProcessor implements EnvironmentPostProcessor {
    static final String KEY = "server.address";

    private static final String SOURCE_NAME = "novaBotServerAddress";

    private final Log log;

    public ServerAddressEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(ServerAddressEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty(KEY);
        String canonical = InetAddressText.fromFile(raw);
        if (raw == null || raw.equals(canonical)) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, Map.of(KEY, canonical)));
        log.info("监听地址 " + KEY + " 是斜杠形态 " + raw + "，启动期已收成 " + canonical);
    }
}
