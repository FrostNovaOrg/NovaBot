package org.frostnova.nova.core.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.frostnova.nova.core.analytics.SessionRecap.*;

/** 固定模板草稿入口，不读取归档或发送消息。 */
public final class SessionRecapRenderer {
    private SessionRecapRenderer() {}
    private static final List<String> ORDER = List.of("newcomer_speak_return", "regulars_absent", "slot_compare", "pk_share");
    private static final List<String> SLOTS = List.of("凌晨", "上午", "下午", "晚上");
    private static final List<String> FORBIDDEN = List.of("因为", "导致", "所以", "由于");
    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd", Locale.ROOT).withZone(ZONE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZONE);
    public enum Nicknames { ORIGINAL, MASKED }
    public record Options(int level, boolean showAmount, Nicknames nicknames, Set<Long> excludedUids) {
        public Options(int level, boolean showAmount, Nicknames nicknames) {
            this(level, showAmount, nicknames, Set.of());
        }
        public Options {
            if (level != 1 && level != 2) throw new IllegalArgumentException("level must be 1 or 2");
            java.util.Objects.requireNonNull(nicknames);
            excludedUids = Set.copyOf(excludedUids);
        }
    }
    public record Line(List<String> keys, String text) {
        public Line { keys = List.copyOf(keys); }
    }
    public record SpokenPattern(String key, Map<String, Object> values) {
        public SpokenPattern { values = Map.copyOf(values); }
    }
    /** 一次小结一个记录；即使没有规律也保留场次，供最近三次判断。 */
    public record History(long session, List<SpokenPattern> patterns) {
        public History { patterns = List.copyOf(patterns); }
    }
    public record Draft(List<Line> lines, List<Line> notes, History history) {
        public Draft { lines = List.copyOf(lines); notes = List.copyOf(notes); }
        public String message() { return String.join("\n", lines.stream().map(Line::text).toList()); }
    }
    public static Draft render(SessionRecap.Result result, Options options, List<History> history) {
        return new Rendering(result, options).render(history);
    }

    private static final class Rendering {
        final Result result;
        final Options options;
        final Presentation context;
        final Map<String, Item> facts = new HashMap<>();
        final List<Line> lines = new ArrayList<>(), notes = new ArrayList<>();
        final Set<Long> excluded = new HashSet<>();
        final long session;

        Rendering(Result result, Options options) {
            this.result = result; this.options = options; context = result.presentation();
            result.facts().forEach(i -> facts.putIfAbsent(i.key(), i));
            excluded.addAll(options.excludedUids());
            if (context != null) excluded.addAll(context.excludedUids());
            session = context != null ? context.start() : java.util.stream.Stream.concat(result.facts().stream(),
                    result.patterns().stream()).mapToLong(Item::session).findFirst().orElse(0);
        }

