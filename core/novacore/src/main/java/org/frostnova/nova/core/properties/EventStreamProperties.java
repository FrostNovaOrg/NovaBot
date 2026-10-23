package org.frostnova.nova.core.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 事件输出（本地 WebSocket 事件流）的配置
 * <p>
 * 把直播间事件按事件输出协议 v2 实时推给本机的其他程序，用于自建面板一类的场景。
 * 它只输出，不接受任何指令。
 * <p>
 * <b>本类只承载字段与说明，实例不由 Spring 直接绑定</b>：实例由
 * {@link org.frostnova.nova.core.protocol.NovaEventStreamConfiguration} 绑出。
 * 类上的注解留着是为了让配置元数据照常生成，配置界面才认得这几项。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = EventStreamProperties.PREFIX)
public class EventStreamProperties {
    /**
     * 现行配置键前缀
     */
    public static final String PREFIX = "novabot.core.event-stream";

    /**
     * 是否启用事件输出。默认关闭；启用后只接受本机连接，流里有观众的昵称与消费金额，别暴露到公网。
     */
    // 只认回环、与 server.address 无关：监听地址改成 0.0.0.0 事件流也只认本机；
    // 跨机读取自行建 SSH 隧道，直接暴露公网风险自负。用不到的人不该凭空多一个监听端点，
    // 这是它默认关就该关的唯一理由。
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出")
    @ConfigSummary("事件中包含观众昵称与消费金额，请勿暴露到公网。")
    private boolean enabled = false;

    /**
     * 事件输出的路径，与配置界面共用服务端口。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 路径")
    @ConfigSummary(value = "与控制台共用服务端口。", details = false)
    private String path = "/nova/events";

    /**
     * 事件输出始终要求只读访问令牌。此键保留兼容已有配置，填写 false 也按 true 运行。令牌由控制台签发，客户端在连接后的第一帧出示。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出要求只读访问令牌")
    @ConfigSummary("始终要求只读访问令牌；此键填写 false 也按 true 运行。客户端在连接后的第一帧出示令牌。")
    private boolean requireToken = true;

    /**
     * 浏览器页面来源列表。填写完整的 http 或 https 来源（协议、主机及可选端口），不带路径；默认空表。未携带 Origin 的本机程序照常连接。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 页面来源")
    @ConfigSummary("填写页面的完整来源，如 https://panel.example 或 http://localhost:7830，不带路径；修改后重启。")
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * 断线回补的缓冲条数，全部房间共用。房间多或热闹就调大，否则重连时会补不上；每条约数 KB，调大前先掂量内存。
     */
    // 三个房间每分钟各 100 条时，2000 条约覆盖 6 分钟，足够客户端断线重连。
    // 每条含原始报文，2000 条量级在十 MB 上下。
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 回补缓冲条数")
    @ConfigSummary("所有房间共享；增大缓冲会增加内存占用。")
    private int bufferSize = 2000;

    /** 同时保留的事件输出连接数，默认 256，包含握手、等待首帧和收尾中的连接。 正整数，修改后重启；其他值使用默认值。 */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 连接总数")
    @ConfigSummary("同时保留的事件输出连接数，默认 256，包含握手、等待首帧和收尾中的连接。 正整数，修改后重启。")
    private int maxConnections = 256;

    /** 等待首帧的连接数，默认 128，包含握手中的连接。 正整数，修改后重启；其他值使用默认值。 */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 等待首帧的连接数")
    @ConfigSummary("等待首帧的连接数，默认 128，包含握手中的连接。 正整数，修改后重启。")
    private int maxPendingConnections = 128;

    /** 同一对端地址的连接数，默认 128；经同一代理接入的连接共用此额度。 正整数，修改后重启；其他值使用默认值。 */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 每来源连接数")
    @ConfigSummary("同一对端地址的连接数，默认 128；经同一代理接入的连接共用此额度。 正整数，修改后重启。")
    private int maxConnectionsPerSource = 128;

    /** 同时进行的握手数，默认 64。 正整数，修改后重启；其他值使用默认值。 */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 同时握手数")
    @ConfigSummary("同时进行的握手数，默认 64。 正整数，修改后重启。")
    private int maxConcurrentHandshakes = 64;

    /** 握手开始后等待连接建立的时间，默认 10000 毫秒。 正整数，修改后重启；其他值使用默认值。 */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 握手等待毫秒数")
    @ConfigSummary("握手开始后等待连接建立的时间，默认 10000 毫秒。 正整数，修改后重启。")
    private int handshakeTimeoutMs = 10000;

    /** 单次写入及关闭等待的时间，默认 10000 毫秒。 正整数，修改后重启；其他值使用默认值。 */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 写入与关闭等待毫秒数")
    @ConfigSummary("单次写入及关闭等待的时间，默认 10000 毫秒。 正整数，修改后重启。")
    private int ioTimeoutMs = 10000;

    /** 校验页面来源列表，格式不合时抛出含说明的异常。 */
    public static void validateAllowedOrigins(List<String> origins) {
        if (origins == null || origins.stream().anyMatch(value -> !validOrigin(value))) {
            throw new IllegalArgumentException("事件输出页面来源请填写完整的 http 或 https 来源，不带路径、末尾斜线或通配符");
        }
    }

    private static boolean validOrigin(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawPath().isEmpty() && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getPort() >= -1 && uri.getPort() <= 65535;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
