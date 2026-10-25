package org.frostnova.nova.console.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@NovaComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/recap-preview")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class RecapPreviewController {
    private final RecapPreviewService service;
    public RecapPreviewController(RecapPreviewService service) { this.service = service; }

    @GetMapping
    public RecapPreviewService.Streamers streamers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.streamers();
    }

    @GetMapping("/{platform}/{uid}")
    public RecapPreviewService.Preview preview(@PathVariable String platform, @PathVariable long uid,
            @RequestParam(defaultValue = "false") boolean showNicknames, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.preview(platform, uid, showNicknames);
    }
}
