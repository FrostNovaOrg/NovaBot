package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.RoomInfoSnapshot;
import org.frostnova.nova.core.model.SeriesPeak;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        NovaCoreProperties properties = new NovaCoreProperties();
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

    @Test
    @DisplayName("停机缺口应原样存回读出")
    void keepsMaintenanceGap() {
        archive.append(new LiveSession("bilibili", 1L, "测试主播", 2L, 1_000_000L, 1_100_000L, 100,
                Map.of(), Map.of(), org.frostnova.nova.core.enums.LiveEndReason.UNCLOSED, List.of(), 42));

        LiveSession session = archive.find(0, Long.MAX_VALUE).get(0);

        assertEquals(42, session.maintenanceGapSeconds());
        assertTrue(session.hasMaintenanceGap());
    }

    @Test
    @DisplayName("4.3.0 之前的记录没有缺口字段，读成 0 且不报错")
    void oldRecordsHaveNoMaintenanceGap() throws Exception {
        // 手工写一行当年格式的记录：没有 endReason、没有 titles、没有 maintenanceGapSeconds
        Files.writeString(dir.resolve("sessions.jsonl"),
                "{\"platform\":\"bilibili\",\"uid\":1,\"uname\":\"测试主播\",\"roomId\":2,"
                        + "\"startTime\":1000000,\"endTime\":1100000,\"durationSeconds\":100,"
                        + "\"metrics\":{\"danmu_count\":455},\"userCounts\":{\"danmu_users\":33}}\n",
                StandardCharsets.UTF_8);

        LiveSession session = archive.find(0, Long.MAX_VALUE).get(0);

        assertEquals(0, session.maintenanceGapSeconds());
        assertFalse(session.hasMaintenanceGap(), "读成 0 表示「当年没在算」，不表示「保证一秒没漏」");
        assertEquals(455.0, session.metric("danmu_count"));
    }

    @Test
    @DisplayName("落盘一行的键集是对外接口，钉死")
    void lineKeySetIsTheInterface() throws Exception {
        // 一行里的键名是外部工具直接消费的接口面：给 LiveSession 加一个字段、
        // 换一种序列化配置，都会悄悄改掉这一行的形状而仓内无一处报错。
        // 这一格把现状钉死——多一个键、少一个键都红。
        archive.append(new LiveSession("bilibili", STREAMER_UID, "测试主播", ROOM_ID,
                1_000_000L, 1_003_600_000L, 3600,
                Map.of("danmu_count", 455.0),
                Map.of("danmu_users", 2),
                org.frostnova.nova.core.enums.LiveEndReason.NORMAL,
                List.of(new RoomInfoSnapshot(1_000_000L, "开播时的标题", "虚拟主播")),
                7,
                Map.of("danmu_users", List.of(19427650908284L, 19471545457530L)),
                3,
                Map.of("danmu_count", new SeriesPeak(1_002_520_000L, 34))));

        JSONObject line = JSON.parseObject(Files.readAllLines(
                dir.resolve("sessions.jsonl"), StandardCharsets.UTF_8).get(0));

        List<String> red = new ArrayList<>();
        try {
            assertEquals(Set.of("platform", "uid", "uname", "roomId", "startTime", "endTime", "durationSeconds",
                    "metrics", "userCounts", "endReason", "titles", "maintenanceGapSeconds", "userSets",
                    "roomOutageSeconds", "peaks"), line.keySet(), "① 顶层键集");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertEquals(Set.of("at", "title", "area"), line.getJSONArray("titles").getJSONObject(0).keySet(),
                    "② titles[0] 键集");
            assertEquals(Set.of("at", "value"), line.getJSONObject("peaks").getJSONObject("danmu_count").keySet(),
                    "② peaks 值键集");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    // ⚠️ 夹具一律用保留段假值。
    // 这里原本写的是真实主播 uid 与房间号——真实身份数据不进测试夹具，
    // 那正是 2026-08-12 那次泄漏的成因（「照真实场景写测试最省事」）
    private static final long STREAMER_UID = 19532742171987L;
    private static final long ROOM_ID = 47789014283216L;

    private LiveSession session(long startTime, long durationSeconds) {
        return new LiveSession("bilibili", STREAMER_UID, "测试主播", ROOM_ID,
                startTime, startTime + durationSeconds * 1000, durationSeconds,
                Map.of("danmu_count", 455.0, "gift_value", 23.6),
                Map.of("danmu_users", 33, "gift_users", 13));
    }

    /**
     * 第③批：序列峰值
     * <p>
     * 峰值是<b>派生字段</b>：从本场序列算出来的，而序列下次开播就清零。
     * 这一批钉的是「算出来的那个数真的跟着场次落了盘」，以及
     * 「本版之前的那些场次读回来是『不知道』而不是 0」。
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("序列峰值")
    class Peaks {
        @Test
        @DisplayName("峰值与时刻原样往返，多条曲线各留各的")
        void peaksRoundTrip() {
            archive.append(new LiveSession("bilibili", STREAMER_UID, "测试主播", ROOM_ID,
                    1_000_000L, 1_003_600_000L, 3600, Map.of(), Map.of(),
                    org.frostnova.nova.core.enums.LiveEndReason.NORMAL, List.of(), 0, Map.of(), 0,
                    Map.of("danmu_count", new SeriesPeak(1_002_520_000L, 34),
                            "watched_count", new SeriesPeak(1_003_500_000L, 8642))));

            LiveSession read = archive.find(0, Long.MAX_VALUE).get(0);

            assertTrue(read.hasPeaks());
            assertEquals(new SeriesPeak(1_002_520_000L, 34), read.peak("danmu_count").orElseThrow());
            assertEquals(new SeriesPeak(1_003_500_000L, 8642), read.peak("watched_count").orElseThrow());
        }

        @Test
        @DisplayName("本版之前的记录没有峰值：读成「不知道」而不是「最高 0」")
        void oldRecordsHaveNoPeaks() throws Exception {
            Files.writeString(dir.resolve("sessions.jsonl"),
                    "{\"platform\":\"bilibili\",\"uid\":1,\"uname\":\"测试主播\",\"roomId\":2,"
                            + "\"startTime\":1000000,\"endTime\":1100000,\"durationSeconds\":100,"
                            + "\"metrics\":{},\"userCounts\":{}}\n",
                    StandardCharsets.UTF_8);

            LiveSession read = archive.find(0, Long.MAX_VALUE).get(0);

            assertFalse(read.hasPeaks(),
                    "那时候的序列早就没了, 事后补不出来——界面上该显示「—」而不是 0");
            assertTrue(read.peak("danmu_count").isEmpty());
        }

        @Test
        @DisplayName("缺时刻的那一项整条跳过，不补一个 0 进去")
        void malformedPeakIsDropped() throws Exception {
            Files.writeString(dir.resolve("sessions.jsonl"),
                    "{\"platform\":\"bilibili\",\"uid\":1,\"uname\":\"测试主播\",\"roomId\":2,"
                            + "\"startTime\":1000000,\"endTime\":1100000,\"durationSeconds\":100,"
                            + "\"metrics\":{},\"userCounts\":{},"
                            + "\"peaks\":{\"danmu_count\":{\"value\":34},"
                            + "\"watched_count\":{\"at\":1050000,\"value\":8642}}}\n",
                    StandardCharsets.UTF_8);

            LiveSession read = archive.find(0, Long.MAX_VALUE).get(0);

            assertTrue(read.peak("danmu_count").isEmpty(),
                    "「峰值 34, 出现在开播那一刻」是一句会被人当真的假话");
            assertEquals(8642, read.peak("watched_count").orElseThrow().value());
        }
    }

    /**
     * 第②批：名单落盘（F5）与单房断线缺口
     * <p>
     * 两者<b>同动归档结构</b>，所以一批做完、一起测。
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("名单落盘与单房断线缺口")
    class UserSetsAndOutage {
        /** 观众 uid 一律用保留段假值，绝不拿实抓语料里的真 uid 当夹具 */
        private LiveSession withSets(Map<String, List<Long>> sets, long gap, long outage) {
            return new LiveSession("bilibili", STREAMER_UID, "测试主播", ROOM_ID,
                    1_000_000L, 1_003_600_000L, 3600,
                    Map.of("danmu_count", 455.0),
                    Map.of("danmu_users", sets.getOrDefault("danmu_users", List.of()).size()),
                    org.frostnova.nova.core.enums.LiveEndReason.NORMAL, List.of(), gap, sets, outage);
        }

        @Test
        @DisplayName("⚠️ 守卫：归档必须留下参与者名单，只留人数等于每播一场丢一场")
        void archivesUserSets() {
            archive.append(withSets(Map.of(
                    "danmu_users", List.of(19427650908284L, 19471545457530L, 19879099161950L),
                    "gift_users", List.of(19427650908284L)), 0, 0));

            LiveSession s = archive.find(0, Long.MAX_VALUE).get(0);

            assertEquals(List.of(19427650908284L, 19471545457530L, 19879099161950L), s.userSet("danmu_users"),
                    "名单必须原样读回——它一次性，这一场丢了就再也补不回来");
            assertEquals(List.of(19427650908284L), s.userSet("gift_users"));
            assertTrue(s.hasUserSets());
        }

        @Test
        @DisplayName("名单长度必须与人数对得上——对不上说明落盘漏了")
        void userSetsAgreeWithCounts() {
            archive.append(withSets(Map.of("danmu_users", List.of(19427650908284L, 19471545457530L)), 0, 0));

            LiveSession s = archive.find(0, Long.MAX_VALUE).get(0);

            assertEquals(s.userCount("danmu_users"), s.userSet("danmu_users").size(),
                    "同一批数据落盘再读回，两者必须一致");
            assertFalse(s.userSetSuspicious("danmu_users"), "一致时不该报可疑");
        }

        @Test
        @DisplayName("尺子先过阳性对照：造一次故意不一致，上面那条断言必须抓得到")
        void inconsistencyIsDetectable() {
            // 人数写 9、名单只给 2 个——若断言写错方向或比了同一个值，这里会假绿
            archive.append(new LiveSession("bilibili", STREAMER_UID, "测试主播", ROOM_ID,
                    1_000_000L, 1_003_600_000L, 3600,
                    Map.of("danmu_count", 455.0),
                    Map.of("danmu_users", 9),
                    org.frostnova.nova.core.enums.LiveEndReason.NORMAL, List.of(), 0,
                    Map.of("danmu_users", List.of(19427650908284L, 19471545457530L)), 0));

            LiveSession s = archive.find(0, Long.MAX_VALUE).get(0);

            assertEquals(9, s.userCount("danmu_users"));
            assertEquals(2, s.userSet("danmu_users").size());
            assertTrue(s.userCount("danmu_users") != s.userSet("danmu_users").size(),
                    "阳性对照：不一致必须能被读出来，否则一致性断言是摆设");
        }

        @Test
        @DisplayName("两种缺口分开存、各自读回，不相加")
        void gapsStaySeparate() {
            archive.append(withSets(Map.of(), 754, 123));

            LiveSession s = archive.find(0, Long.MAX_VALUE).get(0);

            assertEquals(754, s.maintenanceGapSeconds(), "程序停机");
            assertEquals(123, s.roomOutageSeconds(), "单房断线");
            assertTrue(s.hasGap());
            // 停机期间所有房间都在断，两段必然重叠；相加就是重复计数
            assertEquals(877, s.maintenanceGapSeconds() + s.roomOutageSeconds(),
                    "这个和本身没有意义，写在这里是为了说明它不该被当成总缺口");
        }

        @Test
        @DisplayName("老记录没有这两项：名单读成空表、断线读成 0，且能与「真的是 0」分开")
        void oldRecordsRemainReadable() throws Exception {
            Files.writeString(dir.resolve("sessions.jsonl"),
                    "{\"platform\":\"bilibili\",\"uid\":1,\"uname\":\"测试主播\",\"roomId\":2,"
                            + "\"startTime\":1000000,\"endTime\":1100000,\"durationSeconds\":100,"
                            + "\"metrics\":{\"danmu_count\":455},\"userCounts\":{\"danmu_users\":33}}\n",
                    StandardCharsets.UTF_8);

            LiveSession s = archive.find(0, Long.MAX_VALUE).get(0);

            assertTrue(s.userSet("danmu_users").isEmpty());
            assertEquals(0, s.roomOutageSeconds());
            assertFalse(s.hasUserSets(),
                    "空名单要能与「这场真的没人」分开——否则历史场次会显示成零观众");
            // 老记录的既有字段一个都不能读坏
            assertEquals(455.0, s.metric("danmu_count"));
            assertEquals(33, s.userCount("danmu_users"));
        }

        @Test
        @DisplayName("名单里有一个坏项时，只跳过那一个，不丢掉整场")
        void oneBadUidDoesNotDropTheWholeList() throws Exception {
            Files.writeString(dir.resolve("sessions.jsonl"),
                    "{\"platform\":\"bilibili\",\"uid\":1,\"uname\":\"测试主播\",\"roomId\":2,"
                            + "\"startTime\":1000000,\"endTime\":1100000,\"durationSeconds\":100,"
                            + "\"metrics\":{},\"userCounts\":{},"
                            + "\"userSets\":{\"danmu_users\":[19427650908284,\"坏了\",19471545457530]}}\n",
                    StandardCharsets.UTF_8);

            LiveSession s = archive.find(0, Long.MAX_VALUE).get(0);

            assertEquals(List.of(19427650908284L, 19471545457530L), s.userSet("danmu_users"),
                    "名单一次性，坏一个不能连累其余的");
        }
        @Test
        @DisplayName("自校验绊线放宽到 ±1：差 1 是正常竞态不报，差 2 才当缺陷查")
        void toleratesOneOffButFlagsMore() {
            // 归档时人数与名单是两次独立调用取的，两次之间来一个人就差 1。
            // 绊线写成严格相等会在正常竞态上误报，而误报会把人训练成忽略告警
            LiveSession offByOne = new LiveSession("bilibili", STREAMER_UID, "测试主播", ROOM_ID,
                    1_000_000L, 1_003_600_000L, 3600, Map.of(), Map.of("danmu_users", 3),
                    org.frostnova.nova.core.enums.LiveEndReason.NORMAL, List.of(), 0,
                    Map.of("danmu_users", List.of(19427650908284L, 19471545457530L)), 0);
            assertFalse(offByOne.userSetSuspicious("danmu_users"), "差 1 属正常竞态，不该报");

            LiveSession offByTwo = new LiveSession("bilibili", STREAMER_UID, "测试主播", ROOM_ID,
                    1_000_000L, 1_003_600_000L, 3600, Map.of(), Map.of("danmu_users", 4),
                    org.frostnova.nova.core.enums.LiveEndReason.NORMAL, List.of(), 0,
                    Map.of("danmu_users", List.of(19427650908284L, 19471545457530L)), 0);
            assertTrue(offByTwo.userSetSuspicious("danmu_users"), "差 2 超出容差，必须报");
        }

        @Test
        @DisplayName("老记录没有名单时一律不报——那是个不存在的问题")
        void oldRecordsAreNeverSuspicious() throws Exception {
            // 名单 0 条 vs 人数 33，若不特判就会报缺陷，
            // 但那时候本来就不写名单，报的是一个不存在的问题
            Files.writeString(dir.resolve("sessions.jsonl"),
                    "{\"platform\":\"bilibili\",\"uid\":1,\"uname\":\"测试主播\",\"roomId\":2,"
                            + "\"startTime\":1000000,\"endTime\":1100000,\"durationSeconds\":100,"
                            + "\"metrics\":{},\"userCounts\":{\"danmu_users\":33}}\n",
                    StandardCharsets.UTF_8);

            LiveSession s = archive.find(0, Long.MAX_VALUE).get(0);

            assertEquals(33, s.userCount("danmu_users"));
            assertTrue(s.userSet("danmu_users").isEmpty());
            assertFalse(s.userSetSuspicious("danmu_users"),
                    "没有名单的年代不该被当成落盘缺陷");
        }
    }
}
