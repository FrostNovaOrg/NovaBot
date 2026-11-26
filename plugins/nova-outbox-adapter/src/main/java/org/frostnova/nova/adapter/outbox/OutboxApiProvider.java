package org.frostnova.nova.adapter.outbox;

import jakarta.servlet.ServletContext;
import org.frostnova.nova.adapter.outbox.OutboxRecord.Status;
import org.frostnova.nova.adapter.outbox.OutboxRecord.Streamer;
import org.frostnova.nova.core.api.NovaApiBytes;
import org.frostnova.nova.core.api.NovaApiException;
import org.frostnova.nova.core.api.NovaApiFilter;
import org.frostnova.nova.core.api.NovaApiPage;
import org.frostnova.nova.core.api.NovaApiProvider;
import org.frostnova.nova.core.api.NovaApiRequest;
import org.frostnova.nova.core.api.NovaApiRoute;
import org.frostnova.nova.core.api.NovaApiScope;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

@NovaComponent
public class OutboxApiProvider implements NovaApiProvider {
    private static final String READ = "outbox:read";
    private static final String DELIVER = "outbox:deliver";
    private final OutboxService service;
    private final OutboxConversations conversations;
    private final String messagesPath;

    OutboxApiProvider(OutboxService service, OutboxConversations conversations, Optional<ServletContext> context) {
        this.service = service;
        this.conversations = conversations;
        this.messagesPath = context.map(ServletContext::getContextPath).orElse("") + NovaApiFilter.V1 + "/outbox/messages/";
    }

    @Override public String segment() { return "outbox"; }

    @Override public List<NovaApiScope> scopes() {
        return List.of(new NovaApiScope(READ, "读取外发箱", "查看外发会话、可见的通知和图片"),
                new NovaApiScope(DELIVER, "投递外发通知", "认领可见的通知并报告发送结果"));
    }

    @Override public List<NovaApiRoute> routes() {
        return List.of(NovaApiRoute.get("messages", READ, this::list),
                NovaApiRoute.get("messages/{id}", READ, item(request ->
                        message(service.get(id(request), visible(request))))),
                NovaApiRoute.get("messages/{id}/images/{index}", READ, item(this::image)),
                NovaApiRoute.post("messages/{id}/claim", DELIVER, item(this::claim)),
                NovaApiRoute.post("messages/{id}/receipt", DELIVER, item(this::receipt)),
                NovaApiRoute.get("conversations", READ, request -> NovaApiPage.last(conversations.list().stream()
                        .map(c -> Map.of("id", c.id(), "name", c.name(), "remark", c.remark(), "enabled", c.enabled()))
                        .toList())));
    }

    private static Predicate<Streamer> visible(NovaApiRequest request) {
        var caller = request.caller();
        return streamer -> streamer.uid() == null ? caller.admin() : caller.canSee(streamer.platform(), streamer.uid());
    }

    private NovaApiPage list(NovaApiRequest request) {
        String filter = request.query("status");
        Status status;
        try {
            status = filter == null ? Status.PENDING : Status.valueOf(filter);
        } catch (IllegalArgumentException invalid) {
            throw NovaApiException.badRequest("未知的外发状态");
        }
        int limit = (int) queryNumber(request.query("limit"), 20, 1, 100, "每页条数须为 1 至 100");
        long cursor = queryNumber(request.query("cursor"), 0, 0, Long.MAX_VALUE, "游标须为非负整数");
        var rows = service.listForDevice(status, cursor, limit + 1, visible(request));
        var page = rows.subList(0, Math.min(rows.size(), limit));
        var items = new ArrayList<>(page.size());
        for (var row : page) items.add(message(row));
        String next = rows.size() > limit ? Long.toString(page.get(limit - 1).id()) : null;
        return NovaApiPage.of(items, next);
    }

