package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把旧版按「平台:会话号」存的会话设置抄成按「平台:会话类型码:会话号」存
 * <p>
 * 金额可见、命令开关、静音时段三处各做一次，做过的在那一处钉一个标记键，之后不再做。
 * 每条旧键：
 * <ul>
 *     <li>当前推送配置里这个平台这个号只有一种类型：抄到那一种类型的新键；</li>
 *     <li>两种类型都有，或一种都没有（命令开关常见于没配推送的群）：两种类型各抄一份，
 *     与升级前实际生效的完全一样，再由使用者到控制台各自改。</li>
 * </ul>
 * 旧键原样留着：回退到旧版时还读得到。新版只按新键读写，列表里也不列旧键。
 * 新键已经有值的不覆盖。
 */
@Slf4j
@Service
public class SessionSettingsTypeMigration {
    /**
     * 已经抄过一次的标记
     * <p>
     * 与会话键同库、不同形：会话键带冒号，这一条没有，列表与读写都不会把它当成某个会话。
     */
    static final String MIGRATED_KEY = "_sessionTyped";

    /**
     * 要抄的三处
     */
    static final List<String> NAMESPACES = List.of(
            RevenueVisibilityService.NAMESPACE,
            CommandSettingsService.NAMESPACE,
            SessionQuietHoursService.NAMESPACE);

    private final NovaStateStore store;

    private final AbstractDataSource dataSource;

    @Autowired
    public SessionSettingsTypeMigration(NovaStateStore store,
                                        @Autowired(required = false) AbstractDataSource dataSource) {
        this.store = store;
        this.dataSource = dataSource;
    }

    /**
     * 应用就绪后抄一次
     * <p>
     * 须排在状态加载（{@code NovaStateStore} {@code @Order(-10000)}）
     * 与数据源加载（{@code LoadDataSourceListener} {@code @Order(0)}）之后：
     * 排在数据源之前的话，每条旧键都会被当成「配置里一种都没有」而两边都抄。
     */
    @Order(1)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        migrate(dataSource == null ? List.of() : dataSource.getAllUsers());
    }

    /**
     * 按当前推送配置把三处的旧键各抄一遍
     * <p>
     * 推送配置为空时照「一种都没有」处理：两种都抄一份，同样钉标记。
     * @param users 数据源里当前的推送用户
     */
    void migrate(List<PushUser> users) {
        Map<String, Set<PushTargetType>> configured = configuredTypes(users);
        List<String> parts = new ArrayList<>();
        for (String namespace : NAMESPACES) {
            int[] counts = migrate(namespace, configured);
            if (counts != null) {
                parts.add(namespace + " 迁 " + counts[0] + " 条（其中两边都抄 " + counts[1] + " 条）");
            }
        }
        if (!parts.isEmpty()) {
            log.info("会话设置已按会话类型分开存：{}；旧键原样保留", String.join("，", parts));
        }
    }

    /**
     * 抄一处
     * @return 迁了几条、其中两边都抄的几条；这一处已经抄过或整段认不出时为 null
     */
    private int[] migrate(String namespace, Map<String, Set<PushTargetType>> configured) {
        if (store.isMalformed(namespace)) {
            log.warn("会话设置 {} 整段不是对象，不迁移", namespace);
            return null;
        }

        int[] counts = {0, 0};
        boolean[] ran = {false};
        store.write(namespace, data -> {
            if (data.containsKey(MIGRATED_KEY)) {
                return;
            }
            ran[0] = true;
            for (String key : new ArrayList<>(data.keySet())) {
                SessionKey.Parsed legacy = SessionKey.parseLegacy(key);
                if (legacy == null) {
                    continue;
                }
                Set<PushTargetType> types = configured.getOrDefault(
                        legacy.platform() + ":" + legacy.num(), Set.of());
                List<PushTargetType> into = types.size() == 1
                        ? List.copyOf(types)
                        : List.of(PushTargetType.GROUP, PushTargetType.FRIEND);
                for (PushTargetType type : into) {
                    data.putIfAbsent(SessionKey.of(legacy.platform(), type, legacy.num()), copy(data.get(key)));
                }
                counts[0]++;
                if (into.size() > 1) {
                    counts[1]++;
                }
            }
            data.put(MIGRATED_KEY, System.currentTimeMillis());
        });
        return ran[0] ? counts : null;
    }

    /**
     * 当前推送配置里每个「平台:号」出现过哪几种会话类型；停用的目标也算，它仍在配置里
     */
    private static Map<String, Set<PushTargetType>> configuredTypes(List<PushUser> users) {
        Map<String, Set<PushTargetType>> result = new HashMap<>();
        if (users == null) {
            return result;
        }
        for (PushUser user : users) {
            if (user == null || user.getTargets() == null) {
                continue;
            }
            for (PushTarget target : user.getTargets()) {
                if (target == null || target.getPlatform() == null || target.getNum() == null
                        || !SessionKey.known(target.getType())) {
                    continue;
                }
                result.computeIfAbsent(target.getPlatform() + ":" + target.getNum(),
                        key -> EnumSet.noneOf(PushTargetType.class)).add(target.getType());
            }
        }
        return result;
    }

    /**
     * 每份新键拿自己的一份值：两边共用同一个对象的话，改一边另一边也跟着变
     */
    private static Object copy(Object value) {
        if (value instanceof JSONObject object) {
            JSONObject copy = new JSONObject();
            object.forEach((key, item) -> copy.put(key, copy(item)));
            return copy;
        }
        if (value instanceof JSONArray array) {
            JSONArray copy = new JSONArray();
            array.forEach(item -> copy.add(copy(item)));
            return copy;
        }
        return value;
    }
}
