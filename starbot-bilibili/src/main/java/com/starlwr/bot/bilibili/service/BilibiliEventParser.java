package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.bilibili.event.live.*;
import com.starlwr.bot.bilibili.model.BilibiliEmojiInfo;
import com.starlwr.bot.bilibili.model.BilibiliUserInfo;
import com.starlwr.bot.bilibili.model.FansMedal;
import com.starlwr.bot.bilibili.model.Guard;
import com.starlwr.bot.bilibili.protocol.BilibiliProtobufReader;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播间消息解析器
 * <p>
 * 将直播间长连接下发的原始消息解析为 StarBot 事件。直播间消息的字段随版本频繁变动，
 * 且同一字段在不同消息中可能缺失，因此所有取值一律做空值防护：任何单条消息解析失败
 * 都只影响该条消息，不会中断整个直播间的消息处理。
 */
@Slf4j
@StarBotComponent
public class BilibiliEventParser {
    /**
     * 礼物与大航海接口返回的价格单位为电池的千分之一，1000 对应 1 元
     */
    private static final double PRICE_UNIT = 1000.0;

    /**
     * 从大航海播报文案里取陪伴天数，如「今天是TA陪伴主播的第1171天」
     * <p>
     * 「陪伴」与「第」之间隔着主播名等文字，长度不定，因此用惰性匹配并限长——
     * 不限长的话可能跨过整句话去匹配到后面某个不相干的数字。
     */
    private static final Pattern COMPANION_DAYS = Pattern.compile("陪伴[^0-9]{0,30}?第\\s*([0-9]{1,6})\\s*天");

    /**
     * 陪伴天数的合理上限，超出即认为匹配错了位置。按平台自身年龄留足余量
     */
    private static final int MAX_COMPANION_DAYS = 36500;

    /**
     * 已播报过的红包，键为 {@code lot_id}，值为首次见到的时刻
     * <p>
     * 红包的开启消息<b>会被周期性重播</b>：实测一条 {@code POPULARITY_RED_POCKET_START}
     * 的 {@code start_time} 是十分钟前，而 {@code current_time} 就是当下。
     * 不去重就会把同一个红包反复感谢。
     */
    private final Map<String, Instant> seenRedPockets = new ConcurrentHashMap<>();

    /**
     * 因 {@code info[0]} 过短而被整条丢弃的弹幕数
     * <p>
     * 这一处丢弃原先<b>一声不响</b>：格式一旦变了，弹幕会静默消失而日志上什么都看不到。
     * 计数按 1、10、100… 报，既不会淹掉日志，也不会让「丢了多少」无从得知。
     * <p>
     * 实测截至 2026-08-10，八个房间 656 条弹幕的 {@code info[0]} 长度恒为 18，一次都没触发过——
     * 但「没触发过」这个结论只对量过的语料成立，所以这个计数器不是多余的。
     */
    private final AtomicLong shortInfoDropped = new AtomicLong();

    /**
     * 红包记录的条目上限，防止长期运行后无限增长
     */
    private static final int MAX_SEEN_RED_POCKETS = 256;

    /**
     * 红包记录的保留时长。单个红包最长 600 秒，留一小时余量足够
     */
    private static final Duration RED_POCKET_RETENTION = Duration.ofHours(1);

    /**
     * {@code INTERACT_WORD_V2} 的 protobuf 字段号
     * <p>
     * 平台没有公开 {@code .proto}，以下字段号全部由实抓语料反推：2026-08-10 在单个直播间
     * 采集的 2122 条登录态 {@code INTERACT_WORD_V2}，用最小 wire 读取器逐条统计得出，
     * 并与 {@code protoc --decode_raw} 抽样核对过结构。
     * <p>
     * <b>反推必须用登录态语料。</b>匿名连接下 uid 被平台抹成 0，而 proto3 不序列化零值，
     * {@link #V2_UID} 会整个消失——照匿名语料会得出「uid 只在少数消息里出现」的错误结论。
     * 登录态语料里 uid 的出现率是 2122/2122。
     * <p>
     * 各字段的证据强度差别很大，取值时的防护按此区分：
     * <pre>
     *   字段  语义              证据
     *     1   观众 uid          2122/2122，1978 个不同值，与 22.1 逐条一致
     *     2   观众昵称          2122/2122，与 22.2.1 一致
     *     5   消息类型          2122/2122，取值只有 1/2/3，分布 2117/4/1
     *     6   房间号            2122/2122，恒为采集所在房间
     *     7   秒级时间戳        2122/2122，10 位，全部落在采集窗口内
     *     9   本房间粉丝勋章    859/2122（其中 6 条为空消息），子字段见下
     *    10   是否推广位进入    3/2122，恒为 1，与 V1 的 is_spread 对应
     *    13   推广来源文案      3/2122，与字段 10 同现，值为「流量包推广」
     *    22   观众完整信息      2122/2122，子字段见下
     *   9.1   勋章所属主播 uid  853/853
     *   9.2   勋章等级          853/853
     *   9.3   勋章名称          853/853
     *   9.8   勋章是否点亮      284/853（proto3 省略 0，未出现即未点亮）
     *  9.12   勋章所属房间号    853/853
     *  22.1   观众 uid          2122/2122
     *  22.2   基础信息 {1:昵称, 2:头像}
     *  22.3   勋章全量 {11:大航海等级, 13:大航海图标}
     *  22.4   财富等级 {1:等级}
     *  22.6   大航海 {1:等级, 2:到期时间}
     * </pre>
     * <b>不使用</b>字段 8：它多数时候等于字段 7 的毫秒形式，但 287/2122 条对不上，
     * 最多超出字段 7 达三天半，语义未明，拿它当时间戳会把事件时刻记错。
     * <p>
     * <b>不使用</b>字段 16：疑似大航海等级，29/2122 条出现且恒为 3，与 22.3.11、22.6.1
     * 逐条一致。但只见过舰长这一种取值，无法证明总督与提督也走同一个字段，
     * 因此大航海仍从 {@code uinfo} 里取，见 {@link #parseGuardV2}。
     * <p>
     * 字段 4、12、15、19、23、24 语义未坐实，均未取用，详见实现汇报。
     */
    private static final int V2_UID = 1;

    private static final int V2_UNAME = 2;

    private static final int V2_MSG_TYPE = 5;

    private static final int V2_TIMESTAMP = 7;

    private static final int V2_FANS_MEDAL = 9;

    private static final int V2_IS_SPREAD = 10;

    private static final int V2_SPREAD_DESC = 13;

    private static final int V2_UINFO = 22;

    private static final int V2_MEDAL_TARGET_UID = 1;

    private static final int V2_MEDAL_LEVEL = 2;

    private static final int V2_MEDAL_NAME = 3;

    private static final int V2_MEDAL_LIGHTED = 8;

    private static final int V2_MEDAL_ROOM_ID = 12;

    private static final int V2_UINFO_BASE = 2;

    private static final int V2_UINFO_MEDAL = 3;

    private static final int V2_UINFO_WEALTH = 4;

    private static final int V2_UINFO_GUARD = 6;

    private static final int V2_BASE_NAME = 1;

    private static final int V2_BASE_FACE = 2;

