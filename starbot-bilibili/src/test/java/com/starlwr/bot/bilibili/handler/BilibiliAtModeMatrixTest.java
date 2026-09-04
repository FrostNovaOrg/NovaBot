package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.painter.BilibiliDynamicPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.sender.AtAllPermissionResolver;
import com.starlwr.bot.core.sender.AtMode;
import com.starlwr.bot.core.sender.FirstPushTipService;
import com.starlwr.bot.core.sender.PushGate;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.AtAllQuotaService;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.timeline.TimelineEventType;
import com.starlwr.bot.core.timeline.TimelineWriter;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @ 模式矩阵
 * <p>
 * 三种模式 ×「管理员且有额度／非管理员／额度用尽」× 开播与动态两类通知，共十八格，
 * 每格量三个数：<b>谁被 @ 到、发出去几条、时间线记了几条</b>。
 * <p>
 * 第二个数原本量的是「有没有退回订阅名单」，读法是「@ 串独占一条」。@ 串并进正文之后
 * 那个读法量不到了——退回的那一格与模式一在发出去的字面上完全同形，分得开它们的是
 * 时间线那一列。改量条数不是退让：<b>「@ 全体那一档只发一条」正是这次要守的事</b>，
 * 而它在旧读法下恒为假、没有任何一格看得见。
 * <p>
 * <b>为什么要整张表而不是挑几格</b>：这三件事分别落在两处代码——「@ 块怎么拼」在推送处理器，
 * 「@ 不出去时怎么办」在发送器，而模式是唯一把它们串起来的东西。只验几格的话，
 * 某一格的错法（例如模式二也退回订阅名单、或模式三静悄悄谁都不 @）在别的格子里看不见。
 * <p>
 * 走的是<b>真的发送器</b>：真的配额服务、真的权限判定、真的时间线。处理器与发送器之间
 * 用一个替身把消息接下来、再逐条交给真发送器的 {@code sendNow}——那一头绕开的只是队列与
 * 静音闸门（都与 @ 谁无关），@ 的处理是两条路共用的同一段。
 */
