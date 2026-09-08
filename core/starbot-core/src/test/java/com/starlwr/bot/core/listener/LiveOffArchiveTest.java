package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.analytics.LiveDetail;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.model.DanmuRecord;
import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.SeriesPeak;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveDetailArchive;
import com.starlwr.bot.core.service.LiveInterventionTracker;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.service.LiveSessionArchive;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private StarBotCoreProperties properties;

    private DefaultLiveDataService liveData;

    private LiveSessionArchive sessions;

    private LiveDetailArchive details;

    private StarBotDefaultLiveOffEventListener listener;

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        properties.getLive().setSaveLiveData(false);

        liveData = new DefaultLiveDataService(properties);
        sessions = new LiveSessionArchive(properties);
        details = new LiveDetailArchive(properties);
        listener = new StarBotDefaultLiveOffEventListener(liveData, sessions,
                new LiveInterventionTracker(), new LiveRoomInfoHistory(new StarBotStateStore(properties)), details);
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
