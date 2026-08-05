package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.LiveSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直播场次归档测试
 * <p>
 * 归档是运营分析唯一的数据源，且**一旦漏掉就永久补不回来**——
 * 本场数据会在下次开播时清空。因此边界情形要逐个钉死。
 */
@DisplayName("直播场次归档")
class LiveSessionArchiveTest {
    private static final long DAY = 86_400_000L;

    @TempDir
    Path dir;

    private LiveSessionArchive archive;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        archive = new LiveSessionArchive(properties);
    }

    @Test
    @DisplayName("没有归档文件时应返回空表而非报错")
    void emptyWhenNoFile() {
        assertTrue(archive.find(0, Long.MAX_VALUE).isEmpty());
        assertEquals(0, archive.summary().count());
    }

    @Test
    @DisplayName("归档后应能原样读回，含指标与人数")
    void appendThenRead() {
        archive.append(session(1_000_000L, 3600));

        List<LiveSession> found = archive.find(0, Long.MAX_VALUE);

        assertEquals(1, found.size());
        LiveSession s = found.get(0);
        assertEquals("测试主播", s.uname());
        assertEquals(3600, s.durationSeconds());
        assertEquals(455.0, s.metric("danmu_count"));
        assertEquals(33, s.userCount("danmu_users"));
    }

    @Test
    @DisplayName("多场应逐条追加，不覆盖既有记录")
    void appendsWithoutOverwriting() {
        archive.append(session(1_000_000L, 100));
        archive.append(session(2_000_000L, 200));
        archive.append(session(3_000_000L, 300));

        assertEquals(3, archive.find(0, Long.MAX_VALUE).size());
        assertEquals(3, archive.summary().count());
    }

    @Test
    @DisplayName("按时间区间筛选，且以开播时刻归属")
    void filtersByStartTime() {
        long base = 10 * DAY;
        archive.append(session(base, 100));
        archive.append(session(base + DAY, 100));
        archive.append(session(base + 2 * DAY, 100));

        // 左闭右开
        List<LiveSession> found = archive.find(base, base + 2 * DAY);

        assertEquals(2, found.size());
        assertEquals(base, found.get(0).startTime());
        assertEquals(base + DAY, found.get(1).startTime());
    }

    @Test
    @DisplayName("跨零点的直播应整场算在开播那一天，不被拆成两半")
    void sessionSpanningMidnightBelongsToStartDay() {
        // 23:30 开播，播到次日 01:30
        long start = 10 * DAY + 23 * 3600_000L + 1800_000L;
        archive.append(new LiveSession("bilibili", 1L, "测试主播", 2L, start,
                start + 2 * 3600_000L, 7200, Map.of(), Map.of()));

        // 按「开播那一天」查得到
        assertEquals(1, archive.find(10 * DAY, 11 * DAY).size());
        // 按「结束那一天」查不到——否则同一场会在两个周期里各算一次
        assertEquals(0, archive.find(11 * DAY, 12 * DAY).size());
    }

    @Test
    @DisplayName("结果应按开播时间升序，与写入顺序无关")
    void resultsAreSortedByStartTime() {
        archive.append(session(3_000_000L, 300));
        archive.append(session(1_000_000L, 100));
        archive.append(session(2_000_000L, 200));

        List<LiveSession> found = archive.find(0, Long.MAX_VALUE);

        assertEquals(List.of(1_000_000L, 2_000_000L, 3_000_000L),
                found.stream().map(LiveSession::startTime).toList());
    }

    @Test
    @DisplayName("坏行应被跳过，不影响其余记录")
    void skipsCorruptLines() throws Exception {
        archive.append(session(1_000_000L, 100));
        Files.writeString(dir.resolve("sessions.jsonl"), "这不是 JSON\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        archive.append(session(2_000_000L, 200));

        // 一份归档不该因为中间一行坏掉就整个不可用
        assertEquals(2, archive.find(0, Long.MAX_VALUE).size());
        assertEquals(2, archive.summary().count());
    }

    @Test
    @DisplayName("概况应给出条数与最早最晚的开播时刻")
    void summaryReportsRange() {
        archive.append(session(3_000_000L, 300));
        archive.append(session(1_000_000L, 100));

        LiveSessionArchive.Summary summary = archive.summary();

        assertEquals(2, summary.count());
        assertEquals(1_000_000L, summary.earliestStart());
        assertEquals(3_000_000L, summary.latestStart());
    }

    private LiveSession session(long startTime, long durationSeconds) {
        return new LiveSession("bilibili", 3000000000000001L, "测试主播", 2000000001L,
                startTime, startTime + durationSeconds * 1000, durationSeconds,
                Map.of("danmu_count", 455.0, "gift_value", 23.6),
                Map.of("danmu_users", 33, "gift_users", 13));
    }
}
