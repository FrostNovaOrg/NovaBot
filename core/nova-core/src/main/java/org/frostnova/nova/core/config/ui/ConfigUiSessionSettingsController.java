package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.SessionKey;
import org.frostnova.nova.core.service.SessionQuietHoursService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiSessionSettingsController {
    private static final String OFFICIAL = "qq-official";
    private static final PushTargetType GROUP = PushTargetType.GROUP;
    private static final Map<String, String> SETTINGS = Map.of(
            RevenueVisibilityService.NAMESPACE, "金额可见",
            CommandSettingsService.NAMESPACE, "命令开关",
            SessionQuietHoursService.NAMESPACE, "静音时段");

    private final NovaStateStore state;

    public ConfigUiSessionSettingsController(NovaStateStore state) {
        this.state = state;
    }

    @PostMapping(ConfigUiController.BASE_PATH + "/api/state/copy-to-official-group")
    public ResponseEntity<JSONObject> copyToOfficialGroup(@RequestBody JSONObject body) {
        String platform = body.get("platform") instanceof String value ? value : "";
        Long num = ConfigUiStreamerId.positiveDecimal(body.get("num"));
        Long officialNum = ConfigUiStreamerId.positiveDecimal(body.get("officialNum"));
        Long type = ConfigUiStreamerId.positiveDecimal(body.get("type"));
        if (platform.isBlank() || OFFICIAL.equals(platform)
                || type == null || type != GROUP.getCode() || num == null || officialNum == null) {
            return ResponseEntity.badRequest().body(JSONObject.of("success", false,
                    "message", "请选择原群和要换到的官方群"));
        }
        ConfigUiOperationAdvice.session(OFFICIAL, GROUP.getCode(), officialNum);
        String from = SessionKey.require(platform, GROUP, num);
        String to = SessionKey.require(OFFICIAL, GROUP, officialNum);
        try {
            var skipped = state.copyMissingKeyAndSave(from, to, SETTINGS.keySet())
                    .stream().map(SETTINGS::get).sorted().toList();
            return ResponseEntity.ok(JSONObject.of("success", true, "skipped", skipped));
        } catch (IllegalStateException error) {
            return ResponseEntity.internalServerError().body(JSONObject.of("success", false,
                    "message", error.getMessage()));
        }
    }
}
