package org.frostnova.nova.bilibili.config;

import org.frostnova.nova.core.config.ConfigDanger;
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
 * NovaBilibili 配置类
 */
@Getter
@Setter
@Configuration
@NovaComponent
@ConfigurationProperties(prefix = "novabot.bilibili")
public class NovaBilibiliProperties {
    private final BilibiliThread bilibiliThread = new BilibiliThread();

    private final Debug debug = new Debug();

    private final Network network = new Network();

    private final Account account = new Account();

    private final Live live = new Live();

    private final Dynamic dynamic = new Dynamic();

    private final Ranking ranking = new Ranking();

    /**
     * 线程相关
     */
    @Getter
    @Setter
    public static class BilibiliThread {
        /**
         * 线程池核心线程数。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("B站线程池 · 核心线程数")
        private int corePoolSize = 4;

        /**
         * 线程池最大线程数。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("B站线程池 · 最大线程数")
        private int maxPoolSize = 32;

        /**
         * 线程池任务队列容量。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("B站线程池 · 队列容量")
        private int queueCapacity = 256;

        /**
         * 非核心线程存活时间，单位：秒。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("B站线程池 · 空闲存活")
        private int keepAliveSeconds = 300;
    }

    /**
     * 调试相关
     */
    @Getter
    @Setter
    public static class Debug {
        /**
         * 是否记录直播间原始消息的调试日志。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("直播间原始消息调试日志")
        private boolean liveRoomRawMessageLog = false;

        /**
         * 是否记录动态接口原始响应的调试日志。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("动态接口原始响应调试日志")
        private boolean dynamicRawMessageLog = false;

        /**
         * 动态去重定键的取证探针，每条动态记一行三个标识符、不记正文；取证用，用完即关并清理日志。
         */
        // 每行记 id / rid / type，用来判定「同一条内容被推两次时两条记录的 rid 是否
        // 相同」。与上一项（整份原始响应，含关注列表里所有人的动态内容与昵称）刻意
        // 不同。默认关闭；取证结束即关闭并清理日志，探针行里的 id/rid 按关联信息处置。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("动态去重探针")
        private boolean dynamicDedupProbe = false;
    }

    /**
     * 网络相关
     */
    @Getter
    @Setter
    public static class Network {
        /**
         * 请求哔哩哔哩接口用的 User-Agent；浏览器版本写得太旧容易被判成非正常客户端，升级程序时应一并跟进。
         */
        // 改版本号时，配置模板 dist/templates/application.example.yml 里那份要一起改。
        // 模板里的值会覆盖这里的默认值——2026-08-07 就发现模板停在 Chrome/119 而这里
        // 已是 150，生产按模板部署，实际发出去的一直是落后三年的那个。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("B站接口 · User-Agent")
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

        /**
         * 接口请求失败后的最大重试次数。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("B站接口 · 最多重试次数")
        private int apiRetryMaxTimes = 3;

        /**
         * 接口请求失败后的重试间隔，单位：毫秒。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("B站接口 · 重试间隔")
        private int apiRetryInterval = 3000;
    }

    /**
     * 账号与凭据相关
     */
    @Getter
    @Setter
    public static class Account {
        /**
         * 匿名模式：完全不使用登录凭据，不弹二维码、不做复检续期。直播采集照常，动态推送与自动关注不可用。匿名拿到的数据不完整：部分房间弹幕大量缺失，发送者编号被抹掉、昵称只留首字。
         */
        // 实测结论而不是推测：同一房间同一时段匿名连接的弹幕到达率低至 4.3%；拿得到的
        // 弹幕里 uid 一律抹成 0，且按消息类型来——同一条连接上点赞消息仍带完整 uid。
        // 它不是「功能一致的免登录版」，只适合本机试跑、给下游供粗粒度事件流这类
        // 「有多少算多少」的场景。要完整数据就得登录。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigDanger(value = "true", title = "开启匿名模式？",
                consequence = "个人主播的直播间只能拿到约一成弹幕，发送者会被抹成匿名，报告会明显缩水；"
                        + "动态推送与自动关注不可用。")
        @ConfigLabel("匿名模式")
        private boolean anonymous = false;

        /**
         * 登录凭据的存储文件路径。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("登录凭据 · 存储文件")
        private String cookiePath = "cookies.json";

        /**
         * 是否加密存储登录凭据。凭据等同于账号的完整控制权，启用后以 AES-GCM 加密、密钥另放一个文件，两者都仅属主可读写。
         */
        // 明文落盘意味着任何能读到该文件的进程或备份都能直接接管账号。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("登录凭据 · 加密存储")
        private boolean encrypt = true;

        /**
         * 加密密钥的存储文件路径，只在启用加密存储时生效；与密文分开存放，便于放到管得更严的地方。
         */
        // 也可替换为由外部密钥管理服务注入。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("登录凭据 · 密钥文件")
        private String keyPath = "cookies.key";

        /**
         * 登录态复检间隔，单位：秒，0 或负数关闭复检。凭据可能悄悄过期，定期复检把它变成显式告警；默认十分钟一次。
         */
        // 凭据失效后动态推送会静默停摆。复检本身只是一次轻量接口调用，开销可忽略。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("登录凭据 · 复检间隔")
        private int verifyInterval = 600;

