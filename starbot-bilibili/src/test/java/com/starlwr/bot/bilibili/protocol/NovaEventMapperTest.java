package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliEmojiEvent;
import com.starlwr.bot.bilibili.model.BilibiliEmojiInfo;
import com.starlwr.bot.bilibili.model.BilibiliUserInfo;
import com.starlwr.bot.core.event.live.common.FreeGiftEvent;
import com.starlwr.bot.core.event.live.common.MembershipEvent;
import com.starlwr.bot.core.event.live.common.PaidGiftEvent;
import com.starlwr.bot.core.event.live.common.RandomGiftEvent;
import com.starlwr.bot.core.event.live.common.SuperChatEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件 → 协议信封的映射测试
 * <p>
 * 前九条迁自 VRDash 仓库的 {@code ProtocolMapperTest}，每一条都对应一次真实的社交事故风险：
 * 金额显示错了，主播会按 ¥1 的热情感谢一个花了 ¥100 的人。
 * <p>
 * 后面几条是迁入时按我们的事件模型补的：背包标志改读字段、表情弹幕、内联表情与 @回复。
 */
@DisplayName("事件映射到协议信封")
class NovaEventMapperTest {
    private static final String PLATFORM = "bilibili";

    private static LiveStreamerInfo room() {
        LiveStreamerInfo s = new LiveStreamerInfo();
        s.setRoomId(10000L);
        return s;
    }

    private static UserInfo sender() {
        UserInfo u = new UserInfo();
        u.setUid(10000007L);
        u.setUname("观众");
        return u;
    }

    private static GiftInfo gift(long id, String name, double price, int count) {
        return new GiftInfo(id, name, price, count, null);
    }

    // ── 迁自 VRDash 的九条金额口径回归 ──────────────────────────────────────

    @Test
    @DisplayName("普通礼物：金额取 charged")
    void paidGift() {
        PaidGiftEvent e = new PaidGiftEvent(PLATFORM, room(), sender(), gift(1L, "辣条", 1.0, 3), 3.0);
        e.setCharged(3.0);

        JSONObject env = NovaEventMapper.map(e);
        assertNotNull(env);
        JSONObject d = env.getJSONObject("data");
        assertEquals("gift", env.getString("kind"));
        assertEquals(3.0, d.getDoubleValue("rmb"));
        assertTrue(d.getBooleanValue("paid"));
        assertNull(d.get("blindBox"));
    }

    @Test
    @DisplayName("charged 为空时回退到 value，而不是当作 0")
    void fallsBackToValueNotZero() {
        // 把「不知道」记成「没扣钱」会让金额凭空少一截，而且不会有任何报错
        PaidGiftEvent e = new PaidGiftEvent(PLATFORM, room(), sender(), gift(1L, "辣条", 1.0, 3), 3.0);

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertEquals(3.0, d.getDoubleValue("rmb"), "回退到 value，不能是 0");
        assertFalse(d.getBooleanValue("bagGift"), "回退不等于背包礼物");
    }

    @Test
    @DisplayName("免费礼物：判据是事件类型，不看金额")
    void freeGiftIsFreeRegardlessOfAmount() {
        // 银瓜子礼物的 giftInfo.price 会被按金瓜子的比例除以 1000，
        // 一个免费小心心的 price 是 1.0。这里故意给非零单价，断言仍然是 paid=false、rmb=0
        FreeGiftEvent e = new FreeGiftEvent(PLATFORM, room(), sender(), gift(2L, "小心心", 1.0, 5));

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertFalse(d.getBooleanValue("paid"));
        assertEquals(0.0, d.getDoubleValue("rmb"));
        assertEquals(5, d.getIntValue("num"));
    }

