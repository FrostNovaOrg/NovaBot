package com.starlwr.bot.core.config;

import com.starlwr.bot.core.config.ui.TimestampedFileBackup;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.model.TextWithStyle;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StarBotCore 配置类
 * <p>
 * <b>本类是配置的绑定根，不是一份按层划好的配置</b>：{@code starbot.core.*} 下的每一节
 * 都挂在这里，从连接超时到控制台口令、从绘图字体到告警通道。<b>各节自身的层次并不相同</b>——
 * 网络、线程池、日志、直播、数据源这五节是事件源自己要用的，其余各节服务的是推送、控制台、绘图这些外围功能。
 * <p>
 * 因此按层要用到的那几节<b>已经单独成件</b>（{@link NetworkProperties}、
 * {@link NetworkThreadProperties}、{@link LogProperties}、{@link LiveProperties}、
 * {@link DatasourceProperties}），
 * 各自是不带任何框架注解的纯数据类，用它的地方直接依赖那一件即可，<b>不必再依赖整份配置</b>。
 * 配置键一字未动：它们仍是本类的字段，绑定与装配也仍在本类这一侧完成。
 * <p>
 * 其余各节仍以内部类的形式留在本类内——它们的使用方与本类同属一侧，单独成件换不到任何解耦。
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "starbot.core")
public class StarBotCoreProperties {
    /**
     * 单独成件的那几节
     * <p>
     * 这五节的类已随事件源迁入 {@code novacore} 模块，配置键与绑定一字未动：
     * 值仍然绑到本类的这几个字段上。
     * <p>
     * <b>这里不标 {@code @NestedConfigurationProperty}，是有意的。</b>
     * 标了本模块也生成不出完整的元数据——默认值取自字段初始值、说明取自 Javadoc，
     * 两者都只存在于源码里，而本模块编译时看到的是那五个类的 class 文件，
     * 于是生成出来的条目<b>默认值与说明整列是空的</b>。这几节的元数据改由它们所在的模块
     * 自己生成（{@code novacore} 的 {@code CoreConfigurationSections}）；
     * 两侧都标就会出现两份同名条目，合并时谁胜出取决于类路径顺序。
     * <p>
     * 绑定不依赖这个注解：它只是给元数据处理器的提示，与运行期绑定无关。
     */
    @Getter
    private final NetworkThreadProperties networkThread = new NetworkThreadProperties();

    @Getter
    private final LogProperties log = new LogProperties();

    @Getter
    private final NetworkProperties network = new NetworkProperties();

    @Getter
    private final DatasourceProperties datasource = new DatasourceProperties();

    @Getter
    private final Plugin plugin = new Plugin();

    @Getter
    private final LiveProperties live = new LiveProperties();

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
     * 事件时间线相关
     */
    @Getter
    @Setter
    private final Timeline timeline = new Timeline();

    /**
     * 事件时间线
     * <p>
     * 「刚才那条推了吗」「昨晚为什么没推」这类问题的答案，按日记在
     * {@code timeline/YYYY-MM-DD.jsonl} 里。
     */
    @Getter
    @Setter
    public static class Timeline {
        /**
         * 事件时间线的保留天数，含当天；设为 0 或负数表示不自动清理
         * <p>
         * 时间线是排障线索而不是业务数据：两周之前「某条推送失败过」这件事，
         * 已经没有人会再去查，留着只是让日志页越翻越长、磁盘越占越多。
         * 要长期保存的场次数据在 {@code sessions.jsonl} 里，那一份不会被删。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int retentionDays = 14;
    }

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
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigDanger(value = "true", title = "开启外部程序触发？",
                consequence = "这是能执行任意程序的口子：规则里写的程序会以本程序的身份运行，"
                        + "拿得到它拿得到的一切。")
        private boolean enabled = false;

        /**
         * 单条命令的最长执行时间，单位：秒，超时后强制结束
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int timeout = 30;

        /**
         * 同时执行的命令数上限，超出的直接丢弃
         * <p>
         * 弹幕这类事件一秒能来几十条。没有上限的话，一次刷屏就等于一次 fork 炸弹。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int maxConcurrent = 4;

        /**
         * 规则列表
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
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
         * 超级管理员账号，跨会话生效
         * <p>
         * 「禁用命令」这类操作会改变全群的可用功能，只对管理员开放。
         * <b>群主与群管理员自动拥有权限</b>，此处填的是不依赖群角色的额外名单——
         * 机器人的主人未必是每个群的管理员。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
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
         * 关闭后所有推送都会被丢弃，用于调试或临时静音，无需逐条改推送配置。改完立即生效，不必重启。
         */
        // 之所以能即时生效：PushGate 每次判断都重新读它。「临时静音」这个诉求本身就要求立刻管用——
        // 为了让它生效而重启一次，会把正在采集的场次打断
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        private boolean enabled = true;

