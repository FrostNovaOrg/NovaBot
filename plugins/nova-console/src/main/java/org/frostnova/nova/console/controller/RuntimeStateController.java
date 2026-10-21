package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.NovaCommand;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiAudit;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.ChannelSettings;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.SessionQuietHoursService;
import org.frostnova.nova.core.service.StreamerNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 运行状态接口
 * <p>
 * 展示并修改 {@code state.json} 里由群成员在聊天里产生的那几类内容：命令开关、「@我」订阅名单。
 * 它们此前只存在于状态文件中——机器人的主人打开界面看不到任何痕迹，群里为什么突然不应答、
 * 名单里积了多少人，一概无从得知。
 * <p>
 * 界面只提供「关闭」与「移除」，不提供代人订阅：订阅以本人意愿为前提。
 * <p>
 * <b>账号绑定已停用</b>：那一族聊天命令不再注册，界面上的绑定块也随之撤掉，
 * 解绑接口回 410。已有的绑定记录<b>原样留在状态文件里</b>，只是不再显示、不再响应——
 * 删掉它们等于替使用者做了一个不可逆的决定，而这件事随时可能改回来。
 * 状态里那一栏 {@code bindings} 也一并撤掉：整族停用之后没有任何一处渲染它，
 * 留着的话，接口面上就有一栏谁也不看、谁也不敢改的记录。要查那批记录看状态文件本身。
 * <p>
 * 独立于 {@link ConfigUiController} 而非并入其中：那个类已承担配置读写、账号登录、
 * 自检与推送测试，再塞进四个接口与四项依赖只会让它更难改动。安全过滤器按
 * {@code /config/*} 注册，本类同样受其保护。
 */
@Slf4j
@NovaComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/state")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class RuntimeStateController {
    /**
     * 订阅类型的中文说法
     * <p>
     * 界面上不该出现 live / dynamic 这种只有开发者认得的词。未知类型原样展示，
     * 插件自定义了新类型时至少不会显示成空白。
     */
    private static final Map<String, String> TYPE_NAMES = Map.of("live", "开播", "dynamic", "动态");

    private final CommandDispatcher dispatcher;

    private final CommandSettingsService settings;

    private final AtSubscriptionService subscriptions;

    private final NovaStateStore store;

    private final AbstractDataSource dataSource;

    private final RevenueVisibilityService revenueVisibility;

    private final LiveDataService liveDataService;

    private final StreamerNames streamerNames;

    private final SessionQuietHoursService quietHours;

    /**
     * 「此刻在不在这个会话的静音时段里」问它：判定只留闸门里那一份
     */
    private final PushGate pushGate;

    @Autowired
    private org.frostnova.nova.core.service.DatasourceChannelStore channelStore;

    @Autowired(required = false)
    private org.frostnova.nova.core.config.ui.ConfigUiConfiguredUsers configured;

    private List<PushUser> configuredUsers() {
        return configured == null ? new ArrayList<>(dataSource.getAllUsers()) : configured.users(dataSource.getAllUsers());
    }

    private int changeChannels(JSONObject body, String platform, PushTargetType type, Long num,
                               java.util.function.Consumer<JSONObject> change) {
        return channelStore.change(platform, type, num, body.getString("streamerPlatform"), body.getLong("streamerUid"), change);
    }

    private Set<String> changeCommands(JSONObject body, String platform, PushTargetType type, Long num,
                                       List<String> keys, boolean disabled) {
        Set<String> changed = new LinkedHashSet<>();
        List<String> channelKeys = keys.stream().filter(ChannelSettings.COMMAND_KEYS::contains).toList();
        if (!channelKeys.isEmpty() && changeChannels(body, platform, type, num, target -> {
            List<String> current = target.getList("disabledCommands", String.class);
            List<String> next = new ArrayList<>(current == null ? List.of() : current);
            for (String key : channelKeys) {
                if (disabled && !next.contains(key)) { next.add(key); changed.add(key); }
                if (!disabled && next.remove(key)) changed.add(key);
            }
            if (next.isEmpty()) target.remove("disabledCommands");
            else target.put("disabledCommands", next);
        }) == 0) return null;
        for (String key : keys) {
            if (ChannelSettings.COMMAND_KEYS.contains(key)) continue;
            boolean one = disabled ? settings.disable(platform, type, num, key) : settings.enable(platform, type, num, key);
            if (one) changed.add(key);
        }
        if (!changed.isEmpty()) {
            store.save();
            log.info("{} 将会话 {}:{}:{} 的命令 {} {}", ConfigUiAccess.actor(), platform, type.getCode(), num, changed, disabled ? "禁用" : "启用");
        }
        return changed;
    }

    private static String noChannels(PushTargetType type) {
        return (type == PushTargetType.GROUP ? "这个群" : "这个好友") + "还没有配置推送，没有可改的通道";
    }

    @Autowired
    public RuntimeStateController(CommandDispatcher dispatcher, CommandSettingsService settings,
                                  AtSubscriptionService subscriptions,
                                  NovaStateStore store, AbstractDataSource dataSource,
                                  RevenueVisibilityService revenueVisibility, LiveDataService liveDataService,
                                  StreamerNames streamerNames, SessionQuietHoursService quietHours,
                                  PushGate pushGate) {
        this.dispatcher = dispatcher;
        this.settings = settings;
        this.subscriptions = subscriptions;
        this.store = store;
        this.dataSource = dataSource;
        this.revenueVisibility = revenueVisibility;
        this.liveDataService = liveDataService;
        this.streamerNames = streamerNames;
        this.quietHours = quietHours;
        this.pushGate = pushGate;
    }

    /**
     * 运行状态全量
     * <p>
     * {@code totalDataAvailable} 是整台机器的一项能力，不是某个会话的设置：没配累计存储时，
     * 依赖它的命令在群里已经不出现在菜单里，界面上也该把对应的行置灰、把条数改小。
     * 由接口给出而不是让界面自己按命令名去认——认名字的话，下一条「总」字命令进来就会被漏掉。
     * @return 命令清单、各会话的命令开关、订阅名单、未填完的推送配置与累计数据是否可用
     */
    @GetMapping
    public JSONObject state() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("commands", commands());
        result.put("sessions", sessions());
        result.put("subscriptions", subscriptionList());
        result.put("incomplete", incompleteList());
        result.put("totalDataAvailable", liveDataService.supportsTotalData());
        result.put("quietGlobal", quietGlobal());
        return result;
    }

    /**
     * 设置页那一项全局静音时段：「跟全局」那一档的摘要要写出它是几点到几点
     */
    private JSONObject quietGlobal() {
        JSONObject json = new JSONObject();
        json.put("start", pushGate.globalQuietStart());
        json.put("end", pushGate.globalQuietEnd());
        return json;
    }

    /**
     * 开关某个会话中的命令
     * @param body 请求体，含 platform、type（0 好友、1 群）、num、command 与 disabled
     * @return 操作结果；type 缺了或认不出时回 400，会话号不是正整数时回失败说明
     */
    @PostMapping("/command")
    public ResponseEntity<JSONObject> toggleCommand(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Object rawNum = body.get("num");
        String name = body.getString("command");
        boolean disabled = Boolean.TRUE.equals(body.getBoolean("disabled"));
        if (platform == null || rawNum == null || name == null) {
            return ok(fail(result, "缺少参数"));
        }
        Long num = positiveSessionNum(rawNum);
        if (num == null) {
            return ok(fail(result, "会话号「" + rawNum + "」不是正整数"));
        }
        PushTargetType type = sessionType(body.get("type"));
        if (type == null) {
            return badType(result, body.get("type"));
        }

        // 只有「禁用」才校验命令存在与否。启用等同于删掉一条记录，对已改名或已删除的
        // 命令同样应当放行——否则状态文件里的残留就成了界面清不掉的死结
        if (disabled) {
            Optional<NovaCommand> command = find(name);
            if (command.isEmpty()) {
                return ok(fail(result, "未找到命令「" + name + "」"));
            }

            // 「菜单」「启用命令」不可禁用：关掉之后群里就再没有把它开回来的入口了。
            // 界面上这类命令的开关是锁死的，此处仍要拦——接口不能只靠界面自律
            if (!command.get().disableable()) {
                return ok(fail(result, "「" + name + "」不可禁用，否则群里将无法再启用其他命令"));
            }
        }

        List<String> keys = ledgerKeys(name, disabled);
        if (changeCommands(body, platform, type, num, keys, disabled) == null) return ok(fail(result, noChannels(type)));

        result.put("success", true);
        result.put("message", brackets(keys) + "已在" + describe(type, num) + " " + (disabled ? "禁用" : "启用"));
        return ok(result);
    }

    /**
     * 成批开关某个会话中的命令
     * <p>
     * 控制台上的「组开关」与「一键恢复」按下去是<b>一个动作</b>：整组打开、把被群管理员关掉的
     * 几条一起恢复。让界面自己循环调 {@link #toggleCommand} 的话，中途失败会留下一半开一半关的
     * 局面，而屏幕上只有最后那一条的报错——使用者不知道刚才究竟改成了什么样。
     * <p>
     * <b>先全查再全改</b>：名单里只要有一条禁不得，整批都不动。改了一半再报错，
     * 等于让人从一个自己没选过的状态往回收拾。
     * @param body 请求体，含 platform、type（0 好友、1 群）、num、commands 与 disabled
     * @return 操作结果；type 缺了或认不出时回 400，会话号不是正整数时回失败说明
     */
    @PostMapping("/commands")
    public ResponseEntity<JSONObject> toggleCommands(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Object rawNum = body.get("num");
        JSONArray names = body.getJSONArray("commands");
        Boolean disabled = body.getBoolean("disabled");
        if (platform == null || rawNum == null || names == null || disabled == null) {
            return ok(fail(result, "缺少参数"));
        }
        Long num = positiveSessionNum(rawNum);
        if (num == null) {
            return ok(fail(result, "会话号「" + rawNum + "」不是正整数"));
        }
        PushTargetType type = sessionType(body.get("type"));
        if (type == null) {
            return badType(result, body.get("type"));
        }
        if (names.isEmpty()) {
            // 空名单不当作「都办完了」：调用方多半是把要改的那几条算丢了，
            // 而回一句「已改 0 条」与真的改完在界面上长得一样
            return ok(fail(result, "没有指定要改的命令"));
        }

        List<String> wanted = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.getString(i);
            if (name == null || name.isBlank()) {
                return ok(fail(result, "命令名不能为空"));
            }
            wanted.add(name);
        }

        if (Boolean.TRUE.equals(disabled)) {
            for (String name : wanted) {
                Optional<NovaCommand> command = find(name);
                if (command.isEmpty()) {
                    return ok(fail(result, "未找到命令「" + name + "」，整批未改"));
                }
                if (!command.get().disableable()) {
                    return ok(fail(result, "「" + name + "」不可禁用，否则群里将无法再启用其他命令，整批未改"));
                }
            }
        }

        List<String> keys = wanted.stream().flatMap(name -> ledgerKeys(name, Boolean.TRUE.equals(disabled)).stream()).distinct().toList();
        Set<String> changedKeys = changeCommands(body, platform, type, num, keys, Boolean.TRUE.equals(disabled));
        if (changedKeys == null) return ok(fail(result, noChannels(type)));
        int changed = (int) wanted.stream().filter(name -> ledgerKeys(name, Boolean.TRUE.equals(disabled)).stream()
                .anyMatch(changedKeys::contains)).count();

        result.put("success", true);
        result.put("changed", changed);
        result.put("message", "已在" + describe(type, num) + (Boolean.TRUE.equals(disabled) ? " 禁用 " : " 启用 ")
                + changed + " 条命令");
        return ok(result);
    }

    /**
     * 设置某个会话的金额可见性
     * <p>
     * 与命令开关不同，这一项<b>只能在这里改</b>：让群里的人自己把金额打开，
     * 等于这道设置形同虚设。
     * @param body 请求体，含 platform、type（0 好友、1 群）、num 与 visible；visible 为 null 表示恢复默认
     * @return 操作结果；type 缺了或认不出时回 400，会话号不是正整数时回失败说明
     */
    @PostMapping("/revenue")
    public ResponseEntity<JSONObject> setRevenueVisibility(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Object rawNum = body.get("num");
        if (platform == null || rawNum == null) {
            return ok(fail(result, "缺少参数"));
        }
        Long num = positiveSessionNum(rawNum);
        if (num == null) {
            return ok(fail(result, "会话号「" + rawNum + "」不是正整数"));
        }
        PushTargetType type = sessionType(body.get("type"));
        if (type == null) {
            return badType(result, body.get("type"));
        }

        Boolean visible = body.getBoolean("visible");
        int count = changeChannels(body, platform, type, num, target -> {
            if (visible == null) target.remove("revenueVisible");
            else target.put("revenueVisible", visible);
        });
        if (count == 0) return ok(fail(result, noChannels(type)));
        log.info("{} 将会话 {}:{}:{} 的金额可见性设为 {}", ConfigUiAccess.actor(), platform, type.getCode(), num, visible == null ? "默认" : visible);

        result.put("success", true);
        result.put("message", describe(type, num) + " 的金额"
                + (visible == null ? "已恢复默认" : visible ? "已设为可见" : "已隐藏"));
        return ok(result);
    }

    /**
     * 设置某个会话的静音时段
     * <p>
     * 三档：跟全局（清除记录）、本会话自己的时段、本会话不静音。存完即生效：
     * 闸门每次推送都现读这一份，不用重启。
     * <p>
     * 「自己的时段」的起止必须是 HH:mm，否则拒收：存下一档判定那一侧会忽略的时段，
     * 页面摘要就会显示一个其实不生效的时段。这里只收判定那一侧一定认得的写法（两位时、两位分），
     * 手改进状态文件的坏格式照旧由判定那一侧按全局那套规则忽略并 warn。
     * 起止相同同理：判定那一侧当没设，存下来摘要却显示一段时段，所以也拒收。
     * @param body 请求体，含 platform、type（0 好友、1 群）、num、mode（follow／custom／off，空即 follow）与 start、end
     * @return 操作结果；type 缺了或认不出时回 400
     */
    @PostMapping("/quiet")
    public ResponseEntity<JSONObject> setQuietHours(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Object rawNum = body.get("num");
        if (platform == null || rawNum == null) {
            return ok(fail(result, "缺少参数"));
        }
        Long num = positiveSessionNum(rawNum);
        if (num == null) {
            return ok(fail(result, "会话号「" + rawNum + "」不是正整数"));
        }
        PushTargetType type = sessionType(body.get("type"));
        if (type == null) {
            return badType(result, body.get("type"));
        }

        String raw = body.getString("mode");
        SessionQuietHoursService.Mode mode = SessionQuietHoursService.Mode.of(raw);
        if (raw != null && !raw.isBlank() && !mode.key().equalsIgnoreCase(raw.trim())) {
            return ok(fail(result, "无法识别的静音档位「" + raw + "」"));
        }

        String start = body.getString("start");
        String end = body.getString("end");
        if (mode == SessionQuietHoursService.Mode.CUSTOM
                && (start == null || start.isBlank() || end == null || end.isBlank())) {
            return ok(fail(result, "本会话自定义时段需填写开始和结束时间"));
        }
        if (mode == SessionQuietHoursService.Mode.CUSTOM && !(isQuietTime(start) && isQuietTime(end))) {
            return ok(fail(result, "时段格式应为 HH:mm（24 小时制，两位时两位分，如 23:00、07:30），收到「"
                    + start.trim() + "」到「" + end.trim() + "」"));
        }
        if (mode == SessionQuietHoursService.Mode.CUSTOM && start.trim().equals(end.trim())) {
            return ok(fail(result, "开始与结束时间相同（" + start.trim() + "），等于未设置；如需全天照常发送请选择「本会话不静音」，如需沿用设置页请选择「使用全局设置」"));
        }

        int count = changeChannels(body, platform, type, num, target -> {
            if (mode == SessionQuietHoursService.Mode.FOLLOW) target.remove("quietHours");
            else {
                JSONObject quiet = new JSONObject().fluentPut("mode", mode.key());
                if (mode == SessionQuietHoursService.Mode.CUSTOM) {
                    quiet.put("start", start.trim());
                    quiet.put("end", end.trim());
                }
                target.put("quietHours", quiet);
            }
        });
        if (count == 0) return ok(fail(result, noChannels(type)));

        String phrase = switch (mode) {
            case FOLLOW -> "已改为「使用全局设置」";
            case CUSTOM -> "已设为 " + start.trim() + "–" + end.trim();
            case OFF -> "已改为「本会话不静音」";
        };
        log.info("{} 将会话 {}:{}:{} 的静音时段{}", ConfigUiAccess.actor(), platform, type.getCode(), num, phrase);

        result.put("success", true);
        result.put("message", describe(type, num) + " 的静音时段" + phrase);
        return ok(result);
    }

    /**
     * 请求体里的会话类型：只认整数 0（好友）与 1（群）
     * <p>
     * 不猜：同一个号可能既是群又是好友，缺了类型就不知道改的是哪一边。
     * @param raw 请求体里原样的值
     * @return 会话类型，缺了或认不出时为 null
     */
    private static PushTargetType sessionType(Object raw) {
        if (!(raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte)) {
            return null;
        }
        long code = ((Number) raw).longValue();
        if (code == PushTargetType.FRIEND.getCode()) {
            return PushTargetType.FRIEND;
        }
        if (code == PushTargetType.GROUP.getCode()) {
            return PushTargetType.GROUP;
        }
        return null;
    }

    private ResponseEntity<JSONObject> badType(JSONObject result, Object raw) {
        String message = raw == null
                ? "缺少会话类型 type：" + PushTargetType.FRIEND.getCode() + " 为好友、"
                        + PushTargetType.GROUP.getCode() + " 为群"
                : "会话类型 type 只能是 " + PushTargetType.FRIEND.getCode() + "（好友）或 "
                        + PushTargetType.GROUP.getCode() + "（群），收到的是「" + raw + "」";
        return ResponseEntity.badRequest().body(fail(result, message));
    }

    private static ResponseEntity<JSONObject> ok(JSONObject result) {
        return ResponseEntity.ok(result);
    }

    /**
     * 回话与日志里的会话说法，如「群 12345」
     */
    private static String describe(PushTargetType type, Long num) {
        return type.getStr() + " " + num;
    }

    /**
     * 号码只认正整数：十进制数字串或 JSON 整数
     * <p>
     * 会话号、主播号、用户号都走这一条路。不能交给 getLong：它会把 12.5 截成 12、
     * true 读成 1，存到一个没人要的会话上；读不出时又成了 null，移除订阅会被当成
     * 「清空整份名单」
     * @param raw 请求体里原样的值
     * @return 号码，不是正整数时为 null
     */
    private static Long positiveSessionNum(Object raw) {
        long value;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof String text && text.matches("\\d{1,19}")) {
            try {
                value = Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return value > 0 ? value : null;
    }

    /**
     * 是否为静音时段认得的 HH:mm
     * @param value 原样的值，两头空白不算
     * @return 是时返回 true
     */
    private static boolean isQuietTime(String value) {
        String trimmed = value.trim();
        if (!trimmed.matches("\\d{2}:\\d{2}")) {
            return false;
        }
        try {
            LocalTime.parse(trimmed);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * 移除订阅
     * @param body 请求体，含 platform、num、streamerUid、type，userUid 为空时清空整份名单
     * @return 操作结果；号码不是正整数、或类型不是开播与动态时回失败说明，名单不动
     */
    @PostMapping("/subscription")
    public JSONObject removeSubscription(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Object rawNum = body.get("num");
        Object rawStreamerUid = body.get("streamerUid");
        String type = body.getString("type");
        if (platform == null || rawNum == null || rawStreamerUid == null || type == null) {
            return fail(result, "缺少参数");
        }
        Long num = positiveSessionNum(rawNum);
        if (num == null) {
            return fail(result, "会话号「" + rawNum + "」不是正整数");
        }
        Long streamerUid = positiveSessionNum(rawStreamerUid);
        if (streamerUid == null) {
            return fail(result, "主播号「" + rawStreamerUid + "」不是正整数");
        }
        // 界面只办这两类的移除：认不出的类型多半是调用方写错了名单的哪一栏，
        // 照删下去删的是另一份名单，回话却还说「已移除」
        if (!TYPE_NAMES.containsKey(type)) {
            return fail(result, "订阅类型只认开播（live）与动态（dynamic），收到的是「" + type + "」");
        }

        Object rawUserUid = body.get("userUid");
        Long userUid = rawUserUid == null ? null : positiveSessionNum(rawUserUid);
        if (rawUserUid != null && userUid == null) {
            // 用户号读不出时不能当「清空整份名单」办：那是一个字没提清空的请求
            return fail(result, "用户号「" + rawUserUid + "」不是正整数");
        }
        if (userUid == null) {
            int removed = subscriptions.clear(platform, num, streamerUid, type);
            store.save();
            log.info("{} 清空了会话 {} 中主播 {} 的{}订阅名单, 共 {} 人", ConfigUiAccess.actor(), platform + ":1:" + num, subscriptionStreamer(streamerUid), typeName(type), removed);
            result.put("success", true);
            result.put("message", "已清空名单，移除 " + removed + " 人");
            return result;
        }

        subscriptions.unsubscribe(platform, num, streamerUid, type, userUid);
        store.save();
        log.info("{} 移除了会话 {} 中 {} 对主播 {} 的{}订阅", ConfigUiAccess.actor(), platform + ":1:" + num, userUid, subscriptionStreamer(streamerUid), typeName(type));
        result.put("success", true);
        result.put("message", "已移除 " + userUid);
        return result;
    }

    private String subscriptionStreamer(Long uid) {
        return ConfigUiAudit.streamer(dataSource, "bilibili", String.valueOf(uid));
    }

    /**
     * 解除绑定（已停用）
     * <p>
     * 账号绑定整族停用后，这里回 410 而不是 404：<b>它曾经在，现在不办了</b>，
     * 这两件事对着旧界面、旧脚本或旧文档来的调用方是不同的答案。回 200 加一句
     * 「已停用」更糟——调用方会当成办成了。
     * @return 停用说明
     */
    @PostMapping("/binding")
    public ResponseEntity<JSONObject> removeBinding() {
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("message", "账号绑定已停用，已有的绑定记录保留但不再生效");
        return ResponseEntity.status(HttpStatus.GONE).body(result);
    }

    /**
     * 已注册的命令
     * <p>
     * 界面按此渲染每个会话的开关表，因此要给出全部命令而非仅被禁用的那些：
     * 「哪些命令是开着的」与「哪些被关了」同样需要一眼看清。
     * <p>
     * {@code available} 为假的那些也照样列出，只是界面该把它们置灰：它们在群里的菜单中已经不出现，
     * 直接从这张表里抹掉的话，界面就说不出「有这条命令、只是这台机器没开那项能力」。
     */
    private JSONArray commands() {
        JSONArray items = new JSONArray();

        for (NovaCommand command : dispatcher.all()) {
            JSONObject item = new JSONObject();
            item.put("name", command.name());
            item.put("description", command.description());
            item.put("category", command.category());
            item.put("usage", command.usage());
            item.put("aliases", command.aliases());
            item.put("disableable", command.disableable());
            item.put("channelScoped", command.channelScoped());
            item.put("requiresAdmin", command.requiresAdmin());
            item.put("groupOnly", command.groupOnly());
            item.put("available", command.available());
            item.put("usages", usagesOf(command));
            items.add(item);
        }

        return items;
    }

    /**
     * 一条命令底下分几种用法，各自的记账名与在这台机器上开没开
     * <p>
     * 记账名从拼写里认：命令正名与别名各问一遍，把认到的格并起来。
     * 「@名单」问着两格、「开播@名单」只问一格，并起来正好是开播与动态两格；
     * 「直播间数据」与「直播间总数据」各一格，并起来是本场与累计。
     * <p>
     * {@code available} 问的是「这一格在这台机器上开没开」而不是「这条命令有没有」：
     * 没配累计数据时累计那一半要置灰、本场那一半照常。按拼写问而不是写死哪一格靠后置能力——
     * 写死的话下一条带范围开关的命令进来又要改一遍这里的表。
     */
    private JSONArray usagesOf(NovaCommand command) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(command.usageKeys(probeOf(command.name())));
        for (String alias : command.aliases()) {
            keys.addAll(command.usageKeys(probeOf(alias)));
        }

        JSONArray usages = new JSONArray();
        for (String key : keys) {
            JSONObject usage = new JSONObject();
            usage.put("key", key);
            usage.put("available", command.availableFor(probeOf(key)));
            usages.add(usage);
        }
        return usages;
    }

    /**
     * 会话清单
     * <p>
     * 取「已配置推送的会话」与「状态文件里出现过的会话」之并集。只取前者会漏掉从推送配置里
     * 删掉、但仍有会话命令开关或提醒订阅的群。金额、静音与主播命令的旧记录只供回退使用。
     * <p>
     * 按「平台 + 会话类型 + 号」分行：同号的群与好友各有各的设置，各占一行。
     */
    private JSONArray sessions() {
        Map<String, JSONObject> sessions = new LinkedHashMap<>();
        Map<String, Set<String>> streamers = new LinkedHashMap<>();

        for (PushUser user : configuredUsers()) {
            if (!ConfigUiAccess.visible(user.getPlatform(), user.getUid())) continue;
            for (PushTarget target : user.getTargets()) {
                String key = rowKey(target.getPlatform(), target.getType(), target.getNum());
                JSONObject session = sessions.computeIfAbsent(key, k -> session(target.getPlatform(), target.getNum(),
                        target.getType(), true));
                session.getJSONArray("channels").add(JSONObject.of("streamerPlatform", user.getPlatform(),
                        "streamerUid", user.getUid(), "quietActive", pushGate.inQuietHoursFor(target)));
                streamers.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(displayName(user));
            }
        }

        List<CommandSettingsService.Disabled> disabled = settings.all().stream()
                .map(item -> new CommandSettingsService.Disabled(item.platform(), item.type(), item.num(), item.commands().stream()
                        .filter(key -> !ChannelSettings.COMMAND_KEYS.contains(key))
                        .filter(key -> !Set.of("菜单", "启用命令", "禁用命令").contains(key)).toList()))
                .filter(item -> !item.commands().isEmpty()).toList();
        for (CommandSettingsService.Disabled item : disabled) {
            sessions.computeIfAbsent(rowKey(item.platform(), item.type(), item.num()),
                    k -> session(item.platform(), item.num(), item.type(), false));
        }
        // 「@我」订阅只能在群里办，名单的键里没有会话类型，残留的按群算
        for (AtSubscriptionService.Subscription item : subscriptions.all()) {
            sessions.computeIfAbsent(rowKey(item.platform(), PushTargetType.GROUP, item.num()),
                    k -> session(item.platform(), item.num(), PushTargetType.GROUP, false));
        }

        disabled.forEach(item -> sessions.get(rowKey(item.platform(), item.type(), item.num()))
                .put("disabled", item.commands()));
        streamers.forEach((key, names) -> sessions.get(key).put("streamers", names));

        // 金额可见性没有「未设置」这一档好展示：界面上的开关要么开要么关，
        // 因此这里把默认值也算出来给它，另用 revenueExplicit 标出这份值究竟是人配的还是默认的
        sessions.forEach((key, item) -> {
            if (ConfigUiAccess.memberAccount() != null) return;
            String platform = item.getString("platform");
            Long num = item.getLong("num");
            PushTargetType type = PushTargetType.of(item.getIntValue("typeCode"));
            Boolean explicit = revenueVisibility.explicit(platform, type, num);
            item.put("revenueVisible", revenueVisibility.isVisible(platform, type, num));
            item.put("revenueExplicit", explicit != null);

            // 静音：哪一档、自己的起止，以及此刻在不在这个会话的静音时段里。
            // 最后这一项问闸门要，不让界面拿起止自己算——判定只该有一份
            SessionQuietHoursService.Setting quiet = quietHours.get(platform, type, num);
            SessionQuietHoursService.Mode mode = quiet == null ? SessionQuietHoursService.Mode.FOLLOW : quiet.mode();
            item.put("quietMode", mode.key());
            item.put("quietStart", quiet == null ? null : quiet.start());
            item.put("quietEnd", quiet == null ? null : quiet.end());
            item.put("quietActive", pushGate.inQuietHoursFor(platform, type, num));
        });

        List<JSONObject> sorted = sessions.values().stream()
                .filter(item -> ConfigUiAccess.memberAccount() == null || !item.getJSONArray("channels").isEmpty())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        sorted.sort(Comparator.comparing((JSONObject item) -> item.getString("platform"))
                .thenComparing(item -> item.getLongValue("num"))
                .thenComparing(item -> -item.getIntValue("typeCode")));

        JSONArray items = new JSONArray();
        items.addAll(sorted);
        return items;
    }

    /**
     * 会话清单里认一行用的键：「平台:类型码:号」，类型缺失按未知算
     */
    private static String rowKey(String platform, PushTargetType type, Long num) {
        return platform + ":" + typeCode(type) + ":" + num;
    }

    private static int typeCode(PushTargetType type) {
        return (type == null ? PushTargetType.UNKNOWN : type).getCode();
    }

    private JSONObject session(String platform, Long num, PushTargetType type, boolean configured) {
        JSONObject item = new JSONObject();
        item.put("platform", platform);
        item.put("num", num);
        item.put("type", type == null ? null : type.getStr());
        item.put("typeCode", typeCode(type));
        item.put("configured", configured);
        item.put("streamers", List.of());
        item.put("channels", new JSONArray());
        item.put("disabled", List.of());
        item.put("menuHidden", menuHidden(platform, num, type));
        item.put("menuNotes", menuNotes(platform, num, type));
        return item;
    }

    /**
     * 这个会话的菜单里<b>不</b>列哪几条命令
     * <p>
     * 控制台要把这几行置灰、并把摘要从 14 改少，靠的就是这一份：群聊在本群这类通知
     * 全配成 @全体成员时从 14 改成 12，私聊则因八条仅限群聊的命令不列而从 14 改成 6。
     * <b>由这里算而不是让界面自己判</b>：「本群这类通知全配成 @全体成员 时藏掉三条订阅命令」
     * 这条规则在命令那一侧只有一份实现（{@code availableIn}），抄一份到界面上之后，
     * 改了那一份的那天控制台仍按旧规矩画，而两边的代码看起来都对。
     * <p>
     * 类型未知的会话（推送配置里那条目标没写类型）返回 {@code null} 而不是空表：
     * 它没有会话类型，构不出上下文，答不了这个问题。只在状态文件里留着的会话键里带着类型，照类型答。空表会被读成「一条都不藏」——
     * 而<b>答不了与都列着长得一样</b>正是要避免的。
     * @param platform 推送平台
     * @param num 会话号
     * @param type 会话类型，未知时为 null
     * @return 不列进菜单的命令名，答不了时为 null
     */
    private List<String> menuHidden(String platform, Long num, PushTargetType type) {
        if (type == null) {
            return null;
        }

        List<String> hidden = new ArrayList<>();
        for (NovaCommand command : dispatcher.all()) {
            if (!command.availableIn(context(platform, num, type, command))) {
                hidden.add(command.name());
            }
        }
        return hidden;
    }

    /**
     * 菜单里跟在某条命令后面的那句会话相关的说明
     * <p>
     * 「本群开播通知会先 @全体成员，@ 不成时才按这份名单 @ 人」这类话由命令自己给。
     * 控制台照原话显示、不另拼一遍：同一句话两边各写一份，改了一处忘了另一处的时候，
     * 群里听见的与控制台上写的就不是同一件事了。
     * @return 命令名 → 说明，没有说明的不进表
     */
    private JSONObject menuNotes(String platform, Long num, PushTargetType type) {
        JSONObject notes = new JSONObject();
        if (type == null) {
            return notes;
        }

        for (NovaCommand command : dispatcher.all()) {
            String note = command.menuNote(context(platform, num, type, command));
            if (note != null && !note.isBlank()) {
                notes.put(command.name(), note);
            }
        }
        return notes;
    }

    /**
     * 替某条命令造一个「就在这个会话里」的上下文
     * <p>
     * 发送者留空、参数留空：问的是「这条命令在这个会话里还有没有意义」，
     * 那与谁发的、带了什么参数无关。管理员一律按否——多列一条的代价是使用者点开发现用不了，
     * 而反过来把一条真能用的藏掉，界面上不会有任何东西提示它去哪儿了。
     */
    private CommandContext context(String platform, Long num, PushTargetType type, NovaCommand command) {
        return new CommandContext(platform, type, num, null, command.name(), List.of(), "");
    }

    /**
     * 按拼写造一个「就这一句」的上下文
     * <p>
     * 首词是命令名或别名，剩下的当参数：「直播间数据 总」里那个「总」是范围开关、不是命令名的一部分，
     * 拆开才认得出问的是哪一格。与群里那一面认「用的是哪一格」用的是同一套拆法。
     * <p>
     * 会话与发送者留空：问的是「这一句认到哪一格、那一格在这台机器上开没开」，
     * 与谁在哪个群发的无关。
     */
    private static CommandContext probeOf(String typed) {
        String trimmed = typed.trim();
        String[] parts = trimmed.split("\\s+");
        List<String> args = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            args.add(parts[i]);
        }
        return new CommandContext(null, PushTargetType.GROUP, null, null, parts[0], args, trimmed);
    }

    /**
     * 按名字或别名找命令
     * <p>
     * 认首词：点名里那个范围开关（「直播间数据 总」的「总」）不是名字的一部分，
     * 整串拿去找会一无所获。旧表只认正名，别名一进来就报「未找到命令」——
     * 而别名正是点名时最常见的写法。
     */
    private Optional<NovaCommand> find(String typed) {
        String head = typed.trim().split("\\s+")[0];
        return dispatcher.all().stream()
                .filter(item -> item.name().equals(head) || item.aliases().contains(head))
                .findFirst();
    }

    /**
     * 这一栏改完，账上要动哪几个名
     * <p>
     * 「禁用」落到<b>用法名</b>上：关掉的是用法，拼写只是问法。「@名单」一句问着两格就关两格——
     * 只记正名的话群里那两种问法照通，控制台这扇门就成了摆设。
     * <p>
     * 「启用」与群里「启用命令」同一口径：名下各格一起开回来。一句对多格时
     * （「@名单」），回话里写的就是那几格。只删打出来的这一名的话，两格仍关着，
     * 回话却说已经启用。一句只对一格时（「直播间数据 总」与「直播间总数据」
     * 是同一格的两种拼写），把打出来的这一名一并删掉，两种拼写都开得回来。
     * 找不到命令时仍按打出来的这一名删：启用就是清掉一条记录，改过名留下的残留也要清得掉。
     */
    private List<String> ledgerKeys(String name, boolean disabled) {
        List<String> keys = new ArrayList<>(find(name)
                .map(command -> command.usageKeys(probeOf(name)))
                .orElse(List.of()));
        if (disabled) {
            return keys;
        }
        if (keys.isEmpty()) {
            return List.of(name);
        }
        if (keys.size() == 1 && !keys.contains(name)) {
            keys.add(name);
        }
        return keys;
    }

    /**
     * 每一格各自一对书名号
     * <p>
     * 两格并排时若共用一对（中间拿「】【」拼），后一格看上去就成了【】里的话，
     * 读起来像一个长名字。
     */
    private static String brackets(List<String> keys) {
        StringBuilder text = new StringBuilder();
        for (String key : keys) {
            text.append('「').append(key).append('」');
        }
        return text.toString();
    }

    /**
     * 订阅名单，附上主播昵称
     */
    private JSONArray subscriptionList() {
        JSONArray items = new JSONArray();

        for (AtSubscriptionService.Subscription item : subscriptions.all()) {
            if (!ConfigUiAccess.visible("bilibili", item.streamerUid())) continue;
            JSONObject json = new JSONObject();
            json.put("platform", item.platform());
            json.put("num", item.num());
            json.put("streamerUid", item.streamerUid());
            json.put("streamerName", streamerName(item.streamerUid()));
            json.put("type", item.type());
            json.put("typeName", typeName(item.type()));
            json.put("users", item.users());
            items.add(json);
        }

        return items;
    }

    /**
     * 尚未填完、因而未被加载的推送配置
     * <p>
     * 这几条不会出现在会话清单里——它们没被加载，本来也构不成会话。可正因如此，
     * 不把它们说出来的话，页面上就只是一片空白，而使用者刚刚明明配过：
     * <b>最需要一句解释的正是这种时候。</b>
     * <p>
     * 说明由数据源那一侧给，不在这里另拼一遍：哪条算没填完、还差哪个字段，
     * 都是读配置的人才知道的事，两处各写一份迟早会对不上。
     */
    private JSONArray incompleteList() {
        JSONArray items = new JSONArray();

        JSONArray raw = configured == null ? new JSONArray() : configured.raw();
        for (AbstractDataSource.IncompleteEntry entry : dataSource.getIncompleteEntries()) {
            if (ConfigUiAccess.memberAccount() != null) {
                int index = entry.index() - 1;
                if (index < 0 || index >= raw.size() || !(raw.get(index) instanceof JSONObject user)
                        || !ConfigUiAccess.visible(user.getString("platform"), org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId.positiveDecimal(user.get("uid")))) continue;
            }
            JSONObject json = new JSONObject();
            json.put("index", entry.index());
            json.put("fields", entry.fields());
            json.put("message", entry.describe());
            items.add(json);
        }

        return items;
    }

    /**
     * 按 UID 找主播昵称
     * <p>
     * 只在已配置的主播里找：订阅名单里的 uid 必然来自某位已配置主播，
     * 若找不到说明该主播已从推送配置中移除，此时保留 uid 本身比编造一个昵称诚实。
     */
    private String streamerName(long uid) {
        return dataSource.getAllUsers().stream()
                .filter(user -> "bilibili".equals(user.getPlatform()) && user.getUid() != null && user.getUid() == uid)
                .map(this::displayName)
                .findFirst()
                .orElse(null);
    }

    /**
     * 主播的展示名
     * <p>
     * <b>昵称为空串而非 null。</b>推送配置里只写 uid，昵称要等程序去直播平台查回来；
     * 查回来之前（尤其是刚启动、或直播平台未登录时）它是空串。只判空指针会让界面显示成
     * 「推送：」后面什么都没有，多位主播还会因为空串相同而合并成一个。
     * 查不回来时先退回最近一场归档里的昵称（与主播页一致），连那也没有才退回 uid。
     */
    private String displayName(PushUser user) {
        String uname = streamerNames.uname(user);
        return uname == null || uname.isBlank() ? String.valueOf(user.getUid()) : uname;
    }

    private String typeName(String type) {
        return TYPE_NAMES.getOrDefault(type, type);
    }

    private JSONObject fail(JSONObject result, String message) {
        result.put("success", false);
        result.put("message", message);
        return result;
    }
}
