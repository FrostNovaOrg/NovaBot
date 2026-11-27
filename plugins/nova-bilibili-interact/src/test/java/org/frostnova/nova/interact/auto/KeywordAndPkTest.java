package org.frostnova.nova.interact.auto;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.event.live.*;
import org.frostnova.nova.bilibili.service.BilibiliPkOpponentService.Profile;
import org.frostnova.nova.core.event.live.common.PkBattleEvent.Opponent;
import org.frostnova.nova.core.event.live.common.PkBattleEvent.Stage;
import org.frostnova.nova.core.model.EmojiInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KeywordAndPkTest extends RoomInteractionFixture {
    private static final Opponent OTHER = new Opponent(502L, 102L, null, null);
    private static final Opponent RED = new Opponent(503L, 103L, null, null);
    private static final Opponent GREEN = new Opponent(504L, 104L, null, null);
    private static final Opponent WHITE = new Opponent(505L, 105L, null, null);
    private static final Profile PROFILE = new Profile("小蓝", 123456L, 0, null);
    private static final String ANNOUNCEMENT = "本场PK对手：小蓝，粉丝 12.3万，大航海 0";
    private static final String RED_ANNOUNCEMENT = "本场PK对手：小红";
    private static final String GREEN_ANNOUNCEMENT = "本场PK对手：小绿";

    private JSONObject rule(String word, String... replies) {
        return JSONObject.of("keywords", List.of(word), "replies", List.of(replies));
    }

    private void configure(boolean master, JSONObject features, List<JSONObject> rules, String template) {
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", master, "features", features,
                "keywordRules", rules, "pkTemplate", template)));
    }

    private void keywords(JSONObject... rules) {
        configure(true, JSONObject.of("keywordReply", true), List.of(rules), "");
    }

    private void say(UserInfo viewer, String text) {
        events.publishEvent(new BilibiliDanmuEvent(ROOM, viewer, text, text, now));
    }

    private void pk(long id, Stage stage, Opponent... listed) {
        List<Opponent> lineup = listed.length == 0 ? List.of(OTHER) : List.of(listed);
        int type = lineup.size() == 1 ? 6 : 8;
        events.publishEvent(new BilibiliPkBattleEvent(ROOM, id, type, stage, lineup.size() + 1, null, null,
                null, null, null, null, null, lineup, now));
    }

    private void enablePk() {
        configure(true, JSONObject.of("pkOpponent", true), List.of(), "");
    }

    private void stubPkProfiles(long id) {
        when(opponents.lookup(id, OTHER)).thenReturn(CompletableFuture.completedFuture(PROFILE));
        when(opponents.lookup(id, RED)).thenReturn(CompletableFuture.completedFuture(new Profile("小红", null, null, null)));
        when(opponents.lookup(id, GREEN)).thenReturn(CompletableFuture.completedFuture(new Profile("小绿", null, null, null)));
    }

    @Test
    @DisplayName("同一句命中多条后连回、冷却时改回后面的规则，或轮流回复漏掉一句")
    void firstMatchingRuleOwnsTheMessageEvenWhileCoolingDown() {
        var first = rule(" Qun号 ", "一{用户}", "二{主播}", "三");
        var exact = rule("qun号多少", "后面的规则");
        exact.put("exact", true);
        keywords(first, exact);
        var viewer = new UserInfo(0L, "一二三四五六七八九😀十一十二十三");
        say(viewer, " QUN号多少 ");
        tick(0);
        at(59);
        say(HONG, "qun号多少");
        tick(59);
        for (int seconds : List.of(60, 120, 180)) {
            at(seconds);
            say(viewer, "QUN号多少");
            tick(seconds);
        }
        assertEquals(List.of("一一二三四五六七八九😀…", "二示例主播", "三", "一一二三四五六七八九😀…"), sent);
    }

    @Test
    @DisplayName("机器人给纯表情接话，或误回复只匹配部分文字和已停用的规则")
    void ignoresPureEmojiAndMatchesLiteralText() {
        var first = rule(".*", "甲");
        first.put("exact", true);
        var off = rule("停用", "不该回");
        off.put("enabled", false);
        keywords(first, off, rule("比心", "谢谢"));
        say(MING, "随便一句");
        say(MING, "a.*");
        say(MING, "停用");
        var emoji = new EmojiInfo("emote_1", "比心", "https://example.invalid/heart.png");
        events.publishEvent(new BilibiliEmojiEvent(ROOM, MING, emoji, now));
        tick(0);
        assertEquals(List.of(), sent);
        say(MING, "  .*  ");
        tick(0);
        assertEquals(List.of("甲"), sent);
    }

    @ParameterizedTest
    @CsvSource({"1, , 2, 小红", "0, 相同前缀一二三四五六七*甲, 0, 相同前缀一二三四五六七*乙"})
    @DisplayName("一个人刷不同关键词，机器人在 30 秒内对着他连回两句")
    void limitsEachViewerByFullIdentity(long uid, String name, long otherUid, String otherName) {
        var second = rule("时间", "晚上八点");
        second.put("cooldownSeconds", 10);
        keywords(rule("群号", "群号见简介"), second);
        var viewer = new UserInfo(uid, name);
        say(viewer, "群号");
        tick(0);
        assertEquals(List.of("群号见简介"), sent);
        at(3);
        say(viewer, "时间");
        tick(3);
        assertEquals(List.of("群号见简介"), sent);
        say(new UserInfo(otherUid, otherName), "时间");
        tick(3);
        assertEquals(List.of("群号见简介", "晚上八点"), sent);
        at(29);
        say(viewer, "时间");
        tick(29);
        assertEquals(List.of("群号见简介", "晚上八点"), sent);
        at(30);
        say(viewer, "时间");
        tick(30);
        assertEquals(List.of("群号见简介", "晚上八点", "晚上八点"), sent);
    }

    @Test
    @DisplayName("机器人回自己的文字，或给没开功能、没开播和别的直播间的观众接话")
    void guardsRoomSwitchesAndBothKindsOfSelfEcho() {
        var rules = List.of(rule("群号", "群号多少"), rule("多少", "回声"));
        var on = JSONObject.of("keywordReply", true);
        configure(true, new JSONObject(), rules, "");
        say(MING, "群号");
        configure(false, on, rules, "");
        say(MING, "群号");
        configure(true, on, rules, "");
        living = false;
        say(MING, "群号");
        living = true;
        say(SELF, "多少");
        events.publishEvent(new BilibiliDanmuEvent(new LiveStreamerInfo(102L, "乙", 502L), HONG, "多少", "多少", now));
        say(MING, "群号");
        tick(0);
        keywords(rule("多少", "回声"));
        say(new UserInfo(0L, "发*"), "群号多少");
        tick(3);
        assertEquals(List.of("群号多少"), sent);
    }

    @Test
    @DisplayName("同一场重复开打或结算又播对手，缺项留下空标签或把零当成未知")
    void opensOnceAndFormatsOnlyAvailableFields() {
        when(opponents.lookup(anyLong(), eq(OTHER))).thenReturn(CompletableFuture.completedFuture(PROFILE));
        pk(10, Stage.OPEN);
        configure(true, JSONObject.of("pkOpponent", true), List.of(), "\u00a0\uFEFF");
        living = false;
        pk(11, Stage.OPEN);
        living = true;
        pk(12, Stage.SETTLE);
        pk(13, Stage.OPEN);
        pk(13, Stage.OPEN);
        tick(0);
        configure(true, JSONObject.of("pkOpponent", true), List.of(), "{主播}遇到{对手}：{高能}/{舰队}/{粉丝}");
        pk(14, Stage.OPEN);
        tick(3);
        assertEquals(List.of(ANNOUNCEMENT, "示例主播遇到小蓝：未知/0/12.3万"), sent);
        verify(opponents).lookup(13L, OTHER);
        verify(opponents).lookup(14L, OTHER);
        verifyNoMoreInteractions(opponents);
    }

    @Test
    void announcesOneOpponentAsBefore() {
        enablePk();
        stubPkProfiles(20);
        pk(20, Stage.OPEN, OTHER);
        tick(0);
        tick(3);
        assertEquals(List.of(ANNOUNCEMENT), sent);
    }

    @Test
    void announcesBothOpponentsInPlatformOrder() {
        enablePk();
        stubPkProfiles(21);
        var first = new CompletableFuture<Profile>();
        when(opponents.lookup(21L, OTHER)).thenReturn(first);
        pk(21, Stage.OPEN, OTHER, RED);
        tick(0);
        assertTrue(sent.isEmpty());
        at(1);
        first.complete(PROFILE);
        tick(1);
        tick(4);
        assertEquals(List.of(ANNOUNCEMENT, RED_ANNOUNCEMENT), sent);
    }

    @Test
    void announcesOnlyTheFirstThreeListedOpponents() {
        enablePk();
        stubPkProfiles(22);
        pk(22, Stage.OPEN, OTHER, GREEN, RED, WHITE);
        for (int seconds : List.of(0, 3, 6, 9)) tick(seconds);
        assertEquals(List.of(ANNOUNCEMENT, GREEN_ANNOUNCEMENT, RED_ANNOUNCEMENT), sent);
        verify(opponents, never()).lookup(22L, WHITE);
    }

    @Test
    void ignoresRepeatedLineupEvenWhileLookingUpProfiles() {
        enablePk();
        stubPkProfiles(23);
        var first = new CompletableFuture<Profile>();
        when(opponents.lookup(23L, OTHER)).thenReturn(first);
        pk(23, Stage.OPEN, OTHER, RED);
        pk(23, Stage.OPEN, OTHER, RED);
        first.complete(PROFILE);
        tick(0);
        tick(3);
        pk(23, Stage.OPEN, OTHER, RED);
        tick(6);
        assertEquals(List.of(ANNOUNCEMENT, RED_ANNOUNCEMENT), sent);
        verify(opponents).lookup(23L, OTHER);
        verify(opponents).lookup(23L, RED);
    }

    @Test
    void addsNewOpponentsInOrderUpToThreePerBattle() {
        enablePk();
        stubPkProfiles(24);
        var first = new CompletableFuture<Profile>();
        when(opponents.lookup(24L, OTHER)).thenReturn(first);
        pk(24, Stage.OPEN, OTHER);
        pk(24, Stage.OPEN, OTHER, RED);
        tick(0);
        assertTrue(sent.isEmpty());
        first.complete(PROFILE);
        tick(1);
        tick(4);
        pk(24, Stage.OPEN, RED, GREEN, OTHER);
        tick(7);
        pk(24, Stage.OPEN, WHITE, GREEN, RED);
        tick(10);
        assertEquals(List.of(ANNOUNCEMENT, RED_ANNOUNCEMENT, GREEN_ANNOUNCEMENT), sent);
        verify(opponents).lookup(24L, OTHER);
        verify(opponents).lookup(24L, RED);
        verify(opponents).lookup(24L, GREEN);
        verifyNoMoreInteractions(opponents);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void supplementUsesOriginalSendDeadline(boolean restart) {
        enablePk();
        stubPkProfiles(25);
        pk(25, Stage.OPEN, OTHER);
        tick(0);
        if (restart) {
            events.close();
            state.save();
            start();
        }
        at(59);
        pk(25, Stage.OPEN, OTHER, RED);
        tick(60);
        pk(25, Stage.OPEN, OTHER, RED, GREEN);
        tick(63);
        assertEquals(List.of(ANNOUNCEMENT), sent);
        verify(opponents).lookup(25L, RED);
        verify(opponents, never()).lookup(25L, GREEN);
        assertTrue(store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows().stream()
                .anyMatch(r -> RED_ANNOUNCEMENT.equals(r.getString("text")) && r.getString("reason").contains("时限")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skipsOnlyTheOpponentWithMissingOrLateProfile(boolean late) {
        enablePk();
        stubPkProfiles(26);
        var unavailable = new CompletableFuture<Profile>();
        when(opponents.lookup(26L, RED)).thenReturn(unavailable);
        pk(26, Stage.OPEN, RED, OTHER, GREEN);
        if (late) {
            at(11);
            unavailable.complete(new Profile("过时", null, null, null));
        } else {
            unavailable.complete(new Profile("　", null, null, null));
        }
        tick(11);
        tick(14);
        assertEquals(List.of(ANNOUNCEMENT, GREEN_ANNOUNCEMENT), sent);
    }

    @Test
    void ignoresMultiplayerSettlement() {
        enablePk();
        stubPkProfiles(27);
        pk(27, Stage.SETTLE, OTHER, RED);
        tick(0);
        tick(3);
        assertTrue(sent.isEmpty());
        verifyNoInteractions(opponents);
    }

    @Test
    void restoresIndividualOpponentsAfterRestart() {
        enablePk();
        stubPkProfiles(28);
        pk(28, Stage.OPEN, OTHER, RED);
        tick(0);
        tick(3);
        events.close();
        state.save();
        start();
        pk(28, Stage.OPEN, OTHER, RED, GREEN);
        tick(6);
        tick(9);
        tick(12);
        assertEquals(List.of(ANNOUNCEMENT, RED_ANNOUNCEMENT, GREEN_ANNOUNCEMENT), sent);
        verify(opponents).lookup(28L, OTHER);
        verify(opponents).lookup(28L, RED);
        verify(opponents).lookup(28L, GREEN);
    }

    @Test
    void treatsLegacyBattleIdAsEntireBattleAnnounced() {
        enablePk();
        state.write("LiveInteractThanked", root -> root.put("101",
                JSONObject.of("start", broadcastStart, "pkOpponent", List.of("29"))));
        events.close();
        state.save();
        start();
        pk(29, Stage.OPEN, OTHER, RED, GREEN);
        tick(0);
        tick(3);
        tick(6);
        assertTrue(sent.isEmpty());
        verifyNoInteractions(opponents);
    }

    @Test
    void skipsAllOpponentsWhenDisabledPausedOrOffline() {
        stubPkProfiles(30);
        stubPkProfiles(31);
        stubPkProfiles(32);
        configure(true, new JSONObject(), List.of(), "");
        pk(30, Stage.OPEN, OTHER, RED);
        enablePk();
        queue.pauseRoom(501, null, now.plusSeconds(60));
        pk(31, Stage.OPEN, OTHER, RED);
        queue.pauseRoom(501, null, now);
        living = false;
        pk(32, Stage.OPEN, OTHER, RED);
        tick(0);
        tick(3);
        assertTrue(sent.isEmpty());
        verifyNoInteractions(opponents);
    }

    @Test
    void logsListedAndPlannedCountsOncePerBattle() {
        enablePk();
        stubPkProfiles(33);
        var logger = (Logger) LoggerFactory.getLogger(AutoDanmakuQueue.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            pk(33, Stage.OPEN, OTHER, RED, GREEN, WHITE);
            pk(33, Stage.OPEN, OTHER, RED, GREEN, WHITE);
            pk(33, Stage.SETTLE, OTHER, RED, GREEN, WHITE);
            var openings = logs.list.stream().filter(log -> log.getFormattedMessage().contains("PK 开打")).toList();
            assertEquals(1, openings.size());
            var opening = openings.get(0);
            assertEquals(Level.INFO, opening.getLevel());
            for (String field : List.of("直播间=501", "场次=33", "平台对手数=4", "计划播报数=3")) {
                assertTrue(opening.getFormattedMessage().contains(field), opening::getFormattedMessage);
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void countsEachOpponentAgainstRoomSendLimit() {
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", true, "perMinute", 1,
                "features", JSONObject.of("pkOpponent", true))));
        stubPkProfiles(34);
        pk(34, Stage.OPEN, OTHER, RED, GREEN);
        for (int seconds : List.of(0, 3, 6, 60)) tick(seconds);
        assertEquals(List.of(ANNOUNCEMENT), sent);
        var expired = store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows().stream()
                .filter(r -> r.getString("reason").contains("时限"))
                .map(r -> r.getString("text")).toList();
        assertEquals(2, expired.size());
        assertTrue(expired.containsAll(List.of(RED_ANNOUNCEMENT, GREEN_ANNOUNCEMENT)));
    }

    @Test
    @DisplayName("查不到名字、超时或关掉后再开启时，迟到的资料仍发进房间")
    void ignoresMissingLateAndWithdrawnLookups() {
        var on = JSONObject.of("pkOpponent", true);
        configure(true, on, List.of(), "");
        var late = new CompletableFuture<Profile>();
        var withdrawn = new CompletableFuture<Profile>();
        when(opponents.lookup(1L, OTHER)).thenReturn(late);
        when(opponents.lookup(2L, OTHER)).thenReturn(withdrawn);
        when(opponents.lookup(3L, OTHER)).thenReturn(CompletableFuture.completedFuture(new Profile("　", 1L, 2, 3)));
        when(opponents.lookup(4L, OTHER)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
        pk(1, Stage.OPEN);
        at(11);
        late.complete(PROFILE);
        tick(11);
        assertEquals(List.of(), sent);
        pk(2, Stage.OPEN);
        configure(true, new JSONObject(), List.of(), "");
        configure(true, on, List.of(), "");
        withdrawn.complete(PROFILE);
        pk(3, Stage.OPEN);
        pk(4, Stage.OPEN);
        tick(11);
        assertTrue(sent.isEmpty(), sent::toString);
        verify(opponents).lookup(1L, OTHER);
    }

    @Test
    @DisplayName("刷关键词挤掉上舰、醒目留言、PK 或礼物，超过每分钟额度或补发过时回复")
    void sendsInPriorityOrderAndDropsExpiredMessages() {
        var features = JSONObject.of("keywordReply", true, "pkOpponent", true, "gift", true, "membership", true, "superChat", true);
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", true, "features", features,
                "perMinute", 4, "keywordRules", List.of(rule("群号", "回群号")))));
        paid(MING, "花", 1);
        at(8);
        say(MING, "群号");
        when(opponents.lookup(anyLong(), eq(OTHER))).thenReturn(CompletableFuture.completedFuture(PROFILE));
        pk(1, Stage.OPEN);
        events.publishEvent(new BilibiliSuperChatEvent(ROOM, HONG, "留言", 30.0));
        events.publishEvent(new BilibiliCaptainEvent(ROOM, HONG, 100.0, 1, "月"));
        for (int seconds : List.of(8, 11, 14, 17, 20, 38)) tick(seconds);
        var rows = store.history(101, InteractStore.Outcome.SENT, 1, now).rows();
        assertEquals(List.of("gift", "pkOpponent", "superChat", "membership"), rows.stream().map(r -> r.getString("feature")).toList());
        assertEquals("已过发送时限", store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows().get(0).getString("reason"));
    }

    @Test
    @DisplayName("关开关、天选暂停或查询用掉部分时间后，还能发出之前积压的弹幕")
    void cancelsPendingWorkAndExpiresFromOpen() {
        var rules = List.of(rule("群号", "回复"));
        var features = JSONObject.of("keywordReply", true, "pkOpponent", true, "pauseActivities", true);
        configure(true, features, rules, "");
        when(opponents.lookup(anyLong(), eq(OTHER))).thenReturn(CompletableFuture.completedFuture(PROFILE));
        say(MING, "群号");
        pk(1, Stage.OPEN);
        configure(true, new JSONObject(), rules, "");
        configure(true, features, rules, "");
        tick(0);
        assertEquals(List.of(), sent);
        at(61);
        events.publishEvent(new BilibiliLotteryStartEvent(ROOM, 1L, now, now.plusSeconds(60)));
        say(HONG, "群号");
        pk(2, Stage.OPEN);
        tick(61);
        assertEquals(List.of(), sent);
        at(122);
        var waiting = new CompletableFuture<Profile>();
        when(opponents.lookup(3L, OTHER)).thenReturn(waiting);
        pk(3, Stage.OPEN);
        at(131);
        waiting.complete(PROFILE);
        tick(182);
        assertTrue(sent.isEmpty(), sent::toString);
        assertTrue(store.history(101, InteractStore.Outcome.DROPPED, 1, now).rows().stream()
                .anyMatch(r -> "pkOpponent".equals(r.getString("feature")) && r.getString("reason").contains("时限")));
    }

    @Test
    @DisplayName("重启后补发上次排队的弹幕，或重启后观众仍被旧冷却挡住")
    void restartsWithSettingsAndFreshCooldownsOnly() {
        keywords(rule("群号", "回复"));
        say(MING, "群号");
        events.close();
        state.save();
        start();
        tick(1);
        assertTrue(sent.isEmpty());
        say(MING, "群号");
        tick(2);
        assertEquals(List.of("回复"), sent);
    }
}
