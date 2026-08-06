package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.RoomInfoSnapshot;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 直播场次归档
 * <p>
 * 每场直播结束时追加一条记录。<b>这是运营分析唯一的数据来源</b>——
 * 本场数据在下次开播时会被清空，而累计数据没有时间维度，两者都答不出
 * 「上周播了几场」「这个月比上个月如何」。
 * <p>
 * 存成**每行一条 JSON**的追加式文件而不是放进 Redis，理由有三：
 * <ul>
 *     <li>不依赖外部服务，没配 Redis 的部署一样有历史数据</li>
 *     <li>追加写没有读改写周期，不会因为程序崩在中途而毁掉既有记录</li>
 *     <li>体量很小——每条约 500 字节，5 个主播每天 3 场跑一年也不过几 MB，
 *         而且人能直接看、直接导出</li>
 * </ul>
 */
@Slf4j
@Service
public class LiveSessionArchive {
    /**
     * 归档文件名，与直播数据同目录
     */
    private static final String FILE_NAME = "sessions.jsonl";

    private final StarBotCoreProperties properties;

    /**
     * 写锁。多个直播间可能同时下播，追加写虽是原子的，但仍要避免两行交错
     */
    private final Object writeLock = new Object();

    @Autowired
    public LiveSessionArchive(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 归档一场直播
     * <p>
     * <b>失败只记日志，绝不向上抛。</b>调用点在下播事件里，归档写不进去
     * 不该连累下播推送本身。
     * @param session 场次记录
     */
    public void append(@NonNull LiveSession session) {
        String line = JSON.toJSONString(session) + System.lineSeparator();

        synchronized (writeLock) {
            try {
                Files.writeString(path(), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.info("已归档 {} 的一场直播: {} 秒", session.uname(), session.durationSeconds());
            } catch (IOException e) {
                log.error("归档直播场次失败, 该场将不会出现在运营统计中", e);
            }
        }
    }

    /**
     * 读取指定时间区间内的场次
     * <p>
     * 以**开播时刻**归属区间：一场跨零点的直播算在它开始的那一天，
     * 否则同一场会在两个统计周期里各出现半截。
     * @param from 起始时刻（毫秒，含）
     * @param to 结束时刻（毫秒，不含）
     * @return 按开播时间升序的场次
     */
    public List<LiveSession> find(long from, long to) {
        Path path = path();
        List<LiveSession> result = new ArrayList<>();

        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                LiveSession session = parse(line);
                if (session != null && session.startTime() >= from && session.startTime() < to) {
                    result.add(session);
                }
            });
        } catch (NoSuchFileException e) {
            // 还没有任何一场被归档，空表即可
            return List.of();
        } catch (IOException e) {
            log.error("读取直播场次归档失败", e);
            return List.of();
        }

        result.sort((a, b) -> Long.compare(a.startTime(), b.startTime()));
        return result;
    }

    /**
     * 归档的总条数与时间范围，供界面显示「有多少可分析的数据」
     * @return 条数、最早与最晚的开播时刻；无数据时条数为 0
     */
    public Summary summary() {
        long count = 0;
        long earliest = Long.MAX_VALUE;
        long latest = Long.MIN_VALUE;

        try (Stream<String> lines = Files.lines(path(), StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                LiveSession session = parse(line);
                if (session == null) {
                    continue;
                }
                count++;
                earliest = Math.min(earliest, session.startTime());
                latest = Math.max(latest, session.startTime());
            }
        } catch (NoSuchFileException e) {
            return new Summary(0, 0, 0);
        } catch (IOException e) {
            log.error("统计直播场次归档失败", e);
            return new Summary(0, 0, 0);
        }

        return count == 0 ? new Summary(0, 0, 0) : new Summary(count, earliest, latest);
    }

    /**
     * 解析一行，坏行跳过而不是让整份归档不可用
     */
    private LiveSession parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        try {
            JSONObject json = JSON.parseObject(line);
            Map<String, Double> metrics = new HashMap<>();
            JSONObject rawMetrics = json.getJSONObject("metrics");
            if (rawMetrics != null) {
                rawMetrics.forEach((key, value) -> metrics.put(key, ((Number) value).doubleValue()));
            }

            Map<String, Integer> userCounts = new HashMap<>();
            JSONObject rawCounts = json.getJSONObject("userCounts");
            if (rawCounts != null) {
                rawCounts.forEach((key, value) -> userCounts.put(key, ((Number) value).intValue()));
            }

            return new LiveSession(
                    json.getString("platform"),
                    json.getLong("uid"),
                    json.getString("uname"),
                    json.getLong("roomId"),
                    json.getLongValue("startTime"),
                    json.getLongValue("endTime"),
                    json.getLongValue("durationSeconds"),
                    metrics,
                    userCounts,
                    parseEndReason(json.getString("endReason")),
                    parseTitles(json.getJSONArray("titles")));
        } catch (Exception e) {
            log.debug("跳过归档中无法解析的一行: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析结束原因
     * <p>
     * 4.3.0 之前归档的记录没有这一项，认不出的一律当作正常结束——
     * <b>宁可把一场被切的算成正常，也不能把正常场次误标成事故</b>，
     * 后者会让人去追查一个根本不存在的问题。
     */
    private LiveEndReason parseEndReason(String name) {
        if (name == null || name.isBlank()) {
            return LiveEndReason.NORMAL;
        }

        try {
            return LiveEndReason.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.debug("归档中出现无法识别的结束原因 {}, 按正常结束处理", name);
            return LiveEndReason.NORMAL;
        }
    }

    /**
     * 解析标题与分区轨迹，缺失或格式不符时为空表
     */
    private List<RoomInfoSnapshot> parseTitles(JSONArray raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        List<RoomInfoSnapshot> titles = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            JSONObject entry = raw.getJSONObject(i);
            if (entry == null) {
                continue;
            }
            titles.add(new RoomInfoSnapshot(entry.getLongValue("at"), entry.getString("title"), entry.getString("area")));
        }
        return titles;
    }

    /**
     * 归档文件路径，与直播数据同目录
     */
    private Path path() {
        Path liveData = Path.of(properties.getLive().getLiveDataPath());
        Path parent = liveData.getParent();
        return parent == null ? Path.of(FILE_NAME) : parent.resolve(FILE_NAME);
    }

    /**
     * 归档概况
     * @param count 已归档的场次数
     * @param earliestStart 最早一场的开播时刻，无数据时为 0
     * @param latestStart 最晚一场的开播时刻，无数据时为 0
     */
    public record Summary(long count, long earliestStart, long latestStart) {
    }
}
