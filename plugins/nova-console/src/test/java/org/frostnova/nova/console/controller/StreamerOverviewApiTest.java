package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.report.analytics.BilibiliLiveMetricCatalog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StreamerOverviewApiTest {
    @TempDir Path dir;
    MockMvc mvc;
    StreamerController controller;
    LiveSessionArchive archive;
    StreamerSnapshotArchive snapshots;
    LiveDataService live;
    final Instant now = Instant.parse("2026-10-16T04:00:00Z");
    final long boundary = StatisticsDay.startOf(LocalDate.parse("2026-10-16")).toEpochMilli();

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() {
        var props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        props.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        var source = mock(AbstractDataSource.class);
        var user = new PushUser();
        user.setPlatform("bilibili"); user.setUid(1L); user.setEnabled(true);
        when(source.getAllUsers()).thenReturn(List.of(user));
        live = mock(LiveDataService.class);
        archive = new LiveSessionArchive(props);
        snapshots = new StreamerSnapshotArchive(props);
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(i -> java.util.stream.Stream.of(new BilibiliLiveMetricCatalog()));
        controller = new StreamerController(source, new StreamerDirectory(props), live, archive, snapshots,
                new LiveDetailArchive(props), catalogs, mock(ObjectProvider.class), Clock.fixed(now, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    void session(long start, long end) {
        archive.append(new LiveSession("bilibili", 1L, "sample", 2L, start, end, (end-start)/1000,
                Map.of("danmu_count", 5d), Map.of()));
    }
    JSONObject read(String endpoint, String... params) throws Exception {
        var request = get("/config/api/streamers/bilibili/1/" + endpoint);
        for (int i=0; i<params.length; i+=2) request.param(params[i], params[i+1]);
        return JSON.parseObject(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @ParameterizedTest @ValueSource(strings = {"insights", "audience", "calendar", "fans", "trend"})
    @DisplayName("180和360天在所有范围接口生效且拒绝其他值")
    void extendedRanges(String endpoint) throws Exception {
        for (int days : List.of(180, 360)) {
            long from = StatisticsDay.startOf(LocalDate.parse("2026-10-16").minusDays(days-1)).toEpochMilli();
            session(from, from+60000);
            snapshots.append(new StreamerSnapshot("bilibili",1L,"sample",from,Map.of("fans",100d)));
            var body = read(endpoint, "days", ""+days);
            assertEquals(days, body.getIntValue("days"), endpoint);
            if (List.of("insights","audience").contains(endpoint))
                assertEquals(from, body.getJSONObject(endpoint).getLongValue("from"));
            if (endpoint.equals("calendar")) {
                assertEquals(days, body.getJSONObject(endpoint).getJSONArray("items").size());
                assertEquals(from, body.getJSONObject(endpoint).getJSONArray("items").getJSONObject(0).getLongValue("start"));
            }
            if (endpoint.equals("fans"))
                assertEquals(from,body.getJSONObject(endpoint).getJSONArray("points").getJSONObject(0).getLongValue("at"));
            if (endpoint.equals("trend")) {
                assertEquals(days,body.getJSONObject(endpoint).getJSONArray("buckets").size());
                assertEquals(from,body.getJSONObject(endpoint).getJSONArray("buckets").getJSONObject(0).getLongValue("start"));
            }
            for (String invalid : List.of("1", "181", "abc", "0"))
                mvc.perform(get("/config/api/streamers/bilibili/1/"+endpoint).param("days", invalid))
                        .andExpect(status().isBadRequest());
        }
    }
    @Test @DisplayName("点统计日列出交集场次且开播列表与分页一致")
    void intersectingSessions() throws Exception {
        session(boundary-3600000, boundary+3600000);
        session(boundary+3600000, boundary+7200000);
        session(boundary-7200000, boundary-3600000);
        var first = read("sessions", "from", ""+(boundary-3600000), "to", ""+boundary).getJSONObject("sessions");
        assertEquals(1, first.getIntValue("total"));
        assertEquals(boundary-3600000, first.getJSONArray("items").getJSONObject(0).getLongValue("startTime"));
        assertEquals(2, read("sessions", "from", ""+boundary, "to", ""+(boundary+86400000)).getJSONObject("sessions").getIntValue("total"));
        var selected = read("sessions", "starts", (boundary-3600000)+","+(boundary+3600000), "page", "99", "size", "1").getJSONObject("sessions");
        assertEquals(2, selected.getIntValue("total")); assertEquals(2, selected.getIntValue("page"));
        for (String[] invalid : List.of(new String[]{"from", "abc"}, new String[]{"starts", "1,no"}, new String[]{"from", "9", "to", "8"})) {
            var request = get("/config/api/streamers/bilibili/1/sessions");
            for (int i=0;i<invalid.length;i+=2) request.param(invalid[i],invalid[i+1]);
            mvc.perform(request).andExpect(status().isBadRequest());
        }
    }
    @Test @DisplayName("日周月趋势保留范围两端空桶并排除范围外场次")
    void filledTrend() throws Exception {
        session(boundary-10*86400000L, boundary-10*86400000L+60000);
        session(boundary-86400000L, boundary-86400000L+60000);
        for (String period : List.of("day", "week", "month")) {
            var trend = read("trend", "days", "7", "period", period).getJSONObject("trend");
            var buckets = trend.getJSONArray("buckets");
            assertEquals(1, buckets.stream().mapToInt(v -> ((JSONObject)v).getIntValue("sessions")).sum());
            assertEquals(60, buckets.stream().mapToLong(v -> ((JSONObject)v).getLongValue("durationSeconds")).sum());
            if (period.equals("day")) { assertEquals(7, buckets.size()); assertEquals(0,buckets.getJSONObject(0).getIntValue("sessions")); }
            assertTrue(trend.getBooleanValue("metricsKnown"));
        }
    }
    @Test @DisplayName("粉丝曲线只给范围内点且涨幅与概况同源")
    void fansCurve() throws Exception {
        snapshots.append(new StreamerSnapshot("bilibili",1L,"sample",boundary-8*86400000L,Map.of("fans",100d)));
        snapshots.append(new StreamerSnapshot("bilibili",1L,"sample",boundary-86400000L,Map.of("fans",120d)));
        snapshots.append(new StreamerSnapshot("bilibili",1L,"sample",boundary+1000,Map.of("fans",130d)));
        snapshots.append(new StreamerSnapshot("bilibili",2L,"other",boundary+1000,Map.of("fans",900d)));
        var fans = read("fans", "days", "7").getJSONObject("fans");
        assertEquals(2,fans.getJSONArray("points").size()); assertEquals(30d,fans.getDoubleValue("change"));
        assertFalse(fans.getBooleanValue("partial"));
        assertEquals(read("insights","days","7").getJSONObject("insights").getJSONObject("fans").getDouble("change"), fans.getDouble("change"));
    }
    @Test @DisplayName("新接口对不存在主播返回404")
    void missingStreamer() throws Exception {
        for (String endpoint : List.of("calendar","fans","sessions","trend"))
            mvc.perform(get("/config/api/streamers/bilibili/99/"+endpoint)).andExpect(status().isNotFound());
    }
    @Test @DisplayName("开播日历传递本房监看证据并区分已播与未知")
    void calendarEvidence() throws Exception {
        when(live.monitoringIntervals(eq("bilibili"),eq(1L),anyLong())).thenReturn(List.of(
                new LiveGap(boundary-7*86400000L,now.toEpochMilli(),LiveGap.Reason.UNKNOWN)));
        var items = read("calendar","days","7").getJSONObject("calendar").getJSONArray("items");
        assertEquals("off",items.getJSONObject(6).getString("status"));
        assertTrue(items.getJSONObject(6).getBooleanValue("current"));
        session(boundary-3600000,boundary+3600000);
        items = read("calendar","days","7").getJSONObject("calendar").getJSONArray("items");
        for (int n : List.of(5,6)) {
            assertEquals("recorded",items.getJSONObject(n).getString("status"));
            assertEquals(3600,items.getJSONObject(n).getLongValue("effectiveSeconds"));
        }
    }
    @ParameterizedTest(name = "{displayName}：跨统计日={0}") @ValueSource(booleans = {false, true})
    @DisplayName("正在直播的已播时段不能被日历写成确认没播")
    void ongoingSession(boolean crossesDay) throws Exception {
        long start = boundary + (crossesDay ? -3600000 : 3600000);
        when(live.monitoringIntervals("bilibili",1L,now.toEpochMilli())).thenReturn(List.of(
                new LiveGap(boundary-7*86400000L,now.toEpochMilli(),LiveGap.Reason.UNKNOWN)));
        when(live.getLiveStatus("bilibili",1L)).thenReturn(Optional.of(true));
        when(live.getLiveStartTime("bilibili",1L)).thenReturn(Optional.of(start));
        assertTrue(read("live").getBooleanValue("living"));
        assertEquals(start,read("live").getLongValue("since"));
        var items = read("calendar","days","7").getJSONObject("calendar").getJSONArray("items");
        for (int i : crossesDay ? List.of(5,6) : List.of(6)) {
            var day = items.getJSONObject(i);
            assertEquals("recorded",day.getString("status"));
            assertEquals(i == 6,day.getBooleanValue("current"));
            assertEquals(List.of(start),day.getJSONArray("starts").toJavaList(Long.class));
            long from = Math.max(start,day.getLongValue("start"));
            long to = Math.min(now.toEpochMilli(),day.getLongValue("end"));
            assertEquals((to-from)/1000,day.getLongValue("effectiveSeconds"));
        }
        if (!crossesDay) assertEquals("off",items.getJSONObject(5).getString("status"));
        assertTrue(archive.find(0,Long.MAX_VALUE).isEmpty(),"进行中的场次只参与日历，不写入归档");
    }
    @ParameterizedTest(name = "{displayName}：{0}")
    @ValueSource(strings = {"missing", "archived", "zero", "future", "noArchive"})
    @DisplayName("在播起点不可用时最近归档以后不能确认没播")
    void unavailableOngoingStart(String kind) throws Exception {
        long oldStart = boundary - 4 * 86400000L + 3600000;
        long latestStart = boundary - 2 * 86400000L - 3600000;
        if (!kind.equals("noArchive")) {
            session(oldStart, oldStart + 3600000);
            session(latestStart, latestStart + 7200000);
        }
        when(live.monitoringIntervals("bilibili", 1L, now.toEpochMilli())).thenReturn(List.of(
                new LiveGap(boundary - 7 * 86400000L, now.toEpochMilli(), LiveGap.Reason.UNKNOWN)));
        when(live.getLiveStatus("bilibili", 1L)).thenReturn(Optional.of(true));
        when(live.getLiveStartTime("bilibili", 1L)).thenReturn(switch (kind) {
            case "archived" -> Optional.of(oldStart);
            case "zero" -> Optional.of(0L);
            case "future" -> Optional.of(now.toEpochMilli() + 1000);
            default -> Optional.empty();
        });
        var items = read("calendar", "days", "7").getJSONObject("calendar").getJSONArray("items");
        for (int i : List.of(5, 6)) {
            assertEquals("unknown", items.getJSONObject(i).getString("status"), "在播但起点不可用，不能确认没播");
            assertTrue(items.getJSONObject(i).getJSONArray("starts").isEmpty());
        }
        if (!kind.equals("noArchive")) {
            assertEquals("off", items.getJSONObject(1).getString("status"), "最近归档以前的判法保持不变");
            assertEquals("recorded", items.getJSONObject(4).getString("status"));
        }
    }

    @Test @DisplayName("刚下播的归档与在播起点相同只计算一次时长")
    void archivedOngoingStartIsCountedOnce() throws Exception {
        long start = boundary + 3600000;
        session(start, start + 3600000);
        when(live.getLiveStatus("bilibili", 1L)).thenReturn(Optional.of(true));
        when(live.getLiveStartTime("bilibili", 1L)).thenReturn(Optional.of(start));
        var today = read("calendar", "days", "7").getJSONObject("calendar").getJSONArray("items").getJSONObject(6);
        assertEquals("recorded", today.getString("status"));
        assertEquals(3600, today.getLongValue("effectiveSeconds"), "归档与在播不能重复累加时长");
        assertEquals(List.of(start), today.getJSONArray("starts").toJavaList(Long.class));
    }

    @ParameterizedTest(name = "{displayName}：{0}") @ValueSource(strings = {"downtime", "outage"})
    @DisplayName("日历接口接入停机和本房断线以免把中断当天标成确认没播")
    void calendarGapEvidence(String kind) throws Exception {
        when(live.monitoringIntervals("bilibili",1L,now.toEpochMilli())).thenReturn(List.of(
                new LiveGap(boundary-7*86400000L,now.toEpochMilli(),LiveGap.Reason.UNKNOWN)));
        var gap = new LiveGap(boundary+1000,boundary+4000,LiveGap.Reason.UNKNOWN);
        if (kind.equals("downtime")) when(live.downtimeIntervals(0,now.toEpochMilli())).thenReturn(List.of(gap));
        else when(live.roomOutageIntervals("bilibili",1L,0,now.toEpochMilli())).thenReturn(List.of(gap));
        var today = read("calendar","days","7").getJSONObject("calendar").getJSONArray("items").getJSONObject(6);
        assertEquals("unknown",today.getString("status"));
        session(boundary,now.toEpochMilli());
        today = read("calendar","days","7").getJSONObject("calendar").getJSONArray("items").getJSONObject(6);
        assertEquals("recorded",today.getString("status"));
        assertEquals(3,today.getLongValue("gapSeconds"));
    }
    @Test @DisplayName("新增主播接口沿用登录保护")
    void authentication() throws Exception {
        var auth = mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class);
        when(auth.isEnabled()).thenReturn(true);
        var filter = new org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter("sample-token",
                new org.frostnova.nova.core.util.IpMatcher(List.of("0.0.0.0/0", "::/0")), auth,
                new NovaCoreProperties.ConfigUi.Auth(), new NovaCoreProperties.ConfigUi.Agreement());
        var secured = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build();
        for (String endpoint : List.of("calendar","fans","sessions","trend"))
            secured.perform(get("/config/api/streamers/bilibili/1/"+endpoint)).andExpect(status().isUnauthorized());
    }
    @Test @DisplayName("无粉丝快照时不编造涨幅并输出接口样例")
    void samples() throws Exception {
        var empty = read("fans","days","7").getJSONObject("fans");
        assertTrue(empty.containsKey("change")); assertNull(empty.get("change")); assertTrue(empty.getBooleanValue("partial"));
        var calendar = read("calendar","days","7").getJSONObject("calendar");
        assertTrue(calendar.containsKey("provableFrom")); assertNull(calendar.get("provableFrom"));
        session(boundary-3600000,boundary+3600000);
        snapshots.append(new StreamerSnapshot("bilibili",1L,"sample",boundary-8*86400000L,Map.of("fans",100d)));
        snapshots.append(new StreamerSnapshot("bilibili",1L,"sample",boundary+1000,Map.of("fans",130d)));
        for (String endpoint : List.of("calendar","fans","sessions","trend","insights")) {
            var sample = read(endpoint,"days","7");
            System.out.println("OVERVIEW_SAMPLE " + endpoint + " " + JSON.toJSONString(sample,com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls));
        }
    }

}
