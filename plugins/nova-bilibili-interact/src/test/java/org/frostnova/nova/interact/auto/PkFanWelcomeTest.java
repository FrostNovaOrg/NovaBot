package org.frostnova.nova.interact.auto;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.enums.GuardType;
import org.frostnova.nova.bilibili.enums.RoomGuardLevel;
import org.frostnova.nova.bilibili.event.live.*;
import org.frostnova.nova.bilibili.model.BilibiliUserInfo;
import org.frostnova.nova.bilibili.model.FansMedal;
import org.frostnova.nova.bilibili.service.BilibiliEventParser;
import org.frostnova.nova.bilibili.service.BilibiliPkOpponentService.Profile;
import org.frostnova.nova.core.event.live.common.PkBattleEvent.Opponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PkFanWelcomeTest extends RoomInteractionFixture {
    private static final String FEATURE = "pkFanWelcome";
    private static final FansMedal MEDAL = new FansMedal(102L, null, 502L, "蓝星", 6, false);
    private final BilibiliEventParser parser = new BilibiliEventParser(new NovaBilibiliProperties(), null, null, null);

    private void configure(JSONObject options, String... features) {
        var switches = new JSONObject();
        for (String feature : features) switches.put(feature, true);
        options.put("features", switches);
        options.putIfAbsent("enabled", true);
        queue.save(101, RoomSettings.parse(options));
    }

    private void pk(int id, int status, Integer end, int... opponents) {
        var members = new java.util.ArrayList<JSONObject>();
        members.add(JSONObject.of("uid", 101, "room_id", 501));
        for (int opponent : opponents) members.add(JSONObject.of("uid", opponent, "room_id", opponent + 400));
        var basic = JSONObject.of("pk_id", id, "status", status, "type", 8);
        if (end != null) basic.put("end_time", START.plusSeconds(end).getEpochSecond());
        raw("PK_INFO", JSONObject.of("pk_basic", basic, "members", members));
    }

    private void raw(String cmd, JSONObject data) {
        events.publishEvent(parser.parse(JSONObject.of("cmd", cmd, "data", data), ROOM).orElseThrow());
    }

    private void enter(long uid, String name, FansMedal medal, RoomGuardLevel level) {
        var viewer = new BilibiliUserInfo(uid, name);
        viewer.setFansMedal(medal);
        var event = new BilibiliEnterRoomEvent(ROOM, viewer, now);
        event.setRoomGuardLevel(level);
        events.publishEvent(event);
    }

    private void fan(long uid, String name) {
        enter(uid, name, MEDAL, RoomGuardLevel.NONE);
    }

    @Test
    void welcomesUnlitMedalByOwnerFromLegacyFrame() {
        configure(new JSONObject(), FEATURE);
        pk(1, 201, 100, 102);
        raw("INTERACT_WORD", JSONObject.of("msg_type", 1, "uid", 11, "uname", "小青",
                "fans_medal", JSONObject.of("target_id", 102, "medal_name", "蓝星", "is_lighted", 0)));
        tick(0);
        assertEquals(List.of("欢迎 小青 来串门"), sent);
        assertEquals(FEATURE, store.history(101, InteractStore.Outcome.SENT, 1, now).rows().get(0).getString("feature"));
        verifyNoInteractions(opponents);
    }

    @Test
    void welcomesFourthOpponentByRoomFromV2Frame() {
        configure(new JSONObject(), FEATURE);
        pk(1, 201, 100, 102, 103, 104, 105);
        // uid=11，昵称 Lin，进房。字段 9 内的牌子仅房间号 505 匹配第四位对手。
        var bytes = HexFormat.of().parseHex("080b12034c696e28014a05086a60f903");
        raw("INTERACT_WORD_V2", JSONObject.of("pb", Base64.getEncoder().encodeToString(bytes)));
        tick(0);
        assertEquals(List.of("欢迎 Lin 来串门"), sent);
    }

    @Test
    void skipsHomeUnrelatedAndAbsentMedals() {
        configure(new JSONObject(), FEATURE);
        pk(1, 201, null, 102);
        enter(1, "本房", new FansMedal(101L, null, 501L, "蓝星", 6, true), RoomGuardLevel.NONE);
        enter(2, "同名牌子", new FansMedal(999L, null, 999L, "蓝星", 6, true), RoomGuardLevel.NONE);
        enter(3, "没有牌子", null, RoomGuardLevel.NONE);
        fan(4, "对方");
        tick(0);
        tick(3);
        assertEquals(List.of("欢迎 对方 来串门"), sent);
    }

    @Test
    void deduplicatesByUidOrFullMaskedNamePerBattle() {
        configure(new JSONObject(), FEATURE);
        pk(1, 201, null, 102);
        fan(1, "同名");
        pk(1, 201, null, 102);
        fan(1, "改名");
        fan(2, "同名");
        fan(0, "青*");
        fan(0, "青*");
        fan(0, "蓝*");
        for (int t : List.of(0, 3, 6, 9, 12)) tick(t);
        pk(2, 201, null, 102);
        fan(0, "青*");
        tick(15);
        assertEquals(List.of("欢迎 同名 来串门", "欢迎 同名 来串门", "欢迎 青* 来串门", "欢迎 蓝* 来串门", "欢迎 青* 来串门"), sent);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 20})
    void capsUniqueVisitorsAndResetsOnlyOnANewBattle(int limit) {
        var options = JSONObject.of("perMinute", 20);
        if (limit != 5) options.put("pkFanWelcomeLimit", limit);
        configure(options, FEATURE);
        pk(1, 201, null, 102);
        for (int i = 0; i <= limit; i++) {
            at(i * 3);
            fan(20 + i, "观众" + i);
            queue.tick();
        }
        pk(1, 201, null, 102);
        at(120);
        fan(99, "超额");
        queue.tick();
        assertEquals(limit, sent.size());
        pk(2, 201, null, 102);
        fan(20, "又见面");
        tick(123);
        assertEquals(limit + 1, sent.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404})
    void settlementCancelsQueuedWelcomesOnlyForItsOwnBattle(int status) {
        configure(new JSONObject(), FEATURE);
        fan(3, "还没开打");
        pk(1, 201, 300, 102);
        pk(2, 201, 300, 102);
        pk(1, status, null, 102);
        fan(1, "场中");
        tick(0);
        fan(2, "排队");
        pk(2, status, null, 102);
        pk(2, 201, 300, 102);
        fan(3, "已结算");
        tick(3);
        tick(6);
        assertEquals(List.of("欢迎 场中 来串门"), sent);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void stopsAtPlannedEndOrTenMinutesWithoutSettlement(boolean planned) {
        configure(new JSONObject(), FEATURE);
        int end = planned ? 900 : 600;
        pk(1, 201, planned ? end : null, 102);
        at(end - 5);
        pk(1, 201, planned ? end : null, 102);
        fan(1, "截止前");
        queue.tick();
        fan(2, "排队");
        at(end);
        fan(3, "截止后");
        queue.tick();
        tick(end + 3);
        assertEquals(List.of("欢迎 截止前 来串门"), sent);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepsListedAndGuardWelcomesIncludingPairedEffects(boolean effectFirst) {
        var welcome = JSONObject.of("people", List.of(JSONObject.of("uid", "1", "alias", "熟客")));
        configure(JSONObject.of("welcome", welcome), FEATURE, "guardWelcome", "listedWelcome");
        pk(1, 201, null, 102);
        var effect = new BilibiliGuardEntryEffectEvent(ROOM, 1L, GuardType.Captain);
        if (effectFirst) events.publishEvent(effect);
        enter(0, "小*明", MEDAL, RoomGuardLevel.CAPTAIN);
        if (!effectFirst) events.publishEvent(effect);
        enter(2, "小红", MEDAL, RoomGuardLevel.GOVERNOR);
        fan(3, "邻居");
        for (int t : List.of(0, 3, 6, 9)) tick(t);
        assertEquals(List.of("欢迎 熟客 进入直播间", "欢迎总督 小红 进入直播间", "欢迎 邻居 来串门"), sent);
    }

    @Test
    void recognizesNewVisitWhileEarlierEntryIsWaitingForAGuardEffect() {
        configure(new JSONObject(), FEATURE, "guardWelcome");
        fan(1, "开打前");
        pk(1, 201, null, 102);
        at(1);
        fan(1, "开打后");
        tick(4);
        assertEquals(List.of("欢迎 开打后 来串门"), sent);
    }

    @Test
    void ignoresMedalOnTheInactiveEntryPath() {
        var viewer = spy(new BilibiliUserInfo(11L, "小青"));
        viewer.setFansMedal(MEDAL);
        configure(new JSONObject(), FEATURE);
        events.publishEvent(new BilibiliEnterRoomEvent(ROOM, viewer, now));
        configure(new JSONObject());
        pk(1, 201, 10, 102);
        events.publishEvent(new BilibiliEnterRoomEvent(ROOM, viewer, now));
        configure(new JSONObject(), FEATURE);
        at(10);
        events.publishEvent(new BilibiliEnterRoomEvent(ROOM, viewer, now));
        verify(viewer, never()).getFansMedal();
        verifyNoInteractions(opponents);
    }

    @Test
    void skipsSenderAndRespectsRoomFeatureLiveAndActivityGates() {
        configure(new JSONObject());
        pk(1, 201, null, 102);
        fan(1, "功能关闭");
        tick(0);
        configure(JSONObject.of("enabled", false), FEATURE);
        fan(2, "总开关关闭");
        tick(3);
        configure(new JSONObject(), FEATURE, "pauseActivities");
        fan(7, "自己");
        tick(6);
        queue.pauseRoom(501, null, now.plusSeconds(5));
        fan(3, "房间暂停");
        tick(11);
        events.publishEvent(new BilibiliLotteryStartEvent(ROOM, 1L, now, now.plusSeconds(10)));
        fan(4, "天选");
        tick(21);
        events.publishEvent(new BilibiliRedPocketEvent(ROOM, MING, now));
        fan(5, "红包");
        tick(81);
        fan(6, "恢复");
        queue.tick();
        events.publishEvent(new BilibiliLiveOffEvent(ROOM, now));
        fan(8, "下播");
        tick(84);
        living = false;
        pk(2, 201, null, 102);
        fan(9, "未开播");
        queue.tick();
        living = true;
        events.publishEvent(new BilibiliLiveOnEvent(ROOM, now));
        fan(10, "新直播");
        tick(87);
        assertEquals(List.of("欢迎 恢复 来串门"), sent);
    }

    @Test
    void sharesWelcomePriorityAndRoomQuotaAndExpiresAtThirtySeconds() {
        configure(JSONObject.of("perMinute", 1), FEATURE, "guardWelcome");
        pk(1, 201, null, 102);
        enter(1, "舰长甲", null, RoomGuardLevel.CAPTAIN);
        fan(2, "超时");
        tick(0);
        tick(30);
        tick(60);
        fan(3, "访客");
        at(63);
        enter(4, "舰长乙", null, RoomGuardLevel.CAPTAIN);
        tick(63);
        assertEquals(List.of("欢迎舰长 舰长甲 进入直播间", "欢迎 访客 来串门"), sent);
        assertTrue(store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows().stream()
                .anyMatch(r -> FEATURE.equals(r.getString("feature")) && r.getString("reason").contains("时限")));
    }

    @Test
    void sharesOpponentLookupAndFallsBackWhenMissing() {
        var blue = new Opponent(502L, 102L, null, null);
        var green = new Opponent(503L, 103L, null, null);
        var pending = new CompletableFuture<Profile>();
        when(opponents.lookup(1L, blue)).thenReturn(pending);
        when(opponents.lookup(1L, green)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
        configure(JSONObject.of("pkFanWelcomeTemplate", "{主播}欢迎{对手}家的{用户}（{牌子}）"), FEATURE, "pkOpponent");
        pk(1, 201, null, 102, 103);
        fan(1, "小青");
        fan(2, "小红");
        pending.complete(new Profile("小蓝", null, null, null));
        enter(3, "访客", new FansMedal(103L, null, 503L, "绿叶", 1, false), RoomGuardLevel.NONE);
        for (int t : List.of(0, 3, 6, 9)) tick(t);
        assertEquals(List.of("本场PK对手：小蓝", "示例主播欢迎小蓝家的小青（蓝星）", "示例主播欢迎小蓝家的小红（蓝星）",
                "示例主播欢迎对面主播家的访客（绿叶）"), sent);
        verify(opponents).lookup(1L, blue);
        verify(opponents).lookup(1L, green);
    }

    @Test
    void queuedWelcomesHonorALoweredLimitAndDroppedSlotsCanBeUsedAgain() {
        configure(JSONObject.of("pkFanWelcomeLimit", 3), FEATURE);
        pk(1, 201, null, 102);
        fan(1, "甲");
        fan(2, "乙");
        fan(3, "丙");
        tick(0);
        configure(JSONObject.of("pkFanWelcomeLimit", 1), FEATURE);
        tick(3);
        tick(6);
        assertEquals(List.of("欢迎 甲 来串门"), sent);
        configure(JSONObject.of("pkFanWelcomeLimit", 2), FEATURE);
        fan(2, "乙");
        tick(9);
        assertEquals(List.of("欢迎 甲 来串门", "欢迎 乙 来串门"), sent);
    }

    @Test
    void looksUpOnlyTheMatchedOpponentOnceAndDropsLateResults() {
        var opponent = new Opponent(502L, 102L, null, null);
        var pending = new CompletableFuture<Profile>();
        when(opponents.lookup(1L, opponent)).thenReturn(pending);
        configure(JSONObject.of("pkFanWelcomeTemplate", "{对手}家的{用户}"), FEATURE);
        pk(1, 201, null, 102, 103);
        fan(1, "甲");
        fan(2, "乙");
        tick(0);
        pk(1, 404, null, 102, 103);
        pending.complete(new Profile("小蓝", null, null, null));
        tick(3);
        assertTrue(sent.isEmpty());
        verify(opponents).lookup(1L, opponent);
        verifyNoMoreInteractions(opponents);
    }

    @Test
    void restartsAtTheNextOpening() {
        configure(new JSONObject(), FEATURE);
        pk(1, 201, null, 102);
        fan(1, "排队");
        events.close();
        state.save();
        start();
        fan(2, "重启后");
        pk(1, 201, null, 102);
        fan(3, "重复开打");
        tick(0);
        pk(2, 201, null, 102);
        fan(1, "下一场");
        tick(3);
        assertEquals(List.of("欢迎 下一场 来串门"), sent);
    }
}
