package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Level;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.SettingsAppliedListener;
import org.frostnova.nova.core.config.StandbyBoundConfig;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor.BatchApplier;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.converter.LogLevelConverter;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.IpMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 把保存下来的配置改动落到运行中的程序上
 * <p>
 * 绝大多数配置项是启动时读一次就不再回头看的，改了只能等重启。但有几项<b>「等重启」等于没有这项功能</b>：
 * 「临时静音」要的就是现在生效，为此重启一次会把正在采集的场次打断；告警接收人配错了的时候，
 * 正需要告警的往往就是此刻。
 * <p>
 * <b>能即时生效的键是这里这份显式名单，不是推断出来的。</b>「把新值写回配置对象」这个动作本身
 * 到处都能做，但它只在<b>读取方每次都重新读</b>的前提下才真的管用——启动时把值抄进自己字段的组件，
 * 改了配置对象它也看不见。哪几项满足这个前提是一件需要逐项确认的事，写成名单才有地方确认；
 * 靠「凡是标了即时生效的都试着写回去」，就会出现界面说已生效而实际没有的情形，
 * 且<b>没有任何提示说它没生效</b>。
 * <p>
 * 名单与字段上的 {@code @ConfigEffect} 标注必须一一对应，两边对不上时构建会红
 * （判据在 {@code ConfigurationConsistencyTest}）。登录口令与二次验证开关标的也是即时生效，
 * 但落地在 {@code /config/api/auth} 专用口，不进本名单——通用保存若把它们写回，
 * 一枚已登录会话就能换掉门。
 *
 * <h2>不在名单里的那两类</h2>
 * 「命令开关」与「金额可见」同样是即时生效的，但它们<b>不是 application.yml 里的配置项</b>——
 * 两者都按会话记在运行状态里，走运行状态接口那条路，本类够不着也不该够得着。
 */
@Slf4j
@Service
public class RuntimeConfigurationApplier {
    /**
     * 配置项名 → 把新值写回运行中的配置对象
     */
    private static final Map<String, BiConsumer<NovaCoreProperties, String>> APPLIERS = new LinkedHashMap<>();

    static {
        // ---- 推送总开关 ----
        // PushGate 每次判断都重新读这一项，因此写回即生效
        APPLIERS.put("novabot.core.push.enabled", (properties, value) ->
                properties.getPush().setEnabled(Boolean.parseBoolean(value)));
        // 每次跟提示前都现读，写回即生效。关掉时不认领，留给以后打开时的第一条
        APPLIERS.put("novabot.core.push.first-push-tip", (properties, value) ->
                properties.getPush().setFirstPushTip(Boolean.parseBoolean(value)));

        // ---- 静音时段 ----
        // 同上，PushGate 每条推送都现读一次起止时刻
        APPLIERS.put("novabot.core.push.quiet-start", (properties, value) ->
                properties.getPush().setQuietStart(value));
        APPLIERS.put("novabot.core.push.quiet-end", (properties, value) ->
                properties.getPush().setQuietEnd(value));

        // ---- 告警接收人 ----
        APPLIERS.put("novabot.core.alert.webhook-url", (properties, value) ->
                properties.getAlert().setWebhookUrl(value));
        APPLIERS.put("novabot.core.mail.default-to", (properties, value) ->
                properties.getMail().setDefaultTo(value));

        // ---- 配置备份保留份数 ----
        // 下次保存时现读，写回即生效
        APPLIERS.put("novabot.core.config-ui.backup-keep", (properties, value) ->
                properties.getConfigUi().setBackupKeep(
                        TimestampedFileBackup.clamp(Integer.parseInt(value.trim()))));
    }

    /**
     * 带取值校验的核心即时生效项
     * <p>
     * 这几项的读取方都是用到时现读（各项旁边写着读处），写回即生效。与 {@link #APPLIERS} 的不同在两处：
     * <ul>
     *   <li>保存前先过 {@link #validateValues}：取值读不懂的整批拒绝、配置文件不动。
     *       写进文件再让运行时去碰的话，重启时绑定就会失败。</li>
     *   <li>一批里的这几项<b>先全部解析、再一起写回</b>，写回期间持有 {@link NovaCoreProperties.Alert} 的锁：
     *       Webhook 的请求方式与两个字段名一起改时，读方经 {@link NovaCoreProperties.Alert#webhookFormat()}
     *       拿到的不会是半新半旧的一组。</li>
     * </ul>
     * 取值按 Spring 绑定那一路转换，与重启后读到的是同一个值。删键（{@code null}）回到默认值。
     */
    private static final Map<String, CheckedSetting> CHECKED = new LinkedHashMap<>();

