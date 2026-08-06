package com.starlwr.bot.core.converter;

import ch.qos.logback.classic.Level;
import com.starlwr.bot.core.util.StringUtil;
import lombok.NonNull;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 日志级别转换器
 * <p>
 * {@code ch.qos.logback.classic.Level} 不是枚举而是一个带静态常量的普通类，
 * Spring 的默认绑定认不出它。<b>缺了这个转换器时，
 * {@code starbot.core.log.console} 只要填了值就会绑定失败并让程序进入安全模式</b>——
 * 一个写在配置模板里、却一填就把程序弄挂的开关。
 * <p>
 * {@link Level#toLevel(String)} 本身对无法识别的字符串<b>会悄悄退回 DEBUG</b>，
 * 那样使用者把 {@code INFO} 拼错成 {@code IFNO} 之后只会发现日志忽然变得极多，
 * 却完全不知道为什么。这里改为直接报错，把问题在启动时就说清楚。
 */
@Component
@ConfigurationPropertiesBinding
public class LogLevelConverter implements Converter<String, Level> {
    /**
     * 允许的取值，顺序与日志级别由低到高一致
     */
    private static final List<Level> LEVELS = List.of(
            Level.ALL, Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, Level.OFF);

    @Override
    public Level convert(@NonNull String source) {
        if (StringUtil.isBlank(source)) {
            return null;
        }

        String value = source.trim().toUpperCase(Locale.ROOT);
        for (Level level : LEVELS) {
            if (level.levelStr.equals(value)) {
                return level;
            }
        }

        throw new IllegalArgumentException("无法解析配置文件中的日志级别: " + source
                + "，可选值为 " + LEVELS.stream().map(level -> level.levelStr).toList());
    }
}
