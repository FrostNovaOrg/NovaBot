package com.starlwr.bot.core.protocol;

import com.starlwr.bot.core.properties.EventStreamProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * 事件输出协议服务端的装配
 * <p>
 * <b>刻意不用 {@code @EnableWebSocket}。</b> 这里要装配的东西一共就一个映射，手工写出来反而清楚：
 * {@code @EnableWebSocket} 做的也正是「把 WebSocketHttpRequestHandler 挂进一个
 * SimpleUrlHandlerMapping」这件事。
 * <p>
 * 开关同理不走 {@code @ConditionalOnProperty}，直接在方法里读配置：关闭时返回一个空映射，
 * 既不占路径也不起心跳线程。
 *
 * <h2>装配方式</h2>
 * 普通的 {@code @Configuration}，由核心自身的组件扫描拾取。<b>不挂 {@code @StarBotComponent}</b>——
 * 核心不是插件，事件输出是它自带的能力，装配也就不该绕经按约定只给插件用的那个注解。
 */
@Slf4j
@Configuration
public class NovaEventStreamConfiguration implements DisposableBean {
    /**
     * 映射优先级。取一个比默认映射靠前的值，避免路径被通配的静态资源处理器抢走
     */
    private static final int ORDER = 1;

    private NovaEventEndpoint endpoint;

    /**
     * 解析事件输出配置，新旧两套键都认
     * <p>
     * <b>逐项覆盖，不是整段二选一。</b> 先按旧键 {@code starbot.bilibili.event-stream} 绑一趟，
     * 再按现行键 {@code starbot.core.event-stream} 绑第二趟：第二趟只会写入真的出现在配置里的项，
     * 没写的项原样留着第一趟的值。于是「新键在场时压过旧键、缺的项由旧键补上」这句话
     * 落在每一项上而不只是整段上，两套键都没写的项拿到的则是字段自带的默认值。
     * <p>
     * 分两趟绑而不是让 Spring 自动绑，是因为要分辨「这一项写没写」：
     * 自动绑定给出的只有绑完之后的值，分辨不出默认值与显式写成同一个值。
     * @param environment 运行环境
     * @return 解析后的配置
     */
    @Bean
    public EventStreamProperties eventStreamProperties(Environment environment) {
        EventStreamProperties properties = new EventStreamProperties();
        Binder binder = Binder.get(environment);

        boolean legacy = binder.bind(EventStreamProperties.LEGACY_PREFIX, Bindable.ofInstance(properties)).isBound();
        boolean current = binder.bind(EventStreamProperties.PREFIX, Bindable.ofInstance(properties)).isBound();

        if (legacy) {
            log.warn("配置项 {}.* 已改名为 {}.*, 旧键仍然有效, 但请尽快改过来{}",
                    EventStreamProperties.LEGACY_PREFIX, EventStreamProperties.PREFIX,
                    current ? "。两套键同时存在时以新键为准, 新键未写到的项才取旧键的值" : "");
        }

        return properties;
    }

    /**
     * 编号与回补中枢
     * <p>
     * 无论开关是否打开都建出来：它不占线程、不占端口，广播器持有它即可，
     * 免得下游到处判空。
     * @param properties 配置
     * @return 事件流
     */
    @Bean
    public NovaEventStream novaEventStream(EventStreamProperties properties) {
        return new NovaEventStream(properties.getBufferSize());
    }