        Draft render(List<History> history) {
            title(); metrics();
            if (audienceKnown()) newcomers();
            count("fan_club", "fanClub", "灯牌 ", " 个");
            count("follows", "follows", "新关注 ", "");
            if (audienceKnown()) returning();
            if (options.showAmount()) {
                Item top = facts.get("top_gifters");
                if (top != null) {
                    String names = names(people(top));
                    if (!names.isEmpty()) add("top_gifters", "· 礼物前三：" + names);
                }
            }
            words();
            if (context != null) {
                if (!"complete".equals(context.listQuality())) {
                    String reason = switch (context.listQuality()) {
                        case "anonymous" -> "未登录采集";
                        case "missing" -> "没有名单";
                        default -> "不确定";
                    };
                    note("listQuality", "这一场观众名单不完整（" + reason + "），新观众、回来类没有统计。");
                }
                if (context.previousSessions() < 10) note("ledger_sessions", "记录还不到 10 场，没提新观众。");
            }
            var spoken = new ArrayList<SpokenPattern>();
            if (options.level() == 2) {
                var recent = history.stream().filter(h -> h.session() < session)
                        .sorted(Comparator.comparingLong(History::session).reversed()).toList();
                var recentSessions = recent.stream().map(History::session).distinct().limit(3).toList();
                var selected = result.patterns().stream().filter(i -> ORDER.contains(i.key()))
                        .sorted(Comparator.comparingInt((Item i) -> strength(i.strength()))
                                .thenComparingInt(i -> ORDER.indexOf(i.key())))
                        .filter(i -> !patternText(i).isEmpty())
                        .filter(i -> recent.stream().filter(h -> recentSessions.contains(h.session()))
                                .flatMap(h -> h.patterns().stream()).filter(p -> p.key().equals(i.key()))
                                .findFirst().map(p -> changed(i, p, options.showAmount())).orElse(true))
                        .collect(java.util.stream.Collectors.toMap(Item::key, i -> i, (a, b) -> a, java.util.LinkedHashMap::new))
                        .values().stream().limit(2).toList();
                if (!selected.isEmpty()) {
                    lines.add(new Line(selected.stream().map(Item::key).toList(), "从你过去 "
                            + selected.stream().mapToInt(i -> i.sample().qualifiedSessions()).max().orElseThrow() + " 场里看到的"));
                    for (Item item : selected) {
                        add(item.key(), patternText(item) + ("weak".equals(item.strength()) ? "样本还少，先当参考。" : ""));
                        var values = new HashMap<>(item.values());
                        if (!options.showAmount()) { values.remove("giftA"); values.remove("giftB"); }
                        spoken.add(new SpokenPattern(item.key(), values));
                        if (item.key().equals("newcomer_speak_return")) note(item.key(),
                                "注意：这条只说明开口的新观众回来得多，不一定是开口让人回来，也可能是本来就上心的人才会开口。");
                    }
                }
            }
            return new Draft(lines, notes, new History(session, spoken));
        }

        boolean audienceKnown() {
            return context == null || (context.previousSessions() >= 10 && "complete".equals(context.listQuality()));
        }

        void title() {
            Item item = facts.get("duration");
            if (!positive(item, "start")) return;
            long start = (long) value(item, "start");
            var instant = Instant.ofEpochMilli(start);
            String text = "【" + DATE.format(instant) + " " + SLOTS.get(instant.atOffset(ZONE).getHour() / 6) + "场小结】";
            if (positive(item, "end") && value(item, "end") > start)
                text += TIME.format(instant) + "–" + TIME.format(Instant.ofEpochMilli((long) value(item, "end")));
            if (positive(item, "durationMinutes")) {
                long minutes = (long) value(item, "durationMinutes"), hours = minutes / 60, remainder = minutes % 60;
                if (minutes > 0) text += (text.endsWith("】") ? "" : "，") + "播了 " + (hours > 0 ? hours + " 小时" : "")
                        + (remainder > 0 ? (hours > 0 ? " " : "") + remainder + (hours > 0 ? " 分" : " 分钟") : "");
            }
            add("duration", text);
        }

        void metrics() {
            Item watched = facts.get("watched_rank"), gift = facts.get("gift_rank");
            boolean w = positive(watched, "watched"), g = options.showAmount() && positive(gift, "gift");
            if (!w && !g) return;
            int wr = w ? rank(watched) : 0, gr = g ? rank(gift) : 0;
            boolean shared = wr > 0 && wr == gr;
            String text = w ? "看过 " + num(watched, "watched") + " 人" + (!shared ? ranking(wr) : "") : "";
            if (g) text += (w ? "、" : "") + "礼物 ¥" + num(gift, "gift") + (!shared ? ranking(gr) : "");
            if (shared) text += "，都是最近 10 场第 " + wr;
            var keys = new ArrayList<String>();
            if (w) keys.add("watched_rank");
            if (g) keys.add("gift_rank");
            lines.add(new Line(keys, "· " + text));
        }

        void newcomers() {
            Item item = facts.get("newcomers");
            if (!positive(item, "newcomers")) return;
            add("newcomers", "· 第一次进来的有 " + num(item, "newcomers") + " 人"
                    + (positive(item, "newcomerSpoke") ? "，其中 " + num(item, "newcomerSpoke") + " 位开口说话" : ""));
        }

        void count(String key, String field, String prefix, String suffix) {
            Item item = facts.get(key);
            if (positive(item, field)) add(key, "· " + prefix + num(item, field) + suffix);
        }

