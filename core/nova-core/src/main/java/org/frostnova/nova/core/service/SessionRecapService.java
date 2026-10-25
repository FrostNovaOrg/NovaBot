package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.AudienceLedger;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.SessionRecap;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.UserScore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.frostnova.nova.core.analytics.SessionRecap.*;

/** 只读计算封存场次的事实与规律；调用方负责提前更新观众账本。 */
@Service
public class SessionRecapService {
    // 主播页直接展示的归档指标；礼物价值不含其他种类的流水。
    private static final String WATCHED = "watched_count", GIFT = "gift_value";
    private static final List<String> SLOTS = List.of("凌晨", "上午", "下午", "晚上");
    private final AudienceLedgerService ledger;
    private final LiveSessionArchive sessions;
    private final LiveDetailArchive details;
    private final AssistantProperties properties;

    public SessionRecapService(AudienceLedgerService ledger, LiveSessionArchive sessions,
                               LiveDetailArchive details, AssistantProperties properties) {
        this.ledger = ledger;
        this.sessions = sessions;
        this.details = details;
        this.properties = properties;
    }

    public Result calculate(String platform, long uid, long start) throws IOException {
        var history = ledger.sessions(platform, uid).stream().filter(s -> s.startTime() <= start).toList();
        if (history.isEmpty() || history.get(history.size() - 1).startTime() != start)
            return new Result(List.of(), List.of());
        var archived = new TreeMap<Long, LiveSession>();
        for (var session : sessions.find(0, start == Long.MAX_VALUE ? start : start + 1)) {
            if (platform.equals(session.platform()) && Long.valueOf(uid).equals(session.uid()) && session.startTime() <= start)
                archived.put(session.startTime(), session);
        }
        var context = new Context(new Streamer(platform, uid), start, history, archived,
                new HashSet<>(properties.getExcludedUids()));
        context.calculate();
        return new Result(context.facts, context.patterns);
    }

    private final class Context {
        final Streamer streamer;
        final long target;
        final List<AudienceLedger.Session> history, qualified;
        final Map<Long, LiveSession> archived;
        final Set<Long> excluded;
        final List<Item> facts = new ArrayList<>(), patterns = new ArrayList<>();
        final List<Evidence> ledgerEvidence = new ArrayList<>();

        Context(Streamer streamer, long target, List<AudienceLedger.Session> history,
                Map<Long, LiveSession> archived, Set<Long> excluded) {
            this.streamer = streamer;
            this.target = target;
            this.history = history;
            this.archived = archived;
            this.excluded = excluded;
            qualified = history.stream().filter(SessionRecapService::qualified).toList();
        }

        void calculate() throws IOException {
            LiveSession today = archived.get(target);
            if (today != null && today.endTime() > today.startTime() && today.durationSeconds() > 0)
                fact("duration", Map.of("start", today.startTime(), "end", today.endTime(),
                        "durationMinutes", today.durationSeconds() / 60d), List.of(), List.of(target), "sessions");
            rank("watched_rank", "watched", WATCHED);
            rank("gift_rank", "gift", GIFT);
            details.read(streamer.platform(), streamer.uid(), target).ifPresent(this::detailFacts);
            audience();
            slots();
            pk();
        }

        void rank(String key, String valueKey, String metric) {
            Double value = metric(target, metric);
            if (value == null) return;
            var window = tail(qualified, 10).stream().map(AudienceLedger.Session::startTime).toList();
            var known = window.stream().filter(s -> metric(s, metric) != null).toList();
            if (known.isEmpty()) return;
            int rank = 1 + (int) known.stream().filter(s -> metric(s, metric) > value).count();
            fact(key, Map.of(valueKey, value, "rank", rank, "outOf", known.size()), List.of(), known, "sessions");
        }

        Double metric(long start, String key) {
            LiveSession session = archived.get(start);
            if (session == null || session.metrics() == null) return null;
            Double value = session.metrics().get(key);
            return value != null && Double.isFinite(value) && value >= 0 ? value : null;
        }

