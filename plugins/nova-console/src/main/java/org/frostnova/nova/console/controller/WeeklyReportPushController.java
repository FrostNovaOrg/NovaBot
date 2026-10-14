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
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.HtmlUtils;

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
        var found = reports.preview(platform, uid, target(pushPlatform, type, num));
        if (found.isEmpty()) return ResponseEntity.status(404).body(Map.of("success", false,
                "message", StreamerController.MISSING_STREAMER));
        var preview = found.get();
        if (format.equals("png") && preview.image() != null)
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(preview.image());
        if (format.equals("png")) {
            String html = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                    + "<title>运营周报预览</title><style>body{max-width:52rem;margin:2rem auto;padding:0 1rem;"
                    + "font-family:system-ui,sans-serif;line-height:1.7}pre{white-space:pre-wrap;font:inherit}</style>"
                    + "<h1>运营周报预览</h1><p>" + HtmlUtils.htmlEscape(preview.summary())
                    + "</p><pre>" + HtmlUtils.htmlEscape(preview.text()) + "</pre></html>";
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .contentType(new MediaType("text", "html", StandardCharsets.UTF_8)).body(html);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("success", true,
                "summary", preview.summary(), "text", preview.text(),
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