        /**
         * 凭据维护失败后的退避上限，单位：秒；连续失败逐级拉长间隔、成功即复位，设 0 等于不退避。
         */
        // 复检与续期各自独立退避、互不影响——出网劣化时续期查不动，不该把复检也一起
        // 拖慢，那是我们判断「凭据到底还在不在」的唯一途径。上限的意义是别把间隔拉到
        // 几天：退避是为了不在故障期间空转，不是为了放弃。设为小于复检间隔同样关闭退避。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("登录凭据 · 退避上限")
        private int maintenanceBackoffCap = 3600;

        /**
         * 是否自动续期登录凭据。B 站会逐步作废旧凭据，关掉后某天凭据失效、动态推送停摆，只能重新扫码；复检关闭时续期也不执行。
         */
        // 哔哩哔哩自 2023 年起随敏感接口的调用逐步作废 Web 端凭据，官方页面为此提供
        // 了续期链路。续期仅在服务端明确提示需要时才执行，检查随复检一并进行，受
        // verify-interval 控制。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("登录凭据 · 自动续期")
        private boolean autoRefreshCookie = true;

        /**
         * 扫码登录方式：tv（默认）拿到的凭据能长期自动续期；web 拿不到可续期令牌，到期只能重新扫码。没有异常别改成 web。
         */
        // tv 走 TV 端登录接口，连同 Cookie 一并返回可续期的令牌（有效期 180 天）；
        // web 走网页端接口，实测服务端返回的刷新口令恒为空串，自动续期不可用。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("扫码登录方式")
        private String qrCodeLoginMode = "tv";
    }

    /**
     * 直播相关
     */
    @Getter
    @Setter
    public static class Live {
        /**
         * 是否连直播间的长连接；被风控连不上时可关掉，只用备用直播推送。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("连直播间长连接")
        private boolean enableConnectLiveRoom = true;

        /**
         * 只连配了直播推送的房间。开启后「纯监听房间」（配了主播、不配推送的那种）会被整个跳过，表现是加了主播却什么都没发生，跳过时会在日志里列出来。
         */
        // 默认关闭，即每个启用的主播都连。只在连接数受限、又确实只关心推送的场景下才开。
        // 纯监听房间只把事件送进事件输出或累计数据，一条消息都不发。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("只连有推送的直播间")
        private boolean onlyConnectNecessaryRooms = false;

        /**
         * 连下一个直播间的间隔，单位：毫秒；连太快可能触发风控。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("直播间连接间隔")
        private int liveRoomConnectInterval = 1000;

        /**
         * 重连直播间的间隔，单位：毫秒。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("直播间重连间隔")
        private int liveRoomReconnectInterval = 1000;

        /**
         * 数据包解压后允许的最大字节数，非正数回退到默认 32MB；正常包只有几十 KB，这是防异常大包的防御上限，小内存机器可调小。
         */
        // 做成可配的理由是内存——默认堆 -Xmx512m，而这个上限允许单次解压吃掉
        // 32 MB 连续字节数组，在小内存机器上防御上限自己就可能是那根稻草。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("数据包解压 · 字节上限")
        private int maxDecompressedBytes = 32 * 1024 * 1024;

        /**
         * 递归展开压缩包的最大层数，非正数回退到默认 3；正常数据不超过一层。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("数据包解压 · 最大层数")
        private int maxDecodeNestingDepth = 3;

        /**
         * 断线摘要的汇总窗口，单位：秒，0 关闭；逐次断线行只进调试日志，按此窗口汇总成一条带归因的摘要。
         */
        // 逐次一行「连接已断开」在断线风暴里恰好最没用：十个房间各断五次就是五十行，
        // 数不清次数，也看不出是集中在一个房间还是所有房间一起断——而这两者的处理
        // 方式相反。没有断线的窗口不打日志。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("断线摘要汇总窗口")
        private int disconnectDigestInterval = 600;

        /**
         * 礼物配置缓存的过期时间，单位：秒。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("礼物表缓存时长")
        private int giftCacheExpire = 3600;

        /**
         * 是否自动补全事件信息；开启后缺少昵称、头像的事件会额外请求接口补全。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("自动补全事件信息")
        private boolean completeEvent = false;

        /**
         * 是否启用直播间数据风控检测。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("直播间风控检测")
        private boolean autoDetectLiveRoomRisk = true;

        /**
         * 风控检测的周期，单位：秒。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("直播间风控检测 · 周期")
        private int autoDetectLiveRoomRiskInterval = 60;

        /**
         * 连续多少个检测窗口收不到直播间的业务消息判为疑似断流；与检测周期相乘是判定时长（默认 3 分钟），调小会把偶发的安静误判成断流。
         */
        // 本项取代了原先的 auto-detect-live-room-risk-ratio（进房消息占比阈值）。
        // 那个判据 2026-08-07 被实测证伪：热门房间人来人往、进房消息天然刷屏，
        // 一个 41 万人气的房间进房占比 53% 被判风控，而它同时段每分钟收 71 条弹幕、
        // 对独立基准的到达率 93.3%。「进房占比高」与「收不到业务消息」是两回事。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("直播间风控检测 · 判定窗口数")
        private int autoDetectLiveRoomRiskWindows = 3;

