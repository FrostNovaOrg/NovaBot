package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONArray;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 通道设置与按主播保存共用同一把锁和文件格式。 */
@Service
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
                var issues = validator.validateDatasource(after, Set.of());
                if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("；", issues));
                if (backup && Files.exists(path)) new TimestampedFileBackup(path, Clock.systemDefaultZone())
                        .backupForSave(properties.getConfigUi().getBackupKeep());
                DurableFiles.replace(path, after);
                if (dataSource instanceof JsonDataSource json) json.reload();
                return true;
            } catch (IOException e) {
                throw new IllegalStateException("保存推送配置失败", e);
            }
        }
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
