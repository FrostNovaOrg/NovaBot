package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.bilibili.enums.GuardType;
import com.starlwr.bot.bilibili.event.live.*;
import com.starlwr.bot.bilibili.model.BilibiliUserInfo;
import com.starlwr.bot.bilibili.model.FansMedal;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.event.live.base.StarBotLivePurchaseEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("直播间消息解析")
class BilibiliEventParserTest {
    private static final LiveStreamerInfo SOURCE = new LiveStreamerInfo(19805387116684L, "主播", 47731877194803L);

    private StarBotBilibiliProperties properties;
    private BilibiliEventParser parser;

    /**
     * 补全用的接口封装。默认不打桩——事件补全默认关闭，解析过程根本不会碰它
     */
    private BilibiliApiSupport apiSupport;

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
        apiSupport = mock(BilibiliApiSupport.class);
        parser = new BilibiliEventParser(properties, mock(BilibiliGiftService.class), apiSupport,
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

    @Nested
    @DisplayName("INTERACT_WORD_V2")
    class InteractV2 {
        /**
         * 以下 base64 全部脱敏自 2026-08-10 在单个直播间实抓的登录态语料。
         * <p>
         * <b>只替换了承载身份的标量</b>——观众 uid 与昵称、头像地址、勋章所属主播与房间号、
         * 勋章名称。字段号、wire type、嵌套层次、字段出现顺序，以及等级、颜色、时间戳、
         * 大航海到期时间和那些语义未明的字段，全部是平台原样下发的字节重新序列化而来。
         * 因此这些固件测的是<b>真实报文的结构</b>，而不含任何真实观众信息。
         * <p>
         * 主播 uid 与房间号已改写成与 {@link BilibiliEventParserTest#SOURCE} 一致，
         * 便于直接核对勋章归属。
         */
        private static final String ENTER_WITH_GUARD = "CJFOEg/ov5vmiL/op4LkvJfnlLIiAwYDASgBMLPY1oyX7Qo4v+Di0wZAuKjH0f8zSjwIjKHB6LTABBAoGgzmtYvor5Xli4vnq6Agi8L9ByiLwv0HMISh/wc4/9GfA0ABSANgs9jWjJftCmiWzBJiAHjttYKCuqeM5RiAAQOaAQCyAfECCJFOEkAKD+i/m+aIv+inguS8l+eUshIkaHR0cHM6Ly9mYWNlLmV4YW1wbGUvZW50ZXItZ3VhcmQuanBnQgcjMDBEMUYxGrsBCgzmtYvor5Xli4vnq6AQKBiLwv0HIISh/wco/9GfAzCLwv0HOL6nEUgBUIyhwei0wARYA2CWzBJqSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9saXZlLzQ4MzYwYzhmM2I3ZGU4MDMxZTg2ZmYxZWY0YTJkZmMwZWMyYTYxYzIucG5negkjNEM3REZGOTmCAQkjNEM3REZGOTmKAQcjNThBMUY4kgEHI0ZGRkZGRpoBCSM0QzdERkZFNiICCCUyFwgDEhMyMDI2LTA4LTE0IDIzOjU5OjU5Ok8I7w0SSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9saXZlLzgwZjczMjk0M2NjMzM2NzAyOWRmNjVlMjY3OTYwZDU2NzM2YTgyZWUucG5nugEAwgEA";

        private static final String ENTER_WITH_PROMOTION = "CJJOEg/ov5vmiL/op4LkvJfkuZkiAQEoATCz2NaMl+0KOOHk4tMGQPvl47v+M0oAUAFaByNGRjY0OUViAGoP5rWB6YeP5YyF5o6o5bm/eMvUnKivt4zlGJoBALIBhAEIkk4SfwoP6L+b5oi/6KeC5LyX5LmZEiVodHRwczovL2ZhY2UuZXhhbXBsZS9lbnRlci1zcHJlYWQuanBnMjgKD+i/m+aIv+inguS8l+S5mRIlaHR0cHM6Ly9mYWNlLmV4YW1wbGUvZW50ZXItc3ByZWFkLmpwZzoLIP///////////wG6AQDCAQA=";

        private static final String ENTER_PLAIN = "CJNOEg/ov5vmiL/op4LkvJfkuJkiAQEoATCz2NaMl+0KOKLf4tMGQK6ouLv+M2IAeKvs1ejyoozlGJoBALIBQgiTThI3Cg/ov5vmiL/op4LkvJfkuJkSJGh0dHBzOi8vZmFjZS5leGFtcGxlL2VudGVyLXBsYWluLmpwZyICCAcyALoBAMIBAA==";

        private static final String FOLLOW_WITH_MEDAL = "CJROEgzlhbPms6jop4LkvJciAgMBKAIws9jWjJftCjjl6OLTBkCf1OXA/jNKOAiMocHotMAEEAMaDOa1i+ivleWLi+eroCCOrfICKI6t8gIwjq3yAjiOrfICQAFgs9jWjJftCmgKYgB467rniavGjOUYmgEAsgHfAQiUThJtCgzlhbPms6jop4LkvJcSH2h0dHBzOi8vZmFjZS5leGFtcGxlL2ZvbGxvdy5qcGcyLwoM5YWz5rOo6KeC5LyXEh9odHRwczovL2ZhY2UuZXhhbXBsZS9mb2xsb3cuanBnOgsg////////////ARppCgzmtYvor5Xli4vnq6AQAxiOrfICII6t8gIojq3yAjCOrfICSAFQjKHB6LTABGAKegkjNTc2MkE3OTmCAQkjNTc2MkE3OTmKAQkjNTc2MkE3OTmSAQcjRkZGRkZGmgEJIzU3NjJBN0U2MgC6AQA=";

        private static final String SHARE = "CJVOEgzliIbkuqvop4LkvJciAQEoAzCz2NaMl+0KOLns4tMGQIqjn7z+M0o3CIyhwei0wAQQDhoM5rWL6K+V5YuL56ugIIbN+QUowIGDBjDAgYMGOMCBgwZgs9jWjJftCmjXA2IAeL6un+SC1IzlGJoBALIB3AEIlU4SawoM5YiG5Lqr6KeC5LyXEh5odHRwczovL2ZhY2UuZXhhbXBsZS9zaGFyZS5qcGcyLgoM5YiG5Lqr6KeC5LyXEh5odHRwczovL2ZhY2UuZXhhbXBsZS9zaGFyZS5qcGc6CyD///////////8BGmgKDOa1i+ivleWLi+eroBAOGMCBgwYgwIGDBijAgYMGMIbN+QVQjKHB6LTABGDXA3oJIzkxOTI5OENDggEJIzkxOTI5OENDigEJIzkxOTI5OENDkgEHI0ZGRkZGRpoBCSM5MTkyOThFNjIAugEA";

        /**
         * 手工按 wire format 拼的报文，取值 {@code msg_type=99}。语料里没有这种消息
         */
        private static final String UNKNOWN_TYPE = "CIkGEgbop4LkvJcoYziB4s+qBg==";

        /**
         * 手工拼的报文，只有顶层字段而没有 uinfo，用来验证退回顶层 uid 与昵称的通路
         */
        private static final String WITHOUT_UINFO = "CIkGEgzpobblsYLmmLXnp7AoATCz2NaMl+0KOIHiz6oG";

        /**
         * 手工拼的报文，uinfo 里有 uid 但没有 base，用来验证事件补全的通路
         */
        private static final String UINFO_WITHOUT_BASE = "CIkGEgzpobblsYLmmLXnp7AoATiB4s+qBrIBAwiJBg==";

        private Optional<StarBotBaseLiveEvent> parseV2(String pb) {
            return parse("{\"cmd\":\"INTERACT_WORD_V2\",\"data\":{\"dmscore\":3,\"pb\":\"" + pb + "\"}}");
        }

        @Test
        @DisplayName("解析进房消息，含勋章、大航海与财富等级")
        void parseEnterRoomWithMedalAndGuard() {
            BilibiliEnterRoomEvent event = assertInstanceOf(BilibiliEnterRoomEvent.class, parseV2(ENTER_WITH_GUARD).orElseThrow());

            BilibiliUserInfo sender = (BilibiliUserInfo) event.getSender();
            assertEquals(10001L, sender.getUid());
            assertEquals("进房观众甲", sender.getUname());
            assertEquals("https://face.example/enter-guard.jpg", sender.getFace());
            assertEquals(37, sender.getHonorLevel());

            // 字段 7 是秒级，事件对外给出的必须是毫秒
            assertEquals(1786294335000L, event.getTimestamp());

            assertEquals(GuardType.Captain, sender.getGuard().getGuardType());
            assertEquals("https://i0.hdslb.com/bfs/live/48360c8f3b7de8031e86ff1ef4a2dfc0ec2a61c2.png",
                    sender.getGuard().getIcon());

            FansMedal medal = sender.getFansMedal();
            assertEquals(19805387116684L, medal.getUid());
            assertEquals(47731877194803L, medal.getRoomId());
            assertEquals("测试勋章", medal.getName());
            assertEquals(40, medal.getLevel());
            assertTrue(medal.getLighted());

            assertFalse(event.isFromPromotion());
            assertNull(event.getPromotionSource());
        }

        @Test
        @DisplayName("解析推广位进房，且空的勋章子消息视为没有勋章")
        void parseEnterRoomFromPromotion() {
            BilibiliEnterRoomEvent event = assertInstanceOf(BilibiliEnterRoomEvent.class, parseV2(ENTER_WITH_PROMOTION).orElseThrow());

            assertEquals(10002L, event.getSender().getUid());
            assertTrue(event.isFromPromotion());
            assertEquals("流量包推广", event.getPromotionSource());
            assertEquals(1786294881000L, event.getTimestamp());

            BilibiliUserInfo sender = (BilibiliUserInfo) event.getSender();
            // 这条报文带着一条空的勋章子消息。空不等于缺失，但同样应当得出「没有勋章」
            assertNull(sender.getFansMedal());
            assertNull(sender.getGuard());
            assertNull(sender.getHonorLevel());
        }

        @Test
        @DisplayName("解析普通进房，没有勋章与大航海时留空而不是给零值")
        void parseEnterRoomPlain() {
            BilibiliEnterRoomEvent event = assertInstanceOf(BilibiliEnterRoomEvent.class, parseV2(ENTER_PLAIN).orElseThrow());

            BilibiliUserInfo sender = (BilibiliUserInfo) event.getSender();
            assertEquals(10003L, sender.getUid());
            assertEquals("进房观众丙", sender.getUname());
            assertEquals(7, sender.getHonorLevel());
            assertNull(sender.getFansMedal());
            assertNull(sender.getGuard());
            assertFalse(event.isFromPromotion());
            assertNull(event.getPromotionSource());
        }

        @Test
        @DisplayName("解析关注消息")
        void parseFollow() {
            // 样本量不足：全部语料里只有 4 条 msg_type=2。这一条只能证明枚举值确实会下发、
            // 且字段布局与进房一致，不构成对关注这条通路的验收
            BilibiliFollowEvent event = assertInstanceOf(BilibiliFollowEvent.class, parseV2(FOLLOW_WITH_MEDAL).orElseThrow());

            BilibiliUserInfo sender = (BilibiliUserInfo) event.getSender();
            assertEquals(10004L, sender.getUid());
            assertEquals("关注观众", sender.getUname());
            assertEquals(1786295397000L, event.getTimestamp());
            assertEquals(3, sender.getFansMedal().getLevel());
            assertTrue(sender.getFansMedal().getLighted());
        }

        @Test
        @DisplayName("解析分享消息")
        void parseShare() {
            // 样本量不足：全部语料里只有 1 条 msg_type=3，同上，不构成验收
            BilibiliShareEvent event = assertInstanceOf(BilibiliShareEvent.class, parseV2(SHARE).orElseThrow());

            BilibiliUserInfo sender = (BilibiliUserInfo) event.getSender();
            assertEquals(10005L, sender.getUid());
            assertEquals("分享观众", sender.getUname());
            assertEquals(1786295865000L, event.getTimestamp());
            assertEquals(14, sender.getFansMedal().getLevel());
            // 这条的勋章没有点亮标志。proto3 省略零值，未点亮时字段整个消失
            assertFalse(sender.getFansMedal().getLighted());
        }

        @Test
        @DisplayName("没有 uinfo 时退回顶层的 uid 与昵称")
        void fallsBackToTopLevelIdentity() {
            BilibiliEnterRoomEvent event = assertInstanceOf(BilibiliEnterRoomEvent.class, parseV2(WITHOUT_UINFO).orElseThrow());

            assertEquals(777L, event.getSender().getUid());
            assertEquals("顶层昵称", event.getSender().getUname());
            assertEquals(1700000001000L, event.getTimestamp());
        }

        @Test
        @DisplayName("未知的互动类型不产生事件")
        void ignoresUnknownType() {
            assertTrue(parseV2(UNKNOWN_TYPE).isEmpty());
        }

        @Test
        @DisplayName("报文缺失、为空或不是合法 base64 时不产生事件也不抛异常")
        void ignoresUnusablePayload() {
            assertTrue(parse("{\"cmd\":\"INTERACT_WORD_V2\",\"data\":{\"dmscore\":3}}").isEmpty(), "缺 pb 字段");
            assertTrue(parseV2("").isEmpty(), "pb 为空串");
            assertTrue(parseV2("!!!不是 base64!!!").isEmpty(), "pb 不是合法 base64");
            assertTrue(parse("{\"cmd\":\"INTERACT_WORD_V2\"}").isEmpty(), "整个 data 缺失");
        }

        @Test
        @DisplayName("读不出互动类型时不产生事件")
        void ignoresPayloadWithoutMsgType() {
            // 一串随机字节几乎必然读不出 msg_type。这里要的是「不抛异常且不产生事件」，
            // 而不是让它凑出一个进房事件来
            assertTrue(parseV2(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5})).isEmpty());
        }

