package com.starlwr.bot.core.config;

import ch.qos.logback.classic.Level;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.model.TextWithStyle;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/**
 * StarBotCore 配置类
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "starbot.core")
public class StarBotCoreProperties {
    @Getter
    private final NetworkThread networkThread = new NetworkThread();

    @Getter
    private final Log log = new Log();

    @Getter
    private final Network network = new Network();

    @Getter
    private final DataSource datasource = new DataSource();

    @Getter
    private final Plugin plugin = new Plugin();

    @Getter
    private final Live live = new Live();

    @Getter
    private final Paint paint = new Paint();

    @Getter
    private final Mail mail = new Mail();

    @Getter
    private final ConfigUi configUi = new ConfigUi();

    @Getter
    private final Alert alert = new Alert();

    @Getter
    private final Push push = new Push();

    /**
     * 聊天命令相关
     */
    @Getter
    @Setter
    private final Command command = new Command();

    /**
     * 事件触发外部命令相关
     */
    @Getter
    @Setter
    private final Exec exec = new Exec();

    /**
     * 事件触发外部命令
     * <p>
     * 收到事件时执行一个外部程序，用来接那些内置功能覆盖不到的用法——
     * 开播启动录播、上舰写台账、被切流发提醒。
     * <p>
     * <b>默认关闭</b>：这是个能执行任意程序的口子，必须由使用者明确打开。
     * 执行细节与安全约束见 {@code EventCommandRunner}。
     */
    @Getter
    @Setter
    public static class Exec {
        /**
         * 是否启用事件触发外部命令
         */
        private boolean enabled = false;

        /**
         * 单条命令的最长执行时间，单位：秒，超时后强制结束
         */
        private int timeout = 30;

        /**
         * 同时执行的命令数上限，超出的直接丢弃
         * <p>
         * 弹幕这类事件一秒能来几十条。没有上限的话，一次刷屏就等于一次 fork 炸弹。
         */
        private int maxConcurrent = 4;

        /**
         * 规则列表
         */
        private List<ExecRule> rules = new ArrayList<>();
    }

    /**
     * 一条事件命令规则
     */
    @Getter
    @Setter
    public static class ExecRule {
        /**
         * 是否启用该条规则
         */
        private boolean enabled = true;

        /**
         * 事件类名，简名或全限定名皆可
         * <p>
         * 沿继承链匹配，因此填 {@code LiveOnEvent} 可同时命中各平台的具体开播事件。
         */
        private String event;

        /**
         * 命令与参数
         * <p>
         * <b>第一项是可执行文件，其余各项是独立参数——不是一整行命令。</b>
         * 想用管道、重定向之类的 shell 特性，请自己写一个脚本文件再在这里填它的路径；
         * 把命令拼成一行交给 shell 执行，等于让弹幕内容有机会变成命令。
         * <p>
         * 支持的占位符：{@code {event}} {@code {platform}} {@code {uid}}
         * {@code {uname}} {@code {room_id}} {@code {timestamp}} {@code {json}}。
         */
        private List<String> command = new ArrayList<>();
    }

    /**
     * 聊天命令相关
     */
    @Getter
    @Setter
    public static class Command {
        /**
         * 命令前缀，留空表示直接以命令名触发
         * <p>
         * 群里同时有多个机器人时容易撞词，此时可加前缀（如 {@code /}）区分。
         * 默认留空是因为对只装了一个机器人的多数使用者而言，多打一个符号没有收益。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private String prefix = "";

        /**
         * 超级管理员账号，跨会话生效
         * <p>
         * 「禁用命令」这类操作会改变全群的可用功能，只对管理员开放。
         * <b>群主与群管理员自动拥有权限</b>，此处填的是不依赖群角色的额外名单——
         * 机器人的主人未必是每个群的管理员。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private List<Long> admins = new ArrayList<>();
    }

    /**
     * 推送相关
     */
    @Getter
    @Setter
    public static class Push {
        /**
         * 全局推送开关
         * <p>
         * 关闭后所有推送都会被丢弃，用于调试或临时静音，无需逐条改推送配置。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private boolean enabled = true;

        /**
         * 机器人账号每日最多 @全体成员 的次数，0 或负数表示不限制
         * <p>
         * QQ 本身有每日上限，用超之后**平台会静默忽略**——消息照发但 @ 不生效，
         * 配置的人往往过很久才发现「怎么没人被 @ 到」。因此在自己这一侧先记账，
         * 超额时主动退化为普通消息并记日志，而不是把额度花在注定无效的调用上。
         * <p>
         * <b>这份额度由该账号推送的全部会话共享</b>（实测：往一个群发一次，
         * 其他群看到的账号剩余次数同步减一）。默认 10 与 QQ 实测值一致，
         * <b>它才是真正会先卡住的那一道</b>。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private int atAllDailyLimit = 10;

        /**
         * 单个会话每日最多 @全体成员 的次数，0 或负数表示不限制
         * <p>
         * 与账号额度是两个维度：群的额度由群里所有有权限的人共用，机器人只是其中之一。
         * 默认 20 与 QQ 实测值一致。通常先撞到的是账号额度，本项是第二道保险。
         */
        @ConfigLevel(ConfigLevel.Level.ADVANCED)
        private int atAllSessionDailyLimit = 20;

        /**
         * 静音时段开始时间，格式 HH:mm，与结束时间任一为空即视为不启用
         * <p>
         * 半夜被机器人吵醒是这类通知产品被投诉最多的点，因此内置该能力而不是让使用者自行想办法。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private String quietStart = "";

        /**
         * 静音时段结束时间，格式 HH:mm
         * <p>
         * 允许跨零点：开始 23:00、结束 08:00 表示当晚 23 点至次日 8 点。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private String quietEnd = "";
    }

    /**
     * 告警相关
     */
    @Getter
    @Setter
    public static class Alert {
        /**
         * 是否启用告警
         * <p>
         * 关闭后登录失效、连接中断、队列积压等问题只会写进日志，不会主动通知。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private boolean enabled = true;

        /**
         * 同一问题的最短告警间隔，单位：秒
         * <p>
         * 故障往往持续存在，不做收敛就会反复推送同一条消息，最终使人对告警彻底脱敏。
         */
        private int convergenceInterval = 3600;

        /**
         * 接收告警的推送平台名，留空则不通过 QQ 告警
         * <p>
         * 对本项目的使用者而言，告警直接推到管理员 QQ 远比邮件实用——大多数人并不会为
         * 一个机器人专门配置发件邮箱。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private String qqPlatform = "";

        /**
         * 接收告警的目标类型，1 为群聊，0 为私聊
         * <p>
         * 取值必须与 {@link com.starlwr.bot.core.enums.PushTargetType} 的 code 一致：
         * {@code GROUP(1)}、{@code FRIEND(0)}。此处曾误写为「2 为私聊」，而 2 会被解析为
         * {@code UNKNOWN}，告警在发送阶段被直接丢弃，且不留任何痕迹——与 datasource.json 中
         * 推送目标的 type 是同一套编码，不要凭直觉另立一套。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private int qqType = 0;

        /**
         * 接收告警的群号或 QQ 号
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private Long qqNum;

        /**
         * Webhook 告警地址，留空则不启用
         * <p>
         * <b>QQ 与邮件之外唯一不依赖机器人自身链路的通道。</b>QQ 告警走的是机器人的推送链路，
         * 一旦 OneBot 实现掉线或 QQ 掉登录，需要告警的正是这种时候，而告警本身也一并失效了。
         * Webhook 只需一个地址，适配 Bark、Server 酱、钉钉、飞书、Telegram 等常见服务。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private String webhookUrl = "";

        /**
         * Webhook 请求方式：POST 或 GET
         * <p>
         * POST 提交 JSON（字段名见 webhook-title-field 与 webhook-content-field）；
         * GET 把标题与内容拼进查询串，适配 Bark 这类以路径或查询参数接收的服务。
         */
        private String webhookMethod = "POST";

        /**
         * Webhook JSON 中承载标题的字段名
         * <p>
         * 各服务字段名不统一：Server 酱用 title/desp，钉钉与飞书用嵌套结构，
         * 自建接口则各有各的约定，因此做成可配置而非写死。
         */
        private String webhookTitleField = "title";

        /**
         * Webhook JSON 中承载内容的字段名
         */
        private String webhookContentField = "content";

        /**
         * Webhook 附加请求头，用于需要鉴权的服务，如 {@code Authorization: Bearer xxx}
         */
        private final java.util.Map<String, String> webhookHeaders = new java.util.LinkedHashMap<>();

        /**
         * 发送失败的告警的重投间隔，单位：秒，设为 0 关闭重投
         * <p>
         * <b>需要告警的时候往往正是发不出去的时候</b>：出网劣化、QQ 掉登录、Webhook 服务抖动，
         * 三者都会让告警本身失败，而失败之后此前没有下文——「没收到告警」于是被读成「没出事」。
         */
        private int retryInterval = 60;

        /**
         * 待重投队列的容量上限
         * <p>
         * 满了之后丢最旧的，并在日志里说明丢了哪一条。<b>不静默截断</b>：
         * 悄悄丢掉的告警比没有重投更糟，它会让人以为队列在正常工作。
         */
        private int retryQueueSize = 50;

        /**
         * 单条告警的最大重投次数，超过后放弃并写日志
         */
        private int retryMaxAttempts = 10;
    }

    /**
     * 非插件实现的推送平台配置
     */
    @Getter
    private final List<Sender> sender = new ArrayList<>();

    /**
     * 配置界面相关
     */
    @Getter
    @Setter
    public static class ConfigUi {
        /**
         * 是否启用配置界面
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private boolean enabled = true;

        /**
         * 配置界面访问令牌
         * <p>
         * 留空时每次启动自动生成一个随机令牌并输出到日志。配合默认仅监听回环地址的设置，
         * 单机部署无需任何配置即可安全使用；需要从其他机器访问时在此显式设置一个随机串。
         */
        private String token = "";

        /**
         * 允许访问配置界面的来源 IP 白名单，支持精确 IP 与 CIDR 网段
         * <p>
         * 配置界面可修改推送目标并读取运行状态，权限高于推送接口，默认仅放行本机回环地址。
         */
        private List<String> allowIps = new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));

        /**
         * 口令登录相关
         */
        @Getter
        private final Auth auth = new Auth();

        /**
         * 把 NapCat WebUI 挂在本控制台后面时的凭据
         */
        @Getter
        private final NapCat napcat = new NapCat();

        /**
         * 使用协议的同意记录
         */
        @Getter
        private final Agreement agreement = new Agreement();

        /**
         * 使用协议的同意记录
         * <p>
         * 这一节由程序写回，<b>不必手填</b>：控制台第一次打开时会先显示使用协议，
         * 点了「同意并继续」才写下这两项，此后不再打扰。
         * <p>
         * 记在配置文件里而不是浏览器里：换一台电脑、换一个浏览器打开控制台的仍是同一个使用者，
         * 记在浏览器里等于每换一处就要再同意一次，而那让「同意」变成一个随手点掉的动作。
         */
        @Getter
        @Setter
        public static class Agreement {
            /**
             * 已同意的协议版本号
             * <p>
             * 0 表示尚未同意过。协议文案改版时版本号会加一，届时此处记着的旧版本即刻失效，
             * 使用者会被要求重新确认一次——<b>否则改了文案等于没改</b>，没有人会再看到它。
             */
            private int acceptedVersion = 0;

            /**
             * 同意的时间
             * <p>
             * ISO 格式，留空表示尚未同意过。它不参与任何判断，只是留个凭据：
             * 日后要回答「这台机器上是什么时候同意的」时，答案得在盘上，而不是靠人回忆。
             */
            private String acceptedAt = "";

            /**
             * 同意是从哪条通道点下的
             * <p>
             * {@code password}＝输过登录口令之后同意，{@code operator-token}＝凭启动令牌进来之后同意。
             * <p>
             * <b>留空表示这行记录说不出是谁点的</b>，此时会在下次登录之后再请使用者确认一次。
             * 4.4.0 及更早的版本在登录之前就让人点同意，写下的正是这种记录——
             * 那时任何能连上控制台端口的程序都写得下它，因此它证明不了使用者本人确实看过。
             */
            private String acceptedBy = "";
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
            private String token = "";

            /**
             * 上一项换算出来的哈希，由程序写回，不必手填
             * <p>
             * 形态是 {@code SHA-256(token + ".napcat")} 的十六进制串——这不是我们选的，
             * 是 NapCat 的登录接口就收这个。
             */
            private String tokenHash = "";

            /**
             * NapCat WebUI 的二次验证密钥（Base32）
             * <p>
             * 它开了 2FA 才需要填。<b>只能明文保存</b>：代登录时要用它现算验证码，
             * 而算码需要密钥本身——这一点没有折中办法，因此配置文件的权限必须收紧到仅属主可读。
             */
            private String totpSecret = "";

            /**
             * NapCat WebUI 在本机的地址
             * <p>
             * 默认回环。<b>不该改成非回环地址</b>：代登录是拿着凭据去换凭据，
             * 这条请求一旦离开本机，凭据就上了网线。
             */
            private String address = "http://127.0.0.1:6099";
        }

        /**
         * 配置界面口令登录相关
         * <p>
         * 只有把面板开到公网时才需要配置这一节。默认不填口令，面板维持「仅本机 + 地址栏令牌」的形态。
         */
        @Getter
        @Setter
        public static class Auth {
            /**
             * 登录口令
             * <p>
             * 留空表示不启用口令登录。可以直接填明文，启动时会哈希后使用，
             * 同时在日志里输出可替换过去的哈希串——<b>填了明文就意味着看得到配置文件的人也就有了口令</b>。
             */
            private String password = "";

            /**
             * 是否要求二次验证
             * <p>
             * 默认要求。设了口令却没绑定验证器时，界面会持续提示绑定——<b>只有口令的面板
             * 一旦开到公网，其安全性就完全押在这一个口令上</b>，而口令是会被撞库、被键盘记录、
             * 被肩窥的。真的不想要二次验证时把这一项改成 false，那是一个需要写下来的决定。
             */
            private boolean totp = true;

            /**
             * 二次验证密钥（TOTP，Base32）
             * <p>
             * 留空表示尚未绑定验证器，登录时只校验口令。通过界面上的绑定引导扫码后，
             * 密钥会自动写回本配置项。
             * <p>
             * 密钥必须以明文保存，因此配置文件的权限要收紧到仅属主可读。
             */
            private String totpSecret = "";

            /**
             * 是否保留「忘记口令」的启动令牌通道
             * <p>
             * 启用口令登录之后，启动日志里仍会打印一个带令牌的地址，用它可以<b>绕过口令与
             * 二次验证</b>直接进入。这是忘记口令时唯一不必改配置重启就能进去的路，
             * 所以<b>默认保留</b>——默认关掉会把人锁在门外，那比留一道后门更糟。
             * <p>
             * ⚠️ <b>但确认口令与验证器都能用之后，就该把它关掉</b>，理由有三：
             * <ul>
             *   <li>它<b>绕过二次验证</b>——留着它，TOTP 的保护上限就是这个令牌的保密程度</li>
             *   <li>令牌走地址栏，<b>会进反向代理的访问日志</b>。2026-08-13 在生产的
             *       nginx 归档里实测到 82 行含 {@code token=}</li>
             *   <li>若日后把别的服务挂在这套会话之后（如经 {@code auth_request} 代理
             *       OneBot 实现的 WebUI），<b>这个后门会同时成为那些服务的后门</b></li>
             * </ul>
             * 关闭后：令牌不再被接受，启动日志也不再打印那一行——
             * <b>打印一个不管用的地址比不打印更让人困惑</b>。
             */
            private boolean operatorToken = true;

            /**
             * 登录会话的有效期，单位：小时
             * <p>
             * 从登录起算的绝对上限，到点必须重新登录。它约束的是「会话 Cookie 一旦泄漏还能被用多久」，
             * 因此不随使用而顺延。
             */
            private int sessionHours = 168;

            /**
             * 登录会话的闲置超时，单位：小时
             * <p>
             * 多久没有操作即自动退出。管的是在别人的设备上登录后忘记退出这类情形。
             */
            private int idleHours = 12;

            /**
             * 连续登录失败多少次后锁定该来源 IP
             */
            private int maxFailures = 5;

            /**
             * 首次锁定的时长，单位：分钟。反复触发时逐次翻倍
             */
            private int lockoutMinutes = 15;
        }
    }

    /**
     * 网络线程相关
     */
    @Getter
    @Setter
    public static class NetworkThread {
        /**
         * 线程池核心线程数
         */
        private int corePoolSize = 4;

        /**
         * 线程池最大线程数
         */
        private int maxPoolSize = 24;

        /**
         * 线程池任务队列容量
         */
        private int queueCapacity = 64;

        /**
         * 非核心线程存活时间，单位：秒
         */
        private int keepAliveSeconds = 60;
    }

    /**
     * 日志相关
     */
    @Getter
    @Setter
    public static class Log {
        /**
         * 控制台日志级别
         */
        private Level console;

        /**
         * 文件日志级别
         */
        private Level file;

        /**
         * 是否记录事件日志
         */
        private boolean eventLog = false;

        /**
         * 是否记录网络请求日志
         */
        private boolean networkLog = false;

        /**
         * 网络日志的同类去重抑制窗口，单位：秒，设为 0 关闭抑制
         * <p>
         * 打开 {@code network-log} 之后每个请求写一行，而本程序的请求绝大多数是轮询：
         * 三个房间十秒一轮，一小时上千行几乎一样的记录，**要查的那一条异常正好淹在里面**。
         * 排障日志的用处取决于它读不读得下去。
         * <p>
         * 同类按「请求方法 + 去掉查询串的地址」判定；被抑制的条数攒着，
         * 随下一条同类日志一起报出来。<b>失败一律放行，不参与抑制</b>——
         * 抑制的目的就是让异常显出来。
         */
        private int networkLogSuppressWindow = 60;
    }

    /**
     * 网络相关
     */
    @Getter
    @Setter
    public static class Network {
        /**
         * 网络请求连接超时时间，单位：秒
         */
        private int connectTimeout = 10;

        /**
         * 网络请求读取超时时间，单位：秒
         */
        private int readTimeout = 60;
    }

    /**
     * 数据源相关
     */
    @Getter
    @Setter
    public static class DataSource {
        /**
         * JSON 文件路径，仅使用 JSON 数据源时生效
         */
        private String jsonPath = "datasource.json";

        /**
         * JSON 文件发生变化时是否自动重载，仅使用 JSON 数据源时生效
         */
        private boolean jsonAutoReload = true;
    }

    /**
     * 数据源相关
     */
    @Getter
    @Setter
    public static class Plugin {
        /**
         * 是否自动下载插件依赖，可使用 --skip-download-dependency 命令行参数临时跳过自动下载
         */
        private boolean autoDownloadDependency = true;

        /**
         * 用于自动下载插件依赖的 Maven 地址
         */
        private List<String> mavenBaseUrls = new ArrayList<>(Arrays.asList("https://maven.aliyun.com/repository/public", "https://repo1.maven.org/maven2"));
    }

    /**
     * 直播相关
     */
    @Getter
    @Setter
    public static class Live {
        /**
         * 是否持久化直播数据至文件，仅使用默认直播数据服务时生效
         */
        private boolean saveLiveData = true;

        /**
         * 直播数据文件路径，仅使用默认直播数据服务时生效
         */
        private String liveDataPath = "data.json";

        /**
         * 自动保存直播数据间隔，单位：秒，仅使用默认直播数据服务时生效
         */
        private int autoSaveLiveDataInterval = 300;

        /**
         * 判定主播断线重连（下播后短时间内重新开播）的时间间隔，断线重连不会重置直播数据，单位：秒
         */
        private int reconnectInterval = 300;
    }

    /**
     * 绘图相关
     */
    @Getter
    @Setter
    public static class Paint {
        /**
         * 绘图器字体列表，支持配置为字体名称或字体文件路径
         */
        private List<String> fonts = new ArrayList<>();

        /**
         * 绘图器自动扩展高度时扩展像素数，设置过大会导致占用较大内存，设置过小会频繁自动扩展导致效率降低
         */
        private int autoExpandHeight = 5000;

        /**
         * 自定义绘图器底部额外版权信息
         */
        private List<TextWithStyle> extraCopyrights = new ArrayList<>();
    }

    /**
     * 邮件相关
     */
    @Getter
    @Setter
    public static class Mail {
        /**
         * 默认收件邮箱
         */
        private String defaultTo;
    }

    @PostConstruct
    public void init() {
        String os = System.getProperty("os.name").toLowerCase();
        paint.getFonts().add("内置");
        if (os.contains("win")) {
            paint.getFonts().addAll(Arrays.asList("微软雅黑", "宋体", "Segoe UI Emoji", "Segoe UI Symbol", "Arial", "SansSerif"));
        } else if (os.contains("mac")) {
            paint.getFonts().addAll(Arrays.asList("PingFang SC", "Apple Color Emoji", "SansSerif"));
        } else {
            // sudo apt install -y  fonts-noto-cjk  fonts-wqy-zenhei  fonts-noto-color-emoji fonts-freefont-ttf
            paint.getFonts().addAll(Arrays.asList("Noto Sans CJK SC", "WenQuanYi Zen Hei", "Noto Color Emoji", "DejaVu Sans", "FreeSans", "SansSerif"));
        }

        for (TextWithStyle extra : paint.getExtraCopyrights()) {
            if (extra.getFont() != null) {
                if (extra.getSize() != 0) {
                    extra.setFont(extra.getFont().deriveFont(extra.getStyle(), extra.getSize()));
                } else {
                    extra.setFont(extra.getFont().deriveFont(extra.getStyle()));
                }
            }
        }
    }
}
