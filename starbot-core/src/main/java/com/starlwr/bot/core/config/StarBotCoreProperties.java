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
