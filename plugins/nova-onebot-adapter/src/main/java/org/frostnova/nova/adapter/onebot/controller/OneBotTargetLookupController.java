package org.frostnova.nova.adapter.onebot.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.service.OneBotTargetLookup;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@NovaComponent
@RestController
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class OneBotTargetLookupController {
    private final OneBotTargetLookup lookup;

    public OneBotTargetLookupController(OneBotTargetLookup lookup) { this.lookup = lookup; }

    /** Look up exactly one complete group or friend number without exposing configuration. */
    @GetMapping(ConfigUiController.BASE_PATH + "/api/bot/targets/lookup")
    public ResponseEntity<JSONObject> lookup(@RequestParam String platform, @RequestParam String type,
                                           @RequestParam String num, @RequestParam(required = false) String q) {
        Long number = ConfigUiStreamerId.positiveDecimal(num);
        PushTargetType targetType = "group".equals(type) ? PushTargetType.GROUP
                : "friend".equals(type) ? PushTargetType.FRIEND : null;
        if (number == null || targetType == null || platform.isBlank() || q != null)
            return ResponseEntity.badRequest().body(JSONObject.of("success", false, "message", "请填写推送平台、group 或 friend 类型与完整号码，不支持关键字"));
        var found = lookup.lookup(platform, targetType, number, ConfigUiAccess.memberAccount());
        JSONObject result = JSONObject.of("success", true, "found", found.found(), "platform", platform, "type", type, "num", number);
        if (found.found()) result.put(targetType == PushTargetType.GROUP ? "name" : "nickname", found.name());
        if (found.message() != null) result.put("message", found.message());
        return ResponseEntity.ok(result);
    }
}
