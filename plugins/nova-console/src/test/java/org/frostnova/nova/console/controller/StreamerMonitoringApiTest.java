package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamerMonitoringApiTest {
    @TempDir Path dir;
    final Instant now = Instant.parse("2026-10-21T04:00:00Z");
    final long today = Instant.parse("2026-10-20T22:00:00Z").toEpochMilli();
    final long day = 86400000L;
    NovaCoreProperties properties;
    DefaultLiveDataService storage;
    StreamerController controller;
    LiveSessionArchive archive;

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        properties.getLive().setSaveLiveData(true);
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        storage = new DefaultLiveDataService(properties);
        var live = new CompositeLiveDataService(storage, mock(TotalDataStorage.class));
        var data = mock(AbstractDataSource.class);
        var user = new PushUser(); user.setPlatform("sample"); user.setUid(1L); user.setEnabled(true);
        when(data.getAllUsers()).thenReturn(List.of(user));
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(i -> java.util.stream.Stream.empty());
        archive = new LiveSessionArchive(properties);
        controller = new StreamerController(data, new StreamerDirectory(properties), live, archive,
                new StreamerSnapshotArchive(properties), new LiveDetailArchive(properties), catalogs,
                mock(ObjectProvider.class), Clock.fixed(now, ZoneOffset.UTC));
    }

    @AfterEach void close() { storage.onContextClosedEvent(); }

    void watch(long from, long to) {
        storage.recordMonitoring("sample", 1L, 2L, from, true);
        storage.recordMonitoring("sample", 1L, 2L, to, false);
    }
    JSONObject trend(String period) {
        return JSON.parseObject(JSON.toJSONString(controller.rangeTrend("sample", 1L, period, "7").getBody()))
                .getJSONObject("trend");
    }
    JSONObject lastDay() { return trend("day").getJSONArray("buckets").getJSONObject(6); }
    JSONObject insights() { return controller.insights("sample", 1L, "7").getBody().getJSONObject("insights"); }
    JSONObject monitoring(JSONObject parent) {
        var value = parent.getJSONObject("monitoring");
        assertNotNull(value, "monitoring must expose recording and elapsed evidence");
        return value;
    }

    // 今天刚过六小时，分母不能取完整的二十四小时；末根也必须截到全页止点。
    @Test void todayUsesElapsedSixHours() {
        watch(today, today + day);
        watch(today + 3600000, today + 10800000);
        var bucket = lastDay();
        var value = monitoring(bucket);
        assertEquals(21600d, value.getDoubleValue("elapsedSeconds"));
        assertEquals(21600d, value.getDoubleValue("watchedSeconds"));
        assertEquals(now.toEpochMilli(), bucket.getLongValue("end"));
        assertTrue(value.getBooleanValue("recording"));
        System.out.println("MONITORING_TREND_SAMPLE: " + bucket);
        for (String period : List.of("week", "month"))
            assertFalse(trend(period).getJSONArray("buckets").getJSONObject(0).containsKey("monitoring"));
    }

    // 同样的空区间，要分出没开存盘、磁盘暂停和正常记录但没监看到的零覆盖。
    @Test void disabledAndEmptyAreDifferent() {
        properties.getLive().setSaveLiveData(false);
        assertEquals(Boolean.FALSE, monitoring(lastDay()).getBoolean("recording"));
        assertEquals(Boolean.FALSE, monitoring(insights()).getBoolean("recording"));
        properties.getLive().setSaveLiveData(true);
        assertEquals(Boolean.TRUE, monitoring(lastDay()).getBoolean("recording"));
        assertEquals(0d, monitoring(lastDay()).getDoubleValue("watchedSeconds"));
        ReflectionTestUtils.setField(storage, "diskSuspended", true);
        assertEquals(Boolean.FALSE, monitoring(lastDay()).getBoolean("recording"));
        assertEquals(Boolean.FALSE, monitoring(insights().getJSONObject("previous")).getBoolean("recording"));
    }

    // 三种日状态都从日历读取；监看完整却有断线的日期仍须说不准。
    @Test void dailyStatusesMatchCalendar() {
        watch(today - 3 * day, now.toEpochMilli());
        storage.recordRoomOutage("sample", 1L, today - 2 * day + 1000, today - 2 * day + 2000);
        archive.append(new LiveSession("sample", 1L, "sample", 2L, today + 1000, today + 2000, 1, Map.of(), Map.of()));
        var calendar = JSON.parseObject(JSON.toJSONString(controller.calendar("sample", 1L, "7").getBody()))
                .getJSONObject("calendar").getJSONArray("items");
        var buckets = trend("day").getJSONArray("buckets");
        Set<String> statuses = new HashSet<>();
        for (int i = 0; i < buckets.size(); i++) {
            assertEquals(calendar.getJSONObject(i).getString("status"), buckets.getJSONObject(i).getString("status"), "same date must use calendar status");
            statuses.add(buckets.getJSONObject(i).getString("status"));
        }
        assertEquals(Set.of("recorded", "off", "unknown"), statuses);
    }

    // 在播而开播时刻缺失时，日历会撤销确认没播；趋势不能漏掉这一修正。
    @Test void unknownOngoingStartMatchesCalendar() {
        watch(today - 6 * day, now.toEpochMilli());
        storage.setLiveStatus("sample", 1L, true);
        assertEquals("unknown", lastDay().getString("status"));
        storage.setLiveStartTime("sample", 1L, today + 1000);
        assertEquals("recorded", lastDay().getString("status"));
    }

    // 上期的监看区间单独裁剪；上期同进度止点之后的证据不得混入。
    @Test void previousUsesItsOwnRange() {
        watch(today - 6 * day, now.toEpochMilli());
        watch(today - 13 * day, today - 13 * day + 3600000);
        watch(now.toEpochMilli() - 7 * day, today - 6 * day);
        var data = insights();
        var current = monitoring(data);
        var previous = monitoring(data.getJSONObject("previous"));
        assertEquals(540000d, current.getDoubleValue("watchedSeconds"));
        assertEquals(3600d, previous.getDoubleValue("watchedSeconds"));
        assertEquals(540000d, previous.getDoubleValue("elapsedSeconds"));
        assertEquals(7, previous.getIntValue("rangeDays"));
        System.out.println("MONITORING_INSIGHTS_SAMPLE: " + JSONObject.of("monitoring", current, "previous", JSONObject.of("monitoring", previous)));
    }

    // 最早可证明时刻在范围中间时，可观察天数不能直接填范围天数；无证据必须未知。
    @Test void observableDaysUseCalendarProof() {
        assertNull(monitoring(insights()).get("observableDays"));
        watch(today - 3 * day, now.toEpochMilli());
        storage.recordRoomOutage("sample", 1L, today - 2 * day, today - 2 * day + 1000);
        var value = monitoring(insights());
        assertEquals(2.3, value.getDoubleValue("observableDays"));
        assertEquals(7, value.getIntValue("rangeDays"));
        assertEquals(0d, monitoring(insights().getJSONObject("previous")).getDoubleValue("observableDays"));
    }
}
