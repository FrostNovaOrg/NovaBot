package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.NovaSenderService;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.frostnova.nova.adapter.qqbot.QqBotPluginAutoConfiguration.DISPLAY_NAME;
import static org.frostnova.nova.adapter.qqbot.QqBotPluginAutoConfiguration.PLATFORM;
import static org.frostnova.nova.core.model.MessagePlaceholders.IMAGE_BASE64_PREFIX;
import static org.frostnova.nova.core.model.MessagePlaceholders.IMAGE_URL_PREFIX;

@Slf4j
@NovaComponent
class QqBotSender {
    private static final Pattern PLACEHOLDER = Pattern.compile("(" + Pattern.quote(IMAGE_BASE64_PREFIX) + "|"
            + Pattern.quote(IMAGE_URL_PREFIX) + "|\\{at=|\\{face=)(.*?)}", Pattern.DOTALL);
    private static final Pattern MENTION = Pattern.compile("<(?=qqbot-|@)|@(?=everyone)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>＜＞\"'，。！？；、（）【】「」《》()]+",
            Pattern.CASE_INSENSITIVE);
    private static final byte[] PNG_HEADER = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final byte[] JPEG_HEADER = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final int MAX_BASE64_LENGTH = 4 * ((QqBotImageDownloader.MAX_BYTES + 2) / 3);
    private static final Duration REPLY_TTL = Duration.ofMinutes(60);
    private static final String NO_REPLY = "已发出但没等到 QQ 的回包，未重发";
    private static final String BAD_RECEIPT = "QQ 的发送回包无法确认送达，未重发";

    private final QqBotSettings settings;
    private final QqBotDirectory directory;
    private final QqBotApiClient api;
    private final QqBotImageDownloader downloader;
    private final NovaSenderService registry;
    private final Clock clock;

    private final Map<ReplyKey, ReplySequence> replySequences = new LinkedHashMap<>();
    private Sender registered;

    @Autowired
    QqBotSender(QqBotSettings settings, QqBotDirectory directory, QqBotApiClient api,
                QqBotImageDownloader downloader, NovaSenderService registry) {
        this(settings, directory, api, downloader, registry, Clock.systemUTC());
    }

    QqBotSender(QqBotSettings settings, QqBotDirectory directory, QqBotApiClient api,
                QqBotImageDownloader downloader, NovaSenderService registry, Clock clock) {
        this.settings = settings;
        this.directory = directory;
        this.api = api;
        this.downloader = downloader;
        this.registry = registry;
        this.clock = clock;
    }

    synchronized void updateRegistration() {
        var current = settings.snapshot();
        int delay = (int) Math.max(0, Math.min(current.intervalMillis(), Integer.MAX_VALUE));
        if (registered != null) {
            registered.setDelay(delay);
        } else if (current.configured()) {
            Sender sender = new Sender(PLATFORM, null, "", delay, this::deliver);
            registry.addSender(sender, DISPLAY_NAME);
            registered = sender;
        }
    }

