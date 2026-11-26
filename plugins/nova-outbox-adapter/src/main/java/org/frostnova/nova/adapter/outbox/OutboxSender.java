package org.frostnova.nova.adapter.outbox;

import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.model.Message.PushOrigin;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.NovaSenderService;

import java.util.Map;

@Slf4j
@NovaComponent
class OutboxSender implements Sender.DeferredDelivery {
    private final NovaSenderService registry;
    private final OutboxService outbox;

    OutboxSender(NovaSenderService registry, OutboxService outbox) {
        this.registry = registry;
        this.outbox = outbox;
    }

    @PostConstruct
    void register() {
        // 外发会话在推送目标里按群记，号码是会话编号
        registry.addSender(new Sender(OutboxPluginAutoConfiguration.PLATFORM, null, "", 0, this), "外发",
                java.util.Set.of(org.frostnova.nova.core.enums.PushTargetType.GROUP));
    }

    @Override public JSONObject deliver(Map<String, String> headers, Map<String, Object> params, PushOrigin origin) {
        try {
            if (!(params.get("num") instanceof Number number)) throw new IllegalArgumentException("外发会话编号无效");
            var streamer = new OutboxRecord.Streamer(origin.platform(), origin.uid(), origin.name());
            var records = outbox.enqueue(number.longValue(), streamer, origin.kind(), (String) params.get("content"));
            for (var record : records) {
                if (record.status() == OutboxRecord.Status.LIMITED) return failure("超限未入箱：" + record.note());
            }
            return JSONObject.of("code", 0, "id", String.valueOf(records.get(0).id()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("外发投递失败：{}", e.getMessage());
            return failure(e.getMessage());
        }
    }

    private static JSONObject failure(String reason) {
        return JSONObject.of("code", -1, "message", reason, SKIP_IMAGE_FALLBACK, true);
    }
}