        /**
         * 是否启用备用直播推送：不靠长连接，按轮询接口判断开播状态。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("备用直播推送")
        private boolean backupLivePush = true;

        /**
         * 备用直播推送的检测间隔，单位：秒。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("备用推送检测间隔")
        private int backupLivePushInterval = 10;

        /**
         * 主播基础数据（粉丝数等）的留档间隔，单位：小时，0 关闭；6 小时一次足够画趋势，再密只会多打接口。
         */
        // 粉丝数在不播的日子里照样在变，而本场数据只覆盖直播期间——没有这份留档，
        // 「本周涨了多少粉」只能答成「每场开播时分别是多少」。一年不到 2 MB。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("主播基础数据留档间隔")
        private int snapshotInterval = 6;

        /**
         * 下播报告底部标识图片的路径，留空不画；要和动态图打同一个标就填同一个路径，图片按固定高度缩放。
         */
        // 与动态图片的标识分开配置，因为两者未必想打同一个标（dynamic.logo-path）。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("报告自定义标识图片")
        private String reportLogoPath = "";

        /**
         * 词云不计这些用户的弹幕，每行一个用户编号；欢迎、感谢机器人刷屏的词就不进词云，弹幕原文照常保存。保存后立刻生效。
         */
        // 已经结束的场次重新画报告也会按这份名单来。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        @ConfigLabel("词云不计这些用户")
        private List<String> wordCloudExcludeUids = new ArrayList<>();
    }

    /**
     * 动态相关
     */
    @Getter
    @Setter
    public static class Dynamic {
        /**
         * 是否用登录账号自动关注被监听的主播（不是回关粉丝）；B 站动态流只返回已关注账号，不关注就收不到动态。会改登录账号的关注列表，建议用小号。
         */
        // 哔哩哔哩的动态流接口只返回已关注账号的动态，因此动态推送依赖本开关。
        // 关闭后需自行手动关注，否则对应 UP 主的动态不会被推送。修改登录账号的
        // 关注列表，也是建议使用专用小号而非个人主号的原因之一。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("自动关注被监听的主播")
        private boolean autoFollow = true;

        /**
         * 自动关注的执行间隔，单位：秒。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("自动关注间隔")
        private int autoFollowInterval = 30;

        /**
         * 动态接口的请求间隔，单位：秒。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("动态轮询间隔")
        private int apiRequestInterval = 10;

        /**
         * 动态图片底部标识图片的路径，留空不画；程序不内置标识图片，要打自己的标就在这里填，图片按固定高度缩放。
         */
        // 不再内置标识图片：图形资产是独立于代码许可证的著作权客体，字标还额外涉及
        // 商标属性，沿用上游标识并随每张推送图片对外分发并不妥当。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("动态自定义标识图片")
        private String logoPath = "";

        /**
         * 是否自动保存画出来的动态图片。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("动态图片自动存盘")
        private boolean autoSaveImage = false;

        /**
         * 动态发布超过多少分钟就不推了，防止首次启动补推大量历史动态。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("动态补推时限")
        private int pushMinutes = 1440;

        /**
         * 是否推送开播动态（B 站在主播开播后自动生成的那条）。默认关；与开播推送同时开着时，两边都配了的群会收到两遍。只配了动态推送、没配开播推送的主播，关掉本开关就收不到他的开播消息。
         */
        // 开播推送在每个维度上都更好：早得多（实测开播动态比开播晚整 600 秒、出现在
        // 动态流里又晚 21 分钟，一共迟到半小时）、带标题与封面、能 at 全体成员，
        // 开播动态只是它的劣化重复。只配动态推送、没配开播推送时，关掉本开关就
        // 再也收不到他的开播消息了。
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigLabel("推送开播动态")
        private boolean pushLiveDynamic = false;
    }

    /**
     * 排行榜出图相关
     */
    @Getter
    @Setter
    public static class Ranking {
        /**
         * 「最多列出名次」的显示名，设置页与出错提示共用这一份
         */
        public static final String TOP_N_LABEL = "排行榜 · 最多列出名次";

        /**
         * 「整图高度上限」的显示名，设置页与出错提示共用这一份
         */
        public static final String HEIGHT_LIMIT_LABEL = "排行榜 · 整图高度上限";

        /**
         * 排行榜一张图最多列多少名；榜上人多时图末写「其余 N 名未列出」，高度不够时还会再少列。保存后立刻生效。
         */
        // 不填也能用（默认 50）。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        @ConfigLabel(TOP_N_LABEL)
        private int topN = 50;

        /**
         * 排行榜整图的高度上限，单位：像素；装不下的名次不画、计进末尾的「其余 N 名未列出」。手机上嫌长或截得多时调这里。
         */
        // 头部、每一行、脚注与署名全算在内。不填也能用（默认 10000 像素）。
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
        @ConfigLabel(HEIGHT_LIMIT_LABEL)
        private int heightLimit = 10000;
    }
}
