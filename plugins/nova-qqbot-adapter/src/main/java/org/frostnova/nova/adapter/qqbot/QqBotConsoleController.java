package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@NovaComponent
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping(value = ConfigUiController.BASE_PATH + "/api/qqbot", produces = MediaType.APPLICATION_JSON_VALUE)
public class QqBotConsoleController {
    private final QqBotCredentials credentials;
    private final QqBotSettings settings;
    private final QqBotHealthProbe probe;
    private final QqBotDirectory directory;

    QqBotConsoleController(QqBotCredentials credentials, QqBotSettings settings, QqBotHealthProbe probe,
                           QqBotDirectory directory) {
        this.credentials = credentials;
        this.settings = settings;
        this.probe = probe;
        this.directory = directory;
    }

    @GetMapping("/state")
    public ResponseEntity<JSONObject> state() {
        var config = settings.snapshot();
        var connection = probe.connectionState();
        String level = probe.active() ? connection.health().level().name() : "OFF";
        return response(HttpStatus.OK, JSONObject.of("success", true, "configured", config.configured(),
                "appId", config.appId(), "secretMasked", config.maskedSecret(), "intervalMillis", config.intervalMillis(),
                "connection", JSONObject.of("level", level, "summary", connection.health().summary(),
                        "robotName", connection.robotName()),
                "groups", directory.consoleGroups()));
    }

    @PostMapping("/credentials")
    public ResponseEntity<JSONObject> credentials(@RequestBody JSONObject body) {
        Object secret = body.get("appSecret");
        if (!(body.get("appId") instanceof String appId) || (secret != null && !(secret instanceof String))) {
            throw new IllegalArgumentException("请填写 AppID 和 AppSecret");
        }
        credentials.save(appId.strip(), (String) secret);
        return response(HttpStatus.OK, JSONObject.of("success", true));
    }

    @PostMapping("/interval")
    public ResponseEntity<JSONObject> interval(@RequestBody JSONObject body) {
        if (!(body.get("millis") instanceof Number number)) {
            throw new IllegalArgumentException("请填写整数毫秒数");
        }
        long millis;
        try {
            millis = new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("请填写整数毫秒数");
        }
        settings.setIntervalMillis(millis);
        JSONObject result = JSONObject.of("success", true, "intervalMillis", millis);
        if (millis < QqBotSettings.DEFAULT_INTERVAL_MILLIS) {
            result.put("warning", "每分钟 60 条只对做过个人认证或企业认证的机器人有效，没认证的超频会被拒");
        }
        return response(HttpStatus.OK, result);
    }

    @PostMapping("/groups/{num}/name")
    public ResponseEntity<JSONObject> groupName(@PathVariable("num") String num, @RequestBody JSONObject body) {
        Long number = ConfigUiStreamerId.positiveDecimal(num);
        if (number == null || !(body.get("name") instanceof String name)) {
            throw new IllegalArgumentException("请填写群编号和群名");
        }
        directory.renameGroup(number, name);
        return response(HttpStatus.OK, JSONObject.of("success", true));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<JSONObject> failure(RuntimeException error) {
        HttpStatus status = error instanceof IllegalStateException ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
        String message = error instanceof HttpMessageNotReadableException ? "请求格式不正确" : error.getMessage();
        return response(status, JSONObject.of("success", false, "message", message));
    }

    private static ResponseEntity<JSONObject> response(HttpStatus status, JSONObject body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
