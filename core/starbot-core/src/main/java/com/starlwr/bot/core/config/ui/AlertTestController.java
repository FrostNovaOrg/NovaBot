package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.alert.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「发一条测试」
 * <p>
 * 告警这套东西有个让人难受的性质：<b>它平时不响，而它坏了的时候也不响</b>。
 * 配完之后没有任何反馈能告诉使用者这一路是通的，直到某天真出了事，
 * 他才发现那个 Webhook 地址少了一个字母。这支接口就是那个反馈。
 *
 * <h2>为什么用 POST</h2>
 * 它真的会往外发一条消息。GET 会被浏览器预取、被缓存重放，也会被写进任何一份访问日志里
 * 当成一次可以随手重试的读取——而每一次「重试」都是别人手机上多响一声。
 *
 * <h2>路径为什么挂在控制台底下</h2>
 * 与 {@link ConfigUiController#BASE_PATH} 同源，因此来源 IP 白名单、令牌或口令、
 * 使用协议三道闸一道不少。另起一套鉴权的下场是两套规则迟早对不上，
 * 而对不上的那一侧通常是新写的这一套。
 */
@Slf4j
@RestController
public class AlertTestController {
    static final String TEST_PATH = ConfigUiController.BASE_PATH + "/api/alert/test";

    private final AlertService alertService;

    @Autowired
    public AlertTestController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 往某一路告警通道发一条测试
     * @param channel 通道标识：qq、webhook 或 mail
     * @return 结果。通道标识认不出来时回 400，其余一律回 200——
     *         「这一路还没配好」与「发不出去」都是关于配置的答复，不是这次请求本身出了错
     */
    @PostMapping(value = TEST_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JSONObject> test(@RequestParam(required = false) String channel) {
        AlertService.TestResult result = alertService.test(channel == null ? "" : channel.strip());

        JSONObject body = new JSONObject();
        body.put("success", result.delivered());
        body.put("channel", result.id());
        body.put("name", result.name());
        body.put("status", result.status().name());
        body.put("message", result.message());

        if (result.status() == AlertService.TestResult.Status.UNKNOWN) {
            return ResponseEntity.badRequest().body(body);
        }

        return ResponseEntity.ok(body);
    }
}
