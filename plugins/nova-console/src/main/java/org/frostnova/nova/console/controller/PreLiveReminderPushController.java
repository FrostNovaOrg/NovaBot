package org.frostnova.nova.console.controller;

import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.PreLiveReminderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 当前主播、当前通道的开播前提醒状态与试发。 */
@NovaComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/pre-live-reminder/{platform}/{uid}")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class PreLiveReminderPushController {
    private final PreLiveReminderService reminders;

    public PreLiveReminderPushController(PreLiveReminderService reminders) { this.reminders = reminders; }

    private PushTarget target(String platform, int type, long num) {
        var target = new PushTarget();
        target.setPlatform(platform); target.setType(PushTargetType.fromCode(type)); target.setNum(num);
        return target;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable String platform, @PathVariable long uid,
            @RequestParam String pushPlatform, @RequestParam int type, @RequestParam long num) {
        var status = reminders.status(platform, uid, target(pushPlatform, type, num));
        return Map.of("success", true, "status", status.status(), "profileUnreadable", status.profileUnreadable(),
                "historyUnreadable", status.historyUnreadable());
    }

    @PostMapping("/trial")
    public PreLiveReminderService.TrialResult trial(@PathVariable String platform, @PathVariable long uid,
            @RequestParam String pushPlatform, @RequestParam int type, @RequestParam long num,
            @RequestParam(defaultValue = "false") boolean confirmPaused,
            @RequestParam(defaultValue = "false") boolean confirmQuiet,
            @RequestParam(defaultValue = "false") boolean confirmDisabled) {
        return reminders.trial(platform, uid, target(pushPlatform, type, num), confirmPaused, confirmQuiet, confirmDisabled);
    }
}
