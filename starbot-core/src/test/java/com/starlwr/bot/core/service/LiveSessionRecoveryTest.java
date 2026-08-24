package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.listener.StarBotDefaultLiveOnEventListener;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

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

    private StarBotCoreProperties properties;

    private LiveSessionArchive archive;

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
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
            LiveSessionRecovery recovery = new LiveSessionRecovery(after, archive);

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

            LiveSessionRecovery recovery = new LiveSessionRecovery(boot(), archive);

            assertFalse(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, Instant.now().toEpochMilli()));
            assertTrue(archived().isEmpty());
        }

        @Test
        @DisplayName("状态挂在在播但没有开播时间时不归档")
        void skipsWhenStartTimeMissing() {
            DefaultLiveDataService before = boot();
            before.setLiveStatus(PLATFORM, STREAMER.getUid(), true);
            before.saveNow(false);

            LiveSessionRecovery recovery = new LiveSessionRecovery(boot(), archive);

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

            LiveSessionRecovery recovery = new LiveSessionRecovery(boot(), archive);
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

            LiveSessionRecovery recovery = new LiveSessionRecovery(boot(), archive);

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
            new LiveSessionRecovery(after, archive).onApplicationReadyEvent();

            long now = System.currentTimeMillis();
            assertTrue(after.downtimeWithin(watermark, now) > 0, "停机区间必须落在数据里");
            assertEquals(0, after.downtimeWithin(watermark - 100_000, watermark - 1),
                    "停机之前的时段不该算成缺口");
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
            new LiveSessionRecovery(service, archive).onApplicationReadyEvent();

            assertEquals(0, service.downtimeWithin(0, Long.MAX_VALUE / 2));
        }

        @Test
        @DisplayName("旧数据文件没有水位线，不做恢复也不报错")
        void toleratesOldDataFile() throws Exception {
            Files.writeString(dir.resolve("data.json"),
                    "{\"LiveStatus:bilibili\":{\"114514\":true},\"LiveStartTime:bilibili\":{\"114514\":1000000}}",
                    StandardCharsets.UTF_8);

            DefaultLiveDataService service = boot();
            LiveSessionRecovery recovery = new LiveSessionRecovery(service, archive);
            recovery.onApplicationReadyEvent();

            assertTrue(service.getLastSaveTime().isEmpty());
            assertEquals(0, service.downtimeWithin(0, Long.MAX_VALUE / 2));
            // 没有水位线就定不出结束时刻，时长记 0，但那一场仍然留下来
            assertTrue(recovery.archiveUnclosedIfAny(PLATFORM, STREAMER, System.currentTimeMillis()));
            assertEquals(0, archived().get(0).durationSeconds());
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
            StarBotDefaultLiveOnEventListener listener = new StarBotDefaultLiveOnEventListener(
                    properties, after, new LiveSessionRecovery(after, archive));

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
            StarBotDefaultLiveOnEventListener listener = new StarBotDefaultLiveOnEventListener(
                    properties, after, new LiveSessionRecovery(after, archive));

            LiveOnEvent event = new LiveOnEvent(PLATFORM, STREAMER, Instant.ofEpochMilli(now));
            listener.onLiveOnEventCheckReconnect(event);
            listener.onLiveOnEventSetLiveData(event);

            assertTrue(event.isReconnect(), "前提：这一次必须被判为断线重连，否则这条测试什么也没验");
            assertTrue(archived().isEmpty());
            assertEquals(99.0, after.getLiveMetric(PLATFORM, STREAMER.getUid(), "danmu_count"),
                    "重连不该把本场统计清掉");
        }
    }

    /**
     * 读出数据文件里当前的水位线，判据必须取自盘上而不是内存
     */
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
