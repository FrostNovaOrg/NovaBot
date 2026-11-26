package org.frostnova.nova.interact.auto;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.enums.GuardType;
import org.frostnova.nova.bilibili.enums.RoomGuardLevel;
import org.frostnova.nova.bilibili.event.live.*;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoomWelcomeTest extends RoomInteractionFixture {
    private void configure(boolean master, JSONObject welcome, String... enabled) {
        var features = new JSONObject();
        for (String key : enabled) features.put(key, true);
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", master, "features", features, "welcome", welcome)));
    }

    private void enter(UserInfo viewer, RoomGuardLevel level) {
        var entry = new BilibiliEnterRoomEvent(ROOM, viewer, now);
        entry.setRoomGuardLevel(level);
        events.publishEvent(entry);
    }

    @Test
    @DisplayName("同一位舰长反复进场收到好几句，或指定观众收到了普通大航海话术")
    void prefersListedViewerAndDeduplicatesBothEntryEventsInEitherOrder() {
        var people = List.of(JSONObject.of("uid", "1", "alias", "一二三四五六七八九😀甲", "template", "{主播}欢迎{用户}"),
                JSONObject.of("uid", "2"));
        configure(true, JSONObject.of("people", people, "listedTemplate", "回来啦，{用户}"), "guardWelcome", "listedWelcome");
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 1, GuardType.Captain));
        enter(MING, RoomGuardLevel.CAPTAIN);
        enter(HONG, RoomGuardLevel.COMMANDER);
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 2, GuardType.Commander));
        enter(new UserInfo(3L, "小青"), RoomGuardLevel.CAPTAIN);
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 3, GuardType.Captain));
        enter(new UserInfo(3L, "改名了"), RoomGuardLevel.GOVERNOR);
        enter(new UserInfo(4L, "普通观众"), RoomGuardLevel.NONE);
        tick(0);
        tick(3);
        tick(6);
        tick(9);
        assertEquals(List.of("示例主播欢迎一二三四五六七八九😀…", "回来啦，小红", "欢迎舰长 小青 进入直播间"), sent);
    }

    @Test
    @DisplayName("监听账号没登录时，一位舰长进房被欢迎了两遍，或名字被写成 UID")
    void pairsMaskedEntriesWithEffectsInEitherOrder() {
        var people = List.of(JSONObject.of("uid", "1"), JSONObject.of("uid", "2"));
        configure(true, JSONObject.of("people", people, "listedTemplate", "{用户}回来啦"), "guardWelcome", "listedWelcome");
        enter(new UserInfo(0L, "小*明"), RoomGuardLevel.CAPTAIN);
        tick(0);
        at(2);
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 1, GuardType.Captain));
        tick(2);
        tick(6);
        assertEquals(List.of("小*明回来啦"), sent);

        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 2, GuardType.Captain));
        tick(7);
        at(8);
        enter(new UserInfo(0L, "小*红"), RoomGuardLevel.CAPTAIN);
        tick(8);
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 3, GuardType.Governor));
        tick(12);
        tick(16);
        assertEquals(List.of("小*明回来啦", "小*红回来啦"), sent);
    }

    @Test
    @DisplayName("设了提督起步却连舰长也欢迎，或漏掉由特效认出的总督和等级可辨的打码观众")
    void followsMinimumRankEvenIfOnlyOneEventIsAvailable() {
        configure(true, JSONObject.of("minimumLevel", "COMMANDER", "guardTemplate", "\u00a0\uFEFF"), "guardWelcome");
        enter(MING, RoomGuardLevel.CAPTAIN);
        enter(new UserInfo(0L, "小*"), RoomGuardLevel.COMMANDER);
        enter(new UserInfo(0L, "小*"), RoomGuardLevel.COMMANDER);
        tick(3);
        enter(HONG, RoomGuardLevel.UNKNOWN);
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 2, GuardType.Governor));
        tick(6);
        assertEquals(2, sent.size());
        assertEquals("欢迎提督 小* 进入直播间", sent.get(0));
        assertEquals("欢迎总督 小红 进入直播间", sent.get(1));
        configure(true, JSONObject.of("minimumLevel", "GOVERNOR"), "guardWelcome");
        enter(MING, RoomGuardLevel.COMMANDER);
        tick(9);
        assertEquals(2, sent.size());
    }

    @Test
    @DisplayName("未开播或关闭欢迎时冒出欢迎，恢复后又把旧弹幕补出来")
    void ignoresDisabledOfflineAndUnidentifiableListEntries() {
        var options = JSONObject.of("people", List.of(JSONObject.of("uid", "2")));
        enter(MING, RoomGuardLevel.CAPTAIN);
        configure(false, options, "guardWelcome");
        enter(MING, RoomGuardLevel.CAPTAIN);
        configure(true, options, "guardWelcome", "listedWelcome");
        living = false;
        enter(MING, RoomGuardLevel.CAPTAIN);
        living = true;
        enter(HONG, RoomGuardLevel.UNKNOWN);
        configure(true, options, "guardWelcome");
        configure(true, options, "listedWelcome");
        enter(new UserInfo(0L, "打*"), RoomGuardLevel.GOVERNOR);
        tick(0);
        assertTrue(sent.isEmpty());
        configure(true, options, "guardWelcome", "listedWelcome");
        enter(MING, RoomGuardLevel.CAPTAIN);
        tick(1);
        assertEquals(List.of("欢迎舰长 小明 进入直播间"), sent);
    }

    @Test
    @DisplayName("发送账号自己进房时，机器人向自己发了欢迎")
    void skipsSendingAccountOnBothEntrySignals() {
        var options = JSONObject.of("people", List.of(JSONObject.of("uid", "7", "alias", "自己")));
        configure(true, options, "guardWelcome", "listedWelcome");
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, SELF.getUid(), GuardType.Governor));
        tick(4);
        assertTrue(sent.isEmpty(), sent::toString);
        enter(SELF, RoomGuardLevel.GOVERNOR);
        tick(8);
        assertTrue(sent.isEmpty(), sent::toString);
        enter(MING, RoomGuardLevel.CAPTAIN);
        tick(12);
        assertEquals(List.of("欢迎舰长 小明 进入直播间"), sent);
    }

    @Test
    @DisplayName("重启后同一场又欢迎了一遍，或把上场排队的欢迎发到了下一场")
    void resumesOnlyWelcomedIdentitiesForSameBroadcast() {
        var welcome = JSONObject.of("people", List.of(JSONObject.of("uid", "1", "alias", "小明同学",
                "template", "\u2007\u001c")), "listedTemplate", "\uFEFF ");
        configure(true, welcome, "guardWelcome", "listedWelcome");
        enter(MING, RoomGuardLevel.CAPTAIN);
        tick(0);
        enter(HONG, RoomGuardLevel.CAPTAIN);
        events.close();
        state.save();
        start();
        enter(MING, RoomGuardLevel.CAPTAIN);
        tick(3);
        assertEquals(1, sent.size());
        events.publishEvent(new BilibiliLiveOffEvent(ROOM, now));
        broadcastStart = now.toEpochMilli();
        events.publishEvent(new BilibiliLiveOnEvent(ROOM, now));
        enter(MING, RoomGuardLevel.CAPTAIN);
        tick(6);
        assertEquals(List.of("欢迎 小明同学 进入直播间", "欢迎 小明同学 进入直播间"), sent);
    }

    @Test
    @DisplayName("一开播涌进大航海挤掉礼物答谢、突破每分钟上限，或半分钟后还在发旧欢迎")
    void prioritizesGiftsOverWelcomeAndExpiresTheBacklog() {
        var features = JSONObject.of("guardWelcome", true, "follow", true, "gift", true);
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", true, "features", features, "perMinute", 2)));
        follow(HONG);
        at(22);
        paid(MING, "花", 1);
        at(30);
        for (long uid = 10; uid < 35; uid++) enter(new UserInfo(uid, "新观众"), RoomGuardLevel.CAPTAIN);
        tick(30);
        tick(33);
        tick(59);
        tick(60);
        assertEquals(List.of("感谢 小明 赠送的 花x1", "欢迎舰长 新观众 进入直播间"), sent);
        assertTrue(store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows().stream()
                .anyMatch(r -> r.getString("feature").equals("guardWelcome") && r.getString("reason").contains("时限")));
    }

    private void lottery(long id, Integer end) {
        events.publishEvent(new BilibiliLotteryStartEvent(ROOM, id, now, end == null ? null : START.plusSeconds(end)));
    }

    private void sc() {
        events.publishEvent(new BilibiliSuperChatEvent(ROOM, MING, "留言", 30.0));
    }

    @Test
    @DisplayName("天选开始没清队列、期间的礼物和欢迎被补发，或开奖以后新弹幕仍发不出去")
    void dropsPendingAndNewMessagesUntilLotteryEnds() {
        configure(true, new JSONObject(), "pauseActivities", "guardWelcome", "gift", "superChat");
        sc();
        paid(MING, "花", 1);
        lottery(1, 100);
        enter(HONG, RoomGuardLevel.CAPTAIN);
        sc();
        paid(HONG, "茶", 1);
        tick(10);
        assertTrue(sent.isEmpty());
        var skipped = store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows();
        for (String feature : List.of("superChat", "guardWelcome", "gift")) {
            assertTrue(skipped.stream().anyMatch(r -> r.getString("feature").equals(feature)
                    && r.getString("reason").contains("天选进行中")), feature);
        }
        events.publishEvent(new BilibiliLotteryEndEvent(ROOM, 1L));
        sc();
        tick(11);
        tick(20);
        assertEquals(List.of("感谢 小明 的醒目留言"), sent);
    }

    @Test
    @DisplayName("天选与红包重叠或下播重开时过早恢复，或无开奖消息时无限期停发")
    void holdsUntilTheLastOutstandingActivityEnds() {
        configure(true, new JSONObject(), "pauseActivities", "superChat");
        lottery(1, 30);
        at(5);
        lottery(2, 50);
        events.publishEvent(new BilibiliLotteryEndEvent(ROOM, 1L));
        sc();
        tick(9);
        assertTrue(sent.isEmpty());
        at(10);
        events.publishEvent(new BilibiliRedPocketEvent(ROOM, MING, now));
        events.publishEvent(new BilibiliLotteryEndEvent(ROOM, 2L));
        events.publishEvent(new BilibiliLiveOffEvent(ROOM, now));
        events.publishEvent(new BilibiliLiveOnEvent(ROOM, now));
        at(69);
        sc();
        tick(69);
        assertTrue(sent.isEmpty());
        assertEquals("红包进行中", store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows().get(0).getString("reason"));
        at(70);
        sc();
        tick(70);
        lottery(3, null);
        at(669);
        sc();
        tick(669);
        at(670);
        sc();
        tick(670);
        assertEquals(2, sent.size());
        lottery(4, 680);
        at(679);
        sc();
        at(680);
        sc();
        tick(680);
        assertEquals(3, sent.size());
    }

    @Test
    @DisplayName("别的房间有天选、暂停开关没开或已经关掉，仍然停发本房弹幕")
    void limitsActivityPausesToEnabledRoom() {
        configure(true, new JSONObject(), "superChat");
        lottery(1, 120);
        sc();
        tick(0);
        configure(true, new JSONObject(), "superChat", "pauseActivities");
        var other = new LiveStreamerInfo(202L, "另一位", 502L);
        events.publishEvent(new BilibiliLotteryStartEvent(other, 2L, now, null));
        sc();
        tick(3);
        lottery(3, 90);
        sc();
        tick(6);
        assertEquals(2, sent.size());
        configure(true, new JSONObject(), "superChat");
        sc();
        tick(9);
        assertEquals(3, sent.size());
    }

    @Test
    @DisplayName("发送线程在天选结束后才空下来，把期间到点的定时弹幕补发了")
    void skipsTimersDueDuringThePauseEvenIfTheWorkerWasBusy() {
        var plan = JSONObject.of("id", "one", "enabled", true, "minutes", 2, "texts", List.of("定时"), "skipQuiet", false);
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", true, "plans", List.of(plan),
                "features", JSONObject.of("timed", true, "pauseActivities", true))));
        at(119);
        lottery(1, 125);
        tick(126);
        assertTrue(sent.isEmpty());
        var history = store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows();
        assertTrue(history.stream().anyMatch(r -> "timed".equals(r.getString("feature"))
                && "天选进行中".equals(r.getString("reason"))));
        tick(246);
        assertEquals(List.of("定时"), sent);
        at(365);
        lottery(2, 390);
        at(370);
        events.publishEvent(new BilibiliLotteryEndEvent(ROOM, 2L));
        tick(371);
        assertEquals(List.of("定时"), sent);
    }

    @Test
    @DisplayName("两个缺少编号的天选连续开始，收到一个开奖就把另一个天选的暂停也解除了")
    void ambiguousEndsPreserveTheLongestRemainingDeadline() {
        configure(true, new JSONObject(), "pauseActivities", "superChat");
        events.publishEvent(new BilibiliLotteryStartEvent(ROOM, null, now, START.plusSeconds(30)));
        events.publishEvent(new BilibiliLotteryStartEvent(ROOM, null, now, START.plusSeconds(90)));
        at(10);
        events.publishEvent(new BilibiliLotteryEndEvent(ROOM, null));
        at(31);
        sc();
        tick(31);
        assertTrue(sent.isEmpty());
        events.publishEvent(new BilibiliLotteryEndEvent(ROOM, null));
        sc();
        tick(32);
        assertTrue(sent.isEmpty());
        at(90);
        sc();
        tick(90);
        assertEquals(1, sent.size());
    }

    @Test
    @DisplayName("一场天选开奖了，另一场还在进行，机器人就开始刷答谢")
    void lateNumberedEndKeepsAnonymousLotteryPaused() {
        configure(true, new JSONObject(), "pauseActivities", "superChat");
        lottery(1, 30);
        at(10);
        events.publishEvent(new BilibiliLotteryStartEvent(ROOM, null, now, START.plusSeconds(100)));
        at(40);
        events.publishEvent(new BilibiliLotteryEndEvent(ROOM, 1L));
        sc();
        tick(40);
        assertTrue(sent.isEmpty());
        at(100);
        sc();
        tick(100);
        assertEquals(List.of("感谢 小明 的醒目留言"), sent);
    }

    @Test
    @DisplayName("平台时钟偏快又没给倒计时，天选超过十分钟还在暂停")
    void capsFallbackDurationAtTenMinutesFromReceipt() {
        configure(true, new JSONObject(), "pauseActivities", "superChat");
        events.publishEvent(new BilibiliLotteryStartEvent(ROOM, 9L, now.plusSeconds(1200), null));
        at(599);
        sc();
        tick(599);
        at(600);
        sc();
        tick(600);
        assertEquals(1, sent.size());
    }
}