        void returning() {
            Item item = facts.get("returning_newcomers");
            if (!positive(item, "returned") || !positive(item, "previousSpoke")) return;
            var people = people(item);
            if (people.isEmpty()) return;
            String names = names(people);
            String date = context != null && context.previousSession() != null
                    ? "（" + DATE.format(Instant.ofEpochMilli(context.previousSession())) + "）" : "";
            add(item.key(), "· 上一场" + date + "第一次来就开口的 " + num(item, "previousSpoke")
                    + " 位里，这一场回来了 " + people.size() + " 位" + (names.isEmpty() ? "" : "：" + names));
        }

        List<Long> people(Item item) {
            return item.people().stream().filter(uid -> uid > 0 && uid != item.streamer().uid() && !excluded.contains(uid)).distinct().toList();
        }

        String names(List<Long> people) {
            if (context == null) return "";
            var names = people.stream().map(context.nicknames()::get).filter(SessionRecapRenderer::safe)
                    .map(n -> options.nicknames() == Nicknames.MASKED ? mask(n) : n).limit(3).toList();
            if (names.isEmpty()) return "";
            return String.join("、", names) + (people.size() > 3 ? "等 " + people.size() + " 位" : "");
        }

        void words() {
            Item item = facts.get("top_words");
            if (item == null || !(item.values().get("words") instanceof List<?> words)) return;
            var shown = words.stream().filter(String.class::isInstance).map(String.class::cast)
                    .filter(SessionRecapRenderer::safe).map(this::word).filter(s -> !s.isBlank()).limit(3).toList();
            if (!shown.isEmpty()) add(item.key(), "· 弹幕里说得最多的：" + String.join("、", shown));
        }

        String word(String word) {
            if (context == null) return options.nicknames() == Nicknames.MASKED ? "" : word;
            for (var entry : context.nicknames().entrySet())
                if (excluded.contains(entry.getKey()) && !entry.getValue().isBlank() && word.contains(entry.getValue())) return "";
            if (options.nicknames() == Nicknames.MASKED) {
                for (String name : context.nicknames().values().stream().filter(SessionRecapRenderer::safe)
                        .sorted(Comparator.comparingInt(String::length).reversed()).toList()) word = word.replace(name, mask(name));
            }
            return word;
        }

        String patternText(Item i) {
            if (i.sample() == null || i.sample().qualifiedSessions() < 1 || strength(i.strength()) > 2) return "";
            return switch (i.key()) {
                case "newcomer_speak_return" -> audienceKnown() && percentages(i, "spokeReturn", "silentReturn", "todayRate", "usualRate")
                        ? "第一次来就开口的新观众，之后 3 场内回来的有 " + percent(i, "spokeReturn") + "；没开口的是 "
                        + percent(i, "silentReturn") + "。这一场新观众的开口率是 " + percent(i, "todayRate") + "，你平时是 " + percent(i, "usualRate") + "。" : "";
                case "regulars_absent" -> positive(i, "regulars", "absent") ? "常来的 " + num(i, "regulars") + " 位观众里，有 "
                        + num(i, "absent") + " 位最近 5 场都没出现。" : "";
                case "slot_compare" -> slotText(i);
                case "pk_share" -> options.showAmount() && positive(i, "pkGiftPerMin", "normalGiftPerMin", "pkSessions")
                        ? "PK 时每分钟礼物 " + num(i, "pkGiftPerMin") + " 元，不在 PK 时是 " + num(i, "normalGiftPerMin")
                        + " 元（" + num(i, "pkSessions") + " 场有 PK）。" : "";
                default -> "";
            };
        }

        String slotText(Item i) {
            if (!SLOTS.contains(i.values().get("slotA")) || !SLOTS.contains(i.values().get("slotB"))
                    || !positive(i, "sessionsA", "sessionsB", "watchedA", "watchedB")) return "";
            String text = i.values().get("slotA") + "场（" + num(i, "sessionsA") + " 场）看过人数的中位数是 " + num(i, "watchedA")
                    + "，" + i.values().get("slotB") + "场（" + num(i, "sessionsB") + " 场）是 " + num(i, "watchedB");
            if (options.showAmount() && positive(i, "giftA", "giftB")) {
                double a = value(i, "giftA"), b = value(i, "giftB");
                text += Math.abs(a - b) / Math.min(a, b) < .2
                        ? "；礼物差不多（" + num(i, "giftA") + " 元对 " + num(i, "giftB") + " 元）"
                        : "；礼物中位数 " + num(i, "giftA") + " 元对 " + num(i, "giftB") + " 元";
            }
            return text + "。";
        }