    static {
        NovaCoreProperties defaults = new NovaCoreProperties();

        // ---- @全体成员额度：AtAllQuotaService 每次记账前现读 ----
        checked("novabot.core.push.at-all-daily-limit", "@全体成员 每日上限 · 账号",
                defaults.getPush().getAtAllDailyLimit(), RuntimeConfigurationApplier::integer,
                (p, v) -> p.getPush().setAtAllDailyLimit(v));
        checked("novabot.core.push.at-all-session-daily-limit", "@全体成员 每日上限 · 单群",
                defaults.getPush().getAtAllSessionDailyLimit(), RuntimeConfigurationApplier::integer,
                (p, v) -> p.getPush().setAtAllSessionDailyLimit(v));

        // ---- 告警：AlertService 每条告警、每趟重投、每次入队都现读 ----
        checked("novabot.core.alert.enabled", "启用告警",
                defaults.getAlert().isEnabled(), RuntimeConfigurationApplier::bool,
                (p, v) -> p.getAlert().setEnabled(v));
        checked("novabot.core.alert.convergence-interval", "同一问题告警最短间隔",
                defaults.getAlert().getConvergenceInterval(), RuntimeConfigurationApplier::integer,
                (p, v) -> p.getAlert().setConvergenceInterval(v));
        checked("novabot.core.alert.retry-max-attempts", "失败重发 · 最多次数",
                defaults.getAlert().getRetryMaxAttempts(), RuntimeConfigurationApplier::integer,
                (p, v) -> p.getAlert().setRetryMaxAttempts(v));
        checked("novabot.core.alert.retry-queue-size", "失败重发 · 队列容量",
                defaults.getAlert().getRetryQueueSize(), RuntimeConfigurationApplier::integer,
                (p, v) -> p.getAlert().setRetryQueueSize(v));
        // Webhook 每发一次现取一组（webhookFormat）
        checked("novabot.core.alert.webhook-method", "Webhook · 请求方式",
                defaults.getAlert().getWebhookMethod(), RuntimeConfigurationApplier::webhookMethod,
                (p, v) -> p.getAlert().setWebhookMethod(v));
        checked("novabot.core.alert.webhook-title-field", "Webhook · 标题字段名",
                defaults.getAlert().getWebhookTitleField(), RuntimeConfigurationApplier::fieldName,
                (p, v) -> p.getAlert().setWebhookTitleField(v));
        checked("novabot.core.alert.webhook-content-field", "Webhook · 内容字段名",
                defaults.getAlert().getWebhookContentField(), RuntimeConfigurationApplier::fieldName,
                (p, v) -> p.getAlert().setWebhookContentField(v));

        // ---- 超级管理员：CommandDispatcher 每条命令判权限时现读。换成一份新的不可变表，不在原表上改 ----
        checked("novabot.core.command.admins", "超级管理员",
                List.<Long>of(), RuntimeConfigurationApplier::accounts,
                (p, v) -> p.getCommand().setAdmins(v));

        // ---- 直播：开播事件与场次恢复判断重连时现读 ----
        checked("novabot.core.live.reconnect-interval", "短暂下播视为同一场",
                defaults.getLive().getReconnectInterval(), RuntimeConfigurationApplier::integer,
                (p, v) -> p.getLive().setReconnectInterval(v));

        // ---- 绘图：每次扩画布时现读 ----
        checked("novabot.core.paint.auto-expand-height", "画布扩展步长",
                defaults.getPaint().getAutoExpandHeight(), RuntimeConfigurationApplier::positive,
                (p, v) -> p.getPaint().setAutoExpandHeight(v));

        // ---- 新版检查：每次检查现读；关掉时提醒当场收起（UpdateCheckService#pendingUpdate） ----
        checked("novabot.core.config-ui.update.enabled", "检查新版本",
                defaults.getConfigUi().getUpdate().isEnabled(), RuntimeConfigurationApplier::bool,
                (p, v) -> p.getConfigUi().getUpdate().setEnabled(v));
        checked("novabot.core.config-ui.update.source", "检查新版本 · 来源地址",
                defaults.getConfigUi().getUpdate().getSource(), RuntimeConfigurationApplier::webAddress,
                (p, v) -> p.getConfigUi().getUpdate().setSource(v));

        // ---- 网络日志：HttpUtil 每个请求现读，读的就是本配置对象里的那一节（coreLogProperties） ----
        checked("novabot.core.log.network-log", "网络日志",
                defaults.getLog().isNetworkLog(), RuntimeConfigurationApplier::bool,
                (p, v) -> p.getLog().setNetworkLog(v));
        checked("novabot.core.log.network-log-suppress-window", "网络日志 · 去重窗口",
                defaults.getLog().getNetworkLogSuppressWindow(), RuntimeConfigurationApplier::integer,
                (p, v) -> p.getLog().setNetworkLogSuppressWindow(v));

        // ---- 事件时间线：每日清理时现读 ----
        checked("novabot.core.timeline.retention-days", "事件时间线保留天数",
                defaults.getTimeline().getRetentionDays(), RuntimeConfigurationApplier::integer,
                (p, v) -> p.getTimeline().setRetentionDays(v));

        // ---- 外部程序触发：每个事件、每条命令现读。规则本身仍要重启 ----
        checked("novabot.core.exec.enabled", "外部程序触发",
                defaults.getExec().isEnabled(), RuntimeConfigurationApplier::bool,
                (p, v) -> p.getExec().setEnabled(v));
        checked("novabot.core.exec.timeout", "外部程序触发 · 超时",
                defaults.getExec().getTimeout(), RuntimeConfigurationApplier::integer,
                (p, v) -> p.getExec().setTimeout(v));

        // ---- 日志级别：写回后由 LogLevelApplier 当场改两路输出的门槛 ----
        // 删键回到未设（null），两路都按 INFO，与 logback.xml 里的缺省同一个
        checked("novabot.core.log.console", "控制台日志级别",
                null, RuntimeConfigurationApplier::logLevel, (p, v) -> p.getLog().setConsole(v));
        checked("novabot.core.log.file", "日志文件级别",
                null, RuntimeConfigurationApplier::logLevel, (p, v) -> p.getLog().setFile(v));

        // ---- 发件服务：不在配置对象里，写回后由 NovaMailService 按新值重建发件器 ----
        checked("spring.mail.host", "邮件告警 · SMTP 服务器",
                "", RuntimeConfigurationApplier::host, (p, v) -> { });
        checked("spring.mail.port", "邮件告警 · SMTP 端口",
                null, RuntimeConfigurationApplier::port, (p, v) -> { });
        checked("spring.mail.username", "邮件告警 · SMTP 账号",
                "", String::strip, (p, v) -> { });
        // 口令不去首尾空白：授权码里的空白也是口令的一部分，改了就登不上
        checked("spring.mail.password", "邮件告警 · SMTP 密码／授权码",
                "", value -> value, (p, v) -> { });

        // ---- 控制台来源 IP：安全过滤器每个请求现读，名单换了就换一个匹配器 ----
        checked("novabot.core.config-ui.allow-ips", "允许访问的来源 IP",
                defaults.getConfigUi().getAllowIps(), RuntimeConfigurationApplier::allowIps,
                (p, v) -> p.getConfigUi().setAllowIps(v));

        // ---- 登录：会话库与限流每次判断现读 ----
        checked("novabot.core.config-ui.auth.session-hours", "会话有效期",
                defaults.getConfigUi().getAuth().getSessionHours(), RuntimeConfigurationApplier::positive,
                (p, v) -> p.getConfigUi().getAuth().setSessionHours(v));
        checked("novabot.core.config-ui.auth.idle-hours", "闲置自动退出时长",
                defaults.getConfigUi().getAuth().getIdleHours(), RuntimeConfigurationApplier::positive,
                (p, v) -> p.getConfigUi().getAuth().setIdleHours(v));
        checked("novabot.core.config-ui.auth.max-failures", "连续登录失败锁定次数",
                defaults.getConfigUi().getAuth().getMaxFailures(), RuntimeConfigurationApplier::positive,
                (p, v) -> p.getConfigUi().getAuth().setMaxFailures(v));
        checked("novabot.core.config-ui.auth.lockout-minutes", "首次锁定时长",
                defaults.getConfigUi().getAuth().getLockoutMinutes(), RuntimeConfigurationApplier::positive,
                (p, v) -> p.getConfigUi().getAuth().setLockoutMinutes(v));

        // ---- 图片字体：换表时连挑字用的那张一起重排，报告图下一张就按新表挑 ----
        checked("novabot.core.paint.fonts", "图片字体",
                List.<String>of(), RuntimeConfigurationApplier::lines,
                (p, v) -> p.getPaint().replaceFonts(v));

        // ---- 定时任务的间隔：写回后由排它的那一方按新间隔重排 ----
        checked("novabot.core.live.auto-save-live-data-interval", "直播数据 · 保存间隔",
                defaults.getLive().getAutoSaveLiveDataInterval(), RuntimeConfigurationApplier::positive,
                (p, v) -> p.getLive().setAutoSaveLiveDataInterval(v));
        checked("novabot.core.alert.retry-interval", "失败重发 · 间隔",
                defaults.getAlert().getRetryInterval(), RuntimeConfigurationApplier::nonNegative,
                (p, v) -> p.getAlert().setRetryInterval(v));
    }

