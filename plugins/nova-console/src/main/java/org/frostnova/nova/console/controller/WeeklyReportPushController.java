package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiAudit;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
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

/** 通道的周报状态、按草稿预览与发送上一周的报告。 */
@Slf4j
@NovaComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/weekly-report/{platform}/{uid}")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class WeeklyReportPushController {
    private final WeeklyReportDeliveryService reports;
    private final AbstractDataSource dataSource;

    public WeeklyReportPushController(WeeklyReportDeliveryService reports) { this(reports, null); }

    @Autowired
    public WeeklyReportPushController(WeeklyReportDeliveryService reports, AbstractDataSource dataSource) {
        this.reports = reports;
        this.dataSource = dataSource;
    }

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
                platform, uid, target(pushPlatform, type, num))), "previousWeek", reports.previousWeek());
    }
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@PathVariable String platform, @PathVariable Long uid,
            @RequestBody(required = false) JSONObject draft,
            @RequestParam(defaultValue = "png") String format) {
        if (!format.equals("png") && !format.equals("json"))
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请选择 png 或 json"));
        var identity = new PushTarget();
        identity.setType(PushTargetType.GROUP);
        if (draft != null) {
            identity.setPlatform(draft.getString("platform"));
            identity.setNum(draft.getLong("num"));
            if (draft.getInteger("type") != null) identity.setType(PushTargetType.of(draft.getInteger("type")));
        }
        var found = reports.preview(platform, uid, identity, draft);
        if (found.isEmpty()) return ResponseEntity.status(404).body(Map.of("success", false,
                "message", StreamerController.MISSING_STREAMER));
        var preview = found.get();
        if (format.equals("png") && preview.image() != null)
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(preview.image());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("success", true,
                "summary", preview.summary(), "text", preview.text(),
                "imageAvailable", preview.image() != null));
    }
    @PostMapping("/trial")
    public Map<String, Object> trial(@PathVariable String platform, @PathVariable Long uid,
            @RequestParam String pushPlatform, @RequestParam int type, @RequestParam Long num) {
        log.info("{} 向会话 {}:{}:{} 发送主播 {} 上一周的运营周报", ConfigUiAccess.actor(), pushPlatform, type, num,
                dataSource == null ? platform + ":" + uid : ConfigUiAudit.streamer(dataSource, platform, String.valueOf(uid)));
        try {
            var raw = reports.trial(platform, uid, target(pushPlatform, type, num));
            boolean ok = raw != null && Integer.valueOf(0).equals(raw.getInteger("code"));
            return Map.of("success", ok, "message", ok ? "上一周的报告已发送，请到对应会话中确认是否收到"
                    : "发送上一周的报告失败：" + (raw == null ? "消息未发出" : raw.getString("message")));
        } catch (RuntimeException e) {
            return Map.of("success", false, "message", "发送上一周的报告失败：" + e.getMessage());
        }
    }
}
