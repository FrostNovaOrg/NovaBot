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