    @Test
    @DisplayName("⚠️ 盲盒：rmb 取实扣，不取爆出面值")
    void blindBoxUsesPaidNotFaceValue() {
        // 实抓报文：投入「心动盲盒」¥15，爆出「爱心抱枕」面值 ¥16，平台给的 total_coin = 15
        GiftInfo box = gift(32251L, "心动盲盒", 15.0, 1);
        GiftInfo won = gift(32128L, "爱心抱枕", 16.0, 1);
        RandomGiftEvent e = new RandomGiftEvent(PLATFORM, room(), sender(), box, won, 15.0, 16.0);
        e.setCharged(15.0);

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertEquals(15.0, d.getDoubleValue("rmb"), "分级基数必须是实扣");
        assertEquals(16.0, d.getDoubleValue("faceRmb"), "面值另存，用于两级显示");

        JSONObject bb = d.getJSONObject("blindBox");
        assertEquals("心动盲盒", bb.getString("boxName"));
        assertEquals(15.0, bb.getDoubleValue("boxRmb"));
        assertEquals("爱心抱枕", bb.getString("wonGiftName"));
        assertEquals(16.0, bb.getDoubleValue("wonRmb"));
    }

    @Test
    @DisplayName("⚠️ 花小钱爆出大奖，不能按面值分级")
    void cheapBoxBigWinStaysCheap() {
        // 花 ¥10 爆出 ¥500：按 value 分级会升档，主播按 ¥500 的热情感谢就错位了
        RandomGiftEvent e = new RandomGiftEvent(PLATFORM, room(), sender(),
                gift(1L, "盲盒", 10.0, 1), gift(2L, "嘉年华", 500.0, 1), 10.0, 500.0);
        e.setCharged(10.0);

        assertEquals(10.0, NovaEventMapper.map(e).getJSONObject("data").getDoubleValue("rmb"));
    }

    @Test
    @DisplayName("⚠️ 花大钱爆出一堆小心心，也不能按面值分级")
    void expensiveBoxSmallWinStaysExpensive() {
        // 花 ¥100 爆出 ¥1：按 value 分级会掉档、主播随口带过，那是很伤人的
        RandomGiftEvent e = new RandomGiftEvent(PLATFORM, room(), sender(),
                gift(1L, "盲盒", 100.0, 1), gift(2L, "小心心", 1.0, 1), 100.0, 1.0);
        e.setCharged(100.0);

        assertEquals(100.0, NovaEventMapper.map(e).getJSONObject("data").getDoubleValue("rmb"));
    }

    @Test
    @DisplayName("大航海：陪伴天数原样透传")
    void guardCompanionDays() {
        MembershipEvent e = new MembershipEvent(PLATFORM, room(), sender(), 138.0, 1, "月");
        e.setCharged(138.0);
        e.setCompanionDays(1171);

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertEquals(1171, d.getIntValue("companionDays"));
        assertEquals(138.0, d.getDoubleValue("rmb"));
        assertEquals("月", d.getString("unit"));
    }

    @Test
    @DisplayName("⚠️ 陪伴天数解析不出时留空，绝不补 0")
    void guardCompanionDaysStaysNull() {
        // 它是从播报文案正则解析的，文案改版就取不到。「陪伴 0 天」是一句假话，
        // 会被主播当真念出来——比没有这个信息糟得多
        MembershipEvent e = new MembershipEvent(PLATFORM, room(), sender(), 138.0, 1, "月");
        e.setCharged(138.0);

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertNull(d.get("companionDays"));
        assertEquals(0, d.getIntValue("companionDays"),
                "getIntValue 会把 null 读成 0——正因如此才不能靠它判空");
    }

    @Test
    @DisplayName("大航海单位原样透传，不假定是「月」")
    void guardUnitPassthrough() {
        MembershipEvent e = new MembershipEvent(PLATFORM, room(), sender(), 6.0, 7, "天");
        e.setCharged(6.0);

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertEquals("天", d.getString("unit"));
        assertEquals(7, d.getIntValue("num"));
    }

