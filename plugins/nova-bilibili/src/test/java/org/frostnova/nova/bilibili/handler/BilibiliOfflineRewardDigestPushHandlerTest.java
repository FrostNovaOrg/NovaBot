package org.frostnova.nova.bilibili.handler;

import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.listener.NovaHandlerListener;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下播打赏播报处理器测试
 * <p>
 * 金额写不写跟随会话的「金额可见」，是这一类通知与其它几类最不一样的地方；
 * 静音与总开关不走处理器，与其它推送同一条路，这里只验它照样被拦下。
 */
@DisplayName("下播打赏播报处理器")
class BilibiliOfflineRewardDigestPushHandlerTest {
    private static final long STREAMER_UID = 10001L;

    private static final long ROOM_ID = 20002L;

    private BilibiliApiUtil api;

    private NovaMessageSender sender;

    private RevenueVisibilityService revenueVisibility;

    private BilibiliOfflineRewardDigestPushHandler handler;

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
        sender = mock(NovaMessageSender.class);
        revenueVisibility = mock(RevenueVisibilityService.class);
        handler = new BilibiliOfflineRewardDigestPushHandler(api, sender, revenueVisibility);

        // 昵称接口不可用时回退到事件携带的昵称，测试不关心接口路径
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
    }

    @Test
    @DisplayName("金额可见开着时写金额")
    void showsRevenueWhenVisible() {
        when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(true);

        handler.handle(digest(guardPerson("李四", 3, GuardOperateType.ACTIVATION, 198.0),
                giftPerson("张三", "心动盲盒", 2, 100.0)), pushMessage());

        assertEquals(List.of("感谢 李四 开通了舰长（¥198）、张三 送了 心动盲盒×2（合计 ¥100），主播甲 都收到啦"), sentContents());
    }

    @Test
    @DisplayName("金额可见关着时不写金额，只写谁送了什么、上舰写了什么")
    void hidesRevenueWhenNotVisible() {
        when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(false);

        handler.handle(digest(guardPerson("李四", 3, GuardOperateType.ACTIVATION, 198.0),
                giftPerson("张三", "心动盲盒", 2, 100.0)), pushMessage());

        assertEquals(List.of("感谢 李四 开通了舰长、张三 送了 心动盲盒×2，主播甲 都收到啦"), sentContents());
    }

    @Test
    @DisplayName("上舰按等级与开通续费用词")
    void namesGuardLevelAndOperation() {
        when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(false);

        handler.handle(digest(guardPerson("王五", 2, GuardOperateType.RENEWAL, 398.0)), pushMessage());
        handler.handle(digest(guardPerson("赵六", 1, GuardOperateType.ACTIVATION, 1998.0)), pushMessage());

        assertEquals(List.of(
                "感谢 王五 续费了提督，主播甲 都收到啦",
                "感谢 赵六 开通了总督，主播甲 都收到啦"), sentContents());
    }

    @Test
    @DisplayName("默认文字不提「下播」：礼物常在下播几小时后才来，开播中发出去也不能说反")
    void defaultMessageDoesNotClaimStreamEnded() {
        when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(false);

        handler.handle(digest(guardPerson("李四", 3, GuardOperateType.ACTIVATION, 198.0)), pushMessage());

        String content = sentContents().get(0);
        assertEquals("感谢 李四 开通了舰长，主播甲 都收到啦", content);
        assertFalse(content.contains("下播"), "说「下播啦」会让人以为主播刚播完: " + content);
    }

    @Test
    @DisplayName("静音时段这一类通知照样被丢弃，不发也不出声")
    void droppedDuringQuietHours() {
        LocalTime now = LocalTime.now();
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setQuietStart(now.minusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));
        properties.getPush().setQuietEnd(now.plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));

        List<TimelineEvent> timeline = new ArrayList<>();
        NovaHandlerListener listener = listener(properties, timeline::add);

        listener.onNovaExternalBaseEvent(digest(guardPerson("李四", 3, GuardOperateType.ACTIVATION, 198.0)));

        verify(sender, never()).send(any());
        assertEquals(1, timeline.size(), "丢弃在分发那一层记一条");
        assertEquals(TimelineEventType.PUSH_MUTED, timeline.get(0).type());
    }

    // —— 以下为夹具 ——

    /**
     * 一位上过舰的人。入参金额按元写，事件里按分记
     */
    private BilibiliOfflineRewardDigestEvent.Contribution guardPerson(String uname, int level,
                                                                      GuardOperateType operateType, double amountYuan) {
        BilibiliOfflineRewardDigestEvent.Contribution person = new BilibiliOfflineRewardDigestEvent.Contribution();
        person.setUid(50001L);
        person.setUname(uname);
        person.setGuardLevel(level);
        person.setOperateType(operateType);
        person.setGuardAmountFen(Math.round(amountYuan * 100));
        return person;
    }

    /**
     * 一位送过礼物的人。入参金额按元写，事件里按分记
     */
    private BilibiliOfflineRewardDigestEvent.Contribution giftPerson(String uname, String giftName,
                                                                     int count, double amountYuan) {
        long amountFen = Math.round(amountYuan * 100);
        BilibiliOfflineRewardDigestEvent.Contribution person = new BilibiliOfflineRewardDigestEvent.Contribution();
        person.setUid(50002L);
        person.setUname(uname);
        person.setGiftAmountFen(amountFen);
        person.setGifts(List.of(new BilibiliOfflineRewardDigestEvent.GiftLine(giftName, count, amountFen)));
        return person;
    }

    private BilibiliOfflineRewardDigestEvent digest(BilibiliOfflineRewardDigestEvent.Contribution... people) {
        return new BilibiliOfflineRewardDigestEvent(new LiveStreamerInfo(STREAMER_UID, "主播甲", ROOM_ID),
                List.of(people));
    }

    private PushMessage pushMessage() {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(handler.getDefaultParams());
        return message;
    }

    private List<String> sentContents() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender, atLeastOnce()).send(captor.capture());
        return captor.getAllValues().stream().map(Message::getContent).toList();
    }

    /**
     * 把处理器挂进分发那一层，走的是真闸门
     */
    private NovaHandlerListener listener(NovaCoreProperties properties, TimelineWriter timeline) {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = pushMessage();
        message.setHandlerInstance(handler);
        message.setEventClass(BilibiliOfflineRewardDigestEvent.class);
        target.setMessages(new ArrayList<>(List.of(message)));

        PushUser user = new PushUser();
        user.setPlatform("bilibili");
        user.setUid(STREAMER_UID);
        user.setUname("主播甲");
        user.setTargets(List.of(target));

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUser("bilibili", STREAMER_UID)).thenReturn(Optional.of(user));

        return new NovaHandlerListener(dataSource, new PushGate(properties), timeline);
    }
}
