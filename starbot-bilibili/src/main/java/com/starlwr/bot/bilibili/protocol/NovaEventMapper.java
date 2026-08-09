package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent;
import com.starlwr.bot.bilibili.model.BilibiliEmojiInfo;
import com.starlwr.bot.bilibili.model.BilibiliUserInfo;
import com.starlwr.bot.bilibili.model.FansMedal;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.event.live.base.StarBotLivePurchaseEvent;
import com.starlwr.bot.core.event.live.common.*;
import com.starlwr.bot.core.model.EmojiInfo;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;

import java.time.Instant;
import java.util.List;

/**
 * NovaBot 事件 → 事件输出协议 v1 的信封
 * <p>
 * 起点是 VRDash 仓库的 {@code club.vrdash.relay.ProtocolMapper}（同一作者、同为 AGPL-3.0），
 * 迁入后改包名并按 NovaBot 事件模型的实际字段作了调整，差异见文末「迁入时改了什么」。
 * <p>
 * <b>这个类是整条链路上最容易出社交事故的地方</b>：金额显示错了，
 * 主播会按 ¥1 的热情感谢一个花了 ¥100 的人。所以每条口径都在这里写明出处。
 *
 * <h2>四条铁律</h2>
 * <ol>
 *   <li><b>金额一律取 {@code charged ?? value}。</b> {@code charged} 是平台从购买者账上
 *       实际扣除的额度，打折体现在它身上；{@code value} 是「主播到手价值」，
 *       盲盒与背包礼物场景下两者<b>不是一回事</b>。取不到 {@code charged} 时回退 {@code value}，
 *       <b>绝不能当作 0</b>——把「不知道」记成「没扣钱」不会报错，是最难发现的那类 bug。</li>
 *   <li><b>{@code charged} 不是「观众掏了多少真钱」。</b> 扣的是账上余额，
 *       余额可能是白来的（抢红包 / 活动 / 签到）或以充值优惠价买来的。
 *       那个数在直播间的数据里根本不存在，所以协议与 UI 一律不叫它「实付」。</li>
 *   <li><b>免费判据用事件类型，不碰金额。</b> {@link FreeGiftEvent} 就是免费，
 *       别去看 {@code giftInfo.price}——银瓜子礼物那个字段会被按金瓜子的比例除以 1000，
 *       一个免费小心心会算成 ¥1.0。</li>
 *   <li><b>不做任何单位换算。</b> 事件里已经统一成元了，再除一次 1000
 *       会让所有金额小三个数量级。</li>
 * </ol>
 *
 * <h2>迁入时改了什么</h2>
 * <ul>
 *   <li><b>背包礼物改读字段。</b> 原实现从 {@code charged == 0 && value > 0} 反推，
 *       那是它写的时候字段还不存在。现在事件上有 {@code fromBag}，直接读——
 *       理由见 {@code StarBotLiveGiftEvent.fromBag} 的契约说明。</li>
 *   <li><b>补上表情弹幕。</b> 纯表情弹幕在我们这边是独立的 {@link EmojiEvent}，
 *       原实现没有处理它，会被整条丢掉。</li>
 *   <li><b>补上弹幕内联表情与 @回复。</b> 我们的 {@link BilibiliDanmuEvent}
 *       带 {@code emojis} 与 {@code reply}，原实现都填的 null。</li>
 * </ul>
 */
public final class NovaEventMapper {
    public static final int PROTOCOL_VERSION = 1;

    private NovaEventMapper() {
    }

