package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.assistant.AssistantException;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.frostnova.nova.core.assistant.AssistantMemoStore;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** 主播页智能助手；主播范围与写请求检查由控制台权限层统一执行。 */
@Slf4j
@RestController
@NovaComponent
@RequestMapping("/config/api/streamers/{platform}/{uid}/assistant")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class AssistantAskController {
    private final AssistantAskService service;
    private final AssistantMemoStore memos;

    public AssistantAskController(AssistantAskService service, AssistantLocalStore local) {
        this.service = service;
        this.memos = local.memos();
    }

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

    @GetMapping("/memos")
    public ResponseEntity<JSONObject> memos(@PathVariable String platform, @PathVariable Long uid) {
        return memoResponse(memos.list(platform, uid));
    }

    @PostMapping("/memos")
    public ResponseEntity<JSONObject> addMemo(@PathVariable String platform, @PathVariable Long uid, HttpServletRequest request) {
        MemoInput input = memoInput(request);
        return memoResponse(memos.add(platform, uid, input.text(), input.expiresOn()));
    }

    @PutMapping("/memos/{id}")
    public ResponseEntity<JSONObject> updateMemo(@PathVariable String platform, @PathVariable Long uid,
                                                  @PathVariable String id, HttpServletRequest request) {
        MemoInput input = memoInput(request);
        return memoResponse(memos.update(platform, uid, id, input.text(), input.expiresOn()));
    }

    @DeleteMapping("/memos/{id}")
    public ResponseEntity<JSONObject> deleteMemo(@PathVariable String platform, @PathVariable Long uid, @PathVariable String id) {
        return memoResponse(memos.delete(platform, uid, id));
    }

    @DeleteMapping("/memos")
    public ResponseEntity<JSONObject> resetUnreadableMemos(@PathVariable String platform, @PathVariable Long uid) {
        return memoResponse(memos.resetUnreadable(platform, uid));
    }

    private ResponseEntity<JSONObject> memoResponse(List<AssistantMemoStore.Memo> items) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(JSONObject.of(
                "success", true, "today", memos.today().toString(), "items", items.stream()
                        .map(memo -> JSONObject.of("id", memo.id(), "text", memo.text(), "createdOn", memo.createdOn().toString(),
                                "expiresOn", memo.expiresOn() == null ? null : memo.expiresOn().toString()))
                        .toList()));
    }

    private record MemoInput(String text, LocalDate expiresOn) { }

    private static MemoInput memoInput(HttpServletRequest request) {
        JSONObject body;
        try {
            byte[] bytes = request.getInputStream().readNBytes(4097);
            if (bytes.length > 4096) throw new AssistantException("备忘内容须为 1–60 字");
            body = JSON.parseObject(bytes);
        } catch (AssistantException error) { throw error; }
        catch (Exception error) { throw new AssistantException("备忘格式不正确"); }
        if (body == null || !(body.get("text") instanceof String text))
            throw new AssistantException("备忘内容须为 1–60 字");
        Object raw = body.get("expiresOn");
        if (raw == null) return new MemoInput(text, null);
        if (raw instanceof String date && date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && !date.startsWith("0000")) {
            try { return new MemoInput(text, LocalDate.parse(date)); }
            catch (DateTimeParseException ignored) { }
        }
        throw new AssistantException("截止日格式不正确");
    }

    @ExceptionHandler(AssistantException.class)
    public ResponseEntity<JSONObject> error(AssistantException error) {
        HttpStatus status = switch (error.reason()) {
            case "memo_missing" -> HttpStatus.NOT_FOUND;
            case AssistantMemoStore.UNREADABLE, "memo_unavailable" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "memo_readable" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(JSONObject.of("success", false, "reason", error.reason(), "message", error.getMessage()));
    }
}
