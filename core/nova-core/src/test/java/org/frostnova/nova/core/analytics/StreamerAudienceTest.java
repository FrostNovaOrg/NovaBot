package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("主播本期观众名单")
class StreamerAudienceTest {
    static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    static final Instant TO = FROM.plusSeconds(86400);
    static final LiveMetricCatalog.Insights RULES = new LiveMetricCatalog.Insights(
            List.of("gift", "sc", "guard"), "chat", List.of("enter", "chat"), List.of("chat"), "fans");
    final Map<Long, LiveDetail> details = new HashMap<>();
    LiveSession session(int offset, List<Long> users, List<Long> chat, UserScore... scores) {
        long at = FROM.plusSeconds(offset).toEpochMilli();
        var session = new LiveSession("sample", 1L, "sample", 2L, at, at + 60000, 60,
                Map.of(), Map.of(), LiveEndReason.NORMAL,
                List.of(new RoomInfoSnapshot(at, "title", "")), 0,
                Map.of("enter", users, "chat", chat), 0, Map.of(), Map.of());
        details.put(at, new LiveDetail(1, "sample", 1L, "sample", 2L, at, at+60000, 60,
                Map.of(), Map.of(), Map.of(), Map.of("revenue", List.of(scores)), Map.of(),
                List.of(), List.of(), List.of(), Map.of(), List.of()));
        return session;
    }
    StreamerAudience.Page page(List<LiveSession> sessions, String group, String sort, int page) {
        return new StreamerAudience().calculate(sessions, FROM, TO, RULES, "revenue",
                s -> Optional.ofNullable(details.get(s.startTime())), group, sort, page);
    }
    @Test @DisplayName("首次回访及名单情况与概况相同，进房不算互动")
    void compositionMatchesOverview() {
        var sessions = List.of(session(-60, List.of(1L), List.of()),
                session(0, List.of(1L, 2L), List.of(2L)));
        var overview = new StreamerInsights().calculate(sessions, List.of(), FROM, TO, RULES);
        var p = page(sessions, "all", "attendance", 1);
        assertEquals(overview.composition().firstRecorded(), p.counts().first(), "首次人数与概况一致");
        assertEquals(overview.composition().returning(), p.counts().returning(), "回访人数与概况一致");
        assertEquals(overview.audience(), p.counts().all());
        assertEquals(overview.interactionAudience(), p.counts().interaction());
        assertEquals(overview.coverage(), p.coverage());
        assertEquals(1, page(sessions, "first", "attendance", 1).total());
        assertEquals(FROM.minusSeconds(60).toEpochMilli(), page(sessions, "returning", "attendance", 1).items().get(0).firstSeen());
        assertTrue(page(sessions, "interaction", "attendance", 1).items().get(0).interaction());
        assertFalse(p.items().stream().filter(p1 -> !p1.interaction()).findFirst().isEmpty());
        var noHistory = page(List.of(sessions.get(1)), "first", "attendance", 1);
        assertNull(noHistory.counts().first(), "没有历史名单不能全算首次");
        assertTrue(noHistory.items().isEmpty());
    }
    @Test @DisplayName("改昵称按同一观众合并且取最近场昵称与本期场次")
    void latestNameAndVisits() {
        var sessions = List.of(session(60, List.of(1L), List.of(), new UserScore(1L, "new", 0)),
                session(-60, List.of(1L), List.of(), new UserScore(1L, "before", 0)),
                session(0, List.of(1L), List.of(), new UserScore(1L, "old", 0)));
        var p = page(sessions, "all", "attendance", 1);
        assertEquals(1, p.total(), "改名不能变成两个人");
        var person = p.items().get(0);
        assertEquals("new", person.name(), "昵称取最近一场");
        assertEquals(2, person.attendance());
        assertEquals(FROM.minusSeconds(60).toEpochMilli(), person.firstSeen());
        assertEquals(List.of(FROM.plusSeconds(60).toEpochMilli(), FROM.toEpochMilli()),
                person.visits().stream().map(StreamerAudience.Visit::startTime).toList());
        assertEquals("title", person.visits().get(0).title());
    }
    @Test @DisplayName("默认依次按到场流水最近排序，三列可切换排序")
    void ordering() {
        var sessions = List.of(session(0, List.of(1L, 2L, 3L), List.of(),
                new UserScore(1L, "a", 1), new UserScore(2L, "b", 20), new UserScore(3L, "c", 20)),
                session(60, List.of(1L, 4L), List.of(), new UserScore(4L, "d", 100)),
                session(120, List.of(5L), List.of(), new UserScore(5L, "e", 20)));
        assertEquals(List.of("未记录昵称", "d", "e", "b", "c"),
                page(sessions, "all", "attendance", 1).items().stream().map(StreamerAudience.Person::name).toList(), "到场、流水、最近依次降序");
        assertEquals("d", page(sessions, "all", "revenue", 1).items().get(0).name());
        assertEquals("e", page(sessions, "all", "recent", 1).items().get(0).name());
    }
    @Test @DisplayName("四十五人分页不重不漏，越界回最后一页")
    void pagination() {
        var ids = java.util.stream.LongStream.rangeClosed(1, 45).boxed().toList();
        var sessions = List.of(session(0, ids, List.of()));
        for (int n=1; n<=3; n++) {
            var p = page(sessions, "all", "attendance", n);
            assertEquals(n < 3 ? 20 : 5, p.items().size(), "每页二十人，末页五人");
            assertEquals(3, p.pages());
        }
        assertEquals(page(sessions, "all", "attendance", 3), page(sessions, "all", "attendance", 999));
        // 使用不同昵称区分输出，避免依赖观众标识出现在响应里。
        var distinctSessions = new ArrayList<LiveSession>();
        for (int n=1; n<=45; n++) distinctSessions.add(session(n, List.of((long)n), List.of(), new UserScore((long)n, "name"+n, n)));
        Set<String> names = new HashSet<>();
        for (int n=1; n<=3; n++) for (var p : page(distinctSessions, "all", "attendance", n).items()) assertTrue(names.add(p.name()), "分页不能重复");
        assertEquals(45, names.size(), "分页不能丢人");
    }
    @Test @DisplayName("没有昵称显示未记录昵称且不沿用前场昵称")
    void missingName() {
        var sessions = List.of(session(0, List.of(1L), List.of(), new UserScore(1L, "old", 0)),
                session(60, List.of(1L), List.of(), new UserScore(1L, " ", 0)));
        assertEquals("未记录昵称", page(sessions, "all", "attendance", 1).items().get(0).name());
    }
    @Test @DisplayName("两场流水相加，缺明细或分人金额时不当零")
    void revenueAndMissing() {
        var a = session(0, List.of(1L, 2L), List.of(), new UserScore(1L, "a", 10.5));
        var b = session(60, List.of(1L), List.of(), new UserScore(1L, "a", 20));
        assertEquals(30.5, page(List.of(a,b), "all", "attendance", 1).items().get(0).revenue(), "两场流水应为30.50元");
        assertEquals(0, page(List.of(a), "all", "revenue", 1).items().get(1).revenue());
        var old = details.get(b.startTime());
        details.put(b.startTime(), new LiveDetail(1, "sample", 1L, "sample", 2L, b.startTime(), b.endTime(), 60,
                Map.of(), Map.of(), Map.of(), Map.of("gift", List.of(new UserScore(1L,"a",20))), Map.of(),
                List.of(), List.of(), List.of(), Map.of(), List.of()));
        assertNull(page(List.of(a,b), "all", "attendance", 1).items().get(0).revenue(), "旧分表不能补齐大航海金额");
        details.remove(b.startTime());
        assertNull(page(List.of(a,b), "all", "attendance", 1).items().get(0).revenue(), "缺明细不能当零");
        details.put(b.startTime(), old);
    }
}
