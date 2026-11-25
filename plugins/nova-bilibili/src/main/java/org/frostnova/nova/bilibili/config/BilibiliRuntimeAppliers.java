package org.frostnova.nova.bilibili.config;

import org.frostnova.nova.bilibili.service.BilibiliDynamicService;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * B 站采集设置保存后写回运行中的配置
 * <p>
 * 这里每一项的读取方都是用到时现读（各项旁边写着读处），写回即生效：
 * 下一次请求、下一条消息、下一轮动态就按新值办。动态轮询间隔例外：轮询是启动时按间隔排好的，
 * 写回之后由 {@link BilibiliDynamicService#reschedulePoll()} 按新间隔重排。
 * <p>
 * 一批里的这些项<b>先全部读懂、再一起写回</b>，有一项读不懂就一项也不写。
 * 读不懂的取值在写配置文件之前就由 {@link #valueValidators()} 拒收，配置文件与运行中的值都不动。
 * 取值按 Spring 绑定那一路转换，与重启后读到的是同一个值；删键回到默认值。
 * <p>
 * 动态标识图片（{@code dynamic.logo-path}）不在这里：出图那侧第一次用到时把图读进内存后就不再看路径，
 * 改了也要重启才换图。报告标识图片（{@code live.report-logo-path}）在：报告那侧按路径缓存，路径一变就重读。
 */
@Slf4j
@NovaComponent
public class BilibiliRuntimeAppliers implements RuntimeConfigurationApplierContributor {
    private static final String PREFIX = "novabot.bilibili.";

    private static final NovaBilibiliProperties DEFAULTS = new NovaBilibiliProperties();

    private static final Map<String, Setting<?>> SETTINGS = new LinkedHashMap<>();

    private static final String DYNAMIC_POLL_SUFFIX = "dynamic.api-request-interval";

    /**
     * 「动态轮询间隔」：写回之后要叫轮询按新间隔重排
     */
    static final String DYNAMIC_POLL_INTERVAL = PREFIX + DYNAMIC_POLL_SUFFIX;

    /**
     * 「登录凭据 · 自动续期」：启动时记一行它的状态，设置页改了它也记一行，查日志的人才知道它变过
     */
    static final String AUTO_REFRESH_COOKIE = PREFIX + "account.auto-refresh-cookie";

    static {
        // ---- 登录：续期每趟、健康检查每次、扫码每次都现读 ----
        // BilibiliAccountService#credentialNote / #refreshOnce，BilibiliLoginHealthProbe#check
        setting("account.auto-refresh-cookie", "登录凭据 · 自动续期",
                DEFAULTS.getAccount().isAutoRefreshCookie(), BilibiliRuntimeAppliers::bool,
                (p, v) -> p.getAccount().setAutoRefreshCookie(v));
        // BilibiliAccountService#isTvLoginMode，下一次扫码起按新方式
        setting("account.qr-code-login-mode", "扫码登录方式",
                DEFAULTS.getAccount().getQrCodeLoginMode(), BilibiliRuntimeAppliers::loginMode,
                (p, v) -> p.getAccount().setQrCodeLoginMode(v));

        // ---- 直播 ----
        // BilibiliGapRecovery#recover，每次重连现读
        setting("live.reconnect-recovery", "重连后补录",
                DEFAULTS.getLive().isReconnectRecovery(), BilibiliRuntimeAppliers::bool,
                (p, v) -> p.getLive().setReconnectRecovery(v));
        // BilibiliConnectGate#submit，每排一次建连现读
        setting("live.live-room-connect-interval", "直播间连接间隔",
                DEFAULTS.getLive().getLiveRoomConnectInterval(), BilibiliRuntimeAppliers::integer,
                (p, v) -> p.getLive().setLiveRoomConnectInterval(v));
        // BilibiliLiveRoomConnector#scheduleReconnect，每次排重连现读
        setting("live.live-room-reconnect-interval", "直播间重连间隔",
                DEFAULTS.getLive().getLiveRoomReconnectInterval(), BilibiliRuntimeAppliers::integer,
                (p, v) -> p.getLive().setLiveRoomReconnectInterval(v));
        // BilibiliLiveRoomConnector#acceptBinaryMessage，每收一个数据包现读一次
        setting("live.max-decompressed-bytes", "数据包解压 · 字节上限",
                DEFAULTS.getLive().getMaxDecompressedBytes(), BilibiliRuntimeAppliers::integer,
                (p, v) -> p.getLive().setMaxDecompressedBytes(v));
        setting("live.max-decode-nesting-depth", "数据包解压 · 最大层数",
                DEFAULTS.getLive().getMaxDecodeNestingDepth(), BilibiliRuntimeAppliers::integer,
                (p, v) -> p.getLive().setMaxDecodeNestingDepth(v));
        // 报告标识图片：ReportSharedStyle.Logo 每张图现读路径，路径与缓存的不同就重读。
        // 保存时先读一遍，读不到或不是图片就不收
        setting("live.report-logo-path", "报告自定义标识图片",
                DEFAULTS.getLive().getReportLogoPath(), BilibiliRuntimeAppliers::logoPath,
                (p, v) -> p.getLive().setReportLogoPath(v));
        // BilibiliEventParser，每条事件现读
        setting("live.complete-event", "自动补全事件信息",
                DEFAULTS.getLive().isCompleteEvent(), BilibiliRuntimeAppliers::bool,
                (p, v) -> p.getLive().setCompleteEvent(v));

        // ---- 动态：BilibiliDynamicService 每轮轮询现读，出图那侧每张图现读 ----
        // 轮询间隔：写回后由 BilibiliDynamicService#reschedulePoll 按新间隔重排
        setting(DYNAMIC_POLL_SUFFIX, "动态轮询间隔",
                DEFAULTS.getDynamic().getApiRequestInterval(), BilibiliRuntimeAppliers::pollInterval,
                (p, v) -> p.getDynamic().setApiRequestInterval(v));
        setting("dynamic.push-minutes", "动态补推时限",
                DEFAULTS.getDynamic().getPushMinutes(), BilibiliRuntimeAppliers::integer,
                (p, v) -> p.getDynamic().setPushMinutes(v));
        setting("dynamic.push-live-dynamic", "推送开播动态",
                DEFAULTS.getDynamic().isPushLiveDynamic(), BilibiliRuntimeAppliers::bool,
                (p, v) -> p.getDynamic().setPushLiveDynamic(v));
        setting("dynamic.auto-save-image", "动态图片自动保存",
                DEFAULTS.getDynamic().isAutoSaveImage(), BilibiliRuntimeAppliers::bool,
                (p, v) -> p.getDynamic().setAutoSaveImage(v));

        // ---- 调试：每条消息、每次响应、每条动态现读 ----
        setting("debug.live-room-raw-message-log", "直播间原始消息调试日志",
                DEFAULTS.getDebug().isLiveRoomRawMessageLog(), BilibiliRuntimeAppliers::bool,
                (p, v) -> p.getDebug().setLiveRoomRawMessageLog(v));
        setting("debug.dynamic-raw-message-log", "动态接口原始响应调试日志",
                DEFAULTS.getDebug().isDynamicRawMessageLog(), BilibiliRuntimeAppliers::bool,
                (p, v) -> p.getDebug().setDynamicRawMessageLog(v));
        setting("debug.dynamic-dedup-probe", "动态去重排查日志",
                DEFAULTS.getDebug().isDynamicDedupProbe(), BilibiliRuntimeAppliers::bool,
                (p, v) -> p.getDebug().setDynamicDedupProbe(v));

        // ---- 接口：BilibiliApiUtil 每个请求现取请求头与重试参数；直播间长连接在下一次建连时带新的 User-Agent ----
        setting("network.user-agent", "B站接口 · User-Agent",
                DEFAULTS.getNetwork().getUserAgent(), BilibiliRuntimeAppliers::userAgent,
                (p, v) -> p.getNetwork().setUserAgent(v));
        setting("network.api-retry-max-times", "B站接口 · 最多重试次数",
                DEFAULTS.getNetwork().getApiRetryMaxTimes(), BilibiliRuntimeAppliers::integer,
                (p, v) -> p.getNetwork().setApiRetryMaxTimes(v));
        setting("network.api-retry-interval", "B站接口 · 重试间隔",
                DEFAULTS.getNetwork().getApiRetryInterval(), BilibiliRuntimeAppliers::integer,
                (p, v) -> p.getNetwork().setApiRetryInterval(v));
    }

    private final NovaBilibiliProperties properties;

    /**
     * 动态轮询：用到时才取，没有（插件没装全、测试里）时只写回配置，开始轮询时自会按新值排
     */
    private final ObjectProvider<BilibiliDynamicService> dynamicService;

    public BilibiliRuntimeAppliers() {
        this(new NovaBilibiliProperties());
    }

    public BilibiliRuntimeAppliers(NovaBilibiliProperties properties) {
        this(properties, null);
    }

    @Autowired
    public BilibiliRuntimeAppliers(NovaBilibiliProperties properties,
                                   ObjectProvider<BilibiliDynamicService> dynamicService) {
        this.properties = properties;
        this.dynamicService = dynamicService;
    }

    /**
     * @return 本类写回的配置项全名，登记顺序
     */
    public static List<String> keys() {
        return List.copyOf(SETTINGS.keySet());
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        return Map.of();
    }

    @Override
    public BatchApplier batchApplier() {
        return new BatchApplier(SETTINGS.keySet(), this::apply);
    }

    @Override
    public Map<String, Function<String, String>> valueValidators() {
        Map<String, Function<String, String>> validators = new LinkedHashMap<>();
        SETTINGS.forEach((key, setting) -> validators.put(key, value -> {
            if (value == null) {
                return null;
            }
            try {
                setting.parse().apply(value);
                return null;
            } catch (IllegalArgumentException invalid) {
                return key + "（" + setting.label() + "）：" + invalid.getMessage();
            }
        }));
        return validators;
    }

    /**
     * 先把这一批全部读懂，再在同一把锁下一起写回；有一项读不懂就抛出，一项也不写
     */
    private void apply(Map<String, String> changes) {
        List<Consumer<NovaBilibiliProperties>> updates = new ArrayList<>();
        changes.forEach((key, value) -> updates.add(SETTINGS.get(key).prepare(value)));
        synchronized (properties) {
            updates.forEach(update -> update.accept(properties));
        }
        if (changes.containsKey(DYNAMIC_POLL_INTERVAL) && dynamicService != null) {
            dynamicService.ifAvailable(BilibiliDynamicService::reschedulePoll);
        }
        if (changes.containsKey(AUTO_REFRESH_COOKIE)) {
            NovaBilibiliProperties.Account account = properties.getAccount();
            log.info("设置页改了登录凭据自动续期, 现在{}", !account.isAutoRefreshCookie() ? "已关闭"
                    : account.getVerifyInterval() > 0 ? "已启用" : "已启用, 但登录态复检已关闭, 续期不会执行");
        }
    }

    private record Setting<T>(String label, T fallback, Function<String, T> parse,
                              BiConsumer<NovaBilibiliProperties, T> write) {
        Consumer<NovaBilibiliProperties> prepare(String value) {
            T parsed = value == null ? fallback : parse.apply(value);
            return target -> write.accept(target, parsed);
        }
    }

    private static <T> void setting(String suffix, String label, T fallback, Function<String, T> parse,
                                    BiConsumer<NovaBilibiliProperties, T> write) {
        SETTINGS.put(PREFIX + suffix, new Setting<>(label, fallback, parse, write));
    }

    private static <T> T converted(String value, Class<T> type) {
        T result;
        try {
            result = ApplicationConversionService.getSharedInstance().convert(value, type);
        } catch (RuntimeException e) {
            result = null;
        }
        if (result == null) {
            throw new IllegalArgumentException(type == Boolean.class ? "请选择开启或关闭" : "请填写整数");
        }
        return result;
    }

    private static Integer integer(String value) {
        return converted(value, Integer.class);
    }

    private static Boolean bool(String value) {
        return converted(value, Boolean.class);
    }

    /**
     * 动态轮询间隔：整数秒，不短于 {@value BilibiliDynamicService#MIN_POLL_INTERVAL_SECONDS} 秒。
     * 更短的值运行中也只按最短的查，收下来就是界面上写着一个、实际按另一个
     */
    private static Integer pollInterval(String value) {
        int seconds = integer(value);
        if (seconds < BilibiliDynamicService.MIN_POLL_INTERVAL_SECONDS) {
            throw new IllegalArgumentException("最短 " + BilibiliDynamicService.MIN_POLL_INTERVAL_SECONDS
                    + " 秒，请填写不小于它的整数；再密容易被 B 站风控");
        }
        return seconds;
    }

    /**
     * 报告标识图片的路径：留空表示不画；填了就当场读一遍，读不到或读出来不是图片都不收
     * <p>
     * 收下之后下一张报告图就照这个路径读。读不出来的路径存下去，报告上只是悄悄少了标识，
     * 而保存的人看到的是「已生效」。原样返回、不去空白：报告那侧拿到的与配置文件里的是同一个字面。
     */
    private static String logoPath(String value) {
        if (value.isBlank()) {
            return value;
        }
        Path file;
        try {
            file = Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("这不是一个有效的文件路径");
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalArgumentException("读不到这个文件（不存在、不是文件或没有读权限），请填写图片文件的完整路径");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
        } catch (IOException | RuntimeException e) {
            image = null;
        }
        if (image == null) {
            throw new IllegalArgumentException("这个文件不是能识别的图片，请用 PNG、JPEG、GIF 或 BMP");
        }
        return value;
    }

    private static String loginMode(String value) {
        if (!"tv".equalsIgnoreCase(value) && !"web".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("只能是 tv 或 web");
        }
        return value;
    }

    private static String userAgent(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("不能留空");
        }
        return value;
    }
}
