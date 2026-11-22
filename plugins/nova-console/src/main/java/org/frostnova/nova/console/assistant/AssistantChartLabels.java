package org.frostnova.nova.console.assistant;

import org.frostnova.nova.core.enums.LiveEndReason;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.frostnova.nova.console.assistant.AssistantChartRequest.clean;

final class AssistantChartLabels {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:]+");
    private static final Map<String, String> METRICS = Map.ofEntries(
            Map.entry("danmu_count", "弹幕数"), Map.entry("gift_value", "礼物价值（元）"),
            Map.entry("gift_paid", "观众消耗（元）"), Map.entry("guard_value", "大航海价值（元）"),
            Map.entry("super_chat_count", "醒目留言数"), Map.entry("super_chat_value", "醒目留言价值（元）"),
            Map.entry("captain_count", "舰长人次"), Map.entry("commander_count", "提督人次"),
            Map.entry("governor_count", "总督人次"), Map.entry("follow_count", "新增关注"),
            Map.entry("like_total", "点赞次数"), Map.entry("share_count", "分享人次"),
            Map.entry("watched_count", "看过人数"), Map.entry("box_count", "盲盒个数"),
            Map.entry("box_value", "盲盒价值（元）"), Map.entry("box_profit", "盲盒盈亏（元）"),
            Map.entry("free_gift_count", "免费礼物个数"), Map.entry("guard_open_count", "大航海新开人次"),
            Map.entry("guard_renew_count", "大航海续费人次"), Map.entry("image_degraded_count", "推送图片未送达条数"),
            Map.entry("enter_count", "进房人次"), Map.entry("fans_at_start", "开播时粉丝数"),
            Map.entry("fans_medal_at_start", "开播时粉丝团人数"), Map.entry("guard_at_start", "开播时大航海人数"),
            Map.entry("online_count", "在线人数"), Map.entry("online_rank_count", "高能用户数"));
    private static final Map<String, String> USER_METRICS = Map.of(
            "danmu_users", "发弹幕", "gift_users", "送礼", "revenue_users", "贡献流水",
            "super_chat_users", "发醒目留言", "super_chat_users_count", "发醒目留言",
            "box_users", "送盲盒", "box_profit_users", "有盲盒盈亏", "guard_users", "上舰",
            "enter_users", "进房", "like_users", "点赞");
    private static final Map<String, String> LABELS = labels();
    private static final Map<String, String> NORMALIZED_LABELS = LABELS.entrySet().stream().collect(
            Collectors.toUnmodifiableMap(entry -> normalize(entry.getKey()), Map.Entry::getValue));
    private static final Map<String, String> OPERATIONS = Map.of("sum", "合计", "avg", "平均", "min", "最小", "max", "最大");
    private static final Map<String, Map<String, String>> CATEGORY_LABELS = Map.ofEntries(
            Map.entry("audienceage", Map.of("new", "新观众", "returning", "老观众")),
            Map.entry("guardoperation", Map.of("open", "新开", "renew", "续费")),
            Map.entry("guardlevel", Map.of("captain", "舰长", "commander", "提督", "governor", "总督")),
            Map.entry("pkresult", Map.of("win", "胜", "loss", "负", "draw", "平", "mixed", "多种结果")),
            Map.entry("kind", Map.ofEntries(
                    Map.entry("gift", "礼物"), Map.entry("box", "盲盒"), Map.entry("guard", "上舰"),
                    Map.entry("super_chat", "醒目留言"), Map.entry("follow", "关注"), Map.entry("pk_settle", "PK 结算"),
                    Map.entry("session", "场次"), Map.entry("minute", "逐分钟记录"), Map.entry("danmu", "弹幕"),
                    Map.entry("audience_activity", "观众活动"), Map.entry("attendance", "名单到场"))),
            Map.entry("type", Map.of("DANMU", "弹幕", "EMOJI", "表情弹幕", "SUPER_CHAT", "醒目留言")),
            Map.entry("status", Map.of("recorded", "有直播", "off", "确认没播", "unknown", "说不准")),
            Map.entry("listquality", Map.of("complete", "名单完整", "anonymous", "名单含匿名",
                    "unknown", "名单完整性未知", "missing", "无名单")),
            Map.entry("endreason", Arrays.stream(LiveEndReason.values()).collect(
                    Collectors.toUnmodifiableMap(LiveEndReason::name, LiveEndReason::getDescription))));
    private static final Map<String, String> CATEGORY_LABELS_BY_VALUE = categoryLabelsByValue();

    private AssistantChartLabels() {}

    private static Map<String, String> labels() {
        var labels = new HashMap<>(Map.ofEntries(
                Map.entry("day", "统计日"), Map.entry("week", "统计周"), Map.entry("month", "统计月"), Map.entry("hour", "北京时间"),
                Map.entry("weekday", "星期"), Map.entry("minute", "时间"), Map.entry("kind", "记录种类"),
                Map.entry("at", "时间"), Map.entry("startTime", "开播时间"), Map.entry("session", "场次"), Map.entry("period", "时段"),
                Map.entry("sessionCount", "场数"), Map.entry("audience", "观众人数"),
                Map.entry("item", "项目"), Map.entry("alias", "观众"), Map.entry("name", "名称"),
                Map.entry("share", "贡献占比"), Map.entry("type", "分类"),
                Map.entry("audienceAge", "观众"), Map.entry("openedGiftName", "开出礼物"), Map.entry("guardOperation", "上舰方式"),
                Map.entry("guardLevel", "上舰等级"), Map.entry("pkResult", "PK 结果"),
                Map.entry("messages", "弹幕数"), Map.entry("peakOnline", "在线峰值"), Map.entry("durationSeconds", "开播时长（秒）"),
                Map.entry("count", "条数"), Map.entry("distinctAudience", "观众人数"), Map.entry("distinctPayingAudience", "付费人数"),
                Map.entry("avg:online", "平均在线"), Map.entry("avg:watched", "平均看过人数"), Map.entry("sum:quantity", "个数合计"),
                Map.entry("online", "在线人数"), Map.entry("watched", "看过人数"), Map.entry("sessionAudience", "名单人数"),
                Map.entry("quantity", "个数"), Map.entry("amount", "金额（元）"), Map.entry("effectiveSeconds", "有效时长（秒）"),
                Map.entry("pkMyVotes", "本方票数"), Map.entry("pkOpponentVotes", "对手票数"), Map.entry("endTime", "下播时间"),
                Map.entry("revenue", "流水（元）"), Map.entry("sum:amount", "金额合计（元）"),
                Map.entry("durationHours", "开播时长（小时）"), Map.entry("effectiveHours", "有效时长（小时）"),
                Map.entry("from", "起始时间"), Map.entry("to", "截止时间"), Map.entry("start", "开始时间"), Map.entry("end", "结束时间"),
                Map.entry("metricsKnown", "指标已知"), Map.entry("sessions", "场数"), Map.entry("label", "统计时段"),
                Map.entry("dailyTotal", "统计日数"), Map.entry("dailyTruncated", "逐日数据已截断"),
                Map.entry("startsTotal", "开播场数"), Map.entry("startsTruncated", "开播时刻列表已截断"),
                Map.entry("interactionAudience", "互动人数"), Map.entry("follows", "新增关注"), Map.entry("firstRecordedAt", "首次留档时间"),
                Map.entry("composition", "观众构成"), Map.entry("composition.firstRecorded", "首次记录观众人数"),
                Map.entry("composition.returning", "回访观众人数"),
                Map.entry("coverage.complete", "名单完整场数"), Map.entry("coverage.anonymous", "名单含匿名场数"),
                Map.entry("coverage.unknown", "名单完整性未知场数"), Map.entry("coverage.missing", "无名单场数"),
                Map.entry("fans", "粉丝数"), Map.entry("fans.change", "粉丝数变化"), Map.entry("fans.fromAt", "基准快照时间"),
                Map.entry("fans.fromValue", "基准粉丝数"), Map.entry("fans.toAt", "最新快照时间"),
                Map.entry("fans.toValue", "最新粉丝数"), Map.entry("fans.partial", "粉丝快照覆盖不完整"),
                Map.entry("capture.effectiveSeconds", "有效采集时长（秒）"), Map.entry("capture.gapSeconds", "采集缺口（秒）"),
                Map.entry("capture.gapSessions", "采集有缺口的场数"),
                Map.entry("monitoring.recording", "有监听记录"), Map.entry("monitoring.watchedSeconds", "监听时长（秒）"),
                Map.entry("monitoring.elapsedSeconds", "已过时长（秒）"), Map.entry("monitoring.observableDays", "可观测天数"),
                Map.entry("monitoring.rangeDays", "查询天数"), Map.entry("comparisonNote", "对比说明"),
                Map.entry("status", "直播状态"), Map.entry("listQuality", "名单完整性"), Map.entry("endReason", "下播原因"),
                Map.entry("endReasonText", "下播原因"), Map.entry("interrupted", "直播中断"),
                Map.entry("unfinished", "尚未下播"), Map.entry("hasPeaks", "有峰值记录"), Map.entry("hasUserSets", "有观众名单"),
                Map.entry("sampleCount", "样本场数"), Map.entry("titleChangeCount", "标题变更次数"),
                Map.entry("maintenanceGapSeconds", "维护缺口（秒）"), Map.entry("roomOutageSeconds", "房间断线缺口（秒）"),
                Map.entry("value", "指标值"), Map.entry("speakers", "发言人数"),
                Map.entry("totalAmount", "流水合计（元）"), Map.entry("payingUsers", "付费人数"),
                Map.entry("gift", "礼物价值（元）"), Map.entry("superChat", "醒目留言价值（元）"), Map.entry("guard", "大航海价值（元）"),
                Map.entry("guardOpen", "大航海新开金额（元）"), Map.entry("guardRenew", "大航海续费金额（元）"),
                Map.entry("top3Share", "前三位贡献占比"), Map.entry("top10Share", "前十位贡献占比"),
                Map.entry("pointChange", "首尾粉丝数变化"), Map.entry("change", "粉丝数变化"),
                Map.entry("fromAt", "起始时间"), Map.entry("fromValue", "起始数值"),
                Map.entry("toAt", "结束时间"), Map.entry("toValue", "结束数值"), Map.entry("partial", "覆盖不完整"),
                Map.entry("windowFromAt", "查询窗口起始时间"), Map.entry("windowToAtExclusive", "查询窗口截止时间"),
                Map.entry("start_at", "开播时间"), Map.entry("end_at", "下播时间"), Map.entry("online_peak", "在线峰值"),
                Map.entry("pk_wins", "PK 胜场数"), Map.entry("pk_losses", "PK 负场数"), Map.entry("pk_draws", "PK 平场数"),
                Map.entry("gift_amount", "礼物金额（元）"), Map.entry("guard_amount", "上舰金额（元）"),
                Map.entry("session_start", "开播时间"), Map.entry("paid_amount", "实扣金额（元）"),
                Map.entry("characters", "弹幕字数"), Map.entry("first_day", "首次留档统计日")));
        METRICS.forEach((key, name) -> {
            labels.put(key, name);
            labels.put("metrics." + key, name);
            labels.put("peaks." + key + ".at", name.replace("（元）", "") + "峰值时间");
            labels.put("peaks." + key + ".value", name.contains("（元）") ? name.replace("（元）", "峰值（元）") : name + "峰值");
        });
        USER_METRICS.forEach((key, name) -> {
            labels.put("userCounts." + key, name + "人数");
            labels.put("userTimes." + key, name + "人次");
        });
        for (String field : new String[]{"durationSeconds", "effectiveSeconds", "revenue", "messages", "audience", "peakOnline"}) {
            labels.put("metricSampleCounts." + field, labels.get(field).replace("（秒）", "").replace("（元）", "") + "样本场数");
        }
        return Map.copyOf(labels);
    }

    static String translation(String text, String tool, String curveMetric) {
        String key = normalize(text);
        if ("audience".equals(key) && ("aggregate".equals(tool) || AssistantSqlRequest.NAME.equals(tool))) return "观众";
        if ("value".equals(key)) {
            if ("fans".equals(tool)) return "粉丝数";
            if ("curve".equals(tool)) return METRICS.getOrDefault(curveMetric, LABELS.get("value"));
        }
        String label = LABELS.getOrDefault(text, NORMALIZED_LABELS.get(key));
        if (label != null) return label;
        String suffix = "sessioncount";
        if (key.endsWith(suffix)) {
            String field = key.substring(0, key.length() - suffix.length());
            String name = NORMALIZED_LABELS.getOrDefault("avg:" + field, NORMALIZED_LABELS.get(field));
            if (name != null) return name + LABELS.get("sessionCount");
        }
        int separator = key.indexOf(':');
        if (separator > 0) {
            String operation = OPERATIONS.get(key.substring(0, separator));
            String field = NORMALIZED_LABELS.get(key.substring(separator + 1));
            if (operation != null && field != null) return operation + field;
        }
        return null;
    }

    static String category(String column, String text) {
        var labels = CATEGORY_LABELS.getOrDefault(normalize(column), Map.of());
        return labels.getOrDefault(text, CATEGORY_LABELS_BY_VALUE.getOrDefault(text, text));
    }

    private static Map<String, String> categoryLabelsByValue() {
        var labels = new HashMap<String, String>();
        var ambiguous = new HashSet<String>();
        for (var field : CATEGORY_LABELS.values()) {
            field.forEach((value, label) -> {
                String previous = labels.putIfAbsent(value, label);
                if (previous != null && !previous.equals(label)) ambiguous.add(value);
            });
        }
        ambiguous.forEach(labels::remove);
        return Map.copyOf(labels);
    }

    static String requireChinese(String text, String original, int max) {
        String shown = clean(text, max);
        if (IDENTIFIER.matcher(shown).matches())
            throw new AssistantChartRequest.Invalid("图上的名称要用中文：" + clean(original, Integer.MAX_VALUE));
        return shown;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace("_", "").replace(".", "");
    }
}