@DisplayName("@ 模式矩阵")
class BilibiliAtModeMatrixTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    private static final long STREAMER_UID = 10001L;

    private static final long ROOM_ID = 20002L;

    /**
     * 订阅了提醒的两个人，以及他们拼出来的 @ 串
     */
    private static final List<Long> SUBSCRIBERS = List.of(1001L, 1002L);

    private static final String SUBSCRIBER_AT = "{at=1001}{at=1002}";

    private static final String AT_ALL = "{at=all}";

    /**
     * 开播那条通知渲染出来的正文（昵称之外的取值都取不到，与 @ 谁无关）
     */
    private static final String LIVE_BODY = "主播甲 正在直播 \nhttps://live.bilibili.com/" + ROOM_ID;

    /**
     * 通知类别
     */
    private enum Notice {
        LIVE("开播"),
        DYNAMIC("动态");

        private final String label;

        Notice(String label) {
            this.label = label;
        }
    }

    /**
     * 发送那一刻的处境
     */
    private enum Situation {
        ADMIN_WITH_QUOTA("管理员且有额度", true, false),
        NOT_ADMIN("非管理员", false, false),
        QUOTA_EXHAUSTED("额度用尽", true, true);

        private final String label;

        private final boolean admin;

        private final boolean exhausted;

        Situation(String label, boolean admin, boolean exhausted) {
            this.label = label;
            this.admin = admin;
            this.exhausted = exhausted;
        }
    }

    /**
     * 最终 @ 到了谁
     */
    private enum AtWho {
        NOBODY,
        EVERYONE,
        SUBSCRIBERS
    }

    /**
     * 一格的读数
     * @param who 谁被 @ 到
     * @param segments 这次推送真正投出去几条消息
     * @param timeline 本次推送记下的「未 @ 全体」事件条数
     */
    private record Reading(AtWho who, int segments, int timeline) {
    }

    @Test
    @DisplayName("十八格逐格对表：谁被 @、发出去几条、时间线条数")
    void matrix() {
        Map<String, Reading> expected = new LinkedHashMap<>();
        for (Notice notice : Notice.values()) {
            // 模式一从头到尾不碰 @全体成员，因此权限与额度怎么变都影响不到它
            for (Situation situation : Situation.values()) {
                expected.put(key(AtMode.SUBSCRIBERS, situation, notice),
                        new Reading(AtWho.SUBSCRIBERS, 1, 0));
            }

            // 模式二：@ 得出去就 @ 全体，@ 不出去就谁也不 @，两种拦法各记一条
            expected.put(key(AtMode.ALL, Situation.ADMIN_WITH_QUOTA, notice),
                    new Reading(AtWho.EVERYONE, 1, 0));
            expected.put(key(AtMode.ALL, Situation.NOT_ADMIN, notice),
                    new Reading(AtWho.NOBODY, 1, 1));
            expected.put(key(AtMode.ALL, Situation.QUOTA_EXHAUSTED, notice),
                    new Reading(AtWho.NOBODY, 1, 1));

            // 模式三：@ 不出去的那一次改 @ 订阅名单，同样各记一条
            expected.put(key(AtMode.ALL_OR_SUBSCRIBERS, Situation.ADMIN_WITH_QUOTA, notice),
                    new Reading(AtWho.EVERYONE, 1, 0));
            expected.put(key(AtMode.ALL_OR_SUBSCRIBERS, Situation.NOT_ADMIN, notice),
                    new Reading(AtWho.SUBSCRIBERS, 1, 1));
            expected.put(key(AtMode.ALL_OR_SUBSCRIBERS, Situation.QUOTA_EXHAUSTED, notice),
                    new Reading(AtWho.SUBSCRIBERS, 1, 1));
        }

        Map<String, Reading> actual = new LinkedHashMap<>();
        for (Notice notice : Notice.values()) {
            for (AtMode mode : AtMode.values()) {
                for (Situation situation : Situation.values()) {
                    Harness harness = new Harness(situation);
                    List<String> sent = harness.run(params(mode, notice, null), notice);
                    actual.put(key(mode, situation, notice),
                            new Reading(whoIn(sent), sent.size(), harness.skipped));
                }
            }
        }

        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("模式三退回时，@ 串就地换成订阅名单，正文一字不动，仍是一条")
    void fallbackReplacesInPlace() {
        assertEquals(List.of(SUBSCRIBER_AT + LIVE_BODY),
                send(AtMode.ALL_OR_SUBSCRIBERS, Situation.NOT_ADMIN, Notice.LIVE, null));
    }

    @Test
    @DisplayName("模式二被摘时，@ 串就地消失，剩下的正文照发")
    void modeAllDropsTheAtBlockOnly() {
        assertEquals(List.of(LIVE_BODY), send(AtMode.ALL, Situation.QUOTA_EXHAUSTED, Notice.LIVE, null));
    }

    @Test
    @DisplayName("@全体成员 与正文同处一条：群里只响一次")
    void atAllRidesInTheBody() {
        assertEquals(List.of(AT_ALL + LIVE_BODY),
                send(AtMode.ALL, Situation.ADMIN_WITH_QUOTA, Notice.LIVE, null));
    }

    @Test
    @DisplayName("模式一不补 @ 块两次：使用者自己写的 {at} 说了算")
    void keepsHandWrittenAtPlaceholder() {
        assertEquals(List.of("主播甲 说：" + SUBSCRIBER_AT),
                send(AtMode.SUBSCRIBERS, Situation.ADMIN_WITH_QUOTA, Notice.LIVE, "{uname} 说：{at}"));
    }

    @Test
    @DisplayName("模板里已经有 {at} 时，模式三不再退回同一批人")
    void doesNotAtSubscribersTwice() {
        assertEquals(List.of(SUBSCRIBER_AT + "主播甲 正在直播"),
                send(AtMode.ALL_OR_SUBSCRIBERS, Situation.NOT_ADMIN, Notice.LIVE, "{at}{uname} 正在直播"));
    }

    @Test
    @DisplayName("旧配置里的 at_all 仍然当「@全体成员」用")
    void legacyAtAllStillMeansEveryone() {
        JSONObject params = defaultParams(Notice.LIVE);
        params.put(AtMode.LEGACY_PARAM_KEY, true);

        assertEquals(AtWho.EVERYONE,
                whoIn(new Harness(Situation.ADMIN_WITH_QUOTA).run(params, Notice.LIVE)));
    }

    @Test
    @DisplayName("新键写过就以新键为准，旧键不再顶事")
    void newKeyWinsOverLegacy() {
        JSONObject params = params(AtMode.SUBSCRIBERS, Notice.LIVE, null);
        params.put(AtMode.LEGACY_PARAM_KEY, true);

        assertEquals(AtWho.SUBSCRIBERS,
                whoIn(new Harness(Situation.ADMIN_WITH_QUOTA).run(params, Notice.LIVE)));
    }

    private String key(AtMode mode, Situation situation, Notice notice) {
        return notice.label + " · " + mode.key() + " · " + situation.label;
    }

    private List<String> send(AtMode mode, Situation situation, Notice notice, String template) {
        return new Harness(situation).run(params(mode, notice, template), notice);
    }

    /**
     * 从真正发出去的内容里判断 @ 到了谁
     */
    private AtWho whoIn(List<String> sent) {
        String all = String.join("\n", sent);
        if (all.contains(AT_ALL)) {
            return AtWho.EVERYONE;
        }
        return all.contains("{at=1001}") ? AtWho.SUBSCRIBERS : AtWho.NOBODY;
    }

    private JSONObject params(AtMode mode, Notice notice, String template) {
        JSONObject params = defaultParams(notice);
        params.put(AtMode.PARAM_KEY, mode.key());
        if (template != null) {
            params.put("message", template);
        }
        return params;
    }

    /**
     * 取处理器自己的默认参数，不手抄——手抄的那一份改了默认模板也不会红
     */
    private JSONObject defaultParams(Notice notice) {
        return notice == Notice.LIVE
                ? new BilibiliLiveOnPushHandler(mock(BilibiliApiUtil.class), mock(StarBotMessageSender.class),
                        mock(AtSubscriptionService.class), mock(LiveDataService.class)).getDefaultParams()
                : new BilibiliDynamicPushHandler(mock(BilibiliApiUtil.class), mock(BilibiliDynamicPainter.class),
                        mock(StarBotMessageSender.class), mock(AtSubscriptionService.class),
                        mock(LiveDataService.class)).getDefaultParams();
    }

    /**
     * 一次推送的台架：处理器造消息、真发送器发消息、时间线数条数
     */
    private static final class Harness {
        private final Situation situation;

        private int skipped;

        private Harness(Situation situation) {
            this.situation = situation;
        }

        /**
         * 跑一次推送，返回真正投递出去的每一条内容
         */
        private List<String> run(JSONObject params, Notice notice) {
            List<Message> produced = new ArrayList<>();
            StarBotMessageSender collector = mock(StarBotMessageSender.class);
            doAnswer(invocation -> produced.add(invocation.getArgument(0))).when(collector).send(any());

            handle(collector, params, notice);

            List<String> sent = new ArrayList<>();
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), any(), any())).thenAnswer(invocation -> {
                Map<String, Object> posted = invocation.getArgument(2);
                sent.add(String.valueOf(posted.get("content")));
                return new JSONObject().fluentPut("code", 0).fluentPut("id", "1");
            });

            StarBotMessageSender real = realSender(http);
            produced.forEach(real::sendNow);
            return sent;
        }

        /**
         * 让推送处理器把消息造出来
         */
        private void handle(StarBotMessageSender collector, JSONObject params, Notice notice) {
            BilibiliApiUtil api = mock(BilibiliApiUtil.class);
            // 昵称与直播间信息都取不到：本类量的是 @ 谁，与这两者无关
            when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
            when(api.getLiveInfoByRoomId(anyLong())).thenThrow(new RuntimeException("接口不可用"));

            AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
            when(subscriptions.list(anyString(), anyLong(), anyLong(), anyString())).thenReturn(SUBSCRIBERS);

            LiveStreamerInfo source = new LiveStreamerInfo(STREAMER_UID, "主播甲", ROOM_ID);
            if (notice == Notice.LIVE) {
                new BilibiliLiveOnPushHandler(api, collector, subscriptions, mock(LiveDataService.class))
                        .handle(new BilibiliLiveOnEvent(source), pushMessage(params));
                return;
            }

            BilibiliDynamicPainter painter = mock(BilibiliDynamicPainter.class);
            when(painter.paint(any())).thenReturn(Optional.empty());
            new BilibiliDynamicPushHandler(api, painter, collector, subscriptions, mock(LiveDataService.class))
                    .handle(new BilibiliDynamicUpdateEvent(source, mock(Dynamic.class), "发布了动态",
                            "https://t.bilibili.com/1"), pushMessage(params));
        }

        private PushMessage pushMessage(JSONObject params) {
            PushTarget target = new PushTarget();
            target.setPlatform(PLATFORM);
            target.setType(PushTargetType.GROUP);
            target.setNum(GROUP);

            PushMessage message = new PushMessage();
            message.setTarget(target);
            message.setParamsJsonObject(params);
            return message;
        }

        /**
         * 真发送器：真配额、真权限判定、真时间线
         */
        private StarBotMessageSender realSender(HttpUtil http) {
            Sender platform = new Sender();
            platform.setName(PLATFORM);
            platform.setUrl("http://127.0.0.1:7827/onebot/send");
            platform.setDelay(0);

            StarBotSenderService senderService = mock(StarBotSenderService.class);
            when(senderService.getSender(PLATFORM)).thenReturn(Optional.of(platform));

            StarBotCoreProperties properties = new StarBotCoreProperties();
            properties.getPush().setAtAllDailyLimit(1);
            AtAllQuotaService quota = new AtAllQuotaService(properties);
            if (situation.exhausted) {
                // 把那一次额度先花掉，于是本次推送撞上的是真的「今天用完了」
                quota.tryConsume(PLATFORM, GROUP);
            }

            TimelineWriter timeline = event -> {
                if (TimelineEventType.AT_ALL_SKIPPED == event.type()) {
                    skipped++;
                }
            };

            return new StarBotMessageSender(http, senderService, new PushActivityRecorder(TimelineWriter.NONE),
                    new PushGate(properties), timeline, quota, resolvers(situation.admin),
                    new FirstPushTipService(new StarBotStateStore(properties)));
        }

        private ObjectProvider<AtAllPermissionResolver> resolvers(boolean admin) {
            @SuppressWarnings("unchecked")
            ObjectProvider<AtAllPermissionResolver> mocked = mock(ObjectProvider.class);
            List<AtAllPermissionResolver> list = List.of(new AtAllPermissionResolver() {
                @Override
                public boolean supports(String platform) {
                    return PLATFORM.equals(platform);
                }

                @Override
                public boolean canAtAll(String platform, Long num) {
                    return admin;
                }
            });
            when(mocked.iterator()).thenAnswer(invocation -> list.iterator());
            return mocked;
        }
    }
}