    /**
     * 映射一条事件
     * @param event 事件
     * @return 协议信封，不认识的事件返回 {@code null}——协议只承载它列出的那些
     */
    public static JSONObject map(StarBotBaseLiveEvent event) {
        if (event instanceof RandomGiftEvent e) return blindBox(e);
        if (event instanceof FreeGiftEvent e) return freeGift(e);
        if (event instanceof PaidGiftEvent e) return paidGift(e);
        if (event instanceof SuperChatEvent e) return superChat(e);
        if (event instanceof MembershipEvent e) return guard(e);
        if (event instanceof EmojiEvent e) return emojiDanmaku(e);
        if (event instanceof DanmuEvent e) return danmaku(e);
        if (event instanceof EnterRoomEvent e) return envelope(e, e.getSender(), "enter", new JSONObject());
        if (event instanceof FollowEvent e) return envelope(e, e.getSender(), "follow", new JSONObject());
        if (event instanceof ShareEvent e) return envelope(e, e.getSender(), "share", new JSONObject());
        if (event instanceof LikeEvent e) return like(e);
        return null;
    }

    // ── 礼物 ────────────────────────────────────────────────────────────────

    /**
     * 普通付费礼物，<b>以及背包礼物</b>——不为背包礼物单开事件类型，
     * 它就是一条 {@code fromBag} 为真的付费礼物事件。
     * <p>
     * <b>判据是 {@code fromBag} 字段，不是金额。</b> 实测两条背包礼物的 {@code total_coin}
     * 给的都是礼物原价而不是 0（小花花、人气票），光看金额与普通付费礼物完全一致。
     * <p>
     * 注意 {@code charged == 0} 与 {@code charged == null} 必须分开：
     * 前者是「确实一分没扣」，后者是「平台没告诉我们」——后者要回退到 value（铁律一）。
     * 这也是这里不能直接用 {@link #chargedOf} 的原因。
     */
    private static JSONObject paidGift(PaidGiftEvent e) {
        Double charged = e.getCharged();
        double face = orZero(e.getValue());
        double rmb = charged != null ? charged : face;

        JSONObject data = giftBase(e.getGiftInfo(), rmb, face, true);
        data.put("blindBox", null);
        data.put("bagGift", e.isFromBag());
        return envelope(e, e.getSender(), "gift", data);
    }

    private static JSONObject freeGift(FreeGiftEvent e) {
        // 铁律三：免费就是免费，金额一律 0，不要去读 giftInfo.price
        JSONObject data = giftBase(e.getGiftInfo(), 0d, 0d, false);
        data.put("blindBox", null);
        data.put("bagGift", false);
        return envelope(e, e.getSender(), "gift", data);
    }

    /**
     * 盲盒。<b>这是整个映射里最容易搞反的一处。</b>
     * <p>
     * 字段名相当反直觉：{@code randomGiftInfo} 是<b>投入的盲盒</b>、
     * {@code giftInfo} 才是<b>开出的东西</b>；{@code price} 是盲盒实扣合计、
     * {@code value} 是开出面值合计。实抓印证过：投入「心动盲盒」¥15、
     * 爆出「爱心抱枕」面值 ¥16，而平台给的 {@code total_coin} 是 15。
     * <p>
     * 所以分级基数（协议的 {@code rmb}）必须取实扣——否则花 ¥10 爆出 ¥100 的
     * 会被按 ¥100 感谢，花 ¥100 爆出一堆小心心的会被按 ¥1 带过。
     */
    private static JSONObject blindBox(RandomGiftEvent e) {
        GiftInfo won = e.getGiftInfo();
        GiftInfo box = e.getRandomGiftInfo();
        double charged = chargedOf(e, e.getPrice());
        double face = orZero(e.getValue());

        JSONObject data = giftBase(won, charged, face, true);
        JSONObject bb = new JSONObject();
        bb.put("boxName", box == null ? null : box.getName());
        bb.put("boxRmb", charged);
        bb.put("wonGiftName", won == null ? null : won.getName());
        bb.put("wonRmb", face);
        data.put("blindBox", bb);
        data.put("bagGift", false);
        return envelope(e, e.getSender(), "gift", data);
    }

