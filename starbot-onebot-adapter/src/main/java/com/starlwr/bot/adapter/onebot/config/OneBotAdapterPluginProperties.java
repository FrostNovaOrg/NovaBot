package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.config.ConfigEffect;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * StarBotOneBotAdapterPlugin 配置类
 */
@Getter
@Setter
@Configuration
@StarBotComponent
@ConfigurationProperties(prefix = "starbot.adapter.onebot")
public class OneBotAdapterPluginProperties {
    /**
     * OneBot 推送接口统一前缀
     */
    @Getter
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private String baseUrl = "/onebot";

    /**
     * OneBot 推送平台列表
     * <p>
     * 标成即时生效，是因为它<b>确实</b>即时生效：控制台「机器人」页与初始设置第 2 步
     * 存下连接信息之后，适配器当场按新值断旧连新（见 OneBotConnectionManager），
     * 不必重启。落地动作不在设置页那条通用保存通道上——列表元素按设计不在设置页上展示，
     * 因此这一项另在 RuntimeConfigurationApplier 的「另有专门入口落地」表里挂了号。
     */
    @Getter
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private List<OneBotSender> senders = new ArrayList<>();

    @Getter
    private WebsocketThread websocketThread = new WebsocketThread();

    @Getter
    private Detect detect = new Detect();

    @Getter
    private Security security = new Security();

    /**
     * 推送接口安全相关
     */
    @Getter
    @Setter
    public static class Security {
        /**
         * 是否启用推送接口安全校验，仅在完全可信的隔离网络中才建议关闭
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean enabled = true;

        /**
         * 允许调用推送接口的来源 IP 白名单，支持精确 IP 与 CIDR 网段
         * <p>
         * 默认仅放行本机回环地址。StarBot 核心通过回环调用自身推送接口，因此默认值可满足单机部署；
         * 需要由外部程序调用推送接口时，在此追加对应地址。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private List<String> allowIps = new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));

        /**
         * 是否信任反向代理设置的 X-Forwarded-For / X-Real-IP 请求头
         * <p>
         * 仅当 StarBot 确实部署在 Nginx 等反向代理之后时才可开启，否则来源 IP 可被任意伪造。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean trustProxy = false;

        /**
         * 是否输出鉴权失败的审计日志
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean auditLog = true;

        /**
         * 启动时检测到弱配置是否直接终止启动
         * <p>
         * 默认仅告警不阻断，以免既有部署升级后无法启动；对外网可达的部署建议设为 true。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean failOnWeakConfig = false;

        @Getter
        private RateLimit rateLimit = new RateLimit();

        /**
         * 频率限制相关
         */
        @Getter
        @Setter
        public static class RateLimit {
            /**
             * 是否启用频率限制
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private boolean enabled = true;

            /**
             * 单个来源每分钟允许的请求数
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private int permitsPerMinute = 600;

            /**
             * 可容忍的瞬时突发请求数
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private int burst = 100;
        }
    }

    /**
     * 线程相关
     */
    @Getter
    @Setter
    public static class WebsocketThread {
        /**
         * 线程池核心线程数
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int corePoolSize = 2;

        /**
         * 线程池最大线程数
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int maxPoolSize = 16;

        /**
         * 线程池任务队列容量
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int queueCapacity = 128;

        /**
         * 非核心线程存活时间，单位：秒
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int keepAliveSeconds = 300;
    }

    /**
     * 检测相关
     */
    @Getter
    @Setter
    public static class Detect {
        /**
         * 是否启用 HTTP 服务可用性检测
         * <p>
         * 关闭后运行状态页只能显示启动时那一次检查的结果，OneBot 实现中途挂掉不会被察觉，
         * 表现为「消息就是发不出去且无人告知」。检测本身只是一次轻量接口调用，默认开启。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean enableHttpDetect = true;

        /**
         * HTTP 服务可用性检测周期，单位: 秒
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int httpDetectInterval = 300;

        /**
         * OneBot 接口调用耗时超过多少即判定为「慢」，单位: 毫秒
         * <p>
         * 只判「通不通」会漏掉一整类故障：接口一直返回 200、账号一直在线，但每次调用要好几秒，
         * 此时带图的推送已经在丢——图片是几百 KB 的内联 base64，转发一慢就没有工作线程去读它，
         * 最终卡满超时被丢弃，而连通性检查全程看不出异常。
         * <p>
         * 默认 2000 毫秒是照实测定的：健康时（2026-08-08/09）中位 <b>0.00 秒</b>、p99 2.4 秒；
         * 退化时（2026-08-10，cgroup 内存节流）中位 <b>2.83 秒</b>、p90 10.4 秒。
         * 判据取最近若干次的<b>中位数</b>而非最近一次，健康时的偶发毛刺因此不会翻黄。
         * 置 0 或负数即关闭这项判定。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int slowThresholdMillis = 2000;

        // 告警收敛间隔原本在此按通道各配一份，现已统一由 starbot.core.alert.convergence-interval
        // 管理：同一个故障不该因为出口不同而各有一套抑制规则

        /**
         * 是否启用 Websocket 存活检测
         * <p>
         * 长连接可能在 TCP 层看似存活却已收不到任何数据，仅靠连接状态判断不出来，
         * 需借助「多久没收到心跳」来发现。默认开启。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean enableWebsocketDetect = true;

        /**
         * Websocket 静默多久判定为连接失效，单位: 秒
         * <p>
         * 判据是 OneBot 实现推送的心跳，与群里有没有人说话无关：按「多久没人发言」判断的话，
         * 半夜必然误报。实际超时不会小于三个心跳周期，因此配得比心跳间隔短也不会误报；
         * OneBot 实现关闭心跳时本项不生效，静默将不再作为判据。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int websocketSilenceTimeout = 120;

    }
}
