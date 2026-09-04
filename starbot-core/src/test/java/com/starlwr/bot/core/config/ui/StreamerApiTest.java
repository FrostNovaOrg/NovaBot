package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.analytics.LiveMetricCatalog;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.RoomInfoSnapshot;
import com.starlwr.bot.core.model.StreamerSnapshot;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveReportArchive;
import com.starlwr.bot.core.service.LiveSessionArchive;
import com.starlwr.bot.core.service.StreamerDirectory;
import com.starlwr.bot.core.service.StreamerSnapshotArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 主播数据接口
 * <p>
 * 三位夹具主播分别落在三种状态上：在播、只采集不推送、已停用。<b>三种状态各得其一</b>
 * 是本组用例最要紧的一条——「已停用」这一档在内存里的数据源里根本不存在，
 * 名单要是问内存要的，它就永远是空的，而那时其余判据照样全绿。
 * <p>
 * 至于这些字段是不是真的从 HTTP 那一头下来（路由、三道闸、内容协商），
 * 只有真起一次产物才答得了；那一步另有安排，本组用例不假装自己答了。
 */
@DisplayName("主播数据接口")
class StreamerApiTest {
    private static final long DAY = 86_400_000L;

    @TempDir
    Path dir;

    private StarBotCoreProperties properties;

    private AbstractDataSource dataSource;

    private LiveDataService liveDataService;

    private LiveSessionArchive archive;

    private StreamerSnapshotArchive snapshots;

    private LiveReportArchive reports;

    private StreamerController controller;

    /** 今天零点，夹具的场次都按「几天前」摆放，免得跨零点跑测试时结果飘 */
    private long todayStart;

    @BeforeEach
    void setUp() throws IOException {
        properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());

        todayStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        archive = new LiveSessionArchive(properties);
        snapshots = new StreamerSnapshotArchive(properties);
        reports = new LiveReportArchive(properties);

        liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(any(), any())).thenReturn(Optional.of(false));
        when(liveDataService.getLiveStartTime(any(), any())).thenReturn(Optional.empty());

        dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        writeDatasource();
        controller = controller();
    }

    /**
     * 三位主播：1001 在播（配了通道）、1002 只采集不推送（通道在但通知全关）、1003 已停用
     */
    private void writeDatasource() throws IOException {
        Files.writeString(Path.of(properties.getDatasource().getJsonPath()), """
                [
                  {"uid": 1001, "platform": "bilibili", "enabled": true,
                   "targets": [{"platform":"qq-onebot","type":1,"num":30003,
                                "messages":[{"handler":"LiveOn","enabled":true}]}]},
                  {"uid": 1002, "platform": "bilibili", "enabled": true,
                   "targets": [{"platform":"qq-onebot","type":1,"num":30003,
                                "messages":[{"handler":"LiveOn","enabled":false}]}]},
                  {"uid": 1003, "platform": "bilibili", "enabled": false, "targets": []}
                ]
                """, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private StreamerController controller() {
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.of(catalog()));

        return new StreamerController(dataSource, new StreamerDirectory(properties), liveDataService,
                archive, snapshots, reports, catalogs);
    }

    private LiveMetricCatalog catalog() {
        return new LiveMetricCatalog() {
            @Override
            public String platform() {
                return "bilibili";
            }

            @Override
            public List<Metric> metrics() {
                return List.of(Metric.count("danmu_count", "弹幕", "条"));
            }
        };
    }

    // ---------------------------------------------------------------- 列表

    @Test
    @DisplayName("三位夹具主播的状态各得其一，字段是同一组闭集")
    void eachStreamerGetsItsOwnStatus() {
        // 1001 在播；1003 虽然直播状态也是 true，但他停用了，那是上次留下的陈值
        when(dataSource.getAllUsers()).thenReturn(List.of(user(1001L, "主播甲"), user(1002L, "主播乙")));
        when(liveDataService.getLiveStatus("bilibili", 1001L)).thenReturn(Optional.of(true));
        when(liveDataService.getLiveStatus("bilibili", 1003L)).thenReturn(Optional.of(true));
        when(liveDataService.getLiveStartTime("bilibili", 1001L)).thenReturn(Optional.of(todayStart));

        JSONArray items = controller.streamers().getJSONArray("streamers");

        assertEquals(3, items.size(), "停用的那位也要在名单里, 否则「停用」看起来就成了「删除」");
        assertEquals("LIVE", items.getJSONObject(0).getString("status"));
        assertEquals("在播", items.getJSONObject(0).getString("statusText"));
        assertEquals(todayStart, items.getJSONObject(0).getLongValue("since"));

        assertEquals("COLLECT_ONLY", items.getJSONObject(1).getString("status"));
        assertEquals("只采集不推送", items.getJSONObject(1).getString("statusText"));

        assertEquals("DISABLED", items.getJSONObject(2).getString("status"));
        assertFalse(items.getJSONObject(2).getBooleanValue("living"),
                "停用之后不再采集, 那个 true 是上次留下的陈值");
        assertNull(items.getJSONObject(2).get("since"));
    }

    @Test
    @DisplayName("算出状态的那三个事实一并给出，不只给结论")
    void statusCarriesTheFactsBehindIt() {
        when(dataSource.getAllUsers()).thenReturn(List.of(user(1001L, "主播甲")));
        when(liveDataService.getLiveStatus("bilibili", 1001L)).thenReturn(Optional.of(true));

        JSONObject first = controller.streamers().getJSONArray("streamers").getJSONObject(0);

        assertTrue(first.getBooleanValue("enabled"));
        assertTrue(first.getBooleanValue("pushing"));
        assertTrue(first.getBooleanValue("living"), "「没配通道」与「正在播」可以同时成立, 界面一行显示不下两个结论");
    }

    @Test
    @DisplayName("状态闭集随结果一起下发，界面不必自己抄一张会漏项的表")
    void exposesStatusCatalog() {
        JSONArray statuses = controller.streamers().getJSONArray("statuses");

        assertEquals(StreamerController.Status.values().length, statuses.size());
        assertEquals("LIVE", statuses.getJSONObject(0).getString("name"));
        assertEquals("在播", statuses.getJSONObject(0).getString("text"));
    }

    @Test
    @DisplayName("停用的主播不在内存里，昵称退回最近一场归档，不显示成一串 uid")
    void unameFallsBackToTheArchive() {
        archive.append(session(1003L, "夜航Yeh", todayStart - DAY, 3600));

        JSONObject disabled = controller.streamers().getJSONArray("streamers").getJSONObject(2);

        assertEquals("夜航Yeh", disabled.getString("uname"), "只显示一串 uid 的话, 使用者认不出那是谁");
        assertNull(disabled.getString("face"), "头像补全过的那份只在内存里, 没有就是没有, 不编一个");
    }

    @Test
    @DisplayName("七天折线恰好 7 点，没播的那天是 0 而不是跳过")
    void sevenDaySeriesFillsEmptyDays() {
        archive.append(session(1001L, "主播甲", todayStart + 3600_000L, 1800));
        archive.append(session(1001L, "主播甲", todayStart - 2 * DAY + 3600_000L, 600));

        JSONArray series = controller.streamers().getJSONArray("streamers").getJSONObject(0)
                .getJSONArray("series");

        assertEquals(7, series.size());
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        assertEquals(today.minusDays(6).toString(), series.getJSONObject(0).getString("date"));
        assertEquals(today.toString(), series.getJSONObject(6).getString("date"));

        assertEquals(1, series.getJSONObject(6).getIntValue("sessions"));
        assertEquals(1800, series.getJSONObject(6).getLongValue("durationSeconds"));
        assertEquals(1, series.getJSONObject(4).getIntValue("sessions"));
        assertEquals(0, series.getJSONObject(5).getIntValue("sessions"),
                "跳过没播的那天会让折线看起来比实际连贯, 而「哪几天没播」正是要看的东西");
        assertEquals(0, series.getJSONObject(5).getLongValue("durationSeconds"));
    }

    @Test
    @DisplayName("七天汇总只数窗口内的，但「最近一场」不受窗口限制")
    void summaryCountsTheWindowButLastStartDoesNot() {
        archive.append(session(1001L, "主播甲", todayStart - 30 * DAY, 7200));
        archive.append(session(1001L, "主播甲", todayStart - DAY, 600));

        JSONObject summary = controller.streamers().getJSONArray("streamers").getJSONObject(0)
                .getJSONObject("summary");

        assertEquals(7, summary.getIntValue("days"));
        assertEquals(1, summary.getIntValue("sessions"));
        assertEquals(600, summary.getLongValue("durationSeconds"));
        assertEquals(todayStart - DAY, summary.getLongValue("lastStart"));
    }

    @Test
    @DisplayName("一场没播过的主播，七天折线仍是 7 个 0，最近一场为空")
    void neverPlayedStillGetsSevenPoints() {
        JSONObject first = controller.streamers().getJSONArray("streamers").getJSONObject(0);

        assertEquals(7, first.getJSONArray("series").size());
        assertEquals(0, first.getJSONObject("summary").getIntValue("sessions"));
        assertNull(first.getJSONObject("summary").get("lastStart"));
    }

    @Test
    @DisplayName("归档里有、配置里没有的主播点名报出来，不是报个数")
    void namesTheStreamersOutsideTheConfig() {
        archive.append(session(9009L, "已删掉的主播", todayStart - DAY, 100));

        JSONArray outside = controller.streamers().getJSONArray("notConfigured");

        assertEquals(1, outside.size(), "从配置里删掉之后, 他过去的场次仍在归档里, 运营分析那页照样列得出来");
        assertEquals(9009L, outside.getJSONObject(0).getLongValue("uid"));
        assertEquals("已删掉的主播", outside.getJSONObject(0).getString("uname"));
        assertEquals(1, outside.getJSONObject(0).getIntValue("sessions"));
        assertEquals(3, controller.streamers().getJSONArray("streamers").size(), "他不混进在册名单里");
    }

    @Test
    @DisplayName("配置文件读不到时，名单退回内存里加载着的那批，不是空表")
    void rosterFallsBackToTheLoadedUsers() throws IOException {
        // 换一种数据源实现（或配置文件被挪走）时，文件是空的，
        // 而机器人明明正监听着这几位——主播页一行都没有才是真的看不出问题
        Files.delete(Path.of(properties.getDatasource().getJsonPath()));
        PushUser pushing = user(2001L, "内存甲");
        pushing.getTargets().add(target(true));
        when(dataSource.getAllUsers()).thenReturn(List.of(pushing, user(2002L, "内存乙")));

        JSONArray items = controller.streamers().getJSONArray("streamers");

        assertEquals(2, items.size());
        assertEquals("OFFLINE", items.getJSONObject(0).getString("status"));
        assertEquals("COLLECT_ONLY", items.getJSONObject(1).getString("status"),
                "内存里那位没有任何一条推送消息, 同样是「只采集不推送」");
    }

    @Test
    @DisplayName("配置文件与内存都有同一位时只出现一次，以配置文件那一份为准")
    void rosterDoesNotDuplicate() {
        when(dataSource.getAllUsers()).thenReturn(List.of(user(1001L, "主播甲"), user(1002L, "主播乙")));

        JSONArray items = controller.streamers().getJSONArray("streamers");

        assertEquals(3, items.size(), "并两处名单不该把同一位数两遍");
    }

    // ---------------------------------------------------------------- 详情

    @Test
    @DisplayName("详情三段都是实值：概况、场次、趋势")
    void detailHasAllThreeSections() {
        archive.append(session(1001L, "主播甲", todayStart - 8 * DAY, 3600));
        archive.append(session(1001L, "主播甲", todayStart - DAY, 1800));
        snapshots.append(new StreamerSnapshot("bilibili", 1001L, "主播甲", todayStart - 3600_000L,
                Map.of("fans", 12345.0)));

        JSONObject result = detail(1001L);

        JSONObject overview = result.getJSONObject("overview");
        assertEquals(2, overview.getIntValue("sessions"));
        assertEquals(5400, overview.getLongValue("durationSeconds"));
        assertEquals(2700, overview.getLongValue("averageDurationSeconds"));
        assertEquals(todayStart - 8 * DAY, overview.getLongValue("firstStart"));
        assertEquals(todayStart - DAY, overview.getLongValue("lastStart"));
        assertEquals(12345.0, overview.getJSONObject("snapshot").getJSONObject("metrics").getDoubleValue("fans"),
                "粉丝数原样在快照里, 核心不替各平台规定键名");
        assertEquals(todayStart - 3600_000L, overview.getJSONObject("snapshot").getLongValue("at"));

        JSONObject sessions = result.getJSONObject("sessions");
        assertEquals(2, sessions.getIntValue("total"));
        assertEquals(todayStart - DAY, sessions.getJSONArray("items").getJSONObject(0).getLongValue("startTime"),
                "最近的一场排在前面");

        JSONObject trend = result.getJSONObject("trend");
        assertEquals("week", trend.getString("period"));
        assertTrue(trend.getBooleanValue("metricsKnown"));
        assertTrue(trend.getJSONArray("buckets").size() >= 2, "两场分处两周, 中间的空周期也该出现");
    }

    @Test
    @DisplayName("一场都没有时概况给 null 而不是 0：「场均 0 分钟」读起来像每场都秒退")
    void emptyOverviewDoesNotSayZero() {
        JSONObject overview = detail(1001L).getJSONObject("overview");

        assertEquals(0, overview.getIntValue("sessions"));
        assertNull(overview.get("averageDurationSeconds"));
        assertNull(overview.get("snapshot"), "从来没采到过时给 null, 空对象在界面上会渲成「粉丝 0」");
    }

    @Test
    @DisplayName("场次分页：页码越界夹回最后一页，每页条数有上限")
    void sessionPagingClampsOutOfRange() {
        for (int i = 0; i < 5; i++) {
            archive.append(session(1001L, "主播甲", todayStart - i * DAY, 60L * (i + 1)));
        }

        JSONObject page1 = detail(1001L, "week", 1, 2).getJSONObject("sessions");
        assertEquals(5, page1.getIntValue("total"));
        assertEquals(3, page1.getIntValue("pages"));
        assertEquals(2, page1.getJSONArray("items").size());

        JSONObject page3 = detail(1001L, "week", 3, 2).getJSONObject("sessions");
        assertEquals(1, page3.getJSONArray("items").size(), "最后一页只有一条");

        JSONObject beyond = detail(1001L, "week", 99, 2).getJSONObject("sessions");
        assertEquals(3, beyond.getIntValue("page"), "越界夹回最后一页, 回空表会与「这一页恰好没场次」分不开");
        assertEquals(1, beyond.getJSONArray("items").size());

        JSONObject zero = detail(1001L, "week", 0, 0).getJSONObject("sessions");
        assertEquals(1, zero.getIntValue("page"));
        assertEquals(20, zero.getIntValue("size"), "没传每页条数时用默认值");
    }

    @Test
    @DisplayName("场次行带着缺口两项与结束原因，且两项缺口分开给不相加")
    void sessionRowsCarryGapsSeparately() {
        archive.append(new LiveSession("bilibili", 1001L, "主播甲", 20002L,
                todayStart - DAY, todayStart - DAY + 3600_000L, 3600,
                Map.of("danmu_count", 106.0), Map.of(), LiveEndReason.ROOM_LOCK,
                List.of(new RoomInfoSnapshot(todayStart - DAY, "标题", "分区")), 120));

        JSONObject item = detail(1001L).getJSONObject("sessions").getJSONArray("items").getJSONObject(0);

        assertEquals(120, item.getLongValue("maintenanceGapSeconds"));
        assertEquals(0, item.getLongValue("roomOutageSeconds"));
        assertEquals("ROOM_LOCK", item.getString("endReason"));
        assertTrue(item.getBooleanValue("interrupted"));
        assertEquals(106.0, item.getJSONObject("metrics").getDoubleValue("danmu_count"));
        assertFalse(item.getBooleanValue("hasUserSets"), "老记录没有名单, 空不等于零观众");
    }

    @Test
    @DisplayName("趋势按月看时口径跟着换")
    void trendFollowsThePeriod() {
        archive.append(session(1001L, "主播甲", todayStart - 40 * DAY, 3600));
        archive.append(session(1001L, "主播甲", todayStart - DAY, 3600));

        assertEquals("month", detail(1001L, "month", 1, 0).getJSONObject("trend").getString("period"));
        assertEquals("week", detail(1001L, "什么周期", 1, 0).getJSONObject("trend").getString("period"),
                "认不出的周期名退回按周, 与运营分析同一条口径");
    }

    @Test
    @DisplayName("既不在配置里也没有归档过的主播回 404 加一句人话")
    void unknownStreamerIs404() {
        ResponseEntity<JSONObject> response = controller.streamer("bilibili", 8888L, "week", 1, 0);

        assertEquals(404, response.getStatusCode().value());
        assertFalse(response.getBody().getBooleanValue("success"));
        assertTrue(response.getBody().getString("message").contains("没有这位主播的记录"),
                "回一份空壳会让「uid 打错一位」看起来像「这位主播还没播过」");
    }

    @Test
    @DisplayName("从配置里删掉但有归档的主播，详情照样打得开，且标明不在册")
    void removedStreamerStillHasDetail() {
        archive.append(session(9009L, "已删掉的主播", todayStart - DAY, 100));

        JSONObject result = detail(9009L);

        assertTrue(result.getBooleanValue("success"));
        assertFalse(result.getBooleanValue("configured"), "历史数据不会因为不再监听就失去意义");
        assertEquals(1, result.getJSONObject("overview").getIntValue("sessions"));
    }

    // ---------------------------------------------------------------- 场次报告

    @Test
    @DisplayName("有留档时回 200 与 PNG 原字节")
    void reportReturnsPng() {
        byte[] png = "一张报告图".getBytes(StandardCharsets.UTF_8);
        reports.store("bilibili", 1001L, todayStart, png, true);

        ResponseEntity<?> response = controller.report("bilibili", 1001L, todayStart);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertArrayEquals(png, (byte[]) response.getBody());
    }

    @Test
    @DisplayName("没留档时回 404 加一句人话，不是 500 也不是一张空图")
    void reportMissingIs404() {
        ResponseEntity<?> response = controller.report("bilibili", 1001L, todayStart);

        assertEquals(404, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertTrue(((JSONObject) response.getBody()).getString("message").contains("没有留下报告图"));
    }

    @Test
    @DisplayName("场次行自己说得出点不点得开")
    void sessionRowSaysWhetherItHasReport() {
        archive.append(session(1001L, "主播甲", todayStart - DAY, 3600));
        archive.append(session(1001L, "主播甲", todayStart - 2 * DAY, 3600));
        reports.store("bilibili", 1001L, todayStart - DAY, "图".getBytes(StandardCharsets.UTF_8), true);

        JSONArray items = detail(1001L).getJSONObject("sessions").getJSONArray("items");

        assertTrue(items.getJSONObject(0).getBooleanValue("hasReport"));
        assertFalse(items.getJSONObject(1).getBooleanValue("hasReport"),
                "配了下播报告也可能画失败改发了文字版, 那一场就是没有图");
    }

    // ---------------------------------------------------------------- 夹具

    private JSONObject detail(long uid) {
        return detail(uid, "week", 1, 0);
    }

    private JSONObject detail(long uid, String period, int page, int size) {
        ResponseEntity<JSONObject> response = controller.streamer("bilibili", uid, period, page, size);
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private PushUser user(long uid, String uname) {
        PushUser pushUser = new PushUser();
        pushUser.setUid(uid);
        pushUser.setUname(uname);
        pushUser.setRoomId(uid * 10);
        pushUser.setFace("https://example.invalid/" + uid + ".jpg");
        pushUser.setPlatform("bilibili");
        pushUser.setEnabled(true);
        return pushUser;
    }

    private PushTarget target(boolean withMessage) {
        PushTarget pushTarget = new PushTarget();
        pushTarget.setPlatform("qq-onebot");
        pushTarget.setType(PushTargetType.GROUP);
        pushTarget.setNum(30003L);
        if (withMessage) {
            PushMessage message = new PushMessage();
            message.setHandler("LiveOn");
            pushTarget.getMessages().add(message);
        }
        return pushTarget;
    }

    private LiveSession session(long uid, String uname, long startTime, long durationSeconds) {
        return new LiveSession("bilibili", uid, uname, uid * 10, startTime,
                startTime + durationSeconds * 1000, durationSeconds,
                Map.of("danmu_count", 106.0), Map.of());
    }
}
