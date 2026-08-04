package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ConfigLevel;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.datasource.DataSourceServiceRegistry;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.DataSourceService;
import com.starlwr.bot.core.account.AccountLoginProvider;
import com.starlwr.bot.core.account.BotConnectionTester;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.util.QrCodeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
     * 个人空间链接中的 uid
     * <p>
     * 优先按该模式提取：链接里往往还带有 spm_id_from 之类含数字的参数，
     * 单纯取「第一串数字」会取错。
     */
    private static final Pattern SPACE_URL_UID = Pattern.compile("space\\.bilibili\\.com/(\\d{1,19})");

    /**
     * 纯数字形式的 uid
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

    private final ObjectProvider<BotConnectionTester> connectionTesters;

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
                              ObjectProvider<BotConnectionTester> connectionTesters) {
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
     * 配置项字段表
     * <p>
     * 字段来自各模块编译期生成的配置元数据，包含类型、默认值与取自 Javadoc 的中文说明。
     * @return 按分组组织的字段表
     */
    @GetMapping("/api/schema")
    public JSONObject schema() {
        JSONArray groups = new JSONArray();
        Map<String, ConfigLevel.Level> levels = levelResolver.getLevels();

        metadataService.getGroupedFields().forEach((group, fields) -> {
            JSONArray items = new JSONArray();
            for (ConfigurationMetadataService.ConfigurationField field : fields) {
                JSONObject item = new JSONObject();
                item.put("name", field.name());
                item.put("label", field.name().substring(field.name().lastIndexOf('.') + 1));
                item.put("widget", field.widget());
                item.put("description", field.description());
                item.put("defaultValue", field.defaultValue());
                // 未标注的一律按高级处理：新增配置项默认收进高级区，避免常用区随时间不断膨胀
                item.put("level", levels.getOrDefault(field.name(), ConfigLevel.Level.ADVANCED).name());
                items.add(item);
            }

            JSONObject node = new JSONObject();
            node.put("group", group);
            node.put("title", groupTitle(group));
            node.put("fields", items);
            groups.add(node);
        });

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("groups", groups);
        return result;
    }

    /**
     * 当前配置值，取自 application.yml
     * @return 键值映射
     */
    @GetMapping("/api/values")
    public JSONObject values() {
        JSONObject result = new JSONObject();

        try {
            result.put("success", true);
            result.put("values", fileService.read());
        } catch (IOException e) {
            log.error("读取配置文件失败", e);
            result.put("success", false);
            result.put("message", "读取配置文件失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 保存配置值到 application.yml
     * @param body 待保存的键值
     * @return 保存结果
     */
    @PostMapping("/api/values")
    public JSONObject save(@RequestBody Map<String, String> body) {
        JSONObject result = new JSONObject();

        try {
            int changed = fileService.write(body);
            result.put("success", true);
            result.put("changed", changed);
            result.put("message", changed == 0 ? "没有需要保存的改动" : "已保存 " + changed + " 项，重启后生效");
        } catch (IOException e) {
            log.error("保存配置文件失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 读取 application.yml 原始内容
     * @return 原始内容
     */
    @GetMapping("/api/raw")
    public JSONObject raw() {
        JSONObject result = new JSONObject();

        try {
            result.put("success", true);
            result.put("content", fileService.readRaw());
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", "读取失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 覆盖写入 application.yml 原始内容
     * @param body 请求体，content 字段为新内容
     * @return 保存结果
     */
    @PostMapping("/api/raw")
    public JSONObject saveRaw(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();
        String content = body.getString("content");

        // 写坏配置文件会让程序下次起不来，而配置界面随之一同挂掉，远程部署时等于把自己锁在门外，
        // 因此宁可拒绝保存也不能让明显错误的内容落盘
        List<String> issues = validator.validateApplicationYaml(content);
        if (!issues.isEmpty()) {
            result.put("success", false);
            result.put("message", "配置有误，已拒绝保存");
            result.put("issues", issues);
            return result;
        }

        try {
            fileService.writeRaw(content);
            result.put("success", true);
            result.put("message", "已保存，重启后生效");
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 查询当前已配置的机器人连接信息
     * <p>
     * 用于界面回填，免得每次改一个字段都要把整套地址端口重敲一遍。
     * 响应中<strong>不含 token</strong>，详见 {@link BotConnectionTester.Connection}。
     * @return 连接信息
     */
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
     * @param body 请求体，含连接信息
     * @return 保存结果
     */
    @PostMapping("/api/setup/bot")
    public JSONObject saveBot(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, "one-bot-address", body.getString("address"));
        putIfPresent(fields, "one-bot-http-port", body.getString("httpPort"));
        putIfPresent(fields, "one-bot-websocket-port", body.getString("websocketPort"));
        putIfPresent(fields, "one-bot-http-token", body.getString("httpToken"));
        putIfPresent(fields, "one-bot-websocket-token", body.getString("websocketToken"));

        if (fields.isEmpty()) {
            result.put("success", false);
            result.put("message", "没有需要保存的内容");
            return result;
        }

        try {
            int changed = fileService.writeListItemFields("starbot.adapter.onebot.senders", 0, fields);
            result.put("success", true);
            result.put("message", "已保存 " + changed + " 项，重启后生效");
        } catch (IOException e) {
            log.error("保存机器人连接信息失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }

        return result;
    }

    private void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value.trim());
        }
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

        properties.getPush().setEnabled(enabled);

        try {
            fileService.write(Map.of("starbot.core.push.enabled", String.valueOf(enabled)));
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
     * @param body 请求体，含 platform 与 uid（uid 亦可为个人空间链接）
     * @return 主播信息
     */
    @PostMapping("/api/streamer/lookup")
    public JSONObject lookupStreamer(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Long uid = extractUid(body.getString("uid"));

        if (platform == null || platform.isBlank() || uid == null) {
            result.put("success", false);
            result.put("message", "请填写平台与 uid，也可直接粘贴个人空间链接");
            return result;
        }

        Optional<DataSourceService> service = dataSourceServiceRegistry.getDataSourceService(platform);
        if (service.isEmpty()) {
            result.put("success", false);
            result.put("message", "平台 " + platform + " 没有可用的数据源服务，请确认对应插件已加载");
            return result;
        }

        PushUser user = new PushUser();
        user.setUid(uid);
        user.setPlatform(platform);

        try {
            service.get().completePushUser(user);
        } catch (Exception e) {
            log.error("查询主播 {} 信息失败", uid, e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
            return result;
        }

        if (user.getUname() == null || user.getUname().isBlank()) {
            result.put("success", false);
            result.put("message", "未查到 uid " + uid + " 对应的主播，请确认 uid 是否正确");
            return result;
        }

        result.put("success", true);
        result.put("uid", user.getUid());
        result.put("uname", user.getUname());
        result.put("roomId", user.getRoomId());
        result.put("face", user.getFace());
        return result;
    }

    /**
     * 从输入中提取 uid，兼容直接粘贴个人空间链接
     * <p>
     * 让使用者自己去链接里抠出那串数字是没必要的一道门槛。
     * @param input 输入内容
     * @return uid，无法识别时返回 null
     */
    private Long extractUid(String input) {
        if (input == null) {
            return null;
        }

        String trimmed = input.trim();

        Matcher fromUrl = SPACE_URL_UID.matcher(trimmed);
        if (fromUrl.find()) {
            return Long.parseLong(fromUrl.group(1));
        }

        Matcher plain = PLAIN_UID.matcher(trimmed);
        return plain.matches() ? Long.parseLong(plain.group(1)) : null;
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
            item.put("defaultParams", handler.getDefaultParams());
            items.add(item);
        });

        items.sort(Comparator.comparing(item -> ((JSONObject) item).getString("className")));
        result.put("handlers", items);
        return result;
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
            boolean ok = true;
            for (Message message : messages) {
                raw = messageSender.sendNow(message);
                ok = raw != null && Integer.valueOf(0).equals(raw.getInteger("code"));
                if (!ok) {
                    break;
                }
            }

            result.put("success", ok);
            result.put("message", ok ? "已发送，请到对应会话中确认是否收到"
                    : "发送失败：" + (raw == null ? "消息被拦截器取消" : raw.getString("message")));
            result.put("raw", raw);

            if (!ok) {
                result.put("advice", "常见原因：群号或 QQ 号填错、机器人不在该群、OneBot 实现未启动、Token 不匹配。"
                        + "可对照「运行状态」页的机器人连接项排查");
            }
        } catch (Exception e) {
            log.error("发送测试消息失败", e);
            result.put("success", false);
            result.put("message", "发送失败：" + e.getMessage());
        }

        return result;
    }

    /**
     * 列出 application.yml 的历史备份
     * @return 备份文件名列表，按时间倒序
     */
    @GetMapping("/api/backups")
    public JSONObject backups() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("backups", fileService.listBackups());
        return result;
    }

    /**
     * 回滚 application.yml 至指定备份
     * @param body 请求体，name 字段为备份文件名
     * @return 回滚结果
     */
    @PostMapping("/api/backups/restore")
    public JSONObject restoreBackup(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();
        String name = body.getString("name");

        try {
            fileService.restoreBackup(name);
            result.put("success", true);
            result.put("message", "已回滚至 " + name + "，重启后生效");
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", "回滚失败: " + e.getMessage());
        }

        return result;
    }

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
    public JSONObject saveDatasource(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String content = body.getString("content");
        List<String> issues = validator.validateDatasource(content, senderService.getSenderNames());
        if (!issues.isEmpty()) {
            result.put("success", false);
            result.put("message", "推送配置有误，已拒绝保存");
            result.put("issues", issues);
            return result;
        }

        try {
            Path path = Path.of(properties.getDatasource().getJsonPath());
            if (Files.exists(path)) {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

        return result;
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
        result.put("pushEnabled", properties.getPush().isEnabled());
        // 供界面填充「发送测试消息」的推送平台下拉框，避免让使用者手打平台名
        result.put("senders", senderService.getSenderNames().stream().sorted().toList());
        return result;
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

    /**
     * 为配置分组生成中文标题
     * @param group 分组键，例如 starbot.bilibili.live
     * @return 中文标题
     */
    private String groupTitle(String group) {
        Map<String, String> titles = Map.ofEntries(
                Map.entry("starbot.core.network-thread", "核心 · 网络线程"),
                Map.entry("starbot.core.log", "核心 · 日志"),
                Map.entry("starbot.core.network", "核心 · 网络"),
                Map.entry("starbot.core.datasource", "核心 · 数据源"),
                Map.entry("starbot.core.plugin", "核心 · 插件"),
                Map.entry("starbot.core.live", "核心 · 直播"),
                Map.entry("starbot.core.paint", "核心 · 绘图"),
                Map.entry("starbot.core.mail", "核心 · 邮件告警"),
                Map.entry("starbot.core.alert", "核心 · 告警"),
                Map.entry("starbot.core.push", "核心 · 推送"),
                Map.entry("starbot.core", "核心 · 其他"),
                Map.entry("starbot.core.config-ui", "核心 · 配置界面"),
                Map.entry("starbot.adapter.onebot", "OneBot 适配器"),
                Map.entry("starbot.adapter.onebot.security", "OneBot 适配器 · 安全"),
                Map.entry("starbot.adapter.onebot.security.rate-limit", "OneBot 适配器 · 频率限制"),
                Map.entry("starbot.adapter.onebot.websocket-thread", "OneBot 适配器 · 线程"),
                Map.entry("starbot.adapter.onebot.detect", "OneBot 适配器 · 可用性检测"),
                Map.entry("starbot.adapter.onebot.extension.napcat", "NapCat 扩展"),
                Map.entry("starbot.bilibili.bilibili-thread", "哔哩哔哩 · 线程"),
                Map.entry("starbot.bilibili.debug", "哔哩哔哩 · 调试"),
                Map.entry("starbot.bilibili.network", "哔哩哔哩 · 网络"),
                Map.entry("starbot.bilibili.account", "哔哩哔哩 · 账号与凭据"),
                Map.entry("starbot.bilibili.live", "哔哩哔哩 · 直播"),
                Map.entry("starbot.bilibili.dynamic", "哔哩哔哩 · 动态")
        );

        return titles.getOrDefault(group, group);
    }
}
