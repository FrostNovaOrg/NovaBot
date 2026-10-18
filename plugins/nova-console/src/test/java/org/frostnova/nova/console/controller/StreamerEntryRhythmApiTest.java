package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StreamerEntryRhythmApiTest {
    @TempDir Path dir;
    static final long UID = 19604318752096L;
    static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    StreamerController controller;
    LiveSessionArchive sessions;
    LiveDetailArchive details;
    long firstStart;

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() {
        var props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        var source = mock(AbstractDataSource.class);
        when(source.getAllUsers()).thenReturn(List.of());
        sessions = new LiveSessionArchive(props);
        details = spy(new LiveDetailArchive(props));
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(call -> java.util.stream.Stream.empty());
        controller = new StreamerController(source, new StreamerDirectory(props), mock(LiveDataService.class),
                sessions, new StreamerSnapshotArchive(props), details, catalogs, mock(ObjectProvider.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    long add(int daysAgo, int seconds, Map<Integer, Double> entries, Map<Integer, Double> online,
             List<LiveGap> relativeGaps) {
        long start = NOW.minusSeconds(daysAgo * 86400L).toEpochMilli() + 17_000;
        if (firstStart == 0) firstStart = start;
        long base = Math.floorDiv(start, 60_000) * 60_000;
        var series = new LinkedHashMap<String, Map<Long, Double>>();
        if (entries != null) series.put("enter_count", minutes(base, entries));
        series.put("online_count", minutes(base, online));
        var gaps = relativeGaps.stream().map(g -> new LiveGap(base + g.from(), base + g.to(), g.reason())).toList();
        sessions.append(new LiveSession("sample", UID, "sample", 2L, start, start + seconds * 1000L,
                seconds, Map.of(), Map.of(), LiveEndReason.NORMAL, List.of(), 0, Map.of(), 0));
        details.store(new LiveDetail(1, "sample", UID, "sample", 2L, start, start + seconds * 1000L,
                seconds, Map.of(), Map.of(), series, Map.of(), Map.of(), List.of(), List.of(), gaps, Map.of(), List.of()));
        return start;
    }

    Map<Long, Double> minutes(long base, Map<Integer, Double> values) {
        var result = new TreeMap<Long, Double>();
        values.forEach((minute, value) -> result.put(base + minute * 60_000L, value));
        return result;
    }

    JSONObject rhythm(String days) {
        var body = JSON.parseObject(JSON.toJSONString(controller.startSlots("sample", UID, days).getBody()));
        assertNotNull(body.getJSONObject("entryRhythm"), "抽屉必须收到入场节奏及样本说明");
        return body.getJSONObject("entryRhythm");
    }
    JSONObject point(JSONObject rhythm, int index) { return rhythm.getJSONArray("points").getJSONObject(index); }

    @Test @DisplayName("缺口空分钟排除、正常空分钟补零，避免小房间入场被算高")
    void gapsAndSilentMinutes() {
        for (int i = 1; i <= 3; i++) add(i, 583, Map.of(0, 12d), Map.of(),
                List.of(new LiveGap(60_000, 180_000, LiveGap.Reason.STREAM_LOSS)));
        var p = point(rhythm("90"), 0);
        assertEquals(4d, p.getDoubleValue("medianEntries"), "12 / (5 - 2) = 4，非12也非2.4");
        assertEquals(3, p.getIntValue("entriesSamples"));
        assertEquals(0d, point(rhythm("90"), 1).getDoubleValue("medianEntries"), "正常静默段应显示零");
    }

    @Test @DisplayName("先平均再取中位数，避免大房间或首分钟峰值拉高曲线")
    void segmentMeansThenMedian() {
        add(1, 283, Map.of(0, 5d, 1, 5d), Map.of(), List.of());
        add(2, 283, Map.of(0, 20d), Map.of(), List.of());
        add(3, 283, Map.of(4, 100d), Map.of(), List.of());
        var p = point(rhythm("90"), 0);
        assertEquals(4d, p.getDoubleValue("medianEntries"), "每场均值2、4、20，中位数4");
        add(4, 283, Map.of(0, 30d), Map.of(), List.of());
        assertEquals(5d, point(rhythm("90"), 0).getDoubleValue("medianEntries"), "偶数场取中间两值平均");
    }

    @Test @DisplayName("不足三场即截断，避免曲线越过无样本段重新出现")
    void stopsAtFirstInsufficientSegment() {
        for (int i = 1; i <= 3; i++) add(i, 2383, Map.of(0, 5d, 10, 10d, 30, 30d), Map.of(),
                i == 3 ? List.of(new LiveGap(300_000, 600_000, LiveGap.Reason.RESTART)) : List.of());
        var r = rhythm("90");
        assertEquals(1, r.getJSONArray("points").size());
        assertEquals(0, point(r, 0).getIntValue("minute"));
    }

    @Test @DisplayName("旧场和缺失明细不冒充零入场，范围总场次与有效场次分别显示")
    void legacySessionsAndCounts() {
        for (int i = 1; i <= 3; i++) add(i, 283, Map.of(0, 10d), Map.of(), List.of());
        add(4, 283, null, Map.of(0, 900d), List.of());
        long missing = add(5, 283, Map.of(0, 10d), Map.of(), List.of());
        try { Files.delete(dir.resolve("details/sample-" + UID + "-" + missing + "/detail.json")); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        var r = rhythm("90");
        assertEquals(5, r.getIntValue("sessions"));
        assertEquals(3, r.getIntValue("entrySessions"));
        assertEquals(3, point(r, 0).getIntValue("entriesSamples"));
    }

    @Test @DisplayName("在线取各场段内最大再取中位数，未记录在线不冒充零")
    void onlineMaximumAndIndependentSamples() {
        add(1, 283, Map.of(0, 5d), Map.of(0, 10d, 1, 100d), List.of());
        add(2, 283, Map.of(0, 5d), Map.of(3, 40d), List.of());
        add(3, 283, Map.of(0, 5d), Map.of(), List.of());
        var p = point(rhythm("90"), 0);
        assertEquals(70d, p.getDoubleValue("medianOnline"));
        assertEquals(2, p.getIntValue("onlineSamples"));
        assertEquals(3, p.getIntValue("entriesSamples"));
    }

    @Test @DisplayName("开播所在整分钟是零点，尾段只除实际直播分钟")
    void minuteAlignmentAndPartialTail() {
        for (int i = 1; i <= 3; i++) add(i, 360, Map.of(0, 5d, 5, 6d, 6, 2d, 7, 900d), Map.of(), List.of());
        var r = rhythm("90");
        assertEquals(2, r.getJSONArray("points").size());
        assertEquals(1d, point(r, 0).getDoubleValue("medianEntries"));
        assertEquals(5, point(r, 1).getIntValue("minute"));
        assertEquals(4d, point(r, 1).getDoubleValue("medianEntries"), "尾段两格有值，共8除以2");
    }

    @Test @DisplayName("完全缺口不贡献样本，已记录的零仍算有效场次")
    void fullyMissingAndExplicitZero() {
        for (int i = 1; i <= 3; i++) add(i, 283, Map.of(0, 0d), Map.of(), List.of());
        add(4, 283, Map.of(9, 8d), Map.of(), List.of(new LiveGap(0, 300_000, LiveGap.Reason.RESTART)));
        var r = rhythm("90");
        assertEquals(4, r.getIntValue("entrySessions"));
        assertEquals(3, point(r, 0).getIntValue("entriesSamples"));
        assertEquals(0d, point(r, 0).getDoubleValue("medianEntries"));
        assertNull(point(r, 0).get("medianOnline"));
    }

    @Test @DisplayName("九十与一百八十天跟随热图范围，范围外场次不抬高样本")
    void sharedRangeAndEmptyStates() {
        add(1, 283, null, Map.of(), List.of());
        var empty = rhythm("90");
        assertEquals(0, empty.getIntValue("entrySessions"));
        assertTrue(empty.getJSONArray("points").isEmpty());
        add(2, 283, Map.of(0, 5d), Map.of(), List.of());
        add(100, 283, Map.of(0, 10d), Map.of(), List.of());
        add(101, 283, Map.of(0, 15d), Map.of(), List.of());
        add(180, 283, Map.of(0, 999d), Map.of(), List.of());
        assertEquals(2, rhythm("90").getIntValue("sessions"));
        assertEquals(1, rhythm("90").getIntValue("entrySessions"));
        assertTrue(rhythm("90").getJSONArray("points").isEmpty());
        assertEquals(4, rhythm("180").getIntValue("sessions"));
        assertEquals(3, point(rhythm("180"), 0).getIntValue("entriesSamples"));
        assertEquals(2d, point(rhythm("180"), 0).getDoubleValue("medianEntries"));
    }

    @Test @DisplayName("重复打开复用分段缓存，文件变化后不显示旧值")
    void cacheReuseAndFileChange() throws Exception {
        for (int i = 1; i <= 3; i++) add(i, 283, Map.of(0, i == 1 ? 5d : i == 2 ? 2d : 10d), Map.of(), List.of());
        clearInvocations(details);
        assertEquals(1d, point(rhythm("90"), 0).getDoubleValue("medianEntries"));
        rhythm("180");
        verify(details, times(3)).read(eq("sample"), eq(UID), anyLong());
        var path = dir.resolve("details/sample-" + UID + "-" + firstStart + "/detail.json");
        var original = Files.readString(path);
        // 同大小替换也必须失效，否则修正后的明细仍显示旧读数。
        Files.writeString(path, original.replace(":5.0", ":0.0"));
        Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 2000));
        assertEquals(.4, point(rhythm("90"), 0).getDoubleValue("medianEntries"));
        verify(details, times(4)).read(eq("sample"), eq(UID), anyLong());
        Files.writeString(path, Files.readString(path).replace(":0.0", ":100.0"));
        assertEquals(2d, point(rhythm("90"), 0).getDoubleValue("medianEntries"));
        verify(details, times(5)).read(eq("sample"), eq(UID), anyLong());
        Files.delete(path);
        assertEquals(2, rhythm("90").getIntValue("entrySessions"));
        assertTrue(rhythm("90").getJSONArray("points").isEmpty());
    }

    @Test @DisplayName("三十天二百场夹具测量首次及再次打开耗时")
    void archiveTiming() throws Exception {
        var values = new TreeMap<Integer, Double>();
        for (int m = 0; m < 240; m++) values.put(m, (double) (m % 13));
        add(1, 14_383, values, values, List.of());
        var template = details.read("sample", UID, firstStart).orElseThrow();
        for (int i = 1; i < 200; i++) {
            long start = firstStart - i * 3 * 3_600_000L;
            long delta = start - firstStart;
            var series = new LinkedHashMap<String, Map<Long, Double>>();
            template.series().forEach((key, buckets) -> {
                var shifted = new TreeMap<Long, Double>();
                buckets.forEach((at, value) -> shifted.put(at + delta, value));
                series.put(key, shifted);
            });
            sessions.append(new LiveSession("sample", UID, "sample", 2L, start, start + 14_383_000,
                    14_383, Map.of(), Map.of(), LiveEndReason.NORMAL, List.of(), 0, Map.of(), 0));
            details.store(new LiveDetail(1, "sample", UID, "sample", 2L, start, start + 14_383_000,
                    14_383, Map.of(), Map.of(), series, Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of()));
        }
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        clearInvocations(details);
        double[] elapsed = new double[2];
        for (int i = 0; i < 2; i++) {
            long started = System.nanoTime();
            var raw = mvc.perform(get("/config/api/streamers/sample/" + UID + "/start-slots").param("days", "90"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            elapsed[i] = (System.nanoTime() - started) / 1_000_000d;
            var r = JSON.parseObject(raw).getJSONObject("entryRhythm");
            assertNotNull(r);
            assertEquals(200, r.getIntValue("entrySessions"));
            assertEquals(48, r.getJSONArray("points").size());
        }
        verify(details, times(200)).read(eq("sample"), eq(UID), anyLong());
        System.out.printf(Locale.ROOT, "ENTRY_RHYTHM_TIMING: rangeDays=30 sessions=200 minutesPerSession=240 firstMs=%.3f repeatMs=%.3f%n", elapsed[0], elapsed[1]);
    }
}
