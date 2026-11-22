package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.StatisticsDay;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record AssistantSqlRequest(LocalDate from, LocalDate to, EnumSet<Table> tables, String sql) {
    static final String NAME = "query_sql";
    static final String SOURCE = "只读查询（直播归档）";
    static final int CALL_LIMIT = 3;
    static final int SQL_BYTES = 16 * 1024;
    static final List<String> REQUIRED = List.of("from", "to", "tables", "sql");
    static final String SINGLE_QUERY = "SQL 只接受一条以 SELECT 或 WITH 开头的查询";
    private static final Set<String> FIELDS = Set.copyOf(REQUIRED);

    enum Table {
        SESSIONS(
                "start_at INTEGER PRIMARY KEY, day TEXT, end_at INTEGER, duration_seconds INTEGER, effective_seconds INTEGER, "
                        + "messages REAL, online_peak REAL, watched REAL, pk_result TEXT, pk_wins INTEGER, pk_losses INTEGER, pk_draws INTEGER",
                "start_at 开播时刻，day 开播统计日，end_at 下播时刻，duration_seconds 时长秒数，"
                        + "effective_seconds 有效采集秒数，messages 弹幕条数，online_peak 最高在线，watched 看过人数，"
                        + "pk_result 整场已知 PK 结算结果（win 胜、loss 负、draw 平、mixed 多种结果），"
                        + "pk_wins、pk_losses、pk_draws 分别是胜、负、平的结算次数",
                "revenue REAL, gift_amount REAL, guard_amount REAL",
                "revenue 流水，gift_amount 礼物金额（含盲盒开出物），guard_amount 上舰金额"),
        EVENTS(
                "at INTEGER, day TEXT, session_start INTEGER, kind TEXT, name TEXT, quantity REAL, audience TEXT, pk_result TEXT",
                "at 发生时刻，day 发生统计日，session_start 该场开播时刻，kind 种类（gift 礼物、box 盲盒、guard 上舰、"
                        + "super_chat 醒目留言、follow 关注、pk_settle PK 结算、attendance 名单到场），"
                        + "name 礼物名、盲盒名或上舰级别（captain 舰长、commander 提督、governor 总督），"
                        + "quantity 个数，audience 观众代号，pk_result 本次 PK 结果（win 胜、loss 负、draw 平）",
                "amount REAL, paid_amount REAL",
                "amount 到手金额，paid_amount 实扣金额（按归档，旧盲盒用盒价；背包礼物照归档计算）"),
        MINUTES("at INTEGER, day TEXT, session_start INTEGER, online REAL, watched REAL",
                "at 分钟起点，day 该分钟统计日，session_start 该场开播时刻，online 在线人数，watched 看过人数", "", ""),
        DANMU("at INTEGER, day TEXT, session_start INTEGER, audience TEXT, characters INTEGER",
                "at 发送时刻，day 发送统计日，session_start 该场开播时刻，audience 观众代号，characters 弹幕字数（Unicode 字符）", "", ""),
        AUDIENCE("audience TEXT, first_day TEXT",
                "audience 查询时间内有留档的观众代号，first_day 首次留档的统计日（可能早于查询范围）", "", "");

        private final String columns;
        private final String meanings;
        private final String amountColumns;
        private final String amountMeanings;

        Table(String columns, String meanings, String amountColumns, String amountMeanings) {
            this.columns = columns;
            this.meanings = meanings;
            this.amountColumns = amountColumns;
            this.amountMeanings = amountMeanings;
        }

        String key() { return name().toLowerCase(Locale.ROOT); }

        String columns(boolean amountVisible) {
            return amountVisible && !amountColumns.isEmpty() ? columns + ", " + amountColumns : columns;
        }

        String description(boolean amountVisible) {
            return key() + "：" + meanings + (amountVisible && !amountMeanings.isEmpty() ? "，" + amountMeanings : "") + "。";
        }
    }

    static JSONObject fields(boolean amountVisible) {
        return JSONObject.of(
                "from", JSONObject.of("type", "string", "description", "起始统计日 YYYY-MM-DD，含当天"),
                "to", JSONObject.of("type", "string", "description", "结束统计日 YYYY-MM-DD，含当天，合计最多 366 天"),
                "tables", JSONObject.of("type", "array", "minItems", 1, "maxItems", Table.values().length, "uniqueItems", true,
                        "items", JSONObject.of("type", "string", "enum", tableKeys()),
                        "description", "只选择查询所需的表及下列列名。\n"
                                + String.join("\n", Arrays.stream(Table.values()).map(table -> table.description(amountVisible)).toList())),
                "sql", JSONObject.of("type", "string", "maxLength", SQL_BYTES,
                        "description", SINGLE_QUERY + "，UTF-8 最多 16 KiB。计算列用 AS 起简短且不同的列名"));
    }

    static String description(boolean amountVisible) {
        return """
                只读查询。先用现有工具和通用汇总，答不了涉及多表、多步的统计再用本工具。只查询绑定主播的已结束归档。
                """ + AssistantAggregateQuery.DAY_RULE + "。分钟按格起点归日，跨日场次的明细可能没有对应的 sessions 行。"
                + "所有时刻为 Unix 毫秒，统计日为 YYYY-MM-DD，未知值为 NULL。只使用 tables 参数说明里的表和列。"
                + """

                attendance 是来自场次名单的到场记录，时刻按开播估算。数连续到场的观众时，把 events 和 danmu 的
                (session_start, audience) 去重后使用，只计非 NULL 代号；数礼物等事件时按 kind 筛选。
                danmu 含文字和表情弹幕，醒目留言归入 events。库中没有昵称、用户编号或留言原文。
                拒绝名单及匿名观众的代号为 NULL，不计入观众名单、分人或人数统计；其余合计仍包含这些记录。
                """ + (amountVisible ? "金额单位元，" + AssistantAggregateQuery.AMOUNT_BASIS + "。"
                : "当前聊天不显示金额，表中没有任何金额列。")
                + "首次出现沿用现有归档索引，回答新老观众时写明 historyFrom 留档起点。"
                + "每问最多调用 3 次，每次最多装入 50 万行，SQL 限时 5 秒，SQLite 内存上限 64 MiB。"
                + "结果最多 32 列、200 行、64 KiB，可用 resultNumber 和 chartColumns 出图。回答用一两句说明怎么算的。";
    }

    static AssistantSqlRequest parse(JSONObject raw) {
        if (raw == null || !raw.keySet().equals(FIELDS)) {
            throw new Invalid("只读查询需要且只接受起止统计日、表名和 SQL");
        }
        LocalDate from;
        LocalDate to;
        try {
            from = AssistantAggregateRequest.date(raw.get("from"));
            to = AssistantAggregateRequest.date(raw.get("to"));
        } catch (AssistantAggregateRequest.Invalid error) {
            throw new Invalid(error.getMessage());
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 0 || days >= 366) throw new Invalid("起止统计日需按顺序，范围最多 366 天");
        if (!(raw.get("tables") instanceof List<?> names) || names.isEmpty() || names.size() > Table.values().length) {
            throw new Invalid("请选择 1–5 张表");
        }
        var tables = EnumSet.noneOf(Table.class);
        for (Object name : names) {
            var table = Arrays.stream(Table.values()).filter(candidate -> candidate.key().equals(name)).findFirst()
                    .orElseThrow(() -> new Invalid("表名须使用工具说明中的表"));
            if (!tables.add(table)) throw new Invalid("表名不可重复");
        }
        if (!(raw.get("sql") instanceof String sql) || sql.isBlank()) throw new Invalid(SINGLE_QUERY);
        if (sql.length() > SQL_BYTES || sql.getBytes(StandardCharsets.UTF_8).length > SQL_BYTES) {
            throw new Invalid("SQL 超过 16 KiB");
        }
        checkStatement(sql);
        return new AssistantSqlRequest(from, to, tables, sql);
    }

    long fromAt() { return StatisticsDay.startOf(from).toEpochMilli(); }

    long toAt() { return StatisticsDay.startOf(to.plusDays(1)).toEpochMilli(); }

    JSONObject arguments() {
        return JSONObject.of("from", from.toString(), "to", to.toString(),
                "tables", tables.stream().map(Table::key).toList(), "sql", sql);
    }

    static JSONObject scope(JSONObject arguments) {
        return JSONObject.of("from", arguments.get("from"), "to", arguments.get("to"), "tables", arguments.get("tables"));
    }

    private static List<String> tableKeys() { return Arrays.stream(Table.values()).map(Table::key).toList(); }

    // SQLite 预编译时另行检查语句余部，且在原生授权回调中检查操作权限。
    private static void checkStatement(String sql) {
        if (sql.indexOf(0) >= 0) throw new Invalid(SINGLE_QUERY);
        int begin = skipTrivia(sql, 0);
        int end = begin;
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) end++;
        String first = sql.substring(begin, end).toUpperCase(Locale.ROOT);
        if (!first.equals("SELECT") && !first.equals("WITH")) throw new Invalid(SINGLE_QUERY);
    }

    static boolean onlyTrivia(String sql) { return skipTrivia(sql, 0) == sql.length(); }

    private static int skipTrivia(String sql, int at) {
        while (at < sql.length()) {
            char c = sql.charAt(at);
            if (c == ' ' || c >= '\t' && c <= '\r' || c == '\ufeff') {
                at++;
            } else if (sql.startsWith("--", at)) {
                int end = sql.indexOf('\n', at + 2);
                at = end < 0 ? sql.length() : end + 1;
            } else if (sql.startsWith("/*", at)) {
                int end = sql.indexOf("*/", at + 2);
                at = end < 0 ? sql.length() : end + 2;
            } else {
                break;
            }
        }
        return at;
    }

    static JSONObject rejected(String reason) { return JSONObject.of("source", SOURCE, "reason", reason); }

    static final class Invalid extends IllegalArgumentException {
        Invalid(String message) { super(message); }
    }
}
