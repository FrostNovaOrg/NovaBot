package org.frostnova.nova.core.config.ui;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Locale;
import java.util.Map;

/**
 * 启动期把控制台开关的写法规整成 {@code true}／{@code false}
 * <p>
 * 配置里把 {@code novabot.core.config-ui.enabled} 写成 {@code 1}／{@code on}／{@code yes}
 * 想开的人（YAML 里写数字、环境变量里写 ON 都是这一档），拿到的却是全 404：
 * 十九处装配条件（十八处开着的加关闭那一处）只认字面 {@code true}／{@code false}，
 * 别的写法两边都不装配，日志里也没有一句说为什么。写成 {@code 0}／{@code off}／{@code no}
 * 想关的同病——关是碰巧对了，开着的那套不在，可关闭过滤器也不在。
 * <p>
 * 这里不改那十九处，启动期把值改写成规范写法：不分大小写、去掉首尾空白后，
 * {@code true}／{@code on}／{@code yes}／{@code 1} 算开，{@code false}／{@code off}／{@code no}／{@code 0}
 * 算关；没写这个键照旧按默认开着（缺省由 {@code matchIfMissing} 兜着，这里不替它补值）；
 * 空值当作没写，与监听地址空值同口径；认不出的值一律按关处理——开错的开关顶多是控制台
 * 打不开，还能进配置文件改回来，静默全 404 才是查不出原因的那一种。
 * <p>
 * 🔴 必须是 {@code EnvironmentPostProcessor}：装配条件在容器刷新期间求值，
 * 任何 Bean 都排在它后面，排在后面等于没有。
 */
public class ConfigUiSwitchEnvironmentPostProcessor implements EnvironmentPostProcessor {
    private static final String SOURCE_NAME = "novaBotConfigUiSwitch";

    /**
     * 十九处装配条件认的同一个键
     */
    private static final String KEY = "novabot.core.config-ui.enabled";

    private final Log log;

    public ConfigUiSwitchEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(ConfigUiSwitchEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty(KEY);
        if (raw == null) {
            // 没写：默认开着由装配条件的 matchIfMissing 兜着，不替它补值
            return;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource(SOURCE_NAME, Map.of(KEY, Boolean.TRUE.toString())));
            log.info("控制台开关 " + KEY + " 是空值，当作没写，按默认开着处理");
            return;
        }
        String normalized = normalize(trimmed);
        if (normalized == null) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource(SOURCE_NAME, Map.of(KEY, Boolean.FALSE.toString())));
            log.error("控制台开关 " + KEY + " 写成 \"" + raw + "\"，认不出，已按关（false）处理；要开写 true，要关写 false");
            return;
        }
        if (normalized.equals(raw)) {
            // 已经是规范写法，不用动
            return;
        }
        environment.getPropertySources().addFirst(
                new MapPropertySource(SOURCE_NAME, Map.of(KEY, normalized)));
        log.info("控制台开关 " + KEY + " 写成 \"" + raw + "\"，启动期已当 " + normalized + " 处理");
    }

    /**
     * 规整后的写法：开是 {@code true}，关是 {@code false}，认不出回 {@code null}
     */
    private static String normalize(String trimmed) {
        switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "true":
            case "on":
            case "yes":
            case "1":
                return Boolean.TRUE.toString();
            case "false":
            case "off":
            case "no":
            case "0":
                return Boolean.FALSE.toString();
            default:
                return null;
        }
    }
}