    /**
     * 事件输出端点的路径映射
     * @param properties 配置
     * @param stream 事件流
     * @param tokenService 只读口令校验
     * @return 路径映射，未启用时为空映射
     */
    @Bean
    public HandlerMapping novaEventStreamHandlerMapping(EventStreamProperties properties, NovaEventStream stream,
                                                        EventStreamTokenService tokenService) {
        if (!properties.isEnabled()) {
            return new SimpleUrlHandlerMapping(Map.of(), ORDER);
        }

        // 要求口令时把口令服务交给端点：认证发生在连接建立之后的第一帧，不在握手里
        endpoint = new NovaEventEndpoint(stream, properties.isRequireToken() ? tokenService : null);

        WebSocketHttpRequestHandler handler = new WebSocketHttpRequestHandler(endpoint, new DefaultHandshakeHandler());
        handler.getHandshakeInterceptors().add(new LoopbackOnly());
        // 无论开不开口令都装：它拦的是「把凭据塞进握手」这个动作本身。
        // 只在开口令时装的话，没开口令的部署仍会把旧客户端的口令原样记进代理日志
        handler.getHandshakeInterceptors().add(new NoCredentialsInHandshake());

        if (properties.isRequireToken()) {
            log.info("事件输出已启用, 路径 {}, 需在连接后首帧出示只读口令", properties.getPath());
        } else {
            log.info("事件输出已启用, 地址: ws://127.0.0.1:<server.port>{}, 仅接受本机连接", properties.getPath());
            // 这条提示存在的理由：反代与本程序同机、且反代未送 X-Forwarded-* 时，
            // 转发过来的连接源地址就是回环，
            // 「只接受本机连接」届时不再等于「人在这台机器上」——装了反代却没开这个开关，
            // 表面上一切正常，实际上门是敞开的
            log.warn("事件输出未要求口令。⚠️ 这台机器上若装了反向代理, 必须打开 "
                    + EventStreamProperties.PREFIX + ".require-token: "
                    + "反代若未送 X-Forwarded-*, 转发过来的连接源地址就是回环, "
                    + "「只接受本机连接」将不再拦得住任何人");
        }
        return new SimpleUrlHandlerMapping(Map.of(properties.getPath(), handler), ORDER);
    }

    @Override
    public void destroy() {
        if (endpoint != null) {
            endpoint.shutdown();
        }
    }

    /**
     * 只放行来自本机回环地址的握手
     * <p>
     * 判据取自 {@code getRemoteAddress()}。原意是「只看 TCP 对端，不看请求头」——
     * 因为 {@code X-Forwarded-For} 这类头客户端可以随便写，拿它做访问控制等于没做。
     * <p>
     * 🔴 <b>但「不看请求头」这句话有前提，2026-08-13 生产上被推翻过一次。</b>
     * 本程序若开着 {@code server.forward-headers-strategy}（配置控制台的登录锁定
     * 与 https 判定要它），Spring 会用 {@code X-Forwarded-For}
     * <b>改写 {@code getRemoteAddress()}</b>——于是这道判据看到的不再是 TCP 对端，
     * 而是那个头里写的地址。
     * <p>
     * 后果是<b>反直觉的</b>：反代若把 {@code X-Forwarded-*} 送下来，
     * 这道判据看到真实客户端 IP，于是<b>把反代自己挡在门外</b>，
     * 症状是握手回一个<b>空的 200</b>（不是 401、不是 403）。
     * 正确做法是让反代<b>剥掉</b> {@code X-Forwarded-*}（见 {@code dist/templates/Caddyfile}），
     * 让这里如实看到回环，再由首帧口令充当真正的门。
     * <p>
     * ⚠️ 所以这道判据<b>不是</b>在开了 forwarded 策略时仍然可信的来源判据：
     * 它的可信度取决于反代有没有剥头。**要真正的门，靠 {@code require-token}。**
     * <p>
     * 这道拦截与 {@code server.address} 相互独立：把监听地址放开是为了对外提供推送接口，
     * 不等于愿意把观众昵称、uid 与消费金额一并放出去。
     */
    private static class LoopbackOnly implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler handler, Map<String, Object> attributes) {
            InetSocketAddress remote = request.getRemoteAddress();
            if (remote != null && remote.getAddress() != null && remote.getAddress().isLoopbackAddress()) {
                return true;
            }

            log.warn("拒绝了来自 {} 的事件输出连接: 只接受本机连接, 跨机访问请建立 SSH 隧道", remote);
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Exception exception) {
        }
    }
}
