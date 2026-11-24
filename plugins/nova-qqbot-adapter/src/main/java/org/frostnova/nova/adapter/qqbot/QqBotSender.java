package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.NovaSenderService;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    static final String SWITCH_PATH = "手机 QQ 进群 → 群成员里点机器人 → 右上角设置 → 打开";
    private static final String NO_REPLY = "已发出但没等到 QQ 的回包，未重发";
    private static final String BAD_RECEIPT = "QQ 的发送回包无法确认送达，未重发";

    private final QqBotSettings settings;
    private final QqBotDirectory directory;
    private final QqBotApiClient api;
    private final QqBotImageDownloader downloader;
    private final NovaSenderService registry;
    private final QqBotSendWindow window;
    private final QqBotReplyWindows replies;
    private Sender registered;

    QqBotSender(QqBotSettings settings, QqBotDirectory directory, QqBotApiClient api,
                QqBotImageDownloader downloader, NovaSenderService registry, QqBotSendWindow window,
                QqBotReplyWindows replies) {
        this.settings = settings;
        this.directory = directory;
        this.api = api;
        this.downloader = downloader;
        this.registry = registry;
        this.window = window;
        this.replies = replies;
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
                return targetFailure("QQ 官方机器人暂不支持私聊推送");
            }
            if (type != PushTargetType.GROUP.getCode()) {
                return targetFailure("不支持的推送目标类型");
            }
            var current = settings.snapshot();
            if (!current.configured()) {
                return targetFailure("尚未配置 QQ 官方机器人凭据");
            }
            var group = directory.group(current.appId(), input.getLongValue("num")).orElse(null);
            if (group == null) {
                return targetFailure("这个群还没对上号，在群里 @机器人 说一句即可");
            }
            if (group.removed()) {
                return targetFailure("机器人已被移出这个群");
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
            if (replyTo == null || replyTo.isBlank()) return sendGroupMessages(target, message, null, null);
            Instant now = window.now();
            Long created = input.getLong("create_time");
            var reply = replies.get(target.appId(), replyTo, created == null ? now : Instant.ofEpochMilli(created), now);
            // 同一原消息的多个投递共用 msg_seq，直到本次送达情况明确才让出序号。
            synchronized (reply) {
                return sendGroupMessages(target, message, replyTo, reply);
            }
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
    private JSONObject sendGroupMessages(Target target, Outgoing message, String replyTo, QqBotReplyWindows.Window reply) {
        String path = QqBotDirectory.groupPath(target.group().openid());
        List<String> uploaded = new ArrayList<>();
        for (Image image : message.images()) {
            String fileInfo;
            try {
                var body = JSONObject.of("file_type", 1, "file_data", image.base64(), "file_name", image.name(),
                        "srv_send_msg", false);
                fileInfo = window.retryWhenLimited(() -> api.uploadFile(path + "/files", body, target.appId()));
            } catch (QqBotApiException e) {
                return apiFailure(target, message.text(), image.base64(), e, false, false);
            }
            if (fileInfo.isBlank()) {
                return fail(target, "QQ 的图片上传回包缺少文件信息", false);
            }
            uploaded.add(fileInfo);
        }

        String firstId = null;
        try {
            for (int i = 0; i < Math.max(1, uploaded.size()); i++) {
                String fileInfo = uploaded.isEmpty() ? null : uploaded.get(i);
                String text = i == 0 ? message.text() : "";
                JSONObject body = fileInfo == null ? textBody(text)
                        : JSONObject.of("msg_type", 7, "content", text.isBlank() ? " " : text,
                                "media", JSONObject.of("file_info", fileInfo));
                JSONObject result = sendContent(target, path + "/messages", body, fileInfo, replyTo, reply);
                if (result.getIntValue("code") != 0) {
                    if (firstId != null && !result.getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN)) {
                        return success(firstId).fluentPut(Sender.LocalDelivery.IMAGE_DEGRADED, true)
                                .fluentPut("message", result.getString("message"));
                    }
                    return result;
                }
                if (firstId == null) firstId = result.getString("id");
            }
            return success(firstId);
        } catch (RuntimeException e) {
            return uncertain(target, BAD_RECEIPT);
        }
    }

    private JSONObject sendContent(Target target, String path, JSONObject body, String fileInfo, String replyTo,
                                   QqBotReplyWindows.Window reply) {
        List<JSONObject> parts = new ArrayList<>(List.of(body));
        String firstId = null;
        for (int i = 0; i < parts.size();) {
            JSONObject part = parts.get(i);
            String text = part.getString("content");
            try {
                String id = window.retryWhenLimited(() -> sendPart(target, path, part, replyTo, reply));
                if (id.isBlank()) return uncertain(target, BAD_RECEIPT);
                if (firstId == null) firstId = id;
                i++;
            } catch (QqBotApiException e) {
                if (e.code() == 40054007 && parts.size() < 4) {
                    int cut = splitAt(text);
                    if (cut > 0) {
                        part.put("content", text.substring(0, cut));
                        parts.add(i + 1, textBody(text.substring(cut)));
                        continue;
                    }
                }
                boolean fallback = reply != null && !part.containsKey("msg_id");
                JSONObject failed = apiFailure(target, text, fileInfo, e, true, fallback);
                // 已确认的首段编号也让核心知道不能剥图重发整篇正文。
                if (firstId != null) failed.put("id", firstId);
                return failed;
            }
        }
        if (parts.size() > 1) log.info("这条太长被拒，已拆成 {} 段重发", parts.size());
        return success(firstId);
    }

    private static JSONObject textBody(String text) {
        return JSONObject.of("msg_type", 0, "content", text);
    }

    private static int splitAt(String text) {
        int midpoint = text.length() / 2;
        var breaks = LINE_BREAK.matcher(text);
        int cut = -1;
        while (breaks.find() && breaks.end() < text.length()) {
            if (cut < 0 || Math.abs(breaks.end() - midpoint) < Math.abs(cut - midpoint)) cut = breaks.end();
        }
        if (cut > 0) return cut;
        // 单行也能拆开，保留完整的 Unicode 码点。
        return text.offsetByCodePoints(0, text.codePointCount(0, text.length()) / 2);
    }

    private String sendPart(Target target, String path, JSONObject body, String replyTo, QqBotReplyWindows.Window reply) {
        try {
            return post(target, path, body, replyTo, reply);
        } catch (QqBotApiException e) {
            if (!body.containsKey("msg_id") || !e.messageReplyRejected()) throw e;
            reply.exhausted();
            return post(target, path, body, replyTo, reply);
        }
    }

    private String post(Target target, String path, JSONObject body, String replyTo, QqBotReplyWindows.Window reply) {
        window.acquire(target.appId(), target.group().number());
        int sequence = reply == null ? 0 : reply.nextSequence(window.now());
        if (sequence > 0) {
            body.put("msg_id", replyTo);
            body.put("msg_seq", sequence);
        } else {
            body.remove("msg_id");
            body.remove("msg_seq");
        }
        String id;
        try {
            id = responseText(api.request("POST", path, body, target.appId()), "id");
        } catch (QqBotApiException e) {
            if (sequence > 0 && (e.deliveryUnknown() || e.duplicateMessage())) reply.delivered();
            throw e;
        }
        if (sequence > 0) {
            reply.delivered();
        } else if (!id.isBlank()) {
            remember(target, QqBotDirectory.ProactiveStatus.ENABLED, QqBotDirectory.SwitchSource.SEND_SUCCESS, null);
        }
        return id;
    }

    private JSONObject apiFailure(Target target, String text, String protectedValue, QqBotApiException error,
                                  boolean sending, boolean replyFallback) {
        if (sending && error.duplicateMessage()) {
            return uncertain(target, "QQ 判为重复消息，按已发出处理，未重发");
        }
        if (sending && error.deliveryUnknown()) {
            return uncertain(target, error.httpStatus() == 0 ? NO_REPLY : BAD_RECEIPT);
        }
        String official = maskDiagnostic(target, error.getMessage(), protectedValue);
        String trace = maskDiagnostic(target, error.traceId(), protectedValue);
        log.warn("QQ 官方机器人{}失败，群 {}，错误码 {}，原文 {}，trace_id {}",
                sending ? "发消息" : "传图", target.maskedOpenId(), error.code(), official, trace);
        int code = error.code();
        String reason = switch (code) {
            case 40034105 -> (replyFallback ? "回复次数用完或超过 60 分钟，改为主动发言时被拒：" : "")
                    + "群「" + target.group().name()
                    + "」的群主还没打开机器人的「主动在群聊内发言」：" + SWITCH_PATH;
            case 40054007 -> "消息太长，未能完整发出";
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
        JSONObject result = fail(target, maskDiagnostic(target, reason, null), code == 40034105);
        if (error.preventsTextDelivery()) result.put(Sender.LocalDelivery.SKIP_IMAGE_FALLBACK, true);
        return result;
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
            Instant now = window.now();
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

    private static JSONObject targetFailure(String reason) {
        return failure(reason).fluentPut(Sender.LocalDelivery.SKIP_IMAGE_FALLBACK, true);
    }

    private record Target(String appId, QqBotDirectory.GroupEntry group) {
        String maskedOpenId() {
            return QqBotDirectory.maskOpenId(group.openid());
        }
    }

    private record Image(String name, String base64) {}
    private record Outgoing(String text, List<Image> images) {}

    private static final class InvalidImage extends RuntimeException {
        InvalidImage(String message) {
            super(message);
        }
    }
}
