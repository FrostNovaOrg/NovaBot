package org.frostnova.nova.core.config;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.SensitiveFields;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 启动时把「配置文件里还留着、但程序已经不再读」的配置项说出来
 *
 * <h2>为什么要专门说一声</h2>
 * 撤掉一个配置项之后，老配置文件里的那一行不会报错：Spring 默认忽略绑不上的键，
 * 程序照常启动，界面上也看不出异常。<b>使用者会一直以为那一行还在起作用</b>——
 * 而它管的往往正是「机器人怎么才算被叫到」这类每天都在用的行为，
 * 等到发现时已经对着一个不生效的开关调了很久。
 * <p>
 * 所以撤项的同时在这里留一行：安静的失效改成响一次的。<b>只是提醒，不改任何值</b>，
 * 也不替使用者去动他的配置文件。
 *
 * <h2>加一项要写什么</h2>
 * 键名配一句话，说清「现在这件事由什么代替」。只写「已废弃」等于没说——
 * 看见的人还是得去翻更新日志才知道下一步该做什么。
 */
@Slf4j
@Component
public class RetiredConfigurationKeyCheck {
    /**
     * 已撤销的配置项 → 该说的那一句
     */
    private static final Map<String, String> RETIRED = new LinkedHashMap<>();

    static {
        RETIRED.put("starbot.core.command.prefix",
                "命令不再靠前缀触发：群里 @ 机器人后接命令名，私聊直接发命令名。这一行已不起作用，可以删掉");
    }

    private final Environment environment;

    public RetiredConfigurationKeyCheck(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        for (Map.Entry<String, String> entry : RETIRED.entrySet()) {
            // 留空的写法（形如「prefix:」）绑出来是空串而不是 null，同样算「这一行还在」
            if (environment.getProperty(entry.getKey()) != null) {
                log.warn("配置项 {} 已撤销: {}", entry.getKey(), entry.getValue());
            }
        }
        legacyRootHint(environment).ifPresent(log::warn);
    }

    /**
     * 配置里还有写在旧根键 {@code starbot.} 下的项时，给使用者的那一句话
     * <p>
     * 5.4 撤掉了旧根键的兼容读取：整棵旧树一项都不生效，启动却不报错——推送平台是零个、
     * 告警与口令锁跟着没了，首页只说「未配置任何机器人」。这里只提醒，不替使用者改配置文件。
     * <p>
     * 认的是点分键名：yml、properties、命令行 {@code --starbot.…} 与 {@code -Dstarbot.…} 进环境后都是这个形状。
     * 环境变量形（{@code STARBOT_…}）不认：文档和发行模板从没教过用环境变量写配置，
     * 使用者自己脚本里同名开头的变量却可能有，认了就是误报。
     * 撤项表里单独说过的键不重复算。只数键不贴值；值只用来认出开关，开关不算口令。
     *
     * @param environment 运行环境
     * @return 有旧根键时是那句话，没有时为空
     */
    public static Optional<String> legacyRootHint(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return Optional.empty();
        }
        Set<String> names = new HashSet<>();
        boolean secret = false;
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!name.startsWith("starbot.") || RETIRED.containsKey(name)) {
                    continue;
                }
                names.add(name);
                String value = String.valueOf(source.getProperty(name));
                boolean flag = "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
                secret |= SensitiveFields.isSensitive(name, flag ? "java.lang.Boolean" : null);
            }
        }
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("配置里有 " + names.size() + " 项写在 starbot: 下。starbot: 是 5.4 以前的旧根键，"
                + "5.4 起改名为 novabot:，这一整棵没有被读取，里面的设置都没有生效。"
                + "程序不会替你改配置文件，请手工把它们挪到新根键下（还没有新根键的，把 starbot: 改名即可），改完重启"
                + (secret ? "；旧位置还留着口令类设置，改完请删掉" : ""));
    }
}
