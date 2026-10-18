package org.frostnova.nova.bilibili.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliDanmuEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliSuperChatEvent;
import org.frostnova.nova.bilibili.model.BilibiliUserInfo;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 重连后尽力补充统计和原文，不发布实时事件。 */
@Slf4j
@NovaComponent
public class BilibiliGapRecovery implements AutoCloseable {
    private static final String PLATFORM = "bilibili";
    private final BilibiliApiUtil api;
    private final NovaBilibiliProperties properties;
    private final LiveDataService data;
    private final BilibiliLiveStatsAggregator stats;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "bilibili-gap-recovery");
                thread.setDaemon(true);
                return thread;
            });

    public BilibiliGapRecovery(BilibiliApiUtil api, NovaBilibiliProperties properties,
                               LiveDataService data, BilibiliLiveStatsAggregator stats) {
        this.api = api;
        this.properties = properties;
        this.data = data;
        this.stats = stats;
        executor.allowCoreThreadTimeOut(true);
    }

    public CompletableFuture<Void> recover(LiveStreamerInfo source, Instant from, Instant to) {
        if (!properties.getLive().isReconnectRecovery()
                || !data.getLiveStatus(PLATFORM, source.getUid()).orElse(false)) {
            return CompletableFuture.completedFuture(null);
        }
        Long start = data.getLiveStartTime(PLATFORM, source.getUid()).orElse(null);
        if (start == null || !to.isAfter(from)) {
            return CompletableFuture.completedFuture(null);
        }
        Set<?> snapshot = stats.snapshotDanmus(source);
        try {
            return CompletableFuture.runAsync(() -> {
                if (!current(source, start) || !properties.getLive().isReconnectRecovery()) { return; }
                Result danmu = recoverDanmus(source, from, to, start, snapshot);
                Result chat = recoverChats(source, from, to, start);
                log.info("直播间 {} 断线补录 from={} to={} seconds={} danmu={} superChat={}",
                        source.getRoomId(), from, to, Duration.between(from, to).toMillis() / 1000.0, danmu, chat);
            }, executor);
        } catch (RejectedExecutionException e) {
            log.info("直播间 {} 断线补录失败 from={} to={} seconds={} reason=executor-unavailable",
                    source.getRoomId(), from, to, Duration.between(from, to).toMillis() / 1000.0);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class Result {
        int received;
        final Set<String> idFields = new TreeSet<>();
        final Set<String> fields = new TreeSet<>();
        int inGap;
        int duplicates;
        int recovered;
        int invalid;
        Boolean earliestBeforeGap;
        String failure;

        @Override
        public String toString() {
            return "{received=" + received + ", idFields=" + idFields + ", fields=" + fields + ", inGap=" + inGap
                    + ", duplicates=" + duplicates + ", recovered=" + recovered + ", invalid=" + invalid
                    + ", earliestBeforeGap=" + earliestBeforeGap + ", reason=" + (failure == null ? "ok" : "失败:" + failure) + "}";
        }
    }

    private Result recoverDanmus(LiveStreamerInfo source, Instant from, Instant to, long start, Set<?> snapshot) {
        Result result = new Result();
        try {
            JSONArray list = api.getLiveRoomDanmuHistoryOnce(source.getRoomId());
            inspect(list, result);
            List<BilibiliDanmuEvent> eligible = new ArrayList<>();
            Instant earliest = null;
            for (Object raw : list) {
                if (!(raw instanceof JSONObject item)) { result.invalid++; continue; }
                try {
                    Instant at = parseTimeline(item.getString("timeline"));
                    if (at == null) { result.invalid++; continue; }
                    if (earliest == null || at.isBefore(earliest)) { earliest = at; }
                    if (!insideSecond(at, from, to)) { continue; }
                    String text = item.getString("text");
                    if (text == null || item.getLong("uid") == null) {
                        result.invalid++;
                        continue;
                    }
                    eligible.add(new BilibiliDanmuEvent(source,
                            new BilibiliUserInfo(item.getLong("uid"), item.getString("nickname")), text,
                            BilibiliEventParser.stripEmojiNames(text, null), at));
                } catch (RuntimeException e) {
                    result.invalid++;
                }
            }
            result.earliestBeforeGap = earliest == null ? null : earliest.getEpochSecond() < from.getEpochSecond();
            result.inGap = eligible.size();
            for (BilibiliDanmuEvent event : eligible) {
                if (!current(source, start)) { result.failure = "session-changed"; break; }
                if (event.getTimestamp() < start) { continue; }
                if (stats.recoverDanmu(event, start, snapshot)) { result.recovered++; }
                else { result.duplicates++; }
            }
        } catch (RuntimeException e) {
            result.failure = safeReason(e);
        }
        return result;
    }

    private Result recoverChats(LiveStreamerInfo source, Instant from, Instant to, long start) {
        Result result = new Result();
        try {
            JSONArray list = api.getLiveRoomSuperChatsOnce(source.getRoomId());
            inspect(list, result);
            List<BilibiliSuperChatEvent> eligible = new ArrayList<>();
            for (Object raw : list) {
                if (!(raw instanceof JSONObject item)) { result.invalid++; continue; }
                try {
                    Long id = item.getLong("id");
                    Long seconds = item.getLong("start_time");
                    if (id == null || id <= 0 || seconds == null || seconds <= 0) {
                        result.invalid++;
                        continue;
                    }
                    Instant at = Instant.ofEpochSecond(seconds);
                    if (!insideSecond(at, from, to)) { continue; }
                    JSONObject user = item.getJSONObject("user_info");
                    Double price = item.getDouble("price");
                    if (price == null || !Double.isFinite(price) || price < 0 || item.getLong("uid") == null
                            || item.getString("message") == null) {
                        result.invalid++;
                        continue;
                    }
                    BilibiliSuperChatEvent event = new BilibiliSuperChatEvent(source,
                            new BilibiliUserInfo(item.getLong("uid"), user == null ? null : user.getString("uname")),
                            item.getString("message"), price, at);
                    event.setMessageId(id);
                    event.setStartTime(at);
                    eligible.add(event);
                } catch (RuntimeException e) {
                    result.invalid++;
                }
            }
            result.inGap = eligible.size();
            for (BilibiliSuperChatEvent event : eligible) {
                if (!current(source, start)) { result.failure = "session-changed"; break; }
                if (event.getTimestamp() < start) { continue; }
                if (stats.recoverSuperChat(event, start)) { result.recovered++; }
                else { result.duplicates++; }
            }
        } catch (RuntimeException e) {
            result.failure = safeReason(e);
        }
        return result;
    }

    private boolean current(LiveStreamerInfo source, long start) {
        return data.getLiveStatus(PLATFORM, source.getUid()).orElse(false)
                && data.getLiveStartTime(PLATFORM, source.getUid()).orElse(0L) == start;
    }

    private static void inspect(JSONArray list, Result result) {
        result.received = list.size();
        for (Object raw : list) {
            if (!(raw instanceof JSONObject item)) { continue; }
            // 只记字段名，便于核实编号字段；不记录任何字段值。
            for (String key : item.keySet()) {
                if (key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                    result.fields.add(key);
                }
                if (key.matches("(?i)(id(?:_?str)?|(?:msg|message|dm|danmu)_?id(?:_?str)?)")
                        && item.get(key) != null) {
                    result.idFields.add(key);
                }
            }
        }
    }

    private static Instant parseTimeline(String text) {
        if (text == null || text.startsWith("0000-00-00")) { return null; }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean insideSecond(Instant at, Instant from, Instant to) {
        return at.getEpochSecond() >= from.getEpochSecond() && at.getEpochSecond() <= to.getEpochSecond();
    }

    private static String safeReason(RuntimeException e) {
        String message = e.getMessage();
        if (message != null && message.matches("(code=-?[0-9]+|invalid-response|invalid-list(?::(?:data|list|room)=(?:null|object|array|string|number|boolean|other))?|http-blocked)")) {
            return message;
        }
        return e.getClass().getSimpleName();
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
