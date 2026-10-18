package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.PushUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeeklyReportCalendarTest {
    @TempDir Path dir;
    final Instant now = Instant.parse("2026-10-14T04:00:00Z");
    final long first = StatisticsDay.startOf(LocalDate.parse("2026-09-07")).toEpochMilli();
    LiveDataService live;
    WeeklyReportContentService content;
    AbstractDataSource source;
    StreamerDirectory directory;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        var archive = new LiveSessionArchive(properties);
        long start = StatisticsDay.startOf(LocalDate.parse("2026-10-05")).toEpochMilli();
        archive.append(new LiveSession("sample", 1L, "示例", 2L, start, start + 3600000, 3600, Map.of(), Map.of()));
        source = mock(AbstractDataSource.class);
        var user = new PushUser(); user.setPlatform("sample"); user.setUid(1L);
        when(source.getAllUsers()).thenReturn(List.of(user));
        directory = mock(StreamerDirectory.class); when(directory.entries()).thenReturn(List.of());
        live = mock(LiveDataService.class);
        when(live.monitoringIntervals(eq("sample"), eq(1L), anyLong())).thenAnswer(i -> List.of(gap(first, i.getArgument(2))));
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(i -> Stream.empty());
        ObjectProvider<WeeklyReportRenderer> renderers = mock(ObjectProvider.class);
        when(renderers.orderedStream()).thenAnswer(i -> Stream.empty());
        content = new WeeklyReportContentService(source, directory, archive,
                new StreamerSnapshotArchive(properties), live, catalogs, renderers);
    }

    LiveGap gap(long from, long to) { return new LiveGap(from, to, LiveGap.Reason.UNKNOWN); }
    JSONObject calendar(Instant at) {
        var data = content.preview("sample", 1L, at, false).orElseThrow().data();
        var calendar = JSON.parseObject(JSON.toJSONString(data)).getJSONObject("calendar");
        assertNotNull(calendar, "周报必须携带与图文共用的开播日历");
        return calendar;
    }

    @Test @DisplayName("周三预览仍以报告周日收尾，报告周位于35天的最后一行")
    void historicalFiveWeeks() {
        var items = calendar(now).getJSONArray("items");
        assertEquals(35, items.size());
        assertEquals("2026-09-07", items.getJSONObject(0).getString("date"));
        assertEquals("2026-10-11", items.getJSONObject(34).getString("date"));
        assertEquals("2026-10-05", items.getJSONObject(28).getString("date"));
        assertTrue(items.stream().map(o -> (JSONObject)o).noneMatch(d -> d.getBooleanValue("current")));
        var text = content.preview("sample", 1L, now, false).orElseThrow().text();
        assertTrue(text.contains("开播日历（近 5 周）：有直播 1 天、确认没播 34 天、说不准 0 天"));
        verify(live, atLeastOnce()).monitoringIntervals("sample", 1L, now.toEpochMilli());
        verify(live, atLeastOnce()).downtimeIntervals(0, now.toEpochMilli());
        verify(live, atLeastOnce()).roomOutageIntervals("sample", 1L, 0, now.toEpochMilli());
    }

    @Test @DisplayName("停机和断线时没录到开播也不能告诉群确认没播")
    void gapsRemainUnknown() {
        long tuesday = StatisticsDay.startOf(LocalDate.parse("2026-10-06")).toEpochMilli();
        long wednesday = tuesday + 86400000;
        when(live.downtimeIntervals(0, now.toEpochMilli())).thenReturn(List.of(gap(first, first + 1), gap(tuesday + 1000, tuesday + 4000)));
        when(live.roomOutageIntervals("sample", 1L, 0, now.toEpochMilli())).thenReturn(List.of(gap(first, first + 1), gap(wednesday + 1000, wednesday + 4000)));
        var items = calendar(now).getJSONArray("items");
        assertEquals("unknown", items.getJSONObject(29).getString("status"));
        assertEquals("unknown", items.getJSONObject(30).getString("status"));
        assertEquals("off", items.getJSONObject(31).getString("status"));
    }

    @Test @DisplayName("周日23点起播尚未归档，周一生成仍算周日有直播")
    void ongoingSundayIsRecorded() {
        var monday = Instant.parse("2026-10-11T23:00:00Z");
        when(live.getLiveStatus("sample", 1L)).thenReturn(Optional.of(true));
        when(live.getLiveStartTime("sample", 1L)).thenReturn(Optional.of(Instant.parse("2026-10-11T15:00:00Z").toEpochMilli()));
        assertEquals("recorded", calendar(monday).getJSONArray("items").getJSONObject(34).getString("status"));
    }

    @Test @DisplayName("在播却不知开播时刻时不能确认归档之后没播")
    void unknownOngoingStart() {
        when(live.getLiveStatus("sample", 1L)).thenReturn(Optional.of(true));
        when(live.getLiveStartTime("sample", 1L)).thenReturn(Optional.empty());
        var items = calendar(now).getJSONArray("items");
        assertEquals("recorded", items.getJSONObject(28).getString("status"));
        assertEquals("unknown", items.getJSONObject(34).getString("status"));
    }

    @Test @DisplayName("配置停用优先于内存名单，不能把残留在播状态补进日历")
    void disabledEntryWins() {
        when(directory.entries()).thenReturn(List.of(new StreamerDirectory.Entry("sample", 1L, false, false)));
        when(live.getLiveStatus("sample", 1L)).thenReturn(Optional.of(true));
        when(live.getLiveStartTime("sample", 1L)).thenReturn(Optional.of(Instant.parse("2026-10-11T15:00:00Z").toEpochMilli()));
        assertEquals("off", calendar(now).getJSONArray("items").getJSONObject(34).getString("status"));
    }
}
