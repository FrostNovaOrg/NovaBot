package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.properties.ConfigEffect;
import com.starlwr.bot.core.config.ConfigLevel;
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
@ConfigurationProperties(prefix = "novabot.adapter.onebot")
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

        // 告警收敛间隔原本在此按通道各配一份，现已统一由 novabot.core.alert.convergence-interval
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

    /**
     * 机器人告警目标
     */
    @Getter
    @Setter
    public static class Alert {
        /**
         * 接收告警的推送平台名，留空则不通过机器人告警
         * <p>
         * 改完立即生效，不必重启。
         */
        // 收件人这几项之所以能即时生效：告警通道每次发送前都重新读它们。
        // 需要告警的时候往往正是配错了的时候，要等重启才生效的话，改对了也得先没有告警地跑到下次启动
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        private String platform = "";

        /**
         * 接收告警的目标类型，1 为群聊，0 为私聊
         * <p>
         * 取值必须与 {@link com.starlwr.bot.core.enums.PushTargetType} 的 code 一致：
         * {@code GROUP(1)}、{@code FRIEND(0)}。此处曾误写为「2 为私聊」，而 2 会被解析为
         * {@code UNKNOWN}，告警在发送阶段被直接丢弃，且不留任何痕迹——与 datasource.json 中
         * 推送目标的 type 是同一套编码，不要凭直觉另立一套。改完立即生效，不必重启。
         */
        // 与平台名、号码同进退：三项合起来才是一个收件地址，只让其中一项立刻生效，
        // 群改私聊之后那条告警会发到上一个地址去
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        private int type = 0;

        /**
         * 接收告警的群号或用户号，改完立即生效，不必重启
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
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
         * NapCat WebUI 的 token
         * <p>
         * 填明文即可，<b>启动时会立刻换算成登录用的哈希写回，明文不留在盘上</b>。
         * <p>
         * 🔴 <b>换算出来的哈希与 token 在权限上完全等价</b>——NapCat 的登录接口收的就是它，
         * 拿到哈希的人照样登得进去。所以这一步的收益<b>不是「更安全」</b>，
         * 而是「不在这台机器上多造一份 token 的副本」：原文在 NapCat 自己的配置里本来就有，
         * NovaBot 不需要第二份。别把它当成加密后就可以放松保管的东西。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String token = "";

        /**
         * 上一项换算出来的哈希，由程序写回，不必手填
         * <p>
         * 形态是 {@code SHA-256(token + ".napcat")} 的十六进制串——这不是我们选的，
         * 是 NapCat 的登录接口就收这个。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String tokenHash = "";

        /**
         * NapCat WebUI 的二次验证密钥（Base32）
         * <p>
         * 它开了 2FA 才需要填。<b>只能明文保存</b>：代登录时要用它现算验证码，
         * 而算码需要密钥本身——这一点没有折中办法，因此配置文件的权限必须收紧到仅属主可读。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String totpSecret = "";

        /**
         * NapCat WebUI 在本机的地址
         * <p>
         * 默认回环。<b>不该改成非回环地址</b>：代登录是拿着凭据去换凭据，
         * 这条请求一旦离开本机，凭据就上了网线。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String address = "http://127.0.0.1:6099";
    }
}
