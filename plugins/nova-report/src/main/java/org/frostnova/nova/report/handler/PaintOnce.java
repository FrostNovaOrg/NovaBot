package org.frostnova.nova.report.handler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 同一件事件推往几个通道时，画法相同的那几个通道共用一张图
 * <p>
 * 分发把<b>同一个事件实例</b>逐个交给各通道的处理器，所以按事件实例认「同一份报告」，
 * 再按画法（版式、金额可见性、配色）分开：几项都相同的通道只画一次，不按通道数重画。
 * <p>
 * 事件实例只按引用认（弱引用作键），用完被回收时这里跟着忘掉，另有十分钟的上限兜底。
 * 画失败的不记下：下一个通道再画一次，不让一次失败把后面几个通道都拖成文字版。
 */
final class PaintOnce {
    private final Cache<Object, Map<String, String>> byEvent = Caffeine.newBuilder()
            .weakKeys()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(64)
            .build();

    /**
     * @param event 分发来的事件实例
     * @param how 画法：凡是会让图不同的都要写进来
     * @param paint 真去画
     * @return 图片的 Base64 编码，画失败时为空
     */
    Optional<String> get(Object event, String how, Supplier<Optional<String>> paint) {
        Map<String, String> drawn = byEvent.get(event, ignored -> new ConcurrentHashMap<>());
        return Optional.ofNullable(drawn.computeIfAbsent(how, ignored -> paint.get().orElse(null)));
    }
}
