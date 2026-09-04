package com.starlwr.bot.bilibili.handler;

import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.painter.BilibiliDynamicPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.service.LiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态推送处理器：图片降级要记在本场账上
 *
 * <h2>为什么这一路也要记</h2>
 * 报告里那一行说的是「本场有 N 条推送的图片未送达」。此前只有<b>开播封面</b>那一路在记，
 * 动态图丢了不进账——🔴 <b>而一个少数了一路的 N，和一个数对了的 N，在报告上长得一样。</b>
 * 两路记在同一个指标上：使用者要知道的是「有没有图丢了」，
 * 不是「丢的是封面还是动态图」。
 *
 * <h2>夹具 ID</h2>
 * ⚠️ 一律取<b>保留段假值</b>，位数远超平台真实取值范围。
 * （同目录下几个更早的处理器测试用的是 {@code 10001} 这类落在真实分配区间内的值，
 * 那几处未随本笔改动——本组不跟随那个写法。）
 */
@DisplayName("动态推送处理器")
class BilibiliDynamicPushHandlerTest {
    private static final long UID = 19604318752096L;

    private static final long ROOM_ID = 47615208934771L;

    private static final long GROUP = 38025617409263L;

    private BilibiliApiUtil api;

    private BilibiliDynamicPainter painter;

    private StarBotMessageSender sender;

    private AtSubscriptionService subscriptions;

    private LiveDataService liveDataService;

    private BilibiliDynamicPushHandler handler;

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
        painter = mock(BilibiliDynamicPainter.class);
        sender = mock(StarBotMessageSender.class);
        subscriptions = mock(AtSubscriptionService.class);
        liveDataService = mock(LiveDataService.class);
        handler = new BilibiliDynamicPushHandler(api, painter, sender, subscriptions, liveDataService);

        // 昵称接口不可用时回退到事件携带的昵称，本组不关心接口路径
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
        when(subscriptions.list(anyString(), anyLong(), anyLong(), anyString())).thenReturn(List.of());
        when(painter.paint(any())).thenReturn(Optional.of("ZmFrZS1pbWFnZQ=="));
    }

    @Test
    @DisplayName("🔴 动态图没送到时，按主播记进本场的图片降级数")
    void countsImageDegradedIntoTheSession() {
        handler.handle(event(), pushMessage());

        // 先证这一格量得到东西：一条消息都没发的话，下面那个回调无从取起
        List<Message> sent = sentMessages();
        assertFalse(sent.isEmpty(), "一条消息都没发，这一格什么都没量到");

        // 还没降级之前不许有账
        verify(liveDataService, never()).incrementLiveMetric(anyString(), anyLong(), anyString(), anyDouble());

        int fired = fireImageDegraded(sent);
        assertTrue(fired > 0, "含图的那一条没有挂上图片降级回调");

        verify(liveDataService, org.mockito.Mockito.times(fired)).incrementLiveMetric(
                eq("bilibili"), eq(UID), eq(BilibiliLiveMetric.IMAGE_DEGRADED_COUNT), eq(1.0));
    }

    @Test
    @DisplayName("🔴 与开播封面记在同一项上，报告那一行才数得全")
    void sharesTheMetricWithLiveCover() {
        handler.handle(event(), pushMessage());
        fireImageDegraded(sentMessages());

        // 键写死在这里，不读 BilibiliLiveMetric——两路共用同一项这件事，
        // 靠的是两处都写了这个字符串，而不是它们各自引了同一个常量
        verify(liveDataService, org.mockito.Mockito.atLeastOnce()).incrementLiveMetric(
                anyString(), anyLong(), eq("image_degraded_count"), anyDouble());
    }

    @Test
    @DisplayName("🔴 没发生降级就不记账：这一项不是「发了几条」")
    void countsNothingWhenNothingDegraded() {
        handler.handle(event(), pushMessage());

        assertFalse(sentMessages().isEmpty(), "一条消息都没发，这一格什么都没量到");
        verify(liveDataService, never()).incrementLiveMetric(anyString(), anyLong(), anyString(), anyDouble());
    }

    /**
     * 触发所有已挂上的图片降级回调
     * @return 触发了几次
     */
    private int fireImageDegraded(List<Message> messages) {
        int fired = 0;
        for (Message message : messages) {
            for (Runnable callback : message.getOnImageDegradedCallbacks()) {
                callback.run();
                fired++;
            }
        }
        return fired;
    }

    private List<Message> sentMessages() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    private BilibiliDynamicUpdateEvent event() {
        Dynamic dynamic = new Dynamic();
        dynamic.setId("1");
        dynamic.setType("DYNAMIC_TYPE_DRAW");

        return new BilibiliDynamicUpdateEvent(
                new LiveStreamerInfo(UID, "主播甲", ROOM_ID), dynamic, "发布了动态", "https://t.example/1");
    }

    private PushMessage pushMessage() {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(handler.getDefaultParams());
        return message;
    }

    @Test
    @DisplayName("默认模板分成两条，回调逐条都挂：真触发得了的只有含图那条")
    void everyPartCarriesTheCallback() {
        handler.handle(event(), pushMessage());

        List<Message> sent = sentMessages();
        assertEquals(2, sent.size(), "默认模板 {url}{next}{picture} 该分成两条");

        long withCallback = sent.stream().filter(m -> !m.getOnImageDegradedCallbacks().isEmpty()).count();
        assertEquals(2, withCallback, "回调该逐条挂上——哪一条含图由发送侧判，不由这里猜");
    }
}
