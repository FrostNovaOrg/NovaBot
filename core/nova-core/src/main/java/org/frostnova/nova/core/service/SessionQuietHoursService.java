package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.enums.PushTargetType;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话级静音时段
 * <p>
 * 一个号常推好几个群：粉丝群想随时收到开播，工作群夜里不想被吵（或者反过来）。
 * 全局只有一项静音时段时，这件事只能全关或全开。这里给每个会话一档：
 * 跟全局、本会话自己的时段、本会话不静音。
 * <p>
 * <b>这里只存「选哪一档、自己的起止是什么」，不判「此刻在不在静音里」。</b>
 * 那个判定只留 {@link org.frostnova.nova.core.sender.PushGate} 里那一份，
 * 本会话这一档只决定拿哪一对起止去判。
 * <p>
 * 存法与 {@link RevenueVisibilityService} 一样：状态存储里一个命名空间，键「平台:会话类型码:会话号」
 * （见 {@link SessionKey}），清除即回到默认（跟全局）。没设过的会话不占记录，行为与这一项出现之前一模一样。
 */
@Slf4j
@Service
public class SessionQuietHoursService {
    /**
     * 状态存储中的命名空间
     */
    public static final String NAMESPACE = "SessionQuietHours";

    private final NovaStateStore store;

    @Autowired
    public SessionQuietHoursService(NovaStateStore store) {
        this.store = store;
    }

    /**
     * 读取指定会话的那一档
     * <p>
     * 记录读不出（不是对象、档位认不出、字段类型不对）时当跟全局并 warn 一句，不往外抛：
     * 这里在推送主路径上，抛出去会让这一次推送整件发不出去。手改状态文件、
     * 降级回旧版遇到新版写的形状，走的都是这条路。
     * @param platform 推送平台
     * @param type 会话类型，不是群或好友时一律当跟全局
     * @param num 会话号
     * @return 设置，从没设过（或记录认不出）时为跟全局
     */
    public Setting get(@NonNull String platform, PushTargetType type, @NonNull Long num) {
        String key = SessionKey.of(platform, type, num);
        if (key == null) {
            return Setting.follow(platform, type, num);
        }
        try {
            // 读到这里键一定在（不在的是从没设过，不 warn）；值不是对象的，getJSONObject 会静默给 null，所以先看形状
            return store.read(NAMESPACE, key, data -> parse(platform, type, num, record(key, data.get(key))))
                    .orElseGet(() -> Setting.follow(platform, type, num));
        } catch (RuntimeException e) {
            log.warn("会话 {} 的静音时段记录读不出，按跟全局处理: {}", key, e.getMessage());
            return Setting.follow(platform, type, num);
        }
    }

    /**
     * 设置指定会话的那一档
     * @param platform 推送平台
     * @param type 会话类型，只能是群或好友
     * @param num 会话号
     * @param mode 哪一档；为 null 或跟全局时清除记录
     * @param start 自己的时段开始（HH:mm），只在自己的时段那一档有用
     * @param end 自己的时段结束（HH:mm），只在自己的时段那一档有用
     */
    public void set(@NonNull String platform, PushTargetType type, @NonNull Long num, Mode mode,
                    String start, String end) {
        String key = SessionKey.require(platform, type, num);
        store.write(NAMESPACE, data -> {
            if (mode == null || mode == Mode.FOLLOW) {
                data.remove(key);
                return;
            }

            JSONObject value = new JSONObject();
            value.put("mode", mode.key());
            if (mode == Mode.CUSTOM) {
                value.put("start", start == null ? "" : start.trim());
                value.put("end", end == null ? "" : end.trim());
            }
            data.put(key, value);
        });
    }

