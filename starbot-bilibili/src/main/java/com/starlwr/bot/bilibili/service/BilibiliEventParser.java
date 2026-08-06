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
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

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

    private final StarBotBilibiliProperties properties;

    private final BilibiliGiftService giftService;

    private final BilibiliApiSupport apiSupport;

    /**
     * 消息类型到解析方法的映射
     */
    private final Map<String, BiFunction<JSONObject, LiveStreamerInfo, StarBotBaseLiveEvent>> parsers = new HashMap<>();

    @Autowired
    public BilibiliEventParser(StarBotBilibiliProperties properties, BilibiliGiftService giftService, BilibiliApiSupport apiSupport) {
        this.properties = properties;
        this.giftService = giftService;
        this.apiSupport = apiSupport;

        parsers.put("LIVE", this::parseLiveOn);
        parsers.put("PREPARING", this::parseLiveOff);
        parsers.put("DANMU_MSG", this::parseMessage);
        parsers.put("INTERACT_WORD", this::parseInteract);
        parsers.put("SEND_GIFT", this::parseGift);
        parsers.put("SUPER_CHAT_MESSAGE", this::parseSuperChat);
        parsers.put("USER_TOAST_MSG", this::parseGuard);
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
            return Optional.ofNullable(parser.apply(data, source));
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
            event.setPaid(paidOf(meta, value));
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
        // 盲盒的实付就是盲盒本身的价，与 total_coin 应当一致。以 total_coin 为准并在不一致时留下日志——
        // 盲盒尚未拿到过真实报文，这行日志就是将来真有一个盲盒送进来时的证据
        event.setPaid(paidOf(meta, price));
        return event;
    }

    /**
     * 取观众为这一笔实际付出的金额
     * <p>
     * {@code total_coin} 是<b>服务端给出的实际扣除额</b>，比自己拿单价乘数量更可靠：
     * 打折、背包礼物这些情形都体现在它身上，而单价字段体现不出来。
     * 已用一次真实的 ¥0.1 礼物核对过 {@code total_coin == discount_price × num}。
     * <p>
     * 字段缺失时回退到调用方算出的金额，<b>而不是当作 0</b>——
     * 把「取不到」记成「没花钱」会让营收凭空少一截，且不会有任何报错。
     * @param meta 礼物消息内容
     * @param fallback 字段缺失时的回退值
     * @return 实付金额（元）
     */
    private Double paidOf(JSONObject meta, Double fallback) {
        Integer totalCoin = meta.getInteger("total_coin");
        if (totalCoin == null) {
            return fallback;
        }

        double paid = totalCoin / PRICE_UNIT;
        if (fallback != null && Math.abs(paid - fallback) > 0.001) {
            log.debug("礼物 {} 的实付 {} 与按单价算出的 {} 不一致, 以实付为准",
                    meta.getString("giftName"), paid, fallback);
        }
        return paid;
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

        return new BilibiliSuperChatEvent(source, sender, meta.getString("message"), meta.getDouble("price"), timestamp);
    }

    /**
     * 解析大航海消息
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
        boolean complete = properties.getLive().isCompleteEvent();

        BilibiliUserInfo sender = new BilibiliUserInfo(
                senderUid,
                meta.getString("username"),
                complete ? apiSupport.completeFace(senderUid, source).orElse(null) : null
        );
        sender.setGuard(new Guard(guardLevel, complete ? giftService.getGuardIcon(meta.getString("role_name")).orElse(null) : null));

        GuardOperateType operateType = GuardOperateType.of(Optional.ofNullable(meta.getInteger("op_type")).orElse(-1));
        Double price = toYuan(meta.getInteger("price"));
        Integer count = meta.getInteger("num");
        String unit = meta.getString("unit");
        Instant timestamp = Optional.ofNullable(data.getLong("send_time")).map(Instant::ofEpochMilli).orElseGet(Instant::now);

        return switch (guardLevel) {
            case 1 -> {
                BilibiliGovernorEvent event = new BilibiliGovernorEvent(source, sender, price, count, unit, timestamp);
                event.setOperateType(operateType);
                yield event;
            }
            case 2 -> {
                BilibiliCommanderEvent event = new BilibiliCommanderEvent(source, sender, price, count, unit, timestamp);
                event.setOperateType(operateType);
                yield event;
            }
            case 3 -> {
                BilibiliCaptainEvent event = new BilibiliCaptainEvent(source, sender, price, count, unit, timestamp);
                event.setOperateType(operateType);
                yield event;
            }
            default -> {
                log.debug("未处理的直播间大航海类型: {}", guardLevel);
                yield null;
            }
        };
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
