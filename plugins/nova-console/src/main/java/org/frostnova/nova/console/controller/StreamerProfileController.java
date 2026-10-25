package org.frostnova.nova.console.controller;

import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiAudit;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.StreamerProfileStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.io.IOException;

/** 展示档案与核对后的恢复入口；成员范围由统一的主播权限表按路径校验。 */
@NovaComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/streamers")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class StreamerProfileController {
    private final StreamerProfileStore profiles;
    private final TimelineWriter timeline;
    public StreamerProfileController(StreamerProfileStore profiles, TimelineWriter timeline) {
        this.profiles = profiles; this.timeline = timeline;
    }

    @GetMapping("/{platform}/{uid}/profile")
    public ResponseEntity<?> profile(@PathVariable String platform, @PathVariable long uid) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("success", true, "profile", profiles.read(platform, uid)));
    }

    @PostMapping("/{platform}/{uid}/profile/recover")
    public ResponseEntity<?> recover(@PathVariable String platform, @PathVariable long uid) {
        try {
            var profile = profiles.recover(platform, uid);
            String actor = ConfigUiAudit.safe(ConfigUiAccess.actor());
            String streamer = platform + ":" + uid;
            timeline.record(TimelineEvent.of(TimelineEventType.SETTINGS_APPLIED, TimelineEvent.Level.INFO)
                    .streamer(streamer).text(actor + " 核对并恢复了主播 " + streamer + " 的档案")
                    .detail("actor", actor).detail("streamerPlatform", platform).detail("streamerUid", String.valueOf(uid))
                    .build());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(Map.of("success", true, "profile", profile));
        } catch (IOException failure) {
            return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore())
                    .body(Map.of("success", false, "message", "档案没能保存，请稍后再试"));
        }
    }
}
