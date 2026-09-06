package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ConfigEffect;
import com.starlwr.bot.core.config.ConfigLevel;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.page.ConsolePageProvider;
import com.starlwr.bot.core.config.ui.page.ConsolePages;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.EventStreamToken;
import com.starlwr.bot.core.service.EventStreamTokenService;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.datasource.DataSourceServiceRegistry;
import com.starlwr.bot.core.datasource.MonitorLimit;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.StreamerReference;
import com.starlwr.bot.core.service.DataSourceService;
import com.starlwr.bot.core.service.DataSourceService.StreamerWithFans;
import com.starlwr.bot.core.account.AccountLoginProvider;
import com.starlwr.bot.core.account.BotConnectionTester;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.sender.PushGate;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.PushTemplateDefaults;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.timeline.TimelineEventType;
import com.starlwr.bot.core.timeline.TimelineStore;
import com.starlwr.bot.core.util.QrCodeUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置界面接口
 * <p>
 * 界面所需的字段表来自编译期生成的配置元数据，当前值与保存动作直接作用于 application.yml，
 * 因此界面与配置文件始终一致：不存在界面上有而配置文件里没有的字段，也不存在改了界面而文件未变的情况。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH)
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiController {
    /**
     * 配置界面根路径
     */
    public static final String BASE_PATH = "/config";

    /**
     * 机器人连接信息在配置文件里所处的列表
     * <p>
     * 元素内部有哪几个键不写在这里——那由适配器答（见 {@link BotConnectionTester.Applied}）。
     * 这里只记得住「它是一份列表、在配置树的哪个位置」，落盘那一侧要的正是这一句。
     */
    static final String BOT_CONNECTION_LIST = "starbot.adapter.onebot.senders";

    /**
     * 允许的静态资源文件名
     * <p>
     * 只接受字母数字、下划线、连字符与一个扩展名。**不含点号序列**，
     * 因此 {@code ../} 这类路径穿越根本匹配不上——把请求路径映射到类路径资源时，
     * 校验必须写成白名单，写成黑名单迟早会漏。
     */
    private static final Pattern ASSET_NAME = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9]+");

    /**
     * 静态资源的扩展名到内容类型的映射，未列出的扩展名一律不提供
     */
    private static final Map<String, MediaType> ASSET_TYPES = Map.of(
            "css", MediaType.valueOf("text/css;charset=UTF-8"),
            "js", MediaType.valueOf("text/javascript;charset=UTF-8"),
            "svg", MediaType.valueOf("image/svg+xml;charset=UTF-8"));

    /**
     * 界面上展示的二维码边长，单位：像素
     * <p>
     * 终端里打印时用的是 62，那是为了让每个码元恰好占一个字符位；网页上若沿用该值，
     * 每个码元只有一个像素，放大后模糊到扫不出来。此处按实际显示尺寸取值。
     */
    private static final int QR_CODE_IMAGE_SIZE = 320;

    /**
     * 推送记录的时间格式
     */
    private static final DateTimeFormatter HISTORY_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * 纯数字：可能是 uid，也可能是直播间号
     * <p>
     * 链接由各平台自己认（见 {@link DataSourceService#parseStreamerLink}），只有这一种留在这里：
     * 一串纯数字不是谁家的链接，各平台一样对待。
     */
    private static final Pattern PLAIN_UID = Pattern.compile("^(\\d{1,19})$");

    private final ConfigurationMetadataService metadataService;

    private final ConfigurationFileService fileService;

    private final StarBotCoreProperties properties;

    private final AbstractDataSource dataSource;

    /**
     * 各模块注册的健康探针
     * <p>
     * 以 ObjectProvider 而非直接注入 List 获取：探针可能来自插件，而插件的 Bean 定义由
     * BeanDefinitionRegistryPostProcessor 注册，用延迟解析可避免依赖注册与注入的先后顺序。
     */
    private final ObjectProvider<HealthProbe> healthProbes;

    private final ConfigurationValidator validator;

    private final StarBotSenderService senderService;

    private final StarBotMessageSender messageSender;

    private final ObjectProvider<AccountLoginProvider> loginProviders;

    private final PushActivityRecorder activityRecorder;

    private final StarBotEventHandlerService handlerService;

    private final DataSourceServiceRegistry dataSourceServiceRegistry;

    private final ConfigurationLevelResolver levelResolver;

    private final ConfigurationEffectResolver effectResolver;

    private final ConfigurationDangerResolver dangerResolver;

    /**
     * 保存之后把能即时生效的那几项落到运行中的程序上，并记着还欠一次重启的是哪些
     */
    private final RuntimeConfigurationApplier runtimeApplier;

    /**
     * 事件流只读口令的签发与吊销
     */
    private final EventStreamTokenService eventStreamTokens;

    private final ObjectProvider<BotConnectionTester> connectionTesters;

    /**
     * 各插件注册的控制台页面
     * <p>
     * 与登录能力同形，用 ObjectProvider 取：页面来自插件，而插件的 Bean 定义由
     * BeanDefinitionRegistryPostProcessor 注册，延迟解析才不受注册与注入的先后顺序影响。
     */
    private final ObjectProvider<ConsolePageProvider> pageProviders;

    /**
     * 构建信息，版本号从这里来
     * <p>
     * 用 ObjectProvider 取：这个 Bean 由 build-info 生成的属性文件撑着，
     * 从源码直接跑时它不存在。直接注入的话，控制台会在「没打过包」的环境里整个起不来——
     * 而那正是开发时最常见的跑法。
     */
    private final ObjectProvider<BuildProperties> buildProperties;

    /**
     * 推送闸门。首页要显示「此刻在不在静音时段」，判据只有它那一份
     */
    private final PushGate pushGate;

    /**
     * 直播数据。首页「现在」那一栏要问「谁在播」，累计存储开没开也在它身上
     */
    private final LiveDataService liveDataService;

    /**
     * 事件时间线。首页「今日推送 N 条、失败 M 条」按日历上的今天数，从这里来
     */
    private final TimelineStore timeline;

    /**
     * 登录能力。首页待办要答「这台控制台上没上锁」
     */
    private final ConfigUiAuthService authService;

    /**
     * 新版检查。侧栏药丸与首页软待办读同一份结果，「这版先不提醒」也由它记账
     */
    private final UpdateCheckService updateCheck;

    /**
     * 这台机器改过的默认模板。模板编辑器的「默认模板」那一页读写的就是它
     */
    private final PushTemplateDefaults templateDefaults;

    /**
     * 推送配置备份用的钟。测试换成固定钟，免得同一秒内连存两份撞名覆盖。
     */
    Clock backupClock = Clock.systemDefaultZone();

    @Autowired
    public ConfigUiController(ConfigurationMetadataService metadataService,
                              ConfigurationFileService fileService,
                              StarBotCoreProperties properties,
                              AbstractDataSource dataSource,
                              ObjectProvider<HealthProbe> healthProbes,
                              ConfigurationValidator validator,
                              StarBotSenderService senderService,
                              StarBotMessageSender messageSender,
                              ObjectProvider<AccountLoginProvider> loginProviders,
                              PushActivityRecorder activityRecorder,
                              StarBotEventHandlerService handlerService,
                              DataSourceServiceRegistry dataSourceServiceRegistry,
                              ConfigurationLevelResolver levelResolver,
                              ConfigurationEffectResolver effectResolver,
                              ConfigurationDangerResolver dangerResolver,
                              RuntimeConfigurationApplier runtimeApplier,
                              ObjectProvider<BotConnectionTester> connectionTesters,
                              ObjectProvider<ConsolePageProvider> pageProviders,
                              EventStreamTokenService eventStreamTokens,
                              ObjectProvider<BuildProperties> buildProperties,
                              PushGate pushGate,
                              LiveDataService liveDataService,
                              TimelineStore timeline,
                              ConfigUiAuthService authService,
                              PushTemplateDefaults templateDefaults,
                              UpdateCheckService updateCheck) {
        this.templateDefaults = templateDefaults;
        this.pushGate = pushGate;
        this.liveDataService = liveDataService;
        this.timeline = timeline;
        this.authService = authService;
        this.updateCheck = updateCheck;
        this.effectResolver = effectResolver;
        this.dangerResolver = dangerResolver;
        this.runtimeApplier = runtimeApplier;
        this.buildProperties = buildProperties;
        this.eventStreamTokens = eventStreamTokens;
        this.pageProviders = pageProviders;
        this.levelResolver = levelResolver;
        this.connectionTesters = connectionTesters;
        this.activityRecorder = activityRecorder;
        this.handlerService = handlerService;
        this.dataSourceServiceRegistry = dataSourceServiceRegistry;
        this.metadataService = metadataService;
        this.fileService = fileService;
        this.properties = properties;
        this.dataSource = dataSource;
        this.healthProbes = healthProbes;
        this.validator = validator;
        this.senderService = senderService;
        this.messageSender = messageSender;
        this.loginProviders = loginProviders;
    }

    /**
     * 配置界面页面
     * @return 页面内容
     * @throws IOException 读取页面资源失败时抛出
     */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() throws IOException {
        try (var stream = new ClassPathResource("config-ui/index.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                    .body(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 配置界面的静态资源
     * <p>
     * 界面拆成多个文件之后需要这个出口。它与页面走同一个安全过滤器：
     * 地址栏里带过一次令牌后过滤器会写下 Cookie，浏览器取这些子资源时自动携带，
     * 因此不需要在每个资源地址上再挂令牌。
     * <p>
     * <b>文件名必须严格校验。</b>这个方法把请求路径直接映射到类路径资源，
     * 放任 {@code ../} 进来等于把整个 jar 的内容开放出去。此处只接受
     * 「字母数字、下划线、连字符 + 已知扩展名」，不做任何路径拼接以外的解释。
     * @param name 资源文件名
     * @return 资源内容，不存在或文件名非法时返回 404
     */
    @GetMapping("/assets/{name}")
    public ResponseEntity<byte[]> asset(@PathVariable String name) {
        if (!ASSET_NAME.matcher(name).matches()) {
            log.warn("配置界面拒绝了非法的静态资源名: {}", name);
            return ResponseEntity.notFound().build();
        }

        MediaType type = ASSET_TYPES.get(name.substring(name.lastIndexOf('.') + 1));
        if (type == null) {
            return ResponseEntity.notFound().build();
        }

        ClassPathResource resource = new ClassPathResource("config-ui/" + name);
        if (!resource.exists()) {
            return pageScript(name, type);
        }

        try (var stream = resource.getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(type)
                    // 内容随版本走，升级后必须立刻生效，因此不缓存
                    .cacheControl(CacheControl.noCache())
                    .body(stream.readAllBytes());
        } catch (IOException e) {
            log.error("读取配置界面静态资源 {} 失败", name, e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 插件带来的页面脚本
     * <p>
     * 与核心自己的静态资源走同一个地址前缀，因此插件页里的 {@code import './core.js'}
     * 解析出来仍是核心那一份——<b>换个前缀就得让每个插件去拼绝对路径</b>，
     * 而那种路径一旦写错，表现是页面某一块静默地不出现。
     * <p>
     * 只按<b>已登记的文件名</b>精确匹配，取资源用的是该插件自己的类加载器：插件在独立的
     * 类加载器里，核心这一侧看不见它 jar 里的东西。名字不匹配就是 404，
     * 因此这条路只开放插件自报的那几个文件，jar 里的其余内容一概取不到。
     * @param name 脚本文件名
     * @param type 内容类型
     * @return 脚本内容，未登记或读取失败时返回 404
     */
    private ResponseEntity<byte[]> pageScript(String name, MediaType type) {
        Optional<ConsolePageProvider> page = ConsolePages.byScript(pageProviders.orderedStream().toList(), name);
        if (page.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try (InputStream stream = page.get().getClass().getClassLoader()
                .getResourceAsStream(ConsolePages.SCRIPT_ROOT + name)) {
            if (stream == null) {
                log.error("控制台页面 {} 登记的脚本 {} 不在插件资源里", page.get().id(), name);
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(type)
                    .cacheControl(CacheControl.noCache())
                    .body(stream.readAllBytes());
        } catch (IOException e) {
            log.error("读取控制台页面脚本 {} 失败", name, e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 插件注册的控制台页面
     * <p>
     * 界面据此长出页签与页面容器，再按 script 去取各自的脚本。核心的界面文件里因此
     * 一个平台的名字也没有：装了哪些平台，是运行时才知道的事。
     * <p>
     * {@code slot} 是这一页挂在哪儿：连接页上的一张卡，还是设置页「高级」下的一张子页。
     * 由插件自己申报——核心不认识任何一个具体平台，也就无从判断某一页该摆在哪一处。
     * @return 页面清单
     */
    @GetMapping("/api/pages")
    public JSONObject pages() {
        JSONObject result = new JSONObject();
        result.put("success", true);

        JSONArray pages = new JSONArray();
        for (ConsolePageProvider page : ConsolePages.valid(pageProviders.orderedStream().toList())) {
            JSONObject item = new JSONObject();
            item.put("id", page.id());
            item.put("displayName", page.displayName());
            item.put("script", page.script());
            item.put("order", page.order());
            // 枚举名一律小写，与界面上那两处落点一一对应；直接吐枚举名会把 Java 的书写习惯
            // 泄进接口，换个实现语言就得跟着改
            item.put("slot", page.slot().name().toLowerCase(Locale.ROOT));
            pages.add(item);
        }

        result.put("pages", pages);
        return result;
    }

    /**
     * 已注册数据源服务的直播平台
     * <p>
     * 添加主播时要给出平台名，而能查得到主播信息的平台正是这些。界面此前把平台名写死成
     * 一个具体平台，于是<b>没装那个插件时按钮照样点得动</b>，点完才在服务端被回一句
     * 「没有可用的数据源服务」；装了第二个平台时，界面上又没有地方选。
     * @return 平台名清单
     */
    @GetMapping("/api/platforms")
    public JSONObject platforms() {
        JSONObject result = new JSONObject();
        result.put("success", true);

        JSONArray platforms = new JSONArray();
        platforms.addAll(dataSourceServiceRegistry.platforms());

        result.put("platforms", platforms);
        return result;
    }

    /**
     * 配置项字段表
     * <p>
     * 字段来自各模块编译期生成的配置元数据，包含类型、默认值与取自 Javadoc 的中文说明。
     * <p>
     * 分组不再按配置键的前缀自动切（那样切出来的是<b>程序的结构</b>，二十余组，
     * 使用者要办一件事得先猜它归哪个类管），而是按 {@link ConfigurationGroups} 那张表
     * 落到「要办的事」上：常用六组在前，工程用的两组标了 {@code advanced}、由界面折到页底。
     * 一条前缀也匹配不上的配置项<b>不会被塞进任何一组</b>——它的 {@code group} 为空，
     * 构建期那道判据会点名，而不是让它安静地待在一个「其他」里。
     * @return 按分组组织的字段表
     */
    @GetMapping("/api/schema")
    public JSONObject schema() {
        Map<String, ConfigLevel.Level> levels = levelResolver.getLevels();
        Map<String, ConfigEffect.Effect> effects = effectResolver.getEffects();
        Map<String, ConfigurationDangerResolver.Danger> dangers = dangerResolver.getDangers();

        // 组是闭集，先按组开好桶再往里放：这样八个组的先后由那张表定，
        // 而不是由「哪一组的第一个配置项先出现」定
        Map<ConfigurationGroups.Group, JSONArray> buckets = new LinkedHashMap<>();
        ConfigurationGroups.all().forEach(group -> buckets.put(group, new JSONArray()));
        JSONArray orphans = new JSONArray();

        for (ConfigurationMetadataService.ConfigurationField field : metadataService.getFields()) {
            ConfigurationGroups.Group group = ConfigurationGroups.groupOf(field.name());

            JSONObject item = new JSONObject();
            item.put("name", field.name());
            item.put("label", field.name().substring(field.name().lastIndexOf('.') + 1));
            item.put("widget", field.widget());
            item.put("description", field.description());
            item.put("defaultValue", field.defaultValue());
            // 未标注的一律按高级处理：新增配置项默认收进高级区，避免常用区随时间不断膨胀
            item.put("level", levels.getOrDefault(field.name(), ConfigLevel.Level.ADVANCED).name());
            // 生效时机没有默认值可取。这里回 null 而不是补一个「重启生效」：
            // 补上之后这一格就再也不会是空的，「没人标过」这件事在接口上永远看不出来。
            // 界面拿到 null 时按需重启显示，那是显示上的兜底，不是把答案编出来
            ConfigEffect.Effect effect = effects.get(field.name());
            item.put("effect", effect == null ? null : effect.name());
            item.put("sensitive", SensitiveFields.isSensitive(field.name(), field.type()));
            // 归不了组的同样带这一栏，值为 null。省掉它的话，界面分不出「没归组」与「后端是旧版」
            item.put("group", group == null ? null : group.id());

            // 「改到这一档要先问一句」跟着配置项自己走，界面上没有一张写死的危险项清单：
            // 那张清单只能由核心来写，而其中有些项属于插件——核心不该认识它们
            ConfigurationDangerResolver.Danger danger = dangers.get(field.name());
            if (danger != null) {
                JSONObject warning = new JSONObject();
                warning.put("value", danger.value());
                warning.put("title", danger.title());
                warning.put("consequence", danger.consequence());
                item.put("danger", warning);
            }

            if (group == null) {
                orphans.add(item);
            } else {
                item.put("order", buckets.get(group).size());
                buckets.get(group).add(item);
            }
        }

        JSONArray groups = new JSONArray();
        buckets.forEach((group, items) -> {
            JSONObject node = new JSONObject();
            node.put("group", group.id());
            node.put("title", group.title());
            node.put("description", group.description());
            node.put("advanced", group.advanced());
            node.put("order", ConfigurationGroups.orderOf(group));
            // 「这一组改了都要重启」是现算的，不是表上写死的一格：某一项改成即时生效之后，
            // 组标题上那枚牌子会自己消失，而写死的那一格只会继续说着改之前的事
            node.put("allRestart", !items.isEmpty() && items.stream()
                    .noneMatch(item -> ConfigEffect.Effect.IMMEDIATE.name()
                            .equals(((JSONObject) item).getString("effect"))));
            node.put("fields", items);
            groups.add(node);
        });

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("groups", groups);
        // 归不了组的单列一栏而不是并进某一组：并进去之后界面照样显示，
        // 于是「这一项还没人给它安排位置」这件事在跑起来的程序上再也看不见
        result.put("ungrouped", orphans);
        return result;
    }

    /**
     * 当前配置值，取自 application.yml
     * <p>
     * 改过名的配置键要按<b>程序实际生效的那个值</b>回给界面，而不是按现行键在文件里查得到什么：
     * 只写旧位置的既有部署，现行键一个也查不到，界面就会显示默认值，而程序正按旧位置的值在跑
     * （见 {@link ConfigurationKeyAliases}）。落回旧位置的项另附一张来源表，界面据此标出来。
     * @return 键值映射，附落回旧位置的项及其来源
     */
    @GetMapping("/api/values")
    public JSONObject values() {
        JSONObject result = new JSONObject();

        try {
            result.put("success", true);
            Map<String, String> values = fileService.read();
            String backupKeepKey = "starbot.core.config-ui.backup-keep";
            if (values.containsKey(backupKeepKey)) {
                // 文件里可能写着越界值，程序按 1–100 生效。界面要显示生效值，
                // 不然看起来能留 500 份，实际只留 100。
                values.put(backupKeepKey, Integer.toString(properties.getConfigUi().getBackupKeep()));
            }
            // 先补旧位置再遮机密：补进来的项同样可能是机密，顺序反了就会漏出去
            result.put("legacy", ConfigurationKeyAliases.resolve(values));
            // 口令、令牌与密钥不出这道门：面板可能在直播画面里被打开。
            // 带上类型表，开关才不会因为名字里有 token 被遮成占位值——遮了它界面上就恒显「已关闭」
            Map<String, String> types = metadataService.getKnownTypes();
            result.put("values", SensitiveFields.mask(values, name -> typeOf(types, name)));
        } catch (IOException e) {
            log.error("读取配置文件失败", e);
            result.put("success", false);
            result.put("message", "读取配置文件失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 查一个配置项的 Java 类型
     * <p>
     * 写在旧位置的那一行按它对应的现行键去查：元数据里只有现行键，而两者是同一项配置
     * （见 {@link ConfigurationKeyAliases}）。查不到就返回 {@code null}，由调用方自己决定怎么办——
     * 机密遮蔽在类型未知时按名字判，也就是照旧遮住，往安全的方向失败。
     * @param types 元数据里的类型表
     * @param name 配置项完整路径
     * @return 类型全限定名，未知时为 {@code null}
     */
    private static String typeOf(Map<String, String> types, String name) {
        if (types == null) {
            return null;
        }

        String type = types.get(name);
        return type != null ? type : types.get(ConfigurationKeyAliases.currentName(name));
    }

    /**
     * 保存配置值到 application.yml
     * <p>
     * 界面上的字段名取自元数据，元数据里只有现行键，因此<b>写侧只会写现行键</b>：
     * 一个原本写在旧位置的配置项，被保存一次就等于迁到了新位置。
     * <b>旧位置那几行不删也不改</b>——删是替使用者改他自己的配置文件，理由见
     * {@link ConfigurationKeyAliases}。程序两套键都认，多留几行只多一条启动提醒。
     * <p>
     * 写完之后还要多做一件事：把能即时生效的那几项落到运行中的程序上
     * （见 {@link RuntimeConfigurationApplier}），并如实告诉界面剩下哪几项还欠一次重启。
     * <b>此前这里一律回「重启后生效」</b>，于是「暂停推送」这种当场就管用的项也被说成要重启，
     * 有人为此重启了整个程序——而重启会把正在采集的场次打断。
     * @param body 待保存的键值
     * @return 保存结果，含改动项数、其中需重启的项数与它们的键名
     */
    @PostMapping("/api/values")
    public JSONObject save(@RequestBody Map<String, String> body) {
        JSONObject result = new JSONObject();

        // 界面拿到的机密项是占位值，原样送回来的就是没改过的。不剔除的话，
        // 改了别的字段一起保存就会把占位值写进配置，口令、令牌与密钥当场全部失效
        Map<String, String> changes = new LinkedHashMap<>(body);
        Map<String, String> types = metadataService.getKnownTypes();
        SensitiveFields.dropUnchanged(changes, name -> typeOf(types, name));

        if (ConfigUiAuthService.containsDedicatedAuthKey(changes.keySet())) {
            result.put("success", false);
            result.put("message", "登录口令和二次验证请到「登录与安全」里改；「忘记口令」的启动令牌通道这里也改不了——那个页面关得了、开不了，要开须改配置文件再重启");
            return result;
        }

        try {
            List<String> changedKeys = fileService.write(changes);

            // 只对真正落盘的那几个键动运行中的配置：送上来但值没变的项不该触发任何副作用
            Map<String, String> applied = new LinkedHashMap<>();
            changedKeys.forEach(key -> applied.put(key, changes.get(key)));
            List<String> restartRequired = runtimeApplier.applyAndTrack(applied);

            int changed = changedKeys.size();
            int restart = restartRequired.size();

            result.put("success", true);
            result.put("changed", changed);
            result.put("restartRequired", restartRequired);
            result.put("restartPending", runtimeApplier.getPendingRestart());
            result.put("message", saveMessage(changed, restart));
        } catch (IOException e) {
            log.error("保存配置文件失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 保存之后那句话
     * <p>
     * 一项都不用重启时写「已生效」而不是「其中 0 项需重启」：后者要人先读懂句式再算一遍才知道
     * 「不用管」，而这是最常见的那种情形。
     * @param changed 实际保存的项数
     * @param restart 其中需要重启才生效的项数
     * @return 提示文案
     */
    private static String saveMessage(int changed, int restart) {
        if (changed == 0) {
            return "没有需要保存的改动";
        }
        if (restart == 0) {
            return "已保存 " + changed + " 项，已生效";
        }
        return "已保存 " + changed + " 项，其中 " + restart + " 项需重启";
    }

    /*
     * 读写配置文件原文的那一对端点曾经在这里，5.1 撤掉了（确切路径见 CHANGELOG 与仓库历史）。
     *
     * 撤掉的理由是它的失败方向：控制台上直接改整份 application.yml，改坏了程序下次起不来，
     * 而配置界面随之一同挂掉——远程部署时等于把自己锁在门外。表单逐项改与安全模式两条路
     * 都不会走到这一步：前者只动被改的那几行，后者在程序起不来时才接管端口。
     *
     * 现在控制台只在设置页底部显示配置文件的路径，要直接改文件请到服务器上改。
     * 写坏之后的正门是 SafeModeServer，别在这里重新开一个。
     */

    /**
     * 查询当前已配置的机器人连接信息
     * <p>
     * 用于界面回填，免得每次改一个字段都要把整套地址端口重敲一遍。
     * 响应中<strong>不含 token</strong>，详见 {@link BotConnectionTester.Connection}。
     * @return 连接信息
     */
    /**
     * 列出事件流的只读口令
     * <p>
     * <b>只给指纹，不给哈希，更不给明文</b>——明文在签发那一刻之后就不存在了，
     * 而哈希虽然不可逆，也没有任何理由送到浏览器里。
     * <p>
     * 已吊销的一并列出：它们是<b>审计事实</b>，「这把曾经存在过、何时被撤」正是事后要查的。
     * @return 口令清单
     */
    /**
     * 会话校验端点，供反向代理的 {@code auth_request} 使用
     * <p>
     * <b>只回状态码，不回响应体。</b> 能走进这个方法体，就说明
     * {@code ConfigUiSecurityFilter} 已经放行了——也就是会话有效；
     * 会话无效时过滤器早就回了 401，根本到不了这里。
     * <b>校验逻辑因此只有一份</b>，不会出现「代理那条路与界面这条路判得不一样」。
     *
     * <h2>为什么必须挂在 {@code /config} 下</h2>
     * 会话 Cookie 的作用域是 {@code Path=/config}，安全过滤器也只注册在
     * {@code /config} 与 {@code /config/*} 上。<b>放到别的命名空间下，
     * 浏览器根本不会带上会话 Cookie，过滤器也不会跑</b>——两头都落空。
     * <p>
     * ⚠️ 由此得到一条给反代用的硬约束：<b>凡是要靠这套会话把门的被代理服务，
     * 它的对外路径也必须落在 {@code /config} 下</b>，否则 {@code auth_request}
     * 拿不到 Cookie，表现是全部 401 却看不出原因。
     *
     * <h2>关于「无副作用」</h2>
     * 它不认证、不签发、不改配置。<b>但会顺带刷新会话的活跃时间</b>——这是刻意的：
     * 不刷新的话，运维正在被代理的服务里干着活，会话却因为「没访问过配置界面」而到期，
     * 等于把人踢出去。
     * @return 固定 204，无响应体
     */
    @GetMapping("/api/session-check")
    public ResponseEntity<Void> sessionCheck() {
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/event-tokens")
    public JSONObject listEventTokens() {
        JSONObject result = new JSONObject();
        JSONArray items = new JSONArray();
        for (EventStreamToken token : eventStreamTokens.list()) {
            JSONObject item = new JSONObject();
            item.put("fingerprint", eventStreamTokens.fingerprintOf(token));
            item.put("label", token.label());
            item.put("issuedAt", token.issuedAt());
            item.put("revokedAt", token.revokedAt());
            item.put("active", token.active());
            items.add(item);
        }
        result.put("success", true);
        result.put("tokens", items);
        return result;
    }

    /**
     * 签发一把新的只读口令
     * <p>
     * ⚠️ <b>响应里的明文是它唯一一次出现</b>：库里只存哈希，此后不可能再取回。
     * 界面必须把这句话显示给使用者，否则「怎么看不到了」会被当成缺陷。
     * <b>能再取回来的明文，等于明文落盘。</b>
     * <p>
     * 🔒 签发动作发生在<b>服务器侧</b>，口令带出去给面板。顺序不能反——
     * 反过来就是把控制台令牌送进面板机器，那正是这条路线要避免的。
     * @param body 含 label：签给谁，用于日后定向吊销
     * @return 口令明文，仅此一次
     */
    @PostMapping("/api/event-tokens")
    public JSONObject issueEventToken(@RequestBody Map<String, String> body) {
        JSONObject result = new JSONObject();
        String label = body == null ? null : body.get("label");
        if (label == null || label.isBlank()) {
            result.put("success", false);
            // 没有标签就无法定向吊销，只能一次全撤——那就退回了复用控制台令牌时的粒度
            result.put("message", "请填写这把口令签给谁，否则日后无法单独吊销它");
            result.put("reason", "missing_label");
            return result;
        }

        try {
            String token = eventStreamTokens.issue(label.trim());
            result.put("success", true);
            result.put("token", token);
            result.put("message", "这是这把口令唯一一次显示，请立即复制保存；关闭后无法再次查看");
            return result;
        } catch (UncheckedIOException e) {
            log.error("配置界面签发只读口令失败", e);
            result.put("success", false);
            result.put("message", "签发没能写进磁盘，请检查数据目录后重试");
            result.put("reason", "write_failed");
            return result;
        }
    }

    /**
     * 吊销一把只读口令
     * <p>
     * ⚠️ <b>吊销对已经建立的连接不自动生效。</b> 这一条与校验放在哪一侧无关，
     * 撤完必须确认对方确实掉线了，否则「已吊销」只对新连接成立。
     * @param fingerprint 口令指纹
     * @return 吊销结果
     */
    @PostMapping("/api/event-tokens/{fingerprint}/revoke")
    public JSONObject revokeEventToken(@PathVariable String fingerprint) {
        JSONObject result = new JSONObject();
        try {
            boolean found = eventStreamTokens.revoke(fingerprint);
            result.put("success", found);
            result.put("message", found
                    ? "已吊销。⚠️ 已经建立的连接不会自动断开，请确认对方已掉线"
                    : "没有找到这把仍然有效的口令（可能已经撤过了）");
            // not_found 多半是清单过期（别处已经撤过），界面据此重取；写盘失败则不能重取——
            // 盘上还是旧账，刷新只会把同一份旧账再画一遍。两者原先只靠文案区分，而文案是会改的
            if (!found) {
                result.put("reason", "not_found");
            }
        } catch (UncheckedIOException e) {
            log.error("配置界面吊销只读口令失败", e);
            result.put("success", false);
            result.put("message", "吊销没能写进磁盘，请检查数据目录后重试");
            result.put("reason", "write_failed");
        }
        return result;
    }

    @GetMapping("/api/setup/bot")
    public JSONObject currentBot() {
        JSONObject result = new JSONObject();

        Optional<BotConnectionTester.Connection> current = connectionTesters.orderedStream()
                .findFirst()
                .flatMap(BotConnectionTester::current);

        result.put("configured", current.isPresent());
        current.ifPresent(connection -> {
            result.put("address", connection.address());
            result.put("httpPort", connection.httpPort());
            result.put("websocketPort", connection.websocketPort());
        });

        return result;
    }

    /**
     * 测试机器人连接
     * @param body 请求体，含 address、httpPort、httpToken
     * @return 测试结果
     */
    @PostMapping("/api/setup/test-bot")
    public JSONObject testBot(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        Optional<BotConnectionTester> tester = connectionTesters.orderedStream().findFirst();
        if (tester.isEmpty()) {
            result.put("success", false);
            result.put("message", "未找到可用的机器人适配器，请确认对应插件已加载");
            return result;
        }

        BotConnectionTester.Result outcome = tester.get().test(
                body.getString("address"),
                body.getIntValue("httpPort"),
                body.getString("httpToken"));

        result.put("success", outcome.ok());
        result.put("message", outcome.detail());
        result.put("advice", outcome.advice());
        return result;
    }

    /**
     * 保存机器人连接信息
     * <p>
     * 这些字段位于 senders 列表的元素内部，常规配置页按设计不展示列表元素，
     * 但它们恰恰是唯一一批「不配置就跑不起来」的配置项，因此单独提供写入入口。
     *
     * <h2>先接上，再落盘</h2>
     * 顺序是有意的：<b>要写进文件里的那一条，正是适配器接上之后的现状</b>。
     * 反着来（先写那一条再去接）的话，核心得自己猜这条元素长什么样——而元素里有哪几个键、
     * 平台叫什么名字、推送接口挂在哪个路径，都是适配器一侧的事。
     * （「文件不在就先建一份」是另一回事，它得排在最前面，理由见下面那一段。）
     * <p>
     * 因此写文件失败时，连接<b>已经是接上的</b>。这时如实说「已经接上、但没存下来，
     * 重启后会恢复原状」，而不是笼统报一句保存失败——后者会让人以为什么都没发生，
     * 于是照着一个其实已经通了的连接反复重试。
     * @param body 请求体，含连接信息
     * @return 保存结果
     */
    @PostMapping("/api/setup/bot")
    public JSONObject saveBot(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        Optional<BotConnectionTester> tester = connectionTesters.orderedStream().findFirst();
        if (tester.isEmpty()) {
            result.put("success", false);
            result.put("message", "未找到可用的机器人适配器，请确认对应插件已加载");
            return result;
        }

        String address = trimmed(body.getString("address"));
        String httpToken = trimmed(body.getString("httpToken"));
        String websocketToken = trimmed(body.getString("websocketToken"));

        int httpPort;
        int websocketPort;
        try {
            httpPort = port(body.getString("httpPort"));
            websocketPort = port(body.getString("websocketPort"));
        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("message", "端口只能填数字：" + e.getMessage());
            return result;
        }

        if (address.isEmpty() && httpToken.isEmpty() && websocketToken.isEmpty()
                && httpPort == 0 && websocketPort == 0) {
            result.put("success", false);
            result.put("message", "没有需要保存的内容");
            return result;
        }

        try {
            // 文件不在时先建出来，且必须赶在适配器去动运行中的配置之前。
            // 这一份是按配置面现算渲染出来的；适配器接下来要往配置面里添一条连接。
            // 顺序反过来的话，渲染的就是一份已经含着刚添那条连接的配置面——
            // 对象列表现在按字段写得出，但那条连接里可能带着本次运行才生成的推送 Token，
            // 写进文件等于替人把「每次启动换一把」改成了「固定一把」。
            fileService.createIfAbsent();
        } catch (IOException e) {
            log.error("建立配置文件失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
            return result;
        }

        BotConnectionTester.Applied applied =
                tester.get().apply(address, httpPort, websocketPort, httpToken, websocketToken);
        result.put("live", applied.live());

        try {
            fileService.writeListItemFields(BOT_CONNECTION_LIST, 0, applied.configuration());
            result.put("success", true);
            result.put("message", applied.live()
                    ? "已保存，这条连接已经接上了：" + applied.detail()
                    : "已保存，重启后生效：" + applied.detail());
        } catch (IOException e) {
            log.error("保存机器人连接信息失败", e);
            result.put("success", applied.live());
            result.put("message", applied.live()
                    ? "连接已经接上，但没能写进配置文件，重启后将恢复原状: " + e.getMessage()
                    : "保存失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 把端口读成一个数
     * @param value 请求体里的端口，可为 null 或空白
     * @return 端口号，没填时为 0（语义是「这一项不改」）
     */
    private int port(String value) {
        String text = trimmed(value);
        return text.isEmpty() ? 0 : Integer.parseInt(text);
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 切换全局推送开关
     * <p>
     * 同时改写内存中的配置与配置文件：只改文件要等重启才生效，而「临时静音」这个诉求
     * 恰恰要求立即生效；只改内存则重启后又会悄悄恢复推送。
     * @param body 请求体，enabled 字段为目标状态
     * @return 切换结果
     */
    @PostMapping("/api/push/toggle")
    public JSONObject togglePush(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();
        boolean enabled = Boolean.TRUE.equals(body.getBoolean("enabled"));

        // 走与设置页保存同一条通道，而不是在这里再写一次 setEnabled：
        // 「这一项怎么落到运行中的程序上」有两处实现的话，改了其中一处的另一处不会跟着变，
        // 而两条路在界面上看起来是同一个开关
        Map<String, String> change = Map.of("starbot.core.push.enabled", String.valueOf(enabled));
        runtimeApplier.applyAndTrack(change);

        try {
            fileService.write(change);
            result.put("success", true);
            result.put("message", enabled ? "已恢复推送" : "已暂停全部推送");
            log.info("配置界面已{}全局推送", enabled ? "恢复" : "暂停");
        } catch (IOException e) {
            // 内存中的开关已生效，仅是没能持久化，如实告知而不是笼统报失败
            log.error("持久化全局推送开关失败", e);
            result.put("success", true);
            result.put("message", (enabled ? "已恢复推送" : "已暂停全部推送") + "，但写入配置文件失败，重启后将恢复原状");
        }

        return result;
    }

    /**
     * 查询主播信息
     * <p>
     * 添加主播时先把昵称与直播间号显示出来让人确认，避免 uid 打错一位却配了个陌生人——
     * 这类错误在推送真正发生前完全无法察觉。
     * <p>
     * 输入除 uid 与个人空间链接外，也收直播间号：纯数字短号，或直播间链接。
     * 纯数字既像 uid 又像房间号时<b>先按 uid 查，查不到再按房间号查一次</b>；
     * 链接已经标明是哪一种时只走对应那一趟，不加重试。
     * <p>
     * 链接长什么样由各平台自己认，本处不认识任何一家的域名——所以平台要先定下来，
     * 才知道该拿谁的数据源服务去认这段文本。
     * @param body 请求体，含 platform 与 uid（uid 亦可为空间链接、直播间号或直播间链接）
     * @return 主播信息
     */
    @PostMapping("/api/streamer/lookup")
    public JSONObject lookupStreamer(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        String input = body.getString("uid");

        if (platform == null || platform.isBlank() || input == null || input.isBlank()) {
            result.put("success", false);
            result.put("message", "请填写平台与 uid，也可直接粘贴个人空间链接或直播间号");
            return result;
        }

        Optional<DataSourceService> service = dataSourceServiceRegistry.getDataSourceService(platform);
        if (service.isEmpty()) {
            result.put("success", false);
            result.put("message", "平台 " + platform + " 没有可用的数据源服务，请确认对应插件已加载");
            return result;
        }

        DataSourceService data = service.get();
        StreamerQuery query = parseStreamerQuery(input, data);
        if (query == null) {
            result.put("success", false);
            result.put("message", recognizedByOtherPlatform(input, platform)
                    ? "这条链接不属于当前选择的平台，请先把平台选对再粘贴"
                    : "不认识这条链接，可以粘贴主播的个人空间链接或直播间链接，也可以直接填 uid 或直播间号");
            return result;
        }

        StreamerWithFans found;
        try {
            if (query.kind() == StreamerIdKind.ROOM) {
                found = data.lookupByRoomIdWithFans(query.id());
            } else {
                found = data.completeStreamerWithFans(incompleteUser(platform, query.id()));
                if (missingName(found.user()) && query.kind() == StreamerIdKind.DIGITS) {
                    found = data.lookupByRoomIdWithFans(query.id());
                }
            }
        } catch (Exception e) {
            log.error("查询主播 {} 信息失败", query.id(), e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
            return result;
        }

        PushUser user = found.user();

        if (missingName(user)) {
            result.put("success", false);
            result.put("message", query.kind() == StreamerIdKind.ROOM
                    ? "未查到直播间号 " + query.id() + " 对应的主播，请确认直播间号是否正确"
                    : "未查到 uid " + query.id() + " 对应的主播，请确认 uid 是否正确");
            return result;
        }

        result.put("success", true);
        result.put("uid", user.getUid());
        result.put("uname", user.getUname());
        result.put("roomId", user.getRoomId());
        result.put("face", user.getFace());
        // 粉丝数是这张确认小卡上最容易发现「认错人」的一项：uid 打错一位仍可能查到
        // 一位真实存在的人，昵称与头像未必看得出不对，粉丝数往往差着数量级。
        // 取不到时给 null 而不是 0——后者会显示成「这位主播一个粉丝都没有」。
        // 它与昵称、房间号由数据源同一趟带回（见 DataSourceService#completeStreamerWithFans），
        // 取不到已在那一趟里收住，不会连累整次查询
        result.put("fans", found.fans());
        return result;
    }

    /**
     * 只填了 uid 与平台的半成品推送用户，其余字段待数据源补全
     */
    private PushUser incompleteUser(String platform, long uid) {
        PushUser user = new PushUser();
        user.setUid(uid);
        user.setPlatform(platform);
        return user;
    }

    private static boolean missingName(PushUser user) {
        return user == null || user.getUname() == null || user.getUname().isBlank();
    }

    /**
     * 从输入中提取 uid 或直播间号
     * <p>
     * 让使用者自己去链接里抠出那串数字是没必要的一道门槛，而这段文本是不是一条链接、
     * 里头那串数字是 uid 还是直播间号，只有平台自己认得——本处只收它认出来的结果。
     * 纯数字不问平台：它不是谁家的链接，两种编号都像，先按 uid 查、落空再按房间号查一次。
     * @param input 输入内容，非空且已判过非空白
     * @param data 所选平台的数据源服务
     * @return 解析结果，无法识别时返回 null
     */
    private StreamerQuery parseStreamerQuery(String input, DataSourceService data) {
        String trimmed = input.trim();

        Optional<StreamerReference> link = parseStreamerLink(data, trimmed);
        if (link.isPresent()) {
            StreamerReference reference = link.get();
            StreamerIdKind kind = reference.kind() == StreamerReference.Kind.ROOM_ID
                    ? StreamerIdKind.ROOM
                    : StreamerIdKind.UID;
            return new StreamerQuery(kind, reference.id());
        }

        Matcher plain = PLAIN_UID.matcher(trimmed);
        return plain.matches() ? new StreamerQuery(StreamerIdKind.DIGITS, Long.parseLong(plain.group(1))) : null;
    }

    /**
     * 问一个平台认不认得这段文本
     * <p>
     * 认链接的是插件那一侧的代码，它抛异常不该让整次查询变成 500：认不出与认的时候炸了，
     * 对使用者是同一件事——这条链接在这里用不了。
     * @param data 数据源服务
     * @param text 已去过首尾空白的输入
     * @return 认出的 uid 或直播间号，认不出时为空
     */
    private Optional<StreamerReference> parseStreamerLink(DataSourceService data, String text) {
        try {
            Optional<StreamerReference> parsed = data.parseStreamerLink(text);
            return parsed == null ? Optional.empty() : parsed;
        } catch (Exception e) {
            log.debug("解析主播链接失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 别的平台认不认得这条链接
     * <p>
     * 分开说这两种情形：一条谁都不认得的链接，与一条平台选错了的链接。后者若也回「不认识」，
     * 使用者会对着一条完全正常的链接反复检查那串数字。
     * <p>
     * 只在已经认不出的时候才逐平台问一遍，问的是「该不该换一句话说」，不据此改查谁——
     * 平台是使用者在界面上选的，配置也按那个平台写，替他改掉是另一回事。
     * @param input 输入内容
     * @param platform 当前所选平台，已经问过了不再重问
     * @return 有别的平台认得时为真
     */
    private boolean recognizedByOtherPlatform(String input, String platform) {
        String trimmed = input.trim();
        for (String other : dataSourceServiceRegistry.platforms()) {
            if (other.equals(platform)) {
                continue;
            }
            boolean known = dataSourceServiceRegistry.getDataSourceService(other)
                    .flatMap(service -> parseStreamerLink(service, trimmed))
                    .isPresent();
            if (known) {
                return true;
            }
        }
        return false;
    }

    private enum StreamerIdKind {
        UID, ROOM, DIGITS
    }

    private record StreamerQuery(StreamerIdKind kind, long id) {
    }

    /**
     * 已注册的推送处理器
     * <p>
     * 界面据此渲染「推送哪些事件」的勾选项。处理器的全限定类名属于实现细节，
     * 不该要求使用者手抄，此处把它连同展示名一并给出，由界面完成映射。
     * @return 处理器列表
     */
    @GetMapping("/api/handlers")
    public JSONObject handlers() {
        JSONObject result = new JSONObject();
        result.put("success", true);

        JSONArray items = new JSONArray();
        handlerService.getRegisteredHandlers().forEach((className, handler) -> {
            JSONObject item = new JSONObject();
            item.put("className", className);
            item.put("displayName", handler.displayName());
            item.put("description", handler.description());
            item.put("platform", handler.platform());
            item.put("placeholders", handler.placeholders());
            item.put("attachments", handler.attachmentPlaceholders());
            // 「默认」是这台机器此刻的默认（出厂默认盖上控制台改过的那几个键），
            // 不是出厂默认：界面拿它判「这个通道用的是默认还是自定义」，
            // 拿出厂默认去判的话，改过默认模板之后每一个通道都会显示成「自定义」
            item.put("defaultParams", templateDefaults.paramsOf(handler));
            // 出厂默认另给一份：默认模板那一页要答「这一项改过没有」与「恢复出厂」
            item.put("factoryParams", handler.getDefaultParams());
            item.put("options", handler.options());
            items.add(item);
        });

        items.sort(Comparator.comparing(item -> ((JSONObject) item).getString("className")));
        result.put("handlers", items);
        return result;
    }

    /**
     * 读这台机器改过的默认模板
     * <p>
     * 回的是<b>相对出厂默认的覆盖</b>，没改过的处理器整个不出现。整份默认参数在
     * {@code /api/handlers} 的 {@code defaultParams} 里，这一支答的是另一个问题：
     * 「哪几项是人改过的」——两者合一的话，「改成了与出厂一样的值」与「没改过」
     * 就再也分不开，而默认模板那一页上「恢复出厂」该不该亮正是靠这个分。
     * @return 覆盖参数
     */
    @GetMapping("/api/templates")
    public JSONObject templates() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("defaults", templateDefaults.all());
        return result;
    }

    /**
     * 改某一类通知的默认模板
     * <p>
     * 一次一个处理器，传的是<b>整份覆盖</b>：某个键不在里面即回到出厂默认，
     * 空对象即整类回到出厂默认。改这里等于改<b>所有用默认的通道</b>，
     * 因此校验不过时一个字也不写——半份默认模板会一次落到一批群上。
     * @param body 请求体，className 为处理器全类名，params 为整份覆盖
     * @return 保存结果
     */
    @PostMapping("/api/templates")
    public ResponseEntity<JSONObject> saveTemplate(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String className = body.getString("className");
        Optional<com.starlwr.bot.core.handler.StarBotEventHandler> handler =
                className == null ? Optional.empty() : handlerService.getHandler(className);
        if (handler.isEmpty()) {
            result.put("success", false);
            result.put("message", "没有这样一个推送处理器: " + className);
            return ResponseEntity.badRequest().body(result);
        }

        List<String> issues = templateDefaults.save(handler.get(), body.getJSONObject("params"));
        if (!issues.isEmpty()) {
            result.put("success", false);
            result.put("message", "默认模板有误，已拒绝保存");
            result.put("issues", issues);
            return ResponseEntity.ok(result);
        }

        result.put("success", true);
        result.put("message", "已保存，用默认模板的通道跟着一起变");
        log.info("配置界面已更新默认模板: {}", className);
        return ResponseEntity.ok(result);
    }

    /**
     * 最近的推送记录
     * <p>
     * 「刚才那条推了吗」「为什么没推」此前只能翻 journalctl。
     * @return 推送记录，按时间倒序
     */
    @GetMapping("/api/push-history")
    public JSONObject pushHistory() {
        JSONObject result = new JSONObject();
        result.put("success", true);

        JSONArray records = new JSONArray();
        activityRecorder.getHistory().forEach(record -> {
            JSONObject item = new JSONObject();
            item.put("at", HISTORY_TIME.format(record.at()));
            item.put("platform", record.platform());
            item.put("target", record.target());
            item.put("summary", record.summary());
            item.put("success", record.success());
            item.put("reason", record.reason());
            records.add(item);
        });

        result.put("records", records);
        return result;
    }

    /**
     * 账号登录状态
     * <p>
     * 二维码在服务端渲染成图片返回：界面页面刻意不引入任何外部依赖，客户端无法自行绘制二维码。
     * @return 各平台的登录状态与待扫描的二维码
     */
    @GetMapping("/api/login")
    public JSONObject login() {
        JSONObject result = new JSONObject();
        result.put("success", true);

        JSONArray accounts = new JSONArray();
        loginProviders.orderedStream().forEach(provider -> {
            JSONObject item = new JSONObject();
            item.put("platform", provider.platform());
            item.put("displayName", provider.displayName());
            item.put("loggedIn", provider.isLoggedIn());
            item.put("accountId", provider.accountId().orElse(null));
            item.put("accountName", provider.accountName().orElse(null));
            item.put("disabledReason", provider.disabledReason().orElse(null));
            // 凭据还能用多久，以及到期之后会不会自己续上。答不上的平台给 null，
            // 界面那一侧就不写「还剩几天」——两个字段都写死成一个字段的话，
            // 「没有到期时刻」与「到期时刻是 0」在浏览器里长得一样
            item.put("expiresAt", provider.credentialExpiresAt().map(Instant::toEpochMilli).orElse(null));
            item.put("credentialNote", provider.credentialNote().orElse(null));

            provider.pendingQrCodeContent()
                    .flatMap(content -> QrCodeUtil.generateQrCodeAndGetBase64(content, QR_CODE_IMAGE_SIZE))
                    .ifPresent(base64 -> item.put("qrCode", base64));

            accounts.add(item);
        });

        result.put("accounts", accounts);
        return result;
    }

    /**
     * 退出登录
     * @param body 请求体，platform 字段为平台名
     * @return 退出结果
     */
    @PostMapping("/api/login/logout")
    public JSONObject logout(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();
        String platform = body.getString("platform");

        Optional<AccountLoginProvider> provider = loginProviders.orderedStream()
                .filter(item -> item.platform().equals(platform))
                .findFirst();

        if (provider.isEmpty()) {
            result.put("success", false);
            result.put("message", "未找到平台 " + platform);
            return result;
        }

        try {
            provider.get().logout();
            result.put("success", true);
            result.put("message", "已退出登录，请扫描新的二维码重新登录");
            log.info("配置界面已退出 {} 的登录", platform);
        } catch (Exception e) {
            log.error("退出 {} 的登录失败", platform, e);
            result.put("success", false);
            result.put("message", "退出失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 发送测试消息
     * <p>
     * 配置完成后若无法当场验证，群号写错、Token 不匹配、OneBot 未启动、机器人不在群里这四类错误的
     * 表现完全一样：什么都不发生。此处直接发一条消息并把推送接口的原始响应回显出来，
     * 让使用者立刻知道通没通、卡在哪。
     * @param body 请求体，含 platform、type、num，可选 content
     * @return 发送结果与原始响应
     */
    @PostMapping("/api/test-message")
    public JSONObject testMessage(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Integer type = body.getInteger("type");
        Long num = body.getLong("num");

        if (platform == null || platform.isBlank() || type == null || num == null) {
            result.put("success", false);
            result.put("message", "请填写完整的推送平台、类型与号码");
            return result;
        }

        // of() 对未知取值返回 UNKNOWN 而非抛异常，必须显式判断，否则会带着「未知类型」一路发下去
        PushTargetType targetType = PushTargetType.of(type);
        if (targetType == PushTargetType.UNKNOWN) {
            result.put("success", false);
            result.put("message", "推送类型必须为 " + PushTargetType.GROUP.getCode() + "（群聊）或 "
                    + PushTargetType.FRIEND.getCode() + "（私聊）");
            return result;
        }

        String content = body.getString("content");
        if (content == null || content.isBlank()) {
            content = "这是一条来自 StarBot 的测试消息，收到即表示推送链路正常。";
        }

        // 必须走 create：它负责填充顺序号与创建时间，并处理 {next} 分条，直接 new 会漏字段
        List<Message> messages = Message.create(platform, targetType, num, content);
        if (messages.isEmpty()) {
            result.put("success", false);
            result.put("message", "消息内容为空");
            return result;
        }

        try {
            JSONObject raw = null;
            JSONObject failure = null;
            int sent = 0;
            int skipped = 0;

            for (Message message : messages) {
                JSONObject current = messageSender.sendNow(message);

                // 返回 null 表示这一条被**有意跳过**，不是失败：拦截器取消，
                // 或 @全体成员 因无权限、超配额被摘掉后整条为空。
                // 此时必须继续发后面的分条——{next} 会把「{at=all}」与正文拆成两条，
                // 跳过前者就中断的话，正文会一起没了（这个坑真踩过）
                if (current == null) {
                    skipped++;
                    continue;
                }

                raw = current;
                if (!Integer.valueOf(0).equals(current.getInteger("code"))) {
                    failure = current;
                    break;
                }
                sent++;
            }

            boolean ok = failure == null && sent > 0;
            result.put("success", ok);
            result.put("raw", raw);

            if (ok) {
                result.put("message", skipped == 0
                        ? "已发送，请到对应会话中确认是否收到"
                        : "已发送（有 " + skipped + " 条被跳过，多为 @全体成员 无权限或超出每日配额），请到对应会话中确认");
            } else if (failure != null) {
                result.put("message", "发送失败：" + failure.getString("message"));
                result.put("advice", "常见原因：群号或 QQ 号填错、机器人不在该群、OneBot 实现未启动、Token 不匹配。"
                        + "可对照「运行状态」页的机器人连接项排查");
            } else {
                result.put("message", "消息全部被跳过，未实际发出");
                result.put("advice", "可能是推送被拦截器取消，或消息只含 @全体成员 而机器人没有该权限。"
                        + "详情见日志");
            }
        } catch (Exception e) {
            log.error("发送测试消息失败", e);
            result.put("success", false);
            result.put("message", "发送失败：" + e.getMessage());
        }

        return result;
    }

    /*
     * 列出历史备份与回滚到某一份的那两个端点曾经在这里，5.1 随编辑器一并撤掉。
     *
     * 它们是配置文件编辑器的配套：编辑器撤了，「改坏了滚回去」这件事也就没有了界面入口。
     * 备份本身照旧生成——每次保存前 ConfigurationFileService 都留一份带时间戳的 .bak，
     * 那是文件系统上的东西，运维在服务器上直接改回来即可。
     */

    /**
     * 读取推送配置 datasource.json
     * @return 推送配置
     */
    @GetMapping("/api/datasource")
    public JSONObject datasource() {
        JSONObject result = new JSONObject();

        try {
            Path path = Path.of(properties.getDatasource().getJsonPath());
            String content = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "[]";

            result.put("success", true);
            result.put("content", content);
        } catch (IOException e) {
            log.error("读取推送配置失败", e);
            result.put("success", false);
            result.put("message", "读取失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 保存推送配置 datasource.json
     * <p>
     * 写入前除校验 JSON 格式外还检查语义：处理器类名与推送平台写错时，运行期只表现为
     * 「这个主播没有推送」，排查成本极高，因此必须在保存时就拦下。
     * @param body 请求体，content 字段为新内容
     * @return 保存结果
     */
    @PostMapping("/api/datasource")
    public ResponseEntity<JSONObject> saveDatasource(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String content = body.getString("content");
        List<String> issues = validator.validateDatasource(content, senderService.getSenderNames());
        if (!issues.isEmpty()) {
            result.put("success", false);
            result.put("message", "推送配置有误，已拒绝保存");
            result.put("issues", issues);
            return ResponseEntity.ok(result);
        }

        // 超员在数据源那一层也拦得住，但那层只会拒收并写一行日志：界面照样回「已保存」，
        // 而多出来的几位从此不会被监控，使用者在界面上完全看不出来。所以保存这一步得自己说清楚
        int enabledStreamers = countEnabledStreamers(content);
        if (enabledStreamers > MonitorLimit.MAX_STREAMERS) {
            result.put("success", false);
            result.put("message", "最多同时监控 " + MonitorLimit.MAX_STREAMERS + " 位主播（本次提交 " + enabledStreamers + " 位）");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            Path path = Path.of(properties.getDatasource().getJsonPath());
            if (Files.exists(path)) {
                new TimestampedFileBackup(path, backupClock)
                        .backup(properties.getConfigUi().getBackupKeep());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);

            result.put("success", true);
            result.put("message", properties.getDatasource().isJsonAutoReload()
                    ? "已保存，配置将自动重新加载"
                    : "已保存，重启后生效");
            log.info("配置界面已更新推送配置");
        } catch (IOException e) {
            log.error("保存推送配置失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 统计推送配置中启用的主播数
     * <p>
     * 缺少 enabled 字段的按启用计，与数据源加载时的认法保持一致；内容不是数组时返回 0，
     * 交由前面的格式校验去回绝，这里不重复报同一件事。
     * @param content 推送配置内容
     * @return 启用的主播数
     */
    private int countEnabledStreamers(String content) {
        JSONArray users;
        try {
            users = JSONArray.parseArray(content);
        } catch (Exception e) {
            return 0;
        }

        if (users == null) {
            return 0;
        }

        int count = 0;
        for (int i = 0; i < users.size(); i++) {
            JSONObject user;
            try {
                user = users.getJSONObject(i);
            } catch (Exception e) {
                continue;
            }

            if (user != null && !Boolean.FALSE.equals(user.getBoolean("enabled"))) {
                count++;
            }
        }

        return count;
    }

    /**
     * 运行状态
     * @return 健康状况、当前已加载的推送用户与运行信息
     */
    @GetMapping("/api/status")
    public JSONObject status() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("health", health());

        List<Map<String, Object>> users = new ArrayList<>();
        dataSource.getAllUsers().forEach(user -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uid", user.getUid());
            item.put("uname", user.getUname());
            item.put("roomId", user.getRoomId());
            item.put("platform", user.getPlatform());
            item.put("enabled", user.getEnabled());
            item.put("targets", user.getTargets().size());
            users.add(item);
        });

        Runtime runtime = Runtime.getRuntime();
        JSONObject runtimeInfo = new JSONObject();
        runtimeInfo.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        runtimeInfo.put("heapMaxMb", runtime.maxMemory() / 1024 / 1024);
        // Thread.activeCount() 只统计当前线程组及其子组，在 Web 请求线程上调用会漏掉大量线程，
        // 实测同一时刻它报 15 而实际存活的 Java 线程为 25。改用 JVM 级别的线程计数。
        // 注意该值不含 VM Thread、编译器线程等 JVM 内部原生线程，因此会小于线程转储的条目数。
        runtimeInfo.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        runtimeInfo.put("processors", runtime.availableProcessors());

        result.put("users", users);
        result.put("runtime", runtimeInfo);
        // 侧栏上显示的版本号。真源是构建期生成的 build-info，不在界面里另写一份——
        // 写死一份的表现是升级之后侧栏还显示旧版本，而没有任何东西会发现这件事。
        // 从源码直接跑（未经打包）时没有 build-info，此时回空串：界面上那一格空着，
        // 比显示一个编出来的版本号要好
        BuildProperties build = buildProperties.getIfAvailable();
        result.put("version", build == null ? "" : Optional.ofNullable(build.getVersion()).orElse(""));
        // 该提示的新版：侧栏药丸、点开的小面板与首页那条软待办共用这一块。没有新版时整块不下发——
        // 这类字段的读法是「缺席即没有」，与 queue 那种「键必须在、值可以为 0」不是同一种约定，
        // 多发一个空对象只会让界面多一种两头都没定义的中间态。
        // 还没配主播的机器不下发：它连一个主播都还没配，最不该在那一屏上被「有新版」带走。
        // 不按配置文件在不在判——同意使用协议就会写出 application.yml，按文件判的话
        // 刚装好的机器也会挂药丸。
        if (!users.isEmpty()) {
            updateCheck.pendingUpdate().ifPresent(update -> {
                JSONObject updateJson = new JSONObject();
                updateJson.put("latestVersion", update.version());
                updateJson.put("notes", update.notes());
                updateJson.put("url", update.url());
                result.put("update", updateJson);
            });
        }
        // 设置页底部要显示「配置文件在哪」。路径由定位配置文件的那个服务给，不在界面里写死：
        // 写死的那一份在换了工作目录或用 -Dspring.config.location 指过别处时会指错地方
        result.put("configPath", fileService.describeConfigPath());
        // 上限由后端下发，界面不再各写一份，免得两边对不上
        result.put("streamerLimit", MonitorLimit.MAX_STREAMERS);
        result.put("pushEnabled", properties.getPush().isEnabled());
        // 保存过、仍等着重启的那几项。放在这里而不是让界面自己记：这条提示的寿命是「到下次重启为止」，
        // 而只有服务端知道自己是不是刚起来的。记在浏览器里的话，重启完那条提示还挂着
        result.put("restartPending", runtimeApplier.getPendingRestart());
        // 供界面填充「发送测试消息」的推送平台下拉框，避免让使用者手打平台名
        result.put("senders", senderService.getSenderNames().stream().sorted().toList());
        // 这台控制台上没上锁。取自签发会话的那一处，界面不另按配置项自己判一遍——
        // 「有没有口令」与「口令登录启不启用」在配置面上不是同一件事，各判各的迟早会说两种话
        result.put("locked", authService.isEnabled());
        // 累计存储开没开。首页待办与主播页那条小横条都要问，判据只有 LiveDataService 那一份
        result.put("totalDataAvailable", liveDataService.supportsTotalData());
        result.put("quiet", quiet());
        result.put("live", liveNow());
        result.put("today", todayPushCounts());
        result.put("queue", queue());
        result.put("alerts", alerts());
        return result;
    }

    /**
     * 三张告警卡各自配好了没有
     * <p>
     * 首页那条「QQ 告警有死角」要按 Webhook 与邮件这两位决定出不出——QQ 配没配都出，
     * 它催的是掉线时还有一路能叫到人。判定与设置页药丸同源，见 {@link AlertReadiness}：
     * QQ 看有没有号码、Webhook 看地址空不空、邮件看收件与 SMTP 主机都有没有。
     */
    private JSONObject alerts() {
        JSONObject json = new JSONObject();
        StarBotCoreProperties.Alert alert = properties.getAlert();
        json.put("qq", alert.getQqNum() != null);
        json.put("webhook", StringUtil.isNotBlank(alert.getWebhookUrl()));
        json.put("mail", AlertReadiness.mailConfigured(properties.getMail().getDefaultTo(), smtpHost()));
        return json;
    }

    /**
     * 邮件告警的 SMTP 主机
     * <p>
     * 这一项不在核心配置对象上，是 Spring 自己的 {@code spring.mail.host}。
     * 设置页药丸读的是配置文件里这一栏，这里也读同一份，两边才不会分叉。
     * 文件不在或读失败按没配算：首页因此会催人去配，比悄悄当成已配要安全。
     * @return 主机名，没有时为空串
     */
    private String smtpHost() {
        try {
            Map<String, String> values = fileService.read();
            if (values == null) {
                return "";
            }
            String host = values.get("spring.mail.host");
            return host == null ? "" : host;
        } catch (IOException e) {
            log.debug("读 SMTP 主机失败，邮件这一路按未配算: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 记下「这个版本先不提醒」
     * <p>
     * 版本由服务端认定而不是照单全收客户端送来的串：这一动作的真源是页面上那颗按钮，
     * 而隔了几天才送达的请求或乱填的版本号不该被记成使用者的选择。没记下时
     * {@code success=false}，界面据此重取一次状态。
     * @param body 请求体，version 字段为要跳过的版本号
     * @return 记下了没有
     */
    @PostMapping("/api/version/skip")
    public JSONObject skipVersion(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();
        String version = body == null ? "" : Optional.ofNullable(body.getString("version")).orElse("");
        boolean recorded = updateCheck.skip(version);
        result.put("success", recorded);
        if (recorded) {
            log.info("配置界面已记下版本 {} 先不提醒", version);
        }
        return result;
    }

    /**
     * 发送队列此刻积压多少、累计丢过多少
     * <p>
     * 机器人掉线时消息不会立刻消失，而是在队列里排着——排到队满才开始丢最旧的那条。
     * 连接页上那张卡因此要把这个数写出来：光说「连不上」答不了「刚才那条开播通知还在不在」，
     * 而这两件事使用者关心的程度完全不同。
     * <p>
     * 累计丢弃数一并给：积压回落到 0 有两种走法——发出去了，和被丢掉了，
     * 只看积压数这两种长得一模一样。
     * @return 队列状况
     */
    private JSONObject queue() {
        JSONObject json = new JSONObject();
        json.put("pending", messageSender.getPendingCount());
        json.put("dropped", messageSender.getDroppedCount());
        return json;
    }

    /**
     * 静音时段：此刻在不在，以及区间是什么
     * <p>
     * 「在不在」由推送闸门算，界面不照起止时刻自己判一遍：跨零点、起止相同、格式不对
     * 这几条规则只该有一份实现，两份的分叉表现是屏幕上写着「静音中」而推送照发。
     * @return 静音时段状态
     */
    private JSONObject quiet() {
        StarBotCoreProperties.Push push = properties.getPush();
        JSONObject json = new JSONObject();
        json.put("active", pushGate.inQuietHours());
        json.put("start", push.getQuietStart());
        json.put("end", push.getQuietEnd());
        return json;
    }

    /**
     * 此刻正在直播的主播
     * <p>
     * 只列监听清单里的：直播状态是按 平台 + uid 记的，而「这台机器该关心谁」
     * 由推送配置说了算。反过来遍历状态里的全部键的话，删掉主播之后他还会挂在首页上。
     * @return 在播的主播
     */
    private JSONArray liveNow() {
        JSONArray items = new JSONArray();

        dataSource.getAllUsers().forEach(user -> {
            if (Boolean.FALSE.equals(user.getEnabled())) {
                return;
            }
            if (!liveDataService.getLiveStatus(user.getPlatform(), user.getUid()).orElse(false)) {
                return;
            }

            JSONObject item = new JSONObject();
            item.put("uid", user.getUid());
            item.put("uname", user.getUname());
            item.put("roomId", user.getRoomId());
            item.put("platform", user.getPlatform());
            // 开播时刻可能没记上（例如程序在别人已经开播之后才起来），此时给 null，
            // 界面那一侧就不写「已播 N 小时」——编一个开始时刻出来，那个时长会一直是错的
            item.put("since", liveDataService.getLiveStartTime(user.getPlatform(), user.getUid()).orElse(null));
            items.add(item);
        });

        return items;
    }

    /**
     * 今天推成功与推失败各几条
     * <p>
     * 按日历上的今天数，取自时间线而不是进程内那份累计计数器：后者从进程启动起算，
     * 重启一次「今天」就归零，而使用者问的今天不会因为谁重启过而变短。
     * @return 今日推送条数
     */
    private JSONObject todayPushCounts() {
        Map<TimelineEventType, Integer> counts = timeline.countsOn(LocalDate.now());
        JSONObject json = new JSONObject();
        json.put("sent", counts.getOrDefault(TimelineEventType.PUSH_SENT, 0));
        json.put("failed", counts.getOrDefault(TimelineEventType.PUSH_FAILED, 0));
        return json;
    }

    /**
     * 汇总各模块注册的健康探针
     * <p>
     * 单个探针实现出错不应影响整份状态，因此逐个捕获异常并降级为不可用，而非让整个接口失败。
     * @return 健康状况列表
     */
    private JSONArray health() {
        JSONArray items = new JSONArray();

        healthProbes.orderedStream()
                .sorted(Comparator.comparingInt(HealthProbe::order))
                .forEach(probe -> {
                    JSONObject item = new JSONObject();
                    item.put("name", probe.name());
                    // 界面据此把探针分派到「机器人」「哔哩哔哩」页签，由探针自己声明，界面不做名称匹配
                    item.put("scope", probe.scope().name());
                    // 量的是不是登录态。首页要把「登录掉了」与「连不上」分开说：前者非得有人去扫码不可，
                    // 后者多半会自己恢复。同样由探针自己声明——按名字认的判据在探针改个显示名的那天
                    // 静默失效，失效方向还是「从此再也认不出登录失效」
                    item.put("loginState", probe.loginState());

                    try {
                        HealthStatus status = probe.check();
                        item.put("level", status.level().name());
                        item.put("summary", status.summary());
                        item.put("advice", status.advice());
                    } catch (Exception e) {
                        log.warn("健康探针 {} 执行异常", probe.name(), e);
                        item.put("level", HealthStatus.Level.DOWN.name());
                        item.put("summary", "探针执行异常: " + e.getMessage());
                        item.put("advice", "请查看日志确认原因");
                    }

                    items.add(item);
                });

        return items;
    }
}
