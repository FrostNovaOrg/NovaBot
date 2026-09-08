package com.starlwr.bot.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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
    }
}
