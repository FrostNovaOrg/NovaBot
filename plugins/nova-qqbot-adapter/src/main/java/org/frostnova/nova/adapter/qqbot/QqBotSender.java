package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.adapter.qqbot.QqBotDirectory.ProactiveDenial;
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
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

import static org.frostnova.nova.adapter.qqbot.QqBotMessageText.AT_ALL_TAG;
import static org.frostnova.nova.adapter.qqbot.QqBotMessageText.escapeMentions;
import static org.frostnova.nova.adapter.qqbot.QqBotPluginAutoConfiguration.DISPLAY_NAME;
import static org.frostnova.nova.adapter.qqbot.QqBotPluginAutoConfiguration.PLATFORM;
import static org.frostnova.nova.core.model.MessagePlaceholders.AT_ALL;
import static org.frostnova.nova.core.model.MessagePlaceholders.IMAGE_BASE64_PREFIX;
import static org.frostnova.nova.core.model.MessagePlaceholders.IMAGE_URL_PREFIX;

@Slf4j
@NovaComponent
class QqBotSender {
    private static final Pattern PLACEHOLDER = Pattern.compile("(" + Pattern.quote(IMAGE_BASE64_PREFIX) + "|"
            + Pattern.quote(IMAGE_URL_PREFIX) + "|\\{at=|\\{face=)(.*?)}", Pattern.DOTALL);
    private static final Pattern MEMBER_NUMBER = Pattern.compile("[0-9]+");
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>＜＞\"'，。！？；、（）【】「」《》()]+",
            Pattern.CASE_INSENSITIVE);
    private static final byte[] PNG_HEADER = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final byte[] JPEG_HEADER = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final int DIRECT_UPLOAD_BYTES = 5 * 1024 * 1024;
    private static final int MAX_BASE64_LENGTH = 4 * ((QqBotImageDownloader.MAX_BYTES + 2) / 3);
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    static final String SWITCH_PATH = "手机 QQ 进群 → 群成员里点机器人 → 右上角设置 → 打开";
    private static final String NO_REPLY = "已发出但没等到 QQ 的回包，未重发";
    private static final String BAD_RECEIPT = "QQ 的发送回包无法确认送达，未重发";
    private static final String CLAIM_REPLY_FAILED = "官方机器人认领回复发送失败";

    private final QqBotSettings settings;
    private final QqBotDirectory directory;
    private final QqBotApiClient api;
    private final QqBotImageDownloader downloader;
    private final QqBotMultipartUpload multipartUpload;
    private final NovaSenderService registry;
    private final QqBotSendWindow window;
    private final QqBotReplyWindows replies;
    private final ExecutorService eventReplies = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "qqbot-event-reply");
        worker.setDaemon(true);
        return worker;
    });
    private Sender registered;

    QqBotSender(QqBotSettings settings, QqBotDirectory directory, QqBotApiClient api,
                QqBotImageDownloader downloader, NovaSenderService registry, QqBotSendWindow window,
                QqBotReplyWindows replies, QqBotMultipartUpload multipartUpload) {
        this.settings = settings;
        this.directory = directory;
        this.api = api;
        this.downloader = downloader;
        this.multipartUpload = multipartUpload;
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
            // 只收群消息：私聊在投递时就会被拒，先声明出来，程序接口发送前就能说清
            registry.addSender(sender, DISPLAY_NAME, java.util.Set.of(PushTargetType.GROUP));
            registered = sender;
        }
    }

    /** 进群和认领回复可能等待发送额度，交给网关之外的同一个工作线程。 */
    void submitEventReply(Runnable task) {
        try {
            eventReplies.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("QQ 官方机器人事件回复队列不可用");
        }
    }

    void submitClaimReply(QqBotSettings.Credentials credentials, long groupNum, String messageId,
                          QqBotReplyWindows.Window reply, String content) {
        submitEventReply(() -> {
            try {
                if (settings.snapshot().credentials() != credentials) return;
                var group = directory.group(credentials.appId(), groupNum).orElse(null);
                if (group == null || group.removed()) return;
                var target = new Target(credentials.appId(), group, credentials);
                // 昵称按纯文字发送，避免把群成员填写的内容当成消息占位符。
                var text = new QqBotMessageText();
                text.append(content, 0, content.length());
                var message = new Outgoing(text, List.of(), false);
                if (sendReply(target, message, messageId, reply).getIntValue("code") != 0) {
                    log.warn(CLAIM_REPLY_FAILED);
                }
            } catch (RuntimeException e) {
                log.warn(CLAIM_REPLY_FAILED);
            }
        });
    }

    @PreDestroy
    void stopEventReplies() {
        eventReplies.shutdownNow();
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
            Target target = new Target(current.appId(), group, null);
            String content = input.getString("content");
            String replyTo = input.getString("reply_to");
            Outgoing message;
            try {
                message = parseContent(content == null ? "" : content, target);
            } catch (QqBotImageDownloader.TooLarge e) {
                return fail(target, "图片超过 20 MB，没有发出", false);
            } catch (InvalidImage e) {
                return fail(target, e.getMessage(), false);
            } catch (QqBotImageDownloader.Blocked e) {
                return fail(target, QqBotImageDownloader.BLOCKED, false);
            } catch (IOException e) {
                return fail(target, "图片下载失败，请稍后再试", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fail(target, "图片下载已中断", false);
            }
            if (message.text().isBlank() && message.images().isEmpty() && !message.omittedMember()) {
                return fail(target, "消息没有可发送的内容", false);
            }
            if (replyTo == null || replyTo.isBlank()) return sendGroupMessages(target, message, null, null);
            Instant now = window.now();
            Long created = input.getLong("create_time");
            var reply = replies.get(target.appId(), replyTo, created == null ? now : Instant.ofEpochMilli(created), now);
            return sendReply(target, message, replyTo, reply);
        } catch (RuntimeException e) {
            log.warn("QQ 官方机器人投递失败");
            return failure("QQ 官方机器人投递失败，请查看日志");
        }
    }

    private JSONObject sendReply(Target target, Outgoing message, String replyTo, QqBotReplyWindows.Window reply) {
        // 同一原消息的多个投递共用 msg_seq，直到本次送达情况明确才让出序号。
        synchronized (reply) {
            return sendGroupMessages(target, message, replyTo, reply);
        }
    }

    private Outgoing parseContent(String content, Target target) throws IOException, InterruptedException {
        List<Image> images = new ArrayList<>();
        var text = new QqBotMessageText();
        var placeholders = PLACEHOLDER.matcher(content);
        int start = 0;
        boolean skipped = false;
        boolean omittedMember = false;
        while (placeholders.find()) {
            text.append(content, start, placeholders.start());
            start = placeholders.end();
            switch (placeholders.group(1)) {
                case IMAGE_BASE64_PREFIX -> images.add(image(decode(placeholders.group(2))));
                case IMAGE_URL_PREFIX -> images.add(image(downloader.download(placeholders.group(2))));
                case "{at=" -> {
                    String number = placeholders.group(2);
                    if (AT_ALL.equals(placeholders.group())) {
                        text.mentionEveryone();
                    } else if (MEMBER_NUMBER.matcher(number).matches()) {
                        Optional<String> member = number.length() == 16
                                ? directory.memberOpenId(target.appId(), Long.parseLong(number)) : Optional.empty();
                        member.ifPresent(text::mentionMember);
                        omittedMember |= member.isEmpty();
                    } else {
                        skipped = true;
                    }
                }
                default -> skipped = true;
            }
        }
        text.append(content, start, content.length());
        if (omittedMember) {
            log.info("QQ 官方机器人已省略未登记的成员提及");
        } else if (skipped) {
            log.info("QQ 官方机器人已省略群 {} 的提及或表情", target.maskedOpenId());
        }
        return new Outgoing(text, images, omittedMember);
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
        return new Image("image." + format, bytes);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** 提及随正文单独发送，普通图文仍将正文放在首图。 */
    private JSONObject sendGroupMessages(Target target, Outgoing message, String replyTo, QqBotReplyWindows.Window reply) {
        String path = QqBotDirectory.groupPath(target.group().openid());
        var text = message.text();
        boolean hasImages = !message.images().isEmpty();
        if (hasImages && !text.hasMentions()) {
            return sendImages(target, path, message.images(), text.plain(false), replyTo, reply);
        }

        JSONObject body = text.hasMembers() ? markdownBody(text.markdown(true)) : textBody(text.plain(true));
        var notice = sendContent(target, path + "/messages", body, text, replyTo, reply, hasImages);
        if (!hasImages) return notice;
        if (notice.getIntValue("code") != 0) {
            return notice.fluentPut(Sender.LocalDelivery.SKIP_IMAGE_FALLBACK, true);
        }
        String noticeId = notice.getString("id");
        var images = sendImages(target, path, message.images(), "", replyTo, reply);
        if (images.getIntValue("code") != 0) {
            // 正文已单独投递过，图片失败时无需让核心剥图再发一次。
            return imageFailure(noticeId, images.fluentPut(Sender.LocalDelivery.SKIP_IMAGE_FALLBACK, true));
        }
        if (noticeId != null) images.put("id", noticeId);
        return images;
    }

    private JSONObject sendImages(Target target, String path, List<Image> images, String caption, String replyTo,
                                  QqBotReplyWindows.Window reply) {
        List<String> uploaded = new ArrayList<>();
        for (Image image : images) {
            String fileInfo;
            boolean multipart = image.bytes().length > DIRECT_UPLOAD_BYTES;
            String base64 = null;
            try {
                if (multipart) {
                    fileInfo = multipartUpload.upload(path, target.appId(), image.name(), image.bytes());
                } else {
                    base64 = Base64.getEncoder().encodeToString(image.bytes());
                    var body = JSONObject.of("file_type", 1, "file_data", base64, "file_name", image.name(),
                            "srv_send_msg", false);
                    fileInfo = window.retryWhenLimited(() -> api.uploadFile(path + "/files", body, target.appId()));
                }
            } catch (QqBotApiException e) {
                return apiFailure(target, caption, base64, e, null, false, multipart);
            }
            if (fileInfo.isBlank()) {
                return fail(target, "QQ 的图片上传回包缺少文件信息", false);
            }
            uploaded.add(fileInfo);
        }

        String firstId = null;
        try {
            for (int i = 0; i < uploaded.size(); i++) {
                String fileInfo = uploaded.get(i);
                String text = i == 0 ? caption : "";
                JSONObject body = JSONObject.of("msg_type", 7, "media", JSONObject.of("file_info", fileInfo));
                if (!text.isBlank()) body.put("content", text);
                JSONObject result = sendContent(target, path + "/messages", body, null, replyTo, reply, false);
                if (result.getIntValue("code") != 0) {
                    return imageFailure(firstId, result);
                }
                if (firstId == null) firstId = result.getString("id");
            }
            return success(firstId);
        } catch (RuntimeException e) {
            return uncertain(target, BAD_RECEIPT);
        }
    }

    private static JSONObject imageFailure(String firstId, JSONObject failure) {
        if (firstId == null || failure.getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN)) return failure;
        return success(firstId).fluentPut(Sender.LocalDelivery.IMAGE_DEGRADED, true)
                .fluentPut("message", failure.getString("message"));
    }

    private JSONObject sendContent(Target target, String path, JSONObject body, QqBotMessageText source, String replyTo,
                                   QqBotReplyWindows.Window reply, boolean imagesFollow) {
        List<JSONObject> parts = new ArrayList<>(List.of(body));
        JSONObject media = body.getJSONObject("media");
        String fileInfo = media == null ? null : media.getString("file_info");
        String firstId = null;
        boolean atAllRejected = false;
        for (int i = 0; i < parts.size();) {
            JSONObject part = parts.get(i);
            JSONObject markdown = part.getJSONObject("markdown");
            String text = markdown == null ? part.getString("content") : markdown.getString("content");
            if (text == null) text = "";
            if (atAllRejected && text.contains(AT_ALL_TAG)) {
                text = escapeMentions(text.replace(AT_ALL_TAG, ""));
                if (text.isBlank() && (imagesFollow || firstId != null || i + 1 < parts.size())) {
                    i++;
                    continue;
                }
                part.put("content", text);
            }
            try {
                String id = window.retryWhenLimited(() -> sendPart(target, path, part, replyTo, reply),
                        source != null && source.hasMembers());
                if (id.isBlank()) return uncertain(target, BAD_RECEIPT);
                if (firstId == null) firstId = id;
                i++;
            } catch (QqBotApiException e) {
                if (markdown != null && e.markdownPermissionDenied()) {
                    log.warn("平台没给这只机器人发 markdown 的权限，这条改为不 @ 人");
                    String plain = source.plain(false);
                    if (plain.isBlank() && imagesFollow) return success(null);
                    parts.set(i, textBody(plain));
                    continue;
                }
                if (e.atAllLimitReached() && text.contains(AT_ALL_TAG)) {
                    rememberAtAllRejection(target);
                    atAllRejected = true;
                    if (markdown != null) {
                        markdown.put("content", source.markdown(false));
                    }
                    continue;
                }
                if (e.messageTooLong() && parts.size() < 4) {
                    if (markdown != null) {
                        log.warn("这条太长，改为不 @ 人分段发");
                        text = source.plain(!atAllRejected);
                        if (text.isBlank() && imagesFollow) return success(null);
                        parts.set(i, textBody(text));
                    }
                    int cut = splitAt(text);
                    if (cut > 0) {
                        parts.get(i).put("content", text.substring(0, cut));
                        parts.add(i + 1, textBody(text.substring(cut)));
                        continue;
                    }
                    if (markdown != null) continue;
                }
                boolean fallback = reply != null && !part.containsKey("msg_id");
                JSONObject failed = apiFailure(target, text, fileInfo, e, part, fallback, false);
                if (failed == null) {
                    log.warn("QQ 不收不带文字的图片消息（错误码 {}），这条改回带一个空格发", e.code());
                    part.put("content", " ");
                    continue;
                }
                // 已确认的首段编号也让核心知道不能剥图重发整篇正文。
                if (firstId != null) failed.put("id", firstId);
                return failed;
            } catch (RuntimeException e) {
                return uncertain(target, BAD_RECEIPT);
            }
        }
        if (parts.size() > 1) log.info("这条太长被拒，已拆成 {} 段重发", parts.size());
        return success(firstId);
    }

    private static JSONObject textBody(String text) {
        return JSONObject.of("msg_type", 0, "content", text);
    }

    private static JSONObject markdownBody(String text) {
        return JSONObject.of("msg_type", 2, "content", "", "markdown", JSONObject.of("content", text));
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
        cut = text.offsetByCodePoints(0, text.codePointCount(0, text.length()) / 2);
        int tag = text.lastIndexOf(AT_ALL_TAG, cut);
        if (tag >= 0 && cut > tag && cut < tag + AT_ALL_TAG.length()) {
            if (tag > 0) return tag;
            return AT_ALL_TAG.length() < text.length() ? AT_ALL_TAG.length() : -1;
        }
        return cut;
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
        if (target.claimCredentials() != null && settings.snapshot().credentials() != target.claimCredentials()) {
            throw new QqBotApiException(0, CLAIM_REPLY_FAILED, "", 0);
        }
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
            remember(target, QqBotDirectory.ProactiveStatus.ENABLED, QqBotDirectory.SwitchSource.SEND_SUCCESS, null, null);
        }
        return id;
    }

    /** 无文字图片遇到可换写法重试的拒绝时，返回 null 让发送循环补发。 */
    private JSONObject apiFailure(Target target, String text, String protectedValue, QqBotApiException error,
                                  JSONObject messageBody, boolean replyFallback, boolean multipart) {
        boolean sending = messageBody != null;
        if (sending && error.duplicateMessage()) {
            return uncertain(target, "QQ 判为重复消息，按已发出处理，未重发");
        }
        if (sending && error.deliveryUnknown()) {
            return uncertain(target, error.httpStatus() == 0 ? NO_REPLY : BAD_RECEIPT);
        }
        if (target.claimCredentials() != null) {
            // 平台报错可能引用原群消息，认领回复只记录通用原因。
            return fail(target, CLAIM_REPLY_FAILED, error.code() == 40034105);
        }
        String official = maskDiagnostic(target, error.getMessage(), protectedValue);
        int code = error.code();
        ProactiveDenial denial = code == 40034105
                ? (replyFallback ? ProactiveDenial.REPLY_FALLBACK : ProactiveDenial.DIRECT) : null;
        String reason = switch (code) {
            case 40034105 -> denial.reason(target.group().name());
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
            default -> {
                if (multipart) yield switch (code) {
                    case 850026 -> "平台下载源文件失败，请稍后再试";
                    case 10000 -> "平台不支持这项上传操作";
                    default -> "图片分片上传失败（错误码 " + code + "）";
                };
                int status = error.httpStatus();
                if (sending && messageBody.getIntValue("msg_type") == 7 && !messageBody.containsKey("content")
                        && code > 0 && code != 50001 && !error.atAllLimitReached()
                        && status > 0 && status != 408 && status < 500) {
                    yield null;
                }
                yield sending && messageBody.containsKey("markdown") && error.markdownFormatRejected()
                        ? "消息格式被平台拒绝（错误码 " + code + "）" : "QQ 返回错误 " + code + "：" + official;
            }
        };
        if (reason == null) return null;
        String trace = maskDiagnostic(target, error.traceId(), protectedValue);
        log.warn("QQ 官方机器人{}失败，群 {}，错误码 {}，原文 {}，trace_id {}",
                sending ? "发消息" : "传图", target.maskedOpenId(), code, official, trace);
        JSONObject result = fail(target, maskDiagnostic(target, reason, null), denial != null, denial);
        if (!multipart && error.preventsTextDelivery()) result.put(Sender.LocalDelivery.SKIP_IMAGE_FALLBACK, true);
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
        return fail(target, reason, proactiveDisabled, null);
    }

    private JSONObject fail(Target target, String reason, boolean proactiveDisabled, ProactiveDenial denial) {
        remember(target, proactiveDisabled ? QqBotDirectory.ProactiveStatus.DISABLED : null,
                proactiveDisabled ? QqBotDirectory.SwitchSource.SEND_ERROR : null, reason, denial);
        return failure(reason);
    }

    private void rememberAtAllRejection(Target target) {
        log.warn("本群今天已经 @全体 过一次，这条改为不 @");
        try {
            directory.recordAtAllRejection(target.appId(), target.group().number());
        } catch (RuntimeException e) {
            log.warn("无法记录 QQ 官方机器人群 {} 的 @全体 用量", target.maskedOpenId());
        }
    }

    private void remember(Target target, QqBotDirectory.ProactiveStatus status, QqBotDirectory.SwitchSource source,
                          String reason, ProactiveDenial denial) {
        try {
            Instant now = window.now();
            var proactive = status == null ? null : new QqBotDirectory.Proactive(status, source, now);
            var failure = reason == null ? null : new QqBotDirectory.Failure(reason, now, denial);
            directory.recordSendResult(target.appId(), target.group().number(), proactive, failure);
        } catch (RuntimeException e) {
            if (target.claimCredentials() == null) {
                log.warn("无法记录 QQ 官方机器人群 {} 的投递结果", target.maskedOpenId());
            }
        }
    }

    private static JSONObject uncertain(Target target, String reason) {
        if (target.claimCredentials() == null) {
            log.warn("QQ 官方机器人群 {} 送达不明，{}", target.maskedOpenId(), reason);
        }
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

    private record Target(String appId, QqBotDirectory.GroupEntry group, QqBotSettings.Credentials claimCredentials) {
        String maskedOpenId() {
            return QqBotDirectory.maskOpenId(group.openid());
        }
    }

    private record Image(String name, byte[] bytes) {}
    private record Outgoing(QqBotMessageText text, List<Image> images, boolean omittedMember) {}

    private static final class InvalidImage extends RuntimeException {
        InvalidImage(String message) {
            super(message);
        }
    }
}