        void detailFacts(LiveDetail detail) {
            if (detail.gifts() != null) {
                var badges = detail.gifts().stream().filter(g -> "粉丝团灯牌".equals(g.name())).toList();
                if (!badges.isEmpty()) fact("fan_club", Map.of("fanClub", badges.stream().mapToLong(g -> g.count()).sum()),
                        List.of(), List.of(target), "detail:gifts[name=粉丝团灯牌].count");
            }
            var gifters = detail.ranking("gift_users").stream()
                    .filter(u -> allowed(u.userUid()) && Double.isFinite(u.score()))
                    .sorted(Comparator.comparingDouble(UserScore::score).reversed().thenComparing(UserScore::userUid))
                    .limit(3).toList();
            if (!gifters.isEmpty()) fact("top_gifters", Map.of("gift", gifters.stream().map(UserScore::score).toList()),
                    gifters.stream().map(UserScore::userUid).toList(), List.of(target), "detail:rankings.gift_users");
            if (detail.words() != null && !detail.words().isEmpty()) {
                var words = detail.words().entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                        .limit(3).map(Map.Entry::getKey).toList();
                fact("top_words", Map.of("words", words), List.of(), List.of(target), "detail:words");
            }
        }

        boolean allowed(Long uid) { return uid != null && uid > 0 && uid != streamer.uid() && !excluded.contains(uid); }

        void audience() throws IOException {
            // 每位观众仅保留累计计数；最多保留三个尚未成熟的新人批次。
            Map<Long, Viewer> viewers = new HashMap<>();
            var pending = new ArrayDeque<Cohort>();
            var rates = new LinkedHashMap<Long, Double>();
            long spokeN = 0, silentN = 0, spokeReturned = 0, silentReturned = 0;
            Cohort previous = null, today = null;
            int q = 0;
            int olderCount = Math.max(0, qualified.size() - 5);
            for (int index = 0; index < history.size(); index++) {
                var session = history.get(index);
                long start = session.startTime();
                boolean usable = qualified(session);
                var rows = ledger.sessionRows(streamer.platform(), streamer.uid(), start);
                Set<Long> present = new HashSet<>(), spokenNew = new HashSet<>(), silentNew = new HashSet<>();
                for (var row : rows) {
                    if (row.streamer() || !allowed(row.viewerUid())) continue;
                    long viewer = row.viewerUid();
                    if (usable) {
                        present.add(viewer);
                        if (!viewers.containsKey(viewer) && index >= 10)
                            (row.spoke() ? spokenNew : silentNew).add(viewer);
                        Viewer state = viewers.computeIfAbsent(viewer, ignored -> new Viewer());
                        if (q < olderCount) state.older++;
                        else state.recent = true;
                    } else viewers.computeIfAbsent(viewer, ignored -> new Viewer());
                }
                ledgerEvidence.add(ledgerSource(start));
                if (!usable) continue;
                for (Cohort cohort : pending) {
                    cohort.observe(present);
                }
                if (pending.size() == 3) {
                    Cohort mature = pending.removeFirst();
                    if (mature.known) {
                        spokeN += mature.spoken.size(); silentN += mature.silent.size();
                        spokeReturned += mature.spokeReturned.size(); silentReturned += mature.silentReturned.size();
                    }
                }
                today = new Cohort(start, spokenNew, silentNew, index >= 10);
                if (today.known && today.size() > 0) rates.put(start, today.spoken.size() / (double) today.size());
                if (start == target && previous != null && previous.known && index >= 10) {
                    var returned = previous.spoken.stream().filter(present::contains).sorted().toList();
                    fact("returning_newcomers", Map.of("previousSpoke", previous.spoken.size(), "returned", returned.size()),
                            returned, history.stream().map(AudienceLedger.Session::startTime).toList(), "ledger");
                }
                if (start == target && today.known)
                    fact("newcomers", Map.of("newcomers", today.size(), "newcomerSpoke", today.spoken.size()),
                            List.of(), history.stream().map(AudienceLedger.Session::startTime).toList(), "ledger");
                pending.addLast(today);
                previous = today;
                q++;
            }
            if (today != null && today.start == target && today.known && today.size() > 0 && spokeN >= 30 && silentN > 0) {
                var usualWindow = tail(qualified.stream().filter(s -> s.startTime() < target).toList(), 10);
                var usual = usualWindow.stream().map(s -> rates.get(s.startTime())).filter(java.util.Objects::nonNull).toList();
                if (!usual.isEmpty()) {
                    double spokeReturn = spokeReturned / (double) spokeN, silentReturn = silentReturned / (double) silentN;
                    pattern("newcomer_speak_return", Map.of("spokeReturn", spokeReturn, "silentReturn", silentReturn,
                            "spokeN", spokeN, "silentN", silentN, "todayRate", today.spoken.size() / (double) today.size(),
                            "usualRate", median(usual)), strength(spokeN, silentN, 30, spokeReturn, silentReturn),
                            List.of(), history.stream().map(AudienceLedger.Session::startTime).toList(), ledgerEvidence);
                }
            }
            var regulars = viewers.entrySet().stream().filter(e -> e.getValue().older >= 3
                    && e.getValue().older * 5L >= olderCount * 2L).toList();
            if (qualified.size() >= 5 && regulars.size() >= 10) {
                var absent = regulars.stream().filter(e -> !e.getValue().recent)
                        .sorted(Comparator.<Map.Entry<Long, Viewer>>comparingInt(e -> e.getValue().older).reversed()
                                .thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).toList();
                String strength = regulars.size() < 20 ? "weak" : absent.size() * 2 >= regulars.size() ? "strong" : "medium";
                pattern("regulars_absent", Map.of("regulars", regulars.size(), "absent", absent.size()), strength,
                        absent, qualified.stream().map(AudienceLedger.Session::startTime).toList(), ledgerEvidence);
            }
        }