        /**
         * 机器人第一次推送到一个群或好友后，附一句怎么用它的提示（只发一次）
         * <p>
         * 关掉之后不再附这句，也不把这次算作已经提示过——下次打开时的第一条还会带上。改完立即生效，不必重启。
         */
        // 之所以能即时生效：每次跟提示前都现读。关掉必须立刻停，否则关了还会再发一句
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        private boolean firstPushTip = true;

        /**
         * 机器人账号每日最多 @全体成员 的次数，0 或负数表示不限制
         * <p>
         * 平台本身有每日上限，用超之后**平台会静默忽略**——消息照发但 @ 不生效，
         * 配置的人往往过很久才发现「怎么没人被 @ 到」。因此在自己这一侧先记账，
         * 超额时主动退化为普通消息并记日志，而不是把额度花在注定无效的调用上。
         * <p>
         * <b>这份额度由该账号推送的全部会话共享</b>（实测：往一个群发一次，
         * 其他群看到的账号剩余次数同步减一）。默认 10 与平台实测值一致，
         * <b>它才是真正会先卡住的那一道</b>。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int atAllDailyLimit = 10;

        /**
         * 单个会话每日最多 @全体成员 的次数，0 或负数表示不限制
         * <p>
         * 与账号额度是两个维度：群的额度由群里所有有权限的人共用，机器人只是其中之一。
         * 默认 20 与平台实测值一致。通常先撞到的是账号额度，本项是第二道保险。
         */
        @ConfigLevel(ConfigLevel.Level.ADVANCED)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int atAllSessionDailyLimit = 20;

        /**
         * 静音时段开始时间，格式 HH:mm，与结束时间任一为空即视为不启用
         * <p>
         * 半夜被机器人吵醒是这类通知产品被投诉最多的点，因此内置该能力而不是让使用者自行想办法。
         * 改完立即生效，不必重启。
         */
        // 之所以能即时生效：PushGate 每条推送都现读一次起止时刻
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        private String quietStart = "";

        /**
         * 静音时段结束时间，格式 HH:mm
         * <p>
         * 允许跨零点：开始 23:00、结束 08:00 表示当晚 23 点至次日 8 点。改完立即生效，不必重启。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
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
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean enabled = true;

        /**
         * 同一问题的最短告警间隔，单位：秒
         * <p>
         * 故障往往持续存在，不做收敛就会反复推送同一条消息，最终使人对告警彻底脱敏。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int convergenceInterval = 3600;

        /**
         * Webhook 告警地址，留空则不启用
         * <p>
         * <b>机器人推送与邮件之外唯一不依赖机器人自身链路的通道。</b>机器人告警走的是机器人的推送链路，
         * 一旦机器人程序掉线或掉登录，需要告警的正是这种时候，而告警本身也一并失效了。
         * Webhook 只需一个地址，适配 Bark、Server 酱、钉钉、飞书、Telegram 等常见服务。
         * 改完立即生效，不必重启。
         */
        // 请求方式与字段名那几项不在此列：它们是「怎么发」，改动通常伴随一次对接调试，
        // 等一次重启是可以接受的
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        private String webhookUrl = "";

