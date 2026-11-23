package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.time.DayOfWeek.MONDAY;
import static java.time.temporal.TemporalAdjusters.previousOrSame;

/** 东八区用量与配置者确认。损坏时拒绝调用，避免悄悄清零额度。 */
public final class AssistantUsageStore {
    public record Acknowledgement(String actor, String at, String version) { }

    record Streamer(String platform, long uid) { }

    record Spending(BigDecimal todaySpentYuan, BigDecimal weekSpentYuan) {
        private static final Spending ZERO = new Spending(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    record Snapshot(BigDecimal spentYuan, Map<Streamer, Spending> streamers, Acknowledgement acknowledgement) { }

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int VERSION = 2;
    private final Path path;
    private final Clock clock;
    private LocalDate week;
    private LocalDate day;
    private BigDecimal spent = BigDecimal.ZERO;
    private Map<Streamer, Spending> streamers = new LinkedHashMap<>();
    private Acknowledgement acknowledgement;
    private String problem;

    public AssistantUsageStore(Path directory, Clock clock) {
        this.path = directory.resolve("assistant-usage.json");
        this.clock = clock;
        if (Files.exists(path)) {
            try {
                AssistantFiles.restrict(path);
                JSONObject json = JSON.parseObject(Files.readString(path));
                int version = json.getIntValue("version");
                if (version == VERSION) readSpending(json);
                else if (version != 1) throw storageError();
                JSONObject accepted = json.getJSONObject("acknowledgement");
                if (accepted != null) acknowledgement = new Acknowledgement(accepted.getString("actor"),
                        accepted.getString("at"), accepted.getString("version"));
            } catch (Exception error) {
                problem = "智能助手用量文件无法读取，请检查后再试";
            }
        }
        rollOver();
    }

    private void readSpending(JSONObject json) {
        day = LocalDate.parse(json.getString("day"));
        week = LocalDate.parse(json.getString("week"));
        if (!week.equals(day.with(previousOrSame(MONDAY)))) throw storageError();
        spent = nonNegative(json.getBigDecimal("spentYuan"));
        JSONArray entries = json.getJSONArray("streamers");
        if (entries == null) throw storageError();
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String platform = entry.getString("platform");
            Long uid = entry.getLong("uid");
            if (platform == null || platform.isBlank() || uid == null) throw storageError();
            var spending = new Spending(nonNegative(entry.getBigDecimal("todaySpentYuan")),
                    nonNegative(entry.getBigDecimal("weekSpentYuan")));
            if (streamers.putIfAbsent(new Streamer(platform, uid), spending) != null) throw storageError();
        }
    }

    synchronized Snapshot snapshot() {
        rollOver();
        return problem == null ? new Snapshot(spent, new LinkedHashMap<>(streamers), acknowledgement)
                : new Snapshot(null, Map.of(), null);
    }

    synchronized Acknowledgement acknowledgement() { return problem == null ? acknowledgement : null; }

    synchronized String problem() { return problem; }

    synchronized void requireReadable() {
        if (problem != null) throw new AssistantException("usage_unreadable", problem);
    }

    enum Scope {
        TOTAL("weekly_budget", "本周的助手总额度", "本周的助手总额度用完了，下周一 0 点恢复。"),
        STREAMER_WEEK("streamer_weekly_budget", "这位主播本周的助手额度", "这位主播本周的助手额度用完了，下周一 0 点恢复。"),
        STREAMER_DAY("streamer_daily_budget", "这位主播今天的助手额度", "这位主播今天的助手额度用完了，明天 0 点恢复。");

        final String reason;
        final String label;
        final String exhaustedMessage;

        Scope(String reason, String label, String exhaustedMessage) {
            this.reason = reason;
            this.label = label;
            this.exhaustedMessage = exhaustedMessage;
        }
    }

    record Allowance(Scope scope, BigDecimal limit, BigDecimal remaining) {
        boolean limited() { return limit.signum() > 0; }

        boolean exhausted() { return limited() && remaining.signum() <= 0; }

        Integer percent() {
            if (!limited()) return null;
            return remaining.max(BigDecimal.ZERO).movePointRight(2).divide(limit, 0, RoundingMode.DOWN).intValueExact();
        }

        boolean low() { return limited() && remaining.multiply(BigDecimal.valueOf(4)).compareTo(limit) <= 0; }

