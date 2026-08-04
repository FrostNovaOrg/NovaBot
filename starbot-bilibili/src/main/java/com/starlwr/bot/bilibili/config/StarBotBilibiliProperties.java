package com.starlwr.bot.bilibili.config;

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
        private int corePoolSize = 4;

        /**
         * 线程池最大线程数
         */
        private int maxPoolSize = 32;

        /**
         * 线程池任务队列容量
         */
        private int queueCapacity = 256;

        /**
         * 非核心线程存活时间，单位：秒
         */
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
        private boolean liveRoomRawMessageLog = false;

        /**
         * 是否启用动态接口原始响应调试日志
         */
        private boolean dynamicRawMessageLog = false;
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
         */
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";

        /**
         * 接口请求失败后的最大重试次数
         */
        private int apiRetryMaxTimes = 3;

        /**
         * 接口请求失败后的重试间隔，单位：毫秒
         */
        private int apiRetryInterval = 3000;
    }

    /**
     * 账号与凭据相关
     */
    @Getter
    @Setter
    public static class Account {
        /**
         * 登录凭据存储文件路径
         */
        private String cookiePath = "cookies.json";

        /**
         * 是否加密存储登录凭据
         * <p>
         * 凭据中的 SESSDATA 与 bili_jct 等同于账号的完整控制权，明文落盘意味着任何能读到该文件的
         * 进程或备份都能直接接管账号。启用后凭据将以 AES-GCM 加密保存，密钥存放于独立的密钥文件中，
         * 两者均以仅属主可读写的权限创建。
         */
        private boolean encrypt = true;

        /**
         * 加密密钥存储文件路径，仅在启用加密存储时生效
         * <p>
         * 密钥与密文分离存放，便于将密钥置于权限更严格的位置，或替换为由外部密钥管理服务注入。
         */
        private String keyPath = "cookies.key";

        /**
         * 登录态复检间隔，单位：秒，设为 0 或负数可关闭复检
         * <p>
         * 凭据有其有效期，长期运行后可能在无人察觉的情况下失效，届时动态推送会静默停摆。
         * 定期复检可将其转为显式告警并在配置界面上体现。复检本身只是一次轻量接口调用，
         * 默认十分钟一次，开销可忽略。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private int verifyInterval = 600;

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
        private boolean enableConnectLiveRoom = true;

        /**
         * 是否仅连接到启用了直播推送的直播间
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private boolean onlyConnectNecessaryRooms = false;

        /**
         * 直播间连接间隔，连接过快可能触发风控，单位：毫秒
         */
        private int liveRoomConnectInterval = 1000;

        /**
         * 直播间重连间隔，单位：毫秒
         */
        private int liveRoomReconnectInterval = 1000;

        /**
         * 礼物配置缓存过期时间，单位：秒
         */
        private int giftCacheExpire = 3600;

        /**
         * 是否自动补全事件信息，启用后会为缺少昵称、头像等信息的事件额外请求接口补全
         */
        private boolean completeEvent = false;

        /**
         * 是否启用直播间数据风控检测
         */
        private boolean autoDetectLiveRoomRisk = true;

        /**
         * 直播间数据风控检测周期，单位：秒
         */
        private int autoDetectLiveRoomRiskInterval = 60;

        /**
         * 判定为风控的进房事件占比阈值，单位：百分比
         */
        private int autoDetectLiveRoomRiskRatio = 50;

        /**
         * 是否启用备用直播推送，通过轮询接口而非长连接判断开播状态
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private boolean backupLivePush = true;

        /**
         * 备用直播推送检测间隔，单位：秒
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private int backupLivePushInterval = 10;
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
        private boolean autoFollow = true;

        /**
         * 自动关注的执行间隔，单位：秒
         */
        private int autoFollowInterval = 30;

        /**
         * 动态接口请求间隔，单位：秒
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private int apiRequestInterval = 10;

        /**
         * 动态图片底部标识的图片路径，留空则不绘制
         * <p>
         * 本项目不再内置标识图片：图形资产是独立于代码许可证的著作权客体，字标还额外涉及商标属性，
         * 沿用上游标识并随每张推送图片对外分发并不妥当。需要打自己社群的标时，
         * 在此填入本地图片路径即可，图片会按固定高度等比缩放。
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private String logoPath = "";

        /**
         * 是否自动保存绘制出的动态图片
         */
        private boolean autoSaveImage = false;

        /**
         * 动态发布时间早于此分钟数时不再推送，避免首次启动时补推大量历史动态
         */
        @ConfigLevel(ConfigLevel.Level.COMMON)
        private int pushMinutes = 1440;
    }
}
