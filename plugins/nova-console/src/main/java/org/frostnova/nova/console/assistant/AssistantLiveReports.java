package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.service.LiveReportRedrawer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
final class AssistantLiveReports {
    static final String NAME = "draw_live_report";
    private static final String SOURCE = "直播报告";
    private static final int LIMIT = 4;
    private static final String NO_DETAIL = "没有留下这一场的明细，较早的场次无法画报告图，可以改查这场的主要数字";
    private static final String LIVE = "这场还在直播，用「直播报告」命令可以看到此刻为止的报告";
    private static final String UNAVAILABLE = "暂时无法生成这场的报告图";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(StatisticsDay.ZONE);

    record Image(long uid, boolean amountVisible, String png) { }

    private final AssistantQueryService queries;
    private final Supplier<Stream<LiveReportRedrawer>> redrawers;

    AssistantLiveReports(AssistantQueryService queries, Supplier<Stream<LiveReportRedrawer>> redrawers) {
        this.queries = queries;
        this.redrawers = redrawers;
    }

    JSONObject draw(String platform, Map<Long, String> streamers, JSONObject args, boolean qq,
                    Map<Long, PushTarget> channels, List<Image> images) {
        long uid = args.getLongValue("streamer", streamers.keySet().iterator().next());
        if (!streamers.containsKey(uid)) return rejected(AssistantTools.OUTSIDE_QUESTION);
        var channel = channels.get(uid);
        if (qq && channel == null) return rejected("找不到这位主播在当前聊天的通道");

        Long start = args.getLong("start");
        String requestedDay = args.getString("day");
        if (start != null && requestedDay != null) return rejected("请填写开播时刻或统计日其中一项");
        LocalDate day;
        try {
            day = requestedDay == null ? null : LocalDate.parse(requestedDay);
        } catch (DateTimeParseException error) {
            return rejected("统计日请用 YYYY-MM-DD 格式");
        }

        var sessions = queries.reportSessions(platform, uid, start, day);
        if (sessions.isEmpty()) return rejected(day == null ? "没有找到该场次" : "这个统计日没有开播场次");
        var redrawer = redrawers.get().filter(one -> platform.equals(one.platform())).findFirst();
        var drawn = new JSONArray();
        var skipped = new JSONArray();
        var omitted = new JSONArray();
        int remaining = LIMIT - images.size();
        if (start == null && day == null) {
            boolean found = false;
            for (var session : sessions) {
                var detail = queries.reportDetail(session);
                if (detail.isEmpty()) continue;
                if (remaining == 0) omitted.add(timing(session));
                else drawSession(session, detail, redrawer, channel, images, drawn, skipped);
                found = true;
                break;
            }
            if (!found) return rejected(sessions.get(0).endTime() <= 0 ? LIVE : NO_DETAIL);
        } else {
            for (int i = 0; i < sessions.size(); i++) {
                var session = sessions.get(i);
                if (i >= remaining) {
                    omitted.add(timing(session));
                } else {
                    drawSession(session, queries.reportDetail(session), redrawer, channel, images, drawn, skipped);
                }
            }
        }
        var result = JSONObject.of("source", SOURCE, "drawn", drawn, "skipped", skipped, "omitted", omitted,
                "drawnCount", drawn.size(), "omittedCount", omitted.size());
        if (!omitted.isEmpty()) result.put("omittedReason", "每问最多四张报告图，可以说开播时刻再要其余场次");
        return result;
    }

    private void drawSession(LiveSession session, Optional<LiveDetail> detail, Optional<LiveReportRedrawer> redrawer,
                             PushTarget channel, List<Image> images, JSONArray drawn, JSONArray skipped) {
        var time = timing(session);
        if (session.endTime() <= 0 || detail.isEmpty()) {
            time.put("reason", session.endTime() <= 0 ? LIVE : NO_DETAIL);
            skipped.add(time);
            return;
        }
        try {
            var png = redrawer.flatMap(renderer -> channel == null ? renderer.redraw(detail.get())
                    : renderer.channelImage(detail.get(), channel)).filter(bytes -> bytes.length > 0);
            if (png.isPresent()) {
                images.add(new Image(session.uid(), channel == null || Boolean.TRUE.equals(channel.getRevenueVisible()),
                        Base64.getEncoder().encodeToString(png.get())));
                drawn.add(time);
                return;
            }
        } catch (RuntimeException error) {
            log.warn("绘制历史报告失败，异常类型 {}", error.getClass().getSimpleName());
        }
        time.put("reason", UNAVAILABLE);
        skipped.add(time);
    }

    private static JSONObject timing(LiveSession session) {
        Instant start = Instant.ofEpochMilli(session.startTime());
        var time = JSONObject.of("day", StatisticsDay.dateOf(start).toString(), "startTime", session.startTime(),
                "startAt", TIME.format(start));
        if (session.endTime() > 0) time.put("endAt", TIME.format(Instant.ofEpochMilli(session.endTime())));
        return time;
    }

    private static JSONObject rejected(String reason) { return JSONObject.of("source", SOURCE, "reason", reason); }
}
