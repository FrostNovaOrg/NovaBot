package com.starlwr.bot.bilibili.config;

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
         */
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36";

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
        private boolean enableConnectLiveRoom = true;

        /**
         * 是否仅连接到启用了直播推送的直播间
         */
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
        private boolean backupLivePush = true;

        /**
         * 备用直播推送检测间隔，单位：秒
         */
        private int backupLivePushInterval = 10;
    }

    /**
     * 动态相关
     */
    @Getter
    @Setter
    public static class Dynamic {
        /**
         * 是否自动关注开启了动态推送的 UP 主
         */
        private boolean autoFollow = true;

        /**
         * 自动关注的执行间隔，单位：秒
         */
        private int autoFollowInterval = 30;

        /**
         * 动态接口请求间隔，单位：秒
         */
        private int apiRequestInterval = 10;

        /**
         * 是否在动态图片底部绘制 StarBot logo
         */
        private boolean drawLogo = true;

        /**
         * 是否自动保存绘制出的动态图片
         */
        private boolean autoSaveImage = false;

        /**
         * 动态发布时间早于此分钟数时不再推送，避免首次启动时补推大量历史动态
         */
        private int pushMinutes = 1440;
    }
}