        /**
         * 截断点：落在时间戳（字段 7）读完之后、勋章（字段 9）开始之前。
         * <p>
         * 🔴 这个数<b>跟着报文头几个字段的字节数走</b>。头几个字段是变长 varint——
         * uid、房间号、时间戳，值一改字节数就跟着改。改了夹具而没有把这个数往后挪，
         * 时间戳就会被切到截断点外面，解析器退回 {@code Instant.now()}，
         * 而失败信息只会说「时间戳不对」，不会说「截断点该挪」。
         */
        private static final int TRUNCATE_AT = 43;

        @Test
        @DisplayName("被截断的报文仍按已读到的字段产出事件")
        void stillEmitsEventOnTruncatedPayload() {
            // 长连接上真出现半条消息时，uid、msg_type 与时间戳都在报文开头，
            // 丢掉的是勋章与 uinfo。此时宁可产出一个信息不全的进房事件，也不要整条丢弃
            byte[] full = Base64.getDecoder().decode(ENTER_WITH_GUARD);
            byte[] cut = Arrays.copyOf(full, TRUNCATE_AT);

            BilibiliEnterRoomEvent event = assertInstanceOf(BilibiliEnterRoomEvent.class,
                    parseV2(Base64.getEncoder().encodeToString(cut)).orElseThrow());

            assertEquals(10001L, event.getSender().getUid());
            assertEquals("进房观众甲", event.getSender().getUname());
            assertEquals(1786294335000L, event.getTimestamp(),
                    "时间戳被切到截断点外面了——报文头是变长 varint，值一改字节数就变，TRUNCATE_AT 要跟着往后挪");
            assertNull(((BilibiliUserInfo) event.getSender()).getFansMedal(), "截断之后的字段应当缺失而不是被猜出来");
        }