        boolean lowerThan(Allowance other) {
            return remaining.multiply(other.limit).compareTo(other.remaining.multiply(limit)) < 0;
        }

        String notice() {
            if (exhausted()) return "提示：" + scope.exhaustedMessage;
            int percent = percent();
            return "提示：" + scope.label + (percent == 0 ? "还剩不到 1%。" : "还剩 " + percent + "%。");
        }
    }

    record Quota(BigDecimal spentYuan, Allowance total, Allowance weekly, Allowance daily) {
        Scope exhausted() {
            for (Allowance allowance : List.of(total, weekly, daily))
                if (allowance.exhausted()) return allowance.scope();
            return null;
        }

        String notice() {
            Allowance lowest = null;
            for (Allowance allowance : List.of(total, weekly, daily))
                if (allowance.low() && (lowest == null || allowance.lowerThan(lowest))) lowest = allowance;
            return lowest == null ? null : lowest.notice();
        }
    }

    synchronized Quota quota(Streamer streamer, AssistantProperties properties) {
        if (problem != null) return null;
        rollOver();
        Spending used = streamers.getOrDefault(streamer, Spending.ZERO);
        return new Quota(spent, allowance(Scope.TOTAL, properties.getWeeklyBudgetYuan(), spent),
                allowance(Scope.STREAMER_WEEK, properties.getStreamerWeeklyBudgetYuan(), used.weekSpentYuan()),
                allowance(Scope.STREAMER_DAY, properties.getStreamerDailyBudgetYuan(), used.todaySpentYuan()));
    }

    private static Allowance allowance(Scope scope, BigDecimal limit, BigDecimal used) {
        return new Allowance(scope, limit, limit.subtract(used));
    }

    synchronized void checkBudget(Streamer streamer, AssistantProperties properties) {
        requireReadable();
        Scope exhausted = quota(streamer, properties).exhausted();
        if (exhausted != null) throw new AssistantException(exhausted.reason, exhausted.exhaustedMessage);
    }

    synchronized void addCost(Streamer streamer, BigDecimal cost) {
        requireReadable();
        rollOver();
        BigDecimal updated = spent.add(nonNegative(cost));
        Map<Streamer, Spending> byStreamer = streamers;
        if (streamer != null && cost.signum() > 0) {
            byStreamer = new LinkedHashMap<>(streamers);
            Spending current = byStreamer.getOrDefault(streamer, Spending.ZERO);
            byStreamer.put(streamer, new Spending(current.todaySpentYuan().add(cost), current.weekSpentYuan().add(cost)));
        }
        persist(updated, byStreamer, acknowledgement);
        spent = updated;
        streamers = byStreamer;
    }

    synchronized void acknowledge(String actor, String version) {
        requireReadable();
        rollOver();
        if (actor == null || actor.isBlank()) throw new AssistantException("确认人不能为空");
        Acknowledgement updated = new Acknowledgement(actor, clock.instant().toString(), version);
        persist(spent, streamers, updated);
        acknowledgement = updated;
    }

    private void rollOver() {
        if (problem != null) return;
        LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
        LocalDate monday = today.with(previousOrSame(MONDAY));
        if (!monday.equals(week)) {
            week = monday;
            spent = BigDecimal.ZERO;
            streamers.clear();
        }
        if (!today.equals(day)) {
            day = today;
            streamers.replaceAll((streamer, used) -> new Spending(BigDecimal.ZERO, used.weekSpentYuan()));
        }
    }

    private void persist(BigDecimal cost, Map<Streamer, Spending> byStreamer, Acknowledgement accepted) {
        var entries = byStreamer.entrySet().stream().map(entry -> JSONObject.of(
                "platform", entry.getKey().platform(), "uid", entry.getKey().uid(),
                "todaySpentYuan", entry.getValue().todaySpentYuan(), "weekSpentYuan", entry.getValue().weekSpentYuan()))
                .toList();
        try {
            AssistantFiles.write(path, JSONObject.of("version", VERSION, "week", week.toString(), "day", day.toString(),
                    "spentYuan", cost, "streamers", entries, "acknowledgement", accepted).toJSONString());
        } catch (Exception error) { throw storageError(); }
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) throw storageError();
        return value;
    }

    private static AssistantException storageError() { return new AssistantException("usage_unreadable", "智能助手用量文件无法读写，请检查后再试"); }
}
