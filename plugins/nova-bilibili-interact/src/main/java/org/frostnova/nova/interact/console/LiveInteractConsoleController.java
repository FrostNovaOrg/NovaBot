package org.frostnova.nova.interact.console;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.interact.auto.AutoDanmakuQueue;
import org.frostnova.nova.interact.auto.InteractStore;
import org.frostnova.nova.interact.auto.RoomSettings;
import org.frostnova.nova.interact.auto.ThanksKind;
import org.frostnova.nova.interact.auto.WelcomeSettings;
import org.frostnova.nova.interact.config.InteractProperties;
import org.frostnova.nova.interact.config.InteractRuntimeAppliers;
import org.frostnova.nova.interact.danmaku.DanmakuAccountChoice;
import org.frostnova.nova.interact.danmaku.DanmakuResult;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 「直播间互动」的账号、试发、自动弹幕设置和发送记录
 * <p>
 * 接口只给管理员。选哪个发送账号由设置页的保存接口处理。
 */
@RestController
@NovaComponent
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping(value = ConfigUiController.BASE_PATH + "/api/interact/danmaku", produces = MediaType.APPLICATION_JSON_VALUE)
public class LiveInteractConsoleController {
    private final LiveDanmakuService danmaku;

    private final InteractProperties properties;

    private final InteractStore store;
    private final AutoDanmakuQueue queue;

    LiveInteractConsoleController(LiveDanmakuService danmaku, InteractProperties properties, InteractStore store,
                                  AutoDanmakuQueue queue) {
        this.store = store;
        this.queue = queue;
        this.danmaku = danmaku;
        this.properties = properties;
    }

    @GetMapping("/state")
    public ResponseEntity<JSONObject> state() {
        LiveDanmakuService.AccountStatus status = danmaku.accountStatus();
        JSONArray choices = new JSONArray();
        for (DanmakuAccountChoice choice : DanmakuAccountChoice.values()) {
            choices.add(JSONObject.of("value", choice.value(), "label", choice.label()));
        }
        JSONObject account = new JSONObject();
        account.put("usable", status.usable());
        account.put("failure", status.failure() == null ? null : status.failure().name());
        account.put("reason", status.reason());
        account.put("uid", status.uid() == null ? null : String.valueOf(status.uid()));
        account.put("name", status.choice().shortName());
        account.put("pausedSeconds", status.pausedFor() == null ? null : Math.max(1, status.pausedFor().toSeconds()));
        JSONArray rooms = new JSONArray();
        for (LiveDanmakuService.Room room : danmaku.rooms()) {
            rooms.add(JSONObject.of("uid", String.valueOf(room.uid()), "name", room.name(),
                    "roomId", String.valueOf(room.roomId()), "autoEnabled", store.settings(room.uid()).enabled()));
        }
        JSONObject body = JSONObject.of("success", true,
                "choice", status.choice().value(),
                "choices", choices,
                "settingKey", InteractRuntimeAppliers.ACCOUNT,
                "listenerWarning", InteractRuntimeAppliers.LISTENER_WARNING,
                "account", account,
                "rooms", rooms,
                "historyRooms", store.historyRooms(Instant.now()),
                "minInterval", properties.getDanmaku().getMinInterval(),
                "pauseMinutes", properties.getDanmaku().getPauseMinutes(),
                "defaultLimit", LiveDanmakuService.DEFAULT_LENGTH_LIMIT);
        return response(HttpStatus.OK, body);
    }

    @GetMapping("/limit")
    public ResponseEntity<JSONObject> limit(@RequestParam(name = "uid") String uid) {
        LiveDanmakuService.Room room = room(uid).orElseThrow(() -> new IllegalArgumentException("没有这位主播"));
        LiveDanmakuService.LengthLimit limit = danmaku.lengthLimit(room.roomId());
        return response(HttpStatus.OK, JSONObject.of("success", true, "uid", String.valueOf(room.uid()),
                "roomId", String.valueOf(room.roomId()), "limit", limit.max(), "fromPlatform", limit.fromPlatform()));
    }

