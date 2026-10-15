package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** 指定范围的观众分页；输出不包含观众标识。 */
public final class StreamerAudience {
    private static final int PAGE_SIZE = 20;

    public record Visit(long startTime, String title) {}
    public record Person(String name, int attendance, long firstSeen, long lastSeen,
                         Double revenue, boolean interaction, List<Visit> visits) {}
    public record Counts(Integer all, Integer first, Integer returning, Integer interaction) {}
    public record Page(long from, long to, Counts counts, StreamerInsights.Coverage coverage,
                       String group, String sort, int page, int pages, int total, List<Person> items) {}
    public Page calculate(Collection<LiveSession> sessions, Instant from, Instant to,
                          LiveMetricCatalog.Insights rules, String revenueRanking,
                          Function<LiveSession, Optional<LiveDetail>> details,
                          String group, String sort, int page) {
        var overview = new StreamerInsights().calculate(sessions, List.of(), from, to, rules);
        var composition = overview.composition();
        var counts = new Counts(overview.audience(), composition == null ? null : composition.firstRecorded(),
                composition == null ? null : composition.returning(), overview.interactionAudience());
        Map<Long, Long> first = new HashMap<>();
        Map<Long, Entry> people = new HashMap<>();
        if (rules != null) {
            for (var session : sessions.stream().sorted(Comparator.comparingLong(LiveSession::startTime)).toList()) {
                if (session.startTime() >= to.toEpochMilli()) continue;
                Set<Long> audience = users(session, rules.audienceSets());
                for (long uid : audience) first.putIfAbsent(uid, session.startTime());
                if (session.startTime() < from.toEpochMilli()) continue;
                var detail = details.apply(session).orElse(null);
                Map<Long, String> names = new HashMap<>();
                if (detail != null && detail.rankings() != null) {
                    detail.rankings().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(ranking -> {
                        for (var score : ranking.getValue()) {
                            if (score.userName() != null && !score.userName().isBlank()) {
                                names.putIfAbsent(score.userUid(), score.userName());
                            }
                        }
                    });
                }
                boolean moneyKnown = revenueRanking != null && detail != null && detail.rankings() != null
                        && detail.rankings().containsKey(revenueRanking);
                Map<Long, Double> money = new HashMap<>();
                if (moneyKnown) for (var score : detail.ranking(revenueRanking)) money.put(score.userUid(), score.score());
                Set<Long> interacting = users(session, rules.interactionSets());
                var titles = session.titles();
                String title = titles == null || titles.isEmpty() ? "" : titles.get(titles.size() - 1).title();
                for (long uid : audience) {
                    Entry entry = people.computeIfAbsent(uid, ignored -> new Entry());
                    entry.name = names.getOrDefault(uid, "未记录昵称");
                    entry.last = session.startTime();
                    entry.interaction |= interacting.contains(uid);
                    entry.known &= moneyKnown;
                    entry.revenue += money.getOrDefault(uid, 0d);
                    entry.visits.add(new Visit(session.startTime(), title));
                }
            }
        }
        var ordering = comparator(sort);
        var selected = people.entrySet().stream().filter(e -> switch (group) {
            case "first" -> composition != null && first.get(e.getKey()) >= from.toEpochMilli();
            case "returning" -> composition != null && first.get(e.getKey()) < from.toEpochMilli();
            case "interaction" -> e.getValue().interaction;
            default -> true;
        }).sorted((a, b) -> {
            int order = ordering.compare(a.getValue(), b.getValue());
            return order != 0 ? order : Long.compare(a.getKey(), b.getKey());
        }).toList();
        int total = selected.size();
        int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.max(1, Math.min(page, Math.max(1, pages)));
        List<Person> items = new ArrayList<>();
        for (var one : selected.subList((current - 1) * PAGE_SIZE, Math.min(current * PAGE_SIZE, total))) {
            Entry e = one.getValue();
            var visits = new ArrayList<>(e.visits);
            Collections.reverse(visits);
            items.add(new Person(e.name, visits.size(), first.get(one.getKey()), e.last,
                    e.known ? e.revenue : null, e.interaction, List.copyOf(visits)));
        }
        return new Page(from.toEpochMilli(), to.toEpochMilli(), counts, overview.coverage(),
                group, sort, current, pages, total, List.copyOf(items));
    }

    private static Set<Long> users(LiveSession session, List<String> keys) {
        Set<Long> result = new HashSet<>();
        for (String key : keys) result.addAll(session.userSet(key));
        return result;
    }

    private static Comparator<Entry> comparator(String sort) {
        Comparator<Entry> attendance = Comparator.comparingInt((Entry e) -> e.visits.size()).reversed();
        Comparator<Entry> revenue = Comparator.comparing((Entry e) -> e.known ? e.revenue : null,
                Comparator.nullsLast(Comparator.reverseOrder()));
        Comparator<Entry> recent = Comparator.comparingLong((Entry e) -> e.last).reversed();
        return switch (sort) {
            case "revenue" -> revenue.thenComparing(attendance).thenComparing(recent);
            case "recent" -> recent.thenComparing(attendance).thenComparing(revenue);
            default -> attendance.thenComparing(revenue).thenComparing(recent);
        };
    }

    private static final class Entry {
        String name;
        long last;
        double revenue;
        boolean known = true, interaction;
        final List<Visit> visits = new ArrayList<>();
    }
}