        void add(String key, String text) { lines.add(new Line(List.of(key), text)); }
        void note(String key, String text) { notes.add(new Line(List.of(key), text)); }
    }

    private static boolean safe(String text) {
        return text != null && !text.isBlank() && !text.equals("0") && text.codePoints().noneMatch(c -> Character.isISOControl(c)
                || c == 0x2028 || c == 0x2029) && FORBIDDEN.stream().noneMatch(text::contains);
    }
    private static String mask(String name) { return new String(Character.toChars(name.codePointAt(0))) + "*"; }
    private static double value(Item item, String key) { return number(item == null ? Map.of() : item.values(), key); }
    private static double number(Map<String, Object> values, String key) {
        Object v = values.get(key);
        return v instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : Double.NaN;
    }
    private static boolean positive(Item item, String... keys) {
        for (String key : keys) if (!(value(item, key) > 0)) return false;
        return true;
    }
    private static String num(Item i, String key) { return BigDecimal.valueOf(value(i, key)).stripTrailingZeros().toPlainString(); }
    private static String percent(Item i, String key) { return String.format(Locale.ROOT, "%.1f%%", value(i, key) * 100); }
    private static boolean percentages(Item i, String... keys) {
        for (String key : keys) if (!positive(i, key) || value(i, key) > 1 || percent(i, key).equals("0.0%")) return false;
        return true;
    }
    private static int rank(Item i) {
        double rank = value(i, "rank");
        return value(i, "outOf") >= 5 && rank >= 1 && rank <= 3 && rank == Math.rint(rank) ? (int) rank : 0;
    }
    private static String ranking(int rank) { return rank > 0 ? "（最近 10 场第 " + rank + "）" : ""; }
    private static int strength(String strength) {
        if (strength == null) return 3;
        return switch (strength) { case "strong" -> 0; case "medium" -> 1; case "weak" -> 2; default -> 3; };
    }

    private static boolean changed(Item item, SpokenPattern previous, boolean showAmount) {
        var old = new HashMap<>(previous.values());
        var now = item.values();
        if (item.key().equals("slot_compare")) {
            if (java.util.Objects.equals(now.get("slotA"), old.get("slotB")) && java.util.Objects.equals(now.get("slotB"), old.get("slotA"))) {
                for (String prefix : List.of("watched", "gift")) {
                    Object a = old.get(prefix + "A"), b = old.get(prefix + "B");
                    if (a != null && b != null) { old.put(prefix + "A", b); old.put(prefix + "B", a); }
                }
            } else if (!java.util.Objects.equals(now.get("slotA"), old.get("slotA"))
                    || !java.util.Objects.equals(now.get("slotB"), old.get("slotB"))) return false;
        }
        String a = switch (item.key()) {
            case "newcomer_speak_return" -> "spokeReturn";
            case "regulars_absent" -> "absent";
            case "slot_compare" -> "watchedA";
            case "pk_share" -> "pkGiftPerMin";
            default -> "";
        };
        String b = switch (item.key()) {
            case "newcomer_speak_return" -> "silentReturn";
            case "slot_compare" -> "watchedB";
            case "pk_share" -> "normalGiftPerMin";
            default -> "";
        };
        double n = number(now, a), o = number(old, a);
        boolean major = Double.isFinite(n) && Double.isFinite(o) && Math.abs(n - o) > Math.abs(o) * .5;
        double nb = item.key().equals("regulars_absent") ? number(now, "regulars") - n : number(now, b);
        double ob = item.key().equals("regulars_absent") ? number(old, "regulars") - o : number(old, b);
        return major || reversed(n, nb, o, ob) || (showAmount && item.key().equals("slot_compare")
                && reversed(number(now, "giftA"), number(now, "giftB"), number(old, "giftA"), number(old, "giftB")));
    }

    private static boolean reversed(double a, double b, double oldA, double oldB) {
        return Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(oldA) && Double.isFinite(oldB)
                && (a - b) * (oldA - oldB) < 0;
    }
}