        void slots() {
            Map<Integer, List<Long>> groups = new TreeMap<>();
            for (var session : qualified) {
                int slot = Instant.ofEpochMilli(session.startTime()).atZone(StatisticsDay.ZONE).getHour() / 6;
                groups.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(session.startTime());
            }
            var largest = groups.entrySet().stream().sorted(Comparator
                    .<Map.Entry<Integer, List<Long>>>comparingInt(e -> e.getValue().size()).reversed()
                    .thenComparing(Map.Entry::getKey)).limit(2).toList();
            if (largest.size() < 2 || largest.stream().anyMatch(e -> e.getValue().size() < 3)) return;
            var a = largest.get(0); var b = largest.get(1);
            var watchedA = metrics(a.getValue(), WATCHED); var watchedB = metrics(b.getValue(), WATCHED);
            var giftA = metrics(a.getValue(), GIFT); var giftB = metrics(b.getValue(), GIFT);
            // 缺指标不当零；两组的每一场都须有用于比较的数值。
            if (watchedA.size() != a.getValue().size() || watchedB.size() != b.getValue().size()
                    || giftA.size() != a.getValue().size() || giftB.size() != b.getValue().size()) return;
            var used = new ArrayList<>(a.getValue()); used.addAll(b.getValue()); used.sort(Long::compare);
            pattern("slot_compare", Map.of("slotA", SLOTS.get(a.getKey()), "slotB", SLOTS.get(b.getKey()),
                    "sessionsA", a.getValue().size(), "sessionsB", b.getValue().size(),
                    "watchedA", median(watchedA), "watchedB", median(watchedB), "giftA", median(giftA), "giftB", median(giftB)),
                    strength(a.getValue().size(), b.getValue().size(), 3, median(watchedA), median(watchedB)),
                    List.of(), used, sources(used, "sessions"));
        }

        List<Double> metrics(List<Long> starts, String key) {
            return starts.stream().map(s -> metric(s, key)).filter(java.util.Objects::nonNull).toList();
        }

        void pk() throws IOException {
            long pkMillis = 0, normalMillis = 0;
            BigDecimal pkGift = BigDecimal.ZERO, normalGift = BigDecimal.ZERO;
            int pkSessions = 0;
            List<Long> used = new ArrayList<>();
            for (var session : history) {
                long start = session.startTime();
                LiveSession archivedSession = archived.get(start);
                if (start != target && !qualified(session)) continue;
                var events = new EventTotals(start, archivedSession == null ? start : archivedSession.endTime());
                boolean present = details.scanEvents(streamer.platform(), streamer.uid(), start, events::accept);
                if (start == target && present)
                    fact("follows", Map.of("follows", events.follows), List.of(), List.of(target), "events:t=follow");
                if (!qualified(session) || !present || !events.open.isEmpty()
                        || archivedSession == null || archivedSession.endTime() <= start) continue;
                // 第二遍不积攒礼物流水，按合并后的 PK 区间归类每一笔金额。
                var intervals = events.intervals();
                final BigDecimal[] gifts = {BigDecimal.ZERO, BigDecimal.ZERO};
                details.scanEvents(streamer.platform(), streamer.uid(), start, e -> {
                    String type = e.getString("t");
                    if (type == null || !List.of("gift", "box", "guard").contains(type)
                            || e.get("at") == null || e.get("val") == null) return;
                    long at = e.getLongValue("at");
                    if (at < start || at >= archivedSession.endTime()) return;
                    BigDecimal value = e.getBigDecimal("val");
                    int group = intervals.stream().anyMatch(i -> at >= i[0] && at < i[1]) ? 0 : 1;
                    gifts[group] = gifts[group].add(value);
                });
                long duration = intervals.stream().mapToLong(i -> i[1] - i[0]).sum();
                if (duration > 0) pkSessions++;
                pkMillis += duration;
                normalMillis += archivedSession.endTime() - start - duration;
                pkGift = pkGift.add(gifts[0]); normalGift = normalGift.add(gifts[1]);
                used.add(start);
            }
            if (pkSessions < 3 || pkMillis == 0 || normalMillis == 0) return;
            double pkRate = pkGift.doubleValue() * 60_000 / pkMillis;
            double normalRate = normalGift.doubleValue() * 60_000 / normalMillis;
            pattern("pk_share", Map.of("pkSessions", pkSessions, "pkGiftPerMin", pkRate, "normalGiftPerMin", normalRate),
                    strength(pkSessions, pkSessions, 3, pkRate, normalRate), List.of(), used, sources(used, "events:pk_open,pk_settle,gift,box,guard"));
        }

