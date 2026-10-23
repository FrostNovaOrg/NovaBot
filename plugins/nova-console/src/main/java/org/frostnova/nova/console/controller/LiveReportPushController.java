package org.frostnova.nova.console.controller;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveReportDeliveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 当前通道的上一份下播报告。 */
@Slf4j
@NovaComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/live-report/{platform}/{uid}")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class LiveReportPushController {
    private final LiveReportDeliveryService reports;

    public LiveReportPushController(LiveReportDeliveryService reports) { this.reports = reports; }

    @GetMapping("/latest")
    public Map<String, Object> latest(@PathVariable String platform, @PathVariable Long uid) {
        return reports.latest(platform, uid)
                .<Map<String, Object>>map(detail -> Map.of("success", true, "startTime", detail.startTime()))
                .orElseGet(() -> Map.of("success", false, "message", LiveReportDeliveryService.NO_REPORT));
    }

    @PostMapping("/send-latest")
    public Map<String, Object> sendLatest(@PathVariable String platform, @PathVariable Long uid,
            @RequestParam String pushPlatform, @RequestParam int type, @RequestParam Long num,
            @RequestParam long start) {
        var target = new PushTarget();
        target.setPlatform(pushPlatform);
        target.setType(PushTargetType.fromCode(type));
        target.setNum(num);
        log.info("{} 向会话 {}:{}:{} 发送主播 {}:{} 的上一份下播报告，开播时刻 {}",
                ConfigUiAccess.actor(), pushPlatform, type, num, platform, uid, start);
        try {
            var raw = reports.sendLatest(platform, uid, target, start);
            boolean ok = raw != null && Integer.valueOf(0).equals(raw.getInteger("code"));
            return Map.of("success", ok, "message", ok ? "上一份下播报告已发送，请到对应会话中确认是否收到"
                    : "发送上一份下播报告失败：" + (raw == null ? "消息未发出" : raw.getString("message")));
        } catch (RuntimeException e) {
            return Map.of("success", false, "message", "发送上一份下播报告失败：" + e.getMessage());
        }
    }
}
