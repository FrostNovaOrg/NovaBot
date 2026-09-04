package com.starlwr.bot.bilibili.config;

import com.starlwr.bot.core.config.ConfigDanger;
import com.starlwr.bot.core.config.ConfigEffect;
import com.starlwr.bot.core.config.ConfigLevel;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * StarBotBilibili 配置类
 */
@Getter
@Setter
@Configuration
@StarBotComponent
@ConfigurationProperties(prefix = "starbot.bilibili")
public class StarBotBilibiliProperties {
    private final BilibiliThread bilibiliThread = new BilibiliThread();

    private final Debug debug = new Debug();

    private final Network network = new Network();

    private final Account account = new Account();

    private final Live live = new Live();

    private final Dynamic dynamic = new Dynamic();

    /**
     * 线程相关
     */
    @Getter
    @Setter
    public static class BilibiliThread {
        /**
         * 线程池核心线程数
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int corePoolSize = 4;

        /**
         * 线程池最大线程数
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int maxPoolSize = 32;

        /**
         * 线程池任务队列容量
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int queueCapacity = 256;

        /**
         * 非核心线程存活时间，单位：秒
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int keepAliveSeconds = 300;
    }

    /**
     * 调试相关
     */
    @Getter
    @Setter
    public static class Debug {
        /**
         * 是否启用直播间原始消息调试日志
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean liveRoomRawMessageLog = false;

        /**
         * 是否启用动态接口原始响应调试日志
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean dynamicRawMessageLog = false;

        /**
         * 是否开启动态去重定键的取证探针
         * <p>
         * 每条动态记一行 {@code id / rid / type}，用来判定「同一条内容被推两次时
         * 两条记录的 rid 是否相同」。<b>只记这三个标识符，不记正文</b>——
         * 与上一项（整份原始响应，含关注列表里所有人的动态内容与昵称）刻意不同。
         * <p>
         * 取证用，默认关闭；取证结束即关闭并清理日志，探针行里的 id/rid 按关联信息处置。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean dynamicDedupProbe = false;
    }

    /**
     * 网络相关
     */
    @Getter
    @Setter
    public static class Network {
        /**
         * 请求哔哩哔哩接口时使用的 User-Agent
         * <p>
         * 声称的浏览器版本长期停在很旧的版本上容易被判定为非正常客户端，升级时应一并跟进。
         * <p>
         * <b>改版本号时，配置模板 {@code dist/templates/application.example.yml} 里那份要一起改。</b>
         * 模板里的值会覆盖这里的默认值——2026-08-07 就发现模板停在 Chrome/119 而这里已是 150，
         * 生产按模板部署，实际发出去的一直是落后三年的那个。两处不同步等于这个默认值形同虚设。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

        /**
         * 接口请求失败后的最大重试次数
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int apiRetryMaxTimes = 3;

        /**
         * 接口请求失败后的重试间隔，单位：毫秒
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int apiRetryInterval = 3000;
    }

    /**
     * 账号与凭据相关
     */
    @Getter
    @Setter
    public static class Account {
        /**
         * 是否以匿名模式运行
         * <p>
         * 开启后<b>完全不使用登录凭据</b>：不读已保存的凭据、不弹二维码、不做登录态复检与续期。
         * 直播采集照常进行，动态推送与自动关注则不可用——它们必须有登录态。
         * <p>
         * <b>匿名连接拿到的数据是不完整的，这是实测结论而不是推测</b>：
         * <ul>
         *   <li>部分房间的弹幕会被服务端限制下发，同一房间同一时段，匿名连接的到达率实测低至 4.3%</li>
         *   <li>拿得到的弹幕里，发送者 uid 一律被抹成 0、昵称只留首字（形如 {@code b***}）。
         *       这是<b>按消息类型来的</b>：同一条连接上点赞消息仍带完整 uid 与昵称</li>
         * </ul>
         * 因此它<b>不是「功能一致的免登录版」</b>，只适合「有多少算多少」的场景：
         * 本机试跑、给下游供一路粗粒度事件流、不想为采集绑一个账号。要完整数据就得登录。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        @ConfigDanger(value = "true", title = "开启匿名模式？",
                consequence = "个人主播的直播间只能拿到约一成弹幕，发送者会被抹成匿名，报告会明显缩水；"
                        + "动态推送与自动关注不可用。")
        private boolean anonymous = false;

        /**
         * 登录凭据存储文件路径
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String cookiePath = "cookies.json";

        /**
         * 是否加密存储登录凭据
         * <p>
         * 凭据中的 SESSDATA 与 bili_jct 等同于账号的完整控制权，明文落盘意味着任何能读到该文件的
         * 进程或备份都能直接接管账号。启用后凭据将以 AES-GCM 加密保存，密钥存放于独立的密钥文件中，
         * 两者均以仅属主可读写的权限创建。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean encrypt = true;

        /**
         * 加密密钥存储文件路径，仅在启用加密存储时生效
         * <p>
         * 密钥与密文分离存放，便于将密钥置于权限更严格的位置，或替换为由外部密钥管理服务注入。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String keyPath = "cookies.key";

        /**
         * 登录态复检间隔，单位：秒，设为 0 或负数可关闭复检
         * <p>
         * 凭据有其有效期，长期运行后可能在无人察觉的情况下失效，届时动态推送会静默停摆。
         * 定期复检可将其转为显式告警并在配置界面上体现。复检本身只是一次轻量接口调用，
         * 默认十分钟一次，开销可忽略。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int verifyInterval = 600;

        /**
         * 凭据维护失败后的退避上限，单位：秒
         * <p>
         * 复检与续期各自独立退避：一件连续失败就逐级拉长自己的间隔（一倍、两倍、四倍……），
         * 成功即回到复检间隔。<b>两者互不影响</b>——出网劣化时续期查不动，
         * 不该把复检也一起拖慢，那是我们判断「凭据到底还在不在」的唯一途径。
         * <p>
         * 上限的意义是别把间隔拉到几天：退避是为了不在故障期间空转，不是为了放弃。
         * 设为 0 或小于复检间隔时等于关闭退避，每次都按复检间隔重试。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int maintenanceBackoffCap = 3600;

        /**
         * 是否自动续期登录凭据
         * <p>
         * 哔哩哔哩自 2023 年起会随敏感接口的调用逐步作废 Web 端凭据，官方页面为此提供了续期链路。
         * 关闭后凭据会在某天突然失效、动态推送静默停摆，只能重新扫码。
         * <p>
         * 续期仅在服务端明确提示需要时才执行，检查随登录态复检一并进行，因此同样受 verify-interval
         * 控制；复检关闭时续期也不会执行。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean autoRefreshCookie = true;

        /**
         * 扫码登录方式：tv 或 web
         * <p>
         * <b>tv</b>（默认）走 TV 端登录接口，会连同 Cookie 一并返回可续期的令牌（有效期 180 天），
         * 凭据能长期自动续期；<b>web</b> 走网页端接口，实测服务端返回的刷新口令恒为空串，
         * 自动续期不可用，凭据到期后只能重新扫码。
         * <p>
         * 除非 TV 端接口出现异常，否则不建议改为 web。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String qrCodeLoginMode = "tv";
    }

    /**
     * 直播相关
     */
    @Getter
    @Setter
    public static class Live {
        /**
         * 是否启用直播间连接，若连接直播间已被风控，可关闭此开关，仅使用备用直播推送
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean enableConnectLiveRoom = true;

        /**
         * 是否仅连接到启用了直播推送的直播间
         * <p>
         * <b>开启后就没有「纯监听房间」了。</b> 纯监听房间指的是数据源里配了主播、
         * 但不配任何推送目标的那种——只把事件采集下来送进事件输出或累计数据，一条 QQ 消息都不发。
         * 本项为真时这类房间会被整个跳过，<b>表现是「加了主播却什么都没发生」</b>，
         * 所以跳过时会在日志里逐个列出来。
         * <p>
         * 默认关闭，即每个启用的主播都连。只在连接数受限、又确实只关心推送的场景下才开。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean onlyConnectNecessaryRooms = false;

        /**
         * 直播间连接间隔，连接过快可能触发风控，单位：毫秒
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int liveRoomConnectInterval = 1000;

        /**
         * 直播间重连间隔，单位：毫秒
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int liveRoomReconnectInterval = 1000;

        /**
         * 直播间数据包解压后允许的最大字节数，非正数回退到默认 32 MB
         * <p>
         * 这是**防御性上限而非预期值**：正常数据包解压后是几十 KB。
         * 做成可配的理由是内存——默认堆 `-Xmx512m`，而这个上限允许单次解压吃掉
         * 32 MB 连续字节数组，在小内存机器上防御上限自己就可能是那根稻草。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int maxDecompressedBytes = 32 * 1024 * 1024;

        /**
         * 递归展开压缩包的最大层数，非正数回退到默认 3；正常数据不超过一层
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int maxDecodeNestingDepth = 3;

        /**
         * 断线摘要的汇总窗口，单位：秒，设为 0 关闭
         * <p>
         * 逐次一行「连接已断开」在断线风暴里恰好最没用：十个房间各断五次就是五十行，
         * 数不清次数，也看不出是集中在一个房间还是所有房间一起断——而这两者的处理方式相反。
         * 逐次那行走 DEBUG，按本窗口汇总成一条带归因的摘要。没有断线的窗口不打日志。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int disconnectDigestInterval = 600;

        /**
         * 礼物配置缓存过期时间，单位：秒
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int giftCacheExpire = 3600;

        /**
         * 是否自动补全事件信息，启用后会为缺少昵称、头像等信息的事件额外请求接口补全
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean completeEvent = false;

        /**
         * 是否启用直播间数据风控检测
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean autoDetectLiveRoomRisk = true;

        /**
         * 直播间数据风控检测周期，单位：秒
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int autoDetectLiveRoomRiskInterval = 60;

        /**
         * 连续多少个检测窗口业务消息为零，才判定为疑似断流
         * <p>
         * 与 auto-detect-live-room-risk-interval 相乘就是判定所需的持续时长，
         * 默认 3 × 60 秒 = 3 分钟。调小会把偶发的安静误判成断流。
         * <p>
         * 本项取代了原先的 auto-detect-live-room-risk-ratio（进房消息占比阈值）。
         * 那个判据 2026-08-07 被实测证伪：热门房间人来人往、进房消息天然刷屏，
         * 一个 41 万人气的房间进房占比 53% 被判风控，而它同时段每分钟收 71 条弹幕、
         * 对独立基准的到达率 93.3%。**「进房占比高」与「收不到业务消息」是两回事。**
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int autoDetectLiveRoomRiskWindows = 3;

        /**
         * 是否启用备用直播推送，通过轮询接口而非长连接判断开播状态
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean backupLivePush = true;

        /**
         * 备用直播推送检测间隔，单位：秒
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int backupLivePushInterval = 10;

        /**
         * 主播基础数据的留档间隔，单位：小时，0 为关闭
         * <p>
         * 粉丝数这些数字在不播的日子里照样在变，而本场数据只覆盖直播期间——
         * 没有这份留档，「本周涨了多少粉」只能答成「每场开播时分别是多少」。
         * <p>
         * 6 小时一次足够画出趋势，一年不到 2 MB；调得太密除了多打接口没有别的收益。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int snapshotInterval = 6;

        /**
         * 下播报告底部标识的图片路径，留空则不绘制
         * <p>
         * 与动态图片的标识分开配置，因为两者未必想打同一个标；要一致的话，
         * 填成与 {@code starbot.bilibili.dynamic.logo-path} 相同的路径即可。
         * 图片按固定高度等比缩放。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String reportLogoPath = "";
    }

    /**
     * 动态相关
     */
    @Getter
    @Setter
    public static class Dynamic {
        /**
         * 是否用登录账号自动关注「被监听的 UP 主」
         * <p>
         * 关注的是推送配置中要监听的那些 UP 主，<b>不是</b>回关粉丝。哔哩哔哩的动态流接口只返回
         * 已关注账号的动态，不关注就收不到，因此动态推送依赖本开关。关闭后需自行手动关注，
         * 否则对应 UP 主的动态不会被推送。
         * <p>
         * 本开关会修改登录账号的关注列表，这也是建议使用专用小号而非个人主号的原因之一。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean autoFollow = true;

        /**
         * 自动关注的执行间隔，单位：秒
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int autoFollowInterval = 30;

        /**
         * 动态接口请求间隔，单位：秒
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int apiRequestInterval = 10;

        /**
         * 动态图片底部标识的图片路径，留空则不绘制
         * <p>
         * 本项目不再内置标识图片：图形资产是独立于代码许可证的著作权客体，字标还额外涉及商标属性，
         * 沿用上游标识并随每张推送图片对外分发并不妥当。需要打自己社群的标时，
         * 在此填入本地图片路径即可，图片会按固定高度等比缩放。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private String logoPath = "";

        /**
         * 是否自动保存绘制出的动态图片
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean autoSaveImage = false;

        /**
         * 动态发布时间早于此分钟数时不再推送，避免首次启动时补推大量历史动态
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private int pushMinutes = 1440;

        /**
         * 是否推送开播动态（哔哩哔哩在 UP 主开播时自动生成的那条动态）
         * <p>
         * 默认关闭，因为它和开播推送是同一件事的两条通道，开了就会同一个群收到两遍。
         * 而且开播推送在每个维度上都更好：<b>早得多</b>（实测开播动态的发布时间比开播晚整
         * 600 秒，出现在动态流里又晚了 21 分钟，一共迟到半小时）、带标题与封面、能 at 全体成员，
         * 开播动态只是它的劣化重复。
         * <p>
         * <b>打开之前先确认这个 UP 主配了开播推送。</b>只配动态推送、没配开播推送时，
         * 关掉本开关就再也收不到他的开播消息了。
         */
        @ConfigEffect(ConfigEffect.Effect.RESTART)
        private boolean pushLiveDynamic = false;
    }
}
