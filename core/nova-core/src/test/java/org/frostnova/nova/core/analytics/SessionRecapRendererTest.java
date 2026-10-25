package org.frostnova.nova.core.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.frostnova.nova.core.analytics.SessionRecap.*;
import static org.frostnova.nova.core.analytics.SessionRecapRenderer.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionRecapRendererTest {
    @Test @DisplayName("规律被三场去重压下时给管理员解释，避免误以为没有算出规律")
    void repeatedPatternHasAnExplanation() {
        var current = result(facts(), List.of(regulars("strong", 16)));
        var previous = new History(START - DAY, List.of(new SpokenPattern("regulars_absent", Map.of("regulars", 30, "absent", 16))));
        var draft = render(current, 2, true, Nicknames.MASKED, List.of(previous));
        assertTrue(draft.history().patterns().isEmpty());
        assertTrue(draft.notes().stream().anyMatch(n -> n.keys().contains("regulars_absent")
                && n.text().contains("这条规律 3 场内说过，这次没说")));
    }

    @ParameterizedTest @ValueSource(ints = {0, 2})
    @DisplayName("只解释原本前两条被去重的规律，避免把排在后面的规律误说成因去重没说")
    void explainsOnlyRepeatedPatternsInOriginalTopTwo(int repeatedIndex) {
        var patterns = List.of(newcomer("strong"), regulars("strong", 16), slots("strong", 9, 8));
        var repeated = patterns.get(repeatedIndex);
        var previous = new History(START - DAY, List.of(new SpokenPattern(repeated.key(), repeated.values())));
        var draft = render(result(List.of(), patterns), 2, true, Nicknames.MASKED, List.of(previous));
        var explanations = draft.notes().stream()
                .filter(n -> n.text().contains("这条规律 3 场内说过，这次没说")).toList();
        assertTrue(draft.message().contains("常来的 30 位观众"));
        if (repeatedIndex == 0) {
            assertEquals(List.of("regulars_absent", "slot_compare"), selected(draft));
            assertTrue(draft.message().contains("中位数是 200"));
            assertFalse(draft.message().contains("第一次来就开口"));
            assertEquals(List.of(new Line(List.of(repeated.key()), "这条规律 3 场内说过，这次没说")), explanations);
        } else {
            assertEquals(List.of("newcomer_speak_return", "regulars_absent"), selected(draft));
            assertTrue(draft.message().contains("第一次来就开口"));
            assertFalse(draft.message().contains("中位数是 200"));
            assertTrue(explanations.isEmpty(), "第三条原本也不会说，不应解释成因去重没说");
        }
    }

    @Test @DisplayName("第一和第三条同时重复时只解释第一条，正文补上第二和第四条")
    void explainsOnlyTopTwoWhenMultiplePatternsRepeat() {
        var patterns = List.of(newcomer("strong"), regulars("strong", 16), slots("strong", 9, 8), pk("strong", 8));
        var draft = render(result(List.of(), patterns), 2, true, Nicknames.MASKED,
                List.of(history(START - DAY, patterns.get(0), patterns.get(2))));
        assertEquals(List.of(new Line(List.of("newcomer_speak_return"), "这条规律 3 场内说过，这次没说")), draft.notes());
        assertEquals(List.of("regulars_absent", "pk_share"), selected(draft));
        assertEquals(List.of(List.of("regulars_absent"), List.of("pk_share")),
                draft.lines().stream().skip(1).map(Line::keys).toList());
        assertTrue(draft.message().contains("常来的 30 位观众"));
        assertTrue(draft.message().contains("PK 时每分钟礼物 8 元"));
    }

    // All identities, nicknames and words are synthetic.
    static final long HOST = 910000001L, BASE = 920000001L;
    static final long START = ZonedDateTime.parse("2026-10-25T09:01:00+08:00").toInstant().toEpochMilli();
    static final long DAY = 86_400_000L;
    static final Map<Long, String> NAMES = Map.of(BASE, "观众甲", BASE + 1, "观众乙", BASE + 2, "观众丙",
            BASE + 3, "观众丁", BASE + 4, "观众戊", HOST, "合成主播");
    Item fact(String key, Map<String, Object> values, List<Long> people) {
        return new Item(key, new Streamer("test", HOST), START, values, new Sample(10, START - 10 * DAY),
                null, people, List.of());
    }
    Item fact(String key, Map<String, Object> values) { return fact(key, values, List.of()); }
    Item pattern(String key, String strength, Map<String, Object> values, int qualified) {
        return new Item(key, new Streamer("test", HOST), START, values,
                new Sample(25, START - 25 * DAY, qualified), strength, List.of(), List.of());
    }
    Result result(List<Item> facts, List<Item> patterns) {
        return new Result(facts, patterns, new Presentation(START, 25, "complete", START - 2 * DAY, NAMES, Set.of()));
    }
    List<Item> facts() {
        return List.of(fact("duration", Map.of("start", START, "end", START + 250 * 60_000L, "durationMinutes", 250)),
                fact("watched_rank", Map.of("watched", 2314, "rank", 2, "outOf", 10)),
                fact("gift_rank", Map.of("gift", 269, "rank", 2, "outOf", 10)),
                fact("newcomers", Map.of("newcomers", 2045, "newcomerSpoke", 13)),
                fact("fan_club", Map.of("fanClub", 19)), fact("follows", Map.of("follows", 7)),
                fact("returning_newcomers", Map.of("previousSpoke", 20, "returned", 2), List.of(BASE, BASE + 1)),
                fact("top_gifters", Map.of("gift", List.of(9, 8, 7)), List.of(BASE + 2, BASE + 3, BASE + 4)),
                fact("top_words", Map.of("words", List.of("合成热词甲", "合成热词乙", "合成热词丙"))));
    }
    Item newcomer(String strength) {
        return pattern("newcomer_speak_return", strength,
                Map.of("spokeReturn", .4, "silentReturn", .2, "todayRate", .13, "usualRate", .1), 18);
    }
    Item regulars(String strength, int absent) {
        return pattern("regulars_absent", strength, Map.of("regulars", 30, "absent", absent), 20);
    }
    Item slots(String strength, double giftA, double giftB) {
        return pattern("slot_compare", strength, Map.of("slotA", "上午", "slotB", "晚上", "sessionsA", 6,
                "sessionsB", 7, "watchedA", 200, "watchedB", 100, "giftA", giftA, "giftB", giftB), 13);
    }
    Item pk(String strength, double amount) {
        return pattern("pk_share", strength, Map.of("pkGiftPerMin", amount, "normalGiftPerMin", 2, "pkSessions", 6), 16);
    }
    Draft render(Result r, int level, boolean money, Nicknames mode, List<History> history) {
        return SessionRecapRenderer.render(r, new Options(level, money, mode), history);
    }
    String allText(Draft draft) {
        return draft.message() + "\n" + String.join("\n", draft.notes().stream().map(Line::text).toList());
    }
    List<String> selected(Draft d) { return d.history().patterns().stream().map(SpokenPattern::key).toList(); }

    @Test @DisplayName("第一级固定顺序且至多八行，避免短消息挤成报表；每行可查来源")
    void summaryOrderAndSources() {
        var d = render(result(facts(), List.of()), 1, true, Nicknames.ORIGINAL, List.of());
        assertEquals(8, d.lines().size());
        assertEquals(List.of("duration", "watched_rank", "newcomers", "fan_club", "follows",
                "returning_newcomers", "top_gifters", "top_words"), d.lines().stream().map(l -> l.keys().get(0)).toList());
        assertEquals(List.of("watched_rank", "gift_rank"), d.lines().get(1).keys());
        assertTrue(d.message().contains("【10/25 上午场小结】09:01–13:11，播了 4 小时 10 分"));
        assertTrue(d.message().contains("上一场（10/23）"));
        assertTrue(d.message().contains("都是最近 10 场第 2"));
    }

    @Test @DisplayName("空数据和零统计整段省略，避免新关注零等扫兴话与假数据")
    void noEmptyOrZeroStatistics() {
        var zero = List.of(fact("watched_rank", Map.of("watched", 0)), fact("gift_rank", Map.of("gift", 0)),
                fact("newcomers", Map.of("newcomers", 11, "newcomerSpoke", 0)),
                fact("fan_club", Map.of("fanClub", 0)), fact("follows", Map.of("follows", 0)),
                fact("returning_newcomers", Map.of("previousSpoke", 20, "returned", 0)));
        var d = render(result(zero, List.of(regulars("strong", 0), pk("strong", 0))), 2, true, Nicknames.ORIGINAL, List.of());
        assertEquals("· 第一次进来的有 11 人", d.message());
        assertFalse(d.message().contains("0"));
        assertTrue(render(new Result(List.of(), List.of()), 1, true, Nicknames.ORIGINAL, List.of()).lines().isEmpty());
    }

    @ParameterizedTest @CsvSource({"2,2,false", "4,10,false", "3,5,true", "1,4,false", "1,5,true"})
    @DisplayName("只说前三且至少五场才报名次，避免播两场就夸最近十场第二")
    void rankThresholds(int rank, int outOf, boolean shown) {
        var d = render(result(List.of(fact("watched_rank", Map.of("watched", 123, "rank", rank, "outOf", outOf))),
                List.of()), 1, true, Nicknames.ORIGINAL, List.of());
        assertEquals(shown, d.message().contains("最近 " + outOf + " 场第 " + rank));
        assertTrue(d.message().contains("看过 123 人"));
        assertFalse(d.message().contains("倒数"));
    }

    @Test @DisplayName("两个指标名次不同分别说，避免错误地合成同一名次")
    void separateRanks() {
        var d = render(result(List.of(fact("watched_rank", Map.of("watched", 123, "rank", 1, "outOf", 10)),
                fact("gift_rank", Map.of("gift", 7, "rank", 3, "outOf", 8))), List.of()), 1, true, Nicknames.ORIGINAL, List.of());
        assertTrue(d.message().contains("看过 123 人（最近 10 场第 1）"));
        assertTrue(d.message().contains("礼物 ¥7（最近 8 场第 3）"));
        assertFalse(d.message().contains("都是"));
    }

    @ParameterizedTest @CsvSource({"0,凌晨", "5,凌晨", "6,上午", "11,上午", "12,下午", "17,下午", "18,晚上", "23,晚上"})
    @DisplayName("标题按东八区六小时分场，避免服务器时区改变称呼")
    void slotBoundaries(int hour, String slot) {
        long at = ZonedDateTime.parse("2026-10-25T00:01:00+08:00").plusHours(hour).toInstant().toEpochMilli();
        var d = render(result(List.of(fact("duration", Map.of("start", at, "end", at + 45 * 60_000L,
                "durationMinutes", 45))), List.of()), 1, true, Nicknames.ORIGINAL, List.of());
        assertTrue(d.message().contains(slot + "场小结"));
        assertTrue(d.message().contains("播了 45 分钟"));
    }

    @Test @DisplayName("整小时不追加零分钟，结束或时长缺失不编造标题数据")
    void incompleteAndWholeHourDuration() {
        var d = render(result(List.of(fact("duration", Map.of("start", START, "end", START + 3_600_000,
                "durationMinutes", 60))), List.of()), 1, true, Nicknames.ORIGINAL, List.of());
        assertTrue(d.message().endsWith("播了 1 小时"));
        var incomplete = render(result(List.of(fact("duration", Map.of("start", START))), List.of()),
                1, true, Nicknames.ORIGINAL, List.of());
        assertFalse(incomplete.message().contains("播了"));
    }

    @Test @DisplayName("关闭金额隐藏礼物榜与PK并删掉时段金额，避免金额权限泄漏")
    void amountPrivacy() {
        var d = render(result(facts(), List.of(pk("strong", 8), slots("medium", 269, 17))),
                2, false, Nicknames.ORIGINAL, List.of());
        for (String hidden : List.of("礼物", "¥", "元", "PK", "269", "观众丙", "观众丁", "观众戊"))
            assertFalse(d.message().contains(hidden), hidden);
        assertEquals(List.of("slot_compare"), selected(d));
        assertTrue(d.message().contains("看过人数的中位数是 200"));
        assertFalse(d.history().patterns().get(0).values().containsKey("giftA"));
        assertFalse(d.history().patterns().get(0).values().containsKey("giftB"));
    }

    @Test @DisplayName("名单过滤拒绝名单和主播且只列三个名字，避免点名拒绝的观众或长名单刷屏")
    void limitedAndExcludedPeople() {
        var people = List.of(BASE, BASE + 1, BASE + 2, BASE + 3, BASE + 4, HOST, 0L);
        var r = result(List.of(fact("returning_newcomers", Map.of("previousSpoke", 20, "returned", 7), people),
                fact("top_gifters", Map.of(), people)), List.of());
        var d = SessionRecapRenderer.render(r, new Options(1, true, Nicknames.ORIGINAL, Set.of(BASE)), List.of());
        assertFalse(d.message().contains("观众甲"));
        assertFalse(d.message().contains("合成主播"));
        assertFalse(d.message().contains("观众戊"));
        assertTrue(d.message().contains("观众乙、观众丙、观众丁等 4 位"));
        assertTrue(d.message().contains("回来了 4 位"));
    }

    @Test @DisplayName("最多两条先强度再固定顺序，弱样本提醒避免把小样本当定论")
    void selectionAndWeakWarning() {
        var patterns = List.of(pk("strong", 8), slots("weak", 9, 8), regulars("strong", 17), newcomer("medium"));
        var d = render(result(List.of(), patterns), 2, true, Nicknames.ORIGINAL, List.of());
        assertEquals(List.of("regulars_absent", "pk_share"), selected(d));
        assertTrue(d.message().startsWith("从你过去 20 场里看到的"));
        var weak = render(result(List.of(), List.of(slots("weak", 9, 8), newcomer("weak"))), 2, true, Nicknames.ORIGINAL, List.of());
        assertEquals(List.of("newcomer_speak_return", "slot_compare"), selected(weak));
        assertEquals(2, weak.lines().stream().filter(l -> l.text().contains("样本还少，先当参考。")).count());
        assertTrue(weak.message().contains("40.0%"));
        assertEquals(1, weak.notes().size());
        assertTrue(weak.notes().get(0).text().contains("不一定是开口让人回来"));
    }

    @Test @DisplayName("无可用规律整段消失且第一级不选规律，避免空标题")
    void emptyPatternSectionAndLevelOne() {
        var r = result(List.of(), List.of(pk("strong", 8)));
        assertTrue(render(r, 2, false, Nicknames.ORIGINAL, List.of()).lines().isEmpty());
        assertTrue(render(r, 1, true, Nicknames.ORIGINAL, List.of()).lines().isEmpty());
    }

    History history(long session, Item... items) {
        return new History(session, java.util.Arrays.stream(items).map(i -> new SpokenPattern(i.key(), i.values())).toList());
    }
    @Test @DisplayName("最近三次不重复且空小结也计数，避免反复唠叨同一规律")
    void recentHistorySuppression() {
        var item = pk("strong", 8);
        var h = List.of(history(START - 3 * DAY, item), history(START - DAY), history(START - 2 * DAY));
        assertTrue(render(result(List.of(), List.of(item)), 2, true, Nicknames.ORIGINAL, h).lines().isEmpty());
        var old = new ArrayList<>(h);
        old.add(history(START - 4 * DAY, newcomer("strong")));
        assertEquals(List.of("newcomer_speak_return"), selected(render(result(List.of(), List.of(newcomer("strong"))),
                2, true, Nicknames.ORIGINAL, old)));
        assertEquals(List.of("pk_share"), selected(render(result(List.of(), List.of(item)), 2, true, Nicknames.ORIGINAL,
                List.of(history(START + DAY, item), history(START, item)))));
    }

    @ParameterizedTest @CsvSource({"12,false", "12.01,true", "4,false", "3.99,true", "1,true"})
    @DisplayName("主值严格变化过半或两组翻转才能重说，避免小波动也重复提醒")
    void changedMoreThanHalfOrReversed(double value, boolean expected) {
        var d = render(result(List.of(), List.of(pk("strong", value))), 2, true, Nicknames.ORIGINAL,
                List.of(history(START - DAY, pk("strong", 8))));
        assertEquals(expected, !selected(d).isEmpty());
    }

    @Test @DisplayName("组别翻转即使主值变化小仍重说，时段换序不误判翻转")
    void groupReversalAndSlotAlignment() {
        var before = pattern("newcomer_speak_return", "strong",
                Map.of("spokeReturn", .4, "silentReturn", .5, "todayRate", .13, "usualRate", .1), 18);
        assertEquals(List.of("newcomer_speak_return"), selected(render(result(List.of(), List.of(newcomer("strong"))),
                2, true, Nicknames.ORIGINAL, List.of(history(START - DAY, before)))));
        var slot = slots("strong", 9, 8);
        var swapped = pattern("slot_compare", "strong", Map.of("slotA", "晚上", "slotB", "上午", "sessionsA", 7,
                "sessionsB", 6, "watchedA", 100, "watchedB", 200, "giftA", 8, "giftB", 9), 13);
        assertTrue(selected(render(result(List.of(), List.of(slot)), 2, true, Nicknames.ORIGINAL,
                List.of(history(START - DAY, swapped)))).isEmpty());
    }

    @ParameterizedTest @CsvSource({"100,119,true", "100,120,false", "100,125,false", "125,100,false"})
    @DisplayName("礼物差距不到两成使用接近措辞，边界不模糊")
    void similarSlotGifts(double a, double b, boolean similar) {
        var d = render(result(List.of(), List.of(slots("strong", a, b))), 2, true, Nicknames.ORIGINAL, List.of());
        assertEquals(similar, d.message().contains("礼物差不多"));
        assertTrue(d.message().contains("元"));
    }

    @ParameterizedTest @CsvSource({"anonymous,未登录采集", "unknown,不确定", "missing,没有名单"})
    @DisplayName("名单不完整给管理员白话说明且不输出新人，避免名单缺失被当零新人")
    void incompleteListNotes(String quality, String reason) {
        var r = new Result(facts(), List.of(), new Presentation(START, 25, quality, START - DAY, NAMES, Set.of()));
        var d = render(r, 1, true, Nicknames.ORIGINAL, List.of());
        assertFalse(d.message().contains("第一次"));
        assertFalse(d.message().contains("回来了"));
        assertTrue(d.notes().get(0).text().contains("名单不完整（" + reason + "）"));
        assertFalse(d.message().contains("名单不完整"));
    }

    @Test @DisplayName("历史不足十场说明记录不足，避免把安装初期观众都当新人")
    void shortHistoryNote() {
        var r = new Result(facts(), List.of(newcomer("strong")), new Presentation(START, 9, "complete", START - DAY, NAMES, Set.of()));
        var d = render(r, 2, true, Nicknames.ORIGINAL, List.of());
        assertFalse(d.message().contains("新观众"));
        assertFalse(d.message().contains("第一次"));
        assertTrue(d.notes().stream().anyMatch(l -> l.text().equals("记录还不到 10 场，没提新观众。")));
    }

    @Test @DisplayName("所有档位金额与昵称组合无因果措辞，昵称热词不能注入因果句或完整昵称")
    void allCombinationsAndUntrustedText() {
        var injected = new ArrayList<>(facts());
        injected.removeIf(i -> i.key().equals("top_words"));
        injected.add(fact("top_words", Map.of("words", List.of("因为样本", "导致样本", "所以样本", "由于样本", "观众甲"))));
        var candidates = List.of(newcomer("strong"), regulars("medium", 17), slots("weak", 9, 8), pk("strong", 8));
        for (int level : List.of(1, 2)) for (boolean money : List.of(false, true)) for (var mode : Nicknames.values())
                for (Item candidate : candidates) {
            var d = render(result(injected, List.of(candidate)), level, money, mode, List.of());
            var text = allText(d);
            for (String word : List.of("因为", "导致", "所以", "由于")) assertFalse(text.contains(word), word);
            if (mode == Nicknames.MASKED) {
                for (var name : NAMES.values()) assertFalse(text.contains(name), name);
                assertTrue(text.contains("观*"));
            }
        }
    }

    @Test @DisplayName("四条同强度依固定顺序挑选且排除已说过的，避免优先项饿死其他规律")
    void tiesAndSuppressedFallback() {
        var n = newcomer("strong");
        var r = regulars("strong", 17);
        var candidates = List.of(pk("strong", 8), slots("strong", 9, 8), r, n);
        assertEquals(List.of(n.key(), r.key()), selected(render(result(List.of(), candidates), 2, true, Nicknames.ORIGINAL, List.of())));
        assertEquals(List.of("slot_compare", "pk_share"), selected(render(result(List.of(), candidates), 2, true,
                Nicknames.ORIGINAL, List.of(history(START - DAY, n, r)))));
    }

    @Test @DisplayName("过小比例四舍五入为零时不报零比例，避免绕过零值省略规则")
    void roundedZeroRate() {
        var tiny = pattern("newcomer_speak_return", "strong", Map.of("spokeReturn", .00001,
                "silentReturn", .2, "todayRate", .13, "usualRate", .1), 18);
        assertTrue(render(result(List.of(), List.of(tiny)), 2, true, Nicknames.ORIGINAL, List.of()).lines().isEmpty());
    }

    @Test @DisplayName("旧值为零到正值可重说且用最近一次比较，避免陈旧快照触发重复")
    void zeroBaselineAndLatestMention() {
        var current = pk("strong", 8);
        assertEquals(List.of("pk_share"), selected(render(result(List.of(), List.of(current)), 2, true,
                Nicknames.ORIGINAL, List.of(history(START - DAY, pk("strong", 0))))));
        assertTrue(selected(render(result(List.of(), List.of(current)), 2, true, Nicknames.ORIGINAL,
                List.of(history(START - 2 * DAY, pk("strong", 1)), history(START - DAY, current)))).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"因为观众", "样本\n观众", "样本\r观众"})
    @DisplayName("危险昵称不进入正文，避免原名模式绕过模板与行数限制")
    void unsafeNicknames(String name) {
        var r = new Result(List.of(fact("top_gifters", Map.of(), List.of(BASE))), List.of(),
                new Presentation(START, 25, "complete", null, Map.of(BASE, name), Set.of()));
        assertTrue(render(r, 1, true, Nicknames.ORIGINAL, List.of()).lines().isEmpty());
    }

    @Test @DisplayName("草稿与历史不可变且重放一致，避免后续修改污染曾经说过的规律")
    void immutableReplay() {
        var r = result(facts(), List.of(newcomer("strong")));
        var d = render(r, 2, true, Nicknames.ORIGINAL, List.of());
        assertEquals(d, render(r, 2, true, Nicknames.ORIGINAL, List.of()));
        assertThrows(UnsupportedOperationException.class, () -> d.lines().clear());
        assertThrows(UnsupportedOperationException.class, () -> d.history().patterns().get(0).values().clear());
        assertEquals(START, d.history().session());
    }

    @Test @DisplayName("金额沿用报告图的一位小数，避免主播收到浮点长尾")
    void fractionalMoney() {
        var pk = pattern("pk_share", "strong", Map.of("pkGiftPerMin", 50d / 30,
                "normalGiftPerMin", 7d / 30, "pkSessions", 6), 6);
        var d = render(result(List.of(fact("gift_rank", Map.of("gift", .1 + .2))),
                List.of(pk, slots("strong", 12.700000000000001, 8.25))), 2, true, Nicknames.ORIGINAL, List.of());
        assertTrue(d.message().contains("礼物 ¥0.3"));
        assertTrue(d.message().contains("PK 时每分钟礼物 1.7 元，不在 PK 时是 0.2 元"));
        assertTrue(d.message().contains("礼物中位数 12.7 元对 8.3 元"));
        assertFalse(d.message().contains("000000"));
        assertFalse(d.message().contains("666666"));
        var whole = render(result(List.of(fact("gift_rank", Map.of("gift", 269d))), List.of()),
                1, true, Nicknames.ORIGINAL, List.of());
        assertEquals("· 礼物 ¥269", whole.message());
    }

    @ParameterizedTest @CsvSource({"甲,*", "A,*", "🐱,*", "🇨🇳,*", "甲乙,甲*", "Tom,T**",
            "观众甲乙丙丁,观**", "🇨🇳中,🇨🇳*", "👨‍👩‍👧‍👦,*", "é,*"})
    @DisplayName("按字形打码且单字全隐藏，避免截图仍能认出单字或表情昵称")
    void masksGraphemes(String name, String masked) {
        var r = new Result(List.of(fact("top_gifters", Map.of(), List.of(BASE)),
                fact("returning_newcomers", Map.of("previousSpoke", 3, "returned", 1), List.of(BASE)),
                fact("top_words", Map.of("words", List.of(name)))), List.of(),
                new Presentation(START, 25, "complete", null, Map.of(BASE, name), Set.of()));
        var d = render(r, 1, true, Nicknames.MASKED, List.of());
        assertEquals(List.of("· 上一场第一次来就开口的 3 位里，这一场回来了 1 位：" + masked,
                "· 礼物前三：" + masked, "· 弹幕里说得最多的：" + masked),
                d.lines().stream().map(Line::text).toList());
        assertFalse(allText(d).contains(name));
    }

    @Test @DisplayName("单字昵称不打乱普通热词，多个字形的昵称仍打码")
    void maskedWordsKeepSingleGraphemeNicknameSubstrings() {
        var r = new Result(List.of(fact("top_words", Map.of("words", List.of("哈哈哈", "好听", "观众乙来了")))),
                List.of(), new Presentation(START, 25, "complete", null, Map.of(BASE, "哈"), Set.of(),
                Map.of("好", Set.of(BASE + 1), "观众乙", Set.of(BASE + 2))));
        assertEquals("· 弹幕里说得最多的：哈哈哈、好听、观**来了",
                render(r, 1, true, Nicknames.MASKED, List.of()).message());
    }

    @ParameterizedTest @CsvSource({"哈,哈,*,''", "哈,哈哈哈,哈哈哈,哈哈哈", "好,好听,好听,好听",
            "🐱,🐱,*,''", "🐱,🐱来了,🐱来了,🐱来了", "🇨🇳,🇨🇳,*,''", "🇨🇳,🇨🇳加油,🇨🇳加油,🇨🇳加油",
            "👨‍👩‍👧‍👦,👨‍👩‍👧‍👦,*,''", "👨‍👩‍👧‍👦,👨‍👩‍👧‍👦来了,👨‍👩‍👧‍👦来了,👨‍👩‍👧‍👦来了",
            "é,é,*,''", "é,é来了,é来了,é来了", "观众乙,观众乙来了,观**来了,''",
            "🇨🇳中,🇨🇳中来了,🇨🇳*来了,''"})
    @DisplayName("单字形昵称只匹配整条热词，多字形昵称仍匹配其中的部分")
    void wordNicknameMatchingByGrapheme(String name, String word, String masked, String excludedWord) {
        for (boolean detailOnly : List.of(false, true)) {
            var context = new Presentation(START, 25, "complete", null,
                    detailOnly ? Map.of() : Map.of(BASE, name), Set.of(),
                    detailOnly ? Map.of(name, Set.of(BASE)) : Map.of());
            var r = new Result(List.of(fact("top_words", Map.of("words", List.of(word)))), List.of(), context);
            for (var mode : Nicknames.values()) for (boolean excluded : List.of(false, true)) {
                var draft = SessionRecapRenderer.render(r,
                        new Options(1, true, mode, excluded ? Set.of(BASE) : Set.of()), List.of());
                String expected = excluded ? excludedWord : mode == Nicknames.MASKED ? masked : word;
                assertEquals(expected.isEmpty() ? "" : "· 弹幕里说得最多的：" + expected, draft.message(),
                        "name=" + name + ", word=" + word + ", mode=" + mode + ", excluded=" + excluded + ", detailOnly=" + detailOnly);
            }
        }
    }

    @Test @DisplayName("昵称缺失时标明名单未列全，避免回来三位却像只有一位")
    void incompleteNames() {
        var r = new Result(List.of(fact("returning_newcomers", Map.of("previousSpoke", 5, "returned", 3),
                List.of(BASE, BASE + 1, BASE + 2))), List.of(),
                new Presentation(START, 25, "complete", null, Map.of(BASE, "观众甲"), Set.of()));
        assertTrue(render(r, 1, true, Nicknames.ORIGINAL, List.of()).message().contains("回来了 3 位：观众甲等 3 位"));
    }

    @Test @DisplayName("名次写实际参与场数，避免七场记录被说成十场")
    void actualRankWindow() {
        var same = result(List.of(fact("watched_rank", Map.of("watched", 123, "rank", 2, "outOf", 7)),
                fact("gift_rank", Map.of("gift", 8, "rank", 2, "outOf", 7))), List.of());
        assertTrue(render(same, 1, true, Nicknames.ORIGINAL, List.of()).message().contains("都是最近 7 场第 2"));
        assertTrue(render(same, 1, false, Nicknames.ORIGINAL, List.of()).message().contains("最近 7 场第 2"));
        var different = result(List.of(same.facts().get(0), fact("gift_rank", Map.of("gift", 8, "rank", 2, "outOf", 6))), List.of());
        var message = render(different, 1, true, Nicknames.ORIGINAL, List.of()).message();
        assertTrue(message.contains("最近 7 场第 2"));
        assertTrue(message.contains("最近 6 场第 2"));
        assertFalse(message.contains("都是"));
    }

    @Test @DisplayName("换时段组合照说且各组合独立去重，避免新比较被旧时段压住")
    void differentSlotGroups() {
        var current = slots("strong", 9, 8);
        var otherValues = new java.util.HashMap<>(current.values());
        otherValues.put("slotB", "下午");
        var other = pattern("slot_compare", "strong", otherValues, 13);
        assertEquals(List.of("slot_compare"), selected(render(result(List.of(), List.of(current)), 2, true,
                Nicknames.ORIGINAL, List.of(history(START - DAY, other)))));
        assertTrue(selected(render(result(List.of(), List.of(current)), 2, true, Nicknames.ORIGINAL,
                List.of(history(START - DAY, other), history(START - 2 * DAY, current)))).isEmpty());
    }


    @Test @DisplayName("接近的时段金额也收短，舍入为零的金额不展示，避免另一句式漏出长尾或零")
    void similarAndRoundedZeroMoney() {
        var similar = render(result(List.of(), List.of(slots("strong", 12.700000000000001, 12.25))),
                2, true, Nicknames.ORIGINAL, List.of()).message();
        assertTrue(similar.contains("礼物差不多（12.7 元对 12.3 元）"));
        var tiny = render(result(List.of(fact("gift_rank", Map.of("gift", .01))),
                List.of(pk("strong", .01), slots("strong", .01, .02))), 2, true, Nicknames.ORIGINAL, List.of()).message();
        assertFalse(tiny.contains("礼物"));
    }

}
