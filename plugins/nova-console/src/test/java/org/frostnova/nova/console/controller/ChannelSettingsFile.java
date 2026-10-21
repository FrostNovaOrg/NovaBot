package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationValidator;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.service.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.List;
import static org.mockito.Mockito.mock;

/** 通过真实配置文件验证兼容写口，不再从旧状态读取新设置。 */
final class ChannelSettingsFile {
    private final Path file;
    private final DatasourceChannelStore store;
    private int reloads;

    ChannelSettingsFile(NovaCoreProperties properties, AbstractDataSource source) {
        file = Path.of(properties.getLive().getLiveDataPath()).resolveSibling("datasource.json");
        properties.getDatasource().setJsonPath(file.toString());
        if (!Files.exists(file)) {
            try { Files.writeString(file, JSON.toJSONString(source.getAllUsers())); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        var reloader = mock(org.frostnova.nova.core.datasource.JsonDataSource.class);
        org.mockito.Mockito.doAnswer(call -> {
            reloads++;
            var users = new java.util.ArrayList<>(source.getAllUsers());
            for (String platform : users.stream().map(user -> user.getPlatform()).distinct().toList()) {
                users.addAll(source.getUsers(platform));
            }
            for (var user : users) for (var target : user.getTargets()) {
                var saved = model(target.getPlatform(), target.getType(), target.getNum());
                target.setRevenueVisible(saved.getRevenueVisible()); target.setQuietHours(saved.getQuietHours());
                target.setDisabledCommands(saved.getDisabledCommands());
            }
            return null;
        }).when(reloader).reload();
        store = new DatasourceChannelStore(properties, reloader, new ConfigurationValidator(mock(NovaEventHandlerService.class)));
    }

    int reloads() { return reloads; }

    void disable(String platform, PushTargetType type, long num, List<String> keys) {
        store.change(platform, type, num, null, null, target -> target.put("disabledCommands", keys));
    }

    void attach(RuntimeStateController controller) { ReflectionTestUtils.setField(controller, "channelStore", store); }

    JSONObject target(String platform, PushTargetType type, long num) {
        try {
            for (Object value : JSONArray.parseArray(Files.readString(file))) {
                var user = (JSONObject) value;
                for (Object entry : user.getJSONArray("targets")) {
                    var t = (JSONObject) entry;
                    if (platform.equals(t.getString("platform")) && type.getCode() == t.getIntValue("type") && num == t.getLongValue("num")) return t;
                }
            }
            throw new AssertionError("未找到通道 " + platform + ":" + type + ":" + num);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    PushTarget model(String platform, PushTargetType type, long num) {
        JSONObject raw = target(platform, type, num);
        var target = new PushTarget(); target.setPlatform(platform); target.setType(type); target.setNum(num);
        target.setRevenueVisible(raw.getBoolean("revenueVisible"));
        target.setQuietHours(raw.getObject("quietHours", PushTarget.QuietHours.class));
        target.setDisabledCommands(raw.getList("disabledCommands", String.class));
        return target;
    }
}