    private static JSONObject giftBase(GiftInfo g, double rmb, double faceRmb, boolean paid) {
        JSONObject data = new JSONObject();
        data.put("giftId", g == null || g.getId() == null ? 0 : g.getId());
        data.put("giftName", g == null ? "" : g.getName());
        data.put("num", g == null || g.getCount() == null ? 1 : g.getCount());
        data.put("paid", paid);
        data.put("rmb", rmb);
        data.put("faceRmb", faceRmb);
        data.put("icon", g == null ? null : g.getUrl());
        // 连击信息尚未从报文里解析出来。协议允许为 null，展示层会把每条当独立行处理；
        // 补上时换成真实的 combo.id / count / timeoutMs，**不要自造时间窗口**
        data.put("combo", null);
        return data;
    }

    // ── 弹幕 ────────────────────────────────────────────────────────────────

    /**
     * 普通弹幕，含内联表情与 @回复
     * <p>
     * {@code content} 保留表情占位、{@code contentText} 是剥掉表情的纯文本。
     * 协议的 {@code text} 取 {@code content}——展示层要按占位符还原表情图，
     * 给它剥干净的文本等于把信息提前丢掉。
     */
    private static JSONObject danmaku(DanmuEvent e) {
        JSONObject data = new JSONObject();
        data.put("text", e.getContent());
        data.put("emoji", inlineEmoji(e));
        data.put("replyTo", replyTo(e));
        // 弹幕报文的最外层没有 msg_id（实测 10 万条消息里只有醒目留言与 PK 状态类带），
        // 协议要求这个字段存在，故给空串而不是漏字段。**下游据此去重会失效**，
        // 需要去重时应改用 (uid, ts, text) 这类语义键
        data.put("msgId", "");
        return envelope(e, e.getSender(), "danmaku", data);
    }

    /**
     * 纯表情弹幕
     * <p>
     * 它在事件模型里是独立类型而不是带表情的弹幕，所以单独映射。
     * 协议侧统一成 {@code danmaku} + 非空 {@code emoji}，展示层不必区分两种来源。
     */
    private static JSONObject emojiDanmaku(EmojiEvent e) {
        EmojiInfo emoji = e.getEmoji();
        JSONObject data = new JSONObject();
        data.put("text", emoji == null ? "" : emoji.getName());
        data.put("emoji", emojiOf(emoji));
        data.put("replyTo", null);
        data.put("msgId", "");
        return envelope(e, e.getSender(), "danmaku", data);
    }

    /**
     * 内联表情。协议的 {@code emoji} 是单个对象，而一条弹幕可能内联多个，
     * 这里取第一个——协议本意是「这条弹幕是不是表情」，多个的完整信息在 {@code text} 的占位符里
     */
    private static JSONObject inlineEmoji(DanmuEvent e) {
        if (!(e instanceof BilibiliDanmuEvent b)) {
            return null;
        }
        List<BilibiliEmojiInfo> emojis = b.getEmojis();
        return emojis == null || emojis.isEmpty() ? null : emojiOf(emojis.get(0));
    }

    private static JSONObject emojiOf(EmojiInfo emoji) {
        if (emoji == null || emoji.getUrl() == null) {
            return null;
        }
        JSONObject j = new JSONObject();
        j.put("url", emoji.getUrl());
        j.put("w", emoji instanceof BilibiliEmojiInfo b && b.getWidth() != null ? b.getWidth() : 0);
        j.put("h", emoji instanceof BilibiliEmojiInfo b && b.getHeight() != null ? b.getHeight() : 0);
        return j;
    }

    private static JSONObject replyTo(DanmuEvent e) {
        if (!(e instanceof BilibiliDanmuEvent b) || b.getReply() == null) {
            return null;
        }
        UserInfo r = b.getReply();
        JSONObject j = new JSONObject();
        j.put("uid", r.getUid() == null ? "0" : String.valueOf(r.getUid()));
        j.put("name", r.getUname() == null ? "" : r.getUname());
        return j;
    }

