package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 控制台可选的推送目标名单与精确查号。 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiPushTargetsController {
    static final String TARGETS_PATH = ConfigUiController.BASE_PATH + "/api/bot/targets";

    static final String REFRESH_PATH = TARGETS_PATH + "/refresh";

    static final String LOOKUP_PATH = TARGETS_PATH + "/lookup";

    private static final String TYPE_GROUP = "group";

    private static final String TYPE_FRIEND = "friend";

    private final List<PushTargetDirectory> directories;

    private final List<PushTargetLookup> lookups;

    private final AbstractDataSource dataSource;

    public ConfigUiPushTargetsController(List<PushTargetDirectory> directories, List<PushTargetLookup> lookups,
                                         AbstractDataSource dataSource) {
        this.directories = directories;
        this.lookups = lookups;
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

        List<PushTargetDirectory.Snapshot> snapshots = snapshots(false);
        Set<String> configured = configuredTargets();
        String keyword = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);

        JSONArray items = new JSONArray();
        for (PushTargetDirectory.Snapshot snapshot : snapshots) {
            if (group) {
                for (PushTargetDirectory.Group row : snapshot.groups()) {
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
                for (PushTargetDirectory.Friend row : snapshot.friends()) {
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

    /** 强制刷新各推送平台的名单。 */
    @PostMapping(value = REFRESH_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public JSONObject refresh() {
        JSONObject result = summary(snapshots(true));
        result.put("success", true);
        return result;
    }

    @GetMapping(LOOKUP_PATH)
    public ResponseEntity<JSONObject> lookup(@RequestParam String platform, @RequestParam String type,
                                             @RequestParam String num, @RequestParam(required = false) String q) {
        Long number = ConfigUiStreamerId.positiveDecimal(num);
        PushTargetType targetType = TYPE_GROUP.equals(type) ? PushTargetType.GROUP
                : TYPE_FRIEND.equals(type) ? PushTargetType.FRIEND : null;
        if (number == null || targetType == null || platform.isBlank() || q != null) {
            return ResponseEntity.badRequest().body(JSONObject.of("success", false,
                    "message", "请填写推送平台、group 或 friend 类型与完整号码，不支持关键字"));
        }

        PushTargetLookup.Result found = lookups.stream().filter(lookup -> lookup.supports(platform)).findFirst()
                .map(lookup -> lookup.lookup(platform, targetType, number, ConfigUiAccess.memberAccount()))
                .orElseGet(() -> new PushTargetLookup.Result(false, null, "推送平台不可用"));
        JSONObject result = JSONObject.of("success", true, "found", found.found(),
                "platform", platform, "type", type, "num", number);
        if (found.found()) {
            result.put(targetType == PushTargetType.GROUP ? "name" : "nickname", found.name());
        }
        if (found.message() != null) {
            result.put("message", found.message());
        }
        return ResponseEntity.ok(result);
    }

    private List<PushTargetDirectory.Snapshot> snapshots(boolean force) {
        List<PushTargetDirectory.Snapshot> snapshots = new ArrayList<>();
        for (PushTargetDirectory directory : directories) {
            try {
                snapshots.addAll(directory.snapshots(force));
            } catch (RuntimeException e) {
                String sender = directory.id();
                String reason = e.getMessage();
                if (reason == null || reason.isBlank()) {
                    reason = e.getClass().getSimpleName();
                }
                log.warn("取 {} 的推送目标名单失败: {}", sender, reason);
                snapshots.add(new PushTargetDirectory.Snapshot(sender, List.of(), List.of(), null, null, true, reason));
            }
        }
        return snapshots;
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
    private JSONObject summary(List<PushTargetDirectory.Snapshot> snapshots) {
        JSONArray senders = new JSONArray();
        Instant oldest = null;
        boolean stale = false;

        for (PushTargetDirectory.Snapshot snapshot : snapshots) {
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
