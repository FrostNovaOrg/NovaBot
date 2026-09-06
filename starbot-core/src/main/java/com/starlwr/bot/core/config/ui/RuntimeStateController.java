package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import com.starlwr.bot.core.service.StarBotStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    private final StarBotStateStore store;

    private final AbstractDataSource dataSource;

    private final RevenueVisibilityService revenueVisibility;

    private final LiveDataService liveDataService;

    @Autowired
    public RuntimeStateController(CommandDispatcher dispatcher, CommandSettingsService settings,
                                  AtSubscriptionService subscriptions,
                                  StarBotStateStore store, AbstractDataSource dataSource,
                                  RevenueVisibilityService revenueVisibility, LiveDataService liveDataService) {
        this.dispatcher = dispatcher;
        this.settings = settings;
        this.subscriptions = subscriptions;
        this.store = store;
        this.dataSource = dataSource;
        this.revenueVisibility = revenueVisibility;
        this.liveDataService = liveDataService;
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
     * 成批开关某个会话中的命令
     * <p>
     * 控制台上的「组开关」与「一键恢复」按下去是<b>一个动作</b>：整组打开、把被群管理员关掉的
     * 几条一起恢复。让界面自己循环调 {@link #toggleCommand} 的话，中途失败会留下一半开一半关的
     * 局面，而屏幕上只有最后那一条的报错——使用者不知道刚才究竟改成了什么样。
     * <p>
     * <b>先全查再全改</b>：名单里只要有一条禁不得，整批都不动。改了一半再报错，
     * 等于让人从一个自己没选过的状态往回收拾。
     * @param body 请求体，含 platform、num、commands 与 disabled
     * @return 操作结果
     */
    @PostMapping("/commands")
    public JSONObject toggleCommands(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Long num = body.getLong("num");
        JSONArray names = body.getJSONArray("commands");
        Boolean disabled = body.getBoolean("disabled");
        if (platform == null || num == null || names == null || disabled == null) {
            return fail(result, "缺少参数");
        }
        if (names.isEmpty()) {
            // 空名单不当作「都办完了」：调用方多半是把要改的那几条算丢了，
            // 而回一句「已改 0 条」与真的改完在界面上长得一样
            return fail(result, "没有指定要改的命令");
        }

        List<String> wanted = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.getString(i);
            if (name == null || name.isBlank()) {
                return fail(result, "命令名不能为空");
            }
            wanted.add(name);
        }

        if (Boolean.TRUE.equals(disabled)) {
            for (String name : wanted) {
                Optional<StarBotCommand> command = dispatcher.all().stream()
                        .filter(item -> item.name().equals(name))
                        .findFirst();
                if (command.isEmpty()) {
                    return fail(result, "未找到命令「" + name + "」，整批未改");
                }
                if (!command.get().disableable()) {
                    return fail(result, "「" + name + "」不可禁用，否则群里将无法再启用其他命令，整批未改");
                }
            }
        }

        int changed = 0;
        for (String name : wanted) {
            boolean done = Boolean.TRUE.equals(disabled)
                    ? settings.disable(platform, num, name)
                    : settings.enable(platform, num, name);
            if (done) {
                changed++;
            }
        }
        if (changed > 0) {
            // 立即落盘，理由同 toggleCommand
            store.save();
            log.info("配置界面成批{}了会话 {} 中的 {} 条命令", Boolean.TRUE.equals(disabled) ? "禁用" : "启用", num, changed);
        }

        result.put("success", true);
        result.put("changed", changed);
        result.put("message", "已在 " + num + (Boolean.TRUE.equals(disabled) ? " 禁用 " : " 启用 ")
                + changed + " 条命令");
        return result;
    }

    /**
     * 设置某个会话的金额可见性
     * <p>
     * 与命令开关不同，这一项<b>只能在这里改</b>：让群里的人自己把金额打开，
     * 等于这道设置形同虚设。
     * @param body 请求体，含 platform、num 与 visible；visible 为 null 表示恢复默认
     * @return 操作结果
     */
    @PostMapping("/revenue")
    public JSONObject setRevenueVisibility(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        Long num = body.getLong("num");
        if (platform == null || num == null) {
            return fail(result, "缺少参数");
        }

        Boolean visible = body.getBoolean("visible");
        revenueVisibility.set(platform, num, visible);
        // 立即落盘：这是人的一次明确操作，进程此刻被杀掉会让人以为「我明明关了」
        store.save();
        log.info("配置界面将会话 {} 的金额可见性设为 {}", num, visible == null ? "默认" : visible);

        result.put("success", true);
        result.put("message", num + " 的金额" + (visible == null ? "已恢复默认" : visible ? "已设为可见" : "已隐藏"));
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
            item.put("available", command.available());
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
                        target.getType(), true));
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

        for (RevenueVisibilityService.Setting item : revenueVisibility.all()) {
            sessions.computeIfAbsent(item.platform() + ":" + item.num(),
                    k -> session(item.platform(), item.num(), null, false));
        }

        disabled.forEach(item -> sessions.get(item.platform() + ":" + item.num())
                .put("disabled", item.commands()));
        streamers.forEach((key, names) -> sessions.get(key).put("streamers", names));

        // 金额可见性没有「未设置」这一档好展示：界面上的开关要么开要么关，
        // 因此这里把默认值也算出来给它，另用 revenueExplicit 标出这份值究竟是人配的还是默认的
        sessions.forEach((key, item) -> {
            Boolean explicit = revenueVisibility.explicit(item.getString("platform"), item.getLong("num"));
            item.put("revenueVisible", explicit != null ? explicit : defaultRevenue(item.getString("type")));
            item.put("revenueExplicit", explicit != null);
        });

        List<JSONObject> sorted = new ArrayList<>(sessions.values());
        sorted.sort(Comparator.comparing((JSONObject item) -> item.getString("platform"))
                .thenComparing(item -> item.getLongValue("num")));

        JSONArray items = new JSONArray();
        items.addAll(sorted);
        return items;
    }

    /**
     * 未显式设置时的金额可见性
     * <p>
     * 会话清单里的 type 存的是给人看的中文（「群」「好友」），只有来自推送配置的会话才有；
     * 状态文件里残留的会话取不到类型，此时按群聊处理——不确定就按更保守的那一边。
     */
    private boolean defaultRevenue(String type) {
        return PushTargetType.FRIEND.getStr().equals(type);
    }

    private JSONObject session(String platform, Long num, PushTargetType type, boolean configured) {
        JSONObject item = new JSONObject();
        item.put("platform", platform);
        item.put("num", num);
        item.put("type", type == null ? null : type.getStr());
        item.put("configured", configured);
        item.put("streamers", List.of());
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
     * 类型未知的会话（推送配置里已经没有、只在状态文件里留着的那些）返回 {@code null} 而不是空表：
     * 它没有会话类型，构不出上下文，答不了这个问题。空表会被读成「一条都不藏」——
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
        for (StarBotCommand command : dispatcher.all()) {
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

        for (StarBotCommand command : dispatcher.all()) {
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
    private CommandContext context(String platform, Long num, PushTargetType type, StarBotCommand command) {
        return new CommandContext(platform, type, num, null, command.name(), List.of(), "");
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

        for (AbstractDataSource.IncompleteEntry entry : dataSource.getIncompleteEntries()) {
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
