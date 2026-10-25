package org.frostnova.nova.console.controller;

import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.StreamerProfileStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 只读档案；成员范围由统一的主播权限表按路径校验。 */
@NovaComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/streamers")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class StreamerProfileController {
    private final StreamerProfileStore profiles;
    public StreamerProfileController(StreamerProfileStore profiles) { this.profiles = profiles; }

    @GetMapping("/{platform}/{uid}/profile")
    public ResponseEntity<?> profile(@PathVariable String platform, @PathVariable long uid) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("success", true, "profile", profiles.read(platform, uid)));
    }
}