    // ── 其余事件 ────────────────────────────────────────────────────────────

    /**
     * 醒目留言
     * <p>
     * <b>起止时间统一换算成毫秒。</b> 平台给的是秒级时间戳，而协议里其他所有时间
     * （信封的 {@code ts}、{@code live_state.startTs}）都是毫秒。
     * 让 SC 独自用秒，下游只要有一处忘了换算就会把 2026 年算成 1970 年。
     * <p>
     * <b>取不到时给 0，含义是「平台没给这一项」。</b> 协议把这四项声明为必填的 number，
     * 不能省略；而 0 在这里不可能是合法值——2026-08-07 的实测样本里
     * {@code time} 是 60、{@code start_time} 与 {@code end_time} 是十位秒级时间戳、
     * {@code id} 是八位编号，都远大于 0。
     */
    private static JSONObject superChat(SuperChatEvent e) {
        JSONObject data = new JSONObject();
        data.put("text", e.getContent());
        data.put("rmb", chargedOf(e));
        data.put("durationSec", e.getDurationSec() == null ? 0 : e.getDurationSec());
        data.put("messageId", e.getMessageId() == null ? 0L : e.getMessageId());
        data.put("startTs", millisOf(e.getStartTime()));
        data.put("endTs", millisOf(e.getEndTime()));
        return envelope(e, e.getSender(), "superchat", data);
    }

