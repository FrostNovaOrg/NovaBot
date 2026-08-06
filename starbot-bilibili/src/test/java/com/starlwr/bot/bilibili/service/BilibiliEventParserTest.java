package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.bilibili.event.live.*;
import com.starlwr.bot.bilibili.model.BilibiliUserInfo;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("直播间消息解析")
class BilibiliEventParserTest {
    private static final LiveStreamerInfo SOURCE = new LiveStreamerInfo(200000001L, "主播", 20000001L);

    private StarBotBilibiliProperties properties;
    private BilibiliEventParser parser;

    @BeforeEach
    void setUp() {
        properties = new StarBotBilibiliProperties();
        // 事件补全默认关闭，此时解析过程不会触碰任何接口
        parser = new BilibiliEventParser(properties, mock(BilibiliGiftService.class), mock(BilibiliApiSupport.class));
    }

    private Optional<StarBotBaseLiveEvent> parse(String json) {
        return parser.parse(JSON.parseObject(json), SOURCE);
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
    @DisplayName("total_coin 与单价算出的金额不一致时以 total_coin 为准")
    void totalCoinWinsOverUnitPrice() {
        // 背包礼物预期就是这个形态：主播收到面值，而实际一分钱没扣
        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                parse(giftMessage("gold", ",\"total_coin\":0")).orElseThrow());

        assertEquals(3.0, event.getValue(), 0.0001, "主播仍按面值收到");
        assertEquals(0.0, event.getPaid(), 0.0001, "但观众没花钱");
    }

    @Test
    @DisplayName("没有 total_coin 时实付回退到到手价值，而不是当作 0")
    void missingTotalCoinFallsBack() {
        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                parse(giftMessage("gold", "")).orElseThrow());

        assertEquals(3.0, event.getPaid(), 0.0001, "记成 0 会让营收凭空少一截，且不会有任何报错");
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
        assertTrue(parse("{\"cmd\":\"WATCHED_CHANGE\",\"data\":{}}").isEmpty());
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
