package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.painter.BilibiliDynamicPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.handler.StarBotEventHandlerPushMessageInitializer;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.sender.AtAllPermissionResolver;
import com.starlwr.bot.core.sender.FirstPushTipService;
import com.starlwr.bot.core.sender.PushGate;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.AtAllQuotaService;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.timeline.TimelineWriter;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 开播与动态通知合成一条
 * <p>
 * 默认模板里的 {@code {next}} 曾经是文字的可达性保护：封面拉不到会让整条发送失败，
 * 分成两条时文字才幸存。发送侧的兜底落地之后那个理由已经不成立
 * （含图失败会剥掉图片段重发纯文字），剩下的只是观感——而两条通知在群里是两次提示音。
 * <p>
 * <b>难的不是改默认值，是改到已经存在的配置上。</b>使用者的推送参数是整份存下来的，
 * 里面那串 {@code message} 十有八九就是当初界面预填的旧默认值；不迁的话，
 * 改了默认值也只对<b>今后新建的推送</b>生效，而现有的推送一条也不会变。
 * 迁移的判据只有一个：<b>那串字与某一版旧默认值一模一样</b>——
 * 一字不差才算「没改过」，改过的一律不动。
 * <p>
 * 三格并排：<b>旧默认要迁（两版都要）、改过的不许动、合并之后带封面也只发一条</b>。
 * 少了第三格，前两格全绿也可能是「迁到了另一个照样分两条的模板」。
 */