    /**
     * 时刻转毫秒
     * @param instant 时刻，可空
     * @return 毫秒时间戳，取不到时为 0
     */
    private static long millisOf(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    /**
     * 大航海
     * <p>
     * <b>金额是实际成交价，但有约 18% 拿不到。</b> {@code GUARD_BUY} 给的是挂牌价
     * （35 个样本里舰长恒为 198000），实际成交价只有 toast 才有。等不到 toast 的那部分
     * 只有挂牌价可用，系统性偏高 15.4%。协议层区分不了两种来源，
     * <b>所以这个数不能拿去对账</b>。
     * <p>
     * <b>陪伴天数取不到时必须留空。</b> 它是从播报文案正则解析的，文案改版就会取不到。
     * 解析不出时事件上就是 null，这里原样透传——<b>绝不要补一个 0</b>，
     * 「陪伴 0 天」会作为假信息出现在主播眼前。
     */
    private static JSONObject guard(MembershipEvent e) {
        JSONObject data = new JSONObject();
        int level = guardLevelOf(e.getSender());
        data.put("level", level == 0 ? 3 : level);
        data.put("levelName", guardName(level));
        data.put("num", e.getCount() == null ? 1 : e.getCount());
        // 单位原样透传。**不要假定是「月」**——短期赠送的舰长会是「天」
        data.put("unit", e.getUnit());
        data.put("rmb", chargedOf(e));
        data.put("companionDays", e.getCompanionDays());
        return envelope(e, e.getSender(), "guard", data);
    }

    /**
     * 点赞
     * <p>
     * <b>【未能确认】</b>事件上没有次数字段，恒为 1。协议注释说「源侧已按单用户短窗口聚合」，
     * 我们目前没有做这个聚合，所以下游收到的是逐次事件而不是聚合结果。
     */
    private static JSONObject like(LikeEvent e) {
        JSONObject data = new JSONObject();
        data.put("count", 1);
        return envelope(e, e.getSender(), "like", data);
    }

    // ── 金额 ────────────────────────────────────────────────────────────────

    /** 铁律一：{@code charged ?? value}，取不到时回退而不是当 0 */
    private static double chargedOf(StarBotLivePurchaseEvent e) {
        return chargedOf(e, e.getValue());
    }

    private static double chargedOf(StarBotLivePurchaseEvent e, Double fallback) {
        Double charged = e.getCharged();
        return charged != null ? charged : orZero(fallback);
    }

    private static double orZero(Double d) {
        return d == null ? 0d : d;
    }

    // ── 房间级消息 ──────────────────────────────────────────────────────────

    /**
     * 开播状态
     * <p>
     * <b>{@code title} 取自我们记住的最近一次房间信息</b>，而不是这条事件本身——
     * 开播消息里没有标题。刚接上还没收到过标题变更时只能给空串，
     * 这一点在 {@code hello.notes} 里对下游讲明。
     * @param room 房间号
     * @param ts 事件时刻，毫秒
     * @param live 是否在播
     * @param title 直播间标题，未知时给空串
     * @param startTs 开播时刻，毫秒，未在播或未知时为空
     * @return 信封
     */
    public static JSONObject liveState(long room, long ts, boolean live, String title, Long startTs) {
        JSONObject data = new JSONObject();
        data.put("live", live);
        data.put("title", title == null ? "" : title);
        data.put("startTs", startTs);
        return roomEnvelope(room, ts, "live_state", data);
    }

    /**
     * 采集侧的连接状态
     * @param room 房间号
     * @param ts 事件时刻，毫秒
     * @param state {@code connected} / {@code reconnecting} / {@code disconnected}
     * @return 信封
     */
    public static JSONObject sourceState(long room, long ts, String state) {
        JSONObject data = new JSONObject();
        data.put("state", state);
        return roomEnvelope(room, ts, "source_state", data);
    }

    /**
     * 房间统计
     * <p>
     * <b>取不到的项要整个不放，而不是放 null。</b> 协议把这三项声明成可选字段
     * （{@code watched?}）而非可空字段，一个显式的 null 在按协议生成的校验器眼里是类型错误。
     * @param room 房间号
     * @param ts 事件时刻，毫秒
     * @param watched 累计看过人数，取不到时为空
     * @param online 高能用户数，取不到时为空
     * @param likeTotal 本场点赞总数，取不到时为空
     * @return 信封，三项全空时返回 {@code null}——协议规定此时不推送
     */
    public static JSONObject roomStat(long room, long ts, Integer watched, Integer online, Integer likeTotal) {
        if (watched == null && online == null && likeTotal == null) {
            return null;
        }

        JSONObject data = new JSONObject();
        if (watched != null) {
            data.put("watched", watched);
        }
        if (online != null) {
            data.put("online", online);
        }
        if (likeTotal != null) {
            data.put("likeTotal", likeTotal);
        }
        return roomEnvelope(room, ts, "room_stat", data);
    }

    // ── 信封与用户 ──────────────────────────────────────────────────────────

    /**
     * 组装房间级信封，不带 {@code user}
     * <p>
     * 协议要求「解析时必须先按 kind 分派再取字段，不能假设 user 一定存在」，
     * 所以这里<b>不能</b>为了统一而补一个空的 user 上去。
     */
    private static JSONObject roomEnvelope(long room, long ts, String kind, JSONObject data) {
        JSONObject env = new JSONObject();
        env.put("v", PROTOCOL_VERSION);
        env.put("ts", ts);
        env.put("room", room);
        env.put("kind", kind);
        env.put("data", data);
        return env;
    }

    /**
     * 组装用户级信封
     * <p>
     * {@code seq} 由服务端统一编号后补上——它必须在单条连接内严格递增，映射器不管。
     * <p>
     * sender 由调用方传进来而不是从事件上取：进房 / 关注 / 分享走的是操作事件那一支，
     * 和礼物那支没有共同的「带 sender」父类。
     */
    private static JSONObject envelope(StarBotBaseLiveEvent e, UserInfo sender, String kind, JSONObject data) {
        LiveStreamerInfo source = e.getSource();
        JSONObject env = new JSONObject();
        env.put("v", PROTOCOL_VERSION);
        env.put("ts", e.getTimestamp());
        env.put("room", source == null || source.getRoomId() == null ? 0 : source.getRoomId());
        env.put("kind", kind);
        env.put("user", user(sender, source == null ? null : source.getUid()));
        env.put("data", data);
        return env;
    }

    /**
     * 组装用户
     * @param u 用户，为空时给一份全默认值——协议要求这个对象存在
     * @param anchorUid 本房间主播的 uid，用于判定发送者是不是主播本人
     */
    private static JSONObject user(UserInfo u, Long anchorUid) {
        JSONObject j = new JSONObject();
        if (u == null) {
            j.put("uid", "0");
            j.put("idKind", "uid");
            j.put("name", "");
            j.put("face", null);
            j.put("guardLevel", 0);
            j.put("isAdmin", false);
            j.put("isAnchor", false);
            j.put("medal", null);
            return j;
        }
        j.put("uid", u.getUid() == null ? "0" : String.valueOf(u.getUid()));
        // 扫码登录路径拿到的是真实 uid；开放平台源才是 open_id。目前只有前者
        j.put("idKind", "uid");
        j.put("name", u.getUname() == null ? "" : u.getUname());
        j.put("face", u.getFace());
        j.put("guardLevel", guardLevelOf(u));
        j.put("isAdmin", isAdmin(u));
        j.put("isAnchor", isAnchor(u, anchorUid));
        j.put("medal", medal(u));
        return j;
    }

    /**
     * 房管标志
     * <p>
     * <b>只有弹幕消息带这个标志</b>（报文的 {@code info[2][2]}），礼物、上舰、进房这些
     * 消息的报文里根本没有它。所以这里的 false 在多数消息上是「这条消息没说」，
     * 而不是「此人确认不是房管」——<b>下游不要拿它做权限判断</b>，
     * 更不要因为同一个人在礼物消息上是 false 就撤销他在弹幕上显示的房管标识。
     */
    private static boolean isAdmin(UserInfo u) {
        return u instanceof BilibiliUserInfo b && Boolean.TRUE.equals(b.getRoomAdmin());
    }

    /**
     * 主播本人
     * <p>
     * 拿发送者 uid 与房间主播 uid 相比，不依赖报文里的任何标志位。
     * <p>
     * <b>匿名连接的弹幕上恒为 false</b>：平台对未登录连接把弹幕的发送者 uid 抹成 0、
     * 昵称只留首字（形如 {@code b***}），此时谁都对不上主播 uid。这是「比不了」而非「不是」。
     * <p>
     * 抹除是<b>按消息类型来的，不是整条连接一刀切</b>：2026-08-09 实测同一条匿名连接上，
     * 弹幕的 uid 全为 0，点赞消息却带着完整 uid 与完整昵称。所以别把「匿名源」
     * 整体当作「没有 uid」——要按 kind 分别看。
     */
    private static boolean isAnchor(UserInfo u, Long anchorUid) {
        Long uid = u.getUid();
        return uid != null && uid != 0L && anchorUid != null && uid.equals(anchorUid);
    }

    private static JSONObject medal(UserInfo u) {
        if (!(u instanceof BilibiliUserInfo b) || b.getFansMedal() == null) {
            return null;
        }
        FansMedal m = b.getFansMedal();
        JSONObject j = new JSONObject();
        j.put("name", m.getName());
        j.put("level", m.getLevel() == null ? 0 : m.getLevel());
        j.put("isLighted", Boolean.TRUE.equals(m.getLighted()));
        return j;
    }

    /** 0 无 / 1 总督 / 2 提督 / 3 舰长。核心的 UserInfo 没有这个字段，只能从子类取 */
    private static int guardLevelOf(UserInfo u) {
        if (!(u instanceof BilibiliUserInfo b) || b.getGuard() == null
                || b.getGuard().getGuardType() == null) {
            return 0;
        }
        int code = b.getGuard().getGuardType().getCode();
        return code < 1 || code > 3 ? 0 : code;
    }

    private static String guardName(int level) {
        return switch (level) {
            case 1 -> "总督";
            case 2 -> "提督";
            default -> "舰长";
        };
    }
}
