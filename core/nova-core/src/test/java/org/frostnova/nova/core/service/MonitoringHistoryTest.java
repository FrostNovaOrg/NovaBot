package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.datasource.change.*;
import org.frostnova.nova.core.event.datasource.other.NovaDataSourceLoadCompleteEvent;
import org.frostnova.nova.core.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringHistoryTest {
    @TempDir Path dir;
    NovaCoreProperties properties;
    DefaultLiveDataService service;
    final long now = System.currentTimeMillis();
    @BeforeEach void setup() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        properties.getLive().setSaveLiveData(true);
        service = new DefaultLiveDataService(properties);
    }
    @AfterEach void close() { service.onContextClosedEvent(); }
    PushUser user(long room) {
        var user = new PushUser(); user.setPlatform("sample");user.setUid(1L);user.setRoomId(room);return user;
    }
    @Test @DisplayName("监看证据落盘并在重启后止于最后一次保存而不延伸到现在")
    void persistedCheckpoint() throws Exception {
        service.recordMonitoring("sample",1L,2L,now-86400000,true);
        service.saveNow(false);
        var raw = JSONObject.parseObject(Files.readString(dir.resolve("data.json")));
        long saved = raw.getLongValue("LastSaveTime");
        var restored = new DefaultLiveDataService(properties);
        try {
            restored.onApplicationReadyEvent();
            var rows = restored.monitoringIntervals("sample",1L,saved+86400000);
            assertEquals(1,rows.size());assertEquals(saved,rows.get(0).to());
            assertEquals(now-86400000,rows.get(0).from());
        } finally { restored.onContextClosedEvent(); }
    }
    @Test @DisplayName("别的房间断线不影响这位主播的未开播判断")
    void unrelatedRoom() {
        long today = StatisticsDay.startOf(StatisticsDay.dateOf(Instant.ofEpochMilli(now))).toEpochMilli();
        service.recordMonitoring("sample",1L,2L,today-86400000,true);
        service.recordRoomOutage("sample",2L,today,today+660000);
        var result = new StreamerCalendar().calculate(List.of(),service.monitoringIntervals("sample",1L,now),
                service.downtimeIntervals(0,now),service.roomOutageIntervals("sample",1L,0,now),7,
                Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));
        assertEquals("off",result.items().get(6).status());
    }
    @Test @DisplayName("超过5000条后删除的停机和监看历史不能再证明未开播")
    void boundedHistory() {
        long base = StatisticsDay.startOf(StatisticsDay.dateOf(Instant.ofEpochMilli(now)).minusDays(20)).toEpochMilli();
        properties.getLive().setSaveLiveData(false);
        service.recordMonitoring("sample",1L,2L,base,true);
        for (int i=0;i<5001;i++) {
            long start = base + (i == 0 ? 0 : 86400000L+i*1000L);
            service.recordRoomOutage("sample",1L,start,start+1);
        }
        properties.getLive().setSaveLiveData(true);
        var gaps = service.roomOutageIntervals("sample",1L,0,now);
        assertEquals(5000,gaps.size());assertEquals(base+86400000L+1000,gaps.get(0).from());
        var result = new StreamerCalendar().calculate(List.of(),service.monitoringIntervals("sample",1L,now),
                List.of(),gaps,30,Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));
        assertEquals(base+86400000L+1000,result.provableFrom());
        var previous = StatisticsDay.dateOf(Instant.ofEpochMilli(base)).toString();
        assertEquals("unknown",result.items().stream().filter(d -> d.date().equals(previous)).findFirst().orElseThrow().status());
        properties.getLive().setSaveLiveData(false);
        service.recordMonitoring("sample",1L,2L,base,false);
        for (int i=1;i<=5000;i++) {
            service.recordMonitoring("sample",1L,2L,base+i*1000L,true);
            service.recordMonitoring("sample",1L,2L,base+i*1000L+1,false);
        }
        properties.getLive().setSaveLiveData(true);
        var watched = service.monitoringIntervals("sample",1L,now);
        assertEquals(5000,watched.size());assertEquals(base+1000,watched.get(0).from());
    }
    @Test @DisplayName("监看记录超过5000条会裁剪以免数据文件无限增长")
    void monitoringCountLimit() throws Exception {
        long base = now-20*86400000L;
        properties.getLive().setSaveLiveData(false);
        for (int i=0;i<5001;i++) {
            service.recordMonitoring("sample",1L,2L,base+i*2000L,true);
            service.recordMonitoring("sample",1L,2L,base+i*2000L+1000,false);
        }
        properties.getLive().setSaveLiveData(true);
        var watched = service.monitoringIntervals("sample",1L,now);
        assertEquals(5000,watched.size());
        assertEquals(base+2000,watched.get(0).from());
        service.saveNow(false);
        var rows = JSONObject.parseObject(Files.readString(dir.resolve("data.json")))
                .getJSONObject("Monitoring:sample").getJSONArray("1");
        assertEquals(5000,rows.size());
        assertEquals(base+2000,rows.getJSONObject(0).getLongValue("from"));
    }
    @Test @DisplayName("400天前的监看记录会删除以免过期数据持续累积")
    void monitoringAgeLimit() throws Exception {
        long expired = now-401*86400000L;
        service.recordMonitoring("sample",1L,2L,expired,true);
        service.recordMonitoring("sample",1L,2L,expired+1000,false);
        service.recordMonitoring("sample",1L,2L,now-10000,true);
        service.recordMonitoring("sample",1L,2L,now-9000,false);
        service.saveNow(false);
        var rows = JSONObject.parseObject(Files.readString(dir.resolve("data.json")))
                .getJSONObject("Monitoring:sample").getJSONArray("1");
        assertEquals(1,rows.size());
        assertEquals(now-10000,rows.getJSONObject(0).getLongValue("from"));
    }
    @Test @DisplayName("频繁监看切换等待自动保存而名单变更立即保存")
    void monitoringUsesScheduledSave() throws Exception {
        service.saveNow(false);
        String before = Files.readString(dir.resolve("data.json"));
        service.recordMonitoring("sample",1L,2L,now-10000,true);
        service.recordMonitoring("sample",1L,2L,now-9000,false);
        assertEquals(before,Files.readString(dir.resolve("data.json")),"监看切换不能同步重写数据文件");
        service.saveNow(false);
        var rows = JSONObject.parseObject(Files.readString(dir.resolve("data.json")))
                .getJSONObject("Monitoring:sample").getJSONArray("1");
        assertEquals(1,rows.size());
        assertEquals(now-9000,rows.getJSONObject(0).getLongValue("to"));
        service.recordRosterEvent(new NovaDataSourceRemoveEvent(user(2),Instant.ofEpochMilli(now-1000)));
        assertNotNull(JSONObject.parseObject(Files.readString(dir.resolve("data.json"))).getJSONObject("RosterHistory:sample"));
    }
    @Test @DisplayName("加入移出及改房间号留档且移出立即结束监看")
    void rosterChanges() throws Exception {
        var user = user(2);
        service.recordRosterEvent(new NovaDataSourceLoadCompleteEvent(List.of(user),Instant.ofEpochMilli(now-10000)));
        service.recordMonitoring("sample",1L,2L,now-9000,true);
        var updated = user(3);
        service.recordRosterEvent(new NovaDataSourceUpdateEvent(user,updated,Instant.ofEpochMilli(now-5000)));
        assertEquals(now-5000,service.monitoringIntervals("sample",1L,now).get(0).to());
        service.recordMonitoring("sample",1L,3L,now-4000,true);
        service.recordRosterEvent(new NovaDataSourceRemoveEvent(updated,Instant.ofEpochMilli(now-1000)));
        var intervals = service.monitoringIntervals("sample",1L,now);
        assertEquals(now-1000,intervals.get(1).to());
        var rows = JSONObject.parseObject(Files.readString(dir.resolve("data.json"))).getJSONObject("RosterHistory:sample").getJSONArray("1");
        assertEquals(3,rows.size());assertEquals(3L,rows.getJSONObject(1).getLongValue("roomId"));
        assertFalse(rows.getJSONObject(2).getBooleanValue("configured"));
    }
}