    /**
     * 写文件那一步已经逐项查过的数字名单：报错指得出第几项、原文是什么，这里不再抢先另说一遍
     * （见 {@code ConfigurationFileService#rejectInvalidNumericListItems}），整批同样一项都不写
     */
    private static final Set<String> CHECKED_ON_WRITE = Set.of("novabot.core.command.admins");

    /**
     * 一项带校验的核心设置：怎么读懂取值、读懂之后怎么写回
     * @param label 设置页上的名字，拒收时那句话里用
     * @param fallback 删键时回到的默认值
     * @param parse 读懂取值，读不懂时抛 {@link IllegalArgumentException}，消息是给人看的半句
     * @param write 写回运行中的配置对象
     */
    private record CheckedSetting(String label, Object fallback, Function<String, Object> parse,
                                  BiConsumer<NovaCoreProperties, Object> write) {
        Object read(String value) {
            return value == null ? fallback : parse.apply(value);
        }

        /**
         * 写进配置文件的那一份字面：与写回内存的是同一个值
         * <p>
         * 内存里用的是去掉首尾空白的值，文件里却留着空白的话，重启按文件读，读到的就不是保存时生效的那个——
         * Webhook 请求方式填「 get 」，当场按 GET 发，重启后认不出、退回 POST。
         * 字符串取读懂后的那个；名单逐行去空白、去空行；级别写成标准写法；数字与开关去首尾空白。
         * 读不懂的原样返回，由校验那一步去拒。
         */
        String text(String value) {
            if (value == null) {
                return null;
            }
            Object parsed;
            try {
                parsed = parse.apply(value);
            } catch (IllegalArgumentException e) {
                return value;
            }
            if (parsed instanceof String text) {
                return text;
            }
            if (parsed instanceof List<?> list) {
                return String.join("\n", list.stream().map(String::valueOf).toList());
            }
            if (parsed instanceof Level level) {
                return level.levelStr;
            }
            return value.strip();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void checked(String key, String label, T fallback, Function<String, T> parse,
                                    BiConsumer<NovaCoreProperties, T> write) {
        CHECKED.put(key, new CheckedSetting(label, fallback, parse::apply,
                (properties, value) -> write.accept(properties, (T) value)));
    }

    private static <T> T converted(String value, Class<T> type) {
        T result;
        try {
            result = org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()
                    .convert(value, type);
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

    private static Integer positive(String value) {
        int parsed = integer(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("请填写大于 0 的整数");
        }
        return parsed;
    }

    private static Integer nonNegative(String value) {
        int parsed = integer(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("请填写 0 或正整数");
        }
        return parsed;
    }

    private static Boolean bool(String value) {
        return converted(value, Boolean.class);
    }

    /**
     * 日志级别。留空不收：配置文件里留空时日志框架拿到的是空串，它解析不了就退回 DEBUG，
     * 于是一个「没填」会让日志忽然多出一大截，而界面上看着与 INFO 无异
     */
    private static Level logLevel(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("请填写一个级别（如 INFO）；要用默认的 INFO，请点「恢复默认」");
        }
        try {
            Level level = new LogLevelConverter().convert(value);
            if (level != null) {
                return level;
            }
        } catch (IllegalArgumentException ignored) {
            // 下面统一说可选值
        }
        throw new IllegalArgumentException("只能是 TRACE、DEBUG、INFO、WARN、ERROR、OFF 之一");
    }

    /**
     * SMTP 服务器：留空表示不用邮件告警；不收带空白的地址，那样的主机名连不上，也认不出是哪一台
     */
    private static String host(String value) {
        String host = value.strip();
        if (host.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("服务器地址里不能有空格");
        }
        return host;
    }

    private static Integer port(String value) {
        Integer port;
        try {
            port = converted(value, Integer.class);
        } catch (IllegalArgumentException e) {
            port = null;
        }
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("请填写 1 到 65535 之间的端口号，如 465 或 587");
        }
        return port;
    }

    /**
     * 每行一项，空行跳过，各行去掉首尾空白
     */
    private static List<String> lines(String value) {
        return value.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /**
     * 来源 IP 名单：每行一条精确 IP 或 CIDR 网段，写错一条整份不收，并指出是哪一条
     * <p>
     * 启动时匹配器遇到写错的那条只是记一笔日志、跳过它；保存时若也这么放过去，
     * 管理员以为放行了的地址其实进不来，而他看不到那一笔日志。
     */
    private static List<String> allowIps(String value) {
        List<String> entries = lines(value);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("名单不能为空，空名单会把所有人挡在控制台外面");
        }
        for (String entry : entries) {
            String problem = IpMatcher.problemOf(entry);
            if (problem != null) {
                throw new IllegalArgumentException("「" + entry + "」不是有效的 IP 或网段（" + problem + "）");
            }
        }
        return entries;
    }

    /**
     * 只收 http(s) 绝对地址：别的写法取不到东西，查新版就一直静默落空
     */
    private static String webAddress(String value) {
        String address = value.strip();
        if (address.isEmpty()) {
            throw new IllegalArgumentException("不能留空；要用默认来源，请点「恢复默认」");
        }
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme();
            if (uri.isAbsolute() && uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return address;
            }
        } catch (URISyntaxException ignored) {
            // 与协议不对同样对待
        }
        throw new IllegalArgumentException("请填写以 http:// 或 https:// 开头的完整地址");
    }

    private static String webhookMethod(String value) {
        String method = value.strip();
        if (!"POST".equalsIgnoreCase(method) && !"GET".equalsIgnoreCase(method)) {
            throw new IllegalArgumentException("只能是 POST 或 GET");
        }
        return method;
    }

    private static String fieldName(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("不能留空");
        }
        return value.strip();
    }