        Evidence ledgerSource(long start) {
            return new Evidence("audience-ledger/" + streamer.platform() + "-" + streamer.uid() + "/" + start + ".jsonl", "session,viewerUid,streamer,spoke");
        }
        List<Evidence> sources(List<Long> starts, String source) {
            if (source.equals("ledger")) return List.copyOf(ledgerEvidence);
            return starts.stream().map(s -> {
                if (source.equals("sessions")) return new Evidence("sessions.jsonl", streamer.platform() + ":" + streamer.uid() + ":" + s);
                String[] parts = source.split(":", 2);
                return new Evidence("details/" + streamer.platform() + "-" + streamer.uid() + "-" + s + "/"
                        + (parts[0].equals("events") ? "events.jsonl" : "detail.json"), parts[1]);
            }).toList();
        }
        Sample sample(List<Long> starts) { return new Sample(starts.size(), starts.stream().mapToLong(Long::longValue).min().orElse(target)); }
        void fact(String key, Map<String, Object> values, List<Long> people, List<Long> starts, String source) {
            facts.add(new Item(key, streamer, target, values, sample(starts), null, people, sources(starts, source)));
        }
        void pattern(String key, Map<String, Object> values, String strength, List<Long> people, List<Long> starts, List<Evidence> evidence) {
            patterns.add(new Item(key, streamer, target, values, sample(starts), strength, people, evidence));
        }
    }

    private static boolean qualified(AudienceLedger.Session session) {
        return session.qualified() && "complete".equals(session.listQuality());
    }
    private static <T> List<T> tail(List<T> list, int size) { return list.subList(Math.max(0, list.size() - size), list.size()); }
    private static double median(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
    static String strength(long aN, long bN, int threshold, double a, double b) {
        if (aN < 2L * threshold || bN < 2L * threshold) return "weak";
        double small = Math.min(a, b), big = Math.max(a, b);
        return big > 0 && big >= 2 * small ? "strong" : "medium";
    }
    private static final class Viewer { int older; boolean recent; }
    private static final class Cohort {
        final long start;
        final Set<Long> spoken, silent, spokeReturned = new HashSet<>(), silentReturned = new HashSet<>();
        final boolean known;
        Cohort(long start, Set<Long> spoken, Set<Long> silent, boolean known) {
            this.start = start; this.spoken = spoken; this.silent = silent; this.known = known;
        }
        int size() { return spoken.size() + silent.size(); }
        void observe(Set<Long> present) {
            spoken.stream().filter(present::contains).forEach(spokeReturned::add);
            silent.stream().filter(present::contains).forEach(silentReturned::add);
        }
    }
    private static final class EventTotals {
        final long start, end;
        long follows;
        final Map<String, Long> open = new HashMap<>();
        final List<long[]> spans = new ArrayList<>();
        EventTotals(long start, long end) { this.start = start; this.end = end; }
        void accept(JSONObject event) {
            String type = event.getString("t");
            if ("follow".equals(type)) follows++;
            if (event.get("at") == null) return;
            long at = event.getLongValue("at");
            String id = event.getString("pk");
            if ("pk_open".equals(type)) open.putIfAbsent(id, at);
            if ("pk_settle".equals(type)) {
                Long from = open.remove(id);
                if (from != null && Math.max(start, from) < Math.min(end, at))
                    spans.add(new long[]{Math.max(start, from), Math.min(end, at)});
            }
        }
        List<long[]> intervals() {
            spans.sort(Comparator.comparingLong(s -> s[0]));
            List<long[]> merged = new ArrayList<>();
            for (var span : spans) {
                if (merged.isEmpty() || span[0] > merged.get(merged.size() - 1)[1]) merged.add(span.clone());
                else merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], span[1]);
            }
            return merged;
        }
    }
}