    @Test
    @DisplayName("信封结构符合协议")
    void envelopeShape() {
        PaidGiftEvent e = new PaidGiftEvent(PLATFORM, room(), sender(), gift(1L, "辣条", 1.0, 1), 1.0);
        e.setCharged(1.0);

        JSONObject env = NovaEventMapper.map(e);
        assertEquals(1, env.getIntValue("v"));
        assertEquals(10000L, env.getLongValue("room"));
        assertTrue(env.getLongValue("ts") > 0);

        JSONObject u = env.getJSONObject("user");
        assertEquals("10000007", u.getString("uid"), "uid 是字符串，开放平台源时会是 open_id");
        assertEquals("uid", u.getString("idKind"));
        assertEquals(0, u.getIntValue("guardLevel"));
        assertNull(u.get("medal"));

        assertNull(env.get("seq"), "seq 由服务端补，映射器不管");
    }

    // ── 迁入时按我们的事件模型补的 ─────────────────────────────────────────

    @Test
    @DisplayName("⚠️ 背包礼物读 fromBag 字段，不从金额反推")
    void bagGiftReadsFieldNotAmount() {
        PaidGiftEvent e = new PaidGiftEvent(PLATFORM, room(), sender(), gift(3L, "小花花", 0.1, 1), 0.1);
        e.setCharged(0.0);
        e.setFromBag(true);

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertTrue(d.getBooleanValue("bagGift"));
        assertEquals(0.0, d.getDoubleValue("rmb"), "观众这一笔一分没扣");
        assertEquals(0.1, d.getDoubleValue("faceRmb"), "但主播收到了这份价值");
        assertTrue(d.getBooleanValue("paid"), "它仍是金瓜子礼物，不是银瓜子的免费道具");
    }

    @Test
    @DisplayName("⚠️ 实扣为 0 但不是背包礼物时，bagGift 必须为假")
    void zeroChargedAloneIsNotBagGift() {
        // 迁入前的实现从「charged==0 且 value>0」反推，这条就是那段旧逻辑的反例。
        // charged==0 目前恰好只有背包一种来源，但那是当下的巧合而不是约定
        PaidGiftEvent e = new PaidGiftEvent(PLATFORM, room(), sender(), gift(1L, "辣条", 1.0, 3), 3.0);
        e.setCharged(0.0);

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertFalse(d.getBooleanValue("bagGift"), "没有 fromBag 标志就不是背包礼物");
        assertEquals(0.0, d.getDoubleValue("rmb"));
    }

    @Test
    @DisplayName("⚠️ charged 为 0 与 charged 为空必须分开")
    void zeroIsNotUnknown() {
        PaidGiftEvent unknown = new PaidGiftEvent(PLATFORM, room(), sender(), gift(1L, "辣条", 1.0, 3), 3.0);
        JSONObject u = NovaEventMapper.map(unknown).getJSONObject("data");
        assertEquals(3.0, u.getDoubleValue("rmb"), "不知道要回退到面值");

        PaidGiftEvent bag = new PaidGiftEvent(PLATFORM, room(), sender(), gift(1L, "辣条", 1.0, 3), 3.0);
        bag.setCharged(0.0);
        bag.setFromBag(true);
        JSONObject b = NovaEventMapper.map(bag).getJSONObject("data");
        assertEquals(0.0, b.getDoubleValue("rmb"), "确实没扣就是 0");
    }

    @Test
    @DisplayName("纯表情弹幕映射成带 emoji 的弹幕，不再被整条丢掉")
    void emojiDanmakuIsMapped() {
        BilibiliEmojiInfo emoji = new BilibiliEmojiInfo("id_1", "[打call]", "https://x/e.png", 60, 60, 1);
        BilibiliEmojiEvent e = new BilibiliEmojiEvent(room(), sender(), emoji, java.time.Instant.now());

        JSONObject env = NovaEventMapper.map(e);
        assertNotNull(env, "迁入前的实现不认识这个事件类型，会返回 null");
        assertEquals("danmaku", env.getString("kind"));

        JSONObject d = env.getJSONObject("data");
        assertEquals("[打call]", d.getString("text"));
        JSONObject j = d.getJSONObject("emoji");
        assertEquals("https://x/e.png", j.getString("url"));
        assertEquals(60, j.getIntValue("w"));
        assertEquals(60, j.getIntValue("h"));
    }

