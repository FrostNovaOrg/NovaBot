package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.AudienceLedger;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.SessionRecap;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.service.LiveSessionArchive.SessionMetrics;
import org.frostnova.nova.core.model.UserScore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 一次扫描计算多个历史场次，结果与输入的开播时刻顺序相同；重复和未入账时刻也保留。 */
    public List<Result> calculateBatch(String platform, long uid, List<Long> starts) throws IOException {
        if (starts.isEmpty()) return List.of();
        var allHistory = ledger.sessions(platform, uid);
        var requested = new HashSet<>(starts);
        int end = allHistory.size();
        while (end > 0 && !requested.contains(allHistory.get(end - 1).startTime())) end--;
        if (end == 0) return starts.stream().map(start -> new Result(List.of(), List.of())).toList();
        var history = allHistory.subList(0, end);
        long through = history.get(end - 1).startTime();
        var archived = new TreeMap<Long, SessionMetrics>();
        for (var session : sessions.findMetrics(platform, uid, through)) archived.put(session.startTime(), session);
        long[] excluded = properties.getExcludedUids().stream().mapToLong(Long::longValue).sorted().toArray();
        var contexts = new LinkedHashMap<Long, Context>();
        var eventCache = new HashMap<Long, PkSummary>();
        var eventReader = new ArchiveProjection();
        for (int i = 0; i < history.size(); i++) {
            long start = history.get(i).startTime();
            if (!requested.contains(start)) continue;
            var context = new Context(new Streamer(platform, uid), start, history.subList(0, i + 1), archived, excluded);
            context.calculate();
            contexts.put(start, context);
        }
        if (!contexts.isEmpty()) {
            ledger.scanAudience(platform, uid, history, new AudienceScan(uid, excluded, contexts));
            for (var context : contexts.values()) {
                context.slots();
                context.pk(eventCache, eventReader);
            }
        }
        var results = new HashMap<Long, Result>();
        contexts.forEach((start, context) -> results.put(start, new Result(context.facts, context.patterns)));
        return starts.stream().map(start -> results.getOrDefault(start, new Result(List.of(), List.of()))).toList();
    }

    public Result calculate(String platform, long uid, long start) throws IOException {
        return calculateBatch(platform, uid, List.of(start)).get(0);
    }

    private final class Context {
        final Streamer streamer;
        final long target;
        final List<AudienceLedger.Session> history, qualified;
        final Map<Long, SessionMetrics> archived;
        final long[] excluded;
        final List<Item> facts = new ArrayList<>(), patterns = new ArrayList<>();
        final List<Evidence> ledgerEvidence = new ArrayList<>();

        Context(Streamer streamer, long target, List<AudienceLedger.Session> history,
                Map<Long, SessionMetrics> archived, long[] excluded) {
            this.streamer = streamer;
            this.target = target;
            this.history = history;
            this.archived = archived;
            this.excluded = excluded;
            qualified = history.stream().filter(SessionRecapService::qualified).toList();
        }

        void calculate() {
            SessionMetrics today = archived.get(target);
            if (today != null && today.endTime() > today.startTime() && today.durationSeconds() > 0)
                fact("duration", Map.of("start", today.startTime(), "end", today.endTime(),
                        "durationMinutes", today.durationSeconds() / 60d), List.of(), List.of(target), "sessions");
            rank("watched_rank", "watched", WATCHED);
            rank("gift_rank", "gift", GIFT);
            details.read(streamer.platform(), streamer.uid(), target).ifPresent(this::detailFacts);
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
            SessionMetrics session = archived.get(start);
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

        boolean allowed(Long uid) { return uid != null && uid > 0 && uid != streamer.uid() && Arrays.binarySearch(excluded, uid) < 0; }

        void audience(AudienceScan scan) {
            for (var session : history) ledgerEvidence.add(ledgerSource(session.startTime()));
            var starts = history.stream().map(AudienceLedger.Session::startTime).toList();
            var today = scan.usable ? scan.today : scan.previous;
            if (qualified(history.get(history.size() - 1))) {
                if (scan.previous != null && scan.previous.known && scan.index >= 10) {
                    var returned = new ArrayList<Long>();
                    for (int i = 0; i < scan.previous.spoken.size; i++) {
                        long viewer = scan.previous.spoken.values[i];
                        if (scan.viewers.last[scan.viewers.slot(viewer)] == scan.q) returned.add(viewer);
                    }
                    returned.sort(Long::compare);
                    fact("returning_newcomers", Map.of("previousSpoke", scan.previous.spoken.size, "returned", returned.size()),
                            returned, starts, "ledger");
                }
                if (today.known)
                    fact("newcomers", Map.of("newcomers", today.size(), "newcomerSpoke", today.spoken.size),
                            List.of(), starts, "ledger");
            }
            if (today != null && today.start == target && today.known && today.size() > 0 && scan.spokeN >= 30 && scan.silentN > 0) {
                var usualWindow = tail(qualified.stream().filter(s -> s.startTime() < target).toList(), 10);
                var usual = usualWindow.stream().map(s -> scan.rates.get(s.startTime())).filter(java.util.Objects::nonNull).toList();
                if (!usual.isEmpty()) {
                    double spokeReturn = scan.spokeReturned / (double) scan.spokeN;
                    double silentReturn = scan.silentReturned / (double) scan.silentN;
                    pattern("newcomer_speak_return", Map.of("spokeReturn", spokeReturn, "silentReturn", silentReturn,
                            "spokeN", scan.spokeN, "silentN", scan.silentN, "todayRate", today.spoken.size / (double) today.size(),
                            "usualRate", median(usual)), strength(scan.spokeN, scan.silentN, 30, spokeReturn, silentReturn),
                            List.of(), starts, ledgerEvidence);
                }
            }
            int regulars = 0, olderCount = Math.max(0, qualified.size() - 5);
            var absent = new ArrayList<Long>();
            for (int i = 0; i < scan.viewers.keys.length; i++) {
                if (scan.viewers.keys[i] == 0 || scan.viewers.older[i] < 3
                        || scan.viewers.older[i] * 5L < olderCount * 2L) continue;
                regulars++;
                if (scan.viewers.last[i] <= olderCount) absent.add(scan.viewers.keys[i]);
            }
            if (qualified.size() >= 5 && regulars >= 10) {
                absent.sort(Comparator.<Long>comparingInt(v -> scan.viewers.older[scan.viewers.slot(v)]).reversed()
                        .thenComparingLong(Long::longValue));
                String strength = regulars < 20 ? "weak" : absent.size() * 2 >= regulars ? "strong" : "medium";
                pattern("regulars_absent", Map.of("regulars", regulars, "absent", absent.size()), strength,
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

        void pk(Map<Long, PkSummary> cache, ArchiveProjection eventReader) throws IOException {
            long pkMillis = 0, normalMillis = 0;
            BigDecimal pkGift = BigDecimal.ZERO, normalGift = BigDecimal.ZERO;
            int pkSessions = 0;
            List<Long> used = new ArrayList<>();
            for (var session : history) {
                long start = session.startTime();
                if (start != target && !qualified(session)) continue;
                PkSummary summary = cache.get(start);
                if (summary == null) {
                    summary = readPk(start, archived.get(start), qualified(session), eventReader);
                    cache.put(start, summary);
                }
                if (start == target && summary.present)
                    fact("follows", Map.of("follows", summary.follows), List.of(), List.of(target), "events:t=follow");
                if (!summary.usable) continue;
                if (summary.pkMillis > 0) pkSessions++;
                pkMillis += summary.pkMillis;
                normalMillis += summary.normalMillis;
                pkGift = pkGift.add(summary.pkGift); normalGift = normalGift.add(summary.normalGift);
                used.add(start);
            }
            if (pkSessions < 3 || pkMillis == 0 || normalMillis == 0) return;
            double pkRate = pkGift.doubleValue() * 60_000 / pkMillis;
            double normalRate = normalGift.doubleValue() * 60_000 / normalMillis;
            pattern("pk_share", Map.of("pkSessions", pkSessions, "pkGiftPerMin", pkRate, "normalGiftPerMin", normalRate),
                    strength(pkSessions, pkSessions, 3, pkRate, normalRate), List.of(), used, sources(used, "events:pk_open,pk_settle,gift,box,guard"));
        }

        PkSummary readPk(long start, SessionMetrics archivedSession, boolean qualified, ArchiveProjection eventReader) throws IOException {
            var events = new EventTotals(start, archivedSession == null ? start : archivedSession.endTime());
            boolean present = details.scanEvents(streamer.platform(), streamer.uid(), start, eventReader, events::accept);
            if (!qualified || !present || !events.open.isEmpty()
                    || archivedSession == null || archivedSession.endTime() <= start)
                return new PkSummary(present, events.follows, false, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
            var intervals = events.intervals();
            final BigDecimal[] gifts = {BigDecimal.ZERO, BigDecimal.ZERO};
            details.scanEvents(streamer.platform(), streamer.uid(), start, eventReader, e -> {
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
            return new PkSummary(present, events.follows, true, duration,
                    archivedSession.endTime() - start - duration, gifts[0], gifts[1]);
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
    private record PkSummary(boolean present, long follows, boolean usable, long pkMillis,
                             long normalMillis, BigDecimal pkGift, BigDecimal normalGift) {}

    /** 一个观众只占一个 long 和两个 int；零键表示空槽。 */
    private static final class Viewers {
        long[] keys = new long[1024];
        int[] older = new int[1024], last = new int[1024];
        int size;
        int slot(long key) {
            long hash = key;
            hash = (hash ^ (hash >>> 33)) * 0xff51afd7ed558ccdL;
            hash = (hash ^ (hash >>> 33)) * 0xc4ceb9fe1a85ec53L;
            int slot = (int) (hash ^ (hash >>> 33)) & (keys.length - 1);
            while (keys[slot] != 0 && keys[slot] != key) slot = (slot + 1) & (keys.length - 1);
            return slot;
        }
        int ensure(long key) {
            if ((size + 1) * 10L >= keys.length * 7L) grow();
            int slot = slot(key);
            if (keys[slot] == 0) { keys[slot] = key; size++; }
            return slot;
        }
        void grow() {
            long[] oldKeys = keys;
            int[] oldOlder = older, oldLast = last;
            keys = new long[oldKeys.length * 2];
            older = new int[keys.length]; last = new int[keys.length];
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] == 0) continue;
                int slot = slot(oldKeys[i]);
                keys[slot] = oldKeys[i]; older[slot] = oldOlder[i]; last[slot] = oldLast[i];
            }
        }
    }
    private static final class LongBuffer {
        long[] values = new long[64];
        int size;
        void add(long value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }
    }
    private static final class Cohort {
        long start;
        int q;
        boolean known;
        final LongBuffer spoken = new LongBuffer(), silent = new LongBuffer();
        int size() { return spoken.size + silent.size; }
        long returned(LongBuffer group, Viewers viewers) {
            long count = 0;
            for (int i = 0; i < group.size; i++) if (viewers.last[viewers.slot(group.values[i])] > q) count++;
            return count;
        }
    }
    /** 新人批次在第三个后续合格场次成熟，届时最后出现序号即可判定是否回来。 */
    private final class AudienceScan implements AudienceLedgerService.AudienceVisitor {
        final long host;
        final long[] excluded;
        final Map<Long, Context> contexts;
        final Viewers viewers = new Viewers();
        final ArrayDeque<LongBuffer> recent = new ArrayDeque<>();
        final ArrayDeque<Cohort> pending = new ArrayDeque<>();
        final Map<Long, Double> rates = new HashMap<>();
        LongBuffer present = new LongBuffer();
        Cohort today = new Cohort(), previous;
        long spokeN, silentN, spokeReturned, silentReturned;
        int index = -1, q;
        AudienceLedger.Session session;
        boolean usable;

        AudienceScan(long host, long[] excluded, Map<Long, Context> contexts) {
            this.host = host; this.excluded = excluded; this.contexts = contexts;
        }
        public void start(AudienceLedger.Session session) {
            this.session = session;
            index++;
            usable = qualified(session);
            if (!usable) return;
            q++;
            today.start = session.startTime(); today.q = q; today.known = index >= 10;
            if (recent.size() == 5) {
                present = recent.removeFirst();
                for (int i = 0; i < present.size; i++) viewers.older[viewers.slot(present.values[i])]++;
                present.size = 0;
            }
        }
        public void viewer(long uid, boolean streamer, boolean spoke) {
            if (streamer || uid == host || Arrays.binarySearch(excluded, uid) >= 0) return;
            int slot = viewers.slot(uid);
            boolean newcomer = viewers.keys[slot] == 0;
            if (newcomer) slot = viewers.ensure(uid);
            if (!usable) return;
            present.add(uid);
            viewers.last[slot] = q;
            if (newcomer && today.known) (spoke ? today.spoken : today.silent).add(uid);
        }
        public void end() {
            Cohort reusable = null;
            if (usable) {
                if (pending.size() == 3) {
                    reusable = pending.removeFirst();
                    if (reusable.known) {
                        spokeN += reusable.spoken.size; silentN += reusable.silent.size;
                        spokeReturned += reusable.returned(reusable.spoken, viewers);
                        silentReturned += reusable.returned(reusable.silent, viewers);
                    }
                }
                if (today.known && today.size() > 0) rates.put(today.start, today.spoken.size / (double) today.size());
            }
            Context context = contexts.get(session.startTime());
            if (context != null) context.audience(this);
            if (!usable) return;
            previous = today;
            pending.addLast(today);
            today = reusable == null ? new Cohort() : reusable;
            today.spoken.size = 0; today.silent.size = 0;
            recent.addLast(present);
            if (recent.size() < 5) present = new LongBuffer();
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