    /**
     * 每行一个账号，空行跳过。逐项按绑定那一路转成数字，项内带空白的不收：那会把两个号码并成一个
     */
    private static List<Long> accounts(String value) {
        List<Long> accounts = new ArrayList<>();
        for (String line : value.split("\n")) {
            String item = line.strip();
            if (item.isEmpty()) {
                continue;
            }
            if (item.codePoints().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("「" + item + "」不是有效的账号，每行填一个数字账号");
            }
            try {
                accounts.add(converted(item, Long.class));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("「" + item + "」不是有效的账号，每行填一个数字账号");
            }
        }
        return List.copyOf(accounts);
    }

    /**
     * 即时生效、但落地动作不在本类的配置项
     * <p>
     * 有几项<b>根本不经过设置页那条保存通道</b>：它们是列表，而设置页按设计不展示列表元素，
     * 改它们只能走各自的专门入口。落地动作因此也在那个入口里，本类的签名（一个键、一个字符串）
     * 也接不住一整份列表。
     * <p>
     * 🔴 <b>但「即时生效」这句话只有一张表。</b>本表与上面那张一起构成
     * {@link #supportedKeys()}——也就是「保存之后不需要重启的键」的全部。少了这张表的话，
     * 这几项要么被迫标成「重启后生效」（界面白让人重启一次，而它其实已经生效了），
     * 要么标成即时生效而无处对账（判据只能睁一只眼，从此谁标都行）。
     * <p>
     * 值是<b>谁去落地</b>，写清楚才对得上账：光有一份键名清单，等于给这几项签了免检。
     * <p>
     * 核心自有表为空。平台相关的项由 {@link RuntimeConfigurationApplierContributor#appliedElsewhere()} 申报。
     */
    private static final Map<String, String> APPLIED_ELSEWHERE = Map.of();

    /**
     * 保存过、但要等重启才生效的配置项
     * <p>
     * 记在进程里而不是浏览器里，「等到重启」这件事才是准的：重启之后进程换了一个，这份记录随之消失，
     * 界面上那条提示自然收起。记在浏览器里的话，换台机器打开就看不见，
     * 而重启之后它还挂着——那条提示会一直在，直到有人手动点掉。
     */
    private final Set<String> pendingRestart = Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * 启动那一刻配置文件按启动时那一路读出来的值（键到值）
     * <p>
     * 「改回启动时的原样就不再欠重启」要有一份可比的原样：重启读的就是这份文件，
     * 存回之后重启再读一遍读到的与启动时分毫不差，那次重启就可以不欠。
     * 🔴 比的是<b>读出来的值</b>，不是界面送回的字面：手写不带引号的 {@code 23:00}
     * 启动时读成整数，写回时加了引号就读成字符串——界面上的字面一模一样，重启后的值却变了。
     * 双引号里带转义的值界面上已还原、写回时按需加引号转义，重启读到的与界面上的是同一个值。
     * <p>
     * 快照只在构造时拍一次，运行中手改配置文件不进这份：改回手改的值重启仍会生效，
     * 提醒该留着，改回启动时的值才销账。头一回运行还没有配置文件时快照为空，
     * 销不了账也不挡保存，重启一次后快照就有了。
     */
    private final Map<String, Object> startupValues;

    /**
     * 保存之后再按启动时那一路读一遍文件用；没有时销不了账
     */
    private final ConfigurationFileService fileService;

    private final NovaCoreProperties properties;

    /**
     * 插件申报的即时生效项。核心自有表不在这里。
     */
    private final Map<String, Consumer<String>> contributedAppliers;
    private final Map<String, BatchApplier> contributedBatches;

    /**
     * 插件申报的「另有专门入口落地」项。核心自有表不在这里。
     */
    private final Map<String, String> contributedAppliedElsewhere;

    /**
     * 插件申报的取值校验。核心自有表不在这里。
     */
    private final Map<String, Function<String, String>> contributedValidators;

    /**
     * 插件申报的「存之前先问一句」。核心自有表不在这里。
     */
    private final Map<String, Function<String, String>> contributedConfirmations;

    /**
     * 事件时间线
     * <p>
     * 🔴 <b>只记改了哪一组，不记改成了什么。</b>时间线是逐行落在磁盘上的
     * {@code timeline/*.jsonl}，这里处理的值可能包含告警收件地址。
     * 把取值抄进去，等于给配置文件里那几项做了一份不设防的副本。
     * 「昨天谁动了推送那一组」这个问题，光靠组名就答得了。
     */
    private final TimelineWriter timeline;

    /**
     * 写回之后要按新值重排、重建的那几方，见 {@link SettingsAppliedListener}
     * <p>
     * 用到时才取：它们里头有的自己就依赖保存这条路上的东西，构造时取会绕成一个圈。
     */
    private Supplier<List<SettingsAppliedListener>> listeners = List::of;