        /**
         * Webhook 请求方式：POST 或 GET
         * <p>
         * POST 提交 JSON（字段名见 webhook-title-field 与 webhook-content-field）；
         * GET 把标题与内容拼进查询串，适配 Bark 这类以路径或查询参数接收的服务。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String webhookMethod = "POST";

        /**
         * Webhook JSON 中承载标题的字段名
         * <p>
         * 各服务字段名不统一：Server 酱用 title/desp，钉钉与飞书用嵌套结构，
         * 自建接口则各有各的约定，因此做成可配置而非写死。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String webhookTitleField = "title";

        /**
         * Webhook JSON 中承载内容的字段名
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String webhookContentField = "content";

        /**
         * Webhook 附加请求头，用于需要鉴权的服务，如 {@code Authorization: Bearer xxx}
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private final java.util.Map<String, String> webhookHeaders = new java.util.LinkedHashMap<>();

        /**
         * 发送失败的告警的重投间隔，单位：秒，设为 0 关闭重投
         * <p>
         * <b>需要告警的时候往往正是发不出去的时候</b>：出网劣化、机器人掉登录、Webhook 服务抖动，
         * 三者都会让告警本身失败，而失败之后此前没有下文——「没收到告警」于是被读成「没出事」。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int retryInterval = 60;

        /**
         * 待重投队列的容量上限
         * <p>
         * 满了之后丢最旧的，并在日志里说明丢了哪一条。<b>不静默截断</b>：
         * 悄悄丢掉的告警比没有重投更糟，它会让人以为队列在正常工作。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int retryQueueSize = 50;

        /**
         * 单条告警的最大重投次数，超过后放弃并写日志
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int retryMaxAttempts = 10;
    }

    /**
     * 非插件实现的推送平台配置
     */
    @Getter
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private final List<Sender> sender = new ArrayList<>();

    /**
     * 配置界面相关
     */
    @Slf4j
    @Getter
    @Setter
    public static class ConfigUi {
        /**
         * 是否启用配置界面
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean enabled = true;

        /**
         * 配置界面访问令牌
         * <p>
         * 留空时每次启动自动生成一个随机令牌并输出到日志。配合默认仅监听回环地址的设置，
         * 单机部署无需任何配置即可安全使用；需要从其他机器访问时在此显式设置一个随机串。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String token = "";

        /**
         * 允许访问配置界面的来源 IP 白名单，支持精确 IP 与 CIDR 网段
         * <p>
         * 配置界面可修改推送目标并读取运行状态，权限高于推送接口，默认仅放行本机回环地址。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private List<String> allowIps = new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));

        /**
         * 配置文件备份保留份数
         * <p>
         * 每次保存 application.yml 或主播推送配置时都会另留一份带时间的备份。
         * 超出这个数目的旧备份会被删掉。默认 10 份，可在 1 到 100 之间改。改完立即生效，不必重启。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        private int backupKeep = 10;

        /**
         * 越界的 backup-keep 只警告一次：配置绑定后这个值不会自己变，刷屏没有新信息。
         */
        private static final AtomicBoolean BACKUP_KEEP_WARNED = new AtomicBoolean();

        /**
         * 实际生效的备份保留份数
         * <p>
         * 配置文件里可以写成任意整数，裁剪只认 1 到 100。读的时候给出生效值，
         * 避免「页面写着 500、落盘只留 100」这种口是心非。
         */
        public int getBackupKeep() {
            int effective = TimestampedFileBackup.clamp(backupKeep);
            if (effective != backupKeep && BACKUP_KEEP_WARNED.compareAndSet(false, true)) {
                log.warn("backup-keep 写的是 {}, 超出 1–100，按 {} 生效", backupKeep, effective);
            }
            return effective;
        }

        /**
         * 口令登录相关
         */
        @Getter
        private final Auth auth = new Auth();

        /**
         * 使用协议的同意记录
         */
        @Getter
        private final Agreement agreement = new Agreement();

