package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.ChannelSettings;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;
import java.util.function.Supplier;

/** 升级时将会话设置复制到现有通道，保留旧状态供回退使用。 */
@Service
@Slf4j
public class ChannelSettingsMigration {
    public static final String NAMESPACE = "ChannelSettingsMigration";
    public static final String MIGRATED_KEY = "_channelSettings";
    private final NovaStateStore state;
    private final DatasourceChannelStore datasource;
    private final RevenueVisibilityService revenue;
    private final SessionQuietHoursService quiet;

    public ChannelSettingsMigration(NovaStateStore state, DatasourceChannelStore datasource,
                                    RevenueVisibilityService revenue, SessionQuietHoursService quiet) {
        this.state = state;
        this.datasource = datasource;
        this.revenue = revenue;
        this.quiet = quiet;
    }

    @Order(2)
    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        synchronized (DatasourceChannelStore.LOCK) {
            try {
                if (state.isDiskSuspended()) {
                    log.warn("状态文件读不了，本次跳过通道设置迁移，下次启动重试");
                    return;
                }
                if (state.read(NAMESPACE, MIGRATED_KEY, data -> data.get(MIGRATED_KEY)).isPresent()) return;
                datasource.migrate(users -> {
                    for (Object entry : users) {
                        JSONObject user = (JSONObject) entry;
                        if (user.get("uid") == null || user.getString("uid").isBlank()) {
                            log.warn("主播 uid 未填，跳过其通道设置迁移");
                            continue;
                        }
                        JSONArray targets = user.getJSONArray("targets");
                        if (targets == null) continue;
                        for (Object value : targets) copy((JSONObject) value);
                    }
                });
                boolean saved = false;
                try {
                    state.write(NAMESPACE, data -> data.put(MIGRATED_KEY, System.currentTimeMillis()));
                    saved = state.trySave();
                } finally {
                    if (!saved) state.write(NAMESPACE, data -> data.remove(MIGRATED_KEY));
                }
                if (saved) return;
                log.warn("通道设置迁移标记未能保存，下次启动继续迁移");
            } catch (Exception e) {
                log.warn("通道设置迁移未完成，继续启动，下次启动重试", e);
            }
        }
    }

    private void copy(JSONObject target) {
        if (List.of("revenueVisible", "quietHours", "disabledCommands").stream().anyMatch(target::containsKey)) return;
        String platform = target.getString("platform");
        Integer code = target.getInteger("type");
        Long num = target.getLong("num");
        if (platform == null || num == null || code == null || code != 0 && code != 1) {
            log.warn("推送目标 {}:{}:{} 无法识别，跳过通道设置迁移", platform, code, num);
            return;
        }
        PushTargetType type = PushTargetType.of(code);
        String key = SessionKey.of(platform, type, num);
        Boolean visible = readLegacy(RevenueVisibilityService.NAMESPACE, key, () -> revenue.explicit(platform, type, num));
        if (visible != null && visible != revenue.defaultFor(type)) target.put("revenueVisible", visible);
        var setting = readLegacy(SessionQuietHoursService.NAMESPACE, key, () -> normalizeQuiet(quiet.get(platform, type, num)));
        if (setting != null && setting.mode() != SessionQuietHoursService.Mode.FOLLOW) {
            JSONObject q = new JSONObject().fluentPut("mode", setting.mode().key());
            if (setting.mode() == SessionQuietHoursService.Mode.CUSTOM) {
                q.put("start", setting.start());
                q.put("end", setting.end());
            }
            target.put("quietHours", q);
        }
        JSONObject old = readLegacy(CommandSettingsService.NAMESPACE, key,
                () -> state.read(CommandSettingsService.NAMESPACE, key, data -> data.getJSONObject(key)).orElse(null));
        if (old != null) {
            var disabled = old.keySet().stream().filter(ChannelSettings.COMMAND_KEYS::contains).sorted().toList();
            if (!disabled.isEmpty()) target.put("disabledCommands", disabled);
        }
    }

    private <T> T readLegacy(String namespace, String key, Supplier<T> reader) {
        try {
            if (state.isMalformed(namespace)) {
                log.warn("旧设置 {} 不是对象，跳过会话 {} 的这一项", namespace, key);
                return null;
            }
            Object raw = state.read(namespace, key, data -> data.get(key)).orElse(null);
            boolean wrongType = raw != null && (RevenueVisibilityService.NAMESPACE.equals(namespace)
                    ? !(raw instanceof Boolean) : !(raw instanceof JSONObject));
            T value = reader.get();
            if (wrongType) {
                if (value == null) log.warn("会话 {} 的旧设置 {} 类型不对，跳过这一项", key, namespace);
                else log.warn("会话 {} 的旧设置 {} 类型不对，按原值迁移", key, namespace);
            }
            return value;
        } catch (RuntimeException e) {
            log.warn("会话 {} 的旧设置 {} 读不出，跳过这一项: {}", key, namespace, e.toString());
            return null;
        }
    }

    private SessionQuietHoursService.Setting normalizeQuiet(SessionQuietHoursService.Setting setting) {
        if (setting.mode() != SessionQuietHoursService.Mode.CUSTOM) return setting;
        String start = setting.start().trim();
        String end = setting.end().trim();
        try {
            // 旧判法在空串和相同时不静音，即使全局处于静音中也照常发送。
            if (start.isEmpty() || end.isEmpty() || LocalTime.parse(start).equals(LocalTime.parse(end))) {
                log.warn("会话 {} 的旧静音起止为空或相同，迁移为不静音", SessionKey.of(setting.platform(), setting.type(), setting.num()));
                return new SessionQuietHoursService.Setting(setting.platform(), setting.type(), setting.num(),
                        SessionQuietHoursService.Mode.OFF, null, null);
            }
            return new SessionQuietHoursService.Setting(setting.platform(), setting.type(), setting.num(), setting.mode(),
                    LocalTime.parse(start).toString(), LocalTime.parse(end).toString());
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("会话 {} 的旧静音时段 {}–{} 无效，迁移为不静音", SessionKey.of(setting.platform(), setting.type(), setting.num()), start, end);
            return new SessionQuietHoursService.Setting(setting.platform(), setting.type(), setting.num(),
                    SessionQuietHoursService.Mode.OFF, null, null);
        }
    }
}