    @Autowired
    public RuntimeConfigurationApplier(NovaCoreProperties properties,
                                       ObjectProvider<RuntimeConfigurationApplierContributor> contributors,
                                       ConfigurationFileService fileService, TimelineWriter timeline,
                                       ObjectProvider<StandbyBoundConfig> bound,
                                       ObjectProvider<SettingsAppliedListener> listeners) {
        this(properties, contributors.orderedStream().toList(), fileService, timeline,
                bound.getIfAvailable());
        this.listeners = () -> listeners.orderedStream().toList();
    }

    /**
     * 没拍启动快照的构造口：待重启的账只有键名可比，改回原样销不了账
     */
    public RuntimeConfigurationApplier(NovaCoreProperties properties,
                                       ObjectProvider<RuntimeConfigurationApplierContributor> contributors,
                                       TimelineWriter timeline) {
        this(properties, contributors.orderedStream().toList(), null, timeline, null);
    }

    RuntimeConfigurationApplier(NovaCoreProperties properties,
                                Collection<RuntimeConfigurationApplierContributor> contributors,
                                ConfigurationFileService fileService, TimelineWriter timeline) {
        this(properties, contributors, fileService, timeline, null);
    }

    RuntimeConfigurationApplier(NovaCoreProperties properties,
                                Collection<RuntimeConfigurationApplierContributor> contributors,
                                ConfigurationFileService fileService, TimelineWriter timeline,
                                StandbyBoundConfig bound) {
        this.properties = properties;
        this.fileService = fileService;
        Map<String, Object> snapshot = bound == null ? null : bound.values();
        this.startupValues = snapshot != null
                ? Map.copyOf(snapshot)
                : loaded(fileService, "启动时没能读下配置文件，改回原样的那笔待重启账销不掉");
        Contributed contributed = mergeContributions(contributors);
        this.contributedAppliers = contributed.appliers();
        this.contributedBatches = contributed.batches();
        this.contributedAppliedElsewhere = contributed.elsewhere();
        this.contributedValidators = contributed.validators();
        this.contributedConfirmations = contributed.confirmations();
        this.timeline = timeline;
    }

    private record Contributed(Map<String, Consumer<String>> appliers, Map<String, BatchApplier> batches,
                               Map<String, String> elsewhere,
                               Map<String, Function<String, String>> validators,
                               Map<String, Function<String, String>> confirmations) {
    }

    /**
     * 按启动时那一路把配置文件读成一份不可变的键值
     * <p>
     * 文件还不在（装好后一次也没保存过）或读不下来时读成空的：这份只是销账的依据，
     * 读不到就不销，不挡保存本身。
     * @param fileService 配置文件，没有时读成空的
     * @param failure 读不下来时日志里那句话
     */
    /**
     * 绑定那一刻记下的键值。没经过环境准备时，是构造时读到的那一份。
     * @return 键到值
     */
    Map<String, Object> boundAtStartup() {
        return startupValues;
    }

    private static Map<String, Object> loaded(ConfigurationFileService fileService, String failure) {
        if (fileService == null) {
            return Map.of();
        }
        try {
            return Map.copyOf(fileService.readAsLoaded());
        } catch (IOException e) {
            log.warn("{}: {}", failure, e.toString());
            return Map.of();
        }
    }

    private static Contributed mergeContributions(
            Collection<RuntimeConfigurationApplierContributor> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return new Contributed(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        Map<String, Consumer<String>> appliers = new LinkedHashMap<>();
        Map<String, BatchApplier> batches = new LinkedHashMap<>();
        Map<String, String> elsewhere = new LinkedHashMap<>();
        Map<String, Function<String, String>> validators = new LinkedHashMap<>();
        Map<String, Function<String, String>> confirmations = new LinkedHashMap<>();
        for (RuntimeConfigurationApplierContributor contributor : contributors) {
            if (contributor == null) {
                continue;
            }
            mergeAppliers(contributor.appliers(), appliers, batches, elsewhere);
            mergeElsewhere(contributor.appliedElsewhere(), appliers, batches, elsewhere);
            BatchApplier batch = contributor.batchApplier();
            if (batch != null) {
                for (String key : batch.keys()) {
                    rejectDuplicate(key, appliers, batches, elsewhere);
                    if (ConfigUiAuthService.isDedicatedAuthKey(key)) {
                        throw new IllegalStateException("配置项 " + key + " 不能经通用保存改，请走登录与安全专用口");
                    }
                    batches.put(key, batch);
                }
            }
            mergeValidators(contributor.valueValidators(), validators, "取值校验");
            mergeValidators(contributor.saveConfirmations(), confirmations, "保存前确认");
        }
        return new Contributed(Collections.unmodifiableMap(appliers), Collections.unmodifiableMap(batches),
                Collections.unmodifiableMap(elsewhere), Collections.unmodifiableMap(validators),
                Collections.unmodifiableMap(confirmations));
    }

    /**
     * 合并各插件的取值校验（保存前确认同一套规矩）
     * <p>
     * 同一条键被两方校验时抛 {@link IllegalStateException}：范围这种东西两方各说各的，
     * 存下来的值就等于谁也没校验过
     */
    private static void mergeValidators(Map<String, Function<String, String>> declared,
                                        Map<String, Function<String, String>> validators, String what) {
        if (declared == null) {
            return;
        }
        for (Map.Entry<String, Function<String, String>> entry : declared.entrySet()) {
            String key = entry.getKey();
            Function<String, String> validator = entry.getValue();
            if (key == null || validator == null) {
                continue;
            }
            if (validators.containsKey(key)) {
                throw new IllegalStateException("即时生效配置项 " + key + " 的" + what + "被写了两次");
            }
            validators.put(key, validator);
        }
    }

    private static void mergeAppliers(Map<String, Consumer<String>> declared,
                                      Map<String, Consumer<String>> appliers,
                                      Map<String, BatchApplier> batches,
                                      Map<String, String> elsewhere) {
        if (declared == null) {
            return;
        }
        for (Map.Entry<String, Consumer<String>> entry : declared.entrySet()) {
            String key = entry.getKey();
            Consumer<String> applier = entry.getValue();
            if (key == null || applier == null) {
                continue;
            }
            rejectDuplicate(key, appliers, batches, elsewhere);
            appliers.put(key, applier);
        }
    }

    private static void mergeElsewhere(Map<String, String> declared,
                                       Map<String, Consumer<String>> appliers,
                                       Map<String, BatchApplier> batches,
                                       Map<String, String> elsewhere) {
        if (declared == null) {
            return;
        }
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            String key = entry.getKey();
            String who = entry.getValue();
            if (key == null || who == null) {
                continue;
            }
            rejectDuplicate(key, appliers, batches, elsewhere);
            elsewhere.put(key, who);
        }
    }

