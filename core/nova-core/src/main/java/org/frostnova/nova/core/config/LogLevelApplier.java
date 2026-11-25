package org.frostnova.nova.core.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.FilterAttachable;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 设置页上改了控制台或日志文件的级别，当场改到对应那一路输出上
 * <p>
 * 级别在 {@code logback.xml} 里是各路输出自己的门槛（{@link ThresholdFilter}），启动时按配置设一次。
 * 只把新值写回配置对象的话，门槛还是启动时那个：改成 DEBUG 看不到新日志，改成 WARN 也照样一屏 INFO。
 * 这里按输出的名字找到那几路，把门槛换成新级别；事件日志与网络日志那两路的门槛是写死的，不在此列。
 * <p>
 * 未设（删掉这一项）按 INFO，与 {@code logback.xml} 里的缺省同一个。
 */
@Slf4j
@Component
public class LogLevelApplier implements SettingsAppliedListener {
    /**
     * 控制台那一路的输出名，见 {@code logback.xml}
     */
    static final Set<String> CONSOLE_APPENDERS = Set.of("Console", "ConsoleQrCode");

    /**
     * 日志文件那一路的输出名，见 {@code logback.xml}
     */
    static final Set<String> FILE_APPENDERS = Set.of("RollingFile");

    static final String CONSOLE_KEY = "novabot.core.log.console";
    static final String FILE_KEY = "novabot.core.log.file";

    private final NovaCoreProperties properties;

    /**
     * 日志上下文从哪儿取：运行中就是框架那一个
     */
    private final Supplier<ILoggerFactory> loggers;

    @Autowired
    public LogLevelApplier(NovaCoreProperties properties) {
        this(properties, LoggerFactory::getILoggerFactory);
    }

    /**
     * @param properties 配置
     * @param loggers 日志上下文从哪儿取，测试里给一个单独建的
     */
    public LogLevelApplier(NovaCoreProperties properties, Supplier<ILoggerFactory> loggers) {
        this.properties = properties;
        this.loggers = loggers;
    }

    @Override
    public Set<String> keys() {
        return Set.of(CONSOLE_KEY, FILE_KEY);
    }

    @Override
    public void applied(Map<String, String> changes) {
        ILoggerFactory factory = loggers.get();
        if (!(factory instanceof LoggerContext context)) {
            throw new IllegalStateException("日志框架不是 logback，改不了输出门槛");
        }
        if (changes.containsKey(CONSOLE_KEY)) {
            apply(context, CONSOLE_APPENDERS, properties.getLog().getConsole());
        }
        if (changes.containsKey(FILE_KEY)) {
            apply(context, FILE_APPENDERS, properties.getLog().getFile());
        }
    }

    /**
     * 把这几路输出的门槛换成新级别
     * @param context 日志上下文
     * @param names 输出名
     * @param level 新级别，未设为 null，按 INFO
     */
    static void apply(LoggerContext context, Set<String> names, Level level) {
        Level effective = level == null ? Level.INFO : level;
        Map<String, Appender<ILoggingEvent>> found = appenders(context);
        int changed = 0;
        for (String name : names) {
            Appender<ILoggingEvent> appender = found.get(name);
            if (appender == null) {
                continue;
            }
            for (var filter : ((FilterAttachable<ILoggingEvent>) appender).getCopyOfAttachedFiltersList()) {
                if (filter instanceof ThresholdFilter threshold) {
                    threshold.setLevel(effective.levelStr);
                    changed++;
                }
            }
        }
        if (changed == 0) {
            throw new IllegalStateException("没找到输出 " + names + "，日志配置可能被改过");
        }
        log.info("日志输出 {} 的级别已改为 {}", names, effective);
    }

    /**
     * 挂在各个记录器上的全部输出，按名字；异步输出里包着的那一路也算
     */
    private static Map<String, Appender<ILoggingEvent>> appenders(LoggerContext context) {
        Map<String, Appender<ILoggingEvent>> found = new LinkedHashMap<>();
        for (Logger logger : context.getLoggerList()) {
            collect(logger.iteratorForAppenders(), found);
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Iterator<Appender<ILoggingEvent>> appenders, Map<String, Appender<ILoggingEvent>> found) {
        while (appenders.hasNext()) {
            Appender<ILoggingEvent> appender = appenders.next();
            if (appender.getName() == null || found.putIfAbsent(appender.getName(), appender) != null) {
                continue;
            }
            if (appender instanceof AppenderAttachable<?> nested) {
                collect(((AppenderAttachable<ILoggingEvent>) nested).iteratorForAppenders(), found);
            }
        }
    }
}
