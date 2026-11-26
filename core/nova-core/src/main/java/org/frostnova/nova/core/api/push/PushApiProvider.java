package org.frostnova.nova.core.api.push;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.api.NovaApiCaller;
import org.frostnova.nova.core.api.NovaApiErrors;
import org.frostnova.nova.core.api.NovaApiException;
import org.frostnova.nova.core.api.NovaApiPage;
import org.frostnova.nova.core.api.NovaApiProvider;
import org.frostnova.nova.core.api.NovaApiRequest;
import org.frostnova.nova.core.api.NovaApiRoute;
import org.frostnova.nova.core.api.NovaApiScope;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.secret.SecretLogMask;
import org.frostnova.nova.core.sender.DeliveryReceipt;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 推送接口组 {@code /nova/api/v1/push/}：外部程序凭访问令牌请 NovaBot 代发一条消息
 *
 * <h2>为什么放在核心</h2>
 * 发送去向（各家机器人通道、外发）都登记在核心的 {@link NovaSenderService} 里，真正交给去向的也是核心的
 * {@link NovaMessageSender}。放在某一家适配器里，就只发得了那一家；放在这里，登记了的去向都发得了，
 * 一个去向也没装时 {@code platforms} 回空表。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>范围 {@value #SEND} 仅管理员：能往任意群、任意好友发消息，是整个实例层面的事。</li>
 *   <li>与对外的旧推送接口一样，不看全局推送开关与静音时段——这是调用方明确要发的一条；
 *       去向被停用时不发、回错。</li>
 *   <li>结果按这一次的实情回：发出了回 {@code SENT}；交出去了没等到明确结果回 {@code UNKNOWN}，
 *       这一边也不重发；没发出回错误。</li>
 *   <li>同一把令牌任何一分钟里最多发 {@value PushSendQuota#PER_MINUTE} 条（{@link PushSendQuota}）。</li>
 *   <li>每一次发送都在时间线上记一条：哪个账号的哪把令牌、发往哪个去向的哪类目标。
 *       号码只留头尾两位，正文只记字数；令牌只记名字与编号。</li>
 * </ul>
 * 回包里的说明都是写死的话：不带去向回包的原话、内部异常、口令，也不复述请求里的原文。
 */
@Slf4j
@Component
public class PushApiProvider implements NovaApiProvider {
    /** 范围标识 */
    static final String SEND = "push:send";

    static final String NO_PLATFORM = "没有这个去向（platform）；可先调 GET /nova/api/v1/push/platforms 查看能用的去向";

    /** 时间线里原因最多留这么多字 */
    private static final int REASON_IN_RECORD = 120;

    /** 号码：正整数，不带正负号与前导零 */
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,18}");

    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");

    private static final String NEXT = "{next}";

    private final Supplier<NovaSenderService> senders;

    private final Supplier<NovaMessageSender> messages;

    private final Supplier<TimelineWriter> timeline;

    private final PushSendQuota quota;

    /**
     * 依赖都在请求到来时现取：这一组要在接口面装配时就登记，那时发送器还不必建好
     */
    @Autowired
    public PushApiProvider(ObjectProvider<NovaSenderService> senders, ObjectProvider<NovaMessageSender> messages,
                           ObjectProvider<TimelineWriter> timeline) {
        this(senders::getObject, messages::getObject, timeline::getObject, new PushSendQuota());
    }

    PushApiProvider(Supplier<NovaSenderService> senders, Supplier<NovaMessageSender> messages,
                    Supplier<TimelineWriter> timeline, PushSendQuota quota) {
        this.senders = senders;
        this.messages = messages;
        this.timeline = timeline;
        this.quota = quota;
    }

    @Override
    public String segment() {
        return "push";
    }

    @Override
    public String name() {
        return "推送";
    }

    @Override
    public String description() {
        return "外部程序请 NovaBot 经已登记的去向往群或好友代发一条消息用";
    }

    @Override
    public List<NovaApiScope> scopes() {
        return List.of(NovaApiScope.adminOnly(SEND, "发送推送消息",
                "经任何一个已登记的去向往任意群或好友发消息，不受全局推送开关与静音时段管"));
    }

    @Override
    public List<NovaApiRoute> routes() {
        return List.of(NovaApiRoute.get("platforms", SEND, this::platforms),
                NovaApiRoute.post("messages", SEND, this::send));
    }

    private NovaApiPage platforms(NovaApiRequest request) {
        NovaSenderService registry = senders.get();
        NovaMessageSender sender = messages.get();
        Instant now = Instant.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (String id : registry.getSenderNames().stream().sorted().toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("name", registry.displayName(id));
            item.put("targetTypes", registry.targetTypes(id).stream().map(Enum::name).toList());
            item.put("enabled", sender.platformBlock(id, now) == null);
            items.add(item);
        }
        return NovaApiPage.last(items);
    }

    private Object send(NovaApiRequest request) {
        NovaApiCaller caller = request.caller();
        // 发送之前就把要用的都取到手：发出去之后再出错，回包就成了 500，调用方会以为没发出而重发
        NovaSenderService registry = senders.get();
        NovaMessageSender sender = messages.get();
        TimelineWriter writer = timeline.get();

        PushSendQuota.Verdict verdict = quota.take(caller.tokenId());
        if (!verdict.allowed()) {
            if (verdict.firstRefusal()) {
                log.warn("程序接口发送: 令牌 {}（编号 {}，账号 {}）一分钟内超过 {} 条, 之后 {} 秒内的发送请求回 429",
                        caller.tokenName(), caller.tokenId(), caller.accountId(), PushSendQuota.PER_MINUTE,
                        verdict.retryAfterSeconds());
                record(writer, TimelineEvent.of(TimelineEventType.PUSH_FAILED, TimelineEvent.Level.WARN)
                        .text("程序接口发送被限量：令牌「" + caller.tokenName() + "」（" + who(caller) + "）一分钟内超过 "
                                + PushSendQuota.PER_MINUTE + " 条，之后 " + verdict.retryAfterSeconds() + " 秒内的发送请求都没有受理")
                        .detail("source", "api")
                        .detail("account", caller.accountId())
                        .detail("token", caller.tokenName())
                        .detail("tokenId", caller.tokenId())
                        .detail("outcome", "LIMITED")
                        .detail("retryAfter", String.valueOf(verdict.retryAfterSeconds())));
            }
            throw NovaApiException.tooManyRequests("这把令牌一分钟内的发送已到 " + PushSendQuota.PER_MINUTE
                    + " 条上限，请 " + verdict.retryAfterSeconds() + " 秒后再试", verdict.retryAfterSeconds());
        }

        JSONObject body = request.body();
        String platform = platform(body.get("platform"));
        PushTargetType type = targetType(body.get("targetType"));
        long target = target(body.get("target"));
        String content = content(body.get("content"));

        if (!registry.getSenderNames().contains(platform)) {
            throw NovaApiException.notFound(NO_PLATFORM);
        }
        String name = registry.displayName(platform);
        Set<PushTargetType> supported = registry.targetTypes(platform);
        if (!supported.contains(type)) {
            throw NovaApiException.badRequest("去向「" + name + "」不能发往" + type.getStr() + "（targetType 可选："
                    + supported.stream().map(Enum::name).collect(Collectors.joining("、")) + "）");
        }

        Attempt attempt = new Attempt(caller, platform, name, type, target, content.codePointCount(0, content.length()));
        Message message = Message.create(platform, type, target, content).get(0);
        DeliveryReceipt receipt;
        try {
            receipt = sender.deliverForCaller(message);
        } catch (RuntimeException | Error failure) {
            // 说不准交没交出去：记成不明，回包由底座回 500（文档写明 500 不要自动重发）
            record(writer, attempt, "ERROR", null, null);
            throw failure;
        }
        record(writer, attempt, receipt.outcome().name(), receipt.id(), receipt.reason());

        return switch (receipt.outcome()) {
            case SENT -> result(receipt.id(), "SENT");
            case UNKNOWN -> result(null, "UNKNOWN");
            case FAILED -> throw new NovaApiException(502, NovaApiErrors.DELIVERY_FAILED, "去向「" + name
                    + "」没有发出这条消息。常见原因：号码写错、机器人不在这个群里或不是对方好友、内容不被接受；"
                    + "原因记在控制台的时间线上");
            case BLOCKED -> throw NovaApiException.conflict("去向「" + name + "」此刻已停用，这条消息没有发出");
            case UNREACHABLE -> throw NovaApiException.conflict("去向「" + name + "」此刻连不上，这条消息没有发出；可以稍后再试");
            case EMPTY -> throw NovaApiException.conflict("机器人此刻 @ 不了全体成员（没有权限或今天的额度已用完），"
                    + "摘掉之后这条没有内容了，没有发出");
            case MISSING -> throw NovaApiException.notFound(NO_PLATFORM);
        };
    }

    private static Map<String, Object> result(String id, String delivery) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id == null || id.isBlank() ? null : id);
        data.put("delivery", delivery);
        return data;
    }

    private static String platform(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) {
            throw NovaApiException.badRequest("platform 缺了或写错了：应为去向标识（文字），可先调 GET /nova/api/v1/push/platforms 查看");
        }
        return text;
    }

    private static PushTargetType targetType(Object raw) {
        if ("FRIEND".equals(raw)) {
            return PushTargetType.FRIEND;
        }
        if ("GROUP".equals(raw)) {
            return PushTargetType.GROUP;
        }
        throw NovaApiException.badRequest("targetType 缺了或写错了：只能是 FRIEND（好友）或 GROUP（群）");
    }

    /**
     * 号码收字符串或整数；小数、负数、带前导零或超出范围的一律不收
     */
    private static long target(Object raw) {
        String text = raw instanceof String value ? value
                : raw instanceof Integer || raw instanceof Long ? raw.toString() : null;
        if (text != null && NUMBER.matcher(text).matches()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException tooLarge) {
                // 与写错同一句话
            }
        }
        throw NovaApiException.badRequest("target 缺了或写错了：应为号码（正整数），建议写成字符串");
    }

    private static String content(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) {
            throw NovaApiException.badRequest("content 缺了或是空的：要发的内容不能为空");
        }
        if (text.contains(NEXT)) {
            throw NovaApiException.badRequest("content 里不能有 {next}：一次请求只发一条消息，要分几条就分几次请求");
        }
        return text;
    }

    /**
     * 这一次发往哪里、谁发的
     * @param length 正文字数（按码点数）
     */
    private record Attempt(NovaApiCaller caller, String platform, String name, PushTargetType type, long target,
                           int length) {
    }

    private void record(TimelineWriter writer, Attempt attempt, String outcome, String id, String reason) {
        TimelineEventType type;
        TimelineEvent.Level level;
        String lead;
        switch (outcome) {
            case "SENT" -> {
                type = TimelineEventType.PUSH_SENT;
                level = TimelineEvent.Level.INFO;
                lead = "程序接口发出一条消息";
            }
            case "UNKNOWN" -> {
                type = TimelineEventType.PUSH_UNCERTAIN;
                level = TimelineEvent.Level.WARN;
                lead = "程序接口发送结果不明（可能已发出，没有重发）";
            }
            case "ERROR" -> {
                type = TimelineEventType.PUSH_UNCERTAIN;
                level = TimelineEvent.Level.WARN;
                lead = "程序接口发送时出错，发没发出说不准（没有重发）";
            }
            case "BLOCKED" -> {
                type = TimelineEventType.PUSH_FAILED;
                level = TimelineEvent.Level.WARN;
                lead = "程序接口发送没发出（去向已停用）";
            }
            case "UNREACHABLE" -> {
                type = TimelineEventType.PUSH_FAILED;
                level = TimelineEvent.Level.WARN;
                lead = "程序接口发送没发出（去向连不上）";
            }
            case "EMPTY" -> {
                type = TimelineEventType.PUSH_FAILED;
                level = TimelineEvent.Level.WARN;
                lead = "程序接口发送没发出（摘掉 @全体成员 之后没有内容）";
            }
            case "MISSING" -> {
                type = TimelineEventType.PUSH_FAILED;
                level = TimelineEvent.Level.WARN;
                lead = "程序接口发送没发出（没有这个去向）";
            }
            default -> {
                type = TimelineEventType.PUSH_FAILED;
                level = TimelineEvent.Level.WARN;
                lead = "程序接口发送没发出（去向拒收）";
            }
        }
        NovaApiCaller caller = attempt.caller();
        String masked = mask(attempt.target());
        String channel = attempt.type().getStr() + " " + masked;
        String safeReason = safeReason(reason, attempt.target());
        if ("SENT".equals(outcome)) {
            log.info("程序接口发送: 令牌 {}（编号 {}，账号 {}）→ {} {}: 已发出", caller.tokenName(), caller.tokenId(),
                    caller.accountId(), attempt.platform(), channel);
        } else {
            log.warn("程序接口发送: 令牌 {}（编号 {}，账号 {}）→ {} {}: {}{}", caller.tokenName(), caller.tokenId(),
                    caller.accountId(), attempt.platform(), channel, outcome, safeReason == null ? "" : "（" + safeReason + "）");
        }
        record(writer, TimelineEvent.of(type, level)
                .channel(channel)
                .text(lead + "：令牌「" + caller.tokenName() + "」（" + who(caller) + "）→ " + attempt.name() + " " + channel)
                .detail("source", "api")
                .detail("platform", attempt.platform())
                .detail("account", caller.accountId())
                .detail("token", caller.tokenName())
                .detail("tokenId", caller.tokenId())
                .detail("targetType", attempt.type().name())
                .detail("target", masked)
                .detail("length", String.valueOf(attempt.length()))
                .detail("outcome", outcome)
                .detail("id", "SENT".equals(outcome) ? id : null)
                .detail("reason", safeReason));
    }

    /** 时间线上写的账号：显示名，没有时用账号编号 */
    private static String who(NovaApiCaller caller) {
        String name = caller.displayName();
        return name == null || name.isBlank() ? caller.accountId() : name;
    }

    private static void record(TimelineWriter writer, TimelineEvent.Builder event) {
        try {
            writer.record(event.build());
        } catch (RuntimeException failure) {
            // 记不下来不能连累这一次的回包：消息可能已经发出去了
            log.warn("程序接口发送: 没能记进时间线（{}）", failure.getClass().getSimpleName());
        }
    }

    /**
     * 号码只留头尾两位，中间写成省略号；四位以内的整个省掉
     * <p>
     * 与仓内遮编号的写法同一个样子（开头一段、省略号、末尾一段，短的只剩省略号），
     * 号码比密钥短得多，两头各只留两位。
     */
    static String mask(long number) {
        String digits = Long.toString(number);
        return digits.length() <= 4 ? "…" : digits.substring(0, 2) + "…" + digits.substring(digits.length() - 2);
    }

    /**
     * 去向给的原因：遮掉在用的凭据与这次的号码，去掉控制字符，截短
     */
    static String safeReason(String reason, long target) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String text = SecretLogMask.mask(reason).replace(Long.toString(target), mask(target));
        text = CONTROL.matcher(text).replaceAll(" ").strip();
        if (text.codePointCount(0, text.length()) > REASON_IN_RECORD) {
            text = text.substring(0, text.offsetByCodePoints(0, REASON_IN_RECORD)) + "…";
        }
        return text;
    }
}