    private static void rejectDuplicate(String key,
                                        Map<String, Consumer<String>> appliers,
                                        Map<String, BatchApplier> batches,
                                        Map<String, String> elsewhere) {
        if (APPLIERS.containsKey(key)
                || CHECKED.containsKey(key)
                || APPLIED_ELSEWHERE.containsKey(key)
                || appliers.containsKey(key)
                || batches.containsKey(key)
                || elsewhere.containsKey(key)) {
            throw new IllegalStateException("即时生效配置项 " + key + " 被写了两次");
        }
    }

    /**
     * 判据台架的构造口：要哪几个侧件按需给（包内可见：台架都在同包）
     * <p>
     * @param properties 配置对象
     * @return 构造器
     */
    static Bench bench(NovaCoreProperties properties) {
        return new Bench(properties);
    }

    /**
     * {@link #bench} 的构造器
     */
    static final class Bench {
        private final NovaCoreProperties properties;
        private Collection<RuntimeConfigurationApplierContributor> contributors = List.of();
        private ConfigurationFileService fileService;
        private TimelineWriter timeline = TimelineWriter.NONE;
        private List<SettingsAppliedListener> listeners = List.of();

        private Bench(NovaCoreProperties properties) {
            this.properties = properties;
        }

        /**
         * 带上写回之后要按新值重排、重建的那几方
         * @param listeners 组件
         * @return 本构造器
         */
        Bench listeners(SettingsAppliedListener... listeners) {
            this.listeners = List.of(listeners);
            return this;
        }

        /**
         * 带上插件申报的即时生效项
         * @param contributors 插件申报
         * @return 本构造器
         */
        Bench contributors(Collection<RuntimeConfigurationApplierContributor> contributors) {
            this.contributors = contributors == null ? List.of() : List.copyOf(contributors);
            return this;
        }

        /**
         * 带上配置文件：{@link #build()} 那一刻拍启动快照，改回原样销待重启账时再读一遍比
         * @param fileService 配置文件
         * @return 本构造器
         */
        Bench configFile(ConfigurationFileService fileService) {
            this.fileService = fileService;
            return this;
        }

        /**
         * 带上时间线写入口，缺省是不记
         * <p>
         * 缺省不记与别的侧件同法：大多数判据问的是「落没落下去」，
         * 那几条不该因为要记一条时间线而各自准备一个收集器。
         * @param timeline 时间线写入口
         * @return 本构造器
         */
        Bench timeline(TimelineWriter timeline) {
            this.timeline = timeline == null ? TimelineWriter.NONE : timeline;
            return this;
        }

        /**
         * @return 按给出的侧件装配好的实例
         */
        RuntimeConfigurationApplier build() {
            RuntimeConfigurationApplier applier = new RuntimeConfigurationApplier(properties, contributors, fileService,
                    timeline);
            List<SettingsAppliedListener> given = listeners;
            applier.listeners = () -> given;
            return applier;
        }
    }

    /**
     * 核心自有名单与贡献者申报合并后的即时生效键
     * <p>
     * 构建期那道尺在别的模块里，够不着判据台架的包内构造口，走这一条。
     * @param contributors 插件申报，空或 null 时即核心自有名单
     * @return 保存之后不需要重启的配置项名
     */
    public static Set<String> supportedKeys(Collection<RuntimeConfigurationApplierContributor> contributors) {
        // 这一支只把几张表的键名并起来，一个字也不往运行中的程序上落，因此没有可记的
        return new RuntimeConfigurationApplier(new NovaCoreProperties(), contributors, null, TimelineWriter.NONE)
                .supportedKeys();
    }

    /**
     * 名单里有哪些键
     * @return 保存之后不需要重启的配置项名，含 {@link #APPLIED_ELSEWHERE} 里那些
     */
    public Set<String> supportedKeys() {
        Set<String> keys = new LinkedHashSet<>(APPLIERS.keySet());
        keys.addAll(CHECKED.keySet());
        keys.addAll(APPLIED_ELSEWHERE.keySet());
        keys.addAll(contributedAppliers.keySet());
        keys.addAll(contributedBatches.keySet());
        keys.addAll(contributedAppliedElsewhere.keySet());
        return Collections.unmodifiableSet(keys);
    }