    @Test
    @DisplayName("弹幕的内联表情与 @回复照实映射")
    void danmakuCarriesEmojiAndReply() {
        UserInfo replied = new UserInfo();
        replied.setUid(555L);
        replied.setUname("被回复的人");

        BilibiliDanmuEvent e = new BilibiliDanmuEvent(room(), sender(), "牛[dog]", "牛", java.time.Instant.now());
        e.setEmojis(List.of(new BilibiliEmojiInfo("id_2", "[dog]", "https://x/d.png", 20, 20, 1)));
        e.setReply(replied);

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertEquals("牛[dog]", d.getString("text"), "text 取保留占位符的 content，不是剥干净的纯文本");
        assertEquals("https://x/d.png", d.getJSONObject("emoji").getString("url"));
        assertEquals("555", d.getJSONObject("replyTo").getString("uid"));
        assertEquals("被回复的人", d.getJSONObject("replyTo").getString("name"));
    }

    @Test
    @DisplayName("没有表情与回复时两个字段为 null，不是空对象")
    void plainDanmakuHasNulls() {
        BilibiliDanmuEvent e = new BilibiliDanmuEvent(room(), sender(), "普通弹幕", "普通弹幕", java.time.Instant.now());

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertNull(d.get("emoji"));
        assertNull(d.get("replyTo"));
    }

    @Test
    @DisplayName("不认识的事件返回 null，协议只承载它列出的那些")
    void unknownEventReturnsNull() {
        assertNull(NovaEventMapper.map(new com.starlwr.bot.core.event.live.common.WatchedUpdateEvent(
                PLATFORM, room(), 100, "100人看过")));
    }

    // ── 醒目留言的四个字段 ──────────────────────────────────────────────────

    @Test
    @DisplayName("醒目留言：时长、id 与起止时间照实映射，时间统一为毫秒")
    void superChatFields() {
        SuperChatEvent e = new SuperChatEvent(PLATFORM, room(), sender(), "今天好可爱喵", 30.0);
        e.setCharged(30.0);
        e.setDurationSec(60);
        e.setMessageId(18106982L);
        e.setStartTime(Instant.ofEpochSecond(1786111565L));
        e.setEndTime(Instant.ofEpochSecond(1786111625L));

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertEquals(30.0, d.getDoubleValue("rmb"));
        assertEquals(60, d.getIntValue("durationSec"));
        assertEquals(18106982L, d.getLongValue("messageId"));
        // 平台给的是秒，协议里其余所有时间都是毫秒。少换算一处就会把 2026 年算成 1970 年
        assertEquals(1786111565000L, d.getLongValue("startTs"));
        assertEquals(1786111625000L, d.getLongValue("endTs"));
    }

    @Test
    @DisplayName("醒目留言：字段取不到时给 0，协议要求这四项必须存在")
    void superChatFieldsFallBackToZero() {
        SuperChatEvent e = new SuperChatEvent(PLATFORM, room(), sender(), "留言", 30.0);

        JSONObject d = NovaEventMapper.map(e).getJSONObject("data");
        assertEquals(0, d.getIntValue("durationSec"));
        assertEquals(0L, d.getLongValue("messageId"));
        assertEquals(0L, d.getLongValue("startTs"));
        assertEquals(0L, d.getLongValue("endTs"));
    }

    // ── 房管与主播标志 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("房管标志取自报文")
    void adminFlagComesFromPayload() {
        BilibiliUserInfo admin = new BilibiliUserInfo(10000007L, "房管");
        admin.setRoomAdmin(true);

        BilibiliDanmuEvent e = new BilibiliDanmuEvent(room(), admin, "别刷屏", "别刷屏", Instant.now());
        assertTrue(NovaEventMapper.map(e).getJSONObject("user").getBooleanValue("isAdmin"));
    }