    private static final int V2_FULL_MEDAL_GUARD_LEVEL = 11;

    private static final int V2_FULL_MEDAL_GUARD_ICON = 13;

    private static final int V2_LEVEL = 1;

    private final StarBotBilibiliProperties properties;

    private final BilibiliGiftService giftService;

    private final BilibiliApiSupport apiSupport;

    private final BilibiliGuardReconciler guardReconciler;

    /**
     * 消息类型到解析方法的映射
     */
    private final Map<String, BiFunction<JSONObject, LiveStreamerInfo, StarBotBaseLiveEvent>> parsers = new HashMap<>();

    @Autowired
    public BilibiliEventParser(StarBotBilibiliProperties properties, BilibiliGiftService giftService,
                               BilibiliApiSupport apiSupport, BilibiliGuardReconciler guardReconciler) {
        this.properties = properties;
        this.giftService = giftService;
        this.apiSupport = apiSupport;
        this.guardReconciler = guardReconciler;

        parsers.put("LIVE", this::parseLiveOn);
        parsers.put("PREPARING", this::parseLiveOff);
        parsers.put("DANMU_MSG", this::parseMessage);
        // 两种格式都要收。2026-08 起平台改发 V2，三次抓包都是 0 条 V1——只认老格式的话
        // 进房、关注、分享会恒为 0 条且没有任何报错。V1 仍然保留，平台随时可能回滚
        parsers.put("INTERACT_WORD", this::parseInteract);
        parsers.put("INTERACT_WORD_V2", this::parseInteractV2);
        parsers.put("SEND_GIFT", this::parseGift);
        parsers.put("SUPER_CHAT_MESSAGE", this::parseSuperChat);
        parsers.put("USER_TOAST_MSG", this::parseGuard);
        parsers.put("USER_TOAST_MSG_V2", this::parseGuardV2);
        parsers.put("GUARD_BUY", this::parseGuardBuy);
        parsers.put("POPULARITY_RED_POCKET_START", this::parseRedPocket);
        parsers.put("POPULARITY_RED_POCKET_V2_START", this::parseRedPocket);
        parsers.put("LIKE_INFO_V3_CLICK", this::parseLike);
        parsers.put("LIKE_INFO_V3_UPDATE", this::parseLikeUpdate);
        parsers.put("WATCHED_CHANGE", this::parseWatchedUpdate);
        parsers.put("ONLINE_RANK_COUNT", this::parseOnlineRankCount);
        parsers.put("ROOM_CHANGE", this::parseRoomInfoChange);
        parsers.put("WARNING", this::parseWarning);
        parsers.put("CUT_OFF", this::parseCutOff);
        parsers.put("ROOM_LOCK", this::parseRoomLock);
    }

    /**
     * 解析一条直播间消息
     * @param data 消息内容
     * @param source 直播间信息
     * @return 解析出的事件，消息类型不受支持或解析失败时返回空
     */
    public Optional<StarBotBaseLiveEvent> parse(JSONObject data, LiveStreamerInfo source) {
        if (data == null) {
            return Optional.empty();
        }

        String type = data.getString("cmd");
        if (type == null) {
            return Optional.empty();
        }

        // 部分消息的 cmd 带有形如 DANMU_MSG:4:0:2:2:2:0 的后缀
        int colon = type.indexOf(':');
        if (colon > 0) {
            type = type.substring(0, colon);
        }

        if (properties.getDebug().isLiveRoomRawMessageLog()) {
            log.debug("{}: {} -> {}", type, source.getRoomId(), data.toJSONString());
        }

        BiFunction<JSONObject, LiveStreamerInfo, StarBotBaseLiveEvent> parser = parsers.get(type);
        if (parser == null) {
            return Optional.empty();
        }

        try {
            StarBotBaseLiveEvent event = parser.apply(data, source);
            if (event != null) {
                // 原始报文随事件一起走：事件输出协议要把它透传给下游，排障时也要对着它看
                // 「解析出来的字段」与「平台实际下发的内容」是不是一回事。存引用不做序列化，
                // 详见 StarBotBaseLiveEvent.rawMessage
                event.setRawMessage(data);
            }
            return Optional.ofNullable(event);
        } catch (Exception e) {
            log.error("解析直播间 {} 的 {} 类型消息异常, 内容: {}", source.getRoomId(), type, data.toJSONString(), e);
            return Optional.empty();
        }
    }

    /**
     * 解析开播消息
     */
    private StarBotBaseLiveEvent parseLiveOn(JSONObject data, LiveStreamerInfo source) {
        Long liveTime = data.getLong("live_time");
        if (liveTime == null) {
            // 开播消息在直播间连接建立时也会重复下发，此时不带开播时间，不应视为一次新的开播
            return null;
        }

        return new BilibiliLiveOnEvent(source, Instant.ofEpochSecond(liveTime));
    }

    /**
     * 解析下播消息
     */
    private StarBotBaseLiveEvent parseLiveOff(JSONObject data, LiveStreamerInfo source) {
        return new BilibiliLiveOffEvent(source);
    }

    /**
     * 解析弹幕与表情弹幕消息
     */
    private StarBotBaseLiveEvent parseMessage(JSONObject data, LiveStreamerInfo source) {
        // info 中只有下标 0 是必需的，粉丝勋章与荣耀等级所在的下标可能不存在，按可选处理
        JSONArray primary = arrayAt(data.getJSONArray("info"), 0);
        if (primary == null || primary.size() < 16) {
            reportShortInfo(primary);
            return null;
        }

        JSONArray info = data.getJSONArray("info");

        JSONObject meta = primary.getJSONObject(15);
        JSONObject senderInfo = meta == null ? null : meta.getJSONObject("user");
        if (senderInfo == null) {
            return null;
        }

        BilibiliUserInfo sender = buildSenderFromUinfo(senderInfo, source);
        // 弹幕消息的粉丝勋章位于 info[3]，为定长数组而非对象
        sender.setFansMedal(parseArrayFansMedal(arrayAt(info, 3), source));
        sender.setHonorLevel(Optional.ofNullable(arrayAt(info, 16)).map(array -> array.getInteger(0)).orElse(null));
        sender.setRoomAdmin(parseRoomAdmin(arrayAt(info, 2)));

        Instant timestamp = Optional.ofNullable(primary.getLong(4)).map(Instant::ofEpochMilli).orElseGet(Instant::now);
        JSONObject extra = parseExtra(meta.getString("extra"));

        // primary[13] 为字符串时是普通弹幕，为对象时是表情弹幕
        if (primary.get(13) instanceof JSONObject emojiInfo) {
            BilibiliEmojiInfo emoji = new BilibiliEmojiInfo(
                    emojiInfo.getString("emoticon_unique"),
                    extra.getString("content"),
                    emojiInfo.getString("url"),
                    emojiInfo.getInteger("width"),
                    emojiInfo.getInteger("height"),
                    null
            );
            return new BilibiliEmojiEvent(source, sender, emoji, timestamp);
        }

        String content = extra.getString("content");
        String contentText = content;

        List<BilibiliEmojiInfo> emojis = new ArrayList<>();
        JSONObject emots = extra.getJSONObject("emots");
        if (emots != null) {
            for (String emojiName : emots.keySet()) {
                JSONObject emojiInfo = emots.getJSONObject(emojiName);
                if (emojiInfo == null) {
                    continue;
                }

                if (contentText != null) {
                    contentText = contentText.replace(emojiName, "");
                }

                emojis.add(new BilibiliEmojiInfo(
                        emojiInfo.getString("emoticon_unique"),
                        emojiName,
                        emojiInfo.getString("url"),
                        emojiInfo.getInteger("width"),
                        emojiInfo.getInteger("height"),
                        emojiInfo.getInteger("count")
                ));
            }
        }

        BilibiliDanmuEvent event = new BilibiliDanmuEvent(source, sender, content, contentText, timestamp);
        event.setEmojis(emojis);
        event.setReply(parseReply(extra, source));

        return event;
    }

