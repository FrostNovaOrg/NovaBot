package org.frostnova.nova.core.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
    private boolean enabled = false;

    /**
     * 事件输出的路径，与配置界面共用服务端口。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 路径")
    private String path = "/nova/events";

    /**
     * 是否要求出示只读口令。默认关闭；装了反向代理就必须打开——经反代来的连接在程序看来都像本机，
     * 不开等于没有鉴权。口令由控制台签发，只能读事件流。
     */
    // 反代与本程序同机、未送 X-Forwarded-* 时，转发来的连接源地址就是回环；反过来，
    // 反代送了 X-Forwarded-* 而程序开着 server.forward-headers-strategy 时，回环判据
    // 看到的是真实客户端 IP，会把反代自己挡在门外（2026-08-13 生产实测，症状是握手
    // 回空的 200）。两种形态下「只接受本机连接」都不再等于「人在这台机器上」。
    // 口令只读事件流：不给服务器 shell，不给配置控制台。本机 runbook 与本地开发
    // 靠「本机进程直连」，因此默认关。
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出要求只读口令")
    private boolean requireToken = false;

    /**
     * 断线回补的缓冲条数，全部房间共用。房间多或热闹就调大，否则重连时会补不上；每条约数 KB，调大前先掂量内存。
     */
    // 三个房间每分钟各 100 条时，2000 条约覆盖 6 分钟，足够客户端断线重连。
    // 每条含原始报文，2000 条量级在十 MB 上下。
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件输出 · 回补缓冲条数")
    private int bufferSize = 2000;
}
