package org.frostnova.nova.adapter.onebot.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.service.OneBotTargetDirectory;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「能推给谁」的清单
 * <p>
 * 配推送目标时不该再有手填号码的格子——填错一位数不会有任何报错，只是消息发去了别处，
 * 或者哪儿也没去。这两支接口把机器人自己知道的名单交给界面，由使用者从里面挑。
 *
 * <h2>路径为什么挂在控制台底下</h2>
 * 挂在 {@link ConfigUiController#BASE_PATH} 之下，这份名单<b>天生就在控制台那道门后面</b>：
 * 来源 IP 白名单、令牌或口令、使用协议三道闸一道不少。它列的是群号与好友账号，
 * 属于他人的个人信息，不该另建一套自己的鉴权——另建一套的下场是两套规则迟早对不上，
 * 而对不上的那一侧通常是新写的这一套。
 */
@Slf4j
@RestController
@NovaComponent
public class OneBotTargetController {
    static final String TARGETS_PATH = ConfigUiController.BASE_PATH + "/api/onebot/targets";

    static final String REFRESH_PATH = TARGETS_PATH + "/refresh";

    private static final String TYPE_GROUP = "group";

    private static final String TYPE_FRIEND = "friend";

    private final OneBotTargetDirectory directory;

    /**
     * 只读，用来标出哪些群／好友已经配过了
     * <p>
     * 挑选界面写不进数据源：这一支只回答「配没配过」，配与不配由推送页那一侧负责。
     */
    private final AbstractDataSource dataSource;

    public OneBotTargetController(OneBotTargetDirectory directory, AbstractDataSource dataSource) {
        this.directory = directory;
        this.dataSource = dataSource;
    }

    /**
     * 列出可选的推送目标
     * @param type {@code group} 或 {@code friend}
     * @param q 按名与号筛选的关键字，留空表示不筛
     * @return 名单
     */
    @GetMapping(value = TARGETS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JSONObject> targets(@RequestParam(required = false) String type,
                                              @RequestParam(required = false) String q) {
        boolean group = TYPE_GROUP.equals(type);
        if (!group && !TYPE_FRIEND.equals(type)) {
            JSONObject bad = new JSONObject();
            bad.put("success", false);
            bad.put("message", "type 只能是 " + TYPE_GROUP + " 或 " + TYPE_FRIEND);
            return ResponseEntity.badRequest().body(bad);
        }

        List<OneBotTargetDirectory.Snapshot> snapshots = directory.snapshots(false);
        Set<String> configured = configuredTargets();
        String keyword = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);

        JSONArray items = new JSONArray();
        for (OneBotTargetDirectory.Snapshot snapshot : snapshots) {
            if (group) {
                for (OneBotTargetDirectory.Group row : snapshot.groups()) {
                    if (!matches(keyword, row.num(), row.name())) {
                        continue;
                    }

                    JSONObject item = new JSONObject();
                    item.put("sender", snapshot.sender());
                    item.put("num", row.num());
                    item.put("name", row.name());
                    item.put("memberCount", row.memberCount());
                    item.put("admin", row.admin());
                    item.put("configured", configured.contains(key(snapshot.sender(), PushTargetType.GROUP, row.num())));
                    items.add(item);
                }
            } else {
                for (OneBotTargetDirectory.Friend row : snapshot.friends()) {
                    if (!matches(keyword, row.num(), row.nickname(), row.remark())) {
                        continue;
                    }

                    JSONObject item = new JSONObject();
                    item.put("sender", snapshot.sender());
                    item.put("num", row.num());
                    item.put("nickname", row.nickname());
                    item.put("remark", row.remark());
                    item.put("configured", configured.contains(key(snapshot.sender(), PushTargetType.FRIEND, row.num())));
                    items.add(item);
                }
            }
        }

        JSONObject result = summary(snapshots);
        result.put("success", true);
        result.put("type", type);
        result.put("items", items);
        return ResponseEntity.ok(result);
    }

    /**
     * 重新去问一遍 OneBot
     * <p>
     * 用 POST 而不是给列表接口加一个 {@code refresh=1}：它会真的向 OneBot 发一整轮请求，
     * 而 GET 会被浏览器、缓存与预取随手重放。
     * @return 各推送平台的取回情况
     */
    @PostMapping(value = REFRESH_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public JSONObject refresh() {
        JSONObject result = summary(directory.snapshots(true));
        result.put("success", true);
        return result;
    }

    /**
     * 表级的「什么时候取的」与「是不是已经取不到了」
     * <p>
     * 多个推送平台各有各的名单，而界面上是一张表，因此表级的两个字段<b>往保守里取</b>：
     * 时刻取最早的那一个，只要有一个平台取不到就整表标为已过期。反过来（取最新、全过期才算过期）
     * 会让一张半新半旧的表看起来是全新的。
     * <p>
     * 同时逐平台列出来：只报一个「已过期」而不说是哪个平台过期，使用者无从下手。
     */
    private JSONObject summary(List<OneBotTargetDirectory.Snapshot> snapshots) {
        JSONArray senders = new JSONArray();
        Instant oldest = null;
        boolean stale = false;

        for (OneBotTargetDirectory.Snapshot snapshot : snapshots) {
            JSONObject item = new JSONObject();
            item.put("sender", snapshot.sender());
            item.put("fetchedAt", snapshot.fetchedAt() == null ? null : snapshot.fetchedAt().toString());
            item.put("stale", snapshot.stale());
            item.put("message", snapshot.message());
            item.put("groups", snapshot.groups().size());
            item.put("friends", snapshot.friends().size());
            senders.add(item);

            stale = stale || snapshot.stale();
            if (snapshot.fetchedAt() != null && (oldest == null || snapshot.fetchedAt().isBefore(oldest))) {
                oldest = snapshot.fetchedAt();
            }
        }

        JSONObject result = new JSONObject();
        result.put("fetchedAt", oldest == null ? null : oldest.toString());
        result.put("stale", stale);
        result.put("senders", senders);
        return result;
    }

    /**
     * 数据源里已经配过的推送目标
     * <p>
     * 每次请求现算，不缓存：推送配置随时可能在另一个页面上改掉，而缓存住的「已配置」
     * 会让使用者对着一个已经删掉的目标以为自己配过了。
     * @return 「推送平台|类型|号」的集合
     */
    private Set<String> configuredTargets() {
        Set<String> keys = new HashSet<>();

        for (PushUser user : dataSource.getAllUsers()) {
            for (PushTarget target : user.getTargets()) {
                if (target.getPlatform() == null || target.getType() == null || target.getNum() == null) {
                    continue;
                }

                keys.add(key(target.getPlatform(), target.getType(), target.getNum()));
            }
        }

        return keys;
    }

    private String key(String sender, PushTargetType type, long num) {
        return sender + "|" + type.getCode() + "|" + num;
    }

    /**
     * 关键字是否命中某一条
     * <p>
     * 号与名一起筛：使用者手里有的可能是群号，也可能只记得群名的一半。
     */
    private boolean matches(String keyword, long num, String... names) {
        if (keyword.isEmpty()) {
            return true;
        }

        if (String.valueOf(num).contains(keyword)) {
            return true;
        }

        for (String name : names) {
            if (name != null && name.toLowerCase(Locale.ROOT).contains(keyword)) {
                return true;
            }
        }

        return false;
    }
}
