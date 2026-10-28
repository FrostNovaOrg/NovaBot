package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.regex.Pattern;

/** 按 UTC 月份追加控制台操作；记录失败不能改变业务结果。 */
@Slf4j
@Service
public class ConfigUiOperationStore {
    private static final Pattern MONTH_FILE = Pattern.compile("[0-9]{4}-[0-9]{2}\\.jsonl");
    private final NovaCoreProperties properties;
    private final Clock clock;

    @Autowired
    public ConfigUiOperationStore(NovaCoreProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ConfigUiOperationStore(NovaCoreProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    Path directory() {
        Path parent = Path.of(properties.getLive().getLiveDataPath()).toAbsolutePath().getParent();
        return parent.resolve("console-audit");
    }

    public synchronized void append(JSONObject record) {
        try {
            Instant now = clock.instant();
            YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
            Path directory = directory();
            Files.createDirectories(directory);
            record.put("timestamp", now.toEpochMilli());
            Files.writeString(directory.resolve(month + ".jsonl"), record.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            prune();
        } catch (Exception error) {
            // 异常内容可能包含路径或配置，告警只报告异常类型。
            log.warn("控制台操作记录写入或清理失败，操作结果不受影响 ({})", error.getClass().getSimpleName());
        }
    }
    /** 无操作时也清理到期月份，启动时和每天 UTC 零点检查。 */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public synchronized void prune() {
        try {
            Path directory = directory();
            if (!Files.isDirectory(directory)) return;
            YearMonth oldest = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).minusMonths(11);
            try (var files = Files.list(directory)) {
                for (Path path : files.filter(path -> MONTH_FILE.matcher(path.getFileName().toString()).matches()).toList()) {
                    try {
                        YearMonth month = YearMonth.parse(path.getFileName().toString().substring(0, 7));
                        if (month.isBefore(oldest)) Files.deleteIfExists(path);
                    } catch (java.time.format.DateTimeParseException ignored) { }
                }
            }
        } catch (Exception error) {
            log.warn("控制台操作记录清理失败 ({})", error.getClass().getSimpleName());
        }
    }

}
