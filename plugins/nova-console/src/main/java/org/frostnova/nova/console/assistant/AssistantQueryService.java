package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.console.controller.LiveSessionJson;
import org.frostnova.nova.console.controller.StreamerStatistics;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.assistant.AssistantException;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.analytics.StreamerInsights;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.lang.JsonlFiles;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.time.Clock;
import java.util.*;

/**
 * 智能助手的只读查数入口，时间均为毫秒，变化率为比例（0.5 表示 50%）。
 * 不触发采集、报告重绘或归档写入。返回对象均为本次查询新建。
 */
@Service
@NovaComponent
public class AssistantQueryService {
    private static final String SESSION_SOURCE = "场次归档，与主播页同一口径";
    private static final String DETAIL_SOURCE = "场次明细归档";
    static final int LINE_LIMIT = 500_000;
    private static final int LIST_LIMIT = 120;
    private static final List<String> COMPARABLE = List.of("durationSeconds", "effectiveSeconds", "revenue", "messages", "audience", "peakOnline");
    private final StreamerStatistics statistics;
    private final LiveSessionArchive archive;
    private final LiveDetailArchive details;
    private final LiveDataService live;
    private final NovaCoreProperties properties;
    private final ObjectProvider<LiveMetricCatalog> catalogs;
    private final Clock clock;

    @Autowired
    public AssistantQueryService(StreamerStatistics statistics, LiveSessionArchive archive, LiveDetailArchive details,
                                 LiveDataService live, NovaCoreProperties properties,
                                 ObjectProvider<LiveMetricCatalog> catalogs) {
        this(statistics, archive, details, live, properties, catalogs, Clock.systemUTC());
    }

    public AssistantQueryService(StreamerStatistics statistics, LiveSessionArchive archive, LiveDetailArchive details,
                                 LiveDataService live, NovaCoreProperties properties,
                                 ObjectProvider<LiveMetricCatalog> catalogs, Clock clock) {
        this.statistics = statistics;
        this.archive = archive;
        this.details = details;
        this.live = live;
        this.properties = properties;
        this.catalogs = catalogs;
        this.clock = clock;
    }

    JSONObject aggregate(String platform, Long uid, JSONObject args, AssistantQueryContext context, AssistantPrivacy privacy) {
        if (!validIdentity(platform, uid)) return AssistantAggregateQuery.rejected("筛选汇总", "请提供有效主播");
        try {
            var request = AssistantAggregateRequest.parse(args);
            boolean sessions = request.kinds().contains(AssistantAggregateRequest.Kind.SESSION);
            boolean living = live.getLiveStatus(platform, uid).orElse(false);
            Long start = living ? live.getLiveStartTime(platform, uid).orElse(null) : null;
            return new AssistantAggregateQuery(request, privacy).run(platform, uid, archive, sessions ? rules(platform) : null,
                    (at, name) -> file(platform, uid, at, name), living, start, clock.instant(), context);
        } catch (AssistantAggregateRequest.Invalid error) {
            return AssistantAggregateQuery.rejected("筛选汇总", error.getMessage());
        }
    }

    /** 最近 days 个统计日内开播的场次，包含已知开播时刻的当前场次。 */
    public JSONObject sessions(String platform, Long uid, int days, int limit) {
        if (!validIdentity(platform, uid) || days < 1 || days > 360) return missing(SESSION_SOURCE, "items", "请提供有效主播和 1–360 天的范围");
        var range = StreamerInsights.recent(days, clock);
        long from = range.from().toEpochMilli(), to = range.to().toEpochMilli();
        var ordered = sessionsOf(platform, uid).stream().filter(s -> s.startTime() >= from && s.startTime() < to)
                .sorted(Comparator.comparingLong(LiveSession::startTime).reversed()).toList();
        var items = new JSONArray();
        var rules = rules(platform);
        ordered.stream().limit(bound(limit, 20)).forEach(s -> items.add(sessionJson(s, rules)));
        var result = source(SESSION_SOURCE);
        result.putAll(list(items, ordered.size()));
        result.put("days", days);
        return result;
    }

