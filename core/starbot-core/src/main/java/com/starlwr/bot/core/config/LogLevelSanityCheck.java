package com.starlwr.bot.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时检查日志级别配置有没有落进那个「留空即 DEBUG」的坑
 *
 * <h2>坑长什么样</h2>
 * {@code logback.xml} 用 {@code <springProperty defaultValue="INFO">} 读这两项。
 * 但 <b>「配置项留空」与「配置项不存在」不是一回事</b>：
 * 整行删掉时属性确实缺失，{@code defaultValue} 生效；而 YAML 里写成
 * {@code file:} 后面什么都不填时，属性是<b>存在的、值是空串</b>，
 * {@code defaultValue} 不介入，空串传到 {@code ThresholdFilter} 里解析不了，
 * <b>它退回 DEBUG</b>。
 *
 * <h2>为什么值得为它单写一个检查</h2>
 * 这个失效<b>不报错、不告警，只是安静地多写</b>——实测留空跑 25 秒，
 * 日志文件里 140 行 DEBUG 对 87 行 INFO。而 DEBUG 行里带着请求地址、
 * 重试详情与他人昵称，它们会长期留在盘上，且日志文件通常是 0644。
 * <b>使用者没有任何办法从界面或报错上发现这件事</b>，只能靠有人碰巧去数日志行。
 * 所以把它变成一条启动告警：安静的失效改成响的。
 */
@Slf4j
@Component
public class LogLevelSanityCheck {
    /**
     * 会落进这个坑的配置项
     */
    private static final List<String> PROPERTIES = List.of(
            "novabot.core.log.console", "novabot.core.log.file");

    private final Environment environment;

    public LogLevelSanityCheck(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        for (String property : PROPERTIES) {
            String value = environment.getProperty(property);
            // null＝配置项不存在，此时 defaultValue 生效，是安全的；
            // 空串＝配置项存在但没填，这才是那个坑
            if (value != null && value.isBlank()) {
                log.warn("配置项 {} 留空了。留空与整行删掉不是一回事：", property);
                log.warn("  留空传给日志框架的是空串, 它解析不了空串时会退回 DEBUG,");
                log.warn("  于是 DEBUG 全被写进日志, 其中带有请求地址与他人昵称且长期留存。");
                log.warn("  请显式填一个级别（INFO/WARN/…），或把该行整个删掉以使用内置默认 INFO。");
            }
        }
    }
}
