package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.assistant.AssistantException;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 主播页智能助手；主播范围与写请求检查由控制台权限层统一执行。 */
@Slf4j
@RestController
@NovaComponent
@RequestMapping("/config/api/streamers/{platform}/{uid}/assistant")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class AssistantAskController {
    private final AssistantAskService service;

    public AssistantAskController(AssistantAskService service) { this.service = service; }

    @GetMapping("/state")
    public org.frostnova.nova.core.assistant.AssistantClient.State state(HttpServletRequest request) {
        if (!(request.getAttribute(ConfigUiAccess.ACCOUNT) instanceof ConfigUiAccount account))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "尚未登录");
        return service.state(account.id());
    }

    @PostMapping("/ask")
    public JSONObject ask(@PathVariable String platform, @PathVariable Long uid, HttpServletRequest request) {
        if (!(request.getAttribute(ConfigUiAccess.ACCOUNT) instanceof ConfigUiAccount account))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "尚未登录");
        JSONObject body;
        try {
            byte[] bytes = request.getInputStream().readNBytes(65537);
            if (bytes.length > 65536) throw new AssistantException("提问内容过长");
            body = JSON.parseObject(bytes);
        } catch (AssistantException error) { throw error; }
        catch (Exception error) { throw new AssistantException("提问格式不正确"); }
        JSONObject result = service.ask(platform, uid, account.id(), body);
        if (account.role() != ConfigUiAccount.Role.ADMIN) {
            result.getJSONObject("remaining").remove("spentYuan");
            result.getJSONObject("remaining").remove("monthlyBudgetYuan");
        }
        log.info("{} 使用主播 {}:{} 的智能助手，查数 {} 次，花费 {} 元", ConfigUiAccess.actor(), platform, uid,
                result.getJSONArray("steps").size(), result.getBigDecimal("costYuan"));
        return result;
    }

    @ExceptionHandler(AssistantException.class)
    public ResponseEntity<JSONObject> error(AssistantException error) {
        return ResponseEntity.badRequest().body(JSONObject.of("success", false, "message", error.getMessage()));
    }
}
