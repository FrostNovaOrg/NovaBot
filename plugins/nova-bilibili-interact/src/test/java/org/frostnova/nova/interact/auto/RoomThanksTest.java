package org.frostnova.nova.interact.auto;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.event.live.*;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.interact.config.InteractProperties;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService;
import org.frostnova.nova.interact.speaker.BilibiliSpeakerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomThanksTest {
    private static final LiveStreamerInfo ROOM = new LiveStreamerInfo(101L, "示例主播", 501L);
    private static final UserInfo MING = new UserInfo(1L, "小明");
    private static final UserInfo HONG = new UserInfo(2L, "小红");
    private static final UserInfo SELF = new UserInfo(7L, "发言账号");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private Instant now = START;
    private boolean living = true;
    private int platformCode;
    private final List<String> sent = new ArrayList<>();
    private final BilibiliSpeakerService speaker = mock(BilibiliSpeakerService.class);
    private final NovaCoreProperties core = new NovaCoreProperties();
    private final InteractProperties properties = new InteractProperties();
    private AnnotationConfigApplicationContext events;
    private AutoDanmakuQueue queue;
    private InteractStore store;
    private NovaStateStore state;
    private long broadcastStart = START.toEpochMilli();
    @TempDir Path dir;

    @BeforeEach
    void start() {
        core.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        var live = mock(LiveDataService.class);
        when(live.getLiveStartTime("bilibili", 101L)).thenAnswer(i -> Optional.of(broadcastStart));
        when(live.getLiveStatus("bilibili", 101L)).thenAnswer(i -> Optional.of(living));
        when(speaker.uid()).thenReturn(Optional.of(7L));
        when(speaker.get(anyString(), isNull())).thenReturn(JSONObject.of("property", JSONObject.of("danmu", JSONObject.of("length", 50))));
        doAnswer(i -> {
            Map<?, ?> form = i.getArgument(1);
            sent.add((String) form.get("msg"));
            return JSONObject.of("code", platformCode);
        }).when(speaker).postOnce(anyString(), anyMap());
        var source = mock(AbstractDataSource.class);
        var user = new PushUser();
        user.setUid(101L);
        user.setRoomId(501L);
        user.setUname("示例主播");
        when(source.getUsers("bilibili")).thenReturn(List.of(user));
        var sender = new LiveDanmakuService(properties, speaker, mock(BilibiliAccountService.class),
                mock(BilibiliApiUtil.class), source, event -> {});
        ReflectionTestUtils.invokeMethod(sender, "setClock", (Supplier<Instant>) () -> now);
        state = new NovaStateStore(core);
        store = new InteractStore(state);
        queue = new AutoDanmakuQueue(sender, live, store, () -> now);
        sender.rooms().forEach(queue::register);
        events = new AnnotationConfigApplicationContext();
        events.registerBean(LiveInteractRuntime.class, () -> new LiveInteractRuntime(queue, store, sender));
        events.refresh();
    }

    @AfterEach
    void stop() {
        events.close();
    }

    private void settings(boolean master, JSONObject thanks, String... enabled) {
        var features = new JSONObject();
        for (String key : enabled) features.put(key, true);
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", master, "features", features, "thanks", thanks)));
    }

    private void at(int seconds) {
        now = START.plusSeconds(seconds);
    }

    private void tick(int seconds) {
        at(seconds);
        queue.tick();
    }

    private GiftInfo gift(String name, int count) {
        return new GiftInfo(null, name, 0.25, count, null);
    }

    private void paid(UserInfo viewer, String name, int count) {
        events.publishEvent(new BilibiliPaidGiftEvent(ROOM, viewer, gift(name, count), 0.25 * count));
    }

    private void follow(UserInfo viewer) {
        events.publishEvent(new BilibiliFollowEvent(ROOM, viewer));
    }

    private void share(UserInfo viewer) {
        events.publishEvent(new BilibiliShareEvent(ROOM, viewer));
    }

    @Test
    @DisplayName("同一观众连送礼物刷出多句，或另一位观众和下一轮礼物被并入前一句")
    void giftWindowsStartWithEachViewersFirstGift() {
        settings(true, new JSONObject(), "gift");
        paid(MING, "小花花", 1);
        at(3);
        paid(MING, "小花花", 1);
        paid(MING, "辣条", 1);
        paid(HONG, "辣条", 2);
        tick(7);
        assertTrue(sent.isEmpty());
        at(8);
        paid(MING, "新礼物", 1);
        tick(8);
        tick(11);
        tick(16);
        assertEquals(List.of("感谢 小明 赠送的 小花花x2、辣条x1", "感谢 小红 赠送的 辣条x2", "感谢 小明 赠送的 新礼物x1"), sent);
    }

    @Test
    @DisplayName("免费礼物或门槛以下也发，多件相加够门槛却漏发，盲盒一次列了两份")
    void giftThresholdUsesTotalReceivedValueAndListsThreeKinds() {
        settings(true, JSONObject.of("giftMinYuan", 1, "giftWindowSeconds", 3), "gift");
        events.publishEvent(new BilibiliFreeGiftEvent(ROOM, MING, gift("免费", 99)));
        paid(HONG, "未够门槛", 2);
        events.publishEvent(new BilibiliRandomGiftEvent(ROOM, MING, gift("盲盒", 1), gift("花", 1), 10.0, 0.25));
        for (String name : List.of("糖", "茶", "梨", "桃")) paid(MING, name, 1);
        events.publishEvent(new BilibiliRandomGiftEvent(ROOM, new UserInfo(3L, "甲"), gift("盒", 1), gift("果", 1), 10.0, 0.25));
        tick(3);
        tick(6);
        assertEquals(List.of("感谢 小明 赠送的 花x1、糖x1、茶x1等"), sent);
        settings(true, JSONObject.of("giftIncludeFree", true), "gift");
        events.publishEvent(new BilibiliFreeGiftEvent(ROOM, MING, gift("免费", 3)));
        tick(14);
        assertEquals("感谢 小明 赠送的 免费x3", sent.get(1));
    }

    @Test
    @DisplayName("重复关注刷屏、分享被当成关注，或打码的两个人被当成同一个")
    void deduplicatesEachActionWithinBroadcastByUidOrFullMaskedName() {
        String masked = "一二三四五六七八九😀甲";
        settings(true, JSONObject.of("templates", JSONObject.of("follow", "{主播}：{用户}（{人数}）")), "follow", "share");
        follow(new UserInfo(0L, masked));
        follow(new UserInfo(0L, masked + "乙"));
        follow(MING);
        follow(new UserInfo(1L, "改过名"));
        follow(HONG);
        follow(new UserInfo(3L, "小青"));
        share(MING);
        tick(29);
        assertTrue(sent.isEmpty());
        tick(30);
        tick(33);
        assertTrue(sent.contains("示例主播：一二三四五六七八九😀…、一二三四五六七八九😀…等 5 人（5）"), sent.toString());
        assertTrue(sent.contains("感谢 小明 的分享直播间"), sent.toString());
        follow(MING);
        share(MING);
        tick(70);
        assertEquals(2, sent.size());
        events.publishEvent(new BilibiliLiveOffEvent(ROOM, now));
        events.publishEvent(new BilibiliLiveOnEvent(ROOM, now));
        follow(MING);
        tick(100);
        assertEquals("示例主播：小明（1）", sent.get(2));
    }

    @Test
    @DisplayName("关注和礼物挤到上舰前面，连续续费或醒目留言缺了答谢")
    void queuesEachPurchaseByPriorityAndKeepsViewerNamesLiteral() {
        settings(true, JSONObject.of("giftWindowSeconds", 10, "followWindowSeconds", 10,
                "templates", JSONObject.of("membership", "{用户}·{等级}")), "follow", "gift", "membership", "superChat");
        follow(HONG);
        paid(HONG, "花", 1);
        var name = new UserInfo(3L, "{等级}$");
        at(10);
        for (int i = 0; i < 2; i++) {
            events.publishEvent(new BilibiliSuperChatEvent(ROOM, MING, "留言", 30.0));
            events.publishEvent(new BilibiliCaptainEvent(ROOM, name, 1.0, 1, "月"));
        }
        events.publishEvent(new BilibiliCommanderEvent(ROOM, name, 1.0, 1, "月"));
        events.publishEvent(new BilibiliGovernorEvent(ROOM, name, 1.0, 1, "月"));
        for (int second = 10; second <= 25; second += 3) tick(second);
        assertEquals(List.of("{等级}$·舰长", "{等级}$·舰长", "{等级}$·提督", "{等级}$·总督",
                "感谢 小明 的醒目留言", "感谢 小明 的醒目留言"), sent);
        tick(69);
        assertEquals(6, sent.size());
        tick(70);
        var expired = store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows();
        assertEquals(2, expired.size());
        assertTrue(expired.stream().allMatch(r -> r.getString("reason").contains("时限")));
    }

    @Test
    @DisplayName("定时弹幕或一群人的关注先发，挤掉了同一刻到期的上舰和礼物")
    void higherPriorityThanksGoFirstWhenAllAreDueTogether() {
        var plan = JSONObject.of("id", "one", "enabled", true, "minutes", 2, "texts", List.of("定时"), "skipQuiet", false);
        var features = JSONObject.of("timed", true, "gift", true, "follow", true, "share", true,
                "membership", true, "superChat", true);
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", true, "features", features, "plans", List.of(plan))));
        at(90);
        follow(MING);
        share(HONG);
        at(112);
        paid(MING, "花", 1);
        at(120);
        events.publishEvent(new BilibiliSuperChatEvent(ROOM, MING, "留言", 30.0));
        events.publishEvent(new BilibiliCaptainEvent(ROOM, HONG, 1.0, 1, "月"));
        for (int second = 120; second <= 135; second += 3) tick(second);
        assertEquals(List.of("感谢 小红 开通舰长", "感谢 小明 的醒目留言", "感谢 小明 赠送的 花x1"), sent.subList(0, 3));
        assertTrue(sent.subList(3, 5).containsAll(List.of("感谢 小明 的关注", "感谢 小红 的分享直播间")));
        assertEquals("定时", sent.get(5));
    }

    @Test
    @DisplayName("关注、礼物刷屏时把满队列里的上舰和醒目留言答谢挤没了")
    void keepsMembershipAndSuperChatWhenLowerPriorityThanksArrive() {
        var features = JSONObject.of("gift", true, "follow", true, "membership", true, "superChat", true);
        var options = JSONObject.of("giftWindowSeconds", 10, "followWindowSeconds", 10);
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", true, "perMinute", 20,
                "features", features, "thanks", options)));
        paid(MING, "花", 1);
        follow(HONG);
        at(9);
        for (int i = 0; i < 19; i++) events.publishEvent(new BilibiliCaptainEvent(ROOM, HONG, 1.0, 1, "月"));
        events.publishEvent(new BilibiliSuperChatEvent(ROOM, MING, "留言", 30.0));

        for (int second = 10; second <= 67; second += 3) tick(second);
        var dropped = store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows();
        assertEquals(List.of("follow", "gift"), dropped.stream().map(r -> r.getString("feature")).sorted().toList());
        assertTrue(dropped.stream().allMatch(r -> "排队已满".equals(r.getString("reason"))));
        assertEquals(20, sent.size());
        assertTrue(sent.subList(0, 19).stream().allMatch(text -> text.contains("舰长")));
        assertTrue(sent.get(19).contains("醒目留言"));
    }

    @Test
    @DisplayName("一场里重开关注答谢时重复点名，或关闭期间的分享被攒到重开后")
    void togglingDropsOnlyPendingGroupsAndPreservesThankedViewers() {
        var options = JSONObject.of("shareWindowSeconds", 10, "followWindowSeconds", 10,
                "templates", JSONObject.of("follow", "  "));
        settings(true, options, "follow", "share");
        follow(HONG);
        tick(10);
        follow(MING);
        share(MING);
        settings(false, options, "follow", "share");
        settings(true, options, "follow", "share");
        follow(HONG);
        share(HONG);
        tick(20);
        tick(23);
        assertEquals(List.of("感谢 小红 的关注", "感谢 小红 的分享直播间"), sent);
        follow(MING);
        tick(33);
        assertEquals("感谢 小明 的关注", sent.get(2));
    }

    @Test
    @DisplayName("礼物答谢重新打开后，把关闭前攒的礼物和关闭期间收到的礼物也答谢了")
    void onlyThanksGiftsReceivedAfterReenabling() {
        settings(true, new JSONObject(), "gift");
        paid(MING, "花", 1);
        at(1);
        settings(true, new JSONObject());
        paid(MING, "糖", 1);
        at(2);
        settings(true, new JSONObject(), "gift");
        paid(MING, "茶", 1);
        tick(10);
        assertEquals(List.of("感谢 小明 赠送的 茶x1"), sent);
    }

    @Test
    @DisplayName("关闭免费礼物后待发答谢仍列免费礼物，或连同已收的付费礼物也丢了")
    void removingFreeGiftsKeepsPaidItemsAndAppliesNewWording() {
        settings(true, JSONObject.of("giftIncludeFree", true), "gift");
        for (String name : List.of("一", "二", "三", "四")) {
            events.publishEvent(new BilibiliFreeGiftEvent(ROOM, MING, gift(name, 1)));
        }
        paid(MING, "一", 1);
        paid(MING, "五", 1);
        settings(true, JSONObject.of("giftWindowSeconds", 3, "templates", JSONObject.of("gift", "{主播}收到{礼物}")), "gift");
        tick(3);
        assertEquals(List.of("示例主播收到一x1、五x1"), sent);
    }

    @Test
    @DisplayName("关闭开关、下播或重启之前攒的礼物，在恢复时又发出来")
    void discardsPendingAcrossSwitchesOfflineAndRestart() {
        paid(MING, "缺省关", 1);
        settings(false, new JSONObject(), "gift");
        paid(MING, "总开关关", 1);
        settings(true, new JSONObject(), "gift");
        events.publishEvent(new BilibiliCaptainEvent(ROOM, MING, 1.0, 1, "月"));
        events.publishEvent(new BilibiliSuperChatEvent(ROOM, MING, "留言", 30.0));
        follow(MING);
        paid(MING, "关功能前", 1);
        settings(true, new JSONObject());
        settings(true, new JSONObject(), "gift");
        paid(MING, "关总开关前", 1);
        settings(false, new JSONObject(), "gift");
        settings(true, new JSONObject(), "gift");
        paid(MING, "下播前", 1);
        events.publishEvent(new BilibiliLiveOffEvent(ROOM, now));
        paid(MING, "状态缓存还未更新", 1);
        living = false;
        paid(MING, "未开播", 1);
        living = true;
        events.publishEvent(new BilibiliLiveOnEvent(ROOM, now));
        paid(MING, "开播后", 1);
        tick(8);
        assertEquals(List.of("感谢 小明 赠送的 开播后x1"), sent);
        paid(MING, "重启前", 1);
        events.close();
        start();
        tick(20);
        assertEquals(1, sent.size());
        paid(MING, "重启后", 1);
        tick(28);
        assertEquals("感谢 小明 赠送的 重启后x1", sent.get(1));
    }

    @Test
    @DisplayName("同一场直播中途重启又答谢同一位关注者，或把未凑成一句的分享补发")
    void resumesOnlyAlreadyThankedIdentitiesForTheSameBroadcast() {
        settings(true, new JSONObject(), "follow", "share");
        var masked = new UserInfo(0L, "小*");
        follow(masked);
        tick(30);
        share(HONG);
        events.close();
        state.save();
        start();
        follow(masked);
        tick(60);
        assertEquals(List.of("感谢 小* 的关注"), sent);
        share(HONG);
        tick(90);
        assertEquals("感谢 小红 的分享直播间", sent.get(1));
        events.publishEvent(new BilibiliLiveOffEvent(ROOM, now));
        broadcastStart = now.toEpochMilli();
        events.publishEvent(new BilibiliLiveOnEvent(ROOM, now));
        follow(masked);
        tick(120);
        assertEquals("感谢 小* 的关注", sent.get(2));
    }

    @Test
    @DisplayName("平台暂停发送后到期的分享答谢留到恢复时补发")
    void aRejectionDropsWindowsEvenIfWorkerNextRunsAfterRecovery() {
        properties.getDanmaku().setPauseMinutes(1);
        settings(true, JSONObject.of("shareWindowSeconds", 120), "share", "superChat");
        share(HONG);
        platformCode = 10031;
        events.publishEvent(new BilibiliSuperChatEvent(ROOM, MING, "留言", 30.0));
        tick(0);
        platformCode = 0;
        tick(120);
        assertEquals(List.of("感谢 小明 的醒目留言"), sent);
        share(HONG);
        tick(240);
        assertEquals("感谢 小红 的分享直播间", sent.get(1));
    }

    @Test
    @DisplayName("发言账号答谢了自己的礼物、关注或分享")
    void excludesSendingAccount() {
        settings(true, new JSONObject(), "gift", "follow", "share");
        paid(SELF, "自己的", 1);
        follow(SELF);
        share(SELF);
        tick(8);
        tick(30);
        tick(33);
        assertEquals(List.of(), sent);
    }
}
