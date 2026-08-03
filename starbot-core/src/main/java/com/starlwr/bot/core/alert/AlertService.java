package com.starlwr.bot.core.alert;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警服务
 * <p>
 * 把告警统一投递到所有可用通道，并按「问题标识」做收敛：故障往往持续存在，
 * 不收敛就会反复推送同一条消息，最终使人对告警彻底脱敏，真出事时反而看不见。
 */
@Slf4j
@Service
public class AlertService {
    private final StarBotCoreProperties properties;

    private final ObjectProvider<AlertChannel> channels;

    /**
     * 各问题标识最近一次告警的时间
     */
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();

    @Autowired
    public AlertService(StarBotCoreProperties properties, ObjectProvider<AlertChannel> channels) {
        this.properties = properties;
        this.channels = channels;
    }

    /**
     * 发送告警
     * @param key 问题标识，同一标识在收敛间隔内只会告警一次
     * @param subject 标题
     * @param content 内容
     */
    public void alert(String key, String subject, String content) {
        if (!properties.getAlert().isEnabled()) {
            return;
        }

        if (!shouldSend(key)) {
            log.debug("告警 {} 处于收敛期内, 本次不再发送", key);
            return;
        }

        boolean delivered = false;
        for (AlertChannel channel : channels.orderedStream().toList()) {
            if (!channel.isAvailable()) {
                continue;
            }

            try {
                channel.send(subject, content);
                delivered = true;
            } catch (Exception e) {
                // 单个通道失败不应影响其他通道
                log.error("通过 {} 通道发送告警失败", channel.name(), e);
            }
        }

        if (!delivered) {
            log.warn("未配置任何可用的告警通道, 以下问题仅记录在日志中: {} - {}", subject, content);
        }
    }

    /**
     * 问题已恢复，清除其收敛记录
     * <p>
     * 不清除的话，故障恢复后短时间内再次出现将被收敛掉，从而错过第二次告警。
     * @param key 问题标识
     */
    public void resolve(String key) {
        lastAlertAt.remove(key);
    }

    /**
     * 判断是否应当发送
     */
    private boolean shouldSend(String key) {
        Instant last = lastAlertAt.get(key);
        Instant now = Instant.now();

        if (last != null && Duration.between(last, now).getSeconds() < properties.getAlert().getConvergenceInterval()) {
            return false;
        }

        lastAlertAt.put(key, now);
        return true;
    }
}