    /** 当前场、上一场，以及之前至多五场已结束场次的逐项中位数和比较。 */
    public JSONObject session(String platform, Long uid, long start) {
        if (!validIdentity(platform, uid)) return missing(SESSION_SOURCE, "current", "主播标识无效");
        var all = sessionsOf(platform, uid);
        var selected = all.stream().filter(s -> s.startTime() == start).findFirst();
        if (selected.isEmpty()) return missing(SESSION_SOURCE, "current", "没有这一场的记录");
        var rules = rules(platform);
        var before = all.stream().filter(s -> s.startTime() < start)
                .sorted(Comparator.comparingLong(LiveSession::startTime).reversed()).toList();
        var samples = before.stream().filter(s -> !unfinished(s)).limit(5).map(s -> sessionJson(s, rules)).toList();
        var current = sessionJson(selected.get(), rules);
        var previous = before.isEmpty() ? null : sessionJson(before.get(0), rules);
        var median = new JSONObject();
        median.put("sampleCount", samples.size());
        for (String key : COMPARABLE) {
            var values = samples.stream().filter(s -> s.get(key) instanceof Number)
                    .mapToDouble(s -> s.getDoubleValue(key)).sorted().toArray();
            median.put(key, values.length == 0 ? null : (values[(values.length - 1) / 2] + values[values.length / 2]) / 2);
        }
        var counts = new JSONObject();
        COMPARABLE.forEach(key -> counts.put(key, samples.stream().filter(s -> s.get(key) instanceof Number).count()));
        median.put("metricSampleCounts", counts);
        var result = source(SESSION_SOURCE);
        result.put("current", current);
        result.put("previous", previous);
        result.put("median", median);
        result.put("versusPrevious", compare(current, previous));
        result.put("versusMedian", compare(current, median));
        result.put("sampleInsufficient", samples.size() < 3);
        result.put("unfinished", unfinished(selected.get()));
        return result;
    }

