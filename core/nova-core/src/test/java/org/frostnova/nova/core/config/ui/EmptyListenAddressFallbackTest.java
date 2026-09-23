package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件里监听地址写成空值的实例，升级后也得只剩本机
 * <p>
 * YAML 里 {@code address:}、{@code address: ""}、{@code address: ~} 三种空形
 * 都加载成空串。外部件优先级高于内置默认，空串盖掉 {@code 127.0.0.1} 之后
 * 绑定 {@code InetAddress} 得 null，Tomcat 就监听所有网卡——而文档处处写着
 * 「默认只听本机」。5.3.0 到 5.5.1 第一次保存设置写进文件的正是这个空值，
 * 于是存过一次设置的已装实例占大多数，升级后照旧对外开着。
 * <p>
 * 本格走真 {@link SpringApplicationBuilder} 加真 YAML 件，读 {@link ServerProperties#getAddress()}。
 * 不用 {@code MapPropertySource} 模拟外部件：模拟不出来「空串盖掉内置默认」这一层，
 * 上一版就是栽在这个缺口上。
 * <p>
 * 阳性对照两条钉住回落只补「没人写」的那一档：环境变量 {@code SERVER_ADDRESS}
 * 非空时（容器靠它开到 0.0.0.0）必须照旧压过空的配置文件；外部件自己写了
 * {@code 0.0.0.0} 的人也必须照旧听所有网卡。
 */
@DisplayName("存过一次设置的实例升级后仍对外网卡开着")
class EmptyListenAddressFallbackTest {
    private static final String KEY = "server.address";

    /**
     * 空的三种形，以及打印用的名字
     */
    private static final String[][] EMPTY_FORMS = {
            {"bare", "server:\n  address:\n"},
            {"quoted-empty", "server:\n  address: \"\"\n"},
            {"tilde", "server:\n  address: ~\n"},
    };

    @TempDir
    Path externalDir;

    @Test
    @DisplayName("外部配置里 address 空着、写着 \"\"、写着 ~ 的实例，升级后都只剩本机"
            + "——否则存过一次设置的实例升级后照旧对外网卡开着")
    void emptyFormsFallBackToLoopback() throws Exception {
        List<String> bad = new ArrayList<>();
        for (String[] form : EMPTY_FORMS) {
            String name = form[0];
            String yaml = form[1];
            try {
                InetAddress bound = bind(yaml, null);
                assertEquals("127.0.0.1", host(bound),
                        "外部配置 " + name + " 形的 " + KEY + " 是空值，绑定成了 "
                                + describe(bound) + " —— 空值应当作没写、回落本机；"
                                + "听所有网卡时推送接口等 /config 以外的接口对外开着");
            } catch (AssertionError e) {
                bad.add(name + ": " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(),
                "空值回落未生效 " + bad.size() + " 形: " + String.join(" | ", bad));
    }

    @Test
    @DisplayName("外部件空值但 SERVER_ADDRESS=0.0.0.0 时照旧听所有网卡（阳性对照）"
            + "——容器的 ENV 不能被回落盖掉")
    void envVarBeatsEmptyFileValue() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envSource());

        InetAddress bound = bind("server:\n  address:\n", environment);
        assertEquals("0.0.0.0", host(bound),
                "SERVER_ADDRESS=0.0.0.0 被空值回落盖掉了 —— 容器里监听地址会掉回只剩本机，"
                        + "对外的 -p 端口映射后面没人听");
    }

    @Test
    @DisplayName("外部件写 0.0.0.0 的人照旧听所有网卡（阳性对照）"
            + "——回落只补「没人写」的那一档，不盖使用者自己写的值")
    void explicitAnyAddressStays() throws Exception {
        InetAddress bound = bind("server:\n  address: 0.0.0.0\n", null);
        assertEquals("0.0.0.0", host(bound),
                "使用者自己写的 0.0.0.0 被空值回落盖掉了 —— 刻意开到网络的人升级后会突然只剩本机");
    }

    /**
     * 用真上下文绑一次 {@code server.address}
     * <p>
     * {@code spring.config.location} 按「内置件在前、外部目录在后」排列，
     * 与默认的「classpath 在前、file:./ 在后」同一口：后一份盖前一份，
     * 于是外部件里的空值才会把内置的 127.0.0.1 盖掉——这一格要验的正是这件事。
     *
     * @param externalYaml 写进外部目录那份 application.yml 的全文
     * @param environment  可选的自定义环境（阳性对照用来模拟环境变量）；null 则用默认环境
     * @return 绑定到的监听地址，空值绑定结果为 null
     */
    private InetAddress bind(String externalYaml, StandardEnvironment environment) throws Exception {
        Path builtIn = builtInConfig();
        Files.writeString(externalDir.resolve("application.yml"), externalYaml);

        String location = builtIn.toUri() + "," + externalDir.toUri() + "/";
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ListenAddressProbe.class)
                .web(WebApplicationType.NONE);
        if (environment != null) {
            builder.environment(environment);
        }
        try (ConfigurableApplicationContext context =
                     builder.run("--spring.config.location=" + location)) {
            return context.getBean(ServerProperties.class).getAddress();
        }
    }

    /**
     * 模拟 {@code SERVER_ADDRESS=0.0.0.0}
     * <p>
     * 必须是 {@link SystemEnvironmentPropertySource}：普通 Map 源认不出
     * {@code SERVER_ADDRESS} 与 {@code server.address} 的松散绑定，
     * 模拟出来的就不是环境变量那一档了。
     */
    private PropertySource<?> envSource() {
        return new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("SERVER_ADDRESS", "0.0.0.0"));
    }

    private static String host(InetAddress address) {
        return address == null ? "null" : address.getHostAddress();
    }

    private static String describe(InetAddress address) {
        return address == null ? "null（所有网卡）" : String.valueOf(address);
    }

    /**
     * 打进程序里的那份默认配置（本模块源码树）
     * <p>
     * 自定位而不写死模块目录名：目录重排时写死的路径会一起失灵，而失灵的样子是
     * 「找不到文件」，和「空值没回落」长得不像，没人会回头查这里。
     */
    private Path builtInConfig() throws Exception {
        Path source = moduleRoot().resolve("src/main/resources/application.yml");
        assertTrue(Files.exists(source), "打进程序里的那份默认配置不见了: " + source);
        return source;
    }

    private Path moduleRoot() throws Exception {
        var location = EmptyListenAddressFallbackTest.class
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
     * 只用来提供 {@link ServerProperties} 的空配置类。不扫组件：这一格只问绑定结果，
     * 不该把整套业务 Bean 也拉起来。
     */
    @EnableConfigurationProperties(ServerProperties.class)
    static class ListenAddressProbe {
    }
}