    private Map<String, Object> message(OutboxRecord row) {
        var segments = new ArrayList<Map<String, Object>>(row.segments().size());
        for (int index = 0; index < row.segments().size(); index++) {
            var part = row.segments().get(index);
            segments.add(switch (part.type()) {
                case TEXT -> Map.of("type", part.type(), "text", part.value());
                case IMAGE_URL -> Map.of("type", part.type(), "url", part.value());
                case IMAGE_BYTES -> {
                    Long size = service.readableImageSize(row.id(), index);
                    var image = new LinkedHashMap<String, Object>();
                    image.put("type", part.type());
                    image.put("index", index);
                    image.put("url", messagesPath + row.id() + "/images/" + index);
                    image.put("byteCount", size);
                    if (size == null) image.put("unavailable", true);
                    yield image;
                }
            });
        }
        return Map.of("id", row.id(), "status", row.status(), "createdAt", row.createdAt().toString(),
                "expiresAt", row.expiresAt().toString(), "conversation", Map.of("id", row.conversationId(),
                        "name", row.conversationName()), "streamer", row.streamer(), "kind", row.kind(),
                "atAll", row.atAll(), "segments", segments);
    }

    private NovaApiBytes image(NovaApiRequest request) throws IOException {
        int index;
        try {
            index = Integer.parseInt(request.path("index"));
        } catch (NumberFormatException invalid) {
            throw NovaApiException.notFound(OutboxService.NO_LOCAL_IMAGE);
        }
        byte[] bytes = service.readImage(id(request), index, visible(request));
        return new NovaApiBytes(imageType(bytes), bytes);
    }

    private static MediaType imageType(byte[] bytes) throws IOException {
        // 只识别文件头，无需解码像素，也不将图片写入临时文件。
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    String[] types = reader.getOriginatingProvider().getMIMETypes();
                    if (types != null && types.length > 0) return MediaType.parseMediaType(types[0]);
                } finally {
                    reader.dispose();
                }
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private Object claim(NovaApiRequest request) {
        var claimed = service.claimForDevice(id(request), request.caller().tokenName(), visible(request));
        return Map.of("message", message(claimed), "claimNumber", claimed.claimNumber());
    }

    private Object receipt(NovaApiRequest request) {
        var body = request.body();
        if (!(body.get("claimNumber") instanceof Number number) || !(number instanceof Integer || number instanceof Long)) {
            throw NovaApiException.badRequest("认领编号须为整数");
        }
        if (!(body.get("outcome") instanceof String outcome)
                || !(outcome.equals("SENT") || outcome.equals("FAILED") || outcome.equals("SKIPPED"))) {
            throw NovaApiException.badRequest("发送结果须为 SENT、FAILED 或 SKIPPED");
        }
        var note = body.get("note");
        if (note != null && (!(note instanceof String text) || text.codePointCount(0, text.length()) > 200)) {
            throw NovaApiException.badRequest("说明须为不超过 200 字的文字");
        }
        var result = service.receipt(id(request), number.longValue(), Status.valueOf(outcome), (String) note,
                visible(request));
        return Map.of("id", result.id(), "status", result.status());
    }

    private static long queryNumber(String raw, long fallback, long min, long max, String error) {
        if (raw == null) return fallback;
        try {
            long value = Long.parseLong(raw);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException invalid) {
            // 超出整数范围与超出参数范围使用相同的说明。
        }
        throw NovaApiException.badRequest(error);
    }

    private static long id(NovaApiRequest request) {
        try {
            return Long.parseLong(request.path("id"));
        } catch (NumberFormatException invalid) {
            throw NovaApiException.notFound("外发记录不存在");
        }
    }

    private static NovaApiRoute.Handler item(NovaApiRoute.Handler handler) {
        return request -> {
            try {
                return handler.handle(request);
            } catch (OutboxService.NotFound missing) {
                throw NovaApiException.notFound(missing.getMessage());
            } catch (OutboxService.StatusConflict conflict) {
                throw NovaApiException.conflict(conflict.getMessage());
            }
        };
    }
}