    /**
     * 问一遍插件：这批取值合不合各自那几项的规矩
     * <p>
     * 在写配置文件之前问。写进去再由出图那侧兜底的话，配置文件里躺着一个
     * 谁都不该照着办的值，而下一个人翻配置时没有任何提示说它出过界。
     * @param changes 待写入的配置项名到取值
     * @return 不能收的那些，一段人话一条；空表表示都能收。没申报校验的键一律放行
     */
    public List<String> validateValues(Map<String, String> changes) {
        List<String> problems = new ArrayList<>();
        if (changes == null || changes.isEmpty()) {
            return problems;
        }
        for (Map.Entry<String, String> change : changes.entrySet()) {
            CheckedSetting setting = CHECKED.get(change.getKey());
            if (setting == null || change.getValue() == null || CHECKED_ON_WRITE.contains(change.getKey())) {
                continue;
            }
            try {
                Object parsed = setting.read(change.getValue());
                if (ALLOW_IPS.equals(change.getKey())) {
                    rejectSelfLockout(parsed);
                }
            } catch (IllegalArgumentException e) {
                problems.add(change.getKey() + "（" + setting.label() + "）：" + e.getMessage());
            }
        }
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Function<String, String> validator = contributedValidators.get(change.getKey());
            if (validator == null) {
                continue;
            }
            String problem = validator.apply(change.getValue());
            if (problem != null) {
                problems.add(problem);
            }
        }
        return problems;
    }

    /**
     * 问一遍插件：这批取值里有没有存之前要先问一句的
     * <p>
     * 只问取值本身能收、但存下去会出使用者多半没想到的后果的那种（比如新名单把本机挡在外面）。
     * 取值读不懂的不在这里，那是 {@link #validateValues} 的事，整批拒绝、不问。删键（null）不问。
     * @param changes 待写入的配置项名到取值
     * @return 配置项名到要问的那句话，按传入顺序；空表表示不用问
     */
    public Map<String, String> confirmationsFor(Map<String, String> changes) {
        Map<String, String> questions = new LinkedHashMap<>();
        if (changes == null || changes.isEmpty()) {
            return questions;
        }
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Function<String, String> confirmation = contributedConfirmations.get(change.getKey());
            if (confirmation == null || change.getValue() == null) {
                continue;
            }
            String question = confirmation.apply(change.getValue());
            if (question != null) {
                questions.put(change.getKey(), question);
            }
        }
        return questions;
    }

    /**
     * 控制台来源 IP 名单的配置项名
     */
    static final String ALLOW_IPS = "novabot.core.config-ui.allow-ips";

    /**
     * 新名单会不会把正在保存的这个人挡在门外
     * <p>
     * 名单从下一个请求起就按新的放行，挡住了自己的话，只能去服务器上改文件再重启。
     * 判法与安全过滤器是同一段代码（{@link ConfigUiSecurityFilter#admits}）：来源地址取自同一个请求、
     * 同一个方法，开了反向代理那一套（{@code server.forward-headers-strategy}）时两边看到的都是改写之后的那个。
     * 不经网页保存的调用方（没有请求）不在此列。
     * @param parsed 读懂之后的名单
     */
    private static void rejectSelfLockout(Object parsed) {
        jakarta.servlet.http.HttpServletRequest request = ConfigUiAccess.request();
        if (request == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        IpMatcher matcher = new IpMatcher((List<String>) parsed);
        if (!ConfigUiSecurityFilter.admits(matcher, request)) {
            throw new IllegalArgumentException("这样保存后你会被挡在控制台外面：你此刻的来源地址 "
                    + ConfigUiSecurityFilter.clientAddress(request) + " 不在新名单里，名单未改");
        }
    }

    /**
     * 写进配置文件的那一份：带校验的项换成与写回内存同一个值的字面
     * <p>
     * 不这样做的话，内存里是去掉首尾空白的值、文件里还留着空白，重启后按文件读到的不是保存时生效的那个。
     * 读不懂的、删键（{@code null}）的、不在带校验名单里的，原样留着。
     * @param changes 待写入的配置项名到取值
     * @return 新的一份，顺序不变
     */
    public static Map<String, String> forFile(Map<String, String> changes) {
        Map<String, String> result = new LinkedHashMap<>(changes);
        result.replaceAll((key, value) -> {
            CheckedSetting setting = CHECKED.get(key);
            return setting == null ? value : setting.text(value);
        });
        return result;
    }

    /**
     * 把一批已写入配置文件的改动尽量落到运行中的程序上
     * <p>
     * 落不下的（不在名单里，或值的形式不对）一律计入待重启记在本实例上，改对了的键当场划掉，
     * 存回启动时配置文件里原样的同样当场划掉：那次重启已经没有可生效的改动。
     * @param changes 已写入的配置项名到取值
     * @return 其中要等重启才生效的那些，按传入顺序
     */
    public List<String> applyAndTrack(Map<String, String> changes) {
        List<String> restartRequired = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        Map<String, Object> savedValues = null;
        Set<String> failedBatchKeys = applyBatches(changes);
        Set<String> failedCheckedKeys = notifyListeners(changes, applyChecked(changes));

        for (Map.Entry<String, String> change : changes.entrySet()) {
            String key = change.getKey();
            if (CHECKED.containsKey(key)) {
                if (failedCheckedKeys.contains(key)) restartRequired.add(key);
                else recordApplied(key, applied);
                continue;
            }
            if (contributedBatches.containsKey(key)) {
                if (failedBatchKeys.contains(key)) restartRequired.add(key);
                else recordApplied(key, applied);
                continue;
            }
            Runnable applier = resolve(key, change.getValue() == null ? "" : change.getValue());
            if (applier == null) {
                if (savedValues == null) {
                    savedValues = startupValues.isEmpty() ? Map.of()
                            : loaded(fileService, "保存后没能再读一遍配置文件，改回原样的那笔待重启账先不销");
                }
                Object atStartup = startupValues.get(change.getKey());
                if (atStartup != null && atStartup.equals(savedValues.get(change.getKey()))) {
                    // 存下去的与启动时按同一路读出来的值分毫不差（类型也一样）：重启再读一遍什么也不变，那次重启可以不欠。
                    // 比不准的——启动时没这个键、读不下来、一边整数一边字符串——一律留着提醒
                    pendingRestart.remove(change.getKey());
                } else {
                    restartRequired.add(change.getKey());
                }
                continue;
            }

            try {
                applier.run();
                recordApplied(key, applied);
            } catch (RuntimeException e) {
                // 值的形式不对时不当作已生效：界面写「已生效」而实际没变，比多重启一次糟得多。
                // 机密项不写取值：这一行进日志文件，口令写进去就等于多了一份明文
                log.warn("配置项 {} 的取值 {} 无法即时生效, 已按需重启处理: {}", change.getKey(),
                        SensitiveFields.isSensitive(change.getKey(), null) ? SensitiveFields.MASK : change.getValue(),
                        e.toString());
                restartRequired.add(change.getKey());
            }
        }

        // 两种结局各记一条：合成一条「保存了」的话，「明明改了却没生效」
        // 与「改了、等重启」在日志页上长得一样，而后者是要人去点重启的
        record(TimelineEventType.SETTINGS_APPLIED, "已即时生效", applied);
        record(TimelineEventType.SETTINGS_RESTART_PENDING, "要等重启才生效", restartRequired);

        pendingRestart.addAll(restartRequired);
        return restartRequired;
    }

    private Set<String> applyBatches(Map<String, String> changes) {
        Map<BatchApplier, Map<String, String>> batches = new LinkedHashMap<>();
        changes.forEach((key, value) -> {
            BatchApplier batch = contributedBatches.get(key);
            if (batch != null) {
                batches.computeIfAbsent(batch, ignored -> new LinkedHashMap<>()).put(key, value);
            }
        });
        Set<String> failed = new LinkedHashSet<>();
        batches.forEach((batch, values) -> {
            try {
                batch.apply().accept(Collections.unmodifiableMap(values));
            } catch (RuntimeException error) {
                failed.addAll(values.keySet());
                log.warn("配置项 {} 无法即时生效，已按需重启处理: {}", values.keySet(), error.toString());
            }
        });
        return failed;
    }

    /**
     * 把这批里带校验的核心项一起写回：先全部读懂，有一项读不懂就一项也不写
     * <p>
     * 保存口在写文件前已经校验过，走到这里还读不懂的只会来自别的调用方；那时整批按待重启处理，
     * 不留下「一半新一半旧」的运行状态。
     * @param changes 已写入的配置项名到取值
     * @return 没能写回的那些键
     */
    private Set<String> applyChecked(Map<String, String> changes) {
        Map<CheckedSetting, Object> parsed = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        try {
            changes.forEach((key, value) -> {
                CheckedSetting setting = CHECKED.get(key);
                if (setting != null) {
                    keys.add(key);
                    parsed.put(setting, setting.read(value));
                }
            });
        } catch (IllegalArgumentException e) {
            log.warn("配置项 {} 里有取值读不懂, 这一批都按需重启处理: {}", keys, e.getMessage());
            Set<String> all = new LinkedHashSet<>();
            changes.keySet().stream().filter(CHECKED::containsKey).forEach(all::add);
            return all;
        }
        if (parsed.isEmpty()) {
            return Set.of();
        }
        // Webhook 那一组的读方在同一把锁下取值，见 NovaCoreProperties.Alert#webhookFormat
        synchronized (properties.getAlert()) {
            parsed.forEach((setting, value) -> setting.write().accept(properties, value));
        }
        return Set.of();
    }

    /**
     * 叫写回之后要按新值重排、重建的那几方
     * <p>
     * 只叫写回成功了的项：没写回的已经记成待重启，再叫一遍只会让运行中的东西与配置对象对不上。
     * 一方失败只把它那几项记成待重启，不连累别的；日志只写键名，不写取值——发件密码就在其中。
     * @param changes 已写入的配置项名到取值
     * @param failed 写回时就没成的那些键
     * @return 写回或叫醒时没成的那些键
     */
    private Set<String> notifyListeners(Map<String, String> changes, Set<String> failed) {
        Set<String> result = new LinkedHashSet<>(failed);
        for (SettingsAppliedListener listener : listeners.get()) {
            Map<String, String> mine = new LinkedHashMap<>();
            for (String key : listener.keys()) {
                CheckedSetting setting = CHECKED.get(key);
                if (setting != null && changes.containsKey(key) && !failed.contains(key)) {
                    mine.put(key, setting.text(changes.get(key)));
                }
            }
            if (mine.isEmpty()) {
                continue;
            }
            try {
                listener.applied(Collections.unmodifiableMap(mine));
            } catch (RuntimeException e) {
                result.addAll(mine.keySet());
                log.warn("配置项 {} 已写回配置，但没能落到运行中的程序上，按需重启处理: {}",
                        mine.keySet(), e.getClass().getName());
            }
        }
        return result;
    }

    private void recordApplied(String key, List<String> applied) {
        log.info("配置项 {} 已即时生效", key);
        pendingRestart.remove(key);
        applied.add(key);
    }

    /**
     * 把一批键记成一条时间线，<b>只写组名与条数</b>
     * <p>
     * 🔴 取值一个字都不进来，理由见 {@link #timeline}。连完整键名也不写：
     * 组名已经足以定位改动。
     * @param type 记成哪一类
     * @param what 一句人话里的动词部分
     * @param keys 本次的配置项名，空表时不记
     */
    private void record(TimelineEventType type, String what, List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }

        Set<String> groups = new LinkedHashSet<>();
        for (String key : keys) {
            groups.add(groupOf(key));
        }

        timeline.record(TimelineEvent.of(type, TimelineEvent.Level.INFO)
                .text("改了" + String.join("、", groups) + " " + keys.size() + " 项，" + what)
                .detail("groups", String.join(",", groups))
                .detail("count", String.valueOf(keys.size()))
                .build());
    }

    /**
     * 配置项名属于哪一组：去掉最后一段
     * <p>
     * 最后一段正是「改的是哪一项」，而组名答的是「动的是哪一摊」。
     * 没有点号的键（不该出现，但外部传进来的东西不该让这里抛）整段当组名。
     */
    private static String groupOf(String key) {
        int cut = key.lastIndexOf('.');
        return cut <= 0 ? key : key.substring(0, cut);
    }

    /**
     * 找出把这一项落到运行中的程序上的那个动作
     * <p>
     * 登录口令、二次验证与启动令牌通道不在这里落地：通用保存若直接改，一枚已登录会话就能换掉门。
     * 那几项走专用口。出现在本方法里时按需重启处理——不静静跳过，也不动手改门。
     * @param name 配置项名
     * @param value 取值
     * @return 落地动作，落不下时为 null
     */
    private Runnable resolve(String name, String value) {
        if (ConfigUiAuthService.isDedicatedAuthKey(name)) {
            log.warn("配置项 {} 不能经通用保存改，请走登录与安全专用口", name);
            return null;
        }

        BiConsumer<NovaCoreProperties, String> applier = APPLIERS.get(name);
        if (applier != null) {
            return () -> applier.accept(properties, value);
        }

        Consumer<String> contributed = contributedAppliers.get(name);
        if (contributed != null) {
            return () -> contributed.accept(value);
        }

        return null;
    }

    /**
     * 至今保存过、仍在等重启的配置项
     * @return 配置项名
     */
    public List<String> getPendingRestart() {
        synchronized (pendingRestart) {
            return List.copyOf(pendingRestart);
        }
    }
}
