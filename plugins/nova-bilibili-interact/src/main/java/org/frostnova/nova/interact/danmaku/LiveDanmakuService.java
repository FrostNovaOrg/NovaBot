package org.frostnova.nova.interact.danmaku;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.event.live.BilibiliDanmuEvent;
import org.frostnova.nova.bilibili.exception.RiskCooldownException;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.interact.config.InteractProperties;
import org.frostnova.nova.interact.speaker.BilibiliSpeakerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.HttpStatusCodeException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 往直播间发一条弹幕
 * <p>
 * 给房间号和文字，答「发出了」或「没发出、为什么」。本插件里要往直播间说话的功能都调这里，
 * 这里自己不会主动发任何东西：没人调 {@link #send}，就一个请求都没有。
 * <p>
 * 发之前依次核对：房间是不是已添加的主播的、文字合不合要求、选的账号能不能用、这个账号在不在暂停期、
 * 离上一条够不够最短间隔、字数有没有超过这个房间的上限。哪一项不过都直接回「没发出」，不出网。
 * <p>
 * <b>没发出不重试，也不排队。</b>请求超时这类不知道发没发出的，单列为
 * {@link DanmakuFailure#UNCERTAIN}，同样不重发：平台可能已经收下了第一条。
 * <p>
 * 每一次发送（含发之前就被拒的）在时间线上记一条：哪位主播的房间、弹幕正文、发出与否、分类。
 * 凭据始终在各账号的接口工具里，这里经手的只有房间号、正文和平台的代码与提示。
 */
@Slf4j
@NovaComponent
public class LiveDanmakuService {
    /**
     * 发弹幕的接口
     */
    static final String SEND_URL = "https://api.live.bilibili.com/msg/send";

    /**
     * 本账号在某个房间里的信息，字数上限在 {@code data.property.danmu.length}
     */
    static final String USER_ROOM_INFO_URL = "https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByUser?room_id=";

    /**
     * 取不到字数上限时按多少字算
     */
    public static final int DEFAULT_LENGTH_LIMIT = 20;

    /**
     * 取到的字数上限记多久：等级、粉丝牌带来的上限变化不频繁，半小时问一次足够
     */
    static final Duration LIMIT_TTL = Duration.ofMinutes(30);

    /**
     * 取不到、按 20 字算的那一份记多久：短一些，平台恢复后很快就能用上真实的上限
     */
    static final Duration FALLBACK_LIMIT_TTL = Duration.ofMinutes(2);

    /**
     * 平台说「太频繁」的代码
     */
    static final int CODE_TOO_FREQUENT = 10031;

    /**
     * 平台说「超出字数」的代码
     */
    static final int CODE_TOO_LONG = 1003212;

    /**
     * 平台说「未登录」的代码
     */
    static final int CODE_NOT_LOGGED_IN = -101;

    /**
     * 平台说「校验失败」的代码
     */
    static final int CODE_CHECK_FAILED = -111;

    /**
     * 风控类的代码：拦截、校验不过、请求过快、访问受限
     */
    static final Set<Integer> RISK_CODES = Set.of(-352, -412, -509, -799);

    /**
     * code 为 0 时表示被屏蔽词拦下的提示
     */
    static final Set<String> BLOCKED_HINTS = Set.of("f", "k");

    /**
     * code 为 0 时表示太快或重复的提示
     */
    static final Set<String> TOO_FAST_HINTS = Set.of("msg in 1s", "msg repeat");

    /**
     * 时间线里正文最多记多少字
     */
    private static final int TIMELINE_TEXT_LIMIT = 100;

    private final InteractProperties properties;

    private final SpeakingAccount speaker;

    private final SpeakingAccount listener;

    private final AbstractDataSource source;

    private final TimelineWriter timeline;

    private final DanmakuPacing pacing = new DanmakuPacing();

    private final Map<String, CachedLimit> limits = new ConcurrentHashMap<>();

    private volatile Supplier<Instant> clock = Instant::now;

    @Autowired
    public LiveDanmakuService(InteractProperties properties, BilibiliSpeakerService speaker,
                              BilibiliAccountService listener, BilibiliApiUtil listenerApi,
                              AbstractDataSource source, TimelineWriter timeline) {
        this(properties, new SpeakerSpeakingAccount(speaker), new ListenerSpeakingAccount(listener, listenerApi),
                source, timeline);
    }

    LiveDanmakuService(InteractProperties properties, SpeakingAccount speaker, SpeakingAccount listener,
                       AbstractDataSource source, TimelineWriter timeline) {
        this.properties = properties;
        this.speaker = speaker;
        this.listener = listener;
        this.source = source;
        this.timeline = timeline == null ? TimelineWriter.NONE : timeline;
    }

    /**
     * 测试用：换成可拨的时钟
     * @param clock 时钟
     */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock == null ? Instant::now : clock;
    }

    /**
     * 已添加的、知道房间号的哔哩哔哩主播
     * @param uid 主播 uid
     * @param name 主播名，不知道时为「房间 N」
     * @param roomId 房间号
     */
    public record Room(long uid, String name, long roomId) {
    }

    /**
     * 这个房间里一条弹幕最多多少字
     * @param max 最多多少字
     * @param fromPlatform 是从平台取到的；为 false 时是取不到、按 {@value #DEFAULT_LENGTH_LIMIT} 字算的
     */
    public record LengthLimit(int max, boolean fromPlatform) {
    }

    /**
     * 此刻发弹幕用的是哪个账号、能不能用
     * @param choice 选的哪个账号
     * @param usable 能用时为 true
     * @param failure 不能用的分类，能用时为 null
     * @param reason 不能用的原因，能用时为 null
     * @param uid 账号 uid，不能用时可能为 null
     * @param pausedFor 正在暂停时还要等多久，没暂停时为 null
     */
    public record AccountStatus(DanmakuAccountChoice choice, boolean usable, DanmakuFailure failure, String reason,
                                Long uid, Duration pausedFor) {
    }

    private record CachedLimit(LengthLimit limit, Instant expiresAt) {
    }

    /**
     * 往一位已添加的主播的直播间发一条弹幕
     * <p>
     * 文字两头的空白会去掉；空的、含换行的不发。超过这个房间的字数上限的不发，不截断、不拆条。
     * 同一个账号离上一条不到最短间隔的直接回「发得太快」，不排队。没发出不重试。
     * @param roomId 房间号，必须是某位已添加的主播的直播间
     * @param text 弹幕正文
     * @return 结果，不会为 null，也不抛异常
     */
    public DanmakuResult send(long roomId, String text) {
        return send(roomId, text, null);
    }

    /** 与试发共用账号最近一次占用的时间，配置变更立即参与计算。 */
    public boolean intervalElapsed(AccountStatus status) {
        String key = SpeakingAccount.key(status.choice(), status.uid());
        return pacing.tooSoon(key, clock.get(), interval()).isEmpty();
    }

    /** 回传事件中的发送者是否为当前选中的发言账号。 */
    public boolean isOwnDanmaku(BilibiliDanmuEvent event) {
        if (event == null || event.getSender() == null || event.getSender().getUid() == null) return false;
        return event.getSender().getUid().equals(current().uid());
    }

    /** 消息仍有效时才调用 reserveAccount，占到账号名额才把这一段标为发送中。 */
    @FunctionalInterface
    public interface QueueAdmission {
        boolean reserveIfCurrent(BooleanSupplier reserveAccount);
    }

    /** 账号间隔还没到或队列撤回时留给队列再检查，不产生一次发送记录。 */
    public Optional<DanmakuResult> sendQueued(long roomId, String text, long streamerUid, AccountStatus account,
                                              QueueAdmission admit) {
        return Optional.ofNullable(send(roomId, text, new QueuedSend(streamerUid, account, admit)));
    }

    private record QueuedSend(long streamerUid, AccountStatus account, QueueAdmission admit) {}

    private DanmakuResult send(long roomId, String text, QueuedSend queued) {
        String body = text == null ? "" : text.strip();
        Optional<PushUser> streamer = streamerOfRoom(roomId);
        DanmakuResult result;
        try {
            result = attempt(roomId, body, streamer.orElse(null), queued);
        } catch (RuntimeException e) {
            // 不该走到这里；走到了也按「不知道发没发出」交代，不让调用方去重发
            log.warn("弹幕：往房间 {} 发弹幕时出了意外（{}），按结果不明处理", roomId, e.getClass().getSimpleName());
            result = new DanmakuResult(false, DanmakuFailure.UNCERTAIN,
                    "发弹幕时出了意外，不知道发没发出，请到直播间看一眼；不会自动重发", roomId, body, null, null, null);
        }
        if (result != null) record(result, streamer.orElse(null));
        return result;
    }

    private DanmakuResult attempt(long roomId, String text, PushUser streamer, QueuedSend queued) {
        if (streamer == null || (queued != null && !Long.valueOf(queued.streamerUid()).equals(streamer.getUid()))) {
            return refused(DanmakuFailure.ROOM_NOT_ALLOWED, "房间 " + roomId
                    + " 不是已添加的主播的直播间，没发。只能往已添加的主播的直播间发弹幕", roomId, text, null);
        }
        if (text.isEmpty()) {
            return refused(DanmakuFailure.INVALID_TEXT, "弹幕是空的（只有空白也算空），没发。请写点内容再发", roomId, text, null);
        }
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return refused(DanmakuFailure.INVALID_TEXT, "弹幕里不能换行，没发。请写成一行再发", roomId, text, null);
        }

        SpeakingAccount account = current();
        Optional<SpeakingAccount.Refusal> refusal = account.refusal();
        String key = account.key();
        if (refusal.isPresent() || key == null) {
            SpeakingAccount.Refusal why = refusal.orElse(new SpeakingAccount.Refusal(DanmakuFailure.ACCOUNT_UNAVAILABLE,
                    "认不出" + account.choice().shortName() + "是哪个号，这条没发"));
            return refused(why.failure(), why.message(), roomId, text, account.choice());
        }

        if (queued != null && (account.choice() != queued.account().choice() || !queued.account().uid().equals(account.uid()))) {
            return refused(DanmakuFailure.NO_ACCOUNT, "发送账号已更换", roomId, text, account.choice());
        }
        Optional<Duration> paused = pacing.pausedFor(key, clock.get());
        if (paused.isPresent()) {
            return refused(DanmakuFailure.PAUSED, account.choice().shortName() + "刚被平台限制，暂停发弹幕中，这条没发。还要等"
                    + wait(paused.get()) + "；到点自动恢复", roomId, text, account.choice());
        }
        Duration interval = interval();
        Optional<Duration> soon = pacing.tooSoon(key, clock.get(), interval);
        if (soon.isPresent()) {
            return queued == null ? tooFast(roomId, text, account.choice(), interval) : null;
        }

        LengthLimit limit = lengthLimit(account, roomId);
        int length = length(text);
        if (length > limit.max()) {
            return refused(DanmakuFailure.TOO_LONG, "这条有 " + length + " 个字，这个房间一条最多 " + limit.max() + " 个字"
                    + (limit.fromPlatform() ? "" : "（没能从平台取到上限，按 " + DEFAULT_LENGTH_LIMIT + " 字算）")
                    + "，没发。请删短一些，不会自动截断或拆成几条", roomId, text, account.choice());
        }

        BooleanSupplier reserve = () -> pacing.reserve(key, clock.get(), interval);
        boolean reserved = queued == null ? reserve.getAsBoolean() : queued.admit().reserveIfCurrent(reserve);
        if (!reserved) {
            return queued == null ? tooFast(roomId, text, account.choice(), interval) : null;
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("roomid", roomId);
        form.put("msg", text);
        form.put("rnd", clock.get().getEpochSecond());
        form.put("fontsize", 25);
        form.put("color", 16777215);
        form.put("mode", 1);
        form.put("bubble", 0);

        JSONObject response;
        try {
            response = account.postOnce(SEND_URL, form);
        } catch (RiskCooldownException e) {
            Instant until = pacing.pause(key, clock.get(), pauseLength());
            return refused(DanmakuFailure.RISK_CONTROL, "平台正在限制" + account.choice().shortName()
                    + "发弹幕（风控冷却中），这条没发。接下来暂停" + wait(Duration.between(clock.get(), until))
                    + "，到点自动恢复",
                    roomId, text, account.choice());
        } catch (IllegalStateException e) {
            // 查过能用、发之前那一刻又不能用了（例如刚好退出登录）：一个请求都没发
            return refused(DanmakuFailure.ACCOUNT_UNAVAILABLE, account.choice().shortName() + "此刻用不了，这条没发："
                    + e.getMessage(), roomId, text, account.choice());
        } catch (RuntimeException e) {
            return requestFailed(e, roomId, text, account.choice());
        }
        if (response == null) {
            return new DanmakuResult(false, DanmakuFailure.UNCERTAIN, "没收到平台的回包，不知道发没发出，请到直播间看一眼；不会自动重发",
                    roomId, text, account.choice(), null, null);
        }
        return judge(response, roomId, text, account, key);
    }

    /**
     * 按平台的回包判断发没发出。认不出的一律按没发出算，把代码和提示原样带出，不猜
     */
    private DanmakuResult judge(JSONObject response, long roomId, String text, SpeakingAccount account, String key) {
        DanmakuAccountChoice choice = account.choice();
        Integer code = response.getInteger("code");
        String hint = firstNonBlank(response.getString("message"), response.getString("msg"));
        if (code == null) {
            return new DanmakuResult(false, DanmakuFailure.PLATFORM_ERROR, "平台的回包里没有结果代码，认不出发没发出，按没发出算"
                    + quoted(hint), roomId, text, choice, null, hint);
        }
        if (code == 0) {
            if (hint == null) {
                return new DanmakuResult(true, null, "已发出：「" + text + "」", roomId, text, choice, 0, null);
            }
            String normalized = hint.strip().toLowerCase(Locale.ROOT);
            if (BLOCKED_HINTS.contains(normalized)) {
                return new DanmakuResult(false, DanmakuFailure.BLOCKED_WORD, "被屏蔽词拦下了，这条没出现在直播间（平台提示："
                        + hint + "）。换个说法再发", roomId, text, choice, code, hint);
            }
            if (TOO_FAST_HINTS.contains(normalized)) {
                return new DanmakuResult(false, DanmakuFailure.TOO_FAST, "平台说发得太快或和上一条重复了，这条没发（平台提示："
                        + hint + "）。稍等一会儿、换个说法再发", roomId, text, choice, code, hint);
            }
            return new DanmakuResult(false, DanmakuFailure.PLATFORM_ERROR, "平台回了代码 0，但带着认不出的提示「" + hint
                    + "」，按没发出算", roomId, text, choice, code, hint);
        }
        if (code == CODE_TOO_FREQUENT) {
            Instant until = pacing.pause(key, clock.get(), pauseLength());
            return new DanmakuResult(false, DanmakuFailure.TOO_FAST, "平台说" + choice.shortName() + "发送太频繁（" + code + "），这条没发。"
                    + "接下来暂停" + wait(Duration.between(clock.get(), until)) + "，到点自动恢复", roomId, text, choice, code, hint);
        }
        if (RISK_CODES.contains(code)) {
            Instant until = pacing.pause(key, clock.get(), pauseLength());
            return new DanmakuResult(false, DanmakuFailure.RISK_CONTROL, "平台的风控拦下了这条（" + code + quoted(hint) + "），没发。"
                    + choice.shortName() + "接下来暂停" + wait(Duration.between(clock.get(), until)) + "，到点自动恢复",
                    roomId, text, choice, code, hint);
        }
        if (code == CODE_TOO_LONG) {
            limits.remove(limitKey(key, roomId));
            return new DanmakuResult(false, DanmakuFailure.TOO_LONG, "平台说超出了这个房间的字数上限（" + code + quoted(hint)
                    + "），没发。请删短一些再发", roomId, text, choice, code, hint);
        }
        if (code == CODE_NOT_LOGGED_IN) {
            return new DanmakuResult(false, DanmakuFailure.ACCOUNT_UNAVAILABLE, "平台说" + choice.shortName() + "没有登录（" + code
                    + "），这条没发。请到「连接」页重新扫码登录", roomId, text, choice, code, hint);
        }
        if (code == CODE_CHECK_FAILED) {
            return new DanmakuResult(false, DanmakuFailure.ACCOUNT_UNAVAILABLE, "平台说校验失败（" + code + quoted(hint)
                    + "），这条没发。多半是" + choice.shortName() + "的登录态不完整，请到「连接」页重新扫码登录",
                    roomId, text, choice, code, hint);
        }
        return new DanmakuResult(false, DanmakuFailure.PLATFORM_ERROR, "平台返回了错误（代码 " + code + quoted(hint) + "），这条没发",
                roomId, text, choice, code, hint);
    }

    /**
     * 请求没办成：连不上的确定没发出；被平台按 HTTP 状态拒掉的也没发出；其余（超时、中途断开、回包读不出）不知道
     */
    private DanmakuResult requestFailed(RuntimeException e, long roomId, String text, DanmakuAccountChoice choice) {
        if (connectFailure(e)) {
            log.info("弹幕：连不上哔哩哔哩，往房间 {} 的这条没发出（{}）", roomId, e.getClass().getSimpleName());
            return new DanmakuResult(false, DanmakuFailure.NETWORK, "连不上哔哩哔哩，请求没到平台，这条没发出。请检查本机网络",
                    roomId, text, choice, null, null);
        }
        HttpStatusCodeException status = find(e, HttpStatusCodeException.class);
        if (status != null && status.getStatusCode().is4xxClientError()) {
            int http = status.getStatusCode().value();
            return new DanmakuResult(false, DanmakuFailure.PLATFORM_ERROR, "平台拒绝了这次请求（HTTP " + http + "），这条没发",
                    roomId, text, choice, null, "HTTP " + http);
        }
        log.info("弹幕：往房间 {} 的这条结果不明（{}），不重发", roomId, e.getClass().getSimpleName());
        return new DanmakuResult(false, DanmakuFailure.UNCERTAIN, "请求已交给平台，但没等到回话（超时或连接中断），不知道发没发出。"
                + "请到直播间看一眼；不会自动重发", roomId, text, choice, null, null);
    }

    private static boolean connectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof NoRouteToHostException
                    || t instanceof HttpConnectTimeoutException || t instanceof UnresolvedAddressException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static <T extends Throwable> T find(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private DanmakuResult tooFast(long roomId, String text, DanmakuAccountChoice choice, Duration interval) {
        return refused(DanmakuFailure.TOO_FAST, "同一个账号两条弹幕之间至少隔 " + interval.toSeconds()
                + " 秒，这条来得太快，没发（不排队）。稍等再发", roomId, text, choice);
    }

    private static DanmakuResult refused(DanmakuFailure failure, String message, long roomId, String text,
                                         DanmakuAccountChoice choice) {
        return new DanmakuResult(false, failure, message, roomId, text, choice, null, null);
    }

    /**
     * 这个房间里此刻选的那个账号一条最多发多少字
     * <p>
     * 账号用不了时不去问平台，按 {@value #DEFAULT_LENGTH_LIMIT} 字答。
     * @param roomId 房间号
     * @return 字数上限
     */
    public LengthLimit lengthLimit(long roomId) {
        SpeakingAccount account = current();
        if (account.refusal().isPresent() || account.key() == null) {
            return new LengthLimit(DEFAULT_LENGTH_LIMIT, false);
        }
        return lengthLimit(account, roomId);
    }

    private LengthLimit lengthLimit(SpeakingAccount account, long roomId) {
        String cacheKey = limitKey(account.key(), roomId);
        Instant now = clock.get();
        CachedLimit cached = limits.get(cacheKey);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.limit();
        }
        LengthLimit limit = fetchLimit(account, roomId);
        limits.put(cacheKey, new CachedLimit(limit, now.plus(limit.fromPlatform() ? LIMIT_TTL : FALLBACK_LIMIT_TTL)));
        return limit;
    }

    private LengthLimit fetchLimit(SpeakingAccount account, long roomId) {
        try {
            JSONObject data = account.get(USER_ROOM_INFO_URL + roomId);
            JSONObject property = data == null ? null : data.getJSONObject("property");
            JSONObject danmu = property == null ? null : property.getJSONObject("danmu");
            Integer length = danmu == null ? null : danmu.getInteger("length");
            if (length != null && length > 0) {
                return new LengthLimit(length, true);
            }
            log.info("弹幕：房间 {} 的回包里没有字数上限，按 {} 字算", roomId, DEFAULT_LENGTH_LIMIT);
        } catch (RuntimeException e) {
            log.info("弹幕：没能取到房间 {} 的字数上限（{}），按 {} 字算", roomId, e.getClass().getSimpleName(), DEFAULT_LENGTH_LIMIT);
        }
        return new LengthLimit(DEFAULT_LENGTH_LIMIT, false);
    }

    private static String limitKey(String accountKey, long roomId) {
        return accountKey + "@" + roomId;
    }

    /**
     * 此刻发弹幕用的是哪个账号、能不能用、是不是在暂停期
     * @return 状态
     */
    public AccountStatus accountStatus() {
        SpeakingAccount account = current();
        Optional<SpeakingAccount.Refusal> refusal = account.refusal();
        if (refusal.isPresent()) {
            return new AccountStatus(account.choice(), false, refusal.get().failure(), refusal.get().message(),
                    account.uid(), null);
        }
        String key = account.key();
        Duration paused = key == null ? null : pacing.pausedFor(key, clock.get()).orElse(null);
        return new AccountStatus(account.choice(), true, null, null, account.uid(), paused);
    }

    /**
     * 此刻选的是哪个账号
     * @return 选项
     */
    public DanmakuAccountChoice choice() {
        return DanmakuAccountChoice.of(properties.getDanmaku().getAccount());
    }

    /**
     * 已添加的、知道房间号的哔哩哔哩主播，按名字排
     * @return 主播与房间
     */
    public List<Room> rooms() {
        return source.getUsers(BilibiliPlatform.BILIBILI.id()).stream()
                .filter(user -> user.getUid() != null && user.getRoomId() != null && user.getRoomId() > 0)
                .map(user -> new Room(user.getUid(), name(user), user.getRoomId()))
                .sorted(Comparator.comparing(Room::name).thenComparingLong(Room::uid))
                .toList();
    }

    /**
     * 这个房间是哪位已添加的主播的
     * @param roomId 房间号
     * @return 主播，不是任何一位已添加的主播的房间时为空
     */
    public Optional<PushUser> streamerOfRoom(long roomId) {
        return source.getUsers(BilibiliPlatform.BILIBILI.id()).stream()
                .filter(user -> user.getRoomId() != null && user.getRoomId() == roomId)
                .findFirst();
    }

    private SpeakingAccount current() {
        return choice() == DanmakuAccountChoice.LISTENER ? listener : speaker;
    }

    private Duration interval() {
        return Duration.ofSeconds(Math.max(1, properties.getDanmaku().getMinInterval()));
    }

    private Duration pauseLength() {
        return Duration.ofMinutes(Math.max(1, properties.getDanmaku().getPauseMinutes()));
    }

    private void record(DanmakuResult result, PushUser streamer) {
        try {
            String name = streamer == null ? "房间 " + result.roomId() : name(streamer);
            String shown = clip(result.text());
            TimelineEventType type;
            TimelineEvent.Level level;
            String line;
            if (result.sent()) {
                type = TimelineEventType.DANMAKU_SENT;
                level = TimelineEvent.Level.INFO;
                line = "往" + name + "的直播间发了一条弹幕：" + shown;
            } else if (result.uncertain()) {
                type = TimelineEventType.DANMAKU_UNCERTAIN;
                level = TimelineEvent.Level.WARN;
                line = "往" + name + "的直播间发弹幕，不知道发没发出：" + shown;
            } else {
                type = TimelineEventType.DANMAKU_NOT_SENT;
                level = TimelineEvent.Level.WARN;
                line = "往" + name + "的直播间发弹幕没发出（" + result.failure().description() + "）"
                        + (shown.isEmpty() ? "" : "：" + shown);
            }
            TimelineEvent.Builder event = TimelineEvent.of(type, level).text(line)
                    .detail("room", String.valueOf(result.roomId()))
                    .detail("danmaku", shown)
                    .detail("outcome", result.sent() ? "SENT" : result.failure().name())
                    .detail("reason", result.sent() ? null : result.message())
                    .detail("account", result.account() == null ? null : result.account().shortName())
                    .detail("code", result.platformCode() == null ? null : String.valueOf(result.platformCode()))
                    .detail("platformMessage", result.platformMessage());
            if (streamer != null) {
                event.streamer(name)
                        .detail("streamerPlatform", streamer.getPlatform())
                        .detail("streamerUid", String.valueOf(streamer.getUid()));
            }
            timeline.record(event.build());
        } catch (RuntimeException e) {
            log.warn("弹幕：没能记进时间线（{}）", e.getClass().getSimpleName());
        }
    }

    private static String name(PushUser user) {
        String uname = user.getUname();
        return uname == null || uname.isBlank() ? "房间 " + user.getRoomId() : uname;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        if (length(text) <= TIMELINE_TEXT_LIMIT) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, TIMELINE_TEXT_LIMIT)) + "…";
    }

    /**
     * 按字数算长度：一个汉字、一个表情都算一个字
     */
    static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private static String quoted(String hint) {
        return hint == null ? "" : "，平台提示：" + hint;
    }

    /**
     * 「约 4 分 30 秒」这样的说法，不足一秒按一秒说
     */
    static String wait(Duration duration) {
        long seconds = Math.max(1, (duration.toMillis() + 999) / 1000);
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (minutes == 0) {
            return "约 " + rest + " 秒";
        }
        return rest == 0 ? "约 " + minutes + " 分钟" : "约 " + minutes + " 分 " + rest + " 秒";
    }
}
