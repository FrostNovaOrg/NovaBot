package org.frostnova.nova.core.listener;

import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.live.common.LiveOffEvent;
import org.frostnova.nova.core.event.live.common.LiveOnEvent;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.SeriesPeak;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.service.CompositeLiveDataService;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.core.service.LiveInterventionTracker;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.LiveSessionDetailArchiver;
import org.frostnova.nova.core.service.LiveSessionRecovery;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RedisTotalDataStore;
import org.frostnova.nova.core.service.TotalDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 下播归档：峰值与明细
 * <p>
 * 下播那一刻是<b>唯一一次机会</b>：曲线、排行、词频在本场数据里，下一次开播即清零。
 * 这一刻没算出来、没写下去的东西，之后无论如何也补不回来。
 * <p>
 * 因此本组用例钉的不是「函数返回值对不对」，而是<b>「那一刻真的把它们落到盘上了」</b>——
 * 归档少一项，跑起来一点异常都没有，只是几个月后有人发现某一列永远是空的。
 */
@DisplayName("下播归档：峰值与明细")
class LiveOffArchiveTest {
    private static final String PLATFORM = "bilibili";

    /** ⚠️ 保留段假值 */
    private static final long UID = 19604318752096L;

    private static final long ROOM_ID = 47615208934771L;

    private static final long START = 1_700_000_000_000L;

    private static final long MINUTE = 60_000L;

    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private DefaultLiveDataService liveData;

    private LiveSessionArchive sessions;

    private LiveDetailArchive details;

    private NovaDefaultLiveOffEventListener listener;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        properties.getLive().setSaveLiveData(false);