@DisplayName("通知合成一条")
class MergedNoticeTemplateTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    private static final long STREAMER_UID = 10001L;

    private static final long ROOM_ID = 20002L;

    private static final String COVER = "https://i0.hdslb.com/bfs/live/new_room_cover/abc.jpg";

    /**
     * 历史上发过的两版开播默认模板：带 {@code {at}} 的与不带的
     *
     * @see #legacyDefaultsAreTheOnesWeReallyShipped 这两串不许凭印象写
     */
    private static final List<String> LEGACY_LIVE = List.of(
            "{at}{uname} 正在直播 {title}\n{url}{next}{cover}",
            "{uname} 正在直播 {title}\n{url}{next}{cover}");

    private static final List<String> LEGACY_DYNAMIC = List.of(
            "{at}{uname} {action}\n{url}{next}{picture}",
            "{uname} {action}\n{url}{next}{picture}");

    private StarBotEventHandler liveOn() {
        return new BilibiliLiveOnPushHandler(mock(BilibiliApiUtil.class), mock(StarBotMessageSender.class),
                mock(AtSubscriptionService.class), mock(LiveDataService.class));
    }

    private StarBotEventHandler dynamic() {
        return new BilibiliDynamicPushHandler(mock(BilibiliApiUtil.class), mock(BilibiliDynamicPainter.class),
                mock(StarBotMessageSender.class), mock(AtSubscriptionService.class), mock(LiveDataService.class));
    }

    /**
     * 让数据源那一侧把存着的参数加载一遍，返回加载后真正生效的消息模板
     * <p>
     * 走的是真的初始化器：迁移只有落在这条路上才对<b>已经存在的配置</b>生效，
     * 写在处理器里的判断只对新建的推送生效，而那正是本格要防的那种绿。
     */
    private String effectiveMessage(StarBotEventHandler handler, String stored) {
        StarBotEventHandlerService service = mock(StarBotEventHandlerService.class);
        when(service.getHandler(handler.getClass().getName())).thenReturn(Optional.of(handler));

        JSONObject saved = new JSONObject();
        saved.put("message", stored);

        PushMessage message = new PushMessage();
        message.setHandler(handler.getClass().getName());
        message.setParams(saved.toJSONString());

        assertTrue(new StarBotEventHandlerPushMessageInitializer(service).initialize(message),
                "初始化器没认出这个处理器，本格什么也没量到");
        return message.getParamsJsonObject().getString("message");
    }

    @Test
    @DisplayName("新的默认模板里不再有 {next}")
    void defaultTemplateNoLongerSplits() {
        for (StarBotEventHandler handler : List.of(liveOn(), dynamic())) {
            String template = handler.getDefaultParams().getString("message");
            assertFalse(template.contains("{next}"),
                    handler.displayName() + "的默认模板仍然分条: " + template);
        }
    }

    @Test
    @DisplayName("阳性：两版旧默认值都要迁到新默认")
    void legacyDefaultsMigrate() {
        StarBotEventHandler live = liveOn();
        String liveNow = live.getDefaultParams().getString("message");
        for (String legacy : LEGACY_LIVE) {
            assertEquals(liveNow, effectiveMessage(live, legacy),
                    "存着的是旧默认值「" + legacy + "」，没改过的配置应当跟着新默认走");
        }

        StarBotEventHandler dynamic = dynamic();
        String dynamicNow = dynamic.getDefaultParams().getString("message");
        for (String legacy : LEGACY_DYNAMIC) {
            assertEquals(dynamicNow, effectiveMessage(dynamic, legacy),
                    "存着的是旧默认值「" + legacy + "」，没改过的配置应当跟着新默认走");
        }
    }

    @Test
    @DisplayName("阴性：使用者改过的模板一个字都不许动")
    void customisedTemplatesAreLeftAlone() {
        // 四种「像旧默认但不是」：多一个空格、少一个占位符、换了措辞、只是把 {next} 自己去掉了
        List<String> customised = List.of(
                "{uname} 正在直播  {title}\n{url}{next}{cover}",
                "{uname} 正在直播\n{url}{next}{cover}",
                "{uname} 开播啦 {title}\n{url}{next}{cover}",
                "{uname} 正在直播 {title}\n{url}{cover}{next}");

        StarBotEventHandler live = liveOn();
        for (String template : customised) {
            assertEquals(template, effectiveMessage(live, template),
                    "改过的模板被迁走了，使用者的配置就此丢失: " + template);
        }
    }

    @Test
    @DisplayName("判据自己先能认出「没迁」：拿新默认当旧默认写一遍，阳性格必须报不相等")
    void theRulerRecognisesAFailedMigration() {
        StarBotEventHandler live = liveOn();
        String custom = "{uname} 自己写的模板 {url}";
        assertNotEquals(live.getDefaultParams().getString("message"), effectiveMessage(live, custom),
                "连自定义模板都被当成迁移结果，阳性那一格就是恒真绿");
    }

    @Test
    @DisplayName("旧默认串不是凭印象写的：本件列的两版必须都能在处理器的旧默认表里找到")
    void legacyDefaultsAreTheOnesWeReallyShipped() {
        assertEquals(LEGACY_LIVE, liveOn().supersededDefaults().getOrDefault("message", List.of()),
                "本件列的旧默认串与处理器自报的对不上，两边总有一边是凭印象写的");
        assertEquals(LEGACY_DYNAMIC, dynamic().supersededDefaults().getOrDefault("message", List.of()));
    }

    @Test
    @DisplayName("合并之后，带封面的开播通知只发一条")
    void liveNoticeWithCoverIsASingleMessage() {
        List<Message> produced = new ArrayList<>();
        StarBotMessageSender collector = mock(StarBotMessageSender.class);
        doAnswer(invocation -> produced.add(invocation.getArgument(0))).when(collector).send(any());

        Room room = new Room();
        room.setTitle("今天也在直播");
        room.setCover(COVER);

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));

        AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
        when(subscriptions.list(anyString(), anyLong(), anyLong(), anyString())).thenReturn(List.of());

        BilibiliLiveOnPushHandler handler = new BilibiliLiveOnPushHandler(api, collector, subscriptions,
                mock(LiveDataService.class));
        handler.handle(new BilibiliLiveOnEvent(new LiveStreamerInfo(STREAMER_UID, "主播甲", ROOM_ID)),
                pushMessage(handler.getDefaultParams()));

        assertEquals(1, produced.size(),
                "文字与封面应合成一条，实际发出 " + produced.size() + " 条: "
                        + produced.stream().map(Message::getContent).toList());
        assertTrue(produced.get(0).getContent().contains(COVER),
                "合成的那一条里应当带着封面: " + produced.get(0).getContent());
    }

    @Test
    @DisplayName("对照：分两条的旧模板下，封面拉不到那一次一个数也记不到")
    void theSplitTemplateNeverCountedImageDegradation() {
        LiveDataService liveData = mock(LiveDataService.class);
        List<String> sent = runWithFailingCover(LEGACY_LIVE.get(1), liveData);

        // 两条各投一次，其中含图那条失败后**没有**重发：剥掉图片段之后什么都不剩，
        // 发送侧按「没有内容可发」放弃。合并那一格投出去的也是两次（原内容 + 纯文字重发），
        // 所以「投了几次」在这两种情形上长得一样——分得开它们的只有下面那一句
        assertEquals(List.of("主播甲 正在直播 今天也在直播\nhttps://live.bilibili.com/" + ROOM_ID,
                        "{image_url=" + COVER + "}"),
                sent, "分两条时第二条是纯封面, 且没有第三次投递（也就是没有纯文字重发）");
        verifyNoInteractions(liveData);
    }

    @Test
    @DisplayName("合并之后，封面拉不到那一次终于记得成「图片降级」")
    void coverFailureNowCountsAsImageDegraded() {
        LiveDataService liveData = mock(LiveDataService.class);
        List<String> sent = runWithFailingCover(liveOn().getDefaultParams().getString("message"), liveData);

        assertEquals(2, sent.size(), "原内容一次、剥掉图片段的纯文字一次: " + sent);
        verify(liveData).incrementLiveMetric(anyString(), anyLong(),
                eq(BilibiliLiveMetric.IMAGE_DEGRADED_COUNT), eq(1.0d));
    }

    /**
     * 用指定模板推一次开播，封面那一段在投递时必定失败
     * <p>
     * 失败形态照 2026-08-11 那次实测：图片下载失败让<b>整条</b>失败，且响应不带消息 id。
     * 纯文字的那一趟照常送达。
     * @return 真正投出去的每一条内容，按先后顺序
     */
    private List<String> runWithFailingCover(String template, LiveDataService liveData) {
        List<Message> produced = new ArrayList<>();
        StarBotMessageSender collector = mock(StarBotMessageSender.class);
        doAnswer(invocation -> produced.add(invocation.getArgument(0))).when(collector).send(any());

        Room room = new Room();
        room.setTitle("今天也在直播");
        room.setCover(COVER);

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));

        AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
        when(subscriptions.list(anyString(), anyLong(), anyLong(), anyString())).thenReturn(List.of());

        BilibiliLiveOnPushHandler handler = new BilibiliLiveOnPushHandler(api, collector, subscriptions, liveData);
        JSONObject params = handler.getDefaultParams();
        params.put("message", template);
        handler.handle(new BilibiliLiveOnEvent(new LiveStreamerInfo(STREAMER_UID, "主播甲", ROOM_ID)),
                pushMessage(params));

        List<String> sent = new ArrayList<>();
        HttpUtil http = mock(HttpUtil.class);
        when(http.postJson(anyString(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> posted = invocation.getArgument(2);
            String content = String.valueOf(posted.get("content"));
            sent.add(content);
            return content.contains("{image_url=")
                    ? new JSONObject().fluentPut("code", 2).fluentPut("message", "下载文件失败: Not Found")
                    : new JSONObject().fluentPut("code", 0).fluentPut("id", "1");
        });

        StarBotMessageSender real = realSender(http);
        produced.forEach(real::sendNow);
        return sent;
    }

    /**
     * 真发送器：真闸门、真配额、真时间线口子（本件只用它走一次「失败后剥图重发」）
     */
    private StarBotMessageSender realSender(HttpUtil http) {
        Sender platform = new Sender();
        platform.setName(PLATFORM);
        platform.setUrl("http://127.0.0.1:7827/onebot/send");
        platform.setDelay(0);

        StarBotSenderService senderService = mock(StarBotSenderService.class);
        when(senderService.getSender(PLATFORM)).thenReturn(Optional.of(platform));

        @SuppressWarnings("unchecked")
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        when(resolvers.iterator()).thenAnswer(invocation -> List.<AtAllPermissionResolver>of().iterator());

        StarBotCoreProperties properties = new StarBotCoreProperties();
        return new StarBotMessageSender(http, senderService, new PushActivityRecorder(TimelineWriter.NONE),
                new PushGate(properties), TimelineWriter.NONE, new AtAllQuotaService(properties), resolvers,
                new FirstPushTipService(new StarBotStateStore(properties)));
    }

    private PushMessage pushMessage(JSONObject params) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(params);
        message.setParams(JSON.toJSONString(params));
        return message;
    }
}
