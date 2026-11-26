package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigUiOperationProvider;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@NovaComponent
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping(ConfigUiController.BASE_PATH + "/api/outbox/conversations")
public class OutboxConversationController implements ConfigUiOperationProvider {
    public record Edit(String name, String remark, BigDecimal minIntervalSeconds, BigDecimal dailyLimit) {}
    private final OutboxConversations conversations;

    OutboxConversationController(OutboxConversations conversations) { this.conversations = conversations; }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("success", true, "conversations", conversations.list());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Edit edit) {
        return result(conversations.create(edit.name(), edit.remark(), integer(edit.minIntervalSeconds(), 0),
                integer(edit.dailyLimit(), 0)));
    }

    @PutMapping("/{num}")
    public Map<String, Object> update(@PathVariable("num") long num, @RequestBody Edit edit) {
        return result(conversations.update(num, edit.name(), edit.remark(), integer(edit.minIntervalSeconds(), null),
                integer(edit.dailyLimit(), null)));
    }

    @PostMapping("/{num}/disable")
    public Map<String, Object> disable(@PathVariable("num") long num) {
        return result(conversations.disable(num));
    }

    private static Integer integer(BigDecimal number, Integer fallback) {
        if (number == null) return fallback;
        try {
            return number.intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("外发会话限量须为整数");
        }
    }

    private static Map<String, Object> result(OutboxConversations.Conversation conversation) {
        return Map.of("success", true, "conversation", conversation);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
    }

    @Override public List<Operation> operations() {
        return List.of(new Operation("POST", "outbox/conversations", "OUTBOX_CREATE", "新增外发会话"),
                new Operation("PUT", "outbox/conversations/{num}", "OUTBOX_EDIT", "修改外发会话"),
                new Operation("POST", "outbox/conversations/{num}/disable", "OUTBOX_DISABLE", "停用外发会话"));
    }
}