    @PostMapping("/test")
    public ResponseEntity<JSONObject> test(@RequestBody JSONObject body) {
        Object rawText = body == null ? null : body.get("text");
        if (rawText != null && !(rawText instanceof String)) {
            throw new IllegalArgumentException("弹幕内容要是一段文字");
        }
        LiveDanmakuService.Room room = room(body == null ? null : body.get("uid"))
                .orElseThrow(() -> new IllegalArgumentException("请选一位已添加的主播"));
        var account = danmaku.accountStatus();
        DanmakuResult result = danmaku.send(room.roomId(), (String) rawText);
        store.result(room, "test", result, Instant.now());
        queue.accountResult(account, result);
        JSONObject json = new JSONObject();
        json.put("success", true);
        json.put("sent", result.sent());
        json.put("uncertain", result.uncertain());
        json.put("failure", result.failure() == null ? null : result.failure().name());
        json.put("failureText", result.failure() == null ? null : result.failure().description());
        json.put("message", result.message());
        json.put("account", result.account() == null ? null : result.account().shortName());
        json.put("platformCode", result.platformCode());
        json.put("platformMessage", result.platformMessage());
        json.put("roomId", String.valueOf(result.roomId()));
        return response(HttpStatus.OK, json);
    }

    @GetMapping("/settings")
    public ResponseEntity<JSONObject> settings(@RequestParam(name = "uid") String uid) {
        var room = room(uid).orElseThrow(() -> new IllegalArgumentException("没有这位主播"));
        return response(HttpStatus.OK, JSONObject.of("success", true, "settings", store.settings(room.uid()),
                "defaultThanksTemplates", ThanksKind.defaultTemplates(), "defaultWelcomeTemplates", WelcomeSettings.defaultTemplates()));
    }

    @PostMapping("/settings")
    public ResponseEntity<JSONObject> save(@RequestBody JSONObject body) {
        var room = room(body.get("uid")).orElseThrow(() -> new IllegalArgumentException("没有这位主播"));
        if (!(body.get("settings") instanceof Map<?, ?>)) throw new IllegalArgumentException("缺少主播设置");
        RoomSettings settings = RoomSettings.parse(body.getJSONObject("settings"));
        try {
            queue.save(room.uid(), settings);
        } catch (IllegalStateException e) {
            return response(HttpStatus.INTERNAL_SERVER_ERROR, JSONObject.of("success", false, "message", "主播设置未能写入磁盘"));
        }
        return response(HttpStatus.OK, JSONObject.of("success", true, "settings", settings));
    }

    @GetMapping("/history")
    public ResponseEntity<JSONObject> history(@RequestParam(name = "uid") String uid,
            @RequestParam(name = "outcome", required = false) InteractStore.Outcome outcome,
            @RequestParam(name = "page", defaultValue = "1") int page) {
        Long streamer = ConfigUiStreamerId.positiveDecimal(uid);
        if (streamer == null) throw new IllegalArgumentException("请选择主播");
        var history = store.history(streamer, outcome, page, Instant.now());
        return response(HttpStatus.OK, JSONObject.of("success", true, "rows", history.rows(), "total", history.total(),
                "pageSize", InteractStore.PAGE_SIZE));
    }

    private Optional<LiveDanmakuService.Room> room(Object uid) {
        Long number = ConfigUiStreamerId.positiveDecimal(uid);
        if (number == null) {
            return Optional.empty();
        }
        return danmaku.rooms().stream().filter(room -> room.uid() == number).findFirst();
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<JSONObject> failure(RuntimeException error) {
        String message = error instanceof HttpMessageNotReadableException ? "请求格式不正确" : error.getMessage();
        return response(HttpStatus.BAD_REQUEST, JSONObject.of("success", false, "message", message));
    }

    private static ResponseEntity<JSONObject> response(HttpStatus status, JSONObject body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
