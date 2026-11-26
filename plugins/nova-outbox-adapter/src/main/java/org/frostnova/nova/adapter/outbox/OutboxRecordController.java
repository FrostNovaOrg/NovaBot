package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.adapter.outbox.OutboxRecord.Status;
import org.frostnova.nova.adapter.outbox.OutboxRecord.Streamer;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigUiOperationProvider;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@NovaComponent
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping(ConfigUiController.BASE_PATH + "/api/outbox")
public class OutboxRecordController implements ConfigUiOperationProvider {
    private final OutboxService service;

    OutboxRecordController(OutboxService service) {
        this.service = service;
    }

    private static boolean visible(Streamer streamer) {
        return ConfigUiAccess.visible(streamer.platform(), streamer.uid());
    }

    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(value = "status", required = false) Status status,
                                       @RequestParam(value = "beforeId", defaultValue = "0") long beforeId,
                                       @RequestParam(value = "limit", defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("每页条数须为 1 至 100");
        var rows = service.newest(status, beforeId, limit + 1, OutboxRecordController::visible);
        boolean hasMore = rows.size() > limit;
        return Map.of("success", true, "records", hasMore ? rows.subList(0, limit) : rows, "hasMore", hasMore);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return Map.of("success", true, "counts", service.summary(OutboxRecordController::visible));
    }

    @GetMapping("/records/{num}/images/{segment}")
    public ResponseEntity<byte[]> image(@PathVariable("num") long id, @PathVariable("segment") int index) {
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; sandbox")
                    .body(service.readImage(id, index, OutboxRecordController::visible));
        } catch (IllegalArgumentException absent) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/records/{num}/requeue")
    public Map<String, Object> requeue(@PathVariable("num") long id) {
        service.requeue(id);
        return Map.of("success", true);
    }

    @PostMapping("/records/{num}/void")
    public Map<String, Object> voidPending(@PathVariable("num") long id) {
        service.voidPending(id);
        return Map.of("success", true);
    }

    @ExceptionHandler(OutboxService.StatusConflict.class)
    public ResponseEntity<?> conflict(OutboxService.StatusConflict failure) {
        return ResponseEntity.status(409).body(Map.of("success", false, "message", failure.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", failure.getMessage()));
    }

    @Override public List<Operation> operations() {
        return List.of(new Operation("POST", "outbox/records/{num}/requeue", "OUTBOX_REQUEUE", "将外发记录重新放回待发"),
                new Operation("POST", "outbox/records/{num}/void", "OUTBOX_VOID", "作废待发记录"));
    }
}