    /**
     * 报告一条因 {@code info[0]} 过短而被丢弃的弹幕
     * <p>
     * 按 1、10、100… 这样的次数报，而不是每条都报：格式真变了的时候丢弃是成千上万条的，
     * 逐条打日志只会把日志本身冲垮，而完全不打就等于让弹幕静默消失。
     */
    private void reportShortInfo(JSONArray primary) {
        long count = shortInfoDropped.incrementAndGet();
        if (Long.toString(count).matches("10*")) {
            log.warn("已有 {} 条弹幕因 info[0] 过短被丢弃（本条 {} 项，需要至少 16 项）。"
                            + "若这个数在持续增长，说明报文格式变了，需要重新核对下标",
                    count, primary == null ? 0 : primary.size());
        }
    }

    /**
     * 解析房管标志
     * <p>
     * 房管标志在<b>旧格式的发送者数组</b> {@code info[2]} 的第 3 位，
     * 而昵称、头像这些我们是从新格式的 {@code uinfo} 里取的——两处并存，
     * 但 {@code uinfo} 里<b>没有</b>房管标志（实测 1346 条弹幕，uinfo 的键始终是
     * anon / base / guard / guard_leader / medal / title / uhead_frame / uid / wealth）。
     * <p>
     * <b>{@code guard_leader} 不是房管</b>，那是大航海的舰长头衔，两者无关。
     * <p>
     * 实测印证：同一批样本里 24 条取 1、1322 条取 0，{@code info[2]} 的其余位恒定不变。
     * @param sender 旧格式发送者数组
     * @return 是否为房管，数组缺失时为空——空表示「这条消息没说」
     */
    private Boolean parseRoomAdmin(JSONArray sender) {
        if (sender == null || sender.size() < 3) {
            return null;
        }

        Integer admin = sender.getInteger(2);
        return admin == null ? null : admin == 1;
    }

    /**
     * 解析弹幕中的回复对象
     */
    private UserInfo parseReply(JSONObject extra, LiveStreamerInfo source) {
        Long replyUid = extra.getLong("reply_mid");
        if (replyUid == null || replyUid == 0L) {
            return null;
        }

        String replyUname = extra.getString("reply_uname");
        if (!properties.getLive().isCompleteEvent()) {
            return new UserInfo(replyUid, replyUname);
        }

        return new UserInfo(replyUid, replyUname, apiSupport.completeFace(replyUid, source).orElse(null));
    }

    /**
     * 解析弹幕消息中的附加信息
     */
    private JSONObject parseExtra(String extra) {
        if (extra == null || extra.isBlank()) {
            return new JSONObject();
        }

        try {
            JSONObject parsed = JSON.parseObject(extra);
            return parsed == null ? new JSONObject() : parsed;
        } catch (Exception e) {
            log.debug("解析弹幕附加信息失败: {}", extra);
            return new JSONObject();
        }
    }

    /**
     * 解析进房、关注与分享消息
     */
    private StarBotBaseLiveEvent parseInteract(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        BilibiliUserInfo sender = buildSenderFromUinfo(meta.getJSONObject("uinfo"), source);
        if (sender.getUid() == null) {
            sender.setUid(meta.getLong("uid"));
        }
        if (sender.getUname() == null) {
            sender.setUname(meta.getString("uname"));
        }
        sender.setFansMedal(parseObjectFansMedal(meta.getJSONObject("fans_medal"), source));

        Instant timestamp = Optional.ofNullable(meta.getLong("timestamp")).map(Instant::ofEpochSecond).orElseGet(Instant::now);

        Integer msgType = meta.getInteger("msg_type");
        if (msgType == null) {
            return null;
        }

        switch (msgType) {
            case 1 -> {
                BilibiliEnterRoomEvent event = new BilibiliEnterRoomEvent(source, sender, timestamp);
                event.setFromPromotion(isOne(meta.getInteger("is_spread")));
                event.setPromotionSource(Optional.ofNullable(meta.getString("spread_desc")).filter(s -> !s.isBlank()).orElse(null));
                return event;
            }
            case 2 -> {
                return new BilibiliFollowEvent(source, sender, timestamp);
            }
            case 3 -> {
                return new BilibiliShareEvent(source, sender, timestamp);
            }
            default -> {
                log.debug("未处理的直播间互动消息类型: {}", msgType);
                return null;
            }
        }
    }

