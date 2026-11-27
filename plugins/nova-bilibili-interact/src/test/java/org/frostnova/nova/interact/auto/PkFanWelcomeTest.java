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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
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
        pkWithPlanEnd(id, status, end == null ? null : START.plusSeconds(end).getEpochSecond(), opponents);
    }

    private void pkWithPlanEnd(int id, int status, Long end, int... opponents) {
        var members = new java.util.ArrayList<JSONObject>();
        members.add(JSONObject.of("uid", 101, "room_id", 501));
        for (int opponent : opponents) members.add(JSONObject.of("uid", opponent, "room_id", opponent + 400));
        var basic = JSONObject.of("pk_id", id, "status", status, "type", 8);
        if (end != null) basic.put("end_time", end);
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
    void deduplicatesByUidPerBattleEvenIfNamesChangeOrMatch() {
        configure(new JSONObject(), FEATURE);
        pk(1, 201, null, 102);
        fan(1, "同名");
        pk(1, 201, null, 102);
        fan(1, "改名");
        fan(2, "同名");
        tick(0);
        tick(3);
        pk(2, 201, null, 102);
        fan(1, "改名");
        tick(6);
        assertEquals(List.of("欢迎 同名 来串门", "欢迎 同名 来串门", "欢迎 改名 来串门"), sent);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void maskedVisitorGetsOnlyListedWelcomeInEitherOrder(boolean effectFirst) {
        var welcome = JSONObject.of("people", List.of(JSONObject.of("uid", "11", "alias", "熟客")));
        configure(JSONObject.of("welcome", welcome), FEATURE, "listedWelcome");
        pk(1, 201, null, 102);
        var effect = new BilibiliGuardEntryEffectEvent(ROOM, 11L, GuardType.Captain);
        if (effectFirst) events.publishEvent(effect);
        else fan(0, "小*");
        tick(0);
        at(2);
        if (effectFirst) fan(0, "小*");
        else events.publishEvent(effect);
        tick(3);
        tick(6);
        assertEquals(List.of("欢迎 熟客 进入直播间"), sent);
    }

    @Test
    void maskedEntriesWithoutPositiveUidNeverTriggerVisitorWelcome() {
        configure(new JSONObject(), FEATURE);
        pk(1, 201, null, 102);
        var entry = JSONObject.of("msg_type", 1, "uname", "南*", "fans_medal", JSONObject.of("target_id", 102));
        raw("INTERACT_WORD", entry);
        entry.put("uid", 0);
        entry.put("uname", "东*");
        raw("INTERACT_WORD", entry);
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 11L, GuardType.Captain));
        tick(3);
        tick(6);
        assertTrue(sent.isEmpty(), sent::toString);
    }

    @Test
    void listedUidSkipsVisitorWelcomeEvenAfterListedWelcomeWasSent() {
        var welcome = JSONObject.of("people", List.of(JSONObject.of("uid", "11", "alias", "熟客")));
        configure(JSONObject.of("welcome", welcome), FEATURE, "listedWelcome");
        pk(1, 201, null, 102);
        fan(11, "小青");
        tick(0);
        at(4);
        fan(11, "改名");
        tick(7);
        assertEquals(List.of("欢迎 熟客 进入直播间"), sent);
    }

    @Test
    void qualifyingEntryRankSkipsVisitorWelcomeEvenAfterGuardWelcomeWasSent() {
        configure(JSONObject.of("welcome", JSONObject.of("minimumLevel", "COMMANDER")), FEATURE, "guardWelcome");
        pk(1, 201, null, 102);
        enter(11, "小青", MEDAL, RoomGuardLevel.COMMANDER);
        tick(0);
        at(4);
        enter(11, "小青", MEDAL, RoomGuardLevel.GOVERNOR);
        tick(4);
        assertEquals(List.of("欢迎提督 小青 进入直播间"), sent);
    }

    @Test
    void realUidVisitorIsWelcomedImmediatelyEvenIfAnEffectLaterRevealsRank() {
        configure(new JSONObject(), FEATURE, "guardWelcome");
        pk(1, 201, null, 102);
        fan(11, "北山");
        tick(0);
        assertEquals(List.of("欢迎 北山 来串门"), sent);
        at(1);
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 11L, GuardType.Captain));
        tick(3);
        assertEquals(List.of("欢迎 北山 来串门", "欢迎舰长 北山 进入直播间"), sent);
    }

    @Test
    void visitorSwitchPreservesWelcomesForInterleavedEntriesAndEffects(@TempDir Path secondRoomState) {
        replayEntriesAndEffects(false);
        var original = List.copyOf(sent);
        assertEquals(List.of("欢迎 熟客 进入直播间", "欢迎 老友 进入直播间", "欢迎提督 西* 进入直播间"), original);

        events.close();
        dir = secondRoomState;
        at(0);
        sent.clear();
        start();
        replayEntriesAndEffects(true);
        assertEquals(original, sent.stream().filter(text -> !text.endsWith("来串门")).toList());
        assertEquals(List.of("欢迎 邻居 来串门"), sent.stream().filter(text -> text.endsWith("来串门")).toList());
    }

    private void replayEntriesAndEffects(boolean visitors) {
        var welcome = JSONObject.of("people", List.of(JSONObject.of("uid", "11", "alias", "熟客"),
                JSONObject.of("uid", "12", "alias", "老友")));
        var features = JSONObject.of("guardWelcome", true, "listedWelcome", true, FEATURE, visitors);
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", true, "features", features, "welcome", welcome)));
        pk(1, 201, null, 102);
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 11L, GuardType.Captain));
        at(1);
        fan(0, "南*");
        at(2);
        enter(0, "东*", MEDAL, RoomGuardLevel.CAPTAIN);
        tick(5);
        at(6);
        enter(0, "北*", MEDAL, RoomGuardLevel.CAPTAIN);
        at(8);
        events.publishEvent(new BilibiliGuardEntryEffectEvent(ROOM, 12L, GuardType.Captain));
        tick(8);
        at(13);
        fan(12, "北山");
        enter(0, "西*", MEDAL, RoomGuardLevel.COMMANDER);
        tick(16);
        fan(13, "邻居");
        for (int t : List.of(19, 22, 25, 28, 31)) tick(t);
    }

    @Test
    void opponentAnnouncementSurvivesUnrepresentablePlanEndWithVisitorSwitchOff() {
        configure(new JSONObject(), "pkOpponent");
        var opponent = new Opponent(502L, 102L, null, null);
        when(opponents.lookup(1L, opponent)).thenReturn(CompletableFuture.completedFuture(new Profile("青", null, null, null)));
        pkWithPlanEnd(1, 201, Long.MAX_VALUE, 102);
        tick(0);
        assertEquals(List.of("本场PK对手：青"), sent);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MAX_VALUE, 1767226200000L})
    void implausiblePlanEndUsesTenMinuteFallback(long planEnd) {
        configure(new JSONObject(), FEATURE);
        pkWithPlanEnd(1, 201, planEnd, 102);
        at(597);
        fan(1, "截止前");
        queue.tick();
        at(600);
        fan(2, "截止后");
        queue.tick();
        assertEquals(List.of("欢迎 截止前 来串门"), sent);
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 15})
    void endBeforeOpeningUsesTenMinutesFromStartAndAnnouncesOpponent(int receipt) {
        configure(new JSONObject(), FEATURE, "pkOpponent");
        var opponent = new Opponent(502L, 102L, null, null);
        when(opponents.lookup(1L, opponent)).thenReturn(CompletableFuture.completedFuture(new Profile("青", null, null, null)));
        at(receipt);
        var basic = JSONObject.of("pk_id", 1, "status", 201, "start_time", START.getEpochSecond(),
                "end_time", START.minusSeconds(1).getEpochSecond());
        raw("PK_INFO", JSONObject.of("pk_basic", basic, "members", List.of(
                JSONObject.of("uid", 101, "room_id", 501), JSONObject.of("uid", 102, "room_id", 502))));
        tick(receipt);
        at(599);
        fan(11, "截止前");
        queue.tick();
        at(600);
        fan(12, "截止后");
        tick(603);
        assertEquals(List.of("本场PK对手：青", "欢迎 截止前 来串门"), sent);
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
    void sendsRegularAndVisitorWelcomesInQueueOrder() {
        configure(JSONObject.of("welcome", JSONObject.of("minimumLevel", "GOVERNOR")), FEATURE, "guardWelcome");
        pk(1, 201, null, 102);
        enter(1, "甲", null, RoomGuardLevel.GOVERNOR);
        enter(2, "乙", MEDAL, RoomGuardLevel.CAPTAIN);
        enter(3, "丙", null, RoomGuardLevel.GOVERNOR);
        tick(0);
        tick(3);
        tick(6);
        assertEquals(List.of("欢迎总督 甲 进入直播间", "欢迎 乙 来串门", "欢迎总督 丙 进入直播间"), sent);
    }

    @Test
    void visitorAndRegularWelcomesConsumeTheSameMinuteQuota() {
        configure(JSONObject.of("perMinute", 1), FEATURE, "guardWelcome");
        pk(1, 201, null, 102);
        enter(1, "甲", null, RoomGuardLevel.CAPTAIN);
        tick(0);
        at(40);
        fan(2, "乙");
        tick(59);
        assertEquals(List.of("欢迎舰长 甲 进入直播间"), sent);
        tick(60);
        assertEquals(List.of("欢迎舰长 甲 进入直播间", "欢迎 乙 来串门"), sent);
        at(100);
        enter(3, "丙", null, RoomGuardLevel.CAPTAIN);
        tick(119);
        assertEquals(2, sent.size());
        tick(120);
        assertEquals(List.of("欢迎舰长 甲 进入直播间", "欢迎 乙 来串门", "欢迎舰长 丙 进入直播间"), sent);
    }

    @Test
    void visitorExpiresExactlyThirtySecondsAfterEntry() {
        configure(new JSONObject(), FEATURE);
        pk(1, 201, null, 102);
        fan(1, "及时");
        tick(29);
        fan(2, "超时");
        tick(59);
        assertEquals(List.of("欢迎 及时 来串门"), sent);
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
