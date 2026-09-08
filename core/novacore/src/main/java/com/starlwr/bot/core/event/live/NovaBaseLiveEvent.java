package com.starlwr.bot.core.event.live;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.NovaExternalBaseEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * NovaBot 直播事件基类
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaBaseLiveEvent extends NovaExternalBaseEvent {
    /**
     * 平台下发的原始报文，供事件输出协议透传与排障使用，取不到时为空
     * <p>
     * <b>存的是引用而不是序列化后的字符串。</b> 解析器手上本来就有这个对象，
     * 多存一个引用几乎不花钱；而 {@code toJSONString()} 在每分钟数百条消息的房间里
     * 是实打实的开销，且绝大多数场景根本用不到——真要用的时候再序列化。
     * <p>
     * <b>刻意不进 toString 与 JSON 序列化</b>：{@code EventLogger} 会打印整个事件，
     * 事件命令（{@code EventCommandRunner}）会把事件整体转成 JSON 交给外部命令。
     * 把几 KB 的原始报文塞进这两处，等于让日志与命令参数凭空膨胀一个数量级，
     * 而它们本来都不需要这份数据。
     * <p>
     * <b>不要拿它当业务字段用。</b> 它是「平台当时说了什么」的存档，字段随平台改版而变，
     * 没有任何稳定性承诺；解析出来的结构化字段才是契约。
     */
    @ToString.Exclude
    @JSONField(serialize = false)
    private transient JSONObject rawMessage;

    public NovaBaseLiveEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public NovaBaseLiveEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public NovaBaseLiveEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public NovaBaseLiveEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