        liveData = new DefaultLiveDataService(properties);
        sessions = new LiveSessionArchive(properties);
        details = new LiveDetailArchive(properties);
        LiveRoomInfoHistory history = new LiveRoomInfoHistory(new NovaStateStore(properties));
        listener = new NovaDefaultLiveOffEventListener(liveData, sessions,
                new LiveInterventionTracker(), history,
                new LiveSessionDetailArchiver(liveData, history, details));
    }

    @Test
    @DisplayName("② 峰值＝序列最大值，时刻是那一格自己的时刻")
    void peakIsTheMaximumAndItsOwnBucket() {
        liveData.setLiveStartTime(PLATFORM, UID, START);
        // 弹幕在第 42 分钟到顶（34），看过人数一路涨到最后一格
        for (int minute = 0; minute < 60; minute++) {
            liveData.incrementLiveSeries(PLATFORM, UID, "danmu_count", START + minute * MINUTE,
                    minute == 42 ? 34 : 6);
            liveData.maxLiveSeries(PLATFORM, UID, "watched_count", START + minute * MINUTE, 900 + minute * 62L);
        }

        listener.onLiveOffEvent(liveOff(START + 60 * MINUTE));

        LiveSession session = sessions.find(0, Long.MAX_VALUE).get(0);
        assertAll(
                () -> assertTrue(session.hasPeaks()),
                () -> assertEquals(new SeriesPeak(bucket(START + 42 * MINUTE), 34),
                        session.peak("danmu_count").orElseThrow(),
                        "取值与时刻必须同源：分两趟各算一次, 会得出「峰值 34, 出现在第 7 分钟」"),
                () -> assertEquals(new SeriesPeak(bucket(START + 59 * MINUTE), 900 + 59 * 62),
                        session.peak("watched_count").orElseThrow()));
    }

    @Test
    @DisplayName("② 一个点都没有的曲线不产生峰值项——「没有数据」不是「峰值 0」")
    void emptySeriesHasNoPeak() {
        liveData.setLiveStartTime(PLATFORM, UID, START);

        listener.onLiveOffEvent(liveOff(START + MINUTE));

        assertFalse(sessions.find(0, Long.MAX_VALUE).get(0).hasPeaks());
    }

    @Test
    @DisplayName("① 明细整份落盘：曲线、排行、词频、标题、缺口一样不少")
    void detailIsArchived() {
        liveData.setLiveStartTime(PLATFORM, UID, START);
        for (int minute = 0; minute < 30; minute++) {
            liveData.incrementLiveSeries(PLATFORM, UID, "danmu_count", START + minute * MINUTE, minute % 7);
        }
        for (int i = 0; i < 25; i++) {
            liveData.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 19_000_000_000_000L + i, 25 - i);
            liveData.recordLiveUserName(PLATFORM, UID, 19_000_000_000_000L + i, "观众" + i);
        }
        liveData.incrementLiveWordFrequency(PLATFORM, UID, "好听");
        liveData.incrementLiveWordFrequency(PLATFORM, UID, "好听");
        liveData.incrementLiveWordFrequency(PLATFORM, UID, "点歌");
        liveData.recordDowntime(START + 5 * MINUTE, START + 7 * MINUTE, LiveGap.Reason.RESTART);
        liveData.incrementLiveMetric(PLATFORM, UID, "danmu_count", 106);

        listener.onLiveOffEvent(liveOff(START + 30 * MINUTE));

        LiveDetail detail = details.read(PLATFORM, UID, START).orElseThrow();
        assertAll(
                () -> assertEquals(LiveDetail.VERSION, detail.version()),
                () -> assertEquals(START, detail.startTime(), "开播时刻是明细与场次之间唯一的钉子"),
                () -> assertEquals(30, detail.series("danmu_count").size(), "曲线整条留"),
                () -> assertEquals(25, detail.ranking("danmu_users").size(), "排行留全量而不是前几名"),
                () -> assertEquals(2, detail.words().size()),
                () -> assertEquals(2, detail.words().get("好听")),
                () -> assertEquals(1, detail.gaps().size()),
                () -> assertEquals(LiveGap.Reason.RESTART, detail.gaps().get(0).reason()),
                () -> assertEquals(106.0, detail.metrics().get("danmu_count")));
    }

    @Test
    @DisplayName("① 排行榜按分数降序留下，长尾那一端也在")
    void rankingIsFullAndOrdered() {
        liveData.setLiveStartTime(PLATFORM, UID, START);
        for (int i = 0; i < 40; i++) {
            liveData.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 19_000_000_000_000L + i, 40 - i);
        }

        listener.onLiveOffEvent(liveOff(START + MINUTE));

        List<UserScore> ranking = details.read(PLATFORM, UID, START).orElseThrow().ranking("danmu_users");
        assertAll(
                () -> assertEquals(40, ranking.size()),
                () -> assertEquals(40.0, ranking.get(0).score()),
                () -> assertEquals(1.0, ranking.get(39).score()));
    }

    @Test
    @DisplayName("高能时刻按弹幕原文的分钟密度算，付费留言不算在内")
    void highlightsComeFromRawDanmu() {
        liveData.setLiveStartTime(PLATFORM, UID, START);
        // 前 30 分钟每分钟 2 条，第 12 分钟塞 40 条——那一分钟才是高能
        for (int minute = 0; minute < 30; minute++) {
            int count = minute == 12 ? 40 : 2;
            for (int i = 0; i < count; i++) {
                details.appendDanmu(PLATFORM, UID, START, new DanmuRecord(
                        START + minute * MINUTE + i, 19338207415562L, "观众甲", "话", DanmuRecord.Type.DANMU));
            }
        }
        // 付费留言堆在第 20 分钟：它若被算进密度，这一分钟会挤掉真正的高能
        for (int i = 0; i < 60; i++) {
            details.appendDanmu(PLATFORM, UID, START, new DanmuRecord(
                    START + 20 * MINUTE + i, 19338207415562L, "观众甲", "谢谢", DanmuRecord.Type.SUPER_CHAT));
        }

        listener.onLiveOffEvent(liveOff(START + 30 * MINUTE));

        LiveDetail detail = details.read(PLATFORM, UID, START).orElseThrow();
        assertAll(
                () -> assertEquals(1, detail.highlights().size()),
                () -> assertEquals(bucket(START + 12 * MINUTE), detail.highlights().get(0).at()),
                () -> assertEquals(40.0, detail.highlights().get(0).value()));
    }

    @Test
    @DisplayName("没有开播时刻就既不归档也不留明细——一条没有起点的记录只会污染统计")
    void noStartTimeNoArchive() {
        listener.onLiveOffEvent(liveOff(START + MINUTE));

        assertAll(
                () -> assertTrue(sessions.find(0, Long.MAX_VALUE).isEmpty()),
                () -> assertTrue(details.list().isEmpty()));
    }

    @Test
    @DisplayName("场次里的峰值与明细里的那一份同源，不是各算各的")
    void sessionAndDetailSharePeaks() {
        liveData.setLiveStartTime(PLATFORM, UID, START);
        for (int minute = 0; minute < 10; minute++) {
            liveData.incrementLiveSeries(PLATFORM, UID, "danmu_count", START + minute * MINUTE, minute);
        }

        listener.onLiveOffEvent(liveOff(START + 10 * MINUTE));

        assertEquals(sessions.find(0, Long.MAX_VALUE).get(0).peaks(),
                details.read(PLATFORM, UID, START).orElseThrow().peaks(),
                "两处各算一遍的话, 场次表上的人气峰与点开报告看到的会是两个数");
    }

    /**
     * 主播推流断了一下又续上：平台先发下播，reconnectInterval 内再发开播。
     * 记录上这一场要和没断过一样——留档整场、场次一条、累计只算一次。
     * <p>
     * 数据服务、场次归档、明细留档、开播与下播两个监听器都是真的；
     * 累计存储换成一个只把每次并入的弹幕数加起来的假货，量的是「并进去了多少」。
     */
    @Nested
    @DisplayName("断线重连续场")
    class ResumedSession {
        /** ⚠️ 保留段假值 */
        private static final long FRONT_VIEWER = 19338207415563L;

        private static final long BACK_VIEWER = 19338207415564L;

        private static final long ZERO_VIEWER = 19338207415565L;

        private final long offAt = START + 30 * MINUTE;

        private final long resumeAt = offAt + MINUTE;

        private final long endAt = START + 90 * MINUTE;

        private double mergedDanmu;

        /**
         * 每次并入里各观众的弹幕计分，按观众加总
         */
        private final Map<Long, Double> mergedUsers = new java.util.HashMap<>();

        private NovaDefaultLiveOnEventListener onListener;

        @BeforeEach
        void wire() {
            RedisTotalDataStore store = mock(RedisTotalDataStore.class);
            doAnswer(invocation -> {
                RedisTotalDataStore.LiveSnapshot snapshot = invocation.getArgument(2);
                mergedDanmu += snapshot.metrics().getOrDefault("danmu_count", 0.0);
                snapshot.userMetrics().getOrDefault("danmu_users", Map.of())
                        .forEach((user, score) -> mergedUsers.merge(user, score, Double::sum));
                return true;
            }).when(store).merge(any(), any(), any());
            TotalDataStorage total = mock(TotalDataStorage.class);
            when(total.active()).thenReturn(store);

            CompositeLiveDataService composite = new CompositeLiveDataService(liveData, total);
            LiveRoomInfoHistory history = new LiveRoomInfoHistory(new NovaStateStore(properties));
            LiveSessionDetailArchiver detailArchiver = new LiveSessionDetailArchiver(composite, history, details);
            listener = new NovaDefaultLiveOffEventListener(composite, sessions,
                    new LiveInterventionTracker(), history, detailArchiver);
            onListener = new NovaDefaultLiveOnEventListener(properties, composite,
                    new LiveSessionRecovery(composite, sessions, history, detailArchiver));
        }

        @Test
        @DisplayName("续场后的弹幕原文与事件流水照收进这一场（故障：断一下之后整个后半场的弹幕与礼物流水全丢）")
        void resumedHalfIsKeptInTheDetail() {
            playResumedSession();
            // 真下播之后这一场重新封口：再来的弹幕与流水不收
            danmu(START, endAt + 10_000, "真下播后");
            details.appendEvent(PLATFORM, UID, START, endAt + 10_000, "gift", Map.of("gift", "真下播后礼物"));

            List<String> texts = details.readDanmu(PLATFORM, UID, START).stream().map(DanmuRecord::text).toList();
            List<Object> gifts = details.readEvents(PLATFORM, UID, START).stream().map(e -> e.get("gift")).toList();
            assertAll(
                    () -> assertEquals(List.of("前半场", "后半场一", "后半场二"), texts),
                    () -> assertEquals(List.of("前半场礼物", "后半场礼物"), gifts));
        }

        @Test
        @DisplayName("场次归档只一条，时长按整场算（故障：运营统计把这一场算两遍、时长相加）")
        void resumedSessionIsArchivedOnce() {
            playResumedSession();

            List<LiveSession> archived = sessions.find(0, Long.MAX_VALUE);
            assertAll(
                    () -> assertEquals(1, archived.size(), "同一开播时刻只该有一条，实际: " + archived.size()),
                    () -> assertEquals(endAt, archived.get(archived.size() - 1).endTime()),
                    () -> assertEquals(90 * 60, archived.get(archived.size() - 1).durationSeconds()),
                    () -> assertEquals(3.0, archived.get(archived.size() - 1).metric("danmu_count")));
        }

        @Test
        @DisplayName("累计弹幕数等于整场条数（故障：断过一次的那场，前半场在累计里算了两遍）")
        void resumedSessionIsMergedIntoTotalOnce() {
            playResumedSession();

            assertAll(
                    () -> assertEquals(3.0, mergedDanmu, "整场 3 条；前半场重复并入时是 4"),
                    () -> assertEquals(Map.of(FRONT_VIEWER, 1.0, BACK_VIEWER, 2.0, ZERO_VIEWER, 0.0), mergedUsers,
                            "各观众按整场计分各并一次；前半场重复并入时前排观众是 2"),
                    () -> assertEquals(3, mergedUsers.size(), "得 0 分的那位也要并进去，实际: " + mergedUsers));
        }

        @Test
        @DisplayName("下播超过 reconnectInterval 才开播仍算新的一场，前一场留档不再收（故障：隔天开播的弹幕混进昨天那场）")
        void lateLiveOnStartsANewSession() {
            liveOn(START);
            danmu(START, START + MINUTE, "第一场");
            listener.onLiveOffEvent(liveOff(offAt));

            long nextStart = offAt + (properties.getLive().getReconnectInterval() + 1) * 1000L;
            LiveOnEvent next = liveOn(nextStart);
            danmu(START, nextStart + MINUTE, "迟到的写入");
            danmu(nextStart, nextStart + MINUTE, "第二场");
            listener.onLiveOffEvent(liveOff(nextStart + 30 * MINUTE));

            assertAll(
                    () -> assertFalse(next.isReconnect(), "前提：这一次不该被判为断线重连"),
                    () -> assertEquals(List.of("第一场"),
                            details.readDanmu(PLATFORM, UID, START).stream().map(DanmuRecord::text).toList()),
                    () -> assertEquals(List.of("第二场"),
                            details.readDanmu(PLATFORM, UID, nextStart).stream().map(DanmuRecord::text).toList()),
                    () -> assertEquals(2, sessions.find(0, Long.MAX_VALUE).size()));
        }

        @Test
        @DisplayName("下播后、未再开播时来的弹幕不收（故障：留档条数比场次弹幕数多出一截，看不出多在哪）")
        void danmuAfterLiveOffIsNotKept() {
            liveOn(START);
            danmu(START, START + MINUTE, "下播前");
            listener.onLiveOffEvent(liveOff(offAt));
            danmu(START, offAt + 10_000, "下播后");
            details.appendEvent(PLATFORM, UID, START, offAt + 10_000, "gift", Map.of("gift", "下播后礼物"));

            assertAll(
                    () -> assertEquals(List.of("下播前"),
                            details.readDanmu(PLATFORM, UID, START).stream().map(DanmuRecord::text).toList()),
                    () -> assertTrue(details.readEvents(PLATFORM, UID, START).isEmpty()));
        }

        /**
         * 开播 → 前半场一条弹幕一份礼物 → 下播 → 一分钟后开播 → 后半场两条弹幕一份礼物 → 真下播
         */
        private void playResumedSession() {
            liveOn(START);
            danmu(START, START + MINUTE, "前半场");
            liveData.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", FRONT_VIEWER, 1);
            details.appendEvent(PLATFORM, UID, START, START + MINUTE, "gift", Map.of("gift", "前半场礼物"));
            listener.onLiveOffEvent(liveOff(offAt));

            LiveOnEvent resumed = liveOn(resumeAt);
            assertTrue(resumed.isReconnect(), "前提：一分钟后再开播必须被判为断线重连");
            danmu(START, resumeAt + MINUTE, "后半场一");
            danmu(START, resumeAt + 2 * MINUTE, "后半场二");
            liveData.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", BACK_VIEWER, 2);
            // 得 0 分也算参与过：累计参与人数数的是有几个人
            liveData.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", ZERO_VIEWER, 0);
            details.appendEvent(PLATFORM, UID, START, resumeAt + MINUTE, "gift", Map.of("gift", "后半场礼物"));
            listener.onLiveOffEvent(liveOff(endAt));
        }

        private LiveOnEvent liveOn(long at) {
            LiveOnEvent event = new LiveOnEvent(PLATFORM, new LiveStreamerInfo(UID, "主播甲", ROOM_ID),
                    Instant.ofEpochMilli(at));
            onListener.onLiveOnEventCheckReconnect(event);
            onListener.onLiveOnEventSetLiveData(event);
            return event;
        }

        /**
         * 一条弹幕：原文写进开播时刻为 startTime 的那一场，本场弹幕数加一
         */
        private void danmu(long startTime, long at, String text) {
            details.appendDanmu(PLATFORM, UID, startTime,
                    new DanmuRecord(at, 19338207415562L, "观众甲", text, DanmuRecord.Type.DANMU));
            liveData.incrementLiveMetric(PLATFORM, UID, "danmu_count", 1);
        }
    }

    /**
     * 某个时刻落在哪一格
     * <p>
     * 现算而不是写死：开播时刻不必是整分钟，格边界与开播时刻可以差到 59 秒——
     * 写死一个「开播后 42 分整」的期望值，量的就成了「开播时刻恰好是整分钟」。
     */
    private long bucket(long at) {
        return at / MINUTE * MINUTE;
    }

    private LiveOffEvent liveOff(long at) {
        return new LiveOffEvent(PLATFORM,
                new LiveStreamerInfo(UID, "主播甲", ROOM_ID), Instant.ofEpochMilli(at));
    }
}
