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
     * 是否启用事件输出
     * <p>
     * <b>默认关闭。</b> 用不到的人不该凭空多一个监听端点，这是它默认关就该关的唯一理由。
     * <p>
     * 启用后<b>只接受来自本机回环地址的连接</b>，且这一点与 {@code server.address} 无关——
     * 即使为了对外提供推送接口把监听地址改成了 0.0.0.0，事件流也只认本机。
     * 需要从其他机器读取时请自行建立 SSH 隧道，<b>直接暴露到公网风险自负</b>：
     * 事件流里有观众的昵称、uid 与消费金额。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private boolean enabled = false;

    /**
     * 事件输出的路径，与配置界面共用 {@code server.port} 端口
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private String path = "/nova/events";

    /**
     * 是否要求出示只读口令
     * <p>
     * <b>默认关闭</b>，保持「本机进程直连」这条既有用法不变（本机 runbook 与本地开发都靠它）。
     * <p>
     * 🔴 <b>装了反向代理就必须打开，这不是可选项。</b>
     * 反代与本程序在同一台机器上，<b>且反代未把 {@code X-Forwarded-*} 送下来时</b>，
     * 它转发过来的连接<b>源地址就是回环</b>——
     * <p>
     * ⚠️ <b>反过来那一半同样要记住</b>：若反代送了 {@code X-Forwarded-*}，
     * 而本程序开着 {@code server.forward-headers-strategy}，
     * 回环判据看到的会是<b>真实客户端 IP</b>，于是<b>把反代自己挡在门外</b>——
     * 2026-08-13 生产实测到这个形态，症状是握手回一个<b>空的 200</b>。
     * 也就是说<b>反代一上线，「只接受本机连接」这道判据就不再等于「人在这台机器上」</b>，
     * 任何能连上反代的人在端点看来都是本机。此时不开这个开关，等于没有鉴权。
     * <p>
     * 口令由控制台签发，<b>只能读事件流</b>：既不给服务器 shell，也不给配置控制台。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private boolean requireToken = false;

    /**
     * 断线回补的缓冲条数
     * <p>
     * 缓冲是全部房间共用的一条流。三个房间每分钟各 100 条时，2000 条约覆盖 6 分钟，
     * 足够客户端断线重连；房间更多或更热闹就要相应调大，否则重连时会被告知补不上而重置。
     * <p>
     * 每条约数 KB（含原始报文），2000 条量级在十 MB 上下，调大前先掂量内存。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int bufferSize = 2000;
}
