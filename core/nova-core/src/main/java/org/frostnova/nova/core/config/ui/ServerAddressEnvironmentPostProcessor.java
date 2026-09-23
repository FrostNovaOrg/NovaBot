package org.frostnova.nova.core.config.ui;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动期把监听地址的 {@link java.net.InetAddress#toString()} 形态收成裸地址，空值回落本机
 * <p>
 * 配置文件里若是 {@code /127.0.0.1}，Spring 绑定 {@code ServerProperties.address} 会直接失败，
 * 进程在任何 Bean 起来之前就停。文件读写服务那时还没装上，必须在环境后处理这一步改。
 * 归一规则只写在 {@link InetAddressText}，键集也只那一份，这里只负责把它接到启动链上。
 * <p>
 * 另一半：配置文件里把监听地址写成空值（{@code address:}、{@code address: ""}、{@code address: ~}
 * 三种形都加载成空串）时，外部件优先级高于内置默认，空串会把 {@code 127.0.0.1} 盖掉，
 * 绑定 {@code InetAddress} 得 null，Tomcat 就监听所有网卡。5.3.0 到 5.5.1 第一次保存设置
 * 写进文件的正是这个空值，于是存过一次设置的已装实例占大多数，升级后照旧对外开着。
 * 空值当作没写，回落本机。
 * <p>
 * 只补「没人写」的那一档：环境变量 {@code SERVER_ADDRESS} 与命令行 {@code --server.address}
 * 非空时 {@link ConfigurableEnvironment#getProperty(String)} 拿到的就是那条非空值，
 * 走不进空值这一支，它们的值也不会被这里盖掉。完全没写（{@code raw == null}）的那一键
 * 同样不替它补默认——默认不另开管理端口时的 {@code management.server.address} 就是这一档。
 * <p>
 * 🔴 本类是 {@code EnvironmentPostProcessor} 而不是普通的 Bean：绑定发生在容器刷新期间，
 * 任何 {@code @PostConstruct} 都排在它后面，排在后面等于没有。
 */
public class ServerAddressEnvironmentPostProcessor implements EnvironmentPostProcessor {
    private static final String SOURCE_NAME = "novaBotServerAddress";

    /**
     * 空值的回落地址。🔴 必须与内置 {@code application.yml} 的 {@code server.address} 一致：
     * 同一个默认值写两处，改一处忘一处的表现是「空值回落到 A，默认却是 B」。
     */
    private static final String FALLBACK_ADDRESS = "127.0.0.1";

    private final Log log;

    public ServerAddressEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(ServerAddressEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> fixes = new LinkedHashMap<>();
        for (String key : InetAddressText.ADDRESS_KEYS) {
            String raw = environment.getProperty(key);
            if (raw == null) {
                continue;
            }
            if (raw.isEmpty()) {
                fixes.put(key, FALLBACK_ADDRESS);
                log.info("监听地址 " + key + " 是空值，当作没写，启动期已回落成 " + FALLBACK_ADDRESS);
                continue;
            }
            String canonical = InetAddressText.fromFile(raw);
            if (raw.equals(canonical)) {
                continue;
            }
            fixes.put(key, canonical);
            log.info("监听地址 " + key + " 是斜杠形态 " + raw + "，启动期已收成 " + canonical);
        }
        if (!fixes.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, Map.copyOf(fixes)));
        }
    }
}