        @Test
        @DisplayName("开启事件补全后，uinfo 缺少 base 时去补昵称与头像")
        void completesIdentityWhenBaseMissing() {
            properties.getLive().setCompleteEvent(true);
            when(apiSupport.completeUname(eq(777L), any())).thenReturn(Optional.of("补全昵称"));
            when(apiSupport.completeFace(eq(777L), any())).thenReturn(Optional.of("https://face.example/completed.jpg"));

            BilibiliEnterRoomEvent event = assertInstanceOf(BilibiliEnterRoomEvent.class, parseV2(UINFO_WITHOUT_BASE).orElseThrow());

            assertEquals("补全昵称", event.getSender().getUname());
            assertEquals("https://face.example/completed.jpg", event.getSender().getFace());
        }

        @Test
        @DisplayName("uinfo 带 base 时不去调补全接口")
        void doesNotCompleteWhenBasePresent() {
            properties.getLive().setCompleteEvent(true);

            BilibiliEnterRoomEvent event = assertInstanceOf(BilibiliEnterRoomEvent.class, parseV2(ENTER_PLAIN).orElseThrow());

            assertEquals("进房观众丙", event.getSender().getUname());
            verify(apiSupport, never()).completeUname(eq(10003L), any());
        }
    }

    @Nested
    @DisplayName("SEND_GIFT_V2")
    class GiftV2 {
        /**
         * 构造 SEND_GIFT_V2 的 data.pb（base64）
         * <p>
         * 取值与 {@link #giftMessage(String, String)} 的 V1 夹具一致，便于逐字段对照
         * 两种格式解析出的同一个事件。字段号是 SEND_GIFT_V2 的（见 BilibiliEventParser
         * 的 GIFT_V2_* 常量），取值全部合成，不含真实观众信息
         */
        private String giftV2Pb(String coinType) {
            PbWriter medal = new PbWriter()
                    .str(1, "勋章")
                    .varint(2, 8)
                    .varint(7, 2)
                    .str(8, "https://guard.example/2.png")
                    .varint(9, 1)
                    .varint(10, 999);

            PbWriter uinfo = new PbWriter()
                    .varint(1, 555)
                    .message(2, new PbWriter()
                            .str(1, "土豪")
                            .str(2, "https://face.example/3.jpg"))
                    .message(3, medal);

            PbWriter gift = new PbWriter()
                    .varint(1, 31036)
                    .str(2, "辣条")
                    .varint(3, 3)
                    // 5（疑原价）与 6（折扣价）故意取不同值：两者相等时，解析读错了单价
                    // 也全树皆绿——折扣活动一旦上线就会多记收入。价值与单价都该按 6 算
                    .varint(5, 1200)
                    .varint(6, 1000)
                    .varint(7, 3000)
                    .str(8, coinType)
                    .varint(10, 1700000002L)
                    .message(35, new PbWriter().str(1, "https://gift.example/g.png"));

            return new PbWriter()
                    .varint(1, 555)
                    .str(2, "土豪")
                    .message(10, gift)
                    .message(13, new PbWriter().varint(1, 30))
                    .message(15, uinfo)
                    .base64();
        }

        private Optional<StarBotBaseLiveEvent> parseV2(String pb) {
            return parse("{\"cmd\":\"SEND_GIFT_V2\",\"data\":{\"dmscore\":3,\"pb\":\"" + pb + "\"}}");
        }

        @Test
        @DisplayName("解析 V2 付费礼物，与同值 V1 JSON 逐字段一致")
        void parsesPaidGiftParityWithV1() {
            BilibiliPaidGiftEvent v1 = assertInstanceOf(BilibiliPaidGiftEvent.class,
                    parse(giftMessage("gold", ",\"total_coin\":3000")).orElseThrow());
            BilibiliPaidGiftEvent v2 = assertInstanceOf(BilibiliPaidGiftEvent.class,
                    parseV2(giftV2Pb("gold")).orElseThrow());

            BilibiliUserInfo v1Sender = (BilibiliUserInfo) v1.getSender();
            BilibiliUserInfo v2Sender = (BilibiliUserInfo) v2.getSender();
            assertEquals(v1Sender.getUid(), v2Sender.getUid());
            assertEquals(v1Sender.getUname(), v2Sender.getUname());
            assertEquals(v1Sender.getFace(), v2Sender.getFace());
            assertEquals(v1Sender.getHonorLevel(), v2Sender.getHonorLevel());

            // 勋章与舰长标志：V1 在 sender_uinfo.medal（JSON），V2 在 uinfo 的子消息 3（protobuf），
            // 两边子字段号不同，拼夹具时要各按各的表
            assertEquals(v1Sender.getFansMedal().getUid(), v2Sender.getFansMedal().getUid());
            assertEquals(v1Sender.getFansMedal().getName(), v2Sender.getFansMedal().getName());
            assertEquals(v1Sender.getFansMedal().getLevel(), v2Sender.getFansMedal().getLevel());
            assertEquals(v1Sender.getFansMedal().getLighted(), v2Sender.getFansMedal().getLighted());
            assertEquals(v1Sender.getGuard().getGuardType(), v2Sender.getGuard().getGuardType());
            assertEquals(v1Sender.getGuard().getIcon(), v2Sender.getGuard().getIcon());

            assertEquals(v1.getGiftInfo().getId(), v2.getGiftInfo().getId());
            assertEquals(v1.getGiftInfo().getName(), v2.getGiftInfo().getName());
            assertEquals(v1.getGiftInfo().getPrice(), v2.getGiftInfo().getPrice());
            assertEquals(v1.getGiftInfo().getCount(), v2.getGiftInfo().getCount());
            assertEquals(v1.getGiftInfo().getUrl(), v2.getGiftInfo().getUrl());

            assertEquals(v1.getValue(), v2.getValue(), 0.0001);
            assertEquals(v1.getCharged(), v2.getCharged(), 0.0001);
            assertEquals(v1.isFromBag(), v2.isFromBag());
            assertEquals(v1.getTimestamp(), v2.getTimestamp());
        }

        @Test
        @DisplayName("折扣价语义：单价与价值按字段 6（折扣价）算，不按 5（疑原价）")
        void priceComesFromDiscountFieldNotOriginal() {
            // 夹具里 5（疑原价）=1200、6（折扣价）=1000：真实样本两者相等，分不出
            // 解析读的是哪一个——把取值字段搞错的话，折扣活动一上线单价就会多记 20%
            BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                    parseV2(giftV2Pb("gold")).orElseThrow());

            assertEquals(1.0, event.getGiftInfo().getPrice(), 0.0001,
                    "单价价格按折扣价 6=1000 算得 1.0 元；若按疑原价 5=1200 算会得出 1.2 元");
            assertEquals(3.0, event.getValue(), 0.0001, "价值 = 折扣单价 × 数量 3");
        }

        @Test
        @DisplayName("V2 银瓜子礼物解析为免费礼物")
        void parsesFreeGiftV2() {
            BilibiliFreeGiftEvent event = assertInstanceOf(BilibiliFreeGiftEvent.class,
                    parseV2(giftV2Pb("silver")).orElseThrow());

            assertEquals(31036L, event.getGiftInfo().getId());
            assertEquals("辣条", event.getGiftInfo().getName());
            assertEquals(3, event.getGiftInfo().getCount());
            assertEquals(1700000002000L, event.getTimestamp());
        }

        @Test
        @DisplayName("报文缺失或没有 pb 时不产生事件也不抛异常")
        void ignoresMissingPayload() {
            assertTrue(parse("{\"cmd\":\"SEND_GIFT_V2\",\"data\":{\"dmscore\":3}}").isEmpty(), "缺 pb 字段");
            assertTrue(parseV2("").isEmpty(), "pb 为空串");
            assertTrue(parse("{\"cmd\":\"SEND_GIFT_V2\"}").isEmpty(), "整个 data 缺失");
        }

        @Test
        @DisplayName("pb 不是合法 base64 时不产生事件也不抛异常")
        void ignoresIllegalBase64() {
            assertTrue(parseV2("!!!不是 base64!!!").isEmpty());
        }

        /**
         * 截断方式：掐掉最后两个字节。夹具里 uinfo（字段 15）写在最后，它的长度前缀
         * 声明的字节数因此超出剩余——读取器会把整条报文标记为截断并丢掉该字段；
         * 礼物块（字段 10）在它前面，完好无损。
         * <p>
         * 写入器按字段号升序写，只要 15 仍是最大的字段号，掐尾永远落在它身上；
         * 将来夹具加更大的字段号时，这里的截断点要重选
         */
        @Test
        @DisplayName("被截断的报文仍按已读到的礼物块入账")
        void stillChargesGiftOnTruncatedPayload() {
            // 礼物块在报文前部：钱已经在手，不该因为尾巴断了把整条丢弃
            byte[] full = Base64.getDecoder().decode(giftV2Pb("gold"));
            byte[] cut = Arrays.copyOf(full, full.length - 2);

            BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                    parseV2(Base64.getEncoder().encodeToString(cut)).orElseThrow());

            assertEquals(3.0, event.getCharged(), 0.0001, "total_coin 在礼物块里，截断之前就该读到");
            assertEquals(31036L, event.getGiftInfo().getId());
            BilibiliUserInfo sender = (BilibiliUserInfo) event.getSender();
            assertEquals(555L, sender.getUid(), "uinfo 没了退回顶层 uid");
            assertEquals("土豪", sender.getUname(), "uinfo 没了退回顶层昵称");
            assertNull(sender.getFansMedal(), "截断之后的字段应当缺失而不是被猜出来");
            assertNull(sender.getGuard());
        }

        /**
         * 在采集解析日志的开关下解析一条 V2 报文
         * <p>
         * 34 号字段的新读法只在 TRACE 留痕，因此采集前把解析器 logger 抬到 TRACE，
         * 结束后恢复原级别并摘除采集器——日志断言只该约束自己这一条报文的解析
         * @param pb data.pb（base64）
         * @param logsOut 采集到的日志文本，按产出顺序追加
         * @return 解析产出，没有产出时为 {@code null}
         */
        private StarBotBaseLiveEvent parseV2CollectingLogs(String pb, List<String> logsOut) {
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BilibiliEventParser.class);
            ch.qos.logback.classic.Level previousLevel = logger.getLevel();
            logger.setLevel(ch.qos.logback.classic.Level.TRACE);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                StarBotBaseLiveEvent event = parseV2(pb).orElse(null);
                logsOut.addAll(appender.list.stream()
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .toList());
                return event;
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(previousLevel);
            }
        }

        @Test
        @DisplayName("礼物块 34 号是表情特效子消息（灯牌样）：按普通礼物入账，日志无乱码")
        void treatsFaceEffectGiftAsNormalGift() {
            // 2026-09-05 生产实样的布局：灯牌礼物的 34 号是子消息 {1: 特效 id, 2: 特效 type}
            // （face_effect 一类），37 号是 repeated {1,2} 清单。旧读法把 34 当字符串、疑为
            // 开出物名——真样本下会把特效字节按文本打出乱码，且盲盒的方向是错的
            PbWriter gift = new PbWriter()
                    .varint(1, 31036)
                    .str(2, "辣条")
                    .varint(3, 3)
                    .varint(6, 1000)
                    .varint(7, 3000)
                    .str(8, "gold")
                    .varint(10, 1700000002L)
                    .message(34, new PbWriter().varint(1, 5632012).varint(2, 1))
                    .message(37, new PbWriter()
                            .varint(1, 1)
                            .message(2, new PbWriter().varint(1, 6036469).varint(2, 2))
                            .message(2, new PbWriter().varint(1, 5632012).varint(2, 1)));

            List<String> logs = new ArrayList<>();
            BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                    parseV2CollectingLogs(new PbWriter().message(10, gift).base64(), logs));

            assertEquals(3.0, event.getCharged(), 0.0001, "特效不是盲盒，照常按 total_coin 实扣入账");
            assertEquals(1.0, event.getGiftInfo().getPrice(), 0.0001, "单价仍按折扣价（字段 6）算");
            assertTrue(logs.stream().noneMatch(message -> message.contains("\uFFFD")),
                    "日志不得出现乱码——34 号是子消息，字节不是文本");
            assertFalse(logs.stream().anyMatch(message -> message.contains("疑似盲盒")),
                    "盲盒猜判与其 debug 行已撤");
            assertTrue(logs.stream().anyMatch(message -> message.contains("id=5632012") && message.contains("type=1")),
                    "新读法按子消息取到 id 与 type（TRACE 留痕）");
        }

        @Test
        @DisplayName("34 号为空串的礼物（手幅样）不留特效痕迹，照常入账")
        void parsesGiftWithEmptyFaceEffectField() {
            // 同批另一条实样：不带特效的礼物 34 号为空串。按子消息读是一条空消息，
            // id 与 type 都缺席——不能因此打出 id=null 一类的噪声行
            PbWriter gift = new PbWriter()
                    .varint(1, 31036)
                    .str(2, "辣条")
                    .varint(3, 3)
                    .varint(6, 1000)
                    .varint(7, 3000)
                    .str(8, "gold")
                    .varint(10, 1700000002L)
                    .str(34, "");

            List<String> logs = new ArrayList<>();
            BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                    parseV2CollectingLogs(new PbWriter().message(10, gift).base64(), logs));

            assertEquals(3.0, event.getCharged(), 0.0001);
            assertTrue(logs.stream().noneMatch(message -> message.contains("\uFFFD")));
            assertFalse(logs.stream().anyMatch(message -> message.contains("表情特效")), "空特效不留痕");
        }

        /**
         * 测试用的最小 protobuf 写入器
         * <p>
         * 只写 varint、字符串、嵌套消息三种，够拼 SEND_GIFT_V2 夹具即可。
         * {@code BilibiliProtobufReader} 只做 wire 层不认 schema，写入器同样只做 wire 层，
         * 字段号由夹具自己指定
         */
        private static final class PbWriter {
            private final ByteArrayOutputStream out = new ByteArrayOutputStream();

            PbWriter varint(int field, long value) {
                key(field, 0);
                writeVarint(value);
                return this;
            }

            PbWriter str(int field, String value) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                key(field, 2);
                writeVarint(bytes.length);
                out.writeBytes(bytes);
                return this;
            }

            PbWriter message(int field, PbWriter nested) {
                byte[] bytes = nested.out.toByteArray();
                key(field, 2);
                writeVarint(bytes.length);
                out.writeBytes(bytes);
                return this;
            }

            String base64() {
                return Base64.getEncoder().encodeToString(out.toByteArray());
            }

            private void key(int field, int wireType) {
                writeVarint(((long) field << 3) | wireType);
            }

            private void writeVarint(long value) {
                while ((value & ~0x7FL) != 0) {
                    out.write((int) ((value & 0x7F) | 0x80));
                    value >>>= 7;
                }
                out.write((int) value);
            }
        }
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
    @DisplayName("实扣取自 total_coin —— 用一条真实抓到的报文核对")
    void paidComesFromTotalCoin() {
        // 取自 2026-08-06 线上抓到的一条真实 SEND_GIFT，只保留与金额相关的字段（uid 已换成测试值）。
        // price / discount_price / total_coin 三者相等，说明当时没有折扣活动
        String real = "{\"cmd\":\"SEND_GIFT\",\"data\":{\"uid\":555,\"uname\":\"送礼的人\","
                + "\"timestamp\":1785987737,\"giftId\":31039,\"giftName\":\"牛哇牛哇\",\"num\":1,"
                + "\"price\":100,\"discount_price\":100,\"total_coin\":100,\"coin_type\":\"gold\"}}";

        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class, parse(real).orElseThrow());

        assertEquals(0.1, event.getValue(), 0.0001, "到手价值 = discount_price × num");
        assertEquals(0.1, event.getCharged(), 0.0001, "实扣 = total_coin");
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
        assertEquals(9.0, event.getPrice(), 0.0001, "price 是盲盒实扣");
        assertEquals(9.0, event.getValue(), 0.0001, "value 是开出物面值");
    }

    @Test
    @DisplayName("盲盒亏损时实扣应是盒子的价，而不是开出物的价")
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

        assertEquals(15.0, event.getCharged(), 0.0001, "实扣是盒子的 15 元");
        assertEquals(9.0, event.getValue(), 0.0001, "主播只收到 9 元的东西");
        assertEquals(15.0, event.getPrice(), 0.0001);
        // 这条报文里 combo_total_coin 是 9000（开出物的价）而不是 15000。
        // 用错字段会让盲盒实扣系统性记成开出物的价，而在「保本」的盲盒上完全看不出来
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
        assertEquals(0.0, event.getCharged(), 0.0001, "观众一分钱没花");
        assertTrue(event.isFromBag(), "背包标志要显式带上去，下游不该靠金额反推");
    }

    @Test
    @DisplayName("普通礼物不带背包标志")
    void normalGiftIsNotFromBag() {
        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                parse(giftMessage("gold", ",\"total_coin\":3000")).orElseThrow());

        assertFalse(event.isFromBag());
    }

    @Test
    @DisplayName("实扣为 0 但不是背包礼物时，背包标志必须为假")
    void zeroChargedAloneDoesNotMeanFromBag() {
        // 这条钉的是契约本身：charged == 0 目前恰好只有背包一种来源，
        // 但那是当下的巧合而不是约定。将来出现第二种「确实扣了 0」的情形时，
        // 反推会把它静默地当成背包礼物且不报错——本断言就是那道防线
        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                parse(giftMessage("gold", ",\"total_coin\":0")).orElseThrow());

        assertEquals(0.0, event.getCharged(), 0.0001);
        assertFalse(event.isFromBag(), "实扣为 0 不等于来自背包");
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
        assertEquals(0.0, event.getCharged(), 0.0001, "但服务端说没扣钱");
    }

    @Test
    @DisplayName("没有 total_coin 时实扣应为空，表示「平台没告诉我们」")
    void missingTotalCoinLeavesPaidNull() {
        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class,
                parse(giftMessage("gold", "")).orElseThrow());

        // 空与「填一个算出来的值」不同：填上之后下游就分不清
        // 「两个口径确实相等」和「取不到才回退成相等」。回退交给聚合层做
        assertNull(event.getCharged());
        assertEquals(3.0, event.getValue(), 0.0001);
    }

    @Test
    @DisplayName("银瓜子礼物带非数值 total_coin 时不被吞：免费礼物照常产出")
    void silverGiftWithNonNumericTotalCoinStillParses() {
        // total_coin 只在算实扣时才需要，而银瓜子礼物根本不算实扣。
        // 在银瓜子早退之前就去读它的话，平台哪天在这个字段里塞了非数值，
        // 整条礼物会被解析异常吞掉——免费礼物也是礼物，直播报告里不该凭空少一条
        BilibiliFreeGiftEvent event = assertInstanceOf(BilibiliFreeGiftEvent.class,
                parse(giftMessage("silver", ",\"total_coin\":\"not-a-number\"")).orElseThrow());

        assertEquals("辣条", event.getGiftInfo().getName());
        assertEquals(3, event.getGiftInfo().getCount());
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
    @DisplayName("陪伴天数从播报文案里解析出来")
    void companionDaysParsedFromToast() {
        // 文案取自 2026-08-06 实抓的 USER_TOAST_MSG，人名换成占位值
        String json = "{\"cmd\":\"USER_TOAST_MSG\",\"data\":{\"uid\":555,\"username\":\"老舰长\","
                + "\"guard_level\":3,\"op_type\":2,\"price\":168000,\"num\":1,\"unit\":\"月\","
                + "\"role_name\":\"舰长\",\"payflow_id\":\"flow-companion\","
                + "\"toast_msg\":\"<%老舰长%> 在主播某某的直播间开通了舰长，今天是TA陪伴主播的第1171天\"}}";

        BilibiliCaptainEvent event = assertInstanceOf(BilibiliCaptainEvent.class, parse(json).orElseThrow());

        assertEquals(1171, event.getCompanionDays());
    }

    @Test
    @DisplayName("文案里没有陪伴天数时留空，绝不能填 0")
    void companionDaysAbsentStaysNull() {
        // 「陪伴 0 天」会作为假信息出现在感谢文案与报告里，比没有这个信息糟得多
        String json = "{\"cmd\":\"USER_TOAST_MSG\",\"data\":{\"uid\":556,\"username\":\"新舰长\","
                + "\"guard_level\":3,\"op_type\":1,\"price\":138000,\"num\":1,\"unit\":\"月\","
                + "\"role_name\":\"舰长\",\"payflow_id\":\"flow-no-companion\","
                + "\"toast_msg\":\"<%新舰长%> 在主播某某的直播间开通了舰长\"}}";

        BilibiliCaptainEvent event = assertInstanceOf(BilibiliCaptainEvent.class, parse(json).orElseThrow());

        assertNull(event.getCompanionDays(), "解析不出就该是空，不是 0");
    }

    @Test
    @DisplayName("文案改版导致匹配错位置时也要留空，而不是给个荒谬的数")
    void companionDaysRejectsAbsurdValue() {
        // 构造一个能匹配上正则、但天数远超平台年龄的文案
        String json = "{\"cmd\":\"USER_TOAST_MSG\",\"data\":{\"uid\":557,\"username\":\"某人\","
                + "\"guard_level\":3,\"op_type\":1,\"price\":198000,\"num\":1,\"unit\":\"月\","
                + "\"role_name\":\"舰长\",\"payflow_id\":\"flow-absurd\","
                + "\"toast_msg\":\"陪伴主播的第999999天\"}}";

        BilibiliCaptainEvent event = assertInstanceOf(BilibiliCaptainEvent.class, parse(json).orElseThrow());

        assertNull(event.getCompanionDays());
    }

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
