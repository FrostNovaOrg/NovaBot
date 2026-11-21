package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.DatasourceChannelStore;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Console inventory, including disabled streamers and targets omitted by the live datasource. */
@Component
public class ConfigUiConfiguredUsers {
    private final NovaCoreProperties properties;

    public ConfigUiConfiguredUsers(NovaCoreProperties properties) { this.properties = properties; }

    public JSONArray raw() {
        synchronized (DatasourceChannelStore.LOCK) {
            try {
                String content = Files.readString(Path.of(properties.getDatasource().getJsonPath()));
                if (content.startsWith("\uFEFF")) content = content.substring(1);
                JSONArray users = JSONArray.parseArray(content.isBlank() ? "[]" : content);
                return users == null ? new JSONArray() : users;
            } catch (java.io.IOException | RuntimeException e) {
                return new JSONArray();
            }
        }
    }

    public List<PushUser> users(Collection<PushUser> loaded) {
        Map<String, PushUser> users = new LinkedHashMap<>();
        loaded.forEach(user -> users.put(key(user), user));
        for (Object value : raw()) {
            if (!(value instanceof JSONObject row)) continue;
            try {
                Long uid = org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId.positiveDecimal(row.get("uid"));
                String platform = row.getString("platform");
                if (uid == null || platform == null || platform.isBlank()) continue;
                PushUser user = new PushUser(); user.setPlatform(platform); user.setUid(uid);
                PushUser live = users.get(key(user));
                user.setUname(live == null ? row.getString("uname") : live.getUname());
                user.setRoomId(live == null ? row.getLong("roomId") : live.getRoomId());
                user.setEnabled(!Boolean.FALSE.equals(row.getBoolean("enabled")));
                JSONArray targets = row.getJSONArray("targets");
                if (targets != null) for (Object entry : targets) {
                    if (!(entry instanceof JSONObject targetRow)) continue;
                    PushTarget target = new PushTarget();
                    target.setPlatform(targetRow.getString("platform"));
                    target.setNum(targetRow.getLong("num"));
                    Integer type = targetRow.getInteger("type");
                    target.setType(type == null ? null : PushTargetType.of(type));
                    target.setEnabled(!Boolean.FALSE.equals(targetRow.getBoolean("enabled")));
                    target.setQuietHours(targetRow.getObject("quietHours", PushTarget.QuietHours.class));
                    if (target.getPlatform() == null || target.getNum() == null || target.getType() == null
                            || target.getType() == PushTargetType.UNKNOWN) continue;
                    target.setUser(user);
                    user.getTargets().add(target);
                }
                users.put(key(user), user);
            } catch (RuntimeException malformed) {
                // A malformed entry must not hide the rest of the inventory.
            }
        }
        return new ArrayList<>(users.values());
    }

    private static String key(PushUser user) { return user.getPlatform() + ":" + user.getUid(); }
}