        /**
         * 新版检查相关
         */
        @Getter
        private final Update update = new Update();

        /**
         * 新版检查
         * <p>
         * 控制台侧栏据此挂「新版 X」药丸，首页据此挂一条软待办。检查只读不写：
         * 控制台不做在线更新，换了 jar 重启就是更新——因此这里没有任何一处会改这台机器上的程序。
         */
        @Getter
        @Setter
        public static class Update {
            /**
             * 是否检查新版
             * <p>
             * 关掉之后侧栏药丸与首页那条软待办都不会再出现。程序不会自己更新，
             * 这一检查是使用者得知「该去换 jar 了」的唯一入口，默认开着。
             */
            @ConfigLevel(ConfigLevel.Level.COMMON)
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private boolean enabled = true;

            /**
             * 新版信息的来源地址
             * <p>
             * 默认指向发布仓的 latest release 接口，取回的 JSON 里要有
             * {@code tag_name}（版本）、{@code body}（更新说明）与 {@code html_url}（链接）。
             * 自建镜像或换发布渠道时改这里。取不到或取回的东西认不出来时静默跳过——
             * 「查不到新版」不该变成控制台上的一条故障。
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private String source = "https://api.github.com/repos/FrostNovaOrg/NovaBot/releases/latest";
        }

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
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private int acceptedVersion = 0;

            /**
             * 同意的时间
             * <p>
             * ISO 格式，留空表示尚未同意过。它不参与任何判断，只是留个凭据：
             * 日后要回答「这台机器上是什么时候同意的」时，答案得在盘上，而不是靠人回忆。
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
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
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private String acceptedBy = "";
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
             * 改完立即生效，不必重启：这台机器的第一把口令一定是在运行期设下的，
             * 而「设了口令但要等重启才认」的那段时间里，界面说已上锁而门还开着。
             */
            @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
            private String password = "";

            /**
             * 是否要求二次验证
             * <p>
             * 默认要求。设了口令却没绑定验证器时，界面会持续提示绑定——<b>只有口令的面板
             * 一旦开到公网，其安全性就完全押在这一个口令上</b>，而口令是会被撞库、被键盘记录、
             * 被肩窥的。真的不想要二次验证时把这一项改成 false，那是一个需要写下来的决定。
             * 改完立即生效，不必重启：界面上那个开关本来就是当场生效的，
             * 这一行此前标着「需重启」，于是同一件事在界面上有两种说法。
             */
            @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
            private boolean totp = true;

            /**
             * 二次验证密钥（TOTP，Base32）
             * <p>
             * 留空表示尚未绑定验证器，登录时只校验口令。通过界面上的绑定引导扫码后，
             * 密钥会自动写回本配置项。
             * <p>
             * 密钥必须以明文保存，因此配置文件的权限要收紧到仅属主可读。
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private String totpSecret = "";

            /**
             * 是否保留「忘记口令」的启动令牌通道
             * <p>
             * 打开之后，启用口令登录的实例在启动日志里仍会打印一个带令牌的地址，
             * 用它可以<b>绕过口令与二次验证</b>直接进入。
             * <p>
             * ⚠️ <b>默认关闭</b>，理由有三：
             * <ul>
             *   <li>它<b>绕过二次验证</b>——开着它，TOTP 的保护上限就是这个令牌的保密程度</li>
             *   <li>令牌走地址栏，<b>会进反向代理的访问日志</b>。2026-08-13 在生产的
             *       nginx 归档里实测到 82 行含 {@code token=}</li>
             *   <li>若日后把别的服务挂在这套会话之后（如经 {@code auth_request} 代理
             *       机器人程序的 WebUI），<b>这个后门会同时成为那些服务的后门</b></li>
             * </ul>
             * 反过来的默认值曾经也有它的道理——忘记口令时这是唯一不必改配置重启就能进去的路，
             * 默认关掉像是把人锁在门外。<b>但那道门本来就开得着</b>：把这一项改成
             * {@code true} 重启即可，启动日志随即打印那个地址，进去改完口令再改回来。
             * 权衡因此是<b>「忘记口令的那一次多重启一遍」对「每一台设了口令的实例长年带着一个
             * 等同于口令的后门」</b>，而后者是常态、且开着这件事没有任何现象。
             * <p>
             * 关闭时启动日志不打印那个地址——<b>打印一个不管用的地址比不打印更让人困惑</b>，
             * 但会打印一行说明，写清怎么把它临时打开。
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            @ConfigDanger(value = "true", title = "开启「忘记口令」启动令牌通道？",
                    consequence = "开着等于留一道能绕过口令与二次验证的后门。它是给「忘了口令进不来」"
                            + "那一次用的，确认新口令可用之后就该关掉。")
            private boolean operatorToken = false;

            /**
             * 登录会话的有效期，单位：小时
             * <p>
             * 从登录起算的绝对上限，到点必须重新登录。它约束的是「会话 Cookie 一旦泄漏还能被用多久」，
             * 因此不随使用而顺延。
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private int sessionHours = 168;

            /**
             * 登录会话的闲置超时，单位：小时
             * <p>
             * 多久没有操作即自动退出。管的是在别人的设备上登录后忘记退出这类情形。
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private int idleHours = 12;

            /**
             * 连续登录失败多少次后锁定该来源 IP
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private int maxFailures = 5;

            /**
             * 首次锁定的时长，单位：分钟。反复触发时逐次翻倍
             */
            @ConfigEffect(ConfigEffect.Effect.RESTART)
            private int lockoutMinutes = 15;
        }
    }

