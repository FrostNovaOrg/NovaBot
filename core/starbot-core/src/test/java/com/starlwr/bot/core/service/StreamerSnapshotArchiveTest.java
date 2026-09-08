package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.model.StreamerSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主播基础数据留档测试
 * <p>
 * 这份数据是「本周涨了多少粉」的唯一依据，而<b>它同样补不回来</b>——
 * 错过的那次采样对应的时刻已经过去了。重点钉的是取基准的方式：
 * 拿区间内第一条当起点，会把区间开头没采样的那几天算丢。
 */
@DisplayName("主播基础数据留档")
class StreamerSnapshotArchiveTest {
    private static final String PLATFORM = "bilibili";

    private static final Long UID = 19142561034510L;

    private static final long DAY = 86_400_000L;

    @TempDir
    Path dir;

    private StreamerSnapshotArchive archive;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        archive = new StreamerSnapshotArchive(properties);
    }

    private StreamerSnapshot snapshot(long at, double fans) {
        return new StreamerSnapshot(PLATFORM, UID, "测试主播", at, Map.of("fans", fans));
    }

    @Test
    @DisplayName("没有留档文件时应返回空表而非报错")
    void emptyWhenNoFile() {
        assertTrue(archive.find(0, Long.MAX_VALUE).isEmpty());
        assertTrue(archive.latestBefore(PLATFORM, UID, Long.MAX_VALUE).isEmpty());
    }

    @Test
    @DisplayName("留档后应能原样读回")
    void appendThenRead() {
        archive.append(snapshot(10 * DAY, 243));

        List<StreamerSnapshot> found = archive.find(0, Long.MAX_VALUE);

        assertEquals(1, found.size());
        assertEquals(243.0, found.get(0).metric("fans"));
        assertEquals("测试主播", found.get(0).uname());
    }

    @Test
    @DisplayName("一项数据都没拿到的采样不应留档，否则趋势图上会多出一个假的零点")
    void skipsEmptySnapshot() {
        archive.append(new StreamerSnapshot(PLATFORM, UID, "测试主播", 10 * DAY, Map.of()));

        assertTrue(archive.find(0, Long.MAX_VALUE).isEmpty());
    }

    @Test
    @DisplayName("取不到的项应当缺席而不是记成 0")
    void missingMetricIsAbsentNotZero() {
        archive.append(new StreamerSnapshot(PLATFORM, UID, "测试主播", 10 * DAY, Map.of("fans", 243.0)));

        StreamerSnapshot found = archive.find(0, Long.MAX_VALUE).get(0);

        assertTrue(found.has("fans"));
        assertFalse(found.has("guard"), "没采到的项不该出现在快照里");
        assertEquals(0.0, found.metric("guard"), "缺席时取值为 0，但要能与真的是 0 区分开");
    }

    @Test
    @DisplayName("区间按采样时刻筛选，左闭右开")
    void filtersByRange() {
        archive.append(snapshot(5 * DAY, 100));
        archive.append(snapshot(10 * DAY, 200));
        archive.append(snapshot(15 * DAY, 300));

        assertEquals(List.of(200.0),
                archive.find(10 * DAY, 15 * DAY).stream().map(s -> s.metric("fans")).toList());
    }

    @Test
    @DisplayName("读回时应按时刻升序，与写入顺序无关")
    void sortedByTime() {
        archive.append(snapshot(15 * DAY, 300));
        archive.append(snapshot(5 * DAY, 100));
        archive.append(snapshot(10 * DAY, 200));

        assertEquals(List.of(100.0, 200.0, 300.0),
                archive.find(0, Long.MAX_VALUE).stream().map(s -> s.metric("fans")).toList());
    }

    @Test
    @DisplayName("取基准应回溯到区间之前，而不是拿区间内第一条")
    void latestBeforeLooksBackwards() {
        // 第 5 天采过一次，之后一周没播也没采样；算「第 10 天以来涨了多少」时，
        // 基准必须是第 5 天那条，否则中间那几天的变化会被算丢
        archive.append(snapshot(5 * DAY, 100));
        archive.append(snapshot(20 * DAY, 300));

        assertEquals(100.0, archive.latestBefore(PLATFORM, UID, 10 * DAY).orElseThrow().metric("fans"));
    }

    @Test
    @DisplayName("取基准时应认准主播，不能拿别人的数据当基准")
    void latestBeforeIsPerStreamer() {
        archive.append(snapshot(5 * DAY, 100));
        archive.append(new StreamerSnapshot(PLATFORM, 999L, "别人", 8 * DAY, Map.of("fans", 9999.0)));

        assertEquals(100.0, archive.latestBefore(PLATFORM, UID, 10 * DAY).orElseThrow().metric("fans"));
        assertTrue(archive.latestBefore("douyu", UID, 10 * DAY).isEmpty());
    }

    @Test
    @DisplayName("坏行应被跳过，不该让整份留档不可用")
    void skipsMalformedLines() throws Exception {
        archive.append(snapshot(5 * DAY, 100));
        Files.writeString(dir.resolve("snapshots.jsonl"), "这不是 JSON\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        archive.append(snapshot(10 * DAY, 200));

        assertEquals(2, archive.find(0, Long.MAX_VALUE).size());
    }
}