    private JSONObject deliver(Map<String, String> headers, Map<String, Object> params) {
        try {
            JSONObject input = new JSONObject(params);
            int type = input.getIntValue("type", PushTargetType.UNKNOWN.getCode());
            if (type == PushTargetType.FRIEND.getCode()) {
                return failure("QQ 官方机器人暂不支持私聊推送");
            }
            if (type != PushTargetType.GROUP.getCode()) {
                return failure("不支持的推送目标类型");
            }
            var current = settings.snapshot();
            if (!current.configured()) {
                return failure("尚未配置 QQ 官方机器人凭据");
            }
            var group = directory.group(current.appId(), input.getLongValue("num")).orElse(null);
            if (group == null) {
                return failure("这个群还没对上号，在群里 @机器人 说一句即可");
            }
            if (group.removed()) {
                return failure("机器人已被移出这个群");
            }
            Target target = new Target(current.appId(), group);
            String content = input.getString("content");
            String replyTo = input.getString("reply_to");
            Outgoing message;
            try {
                message = parseContent(content == null ? "" : content, target);
            } catch (QqBotImageDownloader.TooLarge e) {
                return fail(target, "图片太大", false);
            } catch (InvalidImage e) {
                return fail(target, e.getMessage(), false);
            } catch (IOException e) {
                return fail(target, "图片下载失败，请稍后再试", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fail(target, "图片下载已中断", false);
            }
            if (message.text().isBlank() && message.images().isEmpty()) {
                return fail(target, "消息没有可发送的内容", false);
            }
            return sendGroupMessages(target, message, replyTo);
        } catch (RuntimeException e) {
            log.warn("QQ 官方机器人投递失败");
            return failure("QQ 官方机器人投递失败，请查看日志");
        }
    }

    private Outgoing parseContent(String content, Target target) throws IOException, InterruptedException {
        List<Image> images = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        var placeholders = PLACEHOLDER.matcher(content);
        int start = 0;
        boolean skipped = false;
        while (placeholders.find()) {
            text.append(content, start, placeholders.start());
            start = placeholders.end();
            switch (placeholders.group(1)) {
                case IMAGE_BASE64_PREFIX -> images.add(image(decode(placeholders.group(2))));
                case IMAGE_URL_PREFIX -> images.add(image(downloader.download(placeholders.group(2))));
                default -> skipped = true;
            }
        }
        text.append(content, start, content.length());
        if (skipped) {
            log.info("QQ 官方机器人已省略群 {} 的提及或表情", target.maskedOpenId());
        }
        // 占位符两边的文字也可能拼出提及标签，因此在正文拼接完成后转义。
        String escaped = MENTION.matcher(text).replaceAll(match -> "<".equals(match.group()) ? "＜" : "＠");
        return new Outgoing(escaped, images);
    }

    private static byte[] decode(String base64) throws QqBotImageDownloader.TooLarge {
        if (base64.length() > MAX_BASE64_LENGTH) {
            throw new QqBotImageDownloader.TooLarge();
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new InvalidImage("图片数据无法解码");
        }
    }

    private static Image image(byte[] bytes) throws QqBotImageDownloader.TooLarge {
        if (bytes.length > QqBotImageDownloader.MAX_BYTES) {
            throw new QqBotImageDownloader.TooLarge();
        }
        String format;
        if (startsWith(bytes, PNG_HEADER)) {
            format = "png";
        } else if (startsWith(bytes, JPEG_HEADER)) {
            format = "jpg";
        } else {
            throw new InvalidImage("图片格式不受支持");
        }
        return new Image("image." + format, Base64.getEncoder().encodeToString(bytes));
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** 上传全部图片后，首图与正文合发，其余图片各发一条。 */
    private JSONObject sendGroupMessages(Target target, Outgoing message, String replyTo) {
        String path = QqBotDirectory.groupPath(target.group().openid());
        List<String> uploaded = new ArrayList<>();
        for (Image image : message.images()) {
            String fileInfo;
            try {
                var body = JSONObject.of("file_type", 1, "file_data", image.base64(), "file_name", image.name(),
                        "srv_send_msg", false);
                fileInfo = api.uploadFile(path + "/files", body, target.appId());
            } catch (QqBotApiException e) {
                return apiFailure(target, message.text(), image.base64(), e, false);
            }
            if (fileInfo.isBlank()) {
                return fail(target, "QQ 的图片上传回包缺少文件信息", false);
            }
            uploaded.add(fileInfo);
        }

        String firstId = null;
        boolean passive = replyTo != null && !replyTo.isBlank();
        try {
            for (int i = 0; i < Math.max(1, uploaded.size()); i++) {
                String fileInfo = uploaded.isEmpty() ? null : uploaded.get(i);
                String text = i == 0 ? message.text() : "";
                // 此处的正文已转义，可以在此之后拼入机器人自己生成的标签。
                JSONObject body = fileInfo == null ? JSONObject.of("msg_type", 0, "content", text)
                        : JSONObject.of("msg_type", 7, "content", text.isBlank() ? " " : text,
                                "media", JSONObject.of("file_info", fileInfo));
                if (passive) {
                    body.put("msg_id", replyTo);
                    body.put("msg_seq", nextReplySequence(target.appId(), replyTo));
                }
                String id;
                try {
                    id = responseText(api.request("POST", path + "/messages", body, target.appId()), "id");
                } catch (QqBotApiException e) {
                    JSONObject failed = apiFailure(target, message.text(), fileInfo, e, true);
                    // 首条已送达时，后续图片被拒也不能让核心剥图再发同一段文字。
                    return firstId != null && !e.deliveryUnknown() ? success(firstId) : failed;
                }
                if (id.isBlank()) {
                    return uncertain(target, BAD_RECEIPT);
                }
                if (firstId == null) {
                    firstId = id;
                }
                if (!passive) {
                    remember(target, QqBotDirectory.ProactiveStatus.ENABLED, QqBotDirectory.SwitchSource.SEND_SUCCESS,
                            null);
                }
            }
            return success(firstId);
        } catch (RuntimeException e) {
            return uncertain(target, BAD_RECEIPT);
        }
    }

    private synchronized int nextReplySequence(String appId, String replyTo) {
        Instant now = clock.instant();
        var entries = replySequences.values().iterator();
        while (entries.hasNext() && !now.isBefore(entries.next().expiresAt())) {
            entries.remove();
        }
        ReplyKey key = new ReplyKey(appId, replyTo);
        ReplySequence previous = replySequences.get(key);
        ReplySequence next = previous == null || !now.isBefore(previous.expiresAt())
                ? new ReplySequence(1, now.plus(REPLY_TTL))
                : new ReplySequence(previous.number() + 1, previous.expiresAt());
        replySequences.put(key, next);
        return next.number();
    }

    private JSONObject apiFailure(Target target, String text, String protectedValue, QqBotApiException error,
                                  boolean sending) {
        if (sending && error.deliveryUnknown()) {
            return uncertain(target, error.httpStatus() == 0 ? NO_REPLY : BAD_RECEIPT);
        }
        String official = maskDiagnostic(target, error.getMessage(), protectedValue);
        String trace = maskDiagnostic(target, error.traceId(), protectedValue);
        log.warn("QQ 官方机器人{}失败，群 {}，错误码 {}，原文 {}，trace_id {}",
                sending ? "发消息" : "传图", target.maskedOpenId(), error.code(), official, trace);
        int code = error.code();
        String reason = switch (code) {
            case 40034105 -> "群「" + target.group().name()
                    + "」的群主还没打开机器人的「主动在群聊内发言」：手机 QQ 进群 → 群成员里点机器人 → 右上角设置 → 打开";
            case 40054010 -> "这条里的链接被平台拦了（" + linkHost(text) + "）";
            case 40054002 -> "机器人在这个群被禁言了";
            case 40034101, 40054003 -> "机器人不在这个群里";
            case 40034006 -> "内容被平台判为违规，没有发出";
            case 40054016 -> "机器人当前不在线";
            case 50055001 -> "QQ 侧发送出错，请稍后再试";
            case 22006, 304061, 340069, 304080 -> "消息格式被平台拒绝（错误码 " + code + "）";
            case 850018 -> "机器人在这个群被禁言，图片没有传上去";
            case 850019 -> "图片格式不受支持";
            case 850031 -> "图片太大";
            case 40093002 -> "今天发文件的额度用完了";
            case 40093001, 850027 -> "图片上传失败，请稍后再试";
            case 0 -> error.httpStatus() == 0 ? official : "QQ 返回错误 " + error.httpStatus() + "：" + official;
            default -> "QQ 返回错误 " + code + "：" + official;
        };
        return fail(target, maskDiagnostic(target, reason, null), code == 40034105);
    }

    private String maskDiagnostic(Target target, String value, String protectedValue) {
        String masked = protectedValue == null ? value : value.replace(protectedValue, "已隐藏");
        return directory.maskKnown(target.appId(), masked).replace('\r', ' ').replace('\n', ' ');
    }

    private static String linkHost(String text) {
        var links = LINK.matcher(text);
        while (links.find()) {
            try {
                String host = URI.create(links.group()).getHost();
                if (host != null) {
                    return host;
                }
            } catch (IllegalArgumentException e) {
                // 继续查找可以解析的链接。
            }
        }
        return "域名未知";
    }

    private JSONObject fail(Target target, String reason, boolean proactiveDisabled) {
        remember(target, proactiveDisabled ? QqBotDirectory.ProactiveStatus.DISABLED : null,
                proactiveDisabled ? QqBotDirectory.SwitchSource.SEND_ERROR : null, reason);
        return failure(reason);
    }

    private void remember(Target target, QqBotDirectory.ProactiveStatus status, QqBotDirectory.SwitchSource source,
                          String reason) {
        try {
            Instant now = clock.instant();
            var proactive = status == null ? null : new QqBotDirectory.Proactive(status, source, now);
            var failure = reason == null ? null : new QqBotDirectory.Failure(reason, now);
            directory.recordSendResult(target.appId(), target.group().number(), proactive, failure);
        } catch (RuntimeException e) {
            log.warn("无法记录 QQ 官方机器人群 {} 的投递结果", target.maskedOpenId());
        }
    }

    private static JSONObject uncertain(Target target, String reason) {
        log.warn("QQ 官方机器人群 {} 送达不明，{}", target.maskedOpenId(), reason);
        return failure(reason).fluentPut(Sender.LocalDelivery.DELIVERY_UNKNOWN, true);
    }

    private static String responseText(Object response, String key) {
        return response instanceof JSONObject json && json.get(key) instanceof String value ? value : "";
    }

    private static JSONObject success(String id) {
        return JSONObject.of("code", 0, "id", id);
    }

    private static JSONObject failure(String reason) {
        return JSONObject.of("code", -1, "message", reason);
    }

    private record Target(String appId, QqBotDirectory.GroupEntry group) {
        String maskedOpenId() {
            return QqBotDirectory.maskOpenId(group.openid());
        }
    }

    private record Image(String name, String base64) {}
    private record Outgoing(String text, List<Image> images) {}
    private record ReplyKey(String appId, String messageId) {}
    private record ReplySequence(int number, Instant expiresAt) {}

    private static final class InvalidImage extends RuntimeException {
        InvalidImage(String message) {
            super(message);
        }
    }
}
