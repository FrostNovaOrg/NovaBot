package org.frostnova.nova.console.controller;

import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.WeeklyReportDeliveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 推送目标旁的周报状态、按会话金额设置预览与试发。 */
@NovaComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/weekly-report/{platform}/{uid}")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class WeeklyReportPushController {
    private final WeeklyReportDeliveryService reports;
    public WeeklyReportPushController(WeeklyReportDeliveryService reports) { this.reports = reports; }

    private PushTarget target(String pushPlatform, int type, Long num) {
        var target = new PushTarget();
        target.setPlatform(pushPlatform);
        target.setType(PushTargetType.fromCode(type));
        target.setNum(num);
        return target;
    }
    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable String platform, @PathVariable Long uid,
            @RequestParam String pushPlatform, @RequestParam int type, @RequestParam Long num) {
        return Map.of("success", true, "status", reports.status(WeeklyReportDeliveryService.key(
                platform, uid, target(pushPlatform, type, num))));
    }
    @GetMapping("/preview")
    public ResponseEntity<?> preview(@PathVariable String platform, @PathVariable Long uid,
            @RequestParam String pushPlatform, @RequestParam int type, @RequestParam Long num,
            @RequestParam(defaultValue = "png") String format) {
        if (!format.equals("png") && !format.equals("json"))
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请选择 png 或 json"));
        var preview = reports.preview(platform, uid, target(pushPlatform, type, num));
        if (format.equals("png") && preview.image() != null)
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(preview.image());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("success", true,
                "summary", preview.summary(), "text", preview.text(), "data", preview.data(),
                "imageAvailable", preview.image() != null));
    }
    @PostMapping("/trial")
    public Map<String, Object> trial(@PathVariable String platform, @PathVariable Long uid,
            @RequestParam String pushPlatform, @RequestParam int type, @RequestParam Long num) {
        try {
            var raw = reports.trial(platform, uid, target(pushPlatform, type, num));
            boolean ok = raw != null && Integer.valueOf(0).equals(raw.getInteger("code"));
            return Map.of("success", ok, "message", ok ? "已发送，请到对应会话中确认是否收到"
                    : "试发失败：" + (raw == null ? "消息未发出" : raw.getString("message")));
        } catch (RuntimeException e) {
            return Map.of("success", false, "message", "试发失败：" + e.getMessage());
        }
    }
}
