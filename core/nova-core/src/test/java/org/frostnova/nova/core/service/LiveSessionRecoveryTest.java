package org.frostnova.nova.core.service;

import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.LiveHighlightFinder;
import org.frostnova.nova.core.config.EventConfig;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.event.live.common.LiveOnEvent;
import org.frostnova.nova.core.listener.NovaDefaultLiveOnEventListener;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.RoomInfoSnapshot;
import org.frostnova.nova.core.model.SeriesPeak;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 停机与崩溃善后测试
 * <p>
 * 这里模拟的是 {@code kill -9}：进程被强杀之后，唯一活下来的东西就是数据文件。
 * 所以每个「崩溃」都用「同一个文件上换一个新的服务实例」来表示——
 * 不留任何内存里的线索，正是真崩溃的样子。
 */
@DisplayName("停机与崩溃善后")
class LiveSessionRecoveryTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(114514L, "测试主播", 47731877194803L);

    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private LiveSessionArchive archive;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        properties.getLive().setSaveLiveData(true);
        // 自动保存挪到一小时后：测试要精确控制「最后一次落盘」发生在哪一刻
        properties.getLive().setAutoSaveLiveDataInterval(3600);
        archive = new LiveSessionArchive(properties);
    }

    /**
     * 起一个新的数据服务并读盘，等价于「进程重启」
     */
    private DefaultLiveDataService boot() {
        DefaultLiveDataService service = new DefaultLiveDataService(properties);
        service.onApplicationReadyEvent();
        return service;
    }

    /**
     * 起一个接线齐全的补档：标题轨迹与明细那两件依赖一并接上
     */
    private LiveSessionRecovery recovery(DefaultLiveDataService service) {
        return recovery(service, new LiveRoomInfoHistory(new NovaStateStore(properties)));
    }

    /**
     * 起一个接线齐全的补档，标题轨迹用指定那一份（读过盘的）
     */
    private LiveSessionRecovery recovery(DefaultLiveDataService service, LiveRoomInfoHistory history) {
        return new LiveSessionRecovery(service, archive, history,
                new LiveSessionDetailArchiver(service, history, new LiveDetailArchive(properties)));
    }

    private List<LiveSession> archived() {
        return archive.find(0, Long.MAX_VALUE);
    }

    @Nested
    @DisplayName("未闭合场次")
    class Unclosed {
        @Test
        @DisplayName("崩溃后下一次开播时，上一场应按未闭合归档且指标不丢")
        void archivesPreviousSessionOnNextLiveOn() {
            long start = Instant.now().toEpochMilli() - 7200_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            before.incrementLiveMetric(PLATFORM, STREAMER.getUid(), "danmu_count", 455);
            // 崩溃前最后一次自动保存，之后进程被强杀
            before.saveNow(false);
            long watermark = watermark();

            DefaultLiveDataService after = boot();
            LiveSessionRecovery recovery = recovery(after);

            assertTrue(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli()));

            List<LiveSession> sessions = archived();
            assertEquals(1, sessions.size());
            LiveSession session = sessions.get(0);
            assertEquals(LiveEndReason.UNCLOSED, session.endReason());
            assertTrue(session.interrupted(), "未闭合场次必须被判为不可比");
            assertEquals(start, session.startTime());
            assertEquals(watermark, session.endTime(), "结束时刻只能是我们能证明的最后一刻");
            assertEquals((watermark - start) / 1000, session.durationSeconds());
            assertEquals(455.0, session.metric("danmu_count"), "崩溃前收到的弹幕数必须留在归档里");
        }

        @Test
        @DisplayName("正常下播后再开播不应产生未闭合记录")
        void noRecordAfterNormalEnd() {
            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), Instant.now().toEpochMilli() - 3600_000);
            // 下播：状态置否，这是「已闭合」的唯一标记
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), false);
            before.saveNow(false);

            LiveSessionRecovery recovery = recovery(boot());

            assertFalse(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli()));
            assertTrue(archived().isEmpty());
        }

        @Test
        @DisplayName("状态挂在在播但没有开播时间时不归档")
        void skipsWhenStartTimeMissing() {
            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.saveNow(false);

            LiveSessionRecovery recovery = recovery(boot());

            assertFalse(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli()));
            assertTrue(archived().isEmpty(), "没有起点的记录归不进任何统计周期，留着只会污染分析");
        }

        @Test
        @DisplayName("水位线早于开播时仍归档，但时长记 0")
        void archivesWithZeroDurationWhenWatermarkTooEarly() {
            long start = System.currentTimeMillis();

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            before.incrementLiveMetric(PLATFORM, STREAMER.getUid(), "danmu_count", 7);
            before.saveNow(false);
            // 把水位线按到开播之前：停机期间主播开了一场又下了，我们一次都没落到这一场的盘
            writeWatermark(start - 60_000);

            LiveSessionRecovery recovery = recovery(boot());
            // 🔴 必须等时钟真的走过 start：归档判的是 start < newStartTime，
            // 两次取时钟落在同一毫秒时会被判成「同一场」而跳过——本用例曾因此偶发红
            awaitClockPast(start);
            recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli());

            List<LiveSession> sessions = archived();
            assertEquals(1, sessions.size(), "算不出时长也要留下这一场：指标比时长值钱");
            assertEquals(0, sessions.get(0).durationSeconds());
            assertEquals(7.0, sessions.get(0).metric("danmu_count"));
        }

        @Test
        @DisplayName("同一场重复收到开播事件时不归档")
        void skipsWhenSameSession() {
            long start = Instant.now().toEpochMilli() - 600_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            before.saveNow(false);

            LiveSessionRecovery recovery = recovery(boot());

            assertFalse(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, start));
            assertTrue(archived().isEmpty());
        }
    }

    @Nested
    @DisplayName("停机缺口")
    class Downtime {
        @Test
        @DisplayName("启动时应把「上次落盘 ~ 现在」记成一段停机")
        void recordsDowntimeOnStartup() {
            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.saveNow(false);
            long watermark = watermark();

            // 🔴 等时钟真的走过落盘那一毫秒再启动。
            // 恢复逻辑对「当前时刻 <= 落盘时刻」是**刻意不记**的（那是时钟回拨的形态，
            // 见 ignoresClockRollback）。落盘与恢复挤进同一毫秒时它同样不记，
            // 于是这条用例会红——而红的原因与被测代码无关。
            // 2026-08-14 真的红过一次：同一个提交，隔了一个多小时重跑就复现了。
            // **一个会因为跑得太快而失败的用例，是把尺子本身弄坏了。**
            awaitClockPast(watermark);

            DefaultLiveDataService after = boot();
            recovery(after).onApplicationReadyEvent();

            long now = System.currentTimeMillis();
            assertTrue(after.downtimeWithin(watermark, now) > 0, "停机区间必须落在数据里");
            assertEquals(0, after.downtimeWithin(watermark - 100_000, watermark - 1),
                    "停机之前的时段不该算成缺口");
        }

        @Test
        @DisplayName("本进程这次启动记的那段停机问得回来，盘上的旧段不算")
        void startupDowntimeIsOnlyTheSegmentThisProcessRecorded() {
            long base = System.currentTimeMillis() - 3_600_000;
            DefaultLiveDataService previous = boot();
            previous.recordDowntime(base, base + 10_000, LiveGap.Reason.MAINTENANCE);
            previous.saveNow(false);

            DefaultLiveDataService loaded = boot();
            assertTrue(loaded.startupDowntime().isEmpty(), "从盘上读回来的旧停机不是本进程记的");

            long watermark = watermark();
            awaitClockPast(watermark);
            DefaultLiveDataService after = boot();
            recovery(after).onApplicationReadyEvent();

            LiveGap segment = after.startupDowntime().orElseThrow();
            assertEquals(watermark, segment.from(), "起点是上次落盘时刻");
            assertTrue(segment.to() > watermark, "终点是这次进程就绪的时刻");
            assertEquals(LiveGap.Reason.RESTART, segment.reason(), "上次不是正常退出，成因是重启");

            long laterFrom = segment.to() + 5_000;
            after.recordDowntime(laterFrom, laterFrom + 1_000, LiveGap.Reason.MAINTENANCE);
            assertEquals(segment, after.startupDowntime().orElseThrow(), "后来再记的停机不盖掉启动这一段");

            assertEquals(segment, new CompositeLiveDataService(after, null).startupDowntime().orElseThrow(),
                    "组合实现要把这一问交回本场那一份");
        }

        @Test
        @DisplayName("只算与场次重叠的部分，开播之前那一截不算")
        void countsOverlapOnly() {
            // 时刻必须取真实的近期时间：停机记录有保留期，1970 年的区间会被当成过期直接裁掉
            long base = System.currentTimeMillis() - 3600_000;

            DefaultLiveDataService service = boot();
            service.recordDowntime(base, base + 100_000);

            assertEquals(100_000, service.downtimeWithin(base, base + 100_000));
            assertEquals(50_000, service.downtimeWithin(base + 50_000, base + 200_000), "跨越开播时刻的停机只算开播之后");
            assertEquals(30_000, service.downtimeWithin(base - 100_000, base + 30_000));
            assertEquals(0, service.downtimeWithin(base + 200_000, base + 300_000));
        }

        @Test
        @DisplayName("多次重启的缺口应累加")
        void sumsMultipleDowntimes() {
            long base = System.currentTimeMillis() - 3600_000;

            DefaultLiveDataService service = boot();
            service.recordDowntime(base, base + 10_000);
            service.recordDowntime(base + 20_000, base + 50_000);

            assertEquals(40_000, service.downtimeWithin(base - 1000, base + 60_000));
        }

        @Test
        @DisplayName("超过保留期的停机记录应被裁掉，不无限膨胀")
        void prunesExpiredDowntimes() {
            long ancient = System.currentTimeMillis() - 40L * 24 * 3600 * 1000;

            DefaultLiveDataService service = boot();
            service.recordDowntime(ancient, ancient + 60_000);
            // 下一次记录时顺手清理，这样清理频率与写入频率一致，不需要额外的定时任务
            service.recordDowntime(System.currentTimeMillis() - 1000, System.currentTimeMillis());

            assertEquals(0, service.downtimeWithin(ancient - 1000, ancient + 120_000));
        }

        @Test
        @DisplayName("时钟回拨时不记停机，宁可没有也不要一个负数区间")
        void ignoresClockRollback() {
            writeWatermark(System.currentTimeMillis() + 3600_000);

            DefaultLiveDataService service = boot();
            recovery(service).onApplicationReadyEvent();

            assertEquals(0, service.downtimeWithin(0, Long.MAX_VALUE / 2));
        }

        @Test
        @DisplayName("归档那一场的缺口秒数，是从区间表里现算出来的")
        void archivedGapComesFromTheIntervals() {
            long start = System.currentTimeMillis() - 7200_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            // 本场之内停过两段：60 秒与 30 秒；另有一段整个落在开播之前，不该算进本场
            before.recordDowntime(start + 600_000, start + 660_000, LiveGap.Reason.MAINTENANCE);
            before.recordDowntime(start + 1200_000, start + 1230_000, LiveGap.Reason.RESTART);
            before.recordDowntime(start - 600_000, start - 300_000, LiveGap.Reason.MAINTENANCE);
            before.recordRoomOutage(PLATFORM, STREAMER.getUid(), start + 610_000, start + 640_000);
            before.saveNow(false);

            DefaultLiveDataService after = boot();
            assertTrue(recovery(after)
                    .archiveUnclosedIfAny(PLATFORM, STREAMER, System.currentTimeMillis()));

            LiveSession session = archived().get(0);
            assertEquals(90, session.maintenanceGapSeconds(), "60 + 30，开播之前那一段不算本场");
            assertEquals(30, session.roomOutageSeconds(), "单房断线单列，不并进上面那个数");
        }

        @Test
        @DisplayName("区间列表逐段给出起止与成因，总时长仍是各段之和")
        void listsIntervalsWithReasons() {
            long base = System.currentTimeMillis() - 3600_000;

            DefaultLiveDataService service = boot();
            service.recordDowntime(base, base + 10_000, LiveGap.Reason.MAINTENANCE);
            service.recordDowntime(base + 20_000, base + 50_000, LiveGap.Reason.RESTART);
            service.recordRoomOutage(PLATFORM, STREAMER.getUid(), base + 60_000, base + 70_000);

            List<LiveGap> downtimes = service.downtimeIntervals(base - 1000, base + 80_000);
            assertEquals(List.of(
                            new LiveGap(base, base + 10_000, LiveGap.Reason.MAINTENANCE),
                            new LiveGap(base + 20_000, base + 50_000, LiveGap.Reason.RESTART)),
                    downtimes, "两段停机应逐段给出，成因各自跟着自己那一段");

            assertEquals(List.of(new LiveGap(base + 60_000, base + 70_000, LiveGap.Reason.STREAM_LOSS)),
                    service.roomOutageIntervals(PLATFORM, STREAMER.getUid(), base - 1000, base + 80_000));

            // 阴性对照：两个总数一个字都没变，区间化改的是「说不说得出缺在哪」，不是「缺了多久」
            assertEquals(40_000, service.downtimeWithin(base - 1000, base + 80_000));
            assertEquals(10_000, service.roomOutageWithin(PLATFORM, STREAMER.getUid(), base - 1000, base + 80_000));
        }

        @Test
        @DisplayName("区间同样只给与场次重叠的那一截，开播之前那一段被裁掉")
        void clipsIntervalsToTheSession() {
            long base = System.currentTimeMillis() - 3600_000;

            DefaultLiveDataService service = boot();
            service.recordDowntime(base, base + 100_000, LiveGap.Reason.MAINTENANCE);

            assertEquals(List.of(new LiveGap(base + 50_000, base + 100_000, LiveGap.Reason.MAINTENANCE)),
                    service.downtimeIntervals(base + 50_000, base + 200_000),
                    "跨越开播时刻的停机, 区间也只留开播之后那一截");
            assertEquals(List.of(), service.downtimeIntervals(base + 200_000, base + 300_000));
        }

        @Test
        @DisplayName("上次正常退出时，这段空白记成维护")
        void readsCleanShutdownAsMaintenance() {
            assertEquals(LiveGap.Reason.MAINTENANCE, reasonAfterShutdown(true));
        }

        @Test
        @DisplayName("上次崩溃或被强杀时，这段空白记成重启")
        void readsCrashAsRestart() {
            assertEquals(LiveGap.Reason.RESTART, reasonAfterShutdown(false));
        }

        /**
         * 停一次、起一次，返回启动时记下的那段停机的成因
         * @param clean 上一个进程是不是正常退出的
         */
        private LiveGap.Reason reasonAfterShutdown(boolean clean) {
            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.saveNow(clean);
            long watermark = watermark();
            // 理由同 recordsDowntimeOnStartup：落盘与恢复挤进同一毫秒时刻意不记
            awaitClockPast(watermark);

            DefaultLiveDataService after = boot();
            assertEquals(clean, after.wasCleanShutdown().orElseThrow(),
                    "启动那一刻读到的必须是上次退出情况, 不是本次");
            recovery(after).onApplicationReadyEvent();

            List<LiveGap> gaps = after.downtimeIntervals(watermark, System.currentTimeMillis());
            assertEquals(1, gaps.size(), "启动只该记一段停机");
            return gaps.get(0).reason();
        }

        @Test
        @DisplayName("成因问不出来时记「原因未定」，不挑一个看起来最像的")
        void fallsBackToUnknownReason() {
            long base = System.currentTimeMillis() - 3600_000;

            DefaultLiveDataService service = boot();
            // 不带成因的那个重载：第三方实现不记成因时走的就是这条路
            service.recordDowntime(base, base + 10_000);

            assertEquals(LiveGap.Reason.UNKNOWN,
                    service.downtimeIntervals(base, base + 10_000).get(0).reason());
        }

        @Test
        @DisplayName("旧数据文件没有水位线，不做恢复也不报错")
        void toleratesOldDataFile() throws Exception {
            Files.writeString(dir.resolve("data.json"),
                    "{\"LiveStatus:bilibili\":{\"114514\":true},\"LiveStartTime:bilibili\":{\"114514\":1000000}}",
                    StandardCharsets.UTF_8);

            DefaultLiveDataService service = boot();
            LiveSessionRecovery recovery = recovery(service);
            recovery.onApplicationReadyEvent();

            assertTrue(service.getLastSaveTime().isEmpty());
            assertEquals(0, service.downtimeWithin(0, Long.MAX_VALUE / 2));
            // 没有水位线就定不出结束时刻，时长记 0，但那一场仍然留下来
            assertTrue(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, System.currentTimeMillis()));
            assertEquals(0, archived().get(0).durationSeconds());
        }

        @Test
        @DisplayName("⚠️ 断线缺口带成因：解析降级读得回、旧记录读回断流、认不出的名字落未定、不抛")
        void roomOutageReasonRoundTripAndLegacyReadback() throws Exception {
            List<String> reds = new ArrayList<>();
            long base = System.currentTimeMillis() - 3_600_000;
            long uid = STREAMER.getUid();
            Path data = dir.resolve("data.json");

            try {
                // 五参数写入端只在确有解析失败计数时才许落 PARSE_DEGRADED（连接器那侧的格守着那条线）；
                // 这里量存储层本身：写下去的成因，重启之后要原样读得回来
                DefaultLiveDataService before = boot();
                before.recordRoomOutage(PLATFORM, uid, base, base + 10_000, LiveGap.Reason.PARSE_DEGRADED);
                before.saveNow(false);

                DefaultLiveDataService after = boot();
                assertEquals(List.of(new LiveGap(base, base + 10_000, LiveGap.Reason.PARSE_DEGRADED)),
                        after.roomOutageIntervals(PLATFORM, uid, base - 1000, base + 60_000),
                        "解析降级的断线缺口要原样读得回来");
            } catch (AssertionError e) {
                reds.add("① " + e.getMessage());
            }

            try {
                // 成因字段出现之前的老记录：只有 from 与 to
                Files.writeString(data, "{\"RoomOutages:bilibili\":{\"114514\":"
                                + "[{\"from\":" + (base + 100_000) + ",\"to\":" + (base + 110_000) + "}]}}",
                        StandardCharsets.UTF_8);
                List<LiveGap> legacy = boot().roomOutageIntervals(PLATFORM, uid, base - 1000, base + 200_000);
                assertEquals(1, legacy.size(), "老记录必须原样读得回，一条都不能丢");
                assertEquals(LiveGap.Reason.STREAM_LOSS, legacy.get(0).reason(),
                        "没有成因字段的断线记录说的就是断流——这一项在加成因之前只由断线重连写入");
            } catch (AssertionError e) {
                reds.add("② " + e.getMessage());
            }

            try {
                DefaultLiveDataService service = boot();
                // 断线重连仍在用的四参数重载：落下来的成因必须还是断流
                service.recordRoomOutage(PLATFORM, uid, base + 200_000, base + 210_000);
                assertEquals(LiveGap.Reason.STREAM_LOSS,
                        service.roomOutageIntervals(PLATFORM, uid, base + 199_000, base + 220_000).get(0).reason(),
                        "四参数重载是断线那条路在用，不许悄悄变成解析降级或未定");
            } catch (AssertionError e) {
                reds.add("③ " + e.getMessage());
            }

            try {
                // 比当前枚举还新的名字（某个值将来被删掉后，老文件里就是这个形状）：不抛，落未定
                Files.writeString(data, "{\"RoomOutages:bilibili\":{\"114514\":"
                                + "[{\"from\":" + (base + 300_000) + ",\"to\":" + (base + 310_000)
                                + ",\"reason\":\"SOME_REMOVED_VALUE\"}]}}",
                        StandardCharsets.UTF_8);
                List<LiveGap> unknown = boot().roomOutageIntervals(PLATFORM, uid, base + 299_000, base + 320_000);
                assertEquals(1, unknown.size());
                assertEquals(LiveGap.Reason.UNKNOWN, unknown.get(0).reason(),
                        "认不出的成因按未定处理，与停机那边同一判法");
            } catch (AssertionError e) {
                reds.add("④ " + e.getMessage());
            }

            assertTrue(reds.isEmpty(), () -> "四问中 " + reds.size() + " 问红: " + String.join("; ", reds));
        }
    }

    @Nested
    @DisplayName("开播监听器接线")
    class Wiring {
        @Test
        @DisplayName("开播时应在清零之前把上一场归档，指标不被清空带走")
        void archivesBeforeReset() {
            long start = System.currentTimeMillis() - 7200_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            before.incrementLiveMetric(PLATFORM, STREAMER.getUid(), "danmu_count", 233);
            before.saveNow(false);

            DefaultLiveDataService after = boot();
            NovaDefaultLiveOnEventListener listener = new NovaDefaultLiveOnEventListener(
                    properties, after, recovery(after));

            LiveOnEvent event = new LiveOnEvent(PLATFORM, STREAMER, Instant.now());
            listener.onLiveOnEventCheckReconnect(event);
            listener.onLiveOnEventSetLiveData(event);

            assertEquals(1, archived().size());
            assertEquals(233.0, archived().get(0).metric("danmu_count"));
            assertEquals(0.0, after.getLiveMetric(PLATFORM, STREAMER.getUid(), "danmu_count"),
                    "新的一场必须从零开始");
        }

        @Test
        @DisplayName("断线重连不是新的一场，不该归档也不该清零")
        void reconnectDoesNotArchive() {
            long now = System.currentTimeMillis();

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), now - 3600_000);
            before.incrementLiveMetric(PLATFORM, STREAMER.getUid(), "danmu_count", 99);
            // 刚刚「下播」过，紧接着又开播——这正是断线重连的样子
            before.setLiveEndTime(PLATFORM, STREAMER.getUid(), now);
            before.saveNow(false);

            DefaultLiveDataService after = boot();
            NovaDefaultLiveOnEventListener listener = new NovaDefaultLiveOnEventListener(
                    properties, after, recovery(after));

            LiveOnEvent event = new LiveOnEvent(PLATFORM, STREAMER, Instant.ofEpochMilli(now));
            listener.onLiveOnEventCheckReconnect(event);
            listener.onLiveOnEventSetLiveData(event);

            assertTrue(event.isReconnect(), "前提：这一次必须被判为断线重连，否则这条测试什么也没验");
            assertTrue(archived().isEmpty());
            assertEquals(99.0, after.getLiveMetric(PLATFORM, STREAMER.getUid(), "danmu_count"),
                    "重连不该把本场统计清掉");
        }
    }

    @Nested
    @DisplayName("补档明细与标题轨迹")
    class RecoveryDetail {
        /**
         * 某个时刻落在哪一分钟格
         */
        private long bucket(long at) {
            return at / 60_000L * 60_000L;
        }

        @Test
        @DisplayName("a 补档把明细整份存下：读得回来、曲线与排行不空（故障：停机里结束的那场以后没法重画）")
        void storesDetailOnRecovery() {
            long start = Instant.now().toEpochMilli() - 7200_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            for (int minute = 0; minute < 15; minute++) {
                before.incrementLiveSeries(PLATFORM, STREAMER.getUid(), "danmu_count",
                        start + minute * 60_000L, minute % 5);
            }
            for (int i = 0; i < 10; i++) {
                before.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), "danmu_users",
                        19_000_000_000_000L + i, 10 - i);
                before.recordLiveUserName(PLATFORM, STREAMER.getUid(), 19_000_000_000_000L + i, "观众" + i);
            }
            // 崩溃前最后一次自动保存，之后进程被强杀
            before.saveNow(false);

            LiveDetailArchive details = new LiveDetailArchive(properties);
            LiveSessionRecovery recovery = recovery(boot());

            assertTrue(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli()));

            LiveDetail detail = details.read(PLATFORM, STREAMER.getUid(), start)
                    .orElseThrow(() -> new NoSuchElementException("补档没存明细，这一场以后重画不出来"));
            assertFalse(detail.series("danmu_count").isEmpty(), "曲线要整条留下");
            assertEquals(15, detail.series("danmu_count").size(), "曲线整条留，一格不少");
            assertEquals(10, detail.ranking("danmu_users").size(), "排行留全量而不是空");
        }

        @Test
        @DisplayName("b 补档的标题轨迹读状态存储里那份，结束时刻以后改的标题不进旧场（故障：报告里标题轨迹是空的，或混进两场之间改的标题）")
        void titlesComeFromStateStoreTruncatedToEndTime() {
            long start = Instant.now().toEpochMilli() - 7200_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            before.saveNow(false);
            long endTime = watermark();

            // 崩溃前的进程记了两条标题变更：一条在本场之内，一条在结束时刻之后（两场之间改的）
            NovaStateStore seed = new NovaStateStore(properties);
            LiveRoomInfoHistory seeded = new LiveRoomInfoHistory(seed);
            seeded.record(PLATFORM, STREAMER.getUid(), start + 60_000, "本场之内改的标题", "");
            seeded.record(PLATFORM, STREAMER.getUid(), endTime + 60_000, "结束之后改的标题", "");
            seed.save();

            NovaStateStore loaded = new NovaStateStore(properties);
            loaded.onApplicationReadyEvent();
            try {
                LiveRoomInfoHistory history = new LiveRoomInfoHistory(loaded);
                LiveSessionRecovery recovery = recovery(boot(), history);

                assertTrue(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli()));

                List<RoomInfoSnapshot> titles = archived().get(0).titles();
                assertEquals(1, titles.size(),
                        "轨迹要来自状态存储：一场之内一条在，结束之后那条（两场之间改的）不算: " + titles);
                assertEquals("本场之内改的标题", titles.get(0).title());
            } finally {
                loaded.onContextClosedEvent();
            }
        }

        @Test
        @DisplayName("c 补档存下明细即封住旧场弹幕：之后再来的弹幕不进旧场原文（故障：旧场弹幕里混进下播后的弹幕）")
        void sealsOldSessionDanmuAfterRecovery() {
            long start = Instant.now().toEpochMilli() - 7200_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            before.saveNow(false);

            LiveDetailArchive details = new LiveDetailArchive(properties);
            details.appendDanmu(PLATFORM, STREAMER.getUid(), start, new DanmuRecord(
                    start + 60_000, 19338207415562L, "观众甲", "场内的弹幕", DanmuRecord.Type.DANMU));

            LiveSessionRecovery recovery = recovery(boot());
            assertTrue(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli()));

            // 补档之后（这一场已结束之后）才到的弹幕
            details.appendDanmu(PLATFORM, STREAMER.getUid(), start, new DanmuRecord(
                    Instant.now().toEpochMilli(), 19338207415562L, "观众甲", "下播之后的弹幕", DanmuRecord.Type.DANMU));

            List<DanmuRecord> danmu = details.readDanmu(PLATFORM, STREAMER.getUid(), start);
            assertEquals(1, danmu.size(), "补档之后这一场已封存，下播后的弹幕不该再追加进旧场: " + danmu);
            assertEquals("场内的弹幕", danmu.get(0).text());
        }

        @Test
        @DisplayName("d 开播那一路补档先读轨迹、清空后跑（故障：开播补档时轨迹已被清空，停机里结束的那场带着空标题归档）")
        void recoveryReadsTitlesBeforeHistoryClearsOnLiveOn() {
            long start = Instant.now().toEpochMilli() - 7200_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            before.saveNow(false);

            // 崩溃前的进程把标题变更落进了 state.json
            NovaStateStore seed = new NovaStateStore(properties);
            new LiveRoomInfoHistory(seed).record(PLATFORM, STREAMER.getUid(), start + 60_000, "崩溃前的标题", "");
            seed.save();

            // 起新实例（＝进程重启），两个监听都进容器，先后只由 @Order 决定
            DefaultLiveDataService after = boot();
            NovaStateStore state = new NovaStateStore(properties);
            state.onApplicationReadyEvent();
            AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
            try {
                LiveRoomInfoHistory history = new LiveRoomInfoHistory(state);
                LiveSessionRecovery recovery = recovery(after, history);
                NovaDefaultLiveOnEventListener listener =
                        new NovaDefaultLiveOnEventListener(properties, after, recovery);

                context.register(EventConfig.class);
                context.registerBean(NovaDefaultLiveOnEventListener.class, () -> listener);
                context.registerBean(LiveRoomInfoHistory.class, () -> history);
                context.refresh();

                context.publishEvent(new LiveOnEvent(PLATFORM, STREAMER, Instant.now()));

                List<LiveSession> sessions = archived();
                assertEquals(1, sessions.size(), "开播要先补档上一场");
                assertEquals(1, sessions.get(0).titles().size(), "补档要抢在轨迹清空之前读到它");
                assertEquals("崩溃前的标题", sessions.get(0).titles().get(0).title());
            } finally {
                context.close();
                state.onContextClosedEvent();
            }
        }

        @Test
        @DisplayName("e 补档的场次记录峰值不空（故障：曲线没有峰值，事后无从问「这一场最高多少人在看」）")
        void recoverySessionCarriesPeaks() {
            long start = Instant.now().toEpochMilli() - 7200_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            for (int minute = 0; minute < 30; minute++) {
                before.incrementLiveSeries(PLATFORM, STREAMER.getUid(), "danmu_count",
                        start + minute * 60_000L, minute);
            }
            before.saveNow(false);

            LiveSessionRecovery recovery = recovery(boot());
            assertTrue(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli()));

            LiveSession session = archived().get(0);
            assertTrue(session.hasPeaks(), "补档的场次要带峰值");
            assertEquals(new SeriesPeak(bucket(start + 29 * 60_000L), 29.0),
                    session.peak("danmu_count").orElseThrow());
        }

        @Test
        @DisplayName("f 补档的高能只数结束时刻及以前的弹幕，结束之后进的不算（故障：重启后旧场还挂着在播时进来的弹幕被算进那场高能）")
        void highlightsCountDanmuOnlyUpToEndTime() {
            // 开播与结束都定在整分钟上：结束之后的弹幕要落在这一场最后一分钟格里，
            // 截止一旦失效它会真的进高能——落在更往后的格里会被分桶默默丢掉，红了也看不见
            long start = Instant.now().toEpochMilli() / 60_000L * 60_000L - 7200_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            before.saveNow(false);
            long endTime = start + 60 * 60_000L;
            writeWatermark(endTime);

            LiveDetailArchive details = new LiveDetailArchive(properties);
            for (int i = 0; i < 20; i++) {
                details.appendDanmu(PLATFORM, STREAMER.getUid(), start, new DanmuRecord(
                        start + 30 * 60_000L + i, 19338207415562L, "观众甲", "场内的第" + i + "条", DanmuRecord.Type.DANMU));
            }
            // 这批弹幕在「这一场结束之后、旧场封存之前」进来（重启后旧场还挂着在播时的样子），
            // 比场内任何一分钟都热闹——若没有截止，它会顶成那场的第一高能
            for (int i = 0; i < 100; i++) {
                details.appendDanmu(PLATFORM, STREAMER.getUid(), start, new DanmuRecord(
                        endTime + 5_000L + i, 19338207415562L, "观众甲", "结束之后的第" + i + "条", DanmuRecord.Type.DANMU));
            }

            LiveSessionRecovery recovery = recovery(boot());
            assertTrue(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli()));

            LiveDetail detail = details.read(PLATFORM, STREAMER.getUid(), start)
                    .orElseThrow(() -> new NoSuchElementException("补档没存明细，这一场以后重画不出来"));
            List<LiveHighlightFinder.Highlight> highlights = detail.highlights();
            assertEquals(1, highlights.size(), "结束之后的弹幕不该算出高能: " + highlights);
            assertEquals(bucket(start + 30 * 60_000L), highlights.get(0).at(), "唯一的高能是场内那一分钟");
        }

        @Test
        @DisplayName("g 补档那场的停机秒算上按房记的重启尾巴，断线秒不算它（故障：补档少报重启后那几秒的采集缺口）")
        void recoveryDowntimeCountsRoomTailButNotDisconnect() {
            long start = Instant.now().toEpochMilli() - 7200_000;

            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
            // 重启尾巴：进程就绪之后、这间认证成功之前的那几秒，按房记、成因是重启
            before.recordRoomOutage(PLATFORM, STREAMER.getUid(),
                    start + 10 * 60_000L, start + 10 * 60_000L + 8_000, LiveGap.Reason.RESTART);
            // 同场另有一段这间自己的断流
            before.recordRoomOutage(PLATFORM, STREAMER.getUid(),
                    start + 40 * 60_000L, start + 40 * 60_000L + 30_000);
            before.saveNow(false);

            LiveSessionRecovery recovery = recovery(boot());
            assertTrue(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli()));

            LiveSession session = archived().get(0);
            assertEquals(8, session.maintenanceGapSeconds(), "按房记的重启尾巴要算进停机秒");
            assertEquals(30, session.roomOutageSeconds(), "断线秒只数断流，重启尾巴不并进来");
        }
    }

    /**
     * 自旋到系统时钟严格越过给定时刻
     * <p>
     * 最多一毫秒，不引入固定睡眠——固定睡眠只是把概率调低，
     * 而这里要的是「一定越过去」。
     */
    private static void awaitClockPast(long instant) {
        while (System.currentTimeMillis() <= instant) {
            Thread.onSpinWait();
        }
    }

    /**
     * 读出数据文件里当前的水位线，判据必须取自盘上而不是内存
     */
    private long watermark() {
        try {
            return com.alibaba.fastjson2.JSON
                    .parseObject(Files.readString(dir.resolve("data.json"), StandardCharsets.UTF_8))
                    .getLongValue("LastSaveTime");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 手工改写数据文件里的水位线，用来构造时钟异常这类不能靠真实时间制造的场景
     */
    private void writeWatermark(long lastSaveTime) {
        try {
            Path path = dir.resolve("data.json");
            String json = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "{}";
            com.alibaba.fastjson2.JSONObject object = com.alibaba.fastjson2.JSON.parseObject(json);
            object.put("LastSaveTime", lastSaveTime);
            Files.writeString(path, object.toJSONString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