    /**
     * 解析进房、关注与分享消息的新版格式（{@code INTERACT_WORD_V2}）
     * <p>
     * 与 {@code INTERACT_WORD} 是同一件事的两种格式，产出<b>完全相同的三种事件</b>，
     * 下游无需区分。差别只在承载方式：这一版把正文塞进 {@code data.pb}，
     * 是一段 base64 编码的 protobuf，字段号的来历与证据见 {@link #V2_UID} 处的字段表。
     * <p>
     * <b>验收覆盖度：</b>进房（{@code msg_type=1}）在 2117 条实抓样本上比对过，
     * 是这条通路的正主。<b>关注（4 条）与分享（1 条）样本量严重不足</b>，
     * 分支照写，见下面各自的说明。
     */
    private StarBotBaseLiveEvent parseInteractV2(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        byte[] payload = decodePayload(meta.getString("pb"), source);
        if (payload == null) {
            return null;
        }

        BilibiliProtobufReader message = BilibiliProtobufReader.parse(payload);
        if (message.isTruncated()) {
            // 报文读到一半就断了。已读到的字段仍然可用（uid 与 msg_type 都在开头），
            // 因此照常往下走，只留一行日志——格式真的变了才有迹可循
            log.debug("直播间 {} 的 INTERACT_WORD_V2 报文未能读完, 已按读到的 {} 个字段继续: {}",
                    source.getRoomId(), message.size(), meta.getString("pb"));
        }

        Long msgType = message.number(V2_MSG_TYPE);
        if (msgType == null) {
            // proto3 不序列化零值，取不到既可能是缺字段也可能是 msg_type=0。
            // 而 0 不在已知取值 1/2/3 里，两种情况都该丢弃
            log.debug("直播间 {} 的 INTERACT_WORD_V2 消息取不到互动类型, 已忽略", source.getRoomId());
            return null;
        }

        BilibiliUserInfo sender = buildSenderFromUinfoV2(message.message(V2_UINFO), source);
        if (sender.getUid() == null) {
            sender.setUid(message.number(V2_UID));
        }
        if (sender.getUname() == null) {
            sender.setUname(message.string(V2_UNAME));
        }
        sender.setFansMedal(parseFansMedalV2(message.message(V2_FANS_MEDAL), source));

        // 字段 7 是秒级。事件模型内部统一用 Instant，换算成毫秒是事件输出协议的事，
        // 与醒目留言的 start_time / end_time 同一处理方式
        Instant timestamp = Optional.ofNullable(epochSecond(message.number(V2_TIMESTAMP))).orElseGet(Instant::now);

        return switch (msgType.intValue()) {
            case 1 -> {
                BilibiliEnterRoomEvent event = new BilibiliEnterRoomEvent(source, sender, timestamp);
                // 字段 10 与 13 只在 3/2122 条里出现，值分别为 1 与「流量包推广」，
                // 与 V1 的 is_spread / spread_desc 对得上。样本少，但取不到就是「没走推广位」，
                // 与 V1 对字段缺失的处理一致，不会造成假信息
                Long spread = message.number(V2_IS_SPREAD);
                event.setFromPromotion(spread != null && spread == 1L);
                event.setPromotionSource(Optional.ofNullable(message.string(V2_SPREAD_DESC))
                        .filter(desc -> !desc.isBlank()).orElse(null));
                yield event;
            }
            // 关注：样本量不足，仅够确认枚举值存在，字段未充分验证。
            // 2122 条语料里只有 4 条 msg_type=2，全部来自同一直播间的同一段时间。
            // 已确认这 4 条的 uid、昵称、时间戳与勋章都在与进房相同的字段号上，
            // 但「关注是否会带 msg_type=1 所没有的字段」无从判断，也无法排除
            // 别的房间或别的客户端版本有不同结构
            case 2 -> new BilibiliFollowEvent(source, sender, timestamp);
            // 分享：样本量不足，仅够确认枚举值存在，字段未充分验证。
            // 整份语料只有 1 条 msg_type=3。单条样本只能证明这个枚举值确实会下发、
            // 且该条的字段布局与进房一致，此外<b>什么都不能证明</b>
            case 3 -> new BilibiliShareEvent(source, sender, timestamp);
            default -> {
                log.debug("未处理的直播间互动消息类型: {}", msgType);
                yield null;
            }
        };
    }

