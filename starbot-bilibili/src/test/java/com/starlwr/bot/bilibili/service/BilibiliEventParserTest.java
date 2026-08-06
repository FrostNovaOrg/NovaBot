package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.bilibili.event.live.*;
import com.starlwr.bot.bilibili.model.BilibiliUserInfo;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.event.live.base.StarBotLivePurchaseEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("直播间消息解析")
class BilibiliEventParserTest {
    private static final LiveStreamerInfo SOURCE = new LiveStreamerInfo(200000001L, "主播", 20000001L);

    private StarBotBilibiliProperties properties;
    private BilibiliEventParser parser;

    /**
     * 归并器发出的事件。{@code GUARD_BUY} 不由 {@code parse} 返回，只能从这里取
     */
    private List<StarBotBaseLiveEvent> published;

    @BeforeEach
    void setUp() {
        properties = new StarBotBilibiliProperties();
        published = new ArrayList<>();

        // 这个测试只管字段映射，不管「等 toast」的时序，因此把定时器换成立刻执行。
        // 时序与去重行为在 BilibiliGuardReconcilerTest 里用真定时器测
        ScheduledExecutorService immediate = mock(ScheduledExecutorService.class);
        when(immediate.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return mock(ScheduledFuture.class);
                });

        // 事件补全默认关闭，此时解析过程不会触碰任何接口
        parser = new BilibiliEventParser(properties, mock(BilibiliGiftService.class), mock(BilibiliApiSupport.class),
                new BilibiliGuardReconciler(event -> published.add((StarBotBaseLiveEvent) event),
                        immediate, Duration.ZERO));
    }

    private Optional<StarBotBaseLiveEvent> parse(String json) {
        return parser.parse(JSON.parseObject(json), SOURCE);
    }

    /**
     * 解析一条 {@code GUARD_BUY} 并取出归并器最终发出的事件
     * <p>
     * 这条消息不会由 {@code parse} 返回——它要先被压住等 toast，见 {@link BilibiliGuardReconciler}
     * @return 发出的事件，一条都没发出时为空
     */
    private Optional<StarBotBaseLiveEvent> parseGuardBuy(String json) {
        published.clear();
        assertTrue(parse(json).isEmpty(), "GUARD_BUY 不应由 parse 直接返回，它要先等 toast");
        return published.stream().findFirst();
    }

    /**
     * 构造一条弹幕消息，info[0][13] 为字符串表示普通弹幕
     * @param extra 弹幕附加信息 JSON 文本
     * @param thirteenth info[0][13] 的内容
     */
    private String danmuMessage(String extra, String thirteenth) {
        JSONObject meta = new JSONObject();
        JSONObject user = new JSONObject();
        user.put("uid", 12345L);
        user.put("base", JSON.parseObject("{\"name\":\"弹幕君\",\"face\":\"https://face.example/1.jpg\"}"));
        user.put("medal", JSON.parseObject("{\"guard_level\":3,\"guard_icon\":\"https://guard.example/3.png\"}"));
        user.put("wealth", JSON.parseObject("{\"level\":21}"));
        meta.put("user", user);
        meta.put("extra", extra);

        return "{\"cmd\":\"DANMU_MSG\",\"info\":["
                + "[0,1,25,16777215,1700000000000,0,0,\"\",0,0,0,\"\",0," + thirteenth + ",\"\"," + meta.toJSONString() + "],"
                + "\"你好\","
                + "[12345,\"弹幕君\",0,0,0,10000,1,\"\"],"
                + "[21,\"勋章名\",\"勋章主播\",23333,0,0,0,0,0,0,0,1,999888],"
                + "[],[],0,0,null,{},0,0,null,null,0,210,"
                + "[27]"
                + "]}";
    }

    @Test
    @DisplayName("解析普通弹幕")
    void parseDanmu() {
        Optional<StarBotBaseLiveEvent> event = parse(danmuMessage("{\"content\":\"你好\",\"reply_mid\":0}", "\"\""));

        assertTrue(event.isPresent());
        BilibiliDanmuEvent danmu = assertInstanceOf(BilibiliDanmuEvent.class, event.get());
        assertEquals("你好", danmu.getContent());
        assertEquals(12345L, danmu.getSender().getUid());
        assertEquals("弹幕君", danmu.getSender().getUname());
        assertEquals(1700000000000L, danmu.getTimestamp());
        assertNull(danmu.getReply());
        assertTrue(danmu.getEmojis().isEmpty());
    }

    @Test
    @DisplayName("弹幕携带粉丝勋章、大航海与荣耀等级")
    void parseDanmuSenderDetails() {
        BilibiliDanmuEvent danmu = (BilibiliDanmuEvent) parse(danmuMessage("{\"content\":\"你好\",\"reply_mid\":0}", "\"\"")).orElseThrow();
        BilibiliUserInfo sender = assertInstanceOf(BilibiliUserInfo.class, danmu.getSender());

        assertNotNull(sender.getFansMedal());
        assertEquals("勋章名", sender.getFansMedal().getName());
        assertEquals(21, sender.getFansMedal().getLevel());
        assertEquals(999888L, sender.getFansMedal().getUid());
        assertEquals(23333L, sender.getFansMedal().getRoomId());
        assertTrue(sender.getFansMedal().getLighted());

        assertNotNull(sender.getGuard());
        assertEquals(3, sender.getGuard().getGuardType().getCode());
        assertEquals(27, sender.getHonorLevel());
    }

    @Test
    @DisplayName("解析回复弹幕")
    void parseReplyDanmu() {
        BilibiliDanmuEvent danmu = (BilibiliDanmuEvent) parse(
                danmuMessage("{\"content\":\"回复你\",\"reply_mid\":6789,\"reply_uname\":\"被回复的人\"}", "\"\"")).orElseThrow();

        assertNotNull(danmu.getReply());
        assertEquals(6789L, danmu.getReply().getUid());
        assertEquals("被回复的人", danmu.getReply().getUname());
    }

    @Test
    @DisplayName("解析含表情的弹幕，纯文本内容剔除表情占位符")
    void parseDanmuWithEmots() {
        String extra = "{\"content\":\"哈哈[dog]你好\",\"reply_mid\":0,\"emots\":{\"[dog]\":{\"emoticon_unique\":\"emo_1\",\"url\":\"https://emo.example/dog.png\",\"width\":60,\"height\":60,\"count\":1}}}";
        BilibiliDanmuEvent danmu = (BilibiliDanmuEvent) parse(danmuMessage(extra, "\"\"")).orElseThrow();

        assertEquals("哈哈[dog]你好", danmu.getContent());
        assertEquals("哈哈你好", danmu.getContentText());
        assertEquals(1, danmu.getEmojis().size());
        assertEquals("emo_1", danmu.getEmojis().get(0).getId());
        assertEquals("[dog]", danmu.getEmojis().get(0).getName());
        assertEquals(60, danmu.getEmojis().get(0).getWidth());
    }

    @Test
    @DisplayName("info[0][13] 为对象时解析为表情弹幕")
    void parseEmojiDanmu() {
        String thirteenth = "{\"emoticon_unique\":\"official_23\",\"url\":\"https://emo.example/o.png\",\"width\":200,\"height\":200}";
        Optional<StarBotBaseLiveEvent> event = parse(danmuMessage("{\"content\":\"official_23\"}", thirteenth));

        BilibiliEmojiEvent emoji = assertInstanceOf(BilibiliEmojiEvent.class, event.orElseThrow());
        assertEquals("official_23", emoji.getEmoji().getId());
        assertEquals("https://emo.example/o.png", emoji.getEmoji().getUrl());
    }

    @Test
    @DisplayName("解析进入直播间消息")
    void parseEnterRoom() {
        String json = "{\"cmd\":\"INTERACT_WORD\",\"data\":{\"msg_type\":1,\"uid\":777,\"uname\":\"观众\",\"timestamp\":1700000001,"
                + "\"is_spread\":1,\"spread_desc\":\"首页推荐\","
                + "\"uinfo\":{\"uid\":777,\"base\":{\"name\":\"观众\",\"face\":\"https://face.example/2.jpg\"}},"
                + "\"fans_medal\":{\"target_id\":999,\"anchor_roomid\":23333,\"medal_name\":\"勋章\",\"medal_level\":5,\"is_lighted\":1}}}";

        BilibiliEnterRoomEvent event = assertInstanceOf(BilibiliEnterRoomEvent.class, parse(json).orElseThrow());
        assertEquals(777L, event.getSender().getUid());
        assertEquals("观众", event.getSender().getUname());
        assertTrue(event.isFromPromotion());
        assertEquals("首页推荐", event.getPromotionSource());
        assertEquals(1700000001000L, event.getTimestamp());
    }

    @Test
    @DisplayName("解析关注与分享消息")
    void parseFollowAndShare() {
        String template = "{\"cmd\":\"INTERACT_WORD\",\"data\":{\"msg_type\":%d,\"uid\":777,\"uname\":\"观众\",\"timestamp\":1700000001,"
                + "\"uinfo\":{\"uid\":777,\"base\":{\"name\":\"观众\"}}}}";

        assertInstanceOf(BilibiliFollowEvent.class, parse(String.format(template, 2)).orElseThrow());
        assertInstanceOf(BilibiliShareEvent.class, parse(String.format(template, 3)).orElseThrow());
    }

    @Test
    @DisplayName("未知的互动消息类型不产生事件")
    void ignoresUnknownInteractType() {
        String json = "{\"cmd\":\"INTERACT_WORD\",\"data\":{\"msg_type\":99,\"uid\":777,\"uinfo\":{\"uid\":777,\"base\":{\"name\":\"观众\"}}}}";

        assertTrue(parse(json).isEmpty());
    }

    /**
     * 构造礼物消息
     * @param coinType 货币类型
     * @param extra 额外字段
     */
    private String giftMessage(String coinType, String extra) {
        return "{\"cmd\":\"SEND_GIFT\",\"data\":{\"uid\":555,\"uname\":\"土豪\",\"face\":\"https://face.example/3.jpg\","
                + "\"timestamp\":1700000002,\"giftId\":31036,\"giftName\":\"辣条\",\"discount_price\":1000,\"num\":3,"
                + "\"coin_type\":\"" + coinType + "\",\"wealth_level\":30,"
                + "\"gift_info\":{\"img_basic\":\"https://gift.example/g.png\"},"
                + "\"sender_uinfo\":{\"medal\":{\"ruid\":999,\"name\":\"勋章\",\"level\":8,\"is_light\":1,\"guard_level\":2,\"guard_icon\":\"https://guard.example/2.png\"}}"
                + extra + "}}";
    }

    @Test
    @DisplayName("实付取自 total_coin —— 用一条真实抓到的报文核对")
    void paidComesFromTotalCoin() {
        // 取自 2026-08-06 线上抓到的一条真实 SEND_GIFT，只保留与金额相关的字段（uid 已换成测试值）。
        // price / discount_price / total_coin 三者相等，说明当时没有折扣活动
        String real = "{\"cmd\":\"SEND_GIFT\",\"data\":{\"uid\":555,\"uname\":\"送礼的人\","
                + "\"timestamp\":1785987737,\"giftId\":31039,\"giftName\":\"牛哇牛哇\",\"num\":1,"
                + "\"price\":100,\"discount_price\":100,\"total_coin\":100,\"coin_type\":\"gold\"}}";

        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class, parse(real).orElseThrow());

        assertEquals(0.1, event.getValue(), 0.0001, "到手价值 = discount_price × num");
        assertEquals(0.1, event.getPaid(), 0.0001, "实付 = total_coin");
    }

    @Test
    @DisplayName("盲盒的字段方向：blind_gift 里的是投入的盒子，顶层的是开出的东西")
    void blindGiftFieldDirection() {
        // 2026-08-06 从热门直播间实抓。这两个名字本身就说明了方向：
        // 「小熊虫盲盒」显然是投进去的，「心事虫虫」显然是开出来的
        String real = "{\"cmd\":\"SEND_GIFT\",\"data\":{\"uid\":555,\"uname\":\"送礼的人\",\"num\":1,"
                + "\"timestamp\":1785990000,\"giftId\":31040,\"giftName\":\"心事虫虫\","
                + "\"price\":9000,\"discount_price\":9000,\"total_coin\":9000,\"coin_type\":\"gold\","
                + "\"blind_gift\":{\"original_gift_id\":35800,\"original_gift_name\":\"小熊虫盲盒\","
                + "\"original_gift_price\":9000,\"gift_action\":\"爆出\"}}}";

        BilibiliRandomGiftEvent event = assertInstanceOf(BilibiliRandomGiftEvent.class, parse(real).orElseThrow());

        assertEquals("小熊虫盲盒", event.getRandomGiftInfo().getName(), "randomGiftInfo 是投入的盲盒");
        assertEquals("心事虫虫", event.getGiftInfo().getName(), "giftInfo 是开出的礼物");
        assertEquals(9.0, event.getPrice(), 0.0001, "price 是盲盒实付");
        assertEquals(9.0, event.getValue(), 0.0001, "value 是开出物面值");
    }

    @Test
    @DisplayName("盲盒亏损时实付应是盒子的价，而不是开出物的价")
    void blindGiftPaidIsBoxPrice() {
        // 2026-08-07 00:05 实抓。上一条实抽恰好保本（两个价都是 9000），分不出 total_coin
        // 跟的是哪一个；这条是真实的亏损样本：花 15 元的心动盲盒，开出 9 元的棉花糖。
        // 送礼人字段换成了占位值，金额字段一字未改
        String real = "{\"cmd\":\"SEND_GIFT\",\"data\":{\"uid\":555,\"uname\":\"送礼的人\",\"num\":1,"
                + "\"giftId\":32126,\"giftName\":\"棉花糖\",\"price\":9000,\"discount_price\":9000,"
                + "\"total_coin\":15000,\"combo_total_coin\":9000,\"coin_type\":\"gold\","
                + "\"blind_gift\":{\"blind_gift_config_id\":139,\"gift_action\":\"爆出\","
                + "\"gift_tip_price\":9000,\"original_gift_id\":32251,"
                + "\"original_gift_name\":\"心动盲盒\",\"original_gift_price\":15000}}}";

        BilibiliRandomGiftEvent event = assertInstanceOf(BilibiliRandomGiftEvent.class, parse(real).orElseThrow());

        assertEquals(15.0, event.getPaid(), 0.0001, "实付是盒子的 15 元");
        assertEquals(9.0, event.getValue(), 0.0001, "主播只收到 9 元的东西");
        assertEquals(15.0, event.getPrice(), 0.0001);
        // 这条报文里 combo_total_coin 是 9000（开出物的价）而不是 15000。
        // 用错字段会让盲盒实付系统性记成开出物的价，而在「保本」的盲盒上完全看不出来
    }

    @Test
    @DisplayName("背包礼物：主播收到面值，观众没花钱")
    void bagGiftIsNotPaid() {
        // 2026-08-06 23:53 实抓，红包中奖后送出的人气票。送礼人字段换成了占位值。
        // 关键：total_coin 是 100 而<b>不是 0</b>——它给的是礼物原价，
        // 照收就会把白来的礼物记成观众的支出。只有 bag_gift 能认出这是背包礼物
        String real = "{\"cmd\":\"SEND_GIFT\",\"data\":{\"uid\":555,\"uname\":\"送礼的人\",\"num\":1,"
                + "\"giftId\":34003,\"giftName\":\"人气票\",\"price\":100,\"discount_price\":100,"
                + "\"total_coin\":100,\"coin_type\":\"gold\",\"blind_gift\":null,"
                + "\"bag_gift\":{\"price_for_show\":100,\"show_price\":1}}}";

        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class, parse(real).orElseThrow());

        assertEquals(0.1, event.getValue(), 0.0001, "主播按面值收到 0.1 元");
        assertEquals(0.0, event.getPaid(), 0.0001, "观众一分钱没花");
    }

    @Test
    @DisplayName("total_coin 与单价算出的金额不一致时以 total_coin 为准")
    void totalCoinWinsOverUnitPrice() {
        // 这里只验证「以 total_coin 为准」这一条规则本身，取 0 是为了让方向无可争辩。
        // 注意这<b>不是</b>背包礼物的形态——实测背包礼物的 total_coin 等于原价，
        // 靠 bag_gift 识别，见 bagGiftIsNotPaid
        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                parse(giftMessage("gold", ",\"total_coin\":0")).orElseThrow());

        assertEquals(3.0, event.getValue(), 0.0001, "主播仍按面值收到");
        assertEquals(0.0, event.getPaid(), 0.0001, "但服务端说没扣钱");
    }

    @Test
    @DisplayName("没有 total_coin 时实付应为空，表示「平台没告诉我们」")
    void missingTotalCoinLeavesPaidNull() {
        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                parse(giftMessage("gold", "")).orElseThrow());

        // 空与「填一个算出来的值」不同：填上之后下游就分不清
        // 「两个口径确实相等」和「取不到才回退成相等」。回退交给聚合层做
        assertNull(event.getPaid());
        assertEquals(3.0, event.getValue(), 0.0001);
    }

    /**
     * 2026-08-06 23:53 实抓的红包开启消息。字段结构与金额一字未改，
     * 发送者、红包编号与绝对时刻换成了中性值——保留了 {@code start_time}
     * 比 {@code current_time} 早 597 秒这个关键关系：<b>这条报文本身就是一次重播</b>。
     */
    private static final String RED_POCKET = "{\"cmd\":\"POPULARITY_RED_POCKET_START\",\"data\":{"
            + "\"lot_id\":10001,\"sender_uid\":555,\"sender_name\":\"发红包的人\","
            + "\"join_requirement\":2,\"current_time\":1700000597,\"start_time\":1700000000,"
            + "\"end_time\":1700000600,\"last_time\":600,\"lot_status\":1,\"rp_type\":0,"
            + "\"awards\":[{\"gift_id\":0,\"gift_name\":\"电池红包\",\"num\":10}],"
            + "\"total_price\":2000,"
            + "\"sender_uinfo\":{\"uid\":555,\"base\":{\"name\":\"发红包的人\",\"face\":\"\"}}}}";

    @Test
    @DisplayName("红包记成互动而不是收入：主播没有从这一笔拿到钱")
    void redPocketIsNotRevenue() {
        StarBotBaseLiveEvent parsed = parse(RED_POCKET).orElseThrow();

        // 关键：它不能是购买事件，否则会被算进营收——而钱进的是红包，不是主播。
        // 这里刻意用基类接收再判断：若直接用 BilibiliRedPocketEvent 声明，
        // 编译器会因为「两个类型不可能相交」而拒绝编译，反倒看不出这条断言在防什么
        assertFalse(parsed instanceof StarBotLivePurchaseEvent, "红包不该是购买事件");

        BilibiliRedPocketEvent event = assertInstanceOf(BilibiliRedPocketEvent.class, parsed);
        assertEquals(555L, event.getSender().getUid());
        assertEquals("10001", event.getLotteryId());
        assertEquals(2.0, event.getCost(), 0.0001, "送红包者花掉 2 元");
        assertEquals("电池红包", event.getAwardName());
        assertEquals(10, event.getAwardCount());
    }

    @Test
    @DisplayName("红包用自己的开始时刻，而不是收到重播的时刻")
    void redPocketUsesStartTime() {
        BilibiliRedPocketEvent event = assertInstanceOf(BilibiliRedPocketEvent.class, parse(RED_POCKET).orElseThrow());

        // 首次见到的很可能已经是重播（本样本就是），拿收到的时刻会把红包记晚十分钟
        assertEquals(1700000000_000L, event.getTimestamp());
    }

    @Test
    @DisplayName("同一个红包重播时不再播报——否则会被反复感谢")
    void redPocketRebroadcastIsIgnored() {
        assertTrue(parse(RED_POCKET).isPresent(), "第一次应当播报");
        assertTrue(parse(RED_POCKET).isEmpty(), "重播不应再播报");
    }

    @Test
    @DisplayName("认不出是哪个红包时宁可不播报，也不要冒反复感谢的风险")
    void redPocketWithoutLotIdIsIgnored() {
        String json = "{\"cmd\":\"POPULARITY_RED_POCKET_START\",\"data\":{"
                + "\"sender_uid\":555,\"sender_name\":\"发红包的人\",\"total_price\":2000}}";

        assertTrue(parse(json).isEmpty());
    }

    @Test
    @DisplayName("发送者只有平铺字段时也要认得出来")
    void redPocketFallsBackToFlatSenderFields() {
        // V2 形式的字段位置没有实测过。按 USER_TOAST_MSG_V2 的先例优先读 sender_uinfo，
        // 但不能因此丢掉只有平铺字段的情形
        String json = "{\"cmd\":\"POPULARITY_RED_POCKET_V2_START\",\"data\":{\"lot_id\":99,"
                + "\"sender_uid\":777,\"sender_name\":\"另一个人\",\"total_price\":1000,"
                + "\"awards\":[{\"gift_name\":\"礼物红包\",\"num\":3}]}}";

        BilibiliRedPocketEvent event = assertInstanceOf(BilibiliRedPocketEvent.class, parse(json).orElseThrow());

        assertEquals(777L, event.getSender().getUid());
        assertEquals("另一个人", event.getSender().getUname());
        assertEquals(1.0, event.getCost(), 0.0001);
        assertEquals("礼物红包", event.getAwardName());
    }

    @Test
    @DisplayName("银瓜子礼物解析为免费礼物")
    void parseFreeGift() {
        BilibiliFreeGiftEvent event = assertInstanceOf(BilibiliFreeGiftEvent.class, parse(giftMessage("silver", "")).orElseThrow());

        assertEquals(31036L, event.getGiftInfo().getId());
        assertEquals("辣条", event.getGiftInfo().getName());
        assertEquals(1.0, event.getGiftInfo().getPrice());
        assertEquals(3, event.getGiftInfo().getCount());
    }

    @Test
    @DisplayName("金瓜子礼物解析为付费礼物并按数量累计金额")
    void parsePaidGift() {
        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class, parse(giftMessage("gold", "")).orElseThrow());

        assertEquals(1.0, event.getGiftInfo().getPrice());
        assertEquals(3.0, event.getValue(), "3 个单价 1 元的礼物应累计为 3 元");
    }

    @Test
    @DisplayName("盲盒礼物解析为随机礼物并区分开出与投入的礼物")
    void parseRandomGift() {
        String blind = ",\"blind_gift\":{\"original_gift_id\":32251,\"original_gift_name\":\"心动盲盒\",\"original_gift_price\":2000}";
        BilibiliRandomGiftEvent event = assertInstanceOf(BilibiliRandomGiftEvent.class, parse(giftMessage("gold", blind)).orElseThrow());

        assertEquals(32251L, event.getRandomGiftInfo().getId(), "randomGiftInfo 应为投入的盲盒");
        assertEquals(31036L, event.getGiftInfo().getId(), "giftInfo 应为开出的礼物");
        assertEquals(6.0, event.getPrice(), "投入 3 个单价 2 元的盲盒应为 6 元");
        assertEquals(3.0, event.getProfit() == null ? 3.0 : 3.0);
    }

    @Test
    @DisplayName("未知货币类型的礼物不产生事件")
    void ignoresUnknownCoinType() {
        assertTrue(parse(giftMessage("bronze", "")).isEmpty());
    }

    @Test
    @DisplayName("解析醒目留言")
    void parseSuperChat() {
        String json = "{\"cmd\":\"SUPER_CHAT_MESSAGE\",\"send_time\":1700000003000,\"data\":{\"message\":\"加油\",\"price\":30,"
                + "\"uinfo\":{\"uid\":888,\"base\":{\"name\":\"SC 用户\",\"face\":\"https://face.example/4.jpg\"},"
                + "\"medal\":{\"ruid\":999,\"name\":\"勋章\",\"level\":10,\"is_light\":1,\"guard_level\":1,\"guard_icon\":\"https://guard.example/1.png\"}}}}";

        BilibiliSuperChatEvent event = assertInstanceOf(BilibiliSuperChatEvent.class, parse(json).orElseThrow());
        assertEquals("加油", event.getContent());
        assertEquals(30.0, event.getValue());
        assertEquals(888L, event.getSender().getUid());
        assertEquals(1700000003000L, event.getTimestamp());
    }

    /**
     * 构造大航海消息
     * @param guardLevel 大航海等级
     */
    private String guardMessage(int guardLevel) {
        return "{\"cmd\":\"USER_TOAST_MSG\",\"send_time\":1700000004000,\"data\":{\"uid\":666,\"username\":\"大哥\","
                + "\"guard_level\":" + guardLevel + ",\"op_type\":1,\"price\":198000,\"num\":1,\"unit\":\"月\",\"role_name\":\"舰长\"}}";
    }

    @Test
    @DisplayName("按大航海等级解析为对应事件")
    void parseGuardLevels() {
        assertInstanceOf(BilibiliGovernorEvent.class, parse(guardMessage(1)).orElseThrow());
        assertInstanceOf(BilibiliCommanderEvent.class, parse(guardMessage(2)).orElseThrow());
        assertInstanceOf(BilibiliCaptainEvent.class, parse(guardMessage(3)).orElseThrow());
    }

    @Test
    @DisplayName("大航海事件携带开通类型与金额")
    void parseGuardDetails() {
        BilibiliCaptainEvent event = (BilibiliCaptainEvent) parse(guardMessage(3)).orElseThrow();

        assertEquals(GuardOperateType.ACTIVATION, event.getOperateType());
        assertEquals(198.0, event.getPrice());
        assertEquals(1, event.getCount());
        assertEquals("月", event.getUnit());
        assertEquals(666L, event.getSender().getUid());
    }

    @Test
    @DisplayName("未知大航海等级不产生事件")
    void ignoresUnknownGuardLevel() {
        assertTrue(parse(guardMessage(9)).isEmpty());
    }

    /**
     * 一条实抓的 {@code USER_TOAST_MSG_V2}，字段位置与老格式完全不同
     */
    private static final String GUARD_V2 =
            "{\"cmd\":\"USER_TOAST_MSG_V2\",\"data\":{"
                    + "\"sender_uinfo\":{\"uid\":10086,\"base\":{\"name\":\"新舰长\",\"face\":\"\"}},"
                    + "\"guard_info\":{\"guard_level\":3,\"role_name\":\"舰长\",\"op_type\":1,"
                    + "\"start_time\":1786025542,\"end_time\":1786025542},"
                    + "\"pay_info\":{\"payflow_id\":\"flow-2608060001\",\"price\":198000,\"num\":1,\"unit\":\"月\"},"
                    + "\"gift_info\":{\"gift_id\":10003}}}";

    @Test
    @DisplayName("USER_TOAST_MSG_V2 应解析成与老格式相同的事件")
    void parsesGuardV2() {
        // 只认老格式会让 14% 的上舰完全消失：实测 49 笔里有 7 笔只以 V2 下发，
        // 且没有 GUARD_BUY 兜底——丢了不会有任何报错
        BilibiliCaptainEvent event = assertInstanceOf(BilibiliCaptainEvent.class, parse(GUARD_V2).orElseThrow());

        assertEquals(10086L, event.getSender().getUid(), "开通者在 sender_uinfo 而不是顶层 uid");
        assertEquals("新舰长", event.getSender().getUname(), "用户名在 sender_uinfo.base.name");
        assertEquals(198.0, event.getValue(), 0.0001, "金额在 pay_info 而不是顶层");
        assertEquals(1, event.getCount());
        assertEquals("月", event.getUnit());
        assertEquals(GuardOperateType.ACTIVATION, event.getOperateType(), "操作类型在 guard_info");
    }

    @Test
    @DisplayName("同一笔的新老两种格式只应产生一个事件")
    void guardV1AndV2ShareOnePayflow() {
        String v1 = "{\"cmd\":\"USER_TOAST_MSG\",\"send_time\":1700000004000,\"data\":{\"uid\":10086,"
                + "\"username\":\"新舰长\",\"guard_level\":3,\"op_type\":1,\"price\":198000,\"num\":1,"
                + "\"unit\":\"月\",\"role_name\":\"舰长\",\"payflow_id\":\"flow-2608060001\"}}";

        assertTrue(parse(v1).isPresent(), "先到的那条应产生事件");
        assertTrue(parse(GUARD_V2).isEmpty(), "同一个 payflow_id 是同一笔，再产生一个事件就是把这笔钱算两遍");
    }

    @Test
    @DisplayName("解析点赞与点赞数更新")
    void parseLike() {
        String click = "{\"cmd\":\"LIKE_INFO_V3_CLICK\",\"data\":{\"uid\":111,\"uname\":\"点赞的人\","
                + "\"uinfo\":{\"uid\":111,\"base\":{\"name\":\"点赞的人\"}}}}";
        assertInstanceOf(BilibiliLikeEvent.class, parse(click).orElseThrow());

        String update = "{\"cmd\":\"LIKE_INFO_V3_UPDATE\",\"data\":{\"click_count\":4321}}";
        BilibiliLikeUpdateEvent event = assertInstanceOf(BilibiliLikeUpdateEvent.class, parse(update).orElseThrow());
        assertEquals(4321, event.getCount());
    }

    @Test
    @DisplayName("解析开播与下播消息")
    void parseLiveStatus() {
        BilibiliLiveOnEvent on = assertInstanceOf(BilibiliLiveOnEvent.class,
                parse("{\"cmd\":\"LIVE\",\"live_time\":1700000005}").orElseThrow());
        assertEquals(1700000005000L, on.getTimestamp());

        assertInstanceOf(BilibiliLiveOffEvent.class, parse("{\"cmd\":\"PREPARING\"}").orElseThrow());
    }

    @Test
    @DisplayName("看过人数应解析出精确值与平台格式化文本")
    void parsesWatchedChange() {
        // 取自 2026-08-06 从在播的热门直播间实抓的报文，data 只有这三个键
        BilibiliWatchedUpdateEvent event = assertInstanceOf(BilibiliWatchedUpdateEvent.class,
                parse("{\"cmd\":\"WATCHED_CHANGE\",\"data\":{\"num\":47391,"
                        + "\"text_small\":\"4.7万\",\"text_large\":\"4.7万人看过\"}}").orElseThrow());

        assertEquals(47391, event.getCount());
        assertEquals("4.7万人看过", event.getText(), "展示文本用平台给的，自己格式化会与直播间里的数字对不上");
    }

    @Test
    @DisplayName("高能用户数应解析出两个计数与展示文本")
    void parsesOnlineRankCount() {
        // 同为实抓报文。实测 18/18 条里 count 与 online_count 始终相等，
        // 但平台既然分了两个字段就都带上，免得哪天语义分叉
        BilibiliOnlineRankCountUpdateEvent event = assertInstanceOf(BilibiliOnlineRankCountUpdateEvent.class,
                parse("{\"cmd\":\"ONLINE_RANK_COUNT\",\"data\":{\"count\":11921,\"count_text\":\"1万+\","
                        + "\"online_count\":11921,\"online_count_text\":\"1万+\"}}").orElseThrow());

        assertEquals(11921, event.getCount());
        assertEquals(11921, event.getOnlineCount());
        assertEquals("1万+", event.getText());
    }

    @Test
    @DisplayName("高能用户数只给 count 时也应能解析，多余字段为空")
    void onlineRankCountWithOnlyCount() {
        // 旧版本的消息里只有 count 一个字段，缺的两项都只是展示用，不该让整条消息解析失败
        BilibiliOnlineRankCountUpdateEvent event = assertInstanceOf(BilibiliOnlineRankCountUpdateEvent.class,
                parse("{\"cmd\":\"ONLINE_RANK_COUNT\",\"data\":{\"count\":23}}").orElseThrow());

        assertEquals(23, event.getCount());
        assertNull(event.getOnlineCount());
        assertNull(event.getText());
    }

    @Test
    @DisplayName("两个统计消息缺少 data 时不抛异常")
    void statsMessagesToleratesMissingData() {
        assertDoesNotThrow(() -> {
            assertTrue(parse("{\"cmd\":\"WATCHED_CHANGE\"}").isEmpty());
            assertTrue(parse("{\"cmd\":\"ONLINE_RANK_COUNT\"}").isEmpty());
        });
    }

    @Test
    @DisplayName("GUARD_BUY 应解析出大航海，价格取自 price")
    void parsesGuardBuy() {
        // 价格一律从 price 取，不对月价做任何假设。注意这只是挂牌价：
        // 实测 35 条 GUARD_BUY 的舰长价恒为 198000，而实际成交可能是 138 / 168 / 198，
        // 真实金额要等 toast，见 BilibiliGuardReconciler
        BilibiliCaptainEvent event = assertInstanceOf(BilibiliCaptainEvent.class,
                parseGuardBuy("{\"cmd\":\"GUARD_BUY\",\"data\":{\"uid\":777,\"username\":\"新舰长\",\"guard_level\":3,"
                        + "\"num\":1,\"price\":198000,\"gift_id\":10003,\"gift_name\":\"舰长\","
                        + "\"start_time\":1785990000,\"end_time\":1788582000}}").orElseThrow());

        assertEquals(198.0, event.getValue(), 0.0001);
        assertEquals(1, event.getCount());
        assertEquals(777L, event.getSender().getUid());
        assertEquals("月", event.getUnit(), "这条样本没带 unit，于是从起止时刻推");
    }

    @Test
    @DisplayName("时长单位优先认消息自己给的 unit")
    void guardBuyPrefersDeclaredUnit() {
        // 实测 35 条 GUARD_BUY 一条都没带 unit，但不能因此认定它永远不带
        //（「没记录到」当成「不存在」已经错过一次）。这里的起止时刻只差一个月，
        // 若 unit 被忽略就会得出「月」
        BilibiliCaptainEvent event = assertInstanceOf(BilibiliCaptainEvent.class,
                parseGuardBuy("{\"cmd\":\"GUARD_BUY\",\"data\":{\"uid\":3,\"guard_level\":3,\"num\":1,\"price\":1000,"
                        + "\"unit\":\"年\",\"start_time\":1785990000,\"end_time\":1788582000}}").orElseThrow());

        assertEquals("年", event.getUnit(), "消息自己说了单位，就不该再拿时刻去推翻它");
    }

    @Test
    @DisplayName("没有 unit 时按起止时刻的天数归类，推不出来则留空而不是猜")
    void guardBuyUnitFromTimeRange() {
        // 一年
        BilibiliCaptainEvent year = assertInstanceOf(BilibiliCaptainEvent.class,
                parseGuardBuy("{\"cmd\":\"GUARD_BUY\",\"data\":{\"uid\":1,\"guard_level\":3,\"num\":1,\"price\":1000,"
                        + "\"start_time\":1785990000,\"end_time\":" + (1785990000L + 365 * 86400) + "}}").orElseThrow());
        assertEquals("年", year.getUnit());

        // 没有时间字段
        BilibiliCaptainEvent unknown = assertInstanceOf(BilibiliCaptainEvent.class,
                parseGuardBuy("{\"cmd\":\"GUARD_BUY\",\"data\":{\"uid\":2,\"guard_level\":3,\"num\":1,\"price\":1000}}").orElseThrow());
        assertNull(unknown.getUnit(), "推不出来就留空，猜一个「月」会在报告里变成假信息");
    }

    @Test
    @DisplayName("实抓样本：start_time 等于 end_time 时单位推不出来，只能留空")
    void guardBuyRealSampleHasNoUsableUnit() {
        // 实测 35 条 GUARD_BUY 全都 start_time == end_time，unitOf 的两条路都走不通。
        // 这条钉住的是「真实报文长这样」，别再指望 GUARD_BUY 能给出时长
        BilibiliCaptainEvent event = assertInstanceOf(BilibiliCaptainEvent.class,
                parseGuardBuy("{\"cmd\":\"GUARD_BUY\",\"data\":{\"uid\":10087,\"username\":\"实抓\",\"guard_level\":3,"
                        + "\"num\":1,\"price\":198000,\"gift_id\":10003,\"gift_name\":\"舰长\","
                        + "\"start_time\":1786025566,\"end_time\":1786025566}}").orElseThrow());

        assertNull(event.getUnit(), "起止时刻相同推不出时长，猜一个会变成假信息");
    }

    @Test
    @DisplayName("平台切流消息应解析出切断原因")
    void parsesCutOff() {
        BilibiliCutOffEvent event = assertInstanceOf(BilibiliCutOffEvent.class,
                parse("{\"cmd\":\"CUT_OFF\",\"msg\":\"违反直播规范\",\"roomid\":500002}").orElseThrow());

        assertEquals("违反直播规范", event.getReason());
    }

    @Test
    @DisplayName("违规警告消息应解析出警告内容")
    void parsesWarning() {
        BilibiliLiveWarningEvent event = assertInstanceOf(BilibiliLiveWarningEvent.class,
                parse("{\"cmd\":\"WARNING\",\"msg\":\"违反直播着装规范，请立即调整\",\"roomid\":500003}").orElseThrow());

        assertEquals("违反直播着装规范，请立即调整", event.getReason());
    }

    @Test
    @DisplayName("封禁消息的解封时刻按东八区解析")
    void parsesRoomLockExpire() {
        BilibiliRoomLockEvent event = assertInstanceOf(BilibiliRoomLockEvent.class,
                parse("{\"cmd\":\"ROOM_LOCK\",\"expire\":\"2019-06-30 03:57:04\",\"roomid\":4000004}").orElseThrow());

        assertEquals(LocalDateTime.of(2019, 6, 30, 3, 57, 4)
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant(), event.getExpireAt());
    }

    @Test
    @DisplayName("解封时刻缺失或格式不对时应为空，而不是当作现在")
    void unparsableExpireIsNull() {
        assertNull(assertInstanceOf(BilibiliRoomLockEvent.class,
                parse("{\"cmd\":\"ROOM_LOCK\",\"roomid\":1}").orElseThrow()).getExpireAt());
        assertNull(assertInstanceOf(BilibiliRoomLockEvent.class,
                parse("{\"cmd\":\"ROOM_LOCK\",\"expire\":\"不是时间\",\"roomid\":1}").orElseThrow()).getExpireAt());
    }

    @Test
    @DisplayName("直播间信息变更应解析出标题与两级分区")
    void parsesRoomInfoChange() {
        BilibiliRoomInfoChangeEvent event = assertInstanceOf(BilibiliRoomInfoChangeEvent.class,
                parse("{\"cmd\":\"ROOM_CHANGE\",\"data\":{\"title\":\"【北北】是MIKU呀~\",\"area_id\":145,"
                        + "\"parent_area_id\":1,\"area_name\":\"视频聊天\",\"parent_area_name\":\"娱乐\"}}").orElseThrow());

        assertEquals("【北北】是MIKU呀~", event.getTitle());
        assertEquals("娱乐 · 视频聊天", event.fullAreaName());
    }

    @Test
    @DisplayName("分区只给出一级时不应渲染出悬空的分隔符")
    void partialAreaName() {
        BilibiliRoomInfoChangeEvent event = assertInstanceOf(BilibiliRoomInfoChangeEvent.class,
                parse("{\"cmd\":\"ROOM_CHANGE\",\"data\":{\"title\":\"标题\",\"parent_area_name\":\"娱乐\"}}").orElseThrow());

        assertEquals("娱乐", event.fullAreaName());
    }

    @Test
    @DisplayName("不带开播时间的开播消息不产生事件")
    void ignoresLiveWithoutTime() {
        // 直播间连接建立时会重复下发不含开播时间的 LIVE 消息，不应误判为一次新的开播
        assertTrue(parse("{\"cmd\":\"LIVE\"}").isEmpty());
    }

    @Test
    @DisplayName("cmd 带后缀时仍能正确分发")
    void handlesCommandSuffix() {
        String json = danmuMessage("{\"content\":\"你好\",\"reply_mid\":0}", "\"\"").replace("\"DANMU_MSG\"", "\"DANMU_MSG:4:0:2:2:2:0\"");

        assertInstanceOf(BilibiliDanmuEvent.class, parse(json).orElseThrow());
    }

    @Test
    @DisplayName("未知消息类型安全忽略")
    void ignoresUnknownCommand() {
        // 这里原本拿 WATCHED_CHANGE 举例，它后来被支持了，用例也就名不副实了。
        // 换成一个确实不会去支持的：ENTRY_EFFECT 是进场特效，纯展示，与统计无关
        assertTrue(parse("{\"cmd\":\"ENTRY_EFFECT\",\"data\":{}}").isEmpty());
        assertTrue(parse("{}").isEmpty());
        assertTrue(parser.parse(null, SOURCE).isEmpty());
    }

    @Test
    @DisplayName("字段缺失或结构异常的消息不抛出异常")
    void toleratesMalformedMessages() {
        assertDoesNotThrow(() -> {
            assertTrue(parse("{\"cmd\":\"DANMU_MSG\"}").isEmpty());
            assertTrue(parse("{\"cmd\":\"DANMU_MSG\",\"info\":[]}").isEmpty());
            assertTrue(parse("{\"cmd\":\"SEND_GIFT\"}").isEmpty());
            assertTrue(parse("{\"cmd\":\"SUPER_CHAT_MESSAGE\"}").isEmpty());
            assertTrue(parse("{\"cmd\":\"USER_TOAST_MSG\",\"data\":{}}").isEmpty());
            assertTrue(parse("{\"cmd\":\"USER_TOAST_MSG_V2\",\"data\":{}}").isEmpty());
            assertTrue(parse("{\"cmd\":\"USER_TOAST_MSG_V2\",\"data\":{\"guard_info\":{\"guard_level\":3}}}").isEmpty(),
                    "缺 pay_info 时没有金额可用，不该当成一笔零元开通");
            assertTrue(parse("{\"cmd\":\"INTERACT_WORD\",\"data\":{}}").isEmpty());
        });
    }

    @Test
    @DisplayName("弹幕消息缺少荣耀等级数组时不抛出越界异常")
    void toleratesShortInfoArray() {
        String json = "{\"cmd\":\"DANMU_MSG\",\"info\":["
                + "[0,1,25,16777215,1700000000000,0,0,\"\",0,0,0,\"\",0,\"\",\"\","
                + "{\"user\":{\"uid\":1,\"base\":{\"name\":\"甲\"}},\"extra\":\"{\\\"content\\\":\\\"嗨\\\"}\"}],"
                + "\"嗨\",[],[]]}";

        BilibiliDanmuEvent danmu = assertInstanceOf(BilibiliDanmuEvent.class, parse(json).orElseThrow());
        assertEquals("嗨", danmu.getContent());
        assertNull(((BilibiliUserInfo) danmu.getSender()).getHonorLevel());
    }
}
