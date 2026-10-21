package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.ChannelSettings;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import java.util.List;

/** 升级时将会话设置复制到现有通道，保留旧状态供回退使用。 */
@Service
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
            if (state.read(NAMESPACE, MIGRATED_KEY, data -> data.get(MIGRATED_KEY)).isPresent()) return;
            datasource.update(users -> {
                for (Object entry : users) {
                    JSONArray targets = ((JSONObject) entry).getJSONArray("targets");
                    if (targets == null) continue;
                    for (Object value : targets) copy((JSONObject) value);
                }
            }, true);
            state.write(NAMESPACE, data -> data.put(MIGRATED_KEY, System.currentTimeMillis()));
            if (!state.trySave()) {
                state.write(NAMESPACE, data -> data.remove(MIGRATED_KEY));
                throw new IllegalStateException("通道设置迁移标记未能保存");
            }
        }
    }

    private void copy(JSONObject target) {
        if (List.of("revenueVisible", "quietHours", "disabledCommands").stream().anyMatch(target::containsKey)) return;
        String platform = target.getString("platform");
        Integer code = target.getInteger("type");
        Long num = target.getLong("num");
        if (platform == null || num == null || code == null || code != 0 && code != 1) return;
        PushTargetType type = PushTargetType.of(code);
        Boolean visible = revenue.explicit(platform, type, num);
        if (visible != null && visible != revenue.defaultFor(type)) target.put("revenueVisible", visible);
        var setting = quiet.get(platform, type, num);
        if (setting.mode() != SessionQuietHoursService.Mode.FOLLOW) {
            JSONObject q = new JSONObject().fluentPut("mode", setting.mode().key());
            if (setting.mode() == SessionQuietHoursService.Mode.CUSTOM) {
                q.put("start", setting.start());
                q.put("end", setting.end());
            }
            target.put("quietHours", q);
        }
        String key = SessionKey.of(platform, type, num);
        JSONObject old = state.read(CommandSettingsService.NAMESPACE, key, data -> data.getJSONObject(key)).orElse(null);
        if (old != null) {
            var disabled = old.keySet().stream().filter(ChannelSettings.COMMAND_KEYS::contains).sorted().toList();
            if (!disabled.isEmpty()) target.put("disabledCommands", disabled);
        }
    }
}
