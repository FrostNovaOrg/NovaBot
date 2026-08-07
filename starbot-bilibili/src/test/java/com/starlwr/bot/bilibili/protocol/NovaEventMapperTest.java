package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliEmojiEvent;
import com.starlwr.bot.bilibili.model.BilibiliEmojiInfo;
import com.starlwr.bot.core.event.live.common.FreeGiftEvent;
import com.starlwr.bot.core.event.live.common.MembershipEvent;
import com.starlwr.bot.core.event.live.common.PaidGiftEvent;
import com.starlwr.bot.core.event.live.common.RandomGiftEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
