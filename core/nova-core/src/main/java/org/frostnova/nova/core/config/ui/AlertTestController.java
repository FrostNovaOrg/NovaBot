package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.alert.AlertChannel;
import org.frostnova.nova.core.alert.AlertRecipientField;
import org.frostnova.nova.core.alert.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    static final String CHANNELS_PATH = ConfigUiController.BASE_PATH + "/api/alert/channels";

    private final AlertService alertService;

    @Autowired
    public AlertTestController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 已登记的告警通道及其收件人栏
     * <p>
     * 设置页按这一份画机器人那一路的收件人栏。核心不认识任何一家的配置键：
     * 没有申报时 {@code recipient} 是空数组，那一路就没有收件人栏。
     * 零通道时 {@code channels} 也是空数组，不是省略这一键。
     * @return {@code {channels:[{id,name,recipient:[{key,label,type,placeholder,pattern,fill}]}]}}
     */
    @GetMapping(value = CHANNELS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public JSONObject channels() {
        JSONArray list = new JSONArray();
        for (AlertChannel channel : alertService.declaredChannels()) {
            JSONObject item = new JSONObject();
            item.put("id", channel.id());
            item.put("name", channel.name());
            JSONArray recipient = new JSONArray();
            List<AlertRecipientField> fields = channel.recipientFields();
            if (fields != null) {
                for (AlertRecipientField field : fields) {
                    recipient.add(recipientJson(field));
                }
            }
            item.put("recipient", recipient);
            list.add(item);
        }
        JSONObject body = new JSONObject();
        body.put("channels", list);
        return body;
    }

    private static JSONObject recipientJson(AlertRecipientField field) {
        JSONObject json = new JSONObject();
        json.put("key", field.key());
        json.put("label", field.label());
        json.put("type", field.type());
        json.put("placeholder", field.placeholder());
        json.put("pattern", field.pattern());
        json.put("fill", field.fill());
        return json;
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