    /** 曲线先在全量点上寻找至多十分钟的最大下降，再独立抽稀，首末点保留。 */
    public JSONObject curve(String platform, Long uid, long start, String metric, int maxPoints) {
        if (!validIdentity(platform, uid)) return missing(DETAIL_SOURCE, "curve", "主播标识无效");
        var found = details.read(platform, uid, start);
        if (found.isEmpty()) return missing(DETAIL_SOURCE, "curve", "没有这一场的曲线明细，尚未下播的场次可能还未归档");
        String key = metric == null || metric.isBlank() ? "online_count" : metric;
        var detail = found.get();
        var points = detail.series(key).entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null && Double.isFinite(e.getValue()))
                .sorted(Map.Entry.comparingByKey()).toList();
        var result = source(DETAIL_SOURCE);
        result.put("metric", key);
        result.put("titles", titles(detail));
        if (points.isEmpty()) {
            result.put("curve", null);
            result.put("drops", null);
            result.put("reason", "这一场没有该指标的曲线");
            return result;
        }
        int count = Math.min(points.size(), Math.max(2, Math.min(maxPoints, LIST_LIMIT)));
        var sampled = new JSONArray();
        for (int i = 0; i < count; i++) {
            int index = count == 1 ? 0 : (int) ((long) i * (points.size() - 1) / (count - 1));
            var point = points.get(index);
            sampled.add(JSONObject.of("at", point.getKey(), "value", point.getValue()));
        }
        result.put("curve", list(sampled, points.size()));
        result.put("drops", drops(points));
        return result;
    }

    /** 弹幕窗口采用 [fromAt, toAt)，只保留计数、词频、发言者集合和至多三十条原话。 */
    public JSONObject danmu(String platform, Long uid, long start, long fromAt, long toAt, int limit) {
        String origin = "场次弹幕原文归档，按时间窗流式统计";
        if (!validIdentity(platform, uid) || fromAt < start || toAt <= fromAt) return missing(origin, "items", "请提供本场内有效的弹幕时间范围");
        Path file = file(platform, uid, start, "danmu.jsonl");
        var items = new JSONArray();
        var people = new HashSet<Long>();
        var words = new HashMap<String, Long>();
        var byType = JSONObject.of("DANMU", 0L, "EMOJI", 0L, "SUPER_CHAT", 0L);
        long total = 0, invalid = 0;
        boolean scanTruncated;
        var tokenizer = BreakIterator.getWordInstance(Locale.CHINA);
        try (var lines = JsonlFiles.lines(file)) {
            var iterator = lines.iterator();
            int scanned = 0;
            while (scanned < LINE_LIMIT && iterator.hasNext()) {
                String line = iterator.next();
                scanned++;
                JSONObject row = parseDanmu(line);
                if (row == null) { invalid++; continue; }
                long at = row.getLongValue("at");
                if (at < fromAt || at >= toAt) continue;
                String type = row.getString("type");
                if (!byType.containsKey(type)) type = "DANMU";
                total++;
                byType.put(type, byType.getLongValue(type) + 1);
                if (row.getLong("uid") != null) people.add(row.getLong("uid"));
                String text = row.getString("text");
                countWords(text, tokenizer, words);
                if (items.size() < bound(limit, 30)) items.add(JSONObject.of("at", at, "uid", row.getLong("uid"),
                        "uname", row.getString("uname"), "type", type, "text", clip(text, 60)));
            }
            scanTruncated = iterator.hasNext();
        } catch (IOException | UncheckedIOException e) {
            return missing(origin, "items", "没有这一场的弹幕文件，或文件无法读取");
        }
        var frequent = new JSONArray();
        words.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(10).forEach(e -> frequent.add(JSONObject.of("word", e.getKey(), "count", e.getValue())));
        var result = source(origin);
        result.putAll(list(items, total));
        result.put("fromAt", fromAt);
        result.put("toAt", toAt);
        result.put("speakers", people.size());
        result.put("byType", byType);
        result.put("words", list(frequent, words.size()));
        result.put("partial", scanTruncated || invalid > 0);
        result.put("invalidLines", invalid);
        if (scanTruncated || invalid > 0) result.put("reason", "文件超出行数上限或含无法解析的行，计数只是已读有效记录的下界");
        if (scanTruncated) result.put("truncated", true);
        return result;
    }

    /** 总额沿主播页目录的流水项；新开和续费只在事件记录能与总额核对时给出。 */
    public JSONObject revenue(String platform, Long uid, long start) {
        if (!validIdentity(platform, uid)) return missing(SESSION_SOURCE, "totalAmount", "主播标识无效");
        var selected = sessionsOf(platform, uid).stream().filter(s -> s.startTime() == start).findFirst();
        var catalog = catalog(platform);
        var rules = catalog == null ? null : catalog.insights();
        if (selected.isEmpty() || rules == null || rules.revenueKeys().isEmpty() || selected.get().metrics() == null
                || selected.get().metrics().isEmpty()) return missing(SESSION_SOURCE, "totalAmount", "没有这一场的流水记录或平台未提供流水口径");
        var session = selected.get();
        var metrics = session.metrics();
        if (unfinished(session) && rules.revenueKeys().stream().noneMatch(metrics::containsKey)) {
            var result = missing(SESSION_SOURCE, "totalAmount", "本场尚未记录流水指标");
            result.put("unfinished", true);
            return result;
        }
        var total = rules.revenue(metrics);
        var result = source("场次归档流水与全量贡献榜，与主播页同一口径");
        result.put("totalAmount", total);
        var components = new JSONObject();
        rules.revenueKeys().forEach(key -> components.put(key, unfinished(session) ? metrics.get(key) : metrics.getOrDefault(key, 0d)));
        result.put("components", components);
        result.put("gift", metrics.get("gift_value"));
        result.put("giftPaid", metrics.get("gift_paid"));
        result.put("superChat", metrics.get("super_chat_value"));
        result.put("guard", metrics.get("guard_value"));
        result.put("basis", "总额按主播页流水项相加；礼物价值与观众实扣可能不同，giftPaid 单列实扣");
        result.put("unfinished", unfinished(session));
        result.putAll(guards(platform, uid, start, metrics.get("guard_value")));
        if (result.containsKey("guardReason")) result.put("reason", result.getString("guardReason"));
        var found = details.read(platform, uid, start);
        String key = catalog.audienceRevenueRanking();
        if (key == null || found.isEmpty() || found.get().rankings() == null || !found.get().rankings().containsKey(key)) {
            result.put("contributors", null);
            result.put("payingUsers", null);
            result.put("top3Share", null);
            result.put("top10Share", null);
            result.put("concentrated", null);
            result.put("reason", "没有全量分人流水榜，付费人数与集中度未知");
        } else {
            var ranking = found.get().ranking(key).stream().filter(u -> u.userUid() != null && u.score() > 0)
                    .sorted(Comparator.comparingDouble(UserScore::score).reversed().thenComparing(UserScore::userUid)).toList();
            var contributors = new JSONArray();
            ranking.stream().limit(10).forEach(u -> contributors.add(JSONObject.of("uid", u.userUid(), "uname", u.userName(), "amount", u.score())));
            result.put("contributors", contributors);
            result.put("total", ranking.size());
            result.put("truncated", ranking.size() > 10);
            Double top3 = total > 0 ? ranking.stream().limit(3).mapToDouble(UserScore::score).sum() / total : null;
            result.put("top3Share", top3);
            result.put("top10Share", total > 0 ? ranking.stream().limit(10).mapToDouble(UserScore::score).sum() / total : null);
            result.put("concentrated", top3 == null ? null : top3 >= .5);
        }
        var payment = paymentPeople(platform, uid, start, session);
        result.putAll(payment);
        if (payment.containsKey("paymentReason")) {
            String reason = result.getString("reason");
            result.put("reason", (reason == null ? "" : reason + "；") + payment.getString("paymentReason"));
        }
        return result;
    }

    /** 直接复用主播页的本期、上期、比较、名单完整度和监听覆盖算法。 */
    public JSONObject insights(String platform, Long uid, int days) {
        return periodResult(platform, uid, days, "insights", "本期表现，与主播页同一算法",
                () -> statistics.insights(platform, uid, Integer.toString(days)));
    }

    /** 与主播页同参数分桶，仅返回最近至多六十桶。 */
    public JSONObject trend(String platform, Long uid, String period, int days) {
        String origin = "场次趋势，与主播页同一算法";
        if (!List.of("day", "week", "month").contains(period == null ? "" : period)) return missing(origin, "trend", "请选择 day、week、month");
        var result = periodResult(platform, uid, days, "trend", origin,
                () -> statistics.rangeTrend(platform, uid, period, Integer.toString(days)));
        var data = result.getJSONObject("trend");
        if (data != null) cap(data, "buckets", 60, true);
        return result;
    }

    /** 与主播页同参数的粉丝快照和变化；至多返回最近一百二十个点。 */
    public JSONObject fans(String platform, Long uid, int days) {
        var result = periodResult(platform, uid, days, "fans", "粉丝快照归档，与主播页同一算法",
                () -> statistics.fans(platform, uid, Integer.toString(days)));
        var data = result.getJSONObject("fans");
        if (data != null) {
            cap(data, "points", LIST_LIMIT, true);
            if (data.getJSONArray("points").isEmpty() && data.get("change") == null) {
                result.put("fans", null);
                result.put("reason", "没有足够的粉丝快照记录");
            }
        }
        return result;
    }

    private JSONObject periodResult(String platform, Long uid, int days, String key, String origin,
                                    java.util.function.Supplier<ResponseEntity<JSONObject>> query) {
        if (!validIdentity(platform, uid) || !List.of(7, 30, 90, 180, 360).contains(days)) return missing(origin, key, "请选择有效主播和近 7、30、90、180、360 天");
        var response = query.get();
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return missing(origin, key, response.getBody() == null ? "没有这份数据" : response.getBody().getString("message"));
        }
        // 深拷贝保证裁剪不改变接口返回对象或其嵌套集合。
        var result = JSON.parseObject(JSON.toJSONString(response.getBody(), com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls));
        result.put("source", origin);
        boundSupportingLists(result);
        return result;
    }

    /** 仅供本机文字去身份使用，不向模型公开。 */
    public Map<Long, Set<String>> localAudience(String platform, Long uid, List<Long> excluded) {
        if (!validIdentity(platform, uid)) throw new AssistantException("主播标识无效");
        Map<Long, Set<String>> result = new HashMap<>();
        var sessions = new ArrayList<>(archive.find(0, Long.MAX_VALUE).stream()
                .filter(s -> platform.equals(s.platform()) && !uid.equals(s.uid()) && !excluded.isEmpty()).toList());
        sessions.addAll(sessionsOf(platform, uid));
        for (var session : sessions) {
            if (Thread.currentThread().isInterrupted()) throw new AssistantException("智能助手提问已中止");
            boolean ownRoom = uid.equals(session.uid());
            java.util.function.BiConsumer<Long, String> add = (person, name) -> {
                if (person == null || uid.equals(person) || !ownRoom && !excluded.contains(person)) return;
                var names = result.computeIfAbsent(person, ignored -> new HashSet<>());
                if (name != null && !name.isBlank()) names.add(name);
            };
            if (session.userSets() != null) session.userSets().values().forEach(people -> people.forEach(person -> add.accept(person, null)));
            details.read(platform, session.uid(), session.startTime()).ifPresent(detail -> {
                if (detail.rankings() != null) detail.rankings().values().forEach(ranking ->
                        ranking.forEach(person -> add.accept(person.userUid(), person.userName())));
            });
            for (String name : List.of("danmu.jsonl", "events.jsonl")) {
                try (var lines = JsonlFiles.lines(file(platform, session.uid(), session.startTime(), name))) {
                    var rows = lines.iterator();
                    while (rows.hasNext()) {
                        if (Thread.currentThread().isInterrupted()) throw new AssistantException("智能助手提问已中止");
                        var row = parse(rows.next());
                        if (row != null && row.get("uid") instanceof Number) add.accept(row.getLong("uid"), row.getString("uname"));
                    }
                } catch (NoSuchFileException ignored) {
                    // 未保存原文的场次仍可从名单和明细读取身份。
                } catch (IOException | UncheckedIOException error) {
                    throw new AssistantException("智能助手无法读取本地观众身份，请稍后再试");
                }
            }
        }
        var current = live.getLiveMetricUserSets(platform, uid);
        current.forEach((metric, people) -> {
            people.stream().filter(person -> !uid.equals(person)).forEach(person -> result.computeIfAbsent(person, ignored -> new HashSet<>()));
            live.getLiveUserRanking(platform, uid, metric, Integer.MAX_VALUE).forEach(person -> {
                if (person.userUid() != null && !uid.equals(person.userUid())) {
                    var names = result.computeIfAbsent(person.userUid(), ignored -> new HashSet<>());
                    if (person.userName() != null && !person.userName().isBlank()) names.add(person.userName());
                }
            });
        });
        return result;
    }

    private List<LiveSession> sessionsOf(String platform, Long uid) {
        var sessions = new ArrayList<>(archive.find(0, Long.MAX_VALUE).stream()
                .filter(s -> platform.equals(s.platform()) && uid.equals(s.uid())).toList());
        if (live.getLiveStatus(platform, uid).orElse(false)) {
            live.getLiveStartTime(platform, uid).ifPresent(start -> {
                sessions.removeIf(s -> s.startTime() == start);
                sessions.add(new LiveSession(platform, uid, null, null, start, 0, 0,
                        live.getLiveMetrics(platform, uid), live.getLiveMetricUserCounts(platform, uid),
                        null, List.of(), 0, live.getLiveMetricUserSets(platform, uid), 0, Map.of()));
            });
        }
        sessions.sort(Comparator.comparingLong(LiveSession::startTime));
        return sessions;
    }

    private JSONObject sessionJson(LiveSession session, LiveMetricCatalog.Insights rules) {
        var item = LiveSessionJson.of(session);
        item.remove("userSets");
        // 名单只用于计数，不把无界的观众标识送给问答调用方。
        var titleItems = item.getJSONArray("titles");
        item.put("titles", list(new JSONArray(titleItems.subList(0, Math.min(titleItems.size(), LIST_LIMIT))), titleItems.size()));
        boolean unfinished = unfinished(session);
        if (unfinished) {
            for (String key : List.of("endTime", "durationSeconds", "effectiveSeconds", "endReason", "endReasonText", "interrupted",
                    "maintenanceGapSeconds", "roomOutageSeconds", "titleChangeCount")) item.put(key, null);
            if (session.titles() == null || session.titles().isEmpty()) item.put("titles", null);
            item.put("listQuality", null);
            item.put("reason", "本场尚未下播，结束信息与未归档的数值未知");
        }
        boolean known = rules != null && session.metrics() != null && !session.metrics().isEmpty();
        boolean revenueKnown = known && !rules.revenueKeys().isEmpty()
                && (!unfinished || rules.revenueKeys().stream().anyMatch(session.metrics()::containsKey));
        item.put("revenue", revenueKnown ? rules.revenue(session.metrics()) : null);
        item.put("messages", known ? session.metrics().get(rules.messageKey()) : null);
        Integer audience = null;
        if (rules != null && session.hasUserSets()) {
            var people = new HashSet<Long>();
            rules.audienceSets().forEach(key -> people.addAll(session.userSet(key)));
            audience = people.size();
        }
        item.put("audience", audience);
        item.put("peakOnline", session.peak("online_count").map(p -> p.value()).orElse(null));
        if (!known || !revenueKnown || audience == null || item.get("peakOnline") == null) item.putIfAbsent("reason", "部分指标未记录，null 表示未知");
        item.put("unfinished", unfinished);
        return item;
    }

    private boolean unfinished(LiveSession session) { return session.endTime() <= 0; }

    private JSONObject compare(JSONObject current, JSONObject baseline) {
        var result = new JSONObject();
        for (String key : COMPARABLE) {
            Double now = current.getDouble(key), before = baseline == null ? null : baseline.getDouble(key);
            result.put(key, JSONObject.of("difference", now == null || before == null ? null : now - before,
                    "changeRatio", now == null || before == null || before == 0 ? null : (now - before) / Math.abs(before)));
        }
        return result;
    }

    private LiveMetricCatalog catalog(String platform) {
        return catalogs.orderedStream().filter(c -> platform.equals(c.platform()) && c.insights() != null).findFirst().orElse(null);
    }

    Set<String> nonMonetaryMetrics(String platform) {
        var visible = new HashSet<>(List.of("online_count", "online_rank_count"));
        var money = new HashSet<String>();
        catalogs.orderedStream().filter(item -> platform.equals(item.platform())).forEach(item -> {
            item.liveMetrics().forEach(metric -> (metric.money() ? money : visible).add(metric.key()));
            item.snapshotMetrics().forEach(metric -> (metric.money() ? money : visible).add(metric.key()));
            if (item.insights() != null) money.addAll(item.insights().revenueKeys());
        });
        visible.removeAll(money);
        return visible;
    }

    private LiveMetricCatalog.Insights rules(String platform) {
        var catalog = catalog(platform);
        return catalog == null ? null : catalog.insights();
    }

    private JSONObject titles(LiveDetail detail) {
        if (detail.titles() == null) return null;
        var items = new JSONArray();
        detail.titles().stream().limit(LIST_LIMIT).forEach(t -> items.add(JSONObject.of("at", t.at(), "title", t.title(), "area", t.area())));
        return list(items, detail.titles().size());
    }

    private record Drop(int from, int to, double amount) {}

    private JSONObject drops(List<Map.Entry<Long, Double>> points) {
        var selected = new ArrayList<Drop>();
        long candidates = 0;
        // 每次线性扫描找一个最大下降，排除已选区间；不重复描述同一次掉人。
        for (int pass = 0; pass < 3; pass++) {
            var maxima = new ArrayDeque<Integer>();
            Drop best = null;
            for (int j = 0; j < points.size(); j++) {
                int index = j;
                if (selected.stream().anyMatch(d -> index >= d.from && index <= d.to)) { maxima.clear(); continue; }
                while (!maxima.isEmpty() && points.get(j).getKey() - points.get(maxima.peekFirst()).getKey() > 600000) maxima.removeFirst();
                if (!maxima.isEmpty()) {
                    int i = maxima.peekFirst();
                    double amount = points.get(i).getValue() - points.get(j).getValue();
                    if (pass == 0 && amount > 0) candidates++;
                    if (amount > 0 && (best == null || amount > best.amount
                            || (amount == best.amount && points.get(j).getKey() - points.get(i).getKey()
                            < points.get(best.to).getKey() - points.get(best.from).getKey()))) best = new Drop(i, j, amount);
                }
                while (!maxima.isEmpty() && points.get(maxima.peekLast()).getValue() <= points.get(j).getValue()) maxima.removeLast();
                maxima.addLast(j);
            }
            if (best == null) break;
            selected.add(best);
        }
        var items = new JSONArray();
        for (var drop : selected) {
            var from = points.get(drop.from); var to = points.get(drop.to);
            items.add(JSONObject.of("fromAt", from.getKey(), "fromValue", from.getValue(), "toAt", to.getKey(), "toValue", to.getValue(),
                    "drop", drop.amount, "dropRatio", from.getValue() == 0 ? null : drop.amount / from.getValue()));
        }
        // 候选按终点去重；重叠的候选只展示其中最强的一段。
        var result = list(items, candidates);
        result.put("selection", "每个终点取十分钟内最大下降，再按下降人数选择至多三段互不重叠的时段；并列取耗时较短者");
        return result;
    }

    private JSONObject guards(String platform, Long uid, long start, Double expected) {
        var result = JSONObject.of("guardOpen", null, "guardRenew", null);
        if (expected != null && expected == 0) { result.put("guardOpen", 0d); result.put("guardRenew", 0d); return result; }
        double opened = 0, renewed = 0;
        boolean complete = true;
        try (var lines = JsonlFiles.lines(file(platform, uid, start, "events.jsonl"))) {
            var iterator = lines.iterator();
            int count = 0;
            while (count < LINE_LIMIT && iterator.hasNext()) {
                var row = parse(iterator.next()); count++;
                if (row == null) { complete = false; continue; }
                if (!"guard".equals(row.getString("t"))) continue;
                try {
                    Double value = row.getDouble("val");
                    if (value == null || !Double.isFinite(value) || value < 0) { complete = false; continue; }
                    int operation = row.getIntValue("ot", -1);
                    if (operation == 1) opened += value;
                    else if (operation == 2) renewed += value;
                    else complete = false;
                } catch (RuntimeException e) { complete = false; }
            }
            complete &= !iterator.hasNext();
        } catch (IOException | UncheckedIOException e) { complete = false; }
        if (complete && expected != null && Math.abs(opened + renewed - expected) < 0.000001) {
            result.put("guardOpen", opened); result.put("guardRenew", renewed);
        } else result.put("guardReason", "缺少完整的新开与续费金额记录，无法拆分大航海流水");
        return result;
    }

    private JSONObject paymentPeople(String platform, Long uid, long start, LiveSession session) {
        var missing = JSONObject.of("payingUsers", null, "paymentReason", "缺少可核对的完整实扣记录，付费人数未知");
        var metrics = session.metrics();
        if (!"bilibili".equals(platform) || unfinished(session) || !metrics.containsKey("gift_value")
                || !metrics.containsKey("guard_value") || !metrics.containsKey("super_chat_value")) return missing;
        var people = new HashSet<Long>();
        double gift = 0, guard = 0, giftPaid = 0;
        boolean complete = true;
        try (var lines = JsonlFiles.lines(file(platform, uid, start, "events.jsonl"))) {
            var iterator = lines.iterator();
            int count = 0;
            while (count < LINE_LIMIT && iterator.hasNext()) {
                var row = parse(iterator.next()); count++;
                if (row == null) { complete = false; continue; }
                String type = row.getString("t");
                if (!List.of("gift", "guard", "box").contains(type == null ? "" : type)) continue;
                try {
                    Double value = row.getDouble("val"), paid = row.getDouble("pay");
                    if (value == null || paid == null || !Double.isFinite(value) || !Double.isFinite(paid) || paid < 0) {
                        complete = false; continue;
                    }
                    if (type.equals("guard")) guard += value;
                    else { gift += value; giftPaid += paid; }
                    Long person = row.getLong("uid");
                    if (paid > 0 && person == null) complete = false;
                    if (paid > 0 && person != null) people.add(person);
                } catch (RuntimeException e) { complete = false; }
            }
            complete &= !iterator.hasNext();
        } catch (NoSuchFileException e) {
            complete = metrics.get("gift_value") == 0 && metrics.get("guard_value") == 0
                    && metrics.getOrDefault("gift_paid", -1d) == 0;
        } catch (IOException | UncheckedIOException e) { complete = false; }
        complete &= Math.abs(gift - metrics.get("gift_value")) < 0.000001
                && Math.abs(guard - metrics.get("guard_value")) < 0.000001;
        if (metrics.containsKey("gift_paid")) complete &= Math.abs(giftPaid - metrics.get("gift_paid")) < 0.000001;
        if (!complete) return missing;
        if (metrics.get("super_chat_value") > 0) {
            // 醒目留言原文留在弹幕文件中；必须连同条数核对，避免只数到一部分付费者。
            if (!metrics.containsKey("super_chat_count")) return missing;
            long chats = 0;
            try (var lines = JsonlFiles.lines(file(platform, uid, start, "danmu.jsonl"))) {
                var iterator = lines.iterator();
                int count = 0;
                while (count < LINE_LIMIT && iterator.hasNext()) {
                    var row = parseDanmu(iterator.next()); count++;
                    if (row == null) return missing;
                    if (!"SUPER_CHAT".equals(row.getString("type"))) continue;
                    Long person = row.getLong("uid");
                    if (person == null) return missing;
                    people.add(person); chats++;
                }
                if (iterator.hasNext() || chats != metrics.get("super_chat_count")) return missing;
            } catch (IOException | UncheckedIOException e) { return missing; }
        }
        return JSONObject.of("payingUsers", people.size());
    }

    private Path file(String platform, Long uid, long start, String name) {
        // 与 LiveDetailArchive 的目录规则相同；只拼接经过校验的平台标识及数字。
        Path parent = Path.of(properties.getLive().getLiveDataPath()).getParent();
        return (parent == null ? Path.of("details") : parent.resolve("details"))
                .resolve(platform + "-" + uid + "-" + start).resolve(name);
    }

    private static JSONObject parse(String line) {
        try { return JSON.parseObject(line); }
        catch (RuntimeException e) { return null; }
    }

    private static JSONObject parseDanmu(String line) {
        var row = parse(line);
        if (row == null) return null;
        try {
            if (row.getLong("at") == null) return null;
            row.getLong("uid");
            return row;
        } catch (RuntimeException e) { return null; }
    }

    private static void boundSupportingLists(JSONObject object) {
        for (String key : new ArrayList<>(object.keySet())) {
            Object value = object.get(key);
            if (value instanceof JSONObject child) boundSupportingLists(child);
            else if (value instanceof JSONArray array) {
                // 主列表由各函数给 total/truncated；其余列表在原字段旁给出对应元数据。
                if (!key.equals("buckets") && !key.equals("points")) {
                    int max = key.equals("daily") ? 360 : LIST_LIMIT;
                    object.put(key + "Total", array.size());
                    object.put(key + "Truncated", array.size() > max);
                    if (array.size() > max) {
                        array = new JSONArray(array.subList(0, max));
                        object.put(key, array);
                    }
                }
                for (Object item : array) if (item instanceof JSONObject child) boundSupportingLists(child);
            }
        }
    }

    private static void countWords(String text, BreakIterator tokenizer, Map<String, Long> words) {
        if (text == null) return;
        tokenizer.setText(text);
        int from = tokenizer.first();
        for (int to = tokenizer.next(); to != BreakIterator.DONE; from = to, to = tokenizer.next()) {
            String word = text.substring(from, to).toLowerCase(Locale.ROOT);
            if (word.codePoints().anyMatch(Character::isLetterOrDigit)) words.merge(word, 1L, Long::sum);
        }
    }

    private static String clip(String text, int max) {
        return text == null || text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
    }

    private static boolean validIdentity(String platform, Long uid) {
        return platform != null && platform.matches("[A-Za-z0-9_-]{1,32}") && uid != null && uid > 0;
    }

    private static int bound(int requested, int max) { return Math.max(1, Math.min(requested, max)); }
    private static JSONObject source(String text) { return JSONObject.of("source", text); }
    private static JSONObject missing(String origin, String key, String reason) {
        var result = source(origin); result.put(key, null); result.put("reason", reason); return result;
    }
    private static JSONObject list(JSONArray items, long total) {
        return JSONObject.of("items", items, "total", total, "truncated", total > items.size());
    }
    private static void cap(JSONObject data, String key, int max, boolean newest) {
        var values = data.getJSONArray(key);
        if (values == null) return;
        int size = values.size();
        int from = newest ? Math.max(0, size - max) : 0;
        data.put(key, new JSONArray(values.subList(from, newest ? size : Math.min(size, max))));
        data.put("total", size); data.put("truncated", size > max);
    }
}