    @Test
    @DisplayName("⚠️ 报文没说房管时为 false，含义是「这条消息没说」而不是「不是房管」")
    void adminFlagAbsentIsFalse() {
        BilibiliUserInfo unknown = new BilibiliUserInfo(10000007L, "观众");

        BilibiliDanmuEvent e = new BilibiliDanmuEvent(room(), unknown, "666", "666", Instant.now());
        assertFalse(NovaEventMapper.map(e).getJSONObject("user").getBooleanValue("isAdmin"));
    }

    @Test
    @DisplayName("主播判定靠 uid 相等，不靠报文里的标志位")
    void anchorFlagMatchesRoomUid() {
        LiveStreamerInfo source = room();
        source.setUid(500000006L);

        UserInfo anchor = new UserInfo();
        anchor.setUid(500000006L);
        anchor.setUname("主播");

        BilibiliDanmuEvent e = new BilibiliDanmuEvent(source, anchor, "谢谢大家", "谢谢大家", Instant.now());
        assertTrue(NovaEventMapper.map(e).getJSONObject("user").getBooleanValue("isAnchor"));
    }

    @Test
    @DisplayName("⚠️ 匿名连接下 uid 被抹成 0，此时不能把观众判成主播")
    void anonymousUidIsNeverAnchor() {
        LiveStreamerInfo source = room();
        // 平台对未登录连接把 uid 一律抹成 0（2026-08-07 实测），
        // 两个 0 一比就相等，观众会被整片判成主播
        source.setUid(0L);

        UserInfo masked = new UserInfo();
        masked.setUid(0L);
        masked.setUname("b***");

        BilibiliDanmuEvent e = new BilibiliDanmuEvent(source, masked, "666", "666", Instant.now());
        assertFalse(NovaEventMapper.map(e).getJSONObject("user").getBooleanValue("isAnchor"));
    }

    // ── 房间级消息 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("房间级消息不带 user")
    void roomMessagesHaveNoUser() {
        JSONObject live = NovaEventMapper.liveState(10000L, 1786111565000L, true, "标题", 1786111000000L);
        JSONObject source = NovaEventMapper.sourceState(10000L, 1786111565000L, "connected");

        // 协议要求「解析时必须先按 kind 分派再取字段，不能假设 user 一定存在」，
        // 为了统一而补一个空 user 会让下游把它当成一位 uid 为 0 的观众
        assertFalse(live.containsKey("user"));
        assertFalse(source.containsKey("user"));
        assertEquals(10000L, live.getLongValue("room"));
        assertEquals("live_state", live.getString("kind"));
        assertEquals("connected", source.getJSONObject("data").getString("state"));
    }

    @Test
    @DisplayName("下播时 startTs 为 null，不是 0")
    void liveOffHasNullStart() {
        JSONObject env = NovaEventMapper.liveState(10000L, 1786111565000L, false, "标题", null);

        JSONObject d = env.getJSONObject("data");
        assertFalse(d.getBooleanValue("live"));
        assertTrue(d.containsKey("startTs"));
        assertNull(d.get("startTs"));
    }

    @Test
    @DisplayName("⚠️ 房间统计取不到的项要整个不放，而不是放 null")
    void roomStatOmitsMissingFields() {
        JSONObject d = NovaEventMapper.roomStat(10000L, 1786111565000L, 1820, null, null).getJSONObject("data");

        // 协议把这三项声明成可选字段（watched?）而非可空字段，
        // 显式的 null 在按协议生成的校验器眼里是类型错误
        assertEquals(1820, d.getIntValue("watched"));
        assertFalse(d.containsKey("online"));
        assertFalse(d.containsKey("likeTotal"));
    }

    @Test
    @DisplayName("房间统计三项全空时不推送")
    void roomStatWithNothingIsNotPublished() {
        assertNull(NovaEventMapper.roomStat(10000L, 1786111565000L, null, null, null));
    }
}
