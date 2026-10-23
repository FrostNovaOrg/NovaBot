package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiAudit;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigurationValidator;
import org.frostnova.nova.core.config.ui.TimestampedFileBackup;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.datasource.JsonDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.util.DurableFiles;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 通道设置与按主播保存共用同一把锁和文件格式。 */
@Service
@Slf4j
public class DatasourceChannelStore {
    public static final Object LOCK = new Object();
    private final NovaCoreProperties properties;
    private final AbstractDataSource dataSource;
    private final ConfigurationValidator validator;

    public DatasourceChannelStore(NovaCoreProperties properties, AbstractDataSource dataSource,
                                  ConfigurationValidator validator) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.validator = validator;
    }

    public boolean update(Consumer<JSONArray> change, boolean backup) {
        return update(change, backup, false, ConfigUiAccess.actor());
    }

    public boolean update(Consumer<JSONArray> change, boolean backup, String actor) {
        return update(change, backup, false, actor);
    }

    /** 升级只校验本次新增的通道字段，未填完的旧配置不能阻止启动。 */
    public boolean migrate(Consumer<JSONArray> change) {
        return update(change, true, true, null);
    }

    private boolean update(Consumer<JSONArray> change, boolean backup, boolean migration, String actor) {
        synchronized (LOCK) {
            Path path = Path.of(properties.getDatasource().getJsonPath());
            try {
                String content = Files.exists(path) ? Files.readString(path) : "[]";
                if (content.startsWith("\uFEFF")) content = content.substring(1);
                JSONArray users = JSONArray.parseArray(content.isBlank() ? "[]" : content);
                if (users == null) throw new IllegalArgumentException("推送配置应为数组");
                String before = ConfigUiController.formatDatasource(users, 0);
                change.accept(users);
                String after = ConfigUiController.formatDatasource(users, 0);
                if (before.equals(after)) return false;
                var issues = migration ? validateMigratedSettings(JSONArray.parseArray(before), JSONArray.parseArray(after))
                        : validator.validateDatasource(after, Set.of());
                if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("；", issues));
                if (backup && Files.exists(path)) new TimestampedFileBackup(path, Clock.systemDefaultZone())
                        .backupForSave(properties.getConfigUi().getBackupKeep());
                DurableFiles.replace(path, after);
                if (!migration) {
                    try {
                        audit(JSONArray.parseArray(before), users, actor);
                    } catch (RuntimeException error) {
                        log.warn("通道设置审计失败，配置已保存，将继续重载", error);
                    }
                }
                if (dataSource instanceof JsonDataSource json) json.reload();
                return true;
            } catch (IOException e) {
                throw new IllegalStateException("保存推送配置失败", e);
            }
        }
    }

    private void audit(JSONArray before, JSONArray after, String actor) {
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            JSONObject user = after.getJSONObject(i);
            JSONArray targets = user.getJSONArray("targets");
            JSONArray previous = before.getJSONObject(i).getJSONArray("targets");
            if (targets == null || previous == null) continue;
            for (int j = 0; j < Math.min(targets.size(), previous.size()); j++) {
                JSONObject target = targets.getJSONObject(j);
                JSONObject old = previous.getJSONObject(j);
                for (String key : List.of("revenueVisible", "quietHours", "disabledCommands", "reportPalette")) {
                    if (Objects.equals(old.get(key), target.get(key))) continue;
                    String setting = switch (key) {
                        case "revenueVisible" -> "金额可见性";
                        case "quietHours" -> "静音";
                        case "reportPalette" -> "报告图配色";
                        default -> "命令开关";
                    };
                    Object value = target.get(key);
                    String description = value == null ? "默认" : value.toString();
                    if ("quietHours".equals(key) && value instanceof JSONObject quiet
                            && "custom".equals(quiet.getString("mode"))) {
                        description = quiet.getString("start") + "–" + quiet.getString("end");
                    }
                    log.info("{} 把会话 {}:{}:{} 里主播 {} 的{}改成 {}", actor,
                            target.getString("platform"), target.getInteger("type"), target.getLong("num"),
                            ConfigUiAudit.streamer(dataSource, user.getString("platform"), user.getString("uid")),
                            setting, description);
                }
            }
        }
    }

    private List<String> validateMigratedSettings(JSONArray before, JSONArray after) {
        var issues = new ArrayList<String>();
        for (int i = 0; i < after.size(); i++) {
            JSONArray targets = after.getJSONObject(i).getJSONArray("targets");
            if (targets == null) continue;
            JSONArray previous = before.getJSONObject(i).getJSONArray("targets");
            for (int j = 0; j < targets.size(); j++) {
                JSONObject target = targets.getJSONObject(j);
                if (!target.equals(previous.getJSONObject(j))) issues.addAll(validator.validateChannelSettings(target));
            }
        }
        return issues;
    }

    /** 未指定主播时修改该会话全部通道，包括暂时停用的通道。 */
    public int change(String platform, PushTargetType type, Long num, String streamerPlatform, Long uid,
                      Consumer<JSONObject> change) {
        int[] count = {0};
        update(users -> {
            for (Object entry : users) {
                JSONObject user = (JSONObject) entry;
                if (uid != null && (!Objects.equals(uid, user.getLong("uid"))
                        || streamerPlatform != null && !Objects.equals(streamerPlatform, user.getString("platform")))) continue;
                JSONArray targets = user.getJSONArray("targets");
                if (targets == null) continue;
                for (Object value : targets) {
                    JSONObject target = (JSONObject) value;
                    if (Objects.equals(platform, target.getString("platform"))
                            && Objects.equals(type.getCode(), target.getInteger("type"))
                            && Objects.equals(num, target.getLong("num"))) {
                        change.accept(target);
                        count[0]++;
                    }
                }
            }
        }, false);
        return count[0];
    }
}
