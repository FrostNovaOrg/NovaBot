package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.StreamerSnapshot;
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
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 主播基础数据留档
 * <p>
 * 定时采样粉丝数等基础数据，<b>补上两场直播之间的空白</b>。此前所有数据都只在直播期间产生，
 * 于是「上周涨了多少粉」只能答成「每场开播时的粉丝数分别是多少」——
 * 一周没播就完全无从谈起，而粉丝数在没播的日子里照样在变。
 * <p>
 * 存储沿用场次归档的做法：与 {@code sessions.jsonl} 同目录的追加式 JSON 行文件。
 * 体量比场次归档还小——每条约 150 字节，5 个主播每 6 小时一次跑一年不到 2 MB。
 */
@Slf4j
@Service
public class StreamerSnapshotArchive {
    /**
     * 留档文件名，与场次归档同目录
     */
    private static final String FILE_NAME = "snapshots.jsonl";

    private final StarBotCoreProperties properties;

    /**
     * 写锁。多个主播的采样可能同时完成，追加写虽是原子的，但仍要避免两行交错
     */
    private final Object writeLock = new Object();

    @Autowired
    public StreamerSnapshotArchive(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 追加一条留档
     * <p>
     * <b>失败只记日志，绝不向上抛。</b>留档是后台采样，写不进去不该影响任何在跑的功能。
     * <p>
     * 指标为空的快照直接丢弃：接口全挂时留一条空记录，只会在趋势图上多出一个假的零点。
     * @param snapshot 快照
     */
    public void append(@NonNull StreamerSnapshot snapshot) {
        if (snapshot.metrics() == null || snapshot.metrics().isEmpty()) {
            log.debug("{} 的这次采样没有拿到任何数据, 不留档", snapshot.uname());
            return;
        }

        String line = JSON.toJSONString(snapshot) + System.lineSeparator();
        synchronized (writeLock) {
            try {
                Files.writeString(path(), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("留档 {} 的基础数据失败", snapshot.uname(), e);
            }
        }
    }

    /**
     * 读取指定时间区间内的留档
     * @param from 起始时刻（毫秒，含）
     * @param to 结束时刻（毫秒，不含）
     * @return 按采样时刻升序的留档
     */
    public List<StreamerSnapshot> find(long from, long to) {
        List<StreamerSnapshot> result = new ArrayList<>();

        try (Stream<String> lines = Files.lines(path(), StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                StreamerSnapshot snapshot = parse(line);
                if (snapshot != null && snapshot.at() >= from && snapshot.at() < to) {
                    result.add(snapshot);
                }
            });
        } catch (NoSuchFileException e) {
            // 还没有采样过，空表即可
            return List.of();
        } catch (IOException e) {
            log.error("读取基础数据留档失败", e);
            return List.of();
        }

        result.sort((a, b) -> Long.compare(a.at(), b.at()));
        return result;
    }

    /**
     * 取某位主播在指定时刻或之前最近的一条留档
     * <p>
     * 这是算涨幅的基准：「本周涨粉」= 现在的粉丝数 − 本周起点之前最近一次采样的粉丝数。
     * <b>用「之前最近的一条」而不是「区间内第一条」</b>，是因为区间开头那几天可能根本没有采样，
     * 拿区间内第一条当起点会把那几天的变化算丢。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param at 时刻（毫秒，含）
     * @return 该时刻或之前最近的一条留档，没有时为空
     */
    public Optional<StreamerSnapshot> latestBefore(@NonNull String platform, @NonNull Long uid, long at) {
        StreamerSnapshot latest = null;

        try (Stream<String> lines = Files.lines(path(), StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                StreamerSnapshot snapshot = parse(line);
                if (snapshot == null || snapshot.at() > at) {
                    continue;
                }
                if (!platform.equals(snapshot.platform()) || !uid.equals(snapshot.uid())) {
                    continue;
                }
                if (latest == null || snapshot.at() > latest.at()) {
                    latest = snapshot;
                }
            }
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            log.error("读取基础数据留档失败", e);
            return Optional.empty();
        }

        return Optional.ofNullable(latest);
    }

    /**
     * 解析一行，坏行跳过而不是让整份留档不可用
     */
    private StreamerSnapshot parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        try {
            JSONObject json = JSON.parseObject(line);
            Map<String, Double> metrics = new HashMap<>();
            JSONObject raw = json.getJSONObject("metrics");
            if (raw != null) {
                raw.forEach((key, value) -> metrics.put(key, ((Number) value).doubleValue()));
            }

            return new StreamerSnapshot(
                    json.getString("platform"),
                    json.getLong("uid"),
                    json.getString("uname"),
                    json.getLongValue("at"),
                    metrics);
        } catch (Exception e) {
            log.debug("跳过留档中无法解析的一行: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 留档文件路径，与直播数据同目录
     */
    private Path path() {
        Path liveData = Path.of(properties.getLive().getLiveDataPath());
        Path parent = liveData.getParent();
        return parent == null ? Path.of(FILE_NAME) : parent.resolve(FILE_NAME);
    }
}
