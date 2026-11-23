package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/** 工具只读，主播身份由调用方绑定。 */
final class AssistantTools {
    private record Tool(String name, String description, JSONObject fields, List<String> required) { }
    private static JSONObject integer(String description) { return JSONObject.of("type", "integer", "description", description); }
    private static JSONObject text(String description) { return JSONObject.of("type", "string", "description", description); }
    private static final List<Tool> TOOLS = List.of(
            new Tool("sessions", "列出最近的直播场次", JSONObject.of("days", integer("最近 1–360 天"), "limit", integer("最多 20 场")), List.of("days", "limit")),
            new Tool("session", "本场与上一场、此前五场中位数的比较", JSONObject.of("start", integer("开播时刻，毫秒")), List.of("start")),
            new Tool("curve", "本场指标曲线和下降窗口", JSONObject.of("start", integer("开播时刻，毫秒"), "metric", text("指标键，默认 online_count"), "maxPoints", integer("2–120 个点")), List.of("start")),
            new Tool("danmu", "本场时间窗弹幕条数与分类统计",
                    JSONObject.of("start", integer("开播时刻，毫秒"), "fromAt", integer("窗口开始，毫秒，含"),
                            "toAt", integer("窗口结束，毫秒，不含")), List.of("start", "fromAt", "toAt")),
            new Tool("revenue", "本场流水合计与构成、贡献者代号及占比", JSONObject.of("start", integer("开播时刻，毫秒")), List.of("start")),
            new Tool("insights", "本期、上期与比较，含样本及监听覆盖", JSONObject.of("days", integer("7、30、90、180、360 天")), List.of("days")),
            new Tool("trend", "按天、周、月分桶的场次趋势", JSONObject.of("period", text("day、week、month"), "days", integer("7、30、90、180、360 天")), List.of("period", "days")),
            new Tool("fans", "粉丝快照与变化。windowFromAt 至 windowToAtExclusive 为查询窗口，fromAt、fromValue、toAt、toValue 是图上首尾点的时刻和值。pointChange 是两点间变化。"
                    + "主播页口径 change 可用窗口前快照作基准",
                    JSONObject.of("days", integer("7、30、90、180、360 天")), List.of("days")),
            new Tool(AssistantAggregateRequest.NAME,
                    "筛选已归档的记录并分组汇总。筛选条件同时满足。"
                            + AssistantAggregateQuery.DAY_RULE + "。区间流水合计或每日流水用 session 的 sum:amount，"
                            + AssistantAggregateQuery.SESSION_REVENUE_RULE + "。逐日流水补齐无开播日，流水和场数为 0，最多 92 行。"
                            + "拆礼物种类或观众时用付费事件。"
                            + AssistantAggregateQuery.MINUTE_RULE + "。"
                            + AssistantAggregateQuery.SESSION_COUNT_RULE + "。"
                            + AssistantAggregateQuery.ZERO_RULE + "。弹幕只按 day 分组时逐日出行，逐条明细按 minute 分组时逐直播时段出行，"
                            + "其余分组省略空组。弹幕计文字与表情，醒目留言单列。"
                            + AssistantAggregateQuery.HISTORY_RULE + "。回答新老观众问题时写明 historyFrom 留档起点。"
                            + "首次出现记录按主播从全部归档逐场建立。若提示还在建立，请转告进度并请用户稍后再问。金额单位元，"
                            + AssistantAggregateQuery.AMOUNT_BASIS + "。"
                            + "观众或新老分组、代号筛选、去重观众人数及付费人数排除拒绝名单。其余金额合计包含拒绝名单上的观众，"
                            + "与控制台流水的观众范围一致。金额权限以调用方为准。"
                            + "合计覆盖截断前的所有匹配记录，null 表示未知。"
                            + AssistantAggregateQuery.KNOWN_AMOUNT_RULE + "。数字以工具结果为准，不要自己加总明细。",
                    AssistantAggregateRequest.fields(), AssistantAggregateRequest.REQUIRED),
            new Tool(AssistantSqlRequest.NAME, AssistantSqlRequest.description(true),
                    AssistantSqlRequest.fields(true), AssistantSqlRequest.REQUIRED),
            new Tool(AssistantChartRequest.NAME, AssistantChartRequest.DESCRIPTION,
                    AssistantChartRequest.fields(), AssistantChartRequest.REQUIRED),
            new Tool(AssistantSketchRequest.NAME, AssistantSketchRequest.DESCRIPTION,
                    AssistantSketchRequest.fields(), AssistantSketchRequest.REQUIRED));

    static List<JSONObject> definitions(AssistantQueryContext context) {
        return TOOLS.stream().filter(tool -> context.amountVisible() || !"revenue".equals(tool.name()))
                .filter(tool -> !AssistantSqlRequest.NAME.equals(tool.name()) || AssistantSqlNative.available())
                .map(tool -> AssistantSqlRequest.NAME.equals(tool.name())
                        ? new Tool(tool.name(), (context.amountVisible() ? tool.description() : AssistantSqlRequest.description(false))
                            + AssistantSqlRequest.dialect(AssistantSqlNative.version()),
                            context.amountVisible() ? tool.fields() : AssistantSqlRequest.fields(false), tool.required()) : tool)
                .map(t -> JSONObject.of("type", "function", "function", JSONObject.of("name", t.name(),
                "description", t.description(), "parameters", JSONObject.of("type", "object", "properties", t.fields(),
                        "required", t.required(), "additionalProperties", false)))).toList();
    }