    /**
     * 列出所有显式设置过的会话
     * @return 各会话的设置，按平台、会话号与会话类型排序；跟全局的不在其中
     */
    public List<Setting> all() {
        List<Setting> result = new ArrayList<>();
        // 整段被手改成字符串、数组、数字：当没有任何会话设过，控制台的 /state 照常返回。
        // 字符串时 namespace 会抛，数组、数字时它静默给空对象，所以先问形状
        if (store.isMalformed(NAMESPACE)) {
            log.warn("静音时段的整段记录 {} 不是对象，按没有任何会话设过处理", NAMESPACE);
            return result;
        }
        JSONObject data = store.namespace(NAMESPACE);

        for (String key : data.keySet()) {
            // 旧键「平台:会话号」与迁移标记都还原不出会话，跳过
            SessionKey.Parsed parsed = SessionKey.parse(key);
            if (parsed == null) {
                continue;
            }

            try {
                Setting setting = parse(parsed.platform(), parsed.type(), parsed.num(), data.getJSONObject(key));
                if (setting.mode() != Mode.FOLLOW) {
                    result.add(setting);
                }
            } catch (Exception ignored) {
                // 手工编辑状态文件时可能混入非法值，跳过即可
            }
        }

        result.sort(Comparator.comparing(Setting::platform).thenComparingLong(Setting::num)
                .thenComparing(Setting::type));
        return result;
    }

    /**
     * 取出一条记录；不是对象的（数字、数组、真假、null、字符串）warn 一句并给 null，由 parse 当跟全局
     */
    private static JSONObject record(String key, Object raw) {
        if (raw instanceof JSONObject value) {
            return value;
        }
        if (raw instanceof Map<?, ?> map) {
            return new JSONObject(map);
        }
        log.warn("会话 {} 的静音时段记录读不出，按跟全局处理: 不是对象（{}）", key, raw);
        return null;
    }

    private static Setting parse(String platform, PushTargetType type, Long num, JSONObject value) {
        if (value == null) {
            return Setting.follow(platform, type, num);
        }
        String raw = value.getString("mode");
        Mode mode = Mode.of(raw);
        if (mode == Mode.FOLLOW && !Mode.FOLLOW.key().equalsIgnoreCase(String.valueOf(raw).trim())) {
            log.warn("会话 {} 的静音档位「{}」认不出，按跟全局处理", SessionKey.of(platform, type, num), raw);
        }
        if (mode != Mode.CUSTOM) {
            return new Setting(platform, type, num, mode, null, null);
        }
        // 起止不是字符串算记录认不出，回到跟全局；是字符串而格式不对的，交给判定那一侧按全局那套规则忽略
        if (!(value.get("start") instanceof String start) || !(value.get("end") instanceof String end)) {
            log.warn("会话 {} 的静音时段起止不是文字，按跟全局处理: {}", SessionKey.of(platform, type, num), value);
            return Setting.follow(platform, type, num);
        }
        return new Setting(platform, type, num, mode, start, end);
    }

    /**
     * 会话的静音档位
     */
    public enum Mode {
        /**
         * 跟全局：用设置页那一项静音时段
         */
        FOLLOW,

        /**
         * 本会话自己的时段
         */
        CUSTOM,

        /**
         * 本会话不静音：全局在静音时，推往这个会话的照发
         */
        OFF;

        /**
         * 从存储或接口里的小写名还原
         * <p>
         * 认不出的一律当跟全局：拿不准时回到与这一项出现之前一样的那一档，
         * 而不是猜成「不静音」把人半夜吵醒，或猜成某段时段把通知吞掉。
         * @param name 名字
         * @return 档位
         */
        public static Mode of(String name) {
            if (name != null) {
                for (Mode candidate : values()) {
                    if (candidate.name().equalsIgnoreCase(name.trim())) {
                        return candidate;
                    }
                }
            }
            return FOLLOW;
        }

        /**
         * 存储与接口里用的名字
         * @return 小写名
         */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 某个会话的静音设置
     *
     * @param platform 推送平台
     * @param type 会话类型
     * @param num 会话号
     * @param mode 档位
     * @param start 自己的时段开始，只在 {@link Mode#CUSTOM} 时有值
     * @param end 自己的时段结束，只在 {@link Mode#CUSTOM} 时有值
     */
    public record Setting(String platform, PushTargetType type, Long num, Mode mode, String start, String end) {
        static Setting follow(String platform, PushTargetType type, Long num) {
            return new Setting(platform, type, num, Mode.FOLLOW, null, null);
        }
    }
}
