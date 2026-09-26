package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.properties.ConfigEffect;
import org.frostnova.nova.core.properties.ConfigLabel;
import org.frostnova.nova.core.config.ConfigLevel;
import org.frostnova.nova.core.plugin.NovaComponent;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * OneBot 适配器插件 配置类
 */
@Getter
@Setter
@Configuration
@NovaComponent
@ConfigurationProperties(prefix = "novabot.adapter.onebot")
public class OneBotAdapterPluginProperties {
    /**
     * OneBot 推送接口的统一路径前缀。
     */
    @Getter
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("OneBot 接口路径前缀")
    private String baseUrl = "/onebot";

    /**
     * OneBot 推送平台列表。在「连接」页或初始设置里保存后立刻断开旧连接、连上新的，不必重启。
     */
    // 连接不在设置页上逐条改，在「连接」页和初始设置里保存。
    @Getter
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    @ConfigLabel("OneBot 推送平台")
    private List<OneBotSender> senders = new ArrayList<>();

    @Getter
    private WebsocketThread websocketThread = new WebsocketThread();

    @Getter
    private Detect detect = new Detect();

    @Getter
    private Security security = new Security();

    @Getter
    private Alert alert = new Alert();

    @Getter
    private NapCat napcat = new NapCat();

    /**
     * 推送接口安全相关
     */
    @Getter
    @Setter
    public static class Security {
        /**
         * 是否启用推送接口安全校验，仅在完全可信的隔离网络中才建议关闭。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("推送接口 · 安全校验")
        private boolean enabled = true;

        /**
         * 允许调用推送接口的来源 IP 白名单，支持精确 IP 与 CIDR 网段；默认仅放行本机回环，外部程序要调用时在这里追加。
         */
        // NovaBot 核心通过回环调用自身推送接口，因此默认值可满足单机部署。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("推送接口 · 允许的来源 IP")
        private List<String> allowIps = new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));

        /**
         * 是否信任反向代理设置的 X-Forwarded-For／X-Real-IP 头；只有确实部署在反代之后才可开，否则来源 IP 可被伪造。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("推送接口 · 信任反向代理")
        private boolean trustProxy = false;

        /**
         * 是否输出鉴权失败的审计日志。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("推送接口 · 鉴权失败审计日志")
        private boolean auditLog = true;

        /**
         * 启动时检测到弱配置是否直接终止启动；默认只告警不拦，对外网可达的部署建议开。
         */
        // 默认不拦是为了既有部署升级后仍能启动。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("推送接口 · 弱防护拒绝启动")
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
             * 是否启用推送接口频率限制。
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            @ConfigLabel("推送接口 · 频率限制")
            private boolean enabled = true;

            /**
             * 单个来源每分钟允许的请求数。
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            @ConfigLabel("推送接口 · 每分钟请求数")
            private int permitsPerMinute = 600;

            /**
             * 可容忍的瞬时突发请求数。
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            @ConfigLabel("推送接口 · 突发请求数")
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
         * 线程池核心线程数。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("OneBot 线程池 · 核心线程数")
        private int corePoolSize = 2;

        /**
         * 线程池最大线程数。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("OneBot 线程池 · 最大线程数")
        private int maxPoolSize = 16;

        /**
         * 线程池任务队列容量。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("OneBot 线程池 · 队列容量")
        private int queueCapacity = 128;

        /**
         * 非核心线程存活时间，单位：秒。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("OneBot 线程池 · 空闲存活")
        private int keepAliveSeconds = 300;
    }

    /**
     * 检测相关
     */
    @Getter
    @Setter
    public static class Detect {
        /**
         * 是否启用 HTTP 服务可用性检测。关掉后 OneBot 实现中途挂掉不会被察觉，表现为消息发不出去且无人告知。
         */
        // 运行状态页只能显示启动时那一次检查的结果。检测本身只是一次轻量接口调用，默认开启。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("OneBot 探测 · HTTP 可用性")
        private boolean enableHttpDetect = true;

        /**
         * 隔多少秒做一次 HTTP 服务可用性检测。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("OneBot 探测 · HTTP 周期")
        private int httpDetectInterval = 300;

        /**
         * OneBot 接口调用超过多少毫秒判定为「慢」。只判通不通会漏掉一类故障：接口不报错但每次要几秒，带图的推送会超时被丢。设 0 或负数关闭这项判定。
         */
        // 默认 2000 毫秒是照实测定的：健康时（2026-08-08/09）中位 0.00 秒、p99 2.4 秒；
        // 退化时（2026-08-10，cgroup 内存节流）中位 2.83 秒、p90 10.4 秒。判据取最近
        // 若干次的中位数而非最近一次，健康时的偶发毛刺因此不会翻黄。图片是几百 KB 的
        // 内联 base64，转发一慢就没有工作线程去读它，最终卡满超时被丢弃。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("OneBot 探测 · 慢调用阈值")
        private int slowThresholdMillis = 2000;

        // 告警收敛间隔原本在此按通道各配一份，现已统一由 novabot.core.alert.convergence-interval
        // 管理：同一个故障不该因为出口不同而各有一套抑制规则

        /**
         * 是否启用长连接存活检测。长连接可能看着还在、其实收不到数据，要靠多久没收到心跳来发现。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("OneBot 探测 · 长连接存活")
        private boolean enableWebsocketDetect = true;

        /**
         * 长连接静默多少秒判定为失效。判据是心跳、与群里有没有人说话无关，配得比心跳间隔短也不会误报；对方关了心跳时本项不生效。
         */
        // 按「多久没人发言」判断的话，半夜必然误报。实际超时不会小于三个心跳周期。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("OneBot 探测 · 长连接静默")
        private int websocketSilenceTimeout = 120;

    }

    /**
     * 机器人告警目标
     */
    @Getter
    @Setter
    public static class Alert {
        /**
         * 接收告警的推送平台名，留空则不通过机器人告警。改完立即生效，不必重启。
         */
        // 收件人这几项之所以能即时生效：告警通道每次发送前都重新读它们。
        // 需要告警的时候往往正是配错了的时候，要等重启才生效的话，改对了也得先没有告警地跑到下次启动
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        @ConfigLabel("机器人告警 · 推送平台")
        private String platform = "";

        /**
         * 告警发到哪：填 1 发到群，填 0 发给好友，与推送页的「群／好友」同一套数字；不要填 2，发不出去。改完立即生效。
         */
        // 取值与 PushTargetType 的 code 对应：GROUP 是 1，FRIEND 是 0。
        // 与平台名、号码同进退：三项合起来才是一个收件地址，只让其中一项立刻生效，
        // 群改私聊之后那条告警会发到上一个地址去
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        @ConfigLabel("机器人告警 · 目标类型")
        private int type = 0;

        /**
         * 接收告警的群号或用户号，改完立即生效，不必重启。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        @ConfigLabel("机器人告警 · 目标号码")
        private Long num;
    }

    /**
     * NapCat WebUI 并入本控制台所需的凭据
     * <p>
     * 只有把 NapCat 的 WebUI 反代到本控制台路径下、且不想让使用者再单独登录一次它时，
     * 才需要填这一节。填了之后，NovaBot 会在使用者点进去时替他换一把 NapCat 的凭据——
     * <b>使用者只在 NovaBot 这一侧配置与操作</b>。
     *
     * <h2>为什么不省掉 NapCat 自己那道门</h2>
     * 省不掉：它是另一个进程的鉴权，我们只能替使用者过，不能替它取消。
     * 外层那道门（反代的 {@code auth_request} → 本控制台会话）才是真正把关的，
     * 它要求的仍是完整的口令 + 二次验证，只需要过一次。
     */
    @Getter
    @Setter
    public static class NapCat {
        /**
         * NapCat WebUI 的令牌，填明文即可，启动时自动换成哈希写回、明文不留盘。哈希与令牌权限等同，别当成了加密就放松保管。
         */
        // 换算出来的哈希在权限上与 token 完全等价——NapCat 的登录接口收的就是它，
        // 拿到哈希的人照样登得进去。这一步的收益不是「更安全」，而是「不在这台机器上
        // 多造一份 token 的副本」：原文在 NapCat 自己的配置里本来就有，NovaBot 不需要
        // 第二份。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("NapCat · WebUI 令牌")
        private String token = "";

        /**
         * 上一项换算出的哈希，程序自动写回，不必手填；NapCat 的登录接口收的就是这个形态。
         */
        // 形态是 SHA-256(token + ".napcat") 的十六进制串——这不是我们选的，是 NapCat
        // 的登录接口就收这个。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("NapCat · 令牌哈希")
        private String tokenHash = "";

        /**
         * NapCat WebUI 的二次验证密钥（Base32），它开了 2FA 才需要填；只能明文保存，配置文件权限要收紧到仅属主可读。
         */
        // 代登录时要用它现算验证码，而算码需要密钥本身——这一点没有折中办法。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("NapCat · 二次验证密钥")
        private String totpSecret = "";

        /**
         * NapCat WebUI 的地址，默认本机回环；代登录要拿凭据去换凭据，别改成离开本机的地址。
         */
        // 这条请求一旦离开本机，凭据就上了网线。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("NapCat · WebUI 地址")
        private String address = "http://127.0.0.1:6099";
    }
}
