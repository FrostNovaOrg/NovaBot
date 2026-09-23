package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打进程序里的默认配置得自己选定监听地址
 * <p>
 * 免配置起步的实例第一次跑起来时，程序目录下那份 application.yml 还不存在，
 * 于是<b>监听哪块网卡这件事只剩 jar 里这一份说了算</b>。它不说的话 address 为空，
 * Spring Boot 就监听所有网卡——推送接口等 {@code /config} 以外的接口对外网卡全开着，
 * 而文档处处写着「默认只听本机」。
 * <p>
 * 使用者在自己那份配置里写的值必须盖过这份默认（阳性对照）：内置默认只在「没人写过」时生效，
 * 别做成一进来就强改使用者取值的写法。
 *
 * <h2>为什么读源码树而不读类路径</h2>
 * 判据自己跑的时候用的是 {@code install} 这一档，而这一档<b>刻意不把 application.yml
 * 复制进类路径</b>（那一档产出的是给插件模块依赖的库，不该夹带运行期配置）。
 * 于是从类路径上读到的要么没有，要么是上一次别的档次留下的旧件——
 * 而旧件读起来与刚出炉的一模一样。这一格因此读源码树里那一份。
 */
@DisplayName("内置默认只听本机")
class BuiltInListenAddressDefaultTest {
    private static final String KEY = "server.address";

    @Test
    @DisplayName("新装、没有外部配置的实例第一次启动时只听本机"
            + "——否则推送接口等 /config 以外的接口对外网卡全开着")
    void defaultsToLoopbackWhenNoExternalConfig() throws Exception {
        Path source = builtInConfig();
        MockEnvironment env = new MockEnvironment();
        loadInto(env, source, "classpath:/application.yml");

        String address = env.getProperty(KEY);
        assertEquals("127.0.0.1", address,
                "内置默认配置没把 " + KEY + " 钉成本机回环 —— 没有外部配置的实例会监听所有网卡，"
                        + "推送接口等 /config 以外的接口对外网卡全开着（读的是 " + source + "）");
    }

    @Test
    @DisplayName("外部配置里写了 " + KEY + ": 0.0.0.0 的人照旧听所有网卡"
            + "——内置默认不盖掉使用者自己写的值（阳性对照）")
    void externalConfigOverridesBuiltInDefault() throws Exception {
        Path source = builtInConfig();
        MockEnvironment env = new MockEnvironment();
        // 使用者那份在程序目录下，优先级高于 jar 里这一份：先放它
        env.getPropertySources().addFirst(new MapPropertySource(
                "file:./application.yml", Map.of(KEY, "0.0.0.0")));
        loadInto(env, source, "classpath:/application.yml");

        assertEquals("0.0.0.0", env.getProperty(KEY),
                "使用者自己写的监听地址被内置默认盖掉了 —— 刻意把 server.address 写成 0.0.0.0 "
                        + "想从别的机器连的人，升级后会突然只剩本机连得上");
    }

    /**
     * 打进程序里的那份默认配置（本模块源码树）
     */
    private Path builtInConfig() throws Exception {
        Path source = moduleRoot().resolve("src/main/resources/application.yml");
        assertTrue(Files.exists(source), "打进程序里的那份默认配置不见了: " + source);
        return source;
    }

    /**
     * 本类所在模块的根目录
     * <p>
     * 自定位而不写死模块目录名：目录重排时写死的路径会一起失灵，而失灵的样子是
     * 「找不到文件」，和「默认配置没写监听地址」长得不像，没人会回头查这里。
     */
    private Path moduleRoot() throws Exception {
        var location = BuiltInListenAddressDefaultTest.class
                .getProtectionDomain().getCodeSource();
        if (location == null) {
            throw new IllegalStateException("取不到本类所在位置，定位不了内置默认配置");
        }
        Path start = Path.of(location.getLocation().toURI());
        Path current = start;
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.exists(current.resolve("src/main/resources/application.yml"))) {
                return current;
            }
            current = current.getParent();
        }

        throw new IllegalStateException("未能定位本模块根目录（自 " + start + " 起找）");
    }

    /**
     * 把一份 YAML 按框架自己那套加载法放进环境的末位（优先级最低，与 jar 内置配置同一档）
     */
    private void loadInto(MockEnvironment env, Path yaml, String name) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(name, new FileSystemResource(yaml));
        assertTrue(!sources.isEmpty(), "YAML 没加载出任何属性源: " + yaml);
        for (PropertySource<?> source : sources) {
            env.getPropertySources().addLast(source);
        }
        // 加载法自证：同一份件里 server.port 一直写着 7827，拿它当「加载真的发生了」的尺
        assertEquals("7827", env.getProperty("server.port"),
                "配置没加载进来 —— 下面比的 " + KEY + " 读数不是这份文件说了算，这一格答不了它要答的问题");
    }
}
