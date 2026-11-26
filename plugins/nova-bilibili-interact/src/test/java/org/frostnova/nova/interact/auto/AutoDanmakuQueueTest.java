package org.frostnova.nova.interact.auto;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.event.live.BilibiliDanmuEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliEmojiEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.event.datasource.change.NovaDataSourceAddEvent;
import org.frostnova.nova.core.event.datasource.change.NovaDataSourceRemoveEvent;
import org.frostnova.nova.core.event.datasource.other.NovaDataSourceLoadCompleteEvent;
import org.frostnova.nova.core.model.EmojiInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.interact.config.InteractProperties;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService;
import org.frostnova.nova.interact.speaker.BilibiliSpeakerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoDanmakuQueueTest {
    @TempDir Path dir;
    private final Instant start = Instant.now();
    private volatile Instant now = start;
    private final List<String> sent = new ArrayList<>();
    private final List<Long> destinations = new ArrayList<>();
    private final List<TimelineEvent> timeline = new ArrayList<>();
    private final Map<Long, Boolean> living = new HashMap<>();
    private final BilibiliSpeakerService speaker = mock(BilibiliSpeakerService.class);
    private final BilibiliAccountService listener = mock(BilibiliAccountService.class);
    private final LiveDataService live = mock(LiveDataService.class);
    private final NovaCoreProperties core = new NovaCoreProperties();
    private final InteractProperties properties = new InteractProperties();
    private final LiveStreamerInfo streamer = new LiveStreamerInfo(1L, "甲", 101L);
    private final PushUser a = user(1, "甲", 101);
    private final PushUser b = user(2, "乙", 102);
    private LiveDanmakuService sender;
    private AutoDanmakuQueue queue;
    private LiveInteractRuntime runtime;
    private InteractStore store;
    private int code;
    private int limit = 20;
    private boolean uncertain;

    private static PushUser user(long uid, String name, long room) {
        PushUser user = new PushUser();
        user.setUid(uid);
        user.setPlatform("bilibili");
        user.setRoomId(room);
        user.setUname(name);
        return user;
    }

    @BeforeEach
    void setup() {
        core.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        var source = mock(AbstractDataSource.class);
        when(source.getUsers("bilibili")).thenReturn(List.of(a, b));
        when(live.getLiveStatus(eq("bilibili"), anyLong())).thenAnswer(i -> Optional.ofNullable(living.get(i.getArgument(1))));
        living.put(1L, true);
        living.put(2L, true);
        when(speaker.uid()).thenReturn(Optional.of(7L));
        when(speaker.get(anyString(), isNull())).thenAnswer(i -> JSONObject.of("property", JSONObject.of("danmu", JSONObject.of("length", limit))));
        when(speaker.postOnce(anyString(), anyMap())).thenAnswer(i -> {
            Map<?, ?> form = i.getArgument(1);
            sent.add((String) form.get("msg"));
            destinations.add((Long) form.get("roomid"));
            return uncertain ? null : JSONObject.of("code", code);
        });
        sender = new LiveDanmakuService(properties, speaker, listener, mock(BilibiliApiUtil.class), source, timeline::add);
        ReflectionTestUtils.invokeMethod(sender, "setClock", (Supplier<Instant>) () -> now);
        restart();
    }

    private void restart() {
        store = new InteractStore(new NovaStateStore(core));
        queue = new AutoDanmakuQueue(sender, live, store, () -> now);
        runtime = new LiveInteractRuntime(queue, store, sender);
        sender.rooms().forEach(queue::register);
    }

    private void configure(long uid, boolean master, boolean feature, RoomSettings.Plan... plans) {
        queue.save(uid, new RoomSettings(master, 6, Map.of("timed", feature), List.of(plans)));
    }

    private RoomSettings.Plan plan(int minutes, boolean quiet) {
        return new RoomSettings.Plan("one", true, minutes, List.of("{主播}一", "二", "三"), false, quiet);
    }

    private void offer(long uid, String text, int priority, int seconds) {
        queue.enqueue(uid, "timed", text, priority, now.plusSeconds(seconds));
    }

    private void tick(long seconds) {
        now = start.plusSeconds(seconds);
        queue.tick();
    }

    private void chat(long uid) {
        runtime.danmaku(new BilibiliDanmuEvent(streamer, new UserInfo(uid, "发言者"), "你好", "你好", now));
    }

    private AnnotationConfigApplicationContext events() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(LiveInteractRuntime.class, () -> runtime);
        context.refresh();
        return context;
    }

    private List<JSONObject> dropped(long uid) {
        return store.history(uid, InteractStore.Outcome.DROPPED, 1, now).rows();
    }

    @Test
    @DisplayName("没开总开关或定时开关的主播房间里冒出弹幕")
    void requiresBothSwitchesInEachRoom() {
        offer(2, "未设置", 1, 10);
        configure(1, false, true, plan(2, false));
        configure(2, true, false, plan(2, false));
        offer(1, "总开关关", 1, 10);
        offer(2, "定时开关关", 1, 10);
        tick(120);
        assertTrue(sent.isEmpty());
        configure(1, true, true, plan(2, false));
        tick(240);
        assertEquals(List.of(101L), destinations);
    }

    @Test
    @DisplayName("开播立即发、回传的机器人弹幕让空房间刷屏，或三条轮流文字一直只发第一条")
    void waitsForIntervalAndActualViewerSinceLastSend() {
        living.put(1L, false);
        configure(1, true, true, plan(10, true));
        tick(600);
        living.put(1L, true);
        runtime.liveOn(new BilibiliLiveOnEvent(streamer, now));
        chat(99);
        tick(1199);
        assertTrue(sent.isEmpty());
        tick(1200);
        chat(7);
        runtime.danmaku(new BilibiliDanmuEvent(streamer, new UserInfo(), "未知发送者", "未知发送者", now));
        tick(1800);
        assertEquals(List.of("甲一"), sent);
        assertEquals("没有观众发言", dropped(1).get(0).getString("reason"));
        for (int i = 0; i < 3; i++) {
            chat(99);
            tick(2400 + i * 600);
        }
        assertEquals(List.of("甲一", "二", "三", "甲一"), sent);
        properties.getDanmaku().setAccount("listener");
        when(listener.getLoginUid()).thenReturn(9L);
        assertTrue(sender.isOwnDanmaku(new BilibiliDanmuEvent(streamer, new UserInfo(9L, ""), "", "")));
        assertFalse(sender.isOwnDanmaku(new BilibiliDanmuEvent(streamer, new UserInfo(7L, ""), "", "")));
    }

    @Test
    @DisplayName("观众在刷表情或匿名聊天，定时弹幕却一直因无人发言而跳过")
    void recognizesEmojiAndMaskedChatWhileExcludingOwnEchoes() {
        configure(1, true, true, plan(2, true));
        var emoji = new EmojiInfo("smile", "微笑", null);
        var masked = new UserInfo(0L, "");
        try (var events = events()) {
            events.publishEvent(new BilibiliEmojiEvent(streamer, new UserInfo(88L, "观众"), emoji));
            tick(120);
            assertEquals(List.of("甲一"), sent);

            now = start.plusSeconds(149);
            events.publishEvent(new BilibiliDanmuEvent(streamer, masked, "甲一", "甲一"));
            events.publishEvent(new BilibiliEmojiEvent(streamer, new UserInfo(7L, ""), emoji));
            tick(240);
            assertEquals(List.of("甲一"), sent);

            assertTrue(sender.send(101, "试发").sent());
            events.publishEvent(new BilibiliDanmuEvent(streamer, masked, "有观众", "有观众"));
            tick(360);
            assertEquals(List.of("甲一", "试发", "二"), sent);

            now = start.plusSeconds(391);
            events.publishEvent(new BilibiliDanmuEvent(streamer, masked, "二", "二"));
            tick(480);
            assertEquals(List.of("甲一", "试发", "二", "三"), sent);
        }
    }

    @Test
    @DisplayName("旧场次还显示在播时收到新开播，新场一开始就发出旧场排队的弹幕")
    void newBroadcastStartsWithAnEmptyQueueAndFullInterval() {
        configure(1, true, true, plan(2, false));
        tick(60);
        offer(1, "上一场", 1, 600);
        runtime.liveOn(new BilibiliLiveOnEvent(streamer, now));
        tick(120);
        assertTrue(sent.isEmpty());
        tick(180);
        assertEquals(List.of("甲一"), sent);
        assertEquals("上一场", dropped(1).get(0).getString("text"));
    }

    @Test
    @DisplayName("同一房间的两条定时计划把观众的一句发言用了两次")
    void waitsForSpeechAfterActualDispatchAcrossPlans() {
        var other = new RoomSettings.Plan("two", true, 2, List.of("另一条"), false, true);
        configure(1, true, true, plan(2, true), other);
        chat(88);
        tick(120);
        tick(123);
        assertEquals(List.of("甲一"), sent);
        assertEquals("另一条", dropped(1).get(0).getString("text"));
    }

    @Test
    @DisplayName("改了间隔还按旧计划发，重复保存发两遍，删掉的计划稍后又出现在房间里")
    void replacesOnlyChangedTimersAndCancelsPendingWork() {
        configure(1, true, true, plan(2, false));
        tick(60);
        configure(1, true, true, plan(3, false));
        tick(120);
        configure(1, true, true, plan(3, false));
        tick(239);
        assertTrue(sent.isEmpty());
        tick(240);
        assertEquals(List.of("甲一"), sent);
        now = start.plusSeconds(420);
        sender.send(101, "试发");
        queue.tick();
        configure(1, true, true);
        tick(423);
        tick(600);
        assertEquals(List.of("甲一", "试发"), sent);
        assertEquals("二", dropped(1).get(0).getString("text"));
    }

    @Test
    @DisplayName("把某条定时计划停用了，它排着的弹幕和下一轮弹幕还在发")
    void disablingPlanCancelsPendingAndFutureSends() {
        var active = plan(2, false);
        configure(1, true, true, active);
        tick(120);
        now = start.plusSeconds(240);
        assertTrue(sender.send(101, "试发").sent());
        queue.tick();
        configure(1, true, true, new RoomSettings.Plan(active.id(), false, active.minutes(), active.texts(),
                active.random(), active.skipQuiet()));
        tick(243);
        tick(360);
        assertEquals(List.of("甲一", "试发"), sent);
    }

    @Test
    @DisplayName("两个房间同时到点或刚有人试发，一条自动弹幕撞上账号限速")
    void sharesTheDirectSendIntervalBetweenRooms() {
        configure(1, true, true);
        configure(2, true, true);
        sender.send(101, "先试发");
        offer(1, "甲", 0, 60);
        offer(2, "乙", 0, 60);
        tick(0);
        tick(2);
        assertEquals(List.of("先试发"), sent);
        tick(3);
        tick(4);
        assertEquals(List.of("先试发", "甲"), sent);
        tick(6);
        assertEquals(List.of(101L, 101L, 102L), destinations);
        assertEquals(3, timeline.size());
        assertTrue(dropped(1).isEmpty());
    }

    @Test
    @DisplayName("房间一分钟内发出超过六条，或排队过时的弹幕仍然发出")
    void rollingMinuteQuotaDefersUntilExpiry() {
        configure(1, true, true);
        for (int i = 0; i < 10; i++) offer(1, "条" + i, 1, i == 6 ? 25 : 90);
        for (int i = 0; i < 60; i += 3) tick(i);
        assertEquals(6, sent.size());
        assertEquals("已过发送时限", dropped(1).get(0).getString("reason"));
        tick(60);
        tick(63);
        tick(66);
        assertEquals(List.of("条7", "条8", "条9"), sent.subList(6, 9));
    }

    @Test
    @DisplayName("把房间每分钟上限调成两条还是按六条发，或两个房间没按设置里的账号间隔发送")
    void honorsCustomRoomQuotaAndAccountInterval() {
        properties.getDanmaku().setMinInterval(2);
        queue.save(1, new RoomSettings(true, 2, Map.of("timed", true), List.of()));
        configure(2, true, true);
        for (String text : List.of("甲一", "甲二", "甲三")) offer(1, text, 1, 90);
        offer(2, "乙", 1, 90);
        tick(0);
        tick(1);
        assertEquals(List.of("甲一"), sent);
        tick(2);
        assertEquals(List.of("甲一", "甲二"), sent);
        tick(4);
        tick(59);
        assertEquals(List.of("甲一", "甲二", "乙"), sent);
        tick(60);
        assertEquals(List.of("甲一", "甲二", "乙", "甲三"), sent);
    }

    @Test
    @DisplayName("房间队列满了挤走高优先级或较新的弹幕，发送记录查不到去向")
    void evictsOldestOfLowestPriorityAndSendsPriorityFirst() {
        configure(1, true, true);
        offer(1, "早高", 5, 100);
        offer(1, "早低", 1, 100);
        for (int i = 0; i < 18; i++) offer(1, "低" + i, 1, 100);
        offer(1, "新高", 5, 100);
        tick(0);
        tick(3);
        tick(6);
        assertEquals(List.of("早高", "新高", "低0"), sent);
        assertEquals("早低", dropped(1).get(0).getString("text"));
        assertEquals("排队已满", dropped(1).get(0).getString("reason"));
    }

    @Test
    @DisplayName("超长弹幕切坏表情、发超过三段，或中间一段结果不明又重发")
    void splitsByCodePointAndAbandonsTailOnUncertainResult() {
        configure(1, true, true);
        limit = 2;
        offer(1, "🌙甲乙丙丁戊己庚辛壬", 0, 100);
        tick(0);
        tick(3);
        tick(6);
        assertEquals(List.of("🌙甲", "乙丙", "丁…"), sent);
        offer(1, "一二三四五", 0, 100);
        tick(9);
        uncertain = true;
        tick(12);
        tick(15);
        assertEquals(List.of("一二", "三四"), sent.subList(3, sent.size()));
        assertEquals("五", dropped(1).get(0).getString("text"));
        assertEquals(1, store.history(1, InteractStore.Outcome.UNCERTAIN, 1, now).total());
    }

    @Test
    @DisplayName("账号被平台暂停后还收单，恢复时把两个房间攒下的弹幕一起刷出去")
    void pausesDiscardAllRoomsAndRefuseNewRequests() {
        configure(1, true, true);
        configure(2, true, true);
        offer(1, "触发限制", 2, 900);
        offer(2, "乙已排队", 1, 900);
        offer(1, "甲已排队", 1, 900);
        code = 10031;
        tick(0);
        offer(2, "暂停中新来", 9, 900);
        tick(299);
        verify(speaker, times(1)).get(anyString(), isNull());
        code = 0;
        tick(300);
        assertEquals(List.of("触发限制"), sent);
        assertEquals(2, dropped(2).size());
        assertEquals(1, dropped(1).size());
        offer(2, "新弹幕", 1, 60);
        queue.tick();
        assertEquals(List.of("触发限制", "新弹幕"), sent);
    }

    @Test
    @DisplayName("平台已说账号掉了登录，机器人还是每到定时计划的时间就发请求")
    void platformLogoutStopsRequestsForTheConfiguredPause() {
        properties.getDanmaku().setPauseMinutes(6);
        configure(1, true, true, plan(2, false));
        configure(2, true, true);
        offer(2, "另一间排着的", -1, 900);
        code = -101;
        tick(120);
        offer(2, "另一间新来的", 1, 900);
        tick(240);
        tick(360);
        tick(479);
        assertEquals(List.of("甲一"), sent);
        verify(speaker, times(1)).get(anyString(), isNull());
        assertEquals(2, dropped(2).size());
        code = 0;
        tick(480);
        assertEquals(List.of("甲一", "甲一"), sent);
    }

    @Test
    @DisplayName("平台校验失败后重新登录，直播间一口气冒出几条过时的弹幕")
    void discardsArrivalsDuringLoginFailureEvenIfManualSendRecovers() {
        properties.getDanmaku().setPauseMinutes(1);
        configure(1, true, true);
        configure(2, true, true);
        offer(2, "乙已排队", 1, 900);
        code = -111;
        var account = sender.accountStatus();
        queue.accountResult(account, sender.send(101, "试发遇到校验失败"));
        offer(1, "重新登录前", 1, 900);
        now = start.plusSeconds(3);
        code = 0;
        assertTrue(sender.send(101, "重新登录后试发").sent());
        now = start.plusSeconds(59);
        offer(2, "重新登录后仍在暂停", 1, 900);
        tick(60);
        offer(2, "到点以后", 1, 60);
        queue.tick();
        tick(63);
        assertEquals(List.of("试发遇到校验失败", "重新登录后试发", "到点以后"), sent);
        assertEquals(1, dropped(1).size());
        assertEquals(2, dropped(2).size());
    }

    @Test
    @DisplayName("退出登录后的队列在再登录时补发，或者没选好账号还出网")
    void noAccountDiscardsExistingAndNewItems() {
        configure(1, true, true);
        offer(1, "已在排队", 1, 100);
        when(speaker.uid()).thenReturn(Optional.empty());
        offer(1, "没有账号", 1, 100);
        tick(0);
        verify(speaker, never()).get(anyString(), any());
        when(speaker.uid()).thenReturn(Optional.of(7L));
        tick(3);
        assertEquals(2, dropped(1).size());
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("房间暂停时仍发弹幕，或一个房间暂停误伤另一个房间")
    void pauseIsScopedAndExpiresOrCanBeReleased() {
        configure(1, true, true);
        configure(2, true, true);
        offer(1, "已排", 1, 90);
        queue.pauseRoom(101, "休息", now.plusSeconds(10));
        offer(1, "新来", 1, 90);
        offer(2, "照发", 1, 90);
        tick(0);
        tick(10);
        offer(1, "到点", 1, 90);
        queue.tick();
        queue.pauseRoom(101, "休息", now.plusSeconds(10));
        queue.pauseRoom(101, "", now);
        offer(1, "解除", 1, 90);
        tick(13);
        assertEquals(List.of("照发", "到点", "解除"), sent);
        assertEquals(List.of("休息", "休息"), dropped(1).stream().map(r -> r.getString("reason")).toList());
    }

    @Test
    @DisplayName("主播下播了队列还在发，或临时停用主播再启用后定时弹幕计划全没了")
    void offlineAndRemovalClearWorkButPreserveSettingsForReactivation() {
        configure(1, true, true, plan(2, false));
        var saved = store.settings(1);
        offer(1, "已排", 1, 600);
        living.put(1L, false);
        runtime.liveOff(new BilibiliLiveOffEvent(streamer, now));
        offer(1, "已下播", 1, 600);
        tick(300);
        assertEquals(2, dropped(1).size());
        living.put(1L, true);
        runtime.liveOn(new BilibiliLiveOnEvent(streamer, now));
        offer(1, "又排", 1, 600);
        try (var events = events()) {
            a.setEnabled(false);
            events.publishEvent(new NovaDataSourceRemoveEvent(a));
            events.publishEvent(new NovaDataSourceLoadCompleteEvent(List.of()));
            tick(600);
            assertTrue(sent.isEmpty());
            assertEquals("又排", dropped(1).get(0).getString("text"));
            assertEquals(saved, new InteractStore(new NovaStateStore(core)).settings(1));
            a.setEnabled(true);
            events.publishEvent(new NovaDataSourceAddEvent(a));
            tick(719);
            assertTrue(sent.isEmpty());
            tick(720);
            assertEquals(List.of("甲一"), sent);
        }
    }

    @Test
    @DisplayName("下播事件已到但缓存还显示在播，刚进队的新内容漏发到已下播的房间")
    void liveOffStopsAdmissionWhileCachedStatusCatchesUp() {
        configure(1, true, true);
        runtime.liveOff(new BilibiliLiveOffEvent(streamer, now));
        offer(1, "已下播", 1, 60);
        tick(0);
        assertTrue(sent.isEmpty());
        assertEquals("主播未开播", dropped(1).get(0).getString("reason"));
        runtime.liveOn(new BilibiliLiveOnEvent(streamer, now));
        offer(1, "已开播", 1, 60);
        tick(3);
        assertEquals(List.of("已开播"), sent);
    }

    @Test
    @DisplayName("到点检查字数花了太久，已过时或已被撤回的弹幕仍然发出")
    void rechecksAfterLengthLookup() {
        configure(1, true, true);
        offer(1, "稍纵即逝", 1, 2);
        when(speaker.get(anyString(), isNull())).thenAnswer(i -> {
            now = now.plusSeconds(3);
            return JSONObject.of("property", JSONObject.of("danmu", JSONObject.of("length", 20)));
        });
        queue.tick();
        assertTrue(sent.isEmpty());
        assertEquals(1, dropped(1).size());
        assertEquals("已过发送时限", dropped(1).get(0).getString("reason"));
    }

    @Test
    @DisplayName("关闭程序时队列内容消失，发送记录没说这些弹幕为什么没发")
    void stopAccountsForPendingMessages() {
        configure(1, true, true);
        offer(1, "排队的一条", 1, 60);
        runtime.stop();
        assertEquals(1, dropped(1).size());
        assertTrue(dropped(1).get(0).getString("reason").contains("停止"));
        tick(3);
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("一条长弹幕已开始分段，后来的弹幕插在同一房间的两段之间")
    void finishesStartedGroupBeforeNextInRoom() {
        configure(1, true, true);
        limit = 2;
        offer(1, "一二三四五", 1, 60);
        tick(0);
        offer(1, "后来", 2, 60);
        tick(3);
        tick(6);
        tick(9);
        assertEquals(List.of("一二", "三四", "五", "后来"), sent);
    }

    @Test
    @DisplayName("启动后启用的在播计划到了时间也没动静，管理员必须手动试发")
    void workerDispatchesDuePlanWithoutAConsoleRequest() throws Exception {
        var dispatched = new CountDownLatch(1);
        doAnswer(i -> {
            Map<?, ?> form = i.getArgument(1);
            sent.add((String) form.get("msg"));
            dispatched.countDown();
            return JSONObject.of("code", 0);
        }).when(speaker).postOnce(anyString(), anyMap());
        configure(1, true, true, plan(2, false));
        runtime.start();
        try {
            now = start.plusSeconds(120);
            assertTrue(dispatched.await(10, TimeUnit.SECONDS), "到期计划没有交给发送账号");
        } finally {
            runtime.stop();
        }
        assertEquals(List.of("甲一"), sent);
        assertEquals(1, store.history(1, InteractStore.Outcome.SENT, 1, now).total());
    }

    @Test
    @DisplayName("平台在试发时报告登录失效，已有的自动弹幕却还留在队列中")
    void accountRefusalDuringManualSendDrainsOutstandingWork() {
        configure(1, true, true);
        offer(1, "已排", 1, 60);
        var account = sender.accountStatus();
        code = -101;
        queue.accountResult(account, sender.send(101, "试发"));
        code = 0;
        tick(3);
        assertEquals(List.of("试发"), sent);
        assertTrue(dropped(1).get(0).getString("reason").contains("账号"));
    }

    @Test
    @DisplayName("重启后补发旧队列，或在播主播的定时计划丢失、立即补发")
    void restoresOnlySettingsAndWaitsAFullInterval() {
        configure(1, true, true, plan(2, false));
        offer(1, "不应补发", 1, 600);
        now = start.plusSeconds(90);
        restart();
        tick(209);
        assertTrue(sent.isEmpty());
        tick(210);
        assertEquals(List.of("甲一"), sent);
    }
}
