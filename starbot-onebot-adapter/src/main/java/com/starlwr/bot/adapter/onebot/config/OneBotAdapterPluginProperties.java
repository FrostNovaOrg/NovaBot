package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.adapter.onebot.model.OneBotSender;
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
    private String baseUrl = "/onebot";

    /**
     * OneBot 推送平台列表
     */
    @Getter
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
        private boolean enabled = true;

        /**
         * 允许调用推送接口的来源 IP 白名单，支持精确 IP 与 CIDR 网段
         * <p>
         * 默认仅放行本机回环地址。StarBot 核心通过回环调用自身推送接口，因此默认值可满足单机部署；
         * 需要由外部程序调用推送接口时，在此追加对应地址。
         */
        private List<String> allowIps = new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));

        /**
         * 是否信任反向代理设置的 X-Forwarded-For / X-Real-IP 请求头
         * <p>
         * 仅当 StarBot 确实部署在 Nginx 等反向代理之后时才可开启，否则来源 IP 可被任意伪造。
         */
        private boolean trustProxy = false;

        /**
         * 是否输出鉴权失败的审计日志
         */
        private boolean auditLog = true;

        /**
         * 启动时检测到弱配置是否直接终止启动
         * <p>
         * 默认仅告警不阻断，以免既有部署升级后无法启动；对外网可达的部署建议设为 true。
         */
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
            private boolean enabled = true;

            /**
             * 单个来源每分钟允许的请求数
             */
            private int permitsPerMinute = 600;

            /**
             * 可容忍的瞬时突发请求数
             */
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
        private int corePoolSize = 2;

        /**
         * 线程池最大线程数
         */
        private int maxPoolSize = 16;

        /**
         * 线程池任务队列容量
         */
        private int queueCapacity = 128;

        /**
         * 非核心线程存活时间，单位：秒
         */
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
         */
        private boolean enableHttpDetect = false;

        /**
         * HTTP 服务可用性检测周期，单位: 秒
         */
        private int httpDetectInterval = 300;

        /**
         * HTTP 告警邮件发送最短间隔时间，用于防止短时间内发送大量告警邮件，单位: 秒
         */
        private int httpAlarmMailInterval = 3600;

        /**
         * 是否启用 Websocket 消息接收检测
         */
        private boolean enableWebsocketDetect = false;

        /**
         * 指定时间内未从 Websocket 接收到消息时发送告警邮件，单位: 秒
         */
        private int websocketDetectInterval = 1800;

        /**
         * Websocket 告警邮件发送最短间隔时间，用于防止短时间内发送大量告警邮件，单位: 秒
         */
        private int websocketAlarmMailInterval = 3600;
    }
}