    /**
     * 插件相关
     */
    @Getter
    @Setter
    public static class Plugin {
        /**
         * 是否自动下载插件依赖，可使用 --skip-download-dependency 命令行参数临时跳过自动下载
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean autoDownloadDependency = true;

        /**
         * 用于自动下载插件依赖的 Maven 地址
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private List<String> mavenBaseUrls = new ArrayList<>(Arrays.asList("https://maven.aliyun.com/repository/public", "https://repo1.maven.org/maven2"));
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
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private List<String> fonts = new ArrayList<>();

        /**
         * 绘图器自动扩展高度时扩展像素数，设置过大会导致占用较大内存，设置过小会频繁自动扩展导致效率降低
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int autoExpandHeight = 5000;

        /**
         * 自定义绘图器底部额外版权信息
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private List<TextWithStyle> extraCopyrights = new ArrayList<>();
    }

    /**
     * 邮件相关
     */
    @Getter
    @Setter
    public static class Mail {
        /**
         * 默认收件邮箱，改完立即生效，不必重启
         */
        // 发件服务那几项（spring.mail.*）不是：它们撑着一个启动时装配好的 bean，
        // 改了配置对象也换不掉它
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        private String defaultTo;
    }

    /**
     * 把单独成件的那几节各自登记为一个 bean
     * <p>
     * 用它们的地方于是可以只声明自己真正需要的那一节，而不是整份配置。
     * 装配写在这里而不是在那几个类上加注解：<b>它们不带任何框架注解，这一点是有意的</b>——
     * 一旦挂上 {@code @ConfigurationProperties}，同一批配置键就有了两个绑定入口，
     * 编译期生成的配置元数据也会多出一份同名条目。
     * @return 网络配置
     */
    @Bean
    public NetworkProperties coreNetworkProperties() {
        return network;
    }

    /**
     * 网络线程池配置
     * @return 网络线程池配置
     */
    @Bean
    public NetworkThreadProperties coreNetworkThreadProperties() {
        return networkThread;
    }

    /**
     * 日志配置
     * @return 日志配置
     */
    @Bean
    public LogProperties coreLogProperties() {
        return log;
    }

    /**
     * 直播配置
     * @return 直播配置
     */
    @Bean
    public LiveProperties coreLiveProperties() {
        return live;
    }

    /**
     * 数据源配置
     * @return 数据源配置
     */
    @Bean
    public DatasourceProperties coreDatasourceProperties() {
        return datasource;
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