    /** 白名单外参数不进入执行、步骤和后续模型请求。 */
    static JSONObject arguments(String name, JSONObject raw) {
        if (AssistantAggregateRequest.NAME.equals(name)) return AssistantAggregateRequest.parse(raw).arguments();
        if (AssistantSqlRequest.NAME.equals(name)) return AssistantSqlRequest.parse(raw).arguments();
        var tool = TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow(IllegalArgumentException::new);
        JSONObject result = new JSONObject();
        for (Map.Entry<String, Object> field : tool.fields().entrySet()) {
            if (!raw.containsKey(field.getKey())) {
                if (tool.required().contains(field.getKey())) throw new IllegalArgumentException();
                continue;
            }
            Object value = raw.get(field.getKey());
            if ("integer".equals(((JSONObject) field.getValue()).getString("type"))) {
                if (!(value instanceof Number)) throw new IllegalArgumentException();
                result.put(field.getKey(), new java.math.BigDecimal(value.toString()).longValueExact());
            } else {
                if (!(value instanceof String text) || text.length() > 80) throw new IllegalArgumentException();
                result.put(field.getKey(), value);
            }
        }
        return result;
    }

    static JSONObject query(AssistantQueryService queries, String platform, Long uid, String name, JSONObject args,
                            AssistantQueryContext context, AssistantPrivacy privacy, IntPredicate pauseHistory) {
        if (AssistantAggregateRequest.NAME.equals(name)) {
            return queries.aggregate(platform, uid, args, context, privacy, pauseHistory);
        }
        if (AssistantSqlRequest.NAME.equals(name)) {
            return queries.querySql(platform, uid, args, context, privacy, pauseHistory);
        }
        Set<String> nonMonetaryMetrics = context.amountVisible() ? Set.of() : queries.nonMonetaryMetrics(platform);
        if (!context.amountVisible() && ("revenue".equals(name) || "curve".equals(name)
                && !nonMonetaryMetrics.contains(curveMetric(args)))) {
            return JSONObject.of("source", "查数权限", "reason", AssistantAggregateRequest.HIDDEN_AMOUNT);
        }
        JSONObject result = switch (name) {
            case "sessions" -> queries.sessions(platform, uid, args.getIntValue("days"), args.getIntValue("limit"));
            case "session" -> queries.session(platform, uid, args.getLongValue("start"));
            case "curve" -> queries.curve(platform, uid, args.getLongValue("start"), args.getString("metric"), args.getIntValue("maxPoints", 120));
            case "danmu" -> queries.danmu(platform, uid, args.getLongValue("start"), args.getLongValue("fromAt"), args.getLongValue("toAt"));
            case "revenue" -> queries.revenue(platform, uid, args.getLongValue("start"));
            case "insights" -> queries.insights(platform, uid, args.getIntValue("days"));
            case "trend" -> queries.trend(platform, uid, args.getString("period"), args.getIntValue("days"));
            case "fans" -> queries.fans(platform, uid, args.getIntValue("days"));
            default -> throw new IllegalArgumentException();
        };
        return context.amountVisible() ? result : (JSONObject) withoutAmounts(result, nonMonetaryMetrics);
    }

    private static String curveMetric(JSONObject args) {
        String metric = args.getString("metric");
        return metric == null || metric.isBlank() ? "online_count" : metric;
    }

    private static Object withoutAmounts(Object value, Set<String> nonMonetaryMetrics) {
        if (value instanceof Map<?, ?> fields) {
            var result = new JSONObject();
            fields.forEach((key, child) -> {
                String name = key.toString();
                switch (name) {
                    case "revenue", "amount", "totalAmount", "titles" -> { }
                    case "metrics", "peaks" -> result.put(name, countMetrics(child, nonMonetaryMetrics));
                    default -> result.put(name, withoutAmounts(child, nonMonetaryMetrics));
                }
            });
            return result;
        }
        if (value instanceof Collection<?> items) {
            var result = new JSONArray();
            items.forEach(item -> result.add(withoutAmounts(item, nonMonetaryMetrics)));
            return result;
        }
        return value;
    }

    private static Object countMetrics(Object value, Set<String> nonMonetaryMetrics) {
        if (value instanceof Map<?, ?> metrics) {
            var result = new JSONObject();
            metrics.forEach((key, metric) -> {
                if (nonMonetaryMetrics.contains(key)) result.put(key.toString(), metric);
            });
            return result;
        }
        if (value instanceof JSONArray descriptions) {
            var result = new JSONArray();
            descriptions.forEach(item -> {
                if (item instanceof JSONObject metric && !metric.getBooleanValue("money")
                        && nonMonetaryMetrics.contains(metric.getString("key"))) result.add(metric);
            });
            return result;
        }
        return null;
    }
}
