package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.service.UserBindingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 * 展示并修改 {@code state.json} 里的三类内容：命令开关、「@我」订阅名单、账号绑定。
 * 它们**都由群成员在聊天里产生**，此前只存在于状态文件中——机器人的主人打开界面
 * 看不到任何痕迹，群里为什么突然不应答、名单里积了多少人、谁绑了哪个 uid，一概无从得知。
 * <p>
 * 界面只提供「关闭」与「移除」，不提供代人订阅或代人绑定：这两件事都以本人意愿为前提，
 * 尤其绑定本就无法验证归属，替别人建立绑定等于凭空造出一条看似可信的对应关系。
 * <p>
 * 独立于 {@link ConfigUiController} 而非并入其中：那个类已承担配置读写、账号登录、
 * 自检与推送测试，再塞进四个接口与四项依赖只会让它更难改动。安全过滤器按
 * {@code /config/*} 注册，本类同样受其保护。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/state")
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
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

    private final UserBindingService bindings;

    private final StarBotStateStore store;

    private final AbstractDataSource dataSource;

    @Autowired
    public RuntimeStateController(CommandDispatcher dispatcher, CommandSettingsService settings,
                                  AtSubscriptionService subscriptions, UserBindingService bindings,
                                  StarBotStateStore store, AbstractDataSource dataSource) {
        this.dispatcher = dispatcher;
        this.settings = settings;
        this.subscriptions = subscriptions;
        this.bindings = bindings;
        this.store = store;
        this.dataSource = dataSource;
    }

    /**
     * 运行状态全量
     * @return 命令清单、各会话的命令开关、订阅名单与绑定关系
     */
    @GetMapping
    public JSONObject state() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("commands", commands());
        result.put("sessions", sessions());
        result.put("subscriptions", subscriptionList());
        result.put("bindings", bindingList());
        return result;
    }

    /**
     * 开关某个会话中的命令
     * @param body 请求体，含 platform、num、command 与 disabled
     * @return 操作结果
     */
    @PostMapping("/command")
    public JSONObject toggleCommand(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Long num = body.getLong("num");
        String name = body.getString("command");
        boolean disabled = Boolean.TRUE.equals(body.getBoolean("disabled"));
        if (platform == null || num == null || name == null) {
            return fail(result, "缺少参数");
        }

        // 只有「禁用」才校验命令存在与否。启用等同于删掉一条记录，对已改名或已删除的
        // 命令同样应当放行——否则状态文件里的残留就成了界面清不掉的死结
        if (disabled) {
            Optional<StarBotCommand> command = dispatcher.all().stream()
                    .filter(item -> item.name().equals(name))
                    .findFirst();
            if (command.isEmpty()) {
                return fail(result, "未找到命令「" + name + "」");
            }

            // 「菜单」「启用命令」不可禁用：关掉之后群里就再没有把它开回来的入口了。
            // 界面上这类命令的开关是锁死的，此处仍要拦——接口不能只靠界面自律
            if (!command.get().disableable()) {
                return fail(result, "「" + name + "」不可禁用，否则群里将无法再启用其他命令");
            }
        }

        boolean changed = disabled
                ? settings.disable(platform, num, name)
                : settings.enable(platform, num, name);
        if (changed) {
            // 立即落盘。状态存储平时靠定时保存，而这里的改动来自人的一次明确操作，
            // 若此刻进程被杀掉，使用者会认为「我明明关了」
            store.save();
            log.info("配置界面{}了会话 {} 中的命令 {}", disabled ? "禁用" : "启用", num, name);
        }

        result.put("success", true);
        result.put("message", "「" + name + "」已在 " + num + " " + (disabled ? "禁用" : "启用"));
        return result;
    }

    /**
     * 移除订阅
     * @param body 请求体，含 platform、num、streamerUid、type，userUid 为空时清空整份名单
     * @return 操作结果
     */
    @PostMapping("/subscription")
    public JSONObject removeSubscription(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Long num = body.getLong("num");
        Long streamerUid = body.getLong("streamerUid");
        String type = body.getString("type");
        if (platform == null || num == null || streamerUid == null || type == null) {
            return fail(result, "缺少参数");
        }

        Long userUid = body.getLong("userUid");
        if (userUid == null) {
            int removed = subscriptions.clear(platform, num, streamerUid, type);
            store.save();
            log.info("配置界面清空了会话 {} 中主播 {} 的{}订阅名单, 共 {} 人", num, streamerUid, typeName(type), removed);
            result.put("success", true);
            result.put("message", "已清空名单，移除 " + removed + " 人");
            return result;
        }

        subscriptions.unsubscribe(platform, num, streamerUid, type, userUid);
        store.save();
        log.info("配置界面移除了会话 {} 中 {} 对主播 {} 的{}订阅", num, userUid, streamerUid, typeName(type));
        result.put("success", true);
        result.put("message", "已移除 " + userUid);
        return result;
    }

    /**
     * 解除绑定
     * @param body 请求体，含 pushPlatform、livePlatform 与 senderUid
     * @return 操作结果
     */
    @PostMapping("/binding")
    public JSONObject removeBinding(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String pushPlatform = body.getString("pushPlatform");
        String livePlatform = body.getString("livePlatform");
        Long senderUid = body.getLong("senderUid");
        if (pushPlatform == null || livePlatform == null || senderUid == null) {
            return fail(result, "缺少参数");
        }

        boolean removed = bindings.unbind(pushPlatform, livePlatform, senderUid);
        if (removed) {
            store.save();
            log.info("配置界面解除了 {} 在 {} 的绑定", senderUid, livePlatform);
        }

        result.put("success", true);
        result.put("message", removed ? "已解除 " + senderUid + " 的绑定" : senderUid + " 当前并未绑定");
        return result;
    }

    /**
     * 已注册的命令
     * <p>
     * 界面按此渲染每个会话的开关表，因此要给出全部命令而非仅被禁用的那些：
     * 「哪些命令是开着的」与「哪些被关了」同样需要一眼看清。
     */
    private JSONArray commands() {
        JSONArray items = new JSONArray();

        for (StarBotCommand command : dispatcher.all()) {
            JSONObject item = new JSONObject();
            item.put("name", command.name());
            item.put("description", command.description());
            item.put("category", command.category());
            item.put("usage", command.usage());
            item.put("aliases", command.aliases());
            item.put("disableable", command.disableable());
            item.put("requiresAdmin", command.requiresAdmin());
            item.put("groupOnly", command.groupOnly());
            items.add(item);
        }

        return items;
    }

    /**
     * 会话清单
     * <p>
     * 取「已配置推送的会话」与「状态文件里出现过的会话」之并集。只取前者会漏掉从推送配置里
     * 删掉、但状态仍残留的群——那些残留正是最需要被看见的：命令仍是关着的，一旦重新配置推送就立刻生效。
     */
    private JSONArray sessions() {
        Map<String, JSONObject> sessions = new LinkedHashMap<>();
        Map<String, Set<String>> streamers = new LinkedHashMap<>();

        for (PushUser user : dataSource.getAllUsers()) {
            for (PushTarget target : user.getTargets()) {
                String key = target.getPlatform() + ":" + target.getNum();
                sessions.computeIfAbsent(key, k -> session(target.getPlatform(), target.getNum(),
                        target.getType() == null ? null : target.getType().getStr(), true));
                streamers.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(displayName(user));
            }
        }

        List<CommandSettingsService.Disabled> disabled = settings.all();
        for (CommandSettingsService.Disabled item : disabled) {
            sessions.computeIfAbsent(item.platform() + ":" + item.num(),
                    k -> session(item.platform(), item.num(), null, false));
        }
        for (AtSubscriptionService.Subscription item : subscriptions.all()) {
            sessions.computeIfAbsent(item.platform() + ":" + item.num(),
                    k -> session(item.platform(), item.num(), null, false));
        }

        disabled.forEach(item -> sessions.get(item.platform() + ":" + item.num())
                .put("disabled", item.commands()));
        streamers.forEach((key, names) -> sessions.get(key).put("streamers", names));

        List<JSONObject> sorted = new ArrayList<>(sessions.values());
        sorted.sort(Comparator.comparing((JSONObject item) -> item.getString("platform"))
                .thenComparing(item -> item.getLongValue("num")));

        JSONArray items = new JSONArray();
        items.addAll(sorted);
        return items;
    }

    private JSONObject session(String platform, Long num, String type, boolean configured) {
        JSONObject item = new JSONObject();
        item.put("platform", platform);
        item.put("num", num);
        item.put("type", type);
        item.put("configured", configured);
        item.put("streamers", List.of());
        item.put("disabled", List.of());
        return item;
    }

    /**
     * 订阅名单，附上主播昵称
     */
    private JSONArray subscriptionList() {
        JSONArray items = new JSONArray();

        for (AtSubscriptionService.Subscription item : subscriptions.all()) {
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
     * 绑定关系
     */
    private JSONArray bindingList() {
        JSONArray items = new JSONArray();

        for (UserBindingService.Binding item : bindings.all()) {
            JSONObject json = new JSONObject();
            json.put("pushPlatform", item.pushPlatform());
            json.put("livePlatform", item.livePlatform());
            json.put("senderUid", item.senderUid());
            json.put("liveUid", item.liveUid());
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
                .filter(user -> user.getUid() != null && user.getUid() == uid)
                .map(RuntimeStateController::displayName)
                .findFirst()
                .orElse(null);
    }

    /**
     * 主播的展示名
     * <p>
     * <b>昵称为空串而非 null。</b>推送配置里只写 uid，昵称要等程序去直播平台查回来；
     * 查回来之前（尤其是刚启动、或直播平台未登录时）它是空串。只判空指针会让界面显示成
     * 「推送：」后面什么都没有，多位主播还会因为空串相同而合并成一个——退回 uid 至少认得出是谁。
     */
    private static String displayName(PushUser user) {
        String uname = user.getUname();
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