    /**
     * 取出并解码 protobuf 正文
     * <p>
     * 实测 2122 条样本的 {@code pb} 全为标准 base64（字符集只含 {@code A-Za-z0-9+/=}），
     * 因此用标准解码器。解码失败只留日志不抛出：单条报文的编码出问题不该影响整个直播间。
     * @return 正文字节，字段缺失或解码失败时为空
     */
    private byte[] decodePayload(String base64, LiveStreamerInfo source) {
        if (base64 == null || base64.isBlank()) {
            log.debug("直播间 {} 的 INTERACT_WORD_V2 消息没有 pb 字段, 已忽略", source.getRoomId());
            return null;
        }

        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            log.debug("直播间 {} 的 INTERACT_WORD_V2 消息的 pb 不是合法 base64, 已忽略: {}", source.getRoomId(), base64);
            return null;
        }
    }

    /**
     * 从 protobuf 形式的 uinfo 构造发送者信息
     * <p>
     * 与 {@link #buildSenderFromUinfo} 是同一件事的两种承载形式，取值位置一一对应：
     * {@code uid} 在字段 1、昵称与头像在 {@code base}、财富等级在 {@code wealth}、
     * 大航海在勋章全量里。
     */
    private BilibiliUserInfo buildSenderFromUinfoV2(BilibiliProtobufReader uinfo, LiveStreamerInfo source) {
        if (uinfo == null) {
            return new BilibiliUserInfo();
        }

        Long uid = uinfo.number(V2_UID);
        BilibiliProtobufReader base = uinfo.message(V2_UINFO_BASE);

        String uname = base == null ? null : base.string(V2_BASE_NAME);
        String face = base == null ? null : base.string(V2_BASE_FACE);

        if (base == null && properties.getLive().isCompleteEvent() && uid != null) {
            uname = apiSupport.completeUname(uid, source).orElse(null);
            face = apiSupport.completeFace(uid, source).orElse(null);
        }

        BilibiliUserInfo sender = new BilibiliUserInfo(uid, uname, face);
        sender.setGuard(parseGuardV2(uinfo));
        sender.setHonorLevel(Optional.ofNullable(uinfo.message(V2_UINFO_WEALTH))
                .map(wealth -> wealth.number(V2_LEVEL))
                .map(Long::intValue)
                .orElse(null));

        return sender;
    }

    /**
     * 解析 protobuf 形式的大航海信息
     * <p>
     * 同一条报文里大航海等级出现在<b>三处</b>：勋章全量的字段 11、{@code guard} 子消息的字段 1、
     * 以及顶层的字段 16。实抓的 29 条上舰观众消息里三处恒为同一个值。
     * <p>
     * 以勋章全量为主，与 {@link #buildSenderFromUinfo} 从 {@code medal} 取的先例一致，
     * 且只有那里同时带图标地址；勋章缺失时退到 {@code guard} 子消息——
     * 此时<b>只有等级没有图标</b>，宁可少一个图标也不要把上舰的人显示成普通观众。
     * @return 大航海信息，两处都取不到或等级为 0 时为空
     */
    private Guard parseGuardV2(BilibiliProtobufReader uinfo) {
        Long guardLevel = Optional.ofNullable(uinfo.message(V2_UINFO_MEDAL))
                .map(medal -> medal.number(V2_FULL_MEDAL_GUARD_LEVEL))
                .orElse(null);
        if (guardLevel != null && guardLevel != 0L) {
            String icon = Optional.ofNullable(uinfo.message(V2_UINFO_MEDAL))
                    .map(medal -> medal.string(V2_FULL_MEDAL_GUARD_ICON))
                    .orElse(null);
            return new Guard(guardLevel.intValue(), icon);
        }

        Long fallback = Optional.ofNullable(uinfo.message(V2_UINFO_GUARD))
                .map(guard -> guard.number(V2_LEVEL))
                .orElse(null);
        if (fallback == null || fallback == 0L) {
            return null;
        }

        return new Guard(fallback.intValue(), null);
    }

    /**
     * 解析 protobuf 形式的粉丝勋章
     * <p>
     * 与 {@link #parseObjectFansMedal} 对应，同样是「观众在本直播间的勋章」，
     * 没有勋章时平台下发一条<b>空的</b>子消息（实抓 859 条里有 6 条如此）而非省略字段，
     * 因此判据仍是所属主播 uid 取不到就当没有勋章。
     */
    private FansMedal parseFansMedalV2(BilibiliProtobufReader medal, LiveStreamerInfo source) {
        if (medal == null) {
            return null;
        }

        Long uid = medal.number(V2_MEDAL_TARGET_UID);
        if (uid == null || uid == 0L) {
            return null;
        }

        Long level = medal.number(V2_MEDAL_LEVEL);
        Long lighted = medal.number(V2_MEDAL_LIGHTED);
        return buildFansMedal(uid, null,
                medal.number(V2_MEDAL_ROOM_ID),
                medal.string(V2_MEDAL_NAME),
                level == null ? null : level.intValue(),
                // proto3 省略零值：284/853 条带这个字段且恒为 1，其余是未点亮而非「没说」
                lighted != null && lighted == 1L,
                source);
    }

    /**
     * 解析礼物消息
     */
    private StarBotBaseLiveEvent parseGift(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        BilibiliUserInfo sender = new BilibiliUserInfo(meta.getLong("uid"), meta.getString("uname"), meta.getString("face"));
        sender.setHonorLevel(meta.getInteger("wealth_level"));

        JSONObject medal = Optional.ofNullable(meta.getJSONObject("sender_uinfo"))
                .map(uinfo -> uinfo.getJSONObject("medal"))
                .orElse(null);
        sender.setFansMedal(parseMedalFansMedal(medal, source));
        sender.setGuard(parseGuard(medal));

        Instant timestamp = Optional.ofNullable(meta.getLong("timestamp")).map(Instant::ofEpochSecond).orElseGet(Instant::now);

        Integer count = meta.getInteger("num");
        GiftInfo gift = new GiftInfo(
                meta.getLong("giftId"),
                meta.getString("giftName"),
                toYuan(meta.getInteger("discount_price")),
                count,
                Optional.ofNullable(meta.getJSONObject("gift_info")).map(info -> info.getString("img_basic")).orElse(null)
        );

        String coinType = meta.getString("coin_type");
        if ("silver".equals(coinType)) {
            return new BilibiliFreeGiftEvent(source, sender, gift, timestamp);
        }

        if (!"gold".equals(coinType)) {
            log.debug("未处理的直播间礼物货币类型: {}", coinType);
            return null;
        }

        JSONObject blind = meta.getJSONObject("blind_gift");
        if (blind == null) {
            Double value = gift.getPrice() == null || count == null ? null : gift.getPrice() * count;
            BilibiliPaidGiftEvent event = new BilibiliPaidGiftEvent(source, sender, gift, value, timestamp);
            event.setCharged(chargedOf(meta, value));
            event.setFromBag(fromBag(meta));
            return event;
        }

        Long randomGiftId = blind.getLong("original_gift_id");
        GiftInfo randomGift = new GiftInfo(
                randomGiftId,
                blind.getString("original_gift_name"),
                toYuan(blind.getInteger("original_gift_price")),
                count,
                properties.getLive().isCompleteEvent() ? giftService.getGiftUrl(randomGiftId).orElse(null) : null
        );

        Double price = randomGift.getPrice() == null || count == null ? null : randomGift.getPrice() * count;
        Double value = gift.getPrice() == null || count == null ? null : gift.getPrice() * count;

        BilibiliRandomGiftEvent event = new BilibiliRandomGiftEvent(source, sender, randomGift, gift, price, value, timestamp);
        // 盲盒的实扣就是盲盒本身的价，与 total_coin 应当一致。以 total_coin 为准并在不一致时留下日志——
        // 盲盒尚未拿到过真实报文，这行日志就是将来真有一个盲盒送进来时的证据
        event.setCharged(chargedOf(meta, price));
        event.setFromBag(fromBag(meta));
        return event;
    }

    /**
     * 判断礼物是否来自背包
     * <p>
     * 判别字段是 {@code bag_gift}：背包礼物为一个对象，普通礼物为 {@code null}。
     * <b>不要拿金额去反推</b>——理由见 {@code StarBotLiveGiftEvent.fromBag} 的契约说明。
     * @param meta 礼物消息体
     * @return 是否来自背包
     */
    private boolean fromBag(JSONObject meta) {
        return meta.getJSONObject("bag_gift") != null;
    }

    /**
     * 取观众为这一笔实际付出的金额
     * <p>
     * {@code total_coin} 是<b>服务端给出的实际扣除额</b>，比自己拿单价乘数量更可靠：
     * 打折与盲盒这些情形都体现在它身上，而单价字段体现不出来。
     * 已用一次真实的 ¥0.1 礼物核对过 {@code total_coin == discount_price × num}，
     * 又用一次真实的心动盲盒核对过 {@code total_coin} 跟的是盒子的价而非开出物的价。
     * <p>
     * <b>唯独背包礼物是例外，只能靠 {@code bag_gift} 认出来。</b>
     * 2026-08-06 实测两条背包礼物，{@code total_coin} 都<b>等于礼物原价而不是 0</b>
     * （小花花 100、人气票 100），照它记就会把白来的礼物算成观众的支出。
     * 判别字段是 {@code bag_gift}：背包礼物为一个对象，普通礼物为 {@code null}。
     * <p>
     * 字段缺失时回退到调用方算出的金额，<b>而不是当作 0</b>——
     * 把「取不到」记成「没花钱」会让营收凭空少一截，且不会有任何报错。
     * @param meta 礼物消息内容
     * @param expected 字段缺失时的回退值
     * @return 实扣金额（元）
     */
    private Double chargedOf(JSONObject meta, Double expected) {
        if (meta.getJSONObject("bag_gift") != null) {
            // 背包礼物来自红包、活动或签到，观众没有为这一笔花钱。
            // 这里必须早于 total_coin 判断：它在背包礼物上给的是原价，不是扣除额
            return 0.0;
        }

        Integer totalCoin = meta.getInteger("total_coin");
        if (totalCoin == null) {
            // 留空而不是填一个算出来的值：空表示「平台没告诉我们」，
            // 填上则表示「平台就是这么说的」。下游据此才能分辨
            // 「两个口径确实相等」与「取不到才回退成相等」，回退由消费方自己做
            return null;
        }

        double charged = totalCoin / PRICE_UNIT;
        if (expected != null && Math.abs(charged - expected) > 0.001) {
            log.debug("礼物 {} 的实扣 {} 与按单价算出的 {} 不一致, 以实扣为准",
                    meta.getString("giftName"), charged, expected);
        }
        return charged;
    }

    /**
     * 解析醒目留言消息
     */
    private StarBotBaseLiveEvent parseSuperChat(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        JSONObject senderInfo = meta.getJSONObject("uinfo");
        BilibiliUserInfo sender = buildSenderFromUinfo(senderInfo, source);
        if (senderInfo != null) {
            JSONObject medal = senderInfo.getJSONObject("medal");
            sender.setFansMedal(parseMedalFansMedal(medal, source));
            sender.setGuard(parseGuard(medal));
        }

        Instant timestamp = Optional.ofNullable(data.getLong("send_time")).map(Instant::ofEpochMilli).orElseGet(Instant::now);

        BilibiliSuperChatEvent event = new BilibiliSuperChatEvent(source, sender, meta.getString("message"), meta.getDouble("price"), timestamp);

        // 字段名与量纲取自 2026-08-07 的实抓样本: time=60（秒）、start_time 与 end_time
        // 是秒级时间戳且相差正好等于 time、id 是醒目留言自己的编号。
        // 这里全部按「取不到就留空」处理——0 秒的 SC 与「不知道多久」是两件事
        event.setDurationSec(meta.getInteger("time"));
        event.setMessageId(meta.getLong("id"));
        event.setStartTime(epochSecond(meta.getLong("start_time")));
        event.setEndTime(epochSecond(meta.getLong("end_time")));

        return event;
    }

    /**
     * 秒级时间戳转时刻
     * @param seconds 秒级时间戳，为空或非正数时视为平台没给
     * @return 时刻，取不到时为空
     */
    private Instant epochSecond(Long seconds) {
        return seconds == null || seconds <= 0 ? null : Instant.ofEpochSecond(seconds);
    }

    /**
     * 解析红包消息（{@code POPULARITY_RED_POCKET_START} 与其 V2 形式）
     * <p>
     * <b>红包不给主播带来收益</b>，钱进的是红包，只有中奖者把奖品换成礼物送出主播才分成。
     * 因此产出的是 {@link BilibiliRedPocketEvent} 而非任何购买事件——
     * 详见 {@link com.starlwr.bot.core.event.live.common.RedPocketEvent} 的说明。
     * <p>
     * 只认「开启」这一条。中奖名单（{@code ..._WINNER_LIST}）暂不处理：
     * 它同样有 v1/V2 两种形式，而目前只抓到过两个不同 {@code lot_id} 的样本，
     * <b>无法证明同一个红包会不会同时下发两版</b>，贸然处理有重复计数的风险。
     * @param data 消息内容
     * @param source 主播信息
     * @return 红包事件，无法识别或属于重播时为空
     */
    private StarBotBaseLiveEvent parseRedPocket(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        Object lotId = meta.get("lot_id");
        if (lotId == null) {
            // 认不出是哪个红包就没法挡重播。按本项目一贯的取舍，宁可漏播一次也不要反复感谢
            log.debug("红包消息缺少 lot_id, 已忽略");
            return null;
        }

        Instant now = Instant.now();
        if (seenRedPockets.putIfAbsent(String.valueOf(lotId), now) != null) {
            log.debug("红包开启消息重播, 已忽略: lot={}", lotId);
            return null;
        }
        sweepRedPockets(now);

        // V2 形式的发送者字段位置未实测过。按 USER_TOAST_MSG_V2 的先例，
        // 新格式会把人塞进 sender_uinfo，所以优先读它，读不到再退回平铺字段
        JSONObject uinfo = meta.getJSONObject("sender_uinfo");
        JSONObject base = uinfo == null ? null : uinfo.getJSONObject("base");
        Long uid = Optional.ofNullable(uinfo).map(info -> info.getLong("uid")).orElseGet(() -> meta.getLong("sender_uid"));
        String uname = Optional.ofNullable(base).map(info -> info.getString("name")).orElseGet(() -> meta.getString("sender_name"));
        String face = Optional.ofNullable(base).map(info -> info.getString("face")).orElseGet(() -> meta.getString("sender_face"));
        if (uid == null && uname == null) {
            // 连是谁发的都取不到，这条就没有播报价值了。留一行日志，格式变了才有迹可循
            log.debug("红包消息认不出发送者, 已忽略: lot={}", lotId);
            return null;
        }

        // 用红包自己的开始时刻而不是收到消息的时刻：首次见到的可能已经是重播
        Instant startedAt = Optional.ofNullable(meta.getLong("start_time")).map(Instant::ofEpochSecond).orElse(now);

        BilibiliRedPocketEvent event = new BilibiliRedPocketEvent(source, new BilibiliUserInfo(uid, uname, face), startedAt);
        event.setLotteryId(String.valueOf(lotId));
        // total_price 与礼物价格同单位（千分之一元），已用真实账单核对：
        // 一笔 total_price=2000 的红包，发红包的人实际支出 20 电池即 ¥2.00
        event.setCost(Optional.ofNullable(meta.getInteger("total_price")).map(price -> price / PRICE_UNIT).orElse(null));

        JSONArray awards = meta.getJSONArray("awards");
        if (awards != null && !awards.isEmpty()) {
            JSONObject award = awards.getJSONObject(0);
            if (award != null) {
                event.setAwardName(award.getString("gift_name"));
                event.setAwardCount(award.getInteger("num"));
            }
        }
        return event;
    }

    /**
     * 清掉超出保留期的红包记录
     */
    private void sweepRedPockets(Instant now) {
        if (seenRedPockets.size() >= MAX_SEEN_RED_POCKETS) {
            seenRedPockets.entrySet().removeIf(entry -> Duration.between(entry.getValue(), now).compareTo(RED_POCKET_RETENTION) > 0);
        }
    }

    /**
     * 解析大航海消息（{@code USER_TOAST_MSG}）
     * <p>
     * 这条带的 {@code price} 是<b>实际成交价</b>，与 {@code GUARD_BUY} 的挂牌价不是一回事，
     * 取舍见 {@link BilibiliGuardReconciler}。
     */
    private StarBotBaseLiveEvent parseGuard(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        Integer guardLevel = meta.getInteger("guard_level");
        if (guardLevel == null) {
            return null;
        }

        Long senderUid = meta.getLong("uid");
        Instant timestamp = Optional.ofNullable(data.getLong("send_time")).map(Instant::ofEpochMilli).orElseGet(Instant::now);

        if (!guardReconciler.acceptToast(meta.getString("payflow_id"), senderUid, guardLevel, timestamp)) {
            return null;
        }

        return buildGuardEvent(source, senderUid, meta.getString("username"), meta.getString("role_name"),
                guardLevel, toYuan(meta.getInteger("price")), meta.getInteger("num"), meta.getString("unit"),
                GuardOperateType.of(Optional.ofNullable(meta.getInteger("op_type")).orElse(-1)),
                companionDaysOf(meta.getString("toast_msg")), timestamp);
    }

    /**
     * 解析大航海消息的新版格式（{@code USER_TOAST_MSG_V2}）
     * <p>
     * 与 {@code USER_TOAST_MSG} 是同一件事的两种格式，字段位置不同：开通者在 {@code sender_uinfo}，
     * 等级与操作类型在 {@code guard_info}，金额在 {@code pay_info}。
     * <p>
     * <b>两种格式都要收。</b>2026-08-06 抓的 49 笔上舰里有 7 笔只以 V2 形式下发、
     * 且没有 {@code GUARD_BUY} 兜底——只认老格式就会让这 14% 完全消失，而且不会有任何报错。
     * 重复的那部分靠 {@code payflow_id} 去重。
     */
    private StarBotBaseLiveEvent parseGuardV2(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        JSONObject guardInfo = meta.getJSONObject("guard_info");
        JSONObject payInfo = meta.getJSONObject("pay_info");
        if (guardInfo == null || payInfo == null) {
            return null;
        }

        Integer guardLevel = guardInfo.getInteger("guard_level");
        if (guardLevel == null) {
            return null;
        }

        JSONObject senderInfo = meta.getJSONObject("sender_uinfo");
        Long senderUid = senderInfo == null ? null : senderInfo.getLong("uid");
        JSONObject base = senderInfo == null ? null : senderInfo.getJSONObject("base");

        Instant timestamp = Optional.ofNullable(data.getLong("send_time")).map(Instant::ofEpochMilli)
                .or(() -> Optional.ofNullable(guardInfo.getLong("start_time")).map(Instant::ofEpochSecond))
                .orElseGet(Instant::now);

        if (!guardReconciler.acceptToast(payInfo.getString("payflow_id"), senderUid, guardLevel, timestamp)) {
            return null;
        }

        return buildGuardEvent(source, senderUid, base == null ? null : base.getString("name"),
                guardInfo.getString("role_name"), guardLevel, toYuan(payInfo.getInteger("price")),
                payInfo.getInteger("num"), payInfo.getString("unit"),
                GuardOperateType.of(Optional.ofNullable(guardInfo.getInteger("op_type")).orElse(-1)),
                companionDaysOf(meta.getString("toast_msg")), timestamp);
    }

    /**
     * 解析大航海开通消息（{@code GUARD_BUY}）
     * <p>
     * <b>解析出的事件不在这里返回，而是交给 {@link BilibiliGuardReconciler} 压住等 toast。</b>
     * 这条的 {@code price} 是挂牌价（35 个样本里舰长恒为 198000），toast 的才是实际成交价；
     * 而这条又恒定先到，不压住就必然取到挂牌价，实测高估 15.4%。
     * <p>
     * 字段也更少：实测 35 条<b>全都没有 {@code unit}</b>，且 {@code start_time == end_time}，
     * 所以 {@link #unitOf} 的两条路都走不通，单位只能是空——这也是宁可等 toast 的理由之一。
     */
    private StarBotBaseLiveEvent parseGuardBuy(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        Integer guardLevel = meta.getInteger("guard_level");
        if (guardLevel == null) {
            return null;
        }

        Long senderUid = meta.getLong("uid");
        Integer count = meta.getInteger("num");
        Double price = toYuan(meta.getInteger("price"));
        Instant timestamp = Optional.ofNullable(meta.getLong("start_time"))
                .map(Instant::ofEpochSecond).orElseGet(Instant::now);

        // 单价还是总价？至今 35 个样本全是 num=1，区分不出来。多买时把三个数一起记下来，
        // 首次出现就能人工核对——按单价处理而实际是总价的话，多月开通会被乘重
        if (count != null && count > 1) {
            log.info("大航海开通数量大于 1, 请核对价格口径: price={} num={} 按单价算得 {} 元",
                    meta.getInteger("price"), count, price == null ? null : price * count);
        }

        guardReconciler.holdGuardBuy(senderUid, guardLevel, timestamp,
                buildGuardEvent(source, senderUid, meta.getString("username"), meta.getString("gift_name"),
                        guardLevel, price, count, unitOf(meta), GuardOperateType.UNKNOWN, null, timestamp));
        return null;
    }

    /**
     * 从播报文案里取陪伴天数
     * <p>
     * 平台没有给这个字段，只把它写进 {@code toast_msg}，如
     * 「&lt;%某人%&gt; 在主播某某的直播间开通了舰长，今天是TA陪伴主播的第1171天」。
     * <p>
     * <b>这是在解析文案，不是解析字段，随时可能因为改版而失效。</b>
     * 因此取不到就返回空，<b>绝不返回 0</b>——「陪伴 0 天」会变成假信息出现在感谢文案里。
     * 同理超出常理的值也当作没取到：正则一旦匹配错位置，宁可丢掉也不要拿去展示。
     * @return 陪伴天数，解析不出或不合常理时为空
     */
    private Integer companionDaysOf(String toastMsg) {
        if (toastMsg == null || toastMsg.isBlank()) {
            return null;
        }

        Matcher matcher = COMPANION_DAYS.matcher(toastMsg);
        if (!matcher.find()) {
            return null;
        }

        try {
            int days = Integer.parseInt(matcher.group(1));
            // 上限按平台自身年龄留足余量。超出说明多半匹配到了别的数字
            return days > 0 && days <= MAX_COMPANION_DAYS ? days : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按等级组装大航海事件
     * <p>
     * 三条播报消息（{@code GUARD_BUY}、{@code USER_TOAST_MSG}、{@code USER_TOAST_MSG_V2}）
     * 字段位置各不相同，取值的差异留在各自的解析方法里，这里只负责组装。
     * @param iconName 用于查图标的名称，各消息取自不同字段
     * @param companionDays 陪伴天数，{@code GUARD_BUY} 没有文案可解析，传空
     * @return 等级不认识时返回 null
     */
    private StarBotBaseLiveEvent buildGuardEvent(LiveStreamerInfo source, Long senderUid, String username,
                                                 String iconName, Integer guardLevel, Double price, Integer count,
                                                 String unit, GuardOperateType operateType, Integer companionDays,
                                                 Instant timestamp) {
        boolean complete = properties.getLive().isCompleteEvent();
        BilibiliUserInfo sender = new BilibiliUserInfo(
                senderUid,
                username,
                complete ? apiSupport.completeFace(senderUid, source).orElse(null) : null
        );
        sender.setGuard(new Guard(guardLevel, complete ? giftService.getGuardIcon(iconName).orElse(null) : null));

        return switch (guardLevel) {
            case 1 -> {
                BilibiliGovernorEvent event = new BilibiliGovernorEvent(source, sender, price, count, unit, timestamp);
                event.setOperateType(operateType);
                event.setCompanionDays(companionDays);
                yield event;
            }
            case 2 -> {
                BilibiliCommanderEvent event = new BilibiliCommanderEvent(source, sender, price, count, unit, timestamp);
                event.setOperateType(operateType);
                event.setCompanionDays(companionDays);
                yield event;
            }
            case 3 -> {
                BilibiliCaptainEvent event = new BilibiliCaptainEvent(source, sender, price, count, unit, timestamp);
                event.setOperateType(operateType);
                event.setCompanionDays(companionDays);
                yield event;
            }
            default -> {
                log.debug("未处理的直播间大航海类型: {}", guardLevel);
                yield null;
            }
        };
    }

    /**
     * 取出大航海开通时长的单位
     * <p>
     * 先认消息自己给的 {@code unit}。{@code GUARD_BUY} 到底带不带这个字段，我们没有实测过
     * ——曾有过「不带」的说法，但那是把「没记录」当成了「不存在」，已被撤回。所以这里不预设，
     * 有就用，只在没有时才回退到起止时刻。
     * <p>
     * 回退时按天数归类而不是精确换算：大航海只按月/年售卖，而月长本就有 28~31 天的浮动，
     * 硬算会得出「1.03 个月」这种东西。
     * @param meta 消息内容
     * @return 「月」或「年」，推不出来时为 null 而不是猜一个
     */
    private String unitOf(JSONObject meta) {
        String declared = meta.getString("unit");
        if (declared != null && !declared.isBlank()) {
            return declared;
        }

        Long start = meta.getLong("start_time");
        Long end = meta.getLong("end_time");
        if (start == null || end == null || end <= start) {
            return null;
        }

        long days = (end - start) / 86400;
        if (days >= 300) {
            return "年";
        }
        return days >= 20 ? "月" : null;
    }

    /**
     * 解析点赞消息
     */
    private StarBotBaseLiveEvent parseLike(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        BilibiliUserInfo sender = buildSenderFromUinfo(meta.getJSONObject("uinfo"), source);
        if (sender.getUid() == null) {
            sender.setUid(meta.getLong("uid"));
        }
        if (sender.getUname() == null) {
            sender.setUname(meta.getString("uname"));
        }
        sender.setFansMedal(parseObjectFansMedal(meta.getJSONObject("fans_medal"), source));

        return new BilibiliLikeEvent(source, sender);
    }

    /**
     * 解析点赞数更新消息
     */
    private StarBotBaseLiveEvent parseLikeUpdate(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        return new BilibiliLikeUpdateEvent(source, meta.getInteger("click_count"));
    }

    /**
     * 解析看过人数更新消息
     */
    private StarBotBaseLiveEvent parseWatchedUpdate(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        return new BilibiliWatchedUpdateEvent(source, meta.getInteger("num"), meta.getString("text_large"));
    }

    /**
     * 解析高能用户数更新消息
     * <p>
     * {@code online_count} 与 {@code count_text} 只在部分版本的消息里出现，
     * 取不到时为空即可——这两项都只是展示用，缺了不影响 {@code count} 这个正主。
     */
    private StarBotBaseLiveEvent parseOnlineRankCount(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        return new BilibiliOnlineRankCountUpdateEvent(source,
                meta.getInteger("count"), meta.getInteger("online_count"), meta.getString("count_text"));
    }

    /**
     * 解析直播间标题与分区变更消息
     */
    private StarBotBaseLiveEvent parseRoomInfoChange(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = data.getJSONObject("data");
        if (meta == null) {
            return null;
        }

        return new BilibiliRoomInfoChangeEvent(source,
                meta.getString("title"),
                meta.getString("parent_area_name"),
                meta.getString("area_name"));
    }

    /**
     * 解析违规警告消息
     */
    private StarBotBaseLiveEvent parseWarning(JSONObject data, LiveStreamerInfo source) {
        return new BilibiliLiveWarningEvent(source, data.getString("msg"));
    }

    /**
     * 解析直播流被切断消息
     */
    private StarBotBaseLiveEvent parseCutOff(JSONObject data, LiveStreamerInfo source) {
        return new BilibiliCutOffEvent(source, data.getString("msg"));
    }

    /**
     * 解析直播间封禁消息
     * <p>
     * 该消息只给解封时刻、不给理由，与警告和切流的字段结构不同。
     */
    private StarBotBaseLiveEvent parseRoomLock(JSONObject data, LiveStreamerInfo source) {
        return new BilibiliRoomLockEvent(source, data.getString("msg"), parseShanghaiTime(data.getString("expire")));
    }

    /**
     * 解析接口以东八区本地时间给出的时刻
     * <p>
     * 形如 {@code 2019-06-30 03:57:04}，不带时区。<b>解析失败返回 null 而不是当前时刻</b>：
     * 用「现在」冒充解封时间会让告警声称直播间已经解封。
     * @param text 时间文本
     * @return 对应时刻，缺失或无法解析时为 null
     */
    private Instant parseShanghaiTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(text.strip().replace(' ', 'T'))
                    .atZone(ZoneId.of("Asia/Shanghai"))
                    .toInstant();
        } catch (Exception e) {
            log.debug("无法解析时间文本 {}", text);
            return null;
        }
    }

    /**
     * 从通用的 uinfo 结构构造发送者信息
     */
    private BilibiliUserInfo buildSenderFromUinfo(JSONObject uinfo, LiveStreamerInfo source) {
        if (uinfo == null) {
            return new BilibiliUserInfo();
        }

        Long uid = uinfo.getLong("uid");
        JSONObject base = uinfo.getJSONObject("base");

        String uname = base == null ? null : base.getString("name");
        String face = base == null ? null : base.getString("face");

        if (base == null && properties.getLive().isCompleteEvent() && uid != null) {
            uname = apiSupport.completeUname(uid, source).orElse(null);
            face = apiSupport.completeFace(uid, source).orElse(null);
        }

        BilibiliUserInfo sender = new BilibiliUserInfo(uid, uname, face);
        sender.setGuard(parseGuard(uinfo.getJSONObject("medal")));
        sender.setHonorLevel(Optional.ofNullable(uinfo.getJSONObject("wealth")).map(wealth -> wealth.getInteger("level")).orElse(null));

        return sender;
    }

    /**
     * 解析大航海信息
     */
    private Guard parseGuard(JSONObject medal) {
        if (medal == null) {
            return null;
        }

        Integer guardLevel = medal.getInteger("guard_level");
        if (guardLevel == null || guardLevel == 0) {
            return null;
        }

        return new Guard(guardLevel, medal.getString("guard_icon"));
    }

    /**
     * 解析弹幕消息中以定长数组形式给出的粉丝勋章
     */
    private FansMedal parseArrayFansMedal(JSONArray medal, LiveStreamerInfo source) {
        if (medal == null || medal.size() < 13) {
            return null;
        }

        Long uid = medal.getLong(12);
        return buildFansMedal(uid, medal.getString(2), medal.getLong(3), medal.getString(1), medal.getInteger(0), isOne(medal.getInteger(11)), source);
    }

    /**
     * 解析以 fans_medal 对象形式给出的粉丝勋章
     */
    private FansMedal parseObjectFansMedal(JSONObject medal, LiveStreamerInfo source) {
        if (medal == null) {
            return null;
        }

        Long uid = medal.getLong("target_id");
        if (uid == null || uid == 0L) {
            return null;
        }

        return buildFansMedal(uid, null, medal.getLong("anchor_roomid"), medal.getString("medal_name"),
                medal.getInteger("medal_level"), isOne(medal.getInteger("is_lighted")), source);
    }

    /**
     * 解析以 medal 对象形式给出的粉丝勋章
     */
    private FansMedal parseMedalFansMedal(JSONObject medal, LiveStreamerInfo source) {
        if (medal == null) {
            return null;
        }

        Long uid = medal.getLong("ruid");
        if (uid == null || uid == 0L) {
            return null;
        }

        return buildFansMedal(uid, null, null, medal.getString("name"), medal.getInteger("level"), isOne(medal.getInteger("is_light")), source);
    }

    /**
     * 构造粉丝勋章，按配置决定是否补全缺失的主播信息
     */
    private FansMedal buildFansMedal(Long uid, String uname, Long roomId, String name, Integer level, Boolean lighted, LiveStreamerInfo source) {
        if (!properties.getLive().isCompleteEvent()) {
            return new FansMedal(uid, uname, roomId, name, level, lighted);
        }

        return new FansMedal(
                uid,
                uname != null ? uname : apiSupport.completeUname(uid, source).orElse(null),
                roomId != null ? roomId : apiSupport.completeRoomId(uid, source).orElse(null),
                apiSupport.completeFace(uid, source).orElse(null),
                name, level, lighted
        );
    }

    /**
     * 将以千分之一元为单位的价格换算为元
     */
    private Double toYuan(Integer price) {
        return price == null ? null : price / PRICE_UNIT;
    }

    /**
     * 判断整数标志位是否为 1，兼容字段缺失的情况
     */
    private boolean isOne(Integer value) {
        return value != null && value == 1;
    }

    /**
     * 按下标安全取出子数组
     * <p>
     * 弹幕消息以定长数组承载各类信息，其长度会随版本变化，越界时直接返回空而非抛出异常。
     * @param array 数组
     * @param index 下标
     * @return 子数组，越界或类型不符时返回 null
     */
    private JSONArray arrayAt(JSONArray array, int index) {
        if (array == null || index < 0 || index >= array.size()) {
            return null;
        }

        return array.get(index) instanceof JSONArray nested ? nested : null;
    }
}
