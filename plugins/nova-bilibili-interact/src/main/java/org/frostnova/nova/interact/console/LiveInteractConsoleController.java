package org.frostnova.nova.interact.console;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.interact.config.InteractRuntimeAppliers;
import org.frostnova.nova.interact.danmaku.DanmakuAccountChoice;
import org.frostnova.nova.interact.danmaku.DanmakuResult;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService;
import org.frostnova.nova.interact.config.InteractProperties;
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

import java.util.Optional;

/**
 * 「直播间互动」页的三个接口：看状态、查房间字数上限、试发一条
 * <p>
 * 三个都只给管理员（见核心的权限表）。回包里只有房间号、主播名、账号 uid 与平台的代码和提示，
 * 没有任何凭据。选哪个账号不在这里改：它是一项设置，走设置保存那一条通道，保存后当场生效。
 */
@RestController
@NovaComponent
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping(value = ConfigUiController.BASE_PATH + "/api/interact/danmaku", produces = MediaType.APPLICATION_JSON_VALUE)
public class LiveInteractConsoleController {
    private final LiveDanmakuService danmaku;

    private final InteractProperties properties;

    LiveInteractConsoleController(LiveDanmakuService danmaku, InteractProperties properties) {
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
                    "roomId", String.valueOf(room.roomId())));
        }
        JSONObject body = JSONObject.of("success", true,
                "choice", status.choice().value(),
                "choices", choices,
                "settingKey", InteractRuntimeAppliers.ACCOUNT,
                "listenerWarning", InteractRuntimeAppliers.LISTENER_WARNING,
                "account", account,
                "rooms", rooms,
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
        DanmakuResult result = danmaku.send(room.roomId(), (String) rawText);
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
